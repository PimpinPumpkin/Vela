package app.vela.ui.map

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rubber duck nav icon, ray cast per pixel from a few ellipsoids: body, head, tail, bill and
 * eyes. Each pixel takes the nearest surface and its true normal,
 * so the duck is round at any size, with a plastic sheen (Blinn-Phong) and a soft sky light from
 * above. Pixels on an outline or a color edge are sampled four times more.
 *
 * Same model space and view as [PuckMesh]: x right, y forward, z up, the ground center at the
 * origin; orthographic, heading clockwise from screen-up, tilt from straight down; the light fixed
 * up and to the right on screen, a little toward the camera. No Android types, so it runs in a JVM
 * test.
 */
internal object DuckRender {
    // Per part: center xyz, radii xyz, rotation (pitch about x, roll about y, yaw about z, degrees),
    // color, gloss.
    private class Part(
        val c: FloatArray, val r: FloatArray, pitch: Float, roll: Float, yaw: Float,
        val color: Int, val gloss: Float, val blend: Boolean = false,
    ) {
        /** Local to model rotation, row major. Its transpose takes model to local. */
        val m = rot(pitch, roll, yaw)
    }

    private const val YELLOW = 0xFFFFCC1A.toInt()
    private const val BILL = 0xFFFF7A00.toInt()
    private const val EYE_WHITE = 0xFFFFFFFF.toInt()
    private const val PUPIL = 0xFF151515.toInt()

    private val parts: List<Part> = buildList {
        add(Part(f(0f, -0.04f, 0.39f), f(0.55f, 0.74f, 0.41f), 0f, 0f, 0f, YELLOW, 0.55f, blend = true)) // body
        add(Part(f(0f, -0.46f, 0.56f), f(0.36f, 0.44f, 0.24f), -38f, 0f, 0f, YELLOW, 0.55f, blend = true)) // tail
        val head = f(0f, 0.34f, 0.88f)
        add(Part(head, f(0.37f, 0.37f, 0.36f), 0f, 0f, 0f, YELLOW, 0.6f, blend = true))
        add(Part(f(0f, 0.74f, 0.80f), f(0.21f, 0.21f, 0.07f), 4f, 0f, 0f, BILL, 0.7f)) // bill
        // Eyes: a white set into the head and a black pupil standing a little proud of it.
        for (sx in listOf(-1f, 1f)) {
            val d = norm(sx * 0.46f, 0.70f, 0.48f)
            add(Part(f(head[0] + d[0] * 0.33f, head[1] + d[1] * 0.33f, head[2] + d[2] * 0.33f), f(0.08f, 0.08f, 0.08f), 0f, 0f, 0f, EYE_WHITE, 0.6f))
            add(Part(f(head[0] + d[0] * 0.37f, head[1] + d[1] * 0.37f, head[2] + d[2] * 0.37f), f(0.05f, 0.05f, 0.05f), 0f, 0f, 0f, PUPIL, 1.2f))
        }
    }

    /** Largest distance of the duck from its ground center: [PuckMesh] fits by the same. */
    val radius: Float = parts.maxOf { p -> sqrt(p.c[0] * p.c[0] + p.c[1] * p.c[1] + p.c[2] * p.c[2]) + max(p.r[0], max(p.r[1], p.r[2])) }

    /** How far outside another molded part (in its squared unit radius) a normal still leans to it. */
    private const val BLEND_W = 0.7f

    /** Footprint radius for the ground shadow. */
    const val FOOTPRINT = 0.86f

    /**
     * Unpremultiplied ARGB pixels, [w] by [h], with the ground center at ([ox], [oy]) and [scale]
     * pixels to a model unit, written to [into] and returned; [ids] is scratch. Keeps no state, so
     * any thread may call it with arrays of its own.
     */
    fun render(
        w: Int, h: Int, ox: Float, oy: Float, scale: Float, headingDeg: Float, tiltDeg: Float,
        into: IntArray = IntArray(w * h), ids: IntArray = IntArray(w * h),
    ): IntArray {
        val psi = Math.toRadians(headingDeg.toDouble())
        val th = Math.toRadians(tiltDeg.coerceIn(0f, 80f).toDouble())
        val cp = cos(psi).toFloat(); val sp = sin(psi).toFloat()
        val ct = cos(th).toFloat(); val st = sin(th).toFloat()
        // Screen right, screen up and toward the camera, in model space.
        val rx = cp; val ry = sp; val rz = 0f
        val ux = -ct * sp; val uy = ct * cp; val uz = st
        val vx = st * sp; val vy = -st * cp; val vz = ct
        // The light, fixed on screen like PuckMesh's but from the camera's side, so the faces the
        // camera sees are the lit ones; turned into model space.
        val l0x = 0.45f; val l0y = -0.25f; val l0z = 0.86f
        var lx = l0x * cp - l0y * sp; var ly = l0x * sp + l0y * cp; var lz = l0z
        val ll = sqrt(lx * lx + ly * ly + lz * lz); lx /= ll; ly /= ll; lz /= ll
        var hx = lx + vx; var hy = ly + vy; var hz = lz + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz); hx /= hl; hy /= hl; hz /= hl
        val far = radius * 3f

