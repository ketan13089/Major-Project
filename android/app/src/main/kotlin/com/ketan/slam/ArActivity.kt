package com.ketan.slam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val CHANNEL = "com.ketan.slam/ar"
        private const val TAG = "SLAM"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private const val GRID_RESOLUTION_METERS = 0.25f
        private const val MERGE_DISTANCE_METERS = 1.2f

        // YOLO capture size — portrait, matching training data orientation
        private const val YOLO_WIDTH  = 640  // landscape — matches sensor native output
        private const val YOLO_HEIGHT = 480
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var hudTextView: TextView
    private lateinit var overlayView: DetectionOverlayView

    // ── ARCore ────────────────────────────────────────────────────────────────
    private var session: Session? = null
    private var sharedCamera: SharedCamera? = null
    private var installRequested = false
    private var displayRotationHelper: DisplayRotationHelper? = null
    private lateinit var backgroundRenderer: BackgroundRenderer

    // ── Shared camera ImageReader for YOLO ────────────────────────────────────
    private var yoloImageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // ── SLAM & Detection ──────────────────────────────────────────────────────
    private lateinit var slamEngine: SlamEngine
    private lateinit var semanticMap: SemanticMapManager
    private lateinit var yoloDetector: YoloDetector

    private var lastDetectionTime = 0L
    private val detectionInterval = 800L
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private val detectionInProgress = AtomicBoolean(false)
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var latestPose: com.google.ar.core.Pose? = null

    // ── Flutter bridge ────────────────────────────────────────────────────────
    private var methodChannel: MethodChannel? = null

    // ── Timing ───────────────────────────────────────────────────────────────
    private var lastHudUpdateTime = 0L
    private val hudUpdateIntervalMs = 300L
    private var lastFlutterUpdateTime = 0L
    private val flutterUpdateIntervalMs = 300L
    private var lastMapUpdateTime = 0L
    private val mapUpdateIntervalMs = 1000L

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val engine = FlutterEngineCache.getInstance().get("slam_engine")
        if (engine != null) {
            methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
        }

        displayRotationHelper = DisplayRotationHelper(this)
        buildLayout()

        slamEngine  = SlamEngine()
        semanticMap = SemanticMapManager()

        try {
            yoloDetector = YoloDetector(this)
            println("$TAG: YOLO ready")
        } catch (e: Exception) {
            println("$TAG: YOLO init failed: ${e.message}")
        }

        if (!hasCameraPermission()) requestCameraPermission()
    }

    private fun buildLayout() {
        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        overlayView = DetectionOverlayView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        hudTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(20, 14, 20, 14)
            text = "Initializing…"
        }

        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(hudTextView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(24, 48, 24, 0) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) { requestCameraPermission(); return }
        if (!ensureSessionReady()) return
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            println("$TAG: Camera unavailable: ${e.message}"); session = null; return
        }
        surfaceView.onResume()
        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        displayRotationHelper?.onPause()
        session?.pause()
        overlayView.clearDetections()
        teardownSharedCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { yoloDetector.close() } catch (_: Exception) {}
        detectionExecutor.shutdownNow()
        teardownSharedCamera()
        session?.close()
        session = null
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Permissions
    // ═════════════════════════════════════════════════════════════════════════

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
        )

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) return
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (!ensureSessionReady()) return
        try {
            session?.resume()
            surfaceView.onResume()
            displayRotationHelper?.onResume()
        } catch (e: Exception) {
            println("$TAG: Resume after permission: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ARCore session — created with SHARED_CAMERA feature
    // ═════════════════════════════════════════════════════════════════════════

    private fun ensureSessionReady(): Boolean {
        if (session != null) return true
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // Create session with shared camera feature enabled
                    session = Session(this, setOf(Session.Feature.SHARED_CAMERA)).also { s ->
                        configureSession(s)
                        setupSharedCamera(s)
                    }
                    println("$TAG: ARCore shared-camera session created")
                    true
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true; false
                }
            }
        } catch (e: UnavailableArcoreNotInstalledException)     { arUnavailable("ARCore not installed"); false }
        catch (e: UnavailableUserDeclinedInstallationException)  { arUnavailable("ARCore install declined"); false }
        catch (e: UnavailableApkTooOldException)                 { arUnavailable("ARCore APK too old"); false }
        catch (e: UnavailableSdkTooOldException)                 { arUnavailable("SDK too old"); false }
        catch (e: UnavailableDeviceNotCompatibleException)       { arUnavailable("Device not compatible"); false }
        catch (e: Exception)                                     { arUnavailable("AR init failed: ${e.message}"); false }
    }

    private fun configureSession(session: Session) {
        Config(session).apply {
            focusMode           = Config.FocusMode.AUTO
            updateMode          = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode    = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            depthMode           = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }.also { session.configure(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Shared Camera setup — ARCore shares the camera with our ImageReader
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupSharedCamera(session: Session) {
        sharedCamera = session.sharedCamera

        cameraThread = HandlerThread("SharedCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Create an ImageReader at portrait size matching training data
        yoloImageReader = ImageReader.newInstance(
            YOLO_WIDTH, YOLO_HEIGHT, ImageFormat.YUV_420_888, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime < detectionInterval ||
                        !detectionInProgress.compareAndSet(false, true)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    lastDetectionTime = now

                    val yPlane = image.planes[0]
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]
                    val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                    val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                    val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
                    val yStride = yPlane.rowStride
                    val uvStride = uPlane.rowStride
                    val uvPixStride = uPlane.pixelStride
                    val w = image.width
                    val h = image.height
                    val pose = latestPose

                    image.close()

                    detectionExecutor.execute {
                        val t0 = System.currentTimeMillis()
                        try {
                            val detections = yoloDetector.detectFromYuv(
                                yBytes, uBytes, vBytes, yStride, uvStride, uvPixStride, w, h
                            )
                            lastInferenceMs = System.currentTimeMillis() - t0

                            overlayView.updateDetections(detections, w, h)

                            if (detections.isNotEmpty()) {
                                println("$TAG: detected ${detections.size}: " +
                                        detections.joinToString { "${it.label}@${"%.2f".format(it.confidence)}" })
                            }

                            if (pose != null) {
                                detections.forEach { det ->
                                    val worldPos = estimate3DPosition(
                                        det.boundingBox.toRect(), pose, w, h
                                    ) ?: return@forEach
                                    mergeOrAddObject(det, worldPos)
                                }
                            }
                        } catch (e: Exception) {
                            println("$TAG: detection worker: ${e.message}")
                        } finally {
                            detectionInProgress.set(false)
                        }
                    }
                } catch (e: Exception) {
                    println("$TAG: shared camera image error: ${e.message}")
                    try { image.close() } catch (_: Exception) {}
                    detectionInProgress.set(false)
                }
            }, cameraHandler)
        }

        // Register our ImageReader surface with ARCore's shared camera
        sharedCamera?.setAppSurfaces(
            session.cameraConfig.cameraId,
            listOf(yoloImageReader!!.surface)
        )

        println("$TAG: Shared camera set up, YOLO surface ${YOLO_WIDTH}x${YOLO_HEIGHT}")
    }

    private fun teardownSharedCamera() {
        try {
            yoloImageReader?.close()
            yoloImageReader = null
            cameraThread?.quitSafely()
            cameraThread?.join(500)
            cameraThread = null
            cameraHandler = null
            sharedCamera = null
        } catch (e: Exception) {
            println("$TAG: teardown error: ${e.message}")
        }
    }

    private fun arUnavailable(msg: String) {
        println("$TAG: $msg")
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GL Renderer
    // ═════════════════════════════════════════════════════════════════════════

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread()
        println("$TAG: GL surface created, textureId=${backgroundRenderer.textureId}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper?.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!::backgroundRenderer.isInitialized) return
        val session = session ?: return

        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper?.updateSessionIfNeeded(session)

            val frame  = session.update()
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            updateHud(camera)

            if (camera.trackingState == TrackingState.TRACKING) {
                updateSlam(frame, camera)
                val now = System.currentTimeMillis()
                if (now - lastFlutterUpdateTime >= flutterUpdateIntervalMs) {
                    lastFlutterUpdateTime = now
                    sendUpdatesToFlutter()
                }
            }
        } catch (e: Exception) {
            println("$TAG: onDrawFrame: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SLAM
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateSlam(frame: Frame, camera: Camera) {
        try {
            val pose = camera.pose
            latestPose = pose
            slamEngine.addPose(Point3D(pose.tx(), pose.ty(), pose.tz()))
            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                if (plane.trackingState == TrackingState.TRACKING) {
                    val poly = plane.polygon
                    if (poly != null && poly.hasRemaining()) slamEngine.addEdges(poly)
                }
            }
        } catch (e: Exception) {
            println("$TAG: SLAM: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Object merge / add
    // ═════════════════════════════════════════════════════════════════════════

    private fun mergeOrAddObject(det: YoloDetector.Detection, worldPos: Point3D) {
        val existing = semanticMap.getAllObjects().firstOrNull { obj ->
            obj.category == det.label &&
                    obj.position.distance(worldPos) < MERGE_DISTANCE_METERS
        }

        if (existing != null) {
            val alpha = 0.2f
            semanticMap.updateObject(
                existing.copy(
                    position = Point3D(
                        existing.position.x * (1f - alpha) + worldPos.x * alpha,
                        existing.position.y * (1f - alpha) + worldPos.y * alpha,
                        existing.position.z * (1f - alpha) + worldPos.z * alpha
                    ),
                    confidence   = maxOf(existing.confidence, det.confidence),
                    lastSeen     = System.currentTimeMillis(),
                    observations = existing.observations + 1
                )
            )
        } else {
            val gridX = (worldPos.x / 0.5f).toInt()
            val gridZ = (worldPos.z / 0.5f).toInt()
            semanticMap.addObject(
                SemanticObject(
                    id          = "${det.label}_${gridX}_${gridZ}",
                    type        = ObjectType.fromLabel(det.label),
                    category    = det.label,
                    position    = worldPos,
                    boundingBox = BoundingBox2D(
                        det.boundingBox.left,  det.boundingBox.top,
                        det.boundingBox.right, det.boundingBox.bottom
                    ),
                    confidence  = det.confidence,
                    firstSeen   = System.currentTimeMillis(),
                    lastSeen    = System.currentTimeMillis()
                )
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3-D position estimation
    // ═════════════════════════════════════════════════════════════════════════

    private fun estimate3DPosition(
        bbox: android.graphics.Rect,
        cameraPose: com.google.ar.core.Pose,
        imgW: Int, imgH: Int
    ): Point3D? {
        return try {
            val area  = (bbox.width() * bbox.height()).toFloat() / (imgW * imgH)
            val depth = when {
                area > 0.3f  -> 1.0f
                area > 0.1f  -> 2.0f
                area > 0.03f -> 3.5f
                else         -> 5.0f
            }
            val q  = cameraPose.rotationQuaternion
            val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
            val fx = 2f * (qx * qz + qy * qw)
            val fy = 2f * (qy * qz - qx * qw)
            val fz = 1f - 2f * (qx * qx + qy * qy)
            Point3D(
                cameraPose.tx() + fx * depth,
                cameraPose.ty() + fy * depth,
                cameraPose.tz() - fz * depth
            )
        } catch (e: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HUD
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateHud(camera: Camera) {
        val now = System.currentTimeMillis()
        if (now - lastHudUpdateTime < hudUpdateIntervalMs) return
        lastHudUpdateTime = now
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()
        val text = buildString {
            appendLine("AR: ${camera.trackingState}")
            appendLine("Pos  x=${s.currentPosition.x.f2} y=${s.currentPosition.y.f2} z=${s.currentPosition.z.f2}")
            appendLine("Map  cells=${s.cellCount}  edges=${s.edgeCount}")
            appendLine("Obj  unique=${sem.totalObjects}")
            append("Detect  ${if (detectionInProgress.get()) "⏳ ${lastInferenceMs}ms" else "idle ${lastInferenceMs}ms"}")
        }
        runOnUiThread { hudTextView.text = text }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Flutter bridge
    // ═════════════════════════════════════════════════════════════════════════

    private fun sendUpdatesToFlutter() {
        val ch  = methodChannel ?: return
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()
        val now = System.currentTimeMillis()
        runOnUiThread {
            ch.invokeMethod("updatePose", mapOf(
                "x" to s.currentPosition.x,
                "y" to s.currentPosition.y,
                "z" to s.currentPosition.z
            ))
            ch.invokeMethod("onUpdate", mapOf(
                "position_x"         to s.currentPosition.x,
                "position_y"         to s.currentPosition.y,
                "position_z"         to s.currentPosition.z,
                "edges_count"        to s.edgeCount,
                "cells_count"        to s.cellCount,
                "total_objects"      to sem.totalObjects,
                "chairs"             to (sem.objectCounts[ObjectType.CHAIR]             ?: 0),
                "doors"              to (sem.objectCounts[ObjectType.DOOR]              ?: 0),
                "fire_extinguishers" to (sem.objectCounts[ObjectType.FIRE_EXTINGUISHER] ?: 0),
                "lift_gates"         to (sem.objectCounts[ObjectType.LIFT_GATE]         ?: 0),
                "notice_boards"      to (sem.objectCounts[ObjectType.NOTICE_BOARD]      ?: 0),
                "trash_cans"         to (sem.objectCounts[ObjectType.TRASH_CAN]         ?: 0),
                "water_purifiers"    to (sem.objectCounts[ObjectType.WATER_PURIFIER]    ?: 0),
                "windows"            to (sem.objectCounts[ObjectType.WINDOW]            ?: 0)
            ))
            if (now - lastMapUpdateTime >= mapUpdateIntervalMs) {
                lastMapUpdateTime = now
                ch.invokeMethod("updateMap", buildMapPayload())
            }
        }
    }

    private fun buildMapPayload(): Map<String, Any> {
        val grid = slamEngine.getOccupancyGrid()
        if (grid.isEmpty()) return mapOf(
            "occupancyGrid" to ByteArray(0), "gridWidth" to 0, "gridHeight" to 0,
            "gridResolution" to GRID_RESOLUTION_METERS.toDouble(), "objects" to emptyList<Any>()
        )
        val minX = grid.keys.minOf { it.x }; val maxX = grid.keys.maxOf { it.x }
        val minZ = grid.keys.minOf { it.z }; val maxZ = grid.keys.maxOf { it.z }
        val w = maxX - minX + 1; val h = maxZ - minZ + 1
        val bytes = ByteArray(w * h)
        grid.forEach { (cell, occupied) ->
            val idx = (cell.z - minZ) * w + (cell.x - minX)
            if (idx in bytes.indices) bytes[idx] = if (occupied) 2 else 1
        }
        val objects = semanticMap.getAllObjects().map { obj ->
            mapOf(
                "id"         to obj.id,
                "type"       to obj.type.name,
                "label"      to obj.category,
                "confidence" to obj.confidence,
                "x"          to obj.position.x,
                "y"          to obj.position.y,
                "z"          to obj.position.z,
                "gridX"      to ((obj.position.x / GRID_RESOLUTION_METERS).toInt() - minX),
                "gridZ"      to ((obj.position.z / GRID_RESOLUTION_METERS).toInt() - minZ)
            )
        }
        return mapOf(
            "occupancyGrid"  to bytes,
            "gridWidth"      to w,
            "gridHeight"     to h,
            "gridResolution" to GRID_RESOLUTION_METERS.toDouble(),
            "originX"        to minX,
            "originZ"        to minZ,
            "objects"        to objects
        )
    }
}

private val Float.f2 get() = "%.2f".format(this)