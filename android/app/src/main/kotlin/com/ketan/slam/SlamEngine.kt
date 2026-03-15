package com.ketan.slam

import java.nio.FloatBuffer

data class SlamStatistics(
    val currentPosition: Point3D,
    val edgeCount: Int,
    val cellCount: Int,
    val totalDistance: Float
)

/**
 * Lightweight SLAM engine that tracks pose history and edge count.
 *
 * The occupancy grid is now managed exclusively by [ArActivity] at a
 * unified 0.20 m resolution (Feature 1.1). This class no longer
 * maintains a redundant grid — it only tracks the camera trajectory
 * and counts wall-edge segments from ARCore plane polygons.
 */
class SlamEngine {

    companion object {
        private const val MIN_MOVE_THRESHOLD = 0.02f     // 2 cm min movement to record
        private const val MAX_HISTORY = 5000             // max pose history entries
    }

    // Pose trail
    private val poseHistory = ArrayDeque<Point3D>(MAX_HISTORY)
    private var currentPosition = Point3D(0f, 0f, 0f)
    private var totalDistance = 0f

    // Edge count (wall segments detected from planes)
    private var edgeCount = 0

    @Synchronized
    fun addPose(position: Point3D) {
        val prev = if (poseHistory.isEmpty()) position else poseHistory.last()
        val delta = position.distance(prev)

        if (delta < MIN_MOVE_THRESHOLD && poseHistory.isNotEmpty()) return

        currentPosition = position
        totalDistance += delta

        if (poseHistory.size >= MAX_HISTORY) {
            poseHistory.removeFirst()
        }
        poseHistory.addLast(position)
    }

    /**
     * Called with ARCore plane polygon. Counts edge segments only —
     * actual grid marking is handled by ArActivity's rasterise methods.
     */
    @Synchronized
    fun addEdges(polygon: FloatBuffer) {
        polygon.rewind()
        var vertexCount = 0
        while (polygon.remaining() >= 2) {
            polygon.get()  // skip x
            polygon.get()  // skip z
            vertexCount++
        }
        if (vertexCount >= 2) {
            edgeCount += vertexCount  // each vertex pair = one edge segment
        }
    }

    fun getPoseHistory(): List<Point3D> = synchronized(this) { poseHistory.toList() }

    fun getStatistics(): SlamStatistics = SlamStatistics(
        currentPosition = currentPosition,
        edgeCount = edgeCount,
        cellCount = 0,  // grid is now in ArActivity
        totalDistance = totalDistance
    )

    fun reset() {
        synchronized(this) {
            poseHistory.clear()
            currentPosition = Point3D(0f, 0f, 0f)
            totalDistance = 0f
            edgeCount = 0
        }
    }
}