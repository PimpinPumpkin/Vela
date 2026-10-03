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
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import app.vela.core.model.TransitItinerary
import app.vela.core.model.LatLng
import org.json.JSONObject
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
 * colors. Querying a stop's PARENT station id aggregates all its child stops/bays (verified live),
 * so a multi-bay transit center gets one complete merged board for free.
 *
 * Google's blob parse stays as the FALLBACK where Transitous has no coverage. Fair use: one fetch
 * per opened stop plus a 30 s refresh while its sheet stays open (the VM's startBoardRefresh, same
 * cadence as the countdown clock, self-canceling on selection change); the User-Agent identifies
 * the app per the Transitous policy.
 */
object Transitous {
    // The community instance. A self-hosted MOTIS is a drop-in swap if Vela ever outgrows fair use.
    const val BASE = "https://api.transitous.org"
    // Single-sourced: Transitous is community infrastructure under fair use, so its contact string
    // must not drift from the app's real version the way the Overpass/Nominatim copies had.
    private const val UA = app.vela.core.VelaConfig.VELA_UA
    private val json = Json { ignoreUnknownKeys = true }

    // --- wire DTOs (only the fields Vela reads) --------------------------------------------------

    @Serializable
    data class MapStop(
        val name: String = "",
        val stopId: String = "",
        val parentId: String? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        // What stops here ("BUS", "SUBWAY", "TRAM", "REGIONAL_RAIL", ...), as the service names them.
        val modes: List<String> = emptyList(),
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
        val cancelled: Boolean = false,   // this stop's call is canceled (MOTIS spells the wire field with two Ls - do not Americanize these four)
        val tripCancelled: Boolean = false, // the whole run is canceled
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

    /** Which of the three kinds the map lets a rider pick a stop or a line belongs to. */
    enum class Kind { BUS, METRO, TRAIN }

    /** The kind a service mode name is: metro covers subway, tram, light rail, funicular and
     *  cable car; train every kind of rail; everything on a road is a bus. Null for boats and air. */
    fun kindOf(mode: String): Kind? = when {
        mode == "SUBWAY" || mode == "METRO" || mode == "TRAM" || mode == "FUNICULAR" || mode == "CABLE_CAR" || mode == "AERIAL_LIFT" -> Kind.METRO
        mode.contains("RAIL") || mode == "SUBURBAN" || mode == "LONG_DISTANCE" || mode == "TRANSIT" -> Kind.TRAIN
        mode == "BUS" || mode == "COACH" || mode == "ODM" || mode == "FLEX" -> Kind.BUS
        else -> null
    }

    /** One stretch of track with the colors of the lines that run on it. */
    data class MapLine(
        val points: List<LatLng>,
        val colors: List<String>,
        val kind: Kind,
        /** The metro lines on this stretch as (short name, "#rrggbb"), sorted the way a station
         *  sign lists them (numbers, then letters). Empty for trains. */
        val labels: List<Pair<String, String>> = emptyList(),
    )

    @Serializable private data class RoutesReply(val routes: List<RouteDto> = emptyList(), val polylines: List<PolyDto> = emptyList())
    @Serializable private data class RouteDto(val mode: String = "", val transitRoutes: List<RouteNameDto> = emptyList())
    @Serializable private data class RouteNameDto(val color: String? = null, val shortName: String? = null)
    @Serializable private data class PolyDto(val polyline: EncodedDto = EncodedDto(), val routeIndexes: List<Int> = emptyList())
    @Serializable private data class EncodedDto(val points: String = "", val precision: Int = 6)

    /**
     * The rail lines in a box, each stretch with its lines' own colors (discussion #648): the
     * service's `map/routes`, which answers every route's shape in the box. Buses, coaches and
     * boats are dropped here (most of the answer); what is left is metro and train track,
     * thinned to [LINE_SIMPLIFY_M]. [zoom] is the service's own filter: under about 9 it sends
     * long-distance rail only, which keeps a wide view's answer small. Null on any failure.
     * The endpoint is marked experimental upstream, so a changed shape reads as "no lines" and
     * the map keeps its plain rail highlight.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun linesInBox(http: OkHttpClient, south: Double, west: Double, north: Double, east: Double, zoom: Int): List<MapLine>? = runCatching {
        http.newCall(Request.Builder().url("$BASE/api/experimental/map/routes?min=$south,$west&max=$north,$east&zoom=$zoom").header("User-Agent", UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            // Read as a stream: a city's answer is tens of megabytes of text once unpacked.
            linesOf(json.decodeFromStream(RoutesReply.serializer(), body.byteStream()))
        }
    }.getOrNull()

    /** Public for the parser test. */
    fun parseLines(text: String): List<MapLine> = runCatching { linesOf(json.decodeFromString(RoutesReply.serializer(), text)) }.getOrDefault(emptyList())

    private fun linesOf(reply: RoutesReply): List<MapLine> {
        val out = ArrayList<MapLine>()
        for (p in reply.polylines) {
            val routes = p.routeIndexes.mapNotNull { reply.routes.getOrNull(it) }
            val rail = routes.filter { kindOf(it.mode).let { k -> k == Kind.METRO || k == Kind.TRAIN } }
            if (rail.isEmpty()) continue
            val kind = if (rail.any { kindOf(it.mode) == Kind.METRO }) Kind.METRO else Kind.TRAIN
            val colors = rail.mapNotNull { r -> r.transitRoutes.firstOrNull()?.color?.trim()?.lowercase()?.takeIf { c -> c.length == 6 && c.all { it in '0'..'9' || it in 'a'..'f' } } }
                .distinct().take(MAX_LINE_COLORS).map { "#$it" }
            // Line letters for the metro stretches (#648: same-color lines like the M and F could
            // not be told apart; shared colors are one strand, so only a name tells them apart).
            val labels = rail.filter { kindOf(it.mode) == Kind.METRO }.mapNotNull { r ->
                val t = r.transitRoutes.firstOrNull() ?: return@mapNotNull null
                val name = t.shortName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_CHARS } ?: return@mapNotNull null
                val c = t.color?.trim()?.lowercase()?.takeIf { c -> c.length == 6 && c.all { it in '0'..'9' || it in 'a'..'f' } }
                name to (if (c != null) "#$c" else "#9AA0A6")
            }.distinctBy { it.first }
                // An express variant ("6X", "FX") is the same line as its base: list the base once.
                .let { all -> val names = all.map { it.first }.toSet(); all.filterNot { it.first.length > 1 && it.first.endsWith("X") && it.first.dropLast(1) in names } }
                .sortedWith(compareBy<Pair<String, String>>({ it.first.toIntOrNull() == null }, { it.first.toIntOrNull() ?: 0 }, { it.first }))
                .take(MAX_LABELS)
            val pts = runCatching { app.vela.core.data.google.PolylineCodec.decode(p.polyline.points, p.polyline.precision) }.getOrNull() ?: continue
            if (pts.size < 2) continue
            // A feed with no shapes gives a straight chord from stop to stop. Between two subway
            // stations that is close enough; between two cities it is a ruler line across the
            // map (a first look at Manhattan was a fan of them), so sparse long ones are left out
            // and the plain highlight shows that track instead.
            // A shape can also be dense through the city and one ruler jump out to the suburbs,
            // which the average hides (discussion #648: commuter rail drew straight lines out of
            // Manhattan). Cut it at every gap over CHORD_SPLIT_M and judge each run on its own.
            var run = ArrayList<LatLng>()
            fun flush() {
                if (run.size >= 2) {
                    var len = 0.0
                    for (i in 1 until run.size) len += distM(run[i - 1].lat, run[i - 1].lng, run[i].lat, run[i].lng)
                    if (len / (run.size - 1) <= CHORD_MAX_M) out += MapLine(simplify(run, LINE_SIMPLIFY_M), colors, kind, labels)
                }
                run = ArrayList()
            }
            for (i in pts.indices) {
                if (i > 0 && distM(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng) > CHORD_SPLIT_M) flush()
                run.add(pts[i])
            }
            flush()
        }
        return out
    }

