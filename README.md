# Vela Maps

A degoogled maps & navigation client for Android — *what NewPipe is to YouTube,
for Google Maps.* Open vector tiles for the basemap, the device itself scraping
Google's public web endpoints (per-user, no backend) for the things only Google
does well: POI quality, routing, and **traffic-aware ETAs**. Built to run on
GrapheneOS and other no-GMS ROMs, distributed via F-Droid.

> Status: **a genuinely usable day-to-day maps app.** Calibrated against live
> captures and verified end-to-end on-device:
>
> - **Search & places** — real POIs with name, rating, **reviews**, full address,
>   category, price, website, weekly hours, distance, a **full photo gallery**,
>   **popular times**, and **"people also search for"**; **Home/Work shortcuts**,
>   saved places, and **deep links** (Vela opens `geo:`/Google-Maps links and
>   shares a place as a keyless `geo:` pin).
> - **Routing** — drive / walk / bike / **public transit**, **traffic-aware ETAs**,
>   selectable **alternates** (drawn greyed + tappable on the map, along *Google's
>   own* route geometry), a **reverse-trip swap**, a **live-traffic overlay**,
>   **search-along-route**, and depart/arrive-time planning.
> - **Navigation** — turn-by-turn with a Google-style maneuver banner (lane
>   guidance, highway/exit shields, swipe-to-look-ahead), spoken + haptic guidance,
>   a **speedometer**, pan-away **re-center**, faster-route re-checks, and an arrival
>   summary.
> - **Polish** — in-app light/dark, one consistent Google-grey UI, custom POI
>   markers, hillshade relief, a **map scale bar**, and **offline** basemap + POI
>   download.
>
> Every push to `main` publishes a signed, Obtainium-friendly `v0.2.<run>` release.
> `MockMapDataSource` stays as an offline fallback; both build types are green.
>
> See **[`SPEC.md`](SPEC.md)** for the full architecture / extractor contract /
> resilience-layer specification (the rebuild target), and **[`ROADMAP.md`](ROADMAP.md)**
> for what's planned (opt-in telemetry, a Vela-own traffic layer, popular times, …).

---

## Screenshots

| Map & search | Search results | Place details | Directions | Navigation |
|:-:|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/01-map.png" width="150"> | <img src="docs/screenshots/02-search.png" width="150"> | <img src="docs/screenshots/03-place.png" width="150"> | <img src="docs/screenshots/04-directions.png" width="150"> | <img src="docs/screenshots/05-navigation.png" width="150"> |

| Public transit | Photo gallery | Light theme — search | Light theme — place |
|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/06-transit.png" width="150"> | <img src="docs/screenshots/07-gallery.png" width="150"> | <img src="docs/screenshots/08-map-light.png" width="150"> | <img src="docs/screenshots/09-place-light.png" width="150"> |

*Keyless OpenFreeMap basemap with custom Google-style POI markers; live Google
search / place / **transit** data and the full **photo gallery**; the directions
panel (alternates, depart-time, search-along-route); turn-by-turn navigation with
the maneuver banner + speedometer; and the in-app **light/dark** themes (decoupled
from the OS).*

## Privacy

There is **no Vela backend, no account, and no telemetry**. Vela fetches from Google
directly from your phone like a logged-out browser — Google sees your IP, query, and
map area, but **not a Google account or any app key**, much like using
`google.com/maps` in an incognito window. Your saved places, history, and settings
never leave the device. **[Read the full breakdown of exactly what each service
receives → `PRIVACY.md`](PRIVACY.md).**

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

Two Gradle modules (AGP 8.7.3, Kotlin 2.1, Compose, Hilt, version catalog,
R8 release builds):

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
        │   │   └─ parse/                 Search / Directions / Transit / Photos / Reviews
        │   ├─ RouteCorridor             "search along route" — filter results to the line
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
        ├─ ui/map/           MapScreen, VelaMapView (MapLibre), MapViewModel, PoiIcons
        ├─ ui/search/        SearchBar
        ├─ ui/place/         PlaceSheet, DirectionsPanel, transit board, photo gallery
        ├─ ui/nav/           ManeuverBanner (lanes/shields/swipe), NavControls, StepsSheet
        ├─ ui/theme/         AppTheme — in-app light/dark, decoupled from the OS
        ├─ ui/               SheetPalette (one shared sheet palette), Format, Units
        ├─ web/              WebPhotoFetcher, WebDirectionsFetcher — hidden-WebView scrapes
        ├─ offline/          OfflineMaps — MapLibre offline region download/store
        └─ ui/settings/      SettingsScreen (appearance / style / voice / haptics / keep-screen-on / offline)
