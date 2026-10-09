package app.vela.core.data

import app.vela.core.model.LatLng
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * One opening of a movable bridge from the NDW feed. [endMs] is null while a bridge is open now
 * and its end is not known yet ([openNow]).
 */
data class BridgeOpening(val loc: LatLng, val startMs: Long, val endMs: Long?, val openNow: Boolean)

/**
 * Bridge openings in the Netherlands from NDW, the Dutch national road data office, as open data
 * (`planningsfeed_brugopeningen`, DATEX II v3). Keyless, no account, about 115 KB gzipped. It
 * lists the movable bridges on the main road network with each planned opening (`riskOf`) and
 * the ones open now (`implemented`, no end time yet). A bridge has coordinates and no name.
 */
object NdwBridgeOpenings {
    const val URL = "https://opendata.ndw.nu/planningsfeed_brugopeningen.xml.gz"

    /** The Netherlands' box. A route that does not enter it never asks NDW. */
    fun inNetherlands(p: LatLng): Boolean = p.lat in 50.7..53.7 && p.lng in 3.2..7.3

    @Volatile private var client: OkHttpClient? = null
    private fun client(base: OkHttpClient): OkHttpClient =
        client ?: base.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
            .also { client = it }

    /** The whole feed, or null on failure. Blocking: call off the main thread. */
    fun fetch(http: OkHttpClient): List<BridgeOpening>? = runCatching {
        val req = Request.Builder().url(URL).header("User-Agent", app.vela.core.VelaConfig.VELA_UA).build()
        client(http).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            GZIPInputStream(resp.body.byteStream()).use { parse(it) }
        }
    }.getOrNull()

    /** Parse the uncompressed feed. A record without coordinates or a start time is skipped,
     *  and so is one whose opening is ending (`beingTerminated`). */
    fun parse(input: InputStream): List<BridgeOpening> {
        val p = KXmlParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        val out = ArrayList<BridgeOpening>()
        var inRecord = false
        var lat: Double? = null
        var lng: Double? = null
        var start: Long? = null
        var end: Long? = null
        var status: String? = null
        var bridge = false
        var text = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return out
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (local(p.name) == "situationRecord") {
                        inRecord = true
                        lat = null; lng = null; start = null; end = null; status = null; bridge = false
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (local(p.name)) {
                        "latitude" -> if (inRecord) lat = t.toDoubleOrNull()
                        "longitude" -> if (inRecord) lng = t.toDoubleOrNull()
                        "overallStartTime" -> if (inRecord) start = instantMs(t)
                        "overallEndTime" -> if (inRecord) end = instantMs(t)
                        "operatorActionStatus" -> if (inRecord) status = t
                        "generalNetworkManagementType" -> if (inRecord) bridge = t == "bridgeSwingInOperation"
                        "situationRecord" -> {
                            inRecord = false
                            val la = lat; val ln = lng; val s = start
                            if (bridge && la != null && ln != null && s != null && status != "beingTerminated") {
                                val openNow = status == "implemented" || status == "beingImplemented"
                                out += BridgeOpening(LatLng(la, ln), s, end, openNow)
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
        }
    }

    private fun local(name: String): String = name.substringAfter(':')

    private fun instantMs(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
}

/**
 * Which bridge on the route to announce, and how. Pure, so the timing is unit-tested.
 *
 * A bridge is announced once it is within [AHEAD_M] along the route. It is announced when it is
 * open now, or when a planned opening overlaps the minutes around the estimated time you reach
 * it ([MARGIN_MS] each side). Each bridge is announced once per route; a bridge that was planned
 * and then opens before you reach it is announced again as open.
 *
 * An "open now" record older than [MAX_OPEN_MS] is ignored: the feed keeps some that were never
 * closed (seen nine days old), and an opening lasts minutes.
 */
object BridgeAlerts {
    const val AHEAD_M = 5_000.0
    const val MARGIN_MS = 3 * 60_000L
    const val MAX_OPEN_MS = 60 * 60_000L

    /** A bridge on the route, [atM] meters along it, with its openings. */
    data class OnRoute(val atM: Double, val openings: List<BridgeOpening>)

    sealed class Alert {
        abstract val index: Int
        data class OpenNow(override val index: Int) : Alert()
        data class Planned(override val index: Int, val startMs: Long) : Alert()
    }

    /**
     * The alert due now, or null. [traveledM] is the meters driven on the route; [remainingM] and
     * [remainingS] are what is left of it, for the time to reach a bridge. [announced] holds keys
     * from [key] already spoken.
     */
    fun due(
        bridges: List<OnRoute>, traveledM: Double, remainingM: Double, remainingS: Double,
        nowMs: Long, announced: Set<String>,
    ): Alert? {
        for ((i, b) in bridges.withIndex()) {
            val ahead = b.atM - traveledM
            if (ahead < 0 || ahead > AHEAD_M) continue
            val etaMs = nowMs + if (remainingM > 0) (remainingS * 1000 * ahead / remainingM).toLong() else 0L
            if (b.openings.any { it.openNow && nowMs - it.startMs <= MAX_OPEN_MS } && key(i, true) !in announced) return Alert.OpenNow(i)
            val hit = b.openings.firstOrNull { o ->
                !o.openNow && o.startMs <= etaMs + MARGIN_MS && (o.endMs ?: o.startMs) >= etaMs - MARGIN_MS
            }
            if (hit != null && key(i, false) !in announced && key(i, true) !in announced) return Alert.Planned(i, hit.startMs)
        }
        return null
    }

    fun key(index: Int, openNow: Boolean): String = "$index:${if (openNow) "open" else "planned"}"
}
