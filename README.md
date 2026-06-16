# Vela Maps

A degoogled maps & navigation client for Android — *what NewPipe is to YouTube,
for Google Maps.* Open vector tiles for the basemap, the device itself scraping
Google's public web endpoints (per-user, no backend) for the things only Google
does well: POI quality, routing, and **traffic-aware ETAs**. Built to run on
GrapheneOS and other no-GMS ROMs, distributed via F-Droid.

> Status: **builds, runs, and pulls live Google data.** Calibrated against a
> live capture (2026-06-15) and verified end-to-end: real POIs with **name,
> rating, reviews, address, category, price, website and weekly hours**, real
> **traffic-aware ETAs** (typical vs live `duration_in_traffic`), turn-by-turn
> maneuvers, and walk/bike modes. The route line is drawn from an open router
> (OSRM) because Google's is vector-tile-only. **Installed + verified on a Pixel 9
> (Android 16)**; signed `v0.1.1` is the latest release, with `nightly` pre-releases on every push. Remaining: popular-times /
> reviews (sign-in gated) and richer cartography. `MockMapDataSource` stays as an
> offline fallback; both build types are green.

---

## Why it's built this way

Two decisions from the planning phase shape everything:

1. **No Vela backend.** Like NewPipe, every install talks to Google directly
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
        │   └─ tiles/                MapStyle catalog (OpenFreeMap default / Positron / Protomaps)
        ├─ location/         LocationProvider — AOSP LocationManager (no Fused)
        ├─ voice/            VoiceGuide — AOSP TextToSpeech, engine-selectable
        ├─ nav/              NavEngine — pure turn-by-turn logic (unit-tested)
        └─ di/               Hilt wiring; picks Mock vs Google off VelaConfig

:app    Jetpack Compose UI (Material 3)
        ├─ MainActivity, VelaApp
        ├─ ui/map/           MapScreen, VelaMapView (MapLibre), MapViewModel
        ├─ ui/search/        SearchBar
        ├─ ui/place/         PlaceSheet
        ├─ ui/nav/           ManeuverBanner, NavControls, StepsSheet (step overview)
        └─ ui/settings/      SettingsScreen (style / voice / data-source)
```

The `MapDataSource` interface is the load-bearing seam: Mock today, Google once
calibrated, and a future Overture/OSM source or self-hostable backend (the
"Piped for Vela" idea) drops in the same way.

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

Release signing comes from CI env vars (`VELA_KEYSTORE_PATH`,
`VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`); local builds fall back to the
debug keystore so `adb install` still works.

**CI** (`.github/workflows/`): every push to `main` builds + tests the APK,
uploads it as an artifact, and publishes it as a **`nightly-<run>`** pre-release
— only the newest nightly is kept on the Releases page, but the tag and
versionCode (= the CI run number) increment each build, so Obtainium detects
every one as an update. Pushing a `v*` tag instead cuts a pinned, versioned
stable release. The release APK is signed with the keystore from repo secrets
`VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS` (without them
it's debug-signed — installable, but not update-compatible across builds). The
repo is public, so release assets (e.g. for Obtainium) download with no token;
workflow artifacts still need you signed in to GitHub.

Out of the box the app talks to the live Google source over the keyless
OpenFreeMap basemap; `MockMapDataSource` is the offline fallback.

## The Google extractor & calibration

Calibrated live on 2026-06-15. The shapes Google can change are pinned here so
re-calibration is a lookup, not a rediscovery:

**Search** — `GET /search?tbm=map&q=<q>&pb=<SearchPb>`. A bare `q=` returns an
empty envelope; the `pb` (viewport-driven, captured in [`SearchPb`](core/src/main/java/app/vela/core/data/google/SearchPb.kt),
no session token needed) is what populates results. Results at `root[64][i]`,
each rooted at `[1]`: name `[1][11]`, address `[1][2][0]`, rating `[1][4][7]`,
reviews `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`.

**Directions** — `GET /maps/preview/directions?pb=<DirectionsPb>` (no token).
Routes at `root[0][1][r]`, summary at `[0]`: distance m `[2][0]`, typical
duration s `[3][0]`, and **live `duration_in_traffic` s `[10][0][0]`**. Steps
arrive as `<step maneuver='TURN_LEFT' meters='120'>…</step>` markup — type and
distance parse straight out of the attributes. The overview geometry isn't in
the JSON at all (Google renders it from vector tiles), so the drawn line comes
from an open router — see [`RouteGeometry`](core/src/main/java/app/vela/core/data/RouteGeometry.kt)
(OSRM today; point it at self-hosted OSRM/Valhalla before release).

**Place details** ride along in the search response — no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, rich status with closing time `[1][203][1][4][0]`
("Open · Closes 9 PM"), and **weekly hours `[1][203][0]`** for most places —
falling back to `[1][118][0][3][0]`. Both are 7-entry arrays starting with
today: day name `[0]` + hours text `[3][0][0]`. (Re-calibrated 2026-06-16;
reading only `[118]` had missed hours for the majority of businesses.) Popular
times and individual review text are the sign-in-gated exceptions, still
unmapped.

To re-calibrate when a shape drifts: capture the request in DevTools, mask the
query/coords, and replace the `pb` template in `SearchPb`/`DirectionsPb`; re-pin
the response indices in `SearchParser`/`DirectionsParser`. `VelaConfig.USE_GOOGLE_SOURCE`
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
  existence proof; Vela's stack is a superset.

## Map style

Defaults to the keyless **OpenFreeMap Liberty** style (full street detail; we
inject house-number labels at z17). Positron, Bright, the MapLibre demo, and
Protomaps are alternates in the `MapStyle` catalog. The polish target is a
custom **Protomaps** (`MapStyle.PROTOMAPS_*`) or MapTiler-keyed style carrying
the "Google-Maps-ify" diff (road hierarchy, 3D buildings, hillshade, custom POI
icons). Styles are plain URLs, updatable over-the-air without an app release.

## Roadmap

- [x] Project scaffold, two-module architecture, CI-style signing
- [x] MapLibre rendering, location, search UI, place sheet
- [x] Turn-by-turn engine + spoken guidance (works on mock routes)
- [x] Google extractor scaffolding (grammar complete)
- [x] **Calibrate search + directions** against a live capture — live & verified
- [x] Place details (hours / website / price / open-status) from the search response
- [x] Route geometry via open router (OSRM) — Google's line is vector-tile-only
- [x] Travel modes (drive / walk / bike)
- [ ] Popular times + individual reviews (sign-in-gated place RPC)
- [ ] Protomaps / MapTiler style + cartographic polish pass
- [ ] Foreground navigation service (screen-off guidance + notification)
- [ ] Offline: bundle PMTiles + embed a routing engine (Valhalla JNI) — v2
- [ ] Traffic overlay tiles (the colored lines) — separate from ETA, later
- [ ] F-Droid submission + reproducible build

## A note on the name

**Vela Maps** (`app.vela`) — the navigator's constellation "the Sails", and
"sail" in several languages. Renamed from "Carto" (which collided with CARTO,
the geospatial company); "Vela" was vetted clear of maps-app and trademark
collisions before the switch.

## License

GPLv3 — copyleft, matching the NewPipe ethos.
