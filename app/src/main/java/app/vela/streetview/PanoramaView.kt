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
 * Set the stitched equirect via [setPanorama]; call [setCompass] first to face a direction.
 */
class PanoramaView(context: Context) : GLSurfaceView(context) {
    private val renderer = PanoRenderer()
    private val scaleDetector: ScaleGestureDetector

    private var lastX = 0f
    private var lastY = 0f
    private var pointerId = -1

    companion object {
        /** Crossfade/morph duration between panos. Renderer mirrors this in FADE_NANOS. */
        const val FADE_MS = 300L
    }

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

    /** The stitched equirect (2:1). Uploaded on the GL thread; the renderer keeps the OUTGOING
     *  texture and crossfades to this one (a "morph" into the next spot, not a hard snap), so we
     *  drive continuous rendering for the length of the fade then fall back to render-on-demand. */
    fun setPanorama(bitmap: Bitmap) {
        queueEvent { renderer.setTexture(bitmap) }
        renderMode = RENDERMODE_CONTINUOUSLY
        removeCallbacks(stopFade)
        postDelayed(stopFade, FADE_MS + 100L)
    }

    private val stopFade = Runnable { renderMode = RENDERMODE_WHEN_DIRTY }

    /**
     * Orient the camera by COMPASS. Google's equirect puts the CAPTURE heading at the texture
     * centre (u=0.5, verified against a stitched pano 2026-07-16), while this renderer's yaw=0
     * looks at texture u=0.75 - so a compass bearing B maps to renderer yaw B - panoHeading - 90.
     * Feeding compass values straight in (the old setInitialYaw) skewed every view by a per-pano
     * heading+90 offset, which is why the opening faced "randomly" wrong even with Google's own yaw.
     */
    fun setCompass(panoHeadingDeg: Float, faceCompassDeg: Float) {
        panoHeading = panoHeadingDeg
        renderer.setYaw(Math.toRadians((faceCompassDeg - panoHeadingDeg - 90.0 + 720.0) % 360.0).toFloat())
    }

    /** Live camera COMPASS bearing / vertical field-of-view in DEGREES - the arrow overlay reads
     *  these each frame to place the "walk this way" chevrons relative to where you're looking. */
    fun currentYawDeg(): Float =
        ((Math.toDegrees(renderer.yawRad().toDouble()) + panoHeading + 90.0) % 360.0 + 360.0).toFloat() % 360f
    fun currentFovDeg(): Float = renderer.fovDeg()

    private var panoHeading = 0f

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
        // Zoom is canonically the HORIZONTAL field of view: when the pane grows (half-screen ->
        // full screen) a fixed vertical fov would NARROW the horizontal view (fovX = fovY*aspect,
        // aspect shrinks) - fullscreen read as "it just zoomed in". Holding fovX keeps the same
        // horizontal framing and reveals more sky/ground instead, and it's also the value the
        // walk-arrow overlay needs (it projects bearings across the screen WIDTH).
        @Volatile private var yaw = 0f
        @Volatile private var pitch = 0f
        @Volatile private var fovX = 75f

        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uMvp = 0
        private var uTex = 0
        private var uAlpha = 0
        private var texId = 0        // the current (incoming) panorama
        private var texPrev = 0      // the outgoing panorama, kept alive until the crossfade ends
        private var fadeStartNanos = 0L
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
        fun fovDeg(): Float = fovX

        /** Vertical fov (deg) derived from the canonical horizontal fov and the live aspect. */
        private fun vFovDeg(): Float {
            val a = if (aspect > 0.05f) aspect else 1f
            return Math.toDegrees(
                2.0 * Math.atan(Math.tan(Math.toRadians(fovX / 2.0)) / a),
            ).toFloat()
        }

        fun dragBy(dx: Float, dy: Float, w: Int, h: Int) {
            // 1.7x so a swipe covers more than one field of view - the 1:1 mapping felt sluggish
            // (user 2026-07-15). Still scales with FOV so zoomed-in panning stays proportional.
            val perPx = Math.toRadians(vFovDeg().toDouble()).toFloat() / max(1, h) * DRAG_SENSITIVITY
            yaw -= dx * perPx      // drag right -> world rotates left (grab-and-pull)
            pitch += dy * perPx    // drag down -> look down
            val lim = Math.toRadians(85.0).toFloat()
            pitch = pitch.coerceIn(-lim, lim)
        }

