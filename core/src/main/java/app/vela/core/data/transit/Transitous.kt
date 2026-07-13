package app.vela.core.data.transit

import app.vela.core.model.StopDeparture
import app.vela.core.model.StopDepartureLine
import app.vela.core.model.StopDepartures
import app.vela.core.model.TransitMode
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
 * Google's blob parse stays as the FALLBACK where Transitous has no coverage. Fair use: called once
 * per opened stop (no polling); the User-Agent identifies the app per the Transitous policy.
 */
object Transitous {
    // The community instance. A self-hosted MOTIS is a drop-in swap if Vela ever outgrows fair use.
    const val BASE = "https://api.transitous.org"
    private const val UA = "VelaMaps/0.4 (+https://github.com/PimpinPumpkin/Vela)"
    private val json = Json { ignoreUnknownKeys = true }

    // --- wire DTOs (only the fields Vela reads) --------------------------------------------------

    @Serializable
    data class MapStop(
        val name: String = "",
        val stopId: String = "",
        val parentId: String? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
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
        val times = stopTimes(http, stop.parentId ?: stop.stopId).ifEmpty { return null }
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
        // Prefer the nearest stop's PARENT station (aggregates every bay); fall back to the stop itself.
        val nearest = stops.first()
        val queryId = nearest.parentId ?: nearest.stopId
        val times = stopTimes(http, queryId).ifEmpty { return null }
        return buildBoard(times, stationName = nearest.name)
    }

    /** Pure grouping of raw stop times into the board model (unit-tested; no network). */
    internal fun buildBoard(times: List<StopTime>, stationName: String?, nowMs: Long = System.currentTimeMillis()): StopDepartures? {
        data class Key(val label: String?, val headsign: String?)
        val groups = LinkedHashMap<Key, MutableList<StopTime>>()
        for (t in times) {
            if (t.place.cancelled) continue
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
                )
            }.sortedBy { it.epochSec ?: Long.MAX_VALUE }
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
