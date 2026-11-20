package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a car pays on Highway 407 ETR near Toronto, as a range.
 *
 * The toll is per kilometer and set by 12 zones, by direction and by time band, plus a $1 trip toll
 * and, without a transponder, a $5.30 camera charge per trip (rate chart in force from 1 January
 * 2026). Which zone each kilometer lies in needs the zone boundaries, and the exits in the map data
 * carry only numbers, so the boundaries are not held. Instead the range runs from the cheapest zone's
 * rate to the dearest zone's rate for that direction and time band, over the kilometers driven on the
 * road. It is wide at rush hour and narrow at night.
 *
 * The rate is the one at the time of entry. A statutory holiday on a weekday is priced as a weekday,
 * since no holiday list is held. Heavy vehicles and motorcycles pay other rates and are not modeled.
 */
object Ontario407 {
    private const val TRIP_TOLL_CENTS = 100
    private const val CAMERA_CHARGE_CENTS = 530

    /** The route is sampled this often, and a sample is on the road when a line of the carriageway
     *  going the same way is within [ON_M] of it. */
    private const val STEP_M = 50.0
    private const val ON_M = 25.0
    private const val HEADING_TOLERANCE_DEG = 60.0

    /** A run holds across a gap this long: an interchange can drop a sample or two. */
    private const val GAP_M = 300.0

    /** A shorter stretch than this is a road passing over or under the 407, not a trip on it. */
    private const val MIN_RUN_M = 1_000.0

    /** The 407's box with a margin. A route with no point in it pays nothing here. */
    private fun nearRoad(p: LatLng): Boolean = p.lat in 43.30..44.06 && p.lng in -79.88..-78.58

    /** The extreme rates, in cents per kilometer, for a car entering at [t]. */
    internal fun rateRange(eastbound: Boolean, t: ZonedDateTime): Pair<Double, Double> {
        val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
        val bands = Ontario407Data.RATES.getValue(if (eastbound) "EB" else "WB").getValue(if (weekend) "weekend" else "weekday")
        val minute = t.hour * 60 + t.minute
        val band = bands.lastOrNull { it.fromMinute <= minute } ?: bands.last()
        return band.minCentsPerKm to band.maxCentsPerKm
    }

    /** A trip on the road: where it starts along the route, how long it is, and which way. */
    internal class Run(val startM: Double, val lengthM: Double, val eastbound: Boolean)

    private class Seg(val a: LatLng, val b: LatLng, val heading: Double) {
        val minLat = minOf(a.lat, b.lat) - BOX; val maxLat = maxOf(a.lat, b.lat) + BOX
        val minLng = minOf(a.lng, b.lng) - BOX; val maxLng = maxOf(a.lng, b.lng) + BOX
    }

    private const val BOX = 0.0004 // about 35 m of latitude

    private val segs: List<Seg> by lazy {
        Ontario407Data.ways.flatMap { w -> (0 until w.size - 1).map { Seg(w[it], w[it + 1], headingDeg(w[it], w[it + 1])) } }
    }

    private fun headingDeg(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun distanceToSegM(p: LatLng, s: Seg): Double {
        val mLng = 111_320.0 * cos(Math.toRadians(p.lat))
        val bx = (s.b.lng - s.a.lng) * mLng; val by = (s.b.lat - s.a.lat) * 111_320.0
        val px = (p.lng - s.a.lng) * mLng; val py = (p.lat - s.a.lat) * 111_320.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    private fun onRoad(p: LatLng, heading: Double): Boolean = segs.any { s ->
        p.lat in s.minLat..s.maxLat && p.lng in s.minLng..s.maxLng &&
            angleBetween(heading, s.heading) <= HEADING_TOLERANCE_DEG && distanceToSegM(p, s) <= ON_M
    }

    /** The stretches of [route] driven on the road, in order. */
    internal fun runs(route: List<LatLng>, cum: DoubleArray): List<Run> {
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
            if (nearRoad(p) && onRoad(p, RouteProjection.bearingAt(route, cum, s))) {
                if (start < 0.0) start = s else if (s - last > GAP_M) { close(); start = s }
                last = s
            } else if (start >= 0.0 && s - last > GAP_M) close()
            s += STEP_M
        }
        close()
        return out
    }

    /**
     * The 407 toll on [route] as one [TollCharge], or none. [TollCharge.cents] and [TollCharge.maxCents]
     * are the cheapest and dearest estimates with the $1 trip toll of each trip, and
     * [TollCharge.feeCents] the $5.30 camera charge of each trip for a vehicle without a transponder.
     * Each trip is priced at the time it enters the road: [nowMs] plus its share of [durationS].
     */
    fun charges(route: List<LatLng>, nowMs: Long, durationS: Double, zone: ZoneId = Tolls.ZONE): List<TollCharge> {
        if (route.size < 2 || route.none { nearRoad(it) }) return emptyList()
        val cum = RouteProjection.cumulative(route)
        val total = cum.last()
        if (total <= 0.0) return emptyList()
        val runs = runs(route, cum)
        if (runs.isEmpty()) return emptyList()
        var low = 0
        var high = 0
        for (r in runs) {
            val entry = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs + (r.startM / total * durationS * 1000.0).toLong()), zone)
            val (min, max) = rateRange(r.eastbound, entry)
            val km = r.lengthM / 1000.0
            low += (km * min).roundToInt() + TRIP_TOLL_CENTS
            high += (km * max).roundToInt() + TRIP_TOLL_CENTS
        }
        return listOf(TollCharge("407 ETR", low, runs.size * CAMERA_CHARGE_CENTS, high))
    }
}
