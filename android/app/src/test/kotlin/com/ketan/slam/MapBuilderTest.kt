package com.ketan.slam

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MapBuilder — log-odds updates, cell classification,
 * incremental updates, plane integration, and grid consistency.
 */
class MapBuilderTest {

    private lateinit var builder: MapBuilder
    private val res = 0.20f

    @Before
    fun setUp() {
        builder = MapBuilder(res)
    }

    // ── Grid coordinate conversion ──────────────────────────────────────────

    @Test
    fun `worldToGrid converts correctly`() {
        assertEquals(0, builder.worldToGrid(0f))
        assertEquals(5, builder.worldToGrid(1.0f))     // 1.0 / 0.20 = 5
        assertEquals(-5, builder.worldToGrid(-1.0f))
        assertEquals(10, builder.worldToGrid(2.0f))
        assertEquals(3, builder.worldToGrid(0.5f))      // 0.5 / 0.20 = 2.5 → rounds to 3
    }

    @Test
    fun `worldToGrid handles edge values`() {
        assertEquals(1, builder.worldToGrid(0.1f))       // 0.1 / 0.20 = 0.5 → rounds to 1 (banker's)
        assertEquals(2, builder.worldToGrid(0.3f))       // 0.3 / 0.20 = 1.5 → rounds to 2
    }

    // ── Incremental update marks camera cell ────────────────────────────────

    @Test
    fun `incrementalUpdate marks camera cell as visited`() {
        builder.incrementalUpdate(1.0f, 2.0f, 0f, 0f, 1f)
        val gx = builder.worldToGrid(1.0f)
        val gz = builder.worldToGrid(2.0f)
        val cell = builder.grid[GridCell(gx, gz)]
        assertNotNull("Camera cell should be in grid", cell)
        assertEquals("Camera cell should be VISITED (4)", 4, cell!!.toInt())
    }

    @Test
    fun `incrementalUpdate creates ray fan free cells`() {
        // Heading forward along Z+, with forward vector (0, 1)
        builder.incrementalUpdate(2.0f, 2.0f, 0f, 0f, 1f)
        // Should have created some free cells in front of camera
        val freeCells = builder.grid.count { it.value.toInt() == 1 }
        assertTrue("Ray fan should create free cells (got $freeCells)", freeCells > 0)
    }

    @Test
    fun `multiple incremental updates grow the map`() {
        val sizeBefore = builder.grid.size
        // Simulate walking forward along Z axis
        for (step in 0..20) {
            val z = step * 0.4f  // 40cm steps = 2 grid cells per step
            builder.incrementalUpdate(0f, z, 0f, 0f, 1f)
        }
        val sizeAfter = builder.grid.size
        assertTrue("Grid should grow with walking (before=$sizeBefore, after=$sizeAfter)",
            sizeAfter > sizeBefore)
        // At minimum, the current camera cell should be visited
        val lastGz = builder.worldToGrid(20 * 0.4f)
        val lastCell = builder.grid[GridCell(0, lastGz)]
        assertNotNull("Last camera position should be in grid", lastCell)
        assertEquals("Last camera cell should be VISITED", 4, lastCell!!.toInt())
    }

    // ── markHitFree / markHitOccupied ───────────────────────────────────────

