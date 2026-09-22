package app.vela.core.data.google

import okhttp3.Request

/**
 * The browser header set the keyless scrape sends, and the validation that keeps a pushed
 * calibration bundle from bricking it.
 *
 * WHY A COHERENT SET: Vela already claims to be Chrome ([app.vela.core.VelaConfig.USER_AGENT]),
 * but it sent ONLY the `User-Agent` string. Real Chrome sends a cluster alongside it — the
 * `Sec-CH-UA*` client hints and the `Sec-Fetch-*` metadata — and their ABSENCE next to a Chrome
 * UA is a sharper inconsistency than a stale version number. Claim it completely or not at all.
 *
 * WHY CALIBRATED: Chrome ships a stable release roughly every four weeks, so a compile-time
 * constant is stale again next month by construction — the shipped UA was Chrome 124 (≈ April
 * 2024) well into 2026. A stale UA is a CORRECTNESS risk before it's a fingerprinting one:
 * Google serves different response shapes to different browser generations, so a client claiming
 * a two-year-old Chrome may be parsing a legacy code path that can be retired without notice —
 * indistinguishable, from the app's side, from ordinary calibration drift. The UA therefore lives
 * in the signed bundle beside the pb templates, and a refresh is a version bump + re-sign.
 *
 * WHY SANITIZE: OkHttp THROWS on a header value containing a control character, and the throw
 * happens at request-build time inside `runCatching` blocks that swallow it. A single stray
 * newline in a pushed `userAgent` would therefore silently kill every scrape with no crash and no
 * log — the same failure class as the 12 s `callTimeout` silently hiding the 197 MB overlay
 * download. [sanitize] rejects anything unsafe so the compiled default survives a bad push.
 */
object BrowserHeaders {

    /** Longest plausible UA. Real ones sit near 120 chars; this is slack, not a target. */
    private const val MAX_UA_LENGTH = 400

    /**
     * A header-safe version of [value], or null when it cannot be sent.
     *
     * Order matters, and it is deliberate:
     *  1. **Trim first.** Kotlin's `trim` drops surrounding `\n`, `\r`, `\t` and even NBSP, so a
     *     trailing newline — the likeliest cosmetic artifact in a hand-edited JSON bundle — is
     *     RECOVERED into a valid UA rather than rejected. Recovering beats silently falling back
     *     to a stale compiled default, which is the failure the remote channel exists to avoid.
     *  2. **Then reject what's left**: blanks, anything longer than [MAX_UA_LENGTH], and any
     *     character outside printable US-ASCII (0x20..0x7E) — exactly OkHttp's accepted range, so
     *     a value that survives this can never throw at request-build time.
     *
     * The security-relevant case is an INTERIOR control character (`"Mozilla/5.0\r\nX-Evil: 1"`),
     * which trim cannot reach and step 2 always rejects. Callers fall back to the compiled
     * default, never to an empty header.
     */
    fun sanitize(value: String?): String? {
        val v = value?.trim() ?: return null
        if (v.isEmpty() || v.length > MAX_UA_LENGTH) return null
        if (v.any { it.code < 0x20 || it.code > 0x7E }) return null
        return v
    }

    /**
     * Apply the Chrome-consistent header set for a top-level Maps document fetch.
     *
     * [secChUa] is the brand list that must MATCH [ua]'s major version — they're pushed together
     * in the calibration bundle for exactly that reason; a hint advertising a different version
     * than the UA string is worse than sending no hint at all. [referer] is omitted when null
     * (the session-warming GET has no referrer, as a real first navigation doesn't).
     */
    fun Request.Builder.browserHeaders(
        ua: String,
        secChUa: String,
        referer: String? = null,
        accept: String = ACCEPT_DOCUMENT,
        fetchDest: String = "document",
        fetchMode: String = "navigate",
        fetchSite: String = "none",
    ): Request.Builder {
        header("User-Agent", ua)
        header("Accept", accept)
        header("Accept-Language", "en-US,en;q=0.9")
        // Client hints. `?0` and `"Windows"` track the DEFAULT desktop UA; if the calibrated UA is
        // ever switched to a mobile string these must move with it (see CLAUDE.md — a mobile UA
        // also changes the response shape Google serves, so that swap is a recalibration, not a
        // header edit).
        header("Sec-CH-UA", secChUa)
        header("Sec-CH-UA-Mobile", "?0")
        header("Sec-CH-UA-Platform", "\"Windows\"")
        header("Sec-Fetch-Dest", fetchDest)
        header("Sec-Fetch-Mode", fetchMode)
        header("Sec-Fetch-Site", fetchSite)
        if (referer != null) header("Referer", referer)
        // The two client hints google.com asks for (`Accept-CH: Downlink, RTT`, checked
        // 2026-09-22): Chrome sends them on every later request to the origin, rounded (Mbps
        // capped at 10, RTT to 25 ms steps), so a request without them is one that never saw
        // the document. The session warm-up is the document; the XHRs that follow carry them.
        if (referer != null) {
            header("Downlink", "10")
            header("RTT", "50")
        }
        return this
    }

    /** One brand out of a `Sec-CH-UA` list: `"Google Chrome";v="153"`. */
    data class Brand(val name: String, val major: String)

    /** The brands in a `Sec-CH-UA` value, in order, for the WebView's client-hint metadata. */
    fun brands(secChUa: String): List<Brand> =
        Regex(""""([^"]+)";v="(\d+)"""").findAll(secChUa).map { Brand(it.groupValues[1], it.groupValues[2]) }.toList()

    /** The major version in a Chrome user-agent string, or null. */
    fun chromeMajor(ua: String): String? = Regex("""Chrome/(\d+)\.""").find(ua)?.groupValues?.get(1)

    const val ACCEPT_DOCUMENT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    /** An in-page XHR/RPC rather than a navigation — what the data endpoints actually are. */
    fun Request.Builder.browserXhrHeaders(
        ua: String,
        secChUa: String,
        referer: String,
    ): Request.Builder = browserHeaders(
        ua = ua,
        secChUa = secChUa,
        referer = referer,
        accept = "*/*",
        fetchDest = "empty",
        fetchMode = "cors",
        fetchSite = "same-origin",
    )
}
