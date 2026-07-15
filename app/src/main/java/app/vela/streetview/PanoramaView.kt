package app.vela.streetview

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * An interactive equirectangular panorama viewer, GLES 2.0. Textures the pano onto the inside
 * of a sphere and lets the user drag to look around and pinch to zoom (field of view). This is
 * how open Street View viewers render - we draw the imagery ourselves rather than embed Google's
 * WebGL SPA (which serves a stripped shell to an Android WebView and composites to black).
 *
 * Set the stitched equirect via [setPanorama]; call [setInitialYaw] first to face a direction.
 */
class PanoramaView(context: Context) : GLSurfaceView(context) {
    private val renderer = PanoRenderer()
    private val scaleDetector: ScaleGestureDetector

    private var lastX = 0f
    private var lastY = 0f
    private var pointerId = -1

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY // static scene; redraw on interaction / texture load
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.zoomBy(d.scaleFactor)
                requestRender()
                return true
            }
        })
    }

    /** The stitched equirect (2:1). Uploaded on the GL thread; recycled after upload. */
    fun setPanorama(bitmap: Bitmap) {
        queueEvent { renderer.setTexture(bitmap); requestRender() }
    }

    /** Initial camera yaw in degrees (the pano's own heading), so it faces down the street. */
    fun setInitialYaw(deg: Float) { renderer.setYaw(Math.toRadians(deg.toDouble()).toFloat()) }

    /** Live camera yaw / vertical field-of-view in DEGREES - the arrow overlay reads these each
     *  frame to place the "walk this way" chevrons relative to where you're looking. */
    fun currentYawDeg(): Float = Math.toDegrees(renderer.yawRad().toDouble()).toFloat()
    fun currentFovDeg(): Float = renderer.fovDeg()

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; pointerId = e.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && pointerId != -1) {
                val i = e.findPointerIndex(pointerId)
                if (i != -1) {
                    val dx = e.getX(i) - lastX
                    val dy = e.getY(i) - lastY
                    lastX = e.getX(i); lastY = e.getY(i)
                    // Drag scales by the current FOV over the view size, so a full-screen swipe
                    // moves ~one field of view - a natural "grab the world" feel at any zoom.
                    renderer.dragBy(dx, dy, width, height)
                    requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // If the tracked finger lifted, re-anchor to a remaining one.
                if (e.getPointerId(e.actionIndex) == pointerId) {
                    val other = if (e.actionIndex == 0) 1 else 0
                    if (other < e.pointerCount) {
                        pointerId = e.getPointerId(other); lastX = e.getX(other); lastY = e.getY(other)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerId = -1
        }
        return true
    }

    /** The GLES 2.0 renderer: one textured sphere, camera at the centre. */
    private class PanoRenderer : Renderer {
        // Camera state (radians / degrees), touched from the UI thread but read on the GL thread;
        // volatile is enough for these scalars (a slightly stale frame is invisible).
        @Volatile private var yaw = 0f
        @Volatile private var pitch = 0f
        @Volatile private var fovY = 75f

        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uMvp = 0
        private var uTex = 0
        private var texId = 0
        private var pendingBitmap: Bitmap? = null

        private lateinit var vertices: FloatBuffer
        private lateinit var uvs: FloatBuffer
        private lateinit var indices: ShortBuffer
        private var indexCount = 0

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private var aspect = 1f

        fun setYaw(r: Float) { yaw = r }
        fun yawRad(): Float = yaw
        fun fovDeg(): Float = fovY

        fun dragBy(dx: Float, dy: Float, w: Int, h: Int) {
            // 1.7x so a swipe covers more than one field of view - the 1:1 mapping felt sluggish
            // (user 2026-07-15). Still scales with FOV so zoomed-in panning stays proportional.
            val perPx = Math.toRadians(fovY.toDouble()).toFloat() / max(1, h) * DRAG_SENSITIVITY
            yaw -= dx * perPx      // drag right -> world rotates left (grab-and-pull)
            pitch += dy * perPx    // drag down -> look down
            val lim = Math.toRadians(85.0).toFloat()
            pitch = pitch.coerceIn(-lim, lim)
        }

        fun zoomBy(scale: Float) { fovY = (fovY / scale).coerceIn(30f, 100f) }

        fun setTexture(bmp: Bitmap) { pendingBitmap = bmp }

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, cfg: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glDisable(GLES20.GL_CULL_FACE) // we view the sphere from inside
            buildSphere(48, 32)
            program = link(VERT, FRAG)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            aspect = w.toFloat() / max(1, h)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            pendingBitmap?.let { uploadTexture(it); pendingBitmap = null }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (texId == 0) return

            Matrix.perspectiveM(proj, 0, fovY, aspect, 0.05f, 20f)
            // Look direction from yaw/pitch (yaw=0 faces -Z). Camera at the origin.
            val cx = (cos(pitch) * sin(yaw))
            val cy = sin(pitch)
            val cz = (-cos(pitch) * cos(yaw))
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, cx, cy, cz, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(uTex, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun uploadTexture(bmp: Bitmap) {
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // Horizontal wrap so the 360° seam is continuous (the image is POT); clamp vertically.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // texImage2D COPIES the pixels into GL texture memory, so the bitmap is free to be
            // recycled by its owner (the VM) after this - we don't recycle it here, or the state's
            // reference would double-free / a recompose could re-upload a recycled bitmap.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }

        private fun buildSphere(slices: Int, stacks: Int) {
            val pos = ArrayList<Float>()
            val uv = ArrayList<Float>()
            for (i in 0..stacks) {
                val v = i.toFloat() / stacks
                val phi = Math.PI * v // colatitude 0..PI
                for (j in 0..slices) {
                    val u = j.toFloat() / slices
                    val theta = 2.0 * Math.PI * u
                    val x = (sin(phi) * cos(theta)).toFloat()
                    val y = cos(phi).toFloat()
                    val z = (sin(phi) * sin(theta)).toFloat()
                    pos.add(x); pos.add(y); pos.add(z)
                    // Flip U so the equirect reads correctly (not mirrored) when the sphere is
                    // viewed from the inside - the standard inside-panorama fix. (device-verified
                    // 2026-07-15: negating X instead left the signage + Google watermark mirrored.)
                    uv.add(1f - u); uv.add(v)
                }
            }
            val idx = ArrayList<Short>()
            val cols = slices + 1
            for (i in 0 until stacks) for (j in 0 until slices) {
                val a = (i * cols + j).toShort()
                val b = ((i + 1) * cols + j).toShort()
                val c = (i * cols + j + 1).toShort()
                val d = ((i + 1) * cols + j + 1).toShort()
                idx.add(a); idx.add(b); idx.add(c)
                idx.add(c); idx.add(b); idx.add(d)
            }
            vertices = floatBuf(pos.toFloatArray())
            uvs = floatBuf(uv.toFloatArray())
            indices = shortBuf(idx.toShortArray())
            indexCount = idx.size
        }

        private fun floatBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun shortBuf(a: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

        private fun link(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            return s
        }

        companion object {
            private const val DRAG_SENSITIVITY = 1.7f
            private const val VERT =
                "uniform mat4 uMvp; attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                    "void main(){ vUv = aUv; gl_Position = uMvp * aPos; }"
            private const val FRAG =
                "precision mediump float; uniform sampler2D uTex; varying vec2 vUv;" +
                    "void main(){ gl_FragColor = texture2D(uTex, vUv); }"
        }
    }
}
