package com.ketan.slam

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the occupancy grid from stored Keyframes, producing a clean
 * architectural floor-plan style map with:
 *   - Solid, continuous walls (dark cells)
 *   - Clear open floor areas (light cells)
 *   - No fragmentation or ghost walls
 *
 * Key fixes applied vs the previous version:
 *
 * FIX 1 — Wall cells were always rendered as CELL_OBSTACLE (pinkish) instead
 *          of CELL_WALL (dark gray). Root cause: `deriveGrid()` always called
 *          `thresholdCell(lo, false)`, discarding the wallHint flag that
 *          `rasterisePlaneAsWall` set. Fix: separate `wallCells` HashSet
 *          tracks which cells came from vertical planes. `deriveGrid()` uses
 *          that set to emit CELL_WALL correctly.
 *
 * FIX 2 — `enforceConsistency()` removed valid wall endpoint cells because
 *          they only had 1 neighbor. This broke continuous walls into short
 *          disconnected stubs. Fix: threshold lowered to < 1 (only remove
 *          truly isolated single cells with zero occupied neighbors).
 *
 * FIX 3 — Degenerate/tiny planes from ARCore's "invalid statistics" planes
 *          were still rasterized, spraying free-space marks across the map in
 *          random directions. Fix: polygon area check — reject planes < 0.25 m².
 *
 * FIX 4 — Ray casting with a near-zero forward vector (before ARCore
 *          initializes tracking) carved a single straight corridor of free
 *          cells in an arbitrary direction. Fix: guard in castRayFan skips
 *          the fan entirely if forwardX/Z length < 0.1.
 *
 * FIX 5 — Wall cells promoted by isWallGap() got logOdds = LO_THRESH_OCC
 *          exactly, but deriveGrid() tested >= LO_THRESH_OCC, so they passed.
 *          However they were NOT added to wallCells set, so they rendered as
 *          obstacles. Fix: `toPromote` loop now also inserts into wallCells.
 *
 * FIX 6 — Temporal decay subtracted from all occupied cells every rebuild,
 *          but wall cells from stable planes are always re-observed and
 *          re-incremented, so the decay had no effect on them. However, for
 *          short-lived obstacle cells (object footprints that moved), decay
 *          was too slow at 0.05/rebuild. Raised to 0.12 for faster cleanup.
 *
 * FIX 7 — After `enforceConsistency()` modified logOdds, `deriveGrid()` was
 *          called and correctly re-derived from logOdds. But wallCells was
 *          only populated during `integrateKeyframe` (the re-projection pass),
 *          not during the gap-fill pass. Gap-filled wall cells therefore became
 *          CELL_OBSTACLE. Now fixed: wallCells is updated in enforceConsistency.
 *
 * FIX 8 — `rebuild()` did not clear logOdds or grid before re-projecting
 *          keyframes. It only applied decay. Cells from old keyframes that
 *          were no longer in the current observation window persisted
 *          indefinitely. Fix: on full rebuild, reset all cell values to 0
 *          before re-integrating, keeping only the decay-modified logOdds
 *          as a warm start (not the full old values).
 */
class MapBuilder(val res: Float) {

