package com.ketan.slam

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Collects real-time performance metrics across all subsystems for
 * benchmarking and evaluation. Designed for BE project demonstration —
 * tracks latency, throughput, accuracy proxies, and resource usage.
 *
 * All methods are thread-safe. Call [snapshot] to get a frozen summary,
 * or [exportJson] to persist to disk.
 */
object PerformanceTracker {

    private const val TAG = "PerfTracker"
    private const val MAX_SAMPLES = 500  // rolling window per metric

    // ── Session timing ──────────────────────────────────────────────────────
    @Volatile var sessionStartMs = 0L; private set
    fun markSessionStart() { sessionStartMs = System.currentTimeMillis() }
    val sessionDurationSec: Float
        get() = if (sessionStartMs > 0) (System.currentTimeMillis() - sessionStartMs) / 1000f else 0f

    // ── Frame rate ──────────────────────────────────────────────────────────
    private val frameTimes = RollingSamples(MAX_SAMPLES)
    @Volatile private var lastFrameTs = 0L
    private val totalFrames = AtomicLong(0)

    fun tickFrame() {
        val now = System.currentTimeMillis()
        totalFrames.incrementAndGet()
        if (lastFrameTs > 0) frameTimes.add((now - lastFrameTs).toFloat())
        lastFrameTs = now
    }

    // ── YOLO inference ──────────────────────────────────────────────────────
    private val yoloLatencies = RollingSamples(MAX_SAMPLES)
    private val yoloDetectionCounts = RollingSamples(MAX_SAMPLES)
    private val totalYoloRuns = AtomicInteger(0)
    private val totalDetections = AtomicInteger(0)
    private val yoloConfidences = RollingSamples(MAX_SAMPLES)

    fun recordYoloInference(latencyMs: Long, detectionCount: Int, confidences: List<Float>) {
        yoloLatencies.add(latencyMs.toFloat())
        yoloDetectionCounts.add(detectionCount.toFloat())
        totalYoloRuns.incrementAndGet()
        totalDetections.addAndGet(detectionCount)
        confidences.forEach { yoloConfidences.add(it) }
    }

    // ── OCR inference ───────────────────────────────────────────────────────
    private val ocrLatencies = RollingSamples(MAX_SAMPLES)
    private val totalOcrRuns = AtomicInteger(0)
    private val totalTextDetections = AtomicInteger(0)

    fun recordOcrInference(latencyMs: Long, textCount: Int) {
        ocrLatencies.add(latencyMs.toFloat())
        totalOcrRuns.incrementAndGet()
        totalTextDetections.addAndGet(textCount)
    }

    // ── Map rebuild ─────────────────────────────────────────────────────────
    private val rebuildLatencies = RollingSamples(MAX_SAMPLES)
    private val lightRebuildLatencies = RollingSamples(MAX_SAMPLES)
    private val totalRebuilds = AtomicInteger(0)
    private val totalLightRebuilds = AtomicInteger(0)

    fun recordRebuild(latencyMs: Long) {
        rebuildLatencies.add(latencyMs.toFloat())
        totalRebuilds.incrementAndGet()
    }

    fun recordLightRebuild(latencyMs: Long) {
        lightRebuildLatencies.add(latencyMs.toFloat())
        totalLightRebuilds.incrementAndGet()
    }

    // ── Grid statistics (sampled periodically) ──────────────────────────────
    private val gridSizeSamples = RollingSamples(MAX_SAMPLES)
    private val freeCellSamples = RollingSamples(MAX_SAMPLES)
    private val wallCellSamples = RollingSamples(MAX_SAMPLES)
    private val obstacleCellSamples = RollingSamples(MAX_SAMPLES)
    private val visitedCellSamples = RollingSamples(MAX_SAMPLES)

    fun recordGridStats(totalCells: Int, free: Int, walls: Int, obstacles: Int, visited: Int) {
        gridSizeSamples.add(totalCells.toFloat())
        freeCellSamples.add(free.toFloat())
        wallCellSamples.add(walls.toFloat())
        obstacleCellSamples.add(obstacles.toFloat())
        visitedCellSamples.add(visited.toFloat())
    }

