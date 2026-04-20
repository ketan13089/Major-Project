package com.ketan.slam

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Prevents duplicate or redundant LLM calls without imposing hard usage
 * quotas. Three guards:
 *
 *   1. Per-flow cooldown      — ignore rapid retaps / runaway schedulers.
 *   2. In-flight uniqueness   — only one call of a given flow at a time.
 *   3. Vision-significance    — don't send near-identical frames when the
 *                               user hasn't moved or turned materially.
 *
 * None of these enforce a daily/session cap, so normal usage is never blocked.
 */
class LlmCallGuard {

    // ── Cooldowns (ms) ────────────────────────────────────────────────────────
    companion object {
        /** Minimum gap between successive Ask-button calls. */
        const val ASK_COOLDOWN_MS      = 2_500L
        /** Minimum gap between successive Guide-me calls. */
        const val NAVIGATE_COOLDOWN_MS = 3_000L
        /** Minimum gap between successive vision-update calls. */
        const val VISION_COOLDOWN_MS   = 15_000L

        /** Resend vision update if user has moved at least this far (m). */
        const val VISION_MOVE_THRESHOLD_M = 1.2f
        /** Or rotated at least this much (radians ≈ 25°). */
        const val VISION_TURN_THRESHOLD_RAD = 0.44f
        /** Or this long has elapsed even if user is stationary (idle refresh). */
        const val VISION_IDLE_REFRESH_MS = 60_000L

        /** If camera JPEG size changes by < this ratio → scene considered similar. */
        const val VISION_FRAME_SIMILARITY = 0.06f
    }

    private val askInFlight      = AtomicBoolean(false)
    private val navigateInFlight = AtomicBoolean(false)
    private val visionInFlight   = AtomicBoolean(false)

    @Volatile private var lastAskMs      = 0L
    @Volatile private var lastNavigateMs = 0L
    @Volatile private var lastVisionMs   = 0L

    // Vision-significance bookkeeping
    @Volatile private var lastVisionUserX = Float.NaN
    @Volatile private var lastVisionUserZ = Float.NaN
    @Volatile private var lastVisionHeading = Float.NaN
    @Volatile private var lastVisionJpegBytes = 0

    // ── Ask ───────────────────────────────────────────────────────────────────
    fun tryBeginAsk(now: Long): Boolean {
        if (now - lastAskMs < ASK_COOLDOWN_MS) return false
        if (!askInFlight.compareAndSet(false, true)) return false
        lastAskMs = now
        return true
    }
    fun endAsk() { askInFlight.set(false) }

    // ── Navigate ──────────────────────────────────────────────────────────────
    fun tryBeginNavigate(now: Long): Boolean {
        if (now - lastNavigateMs < NAVIGATE_COOLDOWN_MS) return false
        if (!navigateInFlight.compareAndSet(false, true)) return false
        lastNavigateMs = now
        return true
    }
    fun endNavigate() { navigateInFlight.set(false) }

    // ── Vision ────────────────────────────────────────────────────────────────

    /**
     * Decide whether to trigger a vision update now. The decision combines:
     *   cooldown + in-flight + motion/turn/idle thresholds.
     * Call [commitVisionStart] after the JPEG is prepared so we can also
     * dedup by frame similarity.
     */
    fun shouldStartVision(
        now: Long, userX: Float, userZ: Float, headingRad: Float
    ): Boolean {
        if (visionInFlight.get()) return false
        if (now - lastVisionMs < VISION_COOLDOWN_MS) return false

        // First call of the session — always allow.
        if (lastVisionUserX.isNaN()) return true

        val dx = userX - lastVisionUserX
        val dz = userZ - lastVisionUserZ
        val moved = sqrt(dx * dx + dz * dz)
        val turned = abs(angleDelta(headingRad, lastVisionHeading))
        val elapsed = now - lastVisionMs

        return moved >= VISION_MOVE_THRESHOLD_M ||
               turned >= VISION_TURN_THRESHOLD_RAD ||
               elapsed >= VISION_IDLE_REFRESH_MS
    }

    /**
     * Claim the in-flight slot and check whether this frame is substantially
     * different from the last one. Returns false if the frame is too similar
     * to the previous one to be worth sending.
     */
    fun commitVisionStart(
        now: Long, userX: Float, userZ: Float, headingRad: Float, jpegBytes: Int
    ): Boolean {
        if (!visionInFlight.compareAndSet(false, true)) return false
        // Frame-size similarity check: cheap proxy for scene change. When paired
        // with the motion gate above, prevents nearly-identical hallway shots.
        if (lastVisionJpegBytes > 0) {
            val sizeRatio = abs(jpegBytes - lastVisionJpegBytes).toFloat() /
                            lastVisionJpegBytes.toFloat()
            // Only reject as similar if the user also hasn't moved/turned much.
            val moved = if (!lastVisionUserX.isNaN()) {
                val dx = userX - lastVisionUserX; val dz = userZ - lastVisionUserZ
                sqrt(dx * dx + dz * dz)
            } else Float.MAX_VALUE
            val turned = if (!lastVisionHeading.isNaN())
                abs(angleDelta(headingRad, lastVisionHeading)) else Float.MAX_VALUE
            val userStill = moved < VISION_MOVE_THRESHOLD_M &&
                            turned < VISION_TURN_THRESHOLD_RAD
            if (userStill && sizeRatio < VISION_FRAME_SIMILARITY) {
                visionInFlight.set(false)
                return false
            }
        }
        lastVisionMs = now
        lastVisionUserX = userX
        lastVisionUserZ = userZ
        lastVisionHeading = headingRad
        lastVisionJpegBytes = jpegBytes
        return true
    }

    fun endVision() { visionInFlight.set(false) }

    // ── Util ──────────────────────────────────────────────────────────────────

    /** Shortest signed angle delta in radians. */
    private fun angleDelta(a: Float, b: Float): Float {
        var d = a - b
        while (d >  Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }
}
