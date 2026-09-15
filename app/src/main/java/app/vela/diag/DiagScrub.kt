package app.vela.diag

/**
 * What the diagnostics export does to each breadcrumb before it leaves the phone.
 *
 * Always: coordinate-looking decimals (three or more places) round to two (~1 km), so a search
 * bias, an off-route fix or a viewport box says which AREA without saying which building.
 *
 * With "Redact places in exports" on (issue #507): coordinates round to ONE place (~10 km), the
 * quoted search terms and intents become "[redacted]", a nav start's destination label goes,
 * URLs keep only their host, `cid=` values go, and the reviews probes (page text) drop their
 * detail entirely. Timing, counts, zoom levels and error text stay, which is what a dev needs to
 * follow a "route came back empty" or "search hung" report.
 */
internal object DiagScrub {
    private val coordLike = Regex("""-?\d{1,3}\.\d{3,}""")
    private val quoted = Regex("\"[^\"]*\"")
    private val url = Regex("""https?://[^\s"'<>]+""")
    private val cid = Regex("""cid=\d+""")
    private val navStart = Regex("""^(start (?:→|->) ).*""")

    fun summary(s: String, redact: Boolean): String {
        val places = if (redact) 1 else 2
        var out = coordLike.replace(s) { m ->
            m.value.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.${places}f", it) } ?: m.value
        }
        if (!redact) return out
        out = quoted.replace(out, "\"[redacted]\"")
        out = url.replace(out) { m ->
            val v = m.value
            val end = v.indexOf('/', v.indexOf("//") + 2).let { if (it < 0) v.length else it }
            v.substring(0, end) + "/[redacted]"
        }
        out = cid.replace(out, "cid=[redacted]")
        out = navStart.replace(out) { m -> m.groupValues[1] + "[redacted]" }
        return out
    }

    fun detail(kind: String, d: String?, redact: Boolean): String? {
        if (d == null) return null
        if (redact && kind == "reviews") return "[redacted]"
        return summary(d, redact)
    }

    fun note(redact: Boolean): String =
        if (redact) "coordinates rounded to ~10 km; searches, destinations, links and place names redacted"
        else "coordinates rounded to ~1 km for privacy"
}
