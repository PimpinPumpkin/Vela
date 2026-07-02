package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.core.VelaConfig
import app.vela.core.data.google.parse.ReviewsWebParser
import app.vela.core.model.Review
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a place's reviews through a hidden [WebView] — the keyless reviews source after Google
 * **deleted** the old `listentitiesreviews` endpoint (now HTTP 404) and moved reviews behind a
 * `batchexecute` RPC (`rpcids=T4jwAf`) whose request proto resisted capture.
 *
 * Rather than replay that RPC, we let Google's own JS render the reviews (loading the place's
 * canonical `?cid=` page, anonymous/no-login, desktop UA — same tactic as [WebPhotoFetcher] and
 * `WebDirectionsFetcher`) and read them back out of the DOM: per review the star rating, author,
 * relative date, text **and the reviewer's uploaded photos** (the old endpoint only ever served
 * avatars — this is also how per-review photos finally arrive). The page builds a JSON array over a
 * JS bridge; [ReviewsWebParser] (in `:core`) turns it into [Review]s.
 *
 * Strictly best-effort + lazy: any failure/timeout returns empty and the place sheet just shows no
 * reviews. Serialized by a [Mutex] since the single WebView navigates per place.
 */
@Singleton
class WebReviewsFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val progress = ConcurrentHashMap<String, (Int) -> Unit>()
    private val seq = AtomicInteger()
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }

        // Live "N reviews found so far" ticks from the scraper (the scrape runs ~10-40 s on busy
        // pages — the UI shows this so the wait reads as progress, not a hang). Arrives on the
        // WebView's JavaBridge thread — the callback must be thread-safe.
        @JavascriptInterface
        fun onProgress(id: String, n: Int) {
            progress[id]?.invoke(n)
        }
    }

    /** Reviews for [featureId] (`0x..:0x..`) — newest/most-relevant first as Google renders them,
     *  each with its uploaded photos — or empty on any failure. [onProgress] streams the running
     *  count of reviews found while the scrape is in flight (called off the main thread). */
    suspend fun fetch(featureId: String, onProgress: (Int) -> Unit = {}): List<Review> {
        val cid = cidOf(featureId) ?: return emptyList()
        return mutex.withLock {
            val id = "r" + seq.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            progress[id] = onProgress
            val raw = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        val wv = ensureWebView()
                        val ready = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Only google.com pages — a stray click on an external link (e.g. the
                                // place's website/menu action) must not navigate the hidden WebView away.
                                val u = request?.url ?: return false
                                val scheme = u.scheme
                                if (scheme != "https" && scheme != "http") return true
                                val host = u.host.orEmpty()
                                return !(host == "google.com" || host.endsWith(".google.com"))
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, SETTLE_MS)
                            }
                        }
                        // Blank the PREVIOUS place's DOM before navigating — a slow load could otherwise
                        // let the MAX_LOAD fallback inject the scraper into the old page and return the
                        // previous place's reviews for THIS featureId (empty > wrong).
                        wv.evaluateJavascript("try{document.documentElement.innerHTML=''}catch(e){}", null)
                        wv.loadUrl("https://www.google.com/maps?cid=$cid&hl=en&gl=us")
                        // Proceed even if the SPA's onPageFinished is slow.
                        main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, MAX_LOAD_MS)
                        ready.await()
                        wv.evaluateJavascript(extractScript(id), null)
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
                progress.remove(id)
            }
            if (raw.isNullOrEmpty()) emptyList() else runCatching { ReviewsWebParser.parse(raw) }.getOrDefault(emptyList())
        }
    }

    /** The Google "cid" = the LOW half of the `0xHIGH:0xLOW` feature id as an unsigned decimal — the
     *  canonical `maps.google.com?cid=` deep-link to a place. */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, 16).toString() }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Desktop UA so Google serves the desktop web Maps (a mobile UA deep-links to intent://).
        wv.settings.userAgentString = VelaConfig.USER_AGENT
        wv.addJavascriptInterface(Bridge(), "VelaBridge")
        // Give the hidden (never-attached) WebView a REAL offscreen viewport. Google's reviews list is
        // virtualized + lazy-loaded off the scroll viewport; a 0×0 headless WebView renders the chrome
        // (rating histogram, topic filters) but NEVER the review cards. A tall explicit layout makes the
        // scroll pane real so the list renders + pages. (The photo gallery's category grids need the
        // same treatment — see WebPhotoFetcher.)
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WV_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(WV_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WV_WIDTH, WV_HEIGHT)
        webView = wv
        return wv
    }

    /** Self-polling DOM scraper: open the full reviews list, then scroll it a window at a time,
     *  ACCUMULATING each review card into a keyed set (Google virtualizes the panel — it recycles
     *  DOM nodes as you scroll, so any single snapshot holds only ~10 cards; the union across scroll
     *  positions is the full list). Bridges the accumulated JSON array back once the list is exhausted
     *  or the cap is hit. */
    private fun extractScript(id: String): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, tries=0, opened=false, acc={}, accN=0, lastN=0, noGrow=0, atBottom=0;
              var openedAt=-1, sawCards=false, lastRep=-1;
              var CAP=50;
              function num(s){ var m=(s||'').match(/([0-9.]+)\s*star/i); return m?Math.round(parseFloat(m[1])):0; }
              function t1(c,sel){ var e=c.querySelector(sel); return e?(e.textContent||'').trim():''; }
              function extract(){
                // Review cards are `.jJc9Ad`, each with a unique `data-review-id` — far more robust than the
                // old "div with one star + text" heuristic, which also matched the place header ("4.6 stars
                // (57,969)") and affiliate ticket cards, and missed most real reviews.
                var revs=[].slice.call(document.querySelectorAll('.jJc9Ad'));
                return revs.map(function(c){
                  var idEl=c.querySelector('[data-review-id]'); var rid=idEl?(idEl.getAttribute('data-review-id')||''):'';
                  var star=c.querySelector('[role="img"][aria-label*="star" i], span[aria-label*=" star" i]');
                  // author: Google's review name class, else pull the NAME out of a button aria — the
                  // name is always right before "'s review" (after a "Share "/"Photo N on " prefix) or
                  // after "Photo of ". (Class names rotate; the aria phrasing is stable + semantic.)
                  var author=t1(c,'.d4r55')||t1(c,'.Vpc5Fe')||t1(c,'.TSUbDb');
                  if(!author){ var bs=[].slice.call(c.querySelectorAll('button[aria-label],a[aria-label]'));
                    var strip=/^(?:Share|Like|Response from|Photo of|Photo\s*\d*\s*on|\+?\s*\d*\s*(?:more\s*)?photos?\s*on|\d+\s*photos?\s*on)\s+/i;
                    for(var i=0;i<bs.length;i++){ var a=bs[i].getAttribute('aria-label')||'';
                      var m=a.match(/^(.+?)'s review\b/); var cand=m?m[1]:(a.match(/^Photo of (.+)${'$'}/)||[])[1];
                      if(cand){ var nm=cand.replace(strip,'').trim(); if(nm){ author=nm; break; } } } }
                  // review text: the wiI7pd body, else the longest leaf span that isn't chrome.
                  var text=t1(c,'.wiI7pd');
                  if(!text){ var best=0; [].slice.call(c.querySelectorAll('span')).forEach(function(s){ if(s.childElementCount===0){ var tt=(s.textContent||'').trim(); if(tt.length>best && tt.length>12 && !/^(see more|more|like|share|response from|local guide)/i.test(tt) && !/\bstar/i.test(tt)){ best=tt.length; text=tt; } } }); }
                  // relative date.
                  var date=t1(c,'.rsqaWe');
                  if(!date){ [].slice.call(c.querySelectorAll('span')).forEach(function(s){ var tt=(s.textContent||'').trim(); if(!date && tt.length<22 && (/\bago\b/.test(tt)||/^20\d\d${'$'}/.test(tt))) date=tt; }); }
                  var avatar=''; var ai=c.querySelector('img'); if(ai && /googleusercontent/.test(ai.src||'')) avatar=ai.src;
                  var photos=[];
                  [].slice.call(c.querySelectorAll('button')).forEach(function(b){
                    var bg=''; try{ bg=getComputedStyle(b).backgroundImage||''; }catch(e){}
                    var mm=bg.match(/url\(["']?(https:\/\/[^"')]+googleusercontent[^"')]+)/);
                    if(mm && !/\/a[\/-]|ACg8oc|ALV-/.test(mm[1]) && photos.indexOf(mm[1])<0) photos.push(mm[1]);
                  });
                  return { rid:rid, r:num(star&&star.getAttribute('aria-label')), a:author.slice(0,80), d:date, t:text, av:avatar, p:photos.slice(0,10) };
                  // Require an AUTHOR (rid stays the de-dup key): the parser drops author-less entries
                  // anyway, so letting them through only wastes CAP slots on cards we can't render.
                }).filter(function(x){ return x.a; });
              }
              function expand(){ [].slice.call(document.querySelectorAll('button')).forEach(function(b){ var l=((b.getAttribute('aria-label')||b.textContent)||'').trim(); if(/^(see more|more)${'$'}/i.test(l)){ try{ b.click(); }catch(e){} } }); }
              // De-dupe across scroll windows by the review's stable id (falls back to author+date+text).
              function key(x){ return x.rid || ((x.a||'')+'|'+(x.d||'')+'|'+((x.t||'').slice(0,48))); }
              // Scroll each tall left-column panel down by ~80% of a viewport (windows overlap so no
              // card is skipped past). Returns true if anything actually moved (false ⇒ at the bottom).
              function scrollStep(){
                var moved=false;
                try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){
                  if(d.scrollHeight>d.clientHeight+200 && d.clientHeight>250 && d.getBoundingClientRect().left<640){
                    var before=d.scrollTop; d.scrollTop=Math.min(d.scrollHeight, d.scrollTop+Math.round(d.clientHeight*0.8));
                    if(d.scrollTop>before+5) moved=true;
                  }
                }); }catch(e){}
                return moved;
              }
              // Open the FULL reviews list. The canonical entry is the "Reviews" role=tab; fall back to a
              // "More reviews" button for layouts that only expose that. On busy pages (food/retail) the
              // tab's list can take ~8 s to render after the click — the idle-bail is gated on `sawCards`
              // below so we never quit during that blank window (that was the "only 3 reviews" bug).
              function openFull(){
                // Prefer the "Reviews" role=tab. Click it every tick UNTIL it actually reports selected —
                // a click on a not-yet-hydrated tab silently no-ops, so one-and-done can leave the list
                // unopened. Once selected we latch `opened` and STOP clicking: re-clicking a selected-but-
                // still-loading list restarts its ~8 s render (that regression turned busy pages back to 3).
                var ts=[].slice.call(document.querySelectorAll('[role="tab"]'));
                for(var i=0;i<ts.length;i++){
                  var tl=((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim();
                  if(/^reviews\b/i.test(tl)){
                    if((ts[i].getAttribute('aria-selected')||'')==='true'){ if(!opened){ opened=true; openedAt=tries; } return; }
                    try{ ts[i].click(); }catch(e){}
                    return;
                  }
                }
                // No reviews tab in this layout — fall back to the "More reviews" button (clicked once).
                if(opened) return;
                var bs=[].slice.call(document.querySelectorAll('button'));
                for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||bs[i].textContent)||''); if(/more reviews/i.test(l)){ try{ bs[i].click(); }catch(e){} opened=true; openedAt=tries; return; } }
              }
              function tick(){
                tries++;
                // Let the SPA hydrate a beat before clicking the Reviews tab — clicking a not-yet-live
                // tab on tick 1 can silently no-op, and then the list only renders much later.
                if(tries>=2) openFull();
                expand();
                // ACCUMULATE this window's cards (don't replace) — the panel virtualizes, so each
                // scroll position exposes a fresh ~10 that would otherwise be lost when recycled.
                var revs=extract();
                for(var i=0;i<revs.length;i++){ var k=key(revs[i]); if(k.length>2 && !acc[k]){ acc[k]=revs[i]; accN++; } }
                // Stream the running count to the app whenever it changes — the sheet shows a live
                // "N of ~M" while this scrape grinds, so the wait reads as progress, not a hang.
                if(accN!==lastRep){ lastRep=accN; try{ VelaBridge.onProgress(ID, accN); }catch(e){} }
                // Have the review cards actually rendered yet? On busy business pages (food, retail) the
                // Reviews tab's list can take ~8 s to populate after the click; until then the panel holds
                // only the rating histogram + topic chips, NOT the cards.
                if(document.querySelectorAll('.jJc9Ad').length>0) sawCards=true;
                var moved=scrollStep();
                atBottom = moved ? 0 : atBottom+1;
                noGrow = (accN===lastN) ? noGrow+1 : 0;
                lastN=accN;
                // Idle-bail ONLY after cards have really rendered. The 3-reviews bug was bailing during the
                // blank pre-render window: atBottom/noGrow climbed while the list was still empty, so the
                // "settled at the bottom, nothing new" test fired before the reviews ever appeared.
                var settled = sawCards && (!opened || tries>=openedAt+6);
                // Done: hit the cap, OR settled at the bottom with no new reviews, OR ran long.
                if( accN>=CAP || (settled && atBottom>=4 && noGrow>=4) || tries>60 ){
                  var out=[]; for(var kk in acc) out.push(acc[kk]);
                  out = out.slice(0,CAP);
                  try{ VelaBridge.onResult(ID, JSON.stringify(out)); }catch(e){ try{ VelaBridge.onResult(ID,'[]'); }catch(e2){} }
                  return;
                }
                setTimeout(tick, 550);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        // Must outlast the script's own hard stop (60 ticks × 550 ms ≈ 33 s + page load) — if Kotlin
        // times out first we return EMPTY, which is worse than few. Lazy + best-effort as ever.
        const val TOTAL_TIMEOUT_MS = 45_000L
        const val SETTLE_MS = 800L
        const val MAX_LOAD_MS = 7_000L
        // Offscreen viewport for the headless WebView — tall so the virtualized review list renders a
        // healthy batch per scroll position.
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 3200
    }
}