        // Per part, a ray in its unit-sphere space is o = u*A + v*B + E, direction D, both
        // constant over the frame: the view is orthographic.
        val n = parts.size
        val a = Array(n) { FloatArray(3) }; val b = Array(n) { FloatArray(3) }
        val e = Array(n) { FloatArray(3) }; val d = Array(n) { FloatArray(3) }
        val qa = FloatArray(n)
        // Each part's bounding circle on screen: a pixel tests only the parts that can cover it.
        val bx = FloatArray(n); val by = FloatArray(n); val br2 = FloatArray(n)
        for (k in 0 until n) {
            val p = parts[k]
            bx[k] = ox + (p.c[0] * rx + p.c[1] * ry + p.c[2] * rz) * scale
            by[k] = oy - (p.c[0] * ux + p.c[1] * uy + p.c[2] * uz) * scale
            val br = max(p.r[0], max(p.r[1], p.r[2])) * scale + 1f
            br2[k] = br * br
            toLocal(p, rx, ry, rz, a[k]); toLocal(p, ux, uy, uz, b[k])
            toLocal(p, far * vx - p.c[0], far * vy - p.c[1], far * vz - p.c[2], e[k])
            toLocal(p, -vx, -vy, -vz, d[k])
            qa[k] = d[k][0] * d[k][0] + d[k][1] * d[k][1] + d[k][2] * d[k][2]
        }

