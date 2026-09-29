package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The nav icon alternatives (car, UFO, pirate ship, rubber duck) as small 3D models, drawn every
 * frame from the camera's tilt and the heading relative to the camera, so on a tilted nav map they
 * stand on the road instead of lying on it like a sticker. The default arrow stays flat (Google's
 * does too). Model space: x right, y forward, z up, roughly unit sized; [project] fits it to pixels.
 * Rendering is orthographic with painter's-order faces (sorted by depth, back faces culled) and one
 * fixed light, drawn with a reused Path and Paint so a frame allocates nothing.
 */
internal class PuckMesh(private val faces: List<Face>) {
    class Face(val pts: FloatArray, val color: Int, val doubleSided: Boolean, val nx: Float, val ny: Float, val nz: Float)

    /** Largest distance of any vertex from the model's ground center, for fitting to a size. */
    val radius: Float = faces.maxOf { f ->
        var m = 0f
        var i = 0
        while (i < f.pts.size) { m = max(m, sqrt(f.pts[i] * f.pts[i] + f.pts[i + 1] * f.pts[i + 1] + f.pts[i + 2] * f.pts[i + 2])); i += 3 }
        m
    }
    /** Footprint radius (x/y only), for the ground shadow. */
    private val footprint: Float = faces.maxOf { f ->
        var m = 0f
        var i = 0
        while (i < f.pts.size) { m = max(m, sqrt(f.pts[i] * f.pts[i] + f.pts[i + 1] * f.pts[i + 1])); i += 3 }
        m
    }

    private val order = IntArray(faces.size)
    private val depth = FloatArray(faces.size)
    private val visible = BooleanArray(faces.size)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE // the thin stroke closes the anti-aliasing seams between faces
        strokeWidth = 1f
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draw centered on ([cx], [cy]) (the ground point under the model), [sizePx] across, facing
     * [headingDeg] clockwise from screen-up, seen at [tiltDeg] from straight down.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, sizePx: Float, headingDeg: Float, tiltDeg: Float) {
        val s = sizePx * 0.46f / radius
        val psi = Math.toRadians(headingDeg.toDouble())
        val th = Math.toRadians(tiltDeg.coerceIn(0f, 80f).toDouble())
        val cp = cos(psi).toFloat(); val sp = sin(psi).toFloat()
        val ct = cos(th).toFloat(); val st = sin(th).toFloat()
        // Ground shadow: a soft disc under the footprint, flattened by the tilt like the road.
        val r = footprint * s * 1.05f
        if (r > 1f) {
            shadowPaint.shader = RadialGradient(
                cx, cy, r, intArrayOf(0x55000000, 0x33000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.save()
            canvas.scale(1f, ct.coerceAtLeast(0.2f), cx, cy)
            canvas.drawCircle(cx, cy, r, shadowPaint)
            canvas.restore()
        }
        // Light fixed to the map (sun up and to the right), toward-camera vector for culling.
        val lx = 0.35f; val ly = 0.45f; val lz = 0.82f
        val camY = -st; val camZ = ct
        var n = 0
        for (k in faces.indices) {
            val f = faces[k]
            val nxr = f.nx * cp + f.ny * sp
            val nyr = -f.nx * sp + f.ny * cp
            val facing = nyr * camY + f.nz * camZ
            visible[k] = f.doubleSided || facing > 0.01f
            if (!visible[k]) continue
            var d = 0f
            var i = 0
            while (i < f.pts.size) {
                val y = -f.pts[i] * sp + f.pts[i + 1] * cp
                d += y * camY + f.pts[i + 2] * camZ
                i += 3
            }
            depth[k] = d / (f.pts.size / 3)
            order[n++] = k
            // Shade now, while the rotated normal is at hand: flat, with a floor so shadowed
            // sides stay colored.
            val lit = (nxr * lx + nyr * ly + f.nz * lz).let { if (f.doubleSided) kotlin.math.abs(it) else max(0f, it) }
            shades[k] = 0.52f + 0.48f * lit
        }
        // Painter's order: far faces first (insertion sort; a few hundred faces, mostly in order).
        for (a in 1 until n) {
            val key = order[a]; var b = a - 1
            while (b >= 0 && depth[order[b]] > depth[key]) { order[b + 1] = order[b]; b-- }
            order[b + 1] = key
        }
        for (o in 0 until n) {
            val f = faces[order[o]]
            path.reset()
            var i = 0
            while (i < f.pts.size) {
                val x = f.pts[i] * cp + f.pts[i + 1] * sp
                val y = -f.pts[i] * sp + f.pts[i + 1] * cp
                val z = f.pts[i + 2]
                val px = cx + x * s
                val py = cy - (y * ct + z * st) * s
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                i += 3
            }
            path.close()
            paint.color = shade(f.color, shades[order[o]])
            canvas.drawPath(path, paint)
        }
    }

    private val shades = FloatArray(faces.size)

    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xff) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xff) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xff) * k).toInt().coerceIn(0, 255)
        return (c and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}

