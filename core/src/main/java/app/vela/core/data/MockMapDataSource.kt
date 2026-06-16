package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode
import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

/**
 * Deterministic fake data so the entire app — search, place sheet, route
 * preview, turn-by-turn, voice — is exercisable with no network and before any
 * Google calibration exists. Everything is positioned relative to the caller's
 * location (or San Francisco as a fallback) so it looks right wherever you are.
 */
@Singleton
class MockMapDataSource @Inject constructor() : MapDataSource {

    override suspend fun search(query: String, near: LatLng?): SearchResult {
        delay(150) // pretend it's a network call
        val center = near ?: SF
        val seeds = if (query.isBlank()) SAMPLES else listOf(query to "search result") + SAMPLES
        val places = seeds.mapIndexed { i, (name, category) ->
            val loc = center.offset(metersN = (i - 3) * 180.0, metersE = ((i * 5) % 7 - 3) * 200.0)
            Place(
                id = "mock:$i",
                name = name.replaceFirstChar { it.uppercase() },
                location = loc,
                category = category,
                address = "${120 + i * 11} Market St",
                rating = 3.6 + (i % 6) * 0.22,
                reviewCount = 31 + i * 47,
                priceLevel = (i % 4) + 1,
                phone = "+1 415-555-0${100 + i}",
                website = "https://example.com",
                openNow = i % 5 != 0,
                hours = listOf("Mon–Fri 8:00–20:00", "Sat–Sun 9:00–18:00"),
                distanceMeters = center.distanceTo(loc),
            )
        }
        return SearchResult(query, places.sortedBy { it.distanceMeters })
    }

    override suspend fun placeDetails(id: String): Place {
        delay(80)
        return search("", null).places.firstOrNull { it.id == id }
            ?: search("", null).places.first()
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
    ): List<Route> {
        delay(220)
        // A simple L-shaped path: straight, one turn, arrive. Enough geometry
        // for the nav engine to follow and the banner/voice to narrate.
        val corner = LatLng(destination.lat, origin.lng)
        val poly = interpolate(origin, corner) + interpolate(corner, destination).drop(1)

        val leg1 = origin.distanceTo(corner)
        val leg2 = corner.distanceTo(destination)
        val speed = if (mode == TravelMode.WALK) 1.4 else 13.4 // m/s
        val turn = if (origin.bearingTo(corner) < destination.let { corner.bearingTo(it) })
            ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT

        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, "Head toward your destination on Market Street",
                origin, leg1, leg1 / speed, road = "Market Street"),
            Maneuver(turn, if (turn == ManeuverType.TURN_RIGHT) "Turn right onto 2nd Street"
                else "Turn left onto 2nd Street", corner, leg2, leg2 / speed, road = "2nd Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive at your destination", destination, 0.0, 0.0),
        )
        val dist = leg1 + leg2
        val dur = dist / speed
        return listOf(
            Route(
                polyline = poly,
                legs = listOf(RouteLeg(dist, dur, dur * 1.22, maneuvers)),
                distanceMeters = dist,
                durationSeconds = dur,
                durationInTrafficSeconds = dur * 1.22, // shows the traffic-aware ETA path
                summary = "Market St",
            ),
        )
    }

    private fun interpolate(a: LatLng, b: LatLng, n: Int = 6): List<LatLng> =
        (0..n).map { t ->
            val f = t.toDouble() / n
            LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
        }

    private fun LatLng.offset(metersN: Double, metersE: Double): LatLng {
        val dLat = metersN / 111_320.0
        val dLng = metersE / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return LatLng(lat + dLat, lng + dLng)
    }

    private companion object {
        val SF = LatLng(37.7749, -122.4194)
        val SAMPLES = listOf(
            "Blue Bottle Coffee" to "Coffee shop",
            "Tartine Bakery" to "Bakery",
            "Ferry Building Marketplace" to "Market",
            "Dolores Park" to "Park",
            "SFMOMA" to "Museum",
            "Philz Coffee" to "Coffee shop",
            "Zuni Café" to "Restaurant",
        )
    }
}
