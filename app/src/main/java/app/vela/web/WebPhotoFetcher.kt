package app.vela.web

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.vela.core.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a place's photo gallery through a hidden [WebView] by **loading the place's own
 * `?cid=` page and scraping the rendered photo URLs out of the DOM** - the same tactic as
 * [WebReviewsFetcher].
 *
 * Why not the dedicated `hspqX` photos RPC? On-device logging (2026-06-28) proved Google
 * **degrades a bare anonymous `hspqX` POST per-session** to a single Street-View-only reply
 * (`streetviewpixels`, ~2 KB) - and a same-session retry returns the byte-identical degraded
 * answer, so the RPC is unreliable keyless. But Google **renders the real photo collage to a
 * logged-out browser on the place PAGE itself** (that's how a user sees them). So we let
 * Google's own JS draw the page and read the `googleusercontent` photo URLs back out of the
 * DOM - much harder for it to bot-degrade than a naked RPC call.
 *
 * Anonymous / no-login, desktop UA (a mobile UA deep-links to `intent://`). Strictly
 * best-effort + lazy: any failure/timeout returns empty and the caller keeps the search-preview
 * photo. Serialized by the base session since the single WebView navigates per place.
 */
@Singleton
class WebPhotoFetcher @Inject constructor(
    @ApplicationContext context: Context,
) : HiddenWebView(context, "photos") {
    private val partials = ConcurrentHashMap<String, (String) -> Unit>()
    private val hists = ConcurrentHashMap<String, (List<Int>) -> Unit>()
    private val infos = ConcurrentHashMap<String, String>()
    private val dateCbs = ConcurrentHashMap<String, (List<Pair<String, String>>) -> Unit>()
    // featureId -> (image id -> date text) mined from the place page, so cache-hits keep dates.
    private val dateCache = ConcurrentHashMap<String, List<Pair<String, String>>>()
    // Feature ids whose TAB-LESS cached gallery already got its one self-heal retry this session.
    private val retriedTabless = java.util.Collections.synchronizedSet(HashSet<String>())
    // featureId -> [5-star..1-star] counts, so a cached-gallery revisit still gets its histogram
    // (the photo cache hit skips the whole walk). Tiny payloads; same rough cap as the photo LRU.
    private val histCache = ConcurrentHashMap<String, List<Int>>()
    // Request ids whose scraper is already in the page: the settle timer and the load cap race
    // to inject it, and only the first may.
    private val injected = java.util.Collections.synchronizedSet(HashSet<String>())
    private val caps = ConcurrentHashMap<String, Int>()
    private val earlies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var warming: CompletableDeferred<Unit>? = null
    @Volatile private var warmed = false

    // featureId -> its scraped gallery. Re-tapping a place (or bouncing back from directions) then
    // shows photos INSTANTLY instead of re-running the ~20 s scrape. Access-order LRU, small cap.
    private val cache = object : LinkedHashMap<String, List<Photo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Photo>>) = size > 32
    }

    override fun bridge(): Any = PhotoBridge()

    private inner class PhotoBridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) = deliver(id, payload)

        // Streaming: the scraper reports the accumulated set whenever it GROWS, so the gallery
        // fills in as tabs are visited instead of arriving all at once at the end. JavaBridge
        // thread: the callback must be thread-safe.
        @JavascriptInterface
        fun onPartial(id: String, payload: String) {
            partials[id]?.invoke(payload)
        }

        // Per-photo posted dates mined from the place page's own APP_INITIALIZATION_STATE, the
        // replacement for the dead hspqX RPC (probe 2026-07-11: the blob carries relative
        // "N ago" strings AND absolute [Y,M,D] arrays beside the photo urls). Zero extra
        // requests; JSON = [[url, dateText], ...].
        @JavascriptInterface
        fun onDates(id: String, json: String) {
            val pairs = runCatching {
                val a = org.json.JSONArray(json)
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONArray(i) ?: return@mapNotNull null
                    val u = e.optString(0); val d = e.optString(1)
                    if (u.isNotBlank() && d.isNotBlank()) u to d else null
                }
            }.getOrNull().orEmpty()
            if (pairs.isNotEmpty()) dateCbs[id]?.invoke(pairs)
        }

        // Why the walk ended the way it did (tab count, when the gallery opened, whether the
        // late-tab rescue fired, total ticks): the menu tab's no-show diagnosis (user 2026-07-11).
        @JavascriptInterface
        fun onInfo(id: String, json: String) {
            android.util.Log.i("VelaPhotoWalk", "$id $json")
            infos[id] = json
        }

        // The place page's rating distribution ([5-star..1-star] counts) scraped in passing while
        // the photo walk is on the overview, the same aria-label table rows the reviews panel
        // reads. One-shot per fetch; JavaBridge thread, callback must be thread-safe.
        @JavascriptInterface
        fun onHistogram(id: String, json: String) {
            val counts = runCatching {
                val a = org.json.JSONArray(json); (0 until a.length()).map { a.getInt(it) }
            }.getOrNull()?.takeIf { it.size == 5 } ?: return
            hists[id]?.invoke(counts)
        }
    }

    /** Prime the hidden WebView BEFORE the first place is opened (call once results land):
     *  creates the WebView and loads maps.google.com once, so the first real photo fetch reuses
     *  a live renderer, warm HTTP/2 connections, cookies, and cached JS, instead of paying the
     *  whole cold start on top of the place page load. Booted, not running: the session puts
     *  the view to sleep once the page has landed. No-op while the view exists. */
    suspend fun warm() {
        if (warmed) return
        session {
            if (warmed || webView != null) return@session
            warmed = true
            val w = CompletableDeferred<Unit>()
            warming = w
            withContext(Dispatchers.Main) {
                ensureWebView().loadUrl("https://www.google.com/maps?hl=en")
                main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, MAX_WARM_MS)
            }
            w.await()
            warming = null
        }
    }

    override fun onReaped() {
        warmed = false // a later search boots it again
        warming?.complete(Unit)
    }

    /** The gallery for [featureId] (`0x..:0x..`): each [Photo] is its URL plus the gallery-tab
     *  [Photo.category] when Google tagged it (Menu / Food & drink / Vibe / By owner; null = All).
     *  No posted date from a DOM scrape. Empty on any failure. [count] caps how many we keep. */
    suspend fun fetch(
        featureId: String,
        count: Int = 80,
        // FIRST BATCH (2026-09-23): stop the walk as soon as [count] photos are in, instead of
        // visiting every gallery tab. A place tap takes the first few; "More photos" asks again
        // without it. A first batch is never cached as the gallery (it is not the gallery).
        early: Boolean = false,
        onPartial: ((List<Photo>) -> Unit)? = null,
        onHistogram: ((List<Int>) -> Unit)? = null,
        onPhotoDates: ((List<Pair<String, String>>) -> Unit)? = null,
    ): List<Photo> {
        val cid = cidOf(featureId) ?: return emptyList()
        synchronized(cache) { cache[featureId] }?.let { cached ->
            if (onHistogram != null) histCache[featureId]?.let(onHistogram) // cached walk = cached histogram
            if (onPhotoDates != null) dateCache[featureId]?.let(onPhotoDates)
            // A CATEGORIZED gallery is served from cache forever (it can't get better). A
            // TAB-LESS one gets ONE fresh walk per session: one flaky fetch used to poison the
            // place all session, "sometimes there's just no Menu tab" (user 2026-07-11). The
            // cached set still shows instantly; the retry streams over it if it finds more.
            if (cached.any { it.category != null } || !retriedTabless.add(featureId)) return cached
            onPartial?.invoke(cached)
        }
        return session {
            var reqId = ""
            val raw = try {
                request(TOTAL_TIMEOUT_MS) { id ->
                    reqId = id
                    caps[id] = count
                    if (early) earlies.add(id)
                    if (onPartial != null) partials[id] = { raw -> onPartial(parseLines(raw)) }
                    hists[id] = { counts -> histCache[featureId] = counts; onHistogram?.invoke(counts) }
                    dateCbs[id] = { pairs -> dateCache[featureId] = pairs; onPhotoDates?.invoke(pairs) }
                    // Blank the PREVIOUS place's DOM before navigating: on a slow load the MAX_LOAD
                    // cap can inject the scraper before the new page commits, and against an empty
                    // DOM that yields an empty result (safe) instead of the previous place's photos
                    // being returned for THIS featureId (cross-place data).
                    evaluate("try{document.documentElement.innerHTML=''}catch(e){}")
                    load("https://www.google.com/maps?cid=$cid&hl=en&gl=us", id)
                    main.postDelayed({ inject(id, count) }, MAX_LOAD_MS)
                }
            } finally {
                partials.remove(reqId)
                hists.remove(reqId)
                dateCbs.remove(reqId)
                injected.remove(reqId)
                caps.remove(reqId)
                earlies.remove(reqId)
            }
            val out = raw?.let { parseLines(it) } ?: emptyList()
            if (out.isNotEmpty() && !early) synchronized(cache) { cache[featureId] = out } // cache only real, whole galleries
            out
        }
    }

    /** Google.com pages only: the overview has a bare "Menu" ACTION LINK to the restaurant's own
     *  site; following it would kill the scrape (and quietly load a third-party site here). */
    override fun allowNavigation(url: Uri): Boolean {
        val host = url.host.orEmpty()
        return host == "google.com" || host.endsWith(".google.com")
    }

    override fun configure(view: WebView) {
        // Real offscreen viewport: the category grids are VIRTUALIZED (like the reviews list); at
        // 0x0 a category tab renders only ~1 tile, so a tall viewport is what makes each category
        // populate fully.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WV_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(WV_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WV_WIDTH, WV_HEIGHT)
    }

    override fun onPageFinished(view: WebView, url: String?, requestId: String) {
        warming?.let { w -> main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, WARM_SETTLE_MS); return }
        main.postDelayed({ inject(requestId, caps[requestId] ?: 80) }, SETTLE_MS)
    }

    /** Start the walk for request [id] once: the page-finish settle and the load cap both call
     *  this, and a request that is gone (timed out, superseded) gets nothing injected. */
    private fun inject(id: String, count: Int) {
        if (!isPending(id) || !injected.add(id)) return
        webView?.evaluateJavascript(extractScript(id, count, id in earlies), null)
    }

    /** Each line is "category\turl" (category "" = uncategorized/All), shared by the final result
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

    /** Self-polling DOM scraper: open the gallery, then VISIT EACH CATEGORY TAB (Menu / Food & drink /
     *  Vibe / By owner) in turn - clicking it, scrolling, and tagging the photos it shows with that
     *  category - then sweep the "All" view for the rest (uncategorized). Bridges "category\turl" lines
     *  back, de-duped by image id (first category a photo appears under wins). Avatars + Street View
     *  excluded. Google keeps these tabs in the DOM (verified on-device), so this is keyless. */
    private fun extractScript(id: String, cap: Int, early: Boolean = false): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, CAP=$cap, EARLY=${if (early) "true" else "false"}, acc={}, tries=0, phase=0, cats=[], ci=0, sub=0, opened=false, pre={}, openedAt=0, rescued=0;
              // The gallery tabs worth tagging (skip All/Latest/Videos/Street View - All is the fallback
              // sweep). Menu names cover the app's 11 languages (tabs arrive localized).
              var CATRE=/^(menu|menú|menù|speisekarte|cardápio|menukaart|меню|meny|food|drink|vibe|by owner)/i;
              var MENURE=/^(menu|menú|menù|speisekarte|cardápio|menukaart|меню|meny)/i;
              function ok(u){ return !!u && u.indexOf('googleusercontent')>=0 && !/streetviewpixels/.test(u) && !/\/a[\/-]|ACg8oc|ALV-/.test(u); }
              function idOf(u){ return u.replace(/=[wshpc].*$/,''); }
              function urlOf(el){ var u=el.currentSrc||el.src||''; if(!u || u.indexOf('googleusercontent')<0){ var bg=el.style.backgroundImage||''; if(!bg){ try{ bg=getComputedStyle(el).backgroundImage||''; }catch(e){} } var m=bg.match(/url\(["']?([^"')]+)/); if(m) u=m[1]; } return u; }
              function collect(cat,skip){ [].slice.call(document.querySelectorAll('img,[role="img"],button,a,[style*="background"]')).forEach(function(el){ var u=urlOf(el); if(ok(u)){ var k=idOf(u); if(skip && skip[k]) return; if(!acc[k]) acc[k]={c:cat,u:u}; } }); }
              function snap(){ var s={}; [].slice.call(document.querySelectorAll('img,[role="img"],button,a,[style*="background"]')).forEach(function(el){ var u=urlOf(el); if(ok(u)) s[idOf(u)]=1; }); return s; }
              // Tabs come from role="tab" ONLY: the place overview also has a bare "Menu" ACTION LINK (an
              // <a> to the restaurant's own site) - clicking that would navigate the WebView off Maps and
              // kill the scrape. (The Kotlin side also blocks off-google navigations as a belt-and-braces.)
              function tabEls(){ return [].slice.call(document.querySelectorAll('[role="tab"]')); }
              function clickTab(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ if((((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim())===name){ try{ ts[i].click(); }catch(e){} return; } } }
              function tabSelected(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ var t=(((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim()); if(t===name) return ts[i].getAttribute('aria-selected')==='true'; } return false; }
              // One-shot: after the gallery opens, its own tiles carry "Photo 2 of 45"-style labels that
              // match /photos?/ - re-firing this would click INTO a photo lightbox and break the tab walk.
              function clickPhotos(){ if(opened) return; var bs=[].slice.call(document.querySelectorAll('button,a')); for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].textContent||'')).toLowerCase(); if((/(^|\s)photos?(\s|${'$'})|see (all )?photos|all photos/.test(l)) && !/street ?view|review|profile|video/.test(l)){ try{ bs[i].click(); }catch(e){} opened=true; openedAt=tries; return; } } }
              function scroll(){ try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){ if(d.scrollHeight>d.clientHeight+300 && d.clientHeight>200) d.scrollTop=d.scrollHeight; }); }catch(e){} }
              // A real category tab is a clean name ("Menu", "Food & drink", "By owner") - EXCLUDE photo
              // captions that also start with a category word ("Menu · Photo 1 of 12") via the letters-only test.
              function tabsNow(){ var out=[]; tabEls().forEach(function(e){ var t=((e.getAttribute('aria-label')||e.textContent)||'').trim(); if(t && t.length<20 && CATRE.test(t) && /^[a-z &]+${'$'}/i.test(t) && out.indexOf(t)<0) out.push(t); }); return out; }
              function lines(){ var out=[]; for(var k in acc) out.push((acc[k].c||'')+'\t'+acc[k].u); return out.slice(0,CAP).join("\n"); }
              function finish(){ try{ VelaBridge.onInfo(ID, JSON.stringify({tabs:cats.length, opened:opened?1:0, openedAt:openedAt, rescued:rescued, ticks:tries})); }catch(e){} try{ VelaBridge.onResult(ID, lines()); }catch(e){ try{ VelaBridge.onResult(ID,''); }catch(e2){} } }
              var histSent=0;
              // The overview's rating-distribution table (each row: "5 stars, 612 reviews") - the
              // same rows the reviews panel carves; grabbed once in passing, costs nothing extra.
              function hist(){ if(histSent) return; var rows=[].slice.call(document.querySelectorAll('tr[aria-label]')).filter(function(r){ return /^\s*\d\s+stars?,/i.test(r.getAttribute('aria-label')||''); }); if(rows.length<5) return; var c={}; rows.forEach(function(r){ var m=(r.getAttribute('aria-label')||'').match(/^\s*(\d)\s+stars?,\s*([\d,]+)/i); if(m) c[m[1]]=parseInt(m[2].replace(/,/g,''),10); }); if(c['5']!==undefined && c['1']!==undefined){ histSent=1; try{ VelaBridge.onHistogram(ID, JSON.stringify([c['5']||0,c['4']||0,c['3']||0,c['2']||0,c['1']||0])); }catch(e){} } }
              var sentN=0;
              // Stream growth: the sheet fills in as photos are found instead of waiting ~20s for
              // the full tab walk (first partial = the OVERVIEW's hero photos, ~1 tick after load).
              function partial(){ var n=0; for(var k in acc) n++; if(n>sentN){ sentN=n; try{ VelaBridge.onPartial(ID, lines()); }catch(e){} } }
              // One-shot probe (menu-date hunt, user 2026-07-11): does the place page's own
              // APP_INITIALIZATION_STATE carry per-photo relative dates ("3 months ago")? If a
              // clean url-near-date shape shows in the snippet, extraction replaces the dead
              // hspqX RPC as the date source at zero extra requests. Logcat: VelaPhotoWalk.
              // Mine the page's APP_INITIALIZATION_STATE for per-photo posted dates - the
              // photo entries carry the url at [6][0] (the dead RPC's shape) with a relative
              // "N ago" string or an absolute [Y,M,D] array nearby. One shot per page; zero
              // extra requests (menu-date hunt, user 2026-07-11).
              // Mine the page's APP_INITIALIZATION_STATE for photo urls with a nearby date -
              // relative "N ago" string or absolute [Y,M,D] array. The url match is LOOSE (any
              // array carrying a googleusercontent string directly or as a member's [0]): the
              // first cut required the dead RPC's [6][0] shape and found nothing (2026-07-11).
              // Review photos pairing with their review's date is CORRECT data. One shot/page.
              function aisDates(){
                if(window.__velaAisDates) return; window.__velaAisDates=1;
                try{
                  var out=[], urls=0, seen={};
                  function isArr(x){ return Object.prototype.toString.call(x)==='[object Array]'; }
                  function hunt(m,dd){
                    if(!m || dd>4) return null;
                    if(isArr(m)){
                      if(m.length>=3 && m.length<=4 && typeof m[0]==='number' && m[0]>2004 && m[0]<2100 &&
                         typeof m[1]==='number' && m[1]>=1 && m[1]<=12 && typeof m[2]==='number' && m[2]>=1 && m[2]<=31){
                        return m[0]+'-'+m[1]+'-'+m[2];
                      }
                      for(var i=0;i<m.length;i++){ var r=hunt(m[i],dd+1); if(r) return r; }
                    } else if(typeof m==='string' && / ago${'$'}/.test(m) && m.length<40){ return m; }
                    return null;
                  }
                  function urlIn(n){
                    for(var i=0;i<n.length;i++){
                      var v=n[i];
                      if(typeof v==='string' && v.indexOf('http')===0 && v.indexOf('googleusercontent.com')>=0) return v;
                      if(isArr(v) && typeof v[0]==='string' && v[0].indexOf('http')===0 && v[0].indexOf('googleusercontent.com')>=0) return v[0];
                    }
                    return null;
                  }
                  function walk(n,d){
                    if(!n || d>18 || out.length>=250) return;
                    if(isArr(n)){
                      var u=urlIn(n);
                      if(u){
                        var k=u.replace(/=[^=]*${'$'}/,'');
                        if(!seen[k]){ seen[k]=1; urls++; var dt=hunt(n,0); if(dt) out.push([u,dt]); }
                      }
                      for(var i=0;i<n.length;i++) walk(n[i],d+1);
                    }
                  }
                  // AIS's payloads are EMBEDDED JSON STRINGS (the ")]}'"-guarded blobs the
                  // transit fetcher reads) - the live tree has no photo urls (probed: 0).
                  // Parse every big string leaf that mentions photos and walk the PARSED tree.
                  var blobs=0, bigStr=0, withImg=0, withAgo=0;
                  function harvest(n,d){
                    if(!n || d>40) return;
                    if(isArr(n)){ for(var i=0;i<n.length;i++) harvest(n[i],d+1); }
                    else if(typeof n==='string' && n.length>2000){
                      bigStr++;
                      if(n.indexOf(' ago')>=0) withAgo++;
                      if(n.indexOf('googleusercontent')>=0 || n.indexOf('lh3.')>=0){
                        withImg++;
                        var t=n; if(t.indexOf(")]}'")===0) t=t.substring(4);
                        var k=t.indexOf('['); if(k>=0 && k<8){
                          try{ var parsed=JSON.parse(t.substring(k)); blobs++; walk(parsed,0); }catch(e3){}
                        }
                      }
                    }
                  }
                  harvest(window.APP_INITIALIZATION_STATE,0);
                  try{ VelaBridge.onInfo(ID, JSON.stringify({bigStr:bigStr, withImg:withImg, withAgo:withAgo, blobs:blobs, aisUrls:urls, aisDated:out.length, sample:((out[0]||[])[0]||'').slice(0,60)})); }catch(e2){}
                  if(out.length) VelaBridge.onDates(ID, JSON.stringify(out));
                }catch(e){}
              }
              function tick(){
                tries++;
                aisDates();
                hist();
                if(phase===0){
                  collect(''); clickPhotos(); scroll();
                  // Wait until the gallery's category tabs actually exist. The old 8-tick (4 s)
                  // cap counted from SCRIPT START - page load + finding the Photos button + the
                  // gallery render routinely ate it all on a cold WebView, so real menu tabs got
                  // skipped and the place walked tab-less (the inconsistent-Menu report, user
                  // 2026-07-11). Now: give the OPENED gallery 6 more ticks to grow tabs, and only
                  // hard-stop at 20 ticks when the gallery never opened at all.
                  cats=tabsNow();
                  if(cats.length>0 || (opened && tries>=openedAt+6) || tries>=20){ ci=0; sub=0; phase=1; }
                }
                else if(phase===1){
                  if(ci>=cats.length){ phase=2; sub=0; }
                  // Per tab: SNAPSHOT what's already on screen, click, then collect ONLY images that
                  // appear after the switch (and only once aria-selected confirms it) - the whole-document
                  // sweep was tagging page chrome + the previous grid's leftover tiles with the category
                  // (the "Menu tab full of non-menu pics" report, 2026-07-10). Menu tabs get a LONGER
                  // scroll dwell so a long menu is walked to the end, not sampled.
                  else { if(sub===0){ pre=snap(); clickTab(cats[ci]); } scroll(); if(sub>=2 && tabSelected(cats[ci])) collect(cats[ci], pre); sub++; var dwell=MENURE.test(cats[ci])?14:6; if(sub>=dwell){ ci++; sub=0; } }
                }
                else {
                  // Late-tab rescue: tabs that appeared AFTER phase 0 gave up would silently be
                  // swept uncategorized - jump back and walk them once.
                  if(sub===0 && cats.length===0 && !rescued){
                    var late=tabsNow();
                    if(late.length>0){ rescued=1; cats=late; ci=0; sub=0; phase=1; partial(); setTimeout(tick, 500); return; }
                  }
                  // The All sweep: click, give the grid one no-collect settle tick, then sweep uncategorized.
                  if(sub===0) clickTab('All');
                  scroll(); if(sub>=1) collect('');
                  sub++; if(sub>=5){ finish(); return; }
                }
                if(tries>84){ collect(''); finish(); return; }
                partial();
                // First batch: done as soon as the cap is reached (a place tap needs a handful, not
                // every tab), so the page stops making requests.
                if(EARLY){ var have=0; for(var k0 in acc) have++; if(have>=CAP){ finish(); return; } }
                setTimeout(tick, 500);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        // Must outlast the script's own hard stop (84 ticks x 500 ms = 42 s + page load <= 8 s): if the
        // Kotlin timeout fires first we return NULL and throw away everything the walk accumulated,
        // instead of the partial set the script's salvage path would deliver.
        const val TOTAL_TIMEOUT_MS = 55_000L
        const val SETTLE_MS = 1_200L
        const val MAX_LOAD_MS = 7_000L
        const val MAX_WARM_MS = 9_000L
        const val WARM_SETTLE_MS = 2_000L
        // Offscreen viewport so the virtualized category grids render a full batch (not ~1 tile).
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 3200
    }
}
