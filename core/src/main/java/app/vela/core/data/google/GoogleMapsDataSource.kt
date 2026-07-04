package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.config.CalibrationStore
import app.vela.core.config.JsTransforms
import app.vela.core.diag.DiagLog
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.RouteEngine
import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.PhotosParser
import app.vela.core.data.google.parse.ReviewsParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.ln
import kotlin.math.log2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real extractor, calibrated against maps.google.com (2026-06-15).
 *
 * Search turned out to need NO pb at all — a plain `/search?tbm=map&q=…` returns
 * the full results JSON, with viewport bias achieved by appending "near lat,lng"
 * to the query. Directions needs a pb (built by [DirectionsPb]) but no session
 * token. Both are the same endpoints google.com/maps calls from a browser, so
 * they work without Play Services — good for GrapheneOS.
 */
@Singleton
/**
 * How prominent a place is on the map ≈ how many people know it. Review count dominates (a Safeway has
 * thousands, the sushi counter inside it dozens), log-compressed so a mega-chain doesn't utterly bury
 * everything, and nudged by rating so among similarly-popular places the better-rated wins.
 */
fun ambientProminence(p: Place): Double =
    ln((p.reviewCount ?: 0) + 1.0) * (0.6 + (p.rating ?: 3.5) / 10.0)

/**
 * Order ambient Google POIs for the browse map. Callers treat "first = wins the label slot" (the ambient
 * layer places a lower symbol-sort-key first and drops overlapping dots), so this decides which POI shows
 * when several collide AND which survive the take-N cap. **Prominence-first**, exact distance only as a
 * tiebreak. This is what a map wants — the recognizable landmarks (a Safeway with 1,273 reviews, an
 * Applebee's with 1,192) lead, and the low-signal junk the category fan-out drags in (a 0-review mobile
 * mechanic, an adult-family-home, a road intersection) sinks to the bottom and is dropped/loses its
 * collision. Distance-bucketing was tried and REVERTED: it floated that near-centre junk above the
 * landmarks (device-measured). The anchor-beats-tenant case still holds (Safeway's reviews ≫ its in-store
 * sushi counter's, so it wins their shared point).
 */
internal fun rankAmbientPlaces(places: List<Place>): List<Place> =
    places.sortedWith(
        compareByDescending<Place> { ambientProminence(it) }
            .thenBy { it.distanceMeters ?: Double.MAX_VALUE },
    )

