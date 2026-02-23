package com.ketan.slam

import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

data class SlamStatistics(
    val currentPosition: Point3D,
    val edgeCount: Int,
    val cellCount: Int,
    val totalDistance: Float
)

class SlamEngine {

    companion object {
        private const val GRID_RESOLUTION = 0.25f        // metres per cell
        private const val MIN_MOVE_THRESHOLD = 0.02f     // 2 cm min movement to record
        private const val MAX_HISTORY = 5000             // max pose history entries
    }

    // Pose trail
    private val poseHistory = ArrayDeque<Point3D>(MAX_HISTORY)
    private var currentPosition = Point3D(0f, 0f, 0f)
    private var totalDistance = 0f

    // Occupancy grid: true = occupied (wall/obstacle), false = free space
    private val occupancyGrid = ConcurrentHashMap<GridCell, Boolean>()

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

        // Mark traversed cell as free space
        markCell(position, occupied = false)

        // Draw line of free cells between previous and current
        if (poseHistory.size >= 2) {
            bresenham3D(prev, position) { p -> markCell(p, occupied = false) }
        }
    }

    /**
     * Called with ARCore plane polygon (FloatBuffer of x,z pairs in plane-local space).
     * We treat each consecutive pair of vertices as a wall edge and mark the cells
     * along the edge as occupied.
     */
    @Synchronized
    fun addEdges(polygon: FloatBuffer) {
        val verts = mutableListOf<Point3D>()
        polygon.rewind()
        while (polygon.remaining() >= 2) {
            val x = polygon.get()
            val z = polygon.get()
            // ARCore plane polygons are in the plane's local coordinate system.
            // We store them relative to world origin using current camera y as y.
            verts.add(Point3D(
                currentPosition.x + x,
                currentPosition.y,
                currentPosition.z + z
            ))
        }

        if (verts.size < 2) return

        for (i in verts.indices) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            bresenham3D(a, b) { p ->
                // Only mark as occupied if not already marked free by traversal
                if (occupancyGrid[toCell(p)] != false) {
                    markCell(p, occupied = true)
                    edgeCount++
                }
            }
        }
    }

    fun getOccupancyGrid(): Map<GridCell, Boolean> = occupancyGrid

    fun getPoseHistory(): List<Point3D> = synchronized(this) { poseHistory.toList() }

    fun getStatistics(): SlamStatistics = SlamStatistics(
        currentPosition = currentPosition,
        edgeCount = edgeCount,
        cellCount = occupancyGrid.size,
        totalDistance = totalDistance
    )

    fun reset() {
        synchronized(this) {
            poseHistory.clear()
            occupancyGrid.clear()
            currentPosition = Point3D(0f, 0f, 0f)
            totalDistance = 0f
            edgeCount = 0
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun toCell(p: Point3D) = GridCell(
        (p.x / GRID_RESOLUTION).roundToInt(),
        (p.z / GRID_RESOLUTION).roundToInt()
    )

    private fun markCell(p: Point3D, occupied: Boolean) {
        val cell = toCell(p)
        // Free space takes priority – never overwrite false with true
        if (occupied && occupancyGrid[cell] == false) return
        occupancyGrid[cell] = occupied
    }

    /**
     * 3-D Bresenham line walk (only X and Z axes matter for 2-D floor map).
     */
    private fun bresenham3D(a: Point3D, b: Point3D, visit: (Point3D) -> Unit) {
        var x0 = (a.x / GRID_RESOLUTION).roundToInt()
        var z0 = (a.z / GRID_RESOLUTION).roundToInt()
        val x1 = (b.x / GRID_RESOLUTION).roundToInt()
        val z1 = (b.z / GRID_RESOLUTION).roundToInt()

        val dx = abs(x1 - x0)
        val dz = abs(z1 - z0)
        val sx = if (x0 < x1) 1 else -1
        val sz = if (z0 < z1) 1 else -1
        var err = dx - dz

        while (true) {
            visit(Point3D(x0 * GRID_RESOLUTION, (a.y + b.y) / 2f, z0 * GRID_RESOLUTION))
            if (x0 == x1 && z0 == z1) break
            val e2 = 2 * err
            if (e2 > -dz) { err -= dz; x0 += sx }
            if (e2 < dx)  { err += dx; z0 += sz }
        }
    }
}