        fun zoomBy(scale: Float) { fovX = (fovX / scale).coerceIn(30f, 110f) }

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
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // A fresh surface has no textures - drop any stale ids so we don't fade from garbage.
            texId = 0; texPrev = 0; fadeStartNanos = 0L
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            aspect = w.toFloat() / max(1, h)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            pendingBitmap?.let { bmp ->
                // Keep the OUTGOING texture around and crossfade into the new one. A new fade that
                // lands mid-fade just discards the previous outgoing frame (rapid walking).
                val newId = uploadTexture(bmp)
                if (texId != 0) {
                    if (texPrev != 0) GLES20.glDeleteTextures(1, intArrayOf(texPrev), 0)
                    texPrev = texId
                    fadeStartNanos = System.nanoTime()
                }
                texId = newId
                pendingBitmap = null
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (texId == 0) return

            // Crossfade progress 0..1. A subtle forward "dolly" (zoom in a few degrees, easing back
            // out as the fade completes) sells stepping FORWARD into the next pano rather than a flat
            // dissolve. When texPrev is 0 there's nothing to fade from - draw the new one outright.
            val fading = texPrev != 0
            val mix = if (fading) {
                ((System.nanoTime() - fadeStartNanos).toFloat() / FADE_NANOS).coerceIn(0f, 1f)
            } else 1f
            val drawFov = vFovDeg() - DOLLY_DEG * (1f - mix)

            Matrix.perspectiveM(proj, 0, drawFov, aspect, 0.05f, 20f)
            // Look direction from yaw/pitch (yaw=0 faces -Z). Camera at the origin.
            val cx = (cos(pitch) * sin(yaw))
            val cy = sin(pitch)
            val cz = (-cos(pitch) * cos(yaw))
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, cx, cy, cz, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

            if (fading && mix < 1f) {
                // Outgoing pano, fully opaque underneath…
                GLES20.glDisable(GLES20.GL_BLEND)
                drawSphere(texPrev, 1f)
                // …incoming pano blended on top, ramping in.
                GLES20.glEnable(GLES20.GL_BLEND)
                drawSphere(texId, mix)
                GLES20.glDisable(GLES20.GL_BLEND)
            } else {
                GLES20.glDisable(GLES20.GL_BLEND)
                drawSphere(texId, 1f)
                if (texPrev != 0) { GLES20.glDeleteTextures(1, intArrayOf(texPrev), 0); texPrev = 0 }
            }

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun drawSphere(tex: Int, alpha: Float) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1f(uAlpha, alpha)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
        }

        /** Uploads a bitmap to a FRESH texture and returns its id (does not touch [texId]). */
        private fun uploadTexture(bmp: Bitmap): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // Horizontal wrap so the 360° seam is continuous (the image is POT); clamp vertically.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // texImage2D COPIES the pixels into GL texture memory, so the bitmap is free to be
            // recycled by its owner (the VM) after this - we don't recycle it here, or the state's
            // reference would double-free / a recompose could re-upload a recycled bitmap.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            return id
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
                    // Natural U (NO flip). Looking down -Z, screen-right is world +X = theta
                    // increasing = u increasing, so texU must increase left-to-right or the whole
                    // pano mirrors (backwards signage + reversed "© Google" watermark). An earlier
                    // `1f - u` "fix" was itself the mirror; plain u keeps text readable AND leaves
                    // drag grab-pull + the arrow bearings geometrically consistent (user 2026-07-15).
                    uv.add(u); uv.add(v)
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
            private const val DOLLY_DEG = 5f            // forward "step into it" zoom during the fade
            private const val FADE_NANOS = 300_000_000f // keep in step with PanoramaView.FADE_MS (300 ms)
            private const val VERT =
                "uniform mat4 uMvp; attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                    "void main(){ vUv = aUv; gl_Position = uMvp * aPos; }"
            private const val FRAG =
                "precision mediump float; uniform sampler2D uTex; uniform float uAlpha; varying vec2 vUv;" +
                    "void main(){ vec4 c = texture2D(uTex, vUv); gl_FragColor = vec4(c.rgb, c.a * uAlpha); }"
        }
    }
}