    const val MAX_LINE_COLORS = 4
    /** Line letters shown per stretch, and the longest short name that counts as a letter. */
    const val MAX_LABELS = 6
    private const val MAX_LABEL_CHARS = 3
    private const val LINE_SIMPLIFY_M = 4.0
    /** Average spacing of a shape's points above which it is a chord, not a drawn track. */
    private const val CHORD_MAX_M = 700.0
    /** A single gap this long between two points of a shape is a chord: the shape is cut there. */
    private const val CHORD_SPLIT_M = 2000.0

    /** Douglas-Peucker: drops points that sit within [tolM] of the line through their neighbors. */
    internal fun simplify(pts: List<LatLng>, tolM: Double): List<LatLng> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to pts.lastIndex)
        val k = Math.cos(Math.toRadians(pts[0].lat))
        fun off(p: LatLng, a: LatLng, b: LatLng): Double {
            val ax = (a.lng - p.lng) * k * 111_320.0; val ay = (a.lat - p.lat) * 111_320.0
            val bx = (b.lng - p.lng) * k * 111_320.0; val by = (b.lat - p.lat) * 111_320.0
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            return Math.hypot(ax + t * dx, ay + t * dy)
        }
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            var worst = -1; var worstD = tolM
            for (m in i + 1 until j) { val d = off(pts[m], pts[i], pts[j]); if (d > worstD) { worstD = d; worst = m } }
            if (worst >= 0) { keep[worst] = true; stack.addLast(i to worst); stack.addLast(worst to j) }
        }
        return pts.filterIndexed { idx, _ -> keep[idx] }
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
    fun boardFor(http: OkHttpClient, stop: MapStop, timeEpochSec: Long? = null): StopDepartures? {
        val ids = (listOf(stop.parentId ?: stop.stopId) + stop.siblingIds).distinct()
        val times = timesFor(http, ids, timeEpochSec).ifEmpty { return null }
        return buildBoard(times, stationName = stop.name)
    }

    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "transitous").apply { isDaemon = true } }

    /** Departures for every id, fetched in parallel, in id order. */
    private fun timesFor(http: OkHttpClient, ids: List<String>, timeEpochSec: Long? = null): List<StopTime> =
        if (ids.size <= 1) ids.flatMap { stopTimes(http, it, timeEpochSec = timeEpochSec) }
        else ids.map { id -> pool.submit<List<StopTime>> { stopTimes(http, id, timeEpochSec = timeEpochSec) } }
            .flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }

    /** The next [n] departures at [stopId] (a parent-station id aggregates all its child stops),
     *  from now or from [timeEpochSec]. */
    fun stopTimes(http: OkHttpClient, stopId: String, n: Int = 50, timeEpochSec: Long? = null): List<StopTime> {
        val url = "$BASE/api/v1/stoptimes?stopId=${URLEncoder.encode(stopId, "UTF-8")}&n=$n" +
            (timeEpochSec?.let { "&time=" + java.time.Instant.ofEpochSecond(it) } ?: "")
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
    fun board(http: OkHttpClient, lat: Double, lng: Double, timeEpochSec: Long? = null): StopDepartures? {
        val stops = stopsNear(http, lat, lng)
        if (stops.isEmpty()) return null
        // Prefer the nearest stop's PARENT station (aggregates every bay), and fold in any
        // same-named sibling nearby - the two curbs of a directional pair - so one board carries
        // both directions, told apart by their headsigns (Google's treatment).
        val nearest = stops.first()
        val key = stopKey(nearest.name)
        val ids = stops
            .filter { (stopKey(it.name) == key && distM(nearest.lat, nearest.lon, it.lat, it.lon) < PAIR_MERGE_M) ||
                distM(nearest.lat, nearest.lon, it.lat, it.lon) < COLOCATED_M }
            .map { it.parentId ?: it.stopId }
            .distinct()
        val times = timesFor(http, ids, timeEpochSec).ifEmpty { return null }
        return buildBoard(times, stationName = nearest.name)
    }

    /** Fold same-named stops within [radiusM] into ONE map icon at the cluster midpoint, carrying
     *  the rest as [MapStop.siblingIds] - the two curbs of a directional pair become one stop like
     *  Google's POI, and the merged board's headsigns tell the directions apart. Distinct names
     *  (a "NB Station"/"SB Station" pair) and far-apart same names both stay separate. */
    fun mergeDirectionalPairs(stops: List<MapStop>, radiusM: Double = PAIR_MERGE_M): List<MapStop> =
        mergeColocated(stops).groupBy { stopKey(it.name) }.flatMap { (_, group) ->
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
                    name = displayName(cl.map { it.name }),
                    lat = cl.sumOf { it.lat } / cl.size,
                    lon = cl.sumOf { it.lon } / cl.size,
                    siblingIds = (cl.drop(1).map { it.stopId } + cl.flatMap { it.siblingIds }).distinct(),
                )
            }
        }.map { if (it.name == it.name.uppercase() && it.name.any(Char::isLetter)) it.copy(name = displayName(listOf(it.name))) else it }

    /**
     * ONE PHYSICAL STOP, SEVERAL FEEDS (2026-09-22, user: "bus icons right next to each other on the
     * same side of the same block" in Midtown). MTA publishes a GTFS feed per borough plus MTA Bus
     * Company, and a Manhattan corner served by an express route appears in two or three of them at
     * the SAME coordinate; NY Waterway lists it again under "E 42nd St & Madison Ave", and Times
     * Square is four subway stations on one point. Stops within [COLOCATED_M] fold whatever their
     * names (132 stops around Bryant Park, 107 pairs within 30 m). The radius stays tiny on purpose:
     * a BRT's NB and SB platforms ~11 m apart are separate stops with separate names.
     */
    fun mergeColocated(stops: List<MapStop>): List<MapStop> {
        val clusters = mutableListOf<MutableList<MapStop>>()
        for (st in stops) {
            val home = clusters.firstOrNull { cl -> cl.any { distM(it.lat, it.lon, st.lat, st.lon) < COLOCATED_M } }
            if (home != null) home.add(st) else clusters.add(mutableListOf(st))
        }
        return clusters.map { cl ->
            if (cl.size == 1) cl.first()
            else cl.first().copy(
                name = displayName(cl.map { it.name }),
                siblingIds = (cl.drop(1).map { it.stopId } + cl.flatMap { it.siblingIds }).distinct(),
            )
        }
    }

    /** The comparison key for a stop name: case, "42nd" / "42", "&" / "/" / "at", street-type and
     *  compass abbreviations, and the ORDER of the two cross streets all stop mattering, so
     *  "E 42nd St & Madison Ave" and "MADISON AV/E 42 ST" are one corner. Direction suffixes
     *  ("NB", "SB") are kept, so a directional pair with distinct names stays two stops. */
    fun stopKey(name: String): String {
        var s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(MARKS, "").lowercase()
        s = s.replace(JOINERS, "/")
        s = s.replace(ORDINAL, "$1")
        return s.split('/').map { part ->
            part.replace(NON_WORD, " ").split(SPACES).filter { it.isNotEmpty() }
                .joinToString(" ") { STOP_ABBREV[it] ?: it }
        }.filter { it.isNotEmpty() }.sorted().joinToString("|")
    }

    private val MARKS = Regex("\\p{M}+")
    private val JOINERS = Regex("\\s*(&|@|/|\\+)\\s*|\\s+(and|at)\\s+")
    private val ORDINAL = Regex("\\b(\\d+)(st|nd|rd|th)\\b")
    private val NON_WORD = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val AFTER_SLASH = Regex("/(\\p{Ll})")
    private val STOP_ABBREV = mapOf(
        "street" to "st", "avenue" to "av", "ave" to "av", "boulevard" to "blvd", "road" to "rd", "place" to "pl",
        "drive" to "dr", "parkway" to "pkwy", "square" to "sq", "east" to "e", "west" to "w", "north" to "n", "south" to "s",
    )

    /** The name to show for a merged stop: a mixed-case name when any feed has one (the MTA's bus
     *  feeds are ALL CAPS), else the first name in title case ("W 42 ST/5 AV" -> "W 42 St/5 Av"). */
    fun displayName(names: List<String>): String {
        val mixed = names.firstOrNull { n -> n != n.uppercase() && n.any(Char::isLetter) }
        if (mixed != null) return mixed
        val n = names.first()
        return n.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
            .replace(AFTER_SLASH) { "/" + it.groupValues[1].uppercase() }
    }

    private const val PAIR_MERGE_M = 160.0
    private const val LAP_SAME_STOP_M = 30.0
    private const val COLOCATED_M = 3.0

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
                // showed doubled (7:25, 7:25, 8:23, 8:23; device-seen 2026-07-13). Same line +
                // same departure minute is the same bus regardless of which feed copy it rode in
                // on, so collapse on the epoch (tripIds differ across agency copies - can't key
                // on those).
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
     * with per-stop realtime vs timetable times AND per-stop/-run CANCELED flags straight from the
     * agency feed - none of which the Google itinerary reuse could provide. The result is trimmed to
     * start at the stop nearest ([atLat],[atLng]) (the stop whose board was tapped), mapped into the
     * SAME [TransitStep] the timeline UI already renders. Null on any failure - the caller falls back
     * to the itinerary-reuse path.
     */
    fun tripStops(http: OkHttpClient, tripId: String, atLat: Double, atLng: Double, atEpochSec: Long? = null): TransitStep? {
        val body = get(http, "$BASE/api/v1/trip?tripId=${URLEncoder.encode(tripId, "UTF-8")}") ?: return null
        val leg = runCatching { json.decodeFromString<TripResp>(body).legs.firstOrNull() }.getOrNull() ?: return null
        return buildTripStep(leg, atLat, atLng, atEpochSec)
    }

    /** Pure mapping of a trip leg into the timeline's [TransitStep] (unit-tested; no network).
     *  [atEpochSec] is the tapped departure's time: on a LOOPING trip (one GTFS trip for a whole
     *  day of laps, which some agencies publish) the tapped stop recurs every lap, and the time
     *  picks the lap. */
    internal fun buildTripStep(leg: TripLeg, atLat: Double, atLng: Double, atEpochSec: Long? = null): TransitStep? {
        val all = buildList {
            add(leg.from)
            addAll(leg.intermediateStops)
            add(leg.to)
        }.filter { it.name.isNotBlank() }
        if (all.size < 2) return null
        // The timeline BOARDS at the tapped stop (nearest-by-distance, so it works from a canonical
        // GTFS stop AND from a Google-resolved listing on a different corner); the stops the run
        // already called at go into priorStops so the view can show them grayed above, Google-style.
        // A terminus tap boards at the origin instead (an arrivals-only view has no ride left).
        val dist = all.map { distM(atLat, atLng, it.lat, it.lon) }
        val nearest = dist.minOrNull() ?: 0.0
        // Every pass the trip makes at the tapped stop (a loop calls there once per lap).
        val passes = all.indices.filter { dist[it] <= nearest + LAP_SAME_STOP_M }
        val epochOf = { st: TripStop -> (st.departure ?: st.arrival ?: st.scheduledDeparture ?: st.scheduledArrival)?.let { parseIso(it) } }
        val pick = if (atEpochSec != null && passes.size > 1)
            passes.minByOrNull { i -> epochOf(all[i])?.let { kotlin.math.abs(it - atEpochSec) } ?: Long.MAX_VALUE } ?: passes.first()
        else passes.firstOrNull() ?: 0
        val idx = if (pick >= all.size - 1) 0 else pick
        // One lap: from the previous pass (where this lap began) to the next one (where it ends).
        val lapStart = passes.lastOrNull { it < idx } ?: 0
        val lapEnd = passes.firstOrNull { it > idx }?.let { it + 1 } ?: all.size
        val prior = all.subList(lapStart, idx).map { st -> stopTime(st, legCanceled = leg.cancelled) }
        val mapped = all.subList(idx, lapEnd).map { st -> stopTime(st, legCanceled = leg.cancelled) }
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

    private fun stopTime(st: TripStop, legCanceled: Boolean): TransitStopTime {
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
            canceled = st.cancelled || legCanceled,
            // Signed minutes off the timetable (negative = early) so the row can color a late
            // call differently from an early one - the feed carries both (verified live).
            delayMin = if (moved) (((shownEpoch!! - schedEpoch!!) / 60).toInt()) else null,
        )
    }

    // --- directions (fallback) ---------------------------------------------------------------------

    /** The planner request. [timeMode] is the chooser's own numbering, the one every caller
     *  passes: 0 leave now, 1 depart at, 2 arrive by, 3 last available. Only the last two are
     *  "arrive by" to the planner. (It read 1 and 2 as arrive by, so "Depart at 08:30" listed the
     *  buses that ARRIVE before 08:30 whenever Google's answer was empty and this one was shown:
     *  issue #649.) */
    internal fun planUrl(origin: LatLng, destination: LatLng, timeMode: Int, timeEpochSec: Long?, prefer: Set<Int>, max: Int = PLAN_MAX): String = buildString {
        append(BASE).append("/api/v1/plan?fromPlace=").append(origin.lat).append(',').append(origin.lng)
        append("&toPlace=").append(destination.lat).append(',').append(destination.lng)
        append("&numItineraries=").append(max)
        if (timeEpochSec != null && timeMode != 0) {
            append("&time=").append(java.time.Instant.ofEpochSecond(timeEpochSec))
            if (timeMode == 2 || timeMode == 3) append("&arriveBy=true")
        }
        val modes = prefer.mapNotNull { PLAN_MODES[it] }
        if (modes.isNotEmpty()) append("&transitModes=").append(modes.joinToString(","))
    }

    /**
     * Transit itineraries from Transitous' own planner (`/api/v1/plan`), 2026-09-28. The FALLBACK,
     * not the primary: Google's transit directions carry traffic-aware and history-aware times where
     * a GTFS planner knows only the timetable and current lateness, so this runs when Google is off
     * ("Use Vela without Google") or answered nothing. [timeMode] is the chooser's: 0 leave now, 1 depart
     * at, 2 arrive by, 3 last available (treated as arrive by). [prefer] is the chooser's vehicle numbering
     * (0 bus, 1 subway, 2 train, 3 tram); empty = every mode. Same [TransitItinerary] shape the
     * Google parser feeds, so the chooser, the map drawing and step-by-step guidance render it unchanged.
     */
    fun plan(
        http: OkHttpClient, origin: LatLng, destination: LatLng,
        timeMode: Int = 0, timeEpochSec: Long? = null, prefer: Set<Int> = emptySet(), max: Int = PLAN_MAX,
    ): List<TransitItinerary> {
        val url = planUrl(origin, destination, timeMode, timeEpochSec, prefer, max)
        val body = get(http, url) ?: return emptyList()
        return runCatching { parsePlan(body, origin, destination) }.getOrDefault(emptyList())
    }

    /** The plan reply's itineraries, in the planner's order. Pure; fixture-tested. */
    internal fun parsePlan(json: String, origin: LatLng, destination: LatLng): List<TransitItinerary> {
        val root = JSONObject(json)
        val its = root.optJSONArray("itineraries") ?: return emptyList()
        return (0 until its.length()).mapNotNull { i -> runCatching { parseItinerary(its.getJSONObject(i), origin, destination) }.getOrNull() }
    }

    private fun parseItinerary(it: JSONObject, origin: LatLng, destination: LatLng): TransitItinerary? {
        val legs = it.optJSONArray("legs") ?: return null
        val steps = ArrayList<TransitStep>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val mode = leg.optString("mode", "")
            val from = leg.optJSONObject("from"); val to = leg.optJSONObject("to")
            val start = parseIso(leg.optString("startTime", "")); val end = parseIso(leg.optString("endTime", ""))
            val secs = leg.optLong("duration", if (start != null && end != null) end - start else 0L)
            if (mode == "WALK" || mode == "BIKE" || mode == "CAR" || mode == "RENTAL") {
                // A transfer walk between two stops, or the first/last mile: the app fetches the
                // turn-by-turn steps for it on demand from the walk router, like the Google legs.
                steps += TransitStep(
                    mode = TransitMode.WALK, durationText = durationText(secs),
                    walkFrom = from?.let { point(it) } ?: origin, walkTo = to?.let { point(it) } ?: destination,
                )
                continue
            }
            val tz = from?.optString("tz", null)
            val line = TransitLine(
                name = leg.optString("routeShortName", "").ifBlank { leg.optString("displayName", "") }.ifBlank { leg.optString("routeLongName", "") },
                mode = modeOf(mode),
                colorHex = leg.optString("routeColor", "").takeIf { it.isNotBlank() }?.let { "#" + it.removePrefix("#") },
                textColorHex = leg.optString("routeTextColor", "").takeIf { it.isNotBlank() }?.let { "#" + it.removePrefix("#") },
            )
            val board = from?.let { stopTime(it, "departure", "scheduledDeparture", tz) }
            val alight = to?.let { stopTime(it, "arrival", "scheduledArrival", to.optString("tz", null) ?: tz) }
            val mids = leg.optJSONArray("intermediateStops")?.let { a ->
                (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { stopTime(it, "arrival", "scheduledArrival", it.optString("tz", null) ?: tz) } }
            }.orEmpty()
            val startSched = parseIso(leg.optString("scheduledStartTime", ""))
            val delayMin = if (start != null && startSched != null && leg.optBoolean("realTime", false)) ((start - startSched) / 60).toInt() else null
            steps += TransitStep(
                mode = line.mode, durationText = durationText(secs), line = line,
                departText = start?.let { clockText(it, tz) }, arriveText = end?.let { clockText(it, to?.optString("tz", null) ?: tz) },
                headsign = leg.optString("headsign", "").takeIf { it.isNotBlank() },
                boardStop = board, alightStop = alight, numStops = mids.size + 1,
                delayText = delayMin?.let { delayText(it) }, intermediateStops = mids,
            )
        }
        if (steps.none { it.line != null }) return null // a walk-only answer is not a transit trip
        val startEp = parseIso(it.optString("startTime", "")); val endEp = parseIso(it.optString("endTime", ""))
        val firstTz = legs.getJSONObject(0).optJSONObject("from")?.optString("tz", null)
        val lastTz = legs.getJSONObject(legs.length() - 1).optJSONObject("to")?.optString("tz", null) ?: firstTz
        var agency: String? = null
        for (i in 0 until legs.length()) legs.getJSONObject(i).optString("agencyName", "").takeIf { it.isNotBlank() }?.let { if (agency == null) agency = it }
        return TransitItinerary(
            departureEpochSec = startEp, arrivalEpochSec = endEp,
            departureText = startEp?.let { clockText(it, firstTz) }, arrivalText = endEp?.let { clockText(it, lastTz) },
            durationText = durationText(it.optLong("duration", if (startEp != null && endEp != null) endEp - startEp else 0L)),
            agency = agency, lines = steps.mapNotNull { s -> s.line }, steps = steps,
        )
    }

    private fun point(o: JSONObject): LatLng? {
        val lat = o.optDouble("lat", Double.NaN); val lng = o.optDouble("lon", Double.NaN)
        return if (lat.isNaN() || lng.isNaN()) null else LatLng(lat, lng)
    }

    private fun stopTime(o: JSONObject, liveKey: String, schedKey: String, tz: String?): TransitStopTime {
        val live = parseIso(o.optString(liveKey, "")); val sched = parseIso(o.optString(schedKey, ""))
        val shown = live ?: sched
        return TransitStopTime(
            name = o.optString("name", ""), code = o.optString("stopCode", "").takeIf { it.isNotBlank() },
            timeText = shown?.let { clockText(it, tz) },
            scheduledText = if (live != null && sched != null && live != sched) clockText(sched, tz) else null,
            location = point(o), canceled = o.optBoolean("cancelled", false),
            delayMin = if (live != null && sched != null && live != sched) ((live - sched) / 60).toInt() else null,
        )
    }

    /** "45 min" / "1 h 5 min", the shape the chooser's chips already fold. */
    internal fun durationText(secs: Long): String {
        val m = ((secs + 30) / 60).coerceAtLeast(1)
        return if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    }

    private fun delayText(min: Int): String? = when {
        min > 0 -> "$min min late"
        min < 0 -> "${-min} min early"
        else -> null
    }

    private const val PLAN_MAX = 5
    /** The chooser's vehicle numbers (Google's, issue #431) as the planner's mode names. */
    private val PLAN_MODES = mapOf(0 to "BUS", 1 to "SUBWAY", 2 to "RAIL", 3 to "TRAM")

    // --- helpers ----------------------------------------------------------------------------------

    private fun get(http: OkHttpClient, url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    /** ISO-8601 UTC ("2026-07-13T20:26:00Z") to epoch seconds. */
    internal fun parseIso(iso: String): Long? = runCatching { java.time.Instant.parse(iso).epochSecond }.getOrNull()

    /** Clock text in the STOP's timezone (falls back to the device zone): 12-hour like the
     *  Google boards, or 24-hour when the device's clock setting says so ([ClockFormat],
     *  issue #357). */
    internal fun clockText(epochSec: Long, tz: String?): String {
        val fmt = SimpleDateFormat(if (app.vela.core.data.ClockFormat.use24h) "HH:mm" else "h:mm a", Locale.US)
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
