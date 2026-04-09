package com.ketan.slam

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Renders semi-transparent colored polygon meshes over ARCore planes and
 * YOLO-detected object footprints.
 *
 * Color scheme:
 *   Floor   → light blue  (0.0, 0.6, 1.0, 0.25)
 *   Wall    → light red   (1.0, 0.3, 0.3, 0.30)
 *   Obstacle→ orange      (1.0, 0.6, 0.0, 0.35)
 *   Object  → yellow      (1.0, 1.0, 0.0, 0.30)
 *   Ceiling → ignored (not drawn)
 *
 * Uses triangle-fan for plane polygons, two triangles for object quads.
 * Vertex buffers are reused across frames and only rebuilt when the
 * plane's vertex count changes.
 */
class MeshRenderer {

    companion object {
        private const val MIN_PLANE_AREA = 0.15f // m² — skip noise patches

        // Max vertices we'll ever need per plane (ARCore planes rarely exceed 100 verts)
        private const val MAX_VERTS = 128

        private const val VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }

    enum class SurfaceType(val r: Float, val g: Float, val b: Float, val a: Float) {
        FLOOR   (0.0f, 0.6f, 1.0f, 0.25f),
        WALL    (1.0f, 0.3f, 0.3f, 0.30f),
        OBSTACLE(1.0f, 0.6f, 0.0f, 0.35f),
        OBJECT  (1.0f, 1.0f, 0.0f, 0.30f),
        CEILING (0f, 0f, 0f, 0f);  // not drawn
    }

    private var program = 0
    private var aPosition = -1
    private var uMvp = -1
    private var uColor = -1

