package com.ketan.slam

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Orchestrates the Gemma semantic correction pipeline:
 * prompt building → API call → response parsing → fusion into MapBuilder/SemanticMapManager.
 *
 * Runs entirely on its own single-thread executor. Thread-safe for external callers.
 */
class SemanticCorrectionEngine(
    private val mapBuilder: MapBuilder,
    private val semanticMap: SemanticMapManager,
    private val res: Float
) {
    companion object {
        private const val TAG = "SemanticAI"
        private const val GRID_CROP_RADIUS = 7  // 15×15 window around user
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var lastCorrectionMs = 0L

    // Circuit breaker state
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile private var circuitOpenUntilMs = 0L
    @Volatile private var extendedBackoff = false

    // Hysteresis: per-cell confirmation counters for structural changes
    private val confirmationCounters = ConcurrentHashMap<GridCell, Int>()
    @Volatile private var lastDecayMs = System.currentTimeMillis()

    // Deduplication: last grid crop hash
    @Volatile private var lastCropHash = 0

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Try to submit a correction task. Returns immediately.
     * Skips if: disabled, in-flight, throttled, circuit open, or tracking lost.
     */
    fun maybeSubmitCorrection(userGX: Int, userGZ: Int, headingRad: Float) {
        if (!SemanticCorrectionConfig.AI_SEMANTIC_CORRECTOR_ENABLED) return
        if (SemanticCorrectionConfig.apiKey.isBlank()) return

        val now = System.currentTimeMillis()
        if (now - lastCorrectionMs < SemanticCorrectionConfig.AI_SEMANTIC_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return

        // Circuit breaker check
        if (now < circuitOpenUntilMs) {
            inFlight.set(false)
            return
        }

        // Decay hysteresis counters periodically
        if (now - lastDecayMs >= SemanticCorrectionConfig.HYSTERESIS_DECAY_INTERVAL_MS) {
            lastDecayMs = now
            decayCounters()
        }

        // Snapshot state on calling thread (fast)
        val cropSnapshot = snapshotGridCrop(userGX, userGZ)
        val objectSnapshot = semanticMap.getAllObjects()
        val heading = headingRad

        // Deduplication check
        val cropHash = cropSnapshot.hashCode()
        if (cropHash == lastCropHash) {
            inFlight.set(false)
            return
        }

        lastCorrectionMs = now

        executor.execute {
            try {
                val prompt = buildPrompt(cropSnapshot, objectSnapshot, userGX, userGZ, heading)
                val responseJson = callApi(prompt)
                if (responseJson != null) {
                    val correction = parseResponse(responseJson, userGX, userGZ)
                    if (correction != SemanticCorrectionResponse.EMPTY) {
                        applyCorrections(correction)
                        lastCropHash = cropHash
                    }
                    consecutiveFailures.set(0)
                    extendedBackoff = false
                }
            } catch (e: Exception) {
                println("$TAG: correction failed: ${e.message}")
                handleFailure()
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** Shut down the executor. Call from Activity.onDestroy(). */
    fun shutdown() {
        executor.shutdownNow()
    }

    // ── Prompt Building ─────────────────────────────────────────────────────

    private data class CropCell(val dx: Int, val dz: Int, val type: Byte)

    private fun snapshotGridCrop(centerGX: Int, centerGZ: Int): List<CropCell> {
        val cells = mutableListOf<CropCell>()
        for (dz in -GRID_CROP_RADIUS..GRID_CROP_RADIUS) {
            for (dx in -GRID_CROP_RADIUS..GRID_CROP_RADIUS) {
                val cell = GridCell(centerGX + dx, centerGZ + dz)
                val type = mapBuilder.grid[cell] ?: MapBuilder.CELL_UNKNOWN.toByte()
                cells.add(CropCell(dx, dz, type))
            }
        }
        return cells
    }

    private fun buildPrompt(
        crop: List<CropCell>,
        objects: List<SemanticObject>,
        userGX: Int, userGZ: Int,
        headingRad: Float
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Map grid (15x15, user at center, . = free, # = wall, O = obstacle, ? = unknown, V = visited):")

        val size = GRID_CROP_RADIUS * 2 + 1
        val gridMap = HashMap<Pair<Int, Int>, Byte>()
        for (c in crop) gridMap[c.dx to c.dz] = c.type

        for (dz in -GRID_CROP_RADIUS..GRID_CROP_RADIUS) {
            for (dx in -GRID_CROP_RADIUS..GRID_CROP_RADIUS) {
                val type = gridMap[dx to dz] ?: MapBuilder.CELL_UNKNOWN.toByte()
                sb.append(when (type.toInt()) {
                    MapBuilder.CELL_FREE     -> '.'
                    MapBuilder.CELL_WALL     -> '#'
                    MapBuilder.CELL_OBSTACLE -> 'O'
                    MapBuilder.CELL_VISITED  -> 'V'
                    else                     -> '?'
                })
            }
            sb.appendLine()
        }

        sb.appendLine("\nUser position: grid center, heading: ${"%.1f".format(Math.toDegrees(headingRad.toDouble()))} deg")

        if (objects.isNotEmpty()) {
            sb.appendLine("\nDetected objects:")
            // Only include objects near the crop
            val nearbyObjects = objects.filter { obj ->
                val ogx = mapBuilder.worldToGrid(obj.position.x)
                val ogz = mapBuilder.worldToGrid(obj.position.z)
                val dx = ogx - userGX
                val dz = ogz - userGZ
                dx in -GRID_CROP_RADIUS..GRID_CROP_RADIUS && dz in -GRID_CROP_RADIUS..GRID_CROP_RADIUS
            }.take(15)  // limit to avoid token bloat
            for (obj in nearbyObjects) {
                val ogx = mapBuilder.worldToGrid(obj.position.x) - userGX
                val ogz = mapBuilder.worldToGrid(obj.position.z) - userGZ
                val aff = ObjectAffordance.forType(obj.type).name
                sb.appendLine("  - ${obj.type.name} at ($ogx,$ogz) conf=${"%.2f".format(obj.confidence)} affordance=$aff")
            }
        }

        sb.appendLine("\nAnalyze the grid and objects. Suggest corrections to improve map realism.")
        sb.appendLine("Focus on: misclassified cells, missing doorways, false obstacle detections, wall continuity.")
        sb.appendLine("Be conservative — only suggest changes with high confidence.")
        return sb.toString()
    }

    // ── API Call (model fallback + exponential backoff retries) ────────────

    /**
     * Tries each model in [SemanticCorrectionConfig.AI_MODELS] in order.
     * For each model, retries up to [SemanticCorrectionConfig.MAX_RETRIES] times
     * with exponential backoff + jitter on 429/5xx.
     * Falls through to the next model when all retries for one model are exhausted.
     * Only throws after every model has been tried.
     */
    private fun callApi(userPrompt: String): String? {
        val models = SemanticCorrectionConfig.AI_MODELS
        val maxRetries = SemanticCorrectionConfig.MAX_RETRIES

        for ((modelIdx, model) in models.withIndex()) {
            val requestBody = buildRequestBody(userPrompt, model)

            for (attempt in 1..maxRetries + 1) {
                val conn = (URL(SemanticCorrectionConfig.AI_ENDPOINT_URL).openConnection() as HttpURLConnection)
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer ${SemanticCorrectionConfig.apiKey}")
                    conn.setRequestProperty("HTTP-Referer", "com.ketan.slam")
                    conn.connectTimeout = SemanticCorrectionConfig.AI_SEMANTIC_TIMEOUT_MS
                    conn.readTimeout = SemanticCorrectionConfig.AI_SEMANTIC_TIMEOUT_MS
                    conn.doOutput = true

                    OutputStreamWriter(conn.outputStream).use { it.write(requestBody) }

                    val responseCode = conn.responseCode

                    if (responseCode == 200) {
                        val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                        val responseJson = JSONObject(response)
                        val choices = responseJson.optJSONArray("choices") ?: return null
                        if (choices.length() == 0) return null
                        val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
                        println("$TAG: API success using $model (attempt $attempt)")
                        return message.optString("content", null)
                    }

                    // Read error body for logging
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.readText() ?: ""
                    } catch (_: Exception) { "" }

                    val isRetryable = responseCode == 429 || responseCode in 500..599
                    if (isRetryable && attempt <= maxRetries) {
                        val retryAfterSec = conn.getHeaderField("Retry-After")?.toLongOrNull()
                        val backoffMs = if (retryAfterSec != null && retryAfterSec > 0) {
                            retryAfterSec * 1000
                        } else {
                            computeBackoff(attempt)
                        }
                        println("$TAG: [$model] HTTP $responseCode attempt $attempt/${maxRetries + 1} — retrying in ${backoffMs}ms")
                        Thread.sleep(backoffMs)
                        continue
                    }

                    // Non-retryable or last attempt for this model
                    if (isRetryable && modelIdx < models.size - 1) {
                        println("$TAG: [$model] exhausted retries (HTTP $responseCode) — falling back to next model")
                        break  // try next model
                    }

                    println("$TAG: API error $responseCode on $model: $errorBody")
                    throw RuntimeException("API returned $responseCode")
                } catch (e: RuntimeException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt <= maxRetries) {
                        val backoffMs = computeBackoff(attempt)
                        println("$TAG: [$model] network error attempt $attempt/${maxRetries + 1} (${e.message}) — retrying in ${backoffMs}ms")
                        Thread.sleep(backoffMs)
                        continue
                    }
                    if (modelIdx < models.size - 1) {
                        println("$TAG: [$model] network errors exhausted — falling back to next model")
                        break
                    }
                    throw RuntimeException("All models failed. Last error: ${e.message}")
                } finally {
                    conn.disconnect()
                }
            }
        }
        return null
    }

    /** Exponential backoff: base * 2^(attempt-1) + random jitter up to 2s. */
    private fun computeBackoff(attempt: Int): Long {
        val exponential = SemanticCorrectionConfig.RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
        val capped = min(exponential, SemanticCorrectionConfig.RETRY_MAX_DELAY_MS)
        val jitter = Random.nextLong(0, 2000)
        return capped + jitter
    }

    private fun buildRequestBody(userPrompt: String, model: String): String {
        return JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 1024)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }.toString()
    }

    // ── Response Parsing ────────────────────────────────────────────────────

    fun parseResponse(jsonStr: String, userGX: Int, userGZ: Int): SemanticCorrectionResponse {
        return try {
            // Strip markdown code fences if present
            val cleaned = jsonStr.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val json = JSONObject(cleaned)
            val globalConf = json.optDouble("global_confidence", 0.0).toFloat()
            if (globalConf < SemanticCorrectionConfig.MIN_GLOBAL_CONFIDENCE) {
                println("$TAG: low global confidence $globalConf, ignoring")
                return SemanticCorrectionResponse.EMPTY
            }

            val cellUpdates = parseCellUpdates(json, userGX, userGZ)
            val doorways = parseDoorways(json, userGX, userGZ)
            val objectUpdates = parseObjectUpdates(json, userGX, userGZ)

            SemanticCorrectionResponse(cellUpdates, doorways, objectUpdates, globalConf)
        } catch (e: Exception) {
            println("$TAG: parse error: ${e.message}")
            SemanticCorrectionResponse.EMPTY
        }
    }

    private fun parseCellUpdates(json: JSONObject, userGX: Int, userGZ: Int): List<SemanticCellUpdate> {
        val arr = json.optJSONArray("cell_updates") ?: return emptyList()
        val results = mutableListOf<SemanticCellUpdate>()
        for (i in 0 until arr.length().coerceAtMost(SemanticCorrectionConfig.MAX_CELL_UPDATES)) {
            try {
                val obj = arr.getJSONObject(i)
                val conf = obj.optDouble("confidence", 0.0).toFloat()
                if (conf < SemanticCorrectionConfig.MIN_CELL_CONFIDENCE) continue
                val cellClass = CellClass.fromString(obj.optString("class", "")) ?: continue
                // Grid coordinates are relative to crop center (user position)
                val gx = obj.getInt("gridX") + userGX
                val gz = obj.getInt("gridZ") + userGZ
                results.add(SemanticCellUpdate(gx, gz, cellClass, conf))
            } catch (_: Exception) { continue }
        }
        return results
    }

    private fun parseDoorways(json: JSONObject, userGX: Int, userGZ: Int): List<SemanticDoorway> {
        val arr = json.optJSONArray("doorways") ?: return emptyList()
        val results = mutableListOf<SemanticDoorway>()
        for (i in 0 until arr.length().coerceAtMost(SemanticCorrectionConfig.MAX_DOORWAYS)) {
            try {
                val obj = arr.getJSONObject(i)
                val conf = obj.optDouble("confidence", 0.0).toFloat()
                if (conf < SemanticCorrectionConfig.MIN_CELL_CONFIDENCE) continue
                val cx = obj.getInt("centerX") + userGX
                val cz = obj.getInt("centerZ") + userGZ
                val orient = obj.optDouble("orientationDeg", 0.0).toFloat()
                val width = obj.optInt("widthCells", 3).coerceIn(1, 6)
                results.add(SemanticDoorway(cx, cz, orient, width, conf))
            } catch (_: Exception) { continue }
        }
        return results
    }

    private fun parseObjectUpdates(json: JSONObject, userGX: Int, userGZ: Int): List<SemanticObjectUpdate> {
        val arr = json.optJSONArray("object_updates") ?: return emptyList()
        val results = mutableListOf<SemanticObjectUpdate>()
        for (i in 0 until arr.length().coerceAtMost(SemanticCorrectionConfig.MAX_OBJECT_UPDATES)) {
            try {
                val obj = arr.getJSONObject(i)
                val conf = obj.optDouble("confidence", 0.0).toFloat()
                if (conf < SemanticCorrectionConfig.MIN_OBJECT_CONFIDENCE) continue
                val action = ObjectAction.fromString(obj.optString("action", "")) ?: continue
                val label = obj.optString("label", "")
                if (label.isBlank()) continue
                val id = obj.optString("id", null)
                val gx = obj.optInt("gridX", 0) + userGX
                val gz = obj.optInt("gridZ", 0) + userGZ
                val affStr = obj.optString("affordance", "")
                val affordance = try { ObjectAffordance.valueOf(affStr.uppercase()) } catch (_: Exception) { null }
                results.add(SemanticObjectUpdate(id, action, label, conf, gx, gz, affordance))
            } catch (_: Exception) { continue }
        }
        return results
    }

    // ── Fusion (apply corrections to map/objects) ───────────────────────────

    private fun applyCorrections(response: SemanticCorrectionResponse) {
        // Apply cell updates
        for (update in response.cellUpdates) {
            val cell = GridCell(update.gridX, update.gridZ)
            val counter = incrementCounter(cell, update.cellClass.name)
            val strength = counterToStrength(counter)
            when (update.cellClass) {
                CellClass.FLOOR -> mapBuilder.applySemanticFloorPrior(
                    update.gridX, update.gridZ, update.confidence * strength)
                CellClass.WALL -> mapBuilder.applySemanticWallPrior(
                    update.gridX, update.gridZ, update.confidence * strength)
                CellClass.OBSTACLE -> { /* obstacles handled by existing pipeline */ }
                CellClass.UNKNOWN -> { /* no-op */ }
            }
        }

        // Apply doorways with hysteresis
        for (door in response.doorways) {
            val cell = GridCell(door.centerX, door.centerZ)
            val counter = incrementCounter(cell, "DOOR")
            if (counter >= SemanticCorrectionConfig.HYSTERESIS_FULL_THRESHOLD) {
                mapBuilder.applySemanticDoorPrior(
                    door.centerX, door.centerZ, door.orientationDeg,
                    door.widthCells, door.confidence)
            }
        }

        // Apply object updates
        for (update in response.objectUpdates) {
            applyObjectUpdate(update)
        }

        // Trigger a map rebuild to reflect changes
        println("$TAG: applied ${response.cellUpdates.size} cell, " +
                "${response.doorways.size} door, ${response.objectUpdates.size} object corrections")
    }

    private fun applyObjectUpdate(update: SemanticObjectUpdate) {
        when (update.action) {
            ObjectAction.CONFIRM -> {
                // Boost confidence of existing object
                if (update.id != null) {
                    val existing = semanticMap.getAllObjects().firstOrNull { it.id == update.id }
                    if (existing != null) {
                        semanticMap.updateObject(existing.copy(
                            confidence = maxOf(existing.confidence, update.confidence),
                            lastSeen = System.currentTimeMillis()
                        ))
                    }
                }
            }
            ObjectAction.SUPPRESS -> {
                // Mark for faster removal by reducing lastSeen
                if (update.id != null) {
                    val existing = semanticMap.getAllObjects().firstOrNull { it.id == update.id }
                    if (existing != null && existing.confidence < 0.6f) {
                        // Only suppress low-confidence objects — don't override strong local detections
                        semanticMap.updateObject(existing.copy(
                            confidence = existing.confidence * 0.5f,
                            lastSeen = existing.lastSeen - 15_000L  // age it 15s
                        ))
                    }
                }
            }
            ObjectAction.RELABEL -> {
                val newType = ObjectType.fromLabel(update.label)
                if (newType != ObjectType.UNKNOWN && update.id != null) {
                    val existing = semanticMap.getAllObjects().firstOrNull { it.id == update.id }
                    if (existing != null) {
                        semanticMap.updateObject(existing.copy(
                            type = newType,
                            category = update.label.lowercase(),
                            lastSeen = System.currentTimeMillis()
                        ))
                    }
                }
            }
            ObjectAction.ADD -> {
                // Add a new object suggested by AI — with lower initial confidence
                val type = ObjectType.fromLabel(update.label)
                if (type != ObjectType.UNKNOWN) {
                    val worldX = mapBuilder.gridToWorld(update.gridX).toFloat()
                    val worldZ = mapBuilder.gridToWorld(update.gridZ).toFloat()
                    val now = System.currentTimeMillis()
                    semanticMap.addObject(SemanticObject(
                        id = "ai_${update.label}_${update.gridX}_${update.gridZ}",
                        type = type,
                        category = update.label.lowercase(),
                        position = Point3D(worldX, 0f, worldZ),
                        boundingBox = BoundingBox2D(0f, 0f, 0f, 0f),
                        confidence = update.confidence * 0.8f,  // discount AI-only detections
                        firstSeen = now,
                        lastSeen = now,
                        observations = 1,
                        localizationMethod = "ai_semantic"
                    ))
                }
            }
        }
    }

    // ── Hysteresis Counters ─────────────────────────────────────────────────

    /** Key encodes cell + suggestion type for independent tracking. */
    private fun counterKey(cell: GridCell, type: String) = GridCell(cell.x * 1000 + type.hashCode(), cell.z)

    private fun incrementCounter(cell: GridCell, type: String): Int {
        val key = counterKey(cell, type)
        val newVal = (confirmationCounters.getOrDefault(key, 0)) + 1
        confirmationCounters[key] = newVal
        return newVal
    }

    private fun counterToStrength(counter: Int): Float = when {
        counter >= SemanticCorrectionConfig.HYSTERESIS_FULL_THRESHOLD -> 1.0f
        counter >= SemanticCorrectionConfig.HYSTERESIS_APPLY_THRESHOLD -> 0.5f
        else -> 0.0f
    }

    private fun decayCounters() {
        val toRemove = mutableListOf<GridCell>()
        for ((key, count) in confirmationCounters) {
            val newCount = count - 1
            if (newCount <= 0) toRemove.add(key)
            else confirmationCounters[key] = newCount
        }
        for (key in toRemove) confirmationCounters.remove(key)
    }

    // ── Circuit Breaker ─────────────────────────────────────────────────────

    private fun handleFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= SemanticCorrectionConfig.CIRCUIT_BREAKER_THRESHOLD) {
            val backoff = if (extendedBackoff)
                SemanticCorrectionConfig.CIRCUIT_BREAKER_EXTENDED_BACKOFF_MS
            else
                SemanticCorrectionConfig.CIRCUIT_BREAKER_BACKOFF_MS
            circuitOpenUntilMs = System.currentTimeMillis() + backoff
            extendedBackoff = !extendedBackoff  // alternate between normal and extended
            println("$TAG: circuit breaker open for ${backoff}ms (failures=$failures)")
        }
    }

    // ── System Prompt ───────────────────────────────────────────────────────

    private val SYSTEM_PROMPT = """
You are an indoor map analysis assistant. Given an occupancy grid snippet and detected objects from a SLAM system, suggest corrections to improve map realism.

Grid legend: . = free/walkable, # = wall, O = obstacle, ? = unknown, V = visited by user.
Grid coordinates are relative to the user at center (0,0). Positive X = right, positive Z = forward.

Rules:
- Walls should form continuous lines (fill small gaps).
- Doors should create passable openings in wall lines.
- Objects on walls (windows, signs) should not block corridor space.
- Floor-standing objects (chairs, trash cans) should be obstacles.
- Be conservative: only suggest changes you are confident about.

Respond ONLY with valid JSON matching this schema (no explanations):
{
  "cell_updates": [{"gridX": 0, "gridZ": 0, "class": "FLOOR|WALL|OBSTACLE|UNKNOWN", "confidence": 0.0}],
  "doorways": [{"centerX": 0, "centerZ": 0, "orientationDeg": 0.0, "widthCells": 3, "confidence": 0.0}],
  "object_updates": [{"id": "string_or_null", "action": "CONFIRM|RELABEL|SUPPRESS|ADD", "label": "DOOR", "confidence": 0.0, "gridX": 0, "gridZ": 0, "affordance": "PASS_THROUGH|WALL_ATTACHED|FLOOR_OBSTACLE|LANDMARK_ONLY"}],
  "global_confidence": 0.0
}
""".trimIndent()
}
