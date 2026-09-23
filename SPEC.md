# Vela Maps - technical specification

The authoritative description of what Vela is, how it is built, and every load-bearing
technical decision. If the codebase were lost, this is the document to rebuild against.

**Rules for this document.** It states what the system does and the constraints that hold it
in place. It is written in the present tense and in plain declarative sentences: no narrative
of how a decision was reached, no first person, no anecdotes, no "we later found". A rule that
exists because of a past failure is written as the rule plus the failure mode it prevents, in
one sentence. Dates appear only where the date is itself the fact (a bake revision, a
calibration version, the date of a measurement that could go stale). Numbers are exact and
carry their units.

**Scope.** Everything technical belongs here. Other documents may explain, narrate or index,
but a technical fact that lives only in another file is a bug in this one.

| Document | Holds |
| --- | --- |
| `SPEC.md` (this file) | Every technical rule, contract, constant and constraint |
| `FEATURES.md` | The running changelog: what shipped, when, in what order |
| `README.md` | The public overview and the install path |
| `PRIVACY.md` | The per-request accounting of what leaves the phone |
| `docs/book/` | Subsystem explainers for people who want to understand a behavior |
| `docs/FAQ.md` | The ten questions people ask first |
| `docs/dpad.md` | The per-surface D-pad audit and the contributor procedure |
| `docs/ANDROID-AUTO.md`, `docs/BUILDING.md`, `docs/TRANSLATING.md`, `docs/LANGUAGES.md`, `FDROID.md` | Task guides |
| `ROADMAP.md` | Open work, planned work, and what will never be built |
| `CLAUDE.md` | Working rules for contributors and assistants, plus per-area gotchas |

**Contents**

