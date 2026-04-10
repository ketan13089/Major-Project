package com.ketan.slam

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serializes and deserializes the complete map state (occupancy grid,
 * log-odds, wall cells, semantic objects, breadcrumbs) to/from JSON files
 * in app-private storage.
 *
 * File format (JSON):
 * ```
 * {
 *   "version": 1,
 *   "timestamp": <epoch_ms>,
 *   "resolution": 0.20,
 *   "bounds": { "minGX": .., "maxGX": .., "minGZ": .., "maxGZ": .. },
 *   "grid": "<base64 encoded byte array>",
 *   "logOdds": "<base64 encoded float array>",
 *   "wallCells": [ [x,z], ... ],
 *   "objects": [ { ... }, ... ],
 *   "breadcrumbs": [ [x,y,z], ... ]
 * }
 * ```
 */
class MapPersistence(private val context: Context) {

    companion object {
        private const val TAG = "MapPersistence"
        private const val MAP_DIR = "saved_maps"
        private const val FORMAT_VERSION = 2
    }

    private fun mapsDir(): File {
        val dir = File(context.filesDir, MAP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /**
     * Save the current map state to a named file.
     * @return the absolute path of the saved file, or null on failure.
     */
    fun saveMap(
        name: String,
        mapBuilder: MapBuilder,
        semanticMap: SemanticMapManager,
        breadcrumbs: List<Point3D>,
        sessionStartMs: Long = 0L
    ): String? {
        return try {
            val json = JSONObject()
            json.put("version", FORMAT_VERSION)
            val now = System.currentTimeMillis()
            json.put("timestamp", now)
            json.put("resolution", mapBuilder.res)

            // Session metadata
            val durationSec = if (sessionStartMs > 0) ((now - sessionStartMs) / 1000).toInt() else 0
            val objectCount = semanticMap.getAllObjects().size
            var freeCount = 0
            for ((_, v) in mapBuilder.grid) {
                val vi = v.toInt()
                if (vi == MapBuilder.CELL_FREE || vi == MapBuilder.CELL_VISITED) freeCount++
            }
            val areaSqM = freeCount * mapBuilder.res * mapBuilder.res
            json.put("durationSec", durationSec)
            json.put("objectCount", objectCount)
            json.put("areaM2", areaSqM.toDouble())
            json.put("wallCount", mapBuilder.getWallCells().size)

            // Bounds
            val bounds = JSONObject()
            bounds.put("minGX", mapBuilder.minGX)
            bounds.put("maxGX", mapBuilder.maxGX)
            bounds.put("minGZ", mapBuilder.minGZ)
            bounds.put("maxGZ", mapBuilder.maxGZ)
            json.put("bounds", bounds)

            // Grid cells — encode as compact arrays: [[x,z,type], ...]
            val gridArr = JSONArray()
            for ((cell, value) in mapBuilder.grid) {
                val entry = JSONArray()
                entry.put(cell.x); entry.put(cell.z); entry.put(value.toInt())
                gridArr.put(entry)
            }
            json.put("grid", gridArr)

            // Log-odds — same format: [[x,z,lo], ...]
            val loArr = JSONArray()
            for ((cell, lo) in mapBuilder.logOdds) {
                val entry = JSONArray()
                entry.put(cell.x); entry.put(cell.z); entry.put(lo.toDouble())
                loArr.put(entry)
            }
            json.put("logOdds", loArr)

            // Wall cells
            val wallArr = JSONArray()
            for (cell in mapBuilder.getWallCells()) {
                val entry = JSONArray()
                entry.put(cell.x); entry.put(cell.z)
                wallArr.put(entry)
            }
            json.put("wallCells", wallArr)

            // Semantic objects
            val objArr = JSONArray()
            for (obj in semanticMap.getAllObjects()) {
                objArr.put(serializeObject(obj))
            }
            json.put("objects", objArr)

            // Breadcrumbs
            val bcArr = JSONArray()
            for (pt in breadcrumbs) {
                val entry = JSONArray()
                entry.put(pt.x.toDouble()); entry.put(pt.y.toDouble()); entry.put(pt.z.toDouble())
                bcArr.put(entry)
            }
            json.put("breadcrumbs", bcArr)

            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(mapsDir(), "${sanitized}.json")
            file.writeText(json.toString())
            Log.d(TAG, "Map saved: ${file.absolutePath} (${file.length() / 1024}KB)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save map: ${e.message}", e)
            null
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /**
     * Load a previously saved map into the provided MapBuilder and SemanticMapManager.
     * @return breadcrumbs list, or null on failure.
     */
    fun loadMap(
        name: String,
        mapBuilder: MapBuilder,
        semanticMap: SemanticMapManager
    ): List<Point3D>? {
        return try {
            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(mapsDir(), "${sanitized}.json")
            if (!file.exists()) {
                Log.w(TAG, "Map file not found: ${file.absolutePath}")
                return null
            }

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version < 1 || version > FORMAT_VERSION) {
                Log.w(TAG, "Unsupported map version: $version")
                return null
            }

            // Restore grid
            val gridArr = json.getJSONArray("grid")
            for (i in 0 until gridArr.length()) {
                val entry = gridArr.getJSONArray(i)
                val cell = GridCell(entry.getInt(0), entry.getInt(1))
                mapBuilder.grid[cell] = entry.getInt(2).toByte()
            }

            // Restore log-odds
            val loArr = json.getJSONArray("logOdds")
            for (i in 0 until loArr.length()) {
                val entry = loArr.getJSONArray(i)
                val cell = GridCell(entry.getInt(0), entry.getInt(1))
                mapBuilder.logOdds[cell] = entry.getDouble(2).toFloat()
            }

            // Restore wall cells
            val wallArr = json.getJSONArray("wallCells")
            val wallCells = mutableSetOf<GridCell>()
            for (i in 0 until wallArr.length()) {
                val entry = wallArr.getJSONArray(i)
                wallCells.add(GridCell(entry.getInt(0), entry.getInt(1)))
            }
            mapBuilder.restoreWallCells(wallCells)

            // Restore bounds
            val bounds = json.getJSONObject("bounds")
            mapBuilder.minGX = bounds.getInt("minGX")
            mapBuilder.maxGX = bounds.getInt("maxGX")
            mapBuilder.minGZ = bounds.getInt("minGZ")
            mapBuilder.maxGZ = bounds.getInt("maxGZ")

            // Restore semantic objects
            val objArr = json.getJSONArray("objects")
            for (i in 0 until objArr.length()) {
                val obj = deserializeObject(objArr.getJSONObject(i))
                if (obj != null) semanticMap.addObject(obj)
            }

            // Restore breadcrumbs
            val bcArr = json.getJSONArray("breadcrumbs")
            val breadcrumbs = mutableListOf<Point3D>()
            for (i in 0 until bcArr.length()) {
                val entry = bcArr.getJSONArray(i)
                breadcrumbs.add(Point3D(
                    entry.getDouble(0).toFloat(),
                    entry.getDouble(1).toFloat(),
                    entry.getDouble(2).toFloat()
                ))
            }

            Log.d(TAG, "Map loaded: ${file.absolutePath} " +
                    "(${mapBuilder.grid.size} cells, ${semanticMap.getAllObjects().size} objects)")
            breadcrumbs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load map: ${e.message}", e)
            null
        }
    }

    // ── List / Delete ────────────────────────────────────────────────────────

    /** List all saved map names. */
    fun listSavedMaps(): List<String> {
        return mapsDir().listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending()
            ?: emptyList()
    }

    /** Delete a saved map by name. */
    fun deleteMap(name: String): Boolean {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(mapsDir(), "${sanitized}.json")
        return file.delete()
    }

    // ── Metadata (header-only read) ──────────────────────────────────────────

    /**
     * Read only the metadata fields from a saved map without full deserialization.
     * Returns null if the file doesn't exist or can't be read.
     */
    fun getMapMetadata(name: String): Map<String, Any>? {
        return try {
            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(mapsDir(), "${sanitized}.json")
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            mapOf(
                "name" to name,
                "timestamp" to json.optLong("timestamp", 0L),
                "areaM2" to json.optDouble("areaM2", 0.0),
                "objectCount" to json.optInt("objectCount", 0),
                "durationSec" to json.optInt("durationSec", 0),
                "wallCount" to json.optInt("wallCount", 0),
                "resolution" to json.optDouble("resolution", 0.20)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read metadata for $name: ${e.message}")
            null
        }
    }

    // ── Serialization helpers ────────────────────────────────────────────────

    private fun serializeObject(obj: SemanticObject): JSONObject {
        val j = JSONObject()
        j.put("id", obj.id)
        j.put("type", obj.type.name)
        j.put("category", obj.category)
        j.put("posX", obj.position.x.toDouble())
        j.put("posY", obj.position.y.toDouble())
        j.put("posZ", obj.position.z.toDouble())
        j.put("confidence", obj.confidence.toDouble())
        j.put("observations", obj.observations)
        obj.label?.let { j.put("label", it) }
        obj.textContent?.let { j.put("textContent", it) }
        obj.roomNumber?.let { j.put("roomNumber", it) }
        obj.localizationMethod?.let { j.put("locMethod", it) }
        // v2 fields: affordance + source
        j.put("affordance", ObjectAffordance.forType(obj.type).name)
        j.put("source", if (obj.localizationMethod == "ai_semantic") "ai_corrected" else "local")
        return j
    }

    private fun deserializeObject(j: JSONObject): SemanticObject? {
        return try {
            SemanticObject(
                id = j.getString("id"),
                type = ObjectType.valueOf(j.getString("type")),
                category = j.getString("category"),
                position = Point3D(
                    j.getDouble("posX").toFloat(),
                    j.getDouble("posY").toFloat(),
                    j.getDouble("posZ").toFloat()
                ),
                boundingBox = BoundingBox2D(0f, 0f, 0f, 0f),  // not persisted
                confidence = j.getDouble("confidence").toFloat(),
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                observations = j.optInt("observations", 1),
                label = j.optString("label", null),
                textContent = j.optString("textContent", null),
                roomNumber = j.optString("roomNumber", null),
                localizationMethod = j.optString("locMethod", null)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize object: ${e.message}")
            null
        }
    }
}