/** Builds the four models. Faces get outward normals from their part's center, so every builder
 *  can list vertices in any winding. */
internal object PuckModels {
    private class Builder {
        val faces = mutableListOf<PuckMesh.Face>()
        fun face(pts: List<FloatArray>, color: Int, center: FloatArray, doubleSided: Boolean = false) {
            val a = pts[0]; val b = pts[1]; val c = pts[2]
            var nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1])
            var ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2])
            var nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-6f) return
            nx /= len; ny /= len; nz /= len
            val mx = pts.sumOf { it[0].toDouble() }.toFloat() / pts.size
            val my = pts.sumOf { it[1].toDouble() }.toFloat() / pts.size
            val mz = pts.sumOf { it[2].toDouble() }.toFloat() / pts.size
            if ((mx - center[0]) * nx + (my - center[1]) * ny + (mz - center[2]) * nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val flat = FloatArray(pts.size * 3)
            pts.forEachIndexed { i, p -> flat[i * 3] = p[0]; flat[i * 3 + 1] = p[1]; flat[i * 3 + 2] = p[2] }
            faces += PuckMesh.Face(flat, color, doubleSided, nx, ny, nz)
        }

        /** A solid between two rings of the same vertex count: side quads plus both caps. */
        fun loft(a: List<FloatArray>, b: List<FloatArray>, color: Int, sideColors: List<Int>? = null, topColor: Int = color, capBottom: Boolean = true) {
            val all = a + b
            val center = floatArrayOf(all.map { it[0] }.average().toFloat(), all.map { it[1] }.average().toFloat(), all.map { it[2] }.average().toFloat())
            for (i in a.indices) {
                val j = (i + 1) % a.size
                face(listOf(a[i], a[j], b[j], b[i]), sideColors?.getOrNull(i) ?: color, center)
            }
            face(b, topColor, center)
            if (capBottom) face(a, color, center)
        }

        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, color: Int) =
            loft(rect(x0, x1, y0, y1, z0), rect(x0, x1, y0, y1, z1), color)

        /** A surface of revolution about the z axis from a (radius, z) profile. */
        fun revolve(profile: List<Pair<Float, Float>>, segments: Int, color: Int, oy: Float = 0f, oz: Float = 0f) {
            val center = floatArrayOf(0f, oy, profile.map { it.second }.average().toFloat() + oz)
            for (p in 0 until profile.size - 1) {
                val (r0, z0) = profile[p]; val (r1, z1) = profile[p + 1]
                for (s in 0 until segments) {
                    val a0 = 2.0 * Math.PI * s / segments; val a1 = 2.0 * Math.PI * (s + 1) / segments
                    fun pt(r: Float, z: Float, a: Double) = floatArrayOf((r * cos(a)).toFloat(), oy + (r * sin(a)).toFloat(), z + oz)
                    val quad = mutableListOf(pt(r0, z0, a0), pt(r0, z0, a1), pt(r1, z1, a1), pt(r1, z1, a0))
                    if (r0 < 1e-4f) quad.removeAt(1) else if (r1 < 1e-4f) quad.removeAt(3)
                    face(quad, color, center)
                }
            }
        }

        fun ellipsoid(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, color: Int, slices: Int = 10, stacks: Int = 6) {
            val center = floatArrayOf(cx, cy, cz)
            for (i in 0 until stacks) {
                val t0 = Math.PI * i / stacks - Math.PI / 2; val t1 = Math.PI * (i + 1) / stacks - Math.PI / 2
                for (j in 0 until slices) {
                    val p0 = 2 * Math.PI * j / slices; val p1 = 2 * Math.PI * (j + 1) / slices
                    fun pt(t: Double, p: Double) = floatArrayOf(
                        cx + rx * (cos(t) * cos(p)).toFloat(), cy + ry * (cos(t) * sin(p)).toFloat(), cz + rz * sin(t).toFloat(),
                    )
                    val quad = mutableListOf(pt(t0, p0), pt(t0, p1), pt(t1, p1), pt(t1, p0))
                    if (i == 0) quad.removeAt(1) else if (i == stacks - 1) quad.removeAt(3)
                    face(quad, color, center)
                }
            }
        }

        fun quad(pts: List<FloatArray>, color: Int) = face(pts, color, floatArrayOf(0f, 0f, -10f), doubleSided = true)

        fun build() = PuckMesh(faces)
    }

    private fun rect(x0: Float, x1: Float, y0: Float, y1: Float, z: Float) =
        listOf(floatArrayOf(x0, y0, z), floatArrayOf(x1, y0, z), floatArrayOf(x1, y1, z), floatArrayOf(x0, y1, z))

    private fun ring(xy: List<Pair<Float, Float>>, z: Float) = xy.map { floatArrayOf(it.first, it.second, z) }

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    private val cache = HashMap<String, PuckMesh>()

    /** The model for a shape, or null for the flat arrow. */
    fun forShape(shape: String, carColor: String): PuckMesh? = when (shape) {
        app.vela.ui.PuckStyle.SHAPE_CAR -> cache.getOrPut("car/$carColor") { car(carColor) }
        app.vela.ui.PuckStyle.SHAPE_UFO -> cache.getOrPut("ufo") { ufo() }
        app.vela.ui.PuckStyle.SHAPE_SHIP -> cache.getOrPut("ship") { ship() }
        app.vela.ui.PuckStyle.SHAPE_DUCK -> cache.getOrPut("duck") { duck() }
        else -> null
    }

    fun carBody(colorName: String) = c(
        when (colorName) {
            "blue" -> "#1a46e5"
            "white" -> "#F4F5F7"
            "green" -> "#1E9E5A"
            "yellow" -> "#F2C230"
            else -> "#D93025"
        },
    )

    private fun car(colorName: String): PuckMesh = Builder().apply {
        val body = carBody(colorName)
        val glass = c("#2B3440")
        val tire = c("#1B1B1B")
        // Wheels first (they sit partly inside the body; the depth sort keeps the body over them).
        for (y in listOf(0.55f, -0.6f)) {
            box(-0.49f, -0.37f, y - 0.19f, y + 0.19f, 0f, 0.32f, tire)
            box(0.37f, 0.49f, y - 0.19f, y + 0.19f, 0f, 0.32f, tire)
        }
        // Lower body: rounded nose, square tail, a slightly lower hood line than the flanks.
        val outline = listOf(-0.45f to -0.96f, 0.45f to -0.96f, 0.45f to 0.78f, 0.36f to 0.98f, -0.36f to 0.98f, -0.45f to 0.78f)
        loft(ring(outline, 0.12f), ring(outline, 0.44f), body)
        // Cabin: glass sides and windshield, body-colored roof.
        val cabBottom = ring(listOf(-0.40f to -0.64f, 0.40f to -0.64f, 0.40f to 0.28f, -0.40f to 0.28f), 0.44f)
        val cabTop = ring(listOf(-0.33f to -0.46f, 0.33f to -0.46f, 0.33f to 0.10f, -0.33f to 0.10f), 0.74f)
        loft(cabBottom, cabTop, glass, topColor = body, capBottom = false)
        // Headlights and taillights.
        box(-0.33f, -0.16f, 0.955f, 0.995f, 0.30f, 0.38f, c("#FFF6C8"))
        box(0.16f, 0.33f, 0.955f, 0.995f, 0.30f, 0.38f, c("#FFF6C8"))
        box(-0.40f, -0.22f, -0.99f, -0.95f, 0.30f, 0.38f, c("#C62828"))
        box(0.22f, 0.40f, -0.99f, -0.95f, 0.30f, 0.38f, c("#C62828"))
    }.build()

    private fun ufo(): PuckMesh = Builder().apply {
        val hover = 0.28f
        // Saucer hull, then the glass dome, then rim lights with a yellow one at the front.
        revolve(listOf(0f to 0.10f, 0.50f to 0.14f, 0.98f to 0.32f, 0.96f to 0.40f, 0.55f to 0.52f, 0.36f to 0.56f), 20, c("#9AA3AD"), oz = hover)
        revolve(listOf(0.36f to 0.56f, 0.32f to 0.72f, 0.20f to 0.85f, 0f to 0.90f), 16, c("#8FE3FF"), oz = hover)
        for (i in 0 until 8) {
            val a = Math.PI / 2 + 2 * Math.PI * i / 8
            val x = (0.93f * cos(a)).toFloat(); val y = (0.93f * sin(a)).toFloat()
            val col = if (i == 0) c("#FFE066") else if (i % 2 == 0) c("#4FC3F7") else c("#FF8A80")
            box(x - 0.06f, x + 0.06f, y - 0.06f, y + 0.06f, 0.33f + hover, 0.43f + hover, col)
        }
        // A little green passenger under the dome.
        ellipsoid(0f, 0f, 0.66f + hover, 0.14f, 0.14f, 0.13f, c("#7ED957"), slices = 8, stacks = 5)
    }.build()

    private fun ship(): PuckMesh = Builder().apply {
        val hullOutline = listOf(0f to 1.0f, 0.30f to 0.55f, 0.36f to 0.0f, 0.33f to -0.70f, 0.26f to -0.95f, -0.26f to -0.95f, -0.33f to -0.70f, -0.36f to 0.0f, -0.30f to 0.55f)
        val keel = hullOutline.map { (x, y) -> x * 0.55f to y * 0.88f }
        loft(ring(keel, 0.02f), ring(hullOutline, 0.40f), c("#8B5A2B"), topColor = c("#C08A55"))
        box(-0.26f, 0.26f, -0.93f, -0.58f, 0.40f, 0.62f, c("#6D4322")) // stern castle
        val mast = c("#4E3118")
        for (y in listOf(0.32f, -0.22f)) {
            box(-0.03f, 0.03f, y - 0.03f, y + 0.03f, 0.40f, 1.45f, mast)
            quad(listOf(floatArrayOf(-0.46f, y + 0.05f, 0.70f), floatArrayOf(0.46f, y + 0.05f, 0.70f), floatArrayOf(0.40f, y + 0.09f, 1.30f), floatArrayOf(-0.40f, y + 0.09f, 1.30f)), c("#F4EAD5"))
        }
        quad(listOf(floatArrayOf(0.03f, 0.32f, 1.30f), floatArrayOf(0.30f, 0.32f, 1.30f), floatArrayOf(0.30f, 0.32f, 1.45f), floatArrayOf(0.03f, 0.32f, 1.45f)), c("#1B1B1B"))
    }.build()

    private fun duck(): PuckMesh = Builder().apply {
        val yellow = c("#FFD93B")
        ellipsoid(0f, -0.15f, 0.36f, 0.56f, 0.80f, 0.36f, yellow, slices = 12, stacks = 7)
        ellipsoid(0f, -0.82f, 0.58f, 0.18f, 0.20f, 0.20f, yellow, slices = 8, stacks = 5) // tail
        ellipsoid(-0.47f, -0.15f, 0.46f, 0.12f, 0.42f, 0.18f, c("#FFE77A"), slices = 8, stacks = 5)
        ellipsoid(0.47f, -0.15f, 0.46f, 0.12f, 0.42f, 0.18f, c("#FFE77A"), slices = 8, stacks = 5)
        ellipsoid(0f, 0.50f, 0.88f, 0.36f, 0.34f, 0.34f, yellow, slices = 12, stacks = 7) // head
        val beakBase = listOf(floatArrayOf(-0.17f, 0.76f, 0.74f), floatArrayOf(0.17f, 0.76f, 0.74f), floatArrayOf(0.17f, 0.76f, 0.88f), floatArrayOf(-0.17f, 0.76f, 0.88f))
        val beakTip = listOf(floatArrayOf(-0.11f, 1.10f, 0.77f), floatArrayOf(0.11f, 1.10f, 0.77f), floatArrayOf(0.11f, 1.10f, 0.83f), floatArrayOf(-0.11f, 1.10f, 0.83f))
        loft(beakBase, beakTip, c("#FF8C1A"))
        for (x in listOf(-0.17f, 0.17f)) ellipsoid(x, 0.72f, 1.00f, 0.06f, 0.05f, 0.07f, c("#111111"), slices = 6, stacks = 4)
    }.build()
}

/** A still of the model for Settings: three-quarter view at a nav-like tilt. */
internal fun puck3DPreviewBitmap(mesh: PuckMesh, sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    mesh.draw(Canvas(bmp), sizePx / 2f, sizePx * 0.58f, sizePx * 0.9f, 25f, 50f)
    return bmp
}
