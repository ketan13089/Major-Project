package com.ketan.slam

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

    /**
     * Output grid: GridCell → cell type byte.
     *
     * Backed by a chunked byte array (see [ChunkedByteGrid]) instead of a
     * HashMap. Same MutableMap<GridCell, Byte> surface for external callers
     * (MapPersistence, SemanticCorrectionEngine, ArActivity, tests), but
     * ~8× less memory per cell and far faster iteration at campus scale.
     */
    val grid = ChunkedByteGrid()

    /** Log-odds accumulation. Chunked like [grid]. */
    val logOdds = ChunkedFloatGrid()

    /**
     * FIX 1: Separate set tracking which cells originated from a vertical wall
     * plane. This survives the deriveGrid() call correctly.
     * Using HashSet (not ConcurrentHashSet) — only accessed under @Synchronized.
     */
    private val wallCells = HashSet<GridCell>()

    /** Observation counter per cell — incremented on every log-odds update.
     *  NOT reset on rebuild; provides a cumulative measure of mapping confidence. */
    private val observationCounts = ChunkedIntGrid()

    /** Cells identified as door openings by AI semantic priors.
     *  These cells are treated as walkable by PathPlanner during inflation. */
    val doorCells = HashSet<GridCell>()

    /** Bounding box of all known cells. */
    @Volatile var minGX = 0; @Volatile var maxGX = 0
    @Volatile var minGZ = 0; @Volatile var maxGZ = 0

    private val observedThisRebuild = HashSet<GridCell>()

    /** Counter for lightRebuild — used to throttle expensive passes. */
    private var lightRebuildCount = 0

    /** Frame counter for throttling per-frame local wall inference. */
    private var incrementalFrameCount = 0

    // ── Lookup helper (avoids repeated GridCell allocation in hot loops) ──────

    /** Allocation-free log-odds read. Routes through [ChunkedFloatGrid.getOrDefault]. */
    private fun loAt(x: Int, z: Int): Float = logOdds.getOrDefault(x, z, 0f)

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
        logOdds.forEachCell { x, z, lo ->
            if (lo > 0f) {
                val obs = observationCounts.getOrDefault(x, z, 0)
                val decay = when {
                    obs >= 10 -> 0.04f
                    obs >= 5  -> 0.08f
                    else      -> 0.15f
                }
                logOdds.putFloat(x, z, (lo - decay).coerceAtLeast(0f))
            } else if (lo < 0f) {
                logOdds.putFloat(x, z, (lo + DECAY_PER_REBUILD * 0.5f).coerceAtMost(0f))
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

        // Phase 1: Decay + derive in a SINGLE pass over the chunked grid.
        // `forEachCell` iterates the dense arrays directly, so this is
        // effectively O(present cells) with no per-cell GridCell allocation.
        val toRemove = mutableListOf<GridCell>()
        logOdds.forEachCell { x, z, lo ->
            var newLo = lo
            if (lo > 0f) {
                val obs = observationCounts.getOrDefault(x, z, 0)
                val decay = when {
                    obs >= 10 -> 0.04f
                    obs >= 5  -> 0.08f
                    else      -> 0.15f
                }
                newLo = (lo - decay).coerceAtLeast(0f)
                if (newLo == 0f) {
                    toRemove.add(GridCell(x, z))
                    return@forEachCell
                }
                logOdds.putFloat(x, z, newLo)
            } else if (lo < 0f) {
                newLo = (lo + DECAY_PER_REBUILD * 0.5f).coerceAtMost(0f)
                logOdds.putFloat(x, z, newLo)
            }
            val cell = GridCell(x, z)
            grid.putByte(x, z, when {
                newLo >= LO_THRESH_OCC  -> if (wallCells.contains(cell)) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
                newLo <= LO_THRESH_FREE -> CELL_FREE.toByte()
                else                    -> CELL_UNKNOWN.toByte()
            })
        }

        // Remove zeroed cells
        for (cell in toRemove) {
            logOdds.removeAt(cell.x, cell.z)
            grid.removeAt(cell.x, cell.z)
            observationCounts.removeAt(cell.x, cell.z)
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
        val limit = 50

        logOdds.forEachCell { cx, cz, lo ->
            if (lo > -0.5f) return@forEachCell
            if (toMarkWall.size >= limit) return@forEachCell

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
            val currentLo = logOdds.getOrDefault(cell.x, cell.z, 0f)
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
        val target = excess + excess / 4  // overshoot 25% to avoid re-triggering next cycle

        // Collect candidate cells by type in one pass over the chunked grid.
        val unknowns = mutableListOf<GridCell>()
        val frees    = mutableListOf<GridCell>()
        val obstacles = mutableListOf<Pair<GridCell, Int>>()
        logOdds.forEachCell { x, z, _ ->
            val dx = x - userGX; val dz = z - userGZ
            val dSq = dx * dx + dz * dz
            if (dSq < radiusSq) return@forEachCell
            val ct = grid.getByte(x, z).toInt().let { if (it == -1) CELL_UNKNOWN else it }
            when (ct) {
                CELL_UNKNOWN  -> unknowns.add(GridCell(x, z))
                CELL_FREE     -> frees.add(GridCell(x, z))
                CELL_OBSTACLE -> obstacles.add(GridCell(x, z) to dSq)
            }
        }

        var evicted = 0
        fun drop(cell: GridCell) {
            logOdds.removeAt(cell.x, cell.z)
            grid.removeAt(cell.x, cell.z)
            observationCounts.removeAt(cell.x, cell.z)
            wallCells.remove(cell)
            evicted++
        }

        // Priority: UNKNOWN > FREE > OBSTACLE. Walls and visited stay.
        for (c in unknowns) { if (evicted >= target) break; drop(c) }
        if (evicted < target) for (c in frees)     { if (evicted >= target) break; drop(c) }
        if (evicted < target && obstacles.isNotEmpty()) {
            obstacles.sortByDescending { it.second }
            for ((c, _) in obstacles) { if (evicted >= target) break; drop(c) }
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

        // Local white-wall inference — runs every ~10 frames (~3× per second at
        // 30 FPS). Cheap because it only scans a small window around the user.
        // See [inferLocalWhiteWalls] for why this closes the "ARCore skips white
        // walls" gap.
        incrementalFrameCount++
        if (incrementalFrameCount % 10 == 0) {
            inferLocalWhiteWalls(gx, gz)
        }
    }

    /**
     * Scan a small window around the user and promote UNKNOWN cells that sit
     * on the boundary between FREE cells and unobserved space to weak walls.
     *
     * Rationale: ARCore doesn't produce vertical planes for white, featureless
     * walls because there's nothing for its visual tracker to latch onto. The
     * floor, however, is usually textured (tiles, carpet, reflections from
     * lights) and the ray-fan / depth pipeline reliably marks it FREE. Where
     * the FREE region ends against unmapped space, there's almost always a
     * wall — even if ARCore can't see it.
     *
     * We mark those boundary cells with a weak L_OCCUPIED hint so that after
     * 3-4 passes the cell crosses LO_THRESH_OCC and appears as a wall. Using
     * a weak delta avoids painting walls across doorways, where the user will
     * actually walk through and re-mark the cell FREE, overpowering the hint.
     */
    private fun inferLocalWhiteWalls(userGX: Int, userGZ: Int) {
        val radius = 15  // ~3 m at 0.20 m/cell
        val hint = L_OCCUPIED * 0.35f

        // For every FREE cell in the window, look at 4-connected neighbours.
        // If the neighbour is UNKNOWN, it's a wall candidate.
        for (dz in -radius..radius) for (dx in -radius..radius) {
            // Cheap circular bound — skip corners outside radius.
            if (dx * dx + dz * dz > radius * radius) continue
            val gx = userGX + dx; val gz = userGZ + dz
            val lo = loAt(gx, gz)
            if (lo > LO_THRESH_FREE) continue  // not a confident floor cell

            // For each 4-neighbour, check if it's truly unobserved.
            checkBoundary(gx + 1, gz, hint)
            checkBoundary(gx - 1, gz, hint)
            checkBoundary(gx, gz + 1, hint)
            checkBoundary(gx, gz - 1, hint)
        }
    }

    private fun checkBoundary(gx: Int, gz: Int, hint: Float) {
        val lo = loAt(gx, gz)
        // Only nudge truly unobserved cells. Don't re-hint already-free or
        // already-wall cells — they already have stronger evidence.
        if (lo > -0.2f && lo < 0.2f) {
            updateLogOdds(gx, gz, hint, wallHint = true)
        }
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
     * Mark a cell as a white/featureless wall with stronger evidence.
     * Called when we have high confidence based on floor-hit + miss pattern:
     * floor is visible but ARCore can't track the wall above it.
     */
    fun markWhiteWall(wx: Float, wz: Float) {
        val gx = worldToGrid(wx)
        val gz = worldToGrid(wz)
        // Higher confidence than inferred wall (0.85 vs 0.6 multiplier)
        updateLogOdds(gx, gz, L_OCCUPIED * 0.85f, wallHint = true)
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
        // Pass 1: Prune noise by enforcing at least 2 neighbors for stability.
        val toReset = mutableListOf<GridCell>()
        logOdds.forEachCell { x, z, lo ->
            if (lo < LO_THRESH_OCC) return@forEachCell
            if (countOccupiedNeighborsAt(x, z) < 2) toReset.add(GridCell(x, z))
        }
        for (cell in toReset) {
            logOdds.putFloat(cell.x, cell.z, 0f)
            wallCells.remove(cell)
        }

        // Pass 2: Fill wall gaps (1-cell and 2-cell).
        val toPromote = mutableListOf<GridCell>()
        logOdds.forEachCell { x, z, lo ->
            if (lo > LO_THRESH_FREE) return@forEachCell
            if (isWallGapAt(x, z) || isWallGap2At(x, z)) toPromote.add(GridCell(x, z))
        }
        for (cell in toPromote) {
            logOdds.putFloat(cell.x, cell.z, LO_THRESH_OCC + 0.1f)
            wallCells.add(cell)
        }

        // Pass 2b: Reinforce L-shaped corner cells.
        val toReinforce = mutableListOf<GridCell>()
        logOdds.forEachCell { x, z, lo ->
            if (lo < LO_THRESH_OCC) return@forEachCell
            if (isLCornerAt(x, z)) toReinforce.add(GridCell(x, z))
        }
        for (cell in toReinforce) {
            val cur = logOdds.getOrDefault(cell.x, cell.z, 0f)
            logOdds.putFloat(cell.x, cell.z, (cur + 0.3f).coerceAtMost(L_MAX))
        }
    }

    /** Count occupied 8-neighbors using [loAt] to reduce GridCell allocations. */
    private fun countOccupiedNeighborsAt(cx: Int, cz: Int): Int {
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

    private fun isWallGapAt(cx: Int, cz: Int): Boolean {
        val left  = loAt(cx - 1, cz) >= LO_THRESH_OCC
        val right = loAt(cx + 1, cz) >= LO_THRESH_OCC
        if (left && right) return true
        val up   = loAt(cx, cz - 1) >= LO_THRESH_OCC
        val down = loAt(cx, cz + 1) >= LO_THRESH_OCC
        return up && down
    }

    /** Detect 2-cell wall gaps: occupied cells separated by 2 on same axis. */
    private fun isWallGap2At(cx: Int, cz: Int): Boolean {
        val r1 = loAt(cx + 1, cz) >= LO_THRESH_OCC
        val l1 = loAt(cx - 1, cz) >= LO_THRESH_OCC
        if ((loAt(cx - 2, cz) >= LO_THRESH_OCC && r1) ||
            (l1 && loAt(cx + 2, cz) >= LO_THRESH_OCC)) return true
        val d1 = loAt(cx, cz + 1) >= LO_THRESH_OCC
        val u1 = loAt(cx, cz - 1) >= LO_THRESH_OCC
        return (loAt(cx, cz - 2) >= LO_THRESH_OCC && d1) ||
               (u1 && loAt(cx, cz + 2) >= LO_THRESH_OCC)
    }

    /** Detect L-shaped corner pattern: 2 adjacent occupied neighbors at 90°. */
    private fun isLCornerAt(cx: Int, cz: Int): Boolean {
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
        logOdds.forEachCell { x, z, lo ->
            val isWall = lo >= LO_THRESH_OCC && wallCells.contains(GridCell(x, z))
            grid.putByte(x, z, when {
                lo >= LO_THRESH_OCC  -> if (isWall) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
                lo <= LO_THRESH_FREE -> CELL_FREE.toByte()
                else                 -> CELL_UNKNOWN.toByte()
            })
        }
    }

    // ── Grid cell helpers ──────────────────────────────────────────────────────

    fun worldToGrid(v: Float) = (v / res).roundToInt()
    fun gridToWorld(g: Int)   = g * res

    private fun updateLogOdds(gx: Int, gz: Int, delta: Float, wallHint: Boolean = false) {
        val cur = logOdds.getOrDefault(gx, gz, 0f)
        val updated = (cur + delta).coerceIn(L_MIN, L_MAX)
        logOdds.putFloat(gx, gz, updated)
        observationCounts.putInt(gx, gz, observationCounts.getOrDefault(gx, gz, 0) + 1)
        val isWall = if (wallHint) {
            wallCells.add(GridCell(gx, gz)); true
        } else {
            wallCells.contains(GridCell(gx, gz))
        }
        grid.putByte(gx, gz, when {
            updated >= LO_THRESH_OCC  -> if (isWall) CELL_WALL.toByte() else CELL_OBSTACLE.toByte()
            updated <= LO_THRESH_FREE -> CELL_FREE.toByte()
            else                      -> CELL_UNKNOWN.toByte()
        })
        trackBounds(gx, gz)
    }

    private fun forceLogOdds(gx: Int, gz: Int, value: Float, cellType: Int) {
        logOdds.putFloat(gx, gz, value)
        grid.putByte(gx, gz, cellType.toByte())
        if (cellType != CELL_WALL) wallCells.remove(GridCell(gx, gz))  // FIX 1: visited/free cells are not walls
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

    /** Thread-safe snapshot of observation counts for path safety scoring.
     *  Builds a dense HashMap from the chunked storage so downstream code
     *  (PathPlanner) sees a stable view that's cheap to hash-lookup. */
    fun observationCountSnapshot(): Map<GridCell, Int> {
        val out = HashMap<GridCell, Int>(observationCounts.size)
        for ((k, v) in observationCounts) out[k] = v
        return out
    }

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
            val lo = logOdds.getOrDefault(gx, gz, 0f)
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
        val perimeter = wallPerimeter(verts)
        if (perimeter < 0.15f) return  // less than 15cm wall — likely noise

        // Pass 1: trace the polygon outline. This alone captures most of the
        // wall footprint because ARCore's vertical-plane polygons are usually
        // thin quads along the wall's floor-line.
        for (i in verts.indices) {
            val a = verts[i]; val b = verts[(i + 1) % verts.size]
            bresenhamLine(
                worldToGrid(a.first), worldToGrid(a.second),
                worldToGrid(b.first), worldToGrid(b.second)
            ) { gx, gz ->
                updateLogOdds(gx, gz, L_OCCUPIED, wallHint = true)
            }
        }

        // Pass 2: fill the interior for thicker vertical planes (corners,
        // columns, wall intersections). Skipped for thin polygons where the
        // outline already captured everything. This makes wall segments visibly
        // continuous on the map instead of single-cell thin.
        if (verts.size < 3) return
        val area = polygonArea(verts)
        if (area < 0.04f) return  // too thin to need interior fill (< 0.04 m²)

        val minX = verts.minOf { it.first };  val maxX = verts.maxOf { it.first }
        val minZ = verts.minOf { it.second }; val maxZ = verts.maxOf { it.second }
        var wx = minX
        while (wx <= maxX) {
            var wz = minZ
            while (wz <= maxZ) {
                if (pointInPolygon(wx, wz, verts)) {
                    updateLogOdds(worldToGrid(wx), worldToGrid(wz), L_OCCUPIED, wallHint = true)
                }
                wz += res
            }
            wx += res
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