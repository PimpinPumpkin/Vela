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
import androidx.compose.foundation.layout.padding
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
/** A topic chip scraped off the panel ("potato dumplings" ×50; "All" has no count). */
data class PanelChip(val label: String, val count: Int? = null)

/** Drives the panel's hidden Google controls from Vela's native UI. Safe to call any time —
 *  no-ops until the panel's WebView exists. */
class ReviewsPanelController {
    internal var webView: WebView? = null
    private fun js(code: String) {
        val wv = webView ?: return
        wv.post { runCatching { wv.evaluateJavascript(code, null) } }
    }
    /** Server-side search across ALL reviews (empty = clear). */
    fun search(q: String) = js("try{window.velaSearch(" + org.json.JSONObject.quote(q) + ")}catch(e){}")
    /** Apply a topic chip by its label ("All" clears). */
    fun chip(label: String) = js("try{window.velaClickChip(" + org.json.JSONObject.quote(label) + ")}catch(e){}")
    /** Sort order: "Most relevant" / "Newest" / "Highest rating" / "Lowest rating". */
    fun sort(label: String) = js("try{window.velaSort(" + org.json.JSONObject.quote(label) + ")}catch(e){}")
}

@Composable
fun GoogleReviewsPanel(
    featureId: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onFailed: () -> Unit = {},
    onPhotos: (List<String>, List<String?>, Int) -> Unit = { _, _, _ -> },
    // Scroll-sync: a boundary drag (reviews at their top edge + finger dragging down, or bottom
    // edge + up) is forwarded here as raw pixel deltas so the caller can move the Vela sheet 1:1
    // with the finger; onOverscrollEnd fires at finger-up with the tracked fling velocity (px/s)
    // AND when an inertial inner fling lands on the top edge with leftover momentum.
    onOverscroll: (Float) -> Unit = {},
    onOverscrollEnd: (Float) -> Unit = {},
    // One-shot when the user starts really scrolling the reviews (re-armed at their top) — the
    // caller slides the sheet to full screen around the panel, Google-style.
    onEngaged: () -> Unit = {},
    // The page's rating distribution ([5★..1★] counts), for a native histogram in Vela's header.
    onHistogram: (List<Int>) -> Unit = {},
    // Fires once the panel has painted (the internal spinner clears) — lets the caller drop any
    // loading teaser it shows alongside.
    onLoaded: () -> Unit = {},
    // Topic chips scraped off the page ("All", "potato dumplings" ×50 …) — Vela renders them
    // natively and drives the hidden originals via [controller].
    onChips: (List<PanelChip>) -> Unit = {},
    controller: ReviewsPanelController? = null,
    // FULL-SCREEN mode (the "Read all reviews" view): the WebView owns the whole screen, so there's
    // NO nested scroll — the scroll-sync touch listener is skipped, and the carve is lighter (keeps
    // Google's own search/sort/histogram + lets Google's native photo/VIDEO viewer handle taps, so
    // videos play). Inline (false) is unused now that the inline reviews are the native list; the
    // panel exists only as this full-screen view.
    fullScreen: Boolean = false,
) {
    val cid = cidOf(featureId) ?: return
    // rememberUpdatedState: the WebView is built once (factory), but onPhotos may recompose — read
    // the latest through this so a tapped photo always reaches the current handler.
    val photos = androidx.compose.runtime.rememberUpdatedState(onPhotos)
    val overscroll = androidx.compose.runtime.rememberUpdatedState(onOverscroll)
    val overscrollEnd = androidx.compose.runtime.rememberUpdatedState(onOverscrollEnd)
    val engaged = androidx.compose.runtime.rememberUpdatedState(onEngaged)
    val histogram = androidx.compose.runtime.rememberUpdatedState(onHistogram)
    val loadedCb = androidx.compose.runtime.rememberUpdatedState(onLoaded)
    val chips = androidx.compose.runtime.rememberUpdatedState(onChips)
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
                        onReady = { ready = true; loadedCb.value() },
                        onFail = onFailed,
                        onPhotos = { urls, caps, i -> photos.value(urls, caps, i) },
                        onOverscroll = { dy -> overscroll.value(dy) },
                        onOverscrollEnd = { v -> overscrollEnd.value(v) },
                        onEngaged = { engaged.value() },
                        fullScreen = fullScreen,
                        onHistogramParsed = { counts -> histogram.value(counts) },
                        onChipsParsed = { list -> chips.value(list) },
                    ).also { controller?.webView = it }
                },
                onRelease = { controller?.webView = null; it.destroy() },
            )
            if (!ready) {
                // Near the TOP of the panel, not centered — the panel is most of a screen tall,
                // so a centered spinner sits below the fold while you scroll toward it.
                CircularProgressIndicator(
                    Modifier.padding(top = 48.dp).size(22.dp).align(Alignment.TopCenter),
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
    onPhotos: (List<String>, List<String?>, Int) -> Unit,
    onOverscroll: (Float) -> Unit,
    onOverscrollEnd: (Float) -> Unit,
    onEngaged: () -> Unit,
    fullScreen: Boolean,
    onHistogramParsed: (List<Int>) -> Unit,
    onChipsParsed: (List<PanelChip>) -> Unit,
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
    // Scroll-sync: the panel lives inside the sheet's scrollable column and OWNS every vertical
    // gesture (disallow-intercept re-asserted on EVERY event — the Compose sheet resets a
    // once-per-gesture disallow and steals the stream otherwise). At a scroll BOUNDARY — reviews
    // at their top edge + finger dragging down, or bottom edge + up — the WebView can't consume
    // the drag, so the deltas are FORWARDED to the caller (onOverscroll), which moves the Vela
    // sheet 1:1 with the finger; finger-up sends the tracked velocity (onOverscrollEnd) so a
    // boundary fling glides the sheet. Edge state comes from the page (`onPanelEdge` bridge).
    //
    // v1 tried gesture HANDOFF instead — flipping requestDisallowInterceptTouchEvent(false) at
    // the boundary so the sheet would take the stream. DON'T go back to that: Compose's drag
    // detector has already abandoned a stream whose early events the WebView consumed, so a
    // mid-gesture flip does nothing (a slow synthetic swipe happened to work; real fingers and
    // flings didn't — the panel became a scroll trap). Manual delta-forwarding has no ownership
    // transfer at all, so it also survives mid-gesture direction reversals.
    val panelAtTop = java.util.concurrent.atomic.AtomicBoolean(true)
    val panelAtBottom = java.util.concurrent.atomic.AtomicBoolean(false)
    // All positions are WINDOW-space (view-local y + the view's window offset): the forwarded
    // scroll moves the WebView itself, so a view-local delta shrinks by exactly the amount the
    // sheet just moved — the sheet would track at HALF the finger speed, stepping every other
    // frame, and the fling velocity would halve too. Window space measures the finger.
    val winLoc = IntArray(2)
    var activeId = -1 // tracked pointer ID (ids survive index compaction; raw ev.y is index 0)
    var lastY = 0f
    var lastX = 0f
    var forwarded = false
    var forwardDist = 0f
    var tracker: android.view.VelocityTracker? = null
    // Feed the tracker window-space copies so its velocity measures the finger too.
    val feedTracker = { ev: MotionEvent ->
        val c = MotionEvent.obtain(ev)
        c.offsetLocation(winLoc[0].toFloat(), winLoc[1].toFloat())
        tracker?.addMovement(c)
        c.recycle()
    }
    wv.overScrollMode = android.view.View.OVER_SCROLL_NEVER // no glow while the sheet takes the drag
    // FULL-SCREEN: the WebView owns the whole screen, no scrolling parent — skip all the scroll-sync
    // machinery and let it scroll natively (this is what makes full-screen jitter-free by design).
    if (!fullScreen) wv.setOnTouchListener { v, ev ->
        if (ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        v.getLocationInWindow(winLoc)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = ev.getPointerId(0)
                lastY = ev.y + winLoc[1]; lastX = ev.x + winLoc[0]
                forwarded = false; forwardDist = 0f
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
                feedTracker(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The tracked finger lifted while another stays down: adopt a survivor and
                // RE-BASE (no delta) — otherwise the next MOVE reads the other finger and the
                // inter-finger distance appears as one huge dy that teleports/dismisses the sheet.
                if (ev.getPointerId(ev.actionIndex) == activeId) {
                    val idx = if (ev.actionIndex == 0) 1 else 0
                    activeId = ev.getPointerId(idx)
                    lastY = ev.getY(idx) + winLoc[1]; lastX = ev.getX(idx) + winLoc[0]
                    tracker?.clear()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                feedTracker(ev)
                val idx = ev.findPointerIndex(activeId)
                if (idx >= 0) {
                    val y = ev.getY(idx) + winLoc[1]
                    val x = ev.getX(idx) + winLoc[0]
                    val dy = y - lastY
                    val dx = x - lastX
                    lastY = y; lastX = x
                    // Forward only clearly-vertical boundary drags (a horizontal chip swipe with
                    // a slight slope must not jiggle the sheet).
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx) &&
                        ((panelAtTop.get() && dy > 0f) || (panelAtBottom.get() && dy < 0f))
                    ) {
                        forwarded = true
                        forwardDist += kotlin.math.abs(dy)
                        onOverscroll(dy)
                    } else if (forwarded && kotlin.math.abs(dy) > 0.5f) {
                        // Left the boundary mid-gesture (direction flip / inner list took over):
                        // END the forwarding now — resets the caller's pull accumulators and
                        // disarms the end-fling, so a drag that merely TOUCHED the boundary can't
                        // fling the sheet when it ends as ordinary review scrolling.
                        forwarded = false; forwardDist = 0f
                        onOverscrollEnd(0f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (forwarded) {
                    var vel = 0f
                    // Fling only a real boundary drag (> a slop's worth of forwarded travel) —
                    // a tap with a 2px jiggle at the boundary must not launch the sheet.
                    if (ev.actionMasked == MotionEvent.ACTION_UP && forwardDist > 24f) {
                        feedTracker(ev)
                        tracker?.computeCurrentVelocity(1000)
                        vel = tracker?.getYVelocity(activeId) ?: 0f
                    }
                    onOverscrollEnd(vel)
                }
                tracker?.recycle(); tracker = null
                activeId = -1
            }
        }
        false
    }
    var loaded = false
    val bridge = object {
        @JavascriptInterface
        fun ready() { wv.post { onReady() } }

        // Scroll-sync edge state (JavaBridge thread → read on the UI thread by the touch listener).
        @JavascriptInterface
        fun onPanelEdge(atTop: Boolean, atBottom: Boolean) {
            panelAtTop.set(atTop)
            panelAtBottom.set(atBottom)
        }

        // An inertial inner fling landed on the top edge with leftover momentum (CSS px/s):
        // carry it into the sheet so the fling doesn't dead-stop at the boundary. Only when no
        // finger is down — a finger-driven boundary drag already forwards its own deltas.
        @JavascriptInterface
        fun onEdgeFling(cssVelocity: Double) {
            wv.post {
                if (tracker == null) {
                    @Suppress("DEPRECATION")
                    onOverscrollEnd((cssVelocity * wv.scale).toFloat())
                }
            }
        }

        // The user started really scrolling the reviews — Vela slides the sheet to full screen
        // around them (Google-style). One-shot per engagement; the page re-arms it at the top.
        @JavascriptInterface
        fun onPanelEngaged() {
            wv.post { onEngaged() }
        }

        // Topic chips ([{l,n?}…]) — rendered natively by Vela; Google's chips + search rows are
        // carved once this is sent (the native controls are the replacement).
        @JavascriptInterface
        fun onChips(json: String) {
            val list = runCatching {
                val a = org.json.JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    PanelChip(o.getString("l"), if (o.has("n")) o.getInt("n") else null)
                }
            }.getOrNull() ?: return
            wv.post { onChipsParsed(list) }
        }

        // The page's rating distribution ([5★,4★,3★,2★,1★] counts) — rendered natively by Vela;
        // Google's in-panel summary block is hidden once this is sent.
        @JavascriptInterface
        fun onHistogram(json: String) {
            val counts = runCatching {
                val a = org.json.JSONArray(json); (0 until a.length()).map { a.getInt(it) }
            }.getOrNull()?.takeIf { it.size == 5 } ?: return
            wv.post { onHistogramParsed(counts) }
        }

        @JavascriptInterface
        fun fail() { wv.post { onFail() } }

        // A tapped review photo — a JSON blob {urls, index, author, date} for the tapped review.
        // Google's own photo route renders nothing inside the carve, so JS blocks it and hands the
        // data here to open Vela's native full-screen gallery, captioned "Author · date" (every
        // photo in one review shares that caption). JavaBridge thread → post to main.
        @JavascriptInterface
        fun onReviewPhotos(json: String) {
            val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
            val arr = o.optJSONArray("urls") ?: return
            val urls = (0 until arr.length()).map { arr.getString(it) }
            if (urls.isEmpty()) return
            val author = o.optString("author").trim()
            val date = o.optString("date").trim()
            val caption = listOf(author, date).filter { it.isNotEmpty() }.joinToString(" · ").ifBlank { null }
            val captions = List<String?>(urls.size) { caption }
            val index = o.optInt("index", 0).coerceIn(0, urls.size - 1)
            wv.post { onPhotos(urls, captions, index) }
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
            view?.evaluateJavascript(carveScript(dark, fullScreen), null)
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
private fun carveScript(dark: Boolean, fullScreen: Boolean): String {
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
        [role="main"] [role="img"][aria-label*="star" i]{filter:invert(1) hue-rotate(180deg) saturate(1.7) brightness(1.12) !important}
        /* Overlays (Sort menu, per-review menus, photo viewer) live OUTSIDE main so the filter never
           reaches them — they'd flash Google's white. Invert them to match; un-invert their images
           (a review photo in the viewer must stay true-colour). */
        [role="menu"],[role="listbox"],[role="dialog"]{filter:invert(0.94) hue-rotate(180deg) !important}
        [role="menu"] img,[role="dialog"] img,[role="dialog"] video,[role="dialog"] [style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important}
    """ else ""
    val fullJs = if (fullScreen) "true" else "false"
    return """
        (function(){
          var tries=0, readySent=false, revAt=-1;
          // FULL = the full-screen "Read all" view: keep Google's OWN search/sort/histogram + its
          // native photo/VIDEO viewer (so videos play), and don't intercept photo taps. Inline
          // reviews are the native list now, so FULL is effectively always true in practice.
          var FULL=$fullJs;
          // --- review media helpers (used by the photo interceptor) ---
          // A review media button is keyed by its jsaction (review.openPhoto), NOT its aria-label:
          // Google labels SOME photos descriptively ("Mixed dumplings with rye bread…") instead of
          // "Photo N on …", so an aria-label match silently drops the described ones (the "first pic
          // won't open, shows 1/1" bug). Class .Tya61d is a belt-and-braces fallback.
          function velaMediaBtns(card){ return [].slice.call(card.querySelectorAll('[jsaction*="review.openPhoto"],.Tya61d')); }
          function velaMediaUrl(b){
            var m=(b.style&&b.style.backgroundImage||'').match(/url\(["']?([^"')]+)/);
            if(m && /googleusercontent|ggpht/.test(m[1])) return m[1];
            try{ var c=(getComputedStyle(b).backgroundImage||'').match(/url\(["']?([^"')]+)/); if(c && /googleusercontent|ggpht/.test(c[1])) return c[1]; }catch(x){}
            var im=b.querySelector('img'); if(im){ var s=im.currentSrc||im.src||''; if(/googleusercontent|ggpht/.test(s)) return s; }
            var dd=b.querySelectorAll('*'); for(var i=0;i<dd.length;i++){ var g=''; try{g=getComputedStyle(dd[i]).backgroundImage||'';}catch(y){} var mm=g.match(/url\(["']?([^"')]+)/); if(mm && /googleusercontent|ggpht/.test(mm[1])) return mm[1]; }
            return null;
          }
          // Upsize the size-suffix (…=w300-h200-…) to =s1600 for a full-res gallery image.
          function velaUpsize(u){ var eq=u.indexOf('=', u.lastIndexOf('/')); return eq<0 ? u : u.slice(0,eq)+'=s1600'; }
          function velaAuthor(card){
            var d=card.querySelector('.d4r55'); if(d && d.textContent.trim()) return d.textContent.trim();
            var a=card.querySelector('[aria-label^="Photo of "]'); if(a) return (a.getAttribute('aria-label')||'').replace(/^Photo of /,'').trim();
            return '';
          }
          function velaDate(card){
            var d=card.querySelector('.rsqaWe'); if(d && d.textContent.trim()) return d.textContent.trim();
            var all=card.querySelectorAll('span,div'); for(var i=0;i<all.length;i++){ var e=all[i]; if(e.children.length===0 && /^(a|an|\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago$/i.test((e.textContent||'').trim())) return e.textContent.trim(); }
            return '';
          }
          // Rating histogram → native. The panel page renders the distribution as tr[aria-label]
          // rows ("5 stars, 1,189 reviews"); parse the counts once, hand them to Vela to draw
          // natively next to its own rating header, then HIDE Google's whole summary block (big
          // 4.7 + stars + histogram — found by walking the table up to the scroller child, with
          // the same never-hide-cards guards as strip). Re-hides each tick (SPA re-attaches).
          function velaHistogram(){
            if(FULL) return; // full-screen keeps Google's own summary/histogram — nothing to carve
            // Do NOTHING until review cards exist. Google's reviews-tab init is fragile while it
            // boots: acting during it — even the histogram SEND, whose native render nudges the
            // WebView's layout mid-init — correlated with the page logging "error.2" and NEVER
            // issuing the review-feed request (panel stuck at zero reviews; reproduced on two
            // places, cleared by the pre-polish build). Cards rendered == init done == safe.
            if(!document.querySelector('.jJc9Ad,[data-review-id]')) return;
            var rows=[].slice.call(document.querySelectorAll('tr[aria-label]')).filter(function(r){
              return /^\s*\d\s+stars?,/i.test(r.getAttribute('aria-label')||'');
            });
            if(rows.length<5) return;
            if(!window.__velaHistSent){
              var counts={};
              rows.forEach(function(r){
                var m=(r.getAttribute('aria-label')||'').match(/^\s*(\d)\s+stars?,\s*([\d,]+)/i);
                if(m) counts[m[1]]=parseInt(m[2].replace(/,/g,''),10);
              });
              if(counts['5']!==undefined && counts['1']!==undefined){
                window.__velaHistSent=1;
                try{ VelaPanel.onHistogram(JSON.stringify([counts['5']||0,counts['4']||0,counts['3']||0,counts['2']||0,counts['1']||0])); }catch(x){}
              }
            }
            // Blocks to carve: the summary block (walk the table up) + the "Reviews are
            // automatically processed…" ⓘ info row (matched by its own text, smallest wrapper).
            var cap=window.innerHeight*0.5;
            var blocks=[];
            var tbl=rows[0].closest('table');
            if(tbl && window.__velaHistSent){
              blocks.push({el:velaBlockOf(tbl),isSum:1});
            }
            // Google's search/sort row + topic-chips row: carved only once the CHIPS data reached
            // Vela (the native search field + chip row are the replacement).
            if(window.__velaChipsSent){
              var crow=velaChipsRow(); if(crow) blocks.push({el:crow,isCtl:1});
              var inp=document.querySelector('[role="main"] input');
              if(inp) blocks.push({el:velaBlockOf(inp),isCtl:1});
            }
            [].slice.call(document.querySelectorAll('[role="main"] div')).some(function(d){
              if(d.offsetHeight<=0 || d.offsetHeight>=80) return false;
              if(!/reviews are automatically processed/i.test(d.textContent||'')) return false;
              if(d.parentElement && d.parentElement.offsetHeight<80) return false; // want the outermost small wrapper
              blocks.push({el:d,isSum:0}); return true;
            });
            // Two-phase carve. Phase 1 (immediately, even before cards): opacity — NO layout
            // change, so Google's mounting virtualized list is untouched, and the user never
            // sees a flash of Google's own histogram. Phase 2 (only well AFTER the feed proved
            // healthy — __velaFedOk + a few stable ticks): display:none to reclaim the blank
            // space. NEVER collapse earlier: a layout removal while the list mounts corrupts
            // its offset math and the SPA permanently unmounts every card (reproduced live
            // twice; un-hiding does not recover).
            var collapse = window.__velaFedOk && (window.__velaStable||0)>4 && (!window.__velaSc || window.__velaSc.scrollTop<=5);
            blocks.forEach(function(w){
              var b=w.el;
              if(!b || b.querySelector('.jJc9Ad,[data-review-id]')) return;
              if(b.offsetHeight>=cap) return;
              b.style.setProperty('opacity','0','important');
              b.style.setProperty('pointer-events','none','important');
              if(w.isSum) window.__velaSumFaded=1; // the summary + control rows each release their hold
              if(w.isCtl) window.__velaCtlFaded=1;
              if(collapse) b.style.setProperty('display','none','important');
            });
          }
          // Vela's app UI is the system Roboto; Google's page headings use "Google Sans" —
          // rewrite JUST those to Roboto for consistency (never touch icon fonts — "Google
          // Symbols"/"Material Icons" don't match the test). Each node checked ONCE (data-vf),
          // capped per pass; new cards get picked up by later passes.
          function velaFont(){
            var m=document.querySelector('[role="main"]'); if(!m) return;
            var els=m.querySelectorAll('*:not([data-vf])');
            var n=Math.min(els.length,400);
            for(var i=0;i<n;i++){
              var e=els[i]; e.setAttribute('data-vf','1');
              try{
                var ff=getComputedStyle(e).fontFamily||'';
                if(/google sans/i.test(ff) && !/symbols|icons/i.test(ff)){
                  e.style.setProperty('font-family','sans-serif','important'); // generic = the SYSTEM font (inherits phone font changes)
                }
              }catch(x){}
            }
          }
          // Walk an element up to its carve-able BLOCK: stop at [role=main], review cards, anything
          // viewport-scale, or a scrollable parent (same guards the summary walk proved out).
          function velaBlockOf(el){
            var cap2=window.innerHeight*0.5;
            var cand=el;
            while(cand.parentElement){
              var p2=cand.parentElement;
              if(p2.getAttribute('role')==='main') break;
              if(p2.querySelector('.jJc9Ad,[data-review-id]')) break;
              if(p2.offsetHeight>=cap2) break;
              if(p2.scrollHeight>p2.clientHeight+50) break;
              cand=p2;
            }
            return cand;
          }
          // --- Native search / topic chips / sort (Vela UI drives Google's hidden controls) ---
          // The chips row is anchored by its "All" chip (position-based, no classes).
          function velaChipsRow(){
            var all=[].slice.call(document.querySelectorAll('[role="main"] button')).filter(function(b){ return ((b.textContent||'').trim())==='All'; })[0];
            if(!all) return null;
            var row=all.parentElement;
            for(var i=0;i<4 && row;i++){ if(row.querySelectorAll('button').length>=2) break; row=row.parentElement; }
            return row;
          }
          function velaChips(){
            if(FULL) return; // full-screen shows Google's own chip/search/sort row
            if(window.__velaChipsSent) return;
            var row=velaChipsRow(); if(!row) return;
            var out=[];
            [].slice.call(row.querySelectorAll('button')).forEach(function(b){
              var t=(b.textContent||'').trim(); if(!t || t.length>32) return;
              var m=t.match(/^(.*?)\s*(\d+)$/);
              if(t==='All') out.push({l:'All'});
              else if(m && m[1]) out.push({l:m[1].trim(), n:parseInt(m[2],10)});
              else out.push({l:t});
            });
            if(out.length>=2){ window.__velaChipsSent=1; try{ VelaPanel.onChips(JSON.stringify(out)); }catch(e){} }
          }
          window.velaClickChip=function(label){
            var row=velaChipsRow(); if(!row) return;
            var bs=[].slice.call(row.querySelectorAll('button'));
            for(var i=0;i<bs.length;i++){ var t=(bs[i].textContent||'').trim(); if(t===label || t.replace(/\s*\d+$/,'')===label){ try{ bs[i].click(); }catch(e){} return; } }
          };
          window.velaSearch=function(q){
            var inp=document.querySelector('[role="main"] input'); if(!inp) return;
            try{
              var set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
              set.call(inp,q);
              inp.dispatchEvent(new Event('input',{bubbles:true}));
              inp.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:'Enter',keyCode:13}));
              inp.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Enter',keyCode:13}));
            }catch(e){}
          };
          window.velaSort=function(label){
            var bs=[].slice.call(document.querySelectorAll('[role="main"] button')).filter(function(b){ return /sort/i.test((b.getAttribute('aria-label')||b.textContent||'')); });
            if(!bs.length) return;
            try{ bs[0].click(); }catch(e){}
            var attempts=0;
            var t=setInterval(function(){
              attempts++;
              var items=[].slice.call(document.querySelectorAll('[role="menuitem"],[role="menuitemradio"],[role="option"]'));
              for(var i=0;i<items.length;i++){
                if(((items[i].textContent||'').trim().toLowerCase())===label.toLowerCase()){ try{ items[i].click(); }catch(e){} clearInterval(t); return; }
              }
              if(attempts>8) clearInterval(t);
            },150);
          };
          // Class-agnostic "reviews are rendered" check. Google A/B-serves front-end builds with
          // ROTATED class names: on those, cards render fine but '.jJc9Ad,[data-review-id]' counts
          // zero — the watchdog then kills a HEALTHY panel the user is about to scroll (reported
          // live). Relative-date texts ("2 months ago") are content, not markup — they can't
          // rotate. Known classes stay as the fast path.
          function velaHasReviews(){
            if(document.querySelector('.jJc9Ad,[data-review-id]')) return true;
            return [].slice.call(document.querySelectorAll('span,div')).some(function(e){
              return e.children.length===0 && /^(a|an|\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago/i.test((e.textContent||'').trim());
            });
          }
          // --- scroll-sync: tell the native side when the reviews scroller is at its top/bottom edge,
          // so the OnTouchListener can hand a boundary drag to the Vela sheet (scroll-up / collapse)
          // instead of hogging every gesture. Only fires on an edge-state CHANGE (cheap).
          var __velaTop=true, __velaBot=false;
          function velaReportEdge(){
            var sc=window.__velaSc; if(!sc) return;
            var at=sc.scrollTop<=1;
            var ab=(sc.scrollTop+sc.clientHeight)>=(sc.scrollHeight-2);
            if(at) window.__velaEngaged=0; // back at the top: re-arm the sheet-takeover signal
            if(at!==__velaTop || ab!==__velaBot){ __velaTop=at; __velaBot=ab; try{ VelaPanel.onPanelEdge(at, ab); }catch(x){} }
          }
          function isolate(){
            var main=document.querySelector('[role="main"]');
            if(!main) return false;
            var h=window.innerHeight, w=window.innerWidth;
            if(!h || !w) return false;
            // Pixels, not vh — vh is 0 in this embedded WebView (verified via devtools).
            // FULL-screen: DON'T pin main. position:fixed makes main the containing block for
            // Google's OWN position:fixed photo/video lightbox (→ it renders off-screen = "can't
            // tap photos"), and overflow-y:auto hijacks scroll from Google's inner reviews list so
            // its virtualizer never pages in until a re-layout is forced (the "select a chip then
            // switch back to All to load" bug). In full-screen the WebView IS the viewport, so
            // Google's own layout + inner scroller + lightbox all work natively — just remove the
            // surrounding chrome (below) and theme it.
            if(!FULL){
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
            }
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
                // Reviewer name / "N reviews" render as links: navigation is blocked, so kill the
                // link affordances (tap underline + highlight flash) — they read as broken.
                + '[role="main"] a,[role="main"] a *,[role="main"] button,[role="main"] button *,[role="main"] a:hover,[role="main"] a:active,[role="main"] a:focus,[role="main"] button:active,[role="main"] button:focus{text-decoration:none !important;outline:none !important;}'
                + '*{-webkit-tap-highlight-color:transparent !important;}'
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
              var t = e.target;
              // Intercept photo taps → Vela's native gallery (the reliable path). Google's own
              // photo VIEWER is a page navigation, which the nav-lockdown blocks AND the carve
              // can't host — so even in full-screen we open the native gallery. (Verified: a raw
              // photo click with the carve fully removed still renders no viewer — it's a nav.)
              var btn = (t && t.closest) ? t.closest('[jsaction*="review.openPhoto"],.Tya61d') : null;
              if(btn){
                e.preventDefault(); e.stopImmediatePropagation();
                var card = btn.closest('.jJc9Ad') || btn.closest('[data-review-id]') || btn.parentElement;
                var urls=[], tap=0;
                velaMediaBtns(card).forEach(function(b){
                  var u=velaMediaUrl(b); if(!u) return;
                  u=velaUpsize(u);
                  var at=urls.indexOf(u);               // de-dupe: a nested jsaction+.Tya61d pair could
                  if(at<0){ at=urls.length; urls.push(u); } // resolve to the same photo URL twice
                  if(b===btn || b.contains(t)) tap=at;
                });
                if(urls.length){
                  var meta={urls:urls, index:tap, author:velaAuthor(card), date:velaDate(card)};
                  try{ VelaPanel.onReviewPhotos(JSON.stringify(meta)); }catch(x){}
                }
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
            if(FULL) return; // full-screen: Google's own inner scroller sizes itself natively
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
                // Attach the edge reporter once per scroller (the SPA can swap the node). The
                // scroll listener also (a) tracks a low-passed scroll VELOCITY so an inertial
                // fling that lands on the top edge hands its leftover momentum to the sheet
                // (else the fling dead-stops at the boundary — the "snap"), and (b) fires a
                // one-shot ENGAGED signal when the user starts really scrolling the reviews, so
                // Vela can slide the sheet to full screen around them (re-armed at top). ALSO
                // watch for content growth: paging in more reviews grows scrollHeight WITHOUT a
                // scroll event, which silently un-bottoms the scroller (stale atBottom would
                // double-scroll an up-drag until the next 1s tick) — and newly-paged cards need
                // their Like/Share stripped NOW, not at the next 1s tick (the "buttons return"
                // flash).
                if(!sc.__velaEdgeHooked){ sc.__velaEdgeHooked=1;
                  sc.addEventListener('scroll', function(){
                    var now=performance.now(), y=sc.scrollTop;
                    var pv=sc.__velaPrev; sc.__velaPrev={t:now,y:y};
                    if(pv && now>pv.t){
                      var v=(y-pv.y)/(now-pv.t); // css px/ms, negative = moving toward the top
                      sc.__velaV = (sc.__velaV===undefined) ? v : (0.6*sc.__velaV + 0.4*v);
                    }
                    if(y<=1 && sc.__velaV<-0.25){
                      var vv=Math.abs(sc.__velaV)*1000; sc.__velaV=0;
                      try{ VelaPanel.onEdgeFling(vv); }catch(x){}
                    }
                    if(!window.__velaEngaged && y>120){
                      window.__velaEngaged=1;
                      try{ VelaPanel.onPanelEngaged(); }catch(x){}
                    }
                    requestAnimationFrame(velaReportEdge);
                  }, {passive:true});
                  try{ new MutationObserver(function(){
                    requestAnimationFrame(function(){ velaReportEdge(); strip(); velaHistogram(); });
                  }).observe(sc,{childList:true,subtree:true}); }catch(x){}
                }
                velaReportEdge();
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
              // Maintenance: keep the carve + scroller sizing fresh for the panel's LIFETIME
              // (the WebView is destroyed with the place sheet, which kills this loop). It used
              // to stop after 60 ticks — but the reviews list pages in new cards indefinitely
              // (each needing its Like/Share stripped via isolate()->strip()), and scroll-sync's
              // edge reporting rides stretch()'s scroller adoption, so a dead loop froze both
              // after a minute. NO tab re-click here — the user may browse Menu/About.
              stretch(); revealOverlays(); velaHistogram(); velaChips(); velaFont();
              // Feed watchdog: Google sometimes serves the page SHELL but silently withholds the
              // review feed (soft bot-throttle — tabs + histogram render, the feed request is
              // never issued; observed live under heavy testing). Ready-but-cardless for ~15 s
              // means the user is staring at an empty panel: fail over to the native scraper
              // instead. Once ANY card has rendered, the watchdog disarms for good and
              // __velaStable starts counting (gates the summary-collapse phase).
              if(!window.__velaFedOk){
                if(velaHasReviews()){ window.__velaFedOk=1; }
                else if((window.__velaFeedless=(window.__velaFeedless||0)+1)>20){
                  try{ VelaPanel.fail(); }catch(e){}
                  return;
                }
              } else {
                window.__velaStable=(window.__velaStable||0)+1;
                if(!window.__velaChipsSent && (window.__velaStable||0)>2){
                  window.__velaChipsSent=1; // no topic chips on this business — unblock the native search/sort
                  try{ VelaPanel.onChips('[]'); }catch(e){}
                }
              }
              setTimeout(tick,1000);
              return;
            }
            var rev=reviewsOpen();
            if(rev && revAt<0) revAt=tries;
            // Fade Google's summary the MOMENT its rows render (opacity = zero layout risk even
            // pre-cards) — waiting for the ready tick left it visible for up to a second (the
            // "saw the google histogram for a second" flash).
            if(rev){ velaHistogram(); velaChips(); }
            // Prefer readying once review CARDS have painted — then the panel never flashes the
            // overview (Order-online button) during the tab transition. BUT don't HANG on it: a
            // place with a rating and zero written reviews never renders a card, and the card class
            // can rotate — so after a short grace once the Reviews tab has settled, ready anyway
            // (shows Google's own "no reviews" state) rather than spinning to the fail() timeout.
            var haveCards = velaHasReviews();
            // Hold the reveal while the native histogram exists but Google's copy hasn't faded
            // yet — revealing in that window shows BOTH histograms (reported). Bounded: give up
            // holding after ~2.5s so a guard-blocked fade can't hang the reveal.
            var grace10 = (revAt>=0 && tries-revAt>=10);
            var sumOk = (!window.__velaHistSent || window.__velaSumFaded || grace10) &&
                        (!window.__velaChipsSent || window.__velaCtlFaded || grace10);
            if(iso && rev && sumOk && (haveCards || (revAt>=0 && tries-revAt>=8))){ readySent=true; window.__velaBootDone=1; setupOnce(); stretch(); velaHistogram(); velaFont(); try{ VelaPanel.ready(); }catch(e){} }
            if(!readySent && tries>60){ try{ VelaPanel.fail(); }catch(e){} return; }
            setTimeout(tick, readySent?1000:250);
          }
          // Zero-flash guarantee: MutationObserver callbacks run at MICROTASK timing — before
          // the browser paints the mutated frame — so fading the summary here means it can
          // never reach the screen (the 250ms tick + post-adoption observers all left a
          // visible-frame window; user kept catching the flash). Disconnects once the fade has
          // landed; the scroller-level observer handles later recycles.
          try{
            var bootMo=new MutationObserver(function(){
              if((window.__velaSumFaded && window.__velaCtlFaded) || window.__velaBootDone){ bootMo.disconnect(); return; }
              velaChips(); velaHistogram();
            });
            bootMo.observe(document.documentElement,{childList:true,subtree:true});
          }catch(x){}
          tick();
        })();
    """.trimIndent()
}
