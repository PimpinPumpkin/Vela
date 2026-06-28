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
import app.vela.core.model.Photo
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
 * Fetches a place's photo gallery through a hidden [WebView] by **loading the place's own
 * `?cid=` page and scraping the rendered photo URLs out of the DOM** — the same tactic as
 * [WebReviewsFetcher].
 *
 * Why not the dedicated `hspqX` photos RPC? On-device logging (2026-06-28) proved Google
 * **degrades a bare anonymous `hspqX` POST per-session** to a single Street-View-only reply
 * (`streetviewpixels`, ~2 KB) — and a same-session retry returns the byte-identical degraded
 * answer, so the RPC is unreliable keyless. But Google **renders the real photo collage to a
 * logged-out browser on the place PAGE itself** (that's how a user sees them). So we let
 * Google's own JS draw the page and read the `googleusercontent` photo URLs back out of the
 * DOM — much harder for it to bot-degrade than a naked RPC call.
 *
 * Anonymous / no-login, desktop UA (a mobile UA deep-links to `intent://`). Strictly
 * best-effort + lazy: any failure/timeout returns empty and the caller keeps the search-preview
 * photo. Serialized by a [Mutex] since the single WebView navigates per place.
 */
@Singleton
class WebPhotoFetcher @Inject constructor(
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

    /** The gallery for [featureId] (`0x..:0x..`) — each [Photo] is its URL (no posted date from a
     *  DOM scrape) — or empty on any failure. [count] caps how many we keep. */
    suspend fun fetch(featureId: String, count: Int = 50): List<Photo> {
        val cid = cidOf(featureId) ?: return emptyList()
        return mutex.withLock {
            val id = "p" + seq.incrementAndGet()
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
                        main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, MAX_LOAD_MS)
                        ready.await()
                        wv.evaluateJavascript(extractScript(id, count), null)
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
            }
            val urls = raw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            urls.map { Photo(upsize(it)) }
        }
    }

    /** The Google "cid" = the LOW half of the `0xHIGH:0xLOW` feature id as an unsigned decimal. */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, 16).toString() }.getOrNull()
    }

    /** Up-size a FIFE thumbnail URL for the sheet's photo strip. */
    private fun upsize(u: String): String =
        u.replace(Regex("=w\\d+-h\\d+[^=]*$"), "=w600-h450").replace(Regex("=s\\d+[^=]*$"), "=s600")

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = VelaConfig.USER_AGENT
        wv.addJavascriptInterface(Bridge(), "VelaBridge")
        webView = wv
        return wv
    }

    /** Self-polling DOM scraper: collect every `googleusercontent` photo URL (the rendered photo
     *  collage), click the "photos" affordance once to open the full gallery, scroll to surface
     *  more, and bridge a newline-joined list back once the count holds steady. Avatars and Street
     *  View are excluded; URLs are de-duped by image id (ignoring the size suffix). */
    private fun extractScript(id: String, cap: Int): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, CAP=$cap, last=-1, stable=0, tries=0, opened=false, best=[];
              function ok(u){
                if(!u || u.indexOf('googleusercontent')<0) return false;
                if(/streetviewpixels/.test(u)) return false;
                if(/\/a[\/-]|ACg8oc|ALV-/.test(u)) return false;          // reviewer avatars
                return true;
              }
              function collect(){
                var map={};
                function add(u){ if(ok(u)){ var k=u.replace(/=[wshpc].*$/,''); if(!map[k]) map[k]=u; } }
                [].slice.call(document.querySelectorAll('img')).forEach(function(im){ add(im.currentSrc||im.src); });
                // Photo tiles are role=img / buttons / inline-styled bg divs — scan only those
                // (NOT every div+span, which would mean getComputedStyle on thousands of nodes).
                [].slice.call(document.querySelectorAll('[role="img"],button,a,[style*="background"]')).forEach(function(el){
                  var bg=el.style.backgroundImage||''; if(!bg){ try{ bg=getComputedStyle(el).backgroundImage||''; }catch(e){} }
                  var m=bg.match(/url\(["']?([^"')]+)/); if(m) add(m[1]);
                });
                var out=[]; for(var k in map) out.push(map[k]); return out;
              }
              function openGallery(){
                if(opened) return;
                var bs=[].slice.call(document.querySelectorAll('button,a'));
                for(var i=0;i<bs.length;i++){
                  var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].textContent||'')).toLowerCase();
                  if(/(^|\s)photos?(\s|${'$'})|see (all )?photos|all photos/.test(l) && !/street ?view|review|profile|video/.test(l)){
                    try{ bs[i].click(); opened=true; return; }catch(e){}
                  }
                }
              }
              function scrollAll(){
                try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){
                  if(d.scrollHeight>d.clientHeight+300 && d.clientHeight>200){ d.scrollTop=d.scrollHeight; }
                }); }catch(e){}
              }
              function tick(){
                tries++;
                openGallery();
                scrollAll();
                var u=collect();
                if(u.length>best.length) best=u;       // keep the largest set (opening the gallery can blink)
                if(u.length===last && u.length>0) stable++; else stable=0;
                last=u.length;
                if(best.length>=CAP || (stable>=3 && tries>=5 && best.length>0) || tries>24){
                  try{ VelaBridge.onResult(ID, best.slice(0,CAP).join("\n")); }catch(e){ try{ VelaBridge.onResult(ID,''); }catch(e2){} }
                  return;
                }
                setTimeout(tick, 500);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 24_000L
        const val SETTLE_MS = 1_200L
        const val MAX_LOAD_MS = 7_000L
    }
}
