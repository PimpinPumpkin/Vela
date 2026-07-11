# Vela Maps

A degoogled maps & navigation client for Android - *what NewPipe is to YouTube,
for Google Maps.* Open vector tiles for the basemap, the device itself scraping
Google's public web endpoints (per-user, no backend) for the things only Google
does well: POI quality, routing, and **traffic-aware ETAs**. Built to run on
GrapheneOS and other no-GMS ROMs.

## Screenshots

| Navigation | Map & search | Place details | Directions | Search results |
|:-:|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/05-navigation.png" width="150"> | <img src="docs/screenshots/01-map.png" width="150"> | <img src="docs/screenshots/03-place.png" width="150"> | <img src="docs/screenshots/04-directions.png" width="150"> | <img src="docs/screenshots/02-search.png" width="150"> |

| Public transit | Photo gallery | Light theme - map | Light theme - place |
|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/06-transit.png" width="150"> | <img src="docs/screenshots/07-gallery.png" width="150"> | <img src="docs/screenshots/08-map-light.png" width="150"> | <img src="docs/screenshots/09-place-light.png" width="150"> |

*Turn-by-turn with the maneuver banner, exit badges and speedometer; the keyless
OpenFreeMap basemap with Google-style POI markers; live place data with the full
photo gallery; the directions panel with alternates and traffic in plain words;
and the in-app light/dark themes (decoupled from the OS).*

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/PimpinPumpkin/Vela)&nbsp;&nbsp;[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid (Vela's own repo)" height="54">](FDROID.md)

Tap a badge - no Play Store, no account. Obtainium auto-tracks the
**weekly stable** release; turn on "include prereleases" and you get the
**nightly** channel instead (every push to main). The F-Droid badge is
**Vela's own repository**, not the f-droid.org catalog - it works in any
F-Droid client and serves the same signed APKs. Or grab an APK straight from
[Releases](https://github.com/PimpinPumpkin/Vela/releases).

**F-Droid setup:** add the repo to your client and install/update from there.
It follows the weekly stable by default; nightlies are there too if you enable
unstable updates for Vela in your client (details in [FDROID.md](FDROID.md)):

```
https://pimpinpumpkin.github.io/Vela/repo
```

Fingerprint and full instructions in [FDROID.md](FDROID.md).

---


## What you get

- **Real turn-by-turn navigation.** Spoken directions from a neural voice that runs
  entirely on your phone, lane diagrams, exit shields, a speedometer, automatic
  reroutes, and a heads-up (on a card and out loud) when your destination closes
  within an hour of your arrival.

  🔊 **Hear Vela's nav voice** - the actual in-app voice at the default
  pace:

  https://github.com/user-attachments/assets/17f246e4-51c8-4d01-998b-dcd7f29dc15f

- **Talk to it.** Tap the mic and say what you want. Vela Voice transcribes speech
  on the phone (a one-time 58 MB download), or hands off to a voice-input app you
  already have. Either way, nothing you say goes to a cloud speech service.
- **Works offline.** Download a state or country once and its maps, turn-by-turn
  routing, and every place in it stay searchable with no signal - typed street
  addresses included.
- **Real place info.** Hours (holidays included), reviews you can search, photo
  galleries, busy times, phone and website - the things you actually check before
  driving somewhere, with a warning if it would be closed when you arrive.
- **Traffic you can read.** Route choices say light, moderate or heavy traffic in
  words next to their green/amber/red times, and the fastest route leads the list.
- **Material You theming, if you want it.** An optional toggle tints Vela's chrome
  with your wallpaper colors (Android 12+); off by default, and the map itself
  stays clean either way.
- **Public transit.** Itineraries with line pills, station-by-station stops, and
  step-by-step guidance to the platform.
- **No account, no tracking.** No Google account, no GMS, no telemetry. What
  Google's servers can and cannot see is documented plainly in [PRIVACY.md](PRIVACY.md).
- **The rest.** Android Auto, 11 languages, in-app light/dark, full D-pad operation
  for keypad phones, parking memory, place lists, and a built-in updater with
  weekly-stable or nightly channels.

The complete running feature list lives in [FEATURES.md](FEATURES.md).

## Everyday features in ten seconds

- **Save your parking**: tap the **P** button on the map when you park. Tap the teal
  pin later for walking directions back, or the pin's Clear button when you leave.
  Long-press **P** for your parking history in case you overwrote a spot by mistake.
- **Lists**: the bookmark button next to the category chips opens **Your lists**.
  Create one there, or add any place from its page (⋮ → Save to list), with a note
  per place. Lists back up to a file from Settings.
- **Import a Google Maps list**: paste a `maps.app.goo.gl` share link into the
  search bar. The list's places show up as results with the owner's notes; tap
  **Save list** to keep a local copy.
- **Nightly builds**: Settings → Version → "Include nightly builds" updates you to
  the newest build instead of weekly stable.

## Why a degoogled app uses Google

A phone without Google Play Services cannot run Google Maps, and the open map
datasets fall well short on search, reviews, hours, and live traffic. So Vela is
a thin client over Google's public web endpoints. It asks them the same way a
logged-out browser does, once per user, with no account, no shared API key, and
no server in the middle. NewPipe does the same for YouTube. There are no ads, and
your searches, saved places, and history stay on the phone. If you run GrapheneOS
or another no-GMS ROM, this gets you working maps back.

The map itself, the streets, the labels, and the house numbers all come from OpenStreetMap. Google is only used for places, search, routing, and traffic. So street names and house numbers can differ from what Google Maps shows, and how much detail you see offline depends on how well OpenStreetMap covers your area. I'm thinking of ways to improve OSM and fill the gaps in the data. Stay tuned.

> Status: **a genuinely usable day-to-day maps app.** Calibrated against live
> captures and verified end-to-end on-device:
>
> - **Search & places** - real POIs with name, rating, **reviews**, full address,
>   category, price, website, weekly hours, distance, a **full photo gallery**,
>   **popular times**, and **"people also search for"**; **Home/Work shortcuts**,
>   saved places, and **deep links** (Vela opens `geo:`/Google-Maps links and
>   shares a place as a keyless `geo:` pin).
> - **Routing** - drive / walk / bike / **public transit**. Turn-by-turn comes from
>   the **open OSRM** router (complete street-named steps, incl. highway refs / exit
>   numbers / shields); **Google is overlaid for the live-traffic ETA and to reroute
>   around jams** (it re-runs OSRM through Google's jam-avoiding path only when they
>   diverge). Selectable **alternates**, **reverse-trip swap**, **live-traffic overlay**,
>   **search-along-route**, and depart/arrive-time planning.
> - **Offline routing** - full turn-by-turn **on the phone** via an embedded
>   **GraphHopper** engine, from a downloadable **135-region world catalog** (all US
>   states, Canada, Europe, + more) hosted as GitHub release assets. Saving offline
>   map tiles for an area grabs its routing graph too.
> - **Navigation** - turn-by-turn with a Google-style maneuver banner: a **real
>   per-lane diagram** (which lane to be in), **highway/exit shields**,
>   swipe-to-look-ahead, spoken + haptic guidance, a **speedometer**, a
>   **posted speed-limit sign** (OSM `maxspeed`, keyless + offline, reddens when
>   speeding), pan-away **re-center**, faster-route re-checks, and an arrival summary.
> - **Polish** - in-app light/dark, one consistent Google-grey UI, custom POI
>   markers, hillshade relief, a **map scale bar**, and **offline** basemap + POI
>   download.
>
> Every push to `main` publishes a signed nightly prerelease; a weekly job promotes
> the newest nightly to the stable release Obtainium tracks by default.
> `MockMapDataSource` stays as an offline fallback; both build types are green.
>
> See **[`SPEC.md`](SPEC.md)** for the full architecture / extractor contract /
> resilience-layer specification (the rebuild target), and **[`ROADMAP.md`](ROADMAP.md)**
> for what's planned (opt-in telemetry, a Vela-own traffic layer, predictive depart-time ETA, …).

## Privacy

There is **no Vela backend, no account, and no telemetry**. Vela fetches from Google
directly from your phone like a logged-out browser - Google sees your IP, query, and
map area, but **not a Google account or any app key**, much like using
`google.com/maps` in an incognito window. Your saved places, history, and settings
never leave the device. **[Read the full breakdown of exactly what each service
receives → `PRIVACY.md`](PRIVACY.md).**

How that compares, honestly:

| What Google gets | Google Maps app | Google Maps web | Vela |
| --- | --- | --- | --- |
| Tied to your Google account | Yes, always signed in | Yes unless incognito | Never - there is no login |
| A persistent device identifier | Yes (device + ad IDs via Play Services) | Browser cookies | No account, no app key; just an IP like any website visitor |
| Your precise GPS position | Continuously while open, plus Location History if enabled | While the tab is open | Never sent. Position stays on the phone; searches send the map area you are looking at |
| Every pan and zoom of the map | Yes - their servers render the map | Yes | No - map tiles come from OpenFreeMap, so Google never sees you browse |
| Your searches | Yes, saved to your account history | Yes | The query text reaches Google anonymously, only when you search |
| Place pages you open | Yes | Yes | The place lookup reaches Google anonymously |
| Turn-by-turn routes | Yes, full trip telemetry | Yes | Routing runs on open OSRM/GraphHopper; Google sees one anonymous ETA check, never your live position |
| Saved places, home, work | Stored on their servers | Stored on their servers | Stored only on your phone |
| Ad profile building | Feeds your ads profile | Feeds your ads profile | Nothing to attach it to |
| Works with no Google contact at all | No | No | Yes - downloaded regions search, route, and navigate fully offline |

The short version: Google shrinks from *knowing who you are and everywhere you go* to
*occasionally answering an anonymous question*. And the parts that matter most while
driving - your GPS trace, your map browsing - never reach Google at all.

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
   traffic that's baked into its directions responses - the parts where Google
   genuinely has unique data.

## How it works - each capability and the method behind it

The one-screen map of *what Vela does* and *how*, with the entry point to read next. Deeper detail is in [`SPEC.md`](SPEC.md); this is the index into it.

| Capability | Method (how) | Start here |
|---|---|---|
| **Basemap** | Open vector tiles (OpenFreeMap / Protomaps) via MapLibre - keyless, no Google | `core/data/tiles/`, `app/ui/map/VelaMapView.kt` |
| **Search, places, reviews, hours** | Per-user keyless scrape of `google.com` `pb` endpoints (browser-like session token); responses are positional arrays walked by calibrated index paths | `core/data/google/`, SPEC §3 |
| **Photo gallery** | Hidden **anonymous WebView** same-origin-fetches the gallery RPC (OkHttp gets a bot-degraded reply) | `app/web/WebPhotoFetcher.kt` |
| **Public transit** | Hidden WebView reads the directions SPA's `APP_INITIALIZATION_STATE` | `app/web/WebDirectionsFetcher.kt`, `core/…/TransitParser` |
| **Turn-by-turn routing** | **FOSSGIS OSRM** (open) - complete street-named steps incl. highway `ref`/exit/lanes; retried on blips | `core/data/RouteGeometry.kt` |
| **Traffic ETA + jam reroute** | Google's directions overlaid on the OSRM route; re-runs OSRM through Google's path only when they diverge (option 3) | `GoogleMapsDataSource.directions`/`applyTraffic` |
| **Offline routing** | On-device **GraphHopper** CH graphs, one per region, downloaded from a 135-region world catalog | `core/data/GraphHopperRouteEngine.kt`, `app/offline/RoutingGraphStore.kt`, `tools/routing-regions.json` |
| **Offline address geocoding** | Typed street address → coordinate → GraphHopper route with no signal; keyless OSM `addr:housenumber` + named-road centrelines indexed on area download (house-precise, interpolated, or street-level fallback) | `core/data/OfflineAddressStore.kt`, `core/data/OverpassPois.kt` |
| **Offline place packs** | Downloading a state also pulls its whole-region place pack (CI-baked SQLite of every OSM POI/address/street), so offline search covers the entire state, Organic-Maps-style. Packs rebuild monthly from fresh OSM; installed ones update in place with small row-level deltas | `app/offline/PoiPackStore.kt`, `core/data/OfflinePacks.kt`, `scripts/build-poi-region.sh`, `scripts/poipack_delta.py`, `.github/workflows/poi-packs.yml` |
| **Open building overlay** | Microsoft building footprints (ODbL) as per-region PMTiles, rendered beneath OSM to fill areas OSM never mapped; world catalog of 51 US states + ~185 countries (US + Global ML sources) | `app/offline/OverlayTileStore.kt`, `app/ui/map/VelaMapView.kt`, `scripts/build-overlay-region.sh`, `tools/overlay-regions.json` |
| **Open house-number overlay** | OpenAddresses address points as per-state PMTiles, streamed + rendered as a house-number SymbolLayer where OSM lacks `addr:housenumber` | `app/ui/map/VelaMapView.kt`, `scripts/build-address-region.sh`, `tools/address-regions.json` |
| **Navigation (banner, voice, haptics)** | Pure `NavEngine` turn logic (unit-tested) → maneuver banner (lane diagram / shields), AOSP TTS, direction-coded vibration | `core/nav/`, `app/ui/nav/`, `core/voice/`, `core/feedback/` |
| **Android Auto (full nav)** | Navigation-category CarAppService with car-side search, route preview with live-traffic alternates, and active turn-by-turn (turn card, "then" step, lane diagram, cluster support). The map renders through MapLibre's MapSnapshotter with route map-matching and smoothed puck motion. Sideloads show up with AA's "Unknown sources" on | `app/car/` (`VelaCarAppService.kt`, `CarMapRenderer.kt`, `screen/`) |
| **Location & heading** | AOSP `LocationManager` + raw rotation-vector sensor - never GMS/Fused | `core/location/` |
| **D-pad-only operation** | The whole UI works with a 5-key D-pad, no touchscreen (touch is a bonus): key-drivable map (arrows pan, OK-at-crosshair taps, hold-OK drops a pin, on-screen zoom buttons), focus rings, key alternatives for every gesture | [`docs/dpad.md`](docs/dpad.md), `app/ui/DpadFocus.kt`, `app/ui/map/MapDpadController.kt` |
| **Lists & Google Maps list import** | Local place lists (icon + colour, notes per place, export/import to a file); pasting a Google Maps share link previews the list's places with a one-tap Save | `core/data/PlaceListStore.kt`, `core/data/google/parse/EntityListParser.kt` |
| **Parking spot memory** | One tap on the P button saves where you parked (teal pin, walking directions back); long-press for the history so an accidental overwrite never loses the car | `core/data/ParkingStore.kt` |
| **Fix drift without an app update** | ECDSA-signed remote `calibration.json` (pb templates, field-index paths, JS transforms) + notices, verified against a pinned key | `core/config/CalibrationStore.kt`, SPEC §5 |
| **Distribution** | Code push to `main` → signed nightly prerelease (docs-only changes don't cut releases); weekly promote to stable (same APK); Obtainium tracks stable by default, nightlies via the prerelease toggle; a self-hosted F-Droid repo serves both channels ([FDROID.md](FDROID.md)) | `.github/workflows/ci.yml` + `promote-stable.yml` + `fdroid-repo.yml` |

## Docs - where to look

| File | What's in it |
|---|---|
| [`README.md`](README.md) | This - what Vela is, why, the how-it-works map above, and build/run |
| [`FEATURES.md`](FEATURES.md) | The full, categorised list of every shipped capability (the encyclopaedia) |
| [`SPEC.md`](SPEC.md) | The authoritative **rebuild spec** - architecture, extractor contract (pb layouts + response indices), resilience layer, hard constraints |
| [`ROADMAP.md`](ROADMAP.md) | Planned work + big bets (opt-in telemetry, a Vela-own traffic layer, giant-country graph splits, …) |
| [`PRIVACY.md`](PRIVACY.md) | Exactly what each Google endpoint receives, per request |
| [`CLAUDE.md`](CLAUDE.md) | Build rules, module layout, and the hard-won gotchas - for contributors (human or AI) |
| [`docs/dpad.md`](docs/dpad.md) | D-pad / no-touchscreen operation - design, findings, per-surface audit, and the merge-with-upstream policy |

## Architecture

Two Gradle modules (AGP 8.7.3, Kotlin 2.1, Compose, Hilt, version catalog,
R8 release builds):

```
:core   the "extractor" - no UI dependency, the NewPipeExtractor pattern
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
        │   ├─ RouteGeometry             OSRM turn-by-turn (open router) + parse of steps/lanes/refs
        │   ├─ RouteEngine               offline-routing interface (connectivity/graph-presence picks it)
        │   ├─ GraphHopperRouteEngine    on-device GraphHopper CH graphs, one per downloaded region
        │   ├─ RouteCorridor             "search along route" - filter results to the line
        │   ├─ OverpassPois              keyless OSM POI + addr + street fetch (offline-search/geocode source)
        │   ├─ OfflinePoiStore           on-device SQLite POI index (offline search)
        │   ├─ OfflineAddressStore       on-device SQLite forward geocoder (typed address → coord, offline)
        │   ├─ OfflinePacks              registry of downloaded whole-region place packs (both stores query them)
        │   └─ tiles/                MapStyle catalog (OpenFreeMap default / Positron / Protomaps)
        ├─ location/         LocationProvider - AOSP LocationManager (no Fused)
        ├─ voice/            VoiceGuide - AOSP TextToSpeech, engine-selectable
        ├─ feedback/         Haptics - direction-coded vibration turn cues
        ├─ config/           Calibration + CalibrationStore (remote pb/paths)
        ├─ nav/              NavEngine - pure turn-by-turn logic + NavReplay auditor (unit-tested)
        ├─ replay/           TripLog - trip CSV format + offline route audit
        └─ di/               Hilt wiring; picks Mock vs Google off VelaConfig

:app    Jetpack Compose UI (Material 3)
        ├─ MainActivity, VelaApp
        ├─ ui/map/           MapScreen, VelaMapView (MapLibre), MapViewModel, PoiIcons
        ├─ ui/search/        SearchBar
        ├─ ui/place/         PlaceSheet, DirectionsPanel, transit board, photo gallery
        ├─ ui/nav/           ManeuverBanner (lanes/shields/swipe), NavControls, StepsSheet
        ├─ ui/theme/         AppTheme - in-app light/dark, decoupled from the OS
        ├─ ui/               SheetPalette (one shared sheet palette), Format, Units
        ├─ web/              WebPhotoFetcher, WebDirectionsFetcher - hidden-WebView scrapes
        ├─ offline/          OfflineMaps (MapLibre tiles) + RoutingGraphStore (GraphHopper graphs) + PoiPackStore (place packs) + OverlayTileStore (building-footprint PMTiles)
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

# the real distribution build - R8 + resource shrinking.
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
(`v0.3.<run>`, versionCode `2000+run`) - nightlies as prereleases, the weekly
promoted stable as the normal release, so Obtainium
tracks the latest with zero configuration and no pre-release toggle. The release APK is signed with the keystore from repo secrets
`VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS` (without them
it's debug-signed - installable, but not update-compatible across builds). An
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

**Search** - `GET /search?tbm=map&q=<q>&pb=<SearchPb>`. A bare `q=` returns an
empty envelope; the `pb` (viewport-driven, captured in [`SearchPb`](core/src/main/java/app/vela/core/data/google/SearchPb.kt),
no session token needed) is what populates results. Results at `root[64][i]`,
each rooted at `[1]`: name `[1][11]`, **full address `[1][39]`** (street, city,
state, ZIP - fall back to joining the components at `[1][2]`), rating `[1][4][7]`,
reviews `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`,
feature id `[1][10]` (`0x..:0x..`, → reviews endpoint), place id `[1][78]`,
**photos `[1][72][0][i][6][0]`** (FIFE URLs; re-size with a `=w500-h350`
suffix), **featured review snippet `[1][142][1][0][1][0][0]`**, and the **About**
attributes at `[1][100][1]` (see below). Full reviews come from a separate
keyless endpoint (below). A **specific/far address** doesn't come back as a `[64]`
list - it's a single geocoded result whose place node sits at `[0][1][0][14]`
(same internal schema), so the parser falls back to that, then to the largest
name+coord array; a structurally-valid response with no matches returns empty
(not a calibration error).

**Directions** - `GET /maps/preview/directions?pb=<DirectionsPb>` (no token).
Routes at `root[0][1][r]`, summary at `[0]`: distance m `[2][0]`, typical
duration s `[3][0]`, and **live `duration_in_traffic` s `[10][0][0]`**. Steps
arrive as `<step maneuver='TURN_LEFT' meters='120'>…</step>` markup - type and
distance parse straight out of the attributes. But Google's keyless steps come
back **abbreviated** on longer routes (a 6-mi route returned 2 of ~10 turns), so
**turn-by-turn + geometry are now PRIMARY from an open router**
([`RouteGeometry`](core/src/main/java/app/vela/core/data/RouteGeometry.kt), FOSSGIS
OSRM with a per-mode `routed-car`/`routed-bike`/`routed-foot` backend) - every turn,
with street names, for drive/walk/bike. Google's directions response is kept for
what it actually wins: the **live `duration_in_traffic`** (scaled onto the OSRM
route) and **per-segment congestion** at `route[3][5][0]` - `[level, startMeters,
lengthMeters]` spans (non-free-flow stretches only), mapped onto the OSRM geometry
as `Route.trafficSpans` → the route line's Google-style colour bands (blue → amber
→ red → dark-red) - plus it's the **fallback router** when OSRM is unreachable. Its
own complete geometry (delta-encoded E7 at `[0][7][i]`, decoded in
[`DirectionsParser`](core/src/main/java/app/vela/core/data/google/parse/DirectionsParser.kt))
still drives the **traffic-aware snap**: when Google's live route diverges from
OSRM's free-flow one (a jam reroute), Vela re-runs OSRM *through points sampled off
Google's polyline* so you follow Google's smart path **with** full OSRM steps. Live
**public transit** is a separate path - Google silently downgrades a keyless
transit request to driving, so it goes through a hidden WebView (see below).

**Place details** ride along in the search response - no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, rich status with closing time `[1][203][1][4][0]`
("Open · Closes 9 PM"), and **weekly hours `[1][203][0]`** for most places -
falling back to `[1][118][0][3][0]`. Both are 7-entry arrays starting with
today: day name `[0]` + hours text `[3][0][0]`. (Re-calibrated 2026-06-16;
reading only `[118]` had missed hours for the majority of businesses.) Google's
**editorial one-liner** ("Welcoming coffeehouse…") sits at `[1][32][1][1]` and the
business's own **"From the owner"** blurb at `[1][154][0][0]` - both shown in the
sheet (the keyless/list response trims them, so they ride the same lazy WebView detail
fetch as popular times). The
**About** panel rides along too at `[1][100][1]` - a list of sections, each with
a title `[s][1]` and items `[s][2][j][1]` (Service options, Highlights,
Accessibility, …). **Popular times** (`[1][84]`) ride a hidden WebView with a
*specific* query - see below.

**Reviews** - ⚠️ **the `listentitiesreviews` RPC below is DEAD** (Google 404'd it; it only ever
served avatars). Reviews now come from the place's `?cid=` page rendered in a hidden WebView
([`WebReviewsFetcher`](app/src/main/java/app/vela/web/WebReviewsFetcher.kt)): `.jJc9Ad` cards de-duped by
`data-review-id`, accumulated across scroll, **with per-review uploaded photos** - up to ~50, not the old
~3 (the headless WebView needs an explicit offscreen viewport for Google's virtualized list to render - see
SPEC §Reviews). The pb below is kept only as calibration history.
`GET /maps/preview/review/listentitiesreviews?pb=…` was a keyless
endpoint (no token: the `!5m2!1s<session>` block accepts any string). The pb is
`!1m2!1y<HIGH>!2y<LOW>!2m2!2i<offset>!3i<count>!3e1!5m2!1svela!7e81`, where
`<HIGH>`/`<LOW>` are the two halves of the place's feature id `[1][10]`
(`0xHIGH:0xLOW`) as unsigned-64 decimals. Reviews come back at `root[2]`, each:
author `[0][1]`, author photo `[0][2]`, relative time `[1]`, text `[3]`, rating
`[4]`. It needs the same consent cookies as search (the shared cookie jar carries
them); a cookieless request returns an empty envelope. It serves a **fixed top
~20** (the `2i` offset is ignored and `3i` count is capped); deeper pagination is
behind an obfuscated continuation token, deliberately not chased.

**Photos** - the search response carries a **photo preview** at
`[1][72][0][i][6][0]` (the immediate hero; Google **moved this block `[105]`→`[72]`
on 2026-06-27**, which briefly blanked every hero strip - hot-fixed via calibration
`v7`, no app update) - **de-duped** (Google now serves the single hero **twice**) plus
a small `[1][204][0][i][1][2][0][0]` block that **only landmark places carry** (a famous
landmark → ~4; an ordinary business → **1**). The **full gallery (~9-25 photos) is scraped from the place's own page**, and that
**replaced** the bare `hspqX` photo RPC. That RPC
(`POST …/batchexecute?rpcids=hspqX`, `/MapsPhotoService.ListEntityPhotos`) is **bot-degraded
per-session** to a Street-View-only reply - on-device logging showed byte-identical degraded
replies across retries, so retrying never recovers it. But Google **renders the real photo
collage to a logged-out browser on the place PAGE itself.** So
[`WebPhotoFetcher`](app/src/main/java/app/vela/web/WebPhotoFetcher.kt) (a **hidden WebView**,
real Chromium, **desktop UA**, anonymous/no-login) loads the place's `?cid=` Maps page, lets
Google's own JS draw it, then a self-polling injected script scrapes every `googleusercontent`
photo URL out of the DOM (avatars + Street View filtered, de-duped by image id; clicks the
"Photos" affordance + scrolls to surface more) and bridges them back - **the same tactic as
[`WebReviewsFetcher`](app/src/main/java/app/vela/web/WebReviewsFetcher.kt)**. A rendered page
is far harder for Google to bot-degrade than a naked RPC POST. **Keyless** (no key, no
account); lazy + best-effort (failure → keep the preview). While it's in flight the sheet
shows a row of **pulsing shimmer tiles** (`MapState.photosLoading`) so it reads as "more
loading". Gotchas: **desktop UA** (a mobile UA makes Google deep-link to `intent://`),
**block non-http(s) redirects**, and a `Handler` not `View.postDelayed` (a headless WebView
never attaches). *(History - the gallery was first wrongly called sign-in-gated, then wrongly
"retry-fixable"; the truth: the bare RPC is per-session-degraded and the page scrape sidesteps
it. 2026-06-28.)*

**Public transit** rides the same WebView trick for the same reason: a `directions`
GET with the transit flag is silently downgraded to a *driving* reply, so
[`WebDirectionsFetcher`](app/src/main/java/app/vela/web/WebDirectionsFetcher.kt)
navigates the `/maps/dir/…/data=!4m2!4m1!3e3` page and reads the itinerary set out
of `APP_INITIALIZATION_STATE` (the **longest** `)]}'` payload at slot `[3]` - a tiny
stub sits beside the real ~165 KB one), which
[`TransitParser`](core/src/main/java/app/vela/core/data/google/parse/TransitParser.kt)
turns into the results board (trips at `root[0][1]`, each trip's summary at
`trip[0]`: departure/arrival times, total duration, agency, and the coloured line
pills you ride). Keyless, best-effort, device-verified Davis→Sacramento.

**Popular / busy times** ride the same WebView for the same TLS-fingerprint reason -
but with one extra catch: the histogram (`[1][84]`) is stripped not just from the
keyless OkHttp reply but also from a **bare-name** WebView search, which comes back
as a 20-result `[64]` list trimmed of `[84]`. The fix is the *query*:
[`WebPopularTimesFetcher`](app/src/main/java/app/vela/web/WebPopularTimesFetcher.kt)
searches **name + address** (e.g. `In-N-Out Burger 1020 Olive Dr Davis CA`), which
resolves to a *single focused result* whose place node at `[0][1][0][14]` keeps
`[84]`. [`PopularTimesParser`](core/src/main/java/app/vela/core/data/google/parse/PopularTimesParser.kt)
reads it (via `SearchParser`'s single-result snap, or straight off the focused node)
into the day-chip + "busy right now" histogram in the place sheet. Keyless,
best-effort, lazy on place-select like photos. *(Earlier called sign-in-gated - that
too was bot-degradation, not a login wall. Corrected 2026-06-19.)*

**Remote calibration.** The brittle bits that drift - the `pb`/proto templates and
the endpoint URLs above (search, directions, reviews, **photos**) - are not
hard-compiled; they live in [`calibration.json`](calibration.json) at the repo root.
[`CalibrationStore`](core/src/main/java/app/vela/core/config/CalibrationStore.kt)
ships a bundled `Calibration.DEFAULT`, then fetches that file from the repo's raw
URL at launch and **adopts it when its `version` is newer** - gated by a host
allowlist (every endpoint must be `google.com`, so a tampered file can't redirect
requests). So when Google reshuffles a `pb`, moves an endpoint, **or relocates a
field index**, the fix is a one-line edit + `version` bump committed to `main` -
**every user gets it on their next launch, no app update**. Phase 2 added the
`paths` object - the search parser's positional field-index paths (`name`,
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
and the UI shows a non-fatal "needs recalibration" notice - that's the *expected*
periodic failure mode, not a crash. `PolylineCodec` needs no calibration; it
decodes Google's geometry exactly and is covered by a reference-vector test.

> **Do not embed a static Google API key.** That converts "a user scraped from
> their own IP" (defensible, NewPipe's footing) into "the app shipped Google's
> credential" (not). The per-user `GoogleSession` bootstrap is the whole point.

## Degoogled / GrapheneOS notes

- **Location:** AOSP `LocationManager` (GPS + NETWORK simultaneously), never
  `FusedLocationProviderClient`. We cache last-known to seed an instant map and
  show a one-time PSDS tip when the cold fix is slow - on GrapheneOS, enabling
  PSDS (Settings → Location) drops TTFF from ~30s to a few seconds.
- **Voice:** a built-in **neural voice** that runs entirely on-device - Vela bundles the sherpa-onnx
  runtime and downloads a Piper model itself (progress bar), no standalone app. A **Voice library**
  (Settings → Voice) lets you browse, download and switch between ~40 Piper voices (Lessac, HFC, Ryan,
  the HFC Female default, British voices, …) to find the read you like. Plus AOSP
  `TextToSpeech`: we enumerate the phone's installed engines so you can override to any system voice.
  Spoken directions have an on/off switch that the in-nav mute button shares. **Voice search** is
  on-device too: the search-bar mic records and transcribes with Vela's own downloadable Whisper
  model (same bundled runtime, mic permission asked at first use), or hands off to a voice-input
  app you already have; with neither installed the mic offers the download.
- **No GMS anywhere:** no Fused location, no FCM, no Firebase, no Play Integrity.
  Everything (MapLibre, OkHttp, Compose, Hilt) is pure AOSP. OrganicMaps is the
  existence proof; Vela's stack is a superset.

## Map style

The active basemap is the keyless **OpenFreeMap Liberty** style (full street
detail; we inject house-number labels at z17), loaded **by URL** - the only setup
that reliably renders vector tiles on-device. Over it we apply a Google-like look
at **runtime**, by system theme:

- **POI markers** - small category-coloured dots with white Material Icons
  glyphs (food orange, shops blue, parks green, …) in front of a muted-grey
  teardrop pin with a soft shadow (Google-style, no white ring), with the label
  to the **left** of the icon; in **light** mode the POI label text is coloured
  by category too, like Google.
- **Roads, Google-style** - white road fills on a light-grey land, with the
  casings **faded out down the hierarchy** until the minor-road casing equals the
  land, so streets are clean white lines with **no outline** (the outlines were
  what made it look un-Google); soft-yellow motorways; bridges mirror their tier.
- **Building footprints + house numbers** - flat grey footprints with a crisp
  outline from neighbourhood zoom (3D extrusions at street zoom), and OSM house
  numbers from ~z16, both keyless from the OpenFreeMap tiles. Density follows
  OpenStreetMap coverage (dense in metros, patchy in some suburbs).
- **Neutralised landuse** - the tan/yellow residential/commercial/school fills are
  flattened into the land (Google keeps these untinted), so no coloured blobs.
- **Light / dark** - a light-grey-land light palette and Google's canonical night
  palette for dark; casings blend into the land in **both** so roads stay clean.
  (Palette tuned live in a MapLibre GL JS harness against Google, then verified
  on-device in light + dark.)
- **Terrain relief (hillshade)** - shaded relief from the keyless open **terrarium**
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

Everything shipped so far is in [FEATURES.md](FEATURES.md) (the complete list) and the
release notes of each build. Still open:

- [ ] **Predictive** (future-traffic) depart-time ETA - needs a directions-`pb` calibration
- [ ] Optional offline highway refs
- [ ] Embedded Mapillary/KartaView street imagery (needs a token; Street View currently opens Google's keyless pano in the browser)
- [ ] F-Droid submission + reproducible build


## A note on the name

**Vela Maps** (`app.vela`) - the navigator's constellation "the Sails", and
"sail" in several languages. The name was vetted clear of maps-app and
trademark collisions.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first - it covers the hard rules (no
backend, no static Google keys, degoogled runtime, the `:core`/`:app` module
boundary, docs-in-the-same-commit, and translations for all 11 locales) and how to
send a change. There is no separate code-of-conduct document by design: keep it
about the code. Security issues go through [`SECURITY.md`](SECURITY.md) (GitHub
private vulnerability reporting), not a public issue.

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

Vela is Free Software: you can use, study, share, and improve it at your will. You may use, modify, and redistribute this project only if your modifications remain open-source under the same license.
