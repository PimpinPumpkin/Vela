package app.vela.core.data.google

import app.vela.core.model.LatLng
import kotlin.math.cos
import kotlin.math.max

/**
 * Per-session values Google's own pages put in their requests, where Vela used to send one
 * constant from every install (marker audit, 2026-09-25). A value every install sends identically
 * is a filter that catches all of Vela and nothing else, which is worse than any "not quite Chrome"
 * difference: that class holds millions of other clients.
 *
 * - `_reqid` on batchexecute: the page starts it at a random number and adds 100000 per call. Vela
 *   sent `_reqid=1` on every photo request and an unrelated random number on every feed request.
 * - `ech` on autocomplete: the page counts its suggest requests; Vela sent `ech=1` on every keystroke.
 * - The JSONP callback on the Street View lookup: Google's Maps JavaScript names each one
 *   `_xdc_._<random>`; Vela sent `callback=cb`.
 * - The map viewport inside a directions request: the captured template froze it on Davis, CA, so
 *   every Vela directions request anywhere claimed a map over Davis. [fitDirections] centers it on
 *   the trip instead, the way a browser's map sits around the route it is asking for.
 */
object RequestShape {
    private val reqSeed = (10_000..99_999).random()
    private val reqCount = java.util.concurrent.atomic.AtomicInteger()
    private val echCount = java.util.concurrent.atomic.AtomicInteger()

    /** The next batchexecute `_reqid`: a random start, then +100000 per request, as the page does. */
    fun nextReqId(): Int = reqSeed + reqCount.getAndIncrement() * 100_000

    /**
     * A map span (`!1d`) as a browser writes one: the map derives it from its zoom, so it is a long
     * decimal that is never the same twice. Vela sent either the captured template's exact
     * `25229.167291701906` (ambient places, details, popular times: every install, every request)
     * or a whole number (`!1d3000`) no map ever produces. The +-0.2% spread changes no results.
     */
    fun span(meters: Double): String = (meters * (1 + (kotlin.random.Random.nextDouble() - 0.5) * 0.004)).toString()

    /** The next autocomplete `ech`: 1, 2, 3... for this process's session. */
    fun nextEch(): Int = echCount.incrementAndGet()

    /** A JSONP callback name in the Maps JavaScript form, `_xdc_._` plus six base-36 characters. */
    fun callbackName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "_xdc_._" + (1..6).map { chars.random() }.joinToString("")
    }

    /** [url] with its `_reqid` replaced by [nextReqId], `source-path` encoded like the page's, and a
     *  `gl` added when the template has none (the page always sends one). */
    fun batchUrl(url: String): String {
        var u = url.replace(Regex("""([?&]_reqid=)\d+"""), "$1${nextReqId()}")
            .replace("source-path=/maps", "source-path=%2Fmaps")
        if (!Regex("""[?&]gl=""").containsMatchIn(u)) u = u.replace(Regex("""([?&]hl=[^&]*)"""), "$1&gl=us")
        return u
    }

    private val DIRECTIONS_VIEW = Regex("""(!3m12!1m3!1d)[0-9.]+(!2d)-?[0-9.]+(!3d)-?[0-9.]+""")

    /**
     * [pb] with the directions request's map viewport (`!3m12!1m3!1d<span>!2d<lng>!3d<lat>`) centered
     * on the box around [points] and spanning it with a margin, floored at 1.5 km. A template
     * without that group (a recalibration) is returned unchanged.
     */
    fun fitDirections(pb: String, points: List<LatLng>): String {
        if (points.isEmpty() || !DIRECTIONS_VIEW.containsMatchIn(pb)) return pb
        val s = points.minOf { it.lat }; val n = points.maxOf { it.lat }
        val w = points.minOf { it.lng }; val e = points.maxOf { it.lng }
        val lat = (s + n) / 2; val lng = (w + e) / 2
        val spanM = max((n - s) * 111_320.0, (e - w) * 111_320.0 * cos(Math.toRadians(lat)))
        val d = span(max(spanM * 1.35, 1_500.0))
        val m = DIRECTIONS_VIEW.find(pb) ?: return pb
        return pb.replaceRange(m.range, "${m.groupValues[1]}$d${m.groupValues[2]}$lng${m.groupValues[3]}$lat")
    }
}
