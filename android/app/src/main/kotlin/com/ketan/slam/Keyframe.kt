package com.ketan.slam

import com.google.ar.core.Plane

/**
 * A snapshot of all spatial observations captured at a single moment in time.
 * Keyframes are stored in [ObservationStore] and replayed by [MapBuilder]
 * to produce a drift-corrected occupancy grid.
 */
data class Keyframe(
    val timestamp: Long,
    val poseX: Float,
    val poseY: Float,
    val poseZ: Float,
    val headingRad: Float,
    val forwardX: Float,
    val forwardZ: Float,
    val planes: List<PlaneSnapshot>,
    val objectSightings: List<ObjectSighting>
)

/**
 * Snapshot of an ARCore plane at capture time.
 * Stores the world-space polygon vertices (already transformed from local space)
 * so the plane can be re-rasterized without needing the original ARCore Plane handle.
 */
data class PlaneSnapshot(
    val type: PlaneType,
    /** World-space polygon vertices as (x, z) pairs. */
    val worldVertices: List<Pair<Float, Float>>,
    /** ARCore plane hashCode — used to track identity across frames. */
    val planeId: Int
)

enum class PlaneType {
    HORIZONTAL_FREE,
    VERTICAL_WALL
}

/**
 * Record of a confirmed YOLO detection with its estimated 3D position,
 * captured at the moment the detection was processed.
 */
data class ObjectSighting(
    val label: String,
    val confidence: Float,
    val worldPosition: Point3D,
    val footprintHalfMetres: Float
)
