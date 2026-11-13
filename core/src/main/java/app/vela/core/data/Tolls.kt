package app.vela.core.data

import app.vela.core.model.LatLng
import java.time.ZoneId

/** A toll a route pays: [cents] for a two-axle car, and [feeCents] added for a crossing by a driver
 *  with no transponder and no account. A toll that cannot be priced to the cent is a range from [cents]
 *  to [maxCents]. */
data class TollCharge(val name: String, val cents: Int, val feeCents: Int, val maxCents: Int = cents)

/** The tolls a car pays on a route, from every table held: Quebec's two bridges ([QuebecTolls]) and
 *  Ontario's Highway 407 ETR ([Ontario407]). */
object Tolls {
    /** The clock the rate tables are read on. Both regions held keep Eastern time. */
    val ZONE: ZoneId = ZoneId.of("America/Toronto")

    fun charges(route: List<LatLng>, nowMs: Long, durationS: Double, zone: ZoneId = ZONE): List<TollCharge> =
        QuebecTolls.charges(route, nowMs, durationS, zone) + Ontario407.charges(route, nowMs, durationS, zone)
}
