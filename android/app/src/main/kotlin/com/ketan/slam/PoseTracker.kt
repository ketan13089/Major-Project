package com.ketan.slam

import android.util.Log
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
 *
 * Also provides:
 * - Pose consistency checking via rolling-window velocity analysis
 * - Drift correction offsets that can be applied to the occupancy grid
 * - Breadcrumb trail for retrace-path navigation
 */
class PoseTracker {

    companion object {
        private const val TAG = "PoseTracker"
        /** Minimum translation (metres) between keyframes. */
        private const val KF_TRANSLATION_THRESHOLD = 0.15f
        /** Minimum rotation (radians) between keyframes. */
        private const val KF_ROTATION_THRESHOLD = 0.17f  // ~10°
        /** Minimum time (ms) between keyframes. */
        private const val KF_MIN_INTERVAL_MS = 200L
        /** Drift magnitude (metres) that triggers a grid rebuild. */
        private const val DRIFT_REBUILD_THRESHOLD = 0.04f
        /** Maximum reference anchors to maintain. */
        private const val MAX_ANCHORS = 6
        /** Distance (metres) the user must travel before placing the next anchor. */
        private const val ANCHOR_SPACING = 3.0f
        /** Max reasonable walking speed (m/s) — poses jumping faster are suspect. */
        private const val MAX_WALK_SPEED = 3.0f
        /** Rolling window size for pose consistency check. */
        private const val CONSISTENCY_WINDOW = 5
        /** Minimum spacing (metres) between breadcrumb points. */
        private const val BREADCRUMB_SPACING = 0.5f
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

    // Rolling-window pose consistency
    private data class TimedPose(val position: Point3D, val timeMs: Long)
    private val recentPoses = ArrayDeque<TimedPose>(CONSISTENCY_WINDOW + 1)
    @Volatile var isPoseConsistent = true
        private set

    // Drift correction — accumulated offset from anchor drift
    @Volatile var driftOffsetX = 0f; private set
    @Volatile var driftOffsetZ = 0f; private set

    // Breadcrumb trail for retrace navigation
    private val breadcrumbs = mutableListOf<Point3D>()
    private var lastBreadcrumbPos = Point3D(0f, 0f, 0f)
    @Volatile var isRecordingTrail = true

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

        // Rolling-window pose consistency check
        val now = System.currentTimeMillis()
        recentPoses.addLast(TimedPose(position, now))
        if (recentPoses.size > CONSISTENCY_WINDOW) recentPoses.removeFirst()
        checkPoseConsistency()

        // Record breadcrumb
        if (isRecordingTrail) {
            val bcDist = sqrt(
                (position.x - lastBreadcrumbPos.x) * (position.x - lastBreadcrumbPos.x) +
                (position.z - lastBreadcrumbPos.z) * (position.z - lastBreadcrumbPos.z)
            )
            if (breadcrumbs.isEmpty() || bcDist >= BREADCRUMB_SPACING) {
                breadcrumbs.add(Point3D(position.x, position.y, position.z))
                lastBreadcrumbPos = position
            }
        }
    }

    /**
     * Check that recent poses are physically plausible.
     * Flags isPoseConsistent = false if user appears to teleport.
     */
    private fun checkPoseConsistency() {
        if (recentPoses.size < 2) { isPoseConsistent = true; return }
        val first = recentPoses.first()
        val last = recentPoses.last()
        val dt = (last.timeMs - first.timeMs) / 1000f
        if (dt < 0.01f) return
        val dist = first.position.distance(last.position)
        val speed = dist / dt
        val wasConsistent = isPoseConsistent
        isPoseConsistent = speed <= MAX_WALK_SPEED
        if (wasConsistent && !isPoseConsistent) {
            Log.w(TAG, "Pose inconsistency: speed=${"%.1f".format(speed)} m/s over ${"%.2f".format(dt)}s")
        }
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
     *
     * Also updates the accumulated drift offset from the most-drifted anchor,
     * which can be used to correct semantic object positions.
     */
    fun checkDrift(): Float {
        var maxDrift = 0f
        var bestDx = 0f; var bestDz = 0f
        for (ref in refAnchors) {
            if (ref.anchor.trackingState != TrackingState.TRACKING) continue
            val currentPose = ref.anchor.pose
            val dx = currentPose.tx() - ref.creationX
            val dy = currentPose.ty() - ref.creationY
            val dz = currentPose.tz() - ref.creationZ
            val drift = sqrt(dx * dx + dy * dy + dz * dz)
            if (drift > maxDrift) {
                maxDrift = drift
                bestDx = dx; bestDz = dz
            }
        }
        if (maxDrift > DRIFT_REBUILD_THRESHOLD) {
            driftOffsetX = bestDx
            driftOffsetZ = bestDz
            Log.d(TAG, "Drift detected: ${"%.3f".format(maxDrift)}m " +
                    "(dx=${"%.3f".format(bestDx)}, dz=${"%.3f".format(bestDz)})")
        }
        return maxDrift
    }

    /** True if drift exceeds the rebuild threshold. */
    fun hasDriftExceededThreshold(): Boolean = checkDrift() > DRIFT_REBUILD_THRESHOLD

    /**
     * After a grid rebuild that accounted for drift, call this to re-anchor
     * the reference points at their current (corrected) positions.
     */
    fun resetAnchorsAfterRebuild() {
        val toReplace = mutableListOf<Pair<Int, RefAnchor>>()
        for ((i, ref) in refAnchors.withIndex()) {
            if (ref.anchor.trackingState != TrackingState.TRACKING) continue
            val p = ref.anchor.pose
            toReplace.add(i to RefAnchor(ref.anchor, p.tx(), p.ty(), p.tz()))
        }
        for ((i, newRef) in toReplace) refAnchors[i] = newRef
        driftOffsetX = 0f
        driftOffsetZ = 0f
    }

    // ── Breadcrumb trail ─────────────────────────────────────────────────────

    /** Get the recorded breadcrumb trail (copy). */
    fun getBreadcrumbs(): List<Point3D> = synchronized(this) { breadcrumbs.toList() }

    /** Clear the breadcrumb trail (e.g., after retrace completes). */
    fun clearBreadcrumbs() = synchronized(this) { breadcrumbs.clear() }

    // ── Accessors ──────────────────────────────────────────────────────────────

    fun getPoseHistory(): List<Point3D> = synchronized(this) { poseHistory.toList() }
    fun getCurrentPosition(): Point3D = currentPosition
    fun getTotalDistance(): Float = totalDistance

    fun destroy() {
        refAnchors.forEach { it.anchor.detach() }
        refAnchors.clear()
    }
}
