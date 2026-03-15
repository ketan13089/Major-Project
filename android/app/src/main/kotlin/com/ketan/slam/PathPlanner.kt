package com.ketan.slam

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A waypoint in world-grid coordinates. */
data class NavWaypoint(val gridX: Int, val gridZ: Int) {
    fun worldX(res: Float) = gridX * res
    fun worldZ(res: Float) = gridZ * res
}

/**
 * A* path planner operating on the existing [GridCell] → Byte occupancy map.
 *
 * Cell type constants mirror ArActivity:
 *   0 = UNKNOWN, 1 = FREE, 2 = OBSTACLE, 3 = WALL, 4 = VISITED
 *
 * Obstacles and walls are inflated by [INFLATE] cells before planning so the
 * computed path keeps a safety margin from edges. The goal cell is exempt from
 * inflation so we can always reach it even if it sits on an obstacle footprint.
 *
 * After raw A*, a greedy line-of-sight pass (string-pulling) removes
 * intermediate collinear waypoints, producing a smoother set of turn points.
 */
class PathPlanner(private val res: Float) {

    companion object {
        /** Safety margin (cells) inflated around OBSTACLE/WALL cells.
         *  2 cells × 0.20 m = 0.40 m clearance — realistic for walking. */
        private const val INFLATE = 2

        // Feature 2.4: semantic cost modifiers
        /** Cost multiplier for cells near stable landmarks (lower = preferred). */
        private const val LANDMARK_COST = 0.8f
        /** Cost multiplier for cells near furniture hazards (higher = penalised). */
        private const val HAZARD_COST   = 1.5f
        /** Radius (cells) around semantic objects for cost adjustment. */
        private const val SEMANTIC_RADIUS = 2

        /** Object types that act as stable landmarks for internal path preference. */
        private val LANDMARK_TYPES = setOf(
            ObjectType.DOOR, ObjectType.LIFT_GATE,
            ObjectType.FIRE_EXTINGUISHER, ObjectType.WINDOW
        )
        /** Object types that represent furniture hazards (narrow gaps). */
        private val HAZARD_TYPES = setOf(ObjectType.CHAIR)
    }

