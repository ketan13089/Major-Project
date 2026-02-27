package com.ketan.slam

import android.content.Context
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// DetectionConfirmationGate
//
// An object is only passed to the map after being seen ≥ requiredHits times
// in roughly the same image region (IoU ≥ minIoU) within a sliding windowMs.
// This eliminates single-frame hallucinations and motion-blur ghosts.
// ─────────────────────────────────────────────────────────────────────────────

private data class Candidate(
    val label:      String,
    var box:        RectF,
    var count:      Int,
    var bestConf:   Float,
    var lastSeenMs: Long
)

class DetectionConfirmationGate(
    private val requiredHits: Int   = 3,
    private val windowMs:     Long  = 4_000L,
    private val minIoU:       Float = 0.35f
) {
    private val candidates = mutableListOf<Candidate>()

    fun feed(detections: List<YoloDetector.Detection>): List<YoloDetector.Detection> {
        val now = System.currentTimeMillis()
        candidates.removeAll { now - it.lastSeenMs > windowMs }

        val confirmed = mutableListOf<YoloDetector.Detection>()

        for (det in detections) {
            val match = candidates.firstOrNull { c ->
                c.label == det.label && iou(c.box, det.boundingBox) >= minIoU
            }
            if (match != null) {
                match.box       = lerp(match.box, det.boundingBox, 0.3f)
                match.count++
                match.lastSeenMs = now
                match.bestConf  = maxOf(match.bestConf, det.confidence)
                if (match.count >= requiredHits) {
                    confirmed += YoloDetector.Detection(match.label, match.bestConf, RectF(match.box))
                }
            } else {
                candidates += Candidate(det.label, RectF(det.boundingBox), 1, det.confidence, now)
            }
        }
        return confirmed
    }

    fun clear() = candidates.clear()

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(a.left, b.left); val iy = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right); val iy2 = minOf(a.bottom, b.bottom)
        if (ix2 <= ix || iy2 <= iy) return 0f
        val inter = (ix2 - ix) * (iy2 - iy)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun lerp(a: RectF, b: RectF, t: Float) = RectF(
        a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// YoloDetector — pure inference, no confirmation state
// ─────────────────────────────────────────────────────────────────────────────

class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.60f  // gate filters further with 3-hit rule
        private const val IOU_THRESHOLD = 0.45f
        private const val MODEL_FILE = "indoor_nav_best_float16.tflite"

        private const val MIN_BOX_FRACTION = 0.004f
        private const val MAX_BOX_FRACTION = 0.80f
        private const val MIN_ASPECT = 0.10f
        private const val MAX_ASPECT = 9.0f
    }

    private var interpreter: Interpreter? = null
    private val allLabels = arrayOf(
        "chair", "door", "fire_extinguisher", "lift_gate",
        "notice_board", "trash_can", "water_purifier", "window"
    )
    private var numClasses = 0
    private lateinit var labels: Array<String>

    init { loadModel(context) }

    data class Detection(val label: String, val confidence: Float, val boundingBox: RectF)

    fun detectFromYuv(
        yBuffer: ByteArray, uBuffer: ByteArray, vBuffer: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixStride: Int,
        imageWidth: Int, imageHeight: Int
    ): List<Detection> {
        val interp = interpreter ?: return emptyList()
        if (numClasses == 0) return emptyList()
        return try {
            val input = yuvToRgbFloatBuffer(yBuffer, uBuffer, vBuffer,
                yRowStride, uvRowStride, uvPixStride, imageWidth, imageHeight)
            val outShape = interp.getOutputTensor(0).shape()
            val raw = when {
                outShape.size == 3 && outShape[1] == (4 + numClasses) -> {
                    val n = outShape[2]
                    val out = Array(1) { Array(4 + numClasses) { FloatArray(n) } }
                    interp.run(input, out); parseChannelsFirst(out[0], n)
                }
                outShape.size == 3 && outShape[2] == (4 + numClasses) -> {
                    val n = outShape[1]
                    val out = Array(1) { Array(n) { FloatArray(4 + numClasses) } }
                    interp.run(input, out); parseChannelsLast(out[0], n)
                }
                else -> emptyList()
            }
            applyNMS(raw)
        } catch (e: Exception) {
            println("$TAG: inference: ${e.message}"); emptyList()
        }
    }

    // Shared camera: 640×480 landscape → rotate 90° CW → 480×640 → pad → 640×640
    private fun yuvToRgbFloatBuffer(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixStride: Int,
        srcWidth: Int, srcHeight: Int
    ): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
        val rotW = srcHeight
        val padX = (INPUT_SIZE - rotW) / 2
        for (py in 0 until INPUT_SIZE) {
            for (px in 0 until INPUT_SIZE) {
                val rotX = px - padX
                if (rotX < 0 || rotX >= rotW) { buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f); continue }
                val srcX  = (srcHeight - 1 - py).coerceIn(0, srcHeight - 1)
                val srcY  = rotX.coerceIn(0, srcWidth - 1)
                val yVal  = yBytes[srcY * yRowStride + srcX].toInt() and 0xFF
                val uvIdx = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixStride
                val uVal  = uBytes[uvIdx].toInt() and 0xFF
                val vVal  = vBytes[uvIdx].toInt() and 0xFF
                val yS = yVal - 16; val uS = uVal - 128; val vS = vVal - 128
                buf.putFloat((1.164f * yS + 1.596f * vS).toInt().coerceIn(0, 255) / 255f)
                buf.putFloat((1.164f * yS - 0.392f * uS - 0.813f * vS).toInt().coerceIn(0, 255) / 255f)
                buf.putFloat((1.164f * yS + 2.017f * uS).toInt().coerceIn(0, 255) / 255f)
            }
        }
        buf.rewind(); return buf
    }

    private fun parseChannelsFirst(output: Array<FloatArray>, n: Int): List<Detection> {
        val r = mutableListOf<Detection>()
        for (i in 0 until n) {
            var maxC = 0f; var best = 0
            for (c in 0 until numClasses) { val v = output[4+c][i]; if (v > maxC) { maxC = v; best = c } }
            if (maxC < CONFIDENCE_THRESHOLD) continue
            val box = xywh2xyxy(output[0][i], output[1][i], output[2][i], output[3][i]) ?: continue
            if (isPlausible(box)) r += Detection(labels[best], maxC, box)
        }
        return r
    }

    private fun parseChannelsLast(output: Array<FloatArray>, n: Int): List<Detection> {
        val r = mutableListOf<Detection>()
        for (i in 0 until n) {
            var maxC = 0f; var best = 0
            for (c in 0 until numClasses) { val v = output[i][4+c]; if (v > maxC) { maxC = v; best = c } }
            if (maxC < CONFIDENCE_THRESHOLD) continue
            val box = xywh2xyxy(output[i][0], output[i][1], output[i][2], output[i][3]) ?: continue
            if (isPlausible(box)) r += Detection(labels[best], maxC, box)
        }
        return r
    }

    private fun xywh2xyxy(cx: Float, cy: Float, w: Float, h: Float): RectF? {
        val s = INPUT_SIZE.toFloat()
        val box = RectF(((cx-w/2f)*s).coerceIn(0f,s), ((cy-h/2f)*s).coerceIn(0f,s),
            ((cx+w/2f)*s).coerceIn(0f,s), ((cy+h/2f)*s).coerceIn(0f,s))
        return if (box.width() < 8f || box.height() < 8f) null else box
    }

    private fun isPlausible(box: RectF): Boolean {
        val s = INPUT_SIZE.toFloat()
        val fr = (box.width() * box.height()) / (s * s)
        if (fr < MIN_BOX_FRACTION || fr > MAX_BOX_FRACTION) return false
        val asp = box.width() / box.height().coerceAtLeast(1f)
        return asp in MIN_ASPECT..MAX_ASPECT
    }

    private fun applyNMS(d: List<Detection>): List<Detection> {
        val sorted = d.sortedByDescending { it.confidence }
        val kept = mutableListOf<Detection>()
        for (det in sorted)
            if (kept.none { it.label == det.label && iou(det.boundingBox, it.boundingBox) > IOU_THRESHOLD }) kept += det
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(a.left, b.left); val iy = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right); val iy2 = minOf(a.bottom, b.bottom)
        if (ix2 <= ix || iy2 <= iy) return 0f
        val inter = (ix2 - ix) * (iy2 - iy)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private fun loadModel(context: Context) {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4; useNNAPI = false })
            val outShape = interpreter!!.getOutputTensor(0).shape()
            println("$TAG: in=${interpreter!!.getInputTensor(0).shape().toList()} out=${outShape.toList()}")
            numClasses = if (outShape.size >= 3) maxOf(minOf(outShape[1], outShape[2]) - 4, 1) else allLabels.size
            labels = if (numClasses <= allLabels.size) allLabels.sliceArray(0 until numClasses)
            else Array(numClasses) { allLabels.getOrElse(it) { "obj_$it" } }
            println("$TAG: classes=$numClasses labels=${labels.toList()} threshold=$CONFIDENCE_THRESHOLD")
        } catch (e: Exception) { println("$TAG: load failed: ${e.message}"); e.printStackTrace() }
    }

    fun close() { interpreter?.close(); interpreter = null }
}

fun RectF.toRect(): android.graphics.Rect =
    android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())