    @Test
    fun `markHitFree creates free cell`() {
        builder.markHitFree(1.0f, 1.0f)
        // After marking, need to trigger deriveGrid — do via incrementalUpdate
        builder.incrementalUpdate(0f, 0f, 0f, 0f, 1f)
        val gx = builder.worldToGrid(1.0f)
        val gz = builder.worldToGrid(1.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("Hit-free cell should have logOdds", lo)
        assertTrue("Hit-free logOdds should be negative (free)", lo!! < 0f)
    }

    @Test
    fun `markHitOccupied creates occupied cell`() {
        builder.markHitOccupied(3.0f, 3.0f)
        val gx = builder.worldToGrid(3.0f)
        val gz = builder.worldToGrid(3.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("Hit-occupied cell should have logOdds", lo)
        assertTrue("Hit-occupied logOdds should be positive", lo!! > 0f)
    }

    @Test
    fun `repeated markHitOccupied increases confidence`() {
        val wx = 5.0f; val wz = 5.0f
        builder.markHitOccupied(wx, wz)
        val gx = builder.worldToGrid(wx); val gz = builder.worldToGrid(wz)
        val lo1 = builder.logOdds[GridCell(gx, gz)]!!

        builder.markHitOccupied(wx, wz)
        val lo2 = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Repeated occupied marks should increase logOdds ($lo1 → $lo2)", lo2 > lo1)
    }

    @Test
    fun `markHitObstacle creates obstacle evidence`() {
        builder.markHitObstacle(4.0f, 4.0f)
        val gx = builder.worldToGrid(4.0f)
        val gz = builder.worldToGrid(4.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("Hit-obstacle cell should have logOdds", lo)
        assertTrue("Hit-obstacle logOdds should be positive", lo!! > 0f)
    }

    // ── Log-odds clamping ───────────────────────────────────────────────────

    @Test
    fun `logOdds clamped to max`() {
        // Mark many times to saturate
        for (i in 0..50) builder.markHitOccupied(1.0f, 1.0f)
        val gx = builder.worldToGrid(1.0f); val gz = builder.worldToGrid(1.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("LogOdds should be clamped (got $lo)", lo <= 4.0f)  // L_MAX = 3.5f
    }

    @Test
    fun `logOdds clamped to min`() {
        for (i in 0..50) builder.markHitFree(2.0f, 2.0f)
        val gx = builder.worldToGrid(2.0f); val gz = builder.worldToGrid(2.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("LogOdds should be clamped negative (got $lo)", lo >= -5.0f)  // L_MIN = -4.0f
    }

    // ── Plane integration ───────────────────────────────────────────────────

    @Test
    fun `integratePlane horizontal creates free cells`() {
        // Square horizontal plane at z=0..2, x=0..2
        val vertices = listOf(
            0.0f to 0.0f, 2.0f to 0.0f, 2.0f to 2.0f, 0.0f to 2.0f
        )
        val plane = PlaneSnapshot(PlaneType.HORIZONTAL_FREE, vertices, planeId = 1)
        builder.integratePlane(plane)
        // Should have some cells with negative logOdds (free)
        val freeCells = builder.logOdds.count { it.value < 0f }
        assertTrue("Horizontal plane should create free cells (got $freeCells)", freeCells > 0)
    }

    @Test
    fun `integratePlane vertical creates wall cells`() {
        // Vertical wall line from (0,5) to (4,5)
        val vertices = listOf(
            0.0f to 5.0f, 4.0f to 5.0f
        )
        val plane = PlaneSnapshot(PlaneType.VERTICAL_WALL, vertices, planeId = 2)
        builder.integratePlane(plane)
        // Should have some cells with positive logOdds (occupied/wall)
        val occCells = builder.logOdds.count { it.value > 0f }
        assertTrue("Vertical plane should create wall cells (got $occCells)", occCells > 0)
    }

    @Test
    fun `tiny plane is rejected`() {
        // Plane smaller than 0.25 m² should be rejected (FIX 3)
        val vertices = listOf(
            0.0f to 0.0f, 0.1f to 0.0f, 0.1f to 0.1f, 0.0f to 0.1f
        )
        val plane = PlaneSnapshot(PlaneType.HORIZONTAL_FREE, vertices, planeId = 3)
        val sizeBefore = builder.logOdds.size
        builder.integratePlane(plane)
        val sizeAfter = builder.logOdds.size
        assertEquals("Tiny plane should be rejected", sizeBefore, sizeAfter)
    }

    // ── Rebuild ─────────────────────────────────────────────────────────────

    @Test
    fun `rebuild with keyframes produces grid`() {
        val kf = Keyframe(
            timestamp = System.currentTimeMillis(),
            poseX = 2.0f, poseY = 1.5f, poseZ = 2.0f,
            headingRad = 0f, forwardX = 0f, forwardZ = 1f,
            planes = listOf(
                PlaneSnapshot(
                    PlaneType.HORIZONTAL_FREE,
                    listOf(0f to 0f, 4f to 0f, 4f to 4f, 0f to 4f),
                    planeId = 10
                )
            ),
            objectSightings = emptyList()
        )
        builder.rebuild(listOf(kf))
        assertTrue("Rebuild should produce grid cells", builder.grid.isNotEmpty())
    }

    @Test
    fun `rebuild applies decay to occupied cells`() {
        // Mark an occupied cell only once (low observation count → fast decay)
        builder.markHitOccupied(10.0f, 10.0f)
        val gx = builder.worldToGrid(10.0f); val gz = builder.worldToGrid(10.0f)
        val loBefore = builder.logOdds[GridCell(gx, gz)]!!

        // Multiple light rebuilds should decay it (no re-projection)
        repeat(15) { builder.lightRebuild(0, 0) }
        val loAfter = builder.logOdds[GridCell(gx, gz)]
        assertTrue("LogOdds should decay after repeated light rebuilds " +
            "(before=$loBefore, after=$loAfter)",
            loAfter == null || loAfter < loBefore)
    }

    // ── lightRebuild ────────────────────────────────────────────────────────

    @Test
    fun `lightRebuild does not crash on empty grid`() {
        builder.lightRebuild(0, 0)
        assertTrue("Grid should still be empty or minimal", builder.grid.size <= 1)
    }

    @Test
    fun `lightRebuild decays poorly observed cells`() {
        // Add a weakly-observed occupied cell
        builder.markHitOccupied(5.0f, 5.0f)
        val gx = builder.worldToGrid(5.0f); val gz = builder.worldToGrid(5.0f)
        val loBefore = builder.logOdds[GridCell(gx, gz)] ?: 0f

        // Multiple light rebuilds should decay it
        repeat(10) { builder.lightRebuild(0, 0) }
        val loAfter = builder.logOdds[GridCell(gx, gz)]
        assertTrue("Poorly observed cell should decay (before=$loBefore, after=$loAfter)",
            loAfter == null || loAfter < loBefore)
    }

    // ── Grid cell type constants ────────────────────────────────────────────

    @Test
    fun `cell type constants match expected values`() {
        assertEquals(0, MapBuilder.CELL_UNKNOWN)
        assertEquals(1, MapBuilder.CELL_FREE)
        assertEquals(2, MapBuilder.CELL_OBSTACLE)
        assertEquals(3, MapBuilder.CELL_WALL)
        assertEquals(4, MapBuilder.CELL_VISITED)
    }

    // ── observationCountSnapshot ────────────────────────────────────────────

    @Test
    fun `observation count increments on repeated marks`() {
        builder.markHitOccupied(3.0f, 3.0f)
        builder.markHitOccupied(3.0f, 3.0f)
        builder.markHitOccupied(3.0f, 3.0f)
        val counts = builder.observationCountSnapshot()
        val gx = builder.worldToGrid(3.0f); val gz = builder.worldToGrid(3.0f)
        val count = counts[GridCell(gx, gz)] ?: 0
        assertTrue("Observation count should be >= 3 (got $count)", count >= 3)
    }

    // ── Thread safety (basic smoke test) ────────────────────────────────────

    @Test
    fun `concurrent incremental updates do not crash`() {
        val threads = (0..4).map { threadIdx ->
            Thread {
                for (i in 0..100) {
                    val x = threadIdx * 2.0f + i * 0.1f
                    builder.incrementalUpdate(x, x, 0f, 0f, 1f)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertTrue("Grid should have cells after concurrent updates", builder.grid.isNotEmpty())
    }
}
