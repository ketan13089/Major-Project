package com.ketan.slam

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the occupancy grid from the observation store.
 *
 * Unlike the previous approach where each frame directly mutated the grid,
 * MapBuilder can perform a full rebuild from stored [Keyframe]s. This means
 * that when ARCore corrects its pose (detected via [PoseTracker.checkDrift]),
 * the grid can be regenerated from scratch using corrected positions — fixing
 * drift-induced wall misalignments and duplicate walls.
 *
 * The builder also performs:
 * - Temporal decay (occupied cells that haven't been re-observed lose confidence)
 * - Spatial consistency enforcement (remove isolated wall cells, fill wall gaps)
 * - Incremental updates for low-latency frame-by-frame additions
 */
class MapBuilder(private val res: Float) {

    companion object {
        // Cell type constants (same as before, sent to Flutter)
        const val CELL_UNKNOWN  = 0
        const val CELL_FREE     = 1
        const val CELL_OBSTACLE = 2
        const val CELL_WALL     = 3
        const val CELL_VISITED  = 4

        // Log-odds tuning
        private const val L_FREE         = -0.4f
        private const val L_OCCUPIED     = 0.7f
        private const val L_MIN          = -3.0f   // was -2.0: allow cells to become more strongly free
        private const val L_MAX          = 2.5f    // was 3.5: don't over-commit occupied, allows correction
        private const val LO_THRESH_FREE = -0.5f
        private const val LO_THRESH_OCC  = 1.0f

        // Temporal decay: occupied cells lose confidence if not re-observed
        private const val DECAY_PER_REBUILD = 0.05f

        // Ray-cast — reduced from 4.0m for fewer false-free cells
        private const val RAY_MAX_DIST = 2.5f

        // Multi-ray fan angles (radians)
        private val RAY_FAN_ANGLES = floatArrayOf(
            Math.toRadians(-30.0).toFloat(),
            Math.toRadians(-20.0).toFloat(),
            Math.toRadians(-10.0).toFloat(),
            0f,
            Math.toRadians(10.0).toFloat(),
            Math.toRadians(20.0).toFloat(),
            Math.toRadians(30.0).toFloat()
        )
    }

    // Thread-safe cell map: GridCell → cell type byte
    val grid = ConcurrentHashMap<GridCell, Byte>()

    // Log-odds accumulation map
    val logOdds = ConcurrentHashMap<GridCell, Float>()

    // Bounding box tracking
    @Volatile var minGX = 0; @Volatile var maxGX = 0
    @Volatile var minGZ = 0; @Volatile var maxGZ = 0

    // Track which cells were observed in this rebuild (for decay)
    private val observedThisRebuild = HashSet<GridCell>()

    // ── Full Rebuild ───────────────────────────────────────────────────────────

    /**
     * Full grid rebuild from all stored keyframes.
     *
     * This is the key improvement: instead of accumulating mutations over time
     * (which bakes in drift errors permanently), we clear and re-project all
     * observations from scratch. Since ARCore's plane poses reflect internal
     * corrections, the re-projected planes will be at corrected positions.
     *
     * Called from a background coroutine every ~2 seconds, or immediately
     * when drift is detected.
     */
    @Synchronized
    fun rebuild(keyframes: List<Keyframe>) {
        observedThisRebuild.clear()

        // Apply temporal decay to all occupied cells before re-integrating
        for ((cell, lo) in logOdds) {
            if (lo > 0f) {
                val decayed = (lo - DECAY_PER_REBUILD).coerceAtLeast(0f)
                logOdds[cell] = decayed
            }
        }

        // Re-project all keyframe observations
        for (kf in keyframes) {
            integrateKeyframe(kf)
        }

        // Consistency enforcement
        enforceConsistency()

        // Derive byte grid from logOdds
        deriveGrid()
    }

    // ── Incremental Update ────────────────────────────────────────────────────

    /**
     * Fast incremental update for the current frame.
     * Called from the GL thread on every frame for low-latency local updates.
     * The full rebuild will later re-process this data anyway.
     */
    fun incrementalUpdate(
        poseX: Float, poseZ: Float,
        headingRad: Float,
        forwardX: Float, forwardZ: Float
    ) {
        val gx = worldToGrid(poseX)
        val gz = worldToGrid(poseZ)

        // Mark camera cell as visited
        forceLogOdds(gx, gz, L_MIN, CELL_VISITED)

        // Mark 0.5m radius around camera as free
        for (dz in -2..2) for (dx in -2..2) {
            if (dx * dx + dz * dz <= 4) updateLogOdds(gx + dx, gz + dz, L_FREE)
        }

        // Cast ray fan forward
        castRayFan(poseX, poseZ, forwardX, forwardZ)
    }

    /**
     * Integrate a single plane into the grid (used both during rebuild
     * and for real-time incremental plane updates).
     */
    fun integratePlane(plane: PlaneSnapshot) {
        when (plane.type) {
            PlaneType.HORIZONTAL_FREE -> rasterisePlaneAsFree(plane.worldVertices)
            PlaneType.VERTICAL_WALL   -> rasterisePlaneAsWall(plane.worldVertices)
        }
    }

    /**
     * Mark an obstacle footprint for a detected object.
     */
    fun markObstacleFootprint(wp: Point3D, halfMetres: Float) {
        val halfCells = (halfMetres / res).roundToInt().coerceAtLeast(1)
        val ogx = worldToGrid(wp.x)
        val ogz = worldToGrid(wp.z)
        for (dz in -halfCells..halfCells) for (dx in -halfCells..halfCells)
            updateLogOdds(ogx + dx, ogz + dz, L_OCCUPIED)
    }

    /**
     * Clear the obstacle footprint for a removed object.
     * Resets the log-odds of cells in the footprint area back toward unknown.
     */
    fun clearObstacleFootprint(wp: Point3D, halfMetres: Float) {
        val halfCells = (halfMetres / res).roundToInt().coerceAtLeast(1)
        val ogx = worldToGrid(wp.x)
        val ogz = worldToGrid(wp.z)
        for (dz in -halfCells..halfCells) for (dx in -halfCells..halfCells) {
            val cell = GridCell(ogx + dx, ogz + dz)
            val cur = logOdds.getOrDefault(cell, 0f)
            if (cur > 0f) {
                logOdds[cell] = 0f  // reset to unknown
                grid[cell] = CELL_UNKNOWN.toByte()
            }
        }
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun integrateKeyframe(kf: Keyframe) {
        val gx = worldToGrid(kf.poseX)
        val gz = worldToGrid(kf.poseZ)

        // Mark camera cell as visited
        forceLogOdds(gx, gz, L_MIN, CELL_VISITED)

        // Mark vicinity free
        for (dz in -2..2) for (dx in -2..2) {
            if (dx * dx + dz * dz <= 4) {
                updateLogOdds(gx + dx, gz + dz, L_FREE)
                observedThisRebuild.add(GridCell(gx + dx, gz + dz))
            }
        }

        // Cast ray fan
        castRayFan(kf.poseX, kf.poseZ, kf.forwardX, kf.forwardZ)

        // Integrate planes
        for (plane in kf.planes) {
            integratePlane(plane)
        }

        // Integrate object sightings
        for (sighting in kf.objectSightings) {
            markObstacleFootprint(sighting.worldPosition, sighting.footprintHalfMetres)
        }
    }

    private fun enforceConsistency() {
        // Pass 1: Remove isolated wall cells (wall with <2 wall/obstacle neighbors)
        val toReset = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            if (lo < LO_THRESH_OCC) continue
            val wallNeighbors = countOccupiedNeighbors(cell)
            if (wallNeighbors < 2) toReset.add(cell)
        }
        for (cell in toReset) {
            logOdds[cell] = 0f
        }

        // Pass 2: Fill single-cell gaps in walls
        // A free cell with wall/obstacle on 2 opposing sides is likely a wall gap
        val toPromote = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            if (lo > LO_THRESH_FREE) continue  // only check free/unknown cells
            if (isWallGap(cell)) toPromote.add(cell)
        }
        for (cell in toPromote) {
            logOdds[cell] = LO_THRESH_OCC
        }
    }

    private fun countOccupiedNeighbors(cell: GridCell): Int {
        var count = 0
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            val lo = logOdds.getOrDefault(GridCell(cell.x + dx, cell.z + dz), 0f)
            if (lo >= LO_THRESH_OCC) count++
        }
        return count
    }

    private fun isWallGap(cell: GridCell): Boolean {
        val left  = logOdds.getOrDefault(GridCell(cell.x - 1, cell.z), 0f) >= LO_THRESH_OCC
        val right = logOdds.getOrDefault(GridCell(cell.x + 1, cell.z), 0f) >= LO_THRESH_OCC
        val up    = logOdds.getOrDefault(GridCell(cell.x, cell.z - 1), 0f) >= LO_THRESH_OCC
        val down  = logOdds.getOrDefault(GridCell(cell.x, cell.z + 1), 0f) >= LO_THRESH_OCC
        return (left && right) || (up && down)
    }

    private fun deriveGrid() {
        for ((cell, lo) in logOdds) {
            grid[cell] = thresholdCell(lo, false)
        }
    }

    // ── Grid cell helpers ──────────────────────────────────────────────────────

    fun worldToGrid(v: Float) = (v / res).roundToInt()
    fun gridToWorld(g: Int)   = g * res

    private fun updateLogOdds(gx: Int, gz: Int, delta: Float, wallHint: Boolean = false) {
        val cell = GridCell(gx, gz)
        val cur = logOdds.getOrDefault(cell, 0f)
        val updated = (cur + delta).coerceIn(L_MIN, L_MAX)
        logOdds[cell] = updated
        grid[cell] = thresholdCell(updated, wallHint)
        trackBounds(gx, gz)
    }

    private fun forceLogOdds(gx: Int, gz: Int, value: Float, cellType: Int) {
        val cell = GridCell(gx, gz)
        logOdds[cell] = value
        grid[cell] = cellType.toByte()
        trackBounds(gx, gz)
    }

    private fun thresholdCell(lo: Float, wallHint: Boolean): Byte = when {
        lo >= LO_THRESH_OCC  -> if (wallHint) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
        lo <= LO_THRESH_FREE -> CELL_FREE.toByte()
        else                 -> CELL_UNKNOWN.toByte()
    }

    private fun trackBounds(gx: Int, gz: Int) {
        if (gx < minGX) minGX = gx; if (gx > maxGX) maxGX = gx
        if (gz < minGZ) minGZ = gz; if (gz > maxGZ) maxGZ = gz
    }

    // ── Ray casting ────────────────────────────────────────────────────────────

    private fun castRayFan(cx: Float, cz: Float, fwdX: Float, fwdZ: Float) {
        val len = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(0.001f)
        val nx = fwdX / len; val nz = fwdZ / len
        for (angle in RAY_FAN_ANGLES) {
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()
            val rx = nx * cosA - nz * sinA
            val rz = nx * sinA + nz * cosA
            rayCastFree(cx, cz, rx, rz, RAY_MAX_DIST)
        }
    }

    private fun rayCastFree(originX: Float, originZ: Float, dirX: Float, dirZ: Float, maxDist: Float) {
        val len = sqrt(dirX * dirX + dirZ * dirZ).coerceAtLeast(0.001f)
        val nx = dirX / len; val nz = dirZ / len
        var d = 0f
        while (d < maxDist) {
            val wx = originX + nx * d; val wz = originZ + nz * d
            val gx = worldToGrid(wx); val gz = worldToGrid(wz)
            val lo = logOdds.getOrDefault(GridCell(gx, gz), 0f)
            if (lo >= LO_THRESH_OCC) break
            updateLogOdds(gx, gz, L_FREE)
            d += res
        }
    }

    // ── Plane rasterization ────────────────────────────────────────────────────

    private fun rasterisePlaneAsFree(verts: List<Pair<Float, Float>>) {
        if (verts.size < 3) return
        val minX = verts.minOf { it.first };  val maxX = verts.maxOf { it.first }
        val minZ = verts.minOf { it.second }; val maxZ = verts.maxOf { it.second }

        var wx = minX
        while (wx <= maxX) {
            var wz = minZ
            while (wz <= maxZ) {
                if (pointInPolygon(wx, wz, verts)) updateLogOdds(worldToGrid(wx), worldToGrid(wz), L_FREE)
                wz += res
            }
            wx += res
        }
    }

    private fun rasterisePlaneAsWall(verts: List<Pair<Float, Float>>) {
        for (i in verts.indices) {
            val a = verts[i]; val b = verts[(i + 1) % verts.size]
            bresenhamLine(
                worldToGrid(a.first), worldToGrid(a.second),
                worldToGrid(b.first), worldToGrid(b.second)
            ) { gx, gz -> updateLogOdds(gx, gz, L_OCCUPIED, wallHint = true) }
        }
    }

    // ── Geometry helpers ───────────────────────────────────────────────────────

    private fun pointInPolygon(px: Float, pz: Float, verts: List<Pair<Float, Float>>): Boolean {
        var winding = 0
        val n = verts.size
        for (i in 0 until n) {
            val ax = verts[i].first;     val az = verts[i].second
            val bx = verts[(i+1)%n].first; val bz = verts[(i+1)%n].second
            if (az <= pz) { if (bz > pz && crossZ(ax, az, bx, bz, px, pz) > 0) winding++ }
            else          { if (bz <= pz && crossZ(ax, az, bx, bz, px, pz) < 0) winding-- }
        }
        return winding != 0
    }

    private fun crossZ(ax: Float, az: Float, bx: Float, bz: Float, px: Float, pz: Float) =
        (bx - ax) * (pz - az) - (bz - az) * (px - ax)

    private fun bresenhamLine(x0: Int, z0: Int, x1: Int, z1: Int, draw: (Int, Int) -> Unit) {
        var x = x0; var z = z0
        val dx = abs(x1 - x0); val dz = abs(z1 - z0)
        val sx = if (x0 < x1) 1 else -1; val sz = if (z0 < z1) 1 else -1
        var err = dx - dz
        while (true) {
            draw(x, z)
            if (x == x1 && z == z1) break
            val e2 = 2 * err
            if (e2 > -dz) { err -= dz; x += sx }
            if (e2 < dx)  { err += dx; z += sz }
        }
    }
}
