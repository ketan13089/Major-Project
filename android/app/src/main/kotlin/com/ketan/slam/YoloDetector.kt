package com.ketan.slam

import android.content.Context
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.5f
        private const val MODEL_FILE = "indoor_nav_best_float16.tflite"
    }

    private var interpreter: Interpreter? = null

    // Order must exactly match the 'names' list in data.yaml:
    // ['chair', 'door', 'fire_extinguisher', 'lift_gate',
    //  'notice_board', 'trash_can', 'water_purifier', 'window']
    private val allLabels = arrayOf(
        "chair",             // 0
        "door",              // 1
        "fire_extinguisher", // 2
        "lift_gate",         // 3
        "notice_board",      // 4
        "trash_can",         // 5
        "water_purifier",    // 6
        "window"             // 7
    )

    private var numClasses: Int = 0
    private lateinit var labels: Array<String>

    init {
        loadModel(context)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Model loading
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadModel(context: Context) {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                numThreads = 4
                useNNAPI = false
            }
            interpreter = Interpreter(model, options)

            val inputTensor  = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outShape     = outputTensor.shape()

            println("$TAG: Input shape:  ${inputTensor.shape().toList()}")
            println("$TAG: Output shape: ${outShape.toList()}")

            numClasses = if (outShape.size >= 3) {
                val dim1 = outShape[1]
                val dim2 = outShape[2]
                val channelDim = minOf(dim1, dim2)
                maxOf(channelDim - 4, 1)
            } else {
                allLabels.size
            }

            labels = if (numClasses <= allLabels.size) {
                allLabels.sliceArray(0 until numClasses)
            } else {
                Array(numClasses) { i -> allLabels.getOrElse(i) { "object_$i" } }
            }

            println("$TAG: numClasses resolved to $numClasses, labels: ${labels.toList()}")
            println("$TAG: Model loaded OK")

        } catch (e: Exception) {
            println("$TAG: Load failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    data class Detection(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    /**
     * Primary detection path — accepts raw YUV420 planes directly from ARCore.
     * No Bitmap allocation, no JPEG compression, lossless pixels.
     */
    fun detectFromYuv(
        yBuffer: ByteArray, uBuffer: ByteArray, vBuffer: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixStride: Int,
        imageWidth: Int, imageHeight: Int
    ): List<Detection> {
        val interp = interpreter ?: run {
            println("$TAG: Interpreter null, skipping")
            return emptyList()
        }
        if (numClasses == 0) {
            println("$TAG: numClasses not resolved, skipping")
            return emptyList()
        }

        return try {
            val inputBuffer = yuvToRgbFloatBuffer(
                yBuffer, uBuffer, vBuffer,
                yRowStride, uvRowStride, uvPixStride,
                imageWidth, imageHeight
            )

            val outShape = interp.getOutputTensor(0).shape()
            println("$TAG: Running inference, output shape: ${outShape.toList()}")

            val detections = when {
                outShape.size == 3 && outShape[1] == (4 + numClasses) -> {
                    val numAnchors = outShape[2]
                    val output = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }
                    interp.run(inputBuffer, output)
                    // After 90° CW rotation width/height are swapped
                    parseChannelsFirst(output[0], imageWidth, imageHeight, numAnchors)
                }
                outShape.size == 3 && outShape[2] == (4 + numClasses) -> {
                    val numAnchors = outShape[1]
                    val output = Array(1) { Array(numAnchors) { FloatArray(4 + numClasses) } }
                    interp.run(inputBuffer, output)
                    parseChannelsLast(output[0], imageWidth, imageHeight, numAnchors)
                }
                else -> {
                    println("$TAG: Unexpected output shape ${outShape.toList()}, " +
                            "expected second or third dim == ${4 + numClasses}. Skipping frame.")
                    emptyList()
                }
            }
            applyNMS(detections)
        } catch (e: Exception) {
            println("$TAG: detectFromYuv exception: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // YUV → RGB float buffer (no Bitmap, no JPEG, lossless)
    // ═════════════════════════════════════════════════════════════════════════

    private fun yuvToRgbFloatBuffer(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixStride: Int,
        srcWidth: Int, srcHeight: Int
    ): ByteBuffer {
        val buf = ByteBuffer
            .allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        // Shared camera delivers landscape frames (e.g. 640×480, width > height).
        // Model expects 640×640 trained on portrait images.
        // Strategy:
        //   1. Rotate 90° CW → portrait 480×640
        //   2. Letterbox-pad width to 640×640 with black bars on left/right
        //      (pad = (640-480)/2 = 80px each side)
        // This exactly matches how Roboflow auto-orients and pads during export.

        val rotW = srcHeight   // 480 after 90° CW rotation
        val rotH = srcWidth    // 640 after 90° CW rotation
        val padX = (INPUT_SIZE - rotW) / 2  // 80px left/right letterbox

        for (py in 0 until INPUT_SIZE) {
            for (px in 0 until INPUT_SIZE) {
                val rotX = px - padX  // position in the rotated portrait frame

                if (rotX < 0 || rotX >= rotW) {
                    // Letterbox padding — output black
                    buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
                    continue
                }

                // Map rotated portrait (rotX, py) back to original landscape (srcX, srcY)
                // 90° CW: rotated(x,y) → original(srcHeight-1-y, x)
                val srcX = (srcHeight - 1 - py).coerceIn(0, srcHeight - 1)
                val srcY = rotX.coerceIn(0, srcWidth - 1)

                // Y plane
                val yVal = yBytes[srcY * yRowStride + srcX].toInt() and 0xFF

                // UV planes (2x subsampled)
                val uvX   = srcX / 2
                val uvY   = srcY / 2
                val uvIdx = uvY * uvRowStride + uvX * uvPixStride
                val uVal  = uBytes[uvIdx].toInt() and 0xFF
                val vVal  = vBytes[uvIdx].toInt() and 0xFF

                // BT.601 YUV → RGB
                val yShifted = yVal - 16
                val uShifted = uVal - 128
                val vShifted = vVal - 128

                val r = (1.164f * yShifted + 1.596f * vShifted).toInt().coerceIn(0, 255)
                val g = (1.164f * yShifted - 0.392f * uShifted - 0.813f * vShifted).toInt().coerceIn(0, 255)
                val b = (1.164f * yShifted + 2.017f * uShifted).toInt().coerceIn(0, 255)

                buf.putFloat(r / 255f)
                buf.putFloat(g / 255f)
                buf.putFloat(b / 255f)
            }
        }
        buf.rewind()
        return buf
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Parsers
    // ═════════════════════════════════════════════════════════════════════════

    private fun parseChannelsFirst(
        output: Array<FloatArray>, origW: Int, origH: Int, numAnchors: Int
    ): List<Detection> {
        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx = output[0][i]; val cy = output[1][i]
            val w  = output[2][i]; val h  = output[3][i]
            var maxConf = 0f; var bestCls = 0
            for (c in 0 until numClasses) {
                val v = output[4 + c][i]
                if (v > maxConf) { maxConf = v; bestCls = c }
            }
            if (maxConf < CONFIDENCE_THRESHOLD) continue
            val box = normalizedToPixel(cx, cy, w, h, origW, origH) ?: continue
            results += Detection(labels[bestCls], maxConf, box)
        }
        return results
    }

    private fun parseChannelsLast(
        output: Array<FloatArray>, origW: Int, origH: Int, numAnchors: Int
    ): List<Detection> {
        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx = output[i][0]; val cy = output[i][1]
            val w  = output[i][2]; val h  = output[i][3]
            var maxConf = 0f; var bestCls = 0
            for (c in 0 until numClasses) {
                val v = output[i][4 + c]
                if (v > maxConf) { maxConf = v; bestCls = c }
            }
            if (maxConf < CONFIDENCE_THRESHOLD) continue
            val box = normalizedToPixel(cx, cy, w, h, origW, origH) ?: continue
            results += Detection(labels[bestCls], maxConf, box)
        }
        return results
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun normalizedToPixel(
        cx: Float, cy: Float, w: Float, h: Float, imgW: Int, imgH: Int
    ): RectF? {
        val box = RectF(
            ((cx - w / 2f) * imgW).coerceIn(0f, imgW.toFloat()),
            ((cy - h / 2f) * imgH).coerceIn(0f, imgH.toFloat()),
            ((cx + w / 2f) * imgW).coerceIn(0f, imgW.toFloat()),
            ((cy + h / 2f) * imgH).coerceIn(0f, imgH.toFloat())
        )
        return if (box.width() < 10f || box.height() < 10f) null else box
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val kept   = mutableListOf<Detection>()
        for (det in sorted) {
            if (kept.none {
                    it.label == det.label &&
                            iou(det.boundingBox, it.boundingBox) > IOU_THRESHOLD
                }) kept += det
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix  = maxOf(a.left,  b.left);  val iy  = maxOf(a.top,    b.top)
        val ix2 = minOf(a.right, b.right); val iy2 = minOf(a.bottom, b.bottom)
        if (ix2 <= ix || iy2 <= iy) return 0f
        val inter = (ix2 - ix) * (iy2 - iy)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0) inter / union else 0f
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

fun RectF.toRect(): android.graphics.Rect =
    android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())