# Carto

A degoogled maps & navigation client for Android — *what NewPipe is to YouTube,
for Google Maps.* Open vector tiles for the basemap, the device itself scraping
Google's public web endpoints (per-user, no backend) for the things only Google
does well: POI quality, routing, and **traffic-aware ETAs**. Built to run on
GrapheneOS and other no-GMS ROMs, distributed via F-Droid.

> Status: **builds, runs, and pulls live Google data.** Calibrated against a
> live capture (2026-06-15) and verified end-to-end: real POIs with **name,
> rating, reviews, address, category, price, website and weekly hours**, real
> **traffic-aware ETAs** (typical vs live `duration_in_traffic`), and turn-by-turn
> maneuvers. The route line is drawn from an open router (OSRM) because Google's
> is vector-tile-only. Remaining: popular-times / individual reviews (sign-in
> gated) and non-driving modes. `MockMapDataSource` stays as an offline fallback;
> both build types are green.

---

## Why it's built this way

Two decisions from the planning phase shape everything:

1. **No Carto backend.** Like NewPipe, every install talks to Google directly
   from the user's own IP, behaving like a single browser. There is no shared
   API key and no server farm to run, scrape from, or get subpoenaed. The cost
   is a maintenance lifestyle: Google rotates these endpoints, so the extractor
   will periodically need re-calibration and an app update.
2. **Open tiles, scraped intelligence.** The *basemap* is open vector tiles
   (Protomaps/MapLibre), so the heaviest per-user load never touches Google and
   we control the cartography. We only scrape Google for POIs, routing, and the
   traffic that's baked into its directions responses — the parts where Google
   genuinely has unique data.

## Architecture

Two Gradle modules, mirroring the Arcana/Callguard house style (AGP 8.7.3,
Kotlin 2.1, Compose, Hilt, version catalog, R8 release builds):

```
:core   the "extractor" — no UI dependency, the NewPipeExtractor pattern
        ├─ model/            LatLng, Place, Route, Maneuver … (pure Kotlin)
        ├─ data/
        │   ├─ MapDataSource         the one seam every screen talks to
        │   ├─ MockMapDataSource     canned data → the app runs with no network
        │   ├─ google/               the real scraper
        │   │   ├─ GoogleSession         per-user bootstrap (token extraction)
        │   │   ├─ GoogleMapsDataSource  search / directions / place details
        │   │   ├─ PbBuilder             builds Google's `pb` URL protobuf
        │   │   ├─ GoogleResponse        XSSI strip + positional-array navigator
        │   │   ├─ PolylineCodec          encoded-polyline decode (calibration-free)
        │   │   └─ parse/                 SearchParser, DirectionsParser
        │   └─ tiles/                MapStyle catalog (demo / Protomaps / Google raster)
        ├─ location/         LocationProvider — AOSP LocationManager (no Fused)
        ├─ voice/            VoiceGuide — AOSP TextToSpeech, engine-selectable
        ├─ nav/              NavEngine — pure turn-by-turn logic (unit-tested)
        └─ di/               Hilt wiring; picks Mock vs Google off CartoConfig

:app    Jetpack Compose UI (Material 3)
        ├─ MainActivity, CartoApp
        ├─ ui/map/           MapScreen, CartoMapView (MapLibre), MapViewModel
        ├─ ui/search/        SearchBar
        ├─ ui/place/         PlaceSheet
        ├─ ui/nav/           ManeuverBanner, NavControls
        └─ ui/settings/      SettingsScreen (style / voice / data-source)
```

The `MapDataSource` interface is the load-bearing seam: Mock today, Google once
calibrated, and a future Overture/OSM source or self-hostable backend (the
"Piped for Carto" idea) drops in the same way.

## Build & run

