package app.vela.web

import android.content.Context
import android.webkit.WebView
import app.vela.core.data.google.parse.TransitParser
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches public-transit directions through a hidden [WebView] — the same trick
 * as [WebPhotoFetcher], for the same reason: Google's transit routing serves a
 * real itinerary set **only to a genuine browser engine**. A plain HTTP client
 * (OkHttp/curl) asking `/maps/preview/directions` with the transit mode flag
 * gets silently downgraded to a *driving* reply (TLS-fingerprint bot-detection,
 * not headers — verified on-device).
 *
 * A WebView is Chromium, so it loads the desktop `maps/dir/…/!3e3` page as an
 * anonymous, no-login session — exactly like a logged-out browser, which does
 * get transit — and the first transit result set is server-rendered into
 * `window.APP_INITIALIZATION_STATE`. We read that nested state out, hand the raw
 * Google response string back over a JS bridge, and parse it with the keyless
 * [TransitParser]. Best-effort: any failure/timeout returns empty.
 *
 * Per-stop drill-down (intermediate stops + the ridden polyline) is a separate,
 * token-gated `sv1Drc` batchexecute that fires when you expand a trip — a future
 * layer; this returns the results board (times, duration, line badges, agency).
 */
@Singleton
class WebDirectionsFetcher @Inject constructor(
    @ApplicationContext context: Context,
) : HiddenWebView(context, "directions") {

    /** The transit results board for [origin]→[destination], or empty on any
     *  failure/timeout (the caller then offers drive/walk/bike instead).
     *  [timeMode] 0 = leave now, 1 = depart at, 2 = arrive by, 3 = last available; [timeEpochSec]
     *  is the chosen wall-clock (Unix seconds), null for "now". */
    suspend fun transit(
        origin: LatLng,
        destination: LatLng,
        timeMode: Int = 0,
        timeEpochSec: Long? = null,
        // Preferred vehicle kinds (issue #431), Google's own numbering: 0 bus, 1 subway, 2 train,
        // 3 tram and light rail. Empty = no preference.
        prefer: Set<Int> = emptySet(),
    ): List<TransitItinerary> = session { transitLocked(origin, destination, timeMode, timeEpochSec, prefer) }

    private suspend fun transitLocked(
        origin: LatLng,
        destination: LatLng,
        timeMode: Int,
        timeEpochSec: Long?,
        prefer: Set<Int> = emptySet(),
    ): List<TransitItinerary> {
        // Google's transit data param. Now = the plain `!4m2!4m1!3e3`. For a scheduled time we insert
        // Google's time block `!2m3!6e{0=depart,1=arrive,2=last}!7e2!8j<unix-seconds>` before `!3e3`.
        // The `!4m` wrappers are DESCENDANT counts, so the inner group grows 4m1 → 4m5 (2m3+6e+7e+8j+3e3)
        // and the outer 4m2 → 4m6. Verified against a real Google Maps transit-with-time URL (2026-07-08);
        // an earlier `!4m8!4m7` guess had the wrong counts, so Google silently fell back to "now".
        val timeRef = when (timeMode) { 2 -> 1; 3 -> 2; else -> 0 } // depart=0, arrive=1, last available=2
        // `!8j` is NOT a unix timestamp: Google reads it as a LOCAL clock in seconds, i.e. the
        // wall-clock time as if it were UTC. Sending the true epoch shifted every schedule by the
        // zone offset (issue #433: BST users saw buses an hour early, a UTC+3 user three hours),
        // and the western US only looked right because 7 hours of shift is a different day's
        // worth of departures nobody noticed. The phone's zone stands in for the origin's.
        val localSec = timeEpochSec?.let { it + java.util.TimeZone.getDefault().getOffset(it * 1000L) / 1000L }
        // The `!2m` options group holds the preferred vehicle kinds (`!5e{k}`, issue #431) and
        // the time block; the `!4m` wrappers count descendants, so they grow with the entries.
        val entries = prefer.sorted().map { "5e$it" } +
            (if (timeMode == 0 || localSec == null) emptyList() else listOf("6e$timeRef", "7e2", "8j$localSec"))
        val data = if (entries.isEmpty()) "!4m2!4m1!3e3"
        else "!4m${entries.size + 3}!4m${entries.size + 2}!2m${entries.size}!" + entries.joinToString("!") + "!3e3"
        val url = "https://www.google.com/maps/dir/" +
            "${origin.lat},${origin.lng}/${destination.lat},${destination.lng}" +
            "/data=$data?hl=en&gl=us"
        val raw = request(TOTAL_TIMEOUT_MS) { id -> load(url, id) }
        return if (raw.isNullOrEmpty()) emptyList()
        else runCatching { TransitParser.parse(raw, origin, destination) }.getOrDefault(emptyList())
    }

    override fun onPageFinished(view: WebView, url: String?, requestId: String) {
        main.postDelayed({ view.evaluateJavascript(JsNames.of(extract(requestId)), null) }, SETTLE_MS)
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 1_800L

        /** Pull the directions response string out of APP_INITIALIZATION_STATE —
         *  the `)]}'`-guarded array Google embeds under slot [3] (minified key).
         *  Two such strings live there: a ~1.7 KB stub and the real ~165 KB
         *  itinerary payload, so we take the LONGEST and require it to be
         *  substantial (the stub appears first). The SPA fills it a beat after
         *  page-finish, so we poll for up to ~7 s. */
        fun extract(id: String) = """
            (function(){
              var tries = 0;
              function findBest(){
                var s = window.APP_INITIALIZATION_STATE, best = "";
                function scan(x, d){
                  if (d > 6 || x == null) return;
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