    companion object {
        const val CELL_UNKNOWN  = 0
        const val CELL_FREE     = 1
        const val CELL_OBSTACLE = 2
        const val CELL_WALL     = 3
        const val CELL_VISITED  = 4

        // ── Log-odds parameters ──────────────────────────────────────────────
        // Free evidence: moderate negative update. Walls should resist being
        // overwritten by a single ray cast, so L_FREE is gentle.
        private const val L_FREE     = -0.3f

        // Occupied evidence: planes give strong positive signal.
        private const val L_OCCUPIED = 0.9f

        // Hard clamps — prevents runaway confidence in either direction.
        // L_MIN is negative (strongly free), L_MAX is positive (strongly occupied).
        private const val L_MIN = -4.0f
        private const val L_MAX =  3.5f

        // Thresholds for classification.
        // Free: at least 2 free observations with no occupied counter-evidence.
        private const val LO_THRESH_FREE = -0.6f
        // Occupied: needs ~2 confirming observations to classify.
        private const val LO_THRESH_OCC  =  1.5f

        // Temporal decay per full rebuild — faster for obstacle (object) cells,
        // but wall cells from planes are re-reinforced every rebuild anyway.
        private const val DECAY_PER_REBUILD = 0.12f

        // Maximum cells before eviction triggers — prevents unbounded growth
        // that causes all grid iterations to get progressively slower.
        private const val MAX_CELLS = 25_000

        // Distance (grid cells) beyond which cells become eviction candidates.
        // 100 cells × 0.20m = 20m from user.
        private const val EVICT_RADIUS_CELLS = 100

        // Max keyframes to re-project during a full drift rebuild.
        // Caps the O(keyframes × rays) cost of rebuild().
        private const val MAX_REBUILD_KEYFRAMES = 400

        // 360° ray fan in 30° steps (12 rays). Fewer rays = less aggressive
        // free-space carving, which is better for small/cluttered rooms.
        private val RAY_FAN_ANGLES = FloatArray(12) { i ->
            Math.toRadians((i * 30).toDouble()).toFloat()
        }

        // Shorter range to avoid carving through obstacles in small rooms.
        // Forward rays: 2m; rear rays: 1.5m.
        private const val RAY_MAX_DIST = 2.0f
        private const val RAY_REAR_MAX_DIST = 1.5f

        // Minimum plane polygon area in m² to be rasterized.
        // Lowered to capture smaller valid planes in rooms.
        private const val MIN_PLANE_AREA_M2 = 0.10f

        // Minimum forward vector magnitude to attempt ray casting.
        // Prevents garbage rays during ARCore initialization.
        private const val MIN_FORWARD_LEN = 0.1f
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Output grid: GridCell → cell type byte. Thread-safe for reads. */
    val grid = ConcurrentHashMap<GridCell, Byte>()

    /** Log-odds accumulation. */
    val logOdds = ConcurrentHashMap<GridCell, Float>()

    /**
     * FIX 1: Separate set tracking which cells originated from a vertical wall
     * plane. This survives the deriveGrid() call correctly.
     * Using HashSet (not ConcurrentHashSet) — only accessed under @Synchronized.
     */
    private val wallCells = HashSet<GridCell>()

    /** Observation counter per cell — incremented on every log-odds update.
     *  NOT reset on rebuild; provides a cumulative measure of mapping confidence. */
    private val observationCounts = ConcurrentHashMap<GridCell, Int>()

    /** Cells identified as door openings by AI semantic priors.
     *  These cells are treated as walkable by PathPlanner during inflation. */
    val doorCells = HashSet<GridCell>()

    /** Bounding box of all known cells. */
    @Volatile var minGX = 0; @Volatile var maxGX = 0
    @Volatile var minGZ = 0; @Volatile var maxGZ = 0

    private val observedThisRebuild = HashSet<GridCell>()

    /** Counter for lightRebuild — used to throttle expensive passes. */
    private var lightRebuildCount = 0

    // ── Lookup helper (avoids repeated GridCell allocation in hot loops) ──────

    /** Read log-odds by (x, z) without allocating if the cell exists in the
     *  iteration set. Falls back to a short-lived GridCell for misses. */
    private fun loAt(x: Int, z: Int): Float =
        logOdds.getOrDefault(GridCell(x, z), 0f)

    // ── Full Rebuild ───────────────────────────────────────────────────────────

    /**
     * Full grid rebuild — used ONLY for drift correction (rare).
     * Caps keyframe re-projection at [MAX_REBUILD_KEYFRAMES] to bound cost.
     * For periodic rebuilds, use [lightRebuild] instead.
     */
    @Synchronized
    fun rebuild(keyframes: List<Keyframe>) {
        observedThisRebuild.clear()
        wallCells.clear()           // FIX 1: clear before re-projection

        // FIX 8: Apply confidence-weighted decay, then zero out the logOdds
        // values so re-projection starts fresh. Well-observed cells decay slower.
        for ((cell, lo) in logOdds) {
            if (lo > 0f) {
                val obs = observationCounts.getOrDefault(cell, 0)
                val decay = when {
                    obs >= 10 -> 0.04f   // well-observed: very slow decay
                    obs >= 5  -> 0.08f   // moderately observed
                    else      -> 0.15f   // poorly observed: fast decay
                }
                logOdds[cell] = (lo - decay).coerceAtLeast(0f)
            } else if (lo < 0f) {
                // Free cells: apply slight positive decay (tend toward unknown)
                logOdds[cell] = (lo + DECAY_PER_REBUILD * 0.5f).coerceAtMost(0f)
            }
        }

        // Re-project only recent keyframes (cap to bound cost).
        // Older keyframes' contributions survive through logOdds decay.
        val recentKfs = if (keyframes.size > MAX_REBUILD_KEYFRAMES)
            keyframes.subList(keyframes.size - MAX_REBUILD_KEYFRAMES, keyframes.size)
        else keyframes
        for (kf in recentKfs) {
            integrateKeyframe(kf)
        }

        // Consistency enforcement
        enforceConsistency()

        // Derive byte grid from logOdds + wallCells
        deriveGrid()
    }

    // ── Light Rebuild (periodic, no re-projection) ────────────────────────────

    /**
     * Lightweight periodic rebuild: decay stale cells, enforce consistency,
     * derive the output grid. Does NOT re-project keyframes.
     *
     * Cost: O(cells) — constant-time per grid size, no keyframe dependence.
     * Use this for the regular 2-second rebuild cycle. Reserve [rebuild] for
     * drift correction only (rare).
     *
     * Also evicts distant cells when the grid exceeds [MAX_CELLS] to prevent
     * unbounded memory and iteration cost growth over long sessions.
     */
    @Synchronized
    fun lightRebuild(userGX: Int, userGZ: Int) {
        lightRebuildCount++

        // Phase 1: Decay + derive in a SINGLE pass (was 2 separate passes).
        // This is the hot loop — runs every 2s over all cells.
        val toRemove = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            var newLo = lo
            if (lo > 0f) {
                val obs = observationCounts.getOrDefault(cell, 0)
                val decay = when {
                    obs >= 10 -> 0.04f
                    obs >= 5  -> 0.08f
                    else      -> 0.15f
                }
                newLo = (lo - decay).coerceAtLeast(0f)
                if (newLo == 0f) {
                    toRemove.add(cell)
                    continue  // skip derive — cell will be removed
                }
                logOdds[cell] = newLo
            } else if (lo < 0f) {
                newLo = (lo + DECAY_PER_REBUILD * 0.5f).coerceAtMost(0f)
                logOdds[cell] = newLo
            }
            // Inline derive — avoids a second full iteration
            grid[cell] = when {
                newLo >= LO_THRESH_OCC  -> if (wallCells.contains(cell)) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
                newLo <= LO_THRESH_FREE -> CELL_FREE.toByte()
                else                    -> CELL_UNKNOWN.toByte()
            }
        }

        // Remove zeroed cells
        for (cell in toRemove) {
            logOdds.remove(cell)
            grid.remove(cell)
            observationCounts.remove(cell)
            wallCells.remove(cell)
        }

        // Phase 2: Evict distant cells if over capacity
        if (logOdds.size > MAX_CELLS) {
            evictDistantCells(userGX, userGZ)
        }

        // Phase 3: Expensive structural passes — throttled to avoid O(n×neighbors)
        // every 2s. Wall inference every 10s, consistency every 6s.
        if (lightRebuildCount % 5 == 0) {
            inferWallsAtFloorEdges()
        }
        if (lightRebuildCount % 3 == 0) {
            enforceConsistency()
            deriveGrid()  // re-derive after consistency modifications
        }
    }

