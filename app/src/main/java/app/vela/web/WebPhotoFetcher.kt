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
    suspend fun fetch(featureId: String, count: Int = 80): List<Photo> {
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
            // Each line is "category\turl" (category "" = uncategorized/All). Category-scraping tags each
            // photo with its gallery tab (Menu / Food & drink / Vibe / By owner / …).
            val out = raw?.split("\n")?.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val tab = line.indexOf('\t')
                if (tab < 0) Photo(upsize(line.trim()))
                else Photo(upsize(line.substring(tab + 1).trim()), category = line.substring(0, tab).trim().ifBlank { null })
            } ?: emptyList()
            out
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
              var ID=$idj, CAP=$cap, acc={}, tries=0, phase=0, cats=[], ci=0, sub=0;
              // The gallery tabs worth tagging (skip All/Latest/Videos/Street View — All is the fallback sweep).
              var CATRE=/^(menu|food|drink|vibe|by owner)/i;
              function ok(u){ return !!u && u.indexOf('googleusercontent')>=0 && !/streetviewpixels/.test(u) && !/\/a[\/-]|ACg8oc|ALV-/.test(u); }
              function idOf(u){ return u.replace(/=[wshpc].*$/,''); }
              function urlOf(el){ var u=el.currentSrc||el.src||''; if(!u || u.indexOf('googleusercontent')<0){ var bg=el.style.backgroundImage||''; if(!bg){ try{ bg=getComputedStyle(el).backgroundImage||''; }catch(e){} } var m=bg.match(/url\(["']?([^"')]+)/); if(m) u=m[1]; } return u; }
              function collect(cat){ [].slice.call(document.querySelectorAll('img,[role="img"],button,a,[style*="background"]')).forEach(function(el){ var u=urlOf(el); if(ok(u)){ var k=idOf(u); if(!acc[k]) acc[k]={c:cat,u:u}; } }); }
              function clickExact(name){ var bs=[].slice.call(document.querySelectorAll('[role="tab"],button,a')); for(var i=0;i<bs.length;i++){ if((((bs[i].getAttribute('aria-label')||bs[i].textContent)||'').trim())===name){ try{ bs[i].click(); }catch(e){} return; } } }
              function clickPhotos(){ var bs=[].slice.call(document.querySelectorAll('button,a')); for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].textContent||'')).toLowerCase(); if((/(^|\s)photos?(\s|${'$'})|see (all )?photos|all photos/.test(l)) && !/street ?view|review|profile|video/.test(l)){ try{ bs[i].click(); }catch(e){} return; } } }
              function scroll(){ try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){ if(d.scrollHeight>d.clientHeight+300 && d.clientHeight>200) d.scrollTop=d.scrollHeight; }); }catch(e){} }
              // A real category tab is a clean name ("Menu", "Food & drink", "By owner") — EXCLUDE photo
              // captions that also start with a category word ("Menu · Photo 1 of 12") via the ·/digit/photo test.
              function tabsNow(){ var out=[]; [].slice.call(document.querySelectorAll('[role="tab"],button,a')).forEach(function(e){ var t=((e.getAttribute('aria-label')||e.textContent)||'').trim(); if(t && t.length<20 && CATRE.test(t) && /^[a-z &]+${'$'}/i.test(t) && out.indexOf(t)<0) out.push(t); }); return out; }
              function finish(){ var lines=[]; for(var k in acc) lines.push((acc[k].c||'')+'\t'+acc[k].u); try{ VelaBridge.onResult(ID, lines.slice(0,CAP).join("\n")); }catch(e){ try{ VelaBridge.onResult(ID,''); }catch(e2){} } }
              function tick(){
                tries++;
                if(phase===0){ clickPhotos(); scroll(); if(tries>=3){ cats=tabsNow(); ci=0; sub=0; phase=1; } }
                else if(phase===1){
                  if(ci>=cats.length){ phase=2; sub=0; }
                  // Per tab: click it, then scroll + COLLECT each tick (accumulate the grid across scroll
                  // positions — it virtualizes, so one collect misses most), for ~5 ticks, then next tab.
                  else { if(sub===0) clickExact(cats[ci]); scroll(); if(sub>=2) collect(cats[ci]); sub++; if(sub>=6){ ci++; sub=0; } }
                }
                else { clickExact('All'); scroll(); collect(''); sub++; if(sub>=4){ finish(); return; } }
                if(tries>58){ collect(''); finish(); return; }
                setTimeout(tick, 500);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        // Longer than before: with a viewport + per-category scraping there are more tabs × ticks to walk.
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_200L
        const val MAX_LOAD_MS = 7_000L
        // Offscreen viewport so the virtualized category grids render a full batch (not ~1 tile).
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 3200
    }
}
