package com.ketan.slam

import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Config for the LLM assistant (query + navigation).
 *
 *   Key and model id are pulled from BuildConfig at activity startup, which
 *   reads them from local.properties:
 *     llm.assistant.api.key  = sk-or-...
 *     llm.assistant.model    = z-ai/glm-4.6   (or whichever OpenRouter id)
 *
 *   See readme_for_llmAPI.md at the repo root.
 */
object LlmAssistantConfig {
    @Volatile var apiKey: String = ""
    @Volatile var model: String = ""

    const val ENDPOINT_URL = "https://openrouter.ai/api/v1/chat/completions"
    const val TIMEOUT_MS = 20_000
    const val MAX_TOKENS = 800
    const val TEMPERATURE = 0.2

    /** Minimum interval between vision-based map update calls (ms). */
    const val VISION_UPDATE_INTERVAL_MS = 12_000L

    /** Radius around the user to include in serialized context (metres). */
    const val CONTEXT_RADIUS_M = 10.0f

    /** Max objects to describe in one prompt (keeps token use bounded). */
    const val MAX_CONTEXT_OBJECTS = 40

    val enabled: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()
}

/** The three LLM flows the app supports. */
enum class LlmTaskKind { QUERY, NAVIGATE, VISION_UPDATE }

/** Snapshot of a camera frame in YUV_420_888 form. */
data class LlmYuvSnapshot(
    val y: ByteArray, val u: ByteArray, val v: ByteArray,
    val yStride: Int, val uvStride: Int, val uvPixStride: Int,
    val width: Int, val height: Int
)

data class LlmQueryResult(
    val answer: String,
    val raw: String
)

data class LlmNavigateResult(
    /** Natural-language acknowledgement to speak before route kicks in. */
    val spoken: String,
    /** The semantic object id the LLM chose as destination, or null if none fit. */
    val targetObjectId: String?,
    /** Fallback: explicit world-space coordinate the LLM suggested. */
    val targetWorldX: Float?,
    val targetWorldZ: Float?,
    val raw: String
)

data class LlmVisionUpdate(
    /** Free-form description of what the LLM sees. */
    val summary: String,
    /** Objects the LLM identified, with approximate relative direction
     *  (front/left/right/back) and distance bucket (near/mid/far). */
    val observed: List<VisionObservation>,
    val raw: String
)

data class VisionObservation(
    val label: String,
    val relativeDirection: String,
    val distanceBucket: String
)

/**
 * Converts a YUV_420_888 camera buffer into a base64 JPEG suitable for the
 * OpenRouter multimodal payload (chat/completions with image_url data URIs).
 */
object LlmImageEncoder {
    fun yuvToBase64Jpeg(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yStride: Int, uvStride: Int, uvPixStride: Int,
        width: Int, height: Int,
        quality: Int = 70
    ): String? {
        return try {
            // Pack YUV420 planes into NV21 (Y then interleaved VU) for YuvImage.
            val nv21 = ByteArray(width * height * 3 / 2)
            var dst = 0
            // Y plane
            for (row in 0 until height) {
                val src = row * yStride
                System.arraycopy(y, src, nv21, dst, width)
                dst += width
            }
            // Interleaved VU
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIdx = row * uvStride + col * uvPixStride
                    if (uvIdx >= v.size || uvIdx >= u.size) continue
                    nv21[dst++] = v[uvIdx]
                    nv21[dst++] = u[uvIdx]
                }
            }
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val bos = ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            println("LlmImageEncoder: ${e.message}")
            null
        }
    }
}

/**
 * Serializes the current environment state into a compact text block the
 * LLM can reason about. Only objects within [LlmAssistantConfig.CONTEXT_RADIUS_M]
 * of the user are included, ranked by distance.
 */
object LlmContextBuilder {
    fun buildEnvironmentText(
        userX: Float, userZ: Float, headingRad: Float,
        semanticMap: SemanticMapManager,
        mapBuilder: MapBuilder
    ): String {
        val sb = StringBuilder()
        sb.appendLine("USER_POSITION: x=${f(userX)} z=${f(userZ)} heading_deg=${f(Math.toDegrees(headingRad.toDouble()).toFloat())}")

        val nearby = semanticMap
            .getObjectsInRadius(Point3D(userX, 0f, userZ), LlmAssistantConfig.CONTEXT_RADIUS_M)
            .sortedBy { dist(userX, userZ, it.position.x, it.position.z) }
            .take(LlmAssistantConfig.MAX_CONTEXT_OBJECTS)

        sb.appendLine("NEARBY_OBJECTS: ${nearby.size}")
        nearby.forEach { obj ->
            val d = dist(userX, userZ, obj.position.x, obj.position.z)
            val bearing = relativeBearing(userX, userZ, obj.position.x, obj.position.z, headingRad)
            val textSuffix = obj.textContent?.let { " text=\"${it.take(40)}\"" } ?: ""
            val roomSuffix = obj.roomNumber?.let { " room=$it" } ?: ""
            sb.append("- id=${obj.id} type=${obj.category} dist_m=${f(d)} bearing=$bearing")
            sb.append(" pos=(${f(obj.position.x)},${f(obj.position.z)})")
            sb.append(" obs=${obj.observations}")
            sb.appendLine("$textSuffix$roomSuffix")
        }

        val wallCount = mapBuilder.grid.values.count { it.toInt() == 3 }
        val floorCount = mapBuilder.grid.values.count { it.toInt() == 1 }
        sb.appendLine("MAP_STATS: floor_cells=$floorCount wall_cells=$wallCount")
        return sb.toString()
    }

    private fun f(v: Float) = String.format("%.2f", v)

    private fun dist(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        val dx = x1 - x0; val dz = z1 - z0
        return sqrt(dx * dx + dz * dz)
    }

    /** Relative bearing as a compass word (front/right/back/left) in user frame. */
    private fun relativeBearing(
        userX: Float, userZ: Float, tx: Float, tz: Float, headingRad: Float
    ): String {
        val dx = tx - userX; val dz = tz - userZ
        val absBearing = kotlin.math.atan2(dx, -dz)  // ARCore: -Z is forward
        var rel = absBearing - headingRad
        while (rel > Math.PI) rel -= (2 * Math.PI).toFloat()
        while (rel < -Math.PI) rel += (2 * Math.PI).toFloat()
        val deg = Math.toDegrees(rel.toDouble())
        return when {
            deg in -45.0..45.0   -> "front"
            deg in 45.0..135.0   -> "right"
            deg in -135.0..-45.0 -> "left"
            else                 -> "back"
        }
    }
}

/**
 * HTTP worker — one instance shared across all three flows.
 *
 * Request is always OpenRouter chat/completions with JSON response_format.
 * For vision updates, the user message includes an image_url content part
 * using a data: URI so no external hosting is needed.
 */
class LlmAssistant(
    private val semanticMap: SemanticMapManager,
    private val mapBuilder: MapBuilder
) {
    private val TAG = "LlmAssistant"

    // Pending vision-update throttle
    @Volatile private var lastVisionMs = 0L

    fun shouldRunVisionUpdate(now: Long): Boolean =
        LlmAssistantConfig.enabled &&
        now - lastVisionMs >= LlmAssistantConfig.VISION_UPDATE_INTERVAL_MS

    fun markVisionRun(now: Long) { lastVisionMs = now }

    // ── Public flows ──────────────────────────────────────────────────────────

    /** Free-form question about the environment. Runs on the caller thread. */
    fun query(
        userText: String,
        userX: Float, userZ: Float, headingRad: Float
    ): LlmQueryResult? {
        if (!LlmAssistantConfig.enabled) return null

        val env = LlmContextBuilder.buildEnvironmentText(userX, userZ, headingRad, semanticMap, mapBuilder)
        val system = """
            You are an AR navigation assistant. The user is inside a building
            and has a phone streaming a semantic map of their surroundings.
            Answer the user's question concisely, using ONLY the environment
            data provided. If the answer isn't derivable from the data, say so.
            Respond as a JSON object: {"answer": "<your spoken reply>"}.
        """.trimIndent()

        val user = """
            ENVIRONMENT:
            $env

            QUESTION: $userText
        """.trimIndent()

        val raw = callApi(system, user, includeImage = null) ?: return null
        val answer = parseJsonField(raw, "answer") ?: raw.trim()
        return LlmQueryResult(answer = answer, raw = raw)
    }

    /** "Guide me to X" — LLM chooses the best matching object id. */
    fun navigate(
        userText: String,
        userX: Float, userZ: Float, headingRad: Float
    ): LlmNavigateResult? {
        if (!LlmAssistantConfig.enabled) return null

        val env = LlmContextBuilder.buildEnvironmentText(userX, userZ, headingRad, semanticMap, mapBuilder)
        val system = """
            You are an AR navigation planner. The user wants to be guided to
            a destination. Choose the single best matching object from the
            NEARBY_OBJECTS list by matching its type/text/room against the
            user's request. Prefer higher observation counts and appropriate
            qualifiers (nearest, farthest, left, right).

            Respond ONLY with a JSON object of the form:
            {
              "target_id": "<id from NEARBY_OBJECTS, or null>",
              "target_x": <world x float or null>,
              "target_z": <world z float or null>,
              "spoken": "<short confirmation to speak aloud>"
            }
            If nothing matches, set target_id/target_x/target_z to null and
            explain briefly in "spoken".
        """.trimIndent()

        val user = """
            ENVIRONMENT:
            $env

            DESTINATION_REQUEST: $userText
        """.trimIndent()

        val raw = callApi(system, user, includeImage = null) ?: return null
        val obj = safeJson(raw) ?: JSONObject()
        val targetId = obj.optString("target_id").takeIf { it.isNotBlank() && it != "null" }
        val targetX  = obj.optDouble("target_x", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        val targetZ  = obj.optDouble("target_z", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        val spoken   = obj.optString("spoken").ifBlank { "Planning your route." }
        return LlmNavigateResult(spoken, targetId, targetX, targetZ, raw)
    }

    /**
     * Vision update — sends a JPEG of the current camera frame along with
     * the current semantic map, asks the LLM to list what it sees. Caller
     * applies the results back into [SemanticMapManager].
     */
    fun visionUpdate(
        jpegBase64: String,
        userX: Float, userZ: Float, headingRad: Float
    ): LlmVisionUpdate? {
        if (!LlmAssistantConfig.enabled) return null

        val env = LlmContextBuilder.buildEnvironmentText(userX, userZ, headingRad, semanticMap, mapBuilder)
        val system = """
            You are a vision module for an AR navigation app. Given a live
            camera image and the current semantic map, report what objects
            are visible that are navigation-relevant (doors, stairs, chairs,
            signs, hallways, exits, people, obstacles). Output JSON:
            {
              "summary": "<one sentence scene description>",
              "observed": [
                {"label": "...", "direction": "front|left|right|back",
                 "distance": "near|mid|far"}
              ]
            }
            Keep the list focused on navigation cues. Ignore decorations.
        """.trimIndent()

        val user = "CURRENT_MAP:\n$env"

        val raw = callApi(system, user, includeImage = jpegBase64) ?: return null
        val obj = safeJson(raw) ?: return null
        val summary = obj.optString("summary")
        val arr = obj.optJSONArray("observed") ?: JSONArray()
        val list = mutableListOf<VisionObservation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(VisionObservation(
                label = o.optString("label"),
                relativeDirection = o.optString("direction", "front"),
                distanceBucket = o.optString("distance", "mid")
            ))
        }
        return LlmVisionUpdate(summary = summary, observed = list, raw = raw)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun callApi(system: String, user: String, includeImage: String?): String? {
        val body = buildRequestBody(system, user, includeImage)
        val conn = (URL(LlmAssistantConfig.ENDPOINT_URL).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${LlmAssistantConfig.apiKey}")
            conn.setRequestProperty("HTTP-Referer", "com.ketan.slam")
            conn.connectTimeout = LlmAssistantConfig.TIMEOUT_MS
            conn.readTimeout = LlmAssistantConfig.TIMEOUT_MS
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                println("$TAG: HTTP ${conn.responseCode} $err")
                return null
            }
            val resp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val root = JSONObject(resp)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            message.optString("content", "")
        } catch (e: Exception) {
            println("$TAG: ${e.message}")
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildRequestBody(system: String, user: String, imageB64: String?): String {
        val root = JSONObject()
        root.put("model", LlmAssistantConfig.model)
        root.put("temperature", LlmAssistantConfig.TEMPERATURE)
        root.put("max_tokens", LlmAssistantConfig.MAX_TOKENS)
        root.put("response_format", JSONObject().put("type", "json_object"))

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))

        val userMsg = JSONObject().put("role", "user")
        if (imageB64 != null) {
            // OpenRouter multimodal: content is an array of parts.
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", user))
            parts.put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
            userMsg.put("content", parts)
        } else {
            userMsg.put("content", user)
        }
        messages.put(userMsg)
        root.put("messages", messages)
        return root.toString()
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun safeJson(raw: String): JSONObject? {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        return try { JSONObject(trimmed) } catch (_: Exception) { null }
    }

    private fun parseJsonField(raw: String, field: String): String? =
        safeJson(raw)?.optString(field)?.takeIf { it.isNotBlank() }
}