Standard Android toolchain (the repo already mirrors Arcana's Gradle setup):

```bash
# debug build (compile check / local install)
./gradlew :app:assembleDebug

# the real distribution build — R8 + resource shrinking, like Arcana.
# Always ship release: debug builds visibly lag during map scroll/nav.
./gradlew :app:assembleRelease

# unit tests for the pure logic (polyline codec, nav engine)
./gradlew :core:test
```

Release signing comes from CI env vars (`CARTO_KEYSTORE_PATH`,
`CARTO_KEYSTORE_PASSWORD`, `CARTO_KEY_ALIAS`); local builds fall back to the
debug keystore so `adb install` still works.

Out of the box the app runs on `MockMapDataSource` and the keyless MapLibre demo
style, so it's fully clickable with zero configuration.

## The Google extractor & calibration

Calibrated live on 2026-06-15. The shapes Google can change are pinned here so
re-calibration is a lookup, not a rediscovery:

**Search** — `GET /search?tbm=map&q=<q>&pb=<SearchPb>`. A bare `q=` returns an
empty envelope; the `pb` (viewport-driven, captured in [`SearchPb`](core/src/main/java/app/carto/core/data/google/SearchPb.kt),
no session token needed) is what populates results. Results at `root[64][i]`,
each rooted at `[1]`: name `[1][11]`, address `[1][2][0]`, rating `[1][4][7]`,
reviews `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`.

**Directions** — `GET /maps/preview/directions?pb=<DirectionsPb>` (no token).
Routes at `root[0][1][r]`, summary at `[0]`: distance m `[2][0]`, typical
duration s `[3][0]`, and **live `duration_in_traffic` s `[10][0][0]`**. Steps
arrive as `<step maneuver='TURN_LEFT' meters='120'>…</step>` markup — type and
distance parse straight out of the attributes. The overview geometry isn't in
the JSON at all (Google renders it from vector tiles), so the drawn line comes
from an open router — see [`RouteGeometry`](core/src/main/java/app/carto/core/data/RouteGeometry.kt)
(OSRM today; point it at self-hosted OSRM/Valhalla before release).

**Place details** ride along in the search response — no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, and weekly hours `[1][118][0][3][0]` (7 entries, day name
`[0]` + hours text `[3][0][0]`). Popular times and individual review text are
the sign-in-gated exceptions, still unmapped.

To re-calibrate when a shape drifts: capture the request in DevTools, mask the
query/coords, and replace the `pb` template in `SearchPb`/`DirectionsPb`; re-pin
the response indices in `SearchParser`/`DirectionsParser`. `CartoConfig.USE_GOOGLE_SOURCE`
is already `true`.

When a response no longer matches, the parsers throw `CalibrationNeededException`
and the UI shows a non-fatal "needs recalibration" notice — that's the *expected*
periodic failure mode, not a crash. `PolylineCodec` needs no calibration; it
decodes Google's geometry exactly and is covered by a reference-vector test.

> **Do not embed a static Google API key.** That converts "a user scraped from
> their own IP" (defensible, NewPipe's footing) into "the app shipped Google's
> credential" (not). The per-user `GoogleSession` bootstrap is the whole point.

## Degoogled / GrapheneOS notes

- **Location:** AOSP `LocationManager` (GPS + NETWORK simultaneously), never
  `FusedLocationProviderClient`. We cache last-known to seed an instant map and
  show a one-time PSDS tip when the cold fix is slow — on GrapheneOS, enabling
  PSDS (Settings → Location) drops TTFF from ~30s to a few seconds.
- **Voice:** AOSP `TextToSpeech`. We enumerate installed engines and let the
  user pick (RHVoice / eSpeak NG from F-Droid sound far better than stock Pico).
- **No GMS anywhere:** no Fused location, no FCM, no Firebase, no Play Integrity.
  Everything (MapLibre, OkHttp, Compose, Hilt) is pure AOSP. OrganicMaps is the
  existence proof; Carto's stack is a superset.

## Map style

Defaults to the keyless **MapLibre demo style** so it renders immediately. The
real target is **Protomaps** (`MapStyle.PROTOMAPS_*`) — point it at a hosted
style (needs a key) or a self-hosted PMTiles archive, then apply the
"Google-Maps-ify" diff (road hierarchy, 3D buildings, hillshade, custom POI
icons). Styles are plain URLs, updatable over-the-air without an app release.

## Roadmap

- [x] Project scaffold, two-module architecture, CI-style signing
- [x] MapLibre rendering, location, search UI, place sheet
- [x] Turn-by-turn engine + spoken guidance (works on mock routes)
- [x] Google extractor scaffolding (grammar complete)
- [x] **Calibrate search + directions** against a live capture — live & verified
- [x] Place details (hours / website / price / open-status) from the search response
- [x] Route geometry via open router (OSRM) — Google's line is vector-tile-only
- [ ] Popular times + individual reviews (sign-in-gated place RPC); travel modes
- [ ] Protomaps style + cartographic polish pass
- [ ] Place details: reviews, hours, popular times
- [ ] Foreground navigation service (screen-off guidance + notification)
- [ ] Offline: bundle PMTiles + embed a routing engine (Valhalla JNI) — v2
- [ ] Traffic overlay tiles (the colored lines) — separate from ETA, later
- [ ] F-Droid submission + reproducible build

## A naming note

"Carto" collides with **CARTO** (carto.com), an established geospatial company —
worth a deliberate decision before any public release, the same way the planning
notes flagged keeping "Google"/"Maps" out of the name. Easy to rename now (one
`applicationId`, one `rootProject.name`); harder after launch.

## License

GPLv3 — copyleft, matching the NewPipe ethos.
