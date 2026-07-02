package app.vela.web

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.vela.core.VelaConfig
import java.io.ByteArrayInputStream
import java.math.BigInteger

/**
 * **Google's live reviews panel, embedded** — a VISIBLE WebView showing the place's own
 * `?cid=` page CSS-carved down to just the reviews pane. What this buys over the background
 * scrape: Google renders at full interactive speed, **auto-pages as you scroll** (no polling
 * loop, no idle heuristics, no cap), and its **own "Search reviews" box searches ALL reviews
 * server-side** (the native search only filters the ~50 scraped ones).
 *
 * The three asks this implements:
 * - **Content blocking**: [shouldInterceptRequest] feeds telemetry/beacon/ad requests an empty
 *   response — `/gen_204` beacons, `play.google.com/log`, analytics/doubleclick/adservice hosts.
 *   (An iframe was verified impossible — `X-Frame-Options: SAMEORIGIN` — but a WebView isn't an
 *   iframe; we own the network layer.)
 * - **Theming**: injected CSS isolates the panel (hide every sibling on the walk from the
 *   reviews pane to <body>) and, in dark mode, applies an invert+hue-rotate filter with images
 *   re-inverted, over Vela's dark background.
 * - **Search box**: Google's panel ships its own — the carve keeps it.
 *
 * Navigation is locked down: after the initial `?cid=` load, ALL page navigations are blocked
 * (`shouldOverrideUrlLoading` → true), so a tap on an author profile / report link can't leave
 * the panel (the SPA's in-panel interactions are client-side and unaffected).
 */
