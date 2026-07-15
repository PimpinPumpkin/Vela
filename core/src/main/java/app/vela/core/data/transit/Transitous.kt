package app.vela.core.data.transit

import app.vela.core.model.StopDeparture
import app.vela.core.model.StopDepartureLine
import app.vela.core.model.StopDepartures
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * **Transitous** (transitous.org) - the community-run, keyless public-transit API over the world's
 * open GTFS + GTFS-Realtime feeds (MOTIS server). It is to transit what FOSSGIS OSRM is to road
 * routing: canonical agency data, no account, fair-use community hosting.
 *
 * This client covers Vela's DEPARTURE BOARDS (phase 1 of the Transitous adoption): [board] finds the
 * stop(s) at a coordinate via `map/stops` and reads `stoptimes` - which, unlike Google's anonymous
 * place page, returns EVERY route serving the stop, with realtime flags and the agency's own route
 * colours. Querying a stop's PARENT station id aggregates all its child stops/bays (verified live),
 * so a multi-bay transit center gets one complete merged board for free.
 *
 * Google's blob parse stays as the FALLBACK where Transitous has no coverage. Fair use: one fetch
 * per opened stop plus a 30 s refresh while its sheet stays open (the VM's startBoardRefresh, same
 * cadence as the countdown clock, self-cancelling on selection change); the User-Agent identifies
 * the app per the Transitous policy.
 */
object Transitous {
    // The community instance. A self-hosted MOTIS is a drop-in swap if Vela ever outgrows fair use.
    const val BASE = "https://api.transitous.org"
    private const val UA = "VelaMaps/0.4 (+https://github.com/alltechdev/vela-dpad)"
    private val json = Json { ignoreUnknownKeys = true }

    // --- wire DTOs (only the fields Vela reads) --------------------------------------------------

    @Serializable
    data class MapStop(
        val name: String = "",
        val stopId: String = "",
        val parentId: String? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        // Same-named directional siblings folded into this icon (never on the wire - filled by
        // mergeDirectionalPairs). Their boards merge into this stop's board.
        val siblingIds: List<String> = emptyList(),
    )

    @Serializable
    private data class StopTimesResp(val stopTimes: List<StopTime> = emptyList())

    @Serializable
    data class StopTime(
        val place: StPlace = StPlace(),
        val mode: String? = null,
        val realTime: Boolean = false,
        val headsign: String? = null,
        val routeShortName: String? = null,
        val routeColor: String? = null,
        val tripId: String? = null,       // keys the /trip stop-sequence fetch
        val cancelled: Boolean = false,   // this stop's call is cancelled
        val tripCancelled: Boolean = false, // the whole run is cancelled
    )

    @Serializable
    data class StPlace(
        val departure: String? = null,
        val scheduledDeparture: String? = null,
        val tz: String? = null,
        val cancelled: Boolean = false,
    )

    // --- API --------------------------------------------------------------------------------------

    /** All transit stops inside the bbox. Null on FAILURE (network/decode) vs empty on a clean
     *  "no stops here" - callers area-cache success only, like the traffic-controls layer. */
    fun stopsInBox(http: OkHttpClient, south: Double, west: Double, north: Double, east: Double): List<MapStop>? {
        val body = get(http, "$BASE/api/v1/map/stops?min=$south,$west&max=$north,$east") ?: return null
        return runCatching { json.decodeFromString<List<MapStop>>(body) }.getOrNull()
    }

    /** Transit stops within roughly [radiusM] of the point, nearest first. Empty on any failure. */
    fun stopsNear(http: OkHttpClient, lat: Double, lng: Double, radiusM: Double = 200.0): List<MapStop> {
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return stopsInBox(http, lat - dLat, lng - dLng, lat + dLat, lng + dLng).orEmpty()
            .sortedBy { distM(lat, lng, it.lat, it.lon) }
    }

