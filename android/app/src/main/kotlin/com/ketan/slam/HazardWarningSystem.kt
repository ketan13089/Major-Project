package com.ketan.slam

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

/**
 * Always-on hazard warning system that analyzes depth hits for obstacles
 * in the user's walking path and provides haptic + TTS alerts.
 *
 * Forward cone filter: only considers hits within ±30° of the forward direction
 * (dot product > 0.866 with the forward vector).
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
        private const val DANGER_DIST = 0.8f       // metres
        private const val WARNING_DIST = 1.5f       // metres
        private const val FORWARD_CONE_DOT = 0.866f // cos(30°)

        private const val DANGER_TTS_COOLDOWN_MS = 2000L
        private const val WARNING_TTS_COOLDOWN_MS = 5000L
        private const val HAPTIC_COOLDOWN_MS = 1000L
    }

    data class DepthHit(
        val worldX: Float,
        val worldZ: Float,
        val distance: Float
    )

    private var lastDangerTtsMs = 0L
    private var lastWarningTtsMs = 0L
    private var lastHapticMs = 0L

    /**
     * Process depth hits from the current frame.
     *
     * @param hits           list of depth hit points in world coordinates
     * @param userX          user's world X position
     * @param userZ          user's world Z position
     * @param forwardX       user's forward direction X component
     * @param forwardZ       user's forward direction Z component
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
        if (hits.isEmpty()) return

        val fwdLen = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (fwdLen < 0.01f) return
        val fnx = forwardX / fwdLen
        val fnz = forwardZ / fwdLen

        var closestInCone = Float.MAX_VALUE

        for (hit in hits) {
            val dx = hit.worldX - userX
            val dz = hit.worldZ - userZ
            val dist = sqrt(dx * dx + dz * dz)
            if (dist < 0.1f || dist > WARNING_DIST) continue

            // Forward cone filter: dot product with forward direction
            val dot = (dx * fnx + dz * fnz) / dist
            if (dot < FORWARD_CONE_DOT) continue

            if (dist < closestInCone) closestInCone = dist
        }

        if (closestInCone == Float.MAX_VALUE) return

        val now = System.currentTimeMillis()

        when {
            closestInCone < DANGER_DIST -> {
                // DANGER level
                if (now - lastHapticMs >= HAPTIC_COOLDOWN_MS) {
                    lastHapticMs = now
                    vibratePattern(longArrayOf(0, 200, 100, 200))  // 2x pulse
                }
                if (now - lastDangerTtsMs >= DANGER_TTS_COOLDOWN_MS) {
                    lastDangerTtsMs = now
                    announcer.speak("Obstacle ahead, stop.")
                }
            }
            closestInCone < WARNING_DIST -> {
                // WARNING level
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
            // For single pulse, use createOneShot; for pattern, use createWaveform
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            v.vibrate(pattern, -1)
        }
    }
}
