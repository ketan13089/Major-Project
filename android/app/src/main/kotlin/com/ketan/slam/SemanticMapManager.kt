package com.ketan.slam

import java.util.concurrent.ConcurrentHashMap

data class GridCell(val x: Int, val z: Int) {
    override fun hashCode() = x * 31 + z
    override fun equals(other: Any?) = other is GridCell && other.x == x && other.z == z
}

data class SemanticMapStatistics(
    val totalObjects: Int,
    val objectCounts: Map<ObjectType, Int>,
    val avgConfidence: Float
)

class SemanticMapManager {

    companion object {
        private const val CELL_SIZE = 1.0f
        private const val DUPLICATE_DISTANCE = 1.2f   // raised to match ArActivity merge radius
        private const val STALE_TIME_MS = 30000L
        private const val MIN_OBSERVATIONS = 3
    }

    private val spatialGrid = ConcurrentHashMap<GridCell, MutableList<SemanticObject>>()
    private val objectsById  = ConcurrentHashMap<String, SemanticObject>()

    // ── Add ───────────────────────────────────────────────────────────────────
    // Returns false when merged into an existing object, true when a new entry
    // was created.
    fun addObject(obj: SemanticObject): Boolean {
        synchronized(this) {
            val cell = toGridCell(obj.position)

            // Search neighbouring cells for a duplicate of the same type
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val nearbyObjects = spatialGrid[GridCell(cell.x + dx, cell.z + dz)] ?: continue
                    for (existing in nearbyObjects) {
                        if (existing.type != obj.type) continue
                        if (obj.position.distance(existing.position) >= DUPLICATE_DISTANCE) continue

                        // ── Merge ─────────────────────────────────────────────
                        val newObs    = existing.observations + 1
                        val weight    = 1f / newObs
                        val merged = existing.copy(
                            position = Point3D(
                                existing.position.x * (1 - weight) + obj.position.x * weight,
                                existing.position.y * (1 - weight) + obj.position.y * weight,
                                existing.position.z * (1 - weight) + obj.position.z * weight
                            ),
                            confidence  = maxOf(existing.confidence, obj.confidence),
                            lastSeen    = obj.lastSeen,
                            observations = newObs
                        )

                        objectsById[existing.id] = merged
                        nearbyObjects.remove(existing)
                        nearbyObjects.add(merged)
                        return false
                    }
                }
            }

            // No duplicate found — insert as new
            spatialGrid.getOrPut(cell) { mutableListOf() }.add(obj)
            objectsById[obj.id] = obj
            return true
        }
    }

    // ── Update in-place (called from ArActivity.mergeOrAddObject) ─────────────
    fun updateObject(obj: SemanticObject) {
        synchronized(this) {
            val old = objectsById[obj.id] ?: return

            // Remove old entry from spatial grid
            spatialGrid[toGridCell(old.position)]?.remove(old)

            // Insert updated entry
            objectsById[obj.id] = obj
            spatialGrid.getOrPut(toGridCell(obj.position)) { mutableListOf() }.add(obj)
        }
    }

    // ── Remove stale low-confidence objects ───────────────────────────────────
    fun removeStaleObjects() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            objectsById.values
                .filter { (now - it.lastSeen) > STALE_TIME_MS && it.observations < MIN_OBSERVATIONS }
                .forEach { obj ->
                    objectsById.remove(obj.id)
                    spatialGrid[toGridCell(obj.position)]?.remove(obj)
                }
        }
    }

    fun getAllObjects(): List<SemanticObject> = objectsById.values.toList()

    fun getObjectsInRadius(center: Point3D, radius: Float): List<SemanticObject> {
        val results    = mutableListOf<SemanticObject>()
        val centerCell = toGridCell(center)
        val cellRadius = (radius / CELL_SIZE).toInt() + 1
        for (dx in -cellRadius..cellRadius) {
            for (dz in -cellRadius..cellRadius) {
                spatialGrid[GridCell(centerCell.x + dx, centerCell.z + dz)]
                    ?.filter { it.position.distance(center) <= radius }
                    ?.let { results.addAll(it) }
            }
        }
        return results
    }

    fun getStatistics(): SemanticMapStatistics {
        val objects = getAllObjects()
        val counts  = mutableMapOf<ObjectType, Int>()
        objects.forEach { counts[it.type] = counts.getOrDefault(it.type, 0) + 1 }
        return SemanticMapStatistics(
            totalObjects  = objects.size,
            objectCounts  = counts,
            avgConfidence = if (objects.isNotEmpty())
                objects.map { it.confidence }.average().toFloat() else 0f
        )
    }

    fun clear() {
        synchronized(this) {
            spatialGrid.clear()
            objectsById.clear()
        }
    }

    private fun toGridCell(position: Point3D) =
        GridCell((position.x / CELL_SIZE).toInt(), (position.z / CELL_SIZE).toInt())
}