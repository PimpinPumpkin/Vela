package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.config.CalibrationStore
import app.vela.core.config.JsTransforms
import app.vela.core.diag.DiagLog
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.CategoryFilter
import app.vela.core.data.LowRamMode
import app.vela.core.data.MapDataSource
import app.vela.core.data.RouteEngine
import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.EntityListParser
import app.vela.core.data.google.parse.PhotosParser
import app.vela.core.data.google.parse.ReviewsParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode
import app.vela.core.model.destinationPoint
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

    /** ISO 3166 alpha-2 of the region the phone is in (cell-network country, falling back to the
     *  locale's) — set by the app layer at startup; drives the `gl=` rewrite in [regionalized]. */
    @Volatile var glRegion: String? = null

    /** Caps how many ambient category requests parse AT ONCE. Each response is loaded whole and
     *  built into a JsonElement tree (~tens of MB for a dense area); the ~13-term fan-out fired them
     *  all in parallel, so a fresh launch / fast far pan allocated ~400 MB of transient parse trees
     *  in a burst, filled the 512 MB heap, and stalled every allocation on a blocking GC (measured
     *  on a Pixel 9: 401 MB live, 80-86 ms WaitForGcToComplete storms, 400 ms frames). Bounding the
     *  fan-out to a few at a time caps the peak transient heap (~4x30 MB instead of 13x30 MB) with the
     *  same final pool - the streaming onPartial paint keeps first dots fast. Shared across calls so
     *  a pan mid-load can't double the burst. */
    // Permits fleet-tunable through calibration.json ("ambientFanoutPermits") - CLAUDE.md's own
    // escape hatch ("if a dense area still spikes, lower the permit") without an app release.
    // Read at construction, so a pushed change applies on the next process start.
    private val ambientFanout = kotlinx.coroutines.sync.Semaphore(
        calibration.current().tune("ambientFanoutPermits", 4.0).toInt().coerceIn(1, 13),
    )

    override suspend fun search(query: String, near: LatLng?, spanMeters: Double?): SearchResult = io {
        session.ensure()
        // Results are viewport-driven, so a location is required; callers
        // normally pass the user's location, with a fallback for the rare null.
        val viewport = near ?: DEFAULT_VIEWPORT
        val cal = calibration.current()
        val firstUrl = "${cal.searchEndpoint}&q=${query.enc()}&pb=${SearchPb.build(query, viewport, cal.searchPb, spanMeters).enc()}".localized()
        suspend fun page(offset: Int): List<Place> {
            val url = if (offset == 0) {
                firstUrl
            } else {
                "${cal.searchEndpoint}&q=${query.enc()}&pb=${SearchPb.build(query, viewport, cal.searchPb, spanMeters, offset).enc()}".localized()
            }
            val raw = get(url)
            // A remote transforms.js can fully re-parse a reshaped response (searchOverride);
            // otherwise the compiled parser runs. Either way, an optional transformPlaces
            // hook gets the last word. No hook / any error → pure compiled path.
            return try {
                jsTransforms.searchOverride(raw)
                    ?: SearchParser.parse(query, GoogleResponse.parse(raw), near, cal.paths).places
            } catch (e: CalibrationNeededException) {
                if (offset == 0) {
                    // Capture the exact request that drifted so an opted-in user can hand it
                    // to a dev (no-op unless diagnostics are on).
                    diag.record("drift", "search parse drift: ${e.message}", url)
                    throw e
                }
                emptyList() // a later page drifting must never kill the first page's results
            }
        }
        val first = page(0)
        // PAGINATE like the Google app: a page is !7iN results (20 today) and a FULL first page
        // means the viewport holds more. Google's keyless web ranking is prominence-heavy over
        // the whole box, so a modest place sitting right next to the user ranks 21-60 for a
        // category query - one page was exactly why "the restaurant right next to me" never made
        // the list (user 2026-07-18). Pages 2+3 fetch CONCURRENTLY (one extra round trip, not
        // two); either failing quietly leaves page 1 intact. Specific-name queries return
        // partial pages and never paginate, so they cost nothing extra. Page size + offsets are
        // DERIVED from the calibrated template (searchPb is remote-updatable); a recaptured
        // template without a !7i token skips pagination rather than guessing.
        val pageSize = SearchPb.pageSize(cal.searchPb)
        val more = if (pageSize != null && first.size >= pageSize - 2) {
            kotlinx.coroutines.coroutineScope {
                val p2 = async { runCatching { page(pageSize) }.getOrDefault(emptyList()) }
                val p3 = async { runCatching { page(pageSize * 2) }.getOrDefault(emptyList()) }
                p2.await() + p3.await()
            }
        } else emptyList()
        val places = (first + more).distinctBy { p ->
            p.featureId ?: "${p.name.lowercase()}|${(p.location.lat * 2000).toInt()}|${(p.location.lng * 2000).toInt()}"
        }
        // detail = the exact request URL so an opted-in user's export is replayable.
        diag.record("search", "\"$query\" near ${near?.lat ?: "?"},${near?.lng ?: "?"} → ${places.size} results (page1 ${first.size})", firstUrl)
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
        SearchResult(query, CategoryFilter.applyIfEnabled(jsTransforms.refineSearch(places)))
    }

    override suspend fun nearbyPlaces(center: LatLng, spanMeters: Double, onPartial: ((List<Place>) -> Unit)?): List<Place> = io {
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
        val allTerms = listOf(
            "places", "restaurants", "coffee", "stores", "shopping", "services", "beauty salon", "fast food",
            // High-traffic everyday categories the food/shop-biased set above under-returns, so the map
            // shows a Google-like MIX (a gas station, a gym, a grocer) rather than mostly restaurants.
            // Low-signal extras are fine — the prominence sort keeps them from displacing real businesses.
            "grocery store", "gas station", "gym", "bar", "pharmacy",
            // Civic/green POIs (schools, parks) — these render as edu/park icons (PoiIcons.groupForCategory
            // maps "…school"→edu, "…park"→park) but were never in the fan-out, so at z14+ browse (ambient
            // active → the OSM poi layers are hidden) they showed from NEITHER source. Their few reviews keep
            // their prominence low, so they surface in quiet/residential views without crowding businesses.
            "school", "park",
        )
        // LOW-RAM: the fan-out is the app's single largest allocation burst. Each term buffers a
        // full response String, a stripped copy, and a JsonElement DOM (GoogleResponse.parse), and
        // 15 of those run 4-at-a-time per pan - the documented ~180 MB/12 s churn. Constrained
        // devices fetch an 8-term subset instead (ported from vela-dpad, 2026-07-23).
        //
        // The subset is NOT just the first N. "school" and "park" are retained DELIBERATELY: while
        // the ambient layer is active at z14+ the basemap's OSM poi layers are filter-hidden, so
        // those categories have NO second source - dropping them makes parks and schools vanish
        // from the map entirely (the fork lost every park/school pin on a first 6-term attempt,
        // caught by A/B screenshot). What goes instead are terms whose places still surface via
        // "places"/"stores" or degrade gracefully: shopping, services, beauty salon, fast food,
        // gym, bar, pharmacy. Fewer ambient POIs is a visible trade, and the right one on a phone
        // that otherwise OOMs. Roomier devices are unaffected.
        val terms = if (LowRamMode.enabled) {
            listOf("places", "restaurants", "coffee", "stores", "grocery store", "gas station", "school", "park")
        } else {
            allTerms
        }
        suspend fun fetchTerm(term: String): List<Place> = ambientFanout.withPermit {
            runCatching {
                val pb = SearchPb.build(term, center, cal.searchPb)
                    .replaceFirst(Regex("!1d[0-9.]+"), "!1d${spanMeters.toInt()}")
                    .replaceFirst(Regex("!4f[0-9.]+"), "!4f${String.format(java.util.Locale.US, "%.1f", zoom)}")
                    .replaceFirst(
                        Regex("!7i\\d+"),
                        // Deep pool per term, so zooming in can go down the rank. Halved on low-RAM:
                        // the pool size drives the RESPONSE BODY size, and the body is what gets
                        // buffered and DOM-parsed per term.
                        if (LowRamMode.enabled) "!7i30" else "!7i60",
                    )
                val url = "${cal.searchEndpoint}&q=${term.enc()}&pb=${pb.enc()}".localized()
                SearchParser.parse(term, GoogleResponse.parse(get(url)), center, cal.paths).places
            }.getOrDefault(emptyList())
        }
        // Dedup by feature id (same place returned under several terms); fall back to name+coords,
        // then rank for the map (locality + prominence — see rankAmbientPlaces).
        fun finish(pool: List<Place>): List<Place> = rankAmbientPlaces(
            CategoryFilter.applyIfEnabled(
                pool.distinctBy {
                    it.featureId ?: "${it.name}@${(it.location.lat * 1e4).toInt()},${(it.location.lng * 1e4).toInt()}"
                },
            ),
        )
        var all = coroutineScope {
            if (onPartial == null) {
                terms.map { term -> async { fetchTerm(term) } }.awaitAll().flatten()
            } else {
                // STREAM the fan-out: paint the accumulated pool as terms land (growth- and
                // time-throttled so the map isn't re-tessellating on every landing) instead of
                // gating the first dots on the SLOWEST of ~13 requests - the tail was most of
                // the perceived wait. The final return below still carries the complete pool.
                val pool = mutableListOf<Place>()
                val mutex = Mutex()
                var landed = 0
                var paintedCount = 0
                var lastPaintNs = 0L
                terms.map { term ->
                    launch {
                        val places = fetchTerm(term)
                        mutex.withLock {
                            pool += places
                            landed++
                            val now = System.nanoTime()
                            // Escalating batch size: the FIRST dots should land fast (10 places is
                            // enough to make the map feel alive), but each partial re-runs symbol
                            // placement for the WHOLE layer, and in a dense downtown 6-8 repaints in
                            // the first seconds were most of the cold-load frame drops (user
                            // 2026-07-14). Once the map is already populated, wait for bigger
                            // batches - the user can't tell 60 dots from 85 mid-stream, but they can
                            // feel the placement passes.
                            // Bigger batches = FEWER whole-layer symbol-collision passes during the
                            // cold load, which is where a weak GPU (4a) stutters (each onPartial
                            // re-places the entire ambient layer). First paint at 25 keeps the map
                            // feeling alive fast; after that wait for +50 so a dense area re-collides
                            // ~2-3 times instead of ~8 (user 2026-07-17). The final return still carries
                            // the complete pool.
                            // Third rung (user 2026-07-17): once 100+ places are painted the map
                            // already reads "full" - later mid-stream repaints are invisible to the
                            // eye but each one re-places every symbol, so demand a bigger batch and
                            // a longer quiet gap before paying for another pass. Dense downtowns go
                            // from ~3-4 passes to ~2-3; sparse areas never reach this rung.
                            val minGrowth = when {
                                paintedCount == 0 -> 25
                                paintedCount >= 100 -> 80
                                else -> 50
                            }
                            val grown = pool.size - paintedCount >= minGrowth
                            val due = now - lastPaintNs > (if (paintedCount >= 100) 800_000_000L else 500_000_000L)
                            if (landed < terms.size && pool.isNotEmpty() && grown && (paintedCount == 0 || due)) {
                                paintedCount = pool.size
                                lastPaintNs = now
                                onPartial(finish(pool.toList()))
                            }
                        }
                    }
                }.joinAll()
                pool
            }
        }
        // SLIM-FLAVOR HEAL (live-bisected 2026-07-14): for the first ~3 s of a fresh session Google
        // serves a stripped per-place block - rating present, review count ABSENT (same query + pb
        // returns the full block seconds later). A cold-start fan-out lands entirely inside that
        // window, so the whole ambient pool parsed with reviewCount=null, which zeroed
        // ambientProminence and silently broke everything keyed on it: prominence ranking, dot
        // sizing, label tiers - all flat. Detect the flavor (rated places but not one count) and
        // refetch ONCE; by then the session is warm and the rich pool overwrites the slim one
        // (and the disk cache stores real counts). Doubles the request burst only on a cold start.
        // Majority (not all-slim): the session can warm MID-burst, leaving a mixed pool.
        val rated = all.count { it.rating != null }
        if (rated >= 3 && all.count { it.rating != null && it.reviewCount == null } > rated / 2) {
            delay(1200)
            val healed = coroutineScope { terms.map { term -> async { fetchTerm(term) } }.awaitAll().flatten() }
            if (healed.any { it.reviewCount != null }) {
                diag.record("ambient", "slim cold-start pool healed: ${all.size} -> ${healed.size} places with counts")
                // Healed first so distinctBy keeps the rich copy when a term partially failed.
                all = healed + all
            }
        }
        finish(all)
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

    override suspend fun placePhotos(featureId: String): List<app.vela.core.model.Photo> = io {
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
        runCatching { PhotosParser.parse(post(cal.photosEndpoint, "f.req=${freq.enc()}")) }.getOrDefault(emptyList())
    }

    override suspend fun streetView(location: LatLng, preferStreet: String?): app.vela.core.model.StreetViewPano? = io {
        // Keyless nearest-pano lookup - the JS Maps API's own GeoPhotoService.SingleImageSearch,
        // authorised by referer (the get() helper already sends it). The parser returns null with no
        // imagery near the point.
        val cal = calibration.current()
        val nearest = streetViewNearest(cal.streetViewMetaUrl, location.lat, location.lng) ?: return@io null

        // Address-street preference (Google-like). Google's address geocode can sit SET-BACK from the
        // street - a mid-block building has the frontage on the avenue and a service alley behind, and
        // the geocode lands nearer the alley. The alley pano is then not just the nearest, its whole
        // connectivity graph is the back cluster, so the real frontage pano is unreachable by walking.
        // When the nearest pano isn't on the address's own street, PROBE toward the street: the nearest
        // pano's heading is the (parallel) street axis, so the frontage sits perpendicular to it. Query
        // a few points out along both perpendiculars and adopt the nearest pano that IS on the address's
        // street. No-regression: no street given / already matches / nothing labelled found → keep the
        // nearest pano, and the probes only fire in the mismatch case.
        if (preferStreet.isNullOrBlank() ||
            StreetViewParser.streetOf(preferStreet) == null ||
            StreetViewParser.streetMatches(nearest.addressLabel, preferStreet)
        ) {
            return@io nearest
        }
        val perpA = (nearest.headingDeg + 90.0) % 360.0
        val perpB = (nearest.headingDeg + 270.0) % 360.0
        val probes = STREET_PROBE_RADII_M.flatMap {
            listOf(location.destinationPoint(it, perpA), location.destinationPoint(it, perpB))
        }
        val match = coroutineScope {
            probes.map { pt -> async { streetViewNearest(cal.streetViewMetaUrl, pt.lat, pt.lng) } }.awaitAll()
        }.filterNotNull()
            .filter { StreetViewParser.streetMatches(it.addressLabel, preferStreet) }
            .distinctBy { it.panoId }
            .minByOrNull { location.distanceTo(LatLng(it.lat, it.lng)) }
        match ?: nearest
    }

    private suspend fun streetViewNearest(metaUrl: String, lat: Double, lng: Double): app.vela.core.model.StreetViewPano? {
        val url = metaUrl
            .replace("{LAT}", "%.7f".format(java.util.Locale.US, lat))
            .replace("{LNG}", "%.7f".format(java.util.Locale.US, lng))
        return runCatching { StreetViewParser.parse(get(url), lat, lng) }.getOrNull()
    }

    override suspend fun streetViewByPano(panoId: String): app.vela.core.model.StreetViewPano? = io {
        // Epoch-exact pano fetch (walking): photometa/v1 by id, keyless. Same parser - it handles
        // the )]}' guard and the extra nesting. Lat/lng fall back to the response's own position.
        val cal = calibration.current()
        val url = cal.streetViewPanoUrl.replace("{PANOID}", panoId)
        runCatching { StreetViewParser.parse(get(url), 0.0, 0.0) }.getOrNull()?.takeIf { it.lat != 0.0 || it.lng != 0.0 }
    }

    override suspend fun streetViewTile(panoId: String, x: Int, y: Int, zoom: Int): ByteArray? = io {
        // The consumer equirect tile endpoint (what maps.google.com renders) - keyless, JPEG,
        // needs only the Google referer. Fixed template, no calibration: the panoid + x/y/zoom
        // fully address a tile in the standard SV pyramid.
        val url = "https://streetviewpixels-pa.googleapis.com/v1/tile" +
            "?cb_client=maps_sv.tactile&panoid=$panoId&x=$x&y=$y&zoom=$zoom&nbt=1&fover=2"
        runCatching { getBytes(url) }.getOrNull()
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        waypoints: List<LatLng>,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
    ): List<Route> = io {
        // Multi-stop: route OSRM straight THROUGH the stops (routeVia filters the spurious per-via
        // arrive/depart into one continuous trip), then overlay Google's live in-traffic ETA ratio for the
        // whole origin→dest so the time is traffic-aware. A waypointed trip is a single path — no alternates.
        if (waypoints.isNotEmpty()) {
            return@io coroutineScope {
                val viaD = async { RouteGeometry.routeVia(http, listOf(origin) + waypoints + destination, mode, avoidTolls, avoidHighways) }
                val gD = async { googleDirectionsRetried(origin, destination, mode) }
                val via = viaD.await().firstOrNull()
                // OSRM unreachable → route the legs on-device (origin→w1→…→dest chained), like the
                // single-destination path's offline fallback; only then fall to Google's DIRECT route
                // (which reaches the destination but loses the stops).
                val onDevice = if (via == null && routeEngine.isReady(mode))
                    chainOnDevice(listOf(origin) + waypoints + destination, mode, avoidTolls, avoidHighways) else null
                val result = when {
                    via != null -> listOf(applyTrafficRatio(via, gD.await().firstOrNull()))
                    onDevice != null -> listOf(onDevice)
                    else -> gD.await().take(1).map { it.copy(abbreviatedSteps = true) }
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
            val openD = async { RouteGeometry.route(http, origin, destination, mode, avoidTolls, avoidHighways) }
            val googleD = async { googleDirectionsRetried(origin, destination, mode) }
            val open = openD.await()
            val google = googleD.await()
            val gTop = google.firstOrNull()
            // AVOID toggles: the public FOSSGIS OSRM rejects `exclude=` outright (probed
            // 2026-07-11: InvalidValue - its profiles weren't built with excludable classes),
            // so a DOWNLOADED graph with the avoid CH profiles is the authoritative avoid
            // router - it goes FIRST. Old-format graphs / no coverage return empty and the
            // online chain below routes normally (avoid best-effort, never a dead end). No
            // live-traffic ETA on these: the offline result is free-flow, like any offline route.
            if ((avoidTolls || avoidHighways) && mode == TravelMode.DRIVE && routeEngine.isReady(mode)) {
                val avoidRoutes = runCatching { routeEngine.route(origin, destination, mode, avoidTolls, avoidHighways) }.getOrDefault(emptyList())
                if (avoidRoutes.isNotEmpty()) return@coroutineScope avoidRoutes
            }
            // TRAFFIC-AWARE routing (option 3): if Google's live-traffic route took a DIFFERENT path
            // than OSRM's free-flow one — i.e. Google rerouted around a jam — re-run OSRM forced
            // through Google's path so we follow the traffic-smart route WITH full street-named steps.
            // (Only on real divergence, so the normal case stays the fast single OSRM call.)
            val trafficRoute = if (open.isNotEmpty() && gTop != null && gTop.polyline.size >= 5 &&
                RouteGeometry.divergent(open.first(), gTop)) {
                RouteGeometry.routeVia(http, listOf(origin) + RouteGeometry.sampleVias(gTop.polyline) + destination, mode, avoidTolls, avoidHighways)
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
                // OSRM unreachable → the on-device offline route, or Google's abbreviated one, whichever
                // we have. The Google routes are TAGGED abbreviated so an adopted one can be silently
                // upgraded to full steps by the nav recheck once the open router recovers.
                if (onDevice.isNotEmpty()) onDevice else google.map { it.copy(abbreviatedSteps = true) }
            } else {
                val primary = if (snapWorthIt) (listOf(trafficRoute!!) + open).map { applyTraffic(it, gTop) }
                    else open.map { applyTraffic(it, gTop) }
                // ALTERNATES to choose from = Google's OWN alternate routes (the real, traffic-aware ones you
                // miss). Kept PROVISIONAL: their polyline + live ETA are shown now, but turn-by-turn is named
                // only when you PICK one to drive ([nameRoute]) — so the picker loads fast and we never snap a
                // route you don't take. Google's routes already carry duration_in_traffic + congestion spans.
                val googleAlts = google.drop(1).filter { it.polyline.size >= 5 }.map { it.copy(provisional = true) }
                // Rank by live in-traffic ETA so the FASTEST-right-now route leads (Google-style), sorting by
                // the EXACT value the picker shows (`durationInTrafficSeconds ?: durationSeconds`, RouteOption)
                // so the top/selected route is always the one the picker tags "Fastest" — otherwise the sort
                // and the displayed times disagree and the fastest-shown route isn't at the top (the bug).
                // The traffic axis is already fair without a fudge factor: PRIMARY routes are run through
                // applyTraffic above (their durationInTrafficSeconds = free-flow × the top Google route's
                // in-traffic ratio), and Google's own alternates carry their real per-route duration_in_traffic
                // — so a route only falls back to raw durationSeconds when there's genuinely no traffic signal
                // for it, and then showing/sorting that free-flow time is the honest, self-consistent fallback.
                // Dedupe by path (keeps the fastest of look-alikes) + cap so the picker stays short.
                dedupeRoutes(
                    (listOf(primary.first()) + googleAlts + primary.drop(1))
                        .sortedWith(
                            compareBy(
                                { it.durationInTrafficSeconds ?: it.durationSeconds },
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
    private fun chainOnDevice(points: List<LatLng>, mode: TravelMode, avoidTolls: Boolean = false, avoidHighways: Boolean = false): Route? {
        val legs = points.zipWithNext().map { (a, b) ->
            runCatching { routeEngine.route(a, b, mode, avoidTolls, avoidHighways).firstOrNull() }.getOrNull() ?: return null
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
    override suspend fun nameRoute(route: Route, origin: LatLng, destination: LatLng, mode: TravelMode, avoidTolls: Boolean, avoidHighways: Boolean): Route = io {
        if (!route.provisional || route.polyline.size < 3) return@io route.copy(provisional = false)
        val vias = listOf(origin) + RouteGeometry.sampleVias(route.polyline) + destination
        // The avoid flags ride along even on a snap: the vias FORCE Google's chosen path, but
        // exclude keeps OSRM from bridging between vias over a road class the user opted out of.
        val named = RouteGeometry.routeVia(http, vias, mode, avoidTolls, avoidHighways).firstOrNull()
            ?.takeIf { it.polyline.lastOrNull()?.let { p -> p.distanceTo(destination) <= SNAP_REACH_M } == true }
        // Keep the route's OWN time figures through the snap. The picker sorted and displayed this
        // route by its Google per-route ETA; applyTraffic here would swap in a recomputed one
        // (OSRM free-flow x the ratio) IN PLACE, which can leapfrog a neighbouring row and leave
        // the "Fastest" tag sitting below a slower first row. Naming is for geometry + named
        // turn-by-turn (and the congestion spans remapped onto that geometry), not a new ETA.
        if (named != null) applyTraffic(named, route).copy(
            provisional = false,
            durationSeconds = route.durationSeconds,
            durationInTrafficSeconds = route.durationInTrafficSeconds,
        )
        // Naming failed: nav runs on Google's abbreviated steps. Tagged so the in-drive recheck
        // can silently upgrade to full steps once the open router answers again.
        else route.copy(provisional = false, abbreviatedSteps = true)
    }

    /** [googleDirections] with the same transient-blip retry the OSRM path has (routeOsrm goes
     *  3x with backoff; Google got ONE shot). The keyless endpoint intermittently hands back a
     *  degraded or empty reply — worst right after a burst of requests, e.g. ending a route and
     *  restarting it — and a single miss used to cost the whole fetch its traffic ratio, its
     *  jam-avoiding snap AND Google's alternates: the picker then led with free-flow OSRM routes
     *  whose white, trafficless ETAs read minutes faster than anything traffic-aware and varied
     *  drastically between restarts (user real-drive report 2026-07-14). Two short backoff
     *  retries recover the routine blips; a genuinely unreachable Google still degrades to
     *  free-flow exactly as before, just honestly rarer. */
    private suspend fun googleDirectionsRetried(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route> {
        var routes: List<Route> = emptyList()
        for (attempt in 0..2) {
            if (attempt > 0) kotlinx.coroutines.delay(300L * attempt)
            routes = runCatching { googleDirections(origin, destination, mode) }.getOrNull().orEmpty()
            if (routes.isNotEmpty()) return routes
        }
        diag.record("directions", "google directions empty after 3 attempts — trafficless fetch")
        return routes
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

    /** Google Maps shared-list import (issue #1), fully keyless. The share link resolves
     *  logged-out to the list page, whose HTML embeds a READY-MADE prefetch URL for the
     *  `/maps/preview/entitylist/getlist` RPC (list id + page session token included) —
     *  lift it verbatim, call it, parse. No pb construction, so a Google-side pb change
     *  can't break the URL builder (only the parser paths, which are documented in
     *  [EntityListParser]). Calibrated live 2026-07-08. */
    override suspend fun importList(shareUrl: String): app.vela.core.model.ImportedList? = io {
        runCatching {
            session.ensure()
            val html = get(shareUrl.trim())
            val href = Regex("""(/maps/preview/entitylist/getlist\?[^"'\s]+)""")
                .find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?: return@runCatching null
            EntityListParser.parse(get("https://www.google.com$href"))
        }.getOrNull()
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

    /** GET raw bytes (Street View tiles) with the Google referer the tile host requires. */
    private fun getBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Referer", "https://www.google.com/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
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
        val out = regionalized()
        val locale = java.util.Locale.getDefault()
        val lang = locale.language.lowercase()
        // Only rewrite to a language the STATUS parser can read (SearchParser.STATUS_LANGS). For any
        // other locale, keep hl=en: an unparseable status string leaves openNow null forever and the
        // UI can't colour open/closed — English status text the English table handles is the safer
        // fallback than localized-but-unparseable (audit 2026-07-06).
        if (lang == "en" || lang !in SearchParser.STATUS_LANGS) return out
        // Chinese needs the SCRIPT in the hl tag: hl=zh-TW answers Traditional, hl=zh-CN Simplified
        // (bare hl=zh is treated as Simplified). parseOpenNow keys on the bare "zh" either way —
        // its keyword table carries both scripts.
        val hl = if (lang == "zh") {
            val hant = locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase() in setOf("TW", "HK", "MO")
            if (hant) "zh-TW" else "zh-CN"
        } else lang
        return out.replace("hl=en", "hl=$hl")
    }

    /** Rewrite `gl=us` to the REGION the phone is actually in ([glRegion], set by the app layer
     *  from the cell network's country, falling back to the locale's) so results are biased to
     *  where the user is, not to the US — the "US-shaped results in Jerusalem" half of issue #71.
     *  Region only tunes ranking/bias, not the response SHAPE (hl already varies per language and
     *  the parsers are shape-searched), so this is safe for any 2-letter code; anything else is
     *  ignored and gl=us stays. No-op for US users: byte-for-byte unchanged. */
    private fun String.regionalized(): String {
        val region = glRegion?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
            ?: return this
        if (region == "us") return this
        return replace("gl=us", "gl=$region")
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
        // Radii (m) to probe out toward the street when the geocode is set-back from it. Two rings
        // cover the usual sidewalk+setback distance; each ring probes both perpendiculars → 4 probes.
        val STREET_PROBE_RADII_M = doubleArrayOf(24.0, 40.0)
    }
}
