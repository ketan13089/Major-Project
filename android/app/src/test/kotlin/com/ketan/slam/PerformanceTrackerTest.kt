package com.ketan.slam

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PerformanceTracker — rolling samples, snapshot
 * accuracy, and metric recording.
 */
class PerformanceTrackerTest {

    @Before
    fun setUp() {
        PerformanceTracker.reset()
    }

    // ── RollingSamples ──────────────────────────────────────────────────────

    @Test
    fun `rolling samples average`() {
        val s = RollingSamples(100)
        s.add(10f); s.add(20f); s.add(30f)
        assertEquals(20f, s.average(), 0.01f)
    }

    @Test
    fun `rolling samples min max`() {
        val s = RollingSamples(100)
        s.add(5f); s.add(15f); s.add(10f)
        assertEquals(5f, s.min(), 0.01f)
        assertEquals(15f, s.max(), 0.01f)
    }

    @Test
    fun `rolling samples latest`() {
        val s = RollingSamples(100)
        s.add(1f); s.add(2f); s.add(3f)
        assertEquals(3f, s.latest(), 0.01f)
    }

    @Test
    fun `rolling samples percentile`() {
        val s = RollingSamples(100)
        for (i in 1..100) s.add(i.toFloat())
        assertEquals(95f, s.percentile(95f), 1f)
    }

    @Test
    fun `rolling samples evicts old entries`() {
        val s = RollingSamples(5)
        for (i in 1..10) s.add(i.toFloat())
        assertEquals(5, s.size())
        // Oldest (1-5) should be evicted, remaining: 6,7,8,9,10
        assertEquals(8f, s.average(), 0.1f)
    }

    @Test
    fun `rolling samples stdDev`() {
        val s = RollingSamples(100)
        s.add(2f); s.add(4f); s.add(4f); s.add(4f); s.add(5f); s.add(5f); s.add(7f); s.add(9f)
        assertTrue("StdDev should be > 0", s.stdDev() > 0f)
    }

    @Test
    fun `empty rolling samples return zero`() {
        val s = RollingSamples(100)
        assertEquals(0f, s.average(), 0f)
        assertEquals(0f, s.min(), 0f)
        assertEquals(0f, s.max(), 0f)
        assertEquals(0f, s.latest(), 0f)
        assertEquals(0f, s.percentile(95f), 0f)
        assertEquals(0f, s.stdDev(), 0f)
    }

    // ── Session timing ──────────────────────────────────────────────────────

    @Test
    fun `session duration tracks elapsed time`() {
        PerformanceTracker.markSessionStart()
        Thread.sleep(100)
        assertTrue("Duration should be > 0", PerformanceTracker.sessionDurationSec > 0f)
    }

    // ── YOLO metrics ────────────────────────────────────────────────────────

