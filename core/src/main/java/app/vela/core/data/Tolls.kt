package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A toll a route pays: [cents] to [maxCents] for a two-axle car, and [feeCents] more for a driver
 *  with no transponder. */
data class TollCharge(val name: String, val cents: Int, val feeCents: Int, val maxCents: Int = cents)

/**
 * What a car pays on the toll roads in the toll file (`tolls.json`, SPEC 7, Toll prices), built by
 * `scripts/build-tolls.py` and downloaded without an app release.
 *
 * A road is priced by the kilometer. Its rate depends on the direction, the kind of day and the time
 * band, and is held as the cheapest and dearest zone's rate, so the price is a range: the kilometers
 * driven on the road at each, plus the trip toll of each trip, with the per-trip fee for a driver
 * without a transponder beside it. A trip is priced at the time it reaches the road, from the rate
 * period in force on that day. A road with no period in force that day shows no price, never an old
 * one. Saturday and Sunday are the weekend; a weekday holiday is priced as a weekday.
 */
object Tolls {
    @Volatile private var roads: List<Road> = emptyList()

    /** The roads loaded, for the diagnostics. */
    val roadCount: Int get() = roads.size

    /** Replaces the loaded roads with those in [json]. False, keeping what is loaded, when the file
     *  cannot be read. A road of a kind or currency this build does not price is left out. */
    fun load(json: String): Boolean {
        val parsed = runCatching {
            Json.parseToJsonElement(json).jsonObject.getValue("roads").jsonArray.mapNotNull { Road.parse(it.jsonObject) }
        }.getOrNull() ?: return false
        roads = parsed
        return true
    }

    /** The tolls a car pays on [route] leaving at [nowMs] on a drive of [durationS]. */
    fun charges(route: List<LatLng>, nowMs: Long, durationS: Double): List<TollCharge> {
        val held = roads
        if (route.size < 2 || held.isEmpty()) return emptyList()
        val near = held.filter { r -> route.any { r.inBox(it) } }
        if (near.isEmpty()) return emptyList()
        val cum = RouteProjection.cumulative(route)
        val total = cum.last()
        if (total <= 0.0) return emptyList()
        return near.mapNotNull { it.charge(route, cum, nowMs, durationS) }
    }

    internal class Band(val fromMinute: Int, val minCentsPerKm: Double, val maxCentsPerKm: Double)

    internal class Period(
        val effective: LocalDate,
        val validUntil: LocalDate,
        val tripCents: Int,
        val feeCents: Int,
        /** Keyed by direction ("east", "west" or "any"), then "weekday" or "weekend". */
        val bands: Map<String, Map<String, List<Band>>>,
    ) {
        /** The cheapest and dearest rate, in cents per kilometer, for a car entering at [t]. */
        fun rateRange(eastbound: Boolean, t: ZonedDateTime): Pair<Double, Double>? {
            val byDay = bands[if (eastbound) "east" else "west"] ?: bands["any"] ?: return null
            val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
            val list = byDay[if (weekend) "weekend" else "weekday"]?.takeIf { it.isNotEmpty() } ?: return null
            val minute = t.hour * 60 + t.minute
            // Before the day's first band is the last one's overnight.
            val band = list.lastOrNull { it.fromMinute <= minute } ?: list.last()
            return band.minCentsPerKm to band.maxCentsPerKm
        }
    }

    /** A trip on a road: where it starts along the route, how long it is, and which way. */
    internal class Run(val startM: Double, val lengthM: Double, val eastbound: Boolean)

