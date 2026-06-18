# Vela — feature list

Status legend: ✅ done · 🟡 partial / in progress · ⬜ planned

## Map & rendering
- ✅ MapLibre Native vector rendering (Compose-wrapped)
- ✅ Detailed open basemap: bundled OpenFreeMap Liberty + injected house numbers at z17; OpenMapTiles vector source pinned to OpenFreeMap's **versioned** tile path (the un-versioned path serves empty tiles — that was a blank-map bug)
- ✅ Route line, **tappable Google-style search-result pins**, location dot as GeoJSON layers
- ✅ Heading-up, tilted navigation camera; fit-route-to-screen on preview; recenter FAB
- ✅ Compass kept clear of the status bar (inset-aware margins)
- ✅ Tap a labelled POI **or a search-result pin** to open it; camera frames all results after a search
- ✅ **Long-press the map** → drop a pin, reverse-geocode it to an address (Nominatim/OSM, keyless), then get Directions — works even where no building is drawn
- ✅ Keyless **OpenFreeMap Liberty** basemap (active, loaded by URL — the setup that renders on-device, no key): **Google-style POI markers + category-coloured labels**, a **clean Google-style road treatment** — white road fills on light-grey land with the **casings faded out** (minor-road casing == the land, so streets are crisp white lines with **no outline**), soft-yellow motorways, **neutralised landuse** (no tan residential/commercial blobs), and **flattened fill-patterns** (Liberty's fern-hatch wetlands + dotted pedestrian plazas → flat fills, like Google) — plus light/dark recolour, all at **runtime** (tuned live in a MapLibre GL JS harness against Google, on-device-verified light + dark)
- 🟡 **MapTiler Streets** path stays wired but off (`USE_MAPTILER=true` to enable, needs the key). The bundled-style **Roboto font** is parked — its vector tiles wouldn't load via `fromJson` on-device (loading Liberty by URL is what works)
- ✅ **In-app Light / Dark / Follow-system switch** (Settings → Appearance) — sets Vela's theme **independently of the phone**, so you can run the app dark without flipping the whole OS (the whole app + the map recolour live off one preference, `AppTheme`/`isAppInDarkTheme`; persisted). Dark mode recolours **every** landuse/landcover fill (commercial, school, retail, …), not just a hardcoded few, so no light/cream patches break the night palette (verified on-device)
- ✅ **Google-style POI markers** — category-coloured circles with white Material Icons glyphs (food=orange, shop=blue, park=green, health=red, transit=blue, …), generated at runtime over a bundled Material Icons font, sized to read like Google's (`iconSize` 0.8); in light mode the POI **label text is coloured by category too** (like Google). **Density tuned for parity:** Liberty's always-on `poi_transit` layer (bus stops at every zoom = clutter) is pushed to **z16+** like Google, while the next business tier (`poi_r7`) is pulled down to **z15** so more shops/restaurants show; MapLibre's label collision keeps it tidy
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
- ✅ Search-result rows show **5-star rating**, colour-coded open/closed status, and the **full address (city/state/ZIP)** to disambiguate similar names / lookalike residential addresses — **sized for legibility** (name at titleMedium, the rating/category/address lines bumped up from the cramped small text)
- ✅ Place sheet (**Google-styled**): high-contrast white-on-dark / black-on-white name + status time (fixed palette, not washed-out by Material You), **5-star rating visual**, **swipe-down to dismiss**, status with the **word colour-coded** (Open green / Closed red) and the time in plain ink, price, **full address with a copy button**, **collapsible weekly hours** (today first, expand for the week)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted)
- ✅ **Full-screen search page** (Google-style) — focusing the search box opens an opaque page with saved + recent searches over the map (back arrow / back gesture closes it); running a search drops back to the map with the results list + red pins
- ✅ **Autocomplete / suggestions as you type** — after a short debounce, the search page shows live **place matches** (name + address) to tap, like Google; tapping one opens its sheet directly. Reuses the calibrated search endpoint (no separate suggest RPC); a stale response is dropped if the query moved on. *(verified on-device: "starb" → Starbucks locations)*
- ✅ Clear-search (X) button; **results list opens tall (~half screen) and expands to nearly full-screen** (~94%) like Google's results page — drag the handle or tap the chevron up to expand, down to shrink, down again to hide it and browse the map (pins stay, a chip re-opens it); back gesture also hides it
- ✅ **Result filters** — chips in the results header: **"Open now"** (places open right now) and **"4.0★"** (rating ≥ 4.0); they stack and the count updates live
- ✅ **Back gesture peels one layer at a time** (steps → navigation → route preview → place sheet → results list) instead of closing the app — only the bare map exits
- ✅ **Full reviews** — the place sheet's **Reviews tab** lists real reviews (author + photo, star rating, relative date, text) pulled from Google's keyless `listentitiesreviews` endpoint by feature id
- ✅ **Tabbed place sheet** (Google-style): **Reviews** (rating summary + featured highlight + full list) and **About** (Service options, Highlights, Accessibility, … from Google's attributes). Layout order: **photos (hero, at the top) → info → hours → action row → tabs** (photos lead so they're visible at the peek height / in landscape)
- ✅ Travel mode lives in the action row: a **Directions** button opens a Drive/**Transit**/Walk/Bike chooser ("how are you getting there?"), then previews the route (ETA + Start for drive/walk/bike) or a **transit results board** (departure times + line pills)
- ✅ Place sheet **peeks** (~56% screen) so the business info isn't immediately full-screen and the map stays visible above it; **drag the handle up to expand** (~92%, for the reviews), down to shrink, down again to dismiss. The body scrolls, so a tall place (hours + tabs) is fully reachable at either height
- ⬜ Popular times; "hours updated N ago" (both place-RPC-only, absent from the search response); Updates/posts tab
- ℹ️ Reviews are the **top ~20** — the `listentitiesreviews` endpoint serves a fixed page (offset ignored) and deeper paging is behind an obfuscated continuation token; not chased (fragility vs. value)
- ✅ Place actions in a **Google-style quick-action row** (circular icon + label): **Call** (dialer), Website, Save, **Share menu (Google Maps link / coordinates / address)**
- ✅ **Place photos** — business photo strip **leads the sheet as a hero** (horizontally scrollable, at the top so it's visible at the peek height / in landscape); **tap a photo to open a full-screen, swipeable gallery** with a counter. Opens with the search response's **preview (~10)** at `[1][105][0][1][0][i][6][0]`, then swaps in the **full gallery (~30–40)** — including for places with **no** search preview (e.g. SpeeDee/Midas-style service shops), which used to show nothing.
- ✅ **Full gallery via a hidden WebView** (`WebPhotoFetcher`) — the gallery RPC (`batchexecute` `hspqX` / `/MapsPhotoService.ListEntityPhotos`) serves the user photos **only to a real browser engine**. A plain HTTP client — even with perfect headers + consent cookies — gets a degraded **Street-View-only** reply: bot-detection at the **TLS/fingerprint** level (verified on-device — OkHttp gets a 162 KB token-less "lite" `/maps` page). So Vela runs a **hidden Android WebView** (real Chromium) that loads `maps.google.com` as an **anonymous, no-login** session — exactly like a logged-out browser, which *does* show the photos — and does a same-origin `fetch` to the RPC, handing the raw response back over a JS bridge. **Keyless** (no API key, no account). Created **lazily** (only when a place's photos are wanted), strictly best-effort (failure → keep the preview). **On-device verified 2026-06-17: 31 photos for SpeeDee-Midas, Davis.** Tradeoff: the WebView runs Google's JS (a fingerprinting step for a degoogled app — the opt-in cost of richer photos), OkHttp fallback kept. Gotchas baked in: **desktop UA** (a mobile UA makes Google deep-link to `intent://` the native app), **block non-http(s) redirects**, and **`Handler`, not `View.postDelayed`** (a headless WebView never attaches to a window, so View timers never fire).
- ✅ Category quick-chips (Restaurants/Coffee/Gas/…) → one-tap search
- ✅ "Search this area" — re-search after panning the map
- ✅ Filter: **open now** + **rating ≥ 4.0** (chips in the results header, stackable); ⬜ price still to add
- ✅ Saved / favourite places (star from the place sheet) — reopening a saved place **enriches it via search** so photos, rating and reviews load (saved places carry no feature id of their own)
- ⬜ **Export / import saved places** (portable, user-savable bookmarks — planned)
- ⬜ Overture/OSM POIs as a fallback source

## Routing & traffic
- ✅ Driving directions with **real traffic-aware ETA** (live `duration_in_traffic`)
- ✅ **Route-line traffic colour** — the drawn route tints blue → amber → red as the
  live traffic-aware time runs over the typical time (overall congestion; per-segment
  isn't reliably in the response and our line is OSRM geometry, so it's whole-route)
- ✅ Alternative routes returned
- ✅ Turn-by-turn maneuver list (type + distance from Google's step markup)
- ✅ **Lane guidance** — Google's lane hints ("Use the right 2 lanes to turn") are
  pulled out of the step markup into their own field, shown highlighted in the step
  list and the nav banner (the main instruction stays clean: "Turn right onto …")
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
- ⬜ Departure/arrival time selection; avoid tolls/highways
- ⬜ Self-hosted routing backend (replace the FOSSGIS community server)

## Navigation
- ✅ Turn-by-turn engine (step advancement, off-route detection, reroute) —
  pure/Android-free, **unit-tested** (arrival-requires-final-maneuver, reroute
  fires once per off-route transition not per fix, off-route clears on return)
- ✅ Spoken guidance via AOSP TextToSpeech (engine-selectable)
- ✅ **Haptic turn cues** — a light "get ready" tick at the pre-turn prompt, then a
  firm **direction-coded** buzz at the turn (left = two long pulses, right = three
  short, straight/other = one), so you can navigate by feel while biking/walking.
  Toggle in Settings → Navigation ("Vibrate on turns", default on)
- ✅ Maneuver banner + remaining time/distance
- ✅ **Directions step list / overview** (before *and* during nav); tap a step to preview that turn on the map
- 🟡 **Foreground navigation service** — guidance continues with the app
  backgrounded / screen off, persistent notification (this iteration)
- 🟡 **Periodic live re-routing** — every ~2 min while underway, re-check
  traffic; if a meaningfully faster route exists, announce it and offer to
  switch (this iteration)
- ⬜ Lane guidance / speed limits / speed-camera + hazard alerts
- ⬜ Android Auto (needs GMS — likely out of scope)
- ✅ **Arrival / trip summary** — on reaching the destination, a "You've arrived"
  card replaces the nav controls with the trip's total time and distance (and the
  destination name), and a Done button returns to a clean map. (Real-drive
  hardening of the foreground service + live re-route is still pending an
  on-device test run.)

## Location (degoogled)
- ✅ AOSP `LocationManager` (GPS + NETWORK), no Fused/GMS
- ✅ Last-known seeding for instant map; PSDS slow-fix tip
- ✅ Heading arrow on the location dot (from GPS bearing)
- ⬜ Compass heading when stationary
- ⬜ Optional BeaconDB WiFi positioning for faster coarse fix

## Offline
- ✅ **Offline basemap region downloads** — the download button on the map saves
  the visible area's tiles/glyphs/sprites (via MapLibre's built-in offline store)
  so it renders later with **no network**; manage/delete saved areas in Settings →
  Offline maps. Open tiles, no Google, no backend.
- ✅ **Offline search** — downloading a map area also pulls its POIs from
  **OpenStreetMap/Overpass** (keyless) into an on-device SQLite index; when Google
  search can't be reached, search falls back to that index ("Offline results")
- ⬜ Offline routing (embedded Valhalla / routing graph) — the heavy native lift
- ⬜ Region downloads as portable PMTiles + historical traffic

## Platform & distribution
- ✅ No Google Play Services anywhere
- ✅ Material 3 Compose UI; Hilt DI; R8 release builds
- ✅ Public GitHub repo + local mirror + offline bundle
- ✅ CI (GitHub Actions): every push to main builds + tests + signs the APK and publishes a **normal versioned release** (`v0.1.<run>`), kept as a revision history — Obtainium tracks the latest with zero config
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
  bump, not a release. Only a change needing genuinely new parsing *logic* still
  ships as a build.

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect
  periodic re-calibration (paths documented in the README). Pb/endpoint drift is
  now a remote `calibration.json` fix (above); index-path drift still needs a build.
- EU/EEA consent wall: pre-seeds Google's `SOCS`/`CONSENT` cookies in the shared
  jar so a cookieless session isn't bounced to `consent.google.com` (best-effort,
  US-verified only; the full form-POST handshake is the follow-up if it persists).