```

The `MapDataSource` interface is the load-bearing seam: Mock today, Google once
calibrated, and a future Overture/OSM source or self-hostable backend (the
"Piped for Vela" idea) drops in the same way.

## Build & run

Standard Android toolchain:

```bash
# debug build (compile check / local install)
./gradlew :app:assembleDebug

# the real distribution build — R8 + resource shrinking.
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
(`v0.2.<run>`, versionCode `2000+run`) — kept as a revision history, so Obtainium
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
Each route's geometry **is** in the response after all — delta-encoded E7
coordinate arrays at `[0][7][i]`, index-aligned with the summaries — so every
route, **alternates included**, draws along Google's *own* roads (decoded in
[`DirectionsParser`](core/src/main/java/app/vela/core/data/google/parse/DirectionsParser.kt);
this replaced an earlier scattered-point guess that doubled back on itself).
**Per-segment live traffic** rides along at `route[3][5][0]` — a list of
`[level, startMeters, lengthMeters]` congestion spans (only the non-free-flow
stretches; gaps are free-flow), parsed into `Route.trafficSpans` and painted as
the route line's Google-style colour bands (free-flow blue → amber → red →
dark-red). An
open router ([`RouteGeometry`](core/src/main/java/app/vela/core/data/RouteGeometry.kt),
FOSSGIS OSRM with a per-mode `routed-car`/`routed-bike`/`routed-foot` backend)
stays only as a fallback for the rare route Google omits geometry for. Live
**public transit** is a separate path — Google silently downgrades a keyless
transit request to driving, so it goes through a hidden WebView (see below).

**Place details** ride along in the search response — no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, rich status with closing time `[1][203][1][4][0]`
("Open · Closes 9 PM"), and **weekly hours `[1][203][0]`** for most places —
falling back to `[1][118][0][3][0]`. Both are 7-entry arrays starting with
today: day name `[0]` + hours text `[3][0][0]`. (Re-calibrated 2026-06-16;
reading only `[118]` had missed hours for the majority of businesses.) Google's
**editorial one-liner** ("Welcoming coffeehouse…") sits at `[1][32][1][1]` and the
business's own **"From the owner"** blurb at `[1][154][0][0]` — both shown in the
sheet (the keyless/list response trims them, so they ride the same lazy WebView detail
fetch as popular times). The
**About** panel rides along too at `[1][100][1]` — a list of sections, each with
a title `[s][1]` and items `[s][2][j][1]` (Service options, Highlights,
Accessibility, …). **Popular times** (`[1][84]`) ride a hidden WebView with a
*specific* query — see below.

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

**Photos** — the search response carries a **~10-photo preview** at
`[1][105][0][1][0][i][6][0]` (the immediate hero). The **full gallery (~30–40)**
comes from the `POST /maps/_/MapsWizUi/data/batchexecute?rpcids=hspqX` RPC
(`/MapsPhotoService.ListEntityPhotos`, feature id at proto `[2][0]`), and it's the
one endpoint that **only a real browser engine** can reach: a plain HTTP client —
even with perfect headers + consent cookies — gets a degraded **Street-View-only**
reply (`streetviewpixels-pa.googleapis.com`), because the bot-detection is at the
**TLS/fingerprint** level (on-device, OkHttp gets a 162 KB token-less "lite" page).
So [`WebPhotoFetcher`](app/src/main/java/app/vela/web/WebPhotoFetcher.kt) runs a
**hidden WebView** (real Chromium): it loads `maps.google.com` as an **anonymous,
no-login** session — exactly like a logged-out browser, which *does* show the
photos — then runs a same-origin `fetch` to the RPC and returns the raw response
over a JS bridge, which [`PhotosParser`](core/src/main/java/app/vela/core/data/google/parse/PhotosParser.kt)
turns into `googleusercontent` URLs (`[i][6][0]`; Street View filtered as a
belt-and-braces). **Keyless** (no key, no account); lazy + best-effort (failure →
keep the preview). Gotchas: **desktop UA** (a mobile UA makes Google redirect to
`intent://` the native app), **block non-http(s) redirects**, and a `Handler` not
`View.postDelayed` (a headless WebView never attaches, so View timers never fire).
*(Earlier I wrongly called the gallery sign-in-gated — that was from testing in a
logged-in browser **and** a bot-degraded HTTP client; a real anonymous browser
engine gets it. Corrected 2026-06-17.)*

