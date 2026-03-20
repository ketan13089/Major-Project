package com.ketan.slam

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.sqrt

/**
 * Always-on hazard warning system that analyzes depth hits AND known semantic
 * objects for obstacles in the user's walking path and provides haptic + TTS alerts.
 *
 * Forward cone filter: only considers hits within ±45° of the forward direction
 * (dot product > 0.707 with the forward vector).
 *
 * Two alert levels:
 * - DANGER (<0.8m): 2x vibration pulse + "Obstacle ahead, stop" (2s cooldown)
 * - WARNING (<1.5m): 1x vibration pulse + "Obstacle ahead" (5s cooldown)
 *
 * During active navigation, WARNING TTS is suppressed to avoid interference
 * with turn-by-turn guidance. Only DANGER TTS is spoken.
 */
class HazardWarningSystem(
    private val announcer: NavigationGuide,
    private val vibrator: Vibrator?
) {
    companion object {
        private const val TAG = "HazardWarn"
        private const val DANGER_DIST = 0.8f       // metres
        private const val WARNING_DIST = 1.5f       // metres
        private const val FORWARD_CONE_DOT = 0.707f // cos(45°)

        private const val DANGER_TTS_COOLDOWN_MS = 2000L
        private const val WARNING_TTS_COOLDOWN_MS = 5000L
        private const val HAPTIC_COOLDOWN_MS = 1000L

        /** Object types that are walkable path obstacles (not doors/windows/signs). */
        private val OBSTACLE_TYPES = setOf(
            ObjectType.CHAIR,
            ObjectType.TRASH_CAN,
            ObjectType.FIRE_EXTINGUISHER,
            ObjectType.WATER_PURIFIER
        )

        // Floor drop-off detection thresholds
        private const val DROP_OFF_THRESHOLD = 0.25f   // metres — Y drop that indicates stairs/edge
        private const val STAIR_RISE_THRESHOLD = 0.15f // metres — Y rise that indicates stairs going up
        private const val FLOOR_CHECK_RANGE = 3.0f     // metres — max distance to check floor ahead
        private const val DROP_OFF_TTS_COOLDOWN_MS = 3000L
    }

    data class DepthHit(
        val worldX: Float,
        val worldZ: Float,
        val distance: Float
    )

    data class FloorHit(
        val worldX: Float,
        val worldY: Float,  // height — used for drop-off detection
        val worldZ: Float
    )

    private var lastDangerTtsMs = 0L
    private var lastWarningTtsMs = 0L
    private var lastHapticMs = 0L
    private var lastDropOffTtsMs = 0L
    private var baselineFloorY = Float.NaN  // running average of floor Y near user

    /**
     * Process depth hits from the current frame.
     *
     * @param hits           list of depth hit points in world coordinates
     * @param userX          user's world X position
     * @param userZ          user's world Z position
     * @param forwardX       user's forward direction X component (camera look direction)
     * @param forwardZ       user's forward direction Z component (camera look direction)
     * @param isNavigating   true if navigation session is active (suppresses WARNING TTS)
     */
    fun processDepthHits(
        hits: List<DepthHit>,
        userX: Float,
        userZ: Float,
        forwardX: Float,
        forwardZ: Float,
        isNavigating: Boolean
    ) {
        val fwdLen = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (fwdLen < 0.01f) return
        val fnx = forwardX / fwdLen
        val fnz = forwardZ / fwdLen

        var closestInCone = Float.MAX_VALUE

        for (hit in hits) {
            val dx = hit.worldX - userX
            val dz = hit.worldZ - userZ
            val dist = sqrt(dx * dx + dz * dz)
            if (dist < 0.05f || dist > WARNING_DIST) continue

            // Forward cone filter: dot product with forward direction
            val dot = (dx * fnx + dz * fnz) / dist
            if (dot < FORWARD_CONE_DOT) continue

            if (dist < closestInCone) closestInCone = dist
        }

        if (closestInCone < Float.MAX_VALUE) {
            Log.d(TAG, "Depth: closest in cone = ${"%.2f".format(closestInCone)}m (${hits.size} hits)")
        }

        triggerAlert(closestInCone, isNavigating, "depth")
    }

    /**
     * Check known semantic objects (YOLO-detected) against the user's walking path.
     * Called every SLAM frame from updateSlam().
     *
     * @param objects        all currently tracked semantic objects
     * @param userX          user's world X position
     * @param userZ          user's world Z position
     * @param forwardX       user's forward direction X component (camera look direction)
     * @param forwardZ       user's forward direction Z component (camera look direction)
     * @param isNavigating   true if navigation session is active
     */
    fun checkSemanticObjects(
        objects: List<SemanticObject>,
        userX: Float,
        userZ: Float,
        forwardX: Float,
        forwardZ: Float,
        isNavigating: Boolean
    ) {
        val fwdLen = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (fwdLen < 0.01f) return
        val fnx = forwardX / fwdLen
        val fnz = forwardZ / fwdLen

        var closestInCone = Float.MAX_VALUE
        var closestLabel: String? = null

        for (obj in objects) {
            if (obj.type !in OBSTACLE_TYPES) continue

            val dx = obj.position.x - userX
            val dz = obj.position.z - userZ
            val dist = sqrt(dx * dx + dz * dz)
            if (dist < 0.05f || dist > WARNING_DIST) continue

            val dot = (dx * fnx + dz * fnz) / dist
            if (dot < FORWARD_CONE_DOT) continue

            if (dist < closestInCone) {
                closestInCone = dist
                closestLabel = obj.category
            }
        }

        if (closestInCone < Float.MAX_VALUE) {
            Log.d(TAG, "YOLO: $closestLabel at ${"%.2f".format(closestInCone)}m in cone")
            triggerAlert(closestInCone, isNavigating, closestLabel ?: "object")
        }
    }

    /**
     * Detect staircase or drop-off hazards by analyzing floor-level depth hits.
     * Compares floor Y near the user (baseline) with floor Y further ahead in
     * the forward cone. A sudden drop indicates stairs-down or a ledge;
     * a sudden rise indicates stairs-up.
     *
     * @param floorHits    floor-level depth hits with world Y coordinates
     * @param userX        user's world X position
     * @param cameraY      camera Y (world) — used to derive approximate floor baseline
     * @param userZ        user's world Z position
     * @param forwardX     camera look direction X
     * @param forwardZ     camera look direction Z
     * @param isNavigating true if navigation session is active
     */
    fun checkFloorDropOff(
        floorHits: List<FloorHit>,
        userX: Float,
        cameraY: Float,
        userZ: Float,
        forwardX: Float,
        forwardZ: Float,
        isNavigating: Boolean
    ) {
        if (floorHits.size < 3) return

        val fwdLen = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (fwdLen < 0.01f) return
        val fnx = forwardX / fwdLen
        val fnz = forwardZ / fwdLen

        // Split floor hits into "near" (within 1m) and "ahead" (1-3m in forward cone)
        var nearSum = 0f; var nearCount = 0
        var aheadMinY = Float.MAX_VALUE; var aheadMaxY = -Float.MAX_VALUE
        var aheadDist = 0f

        for (hit in floorHits) {
            val dx = hit.worldX - userX
            val dz = hit.worldZ - userZ
            val dist = sqrt(dx * dx + dz * dz)

            if (dist < 1.0f) {
                // Near floor — build baseline
                nearSum += hit.worldY
                nearCount++
            } else if (dist <= FLOOR_CHECK_RANGE) {
                // Check if in forward cone
                val dot = (dx * fnx + dz * fnz) / dist
                if (dot < FORWARD_CONE_DOT) continue
                if (hit.worldY < aheadMinY) { aheadMinY = hit.worldY; aheadDist = dist }
                if (hit.worldY > aheadMaxY) aheadMaxY = hit.worldY
            }
        }

        // Update baseline: use near-floor average if available, else derive from camera height
        val currentBaseline = if (nearCount >= 2) {
            val avg = nearSum / nearCount
            baselineFloorY = if (baselineFloorY.isNaN()) avg
                             else baselineFloorY * 0.8f + avg * 0.2f  // smooth
            baselineFloorY
        } else if (baselineFloorY.isNaN()) {
            // First-time estimate: assume floor ~1.5m below camera
            baselineFloorY = cameraY - 1.5f
            baselineFloorY
        } else {
            baselineFloorY
        }

        if (aheadMinY == Float.MAX_VALUE) return  // no forward floor hits

        val now = System.currentTimeMillis()
        if (now - lastDropOffTtsMs < DROP_OFF_TTS_COOLDOWN_MS) return

        val drop = currentBaseline - aheadMinY   // positive = floor drops away
        val rise = aheadMaxY - currentBaseline    // positive = floor rises

        when {
            drop > DROP_OFF_THRESHOLD -> {
                Log.d(TAG, "DROP-OFF: baseline=${"%.2f".format(currentBaseline)} " +
                        "ahead=${"%.2f".format(aheadMinY)} drop=${"%.2f".format(drop)}m " +
                        "at ${"%.1f".format(aheadDist)}m")
                lastDropOffTtsMs = now
                lastHapticMs = now
                vibratePattern(longArrayOf(0, 300, 100, 300))  // strong 2x pulse
                val msg = if (drop > 0.6f) "Stairs going down ahead." else "Drop-off ahead, be careful."
                announcer.speak(msg)
            }
            rise > STAIR_RISE_THRESHOLD -> {
                Log.d(TAG, "STAIRS-UP: baseline=${"%.2f".format(currentBaseline)} " +
                        "ahead=${"%.2f".format(aheadMaxY)} rise=${"%.2f".format(rise)}m")
                lastDropOffTtsMs = now
                lastHapticMs = now
                vibratePattern(longArrayOf(0, 200, 100, 200))
                announcer.speak("Stairs going up ahead.")
            }
        }
    }

    /**
     * Unified alert trigger used by both depth hits and semantic object checks.
     */
    private fun triggerAlert(distance: Float, isNavigating: Boolean, source: String) {
        if (distance == Float.MAX_VALUE) return

        val now = System.currentTimeMillis()

        when {
            distance < DANGER_DIST -> {
                Log.d(TAG, "DANGER ($source) at ${"%.2f".format(distance)}m")
                if (now - lastHapticMs >= HAPTIC_COOLDOWN_MS) {
                    lastHapticMs = now
                    vibratePattern(longArrayOf(0, 200, 100, 200))  // 2x pulse
                }
                if (now - lastDangerTtsMs >= DANGER_TTS_COOLDOWN_MS) {
                    lastDangerTtsMs = now
                    announcer.speak("Obstacle ahead, stop.")
                }
            }
            distance < WARNING_DIST -> {
                Log.d(TAG, "WARNING ($source) at ${"%.2f".format(distance)}m")
                if (now - lastHapticMs >= HAPTIC_COOLDOWN_MS) {
                    lastHapticMs = now
                    vibratePattern(longArrayOf(0, 100))  // 1x pulse
                }
                if (!isNavigating && now - lastWarningTtsMs >= WARNING_TTS_COOLDOWN_MS) {
                    lastWarningTtsMs = now
                    announcer.speak("Obstacle ahead.")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibratePattern(pattern: LongArray) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            v.vibrate(pattern, -1)
        }
    }
}
