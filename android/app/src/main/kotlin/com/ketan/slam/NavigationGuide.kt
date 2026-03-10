package com.ketan.slam

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TurnDirection {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN
}

data class NavigationInstruction(
    val text: String,
    val turn: TurnDirection,
    val distanceMetres: Float
)

/**
 * Generates turn-by-turn [NavigationInstruction] relative to the user's
 * current heading and speaks them via Android TextToSpeech.
 *
 * Heading convention follows ARCore: +X = right, −Z = forward.
 */
class NavigationGuide(context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayDeque<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            }
        }
    }

    /** Speak [text] immediately, flushing any queued utterance. */
    fun speak(text: String) {
        if (!ttsReady) { pending.addLast(text); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }

    /**
     * Compute the next instruction given the user's current state.
     *
     * @param userX      world X in metres
     * @param userZ      world Z in metres
     * @param headingRad ARCore yaw angle in radians
     * @param waypoints  remaining path waypoints (already trimmed of passed points)
     * @param res        metres per grid cell
     */
    fun computeInstruction(
        userX: Float, userZ: Float,
        headingRad: Float,
        waypoints: List<NavWaypoint>,
        res: Float
    ): NavigationInstruction? {
        if (waypoints.isEmpty()) return null

        val target = findLookahead(userX, userZ, waypoints, res) ?: waypoints.last()
        val dx = target.worldX(res) - userX
        val dz = target.worldZ(res) - userZ

        // atan2(dx, -dz): ARCore +X = right, -Z = forward → standard bearing
        val targetAngle = atan2(dx, -dz).toFloat()
        var diff = targetAngle - headingRad
        while (diff >  Math.PI)  diff -= (2 * Math.PI).toFloat()
        while (diff < -Math.PI)  diff += (2 * Math.PI).toFloat()

        val distToGoal = dist(userX, userZ, waypoints.last().worldX(res), waypoints.last().worldZ(res))
        val turn       = angleToTurn(diff)
        return NavigationInstruction(buildText(turn, distToGoal), turn, distToGoal)
    }

    /** Returns true when the user is within [ARRIVAL_M] of the goal. */
    fun isArrived(userX: Float, userZ: Float, goalX: Float, goalZ: Float): Boolean =
        dist(userX, userZ, goalX, goalZ) <= ARRIVAL_M

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Walk the remaining path until cumulative distance exceeds [LOOKAHEAD_M],
     * returning that waypoint. Falls back to the last waypoint.
     */
    private fun findLookahead(
        userX: Float, userZ: Float,
        waypoints: List<NavWaypoint>,
        res: Float
    ): NavWaypoint? {
        var cumDist = 0f
        var px = userX; var pz = userZ
        for (wp in waypoints) {
            cumDist += dist(px, pz, wp.worldX(res), wp.worldZ(res))
            if (cumDist >= LOOKAHEAD_M) return wp
            px = wp.worldX(res); pz = wp.worldZ(res)
        }
        return waypoints.lastOrNull()
    }

    private fun angleToTurn(diff: Float): TurnDirection {
        val deg = Math.toDegrees(diff.toDouble()).toFloat()
        return when {
            deg < -150f || deg > 150f -> TurnDirection.U_TURN
            deg < -90f               -> TurnDirection.SHARP_LEFT
            deg < -40f               -> TurnDirection.LEFT
            deg < -15f               -> TurnDirection.SLIGHT_LEFT
            deg >  90f               -> TurnDirection.SHARP_RIGHT
            deg >  40f               -> TurnDirection.RIGHT
            deg >  15f               -> TurnDirection.SLIGHT_RIGHT
            else                     -> TurnDirection.STRAIGHT
        }
    }

    private fun buildText(turn: TurnDirection, distGoal: Float): String {
        val m       = distGoal.roundToInt().coerceAtLeast(1)
        val distStr = if (m == 1) "1 metre" else "$m metres"
        return when (turn) {
            TurnDirection.STRAIGHT     -> "Go straight for $distStr"
            TurnDirection.SLIGHT_LEFT  -> "Bear left, then $distStr to destination"
            TurnDirection.LEFT         -> "Turn left. $distStr to destination"
            TurnDirection.SHARP_LEFT   -> "Sharp left. $distStr to destination"
            TurnDirection.SLIGHT_RIGHT -> "Bear right, then $distStr to destination"
            TurnDirection.RIGHT        -> "Turn right. $distStr to destination"
            TurnDirection.SHARP_RIGHT  -> "Sharp right. $distStr to destination"
            TurnDirection.U_TURN       -> "Turn around. Destination is behind you"
        }
    }

    private fun dist(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        val dx = x1 - x0; val dz = z1 - z0
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        private const val LOOKAHEAD_M = 1.5f  // metres ahead to compute turn direction
        private const val ARRIVAL_M   = 1.0f  // metres from goal = arrived
    }
}
