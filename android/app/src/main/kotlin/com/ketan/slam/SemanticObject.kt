package com.ketan.slam

import kotlin.math.sqrt

// ── 3D Point ─────────────────────────────────────────────────────────────────

data class Point3D(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Point3D) = Point3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Point3D) = Point3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Point3D(x * scalar, y * scalar, z * scalar)

    fun length() = sqrt(x * x + y * y + z * z)

    // Both names supported — SemanticMapManager uses distance(), old code used distanceTo()
    fun distance(other: Point3D) = (this - other).length()
    fun distanceTo(other: Point3D) = distance(other)
}

// ── 2D Bounding Box ───────────────────────────────────────────────────────────

data class BoundingBox2D(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

// ── Object Type Enum ──────────────────────────────────────────────────────────

enum class ObjectType {
    // New 8-class model classes
    CHAIR,
    DOOR,
    FIRE_EXTINGUISHER,
    LIFT_GATE,
    NOTICE_BOARD,
    TRASH_CAN,
    WATER_PURIFIER,
    WINDOW,

    // Generic fallback
    UNKNOWN;

    companion object {
        fun fromLabel(label: String): ObjectType {
            return when (label.lowercase().trim()) {
                "chair"             -> CHAIR
                "door"              -> DOOR
                "fire_extinguisher" -> FIRE_EXTINGUISHER
                "lift_gate"         -> LIFT_GATE
                "notice_board"      -> NOTICE_BOARD
                "trash_can"         -> TRASH_CAN
                "water_purifier"    -> WATER_PURIFIER
                "window"            -> WINDOW
                else                -> UNKNOWN
            }
        }
    }
}

// ── Semantic Object ───────────────────────────────────────────────────────────

data class SemanticObject(
    val id: String,
    val type: ObjectType,
    val category: String,
    val position: Point3D,
    val boundingBox: BoundingBox2D,
    val confidence: Float,
    val firstSeen: Long,
    var lastSeen: Long,
    var observations: Int = 1,
    var label: String? = null
) {
    fun isSimilarTo(other: SemanticObject, distanceThreshold: Float = 0.5f): Boolean {
        if (this.category != other.category) return false
        return this.position.distance(other.position) < distanceThreshold
    }

    fun mergeWith(other: SemanticObject): SemanticObject {
        return this.copy(
            position = Point3D(
                (position.x + other.position.x) / 2,
                (position.y + other.position.y) / 2,
                (position.z + other.position.z) / 2
            ),
            confidence = (confidence + other.confidence) / 2,
            lastSeen = maxOf(lastSeen, other.lastSeen),
            observations = observations + 1
        )
    }
}