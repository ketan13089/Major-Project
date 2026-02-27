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
 * Usage:
 *   1. Add it to your FrameLayout on top of the GLSurfaceView.
 *   2. Call updateDetections(detections, imageWidth, imageHeight) from any thread.
 */
class DetectionOverlayView(context: Context) : View(context) {

    data class OverlayDetection(
        val label: String,
        val confidence: Float,
        val boxNorm: RectF      // coordinates normalised to [0,1] relative to the source image
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
     * @param imageWidth      Width of the bitmap that was fed to YOLO (before scaling).
     * @param imageHeight     Height of the bitmap.
     */
    fun updateDetections(
        rawDetections: List<YoloDetector.Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        detections = rawDetections.map { d ->
            OverlayDetection(
                label       = d.label,
                confidence  = d.confidence,
                // Normalise bounding box to [0,1]
                boxNorm     = RectF(
                    d.boundingBox.left   / w,
                    d.boundingBox.top    / h,
                    d.boundingBox.right  / w,
                    d.boundingBox.bottom / h
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