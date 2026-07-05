package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.Lane
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
    private const val OSRM_TRIES = 3 // FOSSGIS community server blips on mobile; a miss = nameless Google fallback
    // Max gap (m) between an exit ramp and a following fork/merge for them to count as ONE exit complex
    // (consolidateExits). Generous enough for a long ramp, short enough that a genuine km-away fork isn't folded.
    private const val EXIT_COMPLEX_GAP_M = 500.0
    // Traffic-light landmark guidance (enrichWithLights): a light must be within this far BEFORE the turn (a
    // block or two — "pass the light, then turn"), and within this far of the driven line to count as on-route.
    private const val LIGHT_APPROACH_M = 400.0
    private const val LIGHT_SNAP_M = 25.0
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
        // Place each maneuver at the START of its step (cum BEFORE adding the step's distance) —
        // a maneuver's distanceMeters is the travel AFTER it, the convention placeManeuvers/OSRM
        // use everywhere else. Adding first put every turn one full step LATE along the line
        // (10+ km on a highway step). The final ARRIVE pins to the end of the line.
        var cum = 0.0
        val placed = route.maneuvers.mapIndexed { i, m ->
            val frac = if (i == route.maneuvers.lastIndex) 1.0 else (cum / total).coerceIn(0.0, 1.0)
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, frac))
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

    // --- full open-source routing (PRIMARY turn-by-turn) ----------------------

    /** Complete route(s) for [origin]→[dest] in [mode] from the open router (OSRM `steps=true`):
     *  real road geometry AND every turn, with street names. This is the PRIMARY directions source
     *  — Google's keyless endpoint hands back ABBREVIATED steps for longer routes (a 6-mi route
     *  came back with 2 of ~10 turns), whereas OSRM gives them all. Free-flow duration only (no
     *  traffic — Google is queried separately for the live ETA and overlaid). Empty on any failure
     *  → caller falls back to the Google scrape. */
    fun route(http: OkHttpClient, origin: LatLng, dest: LatLng, mode: TravelMode): List<Route> =
        routeOsrm(http, listOf(origin, dest), mode, alternatives = true)

    /** OSRM forced THROUGH [waypoints] (origin, vias…, dest) — used to follow Google's
     *  traffic-smart path with OSRM's full street-named steps (option 3: traffic-aware routing).
     *  No alternatives (OSRM doesn't return them for multi-waypoint routes). */
    fun routeVia(http: OkHttpClient, waypoints: List<LatLng>, mode: TravelMode): List<Route> =
        if (waypoints.size < 2) emptyList() else routeOsrm(http, waypoints, mode, alternatives = false)

    private fun routeOsrm(http: OkHttpClient, points: List<LatLng>, mode: TravelMode, alternatives: Boolean): List<Route> {
        val backend = backend(mode) ?: return emptyList()
        val coords = points.joinToString(";") { "${it.lng},${it.lat}" }
        val url = "$OSRM_BASE/$backend/route/v1/driving/$coords" +
            "?overview=full&geometries=polyline&steps=true" + if (alternatives) "&alternatives=3" else ""
        val req = Request.Builder().url(url).header("User-Agent", VelaConfig.USER_AGENT).build()
        // The FOSSGIS community OSRM transiently 5xx/429/resets on mobile, and each miss otherwise drops
        // nav to Google's ABBREVIATED (nameless) steps — the "why aren't these street names?" bug. So retry
        // a couple times with a short backoff. A SUCCESSFUL response (even an empty route list = genuine
        // "no route") returns immediately; only transport/5xx failures retry.
        repeat(OSRM_TRIES) { attempt ->
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val routes = json.parseToJsonElement(resp.body?.string().orEmpty())
                            .jsonObject["routes"]?.jsonArray
                        if (routes != null) return routes.mapNotNull { parseOsrmRoute(it.jsonObject) }
                    }
                    // unsuccessful (5xx / 429 rate-limit) — fall through to retry
                }
            } catch (e: Exception) {
                // network blip / timeout — fall through to retry
            }
            if (attempt < OSRM_TRIES - 1) runCatching { Thread.sleep(200L * (attempt + 1)) }
        }
        return emptyList()
    }

    private fun parseOsrmRoute(r: JsonObject): Route? {
        val poly = r["geometry"]?.jsonPrimitive?.contentOrNull?.let { PolylineCodec.decode(it) }
            ?.takeIf { it.size >= 2 } ?: return null
        val dist = r["distance"]?.jsonPrimitive?.doubleOrNull ?: return null
        val dur = r["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val raw = (r["legs"]?.jsonArray ?: return null).flatMap { leg ->
            leg.jsonObject["steps"]?.jsonArray?.mapNotNull { osrmStep(it.jsonObject) } ?: emptyList()
        }
        // A multi-waypoint (via) route splits into legs, inserting a spurious "arrive"+"depart" at
        // each via. Drop those so it reads as one continuous trip — keep only the first DEPART and
        // the final ARRIVE — but FOLD each dropped step's distance into the last kept maneuver:
        // step distances must keep TILING the polyline. NavEngine locates each maneuver by a
        // prefix-sum of step lengths (its wrong-pass protection), and a via-boundary DEPART
        // carries the real via→next-turn travel — on a traffic-snapped route (~12 vias) silently
        // dropping those shifted every later estimate kilometres short.
        val last = raw.lastIndex
        val maneuvers = mutableListOf<Maneuver>()
        raw.forEachIndexed { i, m ->
            val spuriousVia = (m.type == ManeuverType.DEPART && i != 0) ||
                (m.type == ManeuverType.ARRIVE && i != last)
            if (!spuriousVia) {
                maneuvers += m
            } else if (maneuvers.isNotEmpty() && m.distanceMeters > 0.0) {
                val prev = maneuvers.removeAt(maneuvers.lastIndex)
                maneuvers += prev.copy(distanceMeters = prev.distanceMeters + m.distanceMeters)
            }
        }
        val consolidated = consolidateExits(maneuvers)
        maneuvers.clear(); maneuvers.addAll(consolidated)
        if (maneuvers.size < 2) return null
        return Route(
            polyline = poly,
            legs = listOf(RouteLeg(dist, dur, null, maneuvers)),
            distanceMeters = dist,
            durationSeconds = dur,
            durationInTrafficSeconds = null, // filled by the Google traffic overlay
            summary = maneuvers.filter { it.road != null }.maxByOrNull { it.distanceMeters }?.road,
        )
    }

    /** Fold an EXIT / interchange complex into ONE maneuver. OSRM splits a single user action (leave the
     *  highway here) into a ramp step plus trailing fork/merge "lane-selection" steps, each of which fires
     *  its OWN spoken prompt seconds apart ("Take exit 15" … "Keep right" … "Merge onto I-90") — the reported
     *  "it told me related instructions over and over." Google says it once. Fold a ramp's IMMEDIATELY-
     *  following, SHORT-gapped FORK/MERGE run into the ramp: keep the ramp instruction ("Take exit 15
     *  toward …"), SUM the distances so the step lengths still tile the polyline (NavEngine locates each
     *  maneuver by a prefix-sum of step distances), and drop the redundant follow-ups. It stops at the first
     *  real turn / next ramp / continue / arrive, and at a > [EXIT_COMPLEX_GAP_M] gap, so a genuine later
     *  decision (a fork to a different highway a km on) is never swallowed. */
    internal fun consolidateExits(list: List<Maneuver>): List<Maneuver> {
        val out = mutableListOf<Maneuver>()
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m.type == ManeuverType.RAMP_LEFT || m.type == ManeuverType.RAMP_RIGHT) {
                var j = i + 1
                var folded = m.distanceMeters
                while (j < list.size &&
                    (list[j].type == ManeuverType.FORK_LEFT || list[j].type == ManeuverType.FORK_RIGHT ||
                        list[j].type == ManeuverType.MERGE) &&
                    list[j - 1].distanceMeters < EXIT_COMPLEX_GAP_M
                ) {
                    folded += list[j].distanceMeters
                    j++
                }
                if (j > i + 1) { out += m.copy(distanceMeters = folded); i = j; continue }
            }
            out += m
            i++
        }
        return out
    }

    /**
     * Prepend a Google-style landmark clause ("Pass the traffic light, then turn left onto 5th Ave") to a
     * turn when 1–2 traffic signals sit on the road just before it. Deliberately CONSERVATIVE — Google only
     * says it when it helps: ONLY plain surface-street turns (not ramps/merges/roundabouts/continues), ONLY
     * when 1–2 signals fall within ~[LIGHT_APPROACH_M] before the turn AND within ~[LIGHT_SNAP_M] of the driven
     * line (a light on a parallel street doesn't count), and NEVER for 0 or 3+ (nobody narrates "pass 4
     * lights"). Called from the app ONLY when the toggle is on and only for the route being driven. [signals]
     * = OverpassTrafficSignals.fetchAlong. Best-effort: empty signals or no match → route returned unchanged.
     */
    fun enrichWithLights(route: Route, signals: List<LatLng>): Route {
        val poly = route.polyline
        if (signals.isEmpty() || poly.size < 2) return route
        val nav = app.vela.core.i18n.NavStringsRegistry.current()
        fun nearestIdx(p: LatLng): Int {
            var best = 0; var bd = Double.MAX_VALUE
            for (i in poly.indices) { val d = poly[i].distanceTo(p); if (d < bd) { bd = d; best = i } }
            return best
        }
        val legs = route.legs.map { leg ->
            val idx = leg.maneuvers.map { nearestIdx(it.location) }
            val newMans = leg.maneuvers.mapIndexed { i, m ->
                if (i == 0 || (m.type != ManeuverType.TURN_LEFT && m.type != ManeuverType.TURN_RIGHT)) return@mapIndexed m
                val toIdx = idx[i]; val fromIdx = idx[i - 1]
                if (toIdx <= fromIdx) return@mapIndexed m
                // Walk BACK from the turn along the line up to LIGHT_APPROACH_M, collecting the approach points.
                val approach = ArrayList<LatLng>()
                var acc = 0.0; var k = toIdx
                while (k > fromIdx && acc < LIGHT_APPROACH_M) { approach.add(poly[k]); acc += poly[k].distanceTo(poly[k - 1]); k-- }
                val count = signals.count { s -> approach.any { it.distanceTo(s) < LIGHT_SNAP_M } }
                val lead = if (count in 1..2) nav.passLights(count) else ""
                if (lead.isBlank()) m
                else m.copy(instruction = "$lead, then " + m.instruction.replaceFirstChar { it.lowercaseChar() })
            }
            leg.copy(maneuvers = newMans)
        }
        return route.copy(legs = legs)
    }

    private fun osrmStep(s: JsonObject): Maneuver? {
        val man = s["maneuver"]?.jsonObject ?: return null
        val type = man["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val mod = man["modifier"]?.jsonPrimitive?.contentOrNull
        val loc = man["location"]?.jsonArray ?: return null
        val lat = loc.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        val lng = loc.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val name = s["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // Highways identify by REF ("I 80"), not name — OSRM puts the name empty and the ref in `ref`.
        // Dropping it (the old bug) left highway steps nameless → generic "take the exit" AND no shield
        // (the banner parses shields out of the instruction text). `destinations` is where a ramp goes
        // ("I-80 East: Sacramento"). road = name ?: ref so surface streets read by name, highways by shield.
        val ref = s["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val dest = s["destinations"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val exits = s["exits"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val road = name ?: ref
        // Per-lane turn guidance for the Google-style diagram: the maneuver's own intersection
        // (intersections[0]) carries the approach lanes — each lane's allowed arrows + whether it
        // serves this turn (`valid`). Absent on most steps (only turns/exits with mapped lanes).
        val lanes = s["intersections"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("lanes")?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val inds = o["indications"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: return@mapNotNull null
                Lane(inds, o["valid"]?.jsonPrimitive?.booleanOrNull ?: false)
            }.orEmpty()
        return Maneuver(
            type = osrmType(type, mod),
            instruction = osrmPhrase(type, mod, road, dest, exits, man["exit"]?.jsonPrimitive?.intOrNull),
            location = LatLng(lat, lng),
            distanceMeters = s["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            durationSeconds = s["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            road = road,
            ref = ref?.substringBefore(";")?.trim(), // first ref → the shield (a road can have name AND ref)
            lanes = lanes,
        )
    }

    /** OSRM `maneuver.type` + `modifier` → Vela [ManeuverType] (for the arrow + haptic). */
    internal fun osrmType(type: String, mod: String?): ManeuverType {
        fun byMod() = when (mod) {
            "left" -> ManeuverType.TURN_LEFT
            "right" -> ManeuverType.TURN_RIGHT
            "slight left" -> ManeuverType.SLIGHT_LEFT
            "slight right" -> ManeuverType.SLIGHT_RIGHT
            "sharp left" -> ManeuverType.SHARP_LEFT
            "sharp right" -> ManeuverType.SHARP_RIGHT
            "uturn" -> ManeuverType.UTURN
            else -> ManeuverType.STRAIGHT
        }
        return when (type) {
            "depart" -> ManeuverType.DEPART
            "arrive" -> ManeuverType.ARRIVE
            "merge" -> ManeuverType.MERGE
            // "continue"/"new name" going STRAIGHT = the same physical road (possibly renamed —
            // surface-street name changes and highway ref changes alike), no driver action →
            // CONTINUE, which NavEngine keeps on the banner but does NOT speak (Google is silent
            // there too). Any real bend keeps byMod(): OSRM's "continue left" is a 90° bend that
            // keeps the name — that's a TURN_LEFT and must still be spoken. NB "turn"+"straight"
            // below stays STRAIGHT (spoken): OSRM emits type "turn" only where an instruction is
            // warranted (going straight is an active choice at that junction).
            // A "new name" step is JUST a road rename on the same physical road — OSRM often stamps a
            // few-degrees "slight left"/"slight right" bearing artifact on a dead-straight rename node, so
            // treat those as straight too → CONTINUE (silent, like Google). "Olive Dr becomes Richards
            // Way" going straight must NOT be announced. Keep "continue" NARROW: a "continue"+slight is more
            // plausibly a real gentle bend that keeps its name and should stay spoken.
            "new name" -> if (mod == null || mod == "straight" || mod == "slight left" || mod == "slight right")
                ManeuverType.CONTINUE else byMod()
            "continue" -> if (mod == null || mod == "straight") ManeuverType.CONTINUE else byMod()
            "on ramp", "off ramp", "ramp" -> if (mod?.contains("left") == true) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
            "fork" -> if (mod?.contains("left") == true) ManeuverType.FORK_LEFT else ManeuverType.FORK_RIGHT
            "roundabout", "rotary", "roundabout turn" -> ManeuverType.ROUNDABOUT
            // OSRM's TWO-STEP roundabout: an enter step ("roundabout") + an EXIT step ("exit roundabout"/
            // "exit rotary") — and it's the exit step that carries maneuver.exit (the exit NUMBER) and the
            // exit road name. Without this the exit step fell through to byMod() → a bland "Continue onto X"
            // and the "take the Nth exit" was silently dropped (the reported "didn't say which exit").
            "exit roundabout", "exit rotary" -> ManeuverType.EXIT_ROUNDABOUT
            "end of road", "turn" -> byMod()
            else -> byMod()
        }
    }

    /** A human instruction (OSRM ships no text; `osrm-text-instructions` is a JS lib we inline the
     *  gist of) — "Turn right onto the local street", "Continue onto I 80", "Take exit 15 toward Sacramento".
     *  [road] is the name-or-ref of the road being entered; [dest] is a ramp's sign destination
     *  ("I-80 East: Sacramento"); [exitNo] is a ramp's exit number; [rbExit] is a roundabout exit count. */
    // The instruction TEXT is localized: it delegates to the active NavStrings (English by default,
    // byte-identical to the original template set). This is the seam that lets nav be spoken/shown in
    // the app's language — the road/dest name passed in stays in its native local form. See core/i18n.
    internal fun osrmPhrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String =
        app.vela.core.i18n.NavStringsRegistry.current().phrase(type, mod, road, dest, exitNo, rbExit)

    // --- traffic-aware routing (option 3) -------------------------------------

    /** True if Google's traffic-aware route [google] takes a meaningfully DIFFERENT path than
     *  OSRM's free-flow [osrm] — i.e. Google rerouted around a jam. Samples points along Google's
     *  line and checks whether any strays > [thresholdM] from OSRM's line. */
    internal fun divergent(osrm: Route, google: Route, thresholdM: Double = 700.0): Boolean {
        val a = osrm.polyline
        val b = google.polyline
        if (a.size < 2 || b.size < 2) return false
        return (1..5).any { k ->
            val p = b[(b.size * k / 6).coerceIn(0, b.size - 1)]
            a.minOf { p.distanceTo(it) } > thresholdM
        }
    }

    /** Interior points spaced along [poly] to feed OSRM as via-waypoints so it follows that path.
     *  ~[count] of them: dense enough that OSRM's shortest path between consecutive vias IS the road
     *  Google took (a short jam-detour between two sparse vias would otherwise be skipped), yet not so
     *  dense that a via landing on a turn gets swallowed into a via arrive/depart and the turn is lost
     *  (measured ~1-in-10 named-turn loss at 60 vias; negligible at ~12). Only ever used on the
     *  divergent minority of routes, so the tradeoff rides on few requests. */
    internal fun sampleVias(poly: List<LatLng>, count: Int = 12): List<LatLng> {
        if (poly.size < 3) return emptyList() // need at least one interior point
        val interior = poly.size - 2
        val n = count.coerceAtMost(interior)
        return (1..n).map { poly[(1 + (interior - 1).toLong() * (it - 1) / (n - 1).coerceAtLeast(1)).toInt()] }
    }
}
