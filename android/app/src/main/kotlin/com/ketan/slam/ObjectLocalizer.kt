package com.ketan.slam

import android.graphics.RectF
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Estimates the 3D world position of a YOLO detection.
 *
 * Two strategies, tried in order:
 * 1. ARCore hit-test at the detection's bounding-box centre (most accurate).
 * 2. Area-based depth heuristic using a continuous inverse-sqrt model (fallback).
 *
 * Key fix from the original estimate3D: correct coordinate mapping between
 * YOLO's rotated/padded image space and ARCore's screen-space hit-test.
 *
 * The YOLO pipeline transforms the camera frame as follows:
 *   640×480 landscape YUV → rotate 90° CW → 480×640 portrait → pad to 640×640
 * So bbox coordinates from YOLO are in the 640×640 padded space.
 * The actual image content occupies columns [80, 560) in that space (480px wide).
 *
 * ARCore's hitTest expects screen-space pixel coordinates matching the
 * GL surface dimensions.
 */
class ObjectLocalizer(private val yoloInputSize: Int = 640) {

    /**
     * Estimate 3D position for a detection.
     *
     * @param bbox          YOLO bounding box in 640×640 padded space
     * @param cameraPose    ARCore camera pose at detection time
     * @param imgW          original camera image width (640 landscape)
     * @param imgH          original camera image height (480 landscape)
     * @param frame         ARCore frame for hit-testing (may be null)
     * @param surfaceWidth  GL surface width in pixels
     * @param surfaceHeight GL surface height in pixels
     */
    fun estimate3D(
        bbox: RectF,
        cameraPose: com.google.ar.core.Pose,
        imgW: Int,
        imgH: Int,
        frame: Frame?,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): Point3D? {
        return try {
            // Strategy 1: ARCore hit-test
            if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                val hitResult = tryHitTest(bbox, frame, imgW, imgH, surfaceWidth, surfaceHeight)
                if (hitResult != null) return hitResult
            }

            // Strategy 2: Area-based depth fallback
            areaBasedFallback(bbox, cameraPose, imgW, imgH)
        } catch (_: Exception) { null }
    }

    /**
     * Perform ARCore hit-test with correct coordinate mapping.
     *
     * YOLO bbox is in 640×640 padded space. The original camera is 640×480 landscape,
     * rotated 90° CW to 480×640 portrait, then padded with 80px on each side to 640×640.
     *
     * To map bbox center to screen coordinates:
     * 1. Remove padding: effective X in portrait = bbox.centerX - padX
     * 2. Normalize to [0,1] in portrait space
     * 3. Map to GL surface coordinates
     */
    private fun tryHitTest(
        bbox: RectF,
        frame: Frame,
        imgW: Int,
        imgH: Int,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): Point3D? {
        // The rotated image is imgH×imgW = 480×640 in portrait
        // Padding: (640 - 480) / 2 = 80 pixels on each side horizontally
        val rotatedW = imgH   // 480
        val padX = (yoloInputSize - rotatedW) / 2  // 80

        // Bbox center in the padded 640×640 space
        val bboxCX = bbox.centerX()
        val bboxCY = bbox.centerY()

        // Remove padding and normalize to [0, 1] in the portrait image
        val normX = ((bboxCX - padX) / rotatedW).coerceIn(0f, 1f)
        val normY = (bboxCY / yoloInputSize.toFloat()).coerceIn(0f, 1f)

        // Map to screen coordinates
        val screenX = normX * surfaceWidth
        val screenY = normY * surfaceHeight

        val hits = frame.hitTest(screenX, screenY)
        val bestHit = hits.firstOrNull { h ->
            val t = h.trackable
            (t is Plane && t.isPoseInPolygon(h.hitPose) && h.distance <= 8f) ||
                t is com.google.ar.core.DepthPoint
        }

        if (bestHit != null) {
            val hp = bestHit.hitPose
            return Point3D(hp.tx(), hp.ty(), hp.tz())
        }
        return null
    }

    /**
     * Fallback: estimate depth from bounding box area using a continuous
     * inverse-sqrt model. More accurate than the previous 4-bucket approach.
     *
     * Calibrated for typical indoor objects (0.3m–1.5m real-world size):
     * - Large bbox (fills 30% of image) → ~1m away
     * - Small bbox (fills 1% of image) → ~5m away
     */
    private fun areaBasedFallback(
        bbox: RectF,
        cameraPose: com.google.ar.core.Pose,
        imgW: Int,
        imgH: Int
    ): Point3D {
        val area = (bbox.width() * bbox.height()) / (yoloInputSize.toFloat() * yoloInputSize.toFloat())
        val depth = (0.5f / sqrt(area.coerceAtLeast(0.001f))).coerceIn(0.8f, 6.0f)

        // Project along camera's forward axis (ARCore: -Z is forward)
        val q = cameraPose.rotationQuaternion
        val fx = 2f * (q[0] * q[2] + q[1] * q[3])
        val fy = 2f * (q[1] * q[2] - q[0] * q[3])
        val fz = 1f - 2f * (q[0] * q[0] + q[1] * q[1])

        return Point3D(
            cameraPose.tx() + fx * depth,
            cameraPose.ty() + fy * depth,
            cameraPose.tz() - fz * depth
        )
    }

    companion object {
        /**
         * Get the obstacle footprint half-size (metres) for a given object type.
         */
        fun footprintHalfMetres(objectType: String): Float = when (objectType.uppercase()) {
            "DOOR"              -> 0.45f
            "WINDOW"            -> 0.50f
            "NOTICE_BOARD"      -> 0.35f
            "FIRE_EXTINGUISHER" -> 0.20f
            "LIFT_GATE"         -> 0.60f
            "WATER_PURIFIER"    -> 0.25f
            "TRASH_CAN"         -> 0.20f
            "CHAIR"             -> 0.25f
            // OCR text landmarks — small footprint (signs on walls)
            "EXIT_SIGN"         -> 0.15f
            "WASHROOM_SIGN"     -> 0.15f
            "STAIRS_SIGN"       -> 0.15f
            "ROOM_LABEL"        -> 0.10f
            "FACILITY_SIGN"     -> 0.15f
            "WARNING_SIGN"      -> 0.15f
            "TEXT_SIGN"         -> 0.10f
            else                -> 0.30f
        }
    }
}
