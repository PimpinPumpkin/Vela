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
- ✅ **Dark / light map** follows the system theme (keyless recolour; or MapTiler dark if enabled). Dark mode recolours **every** landuse/landcover fill (commercial, school, retail, …), not just a hardcoded few, so no light/cream patches break the night palette (verified on-device)
- ✅ **Google-style POI markers** — category-coloured circles with white Material Icons glyphs (food=orange, shop=blue, park=green, health=red, transit=blue, …), generated at runtime over a bundled Material Icons font; in light mode the POI **label text is coloured by category too** (like Google); minor POI tiers decluttered to higher zoom
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
- ✅ Search-result rows show **5-star rating**, colour-coded open/closed status, and the **full address (city/state/ZIP)** to disambiguate similar names / lookalike residential addresses
- ✅ Place sheet (**Google-styled**): high-contrast white-on-dark / black-on-white name + status time (fixed palette, not washed-out by Material You), **5-star rating visual**, **swipe-down to dismiss**, status with the **word colour-coded** (Open green / Closed red) and the time in plain ink, price, **full address with a copy button**, **collapsible weekly hours** (today first, expand for the week)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted)
- ✅ **Full-screen search page** (Google-style) — focusing the search box opens an opaque page with saved + recent searches over the map (back arrow / back gesture closes it); running a search drops back to the map with the results list + red pins
- ✅ Clear-search (X) button; **draggable results list** — swipe the handle **up to expand** it toward the top (~⅔ screen), **down to shrink**, and down again to hide it and browse the map (pins stay, a chip re-opens it); back gesture also hides it
- ✅ **Back gesture peels one layer at a time** (steps → navigation → route preview → place sheet → results list) instead of closing the app — only the bare map exits
- ✅ **Full reviews** — the place sheet's **Reviews tab** lists real reviews (author + photo, star rating, relative date, text) pulled from Google's keyless `listentitiesreviews` endpoint by feature id
- ✅ **Tabbed place sheet** (Google-style): **Reviews** (rating summary + featured highlight + full list) and **About** (Service options, Highlights, Accessibility, … from Google's attributes). Layout order: **photos (hero, at the top) → info → hours → action row → tabs** (photos lead so they're visible at the peek height / in landscape)
- ✅ Travel mode lives in the action row: a **Directions** button opens a Drive/Walk/Bike chooser ("how are you getting there?"), then previews the route (ETA + Start)
- ✅ Place sheet **peeks** (~56% screen) so the business info isn't immediately full-screen and the map stays visible above it; **drag the handle up to expand** (~92%, for the reviews), down to shrink, down again to dismiss. The body scrolls, so a tall place (hours + tabs) is fully reachable at either height
- ⬜ Popular times; "hours updated N ago" (both place-RPC-only, absent from the search response); Updates/posts tab
- ℹ️ Reviews are the **top ~20** — the `listentitiesreviews` endpoint serves a fixed page (offset ignored) and deeper paging is behind an obfuscated continuation token; not chased (fragility vs. value)
- ✅ Place actions in a **Google-style quick-action row** (circular icon + label): **Call** (dialer), Website, Save, **Share menu (Google Maps link / coordinates / address)**
- ✅ **Place photos** — business photo strip **leads the sheet as a hero** (horizontally scrollable, at the top so it's visible at the peek height / in landscape); **tap a photo to open a full-screen, swipeable gallery** with a counter. Photos are the search response's **preview (~10)** at `[1][105][0][1][0][i][6][0]`.
- ⚠️ **Full ~40+ gallery is sign-in-gated, so not reachable keyless.** The `batchexecute` `hspqX` RPC (`/MapsPhotoService.ListEntityPhotos`) returns the full user-photo gallery **only for a logged-in Google session**; on Vela's anonymous/keyless session it returns just **Street View thumbnails** (`streetviewpixels-pa.googleapis.com`), which `PhotosParser` filters out (keeping only `googleusercontent` user photos → empty here → the preview stays). So the `hspqX` path is wired + remotely-calibratable but yields nothing extra keyless. **Consequence:** a place with no search preview (some service businesses, e.g. auto shops) shows no photos on the keyless path — and that's a **hard ceiling**. (The full gallery was mistakenly "verified" in a *logged-in* browser; corrected 2026-06-17.) The place-page-scrape idea was **chased and ruled out**: the anonymous place page's "googleusercontent" strings are a JS hostname-allowlist + a URL-builder template, **not** photo data (zero `/p/AF1Qip…` ids); and `hspqX` stays Street-View-only even after warming with a place-page visit. So the user gallery is genuinely **sign-in-gated** — unreachable without a Google login, which we won't add. **Keyless photo ceiling = the search preview.**
- ✅ Category quick-chips (Restaurants/Coffee/Gas/…) → one-tap search
- ✅ "Search this area" — re-search after panning the map
- ⬜ Filters (open now, rating, price)
- ✅ Saved / favourite places (star from the place sheet)
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
- 🟡 **Public transit** directions (lines + schedules/times) — **data source pinned
  (2026-06-17):** unlike the photos RPC, transit is **not** a clean endpoint. The
  mode-3 (`!3e3`) directions GET returns an empty envelope; the web UI renders the
  full transit result (route options, departure/arrival times, line names + fares,
  "every N min") from **`APP_INITIALIZATION_STATE` embedded in the `/maps/dir/…/data=…!3e3`
  page HTML** (~180 KB, deeply nested). So wiring it means an HTML-scrape + a deep
  positional parse (heaviest scraper task) — deliberately **deferred until it can be
  verified on a device**, since an unverified transit parser could show wrong times.
  The path is now known, not a mystery.
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