    @Test
    fun `YOLO inference recording`() {
        PerformanceTracker.recordYoloInference(50L, 3, listOf(0.8f, 0.6f, 0.9f))
        PerformanceTracker.recordYoloInference(70L, 2, listOf(0.7f, 0.5f))
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalYoloRuns)
        assertEquals(5, snap.totalDetections)
        assertEquals(60f, snap.yoloAvgMs, 1f)
        assertTrue("Avg confidence should be > 0", snap.avgConfidence > 0f)
    }

    // ── OCR metrics ─────────────────────────────────────────────────────────

    @Test
    fun `OCR inference recording`() {
        PerformanceTracker.recordOcrInference(200L, 2)
        PerformanceTracker.recordOcrInference(300L, 1)
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalOcrRuns)
        assertEquals(3, snap.totalTextDetections)
        assertEquals(250f, snap.ocrAvgMs, 1f)
    }

    // ── Rebuild metrics ─────────────────────────────────────────────────────

    @Test
    fun `rebuild recording`() {
        PerformanceTracker.recordRebuild(150L)
        PerformanceTracker.recordRebuild(200L)
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalRebuilds)
        assertEquals(175f, snap.rebuildAvgMs, 1f)
        assertEquals(200f, snap.rebuildMaxMs, 1f)
    }

    @Test
    fun `light rebuild recording`() {
        PerformanceTracker.recordLightRebuild(5L)
        PerformanceTracker.recordLightRebuild(10L)
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalLightRebuilds)
        assertEquals(7.5f, snap.lightRebuildAvgMs, 0.5f)
    }

    // ── Grid stats ──────────────────────────────────────────────────────────

    @Test
    fun `grid stats recording`() {
        PerformanceTracker.recordGridStats(1000, 600, 200, 50, 150)
        val snap = PerformanceTracker.snapshot()
        assertEquals(1000f, snap.currentGridSize, 1f)
        assertEquals(600f, snap.currentFreeCells, 1f)
        assertEquals(200f, snap.currentWallCells, 1f)
        assertEquals(50f, snap.currentObstacleCells, 1f)
        assertEquals(150f, snap.currentVisitedCells, 1f)
    }

    // ── Path planning ───────────────────────────────────────────────────────

    @Test
    fun `path plan recording`() {
        PerformanceTracker.recordPathPlan(15L, 42, true)
        PerformanceTracker.recordPathPlan(25L, 0, false)
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalPathPlans)
        assertEquals(1, snap.failedPathPlans)
        assertEquals(20f, snap.pathPlanAvgMs, 1f)
        assertEquals(42f, snap.avgPathLength, 0.1f)  // only 1 successful
    }

    // ── Drift ───────────────────────────────────────────────────────────────

    @Test
    fun `drift recording`() {
        PerformanceTracker.recordDrift(0.01f)
        PerformanceTracker.recordDrift(0.03f)
        PerformanceTracker.recordDrift(0.05f)
        val snap = PerformanceTracker.snapshot()
        assertEquals(0.03f, snap.avgDrift, 0.005f)
        assertEquals(0.05f, snap.maxDrift, 0.001f)
    }

    @Test
    fun `drift rebuild count`() {
        PerformanceTracker.recordDriftRebuild()
        PerformanceTracker.recordDriftRebuild()
        assertEquals(2, PerformanceTracker.snapshot().totalDriftRebuilds)
    }

    // ── Object tracking ─────────────────────────────────────────────────────

    @Test
    fun `object count tracking`() {
        PerformanceTracker.recordObjectCount(5)
        PerformanceTracker.recordObjectCount(10)
        PerformanceTracker.recordObjectCount(7)
        val snap = PerformanceTracker.snapshot()
        assertEquals(7f, snap.currentObjectCount, 0.1f)  // latest
        assertEquals(10, snap.peakObjectCount)
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    @Test
    fun `navigation session tracking`() {
        PerformanceTracker.recordNavStart()
        PerformanceTracker.recordNavStart()
        PerformanceTracker.recordNavArrival(30f, 2)
        val snap = PerformanceTracker.snapshot()
        assertEquals(2, snap.totalNavSessions)
        assertEquals(1, snap.successfulNavSessions)
        assertEquals(30f, snap.avgNavDurationSec, 0.1f)
        assertEquals(2f, snap.avgReplansPerSession, 0.1f)
    }

    // ── Memory ──────────────────────────────────────────────────────────────

    @Test
    fun `memory usage recording`() {
        PerformanceTracker.recordMemoryUsage()
        val snap = PerformanceTracker.snapshot()
        assertTrue("Memory should be > 0 MB", snap.avgMemoryMb > 0f)
    }

    // ── Snapshot JSON export ────────────────────────────────────────────────

    @Test
    fun `snapshot toFlatMap contains all metric sections`() {
        PerformanceTracker.markSessionStart()
        PerformanceTracker.recordYoloInference(50L, 2, listOf(0.8f))
        PerformanceTracker.recordGridStats(500, 300, 100, 20, 80)
        val map = PerformanceTracker.snapshot().toFlatMap()
        // Session
        assertTrue(map.containsKey("sessionDurationSec"))
        assertTrue(map.containsKey("totalFrames"))
        // Frame rate
        assertTrue(map.containsKey("avgFps"))
        assertTrue(map.containsKey("minFps"))
        assertTrue(map.containsKey("maxFps"))
        // YOLO
        assertTrue(map.containsKey("yoloAvgMs"))
        assertTrue(map.containsKey("yoloP95Ms"))
        assertTrue(map.containsKey("totalYoloRuns"))
        assertTrue(map.containsKey("avgConfidence"))
        // OCR
        assertTrue(map.containsKey("ocrAvgMs"))
        assertTrue(map.containsKey("totalOcrRuns"))
        // Rebuilds
        assertTrue(map.containsKey("rebuildAvgMs"))
        assertTrue(map.containsKey("totalRebuilds"))
        assertTrue(map.containsKey("lightRebuildAvgMs"))
        // Grid
        assertTrue(map.containsKey("currentGridSize"))
        assertTrue(map.containsKey("peakGridSize"))
        // Path
        assertTrue(map.containsKey("pathPlanAvgMs"))
        assertTrue(map.containsKey("totalPathPlans"))
        // Localization
        assertTrue(map.containsKey("avgDrift"))
        assertTrue(map.containsKey("maxDrift"))
        // Navigation
        assertTrue(map.containsKey("totalNavSessions"))
        // Memory
        assertTrue(map.containsKey("avgMemoryMb"))
        assertTrue(map.containsKey("peakMemoryMb"))
    }

    @Test
    fun `snapshot toFlatMap contains expected keys`() {
        val map = PerformanceTracker.snapshot().toFlatMap()
        assertTrue(map.containsKey("avgFps"))
        assertTrue(map.containsKey("yoloAvgMs"))
        assertTrue(map.containsKey("ocrAvgMs"))
        assertTrue(map.containsKey("totalRebuilds"))
        assertTrue(map.containsKey("currentGridSize"))
        assertTrue(map.containsKey("avgDrift"))
        assertTrue(map.containsKey("totalPathPlans"))
        assertTrue(map.containsKey("peakObjectCount"))
        assertTrue(map.containsKey("totalNavSessions"))
        assertTrue(map.containsKey("avgMemoryMb"))
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all metrics`() {
        PerformanceTracker.recordYoloInference(50L, 2, listOf(0.8f))
        PerformanceTracker.recordPathPlan(10L, 5, true)
        PerformanceTracker.reset()
        val snap = PerformanceTracker.snapshot()
        assertEquals(0, snap.totalYoloRuns)
        assertEquals(0, snap.totalPathPlans)
        assertEquals(0f, snap.avgFps, 0f)
    }
}
