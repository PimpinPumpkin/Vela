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
- ✅ Keyless **OpenFreeMap Liberty** basemap (active, loaded by URL — the setup that renders on-device, no key): **Google-style POI markers + category-coloured labels**, a Google-like **road hierarchy** (gold motorways, white arterials, casing that lightens down the hierarchy so minor roads recede), and light/dark recolour all applied at **runtime**
- 🟡 **MapTiler Streets** path stays wired but off (`USE_MAPTILER=true` to enable, needs the key). The bundled-style **Roboto font** is parked — its vector tiles wouldn't load via `fromJson` on-device (loading Liberty by URL is what works)
- ✅ **Dark / light map** follows the system theme (keyless recolour; or MapTiler dark if enabled)
- ✅ **Google-style POI markers** — category-coloured circles with white Material Icons glyphs (food=orange, shop=blue, park=green, health=red, transit=blue, …), generated at runtime over a bundled Material Icons font; in light mode the POI **label text is coloured by category too** (like Google); minor POI tiers decluttered to higher zoom
- 🟡 Self-hosted PMTiles — the no-key, no-quota Google-look path — remains for later
- ⬜ Protomaps "Google-Maps-ify" style (road hierarchy, hillshade, POI icons)
- ⬜ Satellite / terrain layers
- ⬜ Map rotation/tilt + heading-up mode during nav

## Search & POIs (live Google data)
- ✅ Place search — name, category, **full address (street, city, state, ZIP)**, rating, review count, coordinates
- ✅ Searching a **specific/far address** resolves to that single geocoded location (handles the response's single-result shape, not just the POI list — fixes the old "calibration error" on far addresses); genuinely-empty searches now show "no results" instead of an error
- ✅ Search-result rows show **5-star rating**, colour-coded open/closed status, and the **full address (city/state/ZIP)** to disambiguate similar names / lookalike residential addresses
- ✅ Place sheet (**Google-styled**): high-contrast white-on-dark / black-on-white name + status time (fixed palette, not washed-out by Material You), **5-star rating visual**, **swipe-down to dismiss**, status with the **word colour-coded** (Open green / Closed red) and the time in plain ink, price, **full address with a copy button**, **collapsible weekly hours** (today first, expand for the week)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted)
- ✅ **Full-screen search page** (Google-style) — focusing the search box opens an opaque page with saved + recent searches over the map (back arrow / back gesture closes it); running a search drops back to the map with the results list + red pins
- ✅ Clear-search (X) button; dismiss the results list (swipe-up / back gesture) to browse the map — pins stay, a chip re-opens it
- ✅ **Back gesture peels one layer at a time** (steps → navigation → route preview → place sheet → results list) instead of closing the app — only the bare map exits
- ✅ **Full reviews** — the place sheet's **Reviews tab** lists real reviews (author + photo, star rating, relative date, text) pulled from Google's keyless `listentitiesreviews` endpoint by feature id
- ✅ **Tabbed place sheet** (Google-style): **Reviews** (rating summary + featured highlight + full list) and **About** (Service options, Highlights, Accessibility, … from Google's attributes). Layout order: info → action row → hours → photos → tabs
- ✅ Travel mode lives in the action row: a **Directions** button opens a Drive/Walk/Bike chooser ("how are you getting there?"), then previews the route (ETA + Start)
- ⬜ Popular times; "hours updated N ago" (both place-RPC-only, absent from the search response); Updates/posts tab
- ℹ️ Reviews are the **top ~20** — the `listentitiesreviews` endpoint serves a fixed page (offset ignored) and deeper paging is behind an obfuscated continuation token; not chased (fragility vs. value)
- ✅ Place actions in a **Google-style quick-action row** (circular icon + label): **Call** (dialer), Website, Save, **Share menu (Google Maps link / coordinates / address)**
- ✅ **Place photos** — business photo strip (horizontally scrollable); **tap a photo to open a full-screen, swipeable gallery** with a counter. (~10 photos from the search response's preview group; the full categorized photo set is behind an obfuscated endpoint, not pulled.)
- ✅ Category quick-chips (Restaurants/Coffee/Gas/…) → one-tap search
- ✅ "Search this area" — re-search after panning the map
- ⬜ Filters (open now, rating, price)
- ✅ Saved / favourite places (star from the place sheet)
- ⬜ **Export / import saved places** (portable, user-savable bookmarks — planned)
- ⬜ Overture/OSM POIs as a fallback source

## Routing & traffic
- ✅ Driving directions with **real traffic-aware ETA** (live `duration_in_traffic`)
- ✅ Alternative routes returned
- ✅ Turn-by-turn maneuver list (type + distance from Google's step markup)
- ✅ Route geometry via open router — **per-mode** FOSSGIS OSRM backends
  (`routed-car`/`routed-bike`/`routed-foot`), so drive/walk/bike each follow the
  correct network; Valhalla later
- 🟡 **Live route re-check while navigating** — periodically re-query traffic and
  offer a faster route if one appears (see Navigation below)
- ✅ Walking + cycling modes (drive/walk/bike) — each with its **own** path-following
  line, not a car route reused; transit response shape is a separate TODO
- ⬜ Departure/arrival time selection; avoid tolls/highways
- ⬜ Self-hosted routing backend (replace the FOSSGIS community server)

## Navigation
- ✅ Turn-by-turn engine (step advancement, off-route detection, reroute)
- ✅ Spoken guidance via AOSP TextToSpeech (engine-selectable)
- ✅ Maneuver banner + remaining time/distance
- ✅ **Directions step list / overview** (before *and* during nav); tap a step to preview that turn on the map
- 🟡 **Foreground navigation service** — guidance continues with the app
  backgrounded / screen off, persistent notification (this iteration)
- 🟡 **Periodic live re-routing** — every ~2 min while underway, re-check
  traffic; if a meaningfully faster route exists, announce it and offer to
  switch (this iteration)
- ⬜ Lane guidance / speed limits / speed-camera + hazard alerts
- ⬜ Android Auto (needs GMS — likely out of scope)
- ⬜ Trip overview / arrival summary

## Location (degoogled)
- ✅ AOSP `LocationManager` (GPS + NETWORK), no Fused/GMS
- ✅ Last-known seeding for instant map; PSDS slow-fix tip
- ✅ Heading arrow on the location dot (from GPS bearing)
- ⬜ Compass heading when stationary
- ⬜ Optional BeaconDB WiFi positioning for faster coarse fix

## Offline (v2)
- ⬜ Region downloads: PMTiles + routing graph + POI subset + historical traffic
- ⬜ Embedded Valhalla for offline routing
- ⬜ Offline search (SQLite FTS)

## Platform & distribution
- ✅ No Google Play Services anywhere
- ✅ Material 3 Compose UI; Hilt DI; R8 release builds
- ✅ Public GitHub repo + local mirror + offline bundle
- ✅ CI (GitHub Actions): every push to main builds + tests + signs the APK and publishes a **normal versioned release** (`v0.1.<run>`), kept as a revision history — Obtainium tracks the latest with zero config
- ✅ Settings shows the installed app version (name + build code)
- ⬜ F-Droid submission + reproducible build
- ⬜ UnifiedPush for delay alerts (no FCM)
- ⬜ ACRA / self-hosted crash reporting

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect
  periodic re-calibration (paths documented in the README).
- EU consent wall for cookieless sessions is unhandled.
