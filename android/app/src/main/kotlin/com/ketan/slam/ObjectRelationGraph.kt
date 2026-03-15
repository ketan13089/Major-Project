package com.ketan.slam

/**
 * Spatial relationship types between detected objects.
 * Used INTERNALLY by the system for better path planning and
 * destination disambiguation — never exposed to the blind user.
 */
enum class SpatialRelation {
    /** Objects are within 2 m of each other. */
    NEAR_TO,
    /** Objects are within 1 m and roughly axis-aligned. */
    NEXT_TO,
    /** Objects are 2–5 m apart on roughly opposite sides (e.g., across a corridor). */
    ACROSS_FROM
}

/**
 * A directed edge in the object relationship graph.
 */
data class ObjectEdge(
    val fromId: String,
    val toId: String,
    val relation: SpatialRelation,
    val distance: Float
)

/**
 * Maintains a spatial relationship graph among [SemanticObject]s.
 *
 * This is an INTERNAL system capability used to improve path planning
 * and contextual destination selection. It is NEVER exposed as
 * visual-landmark references to the blind user.
 */
class ObjectRelationGraph {

    /** All edges, rebuilt on every object mutation. */
    private val edges = mutableListOf<ObjectEdge>()

    /** Adjacency index: objectId → list of edges originating from that object. */
    private val adjacency = HashMap<String, MutableList<ObjectEdge>>()

    // ── Thresholds ──────────────────────────────────────────────────────────

    companion object {
        private const val NEAR_DIST   = 2.0f   // metres
        private const val NEXT_DIST   = 1.0f   // metres
        private const val AXIS_TOL    = 0.5f   // tolerance for axis-alignment (metres)
        private const val ACROSS_MIN  = 2.0f   // metres
        private const val ACROSS_MAX  = 5.0f   // metres
    }

    // ── Rebuild ─────────────────────────────────────────────────────────────

    /**
     * Re-compute all pairwise spatial relationships.
     * Called after the semantic object set is mutated.
     */
    @Synchronized
    fun rebuild(objects: List<SemanticObject>) {
        edges.clear()
        adjacency.clear()

        for (i in objects.indices) {
            for (j in i + 1 until objects.size) {
                val a = objects[i]; val b = objects[j]
                val dist = a.position.distance(b.position)
                val dx = kotlin.math.abs(a.position.x - b.position.x)
                val dz = kotlin.math.abs(a.position.z - b.position.z)

                val relations = mutableListOf<SpatialRelation>()

                // NEXT_TO: very close + roughly axis-aligned
                if (dist < NEXT_DIST && (dx < AXIS_TOL || dz < AXIS_TOL)) {
                    relations.add(SpatialRelation.NEXT_TO)
                }
                // NEAR_TO: within 2 m
                if (dist < NEAR_DIST) {
                    relations.add(SpatialRelation.NEAR_TO)
                }
                // ACROSS_FROM: 2–5 m apart (e.g., across corridor)
                if (dist in ACROSS_MIN..ACROSS_MAX) {
                    relations.add(SpatialRelation.ACROSS_FROM)
                }

                for (rel in relations) {
                    val fwd = ObjectEdge(a.id, b.id, rel, dist)
                    val rev = ObjectEdge(b.id, a.id, rel, dist)
                    edges.add(fwd)
                    edges.add(rev)
                    adjacency.getOrPut(a.id) { mutableListOf() }.add(fwd)
                    adjacency.getOrPut(b.id) { mutableListOf() }.add(rev)
                }
            }
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Get all relationship edges for a given object. */
    fun getNeighbors(objectId: String): List<ObjectEdge> =
        adjacency[objectId]?.toList() ?: emptyList()

    /**
     * Find objects of a specific type that have a given relation to a reference object.
     * Used internally for smarter destination disambiguation.
     */
    fun findByRelation(
        allObjects: List<SemanticObject>,
        targetType: ObjectType,
        relation: SpatialRelation,
        refId: String
    ): List<SemanticObject> {
        val relatedIds = (adjacency[refId] ?: emptyList())
            .filter { it.relation == relation }
            .map { it.toId }
            .toSet()
        return allObjects.filter { it.type == targetType && it.id in relatedIds }
    }

    /** Get all current edges (snapshot). */
    fun getAllEdges(): List<ObjectEdge> = synchronized(this) { edges.toList() }

    /** Total number of relationships tracked. */
    val edgeCount: Int get() = edges.size
}
