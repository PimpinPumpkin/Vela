# Vela — feature list

Status legend: ✅ done · 🟡 partial / in progress · ⬜ planned

## Map & rendering
- ✅ MapLibre Native vector rendering (Compose-wrapped)
- ✅ Detailed open basemap (OpenFreeMap Liberty default + injected house numbers at z17; Positron/Bright/demo/Protomaps options)
- ✅ Route line, **tappable Google-style search-result pins**, location dot as GeoJSON layers
- ✅ Heading-up, tilted navigation camera; fit-route-to-screen on preview; recenter FAB
- ✅ Tap a labelled POI **or a search-result pin** to open it; camera frames all results after a search
- 🟡 More polished cartography (OpenFreeMap is functional but not Google-pretty; MapTiler-with-key or a custom style is the upgrade)
- ⬜ Protomaps "Google-Maps-ify" style (road hierarchy, 3D buildings, hillshade, POI icons)
- ⬜ Light/dark/satellite style switching beyond the basics
- ⬜ Map rotation/tilt + heading-up mode during nav

## Search & POIs (live Google data)
- ✅ Place search — name, category, address, rating, review count, coordinates
- ✅ Search-result rows show **5-star rating** + colour-coded open/closed status
- ✅ Place sheet: **5-star rating visual**, **swipe-down to dismiss**, colour-coded open/closed status (green/amber/red), price, **collapsible weekly hours** (today first, expand for the week)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted, shown on search focus)
- ⬜ Popular times + individual review text (sign-in-gated place RPC)
- ✅ Place actions: **Call** (dialer), Website, **Share menu (Google Maps link / coordinates / address)**
- ⬜ Place photos
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
- ✅ Route geometry via open router (OSRM today; Valhalla later)
- 🟡 **Live route re-check while navigating** — periodically re-query traffic and
  offer a faster route if one appears (see Navigation below)
- ✅ Walking + cycling modes (drive/walk/bike); transit response shape is a separate TODO
- ⬜ Departure/arrival time selection; avoid tolls/highways
- ⬜ Self-hosted routing backend (replace OSRM demo server)

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
- ⬜ F-Droid submission + reproducible build
- ⬜ UnifiedPush for delay alerts (no FCM)
- ⬜ ACRA / self-hosted crash reporting

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect
  periodic re-calibration (paths documented in the README).
- EU consent wall for cookieless sessions is unhandled.
