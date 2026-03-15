package com.ketan.slam

/**
 * Thread-safe ring buffer of [Keyframe]s.
 *
 * Observations are appended from the GL thread and read by [MapBuilder]
 * on a background coroutine. The store caps at [maxKeyframes] entries —
 * oldest keyframes are discarded when the limit is reached.
 */
class ObservationStore(private val maxKeyframes: Int = 500) {

    private val buffer = ArrayDeque<Keyframe>(maxKeyframes)

    /** Monotonic version counter — incremented on every append. */
    @Volatile
    var version: Long = 0L
        private set

    @Synchronized
    fun append(kf: Keyframe) {
        if (buffer.size >= maxKeyframes) {
            buffer.removeFirst()
        }
        buffer.addLast(kf)
        version++
    }

    /** Returns a snapshot of all keyframes (safe to iterate off-thread). */
    @Synchronized
    fun snapshot(): List<Keyframe> = buffer.toList()

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun clear() {
        buffer.clear()
        version++
    }
}
