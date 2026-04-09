package com.ketan.slam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition using Google ML Kit Text Recognition v2.
 *
 * Runs on the same YUV camera frames as YOLO, but at a lower frequency
 * (~every 3 seconds) to avoid impacting ARCore tracking performance.
 *
 * Each recognized text block produces a [TextDetection] containing:
 * - the detected text string
 * - a bounding box in the original 640×480 image space
 * - a confidence proxy (ML Kit doesn't expose per-block confidence,
 *   so we use recognized-character-ratio as a proxy)
 * - a semantic classification (room number, sign, or general text)
 */
class TextRecognizer {

    companion object {
        private const val TAG = "TextRecognizer"

        /** Minimum text length to consider (filters noise). */
        private const val MIN_TEXT_LENGTH = 2

        /** Maximum inference time before we give up (ms). */
        private const val TIMEOUT_MS = 3000L

        /** Room number pattern: digits optionally preceded by "room", "lab", etc. */
        private val ROOM_NUMBER_PATTERN = Regex(
            """(?:room|lab|rm|class|hall|office|cabin)?\s*(\d{1,4}[A-Za-z]?)""",
            RegexOption.IGNORE_CASE
        )

        /** Pure digit-based room/lab number. */
        private val PURE_NUMBER_PATTERN = Regex("""^\d{1,4}[A-Za-z]?$""")

        /** Known sign keywords that become navigable landmarks. */
        private val SIGN_KEYWORDS = mapOf(
            "exit"       to TextLandmarkType.EXIT_SIGN,
            "fire exit"  to TextLandmarkType.EXIT_SIGN,
            "emergency"  to TextLandmarkType.EXIT_SIGN,
            "washroom"   to TextLandmarkType.WASHROOM_SIGN,
            "toilet"     to TextLandmarkType.WASHROOM_SIGN,
            "restroom"   to TextLandmarkType.WASHROOM_SIGN,
            "bathroom"   to TextLandmarkType.WASHROOM_SIGN,
            "ladies"     to TextLandmarkType.WASHROOM_SIGN,
            "gents"      to TextLandmarkType.WASHROOM_SIGN,
            "men"        to TextLandmarkType.WASHROOM_SIGN,
            "women"      to TextLandmarkType.WASHROOM_SIGN,
            "stairs"     to TextLandmarkType.STAIRS_SIGN,
            "staircase"  to TextLandmarkType.STAIRS_SIGN,
            "lift"       to TextLandmarkType.LIFT_SIGN,
            "elevator"   to TextLandmarkType.LIFT_SIGN,
            "canteen"    to TextLandmarkType.FACILITY_SIGN,
            "cafeteria"  to TextLandmarkType.FACILITY_SIGN,
            "library"    to TextLandmarkType.FACILITY_SIGN,
            "reception"  to TextLandmarkType.FACILITY_SIGN,
            "office"     to TextLandmarkType.FACILITY_SIGN,
            "parking"    to TextLandmarkType.FACILITY_SIGN,
            "no entry"   to TextLandmarkType.WARNING_SIGN,
            "authorized" to TextLandmarkType.WARNING_SIGN,
            "restricted" to TextLandmarkType.WARNING_SIGN,
            "danger"     to TextLandmarkType.WARNING_SIGN,
            "caution"    to TextLandmarkType.WARNING_SIGN,
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on a YUV camera frame.
     *
     * This is a synchronous call — designed to be invoked from the detection
     * executor thread (same as YOLO). Uses a CountDownLatch to block until
     * ML Kit returns.
     *
     * @param yBuffer Y plane bytes
     * @param uBuffer U plane bytes
     * @param vBuffer V plane bytes
     * @param yStride Y row stride
     * @param uvStride UV row stride
     * @param uvPixStride UV pixel stride
     * @param width  image width (640)
     * @param height image height (480)
     * @return list of text detections in the original image coordinate space
     */
    fun detectText(
        yBuffer: ByteArray, uBuffer: ByteArray, vBuffer: ByteArray,
        yStride: Int, uvStride: Int, uvPixStride: Int,
        width: Int, height: Int
    ): List<TextDetection> {
        return try {
            val bitmap = yuvToBitmap(yBuffer, uBuffer, vBuffer, yStride, uvStride, uvPixStride, width, height)
                ?: return emptyList()

            // Apply preprocessing to enhance text readability
            val enhanced = enhanceForOcr(bitmap)

            val inputImage = InputImage.fromBitmap(enhanced, 90)  // 90° rotation for portrait

            val results = mutableListOf<TextDetection>()
            val latch = CountDownLatch(1)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        val text = block.text.trim()
                        if (text.length < MIN_TEXT_LENGTH) continue

                        val bbox = block.boundingBox ?: continue
                        // Convert bbox to RectF in the rotated image space
                        val rectF = RectF(
                            bbox.left.toFloat(),
                            bbox.top.toFloat(),
                            bbox.right.toFloat(),
                            bbox.bottom.toFloat()
                        )

                        val classification = classifyText(text)
                        val confidence = estimateConfidence(block)

                        results.add(TextDetection(
                            text = text,
                            boundingBox = rectF,
                            confidence = confidence,
                            classification = classification,
                            roomNumber = extractRoomNumber(text),
                            landmarkType = classifyAsLandmark(text)
                        ))
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    println("$TAG: OCR failed: ${e.message}")
                    latch.countDown()
                }

            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bitmap.recycle()
            if (enhanced !== bitmap) enhanced.recycle()

            results.filter { it.confidence >= 0.3f }
        } catch (e: Exception) {
            println("$TAG: detectText error: ${e.message}")
            emptyList()
        }
    }

    fun close() {
        recognizer.close()
    }

    // ── Text classification ──────────────────────────────────────────────────

    private fun classifyText(text: String): TextClassification {
        val lower = text.lowercase().trim()

        // Check for room numbers first
        if (PURE_NUMBER_PATTERN.matches(lower) || ROOM_NUMBER_PATTERN.containsMatchIn(lower)) {
            return TextClassification.ROOM_NUMBER
        }

        // Check for known sign keywords
        for ((keyword, _) in SIGN_KEYWORDS) {
            if (lower.contains(keyword)) return TextClassification.SIGN
        }

        // Longer text is likely a notice
        if (text.length > 20) return TextClassification.NOTICE

        return TextClassification.GENERAL
    }

    private fun extractRoomNumber(text: String): String? {
        val lower = text.lowercase().trim()
        // Try structured pattern first: "Room 203", "Lab 4"
        val match = ROOM_NUMBER_PATTERN.find(lower)
        if (match != null) {
            return match.groupValues[1].uppercase()
        }
        // Try pure number
        if (PURE_NUMBER_PATTERN.matches(text.trim())) {
            return text.trim().uppercase()
        }
        return null
    }

    private fun classifyAsLandmark(text: String): TextLandmarkType? {
        val lower = text.lowercase().trim()
        // Check longest keywords first to avoid partial matches
        val sorted = SIGN_KEYWORDS.entries.sortedByDescending { it.key.length }
        for ((keyword, type) in sorted) {
            if (lower.contains(keyword)) return type
        }
        return null
    }

    /**
     * Estimate confidence from ML Kit text block.
     * ML Kit v2 doesn't expose per-block confidence, so we use heuristics:
     * - Longer recognized text = higher confidence
     * - More recognized lines in block = higher confidence
     * - Text with alphanumeric characters = higher confidence
     */
    private fun estimateConfidence(block: com.google.mlkit.vision.text.Text.TextBlock): Float {
        val text = block.text
        val alphaRatio = text.count { it.isLetterOrDigit() }.toFloat() / text.length.coerceAtLeast(1)
        val lengthBonus = (text.length.coerceAtMost(30) / 30f) * 0.3f
        val lineBonus = (block.lines.size.coerceAtMost(5) / 5f) * 0.2f
        return (alphaRatio * 0.5f + lengthBonus + lineBonus).coerceIn(0f, 1f)
    }

    // ── YUV → Bitmap conversion ─────────────────────────────────────────────

    private fun yuvToBitmap(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        yStride: Int, uvStride: Int, uvPixStride: Int,
        width: Int, height: Int
    ): Bitmap? {
        return try {
            // Build NV21 byte array from separate planes
            val nv21 = ByteArray(width * height * 3 / 2)

            // Copy Y plane
            for (row in 0 until height) {
                System.arraycopy(yBytes, row * yStride, nv21, row * width, width)
            }

            // Interleave V and U for NV21 (VU order)
            val uvHeight = height / 2
            var nv21Offset = width * height
            for (row in 0 until uvHeight) {
                for (col in 0 until width / 2) {
                    val uvIndex = row * uvStride + col * uvPixStride
                    nv21[nv21Offset++] = vBytes[uvIndex]
                    nv21[nv21Offset++] = uBytes[uvIndex]
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            // Use higher JPEG quality (95%) for better text edge preservation
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            println("$TAG: YUV→Bitmap failed: ${e.message}")
            null
        }
    }

    // ── Image preprocessing for OCR ──────────────────────────────────────────

    /**
     * Enhance bitmap for better OCR accuracy.
     * Applies contrast boost and sharpening without affecting the original bitmap.
     * Returns the same bitmap if enhancement fails (graceful degradation).
     */
    private fun enhanceForOcr(source: Bitmap): Bitmap {
        return try {
            // Create mutable copy for processing
            val enhanced = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(enhanced)

            // Step 1: Increase contrast (makes text stand out from background)
            // ColorMatrix: scale RGB by 1.3 and shift by -30 to darken midtones
            val contrastMatrix = ColorMatrix(floatArrayOf(
                1.3f, 0f, 0f, 0f, -30f,   // Red
                0f, 1.3f, 0f, 0f, -30f,   // Green
                0f, 0f, 1.3f, 0f, -30f,   // Blue
                0f, 0f, 0f, 1f, 0f        // Alpha
            ))

            // Step 2: Slight saturation reduction (text is usually monochrome)
            val saturationMatrix = ColorMatrix()
            saturationMatrix.setSaturation(0.8f)

            // Combine matrices
            contrastMatrix.postConcat(saturationMatrix)

            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(contrastMatrix)
                isAntiAlias = true
                isFilterBitmap = true
            }

            canvas.drawBitmap(source, 0f, 0f, paint)
            enhanced
        } catch (e: Exception) {
            println("$TAG: Enhancement failed, using original: ${e.message}")
            source  // Return original on failure
        }
    }
}

// ── Data classes ─────────────────────────────────────────────────────────────

enum class TextClassification {
    ROOM_NUMBER,   // "203", "Room 305", "Lab 4"
    SIGN,          // "EXIT", "WASHROOM", "STAIRS"
    NOTICE,        // Long text on notice boards
    GENERAL        // Other text
}

enum class TextLandmarkType {
    EXIT_SIGN,
    WASHROOM_SIGN,
    STAIRS_SIGN,
    LIFT_SIGN,
    FACILITY_SIGN,
    WARNING_SIGN
}

data class TextDetection(
    val text: String,
    val boundingBox: RectF,
    val confidence: Float,
    val classification: TextClassification,
    val roomNumber: String?,
    val landmarkType: TextLandmarkType?
)