    // ── Path planning ───────────────────────────────────────────────────────
    private val pathPlanLatencies = RollingSamples(MAX_SAMPLES)
    private val pathLengths = RollingSamples(MAX_SAMPLES)
    private val totalPathPlans = AtomicInteger(0)
    private val failedPathPlans = AtomicInteger(0)

    fun recordPathPlan(latencyMs: Long, pathLength: Int, success: Boolean) {
        pathPlanLatencies.add(latencyMs.toFloat())
        if (success) pathLengths.add(pathLength.toFloat())
        totalPathPlans.incrementAndGet()
        if (!success) failedPathPlans.incrementAndGet()
    }

    // ── Drift / localization ────────────────────────────────────────────────
    private val driftSamples = RollingSamples(MAX_SAMPLES)
    private val totalDriftRebuilds = AtomicInteger(0)

    fun recordDrift(driftMetres: Float) { driftSamples.add(driftMetres) }
    fun recordDriftRebuild() { totalDriftRebuilds.incrementAndGet() }

    // ── Keyframes ───────────────────────────────────────────────────────────
    private val totalKeyframes = AtomicInteger(0)
    fun recordKeyframe() { totalKeyframes.incrementAndGet() }

    // ── Object tracking ─────────────────────────────────────────────────────
    private val objectCountSamples = RollingSamples(MAX_SAMPLES)
    @Volatile private var peakObjectCount = 0

    fun recordObjectCount(count: Int) {
        objectCountSamples.add(count.toFloat())
        if (count > peakObjectCount) peakObjectCount = count
    }

    // ── Navigation ──────────────────────────────────────────────────────────
    private val totalNavSessions = AtomicInteger(0)
    private val successfulNavSessions = AtomicInteger(0)
    private val navSessionDurations = RollingSamples(MAX_SAMPLES)  // seconds
    private val replanCounts = RollingSamples(MAX_SAMPLES)

    fun recordNavStart() { totalNavSessions.incrementAndGet() }
    fun recordNavArrival(durationSec: Float, replans: Int) {
        successfulNavSessions.incrementAndGet()
        navSessionDurations.add(durationSec)
        replanCounts.add(replans.toFloat())
    }

    // ── Memory (sampled periodically) ───────────────────────────────────────
    private val memoryUsageMb = RollingSamples(MAX_SAMPLES)