    /** The board for a KNOWN stop (a tapped map icon) - no proximity lookup needed. Queries the
     *  parent station when the stop has one, so a hub icon shows the whole merged board. */
    fun boardFor(http: OkHttpClient, stop: MapStop): StopDepartures? {
        val ids = (listOf(stop.parentId ?: stop.stopId) + stop.siblingIds).distinct()
        val times = ids.flatMap { stopTimes(http, it) }.ifEmpty { return null }
        return buildBoard(times, stationName = stop.name)
    }

    /** The next [n] departures at [stopId] (a parent-station id aggregates all its child stops). */
    fun stopTimes(http: OkHttpClient, stopId: String, n: Int = 50): List<StopTime> {
        val url = "$BASE/api/v1/stoptimes?stopId=${URLEncoder.encode(stopId, "UTF-8")}&n=$n"
        val body = get(http, url) ?: return emptyList()
        return runCatching { json.decodeFromString<StopTimesResp>(body).stopTimes }.getOrDefault(emptyList())
    }

    /**
     * The full departure board for the stop at ([lat], [lng]): nearest stop GROUP within ~200 m
     * (grouped by parent station so a hub's bays merge into one board), grouped by (route, headsign)
     * into the same [StopDepartures] model the Google-blob parser feeds - the whole board UI (pills,
     * countdowns, day markers) renders it unchanged. Null when Transitous has nothing here (no
     * coverage, no stop, network failure) - the caller falls back to the Google path.
     */
    fun board(http: OkHttpClient, lat: Double, lng: Double): StopDepartures? {
        val stops = stopsNear(http, lat, lng)
        if (stops.isEmpty()) return null
        // Prefer the nearest stop's PARENT station (aggregates every bay), and fold in any
        // same-named sibling nearby - the two curbs of a directional pair - so one board carries
        // both directions, told apart by their headsigns (Google's treatment).
        val nearest = stops.first()
        val ids = stops
            .filter { it.name == nearest.name && distM(nearest.lat, nearest.lon, it.lat, it.lon) < PAIR_MERGE_M }
            .map { it.parentId ?: it.stopId }
            .distinct()
        val times = ids.flatMap { stopTimes(http, it) }.ifEmpty { return null }
        return buildBoard(times, stationName = nearest.name)
    }

    /** Fold same-named stops within [radiusM] into ONE map icon at the cluster midpoint, carrying
     *  the rest as [MapStop.siblingIds] - the two curbs of a directional pair become one stop like
     *  Google's POI, and the merged board's headsigns tell the directions apart. Distinct names
     *  (a "NB Station"/"SB Station" pair) and far-apart same names both stay separate. */
    fun mergeDirectionalPairs(stops: List<MapStop>, radiusM: Double = PAIR_MERGE_M): List<MapStop> =
        stops.groupBy { it.name }.flatMap { (_, group) ->
            if (group.size < 2) return@flatMap group
            val clusters = mutableListOf<MutableList<MapStop>>()
            for (st in group) {
                val home = clusters.firstOrNull { cl ->
                    cl.any { distM(it.lat, it.lon, st.lat, st.lon) < radiusM }
                }
                if (home != null) home.add(st) else clusters.add(mutableListOf(st))
            }
            clusters.map { cl ->
                if (cl.size == 1) cl.first()
                else cl.first().copy(
                    lat = cl.sumOf { it.lat } / cl.size,
                    lon = cl.sumOf { it.lon } / cl.size,
                    siblingIds = cl.drop(1).map { it.stopId },
                )
            }
        }

    private const val PAIR_MERGE_M = 160.0

