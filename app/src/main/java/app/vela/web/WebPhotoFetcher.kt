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
    private val partials = ConcurrentHashMap<String, (String) -> Unit>()
    private val seq = AtomicInteger()
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile private var warmed = false

    // featureId → its scraped gallery. Re-tapping a place (or bouncing back from directions) then
    // shows photos INSTANTLY instead of re-running the ~20 s scrape. Access-order LRU, small cap.
    private val cache = object : LinkedHashMap<String, List<Photo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Photo>>) = size > 32
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }

        // Streaming: the scraper reports the accumulated set whenever it GROWS, so the gallery
        // fills in as tabs are visited instead of arriving all at once at the end. JavaBridge
        // thread — the callback must be thread-safe.
        @JavascriptInterface
        fun onPartial(id: String, payload: String) {
            partials[id]?.invoke(payload)
        }
    }

    /** Prime the hidden WebView BEFORE the first place is opened (call on first search): creates
     *  the WebView and loads maps.google.com once, so the first real photo fetch reuses a live
     *  renderer, warm HTTP/2 connections, cookies, and cached JS — instead of paying the whole
     *  cold start on top of the place page load. No-op after anything has used the WebView. */
    fun warm() {
        if (warmed || webView != null) return
        warmed = true
        main.post {
            runCatching {
                // Re-check on the MAIN thread: if a fetch's Dispatchers.Main block created + started
                // using the WebView after this warm() was posted (from a bg thread), it already owns it —
                // don't replace its webViewClient or navigate away from its in-flight ?cid page (audit
                // 2026-07-06). All WebView creation/mutation is on the main thread, so this check is race-free.
                if (webView != null) return@runCatching
                val wv = ensureWebView()
                wv.webViewClient = WebViewClient()
                wv.loadUrl("https://www.google.com/maps?hl=en")
            }
        }
    }

    /** The gallery for [featureId] (`0x..:0x..`) — each [Photo] is its URL plus the gallery-tab
     *  [Photo.category] when Google tagged it (Menu / Food & drink / Vibe / By owner; null = All).
     *  No posted date from a DOM scrape. Empty on any failure. [count] caps how many we keep. */
    suspend fun fetch(featureId: String, count: Int = 80, onPartial: ((List<Photo>) -> Unit)? = null): List<Photo> {
        val cid = cidOf(featureId) ?: return emptyList()
        synchronized(cache) { cache[featureId] }?.let { return it } // instant on revisit — skip the scrape
        return mutex.withLock {
            val id = "p" + seq.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            if (onPartial != null) partials[id] = { raw -> onPartial(parseLines(raw)) }
            val raw = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        val wv = ensureWebView()
                        val ready = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Block anything that isn't a google.com page — the overview has a bare
                                // "Menu" ACTION LINK to the restaurant's own site; following it would kill
                                // the scrape (and quietly load a third-party site in the hidden WebView).
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
                        // Blank the PREVIOUS place's DOM before navigating: on a slow load the MAX_LOAD
                        // fallback can inject the scraper before the new page commits — against an empty
                        // DOM that yields an empty result (safe) instead of the previous place's photos
                        // being returned for THIS featureId (cross-place data).
                        wv.evaluateJavascript("try{document.documentElement.innerHTML=''}catch(e){}", null)
                        wv.loadUrl("https://www.google.com/maps?cid=$cid&hl=en&gl=us")
                        main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, MAX_LOAD_MS)
                        ready.await()
                        wv.evaluateJavascript(extractScript(id, count), null)
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
                partials.remove(id)
            }
            val out = raw?.let { parseLines(it) } ?: emptyList()
            if (out.isNotEmpty()) synchronized(cache) { cache[featureId] = out } // cache only real results
            out
        }
    }

    /** Each line is "category\turl" (category "" = uncategorized/All) — shared by the final result
     *  and the streamed partials. */
    private fun parseLines(raw: String): List<Photo> = raw.split("\n").mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val tab = line.indexOf('\t')
        if (tab < 0) Photo(upsize(line.trim()))
        else Photo(upsize(line.substring(tab + 1).trim()), category = line.substring(0, tab).trim().ifBlank { null })
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
        // Real offscreen viewport — the category grids are VIRTUALIZED (like the reviews list); at 0×0 a
        // category tab renders only ~1 tile, so a tall viewport is what makes each category populate fully.
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WV_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(WV_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WV_WIDTH, WV_HEIGHT)
        webView = wv
        return wv
    }

    /** Self-polling DOM scraper: open the gallery, then VISIT EACH CATEGORY TAB (Menu / Food & drink /
     *  Vibe / By owner) in turn — clicking it, scrolling, and tagging the photos it shows with that
     *  category — then sweep the "All" view for the rest (uncategorized). Bridges "category\turl" lines
     *  back, de-duped by image id (first category a photo appears under wins). Avatars + Street View
     *  excluded. Google keeps these tabs in the DOM (verified on-device), so this is keyless. */
    private fun extractScript(id: String, cap: Int): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, CAP=$cap, acc={}, tries=0, phase=0, cats=[], ci=0, sub=0, opened=false;
              // The gallery tabs worth tagging (skip All/Latest/Videos/Street View — All is the fallback sweep).
              var CATRE=/^(menu|food|drink|vibe|by owner)/i;
              function ok(u){ return !!u && u.indexOf('googleusercontent')>=0 && !/streetviewpixels/.test(u) && !/\/a[\/-]|ACg8oc|ALV-/.test(u); }
              function idOf(u){ return u.replace(/=[wshpc].*$/,''); }
              function urlOf(el){ var u=el.currentSrc||el.src||''; if(!u || u.indexOf('googleusercontent')<0){ var bg=el.style.backgroundImage||''; if(!bg){ try{ bg=getComputedStyle(el).backgroundImage||''; }catch(e){} } var m=bg.match(/url\(["']?([^"')]+)/); if(m) u=m[1]; } return u; }
              function collect(cat){ [].slice.call(document.querySelectorAll('img,[role="img"],button,a,[style*="background"]')).forEach(function(el){ var u=urlOf(el); if(ok(u)){ var k=idOf(u); if(!acc[k]) acc[k]={c:cat,u:u}; } }); }
              // Tabs come from role="tab" ONLY: the place overview also has a bare "Menu" ACTION LINK (an
              // <a> to the restaurant's own site) — clicking that would navigate the WebView off Maps and
              // kill the scrape. (The Kotlin side also blocks off-google navigations as a belt-and-braces.)
              function tabEls(){ return [].slice.call(document.querySelectorAll('[role="tab"]')); }
              function clickTab(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ if((((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim())===name){ try{ ts[i].click(); }catch(e){} return; } } }
              function tabSelected(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ var t=(((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim()); if(t===name) return ts[i].getAttribute('aria-selected')==='true'; } return false; }
              // One-shot: after the gallery opens, its own tiles carry "Photo 2 of 45"-style labels that
              // match /photos?/ — re-firing this would click INTO a photo lightbox and break the tab walk.
              function clickPhotos(){ if(opened) return; var bs=[].slice.call(document.querySelectorAll('button,a')); for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].textContent||'')).toLowerCase(); if((/(^|\s)photos?(\s|${'$'})|see (all )?photos|all photos/.test(l)) && !/street ?view|review|profile|video/.test(l)){ try{ bs[i].click(); }catch(e){} opened=true; return; } } }
              function scroll(){ try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){ if(d.scrollHeight>d.clientHeight+300 && d.clientHeight>200) d.scrollTop=d.scrollHeight; }); }catch(e){} }
              // A real category tab is a clean name ("Menu", "Food & drink", "By owner") — EXCLUDE photo
              // captions that also start with a category word ("Menu · Photo 1 of 12") via the letters-only test.
              function tabsNow(){ var out=[]; tabEls().forEach(function(e){ var t=((e.getAttribute('aria-label')||e.textContent)||'').trim(); if(t && t.length<20 && CATRE.test(t) && /^[a-z &]+${'$'}/i.test(t) && out.indexOf(t)<0) out.push(t); }); return out; }
              function lines(){ var out=[]; for(var k in acc) out.push((acc[k].c||'')+'\t'+acc[k].u); return out.slice(0,CAP).join("\n"); }
              function finish(){ try{ VelaBridge.onResult(ID, lines()); }catch(e){ try{ VelaBridge.onResult(ID,''); }catch(e2){} } }
              var sentN=0;
              // Stream growth: the sheet fills in as photos are found instead of waiting ~20s for
              // the full tab walk (first partial = the OVERVIEW's hero photos, ~1 tick after load).
              function partial(){ var n=0; for(var k in acc) n++; if(n>sentN){ sentN=n; try{ VelaBridge.onPartial(ID, lines()); }catch(e){} } }
              function tick(){
                tries++;
                if(phase===0){
                  collect(''); clickPhotos(); scroll();
                  // Wait until the gallery's category tabs actually exist (slow loads) — but not forever:
                  // a place with no categorized gallery proceeds tab-less to the plain All sweep.
                  cats=tabsNow();
                  if(cats.length>0 || tries>=8){ ci=0; sub=0; phase=1; }
                }
                else if(phase===1){
                  if(ci>=cats.length){ phase=2; sub=0; }
                  // Per tab: click it, then scroll + COLLECT each tick — but only once the tab is actually
                  // SELECTED (aria-selected), else a slow grid swap would tag the previous tab's photos
                  // with this category. Accumulate across ticks (the grid virtualizes).
                  else { if(sub===0) clickTab(cats[ci]); scroll(); if(sub>=2 && tabSelected(cats[ci])) collect(cats[ci]); sub++; if(sub>=6){ ci++; sub=0; } }
                }
                else {
                  // The All sweep: click, give the grid one no-collect settle tick, then sweep uncategorized.
                  if(sub===0) clickTab('All');
                  scroll(); if(sub>=1) collect('');
                  sub++; if(sub>=5){ finish(); return; }
                }
                if(tries>58){ collect(''); finish(); return; }
                partial();
                setTimeout(tick, 500);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        // Must outlast the script's own hard stop (58 ticks × 500 ms = 29 s + page load ≤ 8 s) — if the
        // Kotlin timeout fires first we return NULL and throw away everything the walk accumulated,
        // instead of the partial set the script's salvage path would deliver.
        const val TOTAL_TIMEOUT_MS = 40_000L
        const val SETTLE_MS = 1_200L
        const val MAX_LOAD_MS = 7_000L
        // Offscreen viewport so the virtualized category grids render a full batch (not ~1 tile).
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 3200
    }
}
