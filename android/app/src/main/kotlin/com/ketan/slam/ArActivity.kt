package com.ketan.slam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val CHANNEL  = "com.ketan.slam/ar"
        private const val TAG      = "SLAM"
        private const val CAM_PERM = 1001

        // Grid resolution — 20 cm per cell gives good spatial detail
        private const val RES = 0.20f

        // Cell type constants sent to Flutter
        private const val CELL_UNKNOWN  = 0
        private const val CELL_FREE     = 1
        private const val CELL_OBSTACLE = 2   // detected objects / plane boundaries
        private const val CELL_WALL     = 3   // vertical plane projections
        private const val CELL_VISITED  = 4   // camera has been here

        // Shared camera capture size (landscape — what the sensor delivers natively)
        private const val CAM_W = 640
        private const val CAM_H = 480

        private const val MERGE_DIST = 1.2f       // metres, same-label merge radius
        private const val STALE_MS   = 30_000L    // remove unseen objects after 30 s
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var hudView: TextView
    private lateinit var overlayView: DetectionOverlayView

    // ── ARCore ────────────────────────────────────────────────────────────────
    private var session: Session? = null
    private var installRequested = false
    private var displayRotationHelper: DisplayRotationHelper? = null
    private lateinit var backgroundRenderer: BackgroundRenderer

    // Surface dimensions — needed to map bbox pixel coords to GL viewport for hit-test
    @Volatile private var surfaceWidth  = CAM_W
    @Volatile private var surfaceHeight = CAM_H

    // ── Shared camera ─────────────────────────────────────────────────────────
    private var yoloReader: ImageReader? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    // ── SLAM ──────────────────────────────────────────────────────────────────
    private lateinit var slamEngine: SlamEngine
    private lateinit var semanticMap: SemanticMapManager

    // ── Occupancy grid ────────────────────────────────────────────────────────
    // Thread-safe cell map: GridCell → cell type byte
    private val grid = ConcurrentHashMap<GridCell, Byte>()
    private var minGX = 0; private var maxGX = 0
    private var minGZ = 0; private var maxGZ = 0

    // ── Detection ─────────────────────────────────────────────────────────────
    private lateinit var yoloDetector: YoloDetector
    private val confirmationGate = DetectionConfirmationGate(requiredHits = 3, windowMs = 5_000L)
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private val detecting = AtomicBoolean(false)
    private var lastDetectMs = 0L
    private val detectInterval = 900L
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var latestPose: com.google.ar.core.Pose? = null
    // FIX 1: also store the latest ARCore frame for hit-testing in estimate3D
    @Volatile private var latestFrame: Frame? = null
    @Volatile private var latestHeading = 0f  // radians, for Flutter robot marker

    // ── Flutter ───────────────────────────────────────────────────────────────
    private var methodChannel: MethodChannel? = null
    private var lastHudMs = 0L; private var lastFlutterMs = 0L; private var lastMapMs = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        FlutterEngineCache.getInstance().get("slam_engine")?.let {
            methodChannel = MethodChannel(it.dartExecutor.binaryMessenger, CHANNEL)
        }

        displayRotationHelper = DisplayRotationHelper(this)
        buildLayout()

        slamEngine  = SlamEngine()
        semanticMap = SemanticMapManager()

        try { yoloDetector = YoloDetector(this); println("$TAG: YOLO ready") }
        catch (e: Exception) { println("$TAG: YOLO init failed: ${e.message}") }

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
    }

    override fun onDestroy() {
        super.onDestroy()
        try { yoloDetector.close() } catch (_: Exception) {}
        detectionExecutor.shutdownNow()
        teardownCamera()
        session?.close(); session = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLayout() {
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
            setBackgroundColor(0xCC000000.toInt())
            setPadding(20, 14, 20, 14)
            text = "Initializing…"
        }
        val root = FrameLayout(this)
        root.addView(surfaceView, mpmp())
        root.addView(overlayView, mpmp())
        root.addView(hudView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(24, 48, 24, 0) })
        setContentView(root)
    }

    private fun mpmp() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasCamPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun reqCamPerm() = ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA), CAM_PERM)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, p, results)
        if (rc != CAM_PERM) return
        if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show(); finish(); return
        }
        if (!ensureSession()) return
        try { session?.resume(); surfaceView.onResume(); displayRotationHelper?.onResume() }
        catch (e: Exception) { println("$TAG: resume after perm: ${e.message}") }
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
                    // FIX 1: capture the current frame reference before the executor lambda
                    val currentFrame = latestFrame
                    img.close()

                    detectionExecutor.execute {
                        val t0 = System.currentTimeMillis()
                        try {
                            val raw = yoloDetector.detectFromYuv(yB, uB, vB, yStride, uvStride, uvPix, CAM_W, CAM_H)
                            // Feed through confirmation gate — only 3x-confirmed detections proceed
                            val confirmed = confirmationGate.feed(raw)
                            lastInferenceMs = System.currentTimeMillis() - t0

                            overlayView.updateDetections(raw, CAM_W, CAM_H)  // show raw for HUD

                            if (confirmed.isNotEmpty() && pose != null) {
                                println("$TAG: confirmed ${confirmed.size}: " +
                                        confirmed.joinToString { "${it.label}@${"%.2f".format(it.confidence)}" })
                                confirmed.forEach { det ->
                                    // FIX 1: pass currentFrame for ARCore hit-test based placement
                                    val wp = estimate3D(
                                        det.boundingBox.toRect(), pose, CAM_W, CAM_H,
                                        frame = currentFrame
                                    ) ?: return@forEach
                                    mergeOrAdd(det, wp)
                                    // FIX 2: pass object type for correct footprint size
                                    markObstacleFootprint(wp, det.label)
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
        println("$TAG: GL surface created, textureId=${backgroundRenderer.textureId}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // FIX 1: track surface size so estimate3D can map bbox coords correctly
        surfaceWidth  = width
        surfaceHeight = height
        displayRotationHelper?.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!::backgroundRenderer.isInitialized) return
        val sess = session ?: return
        try {
            sess.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper?.updateSessionIfNeeded(sess)
            val frame  = sess.update()
            // FIX 1: store frame so ImageReader lambda can use it for hit-testing
            latestFrame = frame
            backgroundRenderer.draw(frame)
            val camera = frame.camera
            updateHud(camera)
            if (camera.trackingState == TrackingState.TRACKING) {
                updateSlam(frame, camera)
                val now = System.currentTimeMillis()
                if (now - lastFlutterMs >= 300L) { lastFlutterMs = now; sendToFlutter() }
            }
        } catch (e: Exception) { println("$TAG: draw: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SLAM — called every GL frame
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateSlam(frame: Frame, camera: Camera) {
        try {
            val pose = camera.pose
            latestPose = pose

            val cx = pose.tx(); val cz = pose.tz()
            slamEngine.addPose(Point3D(cx, pose.ty(), cz))

            // --- Mark camera cell and a 0.5m radius as visited/free ---
            val gx = worldToGrid(cx); val gz = worldToGrid(cz)
            markFree(gx, gz, CELL_VISITED)
            for (dz in -2..2) for (dx in -2..2) {
                if (dx * dx + dz * dz <= 4) markFreeIfUnknown(gx + dx, gz + dz)
            }

            // --- Ray-cast forward to mark clear corridor ---
            val q  = pose.rotationQuaternion
            val fwdX = 2f * (q[0] * q[2] + q[1] * q[3])
            val fwdZ = 1f - 2f * (q[0] * q[0] + q[1] * q[1])
            rayCastFree(cx, cz, fwdX, fwdZ, maxDist = 4.0f)

            // --- Integrate ARCore planes ---
            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                if (plane.trackingState != TrackingState.TRACKING) return@forEach
                val poly = plane.polygon ?: return@forEach
                if (!poly.hasRemaining()) return@forEach

                if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                    rasterisePlaneAsFree(plane, poly)
                }
                if (plane.type == Plane.Type.VERTICAL) {
                    rasterisePlaneAsWall(plane, poly)
                }

                slamEngine.addEdges(poly)
            }

            semanticMap.removeStaleObjects()
        } catch (e: Exception) { println("$TAG: slam: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Occupancy grid helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun worldToGrid(v: Float) = (v / RES).roundToInt()
    private fun gridToWorld(g: Int)   = g * RES

    private fun setCell(gx: Int, gz: Int, type: Int) {
        grid[GridCell(gx, gz)] = type.toByte()
        if (gx < minGX) minGX = gx; if (gx > maxGX) maxGX = gx
        if (gz < minGZ) minGZ = gz; if (gz > maxGZ) maxGZ = gz
    }

    private fun markFree(gx: Int, gz: Int, type: Int = CELL_FREE) = setCell(gx, gz, type)
    private fun markFreeIfUnknown(gx: Int, gz: Int) {
        if (!grid.containsKey(GridCell(gx, gz))) setCell(gx, gz, CELL_FREE)
    }

    private fun rayCastFree(originX: Float, originZ: Float, dirX: Float, dirZ: Float, maxDist: Float) {
        val len = sqrt(dirX * dirX + dirZ * dirZ).coerceAtLeast(0.001f)
        val nx = dirX / len; val nz = dirZ / len
        var d = 0f
        while (d < maxDist) {
            val wx = originX + nx * d; val wz = originZ + nz * d
            val gx = worldToGrid(wx); val gz = worldToGrid(wz)
            val existing = grid[GridCell(gx, gz)]?.toInt() ?: CELL_UNKNOWN
            if (existing == CELL_OBSTACLE || existing == CELL_WALL) break
            markFreeIfUnknown(gx, gz)
            d += RES
        }
    }

    private fun rasterisePlaneAsFree(plane: Plane, poly: java.nio.FloatBuffer) {
        val verts = mutableListOf<Pair<Float, Float>>()
        val planePose = plane.centerPose
        poly.rewind()
        while (poly.remaining() >= 2) {
            val lx = poly.get(); val lz = poly.get()
            val world = planePose.transformPoint(floatArrayOf(lx, 0f, lz))
            verts += Pair(world[0], world[2])
        }
        if (verts.size < 3) return

        val minX = verts.minOf { it.first }; val maxX = verts.maxOf { it.first }
        val minZ = verts.minOf { it.second }; val maxZ = verts.maxOf { it.second }

        var wx = minX
        while (wx <= maxX) {
            var wz = minZ
            while (wz <= maxZ) {
                if (pointInPolygon(wx, wz, verts)) markFreeIfUnknown(worldToGrid(wx), worldToGrid(wz))
                wz += RES
            }
            wx += RES
        }
    }

    private fun rasterisePlaneAsWall(plane: Plane, poly: java.nio.FloatBuffer) {
        val planePose = plane.centerPose
        poly.rewind()
        val pts = mutableListOf<Pair<Float, Float>>()
        while (poly.remaining() >= 2) {
            val lx = poly.get(); val lz = poly.get()
            val world = planePose.transformPoint(floatArrayOf(lx, 0f, lz))
            pts += Pair(world[0], world[2])
        }
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            bresenhamLine(worldToGrid(a.first), worldToGrid(a.second),
                worldToGrid(b.first), worldToGrid(b.second)) { gx, gz ->
                setCell(gx, gz, CELL_WALL)
            }
        }
    }

    // FIX 2: per-type obstacle footprint sizes instead of a fixed 0.4 m × 0.4 m box
    private fun markObstacleFootprint(wp: Point3D, objectType: String) {
        val halfM: Float = when (objectType.uppercase()) {
            "DOOR"              -> 0.45f   // ~0.9 m door leaf
            "WINDOW"            -> 0.50f
            "NOTICE_BOARD"      -> 0.35f
            "FIRE_EXTINGUISHER" -> 0.20f
            "LIFT_GATE"         -> 0.60f
            "WATER_PURIFIER"    -> 0.25f
            "TRASH_CAN"         -> 0.20f
            "CHAIR"             -> 0.25f
            else                -> 0.30f
        }
        val halfCells = (halfM / RES).roundToInt().coerceAtLeast(1)
        val ogx = worldToGrid(wp.x); val ogz = worldToGrid(wp.z)
        for (dz in -halfCells..halfCells) for (dx in -halfCells..halfCells)
            setCell(ogx + dx, ogz + dz, CELL_OBSTACLE)
    }

    // Winding number point-in-polygon
    private fun pointInPolygon(px: Float, pz: Float, verts: List<Pair<Float, Float>>): Boolean {
        var winding = 0
        val n = verts.size
        for (i in 0 until n) {
            val ax = verts[i].first; val az = verts[i].second
            val bx = verts[(i+1)%n].first; val bz = verts[(i+1)%n].second
            if (az <= pz) { if (bz > pz && crossZ(ax, az, bx, bz, px, pz) > 0) winding++ }
            else          { if (bz <= pz && crossZ(ax, az, bx, bz, px, pz) < 0) winding-- }
        }
        return winding != 0
    }

    private fun crossZ(ax: Float, az: Float, bx: Float, bz: Float, px: Float, pz: Float) =
        (bx - ax) * (pz - az) - (bz - az) * (px - ax)

    private fun bresenhamLine(x0: Int, z0: Int, x1: Int, z1: Int, draw: (Int, Int) -> Unit) {
        var x = x0; var z = z0
        val dx = abs(x1 - x0); val dz = abs(z1 - z0)
        val sx = if (x0 < x1) 1 else -1; val sz = if (z0 < z1) 1 else -1
        var err = dx - dz
        while (true) {
            draw(x, z)
            if (x == x1 && z == z1) break
            val e2 = 2 * err
            if (e2 > -dz) { err -= dz; x += sx }
            if (e2 < dx)  { err += dx; z += sz }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3-D position estimation
    // FIX 1: try ARCore hit-test first; fall back to area-based depth estimate
    // ─────────────────────────────────────────────────────────────────────────

    private fun estimate3D(
        bbox: android.graphics.Rect,
        cameraPose: com.google.ar.core.Pose,
        imgW: Int, imgH: Int,
        frame: Frame? = null
    ): Point3D? {
        return try {
            // Step 1: ARCore hit-test at bbox centre mapped to GL viewport coordinates
            if (frame != null) {
                val normX = bbox.exactCenterX() / imgW.toFloat()
                val normY = bbox.exactCenterY() / imgH.toFloat()
                val screenX = normX * surfaceWidth
                val screenY = normY * surfaceHeight
                val hits = frame.hitTest(screenX, screenY)
                val best = hits.firstOrNull { h ->
                    val t = h.trackable
                    (t is Plane && t.isPoseInPolygon(h.hitPose)) ||
                            t is com.google.ar.core.DepthPoint
                }
                if (best != null) {
                    val hp = best.hitPose
                    return Point3D(hp.tx(), hp.ty(), hp.tz())
                }
            }

            // Step 2: area-based depth fallback
            val area  = (bbox.width() * bbox.height()).toFloat() / (imgW * imgH)
            val depth = when {
                area > 0.30f -> 1.0f
                area > 0.10f -> 2.0f
                area > 0.03f -> 3.5f
                else         -> 5.0f
            }
            val q = cameraPose.rotationQuaternion
            val fx = 2f * (q[0] * q[2] + q[1] * q[3])
            val fy = 2f * (q[1] * q[2] - q[0] * q[3])
            val fz = 1f - 2f * (q[0] * q[0] + q[1] * q[1])
            Point3D(
                cameraPose.tx() + fx * depth,
                cameraPose.ty() + fy * depth,
                cameraPose.tz() - fz * depth
            )
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic object merge / add
    // FIX 3: use worldToGrid (RES = 0.2 m snap) for object id instead of 0.5 m
    // ─────────────────────────────────────────────────────────────────────────

    private fun mergeOrAdd(det: YoloDetector.Detection, wp: Point3D) {
        val existing = semanticMap.getAllObjects().firstOrNull { o ->
            o.category == det.label && o.position.distance(wp) < MERGE_DIST
        }
        if (existing != null) {
            val a = 0.2f
            semanticMap.updateObject(existing.copy(
                position    = Point3D(existing.position.x*(1-a)+wp.x*a,
                    existing.position.y*(1-a)+wp.y*a,
                    existing.position.z*(1-a)+wp.z*a),
                confidence  = maxOf(existing.confidence, det.confidence),
                lastSeen    = System.currentTimeMillis(),
                observations = existing.observations + 1
            ))
        } else {
            // FIX 3: was (wp.x / 0.5f).toInt() — coarser than RES, caused id collisions
            val gx = worldToGrid(wp.x); val gz = worldToGrid(wp.z)
            semanticMap.addObject(SemanticObject(
                id          = "${det.label}_${gx}_${gz}",
                type        = ObjectType.fromLabel(det.label),
                category    = det.label,
                position    = wp,
                boundingBox = BoundingBox2D(det.boundingBox.left, det.boundingBox.top,
                    det.boundingBox.right, det.boundingBox.bottom),
                confidence  = det.confidence,
                firstSeen   = System.currentTimeMillis(),
                lastSeen    = System.currentTimeMillis()
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HUD
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateHud(camera: Camera) {
        val now = System.currentTimeMillis()
        if (now - lastHudMs < 300L) return; lastHudMs = now
        val s   = slamEngine.getStatistics()
        val sem = semanticMap.getStatistics()
        val text = buildString {
            appendLine("AR: ${camera.trackingState}")
            appendLine("Pos x=${s.currentPosition.x.f2} z=${s.currentPosition.z.f2}")
            appendLine("Cells free=${grid.count { it.value.toInt() == CELL_FREE || it.value.toInt() == CELL_VISITED }} " +
                    "obstacle=${grid.count { it.value.toInt() == CELL_OBSTACLE }}")
            appendLine("Objects confirmed=${sem.totalObjects}")
            append("Inference ${lastInferenceMs}ms")
        }
        runOnUiThread { hudView.text = text }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flutter bridge
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendToFlutter() {
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
        val localGrid = HashMap(grid)
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
            mapOf(
                "id"       to obj.id,       "type"       to obj.type.name,
                "label"    to obj.category, "confidence" to obj.confidence,
                "x"        to obj.position.x, "y" to obj.position.y, "z" to obj.position.z,
                "gridX"    to (worldToGrid(obj.position.x) - gMinX),
                "gridZ"    to (worldToGrid(obj.position.z) - gMinZ),
                "observations" to obj.observations
            )
        }

        return mapOf(
            "occupancyGrid"  to bytes,
            "gridWidth"      to w,      "gridHeight"     to h,
            "gridResolution" to RES.toDouble(),
            "originX"        to gMinX,  "originZ"        to gMinZ,
            "robotGridX"     to (worldToGrid(curPos.x) - gMinX),
            "robotGridZ"     to (worldToGrid(curPos.z) - gMinZ),
            "objects"        to objects
        )
    }
}

private val Float.f2 get() = "%.2f".format(this)