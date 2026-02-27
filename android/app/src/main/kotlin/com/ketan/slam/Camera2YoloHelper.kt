package com.ketan.slam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Opens the back camera via Camera2 API independently of ARCore.
 * Delivers portrait-oriented YUV_420_888 frames at 640x480 directly
 * to the provided [onFrame] callback — no JPEG, no Bitmap, no rotation needed.
 *
 * ARCore continues to use the camera internally for SLAM tracking.
 * Camera2 runs on its own surface in parallel.
 *
 * Usage:
 *   val helper = Camera2YoloHelper(context) { yBytes, uBytes, vBytes, yStride, uvStride, uvPixStride, w, h ->
 *       yoloDetector.detectFromYuv(...)
 *   }
 *   helper.start()   // in onResume
 *   helper.stop()    // in onPause / onDestroy
 */
class Camera2YoloHelper(
    private val context: Context,
    private val onFrame: (
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixStride: Int,
        width: Int,
        height: Int
    ) -> Unit
) {
    companion object {
        private const val TAG = "Camera2Yolo"

        // Portrait capture size — matches typical YOLO training image aspect ratio.
        // Width < Height = portrait. Camera sensor is landscape so Camera2 will
        // automatically deliver the correct YUV data; we just label it correctly.
        // 480x640 gives us the same pixel area as 640x480 but in portrait layout.
        private const val CAPTURE_WIDTH  = 480
        private const val CAPTURE_HEIGHT = 640
    }

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true

        cameraThread = HandlerThread("Camera2YoloThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find the back camera
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            Log.e(TAG, "No back camera found")
            return
        }

        // Check sensor orientation so we can request the right size
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        Log.d(TAG, "Sensor orientation: $sensorOrientation°")

        // For a 90° or 270° sensor, the camera delivers landscape frames naturally.
        // We request portrait dimensions (height > width) so Camera2 crops/scales
        // to portrait for us automatically via the ImageReader surface.
        val (reqW, reqH) = if (sensorOrientation == 90 || sensorOrientation == 270) {
            // Sensor is landscape → swap to get portrait output
            Pair(CAPTURE_WIDTH, CAPTURE_HEIGHT)
        } else {
            // Sensor is already portrait (rare)
            Pair(CAPTURE_HEIGHT, CAPTURE_WIDTH)
        }

        imageReader = ImageReader.newInstance(reqW, reqH, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val yPlane = image.planes[0]
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]

                    val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                    val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                    val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }

                    onFrame(
                        yBytes, uBytes, vBytes,
                        yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride,
                        image.width, image.height
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error: ${e.message}")
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun startCaptureSession(camera: CameraDevice) {
        val surface = imageReader!!.surface

        camera.createCaptureSession(
            listOf(surface),
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }.build()

                    session.setRepeatingRequest(request, null, cameraHandler)
                    Log.d(TAG, "Camera2 capture session started (${imageReader!!.width}x${imageReader!!.height})")
                }

                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    Log.e(TAG, "Camera2 capture session config failed")
                }
            },
            cameraHandler
        )
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            cameraThread?.quitSafely()
            cameraThread?.join(1000)
            cameraThread = null
            cameraHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }
}