    fun recordMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f)
        memoryUsageMb.add(usedMb)
    }

    // ── Snapshot ────────────────────────────────────────────────────────────

    fun snapshot(): PerformanceSnapshot {
        val fps = frameTimes.let { s ->
            val avg = s.average()
            if (avg > 0f) 1000f / avg else 0f
        }
        return PerformanceSnapshot(
            sessionDurationSec = sessionDurationSec,
            totalFrames = totalFrames.get(),
            avgFps = fps,
            minFps = frameTimes.let { if (it.max() > 0f) 1000f / it.max() else 0f },
            maxFps = frameTimes.let { if (it.min() > 0f) 1000f / it.min() else 0f },

            yoloAvgMs = yoloLatencies.average(),
            yoloP95Ms = yoloLatencies.percentile(95f),
            yoloMaxMs = yoloLatencies.max(),
            totalYoloRuns = totalYoloRuns.get(),
            totalDetections = totalDetections.get(),
            avgConfidence = yoloConfidences.average(),
            avgDetectionsPerRun = if (totalYoloRuns.get() > 0)
                totalDetections.get().toFloat() / totalYoloRuns.get() else 0f,

            ocrAvgMs = ocrLatencies.average(),
            ocrP95Ms = ocrLatencies.percentile(95f),
            totalOcrRuns = totalOcrRuns.get(),
            totalTextDetections = totalTextDetections.get(),

            rebuildAvgMs = rebuildLatencies.average(),
            rebuildMaxMs = rebuildLatencies.max(),
            totalRebuilds = totalRebuilds.get(),
            lightRebuildAvgMs = lightRebuildLatencies.average(),
            totalLightRebuilds = totalLightRebuilds.get(),

            currentGridSize = gridSizeSamples.latest(),
            peakGridSize = gridSizeSamples.max(),
            currentFreeCells = freeCellSamples.latest(),
            currentWallCells = wallCellSamples.latest(),
            currentObstacleCells = obstacleCellSamples.latest(),
            currentVisitedCells = visitedCellSamples.latest(),

            pathPlanAvgMs = pathPlanLatencies.average(),
            pathPlanMaxMs = pathPlanLatencies.max(),
            avgPathLength = pathLengths.average(),
            totalPathPlans = totalPathPlans.get(),
            failedPathPlans = failedPathPlans.get(),

            avgDrift = driftSamples.average(),
            maxDrift = driftSamples.max(),
            totalDriftRebuilds = totalDriftRebuilds.get(),
            totalKeyframes = totalKeyframes.get(),

            currentObjectCount = objectCountSamples.latest(),
            peakObjectCount = peakObjectCount,

            totalNavSessions = totalNavSessions.get(),
            successfulNavSessions = successfulNavSessions.get(),
            avgNavDurationSec = navSessionDurations.average(),
            avgReplansPerSession = replanCounts.average(),

            avgMemoryMb = memoryUsageMb.average(),
            peakMemoryMb = memoryUsageMb.max()
        )
    }

    // ── Export to JSON ──────────────────────────────────────────────────────

    fun exportJson(context: Context): String? {
        return try {
            val snap = snapshot()
            val json = snap.toJson()
            val dir = File(context.filesDir, "performance_reports")
            if (!dir.exists()) dir.mkdirs()
            val ts = System.currentTimeMillis()
            val file = File(dir, "perf_${ts}.json")
            file.writeText(json.toString(2))
            Log.i(TAG, "Exported performance report: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    fun reset() {
        sessionStartMs = 0L
        lastFrameTs = 0L
        totalFrames.set(0)
        frameTimes.clear()
        yoloLatencies.clear(); yoloDetectionCounts.clear(); yoloConfidences.clear()
        totalYoloRuns.set(0); totalDetections.set(0)
        ocrLatencies.clear(); totalOcrRuns.set(0); totalTextDetections.set(0)
        rebuildLatencies.clear(); lightRebuildLatencies.clear()
        totalRebuilds.set(0); totalLightRebuilds.set(0)
        gridSizeSamples.clear(); freeCellSamples.clear(); wallCellSamples.clear()
        obstacleCellSamples.clear(); visitedCellSamples.clear()
        pathPlanLatencies.clear(); pathLengths.clear()
        totalPathPlans.set(0); failedPathPlans.set(0)
        driftSamples.clear(); totalDriftRebuilds.set(0)
        totalKeyframes.set(0)
        objectCountSamples.clear(); peakObjectCount = 0
        totalNavSessions.set(0); successfulNavSessions.set(0)
        navSessionDurations.clear(); replanCounts.clear()
        memoryUsageMb.clear()
    }
}

// ── Rolling sample buffer (thread-safe, lock-free) ──────────────────────────

class RollingSamples(private val maxSize: Int) {
    private val deque = ConcurrentLinkedDeque<Float>()
    @Volatile private var count = 0

    fun add(value: Float) {
        deque.addLast(value)
        if (++count > maxSize) { deque.pollFirst(); count-- }
    }

    fun clear() { deque.clear(); count = 0 }

    fun average(): Float {
        val list = deque.toList()
        return if (list.isEmpty()) 0f else list.sum() / list.size
    }

    fun min(): Float = deque.toList().minOrNull() ?: 0f
    fun max(): Float = deque.toList().maxOrNull() ?: 0f
    fun latest(): Float = deque.peekLast() ?: 0f
    fun stdDev(): Float {
        val list = deque.toList()
        if (list.size < 2) return 0f
        val avg = list.sum() / list.size
        val variance = list.sumOf { ((it - avg) * (it - avg)).toDouble() } / list.size
        return sqrt(variance).toFloat()
    }

    fun percentile(p: Float): Float {
        val sorted = deque.toList().sorted()
        if (sorted.isEmpty()) return 0f
        val idx = ((p / 100f) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    fun size(): Int = deque.size
    fun toList(): List<Float> = deque.toList()
}

// ── Snapshot data class ─────────────────────────────────────────────────────

data class PerformanceSnapshot(
    // Session
    val sessionDurationSec: Float,
    val totalFrames: Long,
    // Frame rate
    val avgFps: Float,
    val minFps: Float,
    val maxFps: Float,
    // YOLO
    val yoloAvgMs: Float,
    val yoloP95Ms: Float,
    val yoloMaxMs: Float,
    val totalYoloRuns: Int,
    val totalDetections: Int,
    val avgConfidence: Float,
    val avgDetectionsPerRun: Float,
    // OCR
    val ocrAvgMs: Float,
    val ocrP95Ms: Float,
    val totalOcrRuns: Int,
    val totalTextDetections: Int,
    // Map rebuild
    val rebuildAvgMs: Float,
    val rebuildMaxMs: Float,
    val totalRebuilds: Int,
    val lightRebuildAvgMs: Float,
    val totalLightRebuilds: Int,
    // Grid
    val currentGridSize: Float,
    val peakGridSize: Float,
    val currentFreeCells: Float,
    val currentWallCells: Float,
    val currentObstacleCells: Float,
    val currentVisitedCells: Float,
    // Path planning
    val pathPlanAvgMs: Float,
    val pathPlanMaxMs: Float,
    val avgPathLength: Float,
    val totalPathPlans: Int,
    val failedPathPlans: Int,
    // Localization
    val avgDrift: Float,
    val maxDrift: Float,
    val totalDriftRebuilds: Int,
    val totalKeyframes: Int,
    // Object tracking
    val currentObjectCount: Float,
    val peakObjectCount: Int,
    // Navigation
    val totalNavSessions: Int,
    val successfulNavSessions: Int,
    val avgNavDurationSec: Float,
    val avgReplansPerSession: Float,
    // Memory
    val avgMemoryMb: Float,
    val peakMemoryMb: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", System.currentTimeMillis())

        put("session", JSONObject().apply {
            put("durationSec", sessionDurationSec.f2())
            put("totalFrames", totalFrames)
        })

        put("frameRate", JSONObject().apply {
            put("avgFps", avgFps.f1())
            put("minFps", minFps.f1())
            put("maxFps", maxFps.f1())
        })

        put("yoloInference", JSONObject().apply {
            put("avgLatencyMs", yoloAvgMs.f1())
            put("p95LatencyMs", yoloP95Ms.f1())
            put("maxLatencyMs", yoloMaxMs.f1())
            put("totalRuns", totalYoloRuns)
            put("totalDetections", totalDetections)
            put("avgConfidence", avgConfidence.f3())
            put("avgDetectionsPerRun", avgDetectionsPerRun.f2())
        })

        put("ocrInference", JSONObject().apply {
            put("avgLatencyMs", ocrAvgMs.f1())
            put("p95LatencyMs", ocrP95Ms.f1())
            put("totalRuns", totalOcrRuns)
            put("totalTextDetections", totalTextDetections)
        })

        put("mapRebuilds", JSONObject().apply {
            put("fullRebuildAvgMs", rebuildAvgMs.f1())
            put("fullRebuildMaxMs", rebuildMaxMs.f1())
            put("totalFullRebuilds", totalRebuilds)
            put("lightRebuildAvgMs", lightRebuildAvgMs.f1())
            put("totalLightRebuilds", totalLightRebuilds)
        })

        put("gridStats", JSONObject().apply {
            put("currentSize", currentGridSize.toInt())
            put("peakSize", peakGridSize.toInt())
            put("freeCells", currentFreeCells.toInt())
            put("wallCells", currentWallCells.toInt())
            put("obstacleCells", currentObstacleCells.toInt())
            put("visitedCells", currentVisitedCells.toInt())
        })

        put("pathPlanning", JSONObject().apply {
            put("avgLatencyMs", pathPlanAvgMs.f1())
            put("maxLatencyMs", pathPlanMaxMs.f1())
            put("avgPathLengthCells", avgPathLength.f1())
            put("totalPlans", totalPathPlans)
            put("failedPlans", failedPathPlans)
            put("successRate", if (totalPathPlans > 0)
                ((totalPathPlans - failedPathPlans).toFloat() / totalPathPlans * 100).f1()
            else "N/A")
        })

        put("localization", JSONObject().apply {
            put("avgDriftM", avgDrift.f3())
            put("maxDriftM", maxDrift.f3())
            put("totalDriftRebuilds", totalDriftRebuilds)
            put("totalKeyframes", totalKeyframes)
        })

        put("objectTracking", JSONObject().apply {
            put("currentCount", currentObjectCount.toInt())
            put("peakCount", peakObjectCount)
        })

        put("navigation", JSONObject().apply {
            put("totalSessions", totalNavSessions)
            put("successfulSessions", successfulNavSessions)
            put("successRate", if (totalNavSessions > 0)
                (successfulNavSessions.toFloat() / totalNavSessions * 100).f1()
            else "N/A")
            put("avgDurationSec", avgNavDurationSec.f1())
            put("avgReplansPerSession", avgReplansPerSession.f1())
        })

        put("memory", JSONObject().apply {
            put("avgUsageMb", avgMemoryMb.f1())
            put("peakUsageMb", peakMemoryMb.f1())
        })
    }

    fun toFlatMap(): Map<String, Any> = mapOf(
        "sessionDurationSec" to sessionDurationSec,
        "totalFrames" to totalFrames,
        "avgFps" to avgFps, "minFps" to minFps, "maxFps" to maxFps,
        "yoloAvgMs" to yoloAvgMs, "yoloP95Ms" to yoloP95Ms, "yoloMaxMs" to yoloMaxMs,
        "totalYoloRuns" to totalYoloRuns, "totalDetections" to totalDetections,
        "avgConfidence" to avgConfidence, "avgDetectionsPerRun" to avgDetectionsPerRun,
        "ocrAvgMs" to ocrAvgMs, "ocrP95Ms" to ocrP95Ms,
        "totalOcrRuns" to totalOcrRuns, "totalTextDetections" to totalTextDetections,
        "rebuildAvgMs" to rebuildAvgMs, "rebuildMaxMs" to rebuildMaxMs,
        "totalRebuilds" to totalRebuilds,
        "lightRebuildAvgMs" to lightRebuildAvgMs, "totalLightRebuilds" to totalLightRebuilds,
        "currentGridSize" to currentGridSize, "peakGridSize" to peakGridSize,
        "currentFreeCells" to currentFreeCells, "currentWallCells" to currentWallCells,
        "currentObstacleCells" to currentObstacleCells, "currentVisitedCells" to currentVisitedCells,
        "pathPlanAvgMs" to pathPlanAvgMs, "pathPlanMaxMs" to pathPlanMaxMs,
        "avgPathLength" to avgPathLength,
        "totalPathPlans" to totalPathPlans, "failedPathPlans" to failedPathPlans,
        "avgDrift" to avgDrift, "maxDrift" to maxDrift,
        "totalDriftRebuilds" to totalDriftRebuilds, "totalKeyframes" to totalKeyframes,
        "currentObjectCount" to currentObjectCount, "peakObjectCount" to peakObjectCount,
        "totalNavSessions" to totalNavSessions, "successfulNavSessions" to successfulNavSessions,
        "avgNavDurationSec" to avgNavDurationSec, "avgReplansPerSession" to avgReplansPerSession,
        "avgMemoryMb" to avgMemoryMb, "peakMemoryMb" to peakMemoryMb
    )

    private fun Float.f1() = "%.1f".format(this)
    private fun Float.f2() = "%.2f".format(this)
    private fun Float.f3() = "%.3f".format(this)
}