        val hit = IntArray(2)
        val nrm = FloatArray(3)
        val tmp = FloatArray(3)
        fun sample(px: Float, py: Float): Int {
            val u = (px - ox) / scale; val v = (oy - py) / scale
            var best = Float.MAX_VALUE; var bi = -1
            for (k in 0 until n) {
                val ddx = px - bx[k]; val ddy = py - by[k]
                if (ddx * ddx + ddy * ddy > br2[k]) continue
                val ak = a[k]; val bk = b[k]; val ek = e[k]; val dk = d[k]
                val o0 = u * ak[0] + v * bk[0] + ek[0]
                val o1 = u * ak[1] + v * bk[1] + ek[1]
                val o2 = u * ak[2] + v * bk[2] + ek[2]
                val bb = 2f * (o0 * dk[0] + o1 * dk[1] + o2 * dk[2])
                val cc = o0 * o0 + o1 * o1 + o2 * o2 - 1f
                val disc = bb * bb - 4f * qa[k] * cc
                if (disc < 0f) continue
                val t = (-bb - sqrt(disc)) / (2f * qa[k])
                if (t > 0f && t < best) { best = t; bi = k }
            }
            hit[0] = bi
            if (bi < 0) return 0
            val p = parts[bi]
            val ak = a[bi]; val bk = b[bi]; val ek = e[bi]; val dk = d[bi]
            // The local point on the unit sphere, then the ellipsoid's normal back in model space.
            val q0 = (u * ak[0] + v * bk[0] + ek[0] + best * dk[0]) / p.r[0]
            val q1 = (u * ak[1] + v * bk[1] + ek[1] + best * dk[1]) / p.r[1]
            val q2 = (u * ak[2] + v * bk[2] + ek[2] + best * dk[2]) / p.r[2]
            val m = p.m
            nrm[0] = m[0] * q0 + m[1] * q1 + m[2] * q2
            nrm[1] = m[3] * q0 + m[4] * q1 + m[5] * q2
            nrm[2] = m[6] * q0 + m[7] * q1 + m[8] * q2
            var nl = sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2])
            var nx = nrm[0] / nl; var ny = nrm[1] / nl; var nz = nrm[2] / nl
            // Where two molded parts meet, lean the normal toward the other part's, as a smooth
            // union's would: the join reads as one rounded piece with a soft crease, not a seam.
            var near = 0f
            if (p.blend) {
                val wx = u * rx + v * ux + (far - best) * vx
                val wy = u * ry + v * uy + (far - best) * vy
                val wz = u * rz + v * uz + (far - best) * vz
                for (j in 0 until n) {
                    if (j == bi) continue
                    val pj = parts[j]
                    if (!pj.blend) continue
                    toLocal(pj, wx - pj.c[0], wy - pj.c[1], wz - pj.c[2], tmp)
                    val g = tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2] - 1f
                    if (g >= BLEND_W) continue
                    val wgt = (1f - max(0f, g) / BLEND_W).let { it * it }
                    val m2 = pj.m
                    val g0 = tmp[0] / pj.r[0]; val g1 = tmp[1] / pj.r[1]; val g2 = tmp[2] / pj.r[2]
                    var jx = m2[0] * g0 + m2[1] * g1 + m2[2] * g2
                    var jy = m2[3] * g0 + m2[4] * g1 + m2[5] * g2
                    var jz = m2[6] * g0 + m2[7] * g1 + m2[8] * g2
                    val jl = sqrt(jx * jx + jy * jy + jz * jz); jx /= jl; jy /= jl; jz /= jl
                    nx += jx * wgt; ny += jy * wgt; nz += jz * wgt
                    near += wgt
                }
                nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
            }
            val diff = max(0f, nx * lx + ny * ly + nz * lz)
            val sky = 0.44f + 0.12f * nz // more light on what faces up, less under the belly
            var sp2 = max(0f, nx * hx + ny * hy + nz * hz)
            sp2 *= sp2; sp2 *= sp2; sp2 *= sp2; sp2 *= sp2; sp2 *= sp2 // ^32
            val spec = sp2 * p.gloss
            val facing = max(0f, nx * vx + ny * vy + nz * vz)
            val rim = (1f - facing).let { it * it * it } * 0.12f
            // A little shade in a crease, and warm shadows: yellow scaled down turns olive, so red
            // falls off slower than green and blue faster.
            val k = (sky + 0.55f * diff + rim) * (1f - 0.08f * minOf(1f, near))
            val c = p.color
            val r = ((c shr 16 and 0xff) * minOf(1.05f, k * 1.1f + 0.08f) + 255f * spec).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 0xff) * k + 255f * spec).toInt().coerceIn(0, 255)
            val bl = ((c and 0xff) * k * 0.85f + 255f * spec).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }

        val aa = into
        for (y in 0 until h) for (x in 0 until w) {
            aa[y * w + x] = sample(x + 0.5f, y + 0.5f)
            ids[y * w + x] = hit[0]
        }
        // Four more samples where a neighbor shows another part or the background. The edge test
        // reads only the part ids, so the pixels can be replaced in place.
        val offs = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val id = ids[i]
            val edge = (x > 0 && ids[i - 1] != id) || (x < w - 1 && ids[i + 1] != id) ||
                (y > 0 && ids[i - w] != id) || (y < h - 1 && ids[i + w] != id)
            if (!edge) continue
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            var j = 0
            while (j < 8) {
                val c = sample(x + offs[j], y + offs[j + 1])
                val al = c ushr 24
                sa += al; sr += (c shr 16 and 0xff) * al; sg += (c shr 8 and 0xff) * al; sb += (c and 0xff) * al
                j += 2
            }
            aa[i] = if (sa == 0) 0 else ((sa / 4) shl 24) or ((sr / sa) shl 16) or ((sg / sa) shl 8) or (sb / sa)
        }
        return aa
    }

    private fun toLocal(p: Part, x: Float, y: Float, z: Float, out: FloatArray) {
        val m = p.m
        out[0] = (m[0] * x + m[3] * y + m[6] * z) / p.r[0]
        out[1] = (m[1] * x + m[4] * y + m[7] * z) / p.r[1]
        out[2] = (m[2] * x + m[5] * y + m[8] * z) / p.r[2]
    }

    private fun f(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun norm(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    /** Rz(yaw) * Ry(roll) * Rx(pitch), row major. */
    private fun rot(pitch: Float, roll: Float, yaw: Float): FloatArray {
        val a = Math.toRadians(pitch.toDouble()); val b = Math.toRadians(roll.toDouble()); val g = Math.toRadians(yaw.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
        val cg = cos(g).toFloat(); val sg = sin(g).toFloat()
        val rx = floatArrayOf(1f, 0f, 0f, 0f, ca, -sa, 0f, sa, ca)
        val ry = floatArrayOf(cb, 0f, sb, 0f, 1f, 0f, -sb, 0f, cb)
        val rz = floatArrayOf(cg, -sg, 0f, sg, cg, 0f, 0f, 0f, 1f)
        return mul(rz, mul(ry, rx))
    }

    private fun mul(p: FloatArray, q: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        p[r * 3] * q[c] + p[r * 3 + 1] * q[3 + c] + p[r * 3 + 2] * q[6 + c]
    }
}