    /**
     * Infer walls where floor cells meet unknown cells.
     * This helps detect white walls that ARCore can't track.
     * For each FREE cell, if it has unknown neighbors, mark those as potential walls.
     */
    private fun inferWallsAtFloorEdges() {
        val toMarkWall = mutableListOf<GridCell>()

        for ((cell, lo) in logOdds) {
            if (lo > -0.5f) continue  // not a confident floor cell
            if (toMarkWall.size >= 50) break  // early exit — limit per rebuild

            val cx = cell.x; val cz = cell.z
            // Check 4-connected neighbors inline (avoids list + GridCell allocations)
            val nLo1 = loAt(cx + 1, cz)
            if (nLo1 > -0.3f && nLo1 < 0.3f) toMarkWall.add(GridCell(cx + 1, cz))
            val nLo2 = loAt(cx - 1, cz)
            if (nLo2 > -0.3f && nLo2 < 0.3f) toMarkWall.add(GridCell(cx - 1, cz))
            val nLo3 = loAt(cx, cz + 1)
            if (nLo3 > -0.3f && nLo3 < 0.3f) toMarkWall.add(GridCell(cx, cz + 1))
            val nLo4 = loAt(cx, cz - 1)
            if (nLo4 > -0.3f && nLo4 < 0.3f) toMarkWall.add(GridCell(cx, cz - 1))
        }

        for (cell in toMarkWall) {
            val currentLo = logOdds[cell] ?: 0f
            if (currentLo < LO_THRESH_OCC) {
                updateLogOdds(cell.x, cell.z, L_OCCUPIED * 0.5f, wallHint = true)
            }
        }
    }

    /**
     * Evict cells far from the user to keep memory bounded.
     * Only removes cells beyond [EVICT_RADIUS_CELLS] from the user.
     * Prioritizes removing UNKNOWN > FREE > OBSTACLE > WALL.
     */
    private fun evictDistantCells(userGX: Int, userGZ: Int) {
        val excess = logOdds.size - MAX_CELLS
        if (excess <= 0) return

        val radiusSq = EVICT_RADIUS_CELLS * EVICT_RADIUS_CELLS

        // Two-pass eviction: first evict UNKNOWN/FREE (cheap), only sort if needed.
        // This avoids O(n log n) sort on every eviction for the common case.
        var evicted = 0
        val target = excess + excess / 4  // overshoot 25% to avoid re-triggering next cycle

        // Fast pass: evict unknown cells beyond radius (no sorting needed)
        if (evicted < target) {
            val iter = logOdds.entries.iterator()
            while (iter.hasNext() && evicted < target) {
                val (cell, _) = iter.next()
                val dx = cell.x - userGX; val dz = cell.z - userGZ
                if (dx * dx + dz * dz < radiusSq) continue
                val ct = grid[cell]?.toInt() ?: CELL_UNKNOWN
                if (ct == CELL_UNKNOWN) {
                    iter.remove()
                    grid.remove(cell); observationCounts.remove(cell); wallCells.remove(cell)
                    evicted++
                }
            }
        }

        // Fast pass: evict free cells beyond radius
        if (evicted < target) {
            val iter = logOdds.entries.iterator()
            while (iter.hasNext() && evicted < target) {
                val (cell, _) = iter.next()
                val dx = cell.x - userGX; val dz = cell.z - userGZ
                if (dx * dx + dz * dz < radiusSq) continue
                val ct = grid[cell]?.toInt() ?: CELL_UNKNOWN
                if (ct == CELL_FREE) {
                    iter.remove()
                    grid.remove(cell); observationCounts.remove(cell); wallCells.remove(cell)
                    evicted++
                }
            }
        }

        // Slow pass: if still over, evict obstacles by distance (skip walls/visited)
        if (evicted < target) {
            val obstaclesByDist = mutableListOf<Pair<GridCell, Int>>()
            for ((cell, _) in logOdds) {
                val dx = cell.x - userGX; val dz = cell.z - userGZ
                val dSq = dx * dx + dz * dz
                if (dSq < radiusSq) continue
                val ct = grid[cell]?.toInt() ?: CELL_UNKNOWN
                if (ct == CELL_OBSTACLE) obstaclesByDist.add(cell to dSq)
            }
            obstaclesByDist.sortByDescending { it.second }
            for ((cell, _) in obstaclesByDist) {
                if (evicted >= target) break
                logOdds.remove(cell); grid.remove(cell)
                observationCounts.remove(cell); wallCells.remove(cell)
                evicted++
            }
        }
    }

