package app.vela.offline

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Just enough of the PMTiles v3 format to answer one question: does this archive actually hold a
 * tile at this place?
 *
 * The app needs it because an archive's bounding box is a rectangle and a region is not. A state's
 * box always overlaps its neighbors, so "the smallest box covering the view" can pick an archive
 * that has no tiles there, and the map then draws nothing at all - no error, no fallback, just the
 * land color with the traffic raster and the place pins still on top (issue #552). Asking the file
 * settles it.
 *
 * Reads the 127-byte header plus one or two directory pages, nothing else. Every failure answers
 * null, which callers treat as "cannot tell" and fall back to the old rule, so a format the reader
 * does not understand can never be worse than not having asked.
 */
object PmtilesReader {
    private const val HEADER_LEN = 127
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2

    /** The OpenMapTiles layer the roads live in; present only where the extract had data. */
    const val ROAD_LAYER = "transportation"

    /** A z12 vector tile is tens of kilobytes; anything past this is not one. */
    private const val MAX_TILE_BYTES = 4L * 1024 * 1024

    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val leafOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    fun header(file: File): Header? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(HEADER_LEN)
            if (f.read(head) < HEADER_LEN) return null
            if (String(head, 0, 7) != "PMTiles" || head[7].toInt() != 3) return null
            Header(
                rootOffset = le64(head, 8),
                rootLength = le64(head, 16),
                leafOffset = le64(head, 40),
                tileDataOffset = le64(head, 56),
                internalCompression = head[97].toInt() and 0xFF,
                tileCompression = head[98].toInt() and 0xFF,
                minZoom = head[100].toInt() and 0xFF,
                maxZoom = head[101].toInt() and 0xFF,
            )
        }
    }.getOrNull()

    /** True when the archive holds the tile, false when it demonstrably does not, null when the
     *  file could not be read the way this reader expects. */
    fun hasTile(file: File, z: Int, x: Int, y: Int): Boolean? = entryFor(file, z, x, y)?.let { true } ?: run {
        val h = header(file) ?: return null
        if (z < h.minZoom || z > h.maxZoom) null else false
    }

    /** True when the archive's tile here carries the road network, false when it holds only the
     *  base layers, null when it cannot be told.
     *
     *  This, not mere tile presence, is the question the mount rule has to ask. A bake emits tiles
     *  across its WHOLE bounding box because planetiler's base data (water, landcover, Natural
     *  Earth) is global: a state archive therefore has tiles over its neighbors and out to sea, and
     *  they render as empty land. Only the OSM-derived layers stop at the extract, so the presence
     *  of [ROAD_LAYER] is what separates "this archive draws the map here" from "this archive draws
     *  nothing here". */
    fun hasRoads(file: File, z: Int, x: Int, y: Int): Boolean? {
        val h = header(file) ?: return null
        if (z < h.minZoom || z > h.maxZoom) return null
        val e = entryFor(file, z, x, y) ?: return false
        if (e.length <= 0 || e.length > MAX_TILE_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { f ->
                val raw = ByteArray(e.length.toInt())
                f.seek(h.tileDataOffset + e.offset)
                f.readFully(raw)
                val body = when (h.tileCompression) {
                    COMPRESSION_NONE -> raw
                    COMPRESSION_GZIP -> GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                    else -> return@runCatching null
                }
                layerNames(body).contains(ROAD_LAYER)
            }
        }.getOrNull()
    }

    /** The directory entry for a tile, or null when the archive does not hold it. */
    private fun entryFor(file: File, z: Int, x: Int, y: Int): Entry? {
        val h = header(file) ?: return null
        if (z < h.minZoom || z > h.maxZoom) return null
        val want = tileId(z, x, y)
        return runCatching {
            RandomAccessFile(file, "r").use { f ->
                var offset = h.rootOffset
                var length = h.rootLength
                // A root entry can point at a leaf directory instead of a tile; the loop is bounded
                // rather than recursive because no published archive nests deeper than that.
                repeat(4) {
                    val dir = readDirectory(f, offset, length, h.internalCompression) ?: return@runCatching null
                    val e = find(dir, want) ?: return@runCatching null
                    if (e.runLength > 0) return@runCatching e
                    offset = h.leafOffset + e.offset
                    length = e.length
                }
                null
            }
        }.getOrNull()
    }

    /** The layer names in a Mapbox Vector Tile: repeated Layer (field 3), each with a name
     *  (field 1). Nothing else in the tile is read. */
    fun layerNames(tile: ByteArray): Set<String> {
        val names = HashSet<String>()
        val r = Varints(tile)
        while (r.hasMore()) {
            val key = r.next()
            val field = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> r.next()
                1 -> r.skip(8)
                2 -> {
                    val len = r.next().toInt()
                    if (len < 0 || len > tile.size) return names
                    if (field == 3) {
                        val end = r.position() + len
                        // The layer's own fields; only its name (field 1, length-delimited) matters.
                        while (r.position() < end && r.hasMore()) {
                            val k2 = r.next()
                            val f2 = (k2 shr 3).toInt()
                            when ((k2 and 7L).toInt()) {
                                0 -> r.next()
                                1 -> r.skip(8)
                                2 -> {
                                    val l2 = r.next().toInt()
                                    if (f2 == 1 && l2 in 0..256) names.add(r.string(l2)) else r.skip(l2)
                                }
                                5 -> r.skip(4)
                                else -> return names
                            }
                        }
                        r.seek(end)
                    } else r.skip(len)
                }
                5 -> r.skip(4)
                else -> return names
            }
        }
        return names
    }

    data class Entry(val id: Long, val offset: Long, val length: Long, val runLength: Long)

    /** Every tile entry in the archive, leaf directories walked, in id order. The delta applier
     *  needs the whole set to fingerprint an archive; the mount probe only ever wants one tile and
     *  uses [entryFor], which stops as soon as it finds it. */
    fun entries(file: File): List<Entry>? {
        val h = header(file) ?: return null
        return entriesAt(file, h.rootOffset, h.rootLength, h.leafOffset, h.internalCompression)
    }

    /** The same walk against a directory the header does not point at yet. The delta applier uses
     *  it to fingerprint what a patch WOULD produce before it commits the header to it. */
    fun entriesAt(file: File, rootOffset: Long, rootLength: Long, leafOffset: Long, compression: Int): List<Entry>? = runCatching {
        val h = Triple(rootOffset, rootLength, leafOffset)
        RandomAccessFile(file, "r").use { f ->
            val out = ArrayList<Entry>()
            val stack = ArrayDeque<Pair<Long, Long>>()
            stack.addLast(h.first to h.second)
            var pages = 0
            while (stack.isNotEmpty()) {
                if (pages++ > 8192) return@runCatching null // a malformed archive must not spin forever
                val (off, len) = stack.removeLast()
                val dir = readDirectory(f, off, len, compression) ?: return@runCatching null
                for (e in dir) {
                    if (e.runLength == 0L) stack.addLast(h.third + e.offset to e.length) else out.add(e)
                }
            }
            out.sortBy { it.id }
            out
        }
    }.getOrNull()

    private fun find(entries: List<Entry>, want: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        var best: Entry? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val e = entries[mid]
            if (e.id <= want) { best = e; lo = mid + 1 } else hi = mid - 1
        }
        val e = best ?: return null
        // A run covers [id, id + runLength); a leaf pointer (runLength 0) covers everything from
        // its id up to the next entry, which is why it is returned without a range test.
        if (e.runLength == 0L) return e
        return if (want < e.id + e.runLength) e else null
    }

    private fun readDirectory(f: RandomAccessFile, offset: Long, length: Long, compression: Int): List<Entry>? {
        if (length <= 0 || length > 64L * 1024 * 1024) return null
        val raw = ByteArray(length.toInt())
        f.seek(offset)
        f.readFully(raw)
        val bytes = when (compression) {
            COMPRESSION_NONE -> raw
            COMPRESSION_GZIP -> GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            else -> return null
        }
        val r = Varints(bytes)
        val n = r.next().toInt()
        if (n <= 0 || n > 4_000_000) return null
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.next(); ids[i] = last }
        val runs = LongArray(n) { r.next() }
        val lengths = LongArray(n) { r.next() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            // 0 means "directly after the previous entry", which is how a clustered archive avoids
            // writing an offset per tile.
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    private class Varints(private val b: ByteArray) {
        private var i = 0
        fun hasMore() = i < b.size
        fun position() = i
        fun seek(at: Int) { i = at.coerceIn(0, b.size) }
        fun skip(n: Int) { i = (i + n).coerceIn(0, b.size) }
        fun string(n: Int): String {
            val s = String(b, i, n.coerceAtMost(b.size - i))
            skip(n)
            return s
        }
        fun next(): Long {
            var shift = 0
            var value = 0L
            while (i < b.size) {
                val c = b[i++].toInt() and 0xFF
                value = value or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return value
                shift += 7
            }
            return value
        }
    }

    /** The Hilbert-curve id PMTiles orders tiles by. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        var n = 1L shl z
        var xx = x.toLong()
        var yy = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (xx and s > 0L) 1L else 0L
            val ry = if (yy and s > 0L) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                val t = xx; xx = yy; yy = t
            }
            s /= 2
        }
        return acc + d
    }

    /** Web-mercator tile column and row for a coordinate. */
    fun tileOf(lat: Double, lng: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        return x to y
    }

    private fun le64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
