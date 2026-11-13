package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.nav.RouteProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/**
 * What each place found along a route costs the trip: the extra driving time through it and the
 * road distance to reach it.
 *
 * [RouteCorridor.alongRoute] keeps places within 3 km of the line and can only measure that gap
 * as a straight line. A station 400 m beside a motorway with no exit near it reads as close and
 * is a 15 minute detour. The open router knows the road network, so one `table` request prices
 * every candidate.
 *
 * The detour is measured against the stretch of the route the place sits on. Two anchors on the
 * route, [ANCHOR_M] before and after the place's nearest point, bracket it. The detour is the
 * time from the first anchor to the place plus the time from the place to the second anchor,
 * minus the time between the anchors. All three come from the same router, so its free-flow
 * speeds cancel. Measuring against the destination instead would compare the place with the
 * router's own choice of road for the whole trip. Where that road is not the one the route
 * takes, every place on the route would look like a detour and every place on the router's road
 * would look free.
 *
 * The request carries the car's position, points on the route and the candidates' positions,
 * the same kind of data a route request already sends the open router. Nothing goes to Google.
 */
object AlongRouteDetour {
    /** Most places priced in one request, which is 3n + 1 points. Thirty covers a results list
     *  and keeps the request small for a shared community server. Places past this keep their
     *  place in the list, unpriced, after the priced ones. */
    const val MAX_PLACES = 30

    /** How far before and after a place, along the route, its two anchors sit. */
    const val ANCHOR_M = 3_000.0

    private const val TRIES = 2
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The router's answer for [tableUrl], in seconds ([durations]) and meters ([distances]).
     * For n places a row is the car, then each place's first anchor, then each place. A column is
     * each place, then each place's second anchor. An unreachable pair is null.
     */
    class Table(val durations: List<List<Double?>>, val distances: List<List<Double?>>)

    /** The anchors of each of [places]: [ANCHOR_M] back and forward along [route] from the
     *  nearest point, held to the ends of the route. */
    internal fun anchors(route: List<LatLng>, places: List<Place>): Pair<List<LatLng>, List<LatLng>> {
        val cum = RouteProjection.cumulative(route)
        // No limit on the distance off the line: the corridor has already kept the place.
        val along = places.map { RouteProjection.alongMeters(route, cum, it.location, Double.MAX_VALUE) ?: 0.0 }
        return along.map { RouteProjection.pointAt(route, cum, it - ANCHOR_M) } to
            along.map { RouteProjection.pointAt(route, cum, it + ANCHOR_M) }
    }

    /** The `table` request. Points: the car, the n first anchors, the n places, the n second
     *  anchors. Sources: the car, first anchors and places. Destinations: places and second
     *  anchors. That is every pair [price] reads and no others. */
    internal fun tableUrl(car: LatLng, before: List<LatLng>, places: List<Place>, after: List<LatLng>): String {
        val n = places.size
        val points = listOf(car) + before + places.map { it.location } + after
        return "${RouteGeometry.OSRM_BASE}/routed-car/table/v1/driving/" +
            points.joinToString(";") { "${it.lng},${it.lat}" } +
            "?sources=${(0..2 * n).joinToString(";")}&destinations=${(n + 1..3 * n).joinToString(";")}" +
            "&annotations=duration,distance"
    }

    /** The matrices in [body], or null when the router did not say `Ok` or sent no matrices. */
    internal fun parseTable(body: String): Table? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["code"]?.jsonPrimitive?.content != "Ok") return null
        fun matrix(key: String): List<List<Double?>>? = root[key]?.jsonArray?.map { row ->
            row.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }
        }
        return Table(matrix("durations") ?: return null, matrix("distances") ?: return null)
    }

    /**
     * [places] with [Place.detourSeconds] and the road distance from the car filled in, best
     * first, or null when [table] does not fit them. Only the first [MAX_PLACES] are priced.
     */
    internal fun price(places: List<Place>, table: Table): List<Place>? {
        val priceable = places.take(MAX_PLACES)
        val n = priceable.size
        if (table.durations.size != 2 * n + 1 || table.durations.any { it.size != 2 * n }) return null
        if (table.distances.size != 2 * n + 1 || table.distances.any { it.size != 2 * n }) return null
        val priced = priceable.mapIndexed { i, p ->
            val out = table.durations[1 + i][i]            // first anchor to the place
            val back = table.durations[1 + n + i][n + i]   // the place to the second anchor
            val between = table.durations[1 + i][n + i]    // first anchor to second anchor
            if (out == null || back == null || between == null) p
            else p.copy(
                // Rounding can leave a place on the line a second under the time between anchors.
                detourSeconds = (out + back - between).coerceAtLeast(0.0).roundToInt(),
                distanceMeters = table.distances[0][i] ?: p.distanceMeters,
            )
        }
        return rank(priced) + places.drop(MAX_PLACES)
    }

    /**
     * Priced places first, least detour first. Detours are compared in whole minutes, because the
     * router's seconds are not that exact: inside one minute the better rated place goes first,
     * then the nearer one. Places with no price keep their order, after the priced ones.
     */
    internal fun rank(places: List<Place>): List<Place> {
        val (priced, unpriced) = places.partition { it.detourSeconds != null }
        return priced.sortedWith(
            compareBy<Place> { (it.detourSeconds!! / 60.0).roundToInt() }
                .thenByDescending { it.rating ?: -1.0 }
                .thenBy { it.distanceMeters ?: Double.MAX_VALUE },
        ) + unpriced
    }

    /**
     * [places] priced and ranked by one `table` request, or null when the open router does not
     * answer (the caller keeps the list it has). [route] is the road ahead of the car, whose
     * first point is the car. Blocking: call it off the main thread.
     */
    fun fetch(http: OkHttpClient, route: List<LatLng>, places: List<Place>): List<Place>? {
        if (route.size < 2 || places.isEmpty()) return null
        val use = places.take(MAX_PLACES)
        val (before, after) = anchors(route, use)
        val req = Request.Builder()
            .url(tableUrl(route.first(), before, use, after))
            .header("User-Agent", VelaConfig.VELA_UA)
            .build()
        repeat(TRIES) { attempt ->
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return parseTable(resp.body?.string().orEmpty())?.let { price(places, it) }
                    // A 4xx will not change on a second try.
                    if (resp.code in 400..499) return null
                }
            } catch (e: Exception) {
                // a network blip or a timeout: try once more
            }
            if (attempt < TRIES - 1) Thread.sleep(app.vela.core.util.Jitter.around(200L, 0.5))
        }
        return null
    }
}