    /** Pure grouping of raw stop times into the board model (unit-tested; no network). */
    internal fun buildBoard(times: List<StopTime>, stationName: String?, nowMs: Long = System.currentTimeMillis()): StopDepartures? {
        data class Key(val label: String?, val headsign: String?)
        val groups = LinkedHashMap<Key, MutableList<StopTime>>()
        for (t in times) {
            if (t.place.cancelled || t.cancelled || t.tripCancelled) continue
            groups.getOrPut(Key(t.routeShortName, t.headsign)) { mutableListOf() }.add(t)
        }
        if (groups.isEmpty()) return null
        val lines = groups.map { (k, ts) ->
            val deps = ts.mapNotNull { t ->
                val iso = t.place.departure ?: t.place.scheduledDeparture ?: return@mapNotNull null
                val epoch = parseIso(iso) ?: return@mapNotNull null
                StopDeparture(
                    clockText = clockText(epoch, t.place.tz),
                    epochSec = epoch,
                    // realTime = the feed is live-tracking this run; that's the green-dot signal.
                    realtime = t.realTime,
                    tripId = t.tripId,
                )
            }.sortedBy { it.epochSec ?: Long.MAX_VALUE }
                // Two agencies (or a parent + its curb twin) can both publish the same physical
                // stop, and the sibling merge then feeds the same run in twice - every departure
                // showed doubled (7:25, 7:25, 8:23, 8:23; upstream device-seen 2026-07-13). Same
                // line + same departure minute is the same bus regardless of which feed copy it
                // rode in on, so collapse on the epoch (tripIds differ across agency copies -
                // can't key on those).
                .distinctBy { it.epochSec }
            StopDepartureLine(
                label = k.label,
                mode = modeOf(ts.firstOrNull()?.mode),
                headsign = k.headsign,
                colorHex = ts.firstOrNull()?.routeColor?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("#")) it else "#$it" },
                headwayText = null,
                upcoming = deps,
            )
        }
            .filter { it.upcoming.isNotEmpty() }
            .sortedBy { it.upcoming.firstOrNull()?.epochSec ?: Long.MAX_VALUE }
        if (lines.isEmpty()) return null
        return StopDepartures(stationName = stationName?.takeIf { it.isNotBlank() }, lines = lines)
    }

    // --- trip stop sequence (the "Stops" timeline) -------------------------------------------------

    @Serializable
    private data class TripResp(val legs: List<TripLeg> = emptyList())

    @Serializable
    data class TripLeg(
        val from: TripStop = TripStop(),
        val to: TripStop = TripStop(),
        val intermediateStops: List<TripStop> = emptyList(),
        val mode: String? = null,
        val headsign: String? = null,
        val routeShortName: String? = null,
        val displayName: String? = null,
        val routeColor: String? = null,
        val routeTextColor: String? = null,
        val realTime: Boolean = false,
        val cancelled: Boolean = false,
    )

    @Serializable
    data class TripStop(
        val name: String = "",
        val stopId: String = "",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val arrival: String? = null,
        val departure: String? = null,
        val scheduledArrival: String? = null,
        val scheduledDeparture: String? = null,
        val cancelled: Boolean = false,
        val tz: String? = null,
        val stopCode: String? = null,
    )

    /**
     * The FULL stop sequence of one GTFS run - the "Stops" timeline behind a departure-board line.
     * `/api/v1/trip` returns the actual trip the tapped departure belongs to: every stop it calls at,
     * with per-stop realtime vs timetable times AND per-stop/-run CANCELLED flags straight from the
     * agency feed - none of which the Google itinerary reuse could provide. The result is trimmed to
     * start at the stop nearest ([atLat],[atLng]) (the stop whose board was tapped), mapped into the
     * SAME [TransitStep] the timeline UI already renders. Null on any failure - the caller falls back
     * to the itinerary-reuse path.
     */
    fun tripStops(http: OkHttpClient, tripId: String, atLat: Double, atLng: Double): TransitStep? {
        val body = get(http, "$BASE/api/v1/trip?tripId=${URLEncoder.encode(tripId, "UTF-8")}") ?: return null
        val leg = runCatching { json.decodeFromString<TripResp>(body).legs.firstOrNull() }.getOrNull() ?: return null
        return buildTripStep(leg, atLat, atLng)
    }

    /** Pure mapping of a trip leg into the timeline's [TransitStep] (unit-tested; no network). */
    internal fun buildTripStep(leg: TripLeg, atLat: Double, atLng: Double): TransitStep? {
        val all = buildList {
            add(leg.from)
            addAll(leg.intermediateStops)
            add(leg.to)
        }.filter { it.name.isNotBlank() }
        if (all.size < 2) return null
        // The timeline BOARDS at the tapped stop (nearest-by-distance, so it works from a canonical
        // GTFS stop AND from a Google-resolved listing on a different corner); the stops the run
        // already called at go into priorStops so the view can show them greyed above, Google-style.
        // A terminus tap boards at the origin instead (an arrivals-only view has no ride left).
        val idx = (all.indices.minByOrNull { i -> distM(atLat, atLng, all[i].lat, all[i].lon) } ?: 0)
            .let { if (it >= all.size - 1) 0 else it }
        val prior = all.subList(0, idx).map { st -> stopTime(st, legCancelled = leg.cancelled) }
        val mapped = all.subList(idx, all.size).map { st -> stopTime(st, legCancelled = leg.cancelled) }
        return TransitStep(
            mode = modeOf(leg.mode),
            line = TransitLine(
                name = leg.routeShortName ?: leg.displayName ?: "",
                mode = modeOf(leg.mode),
                colorHex = leg.routeColor?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("#")) it else "#$it" },
                textColorHex = leg.routeTextColor?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("#")) it else "#$it" },
            ),
            headsign = leg.headsign,
            boardStop = mapped.first(),
            alightStop = mapped.last(),
            intermediateStops = mapped.drop(1).dropLast(1),
            numStops = mapped.size - 1,
            departText = mapped.first().timeText,
            arriveText = mapped.last().timeText,
            priorStops = prior,
        )
    }

    private fun stopTime(st: TripStop, legCancelled: Boolean): TransitStopTime {
        val shown = st.departure ?: st.arrival ?: st.scheduledDeparture ?: st.scheduledArrival
        val sched = st.scheduledDeparture ?: st.scheduledArrival
        val shownEpoch = shown?.let { parseIso(it) }
        val schedEpoch = sched?.let { parseIso(it) }
        val moved = schedEpoch != null && shownEpoch != null && schedEpoch != shownEpoch
        return TransitStopTime(
            name = st.name,
            code = st.stopCode,
            timeText = shownEpoch?.let { clockText(it, st.tz) },
            // The timetable time, kept ONLY when realtime moved the shown time - the row UI reads
            // "scheduled differs" as the live signal, same contract as the itinerary parser.
            scheduledText = if (moved) clockText(schedEpoch!!, st.tz) else null,
            location = app.vela.core.model.LatLng(st.lat, st.lon),
            cancelled = st.cancelled || legCancelled,
            // Signed minutes off the timetable (negative = early) so the row can colour a late
            // call differently from an early one - the feed carries both (verified live).
            delayMin = if (moved) (((shownEpoch!! - schedEpoch!!) / 60).toInt()) else null,
        )
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun get(http: OkHttpClient, url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    /** ISO-8601 UTC ("2026-07-13T20:26:00Z") to epoch seconds. */
    internal fun parseIso(iso: String): Long? = runCatching { java.time.Instant.parse(iso).epochSecond }.getOrNull()

    /** 12-hour clock text in the STOP's timezone (falls back to the device zone), matching the
     *  Google-board format the row UI and its TIME-based logic already render. */
    internal fun clockText(epochSec: Long, tz: String?): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        fmt.timeZone = tz?.let { runCatching { TimeZone.getTimeZone(it) }.getOrNull() } ?: TimeZone.getDefault()
        return fmt.format(Date(epochSec * 1000))
    }

    private fun modeOf(mode: String?): TransitMode = when (mode?.uppercase()) {
        "BUS", "COACH" -> TransitMode.BUS
        "TRAM" -> TransitMode.TRAM
        "SUBWAY", "METRO" -> TransitMode.SUBWAY
        "RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL" -> TransitMode.TRAIN
        "FERRY" -> TransitMode.FERRY
        else -> TransitMode.GENERIC
    }

    private fun distM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val mPerLng = 111_320.0 * Math.cos(Math.toRadians(aLat))
        val dx = (aLng - bLng) * mPerLng
        val dy = (aLat - bLat) * 111_320.0
        return Math.sqrt(dx * dx + dy * dy)
    }
}
