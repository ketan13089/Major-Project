package com.ketan.slam

import android.content.Intent
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

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Cache the Flutter engine so ArActivity can access it
        FlutterEngineCache
            .getInstance()
            .put("slam_engine", flutterEngine)

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
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MAP_STORE_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "listMaps" -> {
                    val dir = File(filesDir, "saved_maps")
                    val maps = dir.listFiles { f -> f.extension == "json" }
                        ?.map { it.nameWithoutExtension }
                        ?.sortedDescending()
                        ?: emptyList()
                    result.success(maps)
                }
                "loadMapPayload" -> {
                    val name = call.argument<String>("name") ?: "last_session"
                    val payload = loadMapAsFlutterPayload(name)
                    if (payload != null) result.success(payload)
                    else result.error("NOT_FOUND", "No saved map: $name", null)
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
}