    // Reusable vertex buffer — large enough for any single plane
    // 3 floats per vertex × MAX_VERTS (+1 for fan center)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect((MAX_VERTS + 1) * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Small quad buffer for object footprints (6 verts × 3 floats = 18 floats)
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(6 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // MVP scratch matrices
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Cache: plane hashCode → last vertex count (avoid rebuilding unchanged planes)
    private val planeVertCache = HashMap<Int, Int>()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun createOnGlThread() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            throw RuntimeException("MeshRenderer link failed: $log")
        }

        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        uMvp      = GLES20.glGetUniformLocation(program, "u_MVP")
        uColor    = GLES20.glGetUniformLocation(program, "u_Color")
    }

    // ── Plane rendering ───────────────────────────────────────────────────────

    /**
     * Draw a single ARCore Plane as a semi-transparent triangle fan.
     * The plane polygon is transformed from plane-local to world space
     * using the plane's center pose.
     */
    fun drawPlane(
        plane: Plane,
        type: SurfaceType,
        projMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (program == 0 || type == SurfaceType.CEILING) return

        val polygon = plane.polygon ?: return
        val vertCount = polygon.remaining() / 2
        if (vertCount < 3 || vertCount > MAX_VERTS) return

        // Area check — skip tiny noise planes
        if (planeArea(polygon, vertCount) < MIN_PLANE_AREA) return

        // Build world-space vertices: center + boundary in triangle fan order
        val planePose = plane.centerPose
        vertexBuffer.clear()

        // Fan center = plane center in world space
        vertexBuffer.put(planePose.tx())
        vertexBuffer.put(planePose.ty())
        vertexBuffer.put(planePose.tz())

        // Boundary vertices: transform from plane-local (X,Z) to world space
        polygon.rewind()
        for (i in 0 until vertCount) {
            val lx = polygon.get()
            val lz = polygon.get()
            val world = planePose.transformPoint(floatArrayOf(lx, 0f, lz))
            vertexBuffer.put(world[0])
            vertexBuffer.put(world[1])
            vertexBuffer.put(world[2])
        }

        // Close the fan: repeat first boundary vertex
        polygon.rewind()
        val firstX = polygon.get()
        val firstZ = polygon.get()
        val firstWorld = planePose.transformPoint(floatArrayOf(firstX, 0f, firstZ))
        vertexBuffer.put(firstWorld[0])
        vertexBuffer.put(firstWorld[1])
        vertexBuffer.put(firstWorld[2])

        vertexBuffer.flip()

        // MVP = proj × view (model is identity — vertices already in world space)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        drawMesh(vertexBuffer, vertCount + 2, GLES20.GL_TRIANGLE_FAN, type)
    }

    // ── Object footprint rendering ────────────────────────────────────────────

    /**
     * Draw a ground-plane quad under a detected semantic object.
     * @param position  world-space position (x, y, z)
     * @param halfSize  half-extent in metres
     * @param floorY    approximate floor Y (camera Y − ~1.5m)
     */
    fun drawObjectFootprint(
        position: Point3D,
        halfSize: Float,
        floorY: Float,
        projMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (program == 0) return

        val cx = position.x
        val cz = position.z
        val y = floorY + 0.01f  // slightly above floor to avoid z-fight

        // Two triangles forming a quad
        quadBuffer.clear()
        // Triangle 1
        quadBuffer.put(cx - halfSize); quadBuffer.put(y); quadBuffer.put(cz - halfSize)
        quadBuffer.put(cx + halfSize); quadBuffer.put(y); quadBuffer.put(cz - halfSize)
        quadBuffer.put(cx + halfSize); quadBuffer.put(y); quadBuffer.put(cz + halfSize)
        // Triangle 2
        quadBuffer.put(cx - halfSize); quadBuffer.put(y); quadBuffer.put(cz - halfSize)
        quadBuffer.put(cx + halfSize); quadBuffer.put(y); quadBuffer.put(cz + halfSize)
        quadBuffer.put(cx - halfSize); quadBuffer.put(y); quadBuffer.put(cz + halfSize)
        quadBuffer.flip()

        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        drawMesh(quadBuffer, 6, GLES20.GL_TRIANGLES, SurfaceType.OBJECT)
    }

    /**
     * Draw vertical wall indicators for inferred wall cells.
     * Creates small vertical quads at each wall cell position to visualize
     * detected white/featureless walls that ARCore doesn't track as planes.
     * Draws an X-shaped cross of two quads so it's visible from any angle.
     * 
     * @param wallCells list of (worldX, worldZ) positions where walls were inferred
     * @param cameraY   camera height (for calculating wall quad placement)
     * @param cellSize  map cell resolution in metres
     */
    fun drawInferredWalls(
        wallCells: List<Pair<Float, Float>>,
        cameraY: Float,
        cellSize: Float,
        projMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (program == 0 || wallCells.isEmpty()) return
        
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        
        // Draw each wall cell as two perpendicular vertical quads (X-shape)
        val halfCell = cellSize * 0.7f  // Slightly larger for visibility
        val wallBottom = cameraY - 1.3f  // approximate floor
        val wallTop = cameraY + 0.5f     // slightly above camera
        
        for ((wx, wz) in wallCells.take(40)) {  // Limit for performance
            // Quad 1: aligned to X axis (faces Z direction)
            quadBuffer.clear()
            quadBuffer.put(wx - halfCell); quadBuffer.put(wallBottom); quadBuffer.put(wz)
            quadBuffer.put(wx + halfCell); quadBuffer.put(wallBottom); quadBuffer.put(wz)
            quadBuffer.put(wx + halfCell); quadBuffer.put(wallTop); quadBuffer.put(wz)
            quadBuffer.put(wx - halfCell); quadBuffer.put(wallBottom); quadBuffer.put(wz)
            quadBuffer.put(wx + halfCell); quadBuffer.put(wallTop); quadBuffer.put(wz)
            quadBuffer.put(wx - halfCell); quadBuffer.put(wallTop); quadBuffer.put(wz)
            quadBuffer.flip()
            drawMesh(quadBuffer, 6, GLES20.GL_TRIANGLES, SurfaceType.WALL)
            
            // Quad 2: aligned to Z axis (faces X direction)
            quadBuffer.clear()
            quadBuffer.put(wx); quadBuffer.put(wallBottom); quadBuffer.put(wz - halfCell)
            quadBuffer.put(wx); quadBuffer.put(wallBottom); quadBuffer.put(wz + halfCell)
            quadBuffer.put(wx); quadBuffer.put(wallTop); quadBuffer.put(wz + halfCell)
            quadBuffer.put(wx); quadBuffer.put(wallBottom); quadBuffer.put(wz - halfCell)
            quadBuffer.put(wx); quadBuffer.put(wallTop); quadBuffer.put(wz + halfCell)
            quadBuffer.put(wx); quadBuffer.put(wallTop); quadBuffer.put(wz - halfCell)
            quadBuffer.flip()
            drawMesh(quadBuffer, 6, GLES20.GL_TRIANGLES, SurfaceType.WALL)
        }
    }

    // ── Core draw ─────────────────────────────────────────────────────────────

    private fun drawMesh(
        buffer: FloatBuffer,
        vertCount: Int,
        mode: Int,
        type: SurfaceType
    ) {
        GLES20.glUseProgram(program)

        // Blending
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Depth: test on, write off (transparent overlays shouldn't occlude each other)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // Uniforms
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColor, type.r, type.g, type.b, type.a)

        // Vertex attrib
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        GLES20.glDrawArrays(mode, 0, vertCount)

        // Cleanup
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Classify a plane by type + height relative to camera. */
    fun classifyPlane(plane: Plane, cameraPose: Pose): SurfaceType {
        val relY = plane.centerPose.ty() - cameraPose.ty()
        return when {
            plane.type == Plane.Type.VERTICAL -> SurfaceType.WALL
            plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SurfaceType.CEILING
            relY < -0.8f -> SurfaceType.FLOOR
            else -> SurfaceType.OBSTACLE  // horizontal surface near camera height = table/bed
        }
    }

    /** Shoelace area of a plane polygon (in plane-local 2D coords). */
    private fun planeArea(polygon: java.nio.FloatBuffer, vertCount: Int): Float {
        polygon.rewind()
        var area = 0f
        val xs = FloatArray(vertCount)
        val zs = FloatArray(vertCount)
        for (i in 0 until vertCount) { xs[i] = polygon.get(); zs[i] = polygon.get() }
        for (i in 0 until vertCount) {
            val j = (i + 1) % vertCount
            area += xs[i] * zs[j] - xs[j] * zs[i]
        }
        polygon.rewind()
        return abs(area) * 0.5f
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("MeshRenderer shader compile failed: $log")
        }
        return shader
    }
}
