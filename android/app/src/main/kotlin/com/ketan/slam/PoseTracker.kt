package com.ketan.slam

import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Tracks camera pose history, detects significant movement for keyframe
 * selection, and monitors reference anchors for drift detection.
 *
 * Drift detection works by placing ARCore [Anchor]s at known positions.
 * When ARCore internally corrects its map (e.g. recognizing a revisited area),
 * anchors shift — the delta tells us the coordinate frame has been adjusted,
 * which should trigger a grid rebuild in [MapBuilder].
 */
class PoseTracker {

    companion object {
        /** Minimum translation (metres) between keyframes. */
        private const val KF_TRANSLATION_THRESHOLD = 0.15f
        /** Minimum rotation (radians) between keyframes. */
        private const val KF_ROTATION_THRESHOLD = 0.17f  // ~10°
        /** Minimum time (ms) between keyframes. */
        private const val KF_MIN_INTERVAL_MS = 200L
        /** Drift magnitude (metres) that triggers a grid rebuild. */
        private const val DRIFT_REBUILD_THRESHOLD = 0.05f
        /** Maximum reference anchors to maintain. */
        private const val MAX_ANCHORS = 3
        /** Distance (metres) the user must travel before placing the next anchor. */
        private const val ANCHOR_SPACING = 5.0f
    }

    // Pose history
    private val poseHistory = ArrayDeque<Point3D>(5000)
    private var currentPosition = Point3D(0f, 0f, 0f)
    private var totalDistance = 0f

    // Keyframe gating
    private var lastKeyframePose = Point3D(0f, 0f, 0f)
    private var lastKeyframeHeading = 0f
    private var lastKeyframeTimeMs = 0L

    // Reference anchors for drift detection
    private data class RefAnchor(
        val anchor: Anchor,
        val creationX: Float,
        val creationY: Float,
        val creationZ: Float
    )

    private val refAnchors = mutableListOf<RefAnchor>()
    private var distSinceLastAnchor = 0f

    // ── Pose update ────────────────────────────────────────────────────────────

    @Synchronized
    fun addPose(position: Point3D) {
        val prev = if (poseHistory.isEmpty()) position else poseHistory.last()
        val delta = position.distance(prev)
        if (delta < 0.02f && poseHistory.isNotEmpty()) return

        totalDistance += delta
        distSinceLastAnchor += delta
        currentPosition = position

        if (poseHistory.size >= 5000) poseHistory.removeFirst()
        poseHistory.addLast(position)
    }

    /** Returns true if enough movement/rotation/time has elapsed for a new keyframe. */
    fun shouldCaptureKeyframe(px: Float, pz: Float, headingRad: Float): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeTimeMs < KF_MIN_INTERVAL_MS) return false

        val translationDelta = sqrt(
            (px - lastKeyframePose.x) * (px - lastKeyframePose.x) +
            (pz - lastKeyframePose.z) * (pz - lastKeyframePose.z)
        )
        var rotDelta = kotlin.math.abs(headingRad - lastKeyframeHeading)
        if (rotDelta > Math.PI.toFloat()) rotDelta = (2 * Math.PI).toFloat() - rotDelta

        return translationDelta >= KF_TRANSLATION_THRESHOLD || rotDelta >= KF_ROTATION_THRESHOLD
    }

    fun markKeyframeCaptured(px: Float, pz: Float, headingRad: Float) {
        lastKeyframePose = Point3D(px, 0f, pz)
        lastKeyframeHeading = headingRad
        lastKeyframeTimeMs = System.currentTimeMillis()
    }

    // ── Reference anchors ──────────────────────────────────────────────────────

    /**
     * Attempt to place a reference anchor at the current pose.
     * Anchors are spaced at least [ANCHOR_SPACING] metres apart.
     */
    fun maybeCreateAnchor(session: Session, frame: Frame) {
        if (distSinceLastAnchor < ANCHOR_SPACING) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        try {
            val pose = frame.camera.pose
            val anchor = session.createAnchor(pose)
            refAnchors.add(RefAnchor(anchor, pose.tx(), pose.ty(), pose.tz()))
            distSinceLastAnchor = 0f

            // Cap at MAX_ANCHORS — detach oldest
            while (refAnchors.size > MAX_ANCHORS) {
                refAnchors.removeFirst().anchor.detach()
            }
        } catch (_: Exception) { /* anchor creation can fail if tracking is unstable */ }
    }

    /**
     * Check all reference anchors for drift. Returns the maximum drift
     * distance observed across all anchors. If > [DRIFT_REBUILD_THRESHOLD],
     * the caller should trigger a grid rebuild.
     */
    fun checkDrift(): Float {
        var maxDrift = 0f
        for (ref in refAnchors) {
            if (ref.anchor.trackingState != TrackingState.TRACKING) continue
            val currentPose = ref.anchor.pose
            val dx = currentPose.tx() - ref.creationX
            val dy = currentPose.ty() - ref.creationY
            val dz = currentPose.tz() - ref.creationZ
            val drift = sqrt(dx * dx + dy * dy + dz * dz)
            if (drift > maxDrift) maxDrift = drift
        }
        return maxDrift
    }

    /** True if drift exceeds the rebuild threshold. */
    fun hasDriftExceededThreshold(): Boolean = checkDrift() > DRIFT_REBUILD_THRESHOLD

    // ── Accessors ──────────────────────────────────────────────────────────────

    fun getPoseHistory(): List<Point3D> = synchronized(this) { poseHistory.toList() }
    fun getCurrentPosition(): Point3D = currentPosition
    fun getTotalDistance(): Float = totalDistance

    fun destroy() {
        refAnchors.forEach { it.anchor.detach() }
        refAnchors.clear()
    }
}
