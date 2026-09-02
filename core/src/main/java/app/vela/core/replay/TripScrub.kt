package app.vela.core.replay

import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Make a recorded trip safe to publish, by removing its ends rather than blurring them.
 *
 * A trip CSV is raw GPS, and its first and last fixes are almost always the two places their
 * owner would least like online — home, work, a friend's house. The MIDDLE of the drive is where
 * a nav bug lives, and that part is just roads.
 *
 * So this **trims**: every fix within [radiusM] of the start, the end, the recorded destination
 * and any [extraZones] is deleted outright, and everything between is left at FULL precision.
 * Rounding coordinates instead would protect the endpoints only weakly (a 1 km round still names
 * a block) while destroying the geometry the trip was recorded to diagnose. Trimming gives up
 * nothing that matters and gives away nothing that does.
 *
 * The other lines leak the same fact in other clothes, and all of them are handled:
 * - the `META` **destination** is on a drive home exactly the address being protected, and can sit
 *   outside the radius around the last FIX (recording stops at the curb, or early);
 * - the `META` **label** is the destination's own name — trips are named after where they went,
 *   so it is frequently a street address in plain text;
 * - the route polyline starts at the driveway;
 * - maneuvers carry a position AND an instruction that names the street it is on;
 * - `S` lines are what the voice actually SPOKE, which includes "Arrive at …" and the turns onto
 *   the home street.
 *
 * Timestamps are rebased so the first surviving fix is at zero. Replay only ever uses the
 * *differences* between them, so this costs nothing and stops a published file from saying
 * exactly when its author drives.
 *
 * Unknown line kinds are DROPPED, not passed through. The format is deliberately append-only, so
 * a tag added later would otherwise be published by a scrubber written before it existed. If you
 * add a line kind to [TripLog], decide here whether it is safe to share.
 */
object TripScrub {

    /** Metres trimmed around each private place by default. */
    const val DEFAULT_RADIUS_M = 400.0

    /** What a scrub did, so it can be shown before anything leaves the device. */
    data class Report(
        val csv: String,
        val fixesBefore: Int,
        val fixesAfter: Int,
        val trimmedStartM: Double,
        val trimmedEndM: Double,
        /** The first fix that SURVIVED — the thing to eyeball before publishing. */
        val firstRemaining: LatLng?,
        val maneuversDropped: Int,
        val spokenDropped: Int,
        val routeTrimmed: Boolean,
        val otherLinesDropped: Int,
    ) {
        val fixesRemoved: Int get() = fixesBefore - fixesAfter
    }

