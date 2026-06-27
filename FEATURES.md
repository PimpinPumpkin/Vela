# Vela — feature list

Status legend: ✅ done · 🟡 partial / in progress · ⬜ planned

## Map & rendering
- ✅ MapLibre Native vector rendering (Compose-wrapped)
- ✅ Detailed open basemap: bundled OpenFreeMap Liberty + injected house numbers at z17; OpenMapTiles vector source pinned to OpenFreeMap's **versioned** tile path (the un-versioned path serves empty tiles — that was a blank-map bug)
- ✅ Route line, **tappable Google-style search-result pins**, location dot as GeoJSON layers
- ✅ Heading-up, tilted navigation camera; fit-route-to-screen on preview; recenter FAB. **Pinch-zoom during nav keeps the follow-camera (2026-06-21):** a pinch keeps tracking your position at the zoom you chose (a dedicated `OnScaleListener` adopts it as an override and suppresses the auto-zoom while you pinch), while a **pan** still detaches to let you look around (`OnMoveListener`) and **Re-center** clears the override back to auto-zoom + follow — telling the two gestures apart, which the move-started reason alone couldn't. *(Fixed 2026-06-21: the override snapped back to auto a beat later because a `navFollowing`-keyed reset fired every time follow re-attached; now the zoom is captured **continuously** during the pinch and cleared only on a **real pan** or at **nav-end** (keyed on `navMode`). And the pan-detach moved from `onMoveBegin` to **`onMove`** — `onMoveBegin` could fire before `onScaleBegin`, so a pinch was misread as a pan and **detached the camera the instant you zoomed**; `onMove` runs after `scaling` is set, so a pinch now **locks your zoom AND keeps tracking** (Google-style) and only a real pan detaches.)* **Predictive framing — tried + REVERTED (2026-06-21):** aiming the camera a little ahead of the puck (to see into turns) inched/stuttered on real GPS even off the smooth `progressM`, and feel-wise the plain **smooth puck-follow** was what worked — so the camera follows `navPuck.drawn` directly. (See-into-turns can return later via in-frame padding that lowers the puck WITHOUT swinging the camera target around.) **De-jittered 2026-06-19** (was re-animating every recomposition → lag/shimmer; throttled to real movement >4 m / turn >2° with a 550 ms ease). **Per-frame follow (2026-06-21):** that throttle still felt *stiff* (the camera re-pointed only ~1–3×/s); the follow-camera now runs in the **motion ticker at 60 fps**, easing toward the smoothed puck point each frame (~0.12 s) for a continuous glide — seeded from the live camera on (re)attach so the hand-off from the pre-engage framing / a Re-center is smooth. The old throttled block is kept only for the pre-engage / off-route case. *(Feel change — confirm on a real drive; revertible.)* With **speed-adaptive zoom** (Google-style: pulls back on the freeway to see ahead, tightens on city streets — **continuous + low-passed** since 2026-06-21: hard speed-band thresholds made the zoom ping-pong ("zooms in and back") whenever speed hovered near a boundary in stop-and-go, so it's a smoothly interpolated zoom over a damped speed now). **2026-06-21:** during nav it now follows the **smoothed motion-model puck point** (see the location-indicator entry below) rather than the raw GPS fix, so the view can't lurch to a far spot when the snap is briefly ambiguous
- ✅ **Traversed route greys out** behind the vehicle (Google-style) — a line-progress gradient on the route (grey for the part driven, live traffic ahead). **During nav the route line carries traffic per segment, not the whole map** (2026-06-19 per feedback — the `/maps/vt` whole-map overlay no longer auto-shows when navigating; it washed every road, and the ask was traffic on "just the road we're on"). The route line is now coloured **per segment** like Google — free-flow blue with amber/red bands over the congested stretches (from `route[3][5][0]` in the directions response; see "Routing & traffic"). The manual traffic toggle still paints the full map when you want it. Nav-overlay polish: speedometer + recenter lifted clear of the bottom bar, recenter is now an icon-only FAB tucked bottom-right. **Maneuver-banner swipe fixed** — `pointerInput(Unit)` had captured a stale step index so every swipe repeated one card; `rememberUpdatedState` makes each swipe walk to the next/prev step
- ✅ Compass kept clear of the status bar (inset-aware margins)
- ✅ Tap a labelled POI **or a search-result pin** to open it; camera frames all results after a search. Tapping a POI also reads `name:latin`/`name:en` (not just `name`), and an **unnamed** POI icon (an apartment gym, an unnamed park/playground) **reverse-geocodes to a pin + address** instead of being a dead tap. When several Google listings share the same spot (e.g. a co-branded "SpeeDee Midas" with a sparse **Midas** *and* a rich **SpeeDee** profile), the tap now opens the **most-reviewed = canonical** one rather than whichever happens to be a few feet nearer
- ✅ Bottom sheets (place sheet, steps) **fill to the screen edge** — content is padded off the gesture/nav bar, but the sheet background no longer stops short and lets the map peek through at the very bottom
- ✅ **Long-press the map** → drop a pin, reverse-geocode it to an address (Nominatim/OSM, keyless), then get Directions — works even where no building is drawn
- ✅ Keyless **OpenFreeMap Liberty** basemap (active, loaded by URL — the setup that renders on-device, no key): **Google-style POI markers + category-coloured labels**, a **clean Google-style road treatment** — white road fills on light-grey land with the **casings faded out** (minor-road casing == the land, so streets are crisp white lines with **no outline**), soft-yellow motorways, **neutralised landuse** (no tan residential/commercial blobs), and **flattened fill-patterns** (Liberty's fern-hatch wetlands + dotted pedestrian plazas → flat fills, like Google) — plus light/dark recolour, all at **runtime** (tuned live in a MapLibre GL JS harness against Google, on-device-verified light + dark)
- 🟡 **MapTiler Streets** path stays wired but off (`USE_MAPTILER=true` to enable, needs the key). The bundled-style **Roboto font** is parked — its vector tiles wouldn't load via `fromJson` on-device (loading Liberty by URL is what works)
- ✅ **First-run welcome** — a clean branded intro (no tracking / real places & routes / free & open source) with a single "Get started"; shown once
- ✅ **Tasteful donation** — a permanent "Support Vela" entry in Settings, plus a **one-time** prompt that appears only **after a week** of use ("entirely optional, and this is the only time it'll ask"), trivially dismissed, never blocking. (`Onboarding` holder; set `DONATE_URL` to your own Liberapay/Ko-fi/Sponsors page)
- ✅ **In-app Light / Dark / Follow-system switch** (Settings → Appearance) — sets Vela's theme **independently of the phone**, so you can run the app dark without flipping the whole OS (the whole app + the map recolour live off one preference, `AppTheme`/`isAppInDarkTheme`; persisted). Dark mode recolours **every** landuse/landcover fill (commercial, school, retail, …), not just a hardcoded few, so no light/cream patches break the night palette (verified on-device)
- ✅ **Google-style POI markers** — category-coloured circles with white Material Icons glyphs (food=orange, shop=blue, park=green, health=red, transit=blue, …), generated at runtime over a bundled Material Icons font, sized to read like Google's (`iconSize` 0.8); in light mode the POI **label text is coloured by category too** (like Google). **Density tuned for parity:** Liberty's always-on `poi_transit` layer (bus stops at every zoom = clutter) is pushed to **z16+** like Google, while the next business tier (`poi_r7`) is pulled down to **z15** so more shops/restaurants show; MapLibre's label collision keeps it tidy. **Nameless POIs are filtered out** (`has("name")` AND-ed onto each poi layer's rank filter) — the unnamed icons couldn't be opened anyway (they'd just drop a near-by address pin) and read as duplicate junk, so only labelled, tappable POIs render now. **During turn-by-turn ALL POI tiers are hidden** (`poi_r1`/`poi_r7`/`poi_r20`/`poi_transit` → off while navigating, restored on exit, keyed on the style so a dark/light flip re-applies it): POI labels re-ran MapLibre's symbol collision on every nav camera rotate/zoom and **popped in and out at zoom thresholds** — Google declutters its map the same way during nav, so the nav view is label-clean now *(top-rank `poi_r1` was kept at first but still flickered at the threshold, so 2026-06-21 it's hidden too)*
- ✅ **Buildings** (footprints + 3-D massing) — OSM building data already in the Liberty tiles (`building` 2-D fill + `building-3d` extrusion at high zoom), given enough contrast + a subtle outline to read (was coloured almost identical to the land), and the 2-D `building` layer's minzoom lowered to 14 so **residential houses surface a zoom-level earlier** (~z17 now). **Keyless, no API key, no new data/infra.** *Caveat (two distinct gaps):* (1) OpenFreeMap's keyless tiles drop small footprints from low-zoom tiles, so houses only appear zoomed-in (Google shows them at neighbourhood zoom because its tiles are richer); (2) more fundamentally, **some US residential houses are simply absent from OSM** and so render at *no* zoom — Google has them from satellite/assessor data that was never imported into OSM. Lowering minzoom fixes (1); only **(2) richer building data** closes the "house is on Google but never on Vela" gap. Options: **MapTiler** (still OSM-derived buildings → same data gap, just nicer styling; path wired, off) won't help here; the real fix is **our own tiles baked from Microsoft US Building Footprints (~130 M, ODbL) / Overture / Google Open Buildings** — free + open, but bulk files → self-hosting/PMTiles infra (a deliberate decision, not a quick toggle)
- ✅ **Terrain relief (hillshade)** — Google-style shaded relief from the **keyless
  open terrarium DEM** (AWS Open Data, no key, native fetch so no CORS), added at
  runtime under the road layers (so roads + labels stay crisp) and capped at z16
  (terrain context for the overview, gone at street level). Tuned per theme — a
  soft warm-grey shadow in light, deeper shadows + a cool highlight in dark.
  Verified in a MapLibre GL JS harness (same render engine as MapLibre Native)
  against the real DEM tiles before shipping
