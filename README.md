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
> (Android 16)**; every push to `main` publishes a normal, signed `v0.1.<run>` release (Obtainium-friendly). Remaining: popular-times /
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
        │   ├─ OverpassPois              keyless OSM POI fetch (offline-search source)
        │   ├─ OfflinePoiStore           on-device SQLite POI index (offline search)
        │   └─ tiles/                MapStyle catalog (OpenFreeMap default / Positron / Protomaps)
        ├─ location/         LocationProvider — AOSP LocationManager (no Fused)
        ├─ voice/            VoiceGuide — AOSP TextToSpeech, engine-selectable
        ├─ feedback/         Haptics — direction-coded vibration turn cues
        ├─ config/           Calibration + CalibrationStore (remote pb/paths)
        ├─ nav/              NavEngine — pure turn-by-turn logic (unit-tested)
        └─ di/               Hilt wiring; picks Mock vs Google off VelaConfig

:app    Jetpack Compose UI (Material 3)
        ├─ MainActivity, VelaApp
        ├─ ui/map/           MapScreen, VelaMapView (MapLibre), MapViewModel
        ├─ ui/search/        SearchBar
        ├─ ui/place/         PlaceSheet
        ├─ ui/nav/           ManeuverBanner, NavControls, StepsSheet (step overview)
        ├─ offline/          OfflineMaps — MapLibre offline region download/store
        └─ ui/settings/      SettingsScreen (style / voice / haptics / offline)
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
uploads it as an artifact, and publishes a **normal versioned release**
(`v0.1.<run>`, versionCode `1000+run`) — kept as a revision history, so Obtainium
tracks the latest with zero configuration and no pre-release toggle. The release APK is signed with the keystore from repo secrets
`VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS` (without them
it's debug-signed — installable, but not update-compatible across builds). An
optional `MAPTILER_KEY` secret is injected into `BuildConfig` (`-PmaptilerKey`)
to switch the basemap to MapTiler; it's never committed, and the app falls back
to keyless OpenFreeMap without it. The repo is public, so release assets (e.g.
for Obtainium) download with no token; workflow artifacts still need you signed
in to GitHub.

Out of the box the app talks to the live Google source over the keyless
OpenFreeMap basemap; `MockMapDataSource` is the offline fallback.

## The Google extractor & calibration

Calibrated live on 2026-06-15. The shapes Google can change are pinned here so
re-calibration is a lookup, not a rediscovery:

**Search** — `GET /search?tbm=map&q=<q>&pb=<SearchPb>`. A bare `q=` returns an
empty envelope; the `pb` (viewport-driven, captured in [`SearchPb`](core/src/main/java/app/vela/core/data/google/SearchPb.kt),
no session token needed) is what populates results. Results at `root[64][i]`,
each rooted at `[1]`: name `[1][11]`, **full address `[1][39]`** (street, city,
state, ZIP — fall back to joining the components at `[1][2]`), rating `[1][4][7]`,
reviews `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`,
feature id `[1][10]` (`0x..:0x..`, → reviews endpoint), place id `[1][78]`,
**photos `[1][105][0][1][0][i][6][0]`** (FIFE URLs; re-size with a `=w500-h350`
suffix), **featured review snippet `[1][142][1][0][1][0][0]`**, and the **About**
attributes at `[1][100][1]` (see below). Full reviews come from a separate
keyless endpoint (below). A **specific/far address** doesn't come back as a `[64]`
list — it's a single geocoded result whose place node sits at `[0][1][0][14]`
(same internal schema), so the parser falls back to that, then to the largest
name+coord array; a structurally-valid response with no matches returns empty
(not a calibration error).

**Directions** — `GET /maps/preview/directions?pb=<DirectionsPb>` (no token).
Routes at `root[0][1][r]`, summary at `[0]`: distance m `[2][0]`, typical
duration s `[3][0]`, and **live `duration_in_traffic` s `[10][0][0]`**. Steps
arrive as `<step maneuver='TURN_LEFT' meters='120'>…</step>` markup — type and
distance parse straight out of the attributes, and the **lane hint** ("Use the
right 2 lanes to …") is split off into its own field for the step list + banner.
The overview geometry isn't in
the JSON at all (Google renders it from vector tiles), so the drawn line comes
from an open router — see [`RouteGeometry`](core/src/main/java/app/vela/core/data/RouteGeometry.kt).
It uses the FOSSGIS community OSRM, which exposes a **separate backend per mode**
(`routed-car` / `routed-bike` / `routed-foot`), so drive/walk/bike each get their
own path-following line — the old `router.project-osrm.org` demo only had the car
profile, which is why walk/bike used to draw a wrong, "all over the place" line.
Point it at a self-hosted OSRM/Valhalla before release.

**Place details** ride along in the search response — no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, rich status with closing time `[1][203][1][4][0]`
("Open · Closes 9 PM"), and **weekly hours `[1][203][0]`** for most places —
falling back to `[1][118][0][3][0]`. Both are 7-entry arrays starting with
today: day name `[0]` + hours text `[3][0][0]`. (Re-calibrated 2026-06-16;
reading only `[118]` had missed hours for the majority of businesses.) The
**About** panel rides along too at `[1][100][1]` — a list of sections, each with
a title `[s][1]` and items `[s][2][j][1]` (Service options, Highlights,
Accessibility, …). Popular times remain the sign-in-gated exception.

**Reviews** — `GET /maps/preview/review/listentitiesreviews?pb=…` is a keyless
endpoint (no token: the `!5m2!1s<session>` block accepts any string). The pb is
`!1m2!1y<HIGH>!2y<LOW>!2m2!2i<offset>!3i<count>!3e1!5m2!1svela!7e81`, where
`<HIGH>`/`<LOW>` are the two halves of the place's feature id `[1][10]`
(`0xHIGH:0xLOW`) as unsigned-64 decimals. Reviews come back at `root[2]`, each:
author `[0][1]`, author photo `[0][2]`, relative time `[1]`, text `[3]`, rating
`[4]`. It needs the same consent cookies as search (the shared cookie jar carries
them); a cookieless request returns an empty envelope. It serves a **fixed top
~20** (the `2i` offset is ignored and `3i` count is capped); deeper pagination is
behind an obfuscated continuation token, deliberately not chased.

**Photos (full gallery)** — the search response carries only a ~10-photo preview.
The full gallery (~40+) comes from `POST /maps/_/MapsWizUi/data/batchexecute?rpcids=hspqX`
(the `/MapsPhotoService.ListEntityPhotos` RPC). Body is `f.req=<[[["hspqX",<proto>,null,"generic"]]]>`;
the proto carries the feature id at `[2][0]` and the page size at `[4][2][1]`.
**No `at`/`SNlM0e` token is needed** — just the warmed session cookies, so it's as
keyless as reviews (the earlier "token-gated" belief was wrong). The response is
the chunked batchexecute envelope; the `["wrb.fr","hspqX",<payload>,…]` row's
payload holds the photo list at `[0]`, each URL at `[i][6][0]` (the same `[6][0]`
leaf as the search preview). Best-effort — a failure keeps the preview photos.
([`PhotosParser`](core/src/main/java/app/vela/core/data/google/parse/PhotosParser.kt).)

**Remote calibration.** The brittle bits that drift — the `pb`/proto templates and
the endpoint URLs above (search, directions, reviews, **photos**) — are not
hard-compiled; they live in [`calibration.json`](calibration.json) at the repo root.
[`CalibrationStore`](core/src/main/java/app/vela/core/config/CalibrationStore.kt)
ships a bundled `Calibration.DEFAULT`, then fetches that file from the repo's raw
URL at launch and **adopts it when its `version` is newer** — gated by a host
allowlist (every endpoint must be `google.com`, so a tampered file can't redirect
requests). So when Google reshuffles a `pb`, moves an endpoint, **or relocates a
field index**, the fix is a one-line edit + `version` bump committed to `main` —
**every user gets it on their next launch, no app update**. Phase 2 added the
`paths` object — the search parser's positional field-index paths (`name`,
`address`, `rating`, `photos`, `featureId`, … as `[i,j,…]` arrays; relative to a
result entry whose place node is `[1]`, except `results`/`single` which are
root-relative). Only a change that needs genuinely new parsing *logic* still ships
as a release.

**Reverse-geocode** (long-press the map → drop a pin → address) uses
OpenStreetMap's **Nominatim** (`/reverse`, keyless and documented) rather than
Google, since Google's map search doesn't reverse-geocode a `lat,lng` query.

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

The active basemap is the keyless **OpenFreeMap Liberty** style (full street
detail; we inject house-number labels at z17), loaded **by URL** — the only setup
that reliably renders vector tiles on-device. Over it we apply a Google-like look
at **runtime**, by system theme:

- **POI markers** — small category-coloured circles with white Material Icons
  glyphs (food orange, shops blue, parks green, …); in **light** mode the POI
  label text is coloured by category too, like Google.
- **Road hierarchy** — gold motorways with a darker casing, white arterials, and
  a casing that lightens down the hierarchy so minor roads recede.
- **Light / dark** — a neutral-grey-land light palette and Google's canonical
  night palette for dark.

A `MAPTILER_KEY` (CI secret) path stays wired but **off** (`USE_MAPTILER` in
`MapScreen`): with a key it switches to **MapTiler Streets / Streets Dark**
(proper fonts) instead of the keyless recolour. The keyless route's remaining
ceiling is the font (OpenFreeMap fixes it to Noto Sans; a bundled-Roboto attempt
broke on-device vector rendering and is parked); self-hosted PMTiles is the
no-key, no-quota path for later. Styles are plain URLs, updatable over-the-air
without an app release.

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
