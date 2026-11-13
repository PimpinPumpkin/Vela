package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteOverlap
import app.vela.core.nav.RouteProjection
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The two toll crossings of Quebec and what a car pays on them. Google's reply says a route has
 * tolls and gives no price, so the price comes from the operators' published grids. The bridges
 * are matched to the route by their line ([QuebecTollBridges]), in either direction: both are
 * charged both ways.
 *
 * Rates, for a car (category B, under 230 cm, two axles). They change each year, so they carry the
 * date of the grid they came from:
 * - A-25, Olivier-Charbonneau bridge (grid of 1 June 2025): $2.06 an axle, $4.12 for the car, in the
 *   morning and evening peaks of a working day, $1.65 an axle, $3.30, at other times. A vehicle with
 *   no account pays an administration fee of $6.50 on the first request for payment.
 * - A-30, St. Lawrence bridge (grid of 28 February 2026): $2.20 an axle, $4.40, at every hour. The
 *   grid names no fee for a crossing, only collection fees for one that is not paid.
 *
 * Electric vehicles do not pay on either. A truck, a car with a trailer or a vehicle over 230 cm
 * pays more, and the table does not cover it.
 */
object QuebecTolls {
    /** The A-25 car toll at [t]: the peaks are 06:01 to 09:00 and 15:01 to 18:00 on a working day. A
     *  public holiday on a weekday is priced as a working day, since no holiday list is held. */
    internal fun a25Cents(t: ZonedDateTime): Int {
        val workingDay = t.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val minute = t.hour * 60 + t.minute
        val peak = workingDay && (minute in 361..540 || minute in 901..1080)
        return if (peak) 412 else 330
    }

    internal const val A30_CENTS = 440
    private const val A25_NO_ACCOUNT_FEE_CENTS = 650

    private class Facility(val name: String, val bridge: List<List<LatLng>>, val cents: (ZonedDateTime) -> Int, val feeCents: Int)

    private val facilities = listOf(
        Facility("A-25", QuebecTollBridges.A25, ::a25Cents, A25_NO_ACCOUNT_FEE_CENTS),
        Facility("A-30", QuebecTollBridges.A30, { A30_CENTS }, 0),
    )

    /** Montreal's two toll areas with a margin. A route with no point in it pays nothing here. */
    private fun nearToll(p: LatLng): Boolean = p.lat in 45.27..45.67 && p.lng in -74.06..-73.56

    /**
     * The tolls a car pays on [route], in the order of the table. The price is the one at the time
     * of the crossing, taken as [nowMs] plus the share of [durationS] that the crossing's distance
     * is of the whole route. Empty for a route that crosses neither bridge.
     */
    fun charges(route: List<LatLng>, nowMs: Long, durationS: Double, zone: ZoneId = Tolls.ZONE): List<TollCharge> {
        if (route.size < 2 || route.none { nearToll(it) }) return emptyList()
        val cum = RouteProjection.cumulative(route)
        val total = cum.last()
        if (total <= 0.0) return emptyList()
        return facilities.mapNotNull { f ->
            val at = f.bridge.mapNotNull { RouteOverlap.runsAlong(route, cum, it)?.first }.minOrNull() ?: return@mapNotNull null
            val crossingMs = nowMs + (at / total * durationS * 1000.0).toLong()
            TollCharge(f.name, f.cents(ZonedDateTime.ofInstant(Instant.ofEpochMilli(crossingMs), zone)), f.feeCents)
        }
    }
}
