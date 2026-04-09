package com.ketan.slam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A transparent overlay that draws YOLO bounding boxes + labels on top of
 * the GLSurfaceView camera feed.
 *
 * Coordinate transformation:
 * 1. YOLO outputs boxes in 640×640 space (after 90° CW rotation + padding)
 * 2. We need to:
 *    a) Remove the padding offset (80px on left/right)
 *    b) Rotate 90° CCW to match screen orientation
 *    c) Scale to screen dimensions
 *
 * Usage:
 *   1. Add it to your FrameLayout on top of the GLSurfaceView.
 *   2. Call updateDetections(detections, yoloSize, padX) from any thread.
 */
class DetectionOverlayView(context: Context) : View(context) {

    data class OverlayDetection(
        val label: String,
        val confidence: Float,
        val boxNorm: RectF      // coordinates normalised to [0,1] relative to screen
    )

    // ── Paints ────────────────────────────────────────────────────────────────
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style  = Paint.Style.STROKE
        strokeWidth = 4f
        color  = Color.CYAN
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAA000000.toInt()       // semi-transparent black label background
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 36f
        isFakeBoldText = true
    }

    // Label-background height derived from text size
    private val labelPadH = 6f
    private val labelPadW = 8f

    // Colour palette — cycles through classes so each label gets its own colour
    private val palette = intArrayOf(
        0xFF00FFFF.toInt(), // cyan
        0xFF00FF00.toInt(), // green
        0xFFFF6600.toInt(), // orange
        0xFFFF00FF.toInt(), // magenta
        0xFFFFFF00.toInt(), // yellow
        0xFF00BFFF.toInt(), // deep sky blue
        0xFFFF4444.toInt(), // red
        0xFF44FF88.toInt(), // mint
    )
    private val labelColour = mutableMapOf<String, Int>()
    private var colourIndex = 0

    // ── State updated from background thread ──────────────────────────────────
    @Volatile private var detections: List<OverlayDetection> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call this whenever a new inference result is ready.
     *
     * @param rawDetections   The list of YoloDetector.Detection objects.
     * @param imageWidth      Width of the original camera image (640).
     * @param imageHeight     Height of the original camera image (480).
     *
     * YOLO coordinate system:
     * - Input: 640×640 (rotated 90° CW from 640×480, padded 80px left/right)
     * - Boxes are in this 640×640 space
     * - We need to reverse the transformation to match screen
     */
    fun updateDetections(
        rawDetections: List<YoloDetector.Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // YOLO input size and padding
        val yoloSize = 640f
        val rotatedWidth = imageHeight.toFloat()  // 480 (width after 90° rotation)
        val padX = (yoloSize - rotatedWidth) / 2f  // 80px padding on each side
        
        detections = rawDetections.mapNotNull { d ->
            // Step 1: Get box in YOLO's 640×640 space
            val yoloLeft = d.boundingBox.left
            val yoloTop = d.boundingBox.top
            val yoloRight = d.boundingBox.right
            val yoloBottom = d.boundingBox.bottom
            
            // Step 2: Remove horizontal padding to get coordinates in 480×640 rotated space
            // X range after removing padding: 0 to 480
            val rotLeft = yoloLeft - padX
            val rotRight = yoloRight - padX
            
            // Skip if box is entirely in padding zone
            if (rotRight <= 0 || rotLeft >= rotatedWidth) return@mapNotNull null
            
            // Clamp to valid range
            val clampedRotLeft = rotLeft.coerceIn(0f, rotatedWidth)
            val clampedRotRight = rotRight.coerceIn(0f, rotatedWidth)
            
            // Step 3: Rotate 90° CCW to match screen orientation
            // Rotation formula for 90° CCW: (x, y) → (y, width - x)
            // In rotated space: width=480, height=640
            // After CCW rotation: width=640, height=480
            val screenLeft = yoloTop
            val screenRight = yoloBottom
            val screenTop = rotatedWidth - clampedRotRight
            val screenBottom = rotatedWidth - clampedRotLeft
            
            // Step 4: Normalize to [0,1] relative to final screen dimensions
            // Screen shows: 640 wide × 480 tall (after rotation)
            OverlayDetection(
                label = d.label,
                confidence = d.confidence,
                boxNorm = RectF(
                    screenLeft / yoloSize,      // X normalized by 640
                    screenTop / rotatedWidth,   // Y normalized by 480
                    screenRight / yoloSize,     // X normalized by 640
                    screenBottom / rotatedWidth // Y normalized by 480
                )
            )
        }
        postInvalidate()    // safe to call from any thread
    }

    fun clearDetections() {
        detections = emptyList()
        postInvalidate()
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = detections
        if (current.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        for (det in current) {
            val colour = colourForLabel(det.label)
            boxPaint.color = colour

            // Scale normalised coords to view size
            val left   = det.boxNorm.left   * vw
            val top    = det.boxNorm.top    * vh
            val right  = det.boxNorm.right  * vw
            val bottom = det.boxNorm.bottom * vh

            // Bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Label text
            val labelText = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val textWidth  = textPaint.measureText(labelText)
            val textHeight = textPaint.descent() - textPaint.ascent()

            val labelLeft   = left
            val labelTop    = (top - textHeight - labelPadH * 2).coerceAtLeast(0f)
            val labelRight  = left + textWidth + labelPadW * 2
            val labelBottom = top

            // Background rect
            fillPaint.color = (colour and 0x00FFFFFF) or 0xCC000000.toInt()
            canvas.drawRect(labelLeft, labelTop, labelRight, labelBottom, fillPaint)

            // Text
            textPaint.color = Color.WHITE
            canvas.drawText(
                labelText,
                labelLeft + labelPadW,
                labelBottom - labelPadH - textPaint.descent(),
                textPaint
            )
        }
    }

    private fun colourForLabel(label: String): Int {
        return labelColour.getOrPut(label) {
            palette[colourIndex % palette.size].also { colourIndex++ }
        }
    }
}