- 🟡 Self-hosted PMTiles — the no-key, no-quota Google-look path — remains for later
- ⬜ Protomaps "Google-Maps-ify" style (road hierarchy ✅, hillshade ✅, POI icons ✅ done; this is the bundled-style variant)
- ⬜ Satellite layer (terrain relief ✅ done; aerial imagery still planned)
- ⬜ Map rotation/tilt + heading-up mode during nav

## Search & POIs (live Google data)
- ✅ Place search — name, category, **full address (street, city, state, ZIP)**, rating, review count, coordinates
- ✅ Searching a **specific/far address** resolves to that single geocoded location (handles the response's single-result shape, not just the POI list — fixes the old "calibration error" on far addresses); genuinely-empty searches now show "no results" instead of an error
- ✅ **Address → business snap** — searching a raw address that *is* a business (e.g. "1020 Olive Dr, Davis") now lands on the **business** (In-N-Out Burger, rating/hours/category and all), not the bare address — Google lists the "at this place" business under the geocoded node (`[0][1][0][14][68]`) and Vela now reads it. *(verified on-device; unit-tested; the path is in calibration so it's remotely fixable)*
- ✅ Search-result rows show **5-star rating**, colour-coded open/closed status, and the **full address (city/state/ZIP)** to disambiguate similar names / lookalike residential addresses — **sized for legibility** (name at titleMedium, the rating/category/address lines bumped up from the cramped small text)
- ✅ Place sheet (**Google-styled**): high-contrast white-on-dark / black-on-white name + status time (fixed palette, not washed-out by Material You), **5-star rating visual**, **swipe-down to dismiss from anywhere on the sheet** (a nested-scroll handler: at the top of the body a downward drag collapses then dismisses; mid-list it scrolls), status with the **word colour-coded** (Open green / Closed red) and the time in plain ink, **distance from your location** (when opened from a located search) + price + category, **full address with a copy button**, **collapsible weekly hours** (today first, expand for the week)
- ✅ Full-screen photo viewer: **pinch-to-zoom** (+ pan when zoomed) and **swipe-down-to-dismiss**, swipe sideways between photos
- ✅ **System maps handler** — Vela registers for `geo:` URIs and Google-Maps web links (`/maps/place`, `/search`, `?q=`, `@lat,lng`, `maps.app.goo.gl`), so tapping an address or "open in maps" in any other app offers Vela. A query runs a search (biased to any coords in the link), a bare point drops a reverse-geocoded pin — the degoogled-replacement piece. (`MapLinkParser` in `:core`, unit-tested; parsed in `MainActivity`.)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted) + **recently-viewed places** — opening a place records it; the search page shows a **Recent** section (pin icon, one tap to reopen — enriched via search) above **Recent searches** (clock icon). Capped at 8, deduped, cleared together *(verified on-device)*
- ✅ **Full-screen search page** (Google-style) — focusing the search box opens an opaque page with **Home/Work shortcuts**, saved + recent searches over the map (back arrow / back gesture closes it); running a search drops back to the map with the results list + red pins
- ✅ **Home / Work shortcuts** (Google's signature) — two pinned rows at the top of the search page. Unset shows "Set home/work address"; tapping arms an assign mode (a "Search for your home address" banner + Cancel) and the **next place you pick** — a suggestion, a saved place, or a tapped POI — gets pinned. A set shortcut shows the place name and a **⋮ menu (Change / Remove)**; tapping the row opens the place. You can also set the **currently-open place** as Home/Work straight from its **place-sheet ⋮ overflow**, or promote a Saved place via its ⋮. Persisted in `PlaceShortcutStore` (`vela_shortcuts` prefs), so they survive restarts *(verified on-device: set Home → "Sacramento Valley Station", ⋮ → Change/Remove)*
- ✅ **Autocomplete / suggestions as you type** — after a short debounce, the search page shows live **place matches** (name + address) to tap, like Google; tapping one opens its sheet directly. Reuses the calibrated search endpoint (no separate suggest RPC); a stale response is dropped if the query moved on. *(verified on-device: "starb" → Starbucks locations)*
- ✅ Clear-search (X) button; the **results list is a top-sheet** under the search bar (it hangs from the top, so the gestures follow that): **swipe DOWN to grow** it (~half screen → ~94% full), **swipe UP to retract** it — first shrinking an expanded list, then hiding it to the "N results" pill so you can browse the map (pins stay, tapping the pill re-opens it); the chevron + back gesture also collapse it. A nested-scroll handler lets a down-overscroll at the top of the list expand it ("pull to see more")
- ✅ **Result filters** — chips in the results header: **"Open now"** (places open right now) and **"4.0★"** (rating ≥ 4.0); they stack and the count updates live
- ✅ **Back gesture peels one layer at a time** (steps → navigation → route preview → place sheet → results list) instead of closing the app — only the bare map exits
- ✅ **Full reviews** — the place sheet's **Reviews tab** lists real reviews (author + avatar, star rating, relative date, text) pulled from Google's keyless `listentitiesreviews` endpoint by feature id. **Review photos are collected by URL shape, not a fixed index** (recalibrated 2026-06-20): `ReviewsParser` takes only a reviewer's **uploaded** FIFE photos (`/gps-cs`, `/geougc`, `/p/AF1Qip`) and never their **avatar** (`/a/`, `/a-/`, `ACg8oc`, `ALV-`). This fixes a bug where the old "photos hang under `review[12]`" calibration was sweeping the reviewer's **profile picture** into the thumbnail strip — every review showed the author's face as if it were an uploaded photo. ⚠️ This RPC turns out to return **only avatars**, so the strip currently shows nothing; **real per-review uploaded photos need a separate source** (the photo-inclusion flag resisted discovery — see ROADMAP). Unit-tested (avatars excluded at `[0][2]`/`[12]`/`[60]`, genuine UGC collected). **Intermittent "no reviews" fixed (2026-06-21):** the RPC sometimes returns an empty page (a bot-degraded reply / rate blip), which used to stick as "no reviews" until you reopened the place (seen on Sandy Cove Park + The Black Dog). `fetchReviews` now **retries with backoff (up to 4 tries across ~3 s — widened after a tap-to-retry confirmed a fresh call clears the flake within seconds) when the place's own review *count* is >0 but the fetch came back empty** — that count-vs-zero mismatch is the transient-miss tell — while a genuinely review-less place (count 0/unknown) still stops after one try, so review-less places are never hammered. **Plus an honest UI state:** when the count says there ARE reviews but the list is still empty, the tab shows **"Couldn't load reviews. Tap to retry."** (a load failure, distinguished from a real zero by the count-vs-empty mismatch) instead of the misleading "No reviews available", so a sticky outage that outlasts the auto-retry is one tap from recovering — `MapViewModel.retryReviews()`. *(Seen showing rating + count but no list on Sandy Cove Park; the count proves the featureId loaded, so it's purely the RPC flaking.)*
- ✅ **Tabbed place sheet** (Google-style): **Reviews** (rating summary + featured highlight + full list) and **About**. Layout order: **photos (hero) → info → hours → action row → popular times → tabs** (photos lead so they're visible at the peek height; popular times sit **below** the action buttons, like Google)
- ✅ **About tab = business description + attributes** — leads with Google's **editorial one-liner** ("Welcoming coffeehouse with handcrafted coffee…", node `[32][1][1]`), then the business's own **"From the owner"** blurb (`[154][0][0]`), then the attribute sections (Service options, Highlights, Accessibility, …). The description comes *before* the rest, per request. All three rich fields are trimmed from the keyless/list response, so they ride a lazy WebView detail fetch (`PlaceDetails`) — **wired on every open path** (search-result tap, **recents/saved**, and **POI taps**; the latter two were missing the call, so popular times + the summary silently never loaded there — fixed 2026-06-20). **That same fetch now also backfills the fields a *summary* node drops (2026-06-21):** searching a **suite / multi-tenant address** snaps to the business via a lightweight summary node that omits the **review count, full weekly hours, address, phone, website, price, attributes** (e.g. "Bellagio Nails" at a `Ste A-2` address showed `4.4` with no count and only today's hours). The focused name+address re-fetch is a FULL place node, so `PopularTimesParser` lifts those off too and `MapViewModel` merges them into any field the summary left blank — **feature-id-gated** (only from the matching result, so a neighbour's rating/hours can't be grafted on) and purely additive (no fetch/match → unchanged, no regression). *(reviews themselves always loaded via the reviews RPC; this restores the count + the rest of the card.)*
- ✅ **Action link — Book / Reserve / Order online** (2026-06-21) — Google's primary action button (a salon's "Book online", a restaurant's "Reserve a table" / "Order online") surfaces as a **prominent tinted button** on the place sheet that opens the provider link; the label adapts per business type. Parsed from `[1][75][0][0][5]` (label `[0]`, URL `[1][2][0]`, calibratable as `actionLabel`/`actionUrl`) and **defensively gated** — no real `http(s)` URL → no button, so a shape change can't render a broken action. *(structure verified against a real "Book online" node + unit-tested; on-device visual check pending an unlock; reserve/order coverage for restaurants wants one restaurant capture to confirm the same slot.)*
- ✅ **Attribute highlight chips** (2026-06-21) — the most-scanned attributes (service options, offerings, accessibility, …) surface as a **horizontal chip row on the place overview**, Google-style, instead of being buried in the About tab; pulled from the already-parsed `About` sections (priority-ordered, deduped, capped at 6) so every place with attribute data benefits, including the ones the summary-node enrichment now fills in. *(compiled + reuses the verified card-row pattern; on-device visual check pending an unlock.)*
- ✅ **"People also search for"** (2026-06-21) — a place opened from a **focused name search** (e.g. "Bellagio Nails Everett") shows a Google-style **horizontal row of related-place cards** (name + rating); **tap one to open it** as a full place (its own reviews/hours/popular-times then load). Lifted from the search response's `root[2][11][0]` (`SearchParser.parseSimilarPlaces`, calibratable as `similar`), each entry `[featureId, name, [[_,_,lat,lng], …, rating@6]]`, attached to the primary result. *(Present only when the query focuses on one result — Google's own behaviour; multi-result list taps and bare-address snaps don't carry it. Device-verified: 8 related salons on Bellagio, tapping "Hair By Mirikat Salon" opened it with full details.)*
- ✅ **"Also at this location"** — when other Google listings sit at the same spot (a co-branded shop's duplicate profile, a different unit at the address), the place sheet lists them with rating + category, tap to open — like Google's co-located-businesses section. Drawn for free from search results already in hand (no extra request). Matches on the **same street line** (e.g. "239 G St", suite-insensitive), not raw proximity, so a shop across the street isn't wrongly listed
- ✅ **Directions panel** (Google-style popup, not buried in the place sheet): tapping **Directions** opens a dedicated bottom panel with a **From → To** header (origin dot + destination pin) and a **swap (⇄)** button to **reverse the route** (you ⇄ the place), **Drive / Transit / Walk / Bike** tabs, and a prominent **Start** + **Steps**. For drive/walk/bike it lists the **route options** — each with a **traffic-coloured ETA** (green free-flowing → amber → red), distance, **via-road name** and a **"Fastest"** tag; **tap an alternate to select it** (the map line switches to it). Transit shows the results board instead.
- ✅ **Route from a different starting point** (not just your location) — the directions panel's **From** row is **tappable** (pencil affordance): pick any place via search and the route recomputes from there, like Google. Mirrors the existing **To** + **⇄ swap**; the search overlay covers the panel while picking and restores it on choose/cancel. Editable in **both directions** — the pencil sits on the "From" row normally, and moves to the "To" row when the route is reversed (that's where the custom endpoint then lives). A "Your location" reset row drops a custom endpoint back to live GPS.
- ✅ **Depart / arrive time** — the directions panel has **Leave now / Depart at / Arrive by** chips with a time picker. "Leave now" uses the live traffic-aware duration; a future **Depart at** / **Arrive by** shows an honest arrival/leave **window** from Google's own typical best→worst spread (`summary[10][4]` → `Route.typicalRangeSeconds`, e.g. "arrive 6:08–6:27 PM · in typical traffic"), plus an always-on "usually 1 hr 8 min – 1 hr 27 min" line. Per-*minute* future-traffic prediction stays login/Android-app-only (2026-06-20: the keyless response has no time-of-day curve, our `pb` matches Google's live client exactly, and the web depart-time control is un-automatable — see ROADMAP), so we surface the spread Google itself plans with rather than a false-precision single time
- ✅ **Search along route** — with a trip planned, the directions panel shows **Gas / Food / Coffee / Groceries** chips; tapping one searches near the route and shows only the results **within ~3 km of the route line, ordered start → destination** (so you find stops actually on the way). The route stays drawn; tap a result to open it
- ✅ **Consistent sheet styling** — the place sheet, directions panel, route chooser, **steps list**, nav bar, **the search-results list, the full-screen search page, and the "set as Home/Work" banner** now all share one Google-grey palette (`ui/SheetPalette`: `#1F1F1F`/`#FFF` surface, fixed ink/dim text, teal accent, green/amber/red traffic) instead of differently-shaded Material-You-tinted cards
- ✅ **Permanently-closed (dead) POIs** — detected from the place node's **`[23]==1` flag** (the reliable signal — a dead POI like Caffé Italia carries no open/closed status text at all, so the earlier text-only check missed it; the flag survives the keyless degraded response, calibrated live 2026-06-19), plus a "Permanently" text fallback. They **stay in search results** (labelled "Permanently closed" in red, in both the row and the place sheet) but are **dropped from the map pins** so they don't clutter the map (a place you explicitly open still gets its pin)
- ✅ **Alternate routes** — Google's 2-3 driving alternates are surfaced + selectable in the directions panel (e.g. "20 min · via I-80 E" / "21 min · via Co Hwy E6"); each draws along **Google's OWN route geometry** (delta-encoded in the response at `[0][7][i]`, decoded directly) so the line matches the via-label exactly, alternates included — **no more lines that double back on themselves or cut straight across** (the old scattered-point guess is gone; an open router is now only a fallback for routes Google omits geometry for)
- ✅ **Alternates drawn greyed on the map + tappable** (Google-style) — the non-selected routes render as **grey lines beneath the active blue one** (theme-aware shade so they read on dark/light tiles); **tap a grey alternate to switch to it** (same as picking it in the panel). The directions camera now also **frames the whole route above the panel** (per-edge bottom padding) instead of centring it behind the card *(rendering + framing verified on-device, Davis→South Lake Tahoe: grey I-80 arc alongside the blue US-50 route)*. The route line is drawn **below the basemap's label layers** (Google-style), so **road names and POI text stay legible on top of it** instead of being painted over *(2026-06-21)*
- ✅ Place sheet **peeks** (~56% screen) so the business info isn't immediately full-screen and the map stays visible above it; **drag the handle up to expand** (~92%, for the reviews), down to shrink, down again to dismiss. The body scrolls, so a tall place (hours + tabs) is fully reachable at either height
- ✅ **Pin stays visible above the sheet** — opening a place pushes the map's optical centre up by the sheet height (MapLibre bottom padding) and zooms in, so the pin sits in the visible strip above the card instead of being hidden behind it (Google-style)
- ✅ **Popular / busy times** (Google-style histogram in the place sheet, day chips + "busy right now") — **keyless, 2026-06-19.** Two catches fooled us. First: the keyless **OkHttp** search is bot-degraded (TLS-fingerprint, like photos/transit) and strips the `[84]` histogram, so we wrongly called it login-gated — a real browser engine isn't degraded. Second (the subtler one): even in the WebView, a **bare-name** search returns a 20-result `[64]` list *also* trimmed of `[84]`. The fix is a **specific query (name + address)** — that resolves to a single focused result whose `[0][1][0][14]` node keeps `[84]`. `WebPopularTimesFetcher` (warms google.com→maps, builds the name+address query into the `pb` + `q=`, same-origin-fetches it) + `PopularTimesParser`, wired lazily on **every** place-open path (search-result tap, recents/saved, POI tap — the latter two were missing it, fixed 2026-06-20). Since the fetch is slow (~10–20 s, real WebView) — and pre-warmed on search, so the first place you open after searching loads faster, the sheet shows a **"Loading popular times & details…" indicator** while it's in flight, so it reads as *loading*, not *missing* (clears to the chart, or to nothing for a place with no histogram)
- ⬜ "hours updated N ago" (place-RPC-only, absent from the search response); Updates/posts tab
- ℹ️ Reviews are the **top ~20** — the `listentitiesreviews` endpoint serves a fixed page (offset ignored) and deeper paging is behind an obfuscated continuation token; not chased (fragility vs. value)
- ✅ Place actions in a **Google-style quick-action row** (circular icon + label): **Call** (dialer), Website, Save, **Share menu (Google Maps link / Map pin geo: / coordinates / address)** — the actions are **evenly weighted across the full width** so all five fit on one row without the trailing Share icon clipping off the edge. The **geo: pin** is the degoogled-friendly share (`geo:lat,lng?q=lat,lng(Name)` opens in any maps app, incl. Vela — no google.com); round-trips back through Vela's own `MapLinkParser` (unit-tested)
- ✅ **Place photos** — business photo strip **leads the sheet as a hero** (horizontally scrollable, at the top so it's visible at the peek height / in landscape); **tap a photo to open a full-screen, swipeable gallery** with a counter. Opens with the search response's **preview (~10)** at `[1][105][0][1][0][i][6][0]`, then swaps in the **full gallery (~30–40)** — including for places with **no** search preview (e.g. SpeeDee/Midas-style service shops), which used to show nothing. The full-screen viewer shows each gallery photo's **posted date** ("Photo · May 2026", from the gallery RPC's `entry[21][6][8]` = `[year, month, day, hour]`, calibrated 2026-06-20). *(The contributor's **name** isn't in the keyless gallery RPC — only the date + an upload-source tag — so there's no author line; Google fills the name via a separate per-contributor lookup we don't make. See ROADMAP.)*
- ✅ **Full gallery via a hidden WebView** (`WebPhotoFetcher`) — the gallery RPC (`batchexecute` `hspqX` / `/MapsPhotoService.ListEntityPhotos`) serves the user photos **only to a real browser engine**. A plain HTTP client — even with perfect headers + consent cookies — gets a degraded **Street-View-only** reply: bot-detection at the **TLS/fingerprint** level (verified on-device — OkHttp gets a 162 KB token-less "lite" `/maps` page). So Vela runs a **hidden Android WebView** (real Chromium) that loads `maps.google.com` as an **anonymous, no-login** session — exactly like a logged-out browser, which *does* show the photos — and does a same-origin `fetch` to the RPC, handing the raw response back over a JS bridge. **Keyless** (no API key, no account). Created **lazily** (only when a place's photos are wanted), strictly best-effort (failure → keep the preview). **On-device verified 2026-06-17: 31 photos for SpeeDee-Midas, Davis.** Tradeoff: the WebView runs Google's JS (a fingerprinting step for a degoogled app — the opt-in cost of richer photos), OkHttp fallback kept. Gotchas baked in: **desktop UA** (a mobile UA makes Google deep-link to `intent://` the native app), **block non-http(s) redirects**, and **`Handler`, not `View.postDelayed`** (a headless WebView never attaches to a window, so View timers never fire).
- ✅ Category quick-chips (Restaurants/Coffee/Gas/Groceries/Hotels/Pharmacy/ATMs/Parks) → one-tap search, each with a Google-style leading icon
- ✅ "Search this area" — re-search after panning the map
- ✅ Filter: **open now**, **rating ≥ 4.0**, and **price** (tap the Price chip to cycle ≤$ → ≤$$ → ≤$$$ → ≤$$$$ → off, filtering on `priceLevel`) — chips sit on their own horizontally-scrollable row in the results header, stackable
- ✅ Saved / favourite places (star from the place sheet) — reopening a saved place **enriches it via search** so photos, rating and reviews load (saved places carry no feature id of their own); each saved row in the search page has a **⋮ menu** to **Set as Home / Set as Work** (promote it straight to a shortcut) or **Remove** it *(verified on-device)*
- ✅ **Export / import saved places** (Settings → Saved places) — **Export** writes the starred list to a portable JSON file shared via the system sheet (same FileProvider as the diag export); **Import** picks a file and **merges** it (de-duped by id, never overwrites/removes), with a toast of how many were added. Keyless, local, portable between devices.
- ⬜ Overture/OSM POIs as a fallback source

## Routing & traffic
- ✅ Driving directions with **real traffic-aware ETA** (live `duration_in_traffic`)
- ✅ **Live traffic overlay** (browse mode) — Google's congestion-coloured roads, a **keyless raster layer** (the web map's own public `/maps/vt?…!2straffic` PNG tiles on www.google.com — no API key). **Toggle moved off the map into Settings → Map** (2026-06-19) — it's a niche browse-only layer now that nav shows per-segment route traffic, so it no longer earns a map button. **Drawn below the POI/label layers at ~0.6 opacity** so it doesn't render over POIs or bury the basemap. *(Inherent caveat: Google's pre-baked raster paints free-flow green everywhere and re-rasterises on zoom — it's a subtle scanning aid, off by default.)*
- ✅ **Per-segment route-line traffic** — the drawn route is coloured Google-style
  **along its length**: free-flow blue with amber/red/dark-red bands over the congested
  stretches, from the directions response's own congestion spans (`route[3][5][0]` =
  `[level, startMeters, lengthMeters]`, only the non-free-flow runs — parsed sorted by
  start so the bands walk the line start→end in order). Rendered as **solid colour bands**
  (a MapLibre `step` expression, not an interpolated gradient — the driven/ahead boundary
  and span edges are crisp, per test-drive feedback "should be solid"). Combined with the
  driven-grey split so the part behind the vehicle greys out — but only while actually
  navigating: a pre-nav route **preview** draws clean with no grey nub at the start.
  (Walk/bike and no-live-traffic routes fall back to a single overall blue→amber→red tint.)
- ✅ Alternative routes returned
- ✅ Turn-by-turn maneuver list (type + distance from Google's step markup)
- ✅ **Honest remaining-distance / next-turn on routes that pass near themselves**
  (switchbacks, cloverleaves, out-and-backs) — `NavEngine` now tracks **monotonic
  forward progress** along the route (windowed projection around how far you've already
  driven) instead of a *global*-nearest point, and measures both "remaining" and "distance
  to next turn" **along the road** rather than crow-flies. Before, a return leg passing a
  few metres from the outbound leg made the global-nearest collapse "remaining" to almost-
  arrived while the next turn read crow-flies-huge — the test-drive's "51 mi to turn · 0.3
  mi remaining". *(2026-06-21; unit-tested with a hairpin route — `remainingStaysHonest…`; **verified on-device** on a real 14.5-mi route: `remaining` counted down 14.5→11.9 mi monotonically with 0 violations across 75 nav updates, next-turn always ≪ remaining.)*
- ✅ **Lane guidance** — Google's lane hints ("Use the right 2 lanes to turn") are
  pulled out of the step markup into their own field; the nav banner renders them as
  a **strip of turn-direction arrows** (one per indicated lane) + the hint text, and
  the step list shows them highlighted (the main instruction stays clean: "Turn right
  onto …")
- ✅ **Highway/exit signage** — route refs ("I-80 E", "US-50 E") and exit numbers
  ("Exit 4A") are parsed out of each instruction and rendered as Google-style badges:
  a **green exit tab** + a **bordered route shield**, in both the nav banner and the
  step list
- ✅ Route geometry via open router — **per-mode** FOSSGIS OSRM backends
  (`routed-car`/`routed-bike`/`routed-foot`), so drive/walk/bike each follow the
  correct network; Valhalla later
- 🟡 **Live route re-check while navigating** — periodically re-query traffic and
  offer a faster route if one appears (see Navigation below)
- ✅ Walking + cycling modes (drive/walk/bike) — each with its **own** path-following
  line, not a car route reused
- ✅ **Public transit** directions — a **Transit** chip in the directions chooser
  shows a Google-style results board: each option's **departure–arrival window**,
  **total duration**, **distance**, **agency**, and the **coloured line pills** you
  ride (real Google line colours + per-mode glyph: 🚆 train / 🚌 bus / 🚊 tram / …).
  Like photos, transit is served **only to a real browser engine** (a plain GET with
  the `!3e3` flag is silently downgraded to *driving*), so it goes through a hidden
  **WebView** (`app/web/WebDirectionsFetcher`) that loads the `/maps/dir/…/!3e3` page
  and reads the itinerary set out of `APP_INITIALIZATION_STATE` (the longest
  `)]}'` payload at slot [3]); `TransitParser` (keyless) parses it. **Verified
  on-device** Davis→Sacramento (6 options: Amtrak Thruway, Yolobus 42B/43/44, …).
- ✅ Transit **leg drill-down** — tap an itinerary to expand its ordered legs
  ("Walk 7 min → Bus 42B 5:48–6:41 AM (53 min) → Walk 7 min"): each leg shows its
  mode glyph, the ridden line (name + colour) or "Walk", and board/alight times +
  duration/distance. Parsed (unit-tested) from `trip[1]` in the **same** keyless
  fetch — no extra RPC. *(UI built; on-device visual check pending — phone was off
  ADB when it shipped.)*
- ⬜ Transit drill-down **stop names + ridden polyline** — the intermediate stops
  (board/alight stop names) sit in the leg's stop array and the shape in the same
  payload; deferred until the index can be device-verified (pinned from one capture).
- ⬜ Per-minute **predictive** future-traffic ETA (login/app-only — keyless gives the typical *range* only, see Depart/arrive time above); avoid tolls/highways
- ⬜ Self-hosted routing backend (replace the FOSSGIS community server)

## Navigation
- ✅ Turn-by-turn engine (step advancement, off-route detection, reroute) —
  pure/Android-free, **unit-tested** (arrival-requires-final-maneuver, reroute
  fires once per off-route transition not per fix, off-route clears on return).
  **Maneuver-sync fixes (2026-06-27, from a real highway drive where the line was
  right but the spoken/announced turns were ~6 mi out):** (1) the prompts + step
  advancement now measure distance to the maneuver **ALONG the route**, not
  crow-flies — crow-flies fired "take the exit" miles early wherever the highway
  curved back near the exit, then skipped the real one; (2) `placeManeuvers` now
  pins each turn to the **START** of its step, not the end (it added the step length
  *before* placing, so every turn sat a whole step too far — invisible on short city
  steps, miles off on a long highway step). The blue route line was always correct;
  these were both in the maneuver layer on top of it. **Regression-tested** so they
  can't come back: a curved route where crow-flies ≠ along-route (must not skip a
  loop-back turn that's metres away as the crow flies but km along the road), a long
  highway step (the turn lands at the step's start), and imperial vs metric phrasing
- ✅ **Screen stays awake while navigating** (2026-06-21) — turn-by-turn holds
  `FLAG_KEEP_SCREEN_ON` on the activity window so the next turn is always visible on
  a windscreen mount without tapping to wake the phone. Gated by **Settings →
  Navigation → "Keep screen on while navigating"** (default **on**); the flag is
  cleared the instant nav ends, the toggle is turned off, or the map screen leaves
  composition, so the display sleeps normally everywhere else (no battery drain when
  you're not driving)
- ✅ Spoken guidance via AOSP TextToSpeech (engine-selectable) — **tuned for the
  car**: a measured speech rate (0.97) + neutral pitch, and on init it auto-selects
  the **highest-quality offline voice** for the locale (engines often default to a
  low-quality or download-required one), so guidance sounds natural, not robotic.
  Speaks **"Head east on …"** (the initial cardinal is computed from the route's first
  leg and injected, since Google's markup only says "Head toward …"). **Settings →
  Voice** lists installed engines, a **Test voice** button (hear it on your hardware),
  and a **System voice settings** shortcut to install/download a voice. **Spoken
  distances now follow the Units setting (2026-06-27):** the prompt distance was
  hard-coded "In N metres" regardless of Imperial/Metric — now `NavEngine` phrases it
  per the preference ("In 500 feet" / "In 0.2 miles" vs "In 150 metres"), with the
  `imperial` flag threaded from `Units.imperial` through `NavSession.onLocation`.
  **Road abbreviations are expanded for speech (2026-06-27):** the engine spelled out
  "Pkwy"/"St"/"Ave"/"N" letter-by-letter; `VoiceGuide` now expands them whole-word for
  the spoken text only ("Parkway", "Street", "Avenue", "North", "I-80" → "Interstate
  80"), while the on-screen banner keeps Google's compact forms
- 🐞 **Fixed: silent navigation** — on a targetSdk-30+ build, Android package
  visibility hid every TTS engine (`getEngines()` empty, the engine couldn't be
  bound) so guidance was silently dropped. A `<queries>` for `TTS_SERVICE` restores it;
  picking an engine now actually re-inits TTS (it used to be ignored). *(verified
  on-device: audio focus + frames delivered on nav start)*
- ✅ **One-tap voice on a ROM with no TTS** — many degoogled ROMs ship no engine at
  all, so **Settings → Voice → Install eSpeak NG / Install RHVoice** downloads the
  latest open-source engine from **F-Droid** (resolved via its API) and hands it to
  the system installer; once installed it's a normal engine Vela already drives — no
  heavy synth bundled into the app, works on any ROM. A nav-start hint points there if
  you have none. **Pipeline polished 2026-06-19:** the install button now shows an
  inline **spinner while downloading** (not just a persistent map banner), the status
  **auto-dismisses**, and when the direct APK URL 404s — **eSpeak ships per-ABI split
  APKs**, so the single-file path failed silently — it now **falls back to opening the
  F-Droid page** so the install still completes. *(verified end-to-end on-device:
  download → install → eSpeak appears as a Vela engine → speaks)*
- ✅ **Mute voice during nav** — a speaker toggle in the nav bottom bar silences /
  restores spoken guidance on the fly (Google-style), independent of the haptic cues
- ✅ **Speedometer** — a Google-style circular badge (bottom-left during nav) shows
  your current GPS speed in mph or km/h (follows the Units setting)
- ✅ **Scale bar** — a Google-style ⊔ bracket (bottom-left, by the attribution) sized
  to a round distance, with the distance label above it; reads the live
  metres-per-pixel from the map (correct for zoom **and** latitude on Mercator) and
  follows the Units metric/imperial preference (m/km ↔ ft/mi). Updates as you zoom/pan
- ✅ **Pan-away + Re-center** — dragging the map during navigation **detaches the
  follow-camera** so you can look around (it stops snapping back on every GPS fix);
  a **Re-center** button appears and reattaches it, then hides once you're following
  again (Google-style)
- ✅ **Haptic turn cues** — a light "get ready" tick at the pre-turn prompt, then a
  firm **direction-coded** buzz at the turn (left = two long pulses, right = three
  short, straight/other = one), so you can navigate by feel while biking/walking.
  Toggle in Settings → Navigation ("Vibrate on turns", default on)
- ✅ **Google-style maneuver banner** — a large **directional turn arrow** (the
  maneuver-type glyph, not a generic icon), the distance, the instruction with
  inline **highway/exit shields**, a **lane-guidance** strip, and a compact
  **"then <icon> …"** preview of the maneuver after this one — + remaining
  time/distance **and the arrival clock time** ("3.4 mi · 7:42 PM") on the
  bottom bar. The Steps control is icon-only + the ETA column flexes so a long
  "X mi · 7:42 PM" never pushes the **End** button off-screen
- ✅ **Swipe the banner to look ahead** — drag the maneuver banner left/right to
  walk the upcoming steps (Google-style): the card **tracks your finger** and, past
  a threshold, **slides off and the next/previous step slides in** (a pager-style
  flick, not an instant swap); it greys out, shows that step, and the map's marker +
  camera move there; tap it to resume live guidance. The **re-center button also
  appears while previewing a step** and **snaps you back to the current step** (not
  just the camera) — previously it left you parked on a previewed turn
- ✅ **Traffic-coloured nav ETA** — the big remaining-time readout in the nav bar is
  tinted by live traffic (green free-flowing → amber → red), or the normal ink colour
  when there's no live data (offline / a traffic-less route)
- ✅ **Minimisable route chooser** — a drag handle on the directions panel: **swipe
  it down to minimise** (peek the whole route on the map before you commit), swipe up
  or tap to bring it back; a compact **Start** stays reachable while minimised
- ✅ **Directions step list / overview** (before *and* during nav); tap a step to preview that turn on the map — placed at its **true cumulative distance** along the route line (matching the polyline's own length, not the summed step distances), so the previewed spot lands on the actual turn
- 🟡 **Foreground navigation service** — guidance continues with the app
  backgrounded / screen off via an **ongoing notification** (`NavigationService`, a
  `location`-typed FGS; `POST_NOTIFICATIONS` requested on Start). Google-style content:
  the next turn led by distance ("In 500 ft · Turn right onto Main St"), ETA · distance
  remaining, "faster route available" when one is, a dedicated **nav status-bar icon**
  (`ic_nav`, not the launcher logo), an **End** action, and tap-to-reopen. Updates live
  off `NavSession`; best-effort (a blocked FGS start on Android 14/GrapheneOS falls back
  to in-app nav, no crash). **Open quirk:** Start can drop the activity to the launcher
  while the service keeps running — needs on-device repro (ROM/timing-specific)
- 🟡 **Periodic live re-routing** — every ~2 min while underway, re-check
  traffic; if a meaningfully faster route exists, announce it and offer to
  switch (this iteration)
- ⬜ Speed limits / speed-camera + hazard alerts (lane guidance ✅ done above)
- ⬜ Android Auto (needs GMS — likely out of scope)
- ✅ **Arrival / trip summary** — on reaching the destination, a "You've arrived"
  card replaces the nav controls with the trip's total time and distance (and the
  destination name), and a Done button returns to a clean map. **Fixed 2026-06-20
  (test-drive bug): nav was declaring "arrived" up to tens of km early** — Google's
  step distances total a few % short of the route geometry, so `placeManeuvers` placed
  the ARRIVE maneuver at `sum(stepMeters)/polyLength < 1.0` (observed ~15 km short of a
  134 km route's end) and the 25 m arrival trigger fired there. The final maneuver is now
  pinned to the route end; diagnosed + verified on-device by replaying the recorded trip
  (`arriveLoc` now equals the destination). Unit-tested. (Foreground-service + live
  re-route hardening still pending a full on-device drive.)

## Location (degoogled)
- ✅ AOSP `LocationManager` (GPS + NETWORK), no Fused/GMS
- ✅ Last-known seeding for instant map; PSDS slow-fix tip
- ✅ **Google-style location indicator** — two modes. **Browse:** a blue dot (Google's `#4285F4`) with a white ring + a translucent heading cone/beam beneath the dot — points the **device-facing compass** (the `TYPE_ROTATION_VECTOR` sensor via `HeadingProvider`, low-pass smoothed along the shortest arc, OsmAnd-style) so it shows which way you're facing **even standing still**, where the GPS bearing is noise (2026-06-27); falls back to GPS course when the sensor's absent, hidden when neither is available. *(Navigation never uses it — there the heading comes from the matched road; the sensor heading is pushed to state only in browse and only on a real change, so it can't spam recomposition during nav.)* The dot **greys out when the fix is stale** (~12 s, or before the first live fix) and blue again on a fresh fix. **Nav:** a **solid blue arrow puck** (the dot is hidden) that **snaps onto the route line** and faces down the road, so lateral GPS jitter doesn't make the marker jump. The snap is **honest, not a lie**: it engages only when you're genuinely following the road — within ~22 m (≈ a road width + GPS error) **and** heading the road's way (±55°); a missed exit / off-road / wrong-way fails the gate and shows the **real** position so you can see the divergence (then the off-route reroute kicks in), Google-style *(arrow puck + heading-gated snap added 2026-06-20 from test-drive feedback — "we need an arrow", "don't snap so it's out of touch with reality"; verified on-device via trip replay)*. The puck rides a **Google-style forward-progress motion model** (added 2026-06-21) instead of teleporting to each raw fix: a per-frame ticker glides it **monotonically forward along the route** with **dead reckoning** (between the ~1 Hz fixes the position keeps advancing at the last known speed, so it never stalls mid-second), **eased** progress (it never jitters backward) and **smoothed heading** (rotates through bends instead of snapping at every vertex). The on-route match is **forward-only and monotonic, modelled on OsmAnd's `RoutingHelper`** (2026-06-21): once engaged it searches a **bounded look-ahead AHEAD of the current progress only** (`snapToRouteWindowed`, from `targetM − 25 m` to `targetM + speed·8 s` clamped 150–600 m) — never behind, never the whole route — so a route that **passes near itself** (switchback / cloverleaf / parallel return leg, or an ordinary road that runs close to itself) physically can't pull the puck onto a far or earlier leg. When nothing's ahead within tolerance (a GPS spike or a real off-route) it **HOLDS and dead-reckons forward instead of re-snapping globally** — the old global fall-back is exactly what teleported the camera to "a random spot along the route" — and only a sustained run of misses (~6 fixes) disengages to re-acquire, while `NavEngine`'s own off-route logic drives the reroute. The one global search left is the **initial acquisition** at nav-start / just after a reroute. *(Evolution: the first 2026-06-21 pass windowed the search but still fell back to a global nearest-point on any off-route blip and could re-anchor backward — replay was "better but still jumps to a random spot"; the forward-only matcher closes those two holes at the source.)* **The follow-camera tracks this same smoothed point** — not the raw fix — so the puck and the map move as one and the view no longer lurches to a far spot when nearest-point is briefly ambiguous; **off-route it now holds the last road-aligned heading instead of spinning to the raw GPS bearing** (which jittered and could point the map the wrong way on a brief off-route blip). The **traversed-grey split rides the puck's DRAWN position (`progressM`)** — exactly where the arrow renders, not the `targetM` it's easing toward — and is drawn as a **hard `step` at the EXACT fraction** (2026-06-21), not quantized to a 256-sample grid: the old sampling left the grey/colour boundary up to route-length/256 m (~80 m on a long route) *ahead* of the arrow, which read as a soft "gradient" before driven areas; now it's a clean solid cut dead under the puck, no feathering ("we either drove it or we didn't"). **The cut is also refreshed in the 60 fps motion ticker (2026-06-22, throttled to ~3 m of progress)**, not just at recomposition — otherwise it lagged the per-frame arrow at speed and left a sliver of coloured route just *behind* the arrow. (Still can't land on the wrong leg.) *(Forward-only matcher unverified on a live drive at ship time — to be confirmed by replaying a recorded trip on the new build, since mock GPS is too clean to reproduce the wrong-leg snap.)* **Heading & speed are synthesised from movement when a GPS fix doesn't carry them** (cold start, just-started-moving, some chipsets/ROMs) — gated on real movement so a standstill's jitter can't spin the marker — so the arrow always points the right way and dead-reckoning always has a speed. **A single-fix speed SPIKE is rejected** (2026-06-21) — a GPS glitch ("going 35, hops to 157 mph"); a car can't gain >15 m/s between fixes, so the prior speed is kept, in **both the live and the replay paths** (recorded traces carry the raw glitches too), so the speedo, dead-reckon and zoom don't lurch on a bad reading. **Position OUTLIERS are rejected the same way** (`sanePosition`, 2026-06-21): a coarse **NETWORK / multipath fix** that leaps farther than is physically plausible for the elapsed time — the network provider interleaving with GPS is the "every ~8 s the dot + distance + mph jump to a crazy number" jitter — is dropped, and the position runs through a **speed-adaptive low-pass** (2026-06-22) — heavy smoothing at low speed so parked/idle GPS jitter barely nudges the dot, easing to a 1:1 follow by ~10 m/s where real movement dominates the noise. Google smooths position this way; **OsmAnd notably does NOT** (its dot hops — it only Kalman-smooths the *heading* from sensors), so this is actually steadier than OsmAnd at idle. (Replaced a binary standstill-hold whose hard speed-cliff the GPS speed-noise kept tripping.) Live + replay. *(2026-06-22 fix: the **first fix of a session anchors** and a leap that **persists ≥2 fixes re-anchors** — without it, a replay that starts away from your live position rejected every fix as an outlier vs the stale start point and the dot never moved ("replay thinks I'm stationary").)* *(motion model + camera-locked-to-puck + derived heading/speed; **verified on-device 2026-06-21** via a mock-GPS drive of a real 14.5-mi Davis→Sacramento route: arrow puck tracking, solid grey-behind/blue-ahead route, heading-up camera locked to the puck through the I-80/US-50 interchange with no snap, and the banner/HUD counting down monotonically — `remaining` 14.5→11.9 mi with 0 monotonicity violations across 75 nav updates)*
- ⬜ Compass heading when stationary
- ⬜ Optional BeaconDB WiFi positioning for faster coarse fix

## Offline
- ✅ **Offline basemap region downloads** — **Settings → Offline maps → "Download the
  area you're viewing"** saves the last on-screen area's tiles/glyphs/sprites (via
  MapLibre's built-in offline store) so it renders later with **no network**; the
  same section manages/deletes saved areas. (Moved off the map FAB stack to declutter;
  the viewport is captured on camera-idle.) Open tiles, no Google, no backend.
- ✅ **Offline search** — downloading a map area also pulls its POIs from
  **OpenStreetMap/Overpass** (keyless) into an on-device SQLite index; when Google
  search can't be reached, search falls back to that index ("Offline results"). The
  index now **keeps the POI detail** OSM carries — **address, phone, website and
  opening hours** (from the `addr:*` / `phone` / `website` / `opening_hours` tags) —
  so an offline place sheet isn't just a name on a pin (sparser than Google, but real)
- ⬜ Offline routing (embedded Valhalla / routing graph) — the heavy native lift
- ⬜ Region downloads as portable PMTiles + historical traffic

## Platform & distribution
- ✅ No Google Play Services anywhere
- ✅ Material 3 Compose UI; Hilt DI; R8 release builds
- ✅ Public GitHub repo + local mirror + offline bundle
- ✅ CI (GitHub Actions): every push to main builds + tests + signs the APK and publishes a **normal versioned release** (`v0.2.<run>`, versionCode `2000+run`), kept as a revision history — Obtainium tracks the latest with zero config
- ✅ **Opt-in diagnostics / debug export** (Settings → Diagnostics, **off by default**) — a local-only event log (searches, computed routes, parser "drift", nav start/reroute/arrival) that the user can **Export debug session** to a JSON bundle and hand to a developer via the system share sheet. **Never auto-uploaded** — user-initiated and user-routed; turning it off wipes the log; in-memory only (capped at 300 events). The no-backend half of the telemetry plan; `core/diag/DiagLog` + `app/diag/DiagExporter`, consent dialog on enable, `PRIVACY.md` updated
- ✅ **Crash capture** — an uncaught-exception handler (`app/diag/CrashCatcher`, installed in `VelaApp`) **persists the stack trace + breadcrumbs + app/device versions to disk**, surviving the restart, so after a crash the user can **Export crash report** from Settings → Diagnostics (the fix for "nav crashed but the phone wasn't tethered, no logcat"). Captured even with diagnostics off (a stack trace is benign + local); never auto-sent; chains to the system handler so normal crash behaviour is unchanged
- ✅ **Trip recording + replay** (Settings → "Save my trips", **off by default, separate opt-in** — more invasive than diagnostics since it's your exact routes; never uploaded) — records each navigation's GPS trace to a local file (`app/replay/TripStore`), so a drive can be **replayed on the map at 3×** to test turn-by-turn **without driving it again** (`LocationProvider.replay` feeds the recorded fixes through the same nav/camera/dot pipeline). A replay shows a **"Stop replay"** button on the map and, when it finishes (or is stopped), **live GPS resumes automatically** — earlier the live feed never came back until the app was restarted. The trip is **saved the instant you arrive**, not only when you tap "Done", so it survives if you leave the arrival card. Recorded trips are listed in Settings (newest first) with their **recorded date + point count** and **Replay / Share / Delete**; the list refreshes on entry and shows an empty-state hint while recording is on but nothing's captured yet. **Share** exports the raw CSV trace via the system sheet (same FileProvider as the diag/saved-places export) — so a drive can be pulled off a **release** build and handed over for replay/debug **without a dev build** (this is how the 2026-06-20 early-arrival bug was diagnosed). *(Bigger picture — opt-in **encrypted** upload of traces for remote debugging is the natural next step as the tester pool grows; see ROADMAP's opt-in-telemetry big bet. Location traces are the most sensitive data the app touches, so: explicit per-share consent, client-side encryption to a key only the dev holds, minimal/redactable payload, self-hosted or pre-signed endpoint, auto-expiry.)* The **first-run prompt offers the two opt-ins separately** (diagnostics default-on, trip-saving default-off). Replay now **auto-routes to the trip's destination** and starts turn-by-turn for you (best-effort — the trace still plays if routing fails — and the auto-started nav is torn down when the replay ends), so you no longer have to start nav manually first. *(Pending on-device verification.)*
- ✅ Settings shows the installed app version (name + build code)
- ⬜ F-Droid submission + reproducible build
- ⬜ UnifiedPush for delay alerts (no FCM)
- ⬜ ACRA / self-hosted crash reporting

## Resilience / maintainability
- ✅ **Remotely-updatable scraper calibration** — `calibration.json` at the repo
  root holds the `pb` templates, endpoint URLs **and (phase 2) the search parser's
  positional field-index paths** (`name`, `address`, `rating`, `photos`, … as
  `[i,j,…]` arrays). The app fetches it at launch and adopts a newer `version`
  (host-allowlisted to google.com) **without an app update** — so most Google
  drift (a moved `pb`, endpoint, or field index) is now a one-line edit + version
  bump, not a release.
- ✅ **Signed update channel** — the bundle is **ECDSA-P256/SHA-256 signed**
  (`calibration.json.sig`); the app verifies it against a **pinned public key**
  before adopting, so a repo/CDN compromise can't push config — or code — to
  devices (private key kept out of the repo; `scripts/sign-calibration.sh` re-signs;
  `BundleSignature.verify` is unit-tested). *(verified on-device)*
- ✅ **Pushed notices** — a `notices` array in the signed bundle surfaces dismissable
  alerts on the bare map ("search is down, fix coming") with **no app update**;
  dismissals persist per-id. *(verified on-device end-to-end)*
- ✅ **Remote parse logic** (phase 3) — a signed `transformsJs` bundle runs in a **Rhino
  sandbox** (interpreted, no Java access), so a *response-shape* change can be hot-fixed
  too — not just a moved field. Two search hooks (`parseSearch` full re-parse /
  `transformPlaces` post-process) over a flat place-JSON contract; **compiled Kotlin is
  always the fallback**. *(verified on-device: a pushed transform marked the first
  result, then cleared; engine + sandbox + contract unit-tested)*

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect
  periodic re-calibration (paths documented in the README). Pb/endpoint drift is
  now a remote `calibration.json` fix (above); index-path drift still needs a build.
- EU/EEA consent wall: pre-seeds Google's `SOCS`/`CONSENT` cookies in the shared
  jar so a cookieless session isn't bounced to `consent.google.com` (best-effort,
  US-verified only; the full form-POST handshake is the follow-up if it persists).
