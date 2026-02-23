package com.ketan.slam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 320
        private const val NUM_CLASSES = 20
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f
        private const val MODEL_FILE = "indoor_objects_model.tflite"
    }

    private var interpreter: Interpreter? = null

    private val labels = arrayOf(
        "door", "window", "lift_gate",
        "chair", "table", "desk", "bed", "couch", "cupboard",
        "laptop", "ac", "tv",
        "toilet", "sink", "notice_board", "sign",
        "coffee_cup", "bottle", "book", "bag"
    )

    init {
        loadModel(context)
    }

    private fun loadModel(context: Context) {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                numThreads = 2
                useNNAPI = false   // NNAPI can be unstable; use XNNPACK via CPU
            }
            interpreter = Interpreter(model, options)

            // Log actual tensor shapes for debugging
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            println("$TAG: Input shape: ${inputTensor.shape().toList()}")
            println("$TAG: Output shape: ${outputTensor.shape().toList()}")
            println("$TAG: Model loaded OK")
        } catch (e: Exception) {
            println("$TAG: Load failed: ${e.message}")
            e.printStackTrace()
        }
    }

    data class Detection(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: run {
            println("$TAG: Interpreter null, skipping")
            return emptyList()
        }

        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = bitmapToBuffer(scaled)
            if (scaled !== bitmap) scaled.recycle()

            // Dynamically read output shape
            val outShape = interp.getOutputTensor(0).shape()
            println("$TAG: Running inference, output shape: ${outShape.toList()}")

            // YOLOv8 exports to TFLite in two common shapes:
            //   [1, 4+numClasses, numAnchors]  – channels-first (transposed)
            //   [1, numAnchors, 4+numClasses]  – channels-last
            // We support both.
            val detections = when {
                outShape.size == 3 && outShape[1] == (4 + NUM_CLASSES) -> {
                    // Channels-first: [1, 24, N]
                    val numAnchors = outShape[2]
                    val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(numAnchors) } }
                    interp.run(inputBuffer, output)
                    parseChannelsFirst(output[0], bitmap.width, bitmap.height, numAnchors)
                }
                outShape.size == 3 && outShape[2] == (4 + NUM_CLASSES) -> {
                    // Channels-last: [1, N, 24]
                    val numAnchors = outShape[1]
                    val output = Array(1) { Array(numAnchors) { FloatArray(4 + NUM_CLASSES) } }
                    interp.run(inputBuffer, output)
                    parseChannelsLast(output[0], bitmap.width, bitmap.height, numAnchors)
                }
                else -> {
                    println("$TAG: Unrecognised output shape ${outShape.toList()}, trying channels-first")
                    val numAnchors = if (outShape.size >= 3) outShape[2] else 2100
                    val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(numAnchors) } }
                    interp.run(inputBuffer, output)
                    parseChannelsFirst(output[0], bitmap.width, bitmap.height, numAnchors)
                }
            }

            applyNMS(detections)
        } catch (e: Exception) {
            println("$TAG: Detect exception: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // Output layout: output[feature][anchor]  where features = [x, y, w, h, cls0..clsN]
    private fun parseChannelsFirst(
        output: Array<FloatArray>,
        origW: Int, origH: Int, numAnchors: Int
    ): List<Detection> {
        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx = output[0][i]
            val cy = output[1][i]
            val w  = output[2][i]
            val h  = output[3][i]

            var maxConf = 0f; var bestCls = 0
            for (c in 0 until NUM_CLASSES) {
                val v = output[4 + c][i]
                if (v > maxConf) { maxConf = v; bestCls = c }
            }
            if (maxConf < CONFIDENCE_THRESHOLD) continue

            val box = normalizedToPixel(cx, cy, w, h, origW, origH) ?: continue
            results += Detection(labels[bestCls], maxConf, box)
        }
        return results
    }

    // Output layout: output[anchor][feature]
    private fun parseChannelsLast(
        output: Array<FloatArray>,
        origW: Int, origH: Int, numAnchors: Int
    ): List<Detection> {
        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx = output[i][0]
            val cy = output[i][1]
            val w  = output[i][2]
            val h  = output[i][3]

            var maxConf = 0f; var bestCls = 0
            for (c in 0 until NUM_CLASSES) {
                val v = output[i][4 + c]
                if (v > maxConf) { maxConf = v; bestCls = c }
            }
            if (maxConf < CONFIDENCE_THRESHOLD) continue

            val box = normalizedToPixel(cx, cy, w, h, origW, origH) ?: continue
            results += Detection(labels[bestCls], maxConf, box)
        }
        return results
    }

    private fun normalizedToPixel(cx: Float, cy: Float, w: Float, h: Float,
                                  imgW: Int, imgH: Int): RectF? {
        val left   = (cx - w / 2f) * imgW
        val top    = (cy - h / 2f) * imgH
        val right  = (cx + w / 2f) * imgW
        val bottom = (cy + h / 2f) * imgH

        val box = RectF(
            left.coerceIn(0f, imgW.toFloat()),
            top.coerceIn(0f, imgH.toFloat()),
            right.coerceIn(0f, imgW.toFloat()),
            bottom.coerceIn(0f, imgH.toFloat())
        )
        return if (box.width() < 10f || box.height() < 10f) null else box
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        pixels.forEach { px ->
            buf.putFloat(((px shr 16) and 0xFF) / 255f)
            buf.putFloat(((px shr 8)  and 0xFF) / 255f)
            buf.putFloat((px          and 0xFF) / 255f)
        }
        return buf
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<Detection>()
        for (det in sorted) {
            if (kept.none { it.label == det.label && iou(det.boundingBox, it.boundingBox) > IOU_THRESHOLD })
                kept += det
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(a.left, b.left); val iy = maxOf(a.top, b.top)
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