    /**
     * Scrub [csv]. Returns null when the trip has no fixes at all, or when *every* fix falls
     * inside a trim zone — a drive shorter than the radius has no shareable middle, and quietly
     * emitting an empty file would be the wrong answer to that.
     */
    fun scrub(
        csv: String,
        radiusM: Double = DEFAULT_RADIUS_M,
        extraZones: List<LatLng> = emptyList(),
        label: String = "Shared trip",
    ): Report? {
        val lines = csv.split('\n').filter { it.isNotBlank() }
        val fixes = lines.mapNotNull { line -> parseFix(line)?.let { line to it } }
        if (fixes.isEmpty()) return null

        val parsed = TripLog.parse(csv)
        val zones = buildList {
            add(fixes.first().second.latLng)
            add(fixes.last().second.latLng)
            parsed.dest?.let { add(it) }
            addAll(extraZones)
        }
        fun private(p: LatLng) = zones.any { haversine(p, it) <= radiusM }

        val kept = fixes.filterNot { private(it.second.latLng) }
        if (kept.isEmpty()) return null

        // Everything is expressed against the first SURVIVING fix, so the published file carries
        // no absolute wall-clock at all.
        val tZero = kept.first().second.t
        val keptFrom = kept.first().second.t
        val keptTo = kept.last().second.t

        var maneuversDropped = 0
        var spokenDropped = 0
        var routeTrimmed = false
        var otherDropped = 0
        val out = StringBuilder()

        for (line in lines) {
            val fix = parseFix(line)
            if (fix != null) {
                if (private(fix.latLng)) continue
                out.append(rebaseFix(line, tZero)).append('\n')
                continue
            }
            when (line.substringBefore(',')) {
                "META" -> {
                    // Keep the start time slot (rebased to 0) and the version code — which build
                    // recorded a trip is the single most useful thing about it for diagnosis —
                    // and drop the label and the destination, which are both the address.
                    val f = line.split(',')
                    val version = f.getOrNull(5).orEmpty()
                    val safeLabel = label.replace(',', ' ').replace('\n', ' ').take(80).ifBlank { "Shared trip" }
                    out.append("META,$safeLabel,0,,,$version\n")
                }
                "RP" -> {
                    // The route line starts at the driveway. Keep its longest run of vertices that
                    // is entirely outside every zone, so what is left is contiguous rather than a
                    // line that teleports across a removed section.
                    val poly = runCatching { PolylineCodec.decode(line.substring(3)) }.getOrDefault(emptyList())
                    val run = longestPublicRun(poly, ::private)
                    if (run.size != poly.size) routeTrimmed = true
                    if (run.size >= 2) out.append("RP,").append(PolylineCodec.encode(run)).append('\n')
                }
                // Distance and duration totals identify nothing on their own and are needed to
                // make sense of the trace.
                "RD" -> out.append(line).append('\n')
                "M" -> {
                    val f = line.split(',', limit = 6)
                    val lat = f.getOrNull(2)?.toDoubleOrNull()
                    val lng = f.getOrNull(3)?.toDoubleOrNull()
                    if (lat == null || lng == null || private(LatLng(lat, lng))) maneuversDropped++
                    else out.append(line).append('\n')
                }
                "S" -> {
                    // Spoken lines outside the surviving window are the ones that name the
                    // destination and the streets at either end.
                    val t = line.split(',').getOrNull(1)?.toLongOrNull()
                    if (t == null || t < keptFrom || t > keptTo) spokenDropped++
                    else out.append(rebaseEvent(line, tZero)).append('\n')
                }
                // Frame pacing and battery carry no position.
                "J", "B" -> {
                    val t = line.split(',').getOrNull(1)?.toLongOrNull()
                    if (t == null || t < keptFrom || t > keptTo) otherDropped++
                    else out.append(rebaseEvent(line, tZero)).append('\n')
                }
                else -> otherDropped++
            }
        }

        return Report(
            csv = out.toString(),
            fixesBefore = fixes.size,
            fixesAfter = kept.size,
            trimmedStartM = haversine(fixes.first().second.latLng, kept.first().second.latLng),
            trimmedEndM = haversine(fixes.last().second.latLng, kept.last().second.latLng),
            firstRemaining = kept.first().second.latLng,
            maneuversDropped = maneuversDropped,
            spokenDropped = spokenDropped,
            routeTrimmed = routeTrimmed,
            otherLinesDropped = otherDropped,
        )
    }

    /** The longest contiguous run of vertices with no private one in it. */
    private fun longestPublicRun(poly: List<LatLng>, private: (LatLng) -> Boolean): List<LatLng> {
        var bestStart = 0
        var bestLen = 0
        var start = 0
        var len = 0
        for (i in poly.indices) {
            if (private(poly[i])) {
                start = i + 1
                len = 0
            } else {
                len++
                if (len > bestLen) { bestLen = len; bestStart = start }
            }
        }
        return if (bestLen <= 0) emptyList() else poly.subList(bestStart, bestStart + bestLen)
    }

    private fun parseFix(line: String): TripLog.Point? {
        val p = line.split(',')
        if (p.size < 5) return null
        val lat = p[0].toDoubleOrNull() ?: return null
        val lng = p[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return TripLog.Point(
            lat, lng, p[2].toLongOrNull() ?: 0L, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f,
            offRoute = p.getOrNull(5) == "1",
            accuracyM = p.getOrNull(6)?.toFloatOrNull(),
        )
    }

    /** A fix line with field 2 (its timestamp) rebased; every other field is untouched. */
    private fun rebaseFix(line: String, tZero: Long): String {
        val f = line.split(',').toMutableList()
        f[2] = ((f[2].toLongOrNull() ?: 0L) - tZero).toString()
        return f.joinToString(",")
    }

    /** An event line (`S`/`J`/`B`) with field 1 (its timestamp) rebased. The text may hold
     *  commas, so only the first two fields are touched. */
    private fun rebaseEvent(line: String, tZero: Long): String {
        val f = line.split(',', limit = 3)
        if (f.size < 2) return line
        val t = ((f[1].toLongOrNull() ?: 0L) - tZero).toString()
        return if (f.size == 2) "${f[0]},$t" else "${f[0]},$t,${f[2]}"
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