    internal class Road(
        val id: String,
        val name: String,
        val zone: ZoneId,
        private val box: DoubleArray,
        lines: List<List<LatLng>>,
        val periods: List<Period>,
    ) {
        private class Seg(val a: LatLng, val b: LatLng, val heading: Double)

        /** The road's pieces, by the grid cell their box touches. */
        private val grid: Map<Long, List<Seg>> = HashMap<Long, MutableList<Seg>>().also { g ->
            for (w in lines) for (i in 0 until w.size - 1) {
                val s = Seg(w[i], w[i + 1], headingDeg(w[i], w[i + 1]))
                for (r in cell(minOf(s.a.lat, s.b.lat) - PAD)..cell(maxOf(s.a.lat, s.b.lat) + PAD))
                    for (c in cell(minOf(s.a.lng, s.b.lng) - PAD)..cell(maxOf(s.a.lng, s.b.lng) + PAD))
                        g.getOrPut(key(r, c)) { ArrayList() }.add(s)
            }
        }

        fun inBox(p: LatLng): Boolean = p.lat in box[0]..box[2] && p.lng in box[1]..box[3]

        fun period(day: LocalDate): Period? = periods.lastOrNull { !day.isBefore(it.effective) && !day.isAfter(it.validUntil) }

        private fun onRoad(p: LatLng, heading: Double): Boolean =
            grid[key(cell(p.lat), cell(p.lng))]?.any { s ->
                angleBetween(heading, s.heading) <= HEADING_TOLERANCE_DEG && distanceToSegM(p, s.a, s.b) <= ON_M
            } == true

        /** The stretches of [route] driven on the road, in order. */
        fun runs(route: List<LatLng>, cum: DoubleArray): List<Run> {
            val total = cum.last()
            val out = ArrayList<Run>()
            var start = -1.0
            var last = -1.0
            fun close() {
                if (start >= 0.0 && last - start + STEP_M >= MIN_RUN_M) {
                    val a = RouteProjection.pointAt(route, cum, start)
                    val b = RouteProjection.pointAt(route, cum, last)
                    out += Run(start, last - start + STEP_M, b.lng > a.lng)
                }
                start = -1.0
            }
            var s = 0.0
            while (s <= total) {
                val p = RouteProjection.pointAt(route, cum, s)
                if (inBox(p) && onRoad(p, RouteProjection.bearingAt(route, cum, s))) {
                    if (start < 0.0) start = s else if (s - last > GAP_M) { close(); start = s }
                    last = s
                } else if (start >= 0.0 && s - last > GAP_M) close()
                s += STEP_M
            }
            close()
            return out
        }

        /** The road's toll on [route], or null when the route is not on it or a trip on it falls on
         *  a day no period covers. */
        fun charge(route: List<LatLng>, cum: DoubleArray, nowMs: Long, durationS: Double): TollCharge? {
            val runs = runs(route, cum)
            if (runs.isEmpty()) return null
            val total = cum.last()
            var low = 0
            var high = 0
            var fee = 0
            for (r in runs) {
                val entry = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs + (r.startM / total * durationS * 1000.0).toLong()), zone)
                val p = period(entry.toLocalDate()) ?: return null
                val (min, max) = p.rateRange(r.eastbound, entry) ?: return null
                val km = r.lengthM / 1000.0
                low += (km * min).roundToInt() + p.tripCents
                high += (km * max).roundToInt() + p.tripCents
                fee += p.feeCents
            }
            return TollCharge(name, low, fee, high)
        }

        companion object {
            fun parse(o: JsonObject): Road? = runCatching {
                if (o.str("kind") != "distance" || o.str("currency") != "CAD") return null
                val box = o.getValue("box").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
                require(box.size == 4)
                val lines = o.getValue("lines").jsonArray.map { l ->
                    l.jsonPrimitive.content.split(' ').map { p -> p.split(',').let { LatLng(it[0].toDouble(), it[1].toDouble()) } }
                }
                val periods = o.getValue("periods").jsonArray.map { pe ->
                    val p = pe.jsonObject
                    Period(
                        LocalDate.parse(p.str("effective")),
                        LocalDate.parse(p.str("valid_until")),
                        p.getValue("trip_cents").jsonPrimitive.int,
                        p.getValue("fee_cents").jsonPrimitive.int,
                        p.getValue("bands").jsonObject.mapValues { (_, byDay) ->
                            byDay.jsonObject.mapValues { (_, list) ->
                                list.jsonArray.map { b ->
                                    val a = b as JsonArray
                                    Band(a[0].jsonPrimitive.int, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double)
                                }
                            }
                        },
                    )
                }.sortedBy { it.effective }
                Road(o.str("id"), o.str("name"), ZoneId.of(o.str("zone")), box, lines, periods)
            }.getOrNull()

            private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
        }
    }

    /** The route is sampled this often, and a sample is on the road when a piece of it going the
     *  same way is within [ON_M]. */
    private const val STEP_M = 50.0
    private const val ON_M = 25.0
    private const val HEADING_TOLERANCE_DEG = 60.0

    /** A run holds across a gap this long: an interchange can drop a sample or two. */
    private const val GAP_M = 300.0

    /** A shorter stretch than this is a road passing over or under, not a trip on it. */
    private const val MIN_RUN_M = 1_000.0

    /** The grid the pieces are filed in, in degrees, and the margin each piece's box gets (about
     *  35 m of latitude, more than [ON_M]). */
    private const val CELL_DEG = 0.01
    private const val PAD = 0.0004

    private fun cell(v: Double): Long = floor(v / CELL_DEG).toLong()
    private fun key(row: Long, col: Long): Long = (row shl 32) xor (col and 0xffffffffL)

    private fun headingDeg(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun distanceToSegM(p: LatLng, a: LatLng, b: LatLng): Double {
        val mLng = 111_320.0 * cos(Math.toRadians(p.lat))
        val bx = (b.lng - a.lng) * mLng; val by = (b.lat - a.lat) * 111_320.0
        val px = (p.lng - a.lng) * mLng; val py = (p.lat - a.lat) * 111_320.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    /** The loaded road [id], for tests. */
    internal fun road(id: String): Road? = roads.firstOrNull { it.id == id }
}
