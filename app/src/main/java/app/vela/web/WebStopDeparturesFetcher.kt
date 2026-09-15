package app.vela.web

import android.content.Context
import android.webkit.WebView
import app.vela.core.data.google.parse.StopDeparturesParser
import app.vela.core.model.StopDepartures
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a transit stop's live departure board through a hidden [WebView] — the same
 * anonymous, no-login trick as [WebDirectionsFetcher] and [WebPhotoFetcher]. The board
 * is embedded in the station's own place-details payload (`APP_INITIALIZATION_STATE`), so
 * loading the canonical `?cid=` place page as a real browser engine and reading that state
 * out gives us the schedule with NO extra endpoint and NO account. OkHttp gets a bot-degraded
 * reply (TLS-fingerprint detection), exactly like photos/transit, so it must be a WebView.
 *
 * Verified keyless + anonymous against a live capture (2026-07-12): opening "See departure
 * board" fires no data request, and the times survive a logged-out session (unlike popular
 * times). [StopDeparturesParser] pulls the line/direction/time/headway structure out.
 * Best-effort: any failure/timeout, or a place that isn't a transit stop, returns null.
 */
@Singleton
class WebStopDeparturesFetcher @Inject constructor(
    @ApplicationContext context: Context,
) : HiddenWebView(context, "stops") {

    /** The departure board for the station with [featureId] (`0x..:0x..`), or null on any
     *  failure/timeout, or when the place isn't a transit stop (no board in its payload). */
    suspend fun fetch(featureId: String): StopDepartures? = session { fetchLocked(featureId) }

    private suspend fun fetchLocked(featureId: String): StopDepartures? {
        val cid = cidOf(featureId) ?: return null
        // hl=en to match the app's existing transit board (WebDirectionsFetcher also pins en); the
        // clock times/headway come back in 12-hour form the parser reads. gl=us keeps the schedule US-shaped.
        val url = "https://www.google.com/maps?cid=$cid&hl=en&gl=us"
        val raw = request(TOTAL_TIMEOUT_MS) { id -> load(url, id) }
        // Debug builds keep the last raw payload on disk (filesDir/depdump.txt): the board schema is
        // positional and agency-shaped, so a "this stop parses wrong" report is only diagnosable from the
        // actual blob. Release builds never write it.
        if (app.vela.BuildConfig.DEBUG && !raw.isNullOrEmpty()) {
            runCatching { java.io.File(context.filesDir, "depdump.txt").writeText(raw) }
        }
        return if (raw.isNullOrEmpty()) null
        else runCatching { StopDeparturesParser.parse(raw) }.getOrNull()
    }

    override fun onPageFinished(view: WebView, url: String?, requestId: String) {
        main.postDelayed({ view.evaluateJavascript(extract(requestId), null) }, SETTLE_MS)
    }

    /** cid = LOW half of the `0xHIGH:0xLOW` feature id as unsigned decimal (the `?cid=` deep-link). */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, 16).toString() }.getOrNull()
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 1_600L
        /** Pull the place-details string out of APP_INITIALIZATION_STATE — the longest
         *  `)]}'`-guarded array (the place blob carrying the transit schedule). The SPA fills
         *  it a beat after page-finish, so poll up to ~7 s. Same shape as WebDirectionsFetcher. */
        fun extract(id: String) = """
            (function(){
              var tries = 0;
              function findBest(){
                var s = window.APP_INITIALIZATION_STATE, best = "";
                function scan(x, d){
                  if (d > 7 || x == null) return;
                  if (typeof x === 'string'){ if (x.indexOf(")]}'") === 0 && x.length > best.length) best = x; return; }
                  if (typeof x === 'object'){ for (var k in x) scan(x[k], d + 1); }
                }
                try { scan(s, 0); } catch(e){}
                return best;
              }
              function attempt(){
                var best = findBest();
                if (best && best.length > 5000){ VelaBridge.onResult('$id', best.slice(0, 1500000)); return; }
                if (tries++ < 12) setTimeout(attempt, 600);
                else VelaBridge.onResult('$id', best ? best.slice(0, 1500000) : "");
              }
              attempt();
            })();
        """.trimIndent()
    }
}