    /**
     * Plan a collision-free path from (startGX, startGZ) to (goalGX, goalGZ).
     * Returns an ordered list of waypoints start → goal, or empty if unreachable.
     * Optionally accepts [semanticObjects] for internal cost modifiers (Feature 2.4).
     */
    fun planPath(
        grid: Map<GridCell, Byte>,
        startGX: Int, startGZ: Int,
        goalGX: Int, goalGZ: Int,
        semanticObjects: List<SemanticObject> = emptyList()
    ): List<NavWaypoint> {
        if (grid.isEmpty()) return emptyList()

        val blocked = inflateObstacles(grid)

        fun isWalkable(x: Int, z: Int): Boolean {
            if (blocked.contains(GridCell(x, z))) return false
            val t = grid[GridCell(x, z)]?.toInt() ?: return false
            return t == 1 || t == 4  // CELL_FREE or CELL_VISITED
        }

        // Goal cell is always reachable so we can arrive at detected objects
        // even when their footprint is marked OBSTACLE.
        fun isWalkableOrGoal(x: Int, z: Int) =
            (x == goalGX && z == goalGZ) || isWalkable(x, z)

        // Pre-check: if start is blocked, find nearest walkable cell
        var actualStartGX = startGX
        var actualStartGZ = startGZ
        if (!isWalkable(startGX, startGZ)) {
            val alt = findNearestWalkable(startGX, startGZ, ::isWalkable, 3)
            if (alt != null) { actualStartGX = alt.x; actualStartGZ = alt.z }
            else return emptyList()
        }

        // Pre-check: verify at least one walkable cell near goal
        val goalReachable = (-2..2).any { dx -> (-2..2).any { dz -> isWalkable(goalGX + dx, goalGZ + dz) } }
        if (!goalReachable) return emptyList()

        // ── A* ────────────────────────────────────────────────────────────────
        data class Node(val x: Int, val z: Int, val g: Float, val f: Float)

        val open    = PriorityQueue<Node>(compareBy { it.f })
        val gCost   = HashMap<GridCell, Float>()
        val parent  = HashMap<GridCell, GridCell?>()
        val start   = GridCell(startGX, startGZ)
        val goal    = GridCell(goalGX,  goalGZ)
        val sqrt2   = sqrt(2f)

        // Feature 2.4: Build semantic cost modifier sets
        val landmarkCells = HashSet<GridCell>()
        val hazardCells   = HashSet<GridCell>()
        for (obj in semanticObjects) {
            val ogx = (obj.position.x / res).roundToInt()
            val ogz = (obj.position.z / res).roundToInt()
            val targetSet = when {
                obj.type in LANDMARK_TYPES -> landmarkCells
                obj.type in HAZARD_TYPES   -> hazardCells
                else -> null
            }
            if (targetSet != null) {
                for (dz in -SEMANTIC_RADIUS..SEMANTIC_RADIUS)
                    for (dx in -SEMANTIC_RADIUS..SEMANTIC_RADIUS)
                        targetSet.add(GridCell(ogx + dx, ogz + dz))
            }
        }

        val actualStart = GridCell(actualStartGX, actualStartGZ)
        gCost[actualStart]  = 0f
        parent[actualStart] = null
        open.add(Node(actualStartGX, actualStartGZ, 0f, octile(actualStartGX, actualStartGZ, goalGX, goalGZ)))

        val dirs = arrayOf(
            intArrayOf( 1,  0), intArrayOf(-1,  0),
            intArrayOf( 0,  1), intArrayOf( 0, -1),
            intArrayOf( 1,  1), intArrayOf( 1, -1),
            intArrayOf(-1,  1), intArrayOf(-1, -1)
        )

        var found = false
        while (open.isNotEmpty()) {
            val cur = open.poll() ?: break
            val cc  = GridCell(cur.x, cur.z)
            if (cc == goal) { found = true; break }
            val cg = gCost[cc] ?: continue

            for (d in dirs) {
                val nx = cur.x + d[0]; val nz = cur.z + d[1]
                if (!isWalkableOrGoal(nx, nz)) continue
                // Prevent corner-cutting on diagonals
                if (d[0] != 0 && d[1] != 0) {
                    if (!isWalkableOrGoal(cur.x + d[0], cur.z) ||
                        !isWalkableOrGoal(cur.x, cur.z + d[1])) continue
                }
                val nc      = GridCell(nx, nz)
                val baseCost = if (d[0] != 0 && d[1] != 0) sqrt2 else 1f
                // Feature 2.4: apply semantic cost modifiers (internal system use only)
                val semanticMod = when {
                    nc in hazardCells   -> HAZARD_COST
                    nc in landmarkCells -> LANDMARK_COST
                    else                -> 1.0f
                }
                val moveCost = baseCost * semanticMod
                val ng      = cg + moveCost
                if (ng < (gCost[nc] ?: Float.MAX_VALUE)) {
                    gCost[nc]  = ng
                    parent[nc] = cc
                    open.add(Node(nx, nz, ng, ng + octile(nx, nz, goalGX, goalGZ)))
                }
            }
        }

        if (!found) return emptyList()

        // ── Reconstruct raw path ──────────────────────────────────────────────
        val raw = mutableListOf<NavWaypoint>()
        var cur: GridCell? = goal
        while (cur != null) {
            raw.add(NavWaypoint(cur.x, cur.z))
            cur = parent[cur]
        }
        raw.reverse()

        // ── String-pull (LOS smoothing) ───────────────────────────────────────
        val smoothed = smoothPath(raw, ::isWalkableOrGoal)

        // Verify smoothed path — string-pulling can create invalid shortcuts
        for (i in 0 until smoothed.size - 1) {
            if (!hasLOS(smoothed[i], smoothed[i + 1], ::isWalkableOrGoal)) {
                return raw  // smoothing created an invalid shortcut, use raw path
            }
        }
        return smoothed
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun smoothPath(
        path: List<NavWaypoint>,
        walkable: (Int, Int) -> Boolean
    ): List<NavWaypoint> {
        if (path.size <= 2) return path
        val out    = mutableListOf(path[0])
        var anchor = 0
        var probe  = 2
        while (probe < path.size) {
            if (!hasLOS(path[anchor], path[probe], walkable)) {
                out.add(path[probe - 1])
                anchor = probe - 1
            }
            probe++
        }
        out.add(path.last())
        return out
    }

    private fun hasLOS(a: NavWaypoint, b: NavWaypoint, walkable: (Int, Int) -> Boolean): Boolean {
        var x0 = a.gridX; var z0 = a.gridZ
        val x1 = b.gridX; val z1 = b.gridZ
        val dx = abs(x1 - x0); val dz = abs(z1 - z0)
        val sx = if (x0 < x1) 1 else -1
        val sz = if (z0 < z1) 1 else -1
        var err = dx - dz
        while (true) {
            if (!walkable(x0, z0)) return false
            if (x0 == x1 && z0 == z1) break
            val e2 = 2 * err
            if (e2 > -dz) { err -= dz; x0 += sx }
            if (e2 < dx)  { err += dx; z0 += sz }
        }
        return true
    }

    private fun inflateObstacles(grid: Map<GridCell, Byte>): Set<GridCell> {
        val blocked = HashSet<GridCell>()
        for ((cell, type) in grid) {
            val t = type.toInt()
            if (t == 2 || t == 3) {  // CELL_OBSTACLE or CELL_WALL
                for (dz in -INFLATE..INFLATE)
                    for (dx in -INFLATE..INFLATE)
                        blocked.add(GridCell(cell.x + dx, cell.z + dz))
            }
        }
        return blocked
    }

    /** Find nearest walkable cell within a given radius. */
    private fun findNearestWalkable(cx: Int, cz: Int, walkable: (Int, Int) -> Boolean, radius: Int): GridCell? {
        for (r in 1..radius) {
            for (dz in -r..r) for (dx in -r..r) {
                if (abs(dx) != r && abs(dz) != r) continue  // only check the ring
                if (walkable(cx + dx, cz + dz)) return GridCell(cx + dx, cz + dz)
            }
        }
        return null
    }

    /** Octile distance — admissible heuristic for 8-directional A*. */
    private fun octile(x0: Int, z0: Int, x1: Int, z1: Int): Float {
        val dx = abs(x0 - x1).toFloat()
        val dz = abs(z0 - z1).toFloat()
        return (dx + dz) + (sqrt(2f) - 2f) * minOf(dx, dz)
    }
}
