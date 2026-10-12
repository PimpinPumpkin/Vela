package app.vela.core.util

import app.vela.core.model.LatLng
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The center of an S2 cell from its 64-bit id (the reference decoding: face, then the Hilbert
 * curve position to a leaf (i, j), then the cube face coordinates to a point on the sphere).
 * Google's feature id `0x<cell>:0x<id>` carries one as its first half, so a place link with no
 * coordinates still says roughly where the place is: within a hundred meters for a leaf cell,
 * a few kilometers for a coarser one.
 */
object S2Cell {
    private const val MAX_LEVEL = 30
    private const val POS_BITS = 2 * MAX_LEVEL + 1
    private const val MAX_SIZE = 1L shl MAX_LEVEL
    private const val LOOKUP_BITS = 4
    private const val SWAP = 1
    private const val INVERT = 2
    private val POS_TO_IJ = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 3, 1), intArrayOf(3, 2, 0, 1), intArrayOf(3, 1, 0, 2))
    private val POS_TO_ORIENTATION = intArrayOf(SWAP, 0, 0, INVERT or SWAP)
    private val lookupIj = IntArray(1 shl (2 * LOOKUP_BITS + 2))

    init {
        for (orientation in 0 until 4) initLookup(0, 0, 0, orientation, orientation, 0)
    }

    private fun initLookup(level: Int, i: Int, j: Int, origOrientation: Int, orientation: Int, pos: Int) {
        if (level == LOOKUP_BITS) {
            val ij = (i shl LOOKUP_BITS) + j
            lookupIj[(pos shl 2) + origOrientation] = (ij shl 2) + orientation
            return
        }
        val i2 = i shl 1; val j2 = j shl 1; val pos2 = pos shl 2
        for (sub in 0 until 4) {
            val d = POS_TO_IJ[orientation][sub]
            initLookup(level + 1, i2 + (d shr 1), j2 + (d and 1), origOrientation, orientation xor POS_TO_ORIENTATION[sub], pos2 + sub)
        }
    }

    /** The id's level: 30 for a leaf, lower for every trailing zero pair. */
    fun level(id: Long): Int = MAX_LEVEL - (java.lang.Long.numberOfTrailingZeros(id) shr 1)

    /** The cell's center, or null for an id with no face or no trailing one bit. */
    fun center(id: Long): LatLng? {
        if (id == 0L) return null
        val face = (id ushr POS_BITS).toInt()
        if (face > 5) return null
        var bits = face and SWAP
        var i = 0L
        var j = 0L
        for (k in 7 downTo 0) {
            val nbits = if (k == 7) MAX_LEVEL - 7 * LOOKUP_BITS else LOOKUP_BITS
            bits += (((id ushr (k * 2 * LOOKUP_BITS + 1)) and ((1L shl (2 * nbits)) - 1)) shl 2).toInt()
            bits = lookupIj[bits]
            i += (bits shr (LOOKUP_BITS + 2)).toLong() shl (k * LOOKUP_BITS)
            j += ((bits shr 2) and ((1 shl LOOKUP_BITS) - 1)).toLong() shl (k * LOOKUP_BITS)
            bits = bits and (SWAP or INVERT)
        }
        // The decoded (i, j) is a leaf by the cell's center; the reference's delta puts the
        // center on the right half-leaf for a coarser cell.
        val delta = when {
            level(id) == MAX_LEVEL -> 1L
            ((i xor (id shr 2)) and 1L) != 0L -> 2L
            else -> 0L
        }
        val u = stToUv((2 * i + delta).toDouble() / (2 * MAX_SIZE))
        val v = stToUv((2 * j + delta).toDouble() / (2 * MAX_SIZE))
        val (x, y, z) = when (face) {
            0 -> Triple(1.0, u, v)
            1 -> Triple(-u, 1.0, v)
            2 -> Triple(-u, -v, 1.0)
            3 -> Triple(-1.0, -v, -u)
            4 -> Triple(v, -1.0, -u)
            else -> Triple(v, u, -1.0)
        }
        return LatLng(Math.toDegrees(atan2(z, hypot(x, y))), Math.toDegrees(atan2(y, x)))
    }

    private fun stToUv(s: Double): Double = if (s >= 0.5) (1.0 / 3) * (4 * s * s - 1) else (1.0 / 3) * (1 - 4 * (1 - s) * (1 - s))
}