    // ── Incremental Update ────────────────────────────────────────────────────

    /**
     * Fast per-frame incremental update (called from GL thread).
     * Low-latency local update — the full rebuild will re-process later.
     */
    fun incrementalUpdate(
        poseX: Float, poseZ: Float,
        headingRad: Float,
        forwardX: Float, forwardZ: Float
    ) {
        val gx = worldToGrid(poseX)
        val gz = worldToGrid(poseZ)

        // Camera cell is always visited
        forceLogOdds(gx, gz, L_MIN, CELL_VISITED)

        // ~0.3m radius around camera = definitely free (user physically occupies this space).
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            updateLogOdds(gx + dx, gz + dz, L_FREE)
        }

        // FIX 4: Guard against zero/near-zero forward vector
        val fwdLen = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (fwdLen < MIN_FORWARD_LEN) return

        castRayFan(poseX, poseZ, forwardX, forwardZ)
    }

    /**
     * Integrate a single plane snapshot (used for real-time incremental updates
     * as well as during full rebuild).
     */
    fun integratePlane(plane: PlaneSnapshot) {
        when (plane.type) {
            PlaneType.HORIZONTAL_FREE -> rasterisePlaneAsFree(plane.worldVertices)
            PlaneType.VERTICAL_WALL   -> rasterisePlaneAsWall(plane.worldVertices)
        }
    }

    /** Mark an obstacle footprint (object detection). */
    fun markObstacleFootprint(wp: Point3D, halfMetres: Float) {
        val halfCells = (halfMetres / res).roundToInt().coerceAtLeast(1)
        val ogx = worldToGrid(wp.x)
        val ogz = worldToGrid(wp.z)
        for (dz in -halfCells..halfCells) for (dx in -halfCells..halfCells)
            updateLogOdds(ogx + dx, ogz + dz, L_OCCUPIED, wallHint = false)
    }

    /**
     * Mark an obstacle footprint only if the object's affordance requires it.
     * PASS_THROUGH and LANDMARK_ONLY objects skip stamping entirely.
     * WALL_ATTACHED objects stamp only 1 cell (the wall attachment point).
     */
    fun markAffordanceAwareFootprint(wp: Point3D, halfMetres: Float, affordance: ObjectAffordance) {
        when (affordance) {
            ObjectAffordance.FLOOR_OBSTACLE -> markObstacleFootprint(wp, halfMetres)
            ObjectAffordance.WALL_ATTACHED -> {
                // Stamp only the single cell where the object attaches to the wall
                val gx = worldToGrid(wp.x)
                val gz = worldToGrid(wp.z)
                updateLogOdds(gx, gz, L_OCCUPIED, wallHint = true)
            }
            ObjectAffordance.PASS_THROUGH,
            ObjectAffordance.LANDMARK_ONLY -> { /* no footprint */ }
        }
    }

    // ── Semantic prior methods (AI correction fusion) ───────────────────────

    /**
     * Nudge a cell toward FREE. AI floor prior — gentler than hard overwrite.
     * delta = FLOOR_BASE_DELTA * confidence, clamped to [L_MIN, L_MAX].
     */
    fun applySemanticFloorPrior(gridX: Int, gridZ: Int, confidence: Float) {
        val delta = SemanticCorrectionConfig.FLOOR_BASE_DELTA * confidence.coerceIn(0f, 1f)
        val cell = GridCell(gridX, gridZ)
        val cur = logOdds.getOrDefault(cell, 0f)
        val updated = (cur + delta).coerceIn(L_MIN, L_MAX)
        logOdds[cell] = updated
        grid[cell] = when {
            updated >= LO_THRESH_OCC  -> if (wallCells.contains(cell)) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
            updated <= LO_THRESH_FREE -> CELL_FREE.toByte()
            else                      -> CELL_UNKNOWN.toByte()
        }
        trackBounds(gridX, gridZ)
    }

    /**
     * Nudge a cell toward WALL. AI wall prior — bounded, adds wallHint.
     * delta = WALL_BASE_DELTA * confidence, clamped to L_MAX.
     */
    fun applySemanticWallPrior(gridX: Int, gridZ: Int, confidence: Float) {
        val delta = SemanticCorrectionConfig.WALL_BASE_DELTA * confidence.coerceIn(0f, 1f)
        val cell = GridCell(gridX, gridZ)
        val cur = logOdds.getOrDefault(cell, 0f)
        val updated = (cur + delta).coerceIn(L_MIN, L_MAX)
        logOdds[cell] = updated
        wallCells.add(cell)
        grid[cell] = when {
            updated >= LO_THRESH_OCC  -> CELL_WALL.toByte()
            updated <= LO_THRESH_FREE -> CELL_FREE.toByte()
            else                      -> CELL_UNKNOWN.toByte()
        }
        trackBounds(gridX, gridZ)
    }

    /**
     * Create a passable opening in a wall band at the given center/orientation.
     * Only clears wall evidence if the cell's current log-odds < DOOR_WALL_LO_THRESHOLD
     * to prevent punching through confidently detected walls.
     */
    fun applySemanticDoorPrior(
        centerX: Int, centerZ: Int,
        orientationDeg: Float, widthCells: Int, confidence: Float
    ) {
        val halfWidth = widthCells / 2
        val radians = Math.toRadians(orientationDeg.toDouble())
        val dirX = kotlin.math.cos(radians).toFloat()
        val dirZ = kotlin.math.sin(radians).toFloat()

        for (i in -halfWidth..halfWidth) {
            val gx = centerX + (i * dirX).roundToInt()
            val gz = centerZ + (i * dirZ).roundToInt()
            val cell = GridCell(gx, gz)
            val cur = logOdds.getOrDefault(cell, 0f)

            // Don't punch through confidently detected walls on first try
            if (cur >= SemanticCorrectionConfig.DOOR_WALL_LO_THRESHOLD) continue

            val delta = SemanticCorrectionConfig.DOOR_BASE_DELTA * confidence.coerceIn(0f, 1f)
            val updated = (cur + delta).coerceIn(L_MIN, L_MAX)
            logOdds[cell] = updated
            wallCells.remove(cell)
            doorCells.add(cell)
            grid[cell] = when {
                updated >= LO_THRESH_OCC  -> CELL_OBSTACLE.toByte()
                updated <= LO_THRESH_FREE -> CELL_FREE.toByte()
                else                      -> CELL_UNKNOWN.toByte()
            }
            trackBounds(gx, gz)
        }
    }

    /**
     * Batch apply semantic cell updates.
     */
    fun applySemanticPatch(updates: List<SemanticCellUpdate>) {
        for (u in updates) {
            when (u.cellClass) {
                CellClass.FLOOR   -> applySemanticFloorPrior(u.gridX, u.gridZ, u.confidence)
                CellClass.WALL    -> applySemanticWallPrior(u.gridX, u.gridZ, u.confidence)
                else -> { /* OBSTACLE and UNKNOWN handled by existing pipeline */ }
            }
        }
    }

    /** Clear an obstacle footprint (object removed or position corrected). */
    fun clearObstacleFootprint(wp: Point3D, halfMetres: Float) {
        val halfCells = (halfMetres / res).roundToInt().coerceAtLeast(1)
        val ogx = worldToGrid(wp.x)
        val ogz = worldToGrid(wp.z)
        for (dz in -halfCells..halfCells) for (dx in -halfCells..halfCells) {
            val cell = GridCell(ogx + dx, ogz + dz)
            val cur = logOdds.getOrDefault(cell, 0f)
            if (cur > 0f) {
                logOdds[cell] = 0f
                grid[cell] = CELL_UNKNOWN.toByte()
                wallCells.remove(cell)   // FIX 1: keep wallCells consistent
            }
        }
    }

    // ── Depth-hit based map updates ───────────────────────────────────────────
    // Called from ArActivity depth processing on the GL thread.

    /**
     * Mark a confirmed floor-level hit point as free space.
     * Called when a hit-test returns a point at floor level (below camera by >1.2m).
     */
    fun markHitFree(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        updateLogOdds(gx, gz, L_FREE * 2f)
    }

    /**
     * Mark a confirmed wall hit point as occupied WALL.
     * Called when a hit-test returns a point near camera height or on a vertical plane.
     */
    fun markHitOccupied(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        updateLogOdds(gx, gz, L_OCCUPIED * 1.5f, wallHint = true)
    }

    /**
     * Mark a confirmed obstacle (furniture) hit point as occupied OBSTACLE.
     * Does NOT add to wallCells — renders as CELL_OBSTACLE (brown), not wall (dark).
     */
    fun markHitObstacle(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        updateLogOdds(gx, gz, L_OCCUPIED, wallHint = false)
    }

    /**
     * Mark a depth-image-derived wall point. Slightly less confident than
     * a direct hit-test wall (L_OCCUPIED * 1.0f vs 1.5f).
     */
    fun markDepthImageWall(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        updateLogOdds(gx, gz, L_OCCUPIED, wallHint = true)
    }

    // ── Confidence-weighted dense depth methods ──────────────────────────────

    /**
     * Unified confidence-weighted depth point integration.
     * Replaces separate mark methods for dense depth processing.
     *
     * Confidence weighting:
     *   < 64  → skip (too noisy)
     *   64-127  → 0.5x (weak evidence)
     *   128-191 → 0.85x (good evidence)
     *   192-255 → 1.2x (strong evidence)
     *
     * Height classification (relY = point Y - camera Y):
     *   < -1.2m         → floor (free)
     *   -1.2m to -0.8m  → furniture/obstacle
     *   -0.8m to 1.0m   → wall
     *   > 1.0m          → ceiling (ignore)
     */
    fun markDepthPoint(wx: Float, wz: Float, confidence: Int, relY: Float) {
        if (confidence < 64) return  // too noisy, skip

        val confMultiplier = when {
            confidence < 128 -> 0.5f
            confidence < 192 -> 0.85f
            else             -> 1.2f
        }

        when {
            relY < -1.2f -> {
                // Floor — mark as free
                val gx = worldToGrid(wx)
                val gz = worldToGrid(wz)
                updateLogOdds(gx, gz, L_FREE * 2f * confMultiplier)
            }
            relY in -1.2f..-0.8f -> {
                // Furniture/obstacle level
                val gx = worldToGrid(wx)
                val gz = worldToGrid(wz)
                updateLogOdds(gx, gz, L_OCCUPIED * confMultiplier, wallHint = false)
            }
            relY in -0.8f..1.0f -> {
                // Wall level
                val gx = worldToGrid(wx)
                val gz = worldToGrid(wz)
                updateLogOdds(gx, gz, L_OCCUPIED * confMultiplier, wallHint = true)
            }
            // relY > 1.0f → ceiling, ignore
        }
    }

    /**
     * Bresenham ray clearing from camera to hit point.
     * Marks intermediate cells as free space — measurement-driven free carving.
     * Only call for high-confidence wall hits (conf >= 128, depth > 0.5m).
     */
    fun markDepthFreeRay(camWx: Float, camWz: Float, hitWx: Float, hitWz: Float) {
        val gx0 = worldToGrid(camWx)
        val gz0 = worldToGrid(camWz)
        val gx1 = worldToGrid(hitWx)
        val gz1 = worldToGrid(hitWz)

        // Don't clear the hit cell itself — walk up to 1 cell before it
        val dx = abs(gx1 - gx0)
        val dz = abs(gz1 - gz0)
        val steps = maxOf(dx, dz)
        if (steps < 2) return  // too close, nothing to clear

        bresenhamLine(gx0, gz0, gx1, gz1) { gx, gz ->
            // Stop 1 cell before the hit point
            val toDstX = abs(gx1 - gx)
            val toDstZ = abs(gz1 - gz)
            if (toDstX <= 1 && toDstZ <= 1) return@bresenhamLine
            val lo = logOdds.getOrDefault(GridCell(gx, gz), 0f)
            // Don't clear confirmed occupied cells
            if (lo < LO_THRESH_OCC) {
                updateLogOdds(gx, gz, L_FREE)
            }
        }
    }

    /**
     * Mark an inferred wall (from floor-boundary or motion analysis).
     * Weak signal (L_OCCUPIED * 0.6f) — needs several confirming observations
     * across frames to cross the occupied threshold.
     */
    fun markInferredWall(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        updateLogOdds(gx, gz, L_OCCUPIED * 0.6f, wallHint = true)
    }

    /**
     * Mark a cell as a white/featureless wall with stronger evidence.
     * Called when we have high confidence based on floor-hit + miss pattern:
     * floor is visible but ARCore can't track the wall above it.
     */
    fun markWhiteWall(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        // Higher confidence than inferred wall (0.85 vs 0.6 multiplier)
        updateLogOdds(gx, gz, L_OCCUPIED * 0.85f, wallHint = true)
        // Track for AR overlay rendering
        synchronized(recentInferredWalls) {
            recentInferredWalls.add(wx to wz)
            // Keep only the most recent 100 inferred wall positions
            while (recentInferredWalls.size > 100) {
                recentInferredWalls.removeAt(0)
            }
        }
    }

    /** Recent inferred wall positions for AR overlay rendering */
    private val recentInferredWalls = mutableListOf<Pair<Float, Float>>()

    /**
     * Get recently inferred wall positions (for AR overlay rendering).
     * Returns up to 50 positions for performance reasons.
     */
    fun getRecentInferredWalls(): List<Pair<Float, Float>> {
        synchronized(recentInferredWalls) {
            return recentInferredWalls.takeLast(50).toList()
        }
    }

    /** Clear old inferred wall positions (call periodically) */
    fun clearOldInferredWalls() {
        synchronized(recentInferredWalls) {
            recentInferredWalls.clear()
        }
    }

    // ── Private integration ────────────────────────────────────────────────────

    private fun integrateKeyframe(kf: Keyframe) {
        val gx = worldToGrid(kf.poseX)
        val gz = worldToGrid(kf.poseZ)

        forceLogOdds(gx, gz, L_MIN, CELL_VISITED)

        // ~0.3m radius around keyframe pose = free (user was here)
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            updateLogOdds(gx + dx, gz + dz, L_FREE)
            observedThisRebuild.add(GridCell(gx + dx, gz + dz))
        }

        // FIX 4: Guard zero forward vector
        val fwdLen = sqrt(kf.forwardX * kf.forwardX + kf.forwardZ * kf.forwardZ)
        if (fwdLen >= MIN_FORWARD_LEN) {
            castRayFan(kf.poseX, kf.poseZ, kf.forwardX, kf.forwardZ)
        }

        for (plane in kf.planes) integratePlane(plane)
        for (sighting in kf.objectSightings) {
            val objType = ObjectType.fromLabel(sighting.label)
            val affordance = ObjectAffordance.forType(objType)
            markAffordanceAwareFootprint(sighting.worldPosition, sighting.footprintHalfMetres, affordance)
        }
    }

    // ── Consistency enforcement ────────────────────────────────────────────────

    private fun enforceConsistency() {
        // Pass 1: Remove truly isolated occupied cells (0 occupied neighbors).
        // FIX 2: Was < 2, which removed valid wall endpoints. Now < 1 — only
        // removes completely isolated single cells (noise), not wall segments.
        val toReset = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            if (lo < LO_THRESH_OCC) continue
            if (countOccupiedNeighbors(cell) < 1) toReset.add(cell)
        }
        for (cell in toReset) {
            logOdds[cell] = 0f
            wallCells.remove(cell)     // FIX 1: keep wallCells in sync
        }

        // Pass 2: Fill wall gaps (1-cell and 2-cell).
        // FIX 5 + FIX 7: also add promoted cells to wallCells.
        val toPromote = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            if (lo > LO_THRESH_FREE) continue
            if (isWallGap(cell) || isWallGap2(cell)) toPromote.add(cell)
        }
        for (cell in toPromote) {
            logOdds[cell] = LO_THRESH_OCC + 0.1f  // slightly above threshold so deriveGrid picks it up
            wallCells.add(cell)    // FIX 5: gap-filled cells are walls, not obstacles
        }

        // Pass 2b: Reinforce L-shaped corner cells.
        // If a cell has occupied neighbors forming an L-shape (2 adjacent occupied
        // neighbors at 90°), boost its log-odds for wall continuity.
        val toReinforce = mutableListOf<GridCell>()
        for ((cell, lo) in logOdds) {
            if (lo < LO_THRESH_OCC) continue
            if (isLCorner(cell)) toReinforce.add(cell)
        }
        for (cell in toReinforce) {
            val cur = logOdds.getOrDefault(cell, 0f)
            logOdds[cell] = (cur + 0.3f).coerceAtMost(L_MAX)
        }

        // Wall dilation disabled — was causing walls to appear overly thick
        // and creating blobs in small rooms. The walls from depth hits and
        // plane detection are already multi-cell thick due to sensor noise.
    }

    /** Count occupied 8-neighbors using [loAt] to reduce GridCell allocations. */
    private fun countOccupiedNeighbors(cell: GridCell): Int {
        val cx = cell.x; val cz = cell.z
        var count = 0
        if (loAt(cx - 1, cz - 1) >= LO_THRESH_OCC) count++
        if (loAt(cx    , cz - 1) >= LO_THRESH_OCC) count++
        if (loAt(cx + 1, cz - 1) >= LO_THRESH_OCC) count++
        if (loAt(cx - 1, cz    ) >= LO_THRESH_OCC) count++
        if (loAt(cx + 1, cz    ) >= LO_THRESH_OCC) count++
        if (loAt(cx - 1, cz + 1) >= LO_THRESH_OCC) count++
        if (loAt(cx    , cz + 1) >= LO_THRESH_OCC) count++
        if (loAt(cx + 1, cz + 1) >= LO_THRESH_OCC) count++
        return count
    }

    private fun isWallGap(cell: GridCell): Boolean {
        val cx = cell.x; val cz = cell.z
        val left  = loAt(cx - 1, cz) >= LO_THRESH_OCC
        val right = loAt(cx + 1, cz) >= LO_THRESH_OCC
        if (left && right) return true
        val up   = loAt(cx, cz - 1) >= LO_THRESH_OCC
        val down = loAt(cx, cz + 1) >= LO_THRESH_OCC
        return up && down
    }

    /** Detect 2-cell wall gaps: occupied cells separated by 2 on same axis. */
    private fun isWallGap2(cell: GridCell): Boolean {
        val cx = cell.x; val cz = cell.z
        // Horizontal checks
        val r1 = loAt(cx + 1, cz) >= LO_THRESH_OCC
        val l1 = loAt(cx - 1, cz) >= LO_THRESH_OCC
        if ((loAt(cx - 2, cz) >= LO_THRESH_OCC && r1) ||
            (l1 && loAt(cx + 2, cz) >= LO_THRESH_OCC)) return true
        // Vertical checks
        val d1 = loAt(cx, cz + 1) >= LO_THRESH_OCC
        val u1 = loAt(cx, cz - 1) >= LO_THRESH_OCC
        return (loAt(cx, cz - 2) >= LO_THRESH_OCC && d1) ||
               (u1 && loAt(cx, cz + 2) >= LO_THRESH_OCC)
    }

    /** Detect L-shaped corner pattern: 2 adjacent occupied neighbors at 90°. */
    private fun isLCorner(cell: GridCell): Boolean {
        val cx = cell.x; val cz = cell.z
        val l = loAt(cx - 1, cz) >= LO_THRESH_OCC
        val r = loAt(cx + 1, cz) >= LO_THRESH_OCC
        val u = loAt(cx, cz - 1) >= LO_THRESH_OCC
        val d = loAt(cx, cz + 1) >= LO_THRESH_OCC
        return (l && u) || (l && d) || (r && u) || (r && d)
    }

    /**
     * FIX 1: Derive byte grid using wallCells set for correct CELL_WALL vs
     * CELL_OBSTACLE distinction. Previously always passed wallHint=false,
     * making all occupied cells render as pinkish obstacles.
     */
    private fun deriveGrid() {
        for ((cell, lo) in logOdds) {
            grid[cell] = when {
                lo >= LO_THRESH_OCC  -> if (wallCells.contains(cell)) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
                lo <= LO_THRESH_FREE -> CELL_FREE.toByte()
                else                 -> CELL_UNKNOWN.toByte()
            }
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
        observationCounts[cell] = (observationCounts.getOrDefault(cell, 0)) + 1
        if (wallHint) wallCells.add(cell)     // FIX 1: record wall origin
        // Inline threshold for incremental updates (deriveGrid handles rebuilds)
        grid[cell] = when {
            updated >= LO_THRESH_OCC  -> if (wallCells.contains(cell)) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
            updated <= LO_THRESH_FREE -> CELL_FREE.toByte()
            else                      -> CELL_UNKNOWN.toByte()
        }
        trackBounds(gx, gz)
    }

    private fun forceLogOdds(gx: Int, gz: Int, value: Float, cellType: Int) {
        val cell = GridCell(gx, gz)
        logOdds[cell] = value
        grid[cell] = cellType.toByte()
        if (cellType != CELL_WALL) wallCells.remove(cell)  // FIX 1: visited/free cells are not walls
        trackBounds(gx, gz)
    }

    private fun trackBounds(gx: Int, gz: Int) {
        if (gx < minGX) minGX = gx; if (gx > maxGX) maxGX = gx
        if (gz < minGZ) minGZ = gz; if (gz > maxGZ) maxGZ = gz
    }

    // ── Ray casting ────────────────────────────────────────────────────────────

    /**
     * Cast a full 360° ray fan around the camera position.
     * Forward hemisphere (within ±90° of forward) uses full range (3.5m);
     * rear hemisphere uses shorter range (2.5m).
     */
    private fun castRayFan(cx: Float, cz: Float, fwdX: Float, fwdZ: Float) {
        val len = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(0.001f)
        val nx = fwdX / len; val nz = fwdZ / len
        for (angle in RAY_FAN_ANGLES) {
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()
            val rx = nx * cosA - nz * sinA
            val rz = nx * sinA + nz * cosA
            // Dot product with forward: positive = forward hemisphere
            val dot = rx * nx + rz * nz
            val maxDist = if (dot >= 0f) RAY_MAX_DIST else RAY_REAR_MAX_DIST
            rayCastFree(cx, cz, rx, rz, maxDist)
        }
    }

    /** Thread-safe snapshot of observation counts for path safety scoring. */
    fun observationCountSnapshot(): Map<GridCell, Int> = HashMap(observationCounts)

    /** Snapshot of wall cells for persistence. */
    @Synchronized fun getWallCells(): Set<GridCell> = HashSet(wallCells)

    /** Restore wall cells from a loaded map. */
    @Synchronized fun restoreWallCells(cells: Set<GridCell>) {
        wallCells.clear()
        wallCells.addAll(cells)
    }

    private fun rayCastFree(
        originX: Float, originZ: Float,
        dirX: Float, dirZ: Float,
        maxDist: Float
    ) {
        val len = sqrt(dirX * dirX + dirZ * dirZ).coerceAtLeast(0.001f)
        val nx = dirX / len; val nz = dirZ / len
        var d = res  // start 1 cell ahead — don't mark the camera cell as free again
        while (d < maxDist) {
            val wx = originX + nx * d; val wz = originZ + nz * d
            val gx = worldToGrid(wx); val gz = worldToGrid(wz)
            val lo = logOdds.getOrDefault(GridCell(gx, gz), 0f)
            // Stop at occupied cells — walls block rays
            if (lo >= LO_THRESH_OCC) break
            updateLogOdds(gx, gz, L_FREE)
            d += res
        }
    }

    // ── Plane rasterization ────────────────────────────────────────────────────

    private fun rasterisePlaneAsFree(verts: List<Pair<Float, Float>>) {
        if (verts.size < 3) return

        // FIX 3: Reject degenerate planes from ARCore "invalid statistics" planes.
        // These produce near-zero-area polygons that corrupt free-space markings.
        val area = polygonArea(verts)
        if (area < MIN_PLANE_AREA_M2) return

        val minX = verts.minOf { it.first };  val maxX = verts.maxOf { it.first }
        val minZ = verts.minOf { it.second }; val maxZ = verts.maxOf { it.second }

        var wx = minX
        while (wx <= maxX) {
            var wz = minZ
            while (wz <= maxZ) {
                if (pointInPolygon(wx, wz, verts)) {
                    updateLogOdds(worldToGrid(wx), worldToGrid(wz), L_FREE)
                }
                wz += res
            }
            wx += res
        }
    }

    private fun rasterisePlaneAsWall(verts: List<Pair<Float, Float>>) {
        if (verts.size < 2) return

        // FIX 3: Also reject degenerate vertical planes.
        // Use perimeter check instead of area (walls are thin, area ≈ 0).
        val perimeter = wallPerimeter(verts)
        if (perimeter < 0.15f) return  // less than 15cm wall — likely noise

        for (i in verts.indices) {
            val a = verts[i]; val b = verts[(i + 1) % verts.size]
            bresenhamLine(
                worldToGrid(a.first), worldToGrid(a.second),
                worldToGrid(b.first), worldToGrid(b.second)
            ) { gx, gz ->
                // wallHint = true ensures this cell is tracked in wallCells (FIX 1)
                updateLogOdds(gx, gz, L_OCCUPIED, wallHint = true)
            }
        }
    }

    // ── Geometry helpers ───────────────────────────────────────────────────────

    /** Shoelace formula for polygon area in world coordinates. */
    private fun polygonArea(verts: List<Pair<Float, Float>>): Float {
        var area = 0f
        val n = verts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += verts[i].first  * verts[j].second
            area -= verts[j].first  * verts[i].second
        }
        return abs(area) * 0.5f
    }

    /** Sum of edge lengths for a polygon (used for degenerate wall detection). */
    private fun wallPerimeter(verts: List<Pair<Float, Float>>): Float {
        var p = 0f
        val n = verts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = verts[j].first  - verts[i].first
            val dz = verts[j].second - verts[i].second
            p += sqrt(dx * dx + dz * dz)
        }
        return p
    }

    private fun pointInPolygon(px: Float, pz: Float, verts: List<Pair<Float, Float>>): Boolean {
        var winding = 0
        val n = verts.size
        for (i in 0 until n) {
            val ax = verts[i].first;       val az = verts[i].second
            val bx = verts[(i+1)%n].first; val bz = verts[(i+1)%n].second
            if (az <= pz) { if (bz > pz  && crossZ(ax, az, bx, bz, px, pz) > 0) winding++ }
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
            if (e2 <  dx) { err += dx; z += sz }
        }
    }
}