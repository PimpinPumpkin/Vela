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
        // Downlink / RTT go only where google.com's Accept-CH asked for them, never to an image CDN.
        networkHints: Boolean = referer != null,
    ): Request.Builder {
        header("User-Agent", ua)
        header("Accept", accept)
        header("Accept-Language", acceptLanguage)
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
        if (networkHints) {
            header("Downlink", "10")
            header("RTT", "50")
        }
        return this
    }

    /**
     * The `Accept-Language` every Google request carries. The app sets it from the same locale list
     * its WebViews read (`LocaleList.getDefault()`), through [acceptLanguageFor], so a native request
     * and a page load in one session name the same languages (checked 2026-09-25: they differed, and
     * a Chinese-language install sent `en-US` natively beside a Chinese WebView).
     */
    @Volatile var acceptLanguage: String = "en-US,en;q=0.9"

    /**
     * Chrome's `Accept-Language` for a language list, as net::HttpUtil builds it: each tag, then its
     * bare language unless the next tag shares it (`en-US` -> `en-US,en`; `en,en-US` stays), no
     * duplicates, then q-values falling from 0.9 by 0.1 to a floor of 0.1.
     */
    fun acceptLanguageFor(tags: List<String>): String {
        val langs = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (langs.isEmpty()) return "en-US,en;q=0.9"
        val out = LinkedHashSet<String>()
        for ((i, lang) in langs.withIndex()) {
            out += lang
            val base = lang.substringBefore('-')
            if (base == lang) continue
            if (i < langs.size - 1 && langs[i + 1].substringBefore('-') == base) continue
            out += base
        }
        return out.mapIndexed { i, l -> if (i == 0) l else "$l;q=0.${(10 - i).coerceAtLeast(1)}" }.joinToString(",")
    }

    /** Chrome's `RTT` hint: the estimate times a per-host noise factor, capped at 3000 ms, rounded
     *  to 50 ms (content/common/client_hints). */
    fun rttHint(ms: Int, noise: Double): String =
        (Math.round((ms * noise).coerceIn(0.0, 3000.0) / 50.0) * 50).toString()

    /** Chrome's `Downlink` hint: kbps times the noise factor, capped at 10 Mbps, rounded to 50 kbps,
     *  printed in Mbps the way Chrome prints a double ("10", "1.5", "1.55"). */
    fun downlinkHint(kbps: Int, noise: Double): String {
        val k = Math.round((kbps * noise).coerceIn(0.0, 10_000.0) / 50.0) * 50
        return java.math.BigDecimal(k).movePointLeft(3).stripTrailingZeros().toPlainString()
    }

    /** The full version for the client hints: [candidate] when it is a real build of [ua]'s major
     *  (`155.0.8059.12`), else `<major>.0.0.0`, else null when [ua] names no Chrome major. */
    fun fullVersionFor(ua: String, candidate: String?): String? {
        val major = chromeMajor(ua) ?: return null
        return candidate?.takeIf { Regex("""$major\.0\.\d+\.\d+""").matches(it) } ?: "$major.0.0.0"
    }

    /**
     * The fetch-metadata headers Chrome puts on a request, for the WebView proxy: the WebView hands
     * `shouldInterceptRequest` only some of its headers, and without these a proxied request looks
     * like no browser at all (captured 2026-09-25: every proxied tile, icon, script and log call went
     * out with no `Sec-Fetch-*`). The kind comes from what the request is: the main frame is a
     * navigation, an `image/` or `text/css` Accept is an image or a stylesheet, a `.js` or `/js/`
     * path is a script, anything else is a fetch or XHR. The site is judged against [referer]'s host
     * (the page), by registrable domain (the last two labels, which is right for the Google hosts
     * the proxy carries).
     */
    fun fetchMetadata(url: String, referer: String?, accept: String, mainFrame: Boolean, method: String): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        val site = when {
            mainFrame && referer == null -> "none"
            else -> {
                val page = referer?.let { hostOf(it) } ?: host
                when {
                    page == host -> "same-origin"
                    siteOf(page) == siteOf(host) -> "same-site"
                    else -> "cross-site"
                }
            }
        }
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        val (mode, dest) = when {
            mainFrame -> "navigate" to "document"
            method.equals("POST", true) -> "cors" to "empty"
            accept.startsWith("image/") -> "no-cors" to "image"
            accept.startsWith("text/css") -> "no-cors" to "style"
            path.endsWith(".js") || path.contains("/js/") -> "no-cors" to "script"
            else -> "cors" to "empty"
        }
        return buildMap {
            put("Sec-Fetch-Site", site)
            put("Sec-Fetch-Mode", mode)
            put("Sec-Fetch-Dest", dest)
            if (mainFrame) put("Upgrade-Insecure-Requests", "1")
        }
    }

    private fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase().takeIf { it.isNotEmpty() }

    private fun siteOf(host: String): String = host.split('.').takeLast(2).joinToString(".")

    /** One brand out of a `Sec-CH-UA` list: `"Google Chrome";v="153"`. */
    data class Brand(val name: String, val major: String)

    /** The brands in a `Sec-CH-UA` value, in order, for the WebView's client-hint metadata. */
    fun brands(secChUa: String): List<Brand> =
        Regex(""""([^"]+)";v="(\d+)"""").findAll(secChUa).map { Brand(it.groupValues[1], it.groupValues[2]) }.toList()

    /**
     * The `Sec-CH-UA` value desktop Chrome [major] actually sends. Chrome derives all of it from the
     * major version (components/embedder_support/user_agent_utils.cc): a GREASE brand
     * "Not<c1>A<c2>Brand" whose two characters, version and position in the list are picked by
     * `major` modulo the table sizes, so each release has one exact header. Checked against real
     * headers: 120 `"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"`, 124 and 130
     * (BrowserHeadersTest). Until 2026-09-23 the compiled hint was hand-edited from an older release
     * and carried Chrome 137's GREASE brand under a 153 version number.
     */
    fun secChUaFor(major: Int): String {
        val chars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val greaseVersions = listOf("8", "99", "24")
        val list = listOf(
            "Not${chars[major % chars.size]}A${chars[(major + 1) % chars.size]}Brand" to greaseVersions[major % greaseVersions.size],
            "Chromium" to "$major",
            "Google Chrome" to "$major",
        )
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val order = orders[major % orders.size]
        val out = arrayOfNulls<Pair<String, String>>(3)
        for (i in list.indices) out[order[i]] = list[i]
        return out.joinToString(", ") { "\"${it!!.first}\";v=\"${it.second}\"" }
    }

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