**Public transit** rides the same WebView trick for the same reason: a `directions`
GET with the transit flag is silently downgraded to a *driving* reply, so
[`WebDirectionsFetcher`](app/src/main/java/app/vela/web/WebDirectionsFetcher.kt)
navigates the `/maps/dir/…/data=!4m2!4m1!3e3` page and reads the itinerary set out
of `APP_INITIALIZATION_STATE` (the **longest** `)]}'` payload at slot `[3]` — a tiny
stub sits beside the real ~165 KB one), which
[`TransitParser`](core/src/main/java/app/vela/core/data/google/parse/TransitParser.kt)
turns into the results board (trips at `root[0][1]`, each trip's summary at
`trip[0]`: departure/arrival times, total duration, agency, and the coloured line
pills you ride). Keyless, best-effort, device-verified Davis→Sacramento.

**Popular / busy times** ride the same WebView for the same TLS-fingerprint reason —
but with one extra catch: the histogram (`[1][84]`) is stripped not just from the
keyless OkHttp reply but also from a **bare-name** WebView search, which comes back
as a 20-result `[64]` list trimmed of `[84]`. The fix is the *query*:
[`WebPopularTimesFetcher`](app/src/main/java/app/vela/web/WebPopularTimesFetcher.kt)
searches **name + address** (e.g. `In-N-Out Burger 1020 Olive Dr Davis CA`), which
resolves to a *single focused result* whose place node at `[0][1][0][14]` keeps
`[84]`. [`PopularTimesParser`](core/src/main/java/app/vela/core/data/google/parse/PopularTimesParser.kt)
reads it (via `SearchParser`'s single-result snap, or straight off the focused node)
into the day-chip + "busy right now" histogram in the place sheet. Keyless,
best-effort, lazy on place-select like photos. *(Earlier called sign-in-gated — that
too was bot-degradation, not a login wall. Corrected 2026-06-19.)*

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
- **Roads, Google-style** — white road fills on a light-grey land, with the
  casings **faded out down the hierarchy** until the minor-road casing equals the
  land, so streets are clean white lines with **no outline** (the outlines were
  what made it look un-Google); soft-yellow motorways; bridges mirror their tier.
- **Neutralised landuse** — the tan/yellow residential/commercial/school fills are
  flattened into the land (Google keeps these untinted), so no coloured blobs.
- **Light / dark** — a light-grey-land light palette and Google's canonical night
  palette for dark; casings blend into the land in **both** so roads stay clean.
  (Palette tuned live in a MapLibre GL JS harness against Google, then verified
  on-device in light + dark.)
- **Terrain relief (hillshade)** — shaded relief from the keyless open **terrarium**
  DEM (AWS Open Data; native fetch, no key, no CORS), added under the road layers
  and capped at z16, tuned per theme (a soft warm-grey shadow in light, deeper
  shadows + a cool highlight in dark). Verified in a MapLibre GL JS harness
  against the real DEM tiles before shipping.

A `MAPTILER_KEY` (CI secret) path stays wired but **off** (`USE_MAPTILER` in
`MapScreen`): with a key it switches to **MapTiler Streets / Streets Dark**
(proper fonts) instead of the keyless recolour. The keyless route's remaining
ceiling is the font (OpenFreeMap fixes it to Noto Sans; a bundled-Roboto attempt
broke on-device vector rendering and is parked); self-hosted PMTiles is the
no-key, no-quota path for later. Styles are plain URLs, updatable over-the-air
without an app release.

## Roadmap

- [x] Two-module architecture, CI signing, Obtainium-friendly releases
- [x] MapLibre rendering, location, search UI, place sheet
- [x] **Calibrate search + directions** against live captures — live & verified
- [x] Place details — hours / website / price / open-status / **reviews** / **photo gallery**
- [x] Travel modes — drive / walk / bike / **public transit**
- [x] **Alternates along Google's own route geometry**, **traffic-aware ETAs**, **live-traffic overlay**
- [x] Turn-by-turn — maneuver banner (lanes / exit shields / swipe-ahead), spoken + haptic guidance, **speedometer**, **re-center**, arrival summary
- [x] **Foreground navigation service** — screen-off guidance, notification, faster-route re-checks
- [x] **In-app light/dark**, one consistent Google-grey UI, custom POI markers, hillshade relief
- [x] **Offline** basemap + OSM POI index; **search-along-route**; depart/arrive-time planning
- [x] **Popular / busy times** — keyless via a hidden WebView + a *specific* (name + address) search
- [ ] **Predictive** (future-traffic) depart-time ETA — needs a directions-`pb` calibration
- [ ] Embedded offline routing (Valhalla JNI) + bundled PMTiles fonts — v2
- [ ] Street-level imagery (Mapillary / KartaView; needs a free token)
- [ ] F-Droid submission + reproducible build

## A note on the name

**Vela Maps** (`app.vela`) — the navigator's constellation "the Sails", and
"sail" in several languages. The name was vetted clear of maps-app and
trademark collisions.

## License

GPLv3 — copyleft, matching the NewPipe ethos.
