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
    private val seq = AtomicInteger()
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }
    }

    /** Reviews for [featureId] (`0x..:0x..`) — newest/most-relevant first as Google renders them,
     *  each with its uploaded photos — or empty on any failure. */
    suspend fun fetch(featureId: String): List<Review> {
        val cid = cidOf(featureId) ?: return emptyList()
        return mutex.withLock {
            val id = "r" + seq.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            val raw = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        val wv = ensureWebView()
                        val ready = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val scheme = request?.url?.scheme
                                return scheme != null && scheme != "https" && scheme != "http"
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, SETTLE_MS)
                            }
                        }
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
        webView = wv
        return wv
    }

    /** Self-polling DOM scraper: scroll the review panel to surface more, extract each review card
     *  (one star rating + an author button), and bridge a JSON array back once it's stable. */
    private fun extractScript(id: String): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, last=-1, tries=0;
              function num(s){ var m=(s||'').match(/([0-9.]+)\s*star/i); return m?Math.round(parseFloat(m[1])):0; }
              function extract(){
                var all=[].slice.call(document.querySelectorAll('div'));
                var cards=all.filter(function(d){
                  var st=d.querySelectorAll('[role="img"][aria-label*="star" i], span[aria-label*=" star" i]');
                  var len=d.textContent?d.textContent.length:0;
                  return st.length===1 && d.querySelector('button[aria-label]') && len>40 && len<2500;
                });
                var revs=cards.filter(function(c){ return !cards.some(function(o){ return o!==c && c.contains(o); }); });
                return revs.map(function(c){
                  var star=c.querySelector('[role="img"][aria-label*="star" i], span[aria-label*=" star" i]');
                  var ab=[].slice.call(c.querySelectorAll('button[aria-label]')).filter(function(b){ return /[A-Za-z]/.test(b.getAttribute('aria-label')||''); })[0];
                  var spans=[].slice.call(c.querySelectorAll('span'));
                  var date=''; for(var i=0;i<spans.length;i++){ var t=spans[i].textContent||''; if(t.length<22 && (/\bago\b/.test(t)||/^\s*20\d\d\s*$/.test(t))){ date=t.trim(); break; } }
                  var text='', best=0; for(var j=0;j<spans.length;j++){ var tt=spans[j].textContent||''; if(tt.length>best && !/star/i.test(tt) && tt.indexOf(String.fromCharCode(10))<0){ best=tt.length; text=tt; } }
                  var avatar=''; var ai=c.querySelector('img'); if(ai && /googleusercontent/.test(ai.src||'')) avatar=ai.src;
                  var photos=[];
                  [].slice.call(c.querySelectorAll('button')).forEach(function(b){
                    var bg=''; try{ bg=getComputedStyle(b).backgroundImage||''; }catch(e){}
                    var mm=bg.match(/url\(["']?(https:\/\/[^"')]+googleusercontent[^"')]+)/);
                    if(mm && !/\/a[\/-]|ACg8oc|ALV-/.test(mm[1])) photos.push(mm[1]);
                  });
                  return { r:num(star&&star.getAttribute('aria-label')), a:(ab?ab.getAttribute('aria-label'):'').slice(0,80), d:date, t:text, av:avatar, p:photos.slice(0,8) };
                }).filter(function(x){ return x.a; });
              }
              function scrollPanels(){ try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){ if(d.scrollHeight>d.clientHeight+200 && d.clientHeight>250 && d.getBoundingClientRect().left<640){ d.scrollTop=d.scrollHeight; } }); }catch(e){} }
              function tick(){
                tries++;
                scrollPanels();
                var revs=extract();
                if( (revs.length>=8 && revs.length===last) || tries>16 ){
                  try{ VelaBridge.onResult(ID, JSON.stringify(revs)); }catch(e){ try{ VelaBridge.onResult(ID,'[]'); }catch(e2){} }
                  return;
                }
                last=revs.length;
                setTimeout(tick, 700);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 22_000L
        const val SETTLE_MS = 1_400L
        const val MAX_LOAD_MS = 8_000L
    }
}
