package com.ketan.slam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * A real-time compass overlay for the AR scan screen.
 *
 * Uses Android's TYPE_ROTATION_VECTOR sensor (fused accelerometer + gyroscope +
 * magnetometer) for true-north bearing. This gives accurate, drift-free compass
 * direction independent of ARCore's coordinate frame.
 *
 * The compass needle always points to magnetic north. Cardinal directions
 * (N, NE, E, SE, S, SW, W, NW) are shown, and the current bearing in degrees
 * is displayed below the compass.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Bearing in degrees from true north (0 = N, 90 = E, 180 = S, 270 = W)
    @Volatile
    var bearingDegrees: Float = 0f
        private set

    // Smoothed bearing for rendering (avoids jitter)
    private var smoothBearing: Float = 0f

    // Reusable arrays for sensor computation
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // ── Paints (pre-allocated) ───────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD1A1A2E.toInt()
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt()
        style = Paint.Style.FILL
    }
    private val southPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA9CA3AF.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val northLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt()
        textSize = 12f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bearingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val directionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBFDBFE.toInt()   // light blue
        textSize = 9f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Reusable paths
    private val northPath = Path()
    private val southPath = Path()

    // ── Sensor lifecycle ─────────────────────────────────────────────────────

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // orientation[0] = azimuth in radians (-π to π), 0 = north
        val azimuthRad = orientation[0]
        val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        bearingDegrees = (azimuthDeg + 360f) % 360f

        // Low-pass filter for smooth rendering
        smoothBearing = lowPassAngle(smoothBearing, bearingDegrees, 0.15f)

        // Request repaint — throttled by the system to ~60fps max
        postInvalidateOnAnimation()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = width / 2f   // keep it square
        val radius = cx * 0.72f

        // Scale label paints based on view size
        val scale = width / 120f
        labelPaint.textSize = 9f * scale
        northLabelPaint.textSize = 11f * scale
        bearingPaint.textSize = 11f * scale
        directionPaint.textSize = 8.5f * scale
        tickPaint.strokeWidth = 1f * scale
        ringPaint.strokeWidth = 1.5f * scale

        // Background circle
        canvas.drawCircle(cx, cy, cx - 2, bgPaint)
        canvas.drawCircle(cx, cy, radius + 2 * scale, ringPaint)

        canvas.save()
        canvas.translate(cx, cy)

        // Rotate the entire compass so north needle points to true north
        // (negative bearing: the dial rotates opposite to user's facing direction)
        canvas.rotate(-smoothBearing)

        // Tick marks every 30 degrees
        for (i in 0 until 12) {
            val angle = i * 30f
            canvas.save()
            canvas.rotate(angle)
            val len = if (i % 3 == 0) 6f * scale else 3.5f * scale
            canvas.drawLine(0f, -(radius - 1 * scale), 0f, -(radius - 1 * scale - len), tickPaint)
            canvas.restore()
        }

        // Cardinal & intercardinal labels
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val angles = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        val labelR = radius - 12f * scale

        for (i in labels.indices) {
            canvas.save()
            canvas.rotate(angles[i])
            canvas.translate(0f, -labelR)
            canvas.rotate(-angles[i])           // un-rotate so text is upright
            canvas.rotate(smoothBearing)        // counter the compass rotation
            val p = if (labels[i] == "N") northLabelPaint else labelPaint
            canvas.drawText(labels[i], 0f, p.textSize * 0.35f, p)
            canvas.restore()
        }

        // North needle (red triangle pointing up)
        val needleLen = radius * 0.52f
        val needleW = 5f * scale
        northPath.reset()
        northPath.moveTo(0f, -needleLen)
        northPath.lineTo(-needleW, 3f * scale)
        northPath.lineTo(0f, -1f * scale)
        northPath.lineTo(needleW, 3f * scale)
        northPath.close()
        canvas.drawPath(northPath, northPaint)

        // South needle (gray)
        southPath.reset()
        southPath.moveTo(0f, needleLen)
        southPath.lineTo(-needleW, -3f * scale)
        southPath.lineTo(0f, 1f * scale)
        southPath.lineTo(needleW, -3f * scale)
        southPath.close()
        canvas.drawPath(southPath, southPaint)

        canvas.restore()

        // Center dot
        canvas.drawCircle(cx, cy, 3f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // Bearing text below compass
        val degInt = smoothBearing.roundToInt() % 360
        val dir = cardinalName(degInt)
        val bearingText = "${degInt}° $dir"
        canvas.drawText(bearingText, cx, cy + cx + bearingPaint.textSize + 2 * scale, bearingPaint)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun cardinalName(deg: Int): String {
        val d = ((deg % 360) + 360) % 360
        return when {
            d < 23   -> "N"
            d < 68   -> "NE"
            d < 113  -> "E"
            d < 158  -> "SE"
            d < 203  -> "S"
            d < 248  -> "SW"
            d < 293  -> "W"
            d < 338  -> "NW"
            else     -> "N"
        }
    }

    /**
     * Low-pass filter for angular values (handles 0/360 wrap-around).
     * Returns smoothed angle in [0, 360).
     */
    private fun lowPassAngle(current: Float, target: Float, alpha: Float): Float {
        var delta = target - current
        // Shortest-path wrap
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return ((current + alpha * delta) + 360f) % 360f
    }
}
