package com.ketan.slam

import android.content.Intent
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.ketan.slam/ar"
    private val MAP_STORE_CHANNEL = "com.ketan.slam/map_store"
    private val MAP_CHANNEL = "com.ketan.slam/map"
    private val TTS_CHANNEL = "com.ketan.slam/tts"
    private val VOLUME_CHANNEL = "com.ketan.slam/volume_buttons"

    companion object {
        /**
         * Installs the baseline `com.ketan.slam/map` handler on the given
         * Flutter engine. Answers read-only PerformanceTracker queries so the
         * dashboard works even when ArActivity isn't running. Other methods
         * respond with a friendly "ar_not_running" error rather than timing
         * out silently.
         *
         * ArActivity overrides this with its richer handler while it's alive
         * and must call this again from onDestroy to restore the baseline.
         */
        fun installBaselineMapChannel(context: android.content.Context, engine: FlutterEngine) {
            MethodChannel(engine.dartExecutor.binaryMessenger, "com.ketan.slam/map")
                .setMethodCallHandler { call, result ->
                    // Per-map performance is served on MAP_STORE_CHANNEL
                    // (see 'getMapPerformance' / 'exportMapPerformance' below).
                    // Anything requiring a live AR session fails friendly.
                    when (call.method) {
                        "saveMap", "loadMap", "listMaps", "deleteMap",
                        "triggerEmergency", "getPerformanceMetrics",
                        "exportPerformanceReport" -> {
                            result.error("AR_NOT_RUNNING",
                                "AR session is not active. Open the AR scan first.", null)
                        }
                        else -> result.notImplemented()
                    }
                }
        }
    }

    private lateinit var accessibilityHandler: AccessibilityHandler
    private var volumeNavEnabled = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize accessibility handler
        accessibilityHandler = AccessibilityHandler(this)
        accessibilityHandler.initialize()

        // Set up TTS and volume button channels
        val ttsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            TTS_CHANNEL
        )
        val volumeChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VOLUME_CHANNEL
        )
        accessibilityHandler.setChannels(ttsChannel, volumeChannel)

        // Cache the Flutter engine so ArActivity can access it
        FlutterEngineCache
            .getInstance()
            .put("slam_engine", flutterEngine)

        // Baseline map channel: makes the Performance Metrics dashboard work
        // even when no AR scan is running. ArActivity installs its fuller
        // handler on top of this while live, then restores this one onDestroy.
        installBaselineMapChannel(this, flutterEngine)

        // Set up method channel for opening AR
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->
            if (call.method == "openAR") {
                startActivity(Intent(this, ArActivity::class.java))
                result.success(null)
            } else {
                result.notImplemented()
            }
        }

        // Map persistence channel — always available (survives ArActivity lifecycle)
        val persistence = MapPersistence(this)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MAP_STORE_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "listSavedMaps" -> {
                    // Return list of metadata maps for all saved sessions
                    val names = persistence.listSavedMaps()
                    val metadataList = mutableListOf<Map<String, Any>>()
                    for (name in names) {
                        if (name == "last_session") continue // skip the auto-resume copy
                        val meta = persistence.getMapMetadata(name)
                        if (meta != null) metadataList.add(meta)
                    }
                    result.success(metadataList)
                }
                "loadMapPayload" -> {
                    val name = call.argument<String>("name") ?: "last_session"
                    val payload = loadMapAsFlutterPayload(name)
                    if (payload != null) result.success(payload)
                    else result.error("NOT_FOUND", "No saved map: $name", null)
                }
                "deleteMap" -> {
                    val name = call.argument<String>("name") ?: ""
                    val ok = persistence.deleteMap(name)
                    result.success(mapOf("success" to ok))
                }
                "getMapMetadata" -> {
                    val name = call.argument<String>("name") ?: ""
                    val meta = persistence.getMapMetadata(name)
                    if (meta != null) result.success(meta)
                    else result.error("NOT_FOUND", "No saved map: $name", null)
                }
                "getMapPerformance" -> {
                    val name = call.argument<String>("name") ?: ""
                    val perf = persistence.getMapPerformance(name)
                    // null = map has no embedded perf snapshot (legacy save).
                    // Return an empty map so Flutter can show the empty state
                    // without treating it as an error.
                    result.success(perf ?: emptyMap<String, Any>())
                }
                "exportMapPerformance" -> {
                    val name = call.argument<String>("name") ?: ""
                    val perf = persistence.getMapPerformance(name)
                    if (perf.isNullOrEmpty()) {
                        result.error("NO_METRICS",
                            "No performance data saved for this map.", null)
                    } else try {
                        val dir = File(filesDir, "performance_reports")
                        if (!dir.exists()) dir.mkdirs()
                        val ts = System.currentTimeMillis()
                        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val file = File(dir, "perf_${safeName}_${ts}.json")
                        val json = JSONObject().apply {
                            put("map", name)
                            put("exportedAt", ts)
                            val data = JSONObject()
                            for ((k, v) in perf) data.put(k, v)
                            put("metrics", data)
                        }
                        file.writeText(json.toString(2))
                        result.success(mapOf("path" to file.absolutePath))
                    } catch (e: Exception) {
                        result.error("EXPORT_FAILED", e.message, null)
                    }
                }
                // Legacy: keep listMaps for backward compat with IndoorMapViewer
                "listMaps" -> {
                    val dir = File(filesDir, "saved_maps")
                    val maps = dir.listFiles { f -> f.extension == "json" }
                        ?.map { it.nameWithoutExtension }
                        ?.sortedDescending()
                        ?: emptyList()
                    result.success(maps)
                }
                else -> result.notImplemented()
            }
        }
    }

    /**
     * Read a saved map JSON and convert it to the same payload format that
     * ArActivity.buildMapPayload() sends via the 'updateMap' method call.
     * This allows IndoorMapViewer to display saved maps without AR running.
     */
    private fun loadMapAsFlutterPayload(name: String): Map<String, Any>? {
        return try {
            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(File(filesDir, "saved_maps"), "${sanitized}.json")
            if (!file.exists()) return null

            val json = JSONObject(file.readText())

            // Reconstruct grid bytes
            val gridArr = json.getJSONArray("grid")
            val bounds = json.getJSONObject("bounds")
            val minGX = bounds.getInt("minGX"); val maxGX = bounds.getInt("maxGX")
            val minGZ = bounds.getInt("minGZ"); val maxGZ = bounds.getInt("maxGZ")
            val w = maxGX - minGX + 1; val h = maxGZ - minGZ + 1

            val bytes = ByteArray(w * h)
            for (i in 0 until gridArr.length()) {
                val entry = gridArr.getJSONArray(i)
                val cx = entry.getInt(0); val cz = entry.getInt(1); val v = entry.getInt(2)
                val idx = (cz - minGZ) * w + (cx - minGX)
                if (idx in bytes.indices) bytes[idx] = v.toByte()
            }

            // Reconstruct objects
            val objArr = json.optJSONArray("objects") ?: org.json.JSONArray()
            val objects = mutableListOf<Map<String, Any>>()
            val res = json.optDouble("resolution", 0.20).toFloat()
            for (i in 0 until objArr.length()) {
                val o = objArr.getJSONObject(i)
                val m = mutableMapOf<String, Any>(
                    "id" to o.getString("id"),
                    "type" to o.getString("type"),
                    "label" to o.getString("category"),
                    "confidence" to o.getDouble("confidence"),
                    "x" to o.getDouble("posX"),
                    "y" to o.getDouble("posY"),
                    "z" to o.getDouble("posZ"),
                    "gridX" to ((o.getDouble("posX") / res).roundToInt() - minGX),
                    "gridZ" to ((o.getDouble("posZ") / res).roundToInt() - minGZ),
                    "observations" to o.optInt("observations", 1)
                )
                o.optString("textContent", "").takeIf { it.isNotEmpty() }?.let { m["textContent"] = it }
                o.optString("roomNumber", "").takeIf { it.isNotEmpty() }?.let { m["roomNumber"] = it }
                objects.add(m)
            }

            // Robot position from last breadcrumb or (0,0)
            val bcArr = json.optJSONArray("breadcrumbs")
            var robotX = 0f; var robotZ = 0f
            if (bcArr != null && bcArr.length() > 0) {
                val last = bcArr.getJSONArray(bcArr.length() - 1)
                robotX = last.getDouble(0).toFloat()
                robotZ = last.getDouble(2).toFloat()
            }

            mapOf(
                "occupancyGrid" to bytes,
                "gridWidth" to w, "gridHeight" to h,
                "gridResolution" to res.toDouble(),
                "originX" to minGX, "originZ" to minGZ,
                "robotGridX" to ((robotX / res).roundToInt() - minGX),
                "robotGridZ" to ((robotZ / res).roundToInt() - minGZ),
                "objects" to objects,
                "navPath" to emptyList<Any>()
            )
        } catch (e: Exception) {
            println("MainActivity: loadMapPayload: ${e.message}")
            null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeNavEnabled && event != null) {
            if (accessibilityHandler.handleKeyEvent(keyCode, event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeNavEnabled && event != null) {
            if (accessibilityHandler.handleKeyEvent(keyCode, event)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        accessibilityHandler.shutdown()
        super.onDestroy()
    }
}