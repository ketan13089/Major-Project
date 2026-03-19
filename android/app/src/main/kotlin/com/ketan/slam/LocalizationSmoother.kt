package com.ketan.slam

import kotlin.math.roundToInt

/**
 * Buffers and smooths raw 3D positions per detection before accepting them
 * into the semantic map. Requires 2+ consistent positions within 0.8m over
 * a 5-second window before returning an accepted smoothed position.
 *
 * Uses EMA (Exponential Moving Average) with different weights for
 * hit-test vs fallback localizations. Hit-test results are weighted 3x
 * more than fallback results.
 */
class LocalizationSmoother(private val gridRes: Float) {

    companion object {
        private const val CONSISTENCY_DIST = 0.8f   // max distance between positions to be "consistent"
        private const val MIN_HITS = 2              // minimum consistent positions before accepting
        private const val BUFFER_WINDOW_MS = 5000L  // time window for consistency check
        private const val ALPHA_HIT_TEST = 0.3f     // EMA alpha for hit-test positions
        private const val ALPHA_FALLBACK = 0.15f    // EMA alpha for fallback positions
    }

    data class LocalizationResult(
        val position: Point3D,
        val method: String,      // "hit_test" or "fallback"
        val confidence: Float
    )

    private data class BufferKey(val label: String, val gridX: Int, val gridZ: Int)

    private data class BufferEntry(
        val positions: MutableList<TimedPosition> = mutableListOf(),
        var smoothedPosition: Point3D? = null,
        var accepted: Boolean = false,
        var hitTestCount: Int = 0,
        var fallbackCount: Int = 0
    )

    private data class TimedPosition(
        val position: Point3D,
        val timestamp: Long,
        val method: String
    )

    private val buffers = HashMap<BufferKey, BufferEntry>()

    /**
     * Feed a new localization result. Returns an accepted smoothed position
     * if enough consistent data has been collected, or null if still buffering.
     */
    fun feed(label: String, result: LocalizationResult): Point3D? {
        val key = BufferKey(
            label,
            (result.position.x / gridRes).roundToInt(),
            (result.position.z / gridRes).roundToInt()
        )

        // Find or create buffer, also checking nearby grid cells for existing buffers
        val actualKey = findNearbyKey(key, label) ?: key
        val entry = buffers.getOrPut(actualKey) { BufferEntry() }

        val now = System.currentTimeMillis()

        // Add timed position
        entry.positions.add(TimedPosition(result.position, now, result.method))

        // Track method counts
        when (result.method) {
            "hit_test" -> entry.hitTestCount++
            else -> entry.fallbackCount++
        }

        // Remove old positions outside the window
        entry.positions.removeAll { now - it.timestamp > BUFFER_WINDOW_MS }

        // Check consistency: count positions within CONSISTENCY_DIST of the latest
        val latest = result.position
        val consistent = entry.positions.count { it.position.distance(latest) < CONSISTENCY_DIST }

        if (consistent < MIN_HITS) return null

        // Apply EMA smoothing
        val alpha = if (result.method == "hit_test") ALPHA_HIT_TEST else ALPHA_FALLBACK
        val prev = entry.smoothedPosition
        val smoothed = if (prev == null) {
            result.position
        } else {
            // Weight hit-test positions 3x when mixing with fallback
            val effectiveAlpha = if (result.method == "hit_test" && entry.fallbackCount > 0) {
                (alpha * 3f).coerceAtMost(0.8f)
            } else {
                alpha
            }
            Point3D(
                prev.x * (1 - effectiveAlpha) + result.position.x * effectiveAlpha,
                prev.y * (1 - effectiveAlpha) + result.position.y * effectiveAlpha,
                prev.z * (1 - effectiveAlpha) + result.position.z * effectiveAlpha
            )
        }

        entry.smoothedPosition = smoothed
        entry.accepted = true
        return smoothed
    }

    /** Remove stale buffer entries older than [maxAgeMs]. */
    fun removeStale(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val staleKeys = buffers.entries
            .filter { (_, entry) ->
                entry.positions.isEmpty() ||
                entry.positions.all { now - it.timestamp > maxAgeMs }
            }
            .map { it.key }
        staleKeys.forEach { buffers.remove(it) }
    }

    /** Find an existing buffer key within 1 grid cell of the given key. */
    private fun findNearbyKey(key: BufferKey, label: String): BufferKey? {
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            val nearby = BufferKey(label, key.gridX + dx, key.gridZ + dz)
            if (buffers.containsKey(nearby)) return nearby
        }
        return null
    }
}
