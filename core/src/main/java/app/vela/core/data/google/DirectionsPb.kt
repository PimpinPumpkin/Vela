package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode

/**
 * Builds the `pb` parameter for `/maps/preview/directions`.
 *
 * Captured from a live request and verified on 2026-06-15 for driving, walking
 * and cycling. Two calibration findings:
 *  - travel mode is the `!1e{N}` field inside the `!20m5` block —
 *    **0 = driving, 1 = cycling, 2 = walking, 3 = transit** (everything else is
 *    identical between modes), and
 *  - the optional `!15m3!1s<token>!7e81` session-token block was removed after
 *    confirming routes still come back without it.
 *
 * Driving returns traffic-aware ETAs + alternatives; walking/cycling return a
 * single route with no traffic; transit (3) uses a different response shape the
 * current parser doesn't read, so the UI offers drive / walk / bike only.
 */
object DirectionsPb {
    // Shipped default; the live template comes from CalibrationStore and is passed
    // into [build].
    const val DEFAULT_TEMPLATE =
        "!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2" +
        "!3m12!1m3!1d24960.741896132306!2d-121.7527808!3d38.554674999999996!2m3!1f0.0!2f0.0!3f0.0" +
        "!3m2!1i1024!2i768!4f13.1!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m27!32i1" +
        "!49b1!63m0!66b1!85b1!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1" +
        "!244b1!246b1!250b1!253b1!260b1!266b1!270b1!273b1!279b1!281b1!291m0!10b1!12b1!13b1!14b1" +
        "!16b1!17m1!3e1!20m5!1e{MODE}!2e3!5e2!6b1!14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2" +
        "!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024" +
        "!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40i783!47m2!8b1!10e2"

    fun build(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        template: String = DEFAULT_TEMPLATE,
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
        avoidFerries: Boolean = false,
        waypoints: List<LatLng> = emptyList(),
    ): String {
        val modeCode = when (mode) {
            TravelMode.DRIVE -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALK -> 2
            TravelMode.TRANSIT -> 3
        }
        val drive = mode == TravelMode.DRIVE
        return withWaypoints(withAvoid(template, avoidTolls && drive, avoidHighways && drive, avoidFerries && drive), waypoints)
            .replace("{OLAT}", origin.lat.toString())
            .replace("{OLNG}", origin.lng.toString())
            .replace("{DLAT}", destination.lat.toString())
            .replace("{DLNG}", destination.lng.toString())
            .replace("{MODE}", modeCode.toString())
    }

    /** The avoid flags Google's own web client sends (captured from its `/maps/preview/directions`
     *  request with "Avoid highways" ticked, 2026-09-06, and verified from a plain HTTP client:
     *  Davis to Sacramento flips from I-80 15.3 mi / 21 min to Old River Rd 27.1 mi / 46 min;
     *  Naperville to O'Hare with tolls avoided leaves the I-88/I-294 tollway for I-55/I-90). They
     *  are NOT in the `!20m` route-options group (every scalar there was probed with no effect)
     *  but in the `!6m` feature block's `!2m` submessage: `!1b1` = avoid highways, `!2b1` = avoid
     *  tolls, the same field numbers the web `dir/` URL's `!2m1!1b1` carries. Counts on the
     *  enclosing `!6m` and `!2m` groups grow by the number of flags added. Done by pattern so a
     *  recalibrated template (CalibrationStore) keeps working as long as that block survives.
     *
     *  Avoid ferries (issue #546, captured 2026-09-16 the same way with "Ferries" ticked) is a
     *  DIRECT child of that same outer `!6m` block, not of `!2m`: `!7b1` when ticked, `!7b0` when
     *  not, placed by field order after the nested `!6m..` group and before `!10b1`. Replaying one
     *  request with only that flag flipped swapped a ferry crossing for a long road detour. An
     *  existing `!7b` child is rewritten in place; otherwise `!7b1` is inserted and the outer count
     *  grows by one. */
    /** Whether [withAvoid] can place the flags in [template] at all. */
    fun avoidSupported(template: String): Boolean = AVOID_BLOCK.containsMatchIn(template)

