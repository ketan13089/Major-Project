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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
    private lateinit var compassView: CompassView
    private lateinit var rootLayout: FrameLayout

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
    private var lastDepthProcessMs = 0L          // throttle dense depth + confidence
    private var lastWallInferMs  = 0L           // throttle motion-based wall inference

    // Pre-allocated buffer for depth unprojection
    private val depthWorldBuf = FloatArray(3)

    // Raw depth capability check — cached after first attempt
    @Volatile private var rawDepthSupported: Boolean? = null

    // ── Flutter ───────────────────────────────────────────────────────────────
    private var methodChannel: MethodChannel? = null
    private var navChannel: MethodChannel? = null
    private var mapChannel: MethodChannel? = null
    private lateinit var mapPersistence: MapPersistence
    private var emergencyManager: EmergencyManager? = null
    private var onboardingTutorial: OnboardingTutorial? = null
    private var lastHudMs = 0L; private var lastFlutterMs = 0L; private var lastMapMs = 0L; private var lastPerfPushMs = 0L
    private var sessionStartMs = 0L

    // ── Performance throttles ────────────────────────────────────────────────
    // Navigation tick: reuse cached grid snapshot instead of copying every frame
    private var lastNavTickMs = 0L
    @Volatile private var cachedNavGrid: HashMap<GridCell, Byte>? = null
    @Volatile private var cachedObsCounts: Map<GridCell, Int>? = null
    @Volatile private var cachedNavGridVersion = -1L   // track rebuild version to avoid redundant copies
    // Stale object removal: every 5s, not every frame
    private var lastStaleCheckMs = 0L
    // Drift check cache: avoid redundant anchor iteration
    @Volatile private var cachedDrift = 0f
    private var lastDriftCheckMs = 0L

    // ── Semantic AI Correction ─────────────────────────────────────────────────
    private var semanticCorrectionEngine: SemanticCorrectionEngine? = null

    // ── LLM Assistant (query + navigation + vision updates) ───────────────────
    private var llmAssistant: LlmAssistant? = null
    private var llmUi: LlmAssistantUi? = null
    private val llmExecutor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()
    // Latest YUV snapshot (re-published from the shared-camera listener) for
    // vision updates. We copy these once per listener tick, not per frame.
    @Volatile private var llmLastYuv: LlmYuvSnapshot? = null

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
                    "getPerformanceMetrics" -> {
                        result.success(PerformanceTracker.snapshot().toFlatMap())
                    }
                    "exportPerformanceReport" -> {
                        val path = PerformanceTracker.exportJson(this@ArActivity)
                        if (path != null) result.success(mapOf("path" to path))
                        else result.error("EXPORT_FAILED", "Failed to export report", null)
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

        // Wire up stale-object cleanup → footprint clearing (only for obstacle types)
        semanticMap.onObjectRemoved = { obj ->
            val affordance = ObjectAffordance.forType(obj.type)
            if (affordance == ObjectAffordance.FLOOR_OBSTACLE) {
                val halfM = ObjectLocalizer.footprintHalfMetres(obj.category)
                mapBuilder.clearObstacleFootprint(obj.position, halfM)
            }
        }

        // Initialize semantic AI corrector — key comes from BuildConfig (set in local.properties)
        SemanticCorrectionConfig.apiKey = BuildConfig.OPENROUTER_API_KEY
        if (SemanticCorrectionConfig.apiKey.isNotBlank() &&
            SemanticCorrectionConfig.apiKey != "PASTE_YOUR_OPENROUTER_KEY_HERE") {
            SemanticCorrectionConfig.AI_SEMANTIC_CORRECTOR_ENABLED = true
            semanticCorrectionEngine = SemanticCorrectionEngine(mapBuilder, semanticMap, RES)
            println("$TAG: Semantic AI corrector initialized")
        } else {
            println("$TAG: Semantic AI corrector disabled (no API key in local.properties)")
        }

        // LLM Assistant — reads key + model from BuildConfig (local.properties).
        LlmAssistantConfig.apiKey = BuildConfig.LLM_ASSISTANT_API_KEY
        LlmAssistantConfig.model  = BuildConfig.LLM_ASSISTANT_MODEL
        if (LlmAssistantConfig.enabled) {
            llmAssistant = LlmAssistant(semanticMap, mapBuilder)
            println("$TAG: LLM assistant initialized (model=${LlmAssistantConfig.model})")
        } else {
            println("$TAG: LLM assistant disabled — set llm.assistant.api.key and llm.assistant.model in local.properties")
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

        // Performance tracking
        PerformanceTracker.reset()
        PerformanceTracker.markSessionStart()

        // Attach LLM assistant UI (Ask + Guide me to FABs + spinner + reply card).
        attachLlmUi()

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
        compassView.start()
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        displayRotationHelper?.onPause()
        compassView.stop()
        session?.pause()
        overlayView.clearDetections()
        teardownCamera()
        // Auto-save map on every pause (leaving AR)
        try {
            if (mapBuilder.grid.isNotEmpty()) {
                val bc = poseTracker.getBreadcrumbs()
                // Always save as last_session for quick resume
                mapPersistence.saveMap("last_session", mapBuilder, semanticMap, bc, sessionStartMs)
                // Also save a timestamped session copy
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US)
                val sessionName = "Session_${sdf.format(java.util.Date())}"
                mapPersistence.saveMap(sessionName, mapBuilder, semanticMap, bc, sessionStartMs)
            }
        } catch (e: Exception) { println("$TAG: auto-save: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM Assistant integration
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachLlmUi() {
        llmUi = LlmAssistantUi(this, rootLayout) { flow, transcript ->
            when (flow) {
                LlmTaskKind.QUERY    -> runLlmQuery(transcript)
                LlmTaskKind.NAVIGATE -> runLlmNavigate(transcript)
                else                 -> {}
            }
        }
    }

    private fun runLlmQuery(text: String) {
        val assistant = llmAssistant ?: run {
            llmUi?.toast("LLM assistant not configured"); return
        }
        val pose = latestPose ?: run {
            llmUi?.toast("Waiting for tracking — try again in a moment"); return
        }
        llmUi?.showLoading(true)
        llmExecutor.execute {
            val result = try {
                assistant.query(text, pose.tx(), pose.tz(), latestHeading)
            } catch (e: Exception) { println("$TAG: LLM query: ${e.message}"); null }
            runOnUiThread {
                llmUi?.showLoading(false)
                if (result == null) {
                    llmUi?.toast("Assistant unavailable. Check network or API key.")
                } else {
                    llmUi?.showReply(result.answer)
                    announcer?.speak(result.answer)
                }
            }
        }
    }

    private fun runLlmNavigate(text: String) {
        val assistant = llmAssistant ?: run {
            llmUi?.toast("LLM assistant not configured"); return
        }
        val pose = latestPose ?: run {
            llmUi?.toast("Waiting for tracking — try again in a moment"); return
        }
        llmUi?.showLoading(true)
        llmExecutor.execute {
            val result = try {
                assistant.navigate(text, pose.tx(), pose.tz(), latestHeading)
            } catch (e: Exception) { println("$TAG: LLM navigate: ${e.message}"); null }
            runOnUiThread {
                llmUi?.showLoading(false)
                if (result == null) {
                    llmUi?.toast("Could not plan route.")
                    return@runOnUiThread
                }
                llmUi?.showReply(result.spoken)
                announcer?.speak(result.spoken)

                val dest = resolveLlmDestination(result)
                if (dest == null) {
                    announcer?.speak("I couldn't find a matching destination on the map yet. Keep scanning and try again.")
                    return@runOnUiThread
                }
                startLlmNavigationTo(dest)
            }
        }
    }

    /** Find the SemanticObject corresponding to the LLM's chosen target. */
    private fun resolveLlmDestination(result: LlmNavigateResult): SemanticObject? {
        // Prefer explicit id if the LLM quoted one from NEARBY_OBJECTS
        result.targetObjectId?.let { id ->
            semanticMap.getAllObjects().firstOrNull { it.id == id }?.let { return it }
        }
        // Fallback: synthesize a virtual destination from explicit coords
        val tx = result.targetWorldX; val tz = result.targetWorldZ
        if (tx != null && tz != null) {
            return SemanticObject(
                id = "llm_target_${System.currentTimeMillis()}",
                type = ObjectType.UNKNOWN,
                category = "destination",
                position = Point3D(tx, 0f, tz),
                boundingBox = BoundingBox2D(0f, 0f, 0f, 0f),
                confidence = 1f,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                observations = 2
            )
        }
        return null
    }

    /** Kick off turn-by-turn navigation to an LLM-chosen destination. */
    private fun startLlmNavigationTo(dest: SemanticObject) {
        val pose = latestPose ?: return
        val nm   = navigationManager ?: return
        // Ensure the destination exists in the map for arrival detection.
        if (semanticMap.getAllObjects().none { it.id == dest.id }) {
            semanticMap.addObject(dest)
        }
        val grid = cachedNavGrid ?: HashMap(mapBuilder.grid)
        nm.navigateToExplicit(
            dest = dest,
            userX = pose.tx(), userZ = pose.tz(),
            grid = grid,
            semanticMap = semanticMap,
            observationCounts = cachedObsCounts
        )
    }

    /** Stash a YUV snapshot for periodic vision updates. Called from the shared-camera listener. */
    fun publishYuvForLlm(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yStride: Int, uvStride: Int, uvPixStride: Int,
        width: Int, height: Int
    ) {
        llmLastYuv = LlmYuvSnapshot(y, u, v, yStride, uvStride, uvPixStride, width, height)
        maybeRunVisionUpdate()
    }

    private fun maybeRunVisionUpdate() {
        val assistant = llmAssistant ?: return
        val now = System.currentTimeMillis()
        if (!assistant.shouldRunVisionUpdate(now)) return
        val snap = llmLastYuv ?: return
        val pose = latestPose ?: return
        assistant.markVisionRun(now)

        llmExecutor.execute {
            val b64 = LlmImageEncoder.yuvToBase64Jpeg(
                snap.y, snap.u, snap.v,
                snap.yStride, snap.uvStride, snap.uvPixStride,
                snap.width, snap.height
            ) ?: return@execute
            val result = try {
                assistant.visionUpdate(b64, pose.tx(), pose.tz(), latestHeading)
            } catch (e: Exception) { println("$TAG: LLM vision: ${e.message}"); null }
            if (result != null) applyVisionResult(result, pose)
        }
    }

    /**
     * Merge LLM vision observations into the semantic map by projecting each
     * (direction, distance_bucket) into approximate world coordinates in
     * front/left/right/back of the user, then calling semanticMap.addObject
     * (which de-duplicates nearby entries of the same type).
     */
    private fun applyVisionResult(result: LlmVisionUpdate, pose: com.google.ar.core.Pose) {
        val userX = pose.tx(); val userZ = pose.tz()
        val heading = latestHeading
        val now = System.currentTimeMillis()

        result.observed.forEach { obs ->
            val distance = when (obs.distanceBucket.lowercase()) {
                "near" -> 1.5f; "mid" -> 3.5f; "far" -> 6.0f; else -> 3.0f
            }
            val bearingOffset = when (obs.relativeDirection.lowercase()) {
                "front" -> 0f
                "right" -> (Math.PI / 2).toFloat()
                "back"  -> Math.PI.toFloat()
                "left"  -> -(Math.PI / 2).toFloat()
                else    -> 0f
            }
            val worldBearing = heading + bearingOffset
            // ARCore: -Z forward, +X right
            val tx = userX + kotlin.math.sin(worldBearing) * distance
            val tz = userZ - kotlin.math.cos(worldBearing) * distance

            val type = ObjectType.fromLabel(obs.label)
            val obj = SemanticObject(
                id = "llm_vis_${now}_${obs.label.hashCode()}",
                type = type,
                category = obs.label,
                position = Point3D(tx, pose.ty(), tz),
                boundingBox = BoundingBox2D(0f, 0f, 0f, 0f),
                confidence = 0.6f,
                firstSeen = now,
                lastSeen = now,
                observations = 1,
                localizationMethod = "llm_vision"
            )
            semanticMap.addObject(obj)
        }
        if (result.summary.isNotBlank()) {
            println("$TAG: LLM vision: ${result.summary}")
        }
    }

    override fun onDestroy() {
        destroyed = true
        // Notify Flutter that AR is closing so it can resume its accessibility
        try {
            val engine = FlutterEngineCache.getInstance().get("slam_engine")
            engine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, "com.ketan.slam/ar").invokeMethod("onARClosed", null)
            }
        } catch (_: Exception) {}
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
        semanticCorrectionEngine?.shutdown()
        llmUi?.destroy(); llmUi = null
        llmExecutor.shutdownNow()
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

        // Compass view — real-time true-north bearing via device sensors
        val compassSize = (100 * dp).toInt()
        compassView = CompassView(this).apply {
            contentDescription = "Compass showing current direction"
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        // Mark camera views as not important for TalkBack (decorative/live content)
        surfaceView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        overlayView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val root = FrameLayout(this)
        rootLayout = root
        root.addView(surfaceView, mpmp())
        root.addView(overlayView, mpmp())
        root.addView(hudView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply {
            setMargins((16 * dp).toInt(), (48 * dp).toInt(), (16 * dp).toInt(), 0)
        })
        root.addView(compassView, FrameLayout.LayoutParams(
            compassSize, compassSize + (16 * dp).toInt(),
            Gravity.TOP or Gravity.END).apply {
            setMargins(0, (44 * dp).toInt(), (12 * dp).toInt(), 0)
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

                    // Publish a copy to the LLM assistant (throttled internally).
                    publishYuvForLlm(yB, uB, vB, yStride, uvStride, uvPix, CAM_W, CAM_H)

                    detectionExecutor.execute {
                        val t0 = System.currentTimeMillis()
                        try {
                            // ── YOLO object detection ───────────────────────────
                            val raw = yoloDetector.detectFromYuv(yB, uB, vB, yStride, uvStride, uvPix, CAM_W, CAM_H)
                            val confirmed = confirmationGate.feed(raw)
                            lastInferenceMs = System.currentTimeMillis() - t0
                            PerformanceTracker.recordYoloInference(
                                lastInferenceMs, confirmed.size,
                                confirmed.map { it.confidence })

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
                                        val objType = ObjectType.fromLabel(det.label)
                                        val affordance = ObjectAffordance.forType(objType)
                                        mapBuilder.markAffordanceAwareFootprint(smoothed, halfM, affordance)
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
                                PerformanceTracker.recordOcrInference(
                                    lastOcrInferenceMs, textDetections.size)

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
        PerformanceTracker.tickFrame()
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
                if (now - lastFlutterMs >= 500L) { lastFlutterMs = now; sendToFlutter() }
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
                PerformanceTracker.recordKeyframe()
            }

            // --- Dense depth + confidence processing (every 500ms) ---
            val depthNow = System.currentTimeMillis()
            if (depthNow - lastDepthProcessMs >= 500L) {
                lastDepthProcessMs = depthNow
                extractDepthConfidenceMap(frame, camera)
            }

            // --- Strategy 3: Motion-based wall inference (every 2s) ---
            if (depthNow - lastWallInferMs >= 2000L) {
                lastWallInferMs = depthNow
                try { inferWallsFromMotionContext() } catch (_: Exception) {}
            }

            // --- Reference anchor management (drift detection) ---
            poseTracker.maybeCreateAnchor(session, frame)

            // --- Drift check (cached, not every frame) ---
            val now = System.currentTimeMillis()
            if (now - lastDriftCheckMs >= 1000L) {
                lastDriftCheckMs = now
                cachedDrift = poseTracker.checkDrift()
            }
            val driftTriggered = cachedDrift > 0.04f

            // --- Periodic grid rebuild from observation store ---
            val shouldRebuild = (now - lastRebuildMs >= REBUILD_INTERVAL_MS)

            if (shouldRebuild && !rebuilding.get()) {
                val currentVersion = observationStore.version
                if (currentVersion != lastRebuildVersion || driftTriggered) {
                    lastRebuildMs = now
                    lastRebuildVersion = currentVersion
                    rebuilding.set(true)

                    if (driftTriggered) {
                        // Full rebuild only on drift — re-projects recent keyframes
                        val keyframes = observationStore.snapshot()
                        rebuildExecutor.execute {
                            val rbT0 = System.currentTimeMillis()
                            try {
                                mapBuilder.rebuild(keyframes)
                                poseTracker.resetAnchorsAfterRebuild()
                                cachedDrift = 0f
                                PerformanceTracker.recordRebuild(System.currentTimeMillis() - rbT0)
                                PerformanceTracker.recordDriftRebuild()
                            } catch (e: Exception) {
                                println("$TAG: rebuild: ${e.message}")
                            } finally {
                                rebuilding.set(false)
                            }
                        }
                    } else {
                        // Light rebuild — decay + enforce + derive, no re-projection
                        val userGX = mapBuilder.worldToGrid(cx)
                        val userGZ = mapBuilder.worldToGrid(cz)
                        rebuildExecutor.execute {
                            val lrT0 = System.currentTimeMillis()
                            try {
                                mapBuilder.lightRebuild(userGX, userGZ)
                                PerformanceTracker.recordLightRebuild(System.currentTimeMillis() - lrT0)
                            } catch (e: Exception) {
                                println("$TAG: lightRebuild: ${e.message}")
                            } finally {
                                rebuilding.set(false)
                            }
                        }
                    }
                }
            }

            // --- Semantic AI correction (throttled, non-blocking) ---
            semanticCorrectionEngine?.maybeSubmitCorrection(
                mapBuilder.worldToGrid(cx),
                mapBuilder.worldToGrid(cz),
                latestHeading
            )

            // Stale object removal — every 5s, not every frame
            if (now - lastStaleCheckMs >= 5000L) {
                lastStaleCheckMs = now
                semanticMap.removeStaleObjects()
                localizationSmoother.removeStale(5000L)
            }
            // Batched relation graph rebuild (only if dirty, max every 3s)
            semanticMap.maybeRebuildGraph()

            // Navigation tick — throttled to 500ms; only copy grid when rebuild version changes
            val nm = navigationManager
            if (nm != null && nm.needsGrid) {
                if (now - lastNavTickMs >= 500L) {
                    lastNavTickMs = now
                    val currentVersion = observationStore.version
                    if (currentVersion != cachedNavGridVersion) {
                        cachedNavGridVersion = currentVersion
                        cachedNavGrid = HashMap(mapBuilder.grid)
                        cachedObsCounts = mapBuilder.observationCountSnapshot()
                    }
                }
                val gridSnap = cachedNavGrid
                if (gridSnap != null) {
                    nm.tick(cx, cz, latestHeading, gridSnap, semanticMap,
                        isTracking = lastTrackingState == TrackingState.TRACKING,
                        observationCounts = cachedObsCounts ?: emptyMap())
                }
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
     * Dense depth + confidence map processing.
     * Replaces both extractWallsFromDepth (hit-test based) and
     * extractWallsFromDepthImage (sparse depth image) with a single unified pass.
     *
     * Uses acquireRawDepthImage() + acquireRawDepthConfidenceImage() for per-pixel
     * confidence weighting. Falls back to smoothed depth (confidence=255) on
     * devices that don't support raw depth.
     *
     * Height classification (relY = point Y - camera Y):
     *   < -1.2m         → floor (free)
     *   -1.2m to -0.8m  → furniture/obstacle
     *   -0.8m to 1.0m   → wall
     *   > 1.0m          → ceiling (ignore)
     */
    private fun extractDepthConfidenceMap(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return

        val (depthImage, confImage) = acquireDepthPair(frame) ?: return

        try {
            val w = depthImage.width
            val h = depthImage.height
            val depthPlane = depthImage.planes[0]
            val depthBuf = depthPlane.buffer
            val depthRowStride = depthPlane.rowStride

            val confPlane = confImage?.planes?.get(0)
            val confBuf = confPlane?.buffer
            val confRowStride = confPlane?.rowStride ?: 0

            // Camera intrinsics for unprojection
            val intrinsics = camera.textureIntrinsics
            val focalLen = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val fx = focalLen[0]; val fy = focalLen[1]
            val cx = principal[0]; val cy = principal[1]
            val dims = intrinsics.imageDimensions
            val imageW = dims[0].toFloat()
            val imageH = dims[1].toFloat()
            val scaleX = w.toFloat() / imageW
            val scaleY = h.toFloat() / imageH
            val dfx = fx * scaleX; val dfy = fy * scaleY
            val dcx = cx * scaleX; val dcy = cy * scaleY

            val pose = camera.pose
            val cameraY = pose.ty()
            val camWx = pose.tx()
            val camWz = pose.tz()

            val step = 4  // dense sampling: every 4 pixels
            var wallHitCount = 0

            // Per-column tracking for white wall inference
            val cols = w / step
            val colMisses = IntArray(cols)
            val colWallHits = IntArray(cols)
            val colFloorHits = IntArray(cols)
            // Store floor hit world positions per column for white wall marking
            val colFloorWx = FloatArray(cols)
            val colFloorWz = FloatArray(cols)

            // Forward direction for white wall inference
            val q = pose.rotationQuaternion
            val fwdX = 2f * (q[0] * q[2] + q[1] * q[3])
            val fwdZ = 1f - 2f * (q[0] * q[0] + q[1] * q[1])
            val fLen = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(0.001f)
            val fnx = fwdX / fLen; val fnz = fwdZ / fLen

            for (v in step / 2 until h step step) {
                for (u in step / 2 until w step step) {
                    val colIdx = u / step
                    val depthOffset = v * depthRowStride + u * 2
                    if (depthOffset + 1 >= depthBuf.capacity()) continue

                    val depthMm = depthBuf.getShort(depthOffset).toInt() and 0xFFFF
                    if (depthMm == 0 || depthMm > 5000) {
                        // No depth data — count as miss for white wall inference
                        if (colIdx < cols) colMisses[colIdx]++
                        continue
                    }

                    // Read per-pixel confidence (0-255), default 255 if no confidence image
                    val conf = if (confBuf != null) {
                        val confOffset = v * confRowStride + u
                        if (confOffset < confBuf.capacity()) {
                            confBuf.get(confOffset).toInt() and 0xFF
                        } else 255
                    } else {
                        255  // full trust for smoothed depth fallback
                    }

                    val depthM = depthMm / 1000f

                    // Unproject to camera-local 3D
                    val localX = ((u - dcx) * depthM) / dfx
                    val localY = ((v - dcy) * depthM) / dfy

                    // Transform to world space via camera pose
                    val world = pose.transformPoint(floatArrayOf(localX, localY, -depthM))
                    val wx = world[0]
                    val wz = world[2]
                    val relY = world[1] - cameraY

                    // Unified confidence-weighted integration
                    mapBuilder.markDepthPoint(wx, wz, conf, relY)

                    // Track per-column stats for white wall inference
                    if (colIdx < cols) {
                        when {
                            relY < -1.2f -> {
                                colFloorHits[colIdx]++
                                colFloorWx[colIdx] = wx
                                colFloorWz[colIdx] = wz
                            }
                            relY in -0.8f..1.0f && conf >= 64 -> {
                                colWallHits[colIdx]++
                            }
                        }
                    }

                    // Free-ray clearing for every 3rd high-confidence wall hit
                    if (relY in -0.8f..1.0f && conf >= 128 && depthM > 0.5f) {
                        wallHitCount++
                        if (wallHitCount % 3 == 0) {
                            mapBuilder.markDepthFreeRay(camWx, camWz, wx, wz)
                        }
                    }
                }
            }

            // ── White wall inference from per-column miss patterns ──────────
            inferWhiteWallsFromDepthPattern(
                cols, colMisses, colWallHits, colFloorHits,
                colFloorWx, colFloorWz,
                fnx, fnz, camWx, camWz
            )

        } catch (e: Exception) {
            println("$TAG: depthConfidence: ${e.message}")
        } finally {
            depthImage.close()
            confImage?.close()
        }
    }

    /**
     * Acquire raw depth + confidence images, falling back to smoothed depth
     * if raw depth is not supported on this device.
     * Returns null if no depth at all is available.
     */
    private fun acquireDepthPair(frame: Frame): Pair<android.media.Image, android.media.Image?>? {
        // Try raw depth first (cached capability check)
        if (rawDepthSupported != false) {
            try {
                val rawDepth = frame.acquireRawDepthImage()
                val rawConf = try { frame.acquireRawDepthConfidenceImage() } catch (_: Exception) { null }
                if (rawDepthSupported == null) {
                    rawDepthSupported = true
                    println("$TAG: Raw depth supported: true")
                }
                return rawDepth to rawConf
            } catch (_: Exception) {
                if (rawDepthSupported == null) {
                    rawDepthSupported = false
                    println("$TAG: Raw depth supported: false, falling back to smoothed depth")
                }
            }
        }

        // Fallback: smoothed depth with no confidence image (treat as full confidence)
        return try {
            val depth = frame.acquireDepthImage()
            depth to null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Infer white walls from per-column depth miss patterns.
     * Replaces Strategies 5 & 6 from the old hit-test approach with
     * denser per-column data from the depth image.
     */
    private fun inferWhiteWallsFromDepthPattern(
        cols: Int,
        colMisses: IntArray, colWallHits: IntArray, colFloorHits: IntArray,
        colFloorWx: FloatArray, colFloorWz: FloatArray,
        fnx: Float, fnz: Float,
        camWx: Float, camWz: Float
    ) {
        var totalMisses = 0
        var totalPoints = 0

        for (col in 0 until cols) {
            val misses = colMisses[col]
            val walls = colWallHits[col]
            val floors = colFloorHits[col]
            totalMisses += misses
            totalPoints += misses + walls + floors

            // Strategy 5: Column has floor hits but high misses and no wall hits
            // → featureless white wall above the floor
            if (floors >= 1 && walls == 0 && misses >= 3) {
                val floorX = colFloorWx[col]
                val floorZ = colFloorWz[col]
                for (step in 1..4) {
                    val wallX = floorX + fnx * (0.15f * step)
                    val wallZ = floorZ + fnz * (0.15f * step)
                    mapBuilder.markWhiteWall(wallX, wallZ)
                }
            }
        }

        // Strategy 6: Very high overall miss ratio → staring directly at white wall
        val missRatio = if (totalPoints > 0) totalMisses.toFloat() / totalPoints else 0f
        if (missRatio > 0.50f && (totalPoints - totalMisses) < 15) {
            val distances = floatArrayOf(0.6f, 1.0f, 1.5f, 2.0f, 2.5f)
            val angles = floatArrayOf(-0.4f, -0.2f, 0f, 0.2f, 0.4f)
            for (dist in distances) {
                for (angleOffset in angles) {
                    val cos = kotlin.math.cos(angleOffset)
                    val sin = kotlin.math.sin(angleOffset)
                    val rotFwdX = fnx * cos - fnz * sin
                    val rotFwdZ = fnx * sin + fnz * cos
                    val wallX = camWx + rotFwdX * dist
                    val wallZ = camWz + rotFwdZ * dist
                    mapBuilder.markWhiteWall(wallX, wallZ)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 3: Motion-Based Wall Proximity Inference
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Analyze the occupancy grid around the user's recent path.
     * If one side of the path has a continuous line of CELL_UNKNOWN cells
     * (no floor or wall detected) while the other side has floor, those
     * unknown cells are likely a white wall.
     *
     * Runs every ~2s, lightweight (grid-level only, no ARCore API calls).
     */
    private fun inferWallsFromMotionContext() {
        val breadcrumbs = poseTracker.getBreadcrumbs()
        if (breadcrumbs.size < 10) return // need at least 2m of path (10 × 0.2m)

        // Use the last 15 breadcrumbs (~3m of path)
        val recent = breadcrumbs.takeLast(15)

        // Compute average heading direction from path
        val dx = recent.last().x - recent.first().x
        val dz = recent.last().z - recent.first().z
        val pathLen = sqrt(dx * dx + dz * dz)
        if (pathLen < 1.0f) return // user hasn't moved enough

        val pathNx = dx / pathLen; val pathNz = dz / pathLen
        // Perpendicular (left normal): rotate 90° CCW
        val perpX = pathNz; val perpZ = -pathNx

        // Check grid cells perpendicular to the path, on both sides
        val grid = mapBuilder.grid
        var leftUnknown = 0; var leftKnown = 0
        var rightUnknown = 0; var rightKnown = 0
        val inferredCells = mutableListOf<Pair<Float, Float>>()

        for (pt in recent) {
            val gx = mapBuilder.worldToGrid(pt.x)
            val gz = mapBuilder.worldToGrid(pt.z)

            // Check 2-4 cells to the left and right of path
            for (dist in 2..4) {
                // Left side
                val lx = gx + (perpX * dist).roundToInt()
                val lz = gz + (perpZ * dist).roundToInt()
                val lCell = grid[GridCell(lx, lz)]?.toInt()
                if (lCell == null || lCell == MapBuilder.CELL_UNKNOWN) {
                    leftUnknown++
                    if (dist == 2) inferredCells.add(
                        mapBuilder.gridToWorld(lx).toFloat() to mapBuilder.gridToWorld(lz).toFloat())
                } else leftKnown++

                // Right side
                val rx = gx + (-perpX * dist).roundToInt()
                val rz = gz + (-perpZ * dist).roundToInt()
                val rCell = grid[GridCell(rx, rz)]?.toInt()
                if (rCell == null || rCell == MapBuilder.CELL_UNKNOWN) {
                    rightUnknown++
                    if (dist == 2) inferredCells.add(
                        mapBuilder.gridToWorld(rx).toFloat() to mapBuilder.gridToWorld(rz).toFloat())
                } else rightKnown++
            }
        }

        // If one side is >70% unknown while the other is >50% known,
        // the unknown side likely has a white wall.
        val leftTotal = leftUnknown + leftKnown
        val rightTotal = rightUnknown + rightKnown
        if (leftTotal < 10 || rightTotal < 10) return

        val leftUnkRatio = leftUnknown.toFloat() / leftTotal
        val rightUnkRatio = rightUnknown.toFloat() / rightTotal
        val leftKnownRatio = leftKnown.toFloat() / leftTotal
        val rightKnownRatio = rightKnown.toFloat() / rightTotal

        // Lowered thresholds: >50% unknown (was 70%) and >30% known (was 50%)
        // This makes wall inference more aggressive for white walls
        if (leftUnkRatio > 0.50f && rightKnownRatio > 0.30f) {
            // Left side is likely a white wall
            for ((wx, wz) in inferredCells.take(inferredCells.size / 2)) {
                mapBuilder.markWhiteWall(wx, wz)  // Use stronger evidence
            }
        }
        if (rightUnkRatio > 0.50f && leftKnownRatio > 0.30f) {
            // Right side is likely a white wall
            for ((wx, wz) in inferredCells.drop(inferredCells.size / 2)) {
                mapBuilder.markWhiteWall(wx, wz)  // Use stronger evidence
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

        // Draw inferred walls (white/featureless walls detected by miss patterns)
        val inferredWalls = mapBuilder.getRecentInferredWalls()
        if (inferredWalls.isNotEmpty()) {
            meshRenderer.drawInferredWalls(
                inferredWalls,
                camera.pose.ty(),
                mapBuilder.res,
                projMatrix,
                viewMatrix
            )
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
        val gridSize = mapBuilder.grid.size
        val text = buildString {
            if (camera.trackingState != TrackingState.TRACKING) {
                appendLine("⚠ TRACKING LOST ⚠")
            }
            appendLine("AR: ${camera.trackingState}")
            appendLine("Pos x=${s.currentPosition.x.f2} z=${s.currentPosition.z.f2}")
            appendLine("Cells total=$gridSize")
            appendLine("Objects confirmed=${sem.totalObjects}")
            appendLine("KFs=${observationStore.size()} drift=${"%.3f".format(cachedDrift)}m")
            appendLine("Compass ${compassView.bearingDegrees.roundToInt()}°")
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

    // Cached grid stats — avoid iterating entire grid every 300ms
    private var cachedGridFree = 0; private var cachedGridWalls = 0
    private var cachedGridObs = 0; private var cachedGridVisited = 0
    private var cachedGridTotal = 0; private var lastGridStatsMs = 0L

    private fun sendToFlutter() {
        if (destroyed) return
        val ch  = methodChannel ?: return
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()

        // Record performance metrics — grid stats only every 2s (expensive iteration)
        PerformanceTracker.recordDrift(cachedDrift)
        PerformanceTracker.recordObjectCount(sem.totalObjects)
        val now = System.currentTimeMillis()
        if (now - lastGridStatsMs >= 2000L) {
            lastGridStatsMs = now
            PerformanceTracker.recordMemoryUsage()
            val localGrid = mapBuilder.grid
            var free = 0; var walls = 0; var obs = 0; var visited = 0
            for ((_, v) in localGrid) {
                when (v.toInt()) { 1 -> free++; 3 -> walls++; 2 -> obs++; 4 -> visited++ }
            }
            cachedGridFree = free; cachedGridWalls = walls
            cachedGridObs = obs; cachedGridVisited = visited
            cachedGridTotal = localGrid.size
        }
        PerformanceTracker.recordGridStats(cachedGridTotal, cachedGridFree, cachedGridWalls, cachedGridObs, cachedGridVisited)
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
                "compass_bearing" to compassView.bearingDegrees.toDouble(),
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
            if (now - lastMapMs >= 1000L) {
                lastMapMs = now
                ch.invokeMethod("updateMap", buildMapPayload(s.currentPosition))
            }
            // Push live performance snapshot every 2s
            if (now - lastPerfPushMs >= 2000L) {
                lastPerfPushMs = now
                ch.invokeMethod("perfUpdate", PerformanceTracker.snapshot().toFlatMap())
            }
        }
    }

    private fun buildMapPayload(curPos: Point3D): Map<String, Any> {
        val localGrid = mapBuilder.grid
        if (localGrid.isEmpty()) return mapOf(
            "occupancyGrid" to ByteArray(0), "gridWidth" to 0, "gridHeight" to 0,
            "gridResolution" to RES.toDouble(), "objects" to emptyList<Any>()
        )

        // Use cached bounds from MapBuilder instead of iterating all keys
        val gMinX = mapBuilder.minGX; val gMaxX = mapBuilder.maxGX
        val gMinZ = mapBuilder.minGZ; val gMaxZ = mapBuilder.maxGZ
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