package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The drawn route line.
 *
 * Calibration (2026-06-15) established that Google's `/maps/preview/directions`
 * response carries NO decodable overview polyline — the web client renders the
 * line from vector tiles. So Vela sources the *geometry* from an open router
 * (OSRM, whose geometry is a standard E5 polyline [PolylineCodec] decodes
 * directly) while Google still provides the ETA, live traffic and maneuvers.
 * Same split the offline build will use, just with Valhalla as the engine.
 *
 * NOTE: [OSRM_BASE] is the FOSSGIS community server (fair-use, no key). It hosts
 * a separate backend per travel mode — `routed-car` / `routed-bike` /
 * `routed-foot` — which is why walk/bike get *their own* path-following line and
 * not a car route. (The old router.project-osrm.org demo only had the car
 * profile.) Point it at a self-hosted OSRM/Valhalla before any real release.
 */
object RouteGeometry {
    private const val OSRM_BASE = "https://routing.openstreetmap.de"
    private val json = Json { ignoreUnknownKeys = true }

    /** The FOSSGIS OSRM backend for each mode. Transit has none → null geometry. */
    private fun backend(mode: TravelMode): String? = when (mode) {
        TravelMode.DRIVE -> "routed-car"
        TravelMode.BICYCLE -> "routed-bike"
        TravelMode.WALK -> "routed-foot"
        TravelMode.TRANSIT -> null
    }

    /** Path-following polyline for origin→dest in [mode], or null on any failure. */
    fun fetch(http: OkHttpClient, origin: LatLng, dest: LatLng, mode: TravelMode): List<LatLng>? =
        fetchAll(http, origin, dest, mode, alternatives = false).firstOrNull()

    /** Up to a few real road-following geometries (best-first) — OSRM's fastest
     *  plus, when [alternatives] is on, its alternates. Used to give EVERY Google
     *  route a real line (paired by order) instead of letting the non-fastest ones
     *  fall back to a scattered-point guess that doubled back on itself. Empty on
     *  any failure. */
    fun fetchAll(
        http: OkHttpClient,
        origin: LatLng,
        dest: LatLng,
        mode: TravelMode,
        alternatives: Boolean = true,
    ): List<List<LatLng>> = try {
        // The "/driving/" service keyword is fixed in OSRM's URL grammar; the real
        // transport profile is chosen by which backend (routed-car/bike/foot) we hit.
        val backend = backend(mode) ?: return emptyList()
        val url = "$OSRM_BASE/$backend/route/v1/driving/" +
            "${origin.lng},${origin.lat};${dest.lng},${dest.lat}" +
            "?overview=full&geometries=polyline" + if (alternatives) "&alternatives=3" else ""
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            json.parseToJsonElement(resp.body?.string().orEmpty())
                .jsonObject["routes"]?.jsonArray
                ?.mapNotNull { it.jsonObject["geometry"]?.jsonPrimitive?.contentOrNull }
                ?.map { PolylineCodec.decode(it) }
                ?.filter { it.size >= 2 }
                ?: emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Copy of [route] drawn along [polyline], maneuvers repositioned along it
     *  by cumulative step distance. Google's distances/durations are kept. */
    fun reposition(route: Route, polyline: List<LatLng>): Route {
        if (polyline.size < 2) return route
        // Use the polyline's own length (not the summed step distances) as the
        // denominator so each turn lands at its true cumulative distance — see the
        // note in DirectionsParser.placeManeuvers.
        val total = (0 until polyline.size - 1)
            .sumOf { polyline[it].distanceTo(polyline[it + 1]) }
            .coerceAtLeast(1.0)
        var cum = 0.0
        val placed = route.maneuvers.map { m ->
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, (cum / total).coerceIn(0.0, 1.0)))
        }
        val legs = route.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(maneuvers = placed) else leg }
        return route.copy(polyline = polyline, legs = legs)
    }

    private fun pointAlong(poly: List<LatLng>, frac: Double): LatLng {
        val seg = DoubleArray(poly.size - 1) { poly[it].distanceTo(poly[it + 1]) }
        val target = seg.sum() * frac
        var acc = 0.0
        for (i in seg.indices) {
            if (acc + seg[i] >= target) {
                val f = if (seg[i] == 0.0) 0.0 else (target - acc) / seg[i]
                val a = poly[i]
                val b = poly[i + 1]
                return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
            acc += seg[i]
        }
        return poly.last()
    }
}