    /** STOPS. The template's origin and destination are two identical top-level waypoint groups,
     *  `!1m4!3m2!3d<lat>!4d<lng>!6e2`, which is how a repeated protobuf field reads in pb form; a
     *  stop is one more of them between the two, in trip order. Top-level, so no enclosing count
     *  moves. Verified live from a plain client (2026-09-21, Davis fixture): direct Davis to
     *  Sacramento answered 15.3 mi / 21 min with three alternates; through Woodland it answered ONE
     *  route, 45 min, with per-leg distances, so a waypointed reply carries no alternates (the same
     *  as Google's own web client). Until this landed Vela never sent a stop to Google at all: a trip
     *  with stops was routed through them by the open router and Google's DIRECT answer only
     *  calibrated the speed (issue #600 is what made that visible).
     *
     *  A recalibrated template without the two placeholder groups makes this a no-op ([waypointsSupported]),
     *  and the caller must then treat Google's answer as the direct trip it is. */
    fun waypointsSupported(template: String): Boolean = DEST_GROUP.containsMatchIn(template)

    // Regex.escape, not a hand-written pattern: the first cut wrote `\{DLAT}` with the closing
    // brace bare, which java.util.regex accepts and Android's ICU regex REJECTS - the object's
    // static init threw, every later call saw "Rejecting re-init on previously-failed class", and
    // Google directions were dead on the device while every JVM test passed (2026-09-21).
    private val DEST_GROUP = Regex(Regex.escape("!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2"))

    internal fun withWaypoints(template: String, waypoints: List<LatLng>): String {
        if (waypoints.isEmpty()) return template
        val m = DEST_GROUP.find(template) ?: return template
        val groups = waypoints.joinToString("") { "!1m4!3m2!3d${it.lat}!4d${it.lng}!6e2" }
        return template.substring(0, m.range.first) + groups + template.substring(m.range.first)
    }

    private val AVOID_BLOCK = Regex("""!6m(\d+)(!1m5!18b1!30b1!31m1!1b1!34e1!2m)(\d+)""")

    internal fun withAvoid(
        template: String,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        avoidFerries: Boolean = false,
    ): String {
        val m = AVOID_BLOCK.find(template) ?: return template
        val flags = buildString {
            if (avoidHighways) append("!1b1")
            if (avoidTolls) append("!2b1")
        }
        val added = flags.count { it == '!' }
        // The ferry flag: walk the outer block's direct children (they are in field order).
        val outerCount = m.groupValues[1].toInt()
        val bodyStart = m.range.first + "!6m".length + m.groupValues[1].length
        val children = directChildren(template, bodyStart, outerCount)
        var ferryAt = -1
        var ferryEnd = -1
        var ferryText = ""
        var ferryDelta = 0
        val ferry = children?.firstOrNull { it.field == 7 && it.type == 'b' }
        if (ferry != null) {
            val want = if (avoidFerries) "!7b1" else "!7b0"
            if (template.substring(ferry.start, ferry.end) != want) {
                ferryAt = ferry.start; ferryEnd = ferry.end; ferryText = want
            }
        } else if (avoidFerries && children != null) {
            // Before the first direct child numbered above 7, else at the end of the block.
            val at = children.firstOrNull { it.field > 7 }?.start ?: (children.lastOrNull()?.end ?: bodyStart)
            ferryAt = at; ferryEnd = at; ferryText = "!7b1"; ferryDelta = 1
        }
        if (added == 0 && ferryAt < 0) return template
        val sb = StringBuilder(template)
        // Back to front so the earlier offsets stay valid: every direct child of the outer block
        // after `!1m5` sits past the `!2m` head that AVOID_BLOCK matched.
        if (ferryAt >= 0) sb.replace(ferryAt, ferryEnd, ferryText)
        val outer = outerCount + added + ferryDelta
        val inner = m.groupValues[3].toInt() + added
        sb.replace(m.range.first, m.range.last + 1, "!6m$outer${m.groupValues[2]}$inner$flags")
        return sb.toString()
    }

    private class PbChild(val field: Int, val type: Char, val start: Int, val end: Int)

    private val TOKEN = Regex("""!(\d+)([a-zA-Z])([^!]*)""")

    /** The direct children of a pb group whose body starts at [from] and holds [count] descendant
     *  tokens (an `m` token's value is its own descendant count). Null when the template does not
     *  parse that way, so a malformed template is left alone rather than corrupted. */
    private fun directChildren(pb: String, from: Int, count: Int): List<PbChild>? {
        val tokens = TOKEN.findAll(pb, from).take(count).toList()
        if (tokens.size < count) return null
        val out = ArrayList<PbChild>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (i > 0 && t.range.first != tokens[i - 1].range.last + 1) return null
            val type = t.groupValues[2][0]
            val span = if (type == 'm') (t.groupValues[3].toIntOrNull() ?: return null) else 0
            val last = i + span
            if (last >= tokens.size) return null
            out += PbChild(t.groupValues[1].toInt(), type, t.range.first, tokens[last].range.last + 1)
            i = last + 1
        }
        return out
    }
}
