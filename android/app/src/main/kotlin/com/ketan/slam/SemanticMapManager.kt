package com.ketan.slam

import java.util.concurrent.ConcurrentHashMap

// Grid cell for spatial hashing
data class GridCell(val x: Int, val z: Int) {
    override fun hashCode() = x * 31 + z
    override fun equals(other: Any?) = other is GridCell && other.x == x && other.z == z
}

// Statistics about detected objects
data class SemanticMapStatistics(
    val totalObjects: Int,
    val objectCounts: Map<ObjectType, Int>,
    val avgConfidence: Float
)

class SemanticMapManager {

    companion object {
        private const val CELL_SIZE = 1.0f
        private const val DUPLICATE_DISTANCE = 0.5f
        private const val STALE_TIME_MS = 30000L
        private const val MIN_OBSERVATIONS = 3
    }

    private val spatialGrid = ConcurrentHashMap<GridCell, MutableList<SemanticObject>>()
    private val objectsById = ConcurrentHashMap<String, SemanticObject>()

    fun addObject(obj: SemanticObject): Boolean {
        synchronized(this) {
            val cell = toGridCell(obj.position)

            for (dx in -1..1) {
                for (dz in -1..1) {
                    val nearbyCell = GridCell(cell.x + dx, cell.z + dz)
                    val nearbyObjects = spatialGrid[nearbyCell] ?: continue

                    for (existing in nearbyObjects) {
                        if (existing.type == obj.type) {
                            val distance = obj.position.distance(existing.position)

                            if (distance < DUPLICATE_DISTANCE) {
                                existing.lastSeen = obj.lastSeen
                                existing.observations++

                                val weight = 1.0f / existing.observations
                                val updated = existing.copy(
                                    position = Point3D(
                                        existing.position.x * (1 - weight) + obj.position.x * weight,
                                        existing.position.y * (1 - weight) + obj.position.y * weight,
                                        existing.position.z * (1 - weight) + obj.position.z * weight
                                    )
                                )

                                objectsById[existing.id] = updated
                                nearbyObjects.remove(existing)
                                nearbyObjects.add(updated)

                                return false
                            }
                        }
                    }
                }
            }

            val objects = spatialGrid.getOrPut(cell) { mutableListOf() }
            objects.add(obj)
            objectsById[obj.id] = obj

            return true
        }
    }

    fun removeStaleObjects() {
        synchronized(this) {
            val currentTime = System.currentTimeMillis()
            val toRemove = objectsById.values.filter { obj ->
                (currentTime - obj.lastSeen) > STALE_TIME_MS && obj.observations < MIN_OBSERVATIONS
            }

            toRemove.forEach { obj ->
                objectsById.remove(obj.id)
                spatialGrid[toGridCell(obj.position)]?.remove(obj)
            }
        }
    }

    fun getAllObjects(): List<SemanticObject> = objectsById.values.toList()

    fun getObjectsInRadius(center: Point3D, radius: Float): List<SemanticObject> {
        val results = mutableListOf<SemanticObject>()
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
        val counts = mutableMapOf<ObjectType, Int>()
        objects.forEach { counts[it.type] = counts.getOrDefault(it.type, 0) + 1 }

        return SemanticMapStatistics(
            totalObjects = objects.size,
            objectCounts = counts,
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

    private fun toGridCell(position: Point3D): GridCell {
        return GridCell((position.x / CELL_SIZE).toInt(), (position.z / CELL_SIZE).toInt())
    }
}