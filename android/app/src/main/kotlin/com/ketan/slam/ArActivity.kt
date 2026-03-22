package com.ketan.slam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.ImageFormat
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
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
// import android.os.Vibrator  // FROZEN — hazard vibration disabled
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Central AR orchestrator — manages ARCore lifecycle, GL rendering,
 * YOLO inference triggering, and bridges to Flutter.
 *
 * All mapping logic is delegated to [MapBuilder], pose tracking to
 * [PoseTracker], observations stored in [ObservationStore], and
 * 3D localization handled by [ObjectLocalizer].
 */
class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val CHANNEL      = "com.ketan.slam/ar"
        private const val NAV_CHANNEL  = "com.ketan.slam/nav"
        private const val MAP_CHANNEL  = "com.ketan.slam/map"
        private const val TAG         = "SLAM"
        private const val CAM_PERM    = 1001
        private const val MIC_PERM    = 1002

        // Grid resolution — 20 cm per cell
        private const val RES = 0.20f

        // Shared camera capture size (landscape)
        private const val CAM_W = 640
        private const val CAM_H = 480

        private const val MERGE_DIST = 1.2f       // metres, same-label merge radius
        private const val STALE_MS   = 30_000L    // remove unseen objects after 30 s

        // Map rebuild interval (ms)
        private const val REBUILD_INTERVAL_MS = 2000L
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var hudView: TextView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var navView: TextView
    private lateinit var micButton: TextView

    // ── ARCore ────────────────────────────────────────────────────────────────
    private var session: Session? = null
    private var installRequested = false
    private var displayRotationHelper: DisplayRotationHelper? = null
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var meshRenderer: MeshRenderer
    @Volatile var meshOverlayEnabled = true

    // Surface dimensions — needed for hit-test coordinate mapping
    @Volatile private var surfaceWidth  = CAM_W
    @Volatile private var surfaceHeight = CAM_H

    // ── Shared camera ─────────────────────────────────────────────────────────
    private var yoloReader: ImageReader? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    // ── Core modules (new architecture) ───────────────────────────────────────
    private lateinit var poseTracker: PoseTracker
    private lateinit var mapBuilder: MapBuilder
    private lateinit var observationStore: ObservationStore
    private lateinit var objectLocalizer: ObjectLocalizer
    private lateinit var slamEngine: SlamEngine
    private lateinit var semanticMap: SemanticMapManager

    // ── Detection ─────────────────────────────────────────────────────────────
    private lateinit var yoloDetector: YoloDetector
    private lateinit var textRecognizer: TextRecognizer
    private val confirmationGate = DetectionConfirmationGate(requiredHits = 3, windowMs = 5_000L)
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private val detecting = AtomicBoolean(false)
    private var lastDetectMs = 0L
    private val detectInterval = 900L
    private var lastOcrMs = 0L
    private val ocrInterval = 3000L  // OCR every 3 seconds (less frequent than YOLO)
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var lastOcrInferenceMs = 0L
    @Volatile private var latestPose: com.google.ar.core.Pose? = null
    @Volatile private var latestFrame: Frame? = null
    @Volatile private var latestFrameTs: Long = 0L   // timestamp of latestFrame
    @Volatile private var latestHeading = 0f

    // ── Map rebuild scheduling ────────────────────────────────────────────────
    private val rebuildExecutor = Executors.newSingleThreadExecutor()
    private val rebuilding = AtomicBoolean(false)
    private var lastRebuildMs = 0L
    @Volatile private var lastRebuildVersion = 0L
    private var lastDepthSampleMs = 0L          // throttle depth-hit sampling

    // ── Flutter ───────────────────────────────────────────────────────────────
    private var methodChannel: MethodChannel? = null
    private var navChannel: MethodChannel? = null
    private var mapChannel: MethodChannel? = null
    private lateinit var mapPersistence: MapPersistence
    private var emergencyManager: EmergencyManager? = null
    private var onboardingTutorial: OnboardingTutorial? = null
    private var lastHudMs = 0L; private var lastFlutterMs = 0L; private var lastMapMs = 0L

    // ── Navigation ────────────────────────────────────────────────────────────
    private var navigationManager: NavigationManager? = null

    // ── Activity lifecycle flag ───────────────────────────────────────────────
    @Volatile private var destroyed = false

    // ── Tracking loss recovery ───────────────────────────────────────────────
    private var lastTrackingState: TrackingState = TrackingState.STOPPED
    @Volatile private var frozenPose: com.google.ar.core.Pose? = null
    private var trackingLostTimestamp = 0L
    private var announcer: NavigationGuide? = null

    // ── Object localization smoothing ────────────────────────────────────────
    private lateinit var localizationSmoother: LocalizationSmoother

    // ── Hazard warning system ────────────────────────────────────────────────
    private var hazardWarningSystem: HazardWarningSystem? = null
    private val spatialAudio = SpatialAudioEngine()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        FlutterEngineCache.getInstance().get("slam_engine")?.let {
            methodChannel = MethodChannel(it.dartExecutor.binaryMessenger, CHANNEL)
            navChannel    = MethodChannel(it.dartExecutor.binaryMessenger, NAV_CHANNEL)
            mapChannel    = MethodChannel(it.dartExecutor.binaryMessenger, MAP_CHANNEL)
            navChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "startVoiceNav"  -> {
                        if (hasMicPerm()) navigationManager?.startVoiceCommand()
                        else reqMicPerm()
                        result.success(null)
                    }
                    "stopNavigation" -> { navigationManager?.stopNavigation(); result.success(null) }
                    "toggleMesh"     -> {
                        meshOverlayEnabled = !meshOverlayEnabled
                        result.success(meshOverlayEnabled)
                    }
                    else             -> result.notImplemented()
                }
            }
            mapChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "saveMap" -> {
                        val name = call.argument<String>("name") ?: "map_${System.currentTimeMillis()}"
                        val path = mapPersistence.saveMap(name, mapBuilder, semanticMap,
                            poseTracker.getBreadcrumbs())
                        if (path != null) result.success(mapOf("path" to path, "name" to name))
                        else result.error("SAVE_FAILED", "Failed to save map", null)
                    }
                    "loadMap" -> {
                        val name = call.argument<String>("name")
                        if (name == null) { result.error("NO_NAME", "Map name required", null); return@setMethodCallHandler }
                        val bc = mapPersistence.loadMap(name, mapBuilder, semanticMap)
                        if (bc != null) result.success(mapOf("loaded" to true, "breadcrumbs" to bc.size))
                        else result.error("LOAD_FAILED", "Failed to load map: $name", null)
                    }
                    "listMaps" -> {
                        result.success(mapPersistence.listSavedMaps())
                    }
                    "deleteMap" -> {
                        val name = call.argument<String>("name") ?: ""
                        result.success(mapPersistence.deleteMap(name))
                    }
                    "triggerEmergency" -> {
                        val pos = poseTracker.getCurrentPosition()
                        emergencyManager?.trigger(pos.x, pos.z, semanticMap, mapBuilder,
                            poseTracker.getBreadcrumbs())
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        displayRotationHelper = DisplayRotationHelper(this)
        buildLayout()

        // Initialize core modules
        poseTracker = PoseTracker()
        mapBuilder = MapBuilder(RES)
        observationStore = ObservationStore()
        objectLocalizer = ObjectLocalizer()
        localizationSmoother = LocalizationSmoother(RES)
        slamEngine  = SlamEngine()
        semanticMap = SemanticMapManager()
        mapPersistence = MapPersistence(this)

        // Wire up stale-object cleanup → footprint clearing
        semanticMap.onObjectRemoved = { obj ->
            val halfM = ObjectLocalizer.footprintHalfMetres(obj.category)
            mapBuilder.clearObstacleFootprint(obj.position, halfM)
        }

        navigationManager = NavigationManager(
            context       = this,
            res           = RES,
            onStateChange = { state, msg -> runOnUiThread { updateNavHud(state, msg) } },
            onInstruction = { instr -> runOnUiThread {
                navChannel?.invokeMethod("navInstruction", mapOf(
                    "text"           to instr.text,
                    "turn"           to instr.turn.name,
                    "distanceMetres" to instr.distanceMetres.toDouble()))
            }},
            onPathUpdated = { _ -> /* path is included in the next buildMapPayload call */ }
        )
        navigationManager?.breadcrumbProvider = { poseTracker.getBreadcrumbs() }

        // Standalone TTS announcer for non-navigation alerts (tracking loss, hazards)
        announcer = NavigationGuide(this)

        emergencyManager = EmergencyManager(this, announcer!!, mapPersistence)
        navigationManager?.onEmergency = { userX, userZ ->
            emergencyManager?.trigger(userX, userZ, semanticMap, mapBuilder,
                poseTracker.getBreadcrumbs())
        }

        onboardingTutorial = OnboardingTutorial(this, announcer!!)
        navigationManager?.onTutorial = { onboardingTutorial?.play() }

        // Auto-play tutorial on first launch (delayed to let AR initialize)
        if (onboardingTutorial?.shouldAutoPlay == true) {
            android.os.Handler(mainLooper).postDelayed({
                onboardingTutorial?.play()
            }, 3000L)
        }

        // Hazard warning + spatial audio — FROZEN (disabled for now)
        // @Suppress("DEPRECATION")
        // val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        // hazardWarningSystem = HazardWarningSystem(announcer!!, vibrator)
        // spatialAudio.start()

        try { yoloDetector = YoloDetector(this); println("$TAG: YOLO ready") }
        catch (e: Exception) { println("$TAG: YOLO init failed: ${e.message}") }

        textRecognizer = TextRecognizer()
        println("$TAG: OCR ready")

        if (!hasCamPerm()) reqCamPerm()
    }

    override fun onResume() {
        super.onResume()
        if (!hasCamPerm()) { reqCamPerm(); return }
        if (!ensureSession()) return
        try { session?.resume() } catch (e: CameraNotAvailableException) { session = null; return }
        surfaceView.onResume()
        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        displayRotationHelper?.onPause()
        session?.pause()
        overlayView.clearDetections()
        teardownCamera()
        // Auto-save map on every pause (leaving AR)
        try {
            if (mapBuilder.grid.isNotEmpty()) {
                mapPersistence.saveMap("last_session", mapBuilder, semanticMap,
                    poseTracker.getBreadcrumbs())
            }
        } catch (e: Exception) { println("$TAG: auto-save: ${e.message}") }
    }

    override fun onDestroy() {
        destroyed = true
        // Stop GL callbacks before tearing down resources they depend on
        try { surfaceView.onPause() } catch (_: Exception) {}
        // Clear channel handlers so stale callbacks don't fire on destroyed activity
        try { navChannel?.setMethodCallHandler(null) } catch (_: Exception) {}
        try { mapChannel?.setMethodCallHandler(null) } catch (_: Exception) {}
        methodChannel = null
        navChannel = null
        mapChannel = null
        try { yoloDetector.close() } catch (_: Exception) {}
        try { textRecognizer.close() } catch (_: Exception) {}
        onboardingTutorial?.stop()
        // spatialAudio.stop()  // FROZEN
        hazardWarningSystem = null
        announcer?.shutdown(); announcer = null
        navigationManager?.destroy(); navigationManager = null
        poseTracker.destroy()
        detectionExecutor.shutdownNow()
        rebuildExecutor.shutdownNow()
        teardownCamera()
        session?.close(); session = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLayout() {
        val dp = resources.displayMetrics.density

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlayView = DetectionOverlayView(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        hudView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                setColor(0xBB101020.toInt())
                cornerRadius = 14f * dp
            }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            text = "Initializing…"
            contentDescription = "Status display. Initializing."
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        navView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0xDD1D4ED8.toInt())
                cornerRadius = 28f * dp
            }
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            elevation = 8f * dp
            contentDescription = "Navigation status"
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val micSize = (60 * dp).toInt()
        micButton = TextView(this).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2563EB.toInt())
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 12f * dp
            setOnClickListener { onMicTapped() }
            contentDescription = "Tap to give a voice command. Say things like: take me to the nearest door."
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        // Mark camera views as not important for TalkBack (decorative/live content)
        surfaceView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        overlayView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val root = FrameLayout(this)
        root.addView(surfaceView, mpmp())
        root.addView(overlayView, mpmp())
        root.addView(hudView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply {
            setMargins((16 * dp).toInt(), (48 * dp).toInt(), (16 * dp).toInt(), 0)
        })
        root.addView(navView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins((20 * dp).toInt(), 0, (20 * dp).toInt(), (96 * dp).toInt())
        })
        root.addView(micButton, FrameLayout.LayoutParams(
            micSize, micSize,
            Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (20 * dp).toInt(), (20 * dp).toInt())
        })
        setContentView(root)
    }

    private fun mpmp() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasCamPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun reqCamPerm() = ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA), CAM_PERM)

    private fun reqMicPerm() = ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERM)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, p, results)
        when (rc) {
            CAM_PERM -> {
                if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show(); finish(); return
                }
                if (!ensureSession()) return
                try { session?.resume(); surfaceView.onResume(); displayRotationHelper?.onResume() }
                catch (e: Exception) { println("$TAG: resume after perm: ${e.message}") }
            }
            MIC_PERM -> {
                if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                    navigationManager?.startVoiceCommand()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ARCore session (Shared Camera)
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureSession(): Boolean {
        if (session != null) return true
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    val s = Session(this, setOf(Session.Feature.SHARED_CAMERA))
                    Config(s).apply {
                        focusMode           = Config.FocusMode.AUTO
                        updateMode          = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        planeFindingMode    = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        depthMode           = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                            Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    }.also { s.configure(it) }
                    setupSharedCamera(s)
                    session = s
                    println("$TAG: ARCore shared-camera session created")
                    true
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; false }
            }
        } catch (e: UnavailableArcoreNotInstalledException)    { fatal("ARCore not installed"); false }
        catch (e: UnavailableUserDeclinedInstallationException){ fatal("ARCore declined"); false }
        catch (e: UnavailableApkTooOldException)              { fatal("ARCore APK too old"); false }
        catch (e: UnavailableSdkTooOldException)              { fatal("SDK too old"); false }
        catch (e: UnavailableDeviceNotCompatibleException)    { fatal("Device incompatible"); false }
        catch (e: Exception)                                  { fatal("AR init: ${e.message}"); false }
    }

    private fun fatal(msg: String) {
        println("$TAG: $msg")
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared camera — ImageReader at 640×480
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupSharedCamera(s: Session) {
        camThread = HandlerThread("YoloCam").also { it.start() }
        camHandler = Handler(camThread!!.looper)

        yoloReader = ImageReader.newInstance(CAM_W, CAM_H, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectMs < detectInterval || !detecting.compareAndSet(false, true)) {
                        img.close(); return@setOnImageAvailableListener
                    }
                    lastDetectMs = now
                    val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
                    val yB = ByteArray(yP.buffer.remaining()).also { yP.buffer.get(it) }
                    val uB = ByteArray(uP.buffer.remaining()).also { uP.buffer.get(it) }
                    val vB = ByteArray(vP.buffer.remaining()).also { vP.buffer.get(it) }
                    val yStride = yP.rowStride; val uvStride = uP.rowStride; val uvPix = uP.pixelStride
                    val pose = latestPose
                    val currentFrame = latestFrame
                    val capturedFrameTs = latestFrameTs   // timestamp when this frame was current
                    img.close()

                    detectionExecutor.execute {
                        val t0 = System.currentTimeMillis()
                        try {
                            // ── YOLO object detection ───────────────────────────
                            val raw = yoloDetector.detectFromYuv(yB, uB, vB, yStride, uvStride, uvPix, CAM_W, CAM_H)
                            val confirmed = confirmationGate.feed(raw)
                            lastInferenceMs = System.currentTimeMillis() - t0

                            overlayView.updateDetections(raw, CAM_W, CAM_H)

                            if (confirmed.isNotEmpty() && pose != null) {
                                println("$TAG: confirmed ${confirmed.size}: " +
                                        confirmed.joinToString { "${it.label}@${"%.2f".format(it.confidence)}" })
                                confirmed.forEach { det ->
                                    val locResult = objectLocalizer.estimate3DWithMethod(
                                        det.boundingBox, pose, CAM_W, CAM_H,
                                        frame = currentFrame,
                                        surfaceWidth = surfaceWidth,
                                        surfaceHeight = surfaceHeight,
                                        frameTimestampNs = capturedFrameTs,
                                        currentFrameTimestampNs = latestFrameTs,
                                        label = det.label
                                    ) ?: return@forEach
                                    // Pass through smoother — only mergeOrAdd when accepted
                                    val smoothed = localizationSmoother.feed(det.label, locResult)
                                    if (smoothed != null) {
                                        mergeOrAdd(det, smoothed, locResult.method)
                                        val halfM = ObjectLocalizer.footprintHalfMetres(det.label)
                                        mapBuilder.markObstacleFootprint(smoothed, halfM)
                                    }
                                }
                            }

                            // ── OCR text recognition (every 3s) ─────────────────
                            val ocrNow = System.currentTimeMillis()
                            if (ocrNow - lastOcrMs >= ocrInterval && pose != null) {
                                lastOcrMs = ocrNow
                                val ocrT0 = System.currentTimeMillis()
                                val textDetections = textRecognizer.detectText(
                                    yB, uB, vB, yStride, uvStride, uvPix, CAM_W, CAM_H)
                                lastOcrInferenceMs = System.currentTimeMillis() - ocrT0

                                if (textDetections.isNotEmpty()) {
                                    println("$TAG: OCR found ${textDetections.size}: " +
                                            textDetections.joinToString { "\"${it.text}\"(${it.classification})" })
                                    textDetections.forEach { textDet ->
                                        processTextDetection(textDet, pose!!, currentFrame)
                                    }
                                }
                            }
                        } catch (e: Exception) { println("$TAG: detection: ${e.message}") }
                        finally { detecting.set(false) }
                    }
                } catch (e: Exception) {
                    try { img.close() } catch (_: Exception) {}
                    detecting.set(false)
                }
            }, camHandler)
        }

        s.sharedCamera.setAppSurfaces(s.cameraConfig.cameraId, listOf(yoloReader!!.surface))
        println("$TAG: Shared camera ready ${CAM_W}x${CAM_H}")
    }

    private fun teardownCamera() {
        try {
            yoloReader?.close(); yoloReader = null
            camThread?.quitSafely(); camThread?.join(500)
            camThread = null; camHandler = null
        } catch (e: Exception) { println("$TAG: teardown: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GL Renderer
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = BackgroundRenderer().also { it.createOnGlThread() }
        meshRenderer = MeshRenderer().also { it.createOnGlThread() }
        println("$TAG: GL surface created, textureId=${backgroundRenderer.textureId}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth  = width
        surfaceHeight = height
        displayRotationHelper?.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (destroyed) return
        if (!::backgroundRenderer.isInitialized) return
        val sess = session ?: return
        try {
            sess.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper?.updateSessionIfNeeded(sess)
            val frame  = sess.update()
            latestFrame = frame
            latestFrameTs = frame.timestamp
            backgroundRenderer.draw(frame)
            val camera = frame.camera
            val currentTrackingState = camera.trackingState

            // ── AR Mesh overlay ─────────────────────────────────────────────
            if (meshOverlayEnabled && ::meshRenderer.isInitialized
                && currentTrackingState == TrackingState.TRACKING) {
                drawMeshOverlays(sess, frame, camera)
            }

            // ── Tracking loss detection & recovery ─────────────────────────
            if (lastTrackingState == TrackingState.TRACKING && currentTrackingState != TrackingState.TRACKING) {
                // TRACKING → lost
                frozenPose = latestPose
                trackingLostTimestamp = System.currentTimeMillis()
                announcer?.speak("Tracking lost. Please hold the phone steady.")
                runOnUiThread { hudView.setBackgroundColor(0xCCCC0000.toInt()) }
            } else if (lastTrackingState != TrackingState.TRACKING && currentTrackingState == TrackingState.TRACKING
                       && lastTrackingState != TrackingState.STOPPED) {
                // Lost → TRACKING recovered
                val frozen = frozenPose
                val recovered = camera.pose
                if (frozen != null) {
                    val dx = recovered.tx() - frozen.tx()
                    val dz = recovered.tz() - frozen.tz()
                    val drift = sqrt(dx * dx + dz * dz)
                    if (drift > 0.3f) {
                        announcer?.speak("Tracking recovered. Position shifted ${"%.1f".format(drift)} metres. Rebuilding map.")
                        // Force immediate rebuild
                        val keyframes = observationStore.snapshot()
                        rebuildExecutor.execute {
                            try { mapBuilder.rebuild(keyframes) }
                            catch (e: Exception) { println("$TAG: drift rebuild: ${e.message}") }
                        }
                    } else {
                        announcer?.speak("Tracking recovered.")
                    }
                } else {
                    announcer?.speak("Tracking recovered.")
                }
                frozenPose = null
                runOnUiThread { hudView.setBackgroundColor(0xCC000000.toInt()) }
            }
            lastTrackingState = currentTrackingState

            updateHud(camera)
            if (currentTrackingState == TrackingState.TRACKING) {
                updateSlam(frame, camera, sess)
                val now = System.currentTimeMillis()
                if (now - lastFlutterMs >= 300L) { lastFlutterMs = now; sendToFlutter() }
            }
        } catch (e: Exception) { println("$TAG: draw: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SLAM — called every GL frame
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateSlam(frame: Frame, camera: Camera, session: Session) {
        try {
            val pose = camera.pose
            latestPose = pose

            val cx = pose.tx(); val cz = pose.tz()
            val posPoint = Point3D(cx, pose.ty(), cz)
            slamEngine.addPose(posPoint)
            poseTracker.addPose(posPoint)

            // Compute forward direction
            val q = pose.rotationQuaternion
            val fwdX = 2f * (q[0] * q[2] + q[1] * q[3])
            val fwdZ = 1f - 2f * (q[0] * q[0] + q[1] * q[1])

            // Compute heading
            val sinY = 2f * (q[3] * q[1] + q[2] * q[0])
            val cosY = 1f - 2f * (q[1] * q[1] + q[2] * q[2])
            latestHeading = kotlin.math.atan2(sinY, cosY)

            // --- Incremental map update (low-latency, every frame) ---
            mapBuilder.incrementalUpdate(cx, cz, latestHeading, fwdX, fwdZ)

            // --- Capture plane snapshots and integrate ---
            val planeSnapshots = mutableListOf<PlaneSnapshot>()
            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                if (plane.trackingState != TrackingState.TRACKING) return@forEach
                val poly = plane.polygon ?: return@forEach
                if (!poly.hasRemaining()) return@forEach

                val snapshot = capturePlaneSnapshot(plane, poly)
                planeSnapshots.add(snapshot)

                // Incremental plane integration for immediate visual feedback
                mapBuilder.integratePlane(snapshot)

                slamEngine.addEdges(poly)
            }

            // --- Keyframe capture ---
            if (poseTracker.shouldCaptureKeyframe(cx, cz, latestHeading)) {
                val keyframe = Keyframe(
                    timestamp = System.currentTimeMillis(),
                    poseX = cx, poseY = pose.ty(), poseZ = cz,
                    headingRad = latestHeading,
                    forwardX = fwdX, forwardZ = fwdZ,
                    planes = planeSnapshots,
                    objectSightings = emptyList()  // objects added separately via detection pipeline
                )
                observationStore.append(keyframe)
                poseTracker.markKeyframeCaptured(cx, cz, latestHeading)
            }

            // --- Depth-hit wall extraction (every 200ms on GL thread) ---
            // Denser sampling at higher frequency for better wall/free-space coverage.
            val depthNow = System.currentTimeMillis()
            if (depthNow - lastDepthSampleMs >= 500L) {
                lastDepthSampleMs = depthNow
                extractWallsFromDepth(frame, camera)
            }

            // --- Reference anchor management (drift detection) ---
            poseTracker.maybeCreateAnchor(session, frame)

            // --- Periodic grid rebuild from observation store ---
            val now = System.currentTimeMillis()
            val shouldRebuild = (now - lastRebuildMs >= REBUILD_INTERVAL_MS) ||
                    poseTracker.hasDriftExceededThreshold()

            if (shouldRebuild && !rebuilding.get()) {
                val currentVersion = observationStore.version
                if (currentVersion != lastRebuildVersion) {
                    lastRebuildMs = now
                    lastRebuildVersion = currentVersion
                    val keyframes = observationStore.snapshot()
                    rebuilding.set(true)
                    rebuildExecutor.execute {
                        try {
                            mapBuilder.rebuild(keyframes)
                            // Re-anchor reference points at corrected positions after rebuild
                            poseTracker.resetAnchorsAfterRebuild()
                        } catch (e: Exception) {
                            println("$TAG: rebuild: ${e.message}")
                        } finally {
                            rebuilding.set(false)
                        }
                    }
                }
            }

            semanticMap.removeStaleObjects()
            localizationSmoother.removeStale(5000L)

            // Navigation tick
            val nm = navigationManager
            if (nm != null && nm.needsGrid) {
                nm.tick(cx, cz, latestHeading, HashMap(mapBuilder.grid), semanticMap,
                    isTracking = lastTrackingState == TrackingState.TRACKING,
                    observationCounts = mapBuilder.observationCountSnapshot())
            }
        } catch (e: Exception) { println("$TAG: slam: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Depth-hit wall extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sample a grid of screen positions with ARCore hit-tests and classify each
     * hit point as floor (free space) or wall (occupied) based on its height
     * relative to the camera.
     *
     * This is the primary source of wall data because:
     * 1. It runs on the GL thread with the CURRENT frame — no stale-frame problem.
     * 2. It works even when ARCore's vertical plane detection fails (common indoors).
     * 3. It samples 120 points per call (12 cols × 10 rows) for dense coverage.
     *
     * Height rules (relative to camera Y, assuming ~1.5m camera height):
     *   relY < -1.2m  → floor level  → mark as free
     *   -0.5m ≤ relY ≤ 0.6m → true wall level (close to camera height) → mark as wall
     *   -1.2m ≤ relY < -0.5m → furniture/obstacle level → mark as obstacle (not wall)
     *   relY > 0.6m   → ceiling/shelf → ignore
     *
     * This separation ensures:
     *   - Beds, tables (low) → obstacle (brown), not wall (dark)
     *   - Actual walls (camera height) → wall (dark)
     *   - Floor → free (white)
     */
    private fun extractWallsFromDepth(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return
        val cameraY = camera.pose.ty()
        val userX = camera.pose.tx()
        val userZ = camera.pose.tz()
        // Reduced grid: 8×6 = 48 points. Fewer points = fewer wasted hit-tests,
        // and the successful ones still provide good coverage.
        val cols = 8; val rows = 6
        val stepX = surfaceWidth  / cols.toFloat()
        val stepY = surfaceHeight / rows.toFloat()

        for (col in 0 until cols) {
            for (row in 0 until rows) {
                val sx = col * stepX + stepX / 2f
                val sy = row * stepY + stepY / 2f
                try {
                    val hits = frame.hitTest(sx, sy)
                    val hit = hits.firstOrNull { h ->
                        val t = h.trackable
                        (t is Plane && t.trackingState == TrackingState.TRACKING) ||
                                t is com.google.ar.core.DepthPoint ||
                                t is com.google.ar.core.InstantPlacementPoint
                    } ?: continue

                    val hp  = hit.hitPose
                    val hx  = hp.tx()
                    val hz  = hp.tz()
                    val hy  = hp.ty()

                    // Ignore hits that are too far away — unreliable depth
                    if (hit.distance > 4.0f) continue

                    val relY = hy - cameraY
                    // Check if hit landed on a tracked vertical plane (structural wall)
                    val isVerticalPlane = (hit.trackable as? Plane)?.type == Plane.Type.VERTICAL

                    when {
                        // Floor: well below camera
                        relY < -1.2f -> mapBuilder.markHitFree(hx, hz)
                        // True wall band: near camera height OR confirmed vertical plane
                        isVerticalPlane || relY in -0.5f..0.6f ->
                            mapBuilder.markHitOccupied(hx, hz)
                        // Furniture/obstacle band: between floor and wall level
                        relY in -1.2f..-0.5f ->
                            mapBuilder.markHitObstacle(hx, hz)
                        // Above 0.6m from camera = ceiling/shelf — ignore
                    }
                } catch (_: Exception) { /* hit-test can throw on degraded tracking */ }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AR Mesh Overlays
    // ─────────────────────────────────────────────────────────────────────────

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private fun drawMeshOverlays(sess: Session, frame: Frame, camera: Camera) {
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // Draw all tracked planes
        for (plane in sess.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.subsumedBy != null) continue
            val type = meshRenderer.classifyPlane(plane, camera.pose)
            meshRenderer.drawPlane(plane, type, projMatrix, viewMatrix)
        }

        // Draw ground-plane footprints under confirmed YOLO objects
        val floorY = camera.pose.ty() - 1.5f  // approximate floor
        for (obj in semanticMap.getAllObjects()) {
            val half = ObjectLocalizer.footprintHalfMetres(obj.category)
            meshRenderer.drawObjectFootprint(obj.position, half, floorY,
                projMatrix, viewMatrix)
        }
    }

    /**
     * Capture a PlaneSnapshot from an ARCore Plane — transforms polygon
     * vertices from plane-local space to world space.
     */
    private fun capturePlaneSnapshot(plane: Plane, poly: java.nio.FloatBuffer): PlaneSnapshot {
        val planePose = plane.centerPose
        val verts = mutableListOf<Pair<Float, Float>>()
        poly.rewind()
        while (poly.remaining() >= 2) {
            val lx = poly.get(); val lz = poly.get()
            val world = planePose.transformPoint(floatArrayOf(lx, 0f, lz))
            verts.add(Pair(world[0], world[2]))
        }
        val type = when (plane.type) {
            Plane.Type.VERTICAL -> PlaneType.VERTICAL_WALL
            else -> PlaneType.HORIZONTAL_FREE
        }
        return PlaneSnapshot(type, verts, plane.hashCode())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic object merge / add
    // ─────────────────────────────────────────────────────────────────────────

    private fun mergeOrAdd(det: YoloDetector.Detection, wp: Point3D, method: String? = null) {
        val existing = semanticMap.getAllObjects().firstOrNull { o ->
            o.category == det.label && o.position.distance(wp) < MERGE_DIST
        }
        if (existing != null) {
            // Weight recent observations more heavily to correct for drift
            val n = existing.observations + 1
            val weight = (1f / sqrt(n.toFloat())).coerceIn(0.1f, 0.5f)
            semanticMap.updateObject(existing.copy(
                position    = Point3D(
                    existing.position.x * (1 - weight) + wp.x * weight,
                    existing.position.y * (1 - weight) + wp.y * weight,
                    existing.position.z * (1 - weight) + wp.z * weight),
                confidence  = maxOf(existing.confidence, det.confidence),
                lastSeen    = System.currentTimeMillis(),
                observations = n,
                localizationMethod = method ?: existing.localizationMethod
            ))
        } else {
            val gx = mapBuilder.worldToGrid(wp.x); val gz = mapBuilder.worldToGrid(wp.z)
            semanticMap.addObject(SemanticObject(
                id          = "${det.label}_${gx}_${gz}",
                type        = ObjectType.fromLabel(det.label),
                category    = det.label,
                position    = wp,
                boundingBox = BoundingBox2D(det.boundingBox.left, det.boundingBox.top,
                    det.boundingBox.right, det.boundingBox.bottom),
                confidence  = det.confidence,
                firstSeen   = System.currentTimeMillis(),
                lastSeen    = System.currentTimeMillis(),
                localizationMethod = method
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OCR text detection processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process a single OCR text detection: estimate 3D position, create or
     * merge a semantic object, and optionally associate room numbers with
     * nearby door objects.
     */
    private fun processTextDetection(
        textDet: TextDetection,
        pose: com.google.ar.core.Pose,
        frame: Frame?
    ) {
        // Estimate 3D position using the same localizer as YOLO
        val wp = objectLocalizer.estimate3D(
            textDet.boundingBox, pose, CAM_W, CAM_H,
            frame = frame,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            frameTimestampNs = 0L,          // OCR runs async; skip hit-test, use fallback
            currentFrameTimestampNs = latestFrameTs
        ) ?: return

        when (textDet.classification) {
            TextClassification.ROOM_NUMBER -> {
                val roomNum = textDet.roomNumber ?: return
                // Try to associate with a nearby door
                val nearbyDoor = semanticMap.getAllObjects().firstOrNull { o ->
                    o.type == ObjectType.DOOR && o.position.distance(wp) < 2.0f
                }
                if (nearbyDoor != null) {
                    // Attach room number to the existing door object
                    semanticMap.updateObject(nearbyDoor.copy(
                        roomNumber = roomNum,
                        textContent = textDet.text,
                        lastSeen = System.currentTimeMillis()
                    ))
                    println("$TAG: OCR linked room $roomNum to door ${nearbyDoor.id}")
                } else {
                    // Create standalone room label
                    mergeOrAddText(textDet, wp, ObjectType.ROOM_LABEL, roomNum)
                }
            }
            TextClassification.SIGN -> {
                val objType = ObjectType.fromTextLandmark(textDet.landmarkType)
                mergeOrAddText(textDet, wp, objType, null)
            }
            TextClassification.NOTICE -> {
                // Associate with a nearby notice board if one exists
                val nearbyBoard = semanticMap.getAllObjects().firstOrNull { o ->
                    o.type == ObjectType.NOTICE_BOARD && o.position.distance(wp) < 2.0f
                }
                if (nearbyBoard != null) {
                    semanticMap.updateObject(nearbyBoard.copy(
                        textContent = textDet.text,
                        lastSeen = System.currentTimeMillis()
                    ))
                    println("$TAG: OCR linked notice text to board ${nearbyBoard.id}")
                } else {
                    mergeOrAddText(textDet, wp, ObjectType.TEXT_SIGN, null)
                }
            }
            TextClassification.GENERAL -> {
                // Store as generic text sign if sufficiently confident
                if (textDet.confidence >= 0.5f) {
                    mergeOrAddText(textDet, wp, ObjectType.TEXT_SIGN, null)
                }
            }
        }
    }

    /**
     * Merge or add a text-based semantic object. Similar to [mergeOrAdd] but
     * uses text content for identity matching instead of YOLO label.
     */
    private fun mergeOrAddText(textDet: TextDetection, wp: Point3D, objType: ObjectType, roomNum: String?) {
        val category = objType.name.lowercase()
        val textNorm = textDet.text.trim().lowercase()

        // Merge with existing text object if same type and nearby with similar text
        val existing = semanticMap.getAllObjects().firstOrNull { o ->
            o.type == objType &&
                    o.position.distance(wp) < MERGE_DIST &&
                    (o.textContent?.trim()?.lowercase() == textNorm || o.roomNumber == roomNum)
        }

        if (existing != null) {
            val n = existing.observations + 1
            val weight = (1f / kotlin.math.sqrt(n.toFloat())).coerceIn(0.1f, 0.5f)
            semanticMap.updateObject(existing.copy(
                position = Point3D(
                    existing.position.x * (1 - weight) + wp.x * weight,
                    existing.position.y * (1 - weight) + wp.y * weight,
                    existing.position.z * (1 - weight) + wp.z * weight),
                confidence = maxOf(existing.confidence, textDet.confidence),
                lastSeen = System.currentTimeMillis(),
                observations = n,
                textContent = textDet.text,
                roomNumber = roomNum ?: existing.roomNumber
            ))
        } else {
            val gx = mapBuilder.worldToGrid(wp.x); val gz = mapBuilder.worldToGrid(wp.z)
            semanticMap.addObject(SemanticObject(
                id = "${category}_${gx}_${gz}",
                type = objType,
                category = category,
                position = wp,
                boundingBox = BoundingBox2D(
                    textDet.boundingBox.left, textDet.boundingBox.top,
                    textDet.boundingBox.right, textDet.boundingBox.bottom),
                confidence = textDet.confidence,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                textContent = textDet.text,
                roomNumber = roomNum
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HUD
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateHud(camera: Camera) {
        if (destroyed) return
        val now = System.currentTimeMillis()
        if (now - lastHudMs < 300L) return; lastHudMs = now
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()
        val grid = mapBuilder.grid
        val drift = poseTracker.checkDrift()
        val text = buildString {
            if (camera.trackingState != TrackingState.TRACKING) {
                appendLine("⚠ TRACKING LOST ⚠")
            }
            appendLine("AR: ${camera.trackingState}")
            appendLine("Pos x=${s.currentPosition.x.f2} z=${s.currentPosition.z.f2}")
            appendLine("Cells free=${grid.count { it.value.toInt() == MapBuilder.CELL_FREE || it.value.toInt() == MapBuilder.CELL_VISITED }} " +
                    "obstacle=${grid.count { it.value.toInt() == MapBuilder.CELL_OBSTACLE }}")
            appendLine("Objects confirmed=${sem.totalObjects}")
            appendLine("KFs=${observationStore.size()} drift=${"%.3f".format(drift)}m")
            appendLine("YOLO ${lastInferenceMs}ms  OCR ${lastOcrInferenceMs}ms")
            val textObjs = semanticMap.getAllObjects().count { it.textContent != null }
            append("Text landmarks: $textObjs")
        }
        runOnUiThread {
            hudView.text = text
            hudView.contentDescription = "Status: $text"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flutter bridge
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendToFlutter() {
        if (destroyed) return
        val ch  = methodChannel ?: return
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()
        val pose = latestPose
        val heading = if (pose != null) {
            val q = pose.rotationQuaternion
            val sinY = 2f * (q[3] * q[1] + q[2] * q[0])
            val cosY = 1f - 2f * (q[1] * q[1] + q[2] * q[2])
            kotlin.math.atan2(sinY, cosY)
        } else 0f

        runOnUiThread {
            if (destroyed) return@runOnUiThread
            ch.invokeMethod("onUpdate", mapOf(
                "position_x" to s.currentPosition.x, "position_y" to s.currentPosition.y,
                "position_z" to s.currentPosition.z, "heading"    to heading,
                "edges_count" to s.edgeCount,         "cells_count" to s.cellCount,
                "total_objects" to sem.totalObjects,
                "chairs"             to (sem.objectCounts[ObjectType.CHAIR]             ?: 0),
                "doors"              to (sem.objectCounts[ObjectType.DOOR]              ?: 0),
                "fire_extinguishers" to (sem.objectCounts[ObjectType.FIRE_EXTINGUISHER] ?: 0),
                "lift_gates"         to (sem.objectCounts[ObjectType.LIFT_GATE]         ?: 0),
                "notice_boards"      to (sem.objectCounts[ObjectType.NOTICE_BOARD]      ?: 0),
                "trash_cans"         to (sem.objectCounts[ObjectType.TRASH_CAN]         ?: 0),
                "water_purifiers"    to (sem.objectCounts[ObjectType.WATER_PURIFIER]    ?: 0),
                "windows"            to (sem.objectCounts[ObjectType.WINDOW]            ?: 0)
            ))

            val now = System.currentTimeMillis()
            if (now - lastMapMs >= 800L) {
                lastMapMs = now
                ch.invokeMethod("updateMap", buildMapPayload(s.currentPosition))
            }
        }
    }

    private fun buildMapPayload(curPos: Point3D): Map<String, Any> {
        val localGrid = HashMap(mapBuilder.grid)
        if (localGrid.isEmpty()) return mapOf(
            "occupancyGrid" to ByteArray(0), "gridWidth" to 0, "gridHeight" to 0,
            "gridResolution" to RES.toDouble(), "objects" to emptyList<Any>()
        )

        val gMinX = localGrid.keys.minOf { it.x }; val gMaxX = localGrid.keys.maxOf { it.x }
        val gMinZ = localGrid.keys.minOf { it.z }; val gMaxZ = localGrid.keys.maxOf { it.z }
        val w = gMaxX - gMinX + 1; val h = gMaxZ - gMinZ + 1

        val bytes = ByteArray(w * h)
        localGrid.forEach { (cell, type) ->
            val idx = (cell.z - gMinZ) * w + (cell.x - gMinX)
            if (idx in bytes.indices) bytes[idx] = type
        }

        val objects = semanticMap.getAllObjects().map { obj ->
            val m = mutableMapOf<String, Any>(
                "id"       to obj.id,       "type"       to obj.type.name,
                "label"    to obj.category, "confidence" to obj.confidence,
                "x"        to obj.position.x, "y" to obj.position.y, "z" to obj.position.z,
                "gridX"    to (mapBuilder.worldToGrid(obj.position.x) - gMinX),
                "gridZ"    to (mapBuilder.worldToGrid(obj.position.z) - gMinZ),
                "observations" to obj.observations
            )
            obj.textContent?.let { m["textContent"] = it }
            obj.roomNumber?.let  { m["roomNumber"]  = it }
            m as Map<String, Any>
        }

        val navPath = navigationManager?.currentSession?.path?.map { wp ->
            mapOf("x" to (wp.gridX - gMinX), "z" to (wp.gridZ - gMinZ))
        } ?: emptyList<Any>()

        return mapOf(
            "occupancyGrid"  to bytes,
            "gridWidth"      to w,      "gridHeight"     to h,
            "gridResolution" to RES.toDouble(),
            "originX"        to gMinX,  "originZ"        to gMinZ,
            "robotGridX"     to (mapBuilder.worldToGrid(curPos.x) - gMinX),
            "robotGridZ"     to (mapBuilder.worldToGrid(curPos.z) - gMinZ),
            "objects"        to objects,
            "navPath"        to navPath
        )
    }

    // ── Navigation UI helpers ─────────────────────────────────────────────────

    private fun updateNavHud(state: NavigationState, message: String) {
        if (destroyed) return
        val (label, bgColor) = when (state) {
            NavigationState.LISTENING  -> "🎤 $message" to 0xDD7C3AED.toInt()
            NavigationState.PLANNING   -> "🔍 $message" to 0xDD2563EB.toInt()
            NavigationState.NAVIGATING -> "🧭 $message" to 0xDD1D4ED8.toInt()
            NavigationState.ARRIVED    -> "✅ $message"  to 0xDD16A34A.toInt()
            NavigationState.ERROR      -> "⚠️ $message"  to 0xDDDC2626.toInt()
            NavigationState.IDLE       -> ""             to 0
        }
        (navView.background as? GradientDrawable)?.setColor(bgColor)
        navView.text       = label
        navView.visibility = if (state == NavigationState.IDLE) android.view.View.GONE
        else android.view.View.VISIBLE

        // Update mic button style based on navigation state
        val micBg = micButton.background as? GradientDrawable
        if (state == NavigationState.NAVIGATING) {
            micButton.text = "⏹"
            micBg?.setColor(0xFFDC2626.toInt())
        } else {
            micButton.text = "🎤"
            micBg?.setColor(0xFF2563EB.toInt())
        }

        // Accessibility: update content descriptions and announce state changes
        navView.contentDescription = message
        micButton.contentDescription = if (state == NavigationState.NAVIGATING)
            "Tap to stop navigation" else "Tap to give a voice command"
        if (state != NavigationState.IDLE) {
            navView.announceForAccessibility(message)
        }

        navChannel?.invokeMethod("navStateChange",
            mapOf("state" to state.name, "message" to message))
    }

    private fun onMicTapped() {
        if (!hasMicPerm()) { reqMicPerm(); return }
        if (navigationManager?.state == NavigationState.NAVIGATING) {
            navigationManager?.stopNavigation()
        } else {
            navigationManager?.startVoiceCommand()
        }
    }
}

private val Float.f2 get() = "%.2f".format(this)