class GoogleMapsDataSource @Inject constructor(
    private val http: OkHttpClient,
    private val session: GoogleSession,
    private val calibration: CalibrationStore,
    private val jsTransforms: JsTransforms,
    private val diag: DiagLog,
    private val routeEngine: RouteEngine,
) : MapDataSource {

    override suspend fun search(query: String, near: LatLng?): SearchResult = io {
        session.ensure()
        // Results are viewport-driven, so a location is required; callers
        // normally pass the user's location, with a fallback for the rare null.
        val viewport = near ?: DEFAULT_VIEWPORT
        val cal = calibration.current()
        val pb = SearchPb.build(query, viewport, cal.searchPb)
        val url = "${cal.searchEndpoint}&q=${query.enc()}&pb=${pb.enc()}".localized()
        val raw = get(url)
        // A remote transforms.js can fully re-parse a reshaped response (searchOverride);
        // otherwise the compiled parser runs. Either way, an optional transformPlaces
        // hook gets the last word. No hook / any error → pure compiled path.
        val places = try {
            jsTransforms.searchOverride(raw)
                ?: SearchParser.parse(query, GoogleResponse.parse(raw), near, cal.paths).places
        } catch (e: CalibrationNeededException) {
            // Capture the exact request that drifted so an opted-in user can hand it
            // to a dev (no-op unless diagnostics are on).
            diag.record("drift", "search parse drift: ${e.message}", url)
            throw e
        }
        // detail = the exact request URL so an opted-in user's export is replayable.
        diag.record("search", "\"$query\" near ${near?.lat ?: "?"},${near?.lng ?: "?"} → ${places.size} results", url)
        // open/closed diagnosis: what status + hours did we actually parse for each result? (compare
        // the status string to the hours to see whether Google's string is wrong or we mis-parse.)
        places.take(6).forEach { p ->
            diag.record(
                "placestatus",
                "${p.name}: openNow=${p.openNow} status=\"${p.statusText}\" permClosed=${p.permanentlyClosed} " +
                    "| hours(${p.hours.size}): ${p.hours.joinToString(" · ").take(180)}",
                "",
            )
        }
        SearchResult(query, jsTransforms.refineSearch(places))
    }

    override suspend fun nearbyPlaces(center: LatLng, spanMeters: Double): List<Place> = io {
        session.ensure()
        val cal = calibration.current()
        // The wide default search (!1d≈25229, !4f13.1) returns the ~20 most prominent places over a
        // big area, so a strip mall shows almost none. Tighten the viewport (and match the !4f zoom)
        // + ask for more (!7i40). Calibrated live: span 25229↔zoom 13.1; span ~3.5–4 km returns
        // ~25 places within 700 m vs 1 at the default.
        val zoom = (13.1 + log2(25229.0 / spanMeters)).coerceIn(13.0, 17.5)
        // FAN OUT across category terms + merge: one "places" query is biased to prominent food/
        // shops, so it misses whole tiers (a strip mall's plumber, nail salon, IT shop). A handful
        // of category queries roughly DOUBLES local coverage (live: 22→52 unique within 600 m).
        val terms = listOf(
            "places", "restaurants", "coffee", "stores", "shopping", "services", "beauty salon", "fast food",
            // High-traffic everyday categories the food/shop-biased set above under-returns, so the map
            // shows a Google-like MIX (a gas station, a gym, a grocer) rather than mostly restaurants.
            // Low-signal extras are fine — the prominence sort keeps them from displacing real businesses.
            "grocery store", "gas station", "gym", "bar", "pharmacy",
        )
        val all = coroutineScope {
            terms.map { term ->
                async {
                    runCatching {
                        val pb = SearchPb.build(term, center, cal.searchPb)
                            .replaceFirst(Regex("!1d[0-9.]+"), "!1d${spanMeters.toInt()}")
                            .replaceFirst(Regex("!4f[0-9.]+"), "!4f${String.format(java.util.Locale.US, "%.1f", zoom)}")
                            .replaceFirst(Regex("!7i\\d+"), "!7i60") // deep pool per term, so zooming in can go down the rank
                        val url = "${cal.searchEndpoint}&q=${term.enc()}&pb=${pb.enc()}".localized()
                        SearchParser.parse(term, GoogleResponse.parse(get(url)), center, cal.paths).places
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        // Dedup by feature id (same place returned under several terms); fall back to name+coords,
        // then rank for the map (locality + prominence — see rankAmbientPlaces).
        val deduped = all.distinctBy {
            it.featureId ?: "${it.name}@${(it.location.lat * 1e4).toInt()},${(it.location.lng * 1e4).toInt()}"
        }
        rankAmbientPlaces(deduped)
    }

    override suspend fun placeDetails(id: String): Place = io {
        // CALIBRATE: the dedicated place-detail RPC (reviews, hours, popular
        // times) under /maps/preview/place is not yet mapped. Search already
        // returns name/rating/reviews/address/category, so the UI uses the
        // Place from the search result directly until this is calibrated.
        throw CalibrationNeededException("placeDetails RPC not yet mapped")
    }

    override suspend fun reverseGeocode(location: LatLng): Place? = io {
        // OpenStreetMap's Nominatim — keyless, on-ethos (open data), and a stable
        // documented API, so unlike the Google endpoints it needs no recalibration.
        // Best-effort: any failure (network, rate-limit, no match) → null.
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18" +
                "&lat=${location.lat}&lon=${location.lng}"
            val root = Json.parseToJsonElement(getNominatim(url)).jsonObject
            val addr = root["address"]?.jsonObject ?: return@runCatching null
            fun str(k: String): String? = (addr[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val street = listOfNotNull(str("house_number"), str("road")).joinToString(" ").ifBlank { null }
            val city = str("city") ?: str("town") ?: str("village") ?: str("hamlet") ?: str("suburb")
            val regionPost = listOfNotNull(str("state"), str("postcode")).joinToString(" ").ifBlank { null }
            val addressLine = listOfNotNull(street, city, regionPost).joinToString(", ")
                .ifBlank { (root["display_name"] as? JsonPrimitive)?.content }
            Place(
                id = "pin:${location.lat},${location.lng}",
                name = street ?: city ?: "Dropped pin",
                location = location,
                address = addressLine,
            )
        }.getOrNull()
    }

    override suspend fun reviews(featureId: String): List<Review> = io {
        // /maps/preview/review/listentitiesreviews — a keyless GET. The feature id
        // "0xHIGH:0xLOW" splits into two unsigned-64 decimals (1y/2y); 2i/3i page,
        // 3e1 sorts by most-relevant. The 1s session token can be any string.
        // (Calibrated live 2026-06-16.)
        val parts = featureId.split(":")
        if (parts.size != 2) return@io emptyList()
        val high = runCatching { java.math.BigInteger(parts[0].removePrefix("0x"), 16) }.getOrNull() ?: return@io emptyList()
        val low = runCatching { java.math.BigInteger(parts[1].removePrefix("0x"), 16) }.getOrNull() ?: return@io emptyList()
        val cal = calibration.current()
        val pb = cal.reviewsPb.replace("{HIGH}", high.toString()).replace("{LOW}", low.toString())
        val url = "${cal.reviewsEndpoint}&pb=${pb.enc()}"
        runCatching { ReviewsParser.parse(GoogleResponse.parse(get(url))) }.getOrDefault(emptyList())
    }

    override suspend fun placePhotos(featureId: String): List<String> = io {
        // batchexecute `hspqX` (/MapsPhotoService.ListEntityPhotos) — a keyless POST
        // (no `at` token, just the warmed session cookies). The feature id goes in
        // the proto verbatim ([2][0]); the response carries the full gallery, URL at
        // each entry's [6][0]. (Calibrated live 2026-06-17.) Best-effort: any failure
        // returns empty so the caller keeps the search-preview photos.
        if (!featureId.contains(":")) return@io emptyList()
        session.ensure()
        val cal = calibration.current()
        val inner = cal.photosProto.replace("{FID}", featureId).replace("{COUNT}", PHOTO_COUNT.toString())
        // JsonPrimitive(...).toString() = the proto as a properly-escaped JSON string literal.
        val freq = "[[[\"hspqX\",${JsonPrimitive(inner)},null,\"generic\"]]]"
        runCatching { PhotosParser.parse(post(cal.photosEndpoint, "f.req=${freq.enc()}")).map { it.url } }.getOrDefault(emptyList())
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        waypoints: List<LatLng>,
    ): List<Route> = io {
        // Multi-stop: route OSRM straight THROUGH the stops (routeVia filters the spurious per-via
        // arrive/depart into one continuous trip), then overlay Google's live in-traffic ETA ratio for the
        // whole origin→dest so the time is traffic-aware. A waypointed trip is a single path — no alternates.
        if (waypoints.isNotEmpty()) {
            return@io coroutineScope {
                val viaD = async { RouteGeometry.routeVia(http, listOf(origin) + waypoints + destination, mode) }
                val gD = async { runCatching { googleDirections(origin, destination, mode) }.getOrNull().orEmpty() }
                val via = viaD.await().firstOrNull()
                // OSRM unreachable → route the legs on-device (origin→w1→…→dest chained), like the
                // single-destination path's offline fallback; only then fall to Google's DIRECT route
                // (which reaches the destination but loses the stops).
                val onDevice = if (via == null && routeEngine.isReady(mode))
                    chainOnDevice(listOf(origin) + waypoints + destination, mode) else null
                val result = when {
                    via != null -> listOf(applyTrafficRatio(via, gD.await().firstOrNull()))
                    onDevice != null -> listOf(onDevice)
                    else -> gD.await().take(1)
                }
                diag.record(
                    "directions",
                    "$mode multi-stop ×${waypoints.size} → via=${via != null} onDevice=${onDevice != null} " +
                        "googleDirect=${result.isNotEmpty() && via == null && onDevice == null}" +
                        if (via == null && onDevice == null) " (STOPS DROPPED if google won)" else "",
                    "",
                )
                result
            }
        }
        coroutineScope {
            // PRIMARY: the open router (OSRM) — complete, street-named turn-by-turn + real geometry.
            // Google's keyless directions endpoint hands back ABBREVIATED steps for longer routes
            // (a 6-mi route came back with 2 of ~10 turns), so Google is only the FALLBACK + the
            // live-traffic source. Fetch both in parallel so the traffic round-trip is free.
            val openD = async { RouteGeometry.route(http, origin, destination, mode) }
            val googleD = async { runCatching { googleDirections(origin, destination, mode) }.getOrNull().orEmpty() }
            val open = openD.await()
            val google = googleD.await()
            val gTop = google.firstOrNull()
            // TRAFFIC-AWARE routing (option 3): if Google's live-traffic route took a DIFFERENT path
            // than OSRM's free-flow one — i.e. Google rerouted around a jam — re-run OSRM forced
            // through Google's path so we follow the traffic-smart route WITH full street-named steps.
            // (Only on real divergence, so the normal case stays the fast single OSRM call.)
            val trafficRoute = if (open.isNotEmpty() && gTop != null && gTop.polyline.size >= 5 &&
                RouteGeometry.divergent(open.first(), gTop)) {
                RouteGeometry.routeVia(http, listOf(origin) + RouteGeometry.sampleVias(gTop.polyline) + destination, mode)
                    .firstOrNull()
            } else null
            // OFFLINE fallback: OSRM (and Google) need the network. When OSRM came back empty — no
            // connectivity, or the FOSSGIS server is down — route fully ON-DEVICE from a downloaded
            // GraphHopper graph, if one covers this area. No traffic offline, but complete named turns.
            val onDevice = if (open.isEmpty() && trafficRoute == null && routeEngine.isReady(mode))
                routeEngine.route(origin, destination, mode) else emptyList()
            // Lead with Google's jam-avoiding path (option 3) only when it EARNS it: its live in-traffic
            // ETA is within a small margin of OSRM's FREE-FLOW best, so even Google's detour is time-
            // competitive → the jam is real. The old code led with the snap on ANY >700 m divergence, so a
            // longer/wonky snapped path could win even when it wasn't faster (the "fucky reroute"); now such
            // a snap steps aside for OSRM's clean route. (A true per-alternate re-rank isn't possible: Google
            // hands back ONE live-traffic figure, so applyTraffic scales every route by the same ratio and
            // can't reorder the OSRM alternates — this ETA gate is the meaningful lever.)
            val googleEtaS = gTop?.durationInTrafficSeconds ?: gTop?.durationSeconds
            // The snapped via-route must actually REACH the destination — a truncated one ending at an
            // intermediate via (short ETA, wrong last step) is the "10 min away" nav bug — AND be time-
            // competitive with OSRM's free-flow best.
            val snapReaches = trafficRoute?.polyline?.lastOrNull()
                ?.let { it.distanceTo(destination) <= SNAP_REACH_M } == true
            val snapWorthIt = trafficRoute != null && snapReaches && open.isNotEmpty() && googleEtaS != null &&
                googleEtaS <= open.first().durationSeconds * SNAP_ETA_MARGIN
            diag.record(
                "directions",
                "$mode → OSRM ${open.size} routes / ${open.firstOrNull()?.maneuvers?.size ?: 0} steps; " +
                    "google ${google.size} (typ=${gTop?.durationSeconds?.toInt()}s traf=${gTop?.durationInTrafficSeconds?.toInt()}s " +
                    "ratio=${gTop?.durationInTrafficSeconds?.let { t -> gTop?.durationSeconds?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.2f", t / it) } }}); " +
                    "rerouted=${trafficRoute != null} snapKept=$snapWorthIt snapReaches=$snapReaches " +
                    "(gEta=${googleEtaS?.toInt()}s osrmFF=${open.firstOrNull()?.durationSeconds?.toInt()}s); " +
                    "onDevice=${onDevice.size}",
                "",
            )
            if (open.isEmpty()) {
                // OSRM unreachable → the on-device offline route, or Google's abbreviated one, whichever we have.
                if (onDevice.isNotEmpty()) onDevice else google
            } else {
                val primary = if (snapWorthIt) (listOf(trafficRoute!!) + open).map { applyTraffic(it, gTop) }
                    else open.map { applyTraffic(it, gTop) }
                // ALTERNATES to choose from = Google's OWN alternate routes (the real, traffic-aware ones you
                // miss). Kept PROVISIONAL: their polyline + live ETA are shown now, but turn-by-turn is named
                // only when you PICK one to drive ([nameRoute]) — so the picker loads fast and we never snap a
                // route you don't take. Google's routes already carry duration_in_traffic + congestion spans.
                val googleAlts = google.drop(1).filter { it.polyline.size >= 5 }.map { it.copy(provisional = true) }
                // Rank by live in-traffic ETA so the FASTEST-right-now route leads (Google-style). Each
                // Google alternate carries its OWN duration_in_traffic (parseRoute reads summary[10][0][0]
                // per route in root[0][1]); an OSRM/other route without one is put on the SAME axis by
                // scaling its free-flow by the top Google route's in-traffic ratio (1.0 if no live traffic)
                // — else the old `?: durationSeconds` sorted a traffic-inflated Google alt against an
                // un-inflated free-flow OSRM route (different axes → the fastest didn't reliably lead).
                val gRatio = gTop?.let { g ->
                    g.durationInTrafficSeconds?.takeIf { g.durationSeconds > 0 }?.div(g.durationSeconds)
                }?.coerceIn(0.5, 4.0) ?: 1.0
                // Dedupe by path (keeps the fastest of look-alikes) + cap so the picker stays short.
                dedupeRoutes(
                    (listOf(primary.first()) + googleAlts + primary.drop(1))
                        .sortedWith(
                            compareBy(
                                { it.durationInTrafficSeconds ?: (it.durationSeconds * gRatio) },
                                { it.provisional }, // stable tie-break: a fully-named route leads over a provisional look-alike
                            ),
                        ),
                ).take(MAX_ROUTES)
            }
        }
    }

    /** Drop routes that follow ~the same path as an earlier one (keeps the picker to genuinely distinct
     *  choices). Order is preserved, so the primary stays first. */
    private fun dedupeRoutes(routes: List<Route>): List<Route> {
        val kept = mutableListOf<Route>()
        for (r in routes) if (kept.none { routesSimilar(it, r) }) kept += r
        return kept
    }

    /** Two routes are "the same" if a handful of points sampled along one all sit within ~150 m of the
     *  other's line — i.e. they trace essentially the same roads. */
    private fun routesSimilar(a: Route, b: Route): Boolean {
        if (a.polyline.size < 2 || b.polyline.size < 2) return false
        return (1..4).all { k ->
            val p = b.polyline[(b.polyline.size * k / 5).coerceIn(0, b.polyline.size - 1)]
            a.polyline.minOf { p.distanceTo(it) } < 150.0
        }
    }

    /** Overlay Google's live-traffic ETA + congestion onto an open-router [route] (best-effort):
     *  scale the route's free-flow duration by Google's in-traffic/typical ratio, and map its
     *  congestion spans onto the open geometry by fraction. No Google traffic → keep free-flow. */
    private fun applyTraffic(route: Route, g: Route?): Route {
        val typical = g?.durationSeconds?.takeIf { it > 0 } ?: return route
        val inTraffic = g.durationInTrafficSeconds ?: return route
        val factor = (inTraffic / typical).coerceIn(0.5, 4.0)
        val scale = if (g.distanceMeters > 0) route.distanceMeters / g.distanceMeters else 1.0
        return route.copy(
            durationInTrafficSeconds = route.durationSeconds * factor,
            trafficSpans = g.trafficSpans.map { it.copy(startMeters = it.startMeters * scale, lengthMeters = it.lengthMeters * scale) },
        )
    }

    /** Offline multi-stop: route each leg (origin→w1, w1→w2, …, wn→dest) on the on-device engine and
     *  stitch them into ONE continuous route — polylines joined (dropping each leg's duplicated joint
     *  point), each non-final leg's ARRIVE and non-first leg's DEPART dropped (mirroring what routeVia's
     *  parser does for via boundaries), distances/durations summed. Null if any leg can't be routed
     *  (cross-region or off-graph), so the caller can fall through. */
    private fun chainOnDevice(points: List<LatLng>, mode: TravelMode): Route? {
        val legs = points.zipWithNext().map { (a, b) ->
            runCatching { routeEngine.route(a, b, mode).firstOrNull() }.getOrNull() ?: return null
        }
        val polyline = legs.flatMapIndexed { i, leg -> if (i == 0) leg.polyline else leg.polyline.drop(1) }
        // Boundary DEPART/ARRIVE steps are dropped, but their step distance is FOLDED into the
        // last kept maneuver so step lengths keep TILING the stitched polyline — NavEngine locates
        // each maneuver by a prefix-sum of them (same fold as parseOsrmRoute's via filter).
        val maneuvers = mutableListOf<app.vela.core.model.Maneuver>()
        legs.forEachIndexed { i, leg ->
            leg.maneuvers.forEach { m ->
                val boundary = (m.type == app.vela.core.model.ManeuverType.ARRIVE && i != legs.lastIndex) ||
                    (m.type == app.vela.core.model.ManeuverType.DEPART && i != 0)
                if (!boundary) {
                    maneuvers += m
                } else if (maneuvers.isNotEmpty() && m.distanceMeters > 0.0) {
                    val prev = maneuvers.removeAt(maneuvers.lastIndex)
                    maneuvers += prev.copy(distanceMeters = prev.distanceMeters + m.distanceMeters)
                }
            }
        }
        if (polyline.size < 2 || maneuvers.size < 2) return null
        val dist = legs.sumOf { it.distanceMeters }
        val dur = legs.sumOf { it.durationSeconds }
        return Route(
            polyline = polyline,
            legs = listOf(app.vela.core.model.RouteLeg(dist, dur, null, maneuvers)),
            distanceMeters = dist,
            durationSeconds = dur,
            durationInTrafficSeconds = null, // offline — no live traffic
            summary = legs.firstOrNull()?.summary,
        )
    }

    /** Lighter traffic overlay for a multi-stop route: scale the ETA by Google's in-traffic ratio for a
     *  traffic-aware time, but DON'T map the congestion spans — Google's direct origin→dest path differs
     *  from the through-the-stops path, so its span offsets wouldn't line up. ETA only. */
    private fun applyTrafficRatio(route: Route, g: Route?): Route {
        val typical = g?.durationSeconds?.takeIf { it > 0 } ?: return route
        val inTraffic = g.durationInTrafficSeconds ?: return route
        val factor = (inTraffic / typical).coerceIn(0.5, 4.0)
        return route.copy(durationInTrafficSeconds = route.durationSeconds * factor)
    }

    /** Name a provisional alternate the moment the user picks it to drive: snap its (Google) polyline
     *  through OSRM for real named turn-by-turn, guarded to reach the destination, and re-apply Google's
     *  live-traffic overlay. Failure keeps Google's own (abbreviated) steps so nav still works.
     *  (On-device GraphHopper map-match for downloaded regions plugs in here next.) */
    override suspend fun nameRoute(route: Route, origin: LatLng, destination: LatLng, mode: TravelMode): Route = io {
        if (!route.provisional || route.polyline.size < 3) return@io route.copy(provisional = false)
        val vias = listOf(origin) + RouteGeometry.sampleVias(route.polyline) + destination
        val named = RouteGeometry.routeVia(http, vias, mode).firstOrNull()
            ?.takeIf { it.polyline.lastOrNull()?.let { p -> p.distanceTo(destination) <= SNAP_REACH_M } == true }
        if (named != null) applyTraffic(named, route).copy(provisional = false)
        else route.copy(provisional = false)
    }

    /** Google's keyless directions — now the FALLBACK router (OSRM unreachable) and the
     *  live-traffic source (ETA / duration-in-traffic / congestion spans). Its step list is
     *  abbreviated for long routes, which is exactly why OSRM is primary. */
    private suspend fun googleDirections(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route> {
        session.ensure()
        val cal = calibration.current()
        val pb = DirectionsPb.build(origin, destination, mode, cal.directionsPb)
        val url = "${cal.directionsEndpoint}&pb=${pb.enc()}"
        val routes = try {
            DirectionsParser.parse(GoogleResponse.parse(get(url)))
        } catch (e: CalibrationNeededException) {
            diag.record("drift", "directions parse drift: ${e.message}", url)
            throw e
        }
        return if (routes.all { it.polyline.size > 2 }) routes
        else {
            val geoms = RouteGeometry.fetchAll(http, origin, destination, mode)
            routes.mapIndexed { i, r ->
                if (r.polyline.size > 2) r
                else RouteGeometry.reposition(r, geoms.getOrNull(i) ?: listOf(origin, destination))
            }
        }
    }

    // --- plumbing -----------------------------------------------------------

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.google.com/maps/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw CalibrationNeededException("HTTP ${resp.code} from ${req.url.encodedPath}")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun post(url: String, body: String): String {
        val media = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(media))
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.google.com/maps/")
            .header("X-Same-Domain", "1") // batchexecute expects this from a same-origin caller
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw CalibrationNeededException("HTTP ${resp.code} from ${req.url.encodedPath}")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun getNominatim(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/PimpinPumpkin/Vela)")
            .header("Accept-Language", "en")
            .build()
        http.newCall(req).execute().use { resp -> return resp.body?.string().orEmpty() }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    /** Rewrite the endpoint's `hl=en` to the app/system language so Google returns categories, hours
     *  and open/closed status IN THE USER'S LANGUAGE (Google-Maps-style). The open/closed BOOLEAN is
     *  parsed from that localized status text against the same language's keyword table
     *  (`SearchParser.parseOpenNow` reads the same `Locale.getDefault()` this rewrite does), so text
     *  and boolean can't disagree. `Locale.getDefault()` reflects the in-app language override
     *  (AppLocale sets it) or the system locale. **No-op for English → English users are
     *  byte-for-byte unchanged.** */
    private fun String.localized(): String {
        val lang = java.util.Locale.getDefault().language.lowercase()
        return if (lang.isBlank() || lang == "en") this else replace("hl=en", "hl=$lang")
    }

    private companion object {
        // Fallback viewport when no user location is available — search is
        // viewport-driven and needs one. Callers normally pass the real location.
        val DEFAULT_VIEWPORT = LatLng(37.7749, -122.4194)
        const val PHOTO_COUNT = 50 // gallery page size for the hspqX request
        // Follow Google's jam-avoiding reroute only if its live ETA is within this ×OSRM-free-flow-best
        // (its detour is time-competitive with OSRM's ideal → the jam justifies the reroute). Tunable from
        // real side-by-side data — the `directions` diag logs gEta/osrmFF so the threshold can be pinned.
        const val SNAP_ETA_MARGIN = 1.2
        const val SNAP_REACH_M = 500.0 // the snapped route's last point must be within this of the destination
        const val MAX_ROUTES = 4       // primary + up to 3 alternates in the picker
    }
}
