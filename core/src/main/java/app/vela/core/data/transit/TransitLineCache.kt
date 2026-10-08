package app.vela.core.data.transit

import app.vela.core.data.google.PolylineCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The colored transit lines of the map cells a phone has looked at, kept on the phone.
 *
 * A cell read from here is on the map before any request goes out, and with no connection it is
 * all there is. Fetching instead costs 2 to 4 MB of JSON and over a second for one 0.05 degree
 * cell of New York, six to twelve cells a view, and the map shows the plain highlight meanwhile.
 *
 * One JSON file per cell under [dirOf], named by the cell's key. A cell is fresh for [FRESH_MS];
 * after that it is still shown and the caller fetches it again in the background (routes and
 * their shapes change a few times a year). The folder holds at most [MAX_CELLS] files and
 * [MAX_BYTES]; the cells fetched longest ago go first.
 *
 * Lines are stored as encoded polylines at 6 decimals (0.1 m). [write] returns the lines as a
 * later [read] gives them, and the caller keeps that copy: a stretch that crosses a cell edge
 * comes back from both cells and is recognized by its end points, which must be the same numbers
 * whether a cell came from the network or from this folder.
 */
class TransitLineCache(private val dirOf: () -> File, private val now: () -> Long = System::currentTimeMillis) {

    class Cell(val lines: List<Transitous.MapLine>, val fetchedAt: Long)

    @Serializable private data class CellDto(val v: Int = 0, val at: Long = 0, val lines: List<LineDto> = emptyList())

    /** p: the points; k: the kind; c: the colors; n and m: the labels' names and their colors. */
    @Serializable private data class LineDto(
        val p: String = "",
        val k: String = "",
        val c: List<String> = emptyList(),
        val n: List<String> = emptyList(),
        val m: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun isFresh(cell: Cell): Boolean = now() - cell.fetchedAt in 0 until FRESH_MS

    /** The cell kept under [key], or null when there is none or the file cannot be read. */
    @Synchronized
    fun read(key: String): Cell? {
        val f = fileOf(key)
        if (!f.exists()) return null
        val dto = runCatching { json.decodeFromString(CellDto.serializer(), f.readText()) }.getOrNull()
        if (dto == null || dto.v != VERSION) {
            f.delete()
            return null
        }
        val lines = dto.lines.mapNotNull { l ->
            val kind = runCatching { Transitous.Kind.valueOf(l.k) }.getOrNull() ?: return@mapNotNull null
            val pts = runCatching { PolylineCodec.decode(l.p, PRECISION) }.getOrNull()?.takeIf { it.size >= 2 } ?: return@mapNotNull null
            Transitous.MapLine(pts, l.c, kind, l.n.zip(l.m))
        }
        return Cell(lines, dto.at)
    }

    /** Keeps [lines] under [key] and returns the cell as [read] would. A cell that cannot be
     *  written is still returned, so the caller shows it for this run. */
    @Synchronized
    fun write(key: String, lines: List<Transitous.MapLine>): Cell {
        val at = now()
        val dtos = lines.map { l -> LineDto(PolylineCodec.encode(l.points, PRECISION), l.kind.name, l.colors, l.labels.map { it.first }, l.labels.map { it.second }) }
        val kept = dtos.zip(lines).mapNotNull { (d, l) ->
            val pts = PolylineCodec.decode(d.p, PRECISION).takeIf { it.size >= 2 } ?: return@mapNotNull null
            l.copy(points = pts)
        }
        runCatching {
            val dir = dirOf().apply { mkdirs() }
            // Written beside its target and renamed: a cut-off write never leaves half a cell.
            val tmp = File(dir, fileOf(key).name + ".tmp")
            tmp.writeText(json.encodeToString(CellDto.serializer(), CellDto(VERSION, at, dtos)))
            val target = fileOf(key)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
            prune(dir)
        }
        return Cell(kept, at)
    }

    @Synchronized
    fun clear() {
        dirOf().listFiles()?.forEach { it.delete() }
    }

    private fun fileOf(key: String) =
        File(dirOf(), key.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-') it else '_' }.joinToString("") + ".json")

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { i, f ->
            bytes += f.length()
            if (i >= MAX_CELLS || bytes > MAX_BYTES) f.delete()
        }
    }

    companion object {
        const val FRESH_MS = 7L * 24 * 3600 * 1000
        const val MAX_CELLS = 300
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val VERSION = 1
        private const val PRECISION = 6
    }
}
