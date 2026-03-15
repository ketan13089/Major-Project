package com.ketan.slam

import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class NavigationState { IDLE, LISTENING, PLANNING, NAVIGATING, ARRIVED, ERROR }

data class NavigationSession(
    val intent: NavigationIntent,
    val destination: SemanticObject,
    val path: List<NavWaypoint>,
    val lastInstruction: NavigationInstruction? = null
)

/**
 * Orchestrates the full voice-navigation pipeline:
 *
 *   Voice input → [VoiceCommandProcessor]
 *       ↓ NavigationIntent
 *   Destination selection (from SemanticMapManager)
 *       ↓ SemanticObject
 *   Path planning → [PathPlanner] (A* on occupancy grid)
 *       ↓ List<NavWaypoint>
 *   Guidance loop → [NavigationGuide] (TTS + instruction generation)
 *
 * [tick] is called every SLAM pose update (GL thread). It:
 *   1. Resolves any pending voice intent once the map is available.
 *   2. Checks for arrival and path deviation.
 *   3. Emits a new [NavigationInstruction] at most every [INSTRUCTION_INTERVAL_MS].
 *
 * All callbacks are thread-safe; callers must dispatch UI work to the main thread.
 */
class NavigationManager(
    private val context: android.content.Context,
    private val res: Float,
    /** Called whenever the navigation state changes (any thread → wrap in runOnUiThread). */
    private val onStateChange: (NavigationState, String) -> Unit,
    /** Called when a new turn-by-turn instruction is ready. */
    private val onInstruction: (NavigationInstruction) -> Unit,
    /** Called when the active path changes (empty = no active navigation). */
    private val onPathUpdated: (List<NavWaypoint>) -> Unit
) {
    private val voice   = VoiceCommandProcessor(
        context     = context,
        onIntent    = ::queueIntent,
        onError     = { msg -> setState(NavigationState.ERROR, msg) },
        onListening = { setState(NavigationState.LISTENING, "Listening for destination…") }
    )
    private val planner = PathPlanner(res)
    private val guide   = NavigationGuide(context)

    @Volatile var currentSession: NavigationSession? = null; private set
    @Volatile var state: NavigationState = NavigationState.IDLE; private set

    /** Pending intent queued by voice processor, resolved on next tick() with map access. */
    @Volatile private var pendingIntent: NavigationIntent? = null
    private var lastInstructionMs = 0L

    /** True when this manager needs the occupancy grid snapshot in tick(). */
    val needsGrid: Boolean get() = state != NavigationState.IDLE || pendingIntent != null

    // ── Public API ────────────────────────────────────────────────────────────

    fun startVoiceCommand() = voice.startListening()

    fun stopNavigation() {
        pendingIntent  = null
        currentSession = null
        setState(NavigationState.IDLE, "Navigation stopped")
        onPathUpdated(emptyList())
        guide.speak("Navigation stopped.")
    }

    /**
     * Must be called from the GL/SLAM thread on every pose update.
     * Passes a snapshot of the current occupancy grid and the semantic map.
     */
    fun tick(
        userX: Float, userZ: Float,
        headingRad: Float,
        grid: Map<GridCell, Byte>,
        semanticMap: SemanticMapManager
    ) {
        // Resolve pending voice intent now that grid + map are available
        pendingIntent?.also { intent ->
            pendingIntent = null
            resolveIntent(intent, userX, userZ, grid, semanticMap)
        }

        val session = currentSession ?: return
        if (state != NavigationState.NAVIGATING) return

        val goalX = session.destination.position.x
        val goalZ = session.destination.position.z

        // ── Arrival check ─────────────────────────────────────────────────────
        if (guide.isArrived(userX, userZ, goalX, goalZ)) {
            val label = session.intent.destinationType.name
                .lowercase().replace('_', ' ')
            currentSession = null
            setState(NavigationState.ARRIVED, "Arrived at $label")
            onPathUpdated(emptyList())
            guide.speak("You have arrived at your destination.")
            return
        }

        // ── Deviation → re-plan ───────────────────────────────────────────────
        if (minDistToPath(userX, userZ, session.path) > DEVIATION_M) {
            rePlan(userX, userZ, session, grid, semanticMap)
            return
        }

        // ── Turn-by-turn guidance ─────────────────────────────────────────────
        val now = System.currentTimeMillis()
        if (now - lastInstructionMs < INSTRUCTION_INTERVAL_MS) return
        lastInstructionMs = now

        val remaining = trimPassed(userX, userZ, session.path)
        val instr     = guide.computeInstruction(userX, userZ, headingRad, remaining, res) ?: return

        if (instr.text != session.lastInstruction?.text) {
            guide.speak(instr.text)
            onInstruction(instr)
            currentSession = session.copy(lastInstruction = instr)
        }
    }

    fun destroy() { voice.destroy(); guide.shutdown() }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Stores the intent and lets tick() resolve it on the next frame. */
    private fun queueIntent(intent: NavigationIntent) {
        val label = intent.destinationType.name.lowercase().replace('_', ' ')
        setState(NavigationState.PLANNING, "Finding $label…")
        pendingIntent = intent
    }

    private fun resolveIntent(
        intent: NavigationIntent,
        userX: Float, userZ: Float,
        grid: Map<GridCell, Byte>,
        semanticMap: SemanticMapManager
    ) {
        val label = intent.destinationType.name.lowercase().replace('_', ' ')

        // ── Destination selection ─────────────────────────────────────────────
        val dest = selectDestination(intent, userX, userZ, semanticMap)
        if (dest == null) {
            setState(NavigationState.ERROR, "No $label found — keep scanning")
            guide.speak("No $label detected on the map yet. Keep scanning the area.")
            return
        }

        // ── Path planning ─────────────────────────────────────────────────────
        val startGX = (userX / res).roundToInt()
        val startGZ = (userZ / res).roundToInt()
        val goalGX  = (dest.position.x / res).roundToInt()
        val goalGZ  = (dest.position.z / res).roundToInt()

        val path = planner.planPath(grid, startGX, startGZ, goalGX, goalGZ,
            semanticObjects = semanticMap.getAllObjects())
        if (path.isEmpty()) {
            setState(NavigationState.ERROR, "No clear path — route may be blocked")
            guide.speak("Cannot find a clear path to the $label. Try scanning more of the area.")
            return
        }

        currentSession = NavigationSession(intent, dest, path)
        setState(NavigationState.NAVIGATING, "Navigating to $label")
        onPathUpdated(path)

        val distM = dist(userX, userZ, dest.position.x, dest.position.z)
        val distStr = distM.roundToInt().coerceAtLeast(1).let { if (it == 1) "1 metre" else "$it metres" }
        guide.speak("Navigating to the ${qualifierWord(intent)} $label. Distance: $distStr.")

        // Speak first instruction immediately
        val firstInstr = guide.computeInstruction(userX, userZ, 0f, path, res)
        if (firstInstr != null) {
            guide.speak(firstInstr.text)
            onInstruction(firstInstr)
            currentSession = currentSession!!.copy(lastInstruction = firstInstr)
        }
    }

    /**
     * Selects the best destination from [SemanticMapManager] based on the
     * [NavigationIntent.qualifier]. Only objects with ≥ 2 observations are
     * considered to avoid acting on hallucinated single-frame detections.
     */
    private fun selectDestination(
        intent: NavigationIntent,
        userX: Float, userZ: Float,
        semanticMap: SemanticMapManager
    ): SemanticObject? {
        val candidates = semanticMap.getAllObjects()
            .filter { it.type == intent.destinationType && it.observations >= 2 }
        if (candidates.isEmpty()) return null
        return when (intent.qualifier) {
            DestinationQualifier.NEAREST    -> candidates.minByOrNull { dist(userX, userZ, it.position.x, it.position.z) }
            DestinationQualifier.FARTHEST   -> candidates.maxByOrNull { dist(userX, userZ, it.position.x, it.position.z) }
            DestinationQualifier.LEFT_MOST  -> candidates.minByOrNull { it.position.x }
            DestinationQualifier.RIGHT_MOST -> candidates.maxByOrNull { it.position.x }
        }
    }

    private fun rePlan(
        userX: Float, userZ: Float,
        session: NavigationSession,
        grid: Map<GridCell, Byte>,
        semanticMap: SemanticMapManager
    ) {
        val startGX = (userX / res).roundToInt()
        val startGZ = (userZ / res).roundToInt()
        val goalGX  = (session.destination.position.x / res).roundToInt()
        val goalGZ  = (session.destination.position.z / res).roundToInt()

        val newPath = planner.planPath(grid, startGX, startGZ, goalGX, goalGZ,
            semanticObjects = semanticMap.getAllObjects())
        if (newPath.isEmpty()) {
            currentSession = null
            setState(NavigationState.ERROR, "Re-routing failed — path blocked")
            onPathUpdated(emptyList())
            guide.speak("Re-routing failed. The path appears to be blocked.")
            return
        }
        guide.speak("Re-routing.")
        currentSession = session.copy(path = newPath, lastInstruction = null)
        onPathUpdated(newPath)
    }

    /**
     * Drop waypoints that the user has already passed (closer than [TRIM_M]).
     * Always keeps at least the goal waypoint.
     */
    private fun trimPassed(userX: Float, userZ: Float, path: List<NavWaypoint>): List<NavWaypoint> {
        val firstFar = path.indexOfFirst { dist(userX, userZ, it.worldX(res), it.worldZ(res)) > TRIM_M }
        return if (firstFar == -1) listOf(path.last()) else path.subList(firstFar, path.size)
    }

    private fun minDistToPath(userX: Float, userZ: Float, path: List<NavWaypoint>): Float =
        if (path.isEmpty()) Float.MAX_VALUE
        else path.minOf { dist(userX, userZ, it.worldX(res), it.worldZ(res)) }

    private fun qualifierWord(intent: NavigationIntent) = when (intent.qualifier) {
        DestinationQualifier.NEAREST    -> "nearest"
        DestinationQualifier.FARTHEST   -> "farthest"
        DestinationQualifier.LEFT_MOST  -> "left-most"
        DestinationQualifier.RIGHT_MOST -> "right-most"
    }

    private fun setState(s: NavigationState, msg: String) {
        state = s; onStateChange(s, msg)
    }

    private fun dist(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        val dx = x1 - x0; val dz = z1 - z0
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /** Re-plan when user strays more than this far from the planned path. */
        private const val DEVIATION_M           = 2.0f
        /** Min interval between spoken instruction updates. */
        private const val INSTRUCTION_INTERVAL_MS = 3_000L
        /** Waypoints within this distance of the user are considered "passed". */
        private const val TRIM_M                = 0.6f
    }
}