@Composable
fun GoogleReviewsPanel(
    featureId: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onFailed: () -> Unit = {},
    onPhotos: (List<String>, Int) -> Unit = { _, _ -> },
) {
    val cid = cidOf(featureId) ?: return
    // rememberUpdatedState: the WebView is built once (factory), but onPhotos may recompose — read
    // the latest through this so a tapped photo always reaches the current handler.
    val photos = androidx.compose.runtime.rememberUpdatedState(onPhotos)
    // key(): a place switch / theme flip must tear the WHOLE AndroidView node down and rebuild
    // it — AndroidView's factory runs only when its node enters composition, so destroying the
    // WebView from a keyed effect while the node survived left a dead view and an eternal
    // spinner (no reload, no fallback). onRelease destroys the WebView AFTER Compose detaches
    // it (destroying while attached is illegal and can crash a later draw).
    androidx.compose.runtime.key(featureId, dark) {
        var ready by remember { mutableStateOf(false) }
        Box(modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
                factory = { ctx ->
                    buildPanelWebView(
                        ctx, cid, dark,
                        onReady = { ready = true },
                        onFail = onFailed,
                        onPhotos = { urls, i -> photos.value(urls, i) },
                    )
                },
                onRelease = { it.destroy() },
            )
            if (!ready) {
                CircularProgressIndicator(
                    Modifier.size(22.dp).align(Alignment.Center),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** cid = the LOW half of the `0xHIGH:0xLOW` feature id as unsigned decimal (same as the fetchers). */
private fun cidOf(featureId: String): String? {
    val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
    return runCatching { BigInteger(low, 16).toString() }.getOrNull()
}

// Hosts/paths that are telemetry, ads, or logging — not needed to render reviews. Fed an empty
// response so the panel loads less AND phones home less. The maps SPA itself needs google.com,
// gstatic.com (static assets) and googleusercontent.com (photos) — those pass.
private val BLOCKED_HOSTS = listOf(
    "doubleclick.net", "googleadservices.com", "googlesyndication.com",
    "google-analytics.com", "googletagmanager.com", "adservice.google",
    "csi.gstatic.com",
)
private val BLOCKED_PATHS = listOf("/gen_204", "/log204", "/log?", "/domainreliability/")

private fun blocked(req: WebResourceRequest): Boolean {
    val url = req.url ?: return false
    val host = url.host.orEmpty()
    val path = url.path.orEmpty() + "?" + url.query.orEmpty()
    if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") || host.contains(it) }) return true
    if (BLOCKED_PATHS.any { path.contains(it) }) return true
    // play.google.com/log — Chrome/Maps client telemetry uploads.
    if (host == "play.google.com" && path.startsWith("/log")) return true
    return false
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun buildPanelWebView(
    ctx: android.content.Context,
    cid: String,
    dark: Boolean,
    onReady: () -> Unit,
    onFail: () -> Unit,
    onPhotos: (List<String>, Int) -> Unit,
): WebView {
    val wv = WebView(ctx)
    wv.settings.javaScriptEnabled = true
    wv.settings.domStorageEnabled = true
    // Desktop UA: the desktop place panel is ~408 px wide — phone-width, and it's the layout the
    // scrapers are calibrated against. (A mobile UA deep-links to intent:// — non-starter.)
    wv.settings.userAgentString = VelaConfig.USER_AGENT
    // Match Vela's SheetPalette exactly (Dark #1F1F1F / Light #FFFFFF) so the WebView surface
    // behind the page is the sheet colour before the page even paints.
    wv.setBackgroundColor(if (dark) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt())
    // The panel lives inside the sheet's scrollable column — own the vertical gesture while the
    // finger is on the panel so the list scrolls instead of the sheet.
    wv.setOnTouchListener { v, ev ->
        // Re-assert on EVERY event: the Compose sheet's drag handling resets/ignores a
        // once-per-gesture disallow, and steals the stream after the down (the swipe then
        // moves nothing — touchstart reached the page but the moves never did).
        if (ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }
    var loaded = false
    val bridge = object {
        @JavascriptInterface
        fun ready() { wv.post { onReady() } }

        @JavascriptInterface
        fun fail() { wv.post { onFail() } }

        // A tapped review photo — its review's upsized photo URLs + the tapped index. Google's own
        // photo route renders nothing inside the carve, so JS blocks it and hands the URLs here to
        // open Vela's native full-screen gallery. JavaBridge thread → post to main.
        @JavascriptInterface
        fun onReviewPhotos(json: String, index: Int) {
            val urls = runCatching {
                val a = org.json.JSONArray(json); (0 until a.length()).map { a.getString(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
            wv.post { onPhotos(urls, index.coerceIn(0, urls.size - 1)) }
        }
    }
    wv.addJavascriptInterface(bridge, "VelaPanel")
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null && blocked(request)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // The initial load (and Google-internal redirects during it) may proceed; once the
            // page is up, NO navigation leaves the panel.
            if (loaded) return true
            val u = request?.url ?: return false
            val scheme = u.scheme
            if (scheme != "https" && scheme != "http") return true
            val host = u.host.orEmpty()
            return !(host == "google.com" || host.endsWith(".google.com"))
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            loaded = true
            view?.evaluateJavascript(carveScript(dark), null)
        }
    }
    wv.loadUrl("https://www.google.com/maps?cid=$cid&hl=en&gl=us")
    return wv
}

/**
 * The CSS surgery — every line of this recipe was proven live over Chrome DevTools protocol
 * against the real page (2026-07-01), so don't "simplify" it without re-testing:
 * - Click the Reviews tab retry-until-`aria-selected` (a click on a not-yet-hydrated tab
 *   silently no-ops — same as the scraper).
 * - Size the panel and EVERY ancestor in **pixels** — `vh` units resolve to **0** in this
 *   embedded WebView (100vh !important computed to 0px while window.innerHeight said 560).
 * - Un-clip the ancestor chain: `overflow:visible` + `transform:none` + px heights all the way
 *   up. Without it the panel had geometry but painted NOTHING — a 0-height overflow-clipping
 *   (and transformed, hence containing-block-forming) wrapper clipped every descendant.
 * - Hide siblings on the walk up (kills map canvas/omnibox/footer), EXCEPT dialogs — a tapped
 *   review photo opens a lightbox that is a sibling of the panel.
 * - Strip Google's chrome we don't want: the Overview/Menu/Reviews/About tab bar, the
 *   "Order online" promo block, and the "Write a review" button (blocked — leads to sign-in).
 * - Theme to match Vela's sheet EXACTLY (no seam): <body> carries the Vela colour; main AND every
 *   ancestor are made transparent so that colour is the backdrop; dark inverts only main's content
 *   (a filter on <html> would become the fixed panel's containing block and re-break the sizing).
 *   The ancestors matter — they hold Google's white bg OUTSIDE the (main-scoped) filter, so left
 *   opaque they bleed white through the transparent main.
 * Maintenance passes keep re-applying for a while (the SPA re-attaches chrome on interaction).
 */
private fun carveScript(dark: Boolean): String {
    // Vela's own sheet colour (SheetPalette Dark/Light) — the panel matches it EXACTLY so there's
    // no seam with the surrounding place sheet.
    val bg = if (dark) "#1f1f1f" else "#ffffff"
    // Dark = a scoped invert on the PANEL CONTENT ONLY (main), NOT its background. The Vela colour
    // lives on <body> (which the filter doesn't touch — it's on main), and main + every ancestor
    // are made transparent so that colour is the panel's backdrop; only Google's content inverts.
    val darkCss = if (dark) """
        [role="main"]{filter:invert(0.92) hue-rotate(180deg) !important}
        [role="main"] img,[role="main"] video,[role="main"] canvas{filter:invert(1) hue-rotate(180deg) !important}
        [role="main"] [style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important}
        /* Star glyphs invert to a muddy dark — re-invert them (a double-invert restores the amber).
           Scoped to the [role=img] star WIDGET so no text comes back dark with it. */
        [role="main"] [role="img"][aria-label*="star" i]{filter:invert(1) hue-rotate(180deg) !important}
        /* Overlays (Sort menu, per-review menus, photo viewer) live OUTSIDE main so the filter never
           reaches them — they'd flash Google's white. Invert them to match; un-invert their images
           (a review photo in the viewer must stay true-colour). */
        [role="menu"],[role="listbox"],[role="dialog"]{filter:invert(0.94) hue-rotate(180deg) !important}
        [role="menu"] img,[role="dialog"] img,[role="dialog"] video,[role="dialog"] [style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important}
    """ else ""
    return """
        (function(){
          var tries=0, readySent=false, maint=0, revAt=-1;
          function isolate(){
            var main=document.querySelector('[role="main"]');
            if(!main) return false;
            var h=window.innerHeight, w=window.innerWidth;
            if(!h || !w) return false;
            // Pixels, not vh — vh is 0 in this embedded WebView (verified via devtools).
            main.style.setProperty('position','fixed','important');
            main.style.setProperty('top','0','important');
            main.style.setProperty('left','0','important');
            main.style.setProperty('height',h+'px','important');
            main.style.setProperty('max-height',h+'px','important');
            main.style.setProperty('min-height',h+'px','important');
            main.style.setProperty('width',w+'px','important');
            main.style.setProperty('max-width',w+'px','important');
            main.style.setProperty('overflow-y','auto','important');
            main.style.setProperty('z-index','999999','important');
            // Transparent so <body>'s Vela colour is the panel backdrop (Google's white bg would
            // otherwise show — inverted to near-black in dark, mismatching the sheet).
            main.style.setProperty('background','transparent','important');
            var el=main;
            while(el && el!==document.documentElement){
              var p=el.parentElement; if(!p) break;
              for(var i=0;i<p.children.length;i++){
                var c=p.children[i];
                if(c===el || c.tagName==='STYLE' || c.tagName==='SCRIPT') continue;
                // Google's menus/dialogs/photo-viewer render into portal containers that are EMPTY
                // at carve time and populated on tap. Hiding an empty one leaves the later overlay
                // display:none'd (the "Sort does nothing / photos won't open" bug). If it now holds
                // a live overlay, UN-hide it; otherwise hide it, tagged so revealOverlays can find it.
                if(c.getAttribute('role')==='dialog' || c.querySelector('[role="dialog"],[role="menu"],[role="listbox"]')){
                  c.style.removeProperty('display'); c.removeAttribute('data-vh'); continue;
                }
                c.style.setProperty('display','none','important'); c.setAttribute('data-vh','1');
              }
              // Un-clip + un-transform the whole chain: a 0-height overflow-clipping ancestor
              // clips every descendant no matter its size, and a transformed one becomes the
              // fixed panel's containing block (collapsing it back to 0).
              p.style.setProperty('height',h+'px','important');
              p.style.setProperty('min-height',h+'px','important');
              p.style.setProperty('max-height','none','important');
              p.style.setProperty('overflow','visible','important');
              p.style.setProperty('transform','none','important');
              // AND transparent — main's ancestors carry Google's white background OUTSIDE the
              // invert filter (which is on main), so they'd bleed white through the transparent
              // main. Clearing them lets <body>'s Vela colour show as the seamless backdrop.
              p.style.setProperty('background','transparent','important');
              p.style.setProperty('background-color','transparent','important');
              el=p;
            }
            strip();
            if(!document.getElementById('vela-carve')){
              var st=document.createElement('style'); st.id='vela-carve';
              st.textContent='html,body{overflow-x:hidden !important;background:$bg !important;}'
                + `$darkCss`;
              document.head.appendChild(st);
            }
            return true;
          }
          // Remove Google's own chrome we don't want: the Overview/Menu/Reviews/About tab bar
          // (we force Reviews), the "Get pickup or delivery / Order online" promo block, and the
          // "Write a review" button (blocked — it leads to Google sign-in). Runs every tick since
          // the SPA re-attaches these. Text/role-based, not class-based (Google's classes rotate).
          function stripBlockOf(el){
            // Walk the CTA element up to the [role="main"] direct child that wraps it, and hide
            // that block — BUT never the reviews scroller (guarded below).
            var m=document.querySelector('[role="main"]'); if(!m) return;
            var c=el; while(c && c.parentElement && c.parentElement!==m) c=c.parentElement;
            if(c && c.parentElement===m &&
               !c.querySelector('.jJc9Ad,[data-review-id]') && c.scrollHeight<=c.clientHeight+40){
              c.style.setProperty('display','none','important');
            }
          }
          function strip(){
            var tl=document.querySelector('[role="tablist"]');
            if(tl) tl.style.setProperty('display','none','important');
            // Match the CTA ELEMENTS by their OWN (short) text — NOT a container's textContent.
            // The old container-text match hid any main child containing "order online", and the
            // reviews scroller IS a main child, so a single review saying "order online" nuked the
            // whole list on scroll (the disappearing-panel bug). An <a>/<button> is never a review.
            [].slice.call(document.querySelectorAll('a,button')).forEach(function(b){
              var t=((b.getAttribute('aria-label')||b.textContent)||'').trim();
              if(t.length<=24 && /order online|get pickup/i.test(t)) stripBlockOf(b);
              if(/write a review/i.test(t)) b.style.setProperty('display','none','important');
              // Per-review Like / Share / ⋮-actions: not useful embedded (Like/Share need sign-in,
              // ⋮ is report/etc) and they clutter each card. Hide the button itself (element-precise).
              if(/^like$/i.test(t) || /^share\b/i.test(t) || /^actions for /i.test(t)) b.style.setProperty('display','none','important');
            });
          }
          // Google's popups (Sort menu, per-review menus, the photo viewer) render into portals my
          // carve hid, and OUTSIDE main so the dark filter never reaches them (they'd flash white).
          // For every LIVE overlay: un-hide the [data-vh] ancestors I hid, lift it above the panel,
          // clamp a menu that overflows the viewport, and (dark) invert it — un-inverting its images
          // so review photos in the viewer stay true-colour.
          function revealOverlays(){
            var live=[].slice.call(document.querySelectorAll('[role="menu"],[role="listbox"]'));
            [].slice.call(document.querySelectorAll('[role="dialog"]')).forEach(function(d){
              if(d.getAttribute('aria-hidden')!=='true' && d.getBoundingClientRect().width>0) live.push(d);
            });
            live.forEach(function(o){
              var el=o;
              while(el && el!==document.documentElement){ if(el.getAttribute('data-vh')==='1'){ el.style.removeProperty('display'); el.removeAttribute('data-vh'); } el=el.parentElement; }
              o.style.setProperty('z-index','2147483646','important');
              var r=o.getBoundingClientRect();
              if(r.width>0 && r.right>window.innerWidth){ o.style.setProperty('left',Math.max(4,window.innerWidth-r.width-8)+'px','important'); o.style.setProperty('right','auto','important'); }
            });
          }
          function setupOnce(){
            if(window.__velaOnce) return; window.__velaOnce=1;
            // Submitting the reviews search should drop the soft keyboard. The IME "Search"/"Go"
            // key fires either a keydown Enter OR a 'search' event (on type=search inputs) — catch
            // both and blur the input, which dismisses the keyboard.
            function dropKb(e){
              var t=e.target;
              if(t && t.tagName==='INPUT' && (e.type==='search' || e.key==='Enter')){ setTimeout(function(){ try{ t.blur(); }catch(x){} },0); }
            }
            document.addEventListener('keydown', dropKb, true);
            document.addEventListener('search', dropKb, true);
            // Tapping a review photo: Google's own viewer route-changes but renders nothing inside
            // the carve. Intercept in CAPTURE (before Google's jsaction bubble handler), block it,
            // collect the tapped review's photos (upsized to =s1600), and hand them to Vela's native
            // gallery. Non-photo taps fall through to the overlay reveal below.
            document.addEventListener('click', function(e){
              var btn = e.target && e.target.closest ? e.target.closest('button,[role="button"]') : null;
              if(btn && /^photo \d+ on /i.test(btn.getAttribute('aria-label')||'')){
                e.preventDefault(); e.stopImmediatePropagation();
                var card = btn.closest('.jJc9Ad') || btn.closest('[data-review-id]') || btn.parentElement;
                var urls=[], tap=0;
                [].slice.call(card.querySelectorAll('button,[role="button"]')).forEach(function(b){
                  if(!/^photo \d+ on /i.test(b.getAttribute('aria-label')||'')) return;
                  var bg=''; try{ bg=getComputedStyle(b).backgroundImage||''; }catch(x){}
                  var m=bg.match(/url\(["']?([^"')]+)/);
                  if(m && /googleusercontent/.test(m[1])){ if(b===btn) tap=urls.length; urls.push(m[1].replace(/=[^=\/]*${'$'}/,'=s1600')); }
                });
                if(urls.length){ try{ VelaPanel.onReviewPhotos(JSON.stringify(urls), tap); }catch(x){} }
                return;
              }
              requestAnimationFrame(revealOverlays); setTimeout(revealOverlays,150); setTimeout(revealOverlays,400);
            }, true);
            document.addEventListener('pointerup', function(){
              requestAnimationFrame(revealOverlays); setTimeout(revealOverlays,150);
            }, true);
          }
          // Stretch the reviews scroll region (Google leaves it ~372px) to fill the panel —
          // otherwise swipes below its bottom edge land in dead space. ONLY runs once the
          // Reviews tab is selected: an early pass once pinned the OVERVIEW's container to
          // full height, and after the tab switch it sat as an empty full-panel block starving
          // the real list (the "black panel" regression). Un-stretches a stale target when the
          // SPA swaps nodes.
          function stretch(){
            var main=document.querySelector('[role="main"]');
            if(!main) return;
            var h=window.innerHeight;
            var sc=null, best=0;
            [].slice.call(main.querySelectorAll('div')).forEach(function(d){
              if(d.scrollHeight>d.clientHeight+50 && d.clientHeight>100 && d.scrollHeight>best){ best=d.scrollHeight; sc=d; }
            });
            if(window.__velaSc && window.__velaSc!==sc){
              window.__velaSc.style.removeProperty('height');
              window.__velaSc.style.removeProperty('max-height');
              window.__velaSc=null;
            }
            if(sc){
              var top=Math.max(0, Math.round(sc.getBoundingClientRect().top));
              if(h-top>=150){
                sc.style.setProperty('height',(h-top)+'px','important');
                sc.style.setProperty('max-height',(h-top)+'px','important');
                window.__velaSc=sc;
              }
            }
          }
          function reviewsOpen(){
            var ts=[].slice.call(document.querySelectorAll('[role="tab"]'));
            for(var i=0;i<ts.length;i++){
              var tl=((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim();
              if(/^reviews\b/i.test(tl)){
                if((ts[i].getAttribute('aria-selected')||'')==='true') return true;
                try{ ts[i].click(); }catch(e){}
                return false;
              }
            }
            return false;
          }
          function tick(){
            tries++;
            var iso=isolate();
            if(readySent){
              // Maintenance: keep the carve + scroller sizing fresh for a while (the SPA
              // re-attaches chrome and swaps nodes on interaction), then stop. NO tab
              // re-click here — the user is free to browse Google's Menu/About tabs.
              stretch(); revealOverlays();
              if(maint++<60){ setTimeout(tick,1000); }
              return;
            }
            var rev=reviewsOpen();
            if(rev && revAt<0) revAt=tries;
            // Prefer readying once review CARDS have painted — then the panel never flashes the
            // overview (Order-online button) during the tab transition. BUT don't HANG on it: a
            // place with a rating and zero written reviews never renders a card, and the card class
            // can rotate — so after a short grace once the Reviews tab has settled, ready anyway
            // (shows Google's own "no reviews" state) rather than spinning to the fail() timeout.
            var haveCards = !!document.querySelector('.jJc9Ad,[data-review-id]');
            if(iso && rev && (haveCards || (revAt>=0 && tries-revAt>=8))){ readySent=true; setupOnce(); stretch(); try{ VelaPanel.ready(); }catch(e){} }
            if(!readySent && tries>60){ try{ VelaPanel.fail(); }catch(e){} return; }
            setTimeout(tick, readySent?1000:250);
          }
          tick();
        })();
    """.trimIndent()
}