- [1. Product definition](#1-product-definition)
- [2. Architecture](#2-architecture)
- [3. The Google extractor](#3-the-google-extractor)
- [4. Routing and navigation](#4-routing-and-navigation)
- [5. Places](#5-places)
- [6. Map rendering](#6-map-rendering)
- [7. Offline data](#7-offline-data)
- [8. Transit](#8-transit)
- [9. Voice, dictation and language](#9-voice-dictation-and-language)
- [10. User interface](#10-user-interface)
- [11. Remote resilience](#11-remote-resilience)
- [12. Degoogled constraints](#12-degoogled-constraints)
- [13. Performance model](#13-performance-model)
- [14. Privacy, diagnostics and location hygiene](#14-privacy-diagnostics-and-location-hygiene)
- [15. Build, release and distribution](#15-build-release-and-distribution)
- [16. Where the rest lives](#16-where-the-rest-lives)

---

## 1. Product definition

### 1.1 What it is

Vela Maps (`app.vela`) is a degoogled maps application for Android: search, places, routing,
traffic-aware ETAs and turn-by-turn navigation on a phone with no Google Play Services.
Distribution is F-Droid and Obtainium; the license is GPLv3; the minimum target is
GrapheneOS and other no-GMS ROMs.

### 1.2 Non-negotiables

- **No Vela backend.** Each install talks to the sources directly from the user's own IP and
  behaves like one logged-out browser. There is no shared API key and no server tier.
- **No static shared Google credential.** Sessions bootstrap per user (`GoogleSession`). This
  is the legal footing the project stands on.
- **Open data draws the map.** Basemap tiles are open vector tiles; place data defaults to
  Vela's own bake of open datasets. Google is queried for search, for opening a place, and for
  traffic.
- **Degoogled at runtime** (section 12): AOSP location and TTS only; no FCM, Firebase, Play
  Integrity or fused location.
- **Recalibratable without a release.** Scraped endpoints drift by design of the other side.
  Field indices, pb templates, endpoints, word tables, tuning dials and parse logic all ship
  over a signed remote channel (section 11).

### 1.3 Non-goals

- Account sync of any kind.
- Anything that requires a Google sign-in. The two data classes behind that wall are the
  review "helpful" counts and the popular-times histogram on accounts where it is gated.
- A Vela-hosted tile, routing or search service.

### 1.4 Capability and source matrix

| Capability | Source | Reaches Google | Works offline |
| --- | --- | --- | --- |
| Basemap | OpenFreeMap Liberty vector tiles, or a downloaded PMTiles region | No | With a downloaded region |
| Place pins while browsing | Vela's own places bake (Overture + AllThePlaces positioned with OSM), or Google ambient places, or both (section 5.1) | Only in Google or Both mode | Vela data mode, with a downloaded region |
| Opening a place | Google listing, correlated to the tapped feature | Yes, unless the lookup toggle is off | Tile data only |
| Search | Google `search?tbm=map`; offline falls back to the on-device pack index | Yes | Region packs |
| Reviews, photos, popular times, About | Hidden WebView scrapes of Google's own pages | Yes | No |
| Turn-by-turn routing | FOSSGIS OSRM primary, Google as traffic and fallback, on-device obf offline | Yes for traffic | Downloaded obf region |
| Traffic and live ETA | Google directions | Yes | No |
| Traffic controls (lights, stops, crossings, humps) | Per-region road-features bake, Overpass only where no region exists | No | Yes |
| Surveillance and speed cameras | Bundled and hosted DeFlock dataset; OSM speed cameras | No | Yes |
| Transit boards and stop icons | Transitous (open GTFS + GTFS-Realtime) | No | Cached areas only |
| Transit directions | Google directions page | Yes | No |
| Street View | Google keyless pano metadata and tiles, rendered in-app | Yes | No |
| Reverse geocoding | Nominatim | No | Region packs |

---

## 2. Architecture

### 2.1 Modules and boundaries

Two Gradle modules with a strict boundary.

- **`:core`** - the UI-agnostic extractor. Models, the `MapDataSource` seam, the Google
  scraper and parsers, pb builders, the polyline codec, the pure navigation engine, routing
  engines, offline stores, location and voice abstractions, the remote-config layer, and the
  opt-in diagnostics ring. **No MapLibre type and no Android UI type may appear in `:core`**;
  coordinates convert at the view boundary.
- **`:app`** - the Compose UI, MapLibre Native 11.8.0, the foreground navigation service, the
  Android Auto service, and the five hidden WebView scrapes. Root package `app.vela`,
  application class `VelaApp`, compile-time switches in `VelaConfig`.

The `:app` module may read `:core`; `:core` may not read `:app`. Where `:core` needs a user
setting, `:app` writes a plain flag into `:core` (`CategoryFilter.enabled`, `LowRamMode`,
`LowDataMode`, `NoGoogle`, `RoutingPrefs`) rather than `:core` reading a Compose holder.

- **Use Vela without Google** (`NoGoogle.enabled`, set from Settings > Privacy) is enforced at
  the data source: search answers from the OpenStreetMap geocoder (Photon, 20 results, biased
  around the user), the page-2 search, the ambient fan-out, reviews and photos answer empty,
  Street View answers null, and the Google directions call answers empty, so every route is the
  open router's with no traffic, no Google alternates and no abbreviated fallback. The app gates
  its own Google surfaces on the same setting: the hidden WebView fetchers return null at
  `HiddenWebView.request`, the traffic raster is not added, the satellite Google fallback draws
  no deep layer, the tap lookup and the ambient fan-out are skipped, and the Street View pill and
  the full-screen reviews page are hidden. A short Google Maps link (`maps.app.goo.gl`)
  is resolved by ONE cookieless request to Google's shortener that reads the redirect and stops
  (`ShortLinks.resolve`), only while "Open shared Google Maps links" (`GoogleFree.resolveLinks`, pref
  `google_free_resolve_links`, default on) is on; off, it is refused with a toast. The target is
  parsed on the phone and searched through the open sources. A shared list is refused under the
  switch (`importList` returns null), because its places exist only on Google's servers.

### 2.2 Module tree

```
:core
  model/          LatLng, Place, Route, Maneuver, Transit types (pure Kotlin)
  data/
    MapDataSource         the one interface the UI depends on
    MockMapDataSource     canned data, keeps the app usable with no network
    google/
      GoogleSession         per-user bootstrap and cookie seeding
      GoogleMapsDataSource  search, directions, place details, ambient places
      PbBuilder / SearchPb / DirectionsPb   request protobuf grammar
      GoogleResponse        XSSI strip and positional-array navigator
      PolylineCodec         encoded polyline, calibration-free
      parse/                Search, Directions, Transit, Photos, Reviews, EntityList,
                            PopularTimes, StreetView parsers
    RouteGeometry         OSRM turn-by-turn, step and lane parsing, via snapping
    RouteEngine           offline routing interface
    ObfRouteEngine        on-device OsmAnd router over downloaded .obf files
    ValhallaRouter        safety-weighted bicycle routing
    RouteCorridor         search along a route
    OverpassPois / OverpassEndpoints / OverpassTrafficSignals / OverpassAlprCameras
    OfflinePoiStore / OfflineAddressStore / OfflinePacks
    transit/Transitous    MOTIS client: stops, boards, trips
    CategoryFilter        content gating inside :core
    tiles/                map style catalog
  location/       LocationProvider (AOSP), HeadingProvider, MotionProvider, SpeedKalman,
                  AlongRouteFilter
  nav/            NavEngine (pure), NavSession, RouteProjection, RouteBar, CameraAlerts,
                  CameraFacing, SpeedingAlerts, ExitLabel
  voice/          VoiceGuide, NeuralSynth seam, SpokenScript, SpeechText
  feedback/       Haptics
  config/         Calibration, CalibrationStore, BundleSignature, JsSandbox, JsTransforms
  search/         QueryIntents, VoiceCommandExamples
  replay/         TripLog, TripScrub, NavReplay, DemoTrace
  i18n/           NavStrings tables and registry
  util/           SunTimes, NameScript, OsmHours (OSM opening_hours to the sheet's day lines; `lines()` is the one entry point for every open source), ClockFormat
  diag/           DiagLog, DiagScrub

:app
  MainActivity, VelaApp, VelaConfig
  ui/map/         MapScreen, VelaMapView, MapViewModel, NavController, SearchGates,
                  PoiIcons, AmbientStability, MapDpadController
  ui/place/       PlaceSheet, GoogleChooser, DirectionsPanel, StopsEditor, StreetViewScreen
  ui/nav/         ManeuverBanner, NavControls, NavOverlays, StepsSheet, RouteBarStrip,
                  RoundaboutGlyph, RouteShield
  ui/search/      SearchBar
  ui/settings/    SettingsScreen, SettingsHub, SettingsScaffold, sections/
  ui/theme/       AppTheme, Theme
  ui/             process-wide holders (section 2.3), SheetPalette, Format, Units, VelaMenu,
                  VelaDialog, DpadFocus, AdaptiveDensity, AppFont, QuickCategories
  web/            HiddenWebView base plus the five scrapes and ReviewsPanel
  offline/        ObfStore, RegionCatalog, PoiPackStore, PmtilesRegionStore, PlacesTileStore,
                  BasemapTileStore, OverlayTileStore, GlyphPackStore, OfflineMaps
  car/            VelaCarAppService, CarMapRenderer, ManeuverMapper, screen/
  data/           RoadFeatures, FlockCameras, ContactAddresses
  voice/          AsrRecognizer, AsrEngine, PiperSynth, VoiceInstaller, KokoroInstaller
  service/        NavigationService, NavGlyphs
  download/       DownloadService, DownloadWork
  update/         SelfUpdater
  diag/           DiagExporter, NavTrace
  replay/         TripStore
  streetview/     PanoramaView, StreetViewTiles
```

### 2.3 Seams and process-wide state

- **`MapDataSource`** is the only interface the UI depends on. `MockMapDataSource` keeps the
  whole app usable with no network; `GoogleMapsDataSource` is the real scraper. The binding is
  chosen by `VelaConfig.USE_GOOGLE_SOURCE` in the Hilt module.
- **`GoogleSession`** bootstraps a logged-out session. An in-memory cookie jar pre-seeds
  Google's `SOCS` and `CONSENT` cookies so an EU session is not bounced to `consent.google.com`.
  A `Set-Cookie` must never be allowed to downgrade `CONSENT` to `PENDING`.
- **Process-wide reactive holders** are a `mutableStateOf` mirror over `SharedPreferences`,
  initialized in `VelaApp`: `Units`, `AppTheme`, `AppLocale`, `AppFont`, `UiScale`,
  `MapColors`, `MapPoiPrefs`, `HouseNumbers`, `Buildings3d`, `BuildingOverlay`, `Traffic`,
  `TransitLayer`, `Flock`, `FlockNavAlert`, `SpeedCams`, `SpeedCamWarn`, `SpeedingAlert`,
  `LiveReviews`, `PlaceContent` (`ShowReviews`, `LoadPhotos`, `HideAdult`,
  `HideExternalLinks`), `RoutePicker`, `RouteTrail`, `RoadLabel`, `PreferButtons`,
  `PuckStyle`, `VoiceSearch`, `ContactsSearch`, `LayersButton`, `Onboarding`, `PipMode`,
  `MemoryPressure`, `ConstrainedNetwork`.
- **One view model.** `MapViewModel` owns `MapUiState` and delegates navigation to
  `NavController` through a `Host` interface. Nav code never reaches into the view model.
  **Anything an init-time collector touches must be declared above `init`**: `viewModelScope`
  is `Main.immediate`, so a collector's first pass runs inline, before properties declared
  below `init` exist.

### 2.4 Data flow, search example

`MapScreen` calls `MapViewModel.search()`, which calls `GoogleMapsDataSource.search()`. That
builds the `pb` with `SearchPb`, issues the GET, optionally hands the raw body to a remote JS
transform (section 11), parses with `GoogleResponse` plus `SearchParser` using the calibrated
index paths, optionally post-processes through a second JS hook, and returns a result list the
view model publishes into `MapUiState`.

---

## 3. The Google extractor

Field numbers and array indices drift when Google reshapes a response. The live source of
truth is `calibration.json` (section 11); `Calibration.DEFAULT` is the compiled fallback and
is kept in step at release. The `PbBuilder` grammar and `PolylineCodec` are stable and need no
calibration. A response that no longer matches raises `CalibrationNeededException`, which the
UI surfaces as a non-fatal notice; that is the expected periodic failure mode, not a crash.

### 3.1 Endpoints

All Google endpoints are keyless and host-allowlisted to `google.com` and `www.google.com`.

| Purpose | Request |
| --- | --- |
| Search | `GET /search?tbm=map&q=<q>&pb=<SearchPb>` |
| Ambient places | the same search endpoint, fanned out over category terms |
| Directions (traffic, fallback router) | `GET /maps/preview/directions?pb=<DirectionsPb>` |
| Turn-by-turn (primary) | FOSSGIS OSRM `route/v1/<profile>` with `steps=true`, `geometries=polyline6` |
| Bicycle, safety-weighted | FOSSGIS Valhalla `/route`, costing `bicycle`, `use_roads` 0.1 |
| Shared-list import | `/maps/preview/entitylist/getlist`, URL lifted verbatim from the share page |
| Photos | hidden WebView DOM walk of the place's `?cid=` page |
| Reviews | hidden WebView DOM scrape of the same page, or the visible carve panel |
| Popular times, About, owner blurb | hidden WebView search for name plus address |
| Transit directions | hidden WebView on `/maps/dir/<o>/<d>/data=!4m2!4m1!3e3` |
| Street View metadata | `GeoPhotoService.SingleImageSearch` and `photometa/v1` |
| Street View imagery | `streetviewpixels-pa.googleapis.com/v1/tile` |
| Reverse geocoding | Nominatim `/reverse` (Google's map search does not reverse a coordinate) |

A bare `q=` returns an empty envelope; the viewport-driven `pb` is what populates results.

### 3.2 Search response

Results are at `root[64][i]`, each entry rooted at `[1]`.

| Field | Path |
| --- | --- |
| name | `[1][11]` |
| full address | `[1][39]`, fallback join of `[1][2]` |
| rating | `[1][4][7]` |
| review count | `[1][4][8]` |
| latitude, longitude | `[1][9][2]`, `[1][9][3]` |
| category | `[1][13][0]` |
| website | `[1][7][0]` |
| phone | `[1][178][0][0]` |
| price text | `[1][4][2]` (a range such as "$10-20", not a 1-4 level; `SearchParser.priceLevelOf` derives the level from the label) |
| open status | `[1][203][1][8][0]` |
| rich status | `[1][203][1][4][0]` |
| feature id | `[1][10]`, shaped `0xHIGH:0xLOW` |
| place id | `[1][78]` |
| photos | `[1][72][0][i][6][0]` (FIFE URLs, resize with `=w500-h350`) |
| landmark extra photos | `[1][204][0][i][1][2][0][0]` |
| featured review | `[1][142][1][0][1][0][0]` |
| About sections | `[1][100][1]`, title `[s][1]`, items `[s][2][j][1]` |
| editorial one-liner | `[1][32][1][1]` |
| owner blurb | `[1][154][0][0]` |
| weekly hours | `[1][203][0]`, fallback `[1][118][0][3][0]` |
| in-store departments | `[1][118]` |
| fuel price | `[1][88][0]` |
| wheelchair attribute | `[1][100][1]` attribute id `has_wheelchair_accessible_entrance` |
| popular times histogram | `[1][84]` |
| Street View thumbnail (pano id and yaw) | regex over the serialized entry, `SearchParser.svThumb` |

Shape rules:

- A specific or far address is not a `[64]` list. It is a single geocoded result whose place
  node sits at `[0][1][0][14]` with the same internal schema. All three result-shape gates
  (`singleResultEntry`, `atThisPlaceEntries`, `findResultsArray`) validate through
  `paths.name` rather than a hard-coded index, so recalibrating `name` reaches them.
- "People also search for" is a sibling of a focused result at `root[2][11][0]`, each entry
  `[featureId, name, [[_,_,lat,lng], ..., rating@6]]`. Focused searches only.
- `[84]`, `[32]` and `[154]` are trimmed from the keyless list response and are fetched lazily
  through the WebView detail path.
- A summary node drops review count, full hours, address, phone, price and attributes. The
  focused re-fetch backfills them: `PopularTimesParser` lifts them into `PlaceDetails`, gated
  on feature id, and `MapViewModel.fetchPlaceDetails` merges into blank fields only.
- Status and hours must come from the same block. `[203]` is the main entity's schedule;
  `[118]` carries a department's sub-schedule. Mixing them reports a department's closing time
  against the store's hours.
- The open/closed boolean is parsed from the localized status text by
  `SearchParser.parseOpenNow(status, lang)` against a per-language keyword table, closed words
  first, because several languages' opening phrases are prefix-cousins of their open words.
  **The numeric status-code path does not exist**: those integers are span and style markers,
  proven against a live capture, and reading them colors closed places green.

### 3.3 Hours node

Each entry of `[1][203][0]` is `[name, dow(1=Mon..7=Sun), [Y,M,D], ranges, flag, flag,
special?]`, a rolling next-seven-days list keyed to the actual date, so holiday overrides are
already present. `ranges` is `[[text, [[openH],[closeH]]], ...]` with multiple entries per day
for split shifts; `special[1]` carries a holiday label. `readHours` joins the ranges and
appends the label; `OpeningHours` strips the label, so the computed open/closed fallback is
holiday-aware. Google's live status string stays primary because it is the only source for an
owner's ad-hoc closure.

### 3.4 Directions response

Routes are at `root[0][1][r]`, summary at `[0]`.

| Field | Path |
| --- | --- |
| distance, meters | `[2][0]` |
| typical duration, seconds | `[3][0]` |
| in-traffic duration, seconds | `[10][0][0]` (per route, not shared) |
| overall traffic level | `[10][2]` |
| typical spread | `[10][4]` = `[lowSec, highSec, label]` |
| geometry | `[0][7][i]`, delta-encoded E7, first element absolute; `[i][4]` is elevation |
| per-segment congestion | `route[3][5][0]` = `[level, startMeters, lengthMeters]`, non-free-flow stretches only |
| steps | `<step maneuver=... meters=...>` markup, lane hints split into `Maneuver.laneHint` |

Geometry is index-aligned with the summaries, so alternates draw real roads. Each maneuver is
placed at `cumulativeStepMeters / polylineLength` **except the final ARRIVE, which is pinned to
the route end**: step distances total a few percent short of the geometry, and an unpinned
arrive lands kilometers early on a long route and fires the arrival trigger there.

Request-side route options ride the `!6m` feature block: inside its `!2m` submessage,
`!1b1` avoids highways and `!2b1` avoids tolls; `!7b1` avoids ferries as a direct child of the
outer block. `DirectionsPb.withAvoid` places them by pattern and fixes up the `m` group counts,
so a recalibrated template survives.

**Not reachable keyless** (each probed and settled; do not re-chase):

- A per-departure or time-of-day ETA curve. The response carries no curve, the pb is
  byte-identical to the live web client's, injected time fields are ignored or rejected. The
  honest stand-in is the typical spread `[10][4]`.
- The `listentitiesreviews` RPC. It 404s for everyone and only ever served avatars.
- The `hspqX` photo RPC. It is bot-degraded per session to a Street-View-only reply; retries
  in the same session return byte-identical degraded bodies. The page DOM walk replaces it.
- Per-photo upload dates. The RPC that carries them returns zero photos to an automated
  browser even with the page's own fresh token, and the place page does not embed photo URLs
  at walk time. The date-join plumbing stays in place and inert, and the RPC is not sent: the
  `photoDatesRpc` tuning dial (default 0) turns it back on from the signed calibration.
- Review "helpful" counts, which are login-gated to zero for logged-out sessions.
- EV charger detail (type marker only), the recently-opened badge, and live incidents (Google
  serves binary vector tiles; Waze's feed is reCAPTCHA-gated).

### 3.5 Shared-list import

A `maps.app.goo.gl` link fetched logged out redirects to `/maps/@/data=!4m3!11m2!2s<LISTID>!3e3`,
whose HTML embeds a ready-made prefetch URL for the getlist RPC with the list id and page
session token included. `GoogleMapsDataSource.importList` lifts that URL verbatim by regex and
unescapes `&amp;`; no pb is constructed. Response after the guard: `root[0][4]` title, `[5]`
description, `[3][0]` author, `[8]` items, each `[2]` display name, `[3]` the owner's note,
`[1][4]` address, `[1][5][2..3]` coordinates, `[1][6]` feature id as a decimal signed-int64
pair whose two's-complement hex is the `0x..:0x..` form.

### 3.6 Browser identity

Two user agents, and confusing them is a bug.

- **Google-facing** requests send the calibrated browser UA plus the coherent Chrome header
  set (`Sec-CH-UA*`, `Sec-Fetch-*`) through `BrowserHeaders.browserHeaders` and
  `browserXhrHeaders`. Read it from `calibration.current().userAgent`, never from
  `VelaConfig.USER_AGENT`, which is only the compiled fallback.
- **Community services** (FOSSGIS OSRM and Valhalla, Nominatim, Photon, Overpass, Transitous)
  send `VelaConfig.VELA_UA`, the honest contactable identifier their usage policies ask for.

Constraints:

- `secChUa` is DERIVED from `userAgent`'s major (`BrowserHeaders.secChUaFor`, Chromium's own rule
  from `user_agent_utils.cc`: the GREASE brand's two characters, its version and the brand order
  are all picked by the major modulo the table sizes). `parseBundle` ignores a pushed `secChUa`
  whenever the UA names a major; builds from before 2026-09-23 still send the pushed one, so the
  bundle carries the exact derived string too. `BrowserHeadersTest` pins the rule against real
  Chrome 120, 124 and 130 headers and the compiled pair against the rule. The hand-edited 153
  hint carried Chrome 137's GREASE brand (`"Not/A)Brand";v="24"`) where 153 sends
  `"Not_A Brand";v="8"` in a different order.
- Both fields are sanitized on parse (`BrowserHeaders.sanitize`): surrounding whitespace is
  trimmed, interior control characters and non-ASCII are rejected back to the compiled
  default. OkHttp throws on a control character at request-build time inside a `runCatching`,
  so one stray newline in a pushed bundle would kill every scrape silently.
- The UA is a **desktop** string on purpose. Mobile web Maps serves different markup and
  endpoints, so switching is a recalibration of every parser, not a header edit. The
  `Sec-CH-UA-Mobile: ?0` and `"Windows"` platform hints track it.
- **The six WebViews present the same identity (`app/web/WebViewIdentity`, 2026-09-22).** Every
  hidden WebView (the five fetchers and the reviews panel) applies the calibrated `userAgent`
  and user-agent metadata built from `secChUa` (the brands, mobile `?0`, platform Windows,
  x86 64-bit, a matching full-version list) through androidx.webkit, feature-gated. Measured on
  a device with a header echo before this: a WebView with an overridden UA string still sent its
  own hints, `"Android WebView";v="153"`, `sec-ch-ua-mobile: ?1`, `sec-ch-ua-platform:
  "Android"`, under the Windows Chrome UA, and the high-entropy ones (`sec-ch-ua-model: "K"`,
  platform version 10.0.0) when asked. After: Chrome 153 brands, `?0`, Windows, x86, 64, on
  Vanadium 153. A pushed `userAgent` now reaches the OkHttp client and the WebViews together.
- **`X-Requested-With: <package name>` goes out on EVERY WebView request and no app can stop
  it.** Chromium's removal of the header (M112, a deprecation trial) was abandoned: its feature
  list marks the androidx allow-list API "disabled since the XRW origin trial ended", and
  WebView's own tests assert the header is the package name on main-frame and sub-resource
  requests, on Google's WebView as on Vanadium (where the API reports unsupported). So the
  WebView-backed features (reviews, photos, popular times, transit directions, the stop-board
  fallback, the reviews page) tell google.com "app.vela" by name; search, directions and the
  ambient fan-out (OkHttp) do not. `loadUrl(url, headers)` could replace it on the document
  request only, and the page's own script requests (the review feed's POSTs) would still carry
  it, so that half-measure is not taken. The `VelaWeb identity:` log line records the WebView
  package and whether each switch took.
- The compiled UA tracks Chrome's CURRENT stable on Windows (chromiumdash `fetch_releases`,
  channel Stable, platform Windows); it was one major ahead of stable for a week, which is a
  browser that does not exist. Recalibrate it to the shipping major, not the next one.
- google.com's `Accept-CH` asks for `Downlink` and `RTT` only (checked 2026-09-22), so the XHR
  header set carries both (`Downlink: 10`, `RTT: 50`, Chrome's rounded values on a good link) and
  the document fetch does not, the order a real session has. The high-entropy UA hints are not
  requested there, so only the low-entropy three matter and the WebView metadata's full version,
  platform version and architecture are plausible rather than load-bearing.
- Residuals that are not fixed and not worth chasing: Chrome sends `X-Client-Data` (its
  variations proto) to Google origins and neither client here does; the WebView and OkHttp keep
  separate cookie jars, so one phone is two sessions from one IP; the two clients differ in the
  TLS and HTTP/2 fingerprints below.
- Measured 2026-09-23 (tls.peet.ws, `ja4` / `peetprint` / HTTP/2 `akamai_fingerprint`):
  desktop Chromium 152 on macOS and the Android WebView (Chromium 153) send the SAME ClientHello
  and the same HTTP/2 settings (`t13d1516h2_8daaf6152771_806a8c22fdea`,
  `1:65536;2:0;4:6291456;6:262144|15663105|0|m,a,s,p`), so a desktop UA over a Chromium stack is
  coherent. OkHttp is unmistakable: `t13d1513h2_8daaf6152771_eca864cca44a`, no GREASE, no ECH
  or ALPS, HTTP/2 `4:16777216|16711681|0|m,p,a,s`. Cronet 143 (the newest Chromium-licensed
  `cronet-embedded` on Maven; the 500.x artifacts carry the SDK license) matches Chromium 153
  except for three signature algorithms newer Chromium offers (0x0904-0x0906, ML-DSA), which moves
  its `ja4` to `..._d8a2da3f94cd`.
- The browser window a request describes is per install (`BrowserViewport`, 2026-09-23): the search
  and directions `pb` carry the map's pixel size (`!3m2!1i<w>!2i<h>`) and four rectangles the page
  chrome covers (under `!30m28` in search, `!20m28` in directions), captured from one 1024x768
  window, and autocomplete claimed 1080x2000. Each install picks one common maximized desktop
  Chrome viewport once (`browser_viewport` pref, weighted toward 1920x945) and `SearchPb.build`,
  `DirectionsPb.build` and `suggest` rewrite those fields to it; a recalibrated template without the
  captured shapes is left untouched. The health probe runs at 1920x945.
- Every fixed wait before a Google request is drawn through `core/util/Jitter` (+/-25% by
  default, +/-50% on retry backoffs): the nav recheck, the directions retries, the slim-pool heal,
  the neighbor prefetch gaps, the photo-load stagger and the review retry. An exact 120 000 ms beat
  is a rhythm every install would share. OSRM's retry backoff takes the same spread.
- Do not chase a TLS fingerprint. Matching Chrome's JA3/JA4 and HTTP/2 frame ordering needs a
  custom TLS stack, permanent maintenance and native dependencies, and it breaks reproducible
  F-Droid builds. The defense is diffusion (every user on their own IP), not disguise.

### 3.7 Hidden WebView scrapes

**What a place tap loads (2026-09-23).** The tap's search reply already carries popular times, the
review count, hours, the editorial blurb, the owner description and ONE hero photo. The details page
(`WebPopularTimesFetcher`) loads only when that reply lacks popular times, the review count, an
address or a weekly hours list; it used to also require an owner description, which most businesses
lack, so it loaded on nearly every tap. The photo walk stops at `FIRST_PHOTOS` (6, `early = true`:
the script finishes as soon as it holds that many, the result is never cached as the gallery) and
the strip ends in a "More photos" tile (`MapViewModel.loadAllPhotos`, the full walk); the review
scrape stops at `FIRST_REVIEWS` (10) and the All reviews page holds the rest. Settings >
Performance "Load all photos and reviews" (`FullPlaceLoad`, pref `place_full_load`, off) restores
the whole walk and 50 reviews. Each hidden page is Google's full web app, a few hundred requests per
load, and request volume from one network is what puts it into Google's limited view (the 5-review
feed, issue #602). Measured on the 4a: the first-batch walk finishes at about 3 s, where the full one
takes about 14 s; "More photos" then walked 80.

Five fetchers plus the visible reviews panel run Google's own JS anonymously, because a
rendered page is served data a bare request is not. They share `app/web/HiddenWebView`, which
owns the view (JS, DOM storage, desktop UA, the `VelaBridge` result channel), a request id per
page load so a late poller can only complete its own request, the idle reap and the
memory-pressure reap, the sleep between fetches (`session { }` pairs `onResume` before with
`onPause` after), the non-http scheme block, and console error logging under `VelaWeb`. A
fetcher is its URL, its extractor script and its parser.

Rules that hold for all of them:

- `onResume` at the start of a fetch and `onPause` when the last pending fetch finishes. A
  loaded Google page keeps its compositor and JS timers running indefinitely, which measured as
  roughly 27 percent of app CPU during a plain map pan. `pauseTimers()` is process-wide and is
  deliberately not used.
- Desktop UA. A mobile UA makes Google deep-link to `intent://`.
- Block every navigation after the initial load, and every non-http(s) scheme.
- Use a `Handler`, not `View.postDelayed`: a headless WebView never attaches.
- Size the view explicitly. The viewport is CSS pixels times density (1200x1000 CSS for
  reviews, 1200x3200 offscreen for the photo walk); a raw 1200 physical pixels is about 450
  CSS pixels on a 2.75x phone, which is Google's narrow layout.
- Warm them **after** results land, never before a search: creating two Chromium instances on
  the main thread ahead of a cold search held results at 13 seconds against 4 seconds warm.
- In a Kotlin raw string, `${'$'}{x}` is not a template. Writing it that way emits the literal
  text into the page and the whole script dies with a syntax error; that silently killed the
  review scrape for a week. Every script reports a start marker and any JS error through the
  bridge, and a `WebChromeClient` logs console errors, so the next one is visible.

**Reviews.** The place's `?cid=` page, where `cid` is the low half of the feature id as
unsigned decimal. Cards are `.jJc9Ad` with a unique `data-review-id`, accumulated across scroll
windows and de-duped by that id, keeping the **longest** text seen per id (a card harvested on
the tick its More toggle was clicked is still truncated). The Reviews tab is opened by
`[role="tab"]`, clicked until `aria-selected`; a selected-but-loading list is never re-clicked,
because re-clicking restarts its render. The idle bail is gated on cards being rendered at bail
time, checked per tick rather than latched, because the overview's preview cards latch just
before the tab click blanks the panel. Patience is 8 no-growth ticks and 6 at-bottom ticks.
Every text test on a tab or button runs through `STRIP_PLACE_NAME_JS` first, because Google
embeds the place name in those labels and a place name can contain a review word in some
language.

**The reviews panel** (`ReviewsPanel`, the default path) carves Google's own reviews pane in a
visible WebView. Load-bearing carve rules: `vh` units are 0 in an embedded WebView, so
everything is sized in pixels; the ancestor chain must be un-clipped and un-transformed or
nothing paints; the scroller is stretched only after the Reviews tab reports selected;
disallow-intercept is re-asserted on every touch event or the Compose sheet steals the gesture;
Google's summary block is faded with opacity and only collapsed with `display:none` after the
feed has been healthy about 5 seconds, because removing it from layout while the virtualized
list mounts corrupts the list's offset math and unmounts every card. Scroll handoff to the
sheet **forwards raw deltas**; it must not transfer gesture ownership, since Compose has
already abandoned a stream whose early events the WebView consumed. The feed watchdog keys on
relative-date texts, not class names, because Google A/B-serves builds with rotated classes.

**Language.** Both review paths follow the app language (`reviewsHl()`), because the page
language decides which reviews Google serves and reviews are content, never translated for the
reader. Every English text test is backed by the per-language tables in `:core ReviewWords`
(review, more, sort, star, ago, write, like, share, actions, all, processed), remotely
overridable through calibration `reviewWords`. Ratings read the leading number, which every
language leads with. The more-reviews button requires a review word **and** a more word, or a
bare match clicks the review composer. The sort menu is clicked by index, because several
languages label the button with the current choice rather than a sort word.

**Photos.** The same page, walked: tabs are visited (Menu, Food and drink, Vibe, By owner),
each tab's photos tagged, then "All" is swept for the rest. Photos are keyed off
`jsaction*="review.openPhoto"`, never the aria-label, because Google labels some photos
descriptively. The URL reads from inline background, computed background, or a child `<img>`.
Partial results stream whenever the accumulated set grows.

**Popular times** need a **specific** query, name plus address. A bare-name WebView search
returns a 20-result list trimmed of `[84]`; name plus address resolves to a single focused
result that keeps it.

**Transit directions** go through the page because a keyless transit request is silently
downgraded to a driving reply. The payload is the **longest** `)]}'`-guarded string under slot
`[3]`, polled for because the SPA fills it after page-finish; a small stub sits beside it.

### 3.8 Recalibration procedure

1. Capture the live request in browser devtools or a proxy. Mask the query and any coordinates
   before saving the capture anywhere.
2. For a moved **field**, edit the path in `calibration.json` `paths` (or `directionsPaths`,
   which merges key by key over `Calibration.DEFAULT_DIRECTIONS_PATHS`).
3. For a moved **endpoint or pb**, edit the template. The host must stay on the allowlist.
4. For a reshaped **response**, ship a `transformsJs` bundle (section 11).
5. Bump `version`, run `./scripts/sign-calibration.sh`, and commit `calibration.json` with
   `calibration.json.sig` in the same commit.
6. Keep the compiled `Calibration.DEFAULT` values in step at the next release.
   `DEFAULT.version` deliberately stays 1 so any remote bundle wins the newer-version check.

---

## 4. Routing and navigation

### 4.1 Which engine answers

Routing is open by default. Google is the traffic source and the fallback router, not the
primary, because its keyless steps come back abbreviated on longer routes (a 6-mile route
returns 2 of about 10 turns) while OSRM returns every turn with street names, refs, exits and
lanes.

Order for a planning request:

1. **FOSSGIS OSRM** `route/v1` per mode (`routed-car`, `routed-bike`, `routed-foot`),
   `steps=true`, `geometries=polyline6`. Three attempts with backoff.
2. **Google directions**, fetched in parallel, for the in-traffic duration, the typical
   spread, the per-segment congestion spans, and its own alternates. Also three attempts.
3. **On-device obf** when an avoid is requested and a covering region is installed, or when
   nothing online answers.
4. **Valhalla** for bicycle when safety weighting is on (section 4.3).

Request the geometry at `polyline6`. The default `polyline` is 1e5-scaled, a 1.11 meter
latitude grid, so every vertex of a straight road arrives pre-quantized and the puck has to
smooth out scatter Vela introduced itself. `TripLog` still writes 1e5 because changing it would
misread every existing trip file.

### 4.2 Traffic, calibration and the divergence snap

**ETA calibration.** OSRM's free-flow model has no signal timing, so on signalized arterials
its duration can run far under Google's typical for the same road. `applyTraffic` rebases a
same-course OSRM route onto Google's typical: route, leg and per-maneuver durations scale by
`(googleTypical * distanceScale) / osrmFreeFlow`, clamped to 0.5-3.0; the shown ETA becomes
Google's in-traffic figure; `trafficRatio` stays traffic-versus-typical so the color and the
words do not turn red from OSRM optimism. One calibration is computed per response and applied
to every OSRM-derived route in it, because the alternates share the speed-model bias. The basis
is whichever route follows Google's course: the top OSRM route when it does, otherwise the
via-snap. A multi-stop trip whose Google reply went through the stops is calibrated the same
way; only a reply that missed a stop (the direct trip) compares average speeds instead, so the
distance difference cancels, and goes through the same function with spans off.

**The divergence snap.** When Google's route strays more than 700 meters from OSRM's line
(`RouteGeometry.divergent`), Google is routing around a jam. `sampleVias` takes about 12
interior points of Google's polyline and `routeVia` routes OSRM through them, producing
Google's path with full OSRM steps. Constraints:

- Keep the via count modest. A via that lands on a turn is encoded as a via arrive/depart
  rather than a turn: at 60 vias about 1 in 10 named turns is lost.
- Map-matching would be cleaner and is not available: the public FOSSGIS `/match` caps at 10
  coordinates and public Valhalla `/trace_route` times out.
- The snap leads only when it earns it. Its live ETA must be within `SNAP_ETA_MARGIN` (1.2) of
  the calibrated OSRM free-flow best, or the clean OSRM route leads. When an avoid is on, the
  Google route is the avoiding one and this gate is skipped.
- Refuse a via route when any interior via snapped more than `VIA_SNAP_MAX_M` (40 m) from the
  requested point, or when the via route is longer than Google's course times 1.05 plus 400 m.
- Refuse a via route with a **spur**: project it onto Google's line and flag any stretch of at
  least 250 m that advances less than 35 percent of the distance traveled, with the first and
  last 300 m exempt. The per-fix variant resets a stretch only on normal progress (at least 80
  percent of distance traveled) and flags at 80 m traveled with under 45 percent progress. A
  via landing on an off-ramp snaps only a few meters, so the distance and length guards miss it.

**Congestion bands.** Google's spans are `[level, startMeters, lengthMeters]` on its own line.
Same-course routes map them by fraction. Every other case, alternates and multi-stop included,
goes through `RouteGeometry.transferSpans`: each span's stretch is sampled every 25 m and
projected onto the other route through a 0.005-degree cell grid (`SegmentGrid`); samples within
35 m mark that along-distance, runs become spans with an 80 m gap tolerance and a 40 m minimum.
What Google did not drive stays uncolored.

**Ordering.** Each Google alternate carries its own `duration_in_traffic`, so the list sorts by
`durationInTrafficSeconds ?: durationSeconds`, which is exactly the figure the picker displays,
with provisional routes as the tie-break. The sort key and the displayed value must stay the
same expression or the "fastest" tag lands on a row that is not first. Naming a picked
provisional route keeps its original Google figures rather than adopting the snap's recomputed
ETA, for the same reason.

### 4.3 Avoids and per-mode options

`RoutingPrefs.avoidTolls`, `avoidHighways`, `avoidFerries` mirror the chooser's sticky toggles
and are seeded in `VelaApp`. **Every fetch carries them**, including the ones `NavSession` makes
for itself (reroute, recheck, added stop, `nameRoute`); without that a reroute can route back
through what the plan avoided.

- Online: the flags ride the Google pb (section 3.4) and Google's own route honors them. The
  public FOSSGIS OSRM rejects `exclude=` for every value, so `OSRM_SUPPORTS_EXCLUDE` is false
  and the parameter is never sent; with an avoid on, the unrestricted OSRM routes are not
  offered as alternates.
- Offline: the obf car profile takes `avoid_toll`, `avoid_highway` and `avoid_ferries` as
  dynamic routing.xml parameters, so no baked profiles are needed. The vendored routing.xml
  declares `avoid_motorway` for the car profile and `avoid_highway` for horse riding, so the
  offline highway avoid is unverified on a device.
- The on-device avoid attempt is bounded (`AVOID_ONDEVICE_TIMEOUT_MS`, 4 s planning) and is
  deliberately **unstructured**: a structured child would pin the scope open until the
  non-cancellable native compute finished and defeat the timeout. The orphan finishes and is
  discarded.
- An engine that cannot honor an avoid returns empty rather than routing through what was
  avoided, and the online chain falls back to a normal route tagged `avoidNotHonored`. The
  chooser shows the note only when every route carries it.

**Bicycle safety weighting** (`RoutingPrefs.bikeSafe`, on by default): the obf bicycle profile
where a region covers the trip (6 s planning, 3 s urgent), otherwise Valhalla with `use_roads`
0.1. Valhalla maneuver types map into the OSRM grammar (`osrmGrammar`) and are phrased by
`osrmPhrase`, so voice, banner and step list are identical to every other route. No Google
traffic overlay for bicycle routes.

### 4.4 Multi-stop

`directions(origin, dest, mode, waypoints)` asks Google for the trip through the stops
(`DirectionsPb.withWaypoints`: one more top-level `!1m4!3m2!3d<lat>!4d<lng>!6e2` group per stop
between the origin and destination groups; no enclosing count changes) and routes the open
router through them with `routeVia`, the per-via arrive/depart filtered into one continuous
trip. `RouteGeometry.stopsOnLine` (250 m to the nearest vertex of Google's line, in trip order)
decides whether Google honored the stops; a reply that missed one is handled as the direct trip
it is. When both follow the same course the open route carries Google's time and spans; when
Google's course diverges the open router is snapped along Google's line leg by leg
(`sampleViasThrough`: each leg's samples with the real stop between them, the stops exempt from
the strict via-snap refusal through `routeVia(looseVias)`), kept on the single-destination reach,
length, spur and ETA-margin rules; with an avoid on and no usable snap, Google's own route through
the stops is returned as abbreviated steps. A waypointed trip returns a single route; neither
router offers alternates for one.
`NavEngine.stopMarks(route, stops)` projects each waypoint onto the line to its along-route
pass mark (null past 150 m off the line); `NavSession` holds the stops, the marks and a passed
counter and speaks one cue per stop in order. Reroutes and rechecks fetch with
`stops.drop(passedStops)`, so going off route keeps the stops still ahead.
`NavSession.setStops` is the one replan entry (`addStop` delegates to it): an unchanged list
fetches nothing.

### 4.5 Offline routing

`ObfRouteEngine` runs OsmAnd's pure-Java router and binary reader over `.obf` region files.
The same files answer the posted speed limit (`currentRoadLimit`) and supply romanized road
names (`Route.roadNamesLatin`).

- Region files are baked off-device (`scripts/build-obf-region.sh` plus `VelaObfShim`) and
  hosted as release assets with a manifest; the catalog is `tools/routing-regions.json`.
- **A turn OsmAnd marks `skipToSpeak` is a CONTINUE (`ObfRouteEngine.spokenType`).** The router
  emits a turn type for the road's own bend when nothing is there to choose, and OsmAnd's voice
  skips it; mapping the bare type spoke "turn left onto X" where a road only curved and renamed.
  As CONTINUE it folds into the previous maneuver as a rename. Roundabouts keep their type.
  **A left or right carrying under `STRAIGHT_TURN_DEG` (20) of measured turn is a CONTINUE too:**
  where a one-way carriageway joins its two-way continuation under lane markings the router
  emits `Turn left` with a turn angle of well under a degree and `skipToSpeak` false (measured on
  a state file). The angle is the router's own measurement; a real left is tens of degrees.
- A trip routes on the **smallest installed region box covering both endpoints**; boxes
  overlap at borders, so selection falls through to the next smallest. A trip that does not fit
  one region falls back online.
- **Long routes can fail outright, not merely run slowly.** Past its budget the router throws
  rather than degrading, and `MEMORY_MB` is 256 because the app already runs near its
  `largeHeap` ceiling. Measured on a desktop with the shipped configuration, car profile:
  4 km 0.87 s at 256 MB; 57 km 5.65 s at 256 MB; 151 km fails at 256 MB and takes 4.6 s at
  1024 MB; 348 km fails at 1024 MB and takes 41.9 s at 3072 MB. The threshold is
  region-dependent, not a fixed distance. Offline routing is therefore a city and metro
  feature; intercity needs OsmAnd's precomputed HH, which the bake does not generate.
- Three Android runtime requirements, all invisible until the engine logs:
  commons-logging's reflective discovery must be pre-pinned to `SimpleLog` before any OsmAnd
  class loads (R8 strips the implementation and the discovery NPEs on ART); `kxml2-vela.jar`
  is upstream kxml2 with its bundled `org/xmlpull/**` removed, because the stock jar duplicates
  platform interfaces and R8 hard-fails; and `PlatformUtil.setOsmandRegions(OsmandRegions(false))`
  plus `RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false` must be set at init, because
  `searchRoute` otherwise loads a world-regions index from the working directory, which on
  Android is read-only.
- The departure heading reaches the engine as `RoutingConfiguration.initialDirection` in
  compass radians.

### 4.6 The navigation loop

Every location update, in order: project the fix onto the route, advance the step, recompute
remaining distance and time, emit the events that produced (speak, vibrate, arrived, reroute),
announce a stop just passed, then consider a live-traffic recheck. Anything that stops
navigation stops all of it, because everything is downstream of that one call; that is the
mechanism the pause uses.

**Fix discipline.** Network (beacon) fixes are dropped during navigation and used in browse
only when GPS has been quiet for `NETWORK_FIX_QUIET_MS` (12 s). Inter-fix dt comes from
`elapsedRealtimeNanos`, never `loc.time`, which mixes GNSS UTC with the system clock. Fixes
with accuracy worse than 50 m never feed `NavSession`. The provider registration must keep
`minDistanceM = 0`: a distance filter starves fixes at a standstill. Measured speeds pass a
symmetric acceleration-bounded gate against the last accepted value with a two-fix persistence
escape; a one-sided spike filter self-latches.

**Off route.** The corridor is accuracy-scaled and mode-relative:
`NavEngine.offRouteCorridor(mode, accuracyM)` returns `base + K * accuracy` clamped per mode,
with foot tighter than bike tighter than drive. Defaults without an accuracy figure are
`OFF_ROUTE_M` 40 m and `FAR_OFF_M` 90 m, with `OFF_ROUTE_HITS` 3. A fix beyond the far
distance counts at any speed (parking-lot creep sits under the moving floor forever) and counts
double while moving. A moving fix whose course diverges by more than `HEADING_OFF_DEG` (60
degrees) from the route's local bearing counts as an off-route hit even inside the corridor,
and counts double when it is also a quarter-corridor off the line; a heading-diverged fix never
counts toward the on-route streak. Off-route distance is measured on the windowed, anchored
projection, never a global nearest, so a route that passes near itself cannot claim the puck.
`movingFloorMps` is 2.0.

**Rerouting.**

```
REROUTE_COOLDOWN_MS        10_000   minimum gap between adopted reroutes
REROUTE_FETCH_TIMEOUT_MS   20_000   one urgent attempt's deadline
REROUTE_FINISH_RESERVE_MS   4_000   deadline headroom left for adoption
URGENT_OSRM_TIMEOUT_MS      6_000   connect, read and call for the single urgent OSRM try
URGENT_GOOGLE_GRACE_MS      2_500   how long an urgent fetch waits for Google once OSRM answered
LADDER_OSRM_TRY_MS          8_000   one escalated OSRM try
LADDER_OSRM_SHARE            0.55   share of an escalated budget OSRM may spend
BACK_ON_COURSE_HITS             2   consecutive on-route fixes that discard a stale reroute
```

- The fetch is **unstructured** and its single-flight guard is **time-bounded**
  (`NavSession.rerouteGate`). `withTimeoutOrNull` only interrupts at suspension points, so a
  fetch wedged in non-cancellable work outlives its own deadline; without the time bound the
  guard then rejects every later reroute for the rest of the drive. Never gate rerouting on job
  liveness alone.
- Attempts **escalate**. The first `REROUTE_ESCALATE_AFTER` attempts are urgent single shots;
  after that the full ladder runs with the longer timeout. The failure streak resets on any
  adopted route and on session start and stop.
- A failed fetch clears the off-route latch on the location thread, and a request the cooldown
  turned away clears it too, so the next deviated fixes retry naturally.
- A reroute **pins its departure heading**: OSRM gets `bearings=<heading>,65` for the first
  waypoint only, with an empty entry for every later waypoint (the count must match the
  coordinates or the request is rejected outright and the reroute dies with it). Planning
  fetches send nothing, and a stationary fix sends nothing.
- When the open router gives nothing, `RerouteFallback.pick` takes Google's route if it is
  already back, otherwise races Google against the on-device engine inside the remaining
  budget, first non-empty wins. Google fallbacks are tagged `GOOGLE_ABBREVIATED`.
- A reroute that arrives after the driver is solidly back on the original route is discarded
  (`route === fromRoute && onRouteStreak >= BACK_ON_COURSE_HITS`). A single grazing fix must not
  be enough, or one spurious graze kills a legitimate missed-turn reroute.
- **A provisional route is never driven raw.** The three fetches `NavSession` makes for itself
  run their top candidate through `NavSession.driveable`: a provisional top is named, and if
  naming fails, a full-stepped open-router route from the same reply is preferred even when
  slower. A provisional Google alternate adopted raw arrives as one maneuver placed at the
  on-ramp the car is already on.

**Rechecks and faster routes.**

```
RECHECK_INTERVAL_MS          120_000   each wait drawn +/-25% (Jitter), redrawn per recheck
DEGRADED_RECHECK_INTERVAL_MS  20_000   while the adopted route is degraded, same spread
DEGRADED_FAST_TRIES                6   then back to the normal cadence
MIN_RECHECK_DISTANCE_M         1_500   stop rechecking near the destination
FASTER_THRESHOLD_S                90   minimum saving before an alternate is offered
SAME_COURSE_M                    250   within this of the current line is the SAME route
```

**The offer always resolves itself** (issue #594). It used to wait for an answer, which is a card
over part of the map asking a driver to decide with their hands on the wheel. A bar across the
bottom of the card drains over 10 s, the same however the UI is driven, and focus landing anywhere
on the card FREEZES it until focus leaves. A longer key-driven window was tried and argued down by
the reporter: someone driving with keys is less likely to answer at all, so extra time mostly means
the interruption sits on screen longer, while stopping the clock when they reach for it gives time
exactly to whoever wants it. Then it acts: by default it TAKES the route, which is what the offer exists for
and what Google does, and `FasterRouteAuto` off dismisses it instead. Leaving it on screen is not
one of the choices. The countdown is keyed on the offer itself, so the ETA moving or the speed
ticking cannot hand the driver their ten seconds back, and it is read in the draw phase so it never
recomposes the card.

A same-course candidate recalibrates the shown arrival time (`etaScale`, multiplicative,
clamped 0.5-2.5, applied at the publish site only, reset to 1.0 on every route swap) instead of
being offered. A candidate that is a genuinely different course and saves more than the
threshold is offered, never taken. A degraded candidate (trafficless or abbreviated) never
calibrates and is never offered as faster: free-flow always appears to beat traffic-aware. The
recheck also **heals** the adopted route: a full-stepped same-course candidate replaces an
abbreviated one and a traffic-carrying candidate replaces a trafficless one, never the reverse.

**Guidance.** Prompt and turn-now distances scale with speed, `max(fixed, v * T)` with T of 35
and 10 seconds; `spoken` stores band slots, not meters, so each prompt speaks the true
distance. A step's non-first prompts speak `NavStrings.repeatShort`, and a merge skips the far
band entirely. Maneuvers more than 75 m behind are caught up silently. Arrival is proximity
based (within 40 m crow-flight); no rerouting within 150 m of the destination or while
stationary except for the far-off rule. The DEPART maneuver is spoken once by
`NavSession.start` and skipped by the engine. ETA sums remaining step durations times the
traffic ratio, never remaining distance over average speed.

**Tunnels.** When the guidance feed goes quiet for more than 3.5 s while navigating, on route,
not replaying and not from a standstill, the view model synthesizes 1 Hz fixes along the route
at the last speed (decay tau 60 s, floor 1.5 m/s, cap 3 km) through the normal
`NavSession.onLocation` path. The "searching for GPS" chip stays up for honesty, the first real
fix re-anchors, and synthesized fixes are never recorded into a trip.

**Pause** (`NavSession.paused`). `onLocation` records the fix and returns before the engine, so
there is no engine update, no off-route detection, no reroute, no arrival, no stop cue, no
voice, no recheck and no faster-route offer. The puck keeps moving because it is drawn from the
raw fix, and the arrival clock keeps sliding on a 30-second tick. Resume reroutes once from the
current position when the stop took the driver off the route (perpendicular distance against
the corridor), otherwise speaks the current instruction. **Auto-resume is armed by the stop, not
by the pause**: a stationary or off-route fix sets `autoResumeArmed`, and only then do
`AUTO_RESUME_HITS` (3) consecutive moving, on-route fixes resume. Without the arming step a
pause taken at speed resumes itself three fixes later.

### 4.7 The puck and the camera

The drawn position during navigation is decoupled from the raw fix and both halves of the
motion are filtered.

- **Position**: `core/location/AlongRouteFilter`, a 1-D Kalman over meters-along. It
  dead-reckons at the modeled speed and folds each accepted fix in weighted by that fix's own
  reported accuracy (the along-route sigma is about 0.66 of the reported 68-percent radius,
  since snapping discards the lateral component). A genuine discontinuity calls `reseed`
  instead of averaging a teleport down.
- **Speed**: `core/location/SpeedKalman`. Each fix is the measurement update; between fixes the
  accelerometer steers the prediction from `MotionProvider` (raw `TYPE_LINEAR_ACCELERATION`
  plus `TYPE_ROTATION_VECTOR`, no GMS), projected onto the travel bearing. Accelerometer noise
  is suppressed by an `a^2 / (a^2 + n^2)` gain with `ACCEL_NOISE` 0.5, which leaves a 4 m/s^2
  brake within about 1.5 percent of itself while cutting 0.3 m/s^2 of vibration to a quarter.
  **Do not use a subtract-the-floor shrinkage**: it taxes a real brake by the floor and weakens
  the behavior the filter exists for.
- **Progress** advances in the rate domain: the puck always moves at the modeled speed and the
  position error enters as a bounded nudge to that rate (`PUCK_CORRECT_TIME_S` 0.6, catch-up
  capped at 1.5x speed plus 2 m/s, hold-back at 0.25x plus 0.5). Easing toward a target plus a
  monotonic clamp fight each other at the fix cadence and produce stall-then-lurch.
- **Blind reckoning** is capped at `DEAD_RECKON_S` (3 s). Each fix's advance is
  plausibility-clamped to `speed * dt * 2.5 + 60 m`.
- **Smoothing window** width eases toward its speed target with `PUCK_WIN_TAU_S` 2.5 s. The
  window width is a lateral position on a curve, so speed noise in the width becomes sideways
  movement.
- **Snap tolerance** is mode-aware: 22 m plus a speed term for driving, 8 m plus 1.2 times the
  fix accuracy capped at 16 m for walking and cycling. The heading gate is not consulted below
  2.5 m/s off-road.
- **Camera bearing** eases with an amplitude-adaptive time constant: `CAM_BRG_TAU_STILL` 1.6 s
  when the error is small, `CAM_BRG_TAU_TURN` 0.35 s past `CAM_BRG_TURN_DEG` 25 degrees.
  Geometry noise is a couple of degrees and a real turn is tens, which is what separates them;
  window width is the only lever on chord-bearing noise and a least-squares fit over the same
  window measures no better.
- **Cosmetic eases take a capped time step** (`dtEase` at most 65 ms). A main-thread hitch
  otherwise delivers one frame whose exponential eases jump 45-70 percent of their error at
  once. Integration keeps the real dt.
- Navigation zoom range is 18.0 to 15.5. Free-drive follow engages once per session and eases
  its bearing toward the GPS course with a speed-scaled look-ahead (`FREE_LOOKAHEAD_TAU_S`
  2.5 s); the follow target is a continuously integrated estimate (`FollowEstimator`) fed the
  **raw** accepted fix, not the low-passed one, with half of each residual spread over 0.9 s.
- **In follow mode the puck is a Compose overlay**, not a map symbol, drawn at
  `projection.toScreenLocation` of the same point computed right after the camera move. A
  GeoJSON source update goes through MapLibre's async worker tiling while `moveCamera` is
  synchronous, so a symbol lands on time or one frame late at random, which is one frame of
  travel of vibration against a calm map. Per-frame values are written to
  `mutableFloatStateOf` holders read in the draw phase so nothing recomposes per frame. The
  GeoJSON puck is still used when not following and in browse.

**Diagnosing a jitter report.** Measure before touching anything, in this order: track the
arrow in a screen recording (`scripts/jitter/puck_track.py`); check whether the map itself moves
or turns unevenly (`map_motion.py`, `rotation_fit.py`); check whether map renders are over
budget and whether the over-budget ones are evenly spaced, which indicates a periodic culprit,
or random, which is the device's floor (`gl_frames.py` over a Perfetto trace); check whether
Compatibility rendering is on, which it should not be on a healthy phone. A demo drive has zero
GPS noise, so if it jitters the cause is not GPS and no filter will fix it.

Eight independent causes have been found and fixed. Suspect something **not** on this list
before re-deriving one of them: unfiltered along-route position; ease-plus-clamp progress;
camera bearing following digitization wiggle; a rippling smoothing-window width; demo drives
running the puck clocks at 3x; a 60 ms main-thread stall per fix from a dictionary sort and a
per-fix maneuver re-projection; a 3 km route-line re-upload every 150 ms dropping a map frame
at 6.7 Hz; and the async GeoJSON puck above. Three things measured **worse** and must not
return: a per-frame LineString source for the moving route cut (a line re-tiles on every worker
thread; only a point source is cheap per frame), a whole-route line-gradient for the cut (256
texels over the route smears it into a `routeLength / 256` meter ramp), and smoothing the route
geometry for the arrow (the camera's turn rate through bends was already smooth to 0.02 degrees
per frame of jerk).

**The crash sentinel** that flips a device into Compatibility rendering (a TextureView map)
counts only `REASON_CRASH_NATIVE` on API 30 and above; counting any process death during map
init flipped healthy phones that had been force-stopped during testing, and the TextureView
renderer then sat at 89 percent of a core. The Developer row states the date it engaged.

### 4.8 Route line rendering

- **A paused drive draws the ahead line in `ROUTE_PAUSED_COLOR` (`#9C8AD6`, a muted lavender)**
  and the live traffic color returns on resume. Distinct from the live blue, the congestion amber
  and red and the driven gray, and visible on both themes; a slate gray was tried first and
  vanished into the dark map's road fill. A route color change re-anchors the split
  (`splitReset`) the way the trail toggle does, because the ahead line's gradient is only
  re-uploaded when the cut piece slides; without that only the 400 m around the arrow changed.

- The line is inserted **above all road and bridge geometry and below labels**: anchor to the
  first symbol layer after the last `bridge_*` layer, not simply the first symbol layer, which
  in Liberty is the one-way arrow beneath the bridges.
- The driven/ahead split is a **geometry** split: a traversed full line under an ahead-suffix
  layer trimmed at the puck, with traffic bands remapped onto the suffix. MapLibre bakes a
  line-gradient into a 256-texel texture and has no line-trim-offset, so geometry is the only
  pixel-exact cut.
- The **moving** cut is a paint-only update: `ROUTE_CUT_LAYER` is a 400 m piece over the ahead
  line whose `line-gradient` carries the actual cut at 1.6 m per texel, and its geometry slides
  about every 300 m. Never move route geometry per frame or on a short timer.
- With the driven trail off (`RouteTrail`, default off), the ahead line's gradient is
  transparent up to one texel before the cut piece's end, so the piece paints over nothing.

### 4.9 Road furniture, cameras and alerts

- **Traffic controls** (signals, stops, level crossings, speed humps) come from the per-region
  road-features bake, with Overpass as the fallback only where no region exists. Same-kind nodes
  within `CONTROLS_CLUSTER_M` (45 m) merge to their centroid, because OSM maps one node per
  approach and each approach's stop line sits well back from the middle: 30 m drew two lights at a
  wide four-way where one in the center says the same thing. Icons are sized for something you act
  on in the next few seconds (0.98 at z15.5 rising to 1.95 at z19). The browse layer draws from the
  same zoom its viewport FETCH uses, so the gate that decides whether to ask for them is the gate
  that decides whether to draw them; during navigation the corridor set draws from z15.4, just under
  the camera's 15.5 floor. What appears is bounded by OSM, which maps signals more consistently than
  stop signs, and unevenly between places: Delaware's bake holds 2,331 signals against 2,144 stops,
  while a western state's runs nearer three to one. A residential area can therefore show lights and
  no signs at all, whatever the layer is willing to draw. The corridor fetch is keyed per driven route so a
  same-course heal never refetches, capped at `CONTROLS_ROUTE_CAP` (800) nearest the start.
- **Ownership.** While a corridor set is loaded, the viewport path must neither refetch nor run
  its zoom-clear branch, and the viewport job re-checks ownership **after** its settle delay,
  not when it was scheduled, or a box set replaces the corridor set 350 ms later and paints
  furniture that is not on the route.
- **Stop signs are gated by bearing.** The road-features bake writes each control node's road
  orientation (0-179, undirected); `RouteProjection.alignedWithRoad` keeps a STOP only within 40
  degrees of the route's bearing there. A null bearing keeps the control, so un-rebaked regions
  behave as before. Gating a STOP by its distance to the driven line does **not** work: a
  clustered control sits at the junction centroid, not in the driver's lane.
- **Corridor queries run off the main thread** and through `SegmentIndex`, which simplifies the
  line to 3 m and buckets segments into 0.01-degree cells. A whole-region parse reads the
  inflated bytes once and parses decimals by hand into primitive arrays.
- **Surveillance (ALPR) cameras** ship as a bundled floor plus a hosted update
  (`FlockCameras`): a gzipped `lat, lon, operator, direction` TSV, about 124,000 points, loaded
  into flat arrays with a 0.1-degree grid index off the main thread. The loader compares
  versions and prefers the higher, deleting a download the bundled floor has passed. Route
  counts use a 45 m corridor. Badges cluster at 40 m below street zoom.
- **Direction rule** (`CameraFacing`): for a camera inside the distance gate, take the nearest
  non-degenerate route segment's bearing and compare as undirected lines, `min(d, 180 - d) <=
  50` degrees. A camera with no facing counts. Applied to route counts, the avoid re-rank, the
  alerts and the route bar; the map layer still draws every camera.
- **Alert timing** (`CameraAlerts.due`): `LEAD_SECONDS` 12, floored at `MIN_LEAD_M` 150 and
  capped at `MAX_LEAD_M` 600, silent below `MOVING_FLOOR_MPS` 2.0, once per camera per route,
  never for a camera behind. Cameras within 40 m along the route group into one alert.
- **Avoid surveillance cameras** re-ranks the alternates already offered, preferring the
  fewest-camera route within a small detour (at most the lesser of 25 percent of the ETA and 10
  minutes). It does not graph-route around cameras.
- **Try side streets around cameras** (off by default, nested under the re-rank) adds one
  candidate route when the leading route still passes cameras: `CameraDetour` groups the lead
  route's cameras into clusters (join distance 40 m, nearest first, at most 3) and offers the
  points 150 m to the left and right of the road at each; the chooser routes the trip through the
  left then the right point (merged into the stops in travel order), keeps a candidate whose camera
  count drops within the same detour cap, builds the next cluster on it, and sends at most 6 route
  requests per trip. A kept route leads the list with its badge and carries its waypoint plan
  (`Route.detourPlan`); a drive started on it carries the detour points as silent stops, which
  every reroute and recheck routes through and nothing speaks or lists. A mid-drive stops edit
  keeps the detour: `NavSession.withSilentVias` puts the silent points still ahead back in, each
  where it falls along the current plan route relative to the edited stops. Drive mode only.
- **Speeding alert** (off by default): the voice fires after 4 s continuously over the posted
  limit, re-arms after 8 s back under, at most once per 45 s, with the same 5 km/h tolerance the
  badge uses so the two never contradict each other.
- **Cross-street labels** are points placed at the crossing, not line-center labels on the
  basemap layer. Per crossing street, the pass computes where it meets the route window (first
  proper crossing in route order, else a T-junction end within 25 m, 60 m for a next-turn
  target), moves `NAV_XLABEL_OFFSET_M` (35 m) up that street to the side that ends farther from
  the route, trying a ladder of offsets on both sides and keeping the first with clearance,
  and uploads points with a `tier` property carrying the class split and zoom gates. Clearance is
  measured from the route to the bubble's ANCHOR, which is the tip of its tail; the chip body sits
  above that point and is wider than it, so the on-screen gap is always smaller than
  `NAV_XLABEL_CLEAR_M` (44 m) and shrinks further with camera tilt. Below `NAV_XLABEL_MIN_CLEAR_M`
  (26 m) no callout is drawn at all, because a chip clipping the road you are driving is worse than
  a missing street name. The pass
  runs once per 400 m quantum of progress or when the upcoming turn targets change, never on a
  short timer, and a quantum is only marked done once something was placed.

- **A region covers a point by its boundary polygon, not its bounding box.** `assets/region_polys.json`
  (baked by `scripts/region-polys.py` from the Geofabrik `.poly` beside each catalog extract,
  simplified to about 5 km, 425 regions, about 300 KB) is loaded once at app start into
  `RegionPolys`. `RoutingRegion.covers(lat, lng)` and `PmtilesRegionStore.Region.covers(lat, lng)`
  test the polygon when one exists and the box otherwise, and every region-for-a-point decision
  (the viewport download's routing, places, basemap and overlay picks, the streaming unions, the
  routing offer, the update kinds, the saved-area pack lookup, the road-features region, the Offline
  settings row) goes through them; the tie-break among covering regions remains the smallest box.
  A box is not coverage: an extract's box includes every outlying island and claim, so Vietnam's
  reached Hong Kong and Kansas's crosses the Missouri River.

### 4.10 Trips, replay and demo mode

- The trip format is canonical in `:core` (`replay/TripLog`): a `META` header, then one or more
  route blocks and `lat,lng,t,bearing,speed` fixes. **Trips are segmented**: the start route and
  every mid-drive swap is its own `RP`/`RD`/`M` block activated at the fix where it appears.
  Auditing or replaying a multi-block trip against one mashed route corrupts it.
- `RD` carries the route's provenance flags and source name. Every fix carries its provider and
  the engine's off-route hit count. Every nav decision is a `K` line written through
  `NavSession.onNote`; a `K` line never holds a coordinate by contract.
- **Unknown line kinds are dropped by `TripScrub`, not passed through.** The format is
  append-only, so adding a line kind requires deciding in `TripScrub` whether it is safe to
  share.
- Replay is **hermetic**: `NavSession.replayMode` suppresses live reroute and recheck fetches,
  recorded swaps play back through `replaySetRoute`, and the map scales the puck's clocks by
  `replaySpeedup`. The speedup applies to real replays only, never to demo drives.
- `NavReplay` replays fixes through the real `NavEngine` and diffs cards and voice against the
  maneuver positions, flagging silent turns, early announcements and lying distances.
  `TripLog.audit(csv)` is the one-call entry; the on-demand harnesses take `-DvelaTrip=<abs.csv>`
  and `-DvelaSeg=<n>`, forwarded to the test JVM in `core/build.gradle.kts`.
- **Demo drive** (`demo_drive`, off by default) densifies a planned route into one clean
  `ReplayFix` per second through `DemoTrace.fromRoute` and runs it down the same hermetic
  replay path. It is presented as real navigation: the stop-replay pill is hidden and End
  cancels the demo job, whose `finally` resumes live GPS.

### 4.11 Tap-to-stop during a drive

`MapPoiPrefs.navTapPlaces` (off by default; its setter also turns places on and remembers whether
it had to, so turning it off restores what was there). While it is on, the drive-nav places filter
widens from fuel-only to `NAV_DRIVE_GROUPS`, and a tap on a place does not select it: it becomes
`navTapCandidate`, which MapScreen renders as `NavStopOffer` above the nav bar.

- **The first tap only offers.** The card's button is the second tap, and it is the only thing
  that changes the drive. One stray touch at speed must not re-route anyone.
- **The card prices the stop.** One route through the candidate is fetched, bounded at
  `NAV_DETOUR_TIMEOUT_MS` (8 s), and `DetourEstimate.minutesAdded` compares it with the drive's own
  live remaining time. The candidate is first in the waypoint list, because that is where
  `NavSession.addStop` puts it; pricing any other order prices a drive the button will not build.
  A difference under `MIN_SHOW_S` (20 s) or over `MAX_PLAUSIBLE_S` (3 h) shows nothing at all
  rather than "+0 min" or a failed fetch's figure. The fetch touches nothing: the session keeps
  routing on what it already has.
- **The card takes itself away.** A countdown ring around the close button runs for 10 s, or 25 s
  under `dpadMode` where reaching the button takes more presses, and dismisses at zero. It is
  keyed on `navTapOfferTick`, which every offer bumps, because a second tap on the same place
  leaves the state equal and would otherwise leave the first clock running.
- **The offer is drawn on the map**, as a red "+" teardrop (`PoiIcons.CANDIDATE_PIN`) through the
  same effect that draws numbered stops and the destination flag, so the driver can see where the
  offer is before accepting it.

---

## 5. Places

### 5.1 Sources

Settings > Data and privacy > "Places come from" (`MapPoiPrefs.placesSource`, pref
`map_places_source`) has three values:

- **`open`** - Vela's own bake. The compiled default, and the fleet default through
  `calibration.json` `defaultPlacesSource`. Browsing the map contacts Google not at all.
- **`google`** - Google's ambient places only.
- **`both`** - Vela's data draws the map, and once panning stops one Google fan-out adds what
  it lacks.

The fleet default is remote (`Calibration.defaultPlacesSource` pushed into
`MapPoiPrefs.setRemoteDefault` at init and after each refresh). It applies only to users who
never touched the picker; an explicit pick always wins. `MapPoiPrefs.lookupTappedPlaces`
(default on) controls whether tapping a place looks it up on Google at all.

In **Both** mode Google wins a twin outright. `hideOpenTwins` is a debounced pass (400 ms and
2 s after the last upload) that queries only the **rendered** ambient icons on screen, matches
an open feature by agreeing name within `DEDUPE_NAME_M` (80 m) or the same normalized name
within `DEDUPE_SAME_NAME_M` (150 m), and filters those ids out of the open icon and dot layers.
It re-checks already-hidden ids with a filtered `querySourceFeatures` and releases any whose
Google partner has left the set, so an open place is never left hidden with nothing in its
place. Running the queries inline on every streamed partial upload cost up to 119 ms per upload
in a dense downtown. Google's copy wins because its coordinate is the storefront where Overture
stacks a building's tenants on one parcel point, and because its ranking comes from review
counts rather than a category prior. Offline nothing is hidden.

The same pass purges closures: an open icon matching a permanently-closed Google listing within
80 m, with no open listing of that name within 150 m, is added to the persisted closed set.

**OSM basemap business POIs** (`poi_r1`, `poi_r7`, `poi_r20`) are hidden outright under an open
places source when "OpenStreetMap shops too" (on by default) is turned off, because the three
open datasets cover businesses far better; with it on, `osmFillIn` drops an OSM business by name
wherever an open icon already draws it. Everything else OSM draws (museums, attractions, parks, schools, civic
buildings, places of worship, transit) stays, and `osmFillIn` drops by name only the
non-business OSM points an open icon of a non-business group already draws within 80 m. That
pass is viewport-only and rendered-only on both sides, grow-only within a source set (capped at
1,500 names, because every `setFilter` re-lays the whole source), skipped when the tiers are
hidden, and throttled to once per 2.5 s after a fifth of a screen or 0.4 zoom of movement. A
rendered query costs 37-55 ms in a dense view even when it returns nothing. **Never build a
`Regex` inside a per-feature loop**: compiling one per POI on the main thread produced an ANR in
a dense city.

### 5.2 The places bake

`tools/build-places-region.sh <id> S W N E out.pmtiles [release] [local.parquet]` runs DuckDB
over Overture Places (public S3 parquet or a local extract) and writes PMTiles.

- Business POIs only. Parks, schools, civic and transit are excluded; OSM covers those.
- **AllThePlaces** rows are pulled from the world PMTiles for the region (`pmtiles extract
  --bbox`, decoded with tippecanoe-decode and jq), filtered to business tags, mapped onto
  Overture's category names, and inserted where no Overture row of the same brand or the same
  two leading name words sits within about 150 m. Confidence 0.85, so a matched Overture row
  wins ties. Chains carry OSM-syntax `hours`.
- **OSM positions** win. The region's Geofabrik extract is filtered with `osmium tags-filter`
  to named business nodes and exported to geojsonseq (strip the 0x1e record separator before
  jq); `osm_snap` moves a baked row onto OSM's coordinate anywhere inside the duplicate box (~150 m;
  120 m for a chain) on the whole name or the core name (generic words removed), mutual best
  match only, so each node and each row pair at most once. The name keys keep letters of EVERY
  script (`[^\p{L}\p{N}]` is the separator, like `PlaceNames.PUNCT`); a Latin-only key made every
  name rule a no-op in non-Latin regions. A non-Latin place carries `name_en` (side table
  `names_en`: the OSM row's own tags, its name pair, or the chain dictionary `endict`), shown for
  a Latin-script UI on the places layer and the sheet, and used by the Both-mode twin test.
  The minzoom cell budgets are CAPS: prominence buys at most crank 6 at z14, rank 8 at z15, rank
  24 at z16 (it used to bypass the budget entirely); z17 carries every place. Prominence gains
  `srcbonus` (+0.6 OSM pair, +0.6 chain-locator match, +0.8 Wikidata) and the category prior puts
  food at 2.6 and offices at 0.5.
  ONE SET: OSM landmarks (tourism museum/attraction/gallery/zoo/theme park/
  aquarium/viewpoint, amenity place_of_worship/school/college/university/library/hospital/townhall/
  community_centre/theatre/arts_centre/courthouse/police/fire_station, leisure park/stadium/
  sports_centre/water_park/garden/nature_reserve, historic monument/memorial/castle/ruins/
  archaeological_site; points and polygons) are baked in; `landmark` rows have their own `lrank`
  budget per 1.6 km cell ordered by notability (outline size, Wikidata, and the number of languages
  OSM names it in: 0.6 x log2(1 + languages), capped at 3), which also picks each ~6.5 km cell's
  z11/z12 anchors; the app hides `poi_r*` over an
  archive whose `rev` >= calibration `tuning.placesOneSetRev` (compiled default 99999999 = off; the
  bundle sets 20260923, the first world bake that carries the landmarks, 448 archives). An archive
  older than the dial keeps the basemap's points, or its parks would vanish.
  Order of preference: OSM, then the AllThePlaces locator, then Overture's parcel point.
  Tenants never move.
- **Parks come from OpenStreetMap, so nothing else may filter them out.** The bake drops the park
  category (OSM has parks, mapped as areas with names), which means the basemap POI tiers are their
  only source: their vegetation exclusion covers wood, forest, tree, grass and wetland and must not
  cover park or garden, or a named park draws neither icon nor label in any mode.
- **Stacked points.** Overture puts a building's tenants on one parcel point. A tenant whose own
  address names a unit is snapped to the matching Overture address point (house number plus
  unit within about 200 m, street name ignored: a number plus a unit is unique that close and
  the two themes abbreviate streets differently). What is still stacked is spread on a golden-
  angle ring of 8 to 20 m with the best row left in place.
- **Tenants and kiosks** are flagged: a department of a nearby anchor (address, brand or
  name-head match), a kiosk category or name, or an anchor brand's fuel station or convenience
  shop within about 275 m. A tenant loses 2 prominence points and bakes at minzoom 17, except
  fuel, which stays visible for driving.
- **A forecourt says which one it is.** A brand's fuel station, charging bay or convenience shop
  is often published under the bare brand name, and since fuel is exempt from the tenant minzoom
  both it and the store draw, a few tens of meters apart, under the same label, so a tap on "the
  store" is a coin toss. A row whose name is EXACTLY its anchor's gets " Fuel", " Charging" or
  " Market" appended; a row that already names itself is left alone. Names are rewritten nowhere
  else in the bake.
- **OpenStreetMap is a SOURCE, not only a position.** Overture publishes monthly and nobody
  outside it can correct a wrong row; OSM is the one source in the stack a person can fix and see
  fixed, so named business NODES go in beside Overture's and AllThePlaces' rows, through the same
  tag mapping (`osmcat` / `isbiz`, shared macros), the same name-and-brand dedupe within ~150 m, and
  the same ranking. Confidence 0.8, under AllThePlaces' 0.85, so where another source has the same
  place that row wins. The row's id is the OSM node id, so it is traceable back to the object anyone
  can edit, and the tile's `origin` property says `osm`. Ways and relations stay out: a building's
  centroid is the same guess as the parcel point. Measured on a small country: 766 business nodes in
  the box, 521 added after dedupe.
- **AllThePlaces is the newest weekly run at bake time (2026-09-22)**: `build-places-region.sh`
  reads `data.alltheplaces.xyz/runs/latest.json` unless `ATP_RUN` pins one, with the last pinned
  id as the fallback when the fetch fails. It had been a fixed id, so every bake carried the same
  week-old locator data.
- **One row per business (2026-09-21).** The source dedupes only ever compared a NEW source against
  what was there, and Overture itself carries a business twice (a gas station under "Chevron" and
  "Chevron Station Davis", a shop under "SpeeDee" and "SpeeDee-Midas", a store and the counter
  inside it named after the store). After the last source is in, rows with the same snap key
  within ~60 m collapse onto one leader: not a kiosk category first, then the higher confidence,
  then the row that knows more (address, phone, website, hours); a hash join on the key with the
  box as the residual. The snap key mirrors `PlaceNames.normalized` (accents, parentheticals, "&",
  possessives, legal suffixes, store numbers) so a row the bake keeps is one the app can match.
  Fuel rows also key by their HOUSE NUMBER (`fuel@<number>`, within the same 60 m box): a station
  is one per lot and its rows spell the road three ways. **And the VARIANT family folds too
  (2026-09-22):** a CORE KEY is the snap key minus every word in `tools/place-generic-words.txt`
  (the app's `PlaceNames.GENERIC`, pinned equal by `PlaceNamesTest`; the bake anti-joins the
  unnested tokens because DuckDB refuses a subquery inside a lambda), and rows with the same
  non-empty core key within the box fold onto one leader: "Chevron Gas Station" onto "Chevron",
  "Walgreens Pharmacy" onto "Walgreens", "Starbucks Coffee Company" onto "Starbucks", "The Toasted
  Yolk Cafe" onto "Toasted Yolk". A core key that is only a street number or a single word under
  five letters is not a name and stays out (the app's strong-core rule), so "38th Street Deli" and
  "38th St Grocery" stay two rows, as do "Fine Art Gallery" and "Modern Art Gallery" (empty cores).
  The OVERLAP family stays app-side: it needs the kinds and the pool's shared words.
- **Whether a rebake is worth a delta is measured, not assumed.** `scripts/archive-churn.py` reads
  both archives' PMTiles directories, hashes every tile, and reports per zoom what is identical,
  changed, added and dropped plus a real `zstd --patch-from` delta; `places-churn.yml` bakes a region
  twice, against an OSM extract from N days ago and today's, so the difference is exactly what a
  scheduled rebake picks up. Kentucky over seven days: 1.3% of tiles, 3.2% of bytes, delta 4.4 MB against a 183 MB
  archive. Andorra over six days reads 11% / 25% / 22%, which is a small archive exaggerating what
  one edit touches, so measure a region the size of the ones people download.
- **A seventh of the catalog rebakes nightly**, so an OSM edit reaches the map on its own within a
  week, and `only=<region>` ships one region in about two minutes. Not the whole catalog nightly:
  every archive would be re-published every night, and anyone who downloaded a region would be
  offered a fresh copy of it daily, while streaming readers (the default) pick up a rebaked archive
  with no prompt at all.
- **The toolchain is cached and pinned.** tippecanoe was built from source in every job, which was
  69 s of a job that is now about two minutes, 414 times a wave, for a binary that is identical
  across them. The binaries live in a keyed cache; the key names the pinned tippecanoe and pmtiles
  versions, so a bump invalidates it.
- **Every S3 read prunes on `bbox`, never on the geometry.** Overture's parquet carries a plain
  `bbox` struct with row-group statistics, so a region filter written against it skips the row
  groups outside the region; the same filter written against `ST_X(geometry)` / `ST_Y(geometry)`
  has to decode every place on earth, once per region. That one statement was 504 s of a 570 s
  state bake, 414 times over; against `bbox` the identical rows come back in under 4 s. The
  geometry test stays as the exact filter and `bbox` is the pruning hint beside it (for a point
  xmin = xmax = lng, so the two select the same set).
- **Every row-to-row rule must be a hash join.** A correlated subquery or an OR of tests goes
  effectively quadratic over a state: with the joins fixed, a whole state bakes in minutes
  (one measured state: 244,954 places, 2,960 chain rows added, 2,749 tenants snapped, 524 s end
  to end). The duckdb heredoc runs with `.timer on`. The main heredoc is unquoted, so a backtick
  in a SQL comment executes as a command.
- Tenant demotion is a semi-join (`EXISTS`), not a LEFT JOIN: a tenant matching two anchors is
  otherwise emitted twice and lands in two ring slots.

Per-feature properties: `name`, `class`, `group`, `prominence`, `confidence`, `brand`, `addr`,
`website`, `phone`, `hours`, `src`, `origin`, and the cell ranks `frank` (about 100 m), `rank`
(about 400 m), `crank` (about 1.6 km) and `xrank` (about 6.5 km). `src` stays `overture` for
every row because the tap gate keys on it; use `origin` to tell the datasets apart.

**Prominence** is a category prior (4.5 landmark / 3.2 / 2.2 / 1.6 / 1.0) plus 1.6 for a brand,
0.5 for a website, 0.4 for a phone, 0.2 for an address, plus confidence.

**Baked minzoom** comes from the ranks: landmark categories and `xrank` 1 at z11, landmark
`xrank` at most 3 at z12, `crank` 1 or prominence at least 6 at z13, `crank` at most 2 or
prominence at least 5 at z14, `rank` at most 3 or at least 4.5 at z15, `rank` at most 12 or at
least 3.5 at z16, else z17; tippecanoe runs at `-Z11`.

### 5.3 What draws, by zoom

Density on the map is decided by rank, not by collision.

- Icons step by zoom: top 2 per coarse cell below z15, top 1 per fine cell at z15, `openRankZ16`
  (3) or `openPromZ16` (5.5) at z16, `openRankZ17` (8) or `openPromZ17` (5.0) at z17, then the
  `frank` block budget: `openIconCapNear` (8) at z17.5, `openIconCapClose` (16) at z18.5, and
  everything from z19.5 capped at `openIconCapMax` (40). All of these are calibration dials.
- A tile with no `frank` falls back to the 400 m `rank` with a quarter of the budget, so
  archives baked before `frank` existed do not draw every tenant.
- Default-group and health-group places need to be in the top `openGenericBlockTop` (3) of their
  block or reach `openGenericMinProminence` (4.0), else they stay dots.
- Everything below the cut still draws as a category-colored dot; dots are thinned by rank too
  (none below z15, `rank` at most 6 at z15, at most 15 at z16, all from z17), by opacity steps,
  because a filter cannot read zoom.
- Labels use the same steps as the icons, and at z17.5 and above only the top `openLabelCap`
  (20) per 400 m cell get a name. A label is glyph layout plus a collision pass over four
  anchors; a mall puts dozens in one cell.
- Tenants stay dots until z18.5 so the store owns the block. Icon overlap is allowed from z18
  on the open layer and z17 on the ambient layer; below those, Google's extras lose collision to
  the open icons and Both mode looks identical to Vela data.
- While a route preview is open, the OSM business tiers hide and the open layer filters to
  prominence at least `PREVIEW_LANDMARK_PROMINENCE` (5.5) with no dots. During drive navigation
  the open layer is fuel-only, and the OSM tiers follow the same rule.
- **The ambient GeoJSON source is maxzoom 18.** Past a GeoJSON source's maxzoom every visible
  overscaled tile lays out all of its parent tile's features; at maxzoom 12 a few hundred
  labeled places were placed many times per frame (22 to 51 fps from that one property).

### 5.4 Google ambient ranking

`ambientProminence = ln(reviews + 1) * (0.6 + rating / 10) + (categoryPrior - NEUTRAL_PRIOR) *
PRIOR_WEIGHT`, with `NEUTRAL_PRIOR` 2.2 and `PRIOR_WEIGHT` 0.9. Review count dominates, the
prior breaks the tie so a supermarket outranks the sushi counter inside it, and a place with no
category sinks.

The icon layer's collision sort key is `(10 - prominence) * 1000 + i`, never the list index:
the streamed pool re-ranks as terms land, and an index key reshuffles the whole layer's
placement on every partial upload.

**Sticky ranking** (`AmbientStability`). A settled view is painted several times with different
review counts for the same place: streamed partials, the twin-dedupe re-pass, and the slim-flavor
heal's second fan-out. `remember` freezes each place's prominence at its first rich paint; a pool
whose prominences are all zero is never remembered, because that is the slim flavor and freezing
it would pin the flatness the heal exists to fix. `prominenceOf` is what the view filter, the
cap, the collision order and the icon sizes all read, so later answers can add places but cannot
reshuffle what is drawn. `reset()` runs on the same pan and zoom gate that re-queries and
wherever the layer is cleared.

**Slim flavor.** Google's first few seconds of a fresh session serve a stripped per-place block:
rating yes, review count no. `nearbyPlaces` detects it (at least 3 rated, a majority of rated
entries missing counts) and refetches the fan-out once about 1.2 s later, prepending the healed
places so `distinctBy` keeps the rich copy. Before diagnosing a flat-looking ambient layer,
check whether the pool's counts are null.

**Fan-out discipline.** `nearbyPlaces` fires 15 category requests (8 on the lean path), each parsed whole into
a JsonElement tree of roughly 30 MB in a dense area. The fan-out is bounded by a `Semaphore(4)`
(`ambientFanoutPermits`, read at construction), which took a fresh-launch burst from about
400 MB of transient parse trees to about 64 MB and 13 percent janky frames to 2.3 percent. Do
not unbound it. Partial paints escalate their batch (10 places, then 25 once 60 are painted),
because each partial re-runs whole-layer placement.

**"Show places on the map" is the master switch for EVERY business layer**: the ambient dots, the
open (Overture) layer and the OSM fallback business icons. It predates the open layer and covered
only the first two, so once the open source became the fleet default, turning it off left exactly
the places it exists to hide still drawn (issue #597). `refreshPlacesOverlays` checks it alongside
the source, and `onPoiPrefsChanged` re-runs that path so the toggle acts at once rather than on the
next camera idle.

**Offline the fan-out does not run at all.** `maybeLoadAmbientPois` returns before launching when
`offlineNow()`, placed AFTER the cache repaint so an area visited earlier keeps its dots and only
the network is skipped. Thirteen requests that cannot succeed are cheap with the radio cleanly off
and expensive on a FLAKY link, where each one hangs to the call timeout; that is the case the gate
is for. Measured with the network off: 0.8% of CPU over thirty seconds of panning, four MapLibre
HTTP lines in the whole window, no retry storm.

**Caching.** An empty `nearbyPlaces` result is never an answer and is never cached: each term
swallows its network error into an empty list, so offline returns an empty success. The ambient
path treats null and empty identically, keeps what is painted, serves the freshest covering
store entry regardless of age, and leaves `lastAmbientCenter` unset so the next settle retries.
The durable store is `ambient_cache.json` (32 areas of 200 slim places, 14-day TTL,
write-through from non-empty results only) and drops blank areas at load. Entries carry their
fetch span and a hit is within `span * 0.45`; a fixed radius misses most legitimate revisits.
The cache repaint is unconditional, and a partial paint never shrinks the painted set. A fetch
under `AMBIENT_FRESH_MS` (3 min) old that still covers the view is served as-is, because
Google's ranking jitters between identical same-area requests and the tap-frame camera shift
trips the moved gate.

### 5.5 Tap resolution

A single tap resolves in this order, each candidate class picking the feature **nearest the tap
in screen pixels**, never render-stack order:

1. a search-result pin;
2. a saved pin;
3. a grayed alternate route line;
4. a business **or** a canonical GTFS stop icon, competing by distance rather than by class:
   the ambient Google dots and the named basemap POIs compete with each other and with the stop
   the same way. An absolute class priority lets a few-pixel dot anywhere in the box steal a tap
   landed on an icon, and it made a corner fuel station open the transit stop beside it;
5. a house-number label, which resolves through the reverse-geocode but **keeps the tapped
   number**: the geocode supplies street and city, and a regex replaces whatever house number it
   led with. Google's reverse geocode snaps to the nearest addressable point, which for a tapped
   label is routinely the neighbor. The tile's road name vetoes a mismatched geocode street
   unless the geocode's house number is exact;
6. an unnamed POI icon, reverse-geocoded at the tap;
7. a building footprint, reverse-geocoded at the tap;
8. nothing. Only a long press drops a raw coordinate pin.

**What is drawn under the finger wins.** Before ranking anything, the tap asks MapLibre what is
rendered at the finger's own pixel on the business ICON layers (ambient, open places, the basemap
POI tiers); when something is, only those candidates compete. Ranking the whole 24 dp box by
distance to each feature's POINT is wrong twice over: a place icon is a teardrop anchored at its
tip, so the blob being aimed at sits about 40 px above the point the distance is measured to, and a
tenant's DOT is drawn on its own point, so a dot meters away routinely measures nearer to the finger
than the icon under it. With no icon under the finger the box rules still run, so a dot stays
tappable.

The named-POI resolve is **name-agreeing first**: the pool is the listings whose name is the same
business as the tapped label under the shared rule below (`PlaceNames.agree`, with the town out of
the listing's address as a generic word).
Only an empty pool falls back, and the fallback is bounded twice: a non-transit tap never adopts
a listing whose category is transit or map furniture (`JUNCTION_CATEGORIES`), and a listing that
does not agree by name must be within `NO_NAME_MATCH_M` (60 m) rather than anywhere inside the
1.5 km cap. Nothing left to adopt means the tapped label keeps its own name and point.

The order of the pool is: name matches within the 1.5 km cap (a name match farther away never
blocks the fallbacks, since a brand's other stations miles off otherwise emptied every nearby
option), a cross-script second search, a listing of the tapped kind within 60 m already in the
results, `kindBesideAnchor` (one extra search for the tapped kind around the nearest name-agreeing
listing on the lot, nearest same-kind hit within 60 m of that anchor), then anything within 60 m.

**The house number gates it.** Distance cannot tell a fuel station from the one across the
junction, 40 to 80 m apart. When the tapped row has an address with a leading house number and a
candidate does too, a different number rules the candidate out of every fallback without a name
match and out of name matches beyond `SAME_LOT_M`; on the lot a mismatch is tolerated (open data
numbers are sometimes wrong) and an agreeing number wins. Either side without a number decides
nothing.

The pick must also be near the tap: a settlement label accepts a hit within 30 km, any other
label within 1.5 km, transit stops unbounded. A clear-dominance duplicate override
(`canonical.reviews >= 2 * nearest.reviews + 5`) runs **within** the pool only.

**A listing whose name IS the tapped name beats a nearer one.** The agree rule has to admit a
co-branded pair, so every listing a brand owns on that lot qualifies - the store, its fuel station,
its pharmacy, its coffee counter - and the pick was then whichever sat nearest the tapped point.
The pool is filtered to exact normalized-name matches (`PlaceNames.same`) when any exist, and only
falls back to the loose pool when none do.

**The same-business rule (`core/util/PlaceNames`, 2026-09-21)** is ONE rule for the tap resolve,
the Both-mode twin hiding and, mirrored in SQL, the bake's dedupe; three private copies had drifted
and every drift was a duplicate icon or a wrong tap. It was derived from a side by side of Google's
answers and the open archive over the Davis fixture (495 Google places in the archive's box: 290
exact after normalization, the rest of the real twins in the three families below, and the false
positives the old two-shared-words rule produced). `normalized` folds accents, drops parentheticals,
reads "&" as "and", keeps a possessive on its word, drops legal suffixes (LLC, Inc, DDS Inc), chain
tails ("by Wyndham", "an Ascend Collection Hotel"), a leading "The" or "Dr" and a trailing store
number, expands street abbreviations (St, Ave, NY) and joins dotted initials ("U.S." is "us").
`match(a, b, extraGeneric)` answers EXACT (normalized equal), VARIANT (one name is the other plus
only generic words: "Circle K | Gas Station", "U.S. Bank Branch", "CVS" against "CVS Pharmacy",
"Hilton Garden Inn Davis Downtown", "Petco Grooming"), OVERLAP (nested with non-generic extra
words, "SpeeDee-Midas" over "SpeeDee", or not nested but two identifying words in common, "Davis
Dental Creations" plus the dentist's surname) or NONE. `GENERIC` is the list of words that
describe a business rather than name it (category, structure and place words, street types), and
a caller adds the town out of an address plus `localGeneric(names)`, the words three or more names
in the pool it compares against share (a neighborhood, a mall, a landmark: "Bryant Park",
"Flatiron", "Memorial Heights"), which is the IDF the app cannot compute globally. Three more
families came out of a Midtown Manhattan and downtown Houston side by side (2026-09-22): a BRAND
PREFIX of two or more words with an identifying one among them ("Bank of America Financial
Center" and "Bank of America ATM"); the shorter name, less generic words at its ends, as a PHRASE
inside the longer (three words carry it even when the only identifying one is a street number,
"23rd Street Dental"; two words need a word that is not); and the shorter name's identifying words
all inside the longer's when the longer LEADS with them ("Laurenzo's Restaurant" and "Laurenzo's
Prime Rib"). Plurals fold pairwise ("Sola Salons" and "Sola Salon Studios"), "Dr." is "doctor",
and a nested or subset match needs a strong core (two identifying words, or one of five letters
that is not an ordinal): "The Finn" is not "Dish Society at Finn Hall", "Bayou Place" is not
"Bunnies On The Bayou". Measured through the real rule over the four areas: Davis 72% of Google's
places linked, a suburban corridor 68%, Midtown 81%, downtown Houston 83%; the rest are mostly
places the archive does not have.

**Other languages (2026-09-22).** The generic list is the UNION of one table per app language
(en fr de es it pt nl sv pl ru uk hu he, about 1,800 words after folding): the names on a map
belong to the region, not to the phone, so no language is picked; "Boulangerie Paul" is "Paul",
"Aral Tankstelle" is "Aral", "Аптека Ригла" is "Ригла" whatever the UI language, and a word that
is a descriptor in one language and a name in another is accepted as rare. Legal forms (GmbH,
SARL, S.r.l., S.L., Lda, B.V., AB, Sp. z o.o., ООО, Kft, בע"מ) and street abbreviations (Str.,
Av., Bd, ул., просп.) join the English ones, and the letters NFKD leaves alone fold too (ß, æ, ø,
œ, ł, đ, ё). Names in scripts written without spaces (Han, kana, Hangul, Thai) get no tokens and
are compared as strings by `cjkMatch`: descriptor suffixes and prefixes (店, 支店, 薬局, 銀行,
餐厅, 超市, 有限公司, 지점, 약국, สาขา...) are stripped from both ends, equal cores are a
VARIANT ("星巴克咖啡" and "星巴克"), a core inside the other is an OVERLAP (the extra is a branch
name: "スターバックス 渋谷店"), and a core shorter than two characters matches nothing. The bake's
word file carries the same union. Three rules from a Berlin and Tokyo side by side (2026-09-22):
Europe's brands are four letters (Lidl, Aldi, Rewe, Aral, Esso, Ikea), so a four-letter word
carries a nested or subset match when it LEADS both names, the shorter name has words of its own
and the longer adds exactly one identifying word ("Kolo Coffee" inside "Kolo coffee klcf shop"),
and two names that both reduce to one four-letter word ("Lidl", "Lidl Deutschland") are one
business under `sameBusiness` when their kinds agree; a bare four-letter name still claims nothing
("Hair" against "Hair Studio", "The Finn" against "Dish Society at Finn Hall"). Country names and
food descriptors are generic in German (Deutschland, Essen, Küche). A name glued into one word is
read as its words when a run of the other name's words spells it ("greengymberlin health and
fitness club" and "Green Gym Berlin"; eight letters or more). Measured through the rule: Berlin
links 75% of Google's places to the de-berlin archive, Tokyo 38% under `hl=en` and 65% under
`hl=ja`: Google names Tokyo's places in English for an English phone while three quarters of the
archive's names are Japanese, and Overture carries no alternate-language names there, so no name
rule bridges the two. The tap resolve bridges it instead: when nothing agrees by name and the
tapped label is in a script Google answers differently (`NameScript.scriptLanguage`: kana or Han
inside Japan is `ja`, Han elsewhere `zh-CN`, Traditional over Taiwan, Hong Kong and Macau, Hangul
`ko`, Cyrillic `ru`, Hebrew `iw`, Thai `th`, Arabic `ar`, Greek `el`; Latin decides nothing) and
that language is not the app's, `MapViewModel.crossScriptCandidates` runs the same search with
`hl=<that language>` (`MapDataSource.search(lang)`), keeps the listings that agree, and fetches the
nearest one's copy in the app's language by its own name so the sheet keeps the app language's
category and hours; the copy replaces it when the feature ids match, else the foreign listing
stands. Two requests at most, only on a cross-script miss; the `VelaTap` line carries `cross=N`. A single shared identifying word is NOT a match ("Arroyo Park" against
"Arroyo Pool"), a name made only of generic words matches nothing by overlap ("Hair" inside "Hair
Studio"), and a brand's other listing is a VARIANT that `same` keeps out. Pinned by
`PlaceNamesMatchTest` with the fixture pairs. Two rules take the places' KINDS (the icon group)
as well: `sameBusiness(a, kindA, b, kindB)` refuses an OVERLAP between two known, different kinds
(a fuel station and the pizza place on its lot can share their identifying words), while EXACT and
VARIANT still cross kinds ("Safeway Pharmacy" and "Safeway" are one business in two listings);
and `sameFuelLot(kindA, kindB, distance, numberA, numberB)` calls two fuel stations within
`FUEL_LOT_M` (30 m) one station whatever their names, because a forecourt is one per lot and the
sources name it after different things (the brand in the archive, the operator on Google); two
known house numbers that differ refuse it at any distance, which is the two-stations-across-the-
road case, and 30 m is short of a road plus two setbacks. Ambient features carry `hn` (the
listing's house number) for it. Not yet in the rule: a
non-fuel department listing at the same point with a different name (the station's "Fast & Easy
Mart" beside "Chevron"), and Google's own two profiles for one business.

Sheet titles follow the app language's script: `NameScript.prefer(uiLang, google, label)` keeps
the map's own label as the title when Google's name is not in the app language's script and the
label is.

Offline, `offlineNow()` gates the reviews, photos, details, departures and tap-resolution
fetches: an open place shows its tile data or the listing remembered from an earlier online tap
(`openPlaceCache`, an LRU of 500 persisted to `open_place_links.json`), and nothing waits on a
host that cannot answer.

- **The remembered link is dropped when the app updates or the region's archive changes.** The
  open-place-to-Google link cache short-circuits the resolve, so a link made by an older, worse rule
  outlives the fix for it: a supermarket kept opening its fuel station after the ranking bug was
  fixed, because the tap never reached the ranking again. The recorded CLOSURES are not dropped;
  those are corrections, not a cache.

### 5.6 Search and results

- A query runs three pages of 20 over the viewport window. When the user's location is inside
  that window and the window is wider than about 2.5 km, one extra page runs over a 2.5 km
  window around the user and **leads** the list, because Google's own app weights distance the
  same way and the outlet next to you otherwise loses its slot to better-known places across a
  town-zoom window. Over another neighborhood no nearby pass runs.
- `SearchPb.build` stretches the template's baked window to the caller's real viewport span,
  floored at `MIN_SPAN_M` (1 km) and capped at 500 km.
- Ranking and the shown distances are computed from `rankFrom`: the user's location when it is
  within about 50 km of the viewport, otherwise the viewport center. The request bias stays the
  viewport. `plausibleBias()` discards any bias point within about 50 km of `0,0`, because a
  device with no GPS never moves the camera off MapLibre's default.
- The list ends in a "more results" row that pulls the next three pages and appends what is new;
  it disappears when a pull adds fewer than five or the query changes.
- A search from a close zoom **holds its view**: the fit skips the fly-out when the view is
  under `HOLD_VIEW_SPAN_M` (2.5 km) and at least `HOLD_VIEW_MIN_HITS` (3) results land in the
  visible strip.
- The camera frames the result **cluster**: pins are median-centered and outliers past 4 times
  the median spread (minimum 40 km) are dropped, so one stray far hit cannot zoom the map to a
  continental view. The fit consumes `lastCameraTarget`, or the else-recenter branch re-fires on
  the stale center one recomposition later.
- Filters are local to the fetched results by design and also drop the map pins (the sheet
  reports surviving ids upward).
- Result markers keep the app's own marker language with a red circle, and rated food results
  get a wide rating bubble. Pins collide by rank (`symbolSortKey` = result order, overlap not
  allowed); losers draw as small dots on a second source below, expanding back into pins on
  zoom. Pins anchor bottom; labels try below, then right, then left.
- Typed coordinates drop a pin: `MapLinkParser.parseBareCoordinate` matches the whole string,
  requires a decimal point in both halves and range-checks, so an address with numbers still
  searches.
- Network suggestions come from Google's own search-as-you-type request (`MapDataSource.suggest`,
  the keyless `/s?tbm=map&suggest=p` call biased to the viewport), which honors the location
  bias for a partial address; rows without a location are bare query rows that run as a search.
  When it fails or Google is off, the older search-endpoint + OpenStreetMap race answers. Every
  suggestion row carries a fill-in arrow that puts its primary text into the box, cursor at the end,
  without searching. A typed house address that the search results cannot place is geocoded
  through the same request and leads the results.
- Local suggestions (recent queries, recent places, saved and list places, and opted-in
  contacts) are computed **synchronously** on each keystroke before the debounced network
  fetch, so they are instant and are the only thing that shows offline. Contacts are loaded into
  memory once, because a provider query per keystroke janks. Dedupe against network rows by name
  plus coarse location, not by feature id, which local rows lack.
- One quick-category list (`ui/QuickCategories`) serves the map chips, search along route and
  in-nav search. Every query must be one the offline store expands, or the chip is dead offline.

### 5.7 Saved places, lists, parking and imports

- List membership matches on `ListPlace.matches` (id **or** stable feature id), never a bare id
  comparison: a Google place's id is derived from its name hash and a coarse coordinate, so a
  multi-listing chain can resolve to a different co-located listing next visit.
- `SavedPlace.of(Place)` carries an optional address, defaulted null so older payloads decode;
  every store's JSON sets `ignoreUnknownKeys` so a downgrade survives.
- Recents are timestamped under new preference keys; the legacy keys are read once for migration
  and left in place so a downgraded build still reads them.
- Imports accept GPX, KML and GeoJSON including Google Takeout (`core/data/PlaceImport`).
  **GPX is latitude first; KML and GeoJSON are longitude first.** A KML placemark with more than
  one coordinate tuple is skipped rather than pinned at its first vertex. Import takes name and
  coordinate only, plus an address where the format gives one, because a confidently wrong
  address is worse than no import. Ids are content-derived from the rounded coordinate, so
  re-importing the same file adds nothing, and `0,0` or out-of-range coordinates are dropped.
  The picker keeps a broad `*/*` last, because Android reports `.gpx` as an octet stream.
  `ImportResult` distinguishes added, nothing-new, wrong-format and unreadable; a bare count
  cannot say "this file came from another app". Never wrap the launcher in a bare `runCatching`:
  on a device with no documents provider the button then does nothing at all.
- Parking is one tap on the P button, with a history so an accidental overwrite never loses the
  car.


---

## 6. Map rendering

### 6.1 Basemap and fonts

The basemap is OpenFreeMap **Liberty**, loaded **by URL**. A bundled style blanks the vector
source on-device; the bundled `assets/styles/liberty-roboto.json` exists as an editable
reference and for offline work. **That asset is one minified line**: edit it with a script that
re-dumps with `separators=(',',':')`, or a pretty-print turns a one-line diff into thousands.

Labels render in Roboto from a self-hosted glyph set: Roboto composited over OpenFreeMap's Noto
per glyph, so every non-Latin script keeps full coverage, with the folders keeping the Noto
family names so the only style change is the `glyphs` URL. `ui/map/MapFonts` fetches the live
Liberty JSON at launch (so tile paths keep following the upstream snapshot), patches `glyphs`,
caches the result, and probes the font host; an unreachable host evicts the cache and the map
falls back to plain Noto rather than rendering no labels at all. A style fetch failure keeps the
last good cache for at most 7 days.

Offline, glyphs and the sprite are served from `file://` (`GlyphPackStore`, the `map-fonts`
release unzipped into `files/glyphs/`, about 200 MB on disk). `asset://` hangs the same way the
network does.

Vela's own zoom reads about one level lower than Google's for the same visible area (512-pixel
tiles). Compare against Google by matching the **visible area**, never the zoom number.

### 6.2 Palettes

Three color sets ship. `MapColors` (pref `map_palette`) picks Modern or Classic;
`ThemeMode.AMOLED` layers `applyAmoled` on top of `applyDark`. The style key carries the
palette, the theme and the AMOLED flag, so any change reloads the style. Every layer must be
colored in **all four** apply functions: an unstyled runtime `LineLayer` renders black.

**Modern** (the default, sampled from the Google app):

- Light: land `#f8f7f7`, roads one blue-gray fill `#aab9c9` for streets and arterials with
  casings equal to the land, service `#9bacbc`, motorway `#8aa4c0`, buildings `#e8e9ed` with
  outline `#d6d9e6`, vegetation `#d3f8e1`, water `#90daee`, plaza and parking `#dbe0e8`, trails
  `#7fcdb0`, pitches `#a9eac2`, campuses `#f0eded`, commercial and retail blocks `#fdf9ef`.
- Dark: land `#162640`, other landuse `#1c2638`, water `#000d2a` (darker than the land, and the
  inverted relationship matters), vegetation `#0d3847` (teal), buildings `#1c3b69` with outline
  `#2e3d6d`, minor roads `#3d5a77`, arterials and motorway `#476789`, casings equal to the land,
  service and alley `#2a4056`, trails `#167055`, pitches `#0d4956`.
- Bike paths (OSM `highway=cycleway`) draw teal, `#007b8b` light and `#1f8f9c` dark, split out
  of the trails layer, which keeps foot paths green. On-street painted lanes are not in the tile
  schema and are not drawn.
- 3D extrusions use the flat color at opacity 1 with the style light at intensity 0 and
  `fillExtrusionVerticalGradient(false)`. MapLibre's default light brightens extrusion tops by
  about 40 percent, and Google keeps buildings one color at every zoom.

**Classic** (the archived pre-sample look, selectable):

- Light: land `#f2f1ee`, water `#90daee`, park `#cfeccd`, grass `#d3f8e2`, wood `#c9f2da`,
  wetland `#cdeff0`, plaza `#ededed`, buildings `#dde1e7` with outline `#c4c9d1`, minor and
  secondary roads white with casings `#e4e6ea`, trunk and primary casing `#dadde2`, arterial
  fill `#f9d27a`, motorway `#f0b85a`.
- Dark: land `#242f3e`, water `#17263c`, park and grass `#2c4a34`, wood `#274330`, wetland
  `#26403c`, plaza and other landuse `#2a3546` at opacity 0.5, buildings `#323f54` with outline
  `#3f4e66`, roads `#49536a` minor, `#5e6a85` secondary, `#6f7a96` trunk and motorway, casings
  equal to the land.

**AMOLED**: land `#000000`, water `#04080C`, vegetation `#050E0A`, buildings `#0A0C0F` with
outline `#14171A`, minor roads `#1A1D22`, service `#111418`, trunk and motorway `#22262C`,
casings `#000000`, text halos `#000000`. It layers on `applyDark` so anything it does not touch
inherits dark styling rather than Liberty's light defaults. A palette function must not change
zoom gates or extrusion opacity; those belong in `ensureLayers` and `applyDark`.

### 6.3 Layer rules

- **`maxzoom` is exclusive.** Liberty's `building` fill is minzoom 13 / maxzoom 14, so setting a
  min zoom of 14 without also setting max 24 collapses its range to empty.
- Flat `building` fill draws from z16, `building-3d` extrusions from z17 growing 30 percent to
  full height by z19. At about 500 feet, dense-city towers otherwise lean over the roads.
- **House numbers**: the runtime `vela-housenumber` layer and the `vela-addr-*` overlay must
  share `houseNumberMinZoom()` (a setting: near 18.3, normal 17.8, far 17.3). The address
  overlay's archives carry tiles only at z16-17, and MapLibre never cold-fetches a tile clamped
  two or more levels below the camera, so the layer arms at z17 and the zoom gate is an
  **opacity** step, not the layer's minzoom.
- Both house-number layers carry `textIgnorePlacement(true)`: they yield to icons but never
  enter the collision index, so numbers cannot evict icons.
- Address-overlay numbers anchor above basemap labels and below the ambient icons. Anchored to
  the visible controls layer instead, they sink under the building extrusions.
- **Traffic controls draw in two layers.** The visible layer sits at the very bottom of the
  symbol stack so a sign can never cover a street name, and an invisible claim twin
  (`iconOpacity` 0, `allowOverlap` true, `ignorePlacement` **false**) sits above the basemap
  labels so labels shift away from sign positions. Draw order and placement order are the same
  thing in MapLibre; these genuinely need two layers.
- **Dots below labels.** Both dot tiers sit below the basemap's first symbol layer, so a label's
  halo covers its dot.
- **An invisible-but-queryable layer needs `lineOpacity(0.004)`**, not opacity 0: MapLibre skips
  fully transparent features at render time and `queryRenderedFeatures` only sees rendered ones.
  An 8-digit hex color string is rejected by the color parser and falls back to opaque black.
- Point GeoJSON sources take an explicit maxzoom (18 for ambient, 12 for the sparse ones); line
  sources keep their defaults for line metrics.
- Every `setGeoJson` and every `setProperties` is identity-gated by a `lastApplied` holder, and
  **every such holder must be reset in the style-reload block**, or a theme, palette or
  satellite flip leaves sources invisible.
- The traffic raster inserts above `building-3d` and `building` (fallback: below the first
  symbol layer) so footprints do not paint over the congestion colors.
- Satellite: the base imagery caps at z19 and MapLibre stretches past it. `refreshSatDeep`
  probes the provider's availability index at the view center, 20 then 21 then 22, stopping at
  the first missing level, and `ensureSatelliteDeep` adds one raster layer above the base at the
  probed native level, or a fallback provider where the primary tops out at 19. The deep layer
  cross-fades in (opacity 0 at z18.6 to 1 at z19.6) because the deeper tiles are a different
  capture program, and its minzoom is the fade's first stop so it does not load tiles while
  invisible.
- **Attribution.** A tappable "(c) OpenStreetMap contributors" label sits bottom-left under the
  scale bar in **every** map state, lifted over the nav bar and the minimized results bar and
  moved into the map strip in landscape. MapLibre's own info button is disabled. The satellite
  credit is a separate centered line. Never gate the OSM label on a chrome state.
- **Boundaries**: countries and states or provinces are drawn (admin 2, and admin 3-4 from
  minzoom 4, dashed); county and city limits are not. Never return the boundary layer ids to the
  hide list.
- Building footprints from the overlay draw **beneath** the OSM `building` layer with identical
  theming, so OSM wins wherever it has data.

- **The traffic raster cross-fades.** Google's tiles expire while you drive and a replaced tile
  swapping between frames reads as the whole congestion layer flickering, so the layer carries a
  900 ms `raster-fade-duration`. The default 300 ms is still a blink on a full-screen overlay, and
  traffic has no fine detail a slower fade can smear.

- **Residential streets are widened and named earlier than Liberty draws them** (`widenStreets`,
  applied by the theme pass so every palette gets it). The style leaves a minor road invisible below
  z13.5, 2.5 px at z14, and its NAME unplaced until z15, which next to Google at the same visible
  area reads as a sketch of a town you cannot identify. Minor roads now start at z12.5 and run about
  60% fatter through the town zooms, converging on the style's own 18 px by z20 so close zoom is
  untouched, and `highway-name-minor` drops to a 13.5 floor. Both levers are needed: a symbol layer
  draws nothing above its own floor however fat the line under it is. Paths keep the higher floor,
  since a trail name at town zoom is clutter. It was reported here as costing frames, from a single run
  each side; three runs a side put the two distributions on top of each other (baseline medians
  45/40/43, with the change 41/42/39) and the claim did not survive. Repeated runs of one build vary
  by 5 to 7 fps of median on a 4a, which is wide enough to invent a regression, so a median gap
  under about 6 fps from this rig means nothing.

### 6.4 The building-overlay gate

The overlay is pure occluded overdraw where OSM is dense, so a gate probes rendered OSM coverage
per viewport. Three rules:

1. `queryRenderedFeatures` is a synchronous main-to-render-thread round trip and idle events can
   fire per frame, so events only mark the verdict dirty; probes run at most once per
   `OVL_GATE_MIN_GAP_MS` (1.2 s), 12 points, z16 and above. Probing straight from an idle event
   froze a Pixel 4a to about 1 fps.
2. Measure coverage by **area** (grid points on a building), never by feature count: tile
   generalization merges a dense downtown block into two or three giant polygons.
3. Layers are born hidden and only revealed after a finished render (`OnDidBecomeIdle`). A
   "sparse" verdict before tiles land is indistinguishable from a real gap. Hiding is always
   safe immediately. A floor-blocked call schedules one deferred retry, or an uncommitted
   verdict sticks forever on an idle map.

Gate state is composable-scoped, because `getMapAsync` can register listeners twice.

---

## 7. Offline data

### 7.1 What a region download contains

Routing (obf), places (PMTiles), the basemap picture (PMTiles), a place pack (SQLite), road
features, and the building and address overlays where they exist. `MapPoiPrefs.placesWithDownloads`
(default on) controls whether places ride along.

| Artifact | Built by | Hosted on | Manifest |
| --- | --- | --- | --- |
| Routing `.obf` | `scripts/build-obf-region.sh` + `VelaObfShim` | `obf-regions` | `obf-manifest.json` |
| Place pack | `scripts/build-poi-region.sh`, `poipack_build.py` | `poi-packs` | `poi-pack-manifest.json` |
| Places tiles | `tools/build-places-region.sh` | `places-overlays` | `places-overlay-manifest.json` |
| Basemap tiles | `tools/build-basemap-region.sh` (planetiler) | `basemap-tiles` | `basemap-manifest.json` |
| Road features | `scripts/build-road-features.sh`, `road_features_tsv.py` | `road-features` | its own manifest |
| Building overlay | `scripts/build-overlay-region.sh` | `building-overlays` | `building-overlay-manifest.json` |
| Address overlay | `scripts/build-address-region.sh` | `address-overlays` | `address-overlay-manifest.json` |
| Maxspeed overlay | `maxspeed-overlays.yml` | `maxspeed-overlays` | its own manifest |
| ALPR cameras | `scripts/build-flock-cameras.py` | `flock-cameras` | `flock-manifest.json` |
| Glyphs | `scripts/build-map-fonts.sh` | `map-fonts` | unpacked to Pages |
| TTS runtime, ASR models | vendored builds | `tts-runtime`, `asr-models` | catalog in `:core` |

**A manifest merge derives the manifest from the release, never from the run's own fragments.**
The bake matrix uploads one archive per region and the merge job publishes the manifest, a
read-modify-write on one shared asset. The basemap and places merges carry no concurrency group:
GitHub cancels a job that is PENDING in such a group when a newer one joins it, so in a wave of
runs the middle merges were killed after their archives had already been uploaded, which on
2026-09-18 left 99 of 414 basemap regions in the manifest. Instead
`scripts/repair-basemap-manifest.sh` (called by `merge-basemap-manifest.sh`) and
`scripts/repair-places-manifest.sh` (called by `merge-places-manifest.sh`) build the list from the
archives published on the release, reusing an existing row only when the size is unchanged AND the
asset was not uploaded after the row's rev (size alone kept a stale rev when a rebake landed on the
same byte count), then let the run's own entry files win for the regions it baked. The basemap
script reads a new archive's bbox out of its first 127 bytes; the places script takes it from
`tools/places-regions.json`. Parallel merges race only on the upload, which retries, and each script
lists the release again after uploading and rebuilds once more when an archive landed meanwhile.
The manifest is then a function of what is published: running it after the last upload is enough,
running it twice changes nothing, and a merge that never ran costs nothing. The building, address
and maxspeed merges still fold entries and are serialized by their own concurrency groups. Dispatch a catalog as a couple of sharded runs rather than one per group, so few
merges can queue behind each other in the first place.

**Infrastructure releases are not app releases.** Every non-`v0.*` tag is file hosting whose
assets exist nowhere else. Two standing rules: any cleanup that deletes or edits releases selects
by tag pattern `v0.*`, never by "prerelease" or "old", because the infrastructure releases are
old prereleases by design; and any `gh release list` logic must paginate or bound by tag,
because the repository holds hundreds of releases.

### 7.2 Catalog and selection

`tools/routing-regions.json` is the catalog (every Geofabrik country-level extract, US states,
Canadian provinces, and first-level sub-areas for the countries Geofabrik divides). Rows carry
`group`, `big`, `skip_obf` and `qkprefix` where relevant. A dispatch takes a group list or
`all-sub`.

Selection rules on the phone:

- The smallest archive whose box covers the point wins, and `sourcesFor` returns exactly one
  source: two nested archives draw the overlap twice.
- **The basemap pick then asks the file whether it draws the map there** (`PmtilesReader.hasRoads`,
  probed at `COVERAGE_PROBE_Z` 12 and memoized per archive and tile). A box is a rectangle and a
  region is not, so a neighbor's box always covers ground its data does not reach, and a small
  neighbor can even have the smaller box and win outright; the result was a blank vector map over
  that strip with the traffic raster and the place pins still drawing on bare land. The test is the
  presence of the `transportation` layer, **not** the presence of a tile: a bake emits tiles across
  its whole box from planetiler's global base data, so "is there a tile" answers yes over the
  neighbor and out to sea. **A definite "no roads here" from every candidate returns NOTHING**, so
  the view streams: mounting an archive that has answered no paints an empty map over tiles that
  were about to arrive, and crossing the box edge then unmounted it, so a pan along a download's
  border alternated gray and network. Only a probe that CANNOT answer leaves the old pick in
  charge, because that is the case where asking told us nothing. The pick runs off the main thread.
  **The mount has hysteresis.** A swap reloads the whole style (`basemapArchive` is part of the
  style key), so the pick must not flip on every camera idle along a border. Unmounting is eager
  (no roads at the center tile means stream); mounting an archive that is not the one in use
  requires roads at the eight z12 tiles around the center and at the four corners of the visible
  viewport (`installedFor(center, mounted, view)`), so an archive returns only once the border has
  left the screen. Swaps also keep a `BASEMAP_SWAP_COOLDOWN_MS` (2 s) floor. Offline the eager
  unmount is replaced (`keepMounted`): the archive in use stays while its roads reach the center
  tile, the ring or any viewport corner, because nothing streams in its place and letting go
  blanked the whole screen, the downloaded half included, on a pan across a state line. Online
  the mounted archive is no longer kept until the center leaves its data: it is in use exactly
  while the ring and the corners are all inside it, so a border on screen means streaming and a
  pan along the border reloads nothing.
- **A global low-zoom archive is the floor under the pick** (`BasemapTileStore.WORLD_ID`, baked by
  `world-lowzoom.yml`, about 11 MB at z0-7, pulled once alongside the first offline download). It is
  kept OUT of the per-region candidate list: it covers every point on earth, and it carries no
  `transportation` layer, so the coverage probe would reject it everywhere. It is returned as the
  explicit last resort instead, which is what stops "nothing here holds the map, so stream it" from
  meaning a blank screen with no network. Its max zoom is below `FULL_MAP_ZOOM`, so the existing
  shallow rule already makes it offline-only with nothing new written. Natural Earth supplies water,
  coastlines, boundaries and place labels globally at these zooms, which is why it is cheap; roads
  are OSM-derived and absent, which is what a region download is for.
- **Source swaps have a floor of `BASEMAP_SWAP_COOLDOWN_MS` (2 s).** Re-pointing every basemap layer
  re-tiles and re-lays out the map, which is a visible freeze; at a region's edge the honest answer
  genuinely changes as the view crosses the data, so without a floor a pan along the border stutters
  on every camera idle. A newer camera idle cancels the pending pick, so the wait can only delay a
  swap the view still wants.
- A region download pulls the **same-id** archive first; the center rule alone pulls parent and
  neighbor archives too.
- Geofabrik boxes carry a buffer that spills across borders, so "any box that covers you" ranks
  a neighbor above the right region.
- Building and address overlays stream online from the **union of up to the three smallest**
  covering regions, because a smaller neighboring box can cover a point its data does not reach.
- A shallow basemap archive (maxzoom below `FULL_MAP_ZOOM` 14, read from byte 101 of the PMTiles
  v3 header) is used **only offline**. The bake drops a zoom level when a region would pass the
  2 GiB asset limit.

### 7.3 Freshness

A manifest row's `rev` is an integer that only grows. The obf, basemap and places bakes stamp the
bake date as `YYYYMMDD`; place packs count up instead, one past the live manifest's rev for that
region (`scripts/build-poi-region.sh`), which is the `fromRev` their row deltas key on. Installed
revs live beside the files (`revs.json`) and `MapViewModel.refreshRegionUpdates` turns a newer rev
into an Update button.
Place packs additionally publish **row-level deltas**: `poipack_delta.py` emits one SQL EXCEPT
per table into `del_`/`ins_` tables, published only when the delta is under half the full size.
`PoiPackStore.applyDelta` runs when the installed rev equals the delta's `fromRev`, in one
transaction, deleting by full-row match through each table's index with NULL-safe matching,
inserting, then verifying every table count against the manifest before committing. Street-name
ids are stable content hashes (SHA-1 of the normalized name truncated to a positive 63-bit
integer, collisions fail the build), never insertion counters, or a rebuild renumbers millions
of rows and the delta balloons to pack size. `TABLE_COLUMNS` in `PoiPackStore` mirrors
`poipack_build.py` and `poipack_delta.py`; all three must stay in step.

Scheduled rebakes: ALPR cameras weekly (Monday 08:17 UTC); place packs monthly (3rd and 5th, 07:15, half the catalog each);
road features monthly (4th and 6th, 07:45, halves); places (6th and 7th, 05:00, sharded); basemap (9th and 10th,
05:00, split by catalog half); buildings (three groups), addresses and maxspeed (two shards)
quarterly (January, April, July, October, 2nd, 04:00, `quarterly-data-refresh.yml`). Routing is not
scheduled: the obf bake stays manual because of its runner memory limits
and the manifest flip. A world obf bake stages into `obf-manifest-staging.json`, which the app
never reads; copying staging over the live name flips the whole catalog atomically.

**Delta updates (the applier exists; the bake publishes patches since 2026-09-18).** A rebaked
region changes about one tile in a hundred, so a patch carries only what moved. The format, the
producer (`scripts/pmtiles-make-patch.py`) and the reference applier (`pmtiles-apply-patch.py`)
share one reader (`velapmtiles.py`) so the fingerprint cannot drift between them; the phone's
applier is `app/offline/PmtilesPatch`.

- **Applied IN PLACE**: append the changed tile blobs, append the rebuilt directory, then flip the
  127-byte header LAST. Nothing the old header describes is touched until that write, so an
  interrupted apply leaves the old archive intact and the cost is the patch, not a second copy of
  the region. The archive becomes unclustered, which MapLibre reads (verified on a device with a
  152 MB region archive; `pmtiles verify` refuses it, because the header's length fields stop
  accounting for the whole file once there is dead space in it).
- **The patch names no offsets.** It carries the new directory's ids, run lengths and tile lengths
  plus one flag per entry: the tile rides in this patch, or the archive already holds it under that
  id. The applier resolves those against ITS OWN file and writes the directory itself. The first
  format shipped the finished directory, whose offsets only described a byte-exact copy of the
  archive the bake diffed against, so a phone that had taken one patch was refused every later one
  and downloaded the region whole for ever - found on a device, not in review. The applier also
  reads the tile-data offset from the archive's own header rather than the patch's, which is what
  lets a COMPACTED archive take the next patch.
- **Proven before it is committed**: the fingerprint (SHA-256 over sorted tile ids, run lengths and
  tile hashes, phone-computable because Android has no blake2b) is computed against the directory
  the patch just wrote, while the header still describes the old archive. A mismatch truncates back
  and the caller downloads the region whole. A patched archive therefore holds exactly what a fresh
  download holds, checked rather than asserted.
- **The bake publishes a patch only if it applies.** It diffs against the archive it is replacing,
  applies the result to a copy, checks the fingerprint, and only then uploads it and adds
  `delta: {fromRev, url, sizeMb}` to the manifest row. Over a third of the archive, it is not worth
  a second code path and is skipped. Two bakes on the same UTC day share a revision and so skip the
  patch entirely; the workflow's `rev` input overrides the stamp, which is how the path is exercised
  on demand instead of waiting a night. A rebake that also carries a BAKE CHANGE is not a delta
  candidate: Guernsey and Jersey re-baked a day after the OSM-business source landed carried 2506 of
  4586 tiles (2.17 MB against a 3.3 MB archive) and was correctly refused. The number that matters is
  two bakes of the SAME script, and that is what `scripts/archive-churn.py` measures.
- **Dead space is reclaimed on the phone, not re-downloaded.** A patch appends and leaves the tiles
  it replaced behind, which is the ONLY way a patched archive differs from a freshly downloaded one:
  same tiles, more bytes. `dead.json` accumulates those bytes per archive, and past a fifth of the
  file `PmtilesCompact` REWRITES it: every live tile is already on the phone, so putting them back
  in tile id order and dropping the rest costs a pass over the file and nothing on the network. The
  result is the layout the bake publishes (header, directory, metadata, clustered tile data, no
  leaves, no gaps) and it is proven before it is committed, like the patch: the rewrite goes to a
  temporary file, its fingerprint has to equal the one it came from, and only then does it replace
  the archive. `scripts/pmtiles-compact.py` is the same code on a desktop and is how the layout was
  checked: the published Guernsey and Jersey patch applied to the previous revision gave a 3.36 MB
  archive, compacting it gave 3,259,268 bytes against the 3,259,285 of a fresh download of that
  revision, same fingerprint - 17 bytes apart, which is the leaf directory a fresh bake writes.
  A whole download stays as the last resort for a phone with no room to rewrite: past HALF the file
  in dead space the delta is refused and the region is taken whole. `adb shell setprop
  debug.vela.compact true` rewrites after every patch, for watching it happen. `PmtilesCompactTest`
  holds the claim down in CI: its fixture (`scripts/pmtiles-test-fixture.py`) is a small archive run
  through the real producer and the real applier, so it carries dead bytes the way a phone's does,
  and the test compacts it and checks the fingerprint, every tile id, length and run.
- **Deleting gives the space back.** MapLibre keeps saved areas and the browsing cache in one
  SQLite file; deleting a region removes its rows, not the bytes, so `OfflineMaps.packDatabase`
  (`OfflineManager.packDatabase`, a VACUUM) runs after every saved-area delete and after Clear
  map cache. A phone that had saved and deleted a few large areas otherwise reports gigabytes of
  map data with nothing listed (issue #601). Settings > Offline maps > "Delete all offline data"
  (`MapViewModel.deleteAllOfflineData`, behind a confirm naming the total) removes every saved
  area, every region's routing, place pack, places and basemap archive, the building and address
  overlays (which an area save pulls and no region row can delete), the road features, the offline
  basemap's label glyph pack (`files/glyphs`, ~200 MB) and any legacy graph tree, sweeps the stores' folders for files no catalog id reaches any more, clears
  the browsing cache and packs the database. Voices and speech models are not offline map data
  and stay.
- **Settings > Offline maps reports the archives too.** The map figure counts the glyph pack
  and the road features beside the database, overlays and basemap archives, and `offlineStorageBreakdown`'s "Offline places"
  counts `files/poipacks` AND `files/places`; the archives were absent from the only storage screen
  in the app, so a region's few hundred MB of places were invisible.
- **The archive catalog is memoized for an hour, not forever.** `PmtilesRegionStore.manifest` runs on
  every camera idle, so it caches; the cache EXPIRES (`MANIFEST_TTL_MS`) because a bake publishes a
  new revision while the app is running, and a process that lives for days would otherwise never
  offer the update or take the delta. Found on a device: a rebake published four minutes before the
  Offline maps screen was opened, and the row still said there was nothing to update.
- **Policy is the user's**: `ui/RegionUpdates` (never, the default until the path has been proven
  on a device / on Wi-Fi / on mobile data too),
  metered judged by the system rather than by which radio it is. On Wi-Fi or mobile the app applies
  every published patch that fits an installed archive or pack on its own, a minute after start and
  at most once in 20 hours, skipping a drive in progress. A FULL re-download is never automatic on
  any setting, and a tapped one downloads over the installed copy so a failure keeps the region. Every attempt is recorded in the diagnostics ring (kind `delta`) and
  logcat `VelaDelta` with the bytes and the reason for any fallback, because the failure worth
  seeing is a region that quietly downloads itself whole every week.

### 7.4 Download discipline

- **Every multi-megabyte download runs through `MapViewModel.downloadLaunch(label)`**: the work
  runs on the app-lifetime `DownloadWork.scope` and a refcounted `dataSync` foreground service
  holds the process alive. `viewModelScope` is canceled the moment the task is swiped, and
  without the service an aggressive background killer reaps the process seconds after Home.
- **Every large download must use a derived `callTimeout(0)` client** with a 60 s read timeout.
  The shared scrape client caps a call at 12 s; a big body blows through it, `runCatching`
  swallows the abort, and the asset silently never installs. Manifest fetches stay on the short
  client.
- Every download is cancellable: the store functions take an `active: () -> Boolean` polled per
  chunk, the view model holds per-kind cancel flags reset at download start, and a cancel is not
  reported as a failure. The tile-region save cancels by detaching the observer, setting the
  region inactive and deleting the partial. Any UI that shows progress must show a cancel.
- Whole-parent downloads queue their pieces (`regionQueue`), popping the next at the end of each
  download; cancel clears the queue.
- Sizes shown are **installed** sizes: manifests carry `installedMb`, with fallbacks of the zip
  times 1.8 for graphs and times 2.35 for packs. Regions over 1 GB installed confirm first.

### 7.5 Offline basemap rules

Four rules, each of which produced a blank map:

1. Add the local archive as a source **after** the style loads, and re-point every
   `openmaptiles` layer at it (`withLocalBasemap`). Declared in the JSON or through
   `Style.Builder.withSource`, it never gets past the z0 tile.
2. A labeled tile only completes once every glyph range and the sprite resolve, so with no
   signal the remote hosts hang and the map is blank. Serve both from `file://`.
3. A process that **starts** offline on the remote style poisons the engine's shared glyph and
   sprite managers for every later style, so the local style is chosen before the first load
   (`refreshBasemapArchive(seed)` at init).
4. Every helper that reads the basemap source must go through `basemapSrc(style)`, or the
   offline map comes up light and bare.

---

## 8. Transit

The stack mixes two sources deliberately.

| Piece | Source | Why |
| --- | --- | --- |
| Departure boards | Transitous (MOTIS) first, Google's place page as fallback | The open feeds return every route at a stop with realtime lateness and the agency's own colors; a multi-bay center merges through its parent station. Google's anonymous page embeds as little as one route at a large hub. |
| Stop icons | Transitous stop positions at z15 and above; OSM basemap icons where Transitous has no coverage | GTFS positions come from the agencies; tapping an icon opens the board by stop id and skips name matching entirely. |
| Route stop list | The GTFS trip's own stop sequence (`/trip` by the tapped run) | Exact: the actual run, every stop, realtime per stop. The Google itinerary is the fallback. |
| Realtime lateness | GTFS-Realtime through Transitous | Straight from the agency feed. |
| Transit **directions** | Google's directions page | Google's itinerary ETAs reflect live road traffic on the bus's route; GTFS-Realtime only knows current lateness. |

Details:

- Boards refresh every 30 s while the sheet is open; Google-fallback boards stay one-shot,
  because a refresh there is a whole page load.
- **Directional curb pairs merge.** US GTFS names both curbs identically and carries no
  direction field, so `Transitous.mergeDirectionalPairs` collapses same-name stops within
  `PAIR_MERGE_M` (160 m) to the pair's midpoint, carrying the other ids as siblings; boards
  merge stoptimes across the representative and its siblings, and the `(route, headsign)`
  grouping shows both directions as separate rows. Direction-suffixed names differ as strings
  and never merge. "Same name" is `Transitous.stopKey` (case, ordinals, "&" / "/" / "at",
  street-type and compass abbreviations, and the order of the cross streets do not count), and
  before that pass stops within `COLOCATED_M` (3 m) fold whatever their names: one corner
  published by several feeds (the MTA's per-borough bus feeds, NY Waterway) or a station
  complex's several parents. An ALL-CAPS merged name shows in title case
  (`Transitous.displayName`). Around Bryant Park: 78 icons became 55.
- Where the Transitous layer has coverage, the basemap's OSM bus icons hide by filter (rail and
  airport stay), so a stop cannot draw twice.
- Every successful viewport fetch overwrites its area in a 24-area on-disk LRU
  (`TransitStopCache`), so visited areas keep canonical stops with no signal.
- **Every board fetched is kept on disk (`TransitBoardCache`, newest 48, keyed by the stop's
  coordinate to ~10 m, a 40 m near-match).** A stop tapped with no connection shows the board it
  had last time, with a line saying when it was seen (`stopDeparturesCachedAt`), so the routes,
  headsigns and colors are there and an old time is never read as a live one. A live board
  replaces it and clears the marker; no cached board means the sheet shows no board, as before.
- The transit category gate is multilingual and carries an exclusion list: gate words match and
  non-transit words (fuel, EV, emergency, broadcast) must not, because "station" appears in all
  of them. Both regexes are remotely overridable.
- A hinted tap (the basemap class says transit) that resolves to no Google stop listing falls
  back to a Transitous board at the tapped coordinate, by proximity, with no category and no
  feature id.
- A transit-named place that resolves to an "Intersection" entity re-resolves to the co-located
  stop: search the name plus the mode word, take the nearest live transit listing within 250 m.
  A real co-located pair measured 89 m apart, so a tighter radius misses it.
- Google's transit request must carry the time block when a time is chosen. `!8j` is a **local
  clock**, not a Unix timestamp: Google reads the seconds as wall-clock-as-UTC, so the phone's
  zone offset is added. Preferred vehicles are `!5e{k}` entries in the same options group, and
  the `!4m` wrappers are descendant counts that must be sized from the entries.
- A parsed line is identified by its badge **or** by an agency icon filename when the badge
  carries no text (subway bullets). The agency prefix is the rule: a line icon is
  operator-scoped and contains a slash, while the generic vehicle icon is bare. Mode is read
  from icon filenames only; matching mode words against nearby text misreads place names that
  contain them.

---

## 9. Voice, dictation and language

### 9.1 Spoken guidance

Guidance text is generated per language by `NavStrings` tables in `:core/i18n`, switched by
`NavStringsRegistry`. **Both routers feed the same tables**: `RouteGeometry.osrmPhrase` and
`OfflinePhrases.phrase` both map their maneuvers onto the OSRM `(type, mod)` token pair.

Speech runs through an in-process neural voice by default: the bundled sherpa-onnx runtime with
a downloaded Piper model (`PiperSynth` behind the `:core NeuralSynth` seam). Any of about 40
catalog voices can be installed, each in `filesDir/piper/<id>/`; the installed set is derived
from the filesystem so a partial download self-heals. Selection persists in `voice_model`, the
speaker per voice in `voice_speaker_<id>`.

Rules:

- **R8 must keep `com.k2fsa.sherpa.onnx.**`**: JNI resolves classes by original name.
- **Generate the whole utterance before `AudioTrack.play()`.** Streaming underruns make
  AudioFlinger drop the track, which aborts the process. The utterance is written in about
  200 ms chunks with a generation check between them so an interrupt lands within that window
  without underrunning.
- **A Piper voice speaks one language.** `VoiceGuide` compares `NeuralSynth.voiceLanguage`
  against the language the text was generated in and, on a mismatch, routes to a system TTS in
  the target language; with none installed it stays silent and fires `langUnavailable`, which
  surfaces a "get a voice" action. It never reads one language through another's model.
- Audio focus is refcounted through the utterance callbacks. A system TTS `speak()` returning
  `ERROR` enqueues no utterance and therefore no callback, so that path rolls back its acquire;
  a failed `onInit` clears the pending queue rather than accumulating prompts for a later init.
- **A fresh focus grant leads the first sample by `FOCUS_LEAD_MS` (350 ms).** A player that
  pauses on a transient duck (spoken-word apps, by Android's own guidance) takes a few hundred
  milliseconds to stop, and without the lead the first word lands on the music. Focus already
  held from the previous prompt (`FOCUS_HOLD_MS` 1500) speaks at once; a fresh grant means
  nothing of Vela's is speaking, so no interrupt waits behind the delay.
- Sentence pauses are made by splitting the utterance and splicing silence
  (`splitSentences`/`joinWithGaps`). The runtime's own silence scale is a no-op on this model.
- **Every fragment gets terminal punctuation before synthesis.** A fragment ending in a letter
  or digit gives the model no final prosody contour and the last consonant is swallowed.
- `SpeechText.spokenNumbers` pre-expands three-digit street ordinals ("120th" as "one twenty
  eighth", with a space, not a hyphen) before the neural grapheme-to-phoneme stage.
  `expandForSpeech` also rewrites `<XX>-<n>` state route refs to "State Route n" and inserts a
  comma before " toward ", which makes the phonemizer treat the sign destination as its own
  beat.
- Guidance volume (`voice_volume`) is a gain over the neural voice's float PCM, hard-clipped at
  full scale. The system TTS path takes `KEY_PARAM_VOLUME` capped at 1.0, because Android can
  only attenuate system voices.
- A voice install or a delete-fallback never auto-speaks; only an explicit library pick
  auditions.

- **Clock times are spelled out** (`SpeechText.spokenClock`, English): the phonemizer reads a colon
  between numbers as a measurement and announced an arrival time of 5:49 as a height. Same approach
  as the street ordinals: fix the text rather than fight the G2P. Every spoken string carrying
  numbers, units or punctuation needs the same look, and a test.

### 9.2 Foreign scripts

A road name can be in a different script than the guidance language.

- `SpokenScript.forVoice(text, voiceLang, dict)` substitutes a **real** Latin name from the
  dictionary first and only then falls back to ICU transliteration. Every voice keeps its own
  native script and romanizes everything else; **CJK is never romanized** for any voice, because
  ICU reads Han as pinyin.
- `SpokenScript.forDisplay` does the same for the banner and step list but with **no ICU
  fallback**: a vowel-less skeleton on a sign reads as broken, so an unmapped name keeps its
  local script.
- The dictionary is real data: the basemap's `name:en` and `name:latin` per road, collected by
  the same quantized pass that places the crossing labels, plus `Route.roadNamesLatin` from the
  obf route. The dictionary query is **warm-up paced**, re-running every 2 s while it is still
  growing, because a drive starts with only low-zoom tiles loaded and those carry only major
  roads.
- The navigation opener is held up to `OPENER_MAX_WAIT_MS` (2.5 s), retrying every 200 ms until
  the road it names is covered by the dictionary. An English opener never waits.
- On-map labels use `roadLabelTextField()` = `coalesce(name:en, name:latin, name)` for a
  Latin-script UI, and the local `name` for a non-Latin UI. Nav bubbles filter on the canonical
  `name` and display the Latin form. Place names are data and are never romanized.
- `SpokenScript.applyDict` returns in O(length) when the text contains no character the reader
  cannot read, and digests each dictionary once per instance. Scanning a whole downloaded
  region's dictionary twice per fix was a 60 ms main-thread stall at 1 Hz.

### 9.3 Dictation

Two tiers, both keyless.

- **Tier 1, in process**: `AsrRecognizer` over the same sherpa-onnx runtime, with three
  downloadable engines (`AsrEngine`): Whisper tiny int8 multilingual (58 MB, the default),
  SenseVoice (154 MB, en/zh/ja/ko/yue) and Moonshine (101 MB, English). Each archive is
  self-contained and carries its own Silero VAD. Recognizers cache on `"<engineId>|<lang>"`.
  Whisper is pinned to the app language; auto-detect transcribes noisy far-field audio into the
  wrong script. Recording takes `AUDIOFOCUS_GAIN_TRANSIENT` so media pauses rather than ducks.
- **Tier 2, handoff**: `ACTION_RECOGNIZE_SPEECH` to an installed voice-input app, which records,
  so Vela needs no microphone permission for it. Only apps registering the **activity** count; an
  IME or a `RecognitionService` cannot be launched this way. Resolution is a ladder: the user's
  pinned component, then Android's own default, then the first installed app, so the system
  chooser never interrupts a dictation.

RECORD_AUDIO is requested at the point of use. `listen()` returns a typed result
(Text/NoSpeech/Failed(reason)) and every failure is explained in the UI.

**The ASR path is crash-sentineled.** A truncated or corrupt archive makes sherpa-onnx abort
natively, which is uncatchable, and warm-up runs at startup, so a bad model is an unrecoverable
crash loop. A strike counter is bumped before every native load and cleared after; two stranded
loads quarantine that engine and delete only its directory. One stranded load is forgiven,
because a mid-load process kill strands the counter exactly like a crash and must not delete a
healthy download.

### 9.4 Interface language

`AppLocale` holds the language (empty means follow the system) and is applied in **both**
`MainActivity.attachBaseContext` and `VelaApp.attachBaseContext`. When following the system it
also restores `Locale.setDefault` to the captured device locale, because the override is
process-global and otherwise leaves date formatting and the scrape's `hl` in the previous
language. Changing the language calls `recreate()`.

- Strings live in `res/values/strings.xml` (US English) plus one folder per translated language.
  `values-en-rGB` exists for British wording and carries only the strings that genuinely differ.
- Counts use `<plurals>` with the correct CLDR categories per language.
- Strings that double as a logic key are deliberately not in `strings.xml`. Where a display
  label and a logic key must both exist, they are split, and the key stays English.
- The scrape's `hl` follows the app language but only for the languages whose status keyword
  table exists (`SearchParser.STATUS_LANGS`); any other locale stays `hl=en`, because a status
  string the parser cannot read leaves open/closed null forever. `gl` follows the phone's
  region.
- Hyphenated language codes must resolve through `Locale.forLanguageTag`. The `Locale(String)`
  constructor makes a bogus lowercase language that matches nothing.
- Two build traps that a warm Gradle daemon hides: in a Kotlin string template a `$var`
  touching a CJK character parses as one identifier, so brace it; and a raw apostrophe in
  `strings.xml` is an AAPT error the release resource merge rejects even when a cached debug
  build passed.
- Names, streets and reviews are data and are never translated.
- **US English is the repository's language.** British spellings do not appear in code,
  comments, documentation, commit messages or the base string resources. The exception is
  `values-en-rGB`, which exists to be British, and data strings that match a foreign source's
  own spelling (OSM's `neighbourhood` place class, OSM tag values such as `fitness_centre`, and
  the MOTIS wire field `cancelled`); those are marked where they appear.

### 9.5 Query intents

Every submitted query, typed or dictated, goes through `core/search/QueryIntents.parse(text,
lang)` first: Home, Work, NavigateTo, Route(from, to), Search with filler stripped, or Eta. A
null result means a plain search, so the parser can never make a query worse. Per-language word
tables cover every app language, with English as a fallback in each. A bare verb counts only
before home or work or an explicit "from A to B"; a bare "X to Y" is a route only when X is not
a question word or a verb; and `routeBetween` runs the whole phrase as a search first, so a
place whose name contains "to" stays a place.

A fuzzy pass runs after the exact passes miss (spaced languages only). Tolerance is word-level
and only over the **vocabulary**, never the free text: accents fold, one edit is allowed from
five letters and two from eight, a single-word phrase never fuzzes, and a multi-word phrase must
match word for word so "home depot" cannot collapse to "home".

---

## 10. User interface

### 10.1 Sheet physics

The place sheet, the results sheet and the directions chooser share one grammar, and a new sheet
ports it rather than inventing a fourth:

- A hand-driven `Animatable` (height for the sheets, a 0..1 body fraction for the chooser),
  dragged 1:1 from the handle and from content-at-top through a nested-scroll connection.
- Release projects the fling with `exponentialDecay(friction 1.6)` and rides `animateDecay`
  clamped by `Animatable` bounds at the nearest detent, so the detent stops the coast rather
  than a spring snapping to it. Only a throw that does not carry glides on the spring.
- A flick faster than `FLING_COMMIT_DPS` (180 dp/s) advances at least one detent.
- **Velocity is measured on integrated drag deltas**, not `change.position`, which is local to a
  node that resizes as the sheet moves; position-based tracking measures about zero and every
  flick reads as a slow drag. Every manual tracker also takes `max(tracked, travel / time)`.
- The animated value is read in a **layout** modifier, never in composition. A composition read
  recomposes the whole sheet every animation frame.
- The whole gesture lives in `sheetDragGestures`; copies drift.
- A region that is scrollable but has **zero range** never engages a drag, so a minimized card
  whose content fits needs its own drag surface.
- `SheetFold` folds extra content with the sheet's own height fraction. Its cross-fade uses
  `CompositingStrategy.ModulateAlpha`: a plain `alpha` below 1 renders children into an
  offscreen buffer, which measured about 20 ms of GPU per frame on a Pixel 4a.
- Grabbing the map minimizes the sheets (`onUserPan` from the gesture camera-move reason only,
  never a programmatic move). Every tick-style signal into a sheet needs a consume-once guard
  seeded at mount, because a `LaunchedEffect` also fires on first composition and a remounted
  sheet would replay a stale tick.

### 10.2 Chrome

- **Nothing that renders above the action pills may move after the first frame.** The photo
  strip and the rating row reserve their space while `detailsLoading` is true for a place that
  will have them, keyed on a non-blank `category`, because a place tapped on the open places
  layer has no Google id until details land.
- In landscape, every route-related overlay is a left column: both sheets, the directions
  chooser, the endpoints card, the maneuver banner and the nav bar all take
  `align(Start) + landscapeColumn(...)`, which caps the width and pads the display cutout on the
  start edge. The camera claims `cameraLeftInset` instead of a bottom inset. New route or nav
  chrome without this spans the screen.
- Landscape browse chrome collapses to one line. The one-line condition must not include
  `!searchOpen`: focusing the bar flips `searchOpen`, and moving the search bar to a different
  subtree remounts it, which blurs the field, which flips `searchOpen` back.
- Chrome hides are **measured**, not logical: a short-content place flips its expanded state
  while its wrap-capped card never grows.
- All top-of-map cards render in one top-center column with per-card dismissal. During
  navigation the column hangs off the turn card's **measured** bottom edge.
- Picture-in-picture: everything after the map call is inside one `if (!pipUi)` gate. The map's
  compass and all gestures are off while PiP is active, because the system's own taps reach the
  map as gestures and detach the follow camera.
- The route bar is portrait-only and never in PiP, and shows a 5 km window rather than the whole
  route: scaled to a long trip every nearby mark collapses into one pixel.
- **Pause sits in the nav bar's right slot** (`PauseInBar`, default on). That slot is an empty
  spacer on a touch phone, there only to keep the trip figures centered against End, and the drive's
  two hold controls then sit where each is reached for: pause beside the figures, mute as a plain
  button in the right-edge stack, neither behind a pop-out. The step-list button also wants that slot
  when it was asked for (`PreferButtons`, or a keypad-first device), and then the bar carries BOTH,
  with the figures column shrinking to fit: somebody who asked for buttons should not get a silent
  choice between two of them. The chevron handle opens the step list on its own in every layout, so
  nothing is unreachable either way. The setting turns the slot back into a spacer and restores the
  combined button in the stack.
- **The combined button** (`NavHoldControls`, the layout the setting restores) is one 56 dp target
  for both. The first tap on a running drive only slides mute out beside it, for `OPEN_MS` (6 s); a
  second tap on the same target, which has not moved, pauses. Pausing on the first tap made holding
  the drive the only way to reach mute, which is not what that reach was for. A long press mutes
  outright, so anyone who knows it never sees the pop-out; and while the drive is paused a single
  tap resumes, because the glyph already says what the tap does. The button carries both states,
  because one control standing for two has to: the glyph is pause or resume, the accent fill says
  the drive is held, and a small crossed speaker says it is silent. The long press is touch-only by
  nature and the row is its key path, which is what keeps it D-pad legal.
- **"Searching for GPS" is pinned to the arrow**, above it, with bottom center as the fallback for
  the frames before a puck position exists. The gray dot is what the message is about, and the
  bottom band it used to sit in is where the speed widget and the mute pop-out already are. The
  road-name pill takes the space under the arrow, so the two never meet.

- **One progress bar** (`VelaProgressBar`). Every download had grown its own, which left the one in
  Settings running the full width of a group while every row beside it kept a margin. It has rounded
  ends like the rest of the chrome, a lambda form so an animating value is read in the draw phase
  rather than recomposing its host, and null progress means indeterminate, for a step that cannot
  report a percentage.

- **Spoken guidance can leave the street name out** (issue #596, Settings > Voice "Say street
  names", on by default, `ui/SpokenRoadNames` mirrored into the `:core` flag `nav/SpokenRoadNames`
  that `NavEngine` reads). Off, the voice says "Turn left" where it said "Turn left onto Maple
  Street". NOTHING ON SCREEN CHANGES: the banner, the step list and the pill under the puck keep
  the name, because the reason to drop it is that hearing it is noisy, not that knowing it is
  unwanted. It is NOT a strip of the spoken string: `Maneuver.instructionNoRoad` is built by the
  same per-language template as `instruction` with the road left out, so the word order stays right
  in languages where the name is not at the end, and a null road was never a new case for those
  tables because unnamed roads are everywhere. Filled by the OSRM, Valhalla and obf builders; null
  for Google's abbreviated steps, which are scraped prose with nothing to rebuild from, and there
  speech keeps the full instruction rather than risk a mangled one.
  `Maneuver.spokenInstruction()` is what every SPOKEN site reads: the five in `NavEngine`, plus the
  nav OPENER and the faster-route line in `NavSession`, which are built from the first maneuver and
  are otherwise the first and the loudest place the name would survive the switch. Each of those two
  keeps the NAMED form for its banner and card, which is the whole contract: the switch decides what
  is read aloud, never what is shown.
  ANYTHING THAT REWRITES AN INSTRUCTION AFTER THE ROUTER BUILT IT MUST REWRITE BOTH FORMS, or the
  switch silently undoes that rewrite. Two places do: `consolidateExits` drops the direction that is
  not taken from a folded ramp (missing it, the voice announces both again, which is the bug that
  fix exists for), and `enrichWithLights` prepends the pass-the-lights clause (missing it, turning
  street names off also threw away traffic-light guidance, a separate feature with its own switch).
  Both are pinned by `SpokenRoadNamesTest`.
- **The drive is an Android 16 live update** (issue #595, API 36+, `promoteToLiveUpdate`). The nav
  notification asks to be promoted, which puts the drive in the status bar chip and on the lock
  screen instead of only in the shade. The bar is the ROUTE rather than a download: its scale is the
  route's length in meters, the tracker is the nav puck sitting where the car is (the maneuver glyph is the large icon, Google's own layout), the
  segments are the traffic spans Vela already has (so the jam ahead is visible without unlocking the
  phone), and each remaining stop is a point on it. The chip's critical text is the distance to the
  next turn, the one number worth a glance while moving. Everything is additive and guarded: below
  API 36, with no route, or if anything throws, the notification is exactly what it was, and
  promotion is a REQUEST the system may refuse. The request only counts when the app holds
  `android.permission.POST_PROMOTED_NOTIFICATIONS` (normal, with an app op, so the user can turn
  the promotion off in the app's notification settings): without it the ProgressStyle notification
  posts and renders correctly in the shade, and the system simply never raises the chip. Verified
  on a Pixel 9 (2026-09-19): every extra lands (`android.template=ProgressStyle`, `progressMax` in
  route meters, segments, tracker bitmap, `requestPromotedOngoing`, `shortCriticalText`), and the
  chip appeared only once the permission was declared. The drive's channel is DEFAULT importance
  with no sound and no vibration, which is what Google Maps' own navigation channel is: LOW files it
  as "silent", and a phone set to hide silent notifications on the lock screen hides the live update
  exactly where it is most useful. A foreground notification with `ONLY_ALERT_ONCE` and no sound is
  as quiet at DEFAULT as it was at LOW. Channel importance is fixed once the channel exists, so this
  is a new id (`vela_nav_drive`) and the old one is deleted on first run.

### 10.3 D-pad operation

The whole UI must stay drivable with a five-key D-pad and no touchscreen; touch is a bonus.

- Every interactive element is a focus target with a visible ring in key-driven input mode
  (`Modifier.dpadHighlight`), and every gesture has a key alternative.
- **No screen may open with nothing focused.** A wasted first keypress is the bug. Compose's
  focus recovery is nondeterministic, so every screen attaches an auto-focus target.
- A Compose `DropdownMenu` popup and an `AlertDialog` **cannot** be pre-focused. Use `VelaMenu`
  and `VelaDialog`, which fall back to a raw `Dialog` with an explicitly focusable element under
  D-pad. Their items focus through `.focusable()` plus `.onKeyEvent` plus `pointerInput`, not
  `.clickable`, whose nested focusable will not take `requestFocus` in a dialog window.
- Text fields must not trap focus: `Modifier.dpadFieldEscape` makes up and down leave the field.
- D-pad code **calls the touch paths** (the same tap lambda, gesture flags and zoom override)
  rather than forking them, and every affordance is gated on `dpadMode` or `noTouch` so touch
  behavior is unchanged.
- **Detection is conservative.** `dpadFirst` is true only for a device with no touchscreen or a
  physical non-virtual `SOURCE_DPAD` device. The framework's virtual aggregate device reports
  `KEYBOARD | DPAD` on essentially every phone; counting it makes every phone D-pad-first and
  breaks the search bar. A keypad phone with a fake touchscreen gets full D-pad behavior
  reactively on the first key press through `rememberDpadMode`.
- `AdaptiveDensity.wrap` (chained first in both `attachBaseContext`s) shrinks the effective
  density so a tiny screen reports at least 360 dp of width. It is a hard no-op at 360 dp and
  above.
- Testing with `adb shell input text` or `keyevent` flips the live input mode to Keyboard and
  disables the unarmed search field. That is the test tool, not a bug.

Per-surface audits and the contributor procedure are in `docs/dpad.md`; the regression suite is
`dpad_test_suite/` (`run_all.sh`, `audit_static.sh`, `audit_dynamic.sh`).

### 10.4 Settings

Settings is hub and spoke: `SettingsScreen` dispatches over a `SettingsSection` enum with no
navigation library, `SettingsHub` holds the category rows and the settings search, and
`SettingsScaffold` owns the focus plumbing every page builds on. The search is a static
`SEARCH_INDEX` of label resource to section; a match opens the spoke **and** scrolls to the row
(`Modifier.settingsAnchor(label)` plus `LocalSettingsHighlight`). A new row label must be added
to the index, and a group whose rows can be empty must still render with an empty-state hint, or
its index entry leads to nothing.

**No blocking IPC or IO from a composable body.** A `PackageManager` query in composition
re-runs on every recomposition; load such data with `produceState` plus `withContext(IO)`.

### 10.5 Full-screen viewers

Cover the system bars with `FLAG_LAYOUT_NO_LIMITS` plus transparent bar colors plus
`Modifier.requiredFullScreen()` on the content root plus a top gradient scrim. Hide-bars, dim
and decor tricks leave strips; a Compose dialog window re-asserts inset-fitted params and
refuses to cover the bars.

### 10.6 Android Auto

`app/car/` is a navigation-category `CarAppService` (manifest service, `automotive_app_desc.xml`
`<uses name="template"/>`, the `androidx.car.app.*` permissions, `minCarApiLevel=1`). A sideload
appears in the car launcher only with the Android Auto developer setting "Unknown sources" on,
so the host validator is open. What the phone-side gate actually checks, read off a car log on a
GrapheneOS phone with sandboxed Play (2026-09-22): the Android Auto app asks the Play Store for
the app's owners and denies a package Play never installed (`PlayGearheadService app.vela, app
owners empty` then `CAR.VALIDATOR: Package DENIED`), whatever the install fields say, and the
"Unknown sources" toggle does not cover it; on a stock Pixel an install routed through Google's
own package installer passes. The car map re-applies the palette whenever the car's day/night
changes, draws the phone's puck bitmap rotated by heading minus camera bearing, and keeps the
speed badge and the attribution inside the host's stable area (the part no template UI ever
covers), falling back to the visible area when the host reports no usable stable area. The
guidance voice is band-limited by the protocol
(the Android Auto guidance stream is 16 kHz mono).

Screens: `MainCarScreen` (`PlaceListNavigationTemplate`) to `SearchCarScreen` (`SearchTemplate`)
to `RoutePreviewCarScreen` (`RoutePreviewNavigationTemplate`) to `ActiveNavCarScreen`
(`NavigationTemplate`). `VelaCarSession` owns its own AOSP location feed into the shared
`NavSession`, so navigation runs with the phone UI closed, and handles
`androidx.car.app.action.NAVIGATE` geo intents.

- The map is MapLibre's public `MapSnapshotter` to a bitmap to the car surface. The
  VirtualDisplay and Presentation approach is gone. The renderer map-matches the puck to the
  route, gates the feed to GPS-only, and eases puck and heading between the roughly 1 Hz fixes.
- **One `CarMapRenderer` per session.** Per-screen renderers freeze the map.
- The turn card needs both `NavigationManager.navigationStarted()` and `updateTrip()`;
  `ManeuverMapper` maps Vela maneuvers to car `Maneuver`/`Step`/`Trip`, reading roundabout
  direction and exit number from the route's own geometry rather than assuming.
- The snapshotter resolves the same patched style file the phone map uses; a plain style URL
  leaves the car on Noto.
- The car nav screen: a paused drive shows a `MessageInfo` ("Paused") in place of the turn card
  and the strip carries Pause/Resume; a turn farther than `CONTINUE_FAR_M` (1,500 m) leads the card
  with "Continue on <the road you are on>" (`Maneuver.roadAt`, the phone's pill rule) and shows the
  turn as the "then" step; a search icon opens `AlongRouteCarScreen` (the quick categories as rows,
  a pick searches around the car, a result becomes the next stop through `NavSession.addStop`); the
  map strip's fourth action toggles an overview of the remaining route (`toggleOverview`, exempt
  from the pan auto-recenter). `CarBridge` (`app/car`) carries the phone controller's spoken alerts
  (cameras, speeding, closing soon) to a `CarToast` and the route's lights, stop signs and speed
  cameras to the renderer, which draws them as dots from z13.5 with the plate cameras along the
  route read straight off the bundled set.
- The car snapshotter is themed with the phone's palette through the `StyleLayers` interface
  (`applyMapTheme(SnapshotterHost(snapshotter), dark, amoled)`), applied from the first snapshot
  callback because the style observer never fires for a style handed over as JSON; that first
  frame is discarded for a themed one. The renderer draws no library
  overlay (`QuietSnapshotter`) and its own single OpenStreetMap credit, frames the puck inside
  the host's visible area at `PUCK_DOWN` (0.72) of its height while following in nav (meters
  per pixel from 512 px tiles), glides the puck with `FollowEstimator`, and eases the speed-tiered
  zoom (`ZOOM_EASE` 0.06 per 70 ms tick). A drive started from the car speaks with the Piper voice
  when it is installed and chosen (the service attaches the synth).

### 10.7 Street View

The panorama is rendered in-app, not embedded. Google's WebGL page serves a stripped shell that
renders black on ANGLE; do not retry the embed.

- Metadata comes from the keyless JS-API `GeoPhotoService.SingleImageSearch` (by location) and
  `photometa/v1` (by pano id), authorized by a `Referer: https://www.google.com/maps/` header.
  The address, copyright and position live **inside** the pano node, not at the root. The
  `photometa` response nests one level deeper and carries a `)]}'` guard.
- Tiles come from `streetviewpixels-pa.googleapis.com/v1/tile`. The `/v1/thumbnail` path returns
  403.
- `StreetViewTiles` stitches one zoom level (about 2048x1024 or 4096x2048) and `PanoramaView`
  textures it onto a GLES2 sphere. **Never stitch the full 16384x8192 level**: that is about
  400 MB of texture.
- Two GL rules: view the sphere from inside (culling off) and use **natural U** (`uv = u`).
  Looking down -Z, screen-right is world +X, so a flipped U mirrors the whole panorama, which
  reads as backwards signage.
- **The tile pyramid is not one shape.** Modern panoramas are `512 * 2^z` wide; captures from
  before about 2016 are `416 * 2^z` and some have only four levels. Size the grid from the
  panorama's own level dimensions, or the loader requests tiles past the old grid's edge and
  paints black bands. A stitch that is not 4096x2048 is scaled up, which is exact because an
  equirect still covers 360 by 180 degrees.
- **The compass frame**: Google puts the capture heading at the texture center (u = 0.5) while
  the renderer's yaw 0 looks at u = 0.75, so compass bearing B is renderer yaw `B -
  captureHeading - 90`. Use `setCompass(panoHeading, faceCompass)`; never feed a compass bearing
  in as a raw yaw, and never overwrite the pano's own `headingDeg`, which is the texture
  reference.
- **Which panorama opens** is copied from Google: the search response's Street View thumbnail URL
  carries the exact pano id and camera yaw, so it is used verbatim. The geometric heuristics
  (nearest pano, street-of-address match, perpendicular probes) are the fallback for entries
  without a thumbnail; geometry alone provably mis-picks.
- Walking fetches the neighbor **by pano id**, never by nearest location, or a walk lands on a
  different-year capture at the same spot. Time travel resolves the historical pano's own
  metadata, because epochs differ in both pyramid shape and heading by up to 180 degrees.
- The viewer is a top-aligned pane over the live map, not a dialog. It reports its pose so the
  map draws the puck and a view cone, and the camera eases to the panorama on each hop only,
  never per yaw frame. A tap on the visible map moves the panorama there.
- A `SurfaceView`'s window hole does not follow a pure-Compose resize, so the fullscreen toggle
  recreates the view (`remember(full)` inside `key(view) { AndroidView(...) }`) and re-feeds the
  texture and the current yaw. Zoom is canonically the **horizontal** field of view; holding a
  fixed vertical field of view narrows the view when the pane grows.

### 10.8 Content gating

`ShowReviews` and `LoadPhotos` gate both the fetch and the render, so off means no scrape
traffic rather than hidden UI. `HideAdult` flips the `:core CategoryFilter` flag, which filters
at the `search` and `nearbyPlaces` seam on **category only, never name**, with multilingual
keyword lists, and drops the bars chip from the quick categories because the filter would empty
it. `HideExternalLinks` hides the website row, the Street View pill and the book/order action.
Any new review, photo or external-link surface goes behind the matching holder.


---

## 11. Remote resilience

`CalibrationStore` fetches `calibration.json` and the detached `calibration.json.sig` from the
repository's raw URL at launch and adopts the remote bundle only if all three hold:

1. **The signature verifies** - ECDSA P-256 with SHA-256 against `PINNED_PUBLIC_KEY`. A bad or
   missing signature is ignored and the last good bundle stands; an unsigned or older cache
   falls back to the compiled default for one launch.
2. **Every endpoint host is on the allowlist** (`google.com`, `www.google.com`).
3. **The version is newer** than the active one.

What the bundle can carry, in increasing power:

- **Configuration**: pb templates, endpoint URLs, the photo proto, the search parser's
  positional `paths`, `directionsPaths`, the language keyword tables (`statusClosedWords`,
  `statusOpenWords`, `transitCategoryWords`, `transitExcludeWords`, `reviewWords`), the review
  scrape's CSS selectors, the browser identity fields, the fleet defaults (`defaultVoiceId`,
  `defaultVoiceSpeaker`, `defaultVoiceSpeed`, `defaultMapPalette`, `defaultPlacesSource`,
  `classicRoutePicker`) and the `tuning` dials.
- **Notices**: an array of `id`, `level`, `title`, `body`, `url`. Level `urgent` renders as a
  modal dialog; anything else is a dismissable card on the bare map.
- **Parse logic**: `transformsJs`, a JavaScript bundle run in a Rhino sandbox (`JsSandbox`:
  `optimizationLevel = -1` because ART cannot run Rhino's bytecode generation,
  `initSafeStandardObjects` so no Java or IO is reachable, R8 keep rules in
  `core/consumer-rules.pro`, and a private `ContextFactory` arming the instruction observer as a
  2-second wall-clock kill switch). `JsTransforms` exposes `parseSearch(rawResponse)` and
  `transformPlaces(placesJson)` over a flat `PlaceJson` contract. **Compiled Kotlin is always
  the fallback**: no script, a missing function or any error leaves the result unchanged.

`tuning` is a flat name-to-number map read through `Calibration.tune(key, compiledDefault)`, so
a missing key means the compiled default and adding a dial is a configuration edit. View-layer
consumers read `CalibrationStore.latest`.

**Daily health check** (`.github/workflows/google-health.yml`, 14:20 UTC and on dispatch):
`GoogleHealthProbeTest` runs the app's request builders and parsers with the repo's
`calibration.json` from the Davis fixture (search, directions, directions with avoid-highways,
autocomplete) and prints `HEALTH|check|OK|BLOCKED|DRIFT|detail`. DRIFT (an answer the parsers
cannot read, or the avoid flag ignored) fails the run; BLOCKED (403, 429, the sorry page, a
consent wall: a datacenter IP's treatment) is a warning. `scripts/check-chrome-ua.py` fails the
same workflow when the Chrome major Vela claims is behind a Windows stable major that has been out
7 days, or ahead of stable. A failed scheduled run mails the maintainer; nothing is posted.
Locally: `./gradlew :core:testDebugUnitTest --tests '*GoogleHealthProbeTest' -DvelaLive=true --rerun-tasks`.

Two procedural rules:

- **Adding a `Calibration` field requires wiring `CalibrationStore.parseBundle()` separately.** Fields
  have shipped unread because only the data class was updated; grep for the field name in
  `CalibrationStore` before assuming a push will land.
- A fix that needs genuinely new parsing **logic** still ships as a release, unless it can be
  expressed in `transformsJs`.

---

## 12. Degoogled constraints

These are hard rules. Regressing one is a release blocker.

- **Location**: AOSP `LocationManager` only, never `FusedLocationProviderClient`.
- **`LocationListener` must be an explicit object, never a SAM lambda.** The lambda implements
  only `onLocationChanged`; the other three methods got default bodies in the Android 11 SDK, so
  it compiles, but on Android 10 and below the framework interface has no defaults and a
  present-but-disabled provider triggers `AbstractMethodError` on every launch.
- **Voice**: AOSP `TextToSpeech`, engine-selectable, plus the bundled in-process neural voice.
  Never a hard dependency on Google TTS.
- **Dictation**: on-device or an installed recognizer app. Never a cloud STT API.
- **No GMS**: no FCM, Firebase, Play Integrity or fused location. Push, if ever, is UnifiedPush;
  crash reporting is ACRA or self-hosted.
- **No static Google API key**, in any build variant, ever.
- **EU consent**: pre-seed `SOCS` and `CONSENT`; never let a `Set-Cookie` downgrade `CONSENT` to
  `PENDING`.
- The hidden WebViews run Google's JS **anonymously**. That is a scoped tradeoff for data only a
  real engine is served, and no sign-in is offered anywhere.
- **Permissions are asked with context.** Onboarding is welcome, location, notifications, voice,
  and nothing more; a contextual in-place ask is preferred to a new onboarding step. A
  coarse-only grant gets a one-time explainer and draws a real accuracy circle; navigation gates
  on fine location with an upgrade dialog; a permanently denied locate tap deep-links to system
  settings.
- **Memory pressure is handled.** `MemoryPressure` fans `onTrimMemory` out to registered
  releasers: the ASR model (severe trims plus a 120 s idle reap, with an in-flight counter,
  because freeing native memory mid-decode is a use-after-free), the neural voice (critical
  only, since dropping the voice mid-drive costs a turn), MapLibre's native caches, every hidden
  WebView, and the image cache. `MemoryPressure.lowRam` drives a constrained path: a smaller
  image cache, no ASR warm-up, no speculative WebView warms, and an 8-term ambient fan-out.
  **Any new large or native holding registers a releaser here.**
- **Constrained networks.** `ConstrainedNetwork` reads `NOT_BANDWIDTH_CONSTRAINED` and
  `TRANSPORT_SATELLITE` **by name** through reflection, because the compile SDK does not define
  them and hardcoding a framework integer is a guess. Absent constants yield false. On a
  constrained link the photo walk is skipped and the ambient fan-out takes the lean path.

---

## 13. Performance model

**The two serial resources** are MapLibre's render thread, which serializes symbol collision
placement, every `setGeoJson` and `setProperties`, and drawing itself; and the main thread,
which hosts the camera tickers, all JNI style calls and Compose recomposition. Neither can be
parallelized from app code. The only lever is sending each of them less work, and never at the
moment they are already saturated.

Standing rules:

- Identity-gate every source upload and every property write, and reset every gate holder on
  style reload.
- Never move route geometry per frame or on a short timer (section 4.8).
- Never re-place a whole layer for a value that did not change: a `setFilter` re-lays the whole
  source.
- Keep per-frame work in the layout or draw phase, never in composition.
- Anything read per frame goes into a `mutableFloatStateOf` holder, not `MapUiState`.
- Compose recomposition of one large state object is the cost center: gate high-frequency writes
  (the scale bar pushes only past a 1 percent change; the compass heading is throttled by angle
  and interval).
- A `queryRenderedFeatures` call in a dense view costs 37-55 ms even when it returns nothing.
  Debounce it, gate it on camera stillness, and never run it from an idle event unthrottled.
- **Build release for anything a person will feel.** Measured on the same device, same cold place
  tap, sheet drag and review scroll: debug 14.8 percent janky frames with a 90th percentile of
  69 ms; release 1.1 percent and 28 ms. A whole session's worth of "the sheet lags" was a debug
  build.
- The baseline profile at `app/src/release/generated/baselineProfiles/baseline-prof.txt` is
  committed and baked into every release build, so a sideloaded build is compiled at install
  time instead of running interpreter-cold until overnight background compilation. Regenerate it
  on the Gradle-managed emulator. **Never point that task at a connected device**: the harness
  uninstalls the target app when it finishes, which wipes saved places, trips and grants.
- Measure the right thing. `dumpsys gfxinfo` percentiles cannot separate CPU from GPU; read
  `framestats` phases instead (`DrawStart - PerformTraversalsStart` is measure and layout,
  `FrameCompleted - IssueDrawCommandsStart` is GPU). `gfxinfo` is blind to the GL map surface
  entirely, so the in-app frame probe is the fair per-frame number. A phone throttles after a
  few minutes of scrubbing; check `dumpsys thermalservice` and alternate A/B runs.

**The structural ceiling.** Google bakes server-ranked places and placement priorities into its
tiles and places symbols incrementally in C++, so it never pays runtime whole-layer collision
re-placement when data arrives. MapLibre re-solves whole-view symbol placement on its single
render thread during camera motion, and every `setGeoJson` forces a whole-layer re-place. Vela
streams places at runtime by design, so a placement spike at data-arrival moments in dense areas
is permanent; deferring it to camera-idle moves it where it is invisible. Expect pans and
flights indistinguishable from Google most of the time, with a brief settle-then-appear rhythm
in dense areas.

---

**Measuring the map.** `dumpsys gfxinfo` counts the Compose chrome and is BLIND to the map, which
draws on its own GL thread in a SurfaceView: it reports zero frames rendered for a pan that visibly
stutters. The map's own end-of-frame callback is the signal. `VelaMapView` logs it once a second
under `VelaFps` when the system property `debug.vela.fps` is set (read at map creation, so restart
after setting it), and `scripts/map-fps.sh` runs the whole loop: property, restart, warm map, a
fixed pan pattern, then min/p10/median/max. A Pixel 4a holds 40-55 fps panning a suburb at browse
zoom.

**A dense GeoJSON source needs a high maxzoom.** Past a source's maxzoom every visible overscaled
tile lays out ALL of its parent tile's features, so a source holding hundreds of points and drawn
several zooms above its maxzoom re-places the lot every frame. The ambient places source cost
22 fps against 51 until it went from 12 to 18; the traffic controls (up to 800 along a route),
transit stops and result markers are 18 for the same reason, the camera layers 16, and genuinely
sparse sources (the puck, parking, saved places, Street View, the accuracy circle) stay at 12.

## 14. Privacy, diagnostics and location hygiene

### 14.1 What leaves the phone

`PRIVACY.md` is the user-facing accounting and must agree with section 1.4. Browsing the map in
the default configuration contacts Google not at all. Search, opening a place, traffic and
transit directions reach Google. Routing reaches FOSSGIS. Transit boards reach Transitous.
Reverse geocoding reaches Nominatim.

### 14.2 Diagnostics

`DiagLog` is an opt-in breadcrumb ring, off by default, persisted to a bounded
`filesDir/diag_log.jsonl` so a report survives the process death that usually precedes it, and
deleted on opt-out. `DiagExporter` shares it as a JSON bundle, user-initiated, never uploaded.

`DiagScrub` has two levels. Plain export rounds coordinate-shaped decimals to 2 places (about
1 km). "Redact places in exports" rounds to 1 place (about 10 km), replaces quoted search terms
and intents, drops a navigation start's destination label, keeps only the host of a URL, blanks
`cid` values and drops page-text detail. Counts, zoom levels and error text stay. Add a case to
`DiagScrubTest` whenever a new breadcrumb can carry a name or an address.

**A page probe never logs Google's `@lat,lng`.** Google's place-page path carries a coordinate
derived from the session rather than from the place, so logging `location.pathname` verbatim put the
user's own area in logcat and in any shared export. Probes log the path up to `/@`.

`NavTrace` (off by default) records one row per navigation frame: time, along-route progress,
speed, bearing window, chord bearing, display bearing, camera bearing and frame dt. It carries
**no** position data, which is what makes it safe to attach to a public issue, unlike a recorded
trip.

### 14.3 Sharing a trip

A trip leaks its owner's endpoints in six places, and `TripScrub` handles all six: the fixes,
the `META` destination, the `META` label, the maneuvers, the route polyline's start, and the
spoken lines. It **trims** rather than blurs: every fix within the chosen radius of the start,
the end, the destination and the user's Home and Work is deleted and the middle is kept at full
precision, because rounding protects the ends weakly while destroying the geometry the trip
exists to diagnose. Timestamps rebase to zero. An `S`, `J` or `B` event survives only if a fix
within `EVENT_NEAR_MS` (3 s) survived, and a route block whose polyline trims to nothing drops
its `RD` and `M` lines too. A batch share trims every entry; a trip that trims to nothing is
left out and counted, never sent raw.

### 14.4 Location hygiene

This is about awareness, not a ban on real places. Real places are the raw material of a maps
app: naming the business whose hours parse wrong is a good bug report. The failure mode is
**defaulting to your own surroundings without noticing** - test coordinates, screenshot corners,
sample addresses, "verified on a drive to X" commit lines. Each is nothing alone; together, in
permanent public history, they put the author on a map, and scrubbing them later means rewriting
history.

The question to ask before any place, address or coordinate enters the repository: **was this
chosen for a reason anyone could have, or is it here because it happens to be near the author?**

- **Locality is input, never output.** Use it in a session to reason, query and reproduce; the
  write-up carries the mechanism only. "A fuel station on a corner opened the transit stop beside
  it, because the stop icon outranked every business in the tap box" says everything the fix
  needs. If a bug cannot be explained without the place, it has not been root-caused yet.
- **Fixture default: Davis and Sacramento, California.** Bounding box `38.30,-122.00` to
  `38.90,-121.20`; example address `1451 W Covell Blvd, Davis, CA 95616`; San Francisco
  (`37.7749,-122.4194`) for a generic big city; `37.0,-122.0` for an abstract grid. A different
  area is fine with a reason; a synthetic grid at the author's own latitude is not a reason.
- **Screenshots** default to the demo tools (simulate location, simulate driving). Check the
  corners either way: search recents, place labels and street names all talk.
- **Recorded trips, diagnostics exports and adb dumps carry raw GPS.** Never attach them to
  issues, commits or CI artifacts.
- `.github/workflows/location-guard.yml` scans added diff lines and commit messages against the
  repository secret `LOCATION_TERMS` (one term per line) and reports the file and line only,
  never the matched text. It cannot see issue bodies, comments, release notes or screenshots.

### 14.5 Attribution

No commit message, pull request body, issue comment, release note or file in this repository
carries an AI co-author trailer, a "generated with" line, or any other AI attribution. The
project reads as written by a person because it is maintained by one. This rule overrides any
tooling default that claims otherwise. Before pushing, `git log origin/main..HEAD --format='%B'
| grep -ci "claude\|anthropic\|generated with"` must be 0.

---

## 15. Build, release and distribution

- **Toolchain**: AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1, compileSdk 35, minSdk 26, Java 17,
  Compose, Hilt, a version catalog, R8 in the `release` build type.
- **Channels.** A push to `main` or `canary` builds and tests only; a push can never mint a
  release. The nightly prerelease `v0.4.<run>` (versionName `0.4.<run>`, versionCode
  `2000 + run`) is cut by a daily cron that skips when `main` has not moved, or on demand. Its
  title is `Vela <version> nightly` and its notes open with "Nightly build."; the promotion
  retitles to `Vela <version>` and regenerates the notes, so the channel is readable on the
  release page and in the in-app What's new dialog.
  `canary` is the working branch and also a real update channel: each push replaces the single
  APK on a fixed-tag rolling release whose tag is deliberately not `v0.*`. A weekly workflow
  promotes the newest nightly to stable: same tag, same signed APK, no rebuild.
- **The run number must stay in `ci.yml`.** A separate workflow would reset the counter and
  regress versionCode. Never name a release `v0.4.0`: the updater's regex takes the run number
  for the version code, so it would read as 2000 and never be offered. Keep local development
  builds below versionCode 1000.
- **Commit subjects are the changelog.** Release notes are built from the subjects since the
  previous `v0.[0-9]*` tag. Every stable's notes lead with a hand-written "what's new" list above
  the generated commit list, because the in-app dialog shows the release body verbatim.
- Docs-only pushes skip CI and cut no nightly (`paths-ignore`); a mixed push still builds.
  An fdroid metadata-only change also skips the index rebuild, which rides CI completion.
- **F-Droid.** `fdroid-repo.yml` rebuilds a signed self-hosted repository (latest stable plus
  newest nightly) and deploys it to GitHub Pages, pinning the index's suggested version to the
  latest stable so default clients update weekly. It triggers on `workflow_run` of CI and the
  promotion, not on the release event: releases created by CI's own token do not fire events for
  other workflows. Never re-run only a failed deploy job; dispatch a fresh run.
- **The project website rides the same Pages artifact.** Never add a second Pages deploy
  workflow: `actions/deploy-pages` replaces the whole site, which would take down the F-Droid
  channel and the map glyphs.
- **Signing.** The release keystore lives outside the repository (`~/.vela-signing/`), with CI
  secrets `VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`. Losing it means
  never updating installed builds. The app signing certificate's SHA-256 is published in README
  and FDROID.md and can be read from any signed APK with `apksigner verify --print-certs`; do
  not confuse it with the F-Droid index fingerprint. The calibration signing key is separate and
  equally never committed. A `MAPTILER_KEY` secret reaches `BuildConfig` only.
- **In-app updater** (`SelfUpdater`): lists the app-release tags through
  `git/matching-refs/tags/v0.` and reads one release per tag (`releases/tags/<tag>`; stable reads
  `releases/latest`, canary its rolling tag), never the releases list, whose data releases carry
  ~450 assets each and made a check 4 to 9 MB; a check is 2 to 3 requests plus at most
  `HISTORY_MAX_RELEASES` (8) for the cumulative notes, and logs one `VelaUpdate` line with its
  request and byte counts. It derives the version code from the tag, offers a card, downloads with a no-call-timeout client, checks the zip magic
  bytes, and hands the file to the system installer through the FileProvider, which enforces
  same package and same signature. Notes are cumulative across the versions between installed
  and offered. The launch check is throttled to about daily and "not now" pins the dismissed
  code.
- **Reach is snapshotted weekly** (`scripts/download-stats.sh`, `download-stats.yml`, into
  `docs/stats`). Not telemetry: release download counters, the repo traffic API and per-region
  asset counts are byproducts of hosting files, and every one of them EXPIRES - a deleted release
  takes its counter, traffic is a rolling fourteen days, and a rebuilt asset restarts at zero.
  `docs/stats/README.md` carries the rules for reading them (a streamed overlay counts tile reads,
  not people; the F-Droid channel is invisible; the newest stable's counter is the closest thing to
  an active-install floor because each device pulls it once).
- **Stables are never pruned** (CI's prune step): they are the changelog, the build somebody
  bisecting a regression installs, and the only record of reach there is. Nightlies keep a rolling
  thirty.
- **An update that would cost the car screen asks first** (`InstallSource`). Android Auto will not
  list a sideloaded navigation app, and the tools people use to get around that (AAEnabler, King
  Installer) work by setting the INSTALL SOURCE to Play. The source belongs to the install, not to
  the file, so a self-install overwrites it and the head unit drops Vela. When
  `installingPackageName` is Play - which on a build that is not distributed there can only have
  been put there deliberately - the downloaded APK is held back and offered as a FILE instead, so
  it can be installed through the same tool as the first time; "Update anyway" installs it and
  loses the listing. Obtainium and a plain sideload are unaffected: they claim their own install
  source, which Android Auto was never going to accept.
- **On-device loop.** Build release at or above the installed version code, `adb install -r`,
  and `am force-stop` before `am start` (installing over a running app keeps the old dex).
  **Never `adb uninstall`**: it destroys saved trips and permission grants.
- `MapScreen` sits near the JVM 64 KB method limit. Content lambdas do not count toward it but
  direct composable calls and their argument lists do; when a debug compile fails with "method
  too large", move a call with a long argument list into a small private composable. Four blocks
  are split out that way, all private composables in `MapScreen.kt` below `MapScreen` itself:
  `MapSurface` (the `VelaMapView` call, plus the style URI, nav label lists and saved-pin list
  only the map reads), `BoxScope.BuildingDebugBadge`, `BoxScope.NavTurnBanner` and
  `SearchEntryHost`. A split block takes the locals it needs as parameters; a callback that
  writes `MapScreen`'s own state is passed in as a parameter rather than moved.

---

## 16. Where the rest lives

`FEATURES.md` is the exhaustive, dated list of what shipped and in what order, including the
features this document describes only by rule. `ROADMAP.md` holds open work and the explicit
"not going to happen" list. `docs/book/` explains subsystems for a reader who wants to
understand a behavior rather than rebuild it. `CLAUDE.md` carries the working rules for
contributors and assistants.
