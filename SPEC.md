# Vela Maps - technical specification

The technical reference: rules, contracts, constants and thresholds, stated as what the code
does now. Where this file and the code disagree, the code is right. `docs/book/` explains each
subsystem in prose; section 16 lists the other documents.

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

Vela Maps (`app.vela`) is a maps and navigation app for Android phones without Google Play
services: search, places, routing, traffic-aware ETAs and turn-by-turn navigation. It targets
GrapheneOS and other ROMs with no GMS. License GPLv3. Distribution is F-Droid and Obtainium.

### 1.2 Non-negotiables

- No Vela backend. Each install talks to its sources directly from the user's own IP, like one
  logged-out browser. There is no shared API key and no server tier.
- No static shared Google credential. Sessions bootstrap per install (`GoogleSession`). The
  project's legal footing rests on this.
- Open data draws the map: open vector tiles, and by default Vela's own bake of open datasets
  for places. Section 1.4 lists what Google is asked for.
- Degoogled at runtime (section 12): AOSP location and TTS only; no FCM, Firebase, Play
  Integrity or fused location.
- Recalibratable without a release. Field indices, pb templates, endpoints, word tables,
  tuning dials and parse logic ship over a signed remote channel (section 11), because Google
  reshapes its endpoints.

### 1.3 Non-goals

- Account sync of any kind.
- Anything that needs a Google sign-in, such as review "helpful" counts (section 3.4).
- A Vela-hosted tile, routing or search service.

### 1.4 Capability and source matrix

| Capability | Source | Reaches Google | Works offline |
| --- | --- | --- | --- |
| Basemap | OpenFreeMap Liberty vector tiles, or a downloaded PMTiles region | No | With a downloaded region |
| Place icons while browsing | Vela data by default (Overture, AllThePlaces and OpenStreetMap, baked in this repo); Google's ambient places or both by setting (section 5.1) | Only in Google or Both mode | Vela data, with a downloaded region |
| Opening a place | Google listing, correlated to the tapped feature | Yes, unless the lookup toggle is off | Tile data, or a saved copy |
| Search | Google autocomplete per typing pause (`suggest`), Google `search?tbm=map` on submit, Photon beside them for house-number text; offline, the place packs and the downloaded places archives (`PlacesArchiveSearch`, ranked with the packs in `OfflineRank`) | Yes | Region packs, places archives |
| Reviews, photos, popular times, About | Photos: one `hspqX` request. Popular times and details: the search reply or one focused search. Reviews: a hidden WebView scrape. The page walk only as fallback or for More photos | Yes | Saved copy only |
| Driving route | Google's line with open-data steps (`HybridRoute`, section 4.1): FOSSGIS OSRM's where both routes share the road, else a Valhalla map match, map-tile street names, then bare turns. Without Google: the OSRM route. Without OSRM: Google's own short steps, or the on-phone router | Yes | Downloaded `.obf` region |
| Traffic and live ETA | Google directions | Yes | No |
| Walking, cycling | Walk: OSRM foot; Google's walk only when 15% shorter (`WALK_GOOGLE_SHORTER`). Bike: safety-weighted by default, on-phone profile, else Valhalla | Walk: to compare. Bike: not by default | Downloaded region |
| Traffic layer; satellite past z19 | Google raster tiles, both off by default | Yes | No |
| Traffic controls (lights, stops, crossings, humps) | Per-region road-features bake, Overpass only where no region exists | No | Yes |
| Surveillance and speed cameras | Bundled and hosted DeFlock dataset; OSM speed cameras | No | Yes |
| Transit boards and stop icons | Transitous (open GTFS + GTFS-Realtime); a Google-listed stop Transitous does not cover falls back to the stop's Google page | Only as the fallback | Last board seen, cached areas |
| Transit directions | Google's transit page; Transitous' planner (`/api/v1/plan`) when Google is off or answers nothing | Yes, unless Google is off | No |
| Street View | Google keyless pano metadata and tiles, rendered in-app | Yes | Viewed panoramas |
| Reverse geocoding (pins, house-number and building taps) | Nominatim | No | No (the pin reads "Dropped pin"); a typed address geocodes offline from the region packs |

---

## 2. Architecture

### 2.1 Modules and boundaries

Two Gradle modules.

- `:core` is the UI-agnostic extractor: data sources, parsers, routers, the navigation engine,
  offline stores, remote config, diagnostics. No MapLibre type and no Android UI type appears
  in it; coordinates convert at the view boundary. Compile-time switches are in `VelaConfig`.
- `:app` is the Compose UI, MapLibre Native 13.6.1, the foreground navigation service, the
  Android Auto service and the five hidden WebView scrapes. Root package `app.vela`,
  application class `VelaApp`.

`:app` reads `:core`; `:core` cannot read `:app`. A setting that must act inside `:core` is
written by `:app` into a plain flag there: `CategoryFilter.enabled`, `LowRamMode`,
`LowDataMode`, `NoGoogle`, `RoutingPrefs`, `SpokenRoadNames`.

#### Use Vela without Google

`GoogleFree` (Settings > Privacy, pref `google_free`, default off) mirrors into
`NoGoogle.enabled`. With it on:

- Search answers from Photon, two requests run together: 20 results softly biased toward the
  user, and 10 inside a hard box around the view for partial addresses. Vela's own places lead
  the list (place packs plus the places archive, downloaded or streamed by HTTP range,
  `PlacesArchiveSearch`). Photon is skipped when they answer a category query, or a name query
  with a hit within the view span of its center, at least `GOOGLE_FREE_NEAR_M` (3 km) and at
  most `GOOGLE_FREE_NEAR_CAP_M` (25 km).
- The page-2 search, the ambient fan-out, reviews and photos answer empty. Street View
  answers null.
- The Google directions call answers empty, so every route is the open router's: no traffic, no
  Google alternates, no abbreviated fallback. Transit directions come from Transitous' planner.
- In `:app`, `HiddenWebView.request` returns null, the traffic raster is not added, the
  satellite Google fallback draws no deep layer, the tap lookup is skipped, and the Street View
  pill and the full-screen reviews page are hidden.
- A short Google Maps link (`maps.app.goo.gl`) is resolved by a cookieless request to Google's
  shortener that reads the redirect and stops (`ShortLinks.resolve`), only while "Open shared
  Google Maps links" (`GoogleFree.resolveLinks`, pref `google_free_resolve_links`, default on)
  is on. Off, it is refused with a toast. The target is parsed on the phone and searched
  through the open sources.
- A shared list is refused (`importList` returns null): its places exist only on Google's
  servers.

### 2.2 Module tree

The main classes by package. Parentheses name a class inside the preceding file.

```
:core  (app.vela.core)
  VelaConfig, di/CoreModule
  model/          LatLng, Place, Route, Maneuver, Transit types
  data/
    MapDataSource, MockMapDataSource
    google/
      GoogleSession, GoogleMapsDataSource
      PbBuilder, SearchPb, DirectionsPb   request protobuf grammar
      GoogleResponse        XSSI strip and positional-array navigator
      PolylineCodec, StreetViewParser
      parse/                Search, Directions, Transit, Photos, Reviews, EntityList,
                            PopularTimes parsers
    naming/               HybridRoute, LineNamer, RoadNameTiles
    RouteGeometry         OSRM steps, lanes, via snapping
    RouteEngine           on-phone routing interface
    ObfRouteEngine        OsmAnd router over .obf files
    ValhallaRouter        bicycle routing, map matching
    RouteCorridor         search along a route; ahead() during a drive
    PhotonGeocoder, ShortLinks, PlaceCache, StreetViewCache
    OverpassPois, OverpassEndpoints, OverpassTrafficSignals, OverpassAlprCameras
    OfflinePoiStore, OfflineAddressStore, OfflinePacks, OfflineRank
    transit/Transitous    MOTIS client: stops, boards, trips, plans
    CategoryFilter        content gating
    tiles/                map style catalog
  net/            GoogleTransport (the Cronet hook, AgedSession)
  location/       LocationProvider (AOSP), HeadingProvider, MotionProvider, SpeedKalman,
                  AlongRouteFilter, DemoTrace
  nav/            NavEngine (pure), NavSession, NavReplay, RouteProjection, RouteBar,
                  CameraAlerts, CameraFacing, CameraDetour, DetourEstimate, SpeedingAlerts,
                  ExitLabel
  voice/          VoiceGuide, NeuralSynth, SpokenScript, SpeechText
  feedback/       Haptics
  config/         Calibration, CalibrationStore, BundleSignature, JsSandbox, JsTransforms
  search/         QueryIntents, VoiceCommandExamples
  replay/         TripLog, TripScrub, TripShareBatch
  i18n/           NavStrings
  util/           SunTimes, NameScript, PlaceNames, OpeningHours, Jitter, OsmHours (lines()
                  is the one entry point for OSM opening_hours)
  diag/           DiagLog, DiagEvent

:app  (app.vela)
  MainActivity, VelaApp
  ui/map/         MapScreen, VelaMapView, MapViewModel, NavController, SearchGates,
                  PoiIcons, AmbientStability, FollowEstimator, RoadShields, MapFonts,
                  StyleLayers, MapDpadController
  ui/place/       PlaceSheet (DirectionsPanel), GoogleChooser, RouteTopCard, StopsEditor,
                  PlaceOrigin, StreetViewScreen
  ui/nav/         NavOverlays (ManeuverBanner, NavControls), StepsSheet, RouteBarStrip,
                  RoundaboutGlyph, RouteShield
  ui/search/      SearchBar
  ui/settings/    SettingsScreen, SettingsHub, SettingsScaffold, sections/
  ui/theme/       AppTheme, Theme
  ui/             settings holders (section 2.3), SheetPalette, Format (Units), VelaMenu,
                  VelaDialog, DpadFocus, AdaptiveDensity, QuickCategories
  web/            HiddenWebView and its five fetchers (WebDirectionsFetcher,
                  WebPhotoFetcher, WebPopularTimesFetcher, WebReviewsFetcher,
                  WebStopDeparturesFetcher), ReviewsPanel, WebViewIdentity,
                  WebViewCookieJar, WebProxy, SessionRotation, GoogleStanding,
                  GoogleTelemetry
  net/            CronetTransport
  offline/        ObfStore, RegionCatalog, RegionPolys, PoiPackStore, PlacesTileStore
                  (PmtilesRegionStore, BasemapTileStore), PlacesArchiveSearch,
                  PmtilesReader, PmtilesPatch, PmtilesCompact, OverlayTileStore,
                  MaxspeedOverlayStore, GlyphPackStore, OfflineMaps, LegacyGraphs
                  (removes old GraphHopper graphs)
  car/            VelaCarAppService, VelaCarSession, CarMapRenderer, CarBridge,
                  ManeuverMapper, screen/
  data/           RoadFeatures, FlockCameras, ContactAddresses, TransitStopCache,
                  TransitBoardCache
  voice/          AsrRecognizer, AsrEngine, PiperSynth, VoiceInstaller, KokoroInstaller
  service/        NavigationService, NavGlyphs
  download/       DownloadService (DownloadWork)
  update/         SelfUpdater, ApkChoice, InstallSource
  diag/           DiagExporter, DiagScrub, NavTrace, CrashCatcher
  replay/         TripStore
  streetview/     PanoramaView, StreetViewTiles
```

### 2.3 Seams and process-wide state

- `MapDataSource` is the only interface the UI depends on. `GoogleMapsDataSource` is the real
  scraper; `MockMapDataSource` keeps the app usable with no network. `CoreModule` binds one by
  `VelaConfig.USE_GOOGLE_SOURCE`.
- `GoogleSession` bootstraps a logged-out session. The in-memory cookie jar pre-seeds Google's
  `SOCS` and `CONSENT` cookies so an EU session is not sent to `consent.google.com`, and drops
  any `Set-Cookie` that would downgrade `CONSENT`.
- Settings are process-wide holders, a `mutableStateOf` mirror over `SharedPreferences`,
  started in `VelaApp.onCreate`. A holder not started there reads its default on every launch.
  They are `AppFont`, `AppLocale`, `AppTheme`, `BikeSafe`, `BuildingDebug`, `BuildingOverlay`,
  `Buildings3d`, `CategoryChipsPref`, `ContactsSearch`, `DetailsRetry`, `DynamicColor`,
  `FasterRouteAuto`, `Flock`, `FlockDetour`, `FlockNavAlert`, `FlockRouteAlert`,
  `FullPlaceLoad`, `GoogleFree`, `HideAdult`, `HideExternalLinks`, `HouseNumbers`,
  `LayersButton`, `LinkAction`, `LiveReviews`, `LoadPhotos`, `MapColors`, `MapPoiPrefs`,
  `MapTilt`, `NavEndConfirm`, `NavNorthUp`, `OfflinePlaces`, `Onboarding`,
  `OtherLocationsAuto`, `PageTransitions`, `ParkingButton`, `PauseInBar`, `PhotosOnTap`,
  `PipTurnCard`, `PreferButtons`, `PuckStyle`, `RegionUpdates`, `ReviewsOnTap`, `RoadLabel`,
  `RoutePicker`, `RouteTrafficOnTap`, `RouteTrail`, `SatelliteLayer`, `ShowReviews`,
  `SimLocation`, `SpeechPreload`, `SpeedCamWarn`, `SpeedCams`, `SpeedDisplay`,
  `SpeedingAlert`, `SpokenRoadNames`, `Topography`, `Traffic`, `TransitLayer`,
  `TurnDeclutterPref`, `UiScale`, `Units`, `VoiceSearch`, `WhatsNew`.
- `MemoryPressure`, `PipMode` and `ConstrainedNetwork` hold runtime state, not a pref.
- One view model. `MapViewModel` owns `MapUiState` and delegates navigation to `NavController`
  through `NavController.Host`. Nav code never reaches into the view model. Anything an
  init-time collector touches is declared above `init`: `viewModelScope` is `Main.immediate`,
  so a collector's first pass runs inline, before properties declared below `init` exist.

### 2.4 Data flow, search example

`MapScreen` calls `MapViewModel.search()`, which calls `GoogleMapsDataSource.search()`. That
builds the `pb` with `SearchPb`, issues the GET, and parses the body with a remote JS transform
when one is pushed (`JsTransforms.searchOverride`, section 11), otherwise with `GoogleResponse`
plus `SearchParser` over the calibrated index paths. The list passes through a second JS hook
(`refineSearch`) and returns to the view model, which publishes it into `MapUiState`.

---

## 3. The Google extractor

Google's field numbers and array indices move when it reshapes a response. The live values are
in `calibration.json` (section 11). `Calibration.DEFAULT` is the compiled fallback and is
brought in step at each release. The `PbBuilder` grammar and `PolylineCodec` need no
calibration. A response that no longer matches throws `CalibrationNeededException`, which the
UI shows as a notice.

### 3.1 Endpoints

No request carries a key or an account. A bundle is rejected unless `searchEndpoint`,
`directionsEndpoint`, `reviewsEndpoint`, `photosEndpoint`, `sessionWarmUrl` and
`suggestEndpoint` are on `www.google.com` or `google.com` (`CalibrationStore.ALLOWED_HOSTS`).

| Purpose | Request |
| --- | --- |
| Session cookies | `GET /maps?hl=en&gl=us` (`sessionWarmUrl`), once per process before the first data call (`GoogleSession.ensure`) |
| Search | `GET /search?tbm=map&authuser=0&hl=en&gl=us&q=<q>&pb=<SearchPb>` |
| Ambient places | the search endpoint, one request per category term (5.4) |
| Autocomplete | `GET /s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=en&gl=us&pb=<suggestPb>&q=<q>&tch=1&ech=<n>` (`suggest`, `SuggestParser`) |
| Driving line, traffic, alternates | `GET /maps/preview/directions?authuser=0&hl=en&gl=us&pb=<DirectionsPb>` (4.1) |
| Steps | FOSSGIS OSRM, `routing.openstreetmap.de/<backend>/route/v1/driving/<coords>?overview=full&geometries=polyline6&steps=true`, backend `routed-car`, `routed-bike` or `routed-foot` |
| Map match; safety-weighted bicycle | FOSSGIS Valhalla, `valhalla1.openstreetmap.de`: `/trace_route` and `/trace_attributes`; `/route` with costing `bicycle`, `use_roads` 0.1 |
| Photos | `POST .../batchexecute?rpcids=hspqX` (3.7); the page walk as fallback and for photo categories |
| Reviews | hidden scrape of the place's `?cid=` page; the `qv9Egd` feed behind `nativeReviewFeed` (3.7) |
| Place details | the search endpoint with name plus address (`placeDetails`); the details page as last resort |
| Shared list | `/maps/preview/entitylist/getlist`, URL lifted from the share page (3.5) |
| My Maps | `GET /maps/d/kml?mid=<id>&forcekml=1` (5.6) |
| Transit directions | hidden WebView on `/maps/dir/<o>/<d>/data=!4m2!4m1!3e3` (section 8) |
| Street View metadata | `POST` to `Calibration.STREETVIEW_SEARCH_URL`; by pano id `photometa/v1` (10.7) |
| Street View tiles | `streetviewpixels-pa.googleapis.com/v1/tile` |
| Reverse geocoding | Nominatim `/reverse`. Google's map search does not reverse a coordinate |

A bare `q=` returns an empty envelope; results come from the viewport in the `pb`. `hl=en` is
rewritten to the app language when `SearchParser.STATUS_LANGS` has it (Chinese as `zh-CN` or
`zh-TW`), and `gl=us` to the phone's region.

### 3.2 Search response

Results are at `root[64][i]`, and each entry's place node is `[1]`. The keys are
`Calibration.DEFAULT_PATHS`; a bundle's `paths` overrides them one at a time.

| Key | Path | Field |
| --- | --- | --- |
| `results` | `root[64]` | result list |
| `single` | `root[0][1][0][14]` | the one place node of a focused or geocoded reply |
| `atThisPlace` | `root[0][1][0][14][68]` | businesses at a searched address, node at `[i][0]` |
| `similar` | `root[2][11][0]` | "People also search for": `[featureId, name, [[_,_,lat,lng], ..., rating@6]]` |
| `alsoSearched` | `root[0][1][0][14][99][0][0][1]` | related places under a focused result, entries `[_, placeNode]` |
| `name` | `[1][11]` | |
| `address` | `[1][39]` | fallback: `addressComponents` `[1][2]` joined |
| `rating`, `reviewCount` | `[1][4][7]`, `[1][4][8]` | |
| `lat`, `lng` | `[1][9][2]`, `[1][9][3]` | |
| `category` | `[1][13][0]` | |
| `website` | `[1][7][0]` | |
| `phone` | `[1][178][0][0]` | |
| `priceText` | `[1][4][2]` | a range such as "$10-20"; `SearchParser.priceLevelOf` derives the 1 to 4 level |
| `fuelPrice` | `[1][88][0]` | |
| `actionLabel`, `actionUrl` | `[1][75][0][0][5][0]`, `[1][75][0][0][5][1][2][0]` | the Book, Reserve or Order link |
| `featureId` | `[1][10]` | `0xHIGH:0xLOW` |
| `placeId` | `[1][78]` | |
| `photos` | `[1][72][0]` | FIFE URL at `[i][6][0]`, resized with `=w500-h350`. Landmark extras at `[1][204][0][i][1][2][0][0]` (compiled) |
| `featuredReview` | `[1][142][1][0][1][0][0]` | |
| `about` | `[1][100][1]` | title `[s][1]`, items `[s][2][j][1]`. The wheelchair filter reads attribute id `has_wheelchair_accessible_entrance` at `[s][2][j][0]` |
| `updates` | `[1][122][1]` | business posts: text `[1][0][0][0]`, posted epoch `[2][0]`, link `[4][1]`, link label `[4][2]`, photo `[5][0][0]` |
| `editorialSummary` | `[1][32][1][1]` | |
| `ownerDescription` | `[1][154][0][0]` | |
| `statusRich`, `openStatus` | `[1][203][1][4][0]`, `[1][203][1][8][0]` | status text |
| `status118` | `[1][118][0][3][1][4][0]` | the first department's status text |
| `hours203`, `hours118` | `[1][203][0]`, `[1][118][0][3][0]` | weekly hours (3.3) |
| `departments` | `[1][118]` | per entry: name `[0]`, hours `[3][0]`, status `[3][1][4][0]` |
| `closedFlag` | `[1][23]` | 1 = permanently closed |
| `popularTimes` | `[1][84]` | `[0]` holds 7 days, each `[dow, [[hour, percent, ...], ...]]` |
| none | regex over the entry | Street View thumbnail pano id and yaw (`SearchParser.svThumb`) |

Shape rules:

- A specific or far address answers with no `[64]` list, only the `single` node, which has the
  same schema. `singleResultEntry`, `atThisPlaceEntries` and `findResultsArray` validate
  through `paths.name`, so recalibrating `name` reaches them.
- `similar` and `alsoSearched` exist only on a focused reply (empty `[64]`).
- A list reply carries no popular times. The details request (3.7) returns them.
  `PopularTimesParser` takes rating, count, hours, address and the other backfill fields only
  from the entry whose feature id matches, and `mergeDetails` fills blank fields only.
- The status text is `statusRich`, else `status118`, else `openStatus`, and the hours come from
  the same block. `[203]` is the main entity's schedule and `[118]` a department's. Mixing them
  reports a department's closing time against the store's hours.
- Open or closed is parsed from the status text by `SearchParser.parseOpenNow(status, lang)`,
  a prefix match against per-language word tables (replaceable through `statusClosedWords` and
  `statusOpenWords`). Closed words are tested first, because several languages' opening phrases
  start like their open words. The integers beside the text (`statusCodeRich`,
  `statusCodeSimple`) are style markers and are not read: reading them colors closed places
  green.

#### Other locations of a business

A business name focuses one branch. Entries of `alsoSearched` whose normalized name contains
the normalized query (3 or more characters) and that sit more than 30 m from the focused place
join the results (`SearchParser.otherBranches`). They carry no address and the list is partial.
`MapDataSource.searchBranches` fetches the rest:

1. One page-one request for `<query> near me` over a 30 km window (`BRANCH_SPAN_M`) around the
   focused place. Google answers with a list of branches with full cards. The view is not used,
   because the list is capped and its contents change with the window.
2. The plain query again when the first reply had no `[99]` block
   (`SearchResult.strippedFocus`, a new session's first seconds).
3. Rows that pass `SearchParser.isBranch` (the name contains the query, the query contains the
   name, or it is the focus's own name) are merged by `mergeBranches`: the same name within
   50 m is one row, and the copy with an address is kept.
4. Up to 3 rows (`BRANCH_FILL_MAX`) still without an address are looked up by name at their own
   point (`searchOnce`, nearest within 60 m).

This runs with the search when Settings > Search "Find other locations automatically" is on
(`OtherLocationsAuto`, pref `other_locations_auto`, default on, mirrored into
`core/data/OtherLocations.auto`). It costs 1 to 5 Google requests per focused name search. Off,
the reply returns `SearchResult.focus`, the list ends in a "Show other locations" row
(`MapUiState.resultsBranches`), and the tap calls `searchBranches` and frames every branch
(`resultsBranchesLoaded` lifts the hold-view rule).

### 3.3 Hours node

Each entry of `[1][203][0]` is `[name, dow (1 = Mon .. 7 = Sun), [Y,M,D], ranges, flag, flag,
special?]`. The list is the next seven days by date, so holiday hours are already in it.
`ranges` is `[[text, [[openH],[closeH]]], ...]`, with several entries on a split-shift day, and
`special[1]` is a holiday label. `readHours` joins the ranges and appends the label;
`OpeningHours` strips the label before it parses the times. Google's live status string stays
primary, because only it reflects an owner's one-off closure.

### 3.4 Directions response

The keys are `Calibration.DEFAULT_DIRECTIONS_PATHS`; a bundle's `directionsPaths` overrides
them one at a time.

| Key | Path | Field |
| --- | --- | --- |
| `routes` | `root[0][1]` | one node per route |
| `geometries` | `root[0][7][i]` | index-aligned with the routes: `[0]` latitude deltas, `[1]` longitude deltas, E7, first element absolute |
| `summary` | `route[0]` | |
| `distance` | `summary[2][0]` | meters |
| `typical` | `summary[3][0]` | seconds without traffic |
| `traffic` | `summary[10][0][0]` | seconds in traffic, per route; absent without live traffic |
| `typicalLow`, `typicalHigh` | `summary[10][4][0]`, `summary[10][4][1]` | the typical spread, seconds |
| `start`, `end` | `summary[7][3][2]`, `summary[7][3][3]` | `[_, _, lat, lng]` |
| `summaryText` | `summary[1]` | |
| `spans` | `route[3][5][0]` | `[level, startMeters, lengthMeters]`, congested stretches only |

Steps are `<step maneuver='..' meters='..'>` markup strings anywhere under the route node. The
turn side and severity are in the child `<turn side='..' type='..'>`. A leading "Use the ...
lanes to" clause becomes `Maneuver.laneHint`.

`placeManeuvers` puts each maneuver on the polyline at the fraction given by the summed
distance of the steps before it over the sum of all step distances, and pins the last one
(ARRIVE) to the route end. Step distances fall short of the geometry, and an unpinned arrive
lands early and fires the arrival trigger there.

Request side: the mode is `!1e{MODE}` (0 drive, 1 bicycle, 2 walk, 3 transit). Avoid options
apply to driving and sit in the `!6m` feature block: inside its `!2m` submessage `!1b1` avoids
highways and `!2b1` avoids tolls, and `!7b1` avoids ferries as a direct child of the outer
block. `DirectionsPb.withAvoid` places them by pattern and fixes the `m` group counts, so a
recalibrated template still works. A stop is one more `!1m4!3m2!3d<lat>!4d<lng>!6e2` group
before the destination's (`withWaypoints`).

Not reachable without a key or a login. Each was probed and settled:

- A per-departure or time-of-day ETA curve. The response has none, and injected time fields are
  ignored or rejected. The typical spread stands in.
- The `listentitiesreviews` RPC. It answers 404. Its template stays in calibration
  (`reviewsEndpoint`, `reviewsPb`) and nothing sends it.
- Review "helpful" counts, which are zero for logged-out sessions.
- EV charger detail beyond the type marker, the recently-opened badge, and live incidents
  (Google serves binary vector tiles; Waze's feed is reCAPTCHA-gated).
- A full session's review feed from a native request (3.7).

### 3.5 Shared-list import

A `maps.app.goo.gl` link fetched logged out redirects to
`/maps/@/data=!4m3!11m2!2s<listId>!3e3`. That page's HTML embeds a ready-made URL for the
getlist RPC, with the list id and page session token in it. `GoogleMapsDataSource.importList`
lifts the URL by regex and unescapes `&amp;`; no pb is built. Response after the guard
(`EntityListParser`): `root[0][4]` title, `[5]` description, `[3][0]` author, `[8]` items.
Each item: `[2]` display name, `[3]` the owner's note, `[1][4]` address, `[1][5][2..3]`
coordinates, `[1][6]` feature id as a pair of decimal signed int64, whose two's-complement hex,
zero-padded to 16 digits, is the `0x..:0x..` form.

### 3.6 Browser identity

Two user agents, never mixed:

- Requests to Google send `calibration.current().userAgent` with Chrome's header set, built by
  `BrowserHeaders.browserHeaders` (a navigation) or `browserXhrHeaders` (a data call).
  `VelaConfig.USER_AGENT`, `SEC_CH_UA` and `CHROME_FULL_VERSION` are only the compiled
  fallbacks.
- Community services (FOSSGIS OSRM and Valhalla, Nominatim, Photon, Overpass, Transitous) and
  the calibration fetch send `VelaConfig.VELA_UA`, the contactable identifier their usage
  policies ask for.

#### Headers

| Request | `Accept` | `Sec-Fetch-Dest`, `-Mode`, `-Site` | `Referer` | `Downlink`, `RTT` |
| --- | --- | --- | --- | --- |
| Session warm-up | `BrowserHeaders.ACCEPT_DOCUMENT` | `document`, `navigate`, `none` | none | no |
| Data call | `*/*` | `empty`, `cors`, `same-origin` | `https://www.google.com/maps/` | yes |
| Image (`GoogleTransport.imageHeaders`, Street View tiles) | image types | `image`, `no-cors`, `cross-site` | `https://www.google.com/` | no |

All three also send `User-Agent`, `Accept-Language`, `Sec-CH-UA`, `Sec-CH-UA-Mobile: ?0` and
`Sec-CH-UA-Platform: "Windows"`.

- The UA is a desktop Windows Chrome string. Mobile web Maps serves different markup and
  endpoints and deep-links to `intent://`, so a mobile UA means recalibrating every parser.
  It tracks Chrome's current stable on Windows, never the next major
  (`scripts/check-chrome-ua.py` compares it with chromiumdash `fetch_releases`, channel Stable,
  platform Windows).
- `secChUa` is derived from the UA's major by `BrowserHeaders.secChUaFor`, Chromium's rule from
  `user_agent_utils.cc`: the GREASE brand's two characters, its version and the brand order are
  picked by the major modulo the table sizes. `CalibrationStore.parseBundle` uses a pushed
  `secChUa` only when the UA names no Chrome major. Older builds read the pushed value, so the
  bundle carries the derived string too. `BrowserHeadersTest` pins the rule against real Chrome
  120, 124 and 130 headers, and the compiled pair against the rule.
- `BrowserHeaders.sanitize` trims `userAgent`, `secChUa`, `chromeFullVersion` and `rpcContext`
  on parse and rejects a blank value, one over 400 characters, or any character outside 0x20 to
  0x7E. A rejected value falls back to the compiled default; a rejected `rpcContext` sends no
  header. OkHttp throws on a control character at request build, inside a `runCatching`, so one
  stray newline in a bundle would otherwise stop every request with no log.
- `Accept-Language` is `BrowserHeaders.acceptLanguage`, which the app sets from
  `LocaleList.getDefault()` (the list the WebViews read) through `acceptLanguageFor`: each tag,
  then its bare language unless the next tag shares it, q from 0.9 down by 0.1.
- `Downlink` and `RTT` go on data calls only. google.com asks for them in the document's
  `Accept-CH`, so a real session sends them after the document. Over Cronet the values come
  from its network-quality estimator, scaled by a per-host noise factor of 0.9 to 1.1 and
  rounded as Chrome rounds them (`rttHint`: 50 ms steps, capped at 3000; `downlinkHint`: 50
  kbps steps printed in Mbps, capped at 10). Until the estimator has a value, and on OkHttp,
  they are `10` and `50`.
- Data calls send the three low-entropy hints only. Chrome ignores a subresource's `Accept-CH`
  for the high-entropy set.
- Neither client sends `X-Client-Data` or the four `x-browser-*` headers that Chrome adds on
  Google requests. A shared fake `X-Client-Data` would be identical on every install, and
  `x-browser-validation` is a hash that needs Chrome's own key.

#### Transport

`GoogleTransport.hook` on the shared OkHttp client hands every request to a Google host
(`GoogleUsage.isGoogle`: google.com, googleapis.com, gstatic.com, googleusercontent.com,
ggpht.com) to `app/net/CronetTransport`, Chromium's network stack (dial `useCronet`, default
on). Everything else stays on OkHttp. So does a Google request when Cronet is off, cannot be
built, or throws before answering.

OkHttp's handshake identifies OkHttp whatever the user agent says. Measured 2026-09-23 on
tls.peet.ws (JA4, then the HTTP/2 fingerprint):

```
Chromium 152 desktop, Android WebView 153
  t13d1516h2_8daaf6152771_806a8c22fdea   1:65536;2:0;4:6291456;6:262144|15663105|0|m,a,s,p
OkHttp (no GREASE, ECH or ALPS)
  t13d1513h2_8daaf6152771_eca864cca44a   4:16777216|16711681|0|m,p,a,s
```

- `CronetHolder` builds one `ExperimentalCronetEngine` on first use: HTTP/2, QUIC, Brotli, the
  network-quality estimator, a 64 MB disk cache in `cacheDir/cronet`. A navigation goes at
  `REQUEST_PRIORITY_HIGHEST` (`Priority: u=0, i`). The OkHttp call timeout still bounds the
  wait. A client with no call timeout (`callTimeout(0)`) is bounded at 30 s there, so a Google
  reply that needs longer is asked through a client with a set deadline
  (`GoogleMapsDataSource.largeReplyHttp`).
- The library is Chromium's own prebuilt Release build for the Chrome for Android stable
  version pinned in `gradle.properties` `vela.cronetVersion`, the same major the UA claims.
  Bump the two together. Maven's `cronet-embedded` stopped at 143.
- `scripts/build-cronet-aar.sh` packs the jars, the four ABIs' libraries, Chromium's ProGuard
  rules and its LICENSE from `storage.googleapis.com/chromium-cronet/android/<v>/Release/cronet/`
  into `app/libs/cronet-<v>.aar` (gitignored). `cronet-build.yml` publishes it weekly to the
  `cronet-runtime` release; CI fetches the pinned one from there, else packs it. The jars are
  Java 25 class files, which AGP 9.4's R8 reads and AGP 8.10's does not. Protobuf is shaded
  inside them (`org.chromium.net.internal`), so it does not clash with OsmAnd's.
- The all-in-one APK carries Cronet for arm64-v8a and armeabi-v7a only. On x86 and x86_64 the
  engine fails to load once and Google requests stay on OkHttp. The per-chip x86 APKs carry
  their own.
- Cronet advertises `gzip, deflate, br` and strips `zstd` from a caller's `Accept-Encoding`, so
  `zstd` is not sent.
- Coil loads images through the shared client with `GoogleTransport.imageHeaders` in front, so
  photos carry the browser headers and ride Cronet.

#### WebViews

- Every WebView (the five fetchers and the all-reviews page) calls
  `app/web/WebViewIdentity.apply`. It sets the calibrated `userAgent` and, through
  androidx.webkit where the feature exists, user-agent metadata built from `secChUa`: the
  brands, mobile false, platform Windows 15.0.0, x86, 64-bit, form factor Desktop, and the full
  version from `BrowserHeaders.fullVersionFor` (`Calibration.chromeFullVersion` when it is a
  build of the UA's major, else `<major>.0.0.0`; the GREASE brand keeps `<n>.0.0.0`). Without
  the metadata a WebView with an overridden UA still sends `"Android WebView"`, `?1` and
  `"Android"` hints. It also turns off file access, content access and geolocation. A `VelaWeb`
  `identity:` log line records the WebView package and which switches took.
- A WebView adds `X-Requested-With: <package name>` to every request it sends itself, and no
  app setting removes it: Chromium abandoned the header's removal, and the androidx allow-list
  API is disabled on Google's WebView and unsupported on Vanadium. `loadUrl(url, headers)`
  could replace it on the document request only.
- `app/web/WebProxy` removes the header by sending the page's requests over Cronet. Dial
  `webProxy`: compiled default off, on for every install through `calibration.json`
  `tuning.webProxy` = 1.
  - GETs are intercepted in `shouldInterceptRequest` and streamed (`WebStreamProxy`) with the
    WebView's own cookies (`WebViewCookieJar`), so the page keeps its session.
  - POSTs: a document-start script (`WebProxy.SHIM`, dial `webProxyPosts`, default 1) wraps
    XHR, fetch and sendBeacon on google.com, tags the URL with a one-time id and hands the body
    over a JS interface. Text goes as is; a Blob, ArrayBuffer, typed array or `Request` goes as
    base64 (`putB64`). The interface name and the tag parameter are random per process.
  - CORS preflights to a Google host go out over Cronet too. A 204 is answered to the page as
    200, because an intercepted 204 loses its CORS headers.
  - A body the shim cannot read (FormData) leaves from the WebView with the header and is
    logged `untagged POST body:`. Other `VelaWebProxy` lines: `carries:`, `answers locally:`,
    `passes through:`.
  - Any proxy failure returns null and the WebView loads the request itself.
  - Cost on a Pixel 9 place tap: about 0.2 s on the review page's load (2.15 s against 1.92 s).
- Page telemetry (`play.google.com/log`, `gen_204`, `ogads-pa.*`) is answered locally with an
  empty 200 only when Settings > Privacy "Block Google's page telemetry" is on
  (`web/GoogleTelemetry`, pref `block_google_telemetry`, default off). It works with the proxy
  on or off, and the dial `webProxyBlockLogs` overrides it when set. It is off by default
  because a browser that never sends telemetry looks less like one.
- A bridge object is visible to the page's scripts, so bridge names are random per process.
  Scripts are written with `VelaBridge` and `VelaPanel` and pass through `JsNames.of`.

#### Values that differ per install or per request

A value every install sends identically would pick out Vela and nothing else. `RequestShape`
and `BrowserViewport` produce these:

| Value | Rule |
| --- | --- |
| batchexecute `_reqid` | random start per process, plus 100000 per call (`nextReqId`) |
| batchexecute URL and body | localized, a `gl` added, `source-path` encoded as `%2Fmaps`, body ending in `&` (`batchUrl`) |
| autocomplete `ech` | counts up per request (`nextEch`) |
| Street View JSONP callback (the old GET) | `_xdc_._` plus 6 random characters (`callbackName`) |
| map span `!1d` | a long decimal within 0.2% of the asked value (`span`) |
| directions viewport | centered on the trip, spanning it at 1.35x, at least 1.5 km (`fitDirections`) |
| window size | one of `BrowserViewport.CHOICES` (maximized desktop Chrome sizes, weighted toward 1920x945), picked once per install (pref `browser_viewport`) |

`SearchPb.build`, `DirectionsPb.build`, `suggest` and the photo request write the window size.
In search and directions that is `!3m2!1i<w>!2i<h>` and the four page-chrome rectangles
(`!30m28` in search, `!20m28` in directions). A recalibrated template without the captured
1024x768 shapes is left untouched. The health probe runs at 1920x945.

Every fixed wait before a Google request is drawn through `core/util/Jitter`: plus or minus 25%
by default (`DEFAULT_SPREAD`), 50% on retry backoffs. That covers the nav recheck, directions
retries, the slim-pool heal, neighbor prefetch gaps, the photo-load stagger, place retries and
the review retry. OSRM's retry backoff uses it too.

#### Sessions

- There are two cookie jars: the app's in-memory jar, new at every launch, and the WebView's,
  kept on disk. One phone is two sessions from one IP.
- Per-place requests (details, photo pages, the review feed) carry the `AgedSession` tag, and
  `CronetTransport` sends them with the WebView's cookies (`WebViewCookieJar`; dial
  `agedSession`, default 1). Google gives a new session a limited view: five reviews, and
  popular times missing on busy places. Without Cronet the request keeps the app's session.
- `web/SessionRotation` (Settings > Privacy, pref `google_session_rotate`) discards the saved
  session every week (default), every day, or at every process start, and on "Start a new
  session now". A rotation clears the WebView's cookies and site storage, Cronet's disk cache
  when the engine has not opened it yet, and the WebView HTTP cache when the next Google
  WebView is built (`consumeCacheClear`). The button also empties the app's jar
  (`ResettableCookieJar`). Log tag `VelaSession`.
- `web/GoogleStanding` marks the session limited when the first `hspqX` page returns at most 20
  photos (`LIMITED_PHOTO_PAGE_MAX`) with a next page waiting, or when "More reviews" on the
  all-reviews page loads nothing. A first page of 40 or more (`FULL_PHOTO_PAGE_MIN`) clears it.
  50 are asked for; a full session answers 50 and a limited one 10. The mark is stored against
  the session's start stamp and reset by a rotation. A missing popular-times chart is not
  evidence. While marked, a Google place without a chart shows `place_limited_view` where the
  chart would be, and Settings > Privacy shows `settings_google_session_limited`.

#### Request counter

`core/net/GoogleUsage` counts every request to a Google host by purpose, on the phone only.
`GoogleTransport.hook` records each one from the caller's `GoogleUsage.Kind` tag or
`kindOf(url)`. A hidden page load counts at `HiddenWebView.request` or `ReviewsPanel`, and each
request a Google page makes afterward at `WebProxy.intercept`, with the proxy on or off.
`app/diag/GoogleUsageStore` keeps 14 days (`KEEP_DAYS`), Settings > Privacy shows today and the
week, and the diagnostics export carries `googleRequests`. MapLibre's own tile fetches (traffic
raster, the satellite fallback) are not counted. The hidden reviews page costs about 137
requests per load, measured on a Pixel 9.

#### Dials

- Any `tuning` dial can be overridden on one device with
  `adb shell setprop debug.vela.tune.<key> <n>` (`ui/AppTune`).
- `netLog` (Cronet's network log for 90 s and the WebView remote inspector) and `feedDump` (raw
  review-feed replies saved to the app's files) read the property alone (`AppTune.localOn`). A
  bundle cannot turn them on.
- `demoClock <minutes since midnight>` is the screenshot clock (`ui/DemoClock`), property only.
  It pins "now" to that time's next occurrence: status lines are recomputed from the place's
  hours, the arrival clock counts from it, and transit boards and itineraries are fetched for
  it.

### 3.7 Hidden WebView scrapes

#### What a place tap loads

| Part | First source | Then |
| --- | --- | --- |
| Photos | one `hspqX` request for 50 (`MapDataSource.placePhotoPage`, `PHOTO_COUNT`), each photo dated | retried while empty. After the last empty answer the sheet keeps the search's hero photo and "More photos" walks the page |
| More photos | the next `hspqX` page, one request each | one retry, then the full page walk, which also brings the photo categories |
| Reviews | the page scrape, capped at `FIRST_REVIEWS` (10). `requestReviews` arms it and `ensureReviews` starts it when the Reviews tab's area is on screen, never during a drive | up to 2 more scrapes when fewer than min(4, review count) came back |
| Details | nothing when the search reply has popular times, a review count, an address and weekly hours. Else one plain request of the details search (`MapDataSource.placeDetails`, parsed by `PopularTimesParser`) | retried while popular times are missing. The details page only when no try returned popular times or a count, and not during a drive or with the route chooser up |

- A one-request load gets `placeTries()` tries (dial `placeTries`, default 3, range 1 to 5).
  The wait before retry n is `placeRetryMs` (2500) plus `placeRetryStepMs` (1000) per later
  try, jittered. A new Google session answers its first seconds stripped and the repeat in
  full.
- Each details reply is merged as it lands (`mergeDetails`). Only popular times wait on the
  retries.
- Photos and details start a jittered 700 ms after the tap resolves.
- Per-place cache, process lifetime, 80 places each: photos and the feed 6 hours
  (`PHOTOS_CACHE_MS`, `REVIEWS_CACHE_MS`), details 15 minutes (`DETAILS_CACHE_MS`).
- Kill switches in `tuning` (1 = the one-request path, 0 = the page path): `nativePlacePhotos`
  (default 1), `nativeDetails` (default 1), `nativeReviewFeed` (default 0).
- With `nativePlacePhotos` 0 the first batch is a page walk stopped at `FIRST_PHOTOS` (6).
- The full walk also sends one `hspqX` request (`placePhotos`) and joins its dates by image id.
  `photoDatesRpc` 0 stops that request.
- No Google page is warmed. `warmWebViewsWhenQuiet` boots the WebView engine alone, with a
  throwaway view, at a quiet moment after the map settles, so Chromium's start does not land on
  the first place tap. Creating WebViews ahead of a cold search held results at 13 s against
  4 s.
- The ambient neighbor prefetch runs only when Google is the sole places source.
- A place with no category, rating, review count or featured review (`isListing()` false: an
  address or a pin) gets no Reviews tab, no review scrape, no popular-times placeholder and no
  limited-view note.

#### The two RPCs

Both are form-encoded POSTs to
`https://www.google.com/maps/_/MapsWizUi/data/batchexecute?rpcids=<id>&source-path=%2Fmaps&hl=<hl>&gl=<gl>&_reqid=<n>&rt=c`
with body `f.req=[[["<id>","<proto>",null,"generic"]]]&` and the headers `X-Same-Domain: 1`
and `x-maps-diversion-context-bin: CAE=` (`Calibration.rpcContext`). Without the context header
the feed answers empty and the gallery answers zero photos. The reply is `)]}'` plus chunked
rows; the data row is `["wrb.fr","<id>","<payload JSON string>",...]`.

| | `hspqX` (photos) | `qv9Egd` (review feed) |
| --- | --- | --- |
| Template | `photosEndpoint`, `photosProto` | URL compiled, `reviewFeedProto` |
| Request | `{FID}` at `[2][0]`, `{COUNT}` at `[4][2][1]`, cursor at `[4][2][2]`, the window at `[4][1]` | `{FID}`, `{TOKEN}`, 10 per page |
| Payload | `[0]` photos, `[5]` next cursor. `[1]` is not the photo total and is not read | `[1]` next-page token, `[2]` reviews, `[5]` true at the end of the list |
| Entry | URL `[6][0]` (`googleusercontent` only, resized to `=w1024-h768`), date `[21][6][8]` = `[year, month, day, hour]` | under `[i][0]`: author `[1][4][5][0]`, avatar `[1][4][5][1]`, relative time `[1][6]`, stars `[2][0][0]`, text `[2][15][0][0]`, photos `[2][2][k][1][6][0]` |

The photo RPC tags no category, so the Menu tab comes only from the page walk.

The feed stays off. Google limits a new anonymous session to five reviews with no paging. A
full session answers a native request with an empty list and `[6] = [true]`: it requires the
single-use `X-maps-bgkey` BotGuard token that Google's page script mints for each request. The
page scrape on that session sends the token and gets the full list.

#### `HiddenWebView`

Five fetchers run Google's own pages anonymously, because a rendered page is served data a bare
request is not. Each subclasses `app/web/HiddenWebView` and is its URL, its extractor script
and its parser.

| Fetcher (log tag) | Page | Timeout |
| --- | --- | --- |
| `WebReviewsFetcher` (`reviews`) | `/maps?cid=<cid>&hl=<reviewsHl()>&gl=us`, 1200x1000 CSS px | 45 s |
| `WebPhotoFetcher` (`photos`) | `/maps?cid=<cid>&hl=en&gl=us`, 1200x3200 px | 55 s |
| `WebPopularTimesFetcher` (`popular`) | `google.com/`, then `sessionWarmUrl`, then a same-origin `fetch` of the details search URL | 22 s |
| `WebDirectionsFetcher` (`directions`) | `/maps/dir/<o>/<d>/data=!4m2!4m1!3e3?hl=<lang>&gl=us` | 20 s |
| `WebStopDeparturesFetcher` (`stops`) | `/maps?cid=<cid>&hl=en&gl=us` | 20 s |

`cid` is the low half of the feature id as unsigned decimal.

The base class owns:

- The view: JavaScript, DOM storage, `WebViewIdentity`, the proxy shim and the result bridge.
- A request id per page load, so a late poller can complete only its own request. A request
  that is given up on stops its page.
- `session { }`: fetches are serialized, with `onResume` before and `onPause` after. A loaded
  Google page otherwise keeps its compositor and timers running, measured at about 27% of app
  CPU during a map pan. `pauseTimers()` is process-wide and is not used.
- The reap after 120 s idle (`reapIdleMs`), and at once under severe memory pressure.
- Blocking non-http(s) schemes. A fetcher can refuse hosts through `allowNavigation`; the photo
  walk stays on google.com.
- Console errors logged under `VelaWeb`.

Rules for a fetcher:

- Use the `main` `Handler`, not `View.postDelayed`: a headless WebView never attaches.
- Size the view in CSS pixels times density. A raw 1200 physical pixels is about 450 CSS pixels
  on a 2.75x phone, which gets Google's narrow layout.
- In a Kotlin raw string `${'$'}{x}` emits the literal text and the script dies with a syntax
  error.

#### Reviews scrape

- Cards are `.jJc9Ad` with a unique `data-review-id`. They are accumulated across scroll
  windows and de-duped by id, keeping the longest text seen per id, because a card read on the
  tick its More toggle was clicked is still truncated. The selectors (`card`, `id`,
  `moreToggle`, `author`, `text`, `date`) can be replaced through calibration
  `reviewSelectors`.
- The Reviews tab is found by `[role="tab"]` and clicked until `aria-selected`. A selected tab
  whose list is still loading is never clicked again, because that restarts its render.
- Every text test on a tab or button runs through `STRIP_PLACE_NAME_JS` first. Google puts the
  place name in those labels, and a name can contain a review word in some language.
- The script ticks every 250 ms. It ends at the cap, or when cards are on screen and the list
  is idle: 13 ticks at the bottom and 18 without growth once the full list is open, 9 and 9
  before that. "Cards on screen" is tested per tick and never latched, because the overview's
  preview cards render just before the tab click blanks the panel. It stops at 130 ticks
  regardless.
- The star histogram comes from the same page (`onHistogram`, rows parsed by
  `ReviewWords.HISTOGRAM_ROW`).

#### All-reviews page

`ReviewsPanel` (`GoogleReviewsPanel`, used full screen only) shows Google's own reviews pane in
a visible WebView, carved by injected CSS. It opens from the Reviews tab's button; the inline
list is the scrape above.

- `vh` units are 0 in an embedded WebView, so everything is sized in pixels.
- The ancestor chain must be un-clipped and un-transformed, or nothing paints.
- Google's summary block is hidden with opacity first and collapsed with `display:none` only
  after the feed has shown cards for about 5 s. Removing it from layout while the virtualized
  list mounts unmounts every card.
- The feed watchdog keys on relative-date texts, not class names, because Google serves builds
  with rotated classes.
- A review's media buttons are found by `jsaction*="review.openPhoto"`, never the aria-label,
  which Google sometimes writes as a description.
- The sort menu is clicked by index, because several languages label the button with the
  current choice.
- All navigation is blocked once the page has loaded.
- A downward drag from the top edge is forwarded as raw deltas for pull-to-close. Otherwise the
  WebView scrolls itself.
- Ad, analytics and beacon requests are always answered with an empty body (`BLOCKED_HOSTS`,
  `BLOCKED_PATHS`).

#### Language

Both review paths load the page in the app language (`reviewsHl()`; English when the word
tables lack the language), because the page language decides which reviews Google serves.
Reviews are never translated. Every text test uses the per-language tables in `:core`
`ReviewWords` (keys `review`, `more`, `sort`, `star`, `ago`, `write`, `like`, `share`,
`actions`, `all`, `processed`), replaceable through calibration `reviewWords`. Ratings read the
leading number. The more-reviews button must match a review word and a more word, or a bare
match clicks the review composer.

#### Photo walk

The place page's gallery tabs are visited in turn (Menu, Food and drink, Vibe, By owner), each
tab's photos tagged, then "All" is swept for the rest. Tabs come from `role="tab"` only, because
the overview's Menu action link leads off the site. A photo is any element whose `src`, inline
background or computed background is a `googleusercontent` URL, de-duped by image id. Partial
results stream whenever the set grows. Per-photo dates are read from the page's
`APP_INITIALIZATION_STATE`.

#### Details page

`WebPopularTimesFetcher` needs a specific query, name plus address. A bare-name search returns
a 20-result list without `[84]`; name plus address resolves to one focused result that has it.

#### Transit directions

They go through the page because a keyless transit request is answered with a driving reply.
The payload is the longest `)]}'`-guarded string in `window.APP_INITIALIZATION_STATE`. The
script polls for it (up to 12 times, 600 ms apart, wanting more than 5000 characters) because
the page fills it after load, and a small stub sits beside it.

#### Place sheet

- Tabs sit under the action pills and open on Overview (info rows, popular times, highlights, a
  review summary card, About, related places). Selection is keyed by tab name. A pinned copy of
  the tab row shows once the in-flow row scrolls under the sheet top. Over three tabs the row
  scrolls.
- The tab set is decided from the first reply; a rated or reviewed place gets Photos up front.
  Placeholders hold the chart's and the histogram's height while those load.
- Reviews tab: a full-width button to the all-reviews page, then the loaded list, sorted
  (relevance, highest, lowest) and filtered locally.
- Photos tab: a grid filtered by Google's photo categories. With none yet, a food place offers
  a Menu chip (`loadPhotoCategories`, the full walk, once per place).
- Business posts from `paths.updates` land in `Place.updates` with no extra request. Overview
  shows the newest with "Show all N updates"; the Updates tab exists when there are two or
  more.

#### Settings

| Setting | Holder, pref, default | Effect |
| --- | --- | --- |
| Performance > Load all photos and reviews | `FullPlaceLoad`, `place_full_load`, off | every tap runs the full photo walk and scrapes up to 50 reviews at once |
| Places > Load reviews only when I tap | `ReviewsOnTap`, `reviews_on_tap`, off | a Show reviews button replaces the scroll trigger (`reviewsAwaitingTapFor`, `loadReviewsNow`); overrides `FullPlaceLoad` for reviews |
| Places > Load photos only when I tap | `PhotosOnTap`, `photos_on_tap`, off | the photo request waits for a Show photos button (`photosAwaitingTapFor`, `loadPhotosNow`) |
| Places > Wait for popular times | `DetailsRetry`, `details_retry`, on | off makes `placeTries()` 1 |
| Privacy > Live traffic only when I tap | `RouteTrafficOnTap`, `route_traffic_on_tap`, off | clears `RoutingPrefs.googleTraffic` for each new trip, so directions, reroutes and rechecks skip Google and the transit chip is not prefetched. The chooser's Show traffic (`requestRouteTraffic`) sets it for that trip and refetches |
| Navigation > Start drives north-up | `NavNorthUp`, `nav_north_up`, off | sets `navNorthUp` at every drive start. The compass still toggles it per drive, and a tap while the camera is detached also re-centers |
| Navigation > Navigation icon | `PuckStyle.shape`, `puck_shape`, arrow | see below |

The navigation icon is the arrow, a top-down car (`drawCarPuck`, color pref `puck_car_color`:
red, blue, white, green, yellow), a UFO, a pirate ship or a rubber duck (`drawUfoPuck`,
`drawShipPuck`, `drawDuckPuck`). Those are top-down bitmaps for the map symbol, the
notification and the car screen. In the nav follow overlay the four are 3D models
(`ui/map/Puck3D.kt`, `PuckModels`): flat-shaded low-poly meshes drawn every frame,
orthographic, from the heading relative to the camera and the camera's tilt
(`puckOverlayTilt`), back faces culled, faces painted far to near, over a ground shadow. The
arrow stays flat. Measured on a Pixel 4a demo drive: 60 fps, 0.17% janky frames.

The nav Overview button is a toggle. With `inNavOverview` set (by Overview; cleared by
Re-center, a pan and the end of the drive) a press re-centers instead of fitting again. The fit
keeps the chrome clear: in portrait the turn card, the bar, the route bar strip and the button
column; in landscape the whole left column plus 28 dp, with 12% top and bottom.

### 3.8 Recalibration procedure

1. Capture the live request in browser devtools or a proxy. Mask the query and any coordinates
   before saving the capture anywhere.
2. For a moved field, edit the path in `calibration.json` `paths`, `directionsPaths` or
   `suggestPaths`. Each merges key by key over its compiled default (`DEFAULT_PATHS`,
   `DEFAULT_DIRECTIONS_PATHS`, `DEFAULT_SUGGEST_PATHS`).
3. For a moved endpoint or pb, edit the template. The host must stay on the allowlist.
4. For a reshaped response, ship a `transformsJs` bundle (section 11).
5. Raise `version`, run `./scripts/sign-calibration.sh`, and commit `calibration.json` with
   `calibration.json.sig` in the same commit.
6. Bring `Calibration.DEFAULT` in step at the next release. `DEFAULT.version` stays 1 so that
   any remote bundle is newer.

---

## 4. Routing and navigation

### 4.1 Which source answers

`GoogleMapsDataSource.directions` serves every route request from five sources.

| Source | What it is |
| --- | --- |
| Open router | FOSSGIS OSRM, `routing.openstreetmap.de/{routed-car,routed-bike,routed-foot}/route/v1`, `steps=true`, `geometries=polyline6`, `alternatives=3` on a trip without stops |
| Google | Keyless directions (3.4): the line, typical and in-traffic time, congestion spans, alternates. Its own steps are abbreviated on longer trips (a 6-mile route returned 2 of about 10 turns) |
| Matcher | FOSSGIS Valhalla `trace_route` and `trace_attributes` at `valhalla1.openstreetmap.de` (`ValhallaRouter`) |
| Tiles | Street names from the map's z14 vector tiles (`RoadNameTiles`, `LineNamer`) |
| On-device router | `ObfRouteEngine` over downloaded region files (4.5) |

Driving to one destination: the open router and Google are asked at once, three attempts each
with backoff on a planning request. The line driven is Google's, because Google knows closures
and traffic. The steps come from open data. A turn whose street cannot be confirmed goes out
without a name.

| Situation | Line | Steps | `RouteSource` |
| --- | --- | --- | --- |
| Both answer, Google's line leaves the open route | Google's | Open router's on shared road. On each differing stretch: matcher, else tiles, else bare turns (4.2) | `GOOGLE_HYBRID` |
| Both answer, same road throughout | Open router's, with Google's times and spans | Open router's | `OSRM` |
| Google silent | Open router's, no traffic | Open router's | `OSRM` |
| Open router silent | On-device route when a region answers, else Google's | That source's own. Google's are abbreviated | `OBF`, `GOOGLE_ABBREVIATED` |
| No network | On-device route, no traffic | On-device | `OBF` |
| Google off | As "Google silent". No Google request is made | | |
| Reroute during a drive | The same rules under a deadline, one attempt per source, with the on-device route taken early where a region covers the trip (4.6) | | |

Google is off when `NoGoogle.enabled` is set ("Use Vela without Google") or
`RoutingPrefs.googleTraffic` is false ("Live traffic only when I tap", until Show traffic is
tapped).

Other modes:

- Walking: the open foot router. Google's walk is offered only when at least 15% shorter (4.3).
- Cycling: a safety-weighted route by default, from the on-device bicycle profile, else Valhalla
  (4.3).
- A trip with stops: 4.4. Transit: section 8.

The open router is asked for `polyline6` geometry. The default `polyline` is a 1.11 m latitude
grid, which scatters the vertices of a straight road. `TripLog` still writes 1e5 so existing trip
files read back.

### 4.2 The hybrid route, traffic and alternates

`HybridRoute` builds the `GOOGLE_HYBRID` route for DRIVE. Nothing is routed through sampled
points, so the result cannot loop, and it is Google's line exactly.

#### Stretches

`HybridRoute.stretchesFor` returns the parts of Google's line that leave the open route, in
meters along Google's line.

| Constant | Value | Rule |
| --- | --- | --- |
| `STEP_M` | 20 m | Sampling interval on Google's line |
| `OFF_M` | 15 m | A sample farther than this from the open route is off it |
| `JOIN_GAP_M` | 60 m | Off runs closer together than this are one |
| `MIN_RUN_M` | 40 m | A shorter run is drawing noise |
| `TIGHT_M`, `WALK_MAX_M` | 10 m, 600 m | Each end is walked out to where the lines are this close, at most this far |
| `PAD_M` | 90 m | Added at both ends, so the turn off the shared road and the turn back belong to the stretch |
| `EDGE_CLEAR_M`, `EDGE_BEND_DEG` | 60 m, 35 degrees | An edge keeps this clear of a corner this sharp |

Touching stretches merge. An open-router step outside every stretch is carried over only when
vouched: the open route's own path stays within `AGREE_OFF_M` (15 m) of Google's line for the
`AGREE_BACK_M` (80 m) before the step and for up to `AGREE_M` (400 m) of its leg after it,
sampled every 40 m and at the leg's end. A step that fails gets a stretch around it, so no
open-router step is dropped without a stretch covering its place. With no stretches the open
route goes out.

#### Steps for a stretch

The first source that answers:

1. The matcher (`ValhallaRouter.matchWithEdges`): `trace_route` over the slice, `shape_match:
   map_snap`, costing `auto`, 2.5 s (1.2 s on an urgent fetch). A stretch over `MATCH_MAX_M`
   (180 km) skips it; the server refuses 200 km.
2. `LineNamer.name(strict = true)` over the tiles.
3. `LineNamer` with no tile lines: bare turns from the line's bends.

Tiles come second because, measured on 90 routes in six areas, they named 1.4% of named turns
wrong. When the stretches are not done within `HYBRID_WAIT_MS` (5.5 s; `HYBRID_WAIT_URGENT_MS`
1.5 s), the hybrid is rebuilt with bare turns on every stretch. The open router's own route is
never substituted for a stretch.

A match is accepted by `offLine`, which samples both paths every 30 m. A sample over
`MATCH_OFF_M` (22 m) from the other path is a stray. Strays within 90 m of each other join and
are padded 40 m. The match is refused when one stray run exceeds `OFF_RUN_MAX_M` (380 m), the
strays total more than `OFF_TOTAL_SHARE` (8% of the line, 400 m at least), or the lengths differ
by more than `MATCH_LENGTH_SLACK` (6%) plus 30 m, the end slack and the strays. On a stretch that
touches the trip's start or end, the first or last `MATCH_TRIP_END_SLACK_M` (150 m) is not
compared. A stretch touches the end when it runs to within 1 m of the measured length of
Google's line (`HybridRoute.tripEndSlack`), the scale stretches are cut on. Google's stated
distance is not that length and is not used. Accepted strays (`Match.off`) reach the stitch as
untrusted intervals.

Lanes come from the open router, because the matcher has none (`laneDetail`, planning fetches
only, and only when the edges came back). The open router is led along the matched path with a
via in the middle of every step of `LANE_VIA_MIN_STEP_M` (80 m) or more, at most `LANE_VIAS_MAX`
(20), headings pinned, within `LANE_TRY_MS` (1.8 s). When its path equals the matched path
within `LANE_SAME_PATH_M` (8 m), its steps replace the matcher's and its turn names pass
`recheck`. Otherwise, and always on a stretch at a trip end (where only the part past the end
slack is asked for), the matcher's steps stay and borrow lanes one step at a time: the same side
of turn within `LANE_BORROW_AT_M` (20 m), and the open path within `LANE_BORROW_PATH_M` (12 m)
of the matched path 30 and 60 m either side.

#### The name check

`trace_attributes` returns the edges under the matched path: names, length, and a soft flag for
pieces inside a junction (internal edges, turn channels). `ValhallaRouter.checkedRoad` tests
each turn's stated street against them. Ramps, merges and roundabouts are not tested; they name
where they lead. The turn's edge is the one beginning nearest it within `EDGE_AT_M` (12 m), and
edges are read for `LOOK_M` (300 m) past it.

The stated name is kept when all of these hold:

- It begins within `LEAD_MAX_M` (60 m; `SOFT_LEAD_MAX_M` 150 m when everything before it is
  soft) and within the step's first half.
- Only unnamed pieces, soft pieces and stubs up to `STUB_M` (19 m) come before it, with at most
  `NAMED_LEAD_MAX_M` (40 m) of them named.
- It holds for `HOLD_M` (20 m) or half the step.

Otherwise the turn takes the first street held for `RENAME_HOLD_M` (40 m) with only stubs
before it, or no name. A route number stands in for the street name only when the road has no
other. Without edges a turn carries no street name; exits and sign text are kept.

Related corrections:

- Unsaid turns (`withUnsaidTurns`): the matcher treats staying on a numbered route through a
  corner as going straight. Where the matched line turns `UNSAID_BEND_DEG` (60) or more with no
  step within `UNSAID_NEAR_M` (50 m), and the street name `UNSAID_SIDE_M` (45 m) before differs
  from the one after, a turn is inserted and named by the same rule.
- Roundabouts: the enter step takes the exit step's street. The ring's own name is never said.
- `ValhallaRouter.recheck` runs the check on another router's turns. The open route's steps are
  checked against `ValhallaRouter.edges` for its own line, requested when the open router
  answers and waited for `OPEN_NAMES_WAIT_MS` (1.5 s; `OPEN_NAMES_WAIT_URGENT_MS` 0.3 s). This
  covers the steps carried into a hybrid and the plain open route when it goes out. No answer:
  the names stand. A turn not found on the edges keeps its name there and loses it in
  `laneDetail`.
- The same edges say what kind of road each piece is (`Edge.track`, the service's `use`).
  OSRM's car profile drives an unsigned farm or forest track as a slow road; in much of Europe
  it is closed to cars. When the open route drives through `THROUGH_TRACK_MIN_M` (30 m) or more
  of track (`ValhallaRouter.throughTrackM`: track with ordinary road both before and after, so a
  trip that starts or ends on one is left alone) and that route is among those offered, the
  other service's car route replaces it (`ValhallaRouter.driveRoute`, `use_tracks` 0, the same
  avoid options). It must answer within `OFF_TRACK_WAIT_MS` (4 s; 1.5 s on a reroute) and be at
  most 1.3 times as long plus 2 km, or the open route stands. A trip with stops gets the same
  check when the line offered is the open router's own (`offTrackRoute`, the other service
  asked through the same points). A line longer than the matcher's 200 km is not checked.
  With Google on the route follows Google's line and only an alternate can be replaced.

#### Stitch

`HybridRoute.stitch` merges the two step lists onto Google's line.

- An open step is kept when it projects within `SNAP_M` (40 m) of Google's line, lies outside
  every stretch and is vouched. The open router's first and last step are always kept, at 0 and
  at the end of the line.
- A stretch step is placed by projecting it onto Google's line inside a window around where the
  step lengths plus the drift so far put it: `PLACE_REACH_M` (40 m) or 8% of the step just
  driven, `PLACE_REACH_END_M` (150 m) within `TRIP_END_M` (400 m) of the trip's ends. It never
  lands before the previous step.
- A stretch's left, right or sharp turn is dropped where Google's line bends under `FLAT_DEG`
  (20) within `FLAT_REACH_M` (40 m), and a U-turn where the line turns under `UTURN_DEG` (100).
- Inside an untrusted interval no stretch step is used. Each corner of Google's line of
  `UNTRUSTED_BEND_DEG` (50) or more there becomes a bare turn.
- An open step and a stretch step within `MERGE_M` (30 m) are one junction, and the open one is
  kept. Two steps from one source are never merged, except a ramp's two bends named for the same
  road within `SAME_ROAD_M` (150 m).
- Step lengths are re-measured along Google's line. Durations are Google's typical time by
  share of distance.

A result that does not begin with a departure and end with an arrival is discarded, and the
via-snap below applies. The line drawn for a hybrid is `Route.drawPolyline` (4.8).

- `HybridRoute.splice`: a stretch whose matched road shape does not meet its ends is drawn on
  the shape where Google's line runs within 9 m of it, else on Google's line.

#### LineNamer

`LineNamer` (`core/data/naming`) keeps a route's line and derives its steps. It gives no lanes
or sign destinations and ignores Google's step positions.

- Sampling: every 8 m, heading over 16 m either side.
- Turns: peaks of the heading change across 32 m either side, 35 degrees or more, at least 30 m
  apart. Classes: 55 degrees a turn, 135 sharp, 165 a U-turn, less is slight.
- Names: the nearest tile street within 30 m driving or 25 m walking that runs within 35 degrees
  of the line. Runs under 40 m are absorbed.
- A turn is announced when the name changes across it or it bends 70 degrees or more. A name
  change without a turn is a rename folded into the step before.
- Driving phrases follow road class: joining a motorway or trunk is a ramp, leaving one an exit,
  a split under 55 degrees between two a keep.
- Walking ignores the name of a road bridge of tertiary class or higher, marked from
  `transportation` bridge segments within 6 m, since `transportation_name` has no bridge flag.
- Under `MIN_NAMED_SHARE` (60%) of the line named, or `MIN_NAMED_SHARE_WALK` (50%), the result
  is null.
- Strict mode, used for stretches, is never refused for a low named share. It matches within
  `STRICT_MAX_OFF_M` (12 m). A sample is unnamed when the nearest aligned street with another
  name is less than `AMBIGUOUS_M` (12 m) farther from it than the nearest street. That other
  street counts at any distance, past `STRICT_MAX_OFF_M` too. Both are found over every segment
  near the sample before either limit is applied, so the order the tiles list them in does not
  decide whether the other street is seen. A turn names its street only when the line stays on
  it `STRICT_RUN_M` (60 m), a ramp or rename `STRICT_FAR_RUN_M` (100 m). Refs containing
  "historic" are dropped.

The source is `GOOGLE_LINE_NAMED` when it names a whole route. Names come from `RoadNameTiles`:
the z14 tiles the line crosses (48 at most, 96 kept in an LRU), fetched by the app's
`RoadNameTileSource`, a downloaded basemap archive first, else live OpenFreeMap tiles (template
from its TileJSON, cached 6 h).

#### Traffic times and calibration

`applyTraffic` puts Google's times on an open-router route.

- Traffic factor: Google's in-traffic over typical duration, clamped 0.5 to 4.0. The route's
  `durationInTrafficSeconds` is its duration times the factor.
- Calibration: OSRM's free-flow model has no signal timing and runs under Google's typical on
  arterials. Route, leg and step durations scale by Google's typical, times the ratio of the two
  distances, over the open router's duration, clamped 0.5 to 3.0.
- One calibration is computed per response and applied to every open-router route in it. The
  basis is the open top route when it is not `RouteGeometry.divergent` from Google's top, else a
  via-snap. The hybrid is never the basis and takes no calibration: its times are Google's.
- A route that does not follow Google's course (a trip with stops where Google skipped a stop
  or took another way) uses `speedCal`: the ratio of the two average speeds, same clamp.
- On a same-course route Google's typical spread carries over, scaled by distance.

#### Congestion spans

Google's spans are `[level, startMeters, lengthMeters]` on its own line. A same-course route
maps them by fraction. Every other route goes through `RouteGeometry.transferSpans`: each span
is sampled every 25 m and projected onto the other route through a 0.005-degree cell grid
(`SegmentGrid`). Samples within 35 m mark that distance, and runs become spans with an 80 m gap
tolerance and a 40 m minimum. Road Google did not drive stays uncolored.

#### The via-snap

The older way to follow Google's course: `sampleVias` takes 12 interior points of Google's line
and `routeVia` routes OSRM through them (`OSRM_VIA_SNAP`). The code still uses it in four
places: a DRIVE route whose stitch failed, cycling on the general chain, a trip with stops
(4.4), and `nameRoute`. The first three require `RouteGeometry.divergent` (one of five points
sampled on Google's line over 700 m from the open route), and a planning fetch or an avoid.

- Twelve vias, because a via landing on a turn replaces the turn: at 60 vias about 1 in 10
  named turns is lost.
- The public OSRM `/match` caps at 10 coordinates, so it cannot do this job.
- Refused when an interior via snapped over `VIA_SNAP_MAX_M` (40 m), when the route ends over
  `SNAP_REACH_M` (500 m) from the destination, or when it is longer than Google's course times
  `SNAP_LENGTH_SLACK` (1.05) plus `SNAP_LENGTH_SLACK_M` (400 m).
- Refused on a spur (`RouteGeometry.spurAt`): at least `SPUR_MIN_M` (80 m) of route that
  advances under `SPUR_PROGRESS_FRACTION` (45%) of the distance traveled along Google's line.
  The stretch resets on progress of `SPUR_NORMAL_FRACTION` (80%). The first and last
  `SPUR_END_SLACK_M` (300 m) are exempt, and past `SPUR_LOCAL_M` (200 m) from the windowed match
  the whole course is searched. `spurWithTurn` refuses only when a turn or U-turn sits within
  `SPUR_TURN_NEAR_M` (150 m) of the spur, since a loop ramp has the same shape without one.
- It leads only when Google's live ETA is within `SNAP_ETA_MARGIN` (1.2) of the calibrated open
  free-flow best. With an avoid on the margin is skipped.

`nameRoute` names a provisional alternate when it is picked: the via-snap with the same
refusals, else `LineNamer` over the tiles, else Google's abbreviated steps
(`GOOGLE_ABBREVIATED`). The route keeps its own Google time figures through naming.

#### Ordering and alternates

- Google's other routes are offered as provisional alternates (`GOOGLE_PROVISIONAL`), each with
  its own `duration_in_traffic`.
- Open-router routes offered beside a hybrid: none when `RouteGeometry.divergent` holds,
  otherwise all but its top route. With an avoid on: none.
- The list sorts by `durationInTrafficSeconds ?: durationSeconds`, the figure the picker
  displays, with named routes ahead of provisional ones on a tie. Sort key and displayed value
  must stay the same expression, or the "fastest" tag lands on a row that is not first.
- A route whose four sampled points all lie within 150 m of an earlier route is dropped. At
  most `MAX_ROUTES` (4) are returned.

#### Step fixes on every router's output

- `RouteGeometry.foldSameRoadMerges` folds a merge onto the road the driver is already on into
  the step before, which takes its length, road and ref. "Already on" means the road or ref the
  earlier steps entered is the merge's, or the step before pointed toward it and has run more
  than `EXIT_COMPLEX_GAP_M` (500 m). The merge at the end of a ramp is kept. `SameRoadMergeTest`.
- `RouteGeometry.roundaboutMod` words an OSRM roundabout from `RoundaboutGeometry.exitAngleDeg`
  ("straight" within 30 degrees), because OSRM's modifier is not the entry-to-exit turn. With
  no geometry a "straight" is dropped.
- `osrmPhrase` phrases "turn straight" as continue.

`RouteGeometry.rampTurns`, all three routers. A turn whose road has no name or ref, followed
within `RAMP_TURN_MAX_M` (600 m) by a merge onto a named road, is the turn onto that road's
on-ramp. It takes the language's ramp phrase toward the merge's ref or name ("Take the ramp on
the left toward I 80"). Type, arrow and lanes are unchanged.

#### Checking a trip

Every hybrid logs its stretch sources and `steps vs line: N turns, N agree, ...` under
`VelaDirections`. `StepAudit` looks up each left or right where the step lengths put it and
expects the line to bend that way within 40 m, and expects a step within 60 m of every bend of
60 degrees or more. `adb shell setprop log.tag.VelaSteps DEBUG` adds the step list with each
step's source. `log.tag.VelaCapture DEBUG` logs Google's line for a trip. Captured lines live in
`core/src/test/resources/google_lines/`, and `NamingStudyTest.replayCapturedLines` rebuilds and
checks the route from each.

### 4.3 Avoids, walking and cycling

#### Avoids

`RoutingPrefs.avoidTolls`, `avoidHighways` and `avoidFerries` mirror the chooser's sticky
toggles and are seeded in `VelaApp`. Every fetch carries them, including the ones `NavSession`
makes for itself (reroute, recheck, changed stops, `nameRoute`), so a reroute cannot route back
through what the plan avoided. They apply to DRIVE.

- Google honors them through the pb (3.4), and its route is the avoiding one. The avoid counts
  as honored only when the pb template carries the feature block (`DirectionsPb.avoidSupported`).
- The public OSRM rejects `exclude=` for every value. `OSRM_SUPPORTS_EXCLUDE` is false and the
  parameter is never sent. With an avoid on, the open router's routes are not offered as
  alternates.
- Google answers but neither a hybrid nor a via-snap could be built on a divergent course:
  Google's own routes go out as `GOOGLE_ABBREVIATED`.
- Google silent and a region installed: the on-device router is tried for
  `AVOID_ONDEVICE_TIMEOUT_MS` (4 s). The attempt runs on an unstructured scope so the timeout
  can abandon the search.
- The on-device car profile takes `avoid_toll`, `avoid_motorway` and `avoid_ferries` as dynamic
  routing.xml parameters. `avoid_highway` there belongs to the horse-riding profile and does
  nothing for a car.
- Nothing honored the avoid: the normal route is tagged `avoidNotHonored`. The chooser shows the
  note only when every route carries it.

#### Walking

`walkRoutes` handles a walk without stops. Google is asked once, on planning fetches only, and
waited for `WALK_GOOGLE_WAIT_MS` (6 s). Its walk is offered, first, when it is at least
`WALK_GOOGLE_SHORTER` (15%) shorter than the open walk or the open router gave nothing, and
`LineNamer` can name it within `WALK_NAME_WAIT_MS` (8 s). It is never snapped: a foot route
forced through points on Google's line doubles back at each one (20 to 120% longer on Davis and
Sacramento walks). There is no traffic row for walking. Logcat `VelaWalk` gives both lengths.

#### Cycling

`RoutingPrefs.bikeSafe` is on by default. `bikeSafeRoutes` tries the on-device bicycle profile
when a region is installed (`BIKE_ONDEVICE_TIMEOUT_MS` 6 s, `BIKE_ONDEVICE_URGENT_MS` 3 s),
then Valhalla `route` with `costing: bicycle`, `use_roads` 0.1, `use_hills` 0.5 and
`bicycle_type` Hybrid. Valhalla maneuver types map into the OSRM grammar (`osrmGrammar`) and are
phrased by `osrmPhrase`, so voice, banner and step list match every other route. These routes
carry no Google traffic. With the setting off, or when neither answers, `routed-bike` runs
through the driving chain without the hybrid.

### 4.4 Multi-stop

`directions(origin, dest, mode, waypoints)` returns one route for a trip with stops. Neither
router offers alternates for one.

- Google is asked for the trip through the stops. `DirectionsPb.withWaypoints` adds one
  top-level `!1m4!3m2!3d<lat>!4d<lng>!6e2` group per stop between the origin and destination
  groups, with no count to change.
- The open router is routed through the stops with `routeVia`. The arrive and depart at each
  via fold into the step before, so the trip reads as one leg.
- `RouteGeometry.stopsOnLine` decides whether Google honored the stops: each within
  `STOP_ON_LINE_M` (250 m) of a vertex of its line, in trip order. A reply that missed one is
  the direct trip and only calibrates speed (`speedCal`).

| Case | Result |
| --- | --- |
| Google honored the stops, same course | The open route with Google's time and spans |
| Google's course diverges (planning, or an avoid on) | The open router snapped along Google's line leg by leg (`sampleViasThrough`: 12 samples per leg with the real stop between them). Stops are exempt from the via-snap distance refusal (`looseVias`). Kept under the reach, length, spur and ETA-margin rules of 4.2 |
| Avoid on, no usable snap | Google's route through the stops, `GOOGLE_ABBREVIATED` |
| Detour not worth it, or Google missed a stop | The open route with `speedCal` and transferred spans |
| Open router silent | The legs chained on the device (`chainOnDevice`), else Google's route, abbreviated |

During a drive:

- `NavEngine.stopMarks(route, stops)` projects each stop onto the line in order. A stop over
  `STOP_ON_ROUTE_M` (150 m) from it has a null mark.
- `NavSession` holds the stops, the marks and a passed counter, and speaks one cue per stop when
  progress comes within `STOP_ARRIVE_TOL_M` (25 m) of its mark.
- Rechecks fetch with `stops.drop(passedStops)`, so a traffic re-check never pulls a drive off
  a saved route or a camera detour. A reroute after leaving the route drops the silent stops
  and fetches through the real ones only: the driver is taken on from here, not back to the
  stretch just left. For a saved route (`plannedWayName`) that is said once,
  `NavStrings.leftYourRoute`, with a status card naming it (`onLeftPlannedWay`).
- A stop with a null mark counts as passed only when a later stop with a mark is passed
  (`NavEngine.stopsPassed`). A stops edit, or a reroute that could not fit the stops, therefore
  keeps them all. With no later mark, it is reached when the car comes within
  `STOP_NEAR_PIN_M` (250 m) of its pin: the middle of a mall or a park gets no mark, and
  without this such a stop stays ahead for good. The rule is off between a stops edit and the
  route for the new list.
- The cues and the skip test act only while the engine's route is the very object the marks
  were measured on (`planRoute`). `applyEnrichedRoute(base, lit)` therefore replaces
  `_state.route`, `planRoute` and every other reference to `base` together, and does nothing
  unless both still are `base` and no reroute is in flight.
- Progress that jumps more than `STOP_SKIP_JUMP_M` (250 m) past the next stop in one fix, faster
  than `STOP_SKIP_SPEED_MPS` (70 m/s), is a skip (`NavEngine.stopSkipped`). Ground covered over
  a pause or a GPS gap is slower than that and counts as driven. The session holds the stops
  and reroutes through them each fix until a new route lands. That reroute is not put through the back-on-course test, which
  discarded every answer because the car had never left the line. A jump that only passed
  silent stops is not a skip (`onlySilentSkipped`): the driver went another way round the
  stretch a saved route or a camera detour was built through, and those points count as passed.
- `NavSession.setStops` is the one replan entry; `addStop` delegates to it. It ignores the
  reroute cooldown, and the new list is the plan at once, so a failed fetch keeps it.
  `MapViewModel.applyStops` calls it only when the list differs from
  `NavSession.remainingStops()`.

Step sheet: `NavStopsRow` always leads it. With no stops ahead it reads "Edit route" and opens
the stops editor. With stops it also offers "Remove next", which after a `VelaDialog` confirm
calls `applyStops(stops.drop(1))`. Title and buttons share a line only when they fit, measured.
The mid-drive editor's Add stop applies pending edits, closes, and opens the along-route search
(`NavSearchChips`); a pick joins the drive through `addStopDuringNav`. The planning pick
(`beginPickStop`) is not used during a drive, because the search page is never drawn then.

Closing-soon warning (`NavController.maybeWarnClosingSoon`, at nav start): each stop ahead is
checked at its own arrival, then the destination. Only the first place that closes within 60
minutes after arrival, or before it, is spoken. A trip with stops arrives as one leg, so a
stop's arrival is the trip's time scaled by the stop's along-route fraction (`stopArrivals`). A
stop added during the drive is checked on the replanned route, waited for up to 20 s.

### 4.5 Offline routing

`ObfRouteEngine` runs OsmAnd's Java router and binary reader over `.obf` region files. The same
files answer the posted speed limit (`currentRoadLimit`) and supply romanized road names
(`Route.roadNamesLatin`). Profiles are `car`, `bicycle` and `pedestrian`.

Data and selection:

- Region files are baked off-device (`scripts/build-obf-region.sh`, `VelaObfShim`) and hosted as
  release assets with a manifest. The catalog is `tools/routing-regions.json`.
- The router gets every installed region whose box intersects the trip's padded box (a quarter
  of the span each way, 0.27 degrees at least). Both ends must fall inside a candidate's box, or
  the engine returns empty and the caller goes online. `covers` is that test without opening a
  file.
- A box can hold a trip its data does not. A trip is refused when either end has no road within
  `ENDPOINT_SNAP_M` (2 km), tested before the search and on the route found. Without the test
  the router joins the nearest roads it has, tens of kilometers from each end.
- `index.json` is parsed once and kept until the file changes. `covers` and `currentRoadLimit`
  are asked several times a second during a drive.

Memory and highway hierarchy (HH):

- `MEMORY_MB` is 256, because the app already runs near its `largeHeap` ceiling. Past its budget
  the plain search throws. Car profile on a desktop at 256 MB: 57 km in 5.65 s, 151 km fails.
  The threshold depends on the region.
- The bake adds OsmAnd's precomputed shortcuts to each region file (`bake_obf_hh` in
  `scripts/bake-lib.sh`: `hh-routing-prepare`, `hh-routing-shortcuts`, then `BinaryInspector -c`
  combines the section into the file). The manifest row carries `hh: true`. A region the step
  fails on ships without it (`hh: false`).
- `ObfRouteEngine` calls `setDefaultHHRoutingConfig()` for DRIVE and BICYCLE. The router falls
  back to the plain search when a file has no HH, when the trip spans two files, or when the HH
  answer is not correct.
- Measured on a dense 136 MB state file at 256 MB: a 256 km car trip runs out of memory after
  27 s on the plain search and takes 0.5 s in 32 MB with HH. On a Pixel 4a the Delaware file
  routes 148 km in 0.7 s using 71 MB.
- Car sets: default and `avoid_motorway` (`--routing_params=---avoid_motorway`). The router
  picks the set matching the avoids and filters it for tolls and ferries. A file with only the
  default set fails an avoid-highways trip on HH and falls back about a second later. The
  default set is about 3.5% of the file, the second about 2%.
- Bicycle set: `--routing_profile=bicycle`, best effort, about 9% of the file. A region it fails
  on keeps the car sets. Without it a bike route loses 0.1 to 0.6 s before the fallback.
- Walking has no set and stays on the plain search, which failed past about 28 km on that state
  file. Grid cells (`build-cells-region.sh`) get no HH.
- HH asks `OsmandRegions.getRegionsToDownload` which regions hold the trip's ends. Vela ships no
  world-regions index, so its stub returns an empty list, which HH reads as no restriction.

Engine rules:

- `ObfRouteEngine.spokenType` maps a turn OsmAnd marks `skipToSpeak` to CONTINUE, which folds
  into the previous maneuver as a rename. The router emits such a turn where a road only bends.
  A left or right with under `STRAIGHT_TURN_DEG` (20) of measured turn is a CONTINUE too.
  Roundabouts keep their type.
- The departure heading reaches the engine as `RoutingConfiguration.initialDirection`, in
  compass radians.
- A caller that waits a bounded time passes it as `route(maxMs =)`. Past it the engine sets
  `RouteCalculationProgress.isCancelled`, which both OsmAnd planners poll, and returns empty.
  Time spent waiting for the engine's lock counts. This keeps an abandoned search from holding
  the lock against the speed-limit lookup and the next reroute. Planning with no network passes
  no limit. `ObfStopProbeTest` (`-DvelaObf=<dir with delaware.obf>`).

Android runtime requirements, all set before any OsmAnd class loads:

- commons-logging is pinned to `SimpleLog`. R8 strips the implementation and its reflective
  discovery fails on ART.
- `kxml2-vela.jar` is upstream kxml2 without its bundled `org/xmlpull/**`, which duplicates
  platform interfaces and fails R8.
- `PlatformUtil.setOsmandRegions(OsmandRegions(false))` and
  `RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false`. Otherwise `searchRoute` loads a
  world-regions index from the working directory, which is read-only on Android.

### 4.6 The navigation loop

Each fix that reaches `NavSession.onLocation` runs in this order: project it onto the route
(`NavEngine.update`), advance the step, recompute remaining distance and time, act on the events
(speak, vibrate, arrived, reroute), announce a stop just passed, consider a recheck.

#### Fixes

- Guidance takes GPS fixes only, with accuracy of 50 m or better.
- A network fix moves the dot only when GPS has been quiet for `NETWORK_FIX_QUIET_MS` (12 s) and
  it beats the last fix aged (`FixRules.betterThanLast`: the last fix's accuracy radius grows by
  max(5 m/s, speed) per second of age).
- A fix at least twice as accurate as the one showing, itself 50 m or worse
  (`FixRules.isUpgrade`), skips the outlier hold and the low-pass, so the first GPS lock lands at
  once. A GPS fix that follows a GPS fix does so only when it lands within twice the shown fix's
  radius: under poor sky a receiver's accuracy figure halves and doubles from fix to fix, and a
  multipath leap taken as is becomes the position the outlier hold then keeps against the next
  good fixes. A first lock sharpening on a phone with no network location still lands at once.
- Time between fixes comes from `elapsedRealtimeNanos`. `loc.time` mixes GNSS UTC with the
  system clock.
- The provider registration keeps `minDistanceM = 0`. A distance filter delivers nothing at a
  standstill.
- A measured speed more than 8 m/s² × dt + 5 m/s from the last accepted one is rejected once.
  The second such fix in a row is accepted (`gateMeasuredSpeed`).

#### Progress and off route

Progress is the projection of the fix inside the window from 60 m behind to 600 m ahead of the
progress so far, scored by distance plus `ANCHOR_PENALTY` (2 cm per meter of along-route
distance from that progress), so on a road driven out and back the pass being driven wins. It is
accepted within `ON_ROUTE_M` (60 m). Outside that the whole route is searched with the same
penalty, and a jump over `REACQUIRE_JUMP_M` (1,500 m) must repeat for three fixes. Otherwise
progress holds. Off-route distance is the distance of that projection, never the whole route's
nearest point.

`NavEngine.offRouteCorridor(mode, accuracyM)` scales the corridor with the fix's accuracy,
clamped to 3 to 40 m and taken as `DEFAULT_ACC_M` (12 m) when absent. `farOffDistance` is twice
the corridor, capped.

| Mode | Corridor | Clamp | Far cap | Moving floor |
| --- | --- | --- | --- | --- |
| Drive | 18 + 2.0 × accuracy | 24 to 70 m | 110 m | 2.0 m/s |
| Bicycle | 12 + 1.9 × accuracy | 18 to 55 m | 75 m | 1.0 m/s |
| Walk | 8 + 1.8 × accuracy | 15 to 50 m | 60 m | 0.6 m/s |

At 12 m a car gets 42 m and 84 m. `OFF_ROUTE_M` (40 m) and `FAR_OFF_M` (90 m) are only the
`NavEngine.update` defaults that tests and replays use.

- `OFF_ROUTE_HITS` (3) hits request a reroute. A fix inside the corridor on the route's heading
  clears the count.
- A fix outside the corridor is one hit while moving. Below the moving floor it counts only
  beyond the far distance, so drift at a red light does not reroute and a crawl out of a parking
  lot does.
- A moving fix beyond the far distance is two hits.
- A moving fix whose course is more than `HEADING_OFF_DEG` (60) off the route's local bearing is
  a hit even inside the corridor, and two when it is also a quarter-corridor off the line. It
  never counts toward the on-route streak.
- No reroute fires while both the route left and the straight-line distance to the destination
  are under `DEST_ZONE_M` (150 m). The request is held until the car leaves that zone.
- Turn prompts are muted while off route.

#### Guidance

- Prompt distances scale with speed v: far `max(400 m, v × 35 s)`, near `max(150 m, v × 10 s)`,
  each rounded to 50 m, and turn-now `v × 2.5 s` clamped to 25 to 90 m. `spoken` stores band
  slots, so each prompt speaks the true distance.
- The step advances at `v × 2.5 s` clamped to `ADVANCE_MIN_M` (5 m) to 90 m, so at a crawl or a
  standstill the card and the road name stay on the turn in hand until the car is at it. The
  turn-now line is still said 25 m out, once (`TURN_NOW_SLOT` in `spoken`). Both used to happen
  25 m out: a car waiting at a stop line 20 m short of a left turn was shown the turn after it.
- A step's first prompt carries lane guidance. Later prompts speak `NavStrings.repeatShort`. A
  merge skips the far band. Arrival gets one near-band cue.
- CONTINUE and STRAIGHT are silent unless their lanes show a real fork
  (`continueHasGenuineFork`). The DEPART maneuver is spoken once by `NavSession.start` and
  skipped by the engine.
- Maneuvers more than `PASSED_SLACK_M` (75 m) behind are caught up silently.
- Traffic-light guidance (Settings > Navigation, pref `nav_traffic_lights`, off by default,
  English only). With it on, `NavController.lightCues` looks up the signals along each route
  the drive takes up and `RouteGeometry.enrichWithLights` marks them on the plain left and
  right turns they come before (`Maneuver.lightsBeforeM`, meters before the turn): signals
  within `LIGHT_APPROACH_M` (400 m) of the turn and `LIGHT_SNAP_M` (25 m) of the line, one per
  junction (`LIGHT_CLUSTER_M`, 30 m), never the turn's own. The marked copy goes to the session
  through `applyEnrichedRoute`; it has the same line, so the route observer writes no trip
  block and fetches nothing again. Instruction text is not changed. When an approach prompt
  is spoken, `NavEngine.lightLead` counts the lights still at least `LIGHT_AHEAD_MIN_M` (20 m)
  ahead of the car and puts "Pass the traffic light, then" (or "Pass 2 traffic lights, then")
  in front for one or two. A lane prompt, the turn-now line and the arrival cue never take
  it. The signals come from the downloaded road features. Where no region is downloaded the
  public Overpass server is asked for the first route of a drive only. With the switch off
  nothing is looked up. Logcat `VelaDirections` prints how many steps got a light, and how
  many stops have been passed each time one is counted.
- Arrival fires within `ARRIVE_RADIUS_M` (25 m) along the route of the arrive maneuver, within
  `ARRIVE_PROX_M` (40 m) straight-line of it, or with under 50 m of route left while stopped and
  within 60 m straight-line. Those three run on the last step. On any step, a car stopped with
  `END_PARK_ALONG_M` (60 m) or less of route left and within `ARRIVE_PROX_M` of the end has
  arrived: the step does not move on at a standstill, so a car parked short of a last turn
  into a lot never reaches the last step.
- ETA sums the remaining step durations, the current leg pro-rated, times the route's traffic
  ratio, then times `etaScale`. It is never remaining distance over average speed, unless the
  steps carry under 70% of the route's duration.
- Turn card shields (`roadSigns`, `ui/nav/NavOverlays.kt`): the exit tab, the maneuver's own ref
  and every route number in the instruction text, deduplicated by `routeKey` (letters and
  number; spaces, dashes and a trailing direction dropped), three at most. The card's "then" row
  skips a roundabout's own exit step and shows the maneuver after it at the summed distance.

#### Rerouting

```
REROUTE_COOLDOWN_MS         10_000   minimum gap between adopted reroutes
REROUTE_FETCH_TIMEOUT_MS    20_000   an urgent attempt's deadline
REROUTE_LADDER_TIMEOUT_MS   40_000   an escalated attempt's deadline
REROUTE_ESCALATE_AFTER           2   failed urgent attempts before the full ladder
REROUTE_STUCK_GRACE_MS       5_000   past deadline plus this, a running job is abandoned
REROUTE_FINISH_RESERVE_MS    4_000   kept from the deadline for naming and adoption
REROUTE_NAME_SLACK_MS          500   naming gives up this long before the deadline
REROUTE_SPEAK_MIN_MS        30_000   "Rerouting" is spoken at most this often
REACH_TOLERANCE_M              500   an adopted route must end this near the destination
BACK_ON_COURSE_HITS              2   consecutive on-route fixes that discard a reroute
URGENT_OSRM_TIMEOUT_MS       6_000   the one urgent OSRM call
URGENT_GOOGLE_GRACE_MS       2_500   urgent wait for Google once OSRM answered
URGENT_DEFAULT_BUDGET_MS    16_000   an urgent fetch given no budget
PHONE_FIRST_ONLINE_WAIT_MS   2_500   urgent wait for OSRM before the on-device route is taken
PHONE_FIRST_ONDEVICE_WAIT_MS 4_000   how long past that the on-device search may still take
LADDER_OSRM_TRY_MS           8_000   one escalated OSRM try
LADDER_OSRM_SHARE             0.55   share of an escalated budget OSRM may spend
LADDER_SNAP_RESERVE_MS       6_000   an escalated fetch stops waiting for Google this early
```

- `NavSession.rerouteGate` allows one fetch at a time and enforces the cooldown. A job older
  than its deadline plus the grace is abandoned and a new one starts. The fetch runs on an
  unstructured scope: `withTimeoutOrNull` cannot interrupt a blocked socket read or the
  on-device search, and a fetch stuck there would otherwise block every later reroute.
- A failed fetch clears the off-route latch on the location thread, and so does a request the
  cooldown turned away, so the next deviated fixes ask again.
- The first `REROUTE_ESCALATE_AFTER` attempts are urgent: one try per source, a budget of the
  deadline minus the finish reserve, shorter hybrid waits, no lane detail, and no via-snap
  unless an avoid is on. After that the three-try ladder runs under the longer deadline. The
  failure streak resets on any adopted route and on session start and stop.
- Phone first: when `RouteEngine.covers(origin, destination, mode)` is true, an urgent fetch
  starts the on-device route beside the open router and adopts it when the open router has not
  answered within `PHONE_FIRST_ONLINE_WAIT_MS`. The on-device search gets
  `PHONE_FIRST_ONDEVICE_WAIT_MS` more, then the fetch goes back to waiting on the open router. A
  trip with stops chains its legs the same way. The adopted route is offline and trafficless,
  so the recheck heals it. On such a fetch the open router's call runs outside the fetch's scope
  (`abandonableAsync`): a scope returns only when its children have finished, and as a child the
  blocked call would hold the phone's route for the rest of `URGENT_OSRM_TIMEOUT_MS`. The call is
  canceled when the fetch returns or is canceled, and ends at its own timeout.
- When the open router gives nothing, `RerouteFallback.pick` takes Google's route if it is
  already back, otherwise races Google against the on-device router inside the remaining budget.
  Google's routes are tagged `GOOGLE_ABBREVIATED`.
- A reroute pins its departure heading. OSRM gets `bearings=<heading>,65` for the first
  waypoint and an empty entry for every later one; the count must match the coordinates or the
  request is rejected. Planning fetches and stationary fixes send none.
- Google's request carries no heading. On a fetch that has one, a Google route whose first 80 m
  run more than 120 degrees off it (`RouteGeometry.startsAgainst`) is set aside when the open
  router answered, so the hybrid cannot tell a moving car to turn around. `forwardChoice`
  limits what that may cost: a forward Google route within `FORWARD_MAX_EXTRA_S` (180 s) or
  `FORWARD_MAX_EXTRA_SHARE` (8 percent) of the best one, else the open router's route when it
  is that close without traffic, else the best route, turn-around and all. The open router's
  time is first put on Google's clock (its length at the best route's average speed, factor
  held to 0.5 to 3), because its own free-flow time runs fast.
- A reroute that lands after the driver is back on the original route is discarded
  (`route === fromRoute && onRouteStreak >= BACK_ON_COURSE_HITS`). One grazing fix is not enough.
- A provisional route is never driven raw. `NavSession.driveable` names a provisional top
  candidate. If naming fails, a full-stepped route from the same reply is preferred even when
  slower.
- A reroute that could not include the remaining stops is still adopted. The stops stay in the
  plan and the voice says so.

#### Rechecks and faster routes

```
RECHECK_INTERVAL_MS          120_000   each wait drawn +/-25% (Jitter), redrawn per recheck
DEGRADED_RECHECK_INTERVAL_MS  20_000   while the route is degraded, same spread
DEGRADED_FAST_TRIES                6   then back to the normal interval
MIN_RECHECK_DISTANCE_M         1_500   no recheck this near the destination
SAME_COURSE_M                    250   a candidate within this of the current line is the same course
FASTER_THRESHOLD_S                90   minimum saving before a route is offered
MIN_PLAUSIBLE_ETA_FRACTION       0.4   a candidate under this share of the time left is not offered
```

A recheck fetches from the current position through the remaining stops. It does not run off
route, in a replay, with an offer on screen, or with "Live traffic re-checks" off (pref
`nav_live_rechecks`, `NavSession.liveRechecks`). A degraded route is one with abbreviated steps
or no live traffic.

- Same course, with traffic: the shown arrival time is recalibrated. `etaScale` is multiplied by
  candidate ETA over time left, clamped 0.5 to 2.5, applied where the state is published, and
  reset to 1.0 on every route swap.
- Same course, better quality: the candidate replaces the current route silently. Full steps
  replace abbreviated ones and a traffic-carrying route replaces a trafficless one, never the
  reverse.
- Different course: offered when it has traffic and real steps, covers every remaining stop,
  saves more than `FASTER_THRESHOLD_S`, and its ETA is between 0.4 and 0.9 of the time left. A
  trafficless candidate is never offered and never calibrates, because free-flow always appears
  to beat traffic. A dismissed candidate returns only when it saves 60 s more than before.

The offer resolves itself. A bar across the card drains over 10 s, and focus anywhere on the
card freezes it until focus leaves. Then the route is taken, or dismissed when `FasterRouteAuto`
(pref `faster_route_auto`) is off. The countdown is keyed on the offer, so a recomposition
cannot restart it.

#### Tunnels

When no fix has fed guidance for `DR_START_MS` (3.5 s) while navigating, on route and not
replaying, `NavController` synthesizes 1 Hz fixes along the route through
`NavSession.onLocation`. Speed starts at the last shown speed and decays with `DR_DECAY_S`
(60 s). Synthesis never starts or continues below `DR_MIN_SPEED` (1.5 m/s) and stops after
`DR_MAX_M` (3 km). The "searching for GPS" chip stays up, the first real fix re-anchors, and
synthesized fixes are never recorded into a trip.

#### Pause

While `NavSession.paused` is set, `onLocation` records the fix and returns before the engine.
Nothing downstream runs: no off-route detection, reroute, arrival, stop cue, voice, recheck or
offer. The puck keeps moving because it is drawn from the raw fix, and the arrival clock slides
on a 30-second tick. Resume reroutes once when the fix is outside the corridor, otherwise speaks
the current instruction. Auto-resume is armed by the stop: a stationary or off-route fix sets
`autoResumeArmed`, and then `AUTO_RESUME_HITS` (3) consecutive moving, on-route fixes resume.
Without the arming step a pause taken at speed would resume itself three fixes later.

### 4.7 The puck and the camera

During navigation the drawn position is a filtered estimate of meters along the route, not the
raw fix. The per-frame loop (the nav ticker) is in `ui/map/VelaMapView.kt`.

#### Position and speed

- Position is `core/location/AlongRouteFilter`, a 1-D Kalman filter over meters along the route.
  It dead-reckons at the modeled speed and folds in each accepted fix weighted by its reported
  accuracy (along-route sigma 0.66 of the 68 percent radius, since snapping discards the lateral
  part). A discontinuity calls `reseed` and is not averaged.
- Speed is `core/location/SpeedKalman`. Each fix is the measurement. Between fixes the
  accelerometer steers the prediction: `MotionProvider` (`TYPE_LINEAR_ACCELERATION` plus
  `TYPE_ROTATION_VECTOR`, no Google services), projected onto the travel bearing. Its noise is
  cut by the gain `a² / (a² + n²)` with `ACCEL_NOISE` 0.5, which keeps a 4 m/s² brake within 1.5
  percent and cuts 0.3 m/s² of vibration to a quarter.
- Progress advances in the rate domain: the modeled speed plus `error / PUCK_CORRECT_TIME_S`
  (0.6 s), capped at `1.5 x speed + 2 m/s` of catch-up and `0.25 x speed + 0.5 m/s` of
  hold-back. An ease toward a target under a monotonic clamp stalls and lurches at the fix rate.
- Dead reckoning stops `DEAD_RECKON_S` (3 s) after the last accepted fix. A fix's advance is
  accepted up to `speed x dt x 2.5 + 60 m`.
- The smoothing window's width eases toward its speed target with `PUCK_WIN_TAU_S` (2.5 s). On a
  curve the width is a lateral position, so speed noise in it is sideways movement.
- Snap tolerance (`puckSnapTolerance`): driving, 22 m plus a speed term of up to 13 m. Walking
  and cycling, 8 m plus 1.2 times the fix accuracy, capped at 16 m. The heading gate is skipped
  below 1 m/s driving and 2.5 m/s on foot or by bike.

#### Nav camera

- Bearing eases with a time constant from `CAM_BRG_TAU_STILL` (1.6 s) at small error to
  `CAM_BRG_TAU_TURN` (0.35 s) past `CAM_BRG_TURN_DEG` (25 degrees). Geometry noise is a few
  degrees and a turn is tens.
- Zoom runs from 18.5 at a standstill to 15.8 at 30 m/s, on a speed eased over 0.6 s. A pinch
  sets an override that a pan or Re-center clears.
- Tilt is 55 degrees heading-up (0 north-up, or the angle a two-finger tilt set), capped by
  `navTiltCap` at a zoom the user pinched to: the whole tilt from `NAV_TILT_FULL_ZOOM` (15) in,
  flat from `NAV_TILT_FLAT_ZOOM` (12.5) out, linear between. The cap is applied during the
  pinch itself (`onScale`), where the follow ticker stands aside, and by the ticker after it.
  A view tilted 55 degrees at city-wide zoom reaches the horizon and loads the tiles for all
  of it. Pixel 4a, San Francisco, eight zoom sweeps between z16.7 and z10.6 in a drive:
  4,195 frames and 16 stalls over 250 ms (longest 943 ms) uncapped, 5,178 frames and none
  (longest frame 148 ms) capped, 5,554 flat. The camera's own zoom stays at 15.8 or above, so
  only a pinch meets the cap.
- Cosmetic eases take `dtEase`, the frame time capped at `0.065 x replaySpeedup` s. Integration
  keeps the real time. Uncapped, one long frame moves an ease 45 to 70 percent of its error.
- A two-finger move is a pan only after `TWO_FINGER_PAN_DP` (44 dp) of travel with no tilt or
  pinch begun. The tilt detector claims its gesture after 20 dp, after the move detector
  starts, so a move judged at its first event read every tilt as a pan.
- The follow loops (drive and free-drive) stop writing the camera from the second finger down
  (`twoDown`, set by the map's touch listener), not from the pinch being recognized. Until
  then the fingers slid the map and the loop put it back every frame, which felt like the map
  holding on.
- MapLibre starts a pinch zoom only above a span speed. `res/values/map_gestures.xml` lowers
  `maplibre_minimum_scale_speed` from 0.6 dp to 0.15 dp per millisecond. On a Pixel 4a a pinch
  closing at 990 px a second zoomed nothing at 0.6 dp, and one at 400 zooms at 0.15. A level
  tilt drag, a diagonal one with uneven fingers and a two-finger pan still read as tilt and
  pan. `scripts/touch/two-finger.sh` plays these gestures on a phone.
- A pinch whose fingers also turn needs `maplibre_minimum_angled_scale_speed`, lowered from
  0.9 dp to 0.15 dp. A hand's pinch always turns a little: on a Pixel 4a a pinch at 1,000 px a
  second with a 20 degree twist zoomed nothing at 0.9 dp and 0.6 levels at 0.15.
- A pinch and a turn work in one gesture (`isDisableRotateWhenScaling` and
  `isIncreaseScaleThresholdWhenRotating` off). MapLibre's defaults let whichever is recognized
  first shut the other out, and with the lower pinch speeds the pinch nearly always won, so
  the map would not turn. A turn starts after 3 degrees of twist (`TURN_START_DEG`), and after
  15 once a pinch is under way (`TURN_START_PINCHING_DEG`, set in the scale listener). A turn
  of the browse map that rests within `BROWSE_TURN_KEEP_MIN_DEG` (12) of north goes back to
  north, checked at camera idle because the turn's fling runs on after the fingers lift.
  While a drive follows the car a pinch does not turn the map.
- A parked drive slows the loop to `NAV_IDLE_TICK_MS` (120 ms). A moving detached camera keeps
  it at frame rate.

#### The puck overlay

While navigating, the puck is a Compose overlay at `projection.toScreenLocation` of its point,
computed right after the camera move. A GeoJSON source update is tiled asynchronously while
`moveCamera` is synchronous, so a symbol lands on time or a frame late at random. Per-frame
values go into `mutableFloatStateOf` holders read in the draw phase.

- The overlay also draws before the puck engages, at the raw fix, and while the camera is
  detached, projected through the live camera.
- The overlay stays up while a gesture moves a detached camera, re-projected every frame. A
  swap to the map's own flat symbol for the gesture and `PUCK_GESTURE_SETTLE_MS` (180 ms)
  after exists behind the test dial `puckGestureSwap`; it is off because the car vanishes for
  several frames at each end of a pinch on a Pixel 4a (San Francisco fixture).
- The browse map uses the GeoJSON symbol.

#### Free-drive follow

- The target is `FollowEstimator`, fed the raw accepted fix, with half of each residual spread
  over 0.9 s.
- The bearing eases toward the GPS course with a speed-scaled look-ahead
  (`FREE_LOOKAHEAD_TAU_S` 2.5 s). The fly-in to street zoom runs once per follow.
- Below 0.5 m/s, target moves under `FOLLOW_STILL_DEADBAND_M` (2 m) are ignored, so a parked
  car lets the loop go idle.
- A two-finger tilt pauses the follow's camera writes and becomes its tilt target
  (`browseUserTilt`) in place of the automatic 0 or 55 degrees. It survives a pan, a sheet and
  the locate button. The compass, visible while a tilt is held, returns the map to north and
  flat and clears it, as does starting a drive. A tilt ending under `BROWSE_TILT_KEEP_MIN_DEG`
  (8) is a pinch's wobble and is dropped. The idle test compares against the active tilt target.
- A hand rotation releases the follow.

#### Compatibility rendering

Two launches in a row that die before the map's first render set `texture_render` (a TextureView
map). On API 30 and above only `REASON_CRASH_NATIVE` counts: counting any death flipped healthy
phones that had been force-stopped, and the TextureView renderer used 89 percent of a core on a
Pixel 4a. Settings > Performance shows the date it engaged.

#### Diagnosing jitter

Measure first, in this order: the arrow in a screen recording (`scripts/jitter/puck_track.py`),
uneven map motion or rotation (`map_motion.py`, `rotation_fit.py`), over-budget renders in a
Perfetto trace (`gl_frames.py`; evenly spaced means a periodic cause, random is the device's
floor), then Compatibility rendering, which should be off. A demo drive has no GPS noise, so
jitter there is not GPS.

Measured and not worth doing:

- A per-frame LineString source for the route cut: a line re-tiles on every worker. Route
  geometry is never uploaded per frame (4.8).
- A whole-route `line-gradient` cut: 256 texels smear it over `routeLength / 256` meters.
- Smoothing route geometry to calm the arrow: the camera's turn rate through bends was already
  smooth to 0.02 degrees per frame.
- A least-squares bearing fit over the window: no better than the chord.
- Subtract-the-floor accelerometer shrinkage: it taxes a real brake by the floor.

### 4.7a The first seconds of a drive

Frame rates are a Pixel 4a on a demo drive, per second from Start.

- The drive starts with a cut. Its first camera move is a `moveCamera` to the car at the nav
  zoom, tilt 0, and the tilt eases to 55 with `NAV_START_TILT_TAU_S` (3 s). A flight from the
  route overview places a tile set at every zoom it crosses: seconds 2 to 4 ran 6 to 20 fps
  flying and 29 to 59 cutting. A 0.55 s tilt ease has one second at 5 to 9 fps.
- The overview and the way back are cuts too. A fresh Overview press `moveCamera`s to the fit;
  the 4 s refits (`OVERVIEW_REFIT_MS`) animate. Re-attaching from more than `CUT_BACK_ZOOM_GAP`
  (1.5) zoom levels out seeds the camera at the car, flat.
- A step previewed from the overview ends it: the camera goes to the step and stays, and
  resuming the preview returns to the car (`MapViewModel.previewStep`). A pan, a pinch,
  Re-center and a preview all stop the refit within a quarter second.
- A step tapped in the step list drops the list (`StepsSheet` dismisses itself), and that close
  keeps the preview (`closeSteps` after a pick). Any other close of the list ends a preview.
- Each cut lays a veil in the land color of the palette in use (black on AMOLED and over
  satellite imagery) under the puck at `CUT_VEIL_ALPHA` (0.85), faded off over `CUT_FADE_MS`
  (320 ms). It is a `drawRect(alpha)`; a layer alpha renders offscreen.
- The overview hides the `OVERVIEW_HIDE_PREFIXES` layers (places, POIs, minor road names, house
  numbers, one-way arrows, controls, cameras, transit stops, nav callouts, 3D buildings, the
  building overlay) and restores them in its effect's `finally`. Major road names, shields,
  the route and the destination stay.
- Before the puck engages, the loop still eases the opening tilt, waiting out a pre-engage
  re-point flight (`preEngageAnimUntil`, 600 ms).
- `placesNavHold` hides the places icon layers for `NAV_PLACES_HOLD_MS` (7 s) from the start of
  a driving trip, so their sources request no tiles: seconds 4 to 6 ran 16/7/23 fps shown and
  29/45/50 hidden. A style reload mid-hold rebuilds through `applyOpenPlacesHidden`.
- Drive navigation hides `road_one_way_arrow` and `road_one_way_arrow_opposite`.
- The voice speaks at `PiperSynth.SPEAK_PRIORITY` (nice 8, foreground group). At the default
  priority it held the map at 2 to 30 fps; `BACKGROUND` puts a prompt ten seconds out. An
  imminent-turn prompt and the opener (`interrupt = true`) keep the default. No prompt is
  dropped by priority.
- The opener and the first two turns' prompts are synthesized while the route preview is up
  (`NavSession.prepareStart` -> `VoiceGuide.prepare` -> `PiperSynth.prepare`, background
  priority). `NavEngine.startPrompts` gives the far and near approach lines at the 400 m and
  150 m band floors and the turn-now line, in `update`'s wording (`StartPromptsTest`). Up to
  `MAX_PREPARED` (8) lines are kept, by voice, speaker, speed and exact text. A spoken line
  drops any prepare queued behind it (`speaksAsked`) and raises the one in hand (9.1).
- No hidden Google page loads during a drive: the details page not while navigating or with
  the route chooser up (`pageWanted`), the review page not while navigating. A loaded page
  works on for about 20 s after its answer, whether stopped, paused or blanked.
- `route()` warms `roadFeaturesCoverRoute` on the first route, so the road-features file is
  parsed while the chooser is open.
- The one-tap Start from the place sheet (`startNavToSelected`) first waits for
  `MapViewModel.awaitRouteWork` (road features plus the camera job, at most 3 s).
- The building-overlay gate is asked on a timer during a drive: `NAV_OVL_FIRST_MS` (2.5 s)
  after the start, then every `NAV_OVL_TICK_MS` (4 s). It probes once per 550 m cell the car
  enters, only while the camera follows (6.4).

Measured and not worth doing:

- A slower zoom ease for the first 3 s, or deferring the declutter and the first road-label
  pass: the work only moved later.
- Hiding all of Vela's runtime symbol layers for the first 2.5 s: the reveal has its own dip.
- Tile workers at background priority: no gain, street detail later.
- Waiting for the voice lines or for map idle before starting: no gain.

### 4.7b Turns and trip playback

#### Turns

`TurnDeclutter` cuts the map back to street names while the follow camera swings, because symbol
placement re-runs on every frame the camera rotates. On a Pixel 4a Davis demo drive at 3x, turn
seconds ran 40 to 49 fps with all layers and 48 to 60 with it. No one layer group carries the cost.

- While the bearing error is at least `START_DEG` (15) for `ARM_FRAMES` (3) frames in a row,
  every visible symbol layer is hidden except the street names (`highway-name-major`, `-minor`,
  the shields, exit numbers, the `vela-nav-` callouts) and the arrow. They return once the error
  stays under `CALM_DEG` (4) for `SETTLE_MS` (1 s).
- Only layers visible when the hide began are touched. The open places layers are re-filtered
  by `applyOpenPlacesHidden` after a restore. The overview takes the layers back first.
- Each flip re-lays out the layers' sources, a 60 to 200 ms map frame. With the
  start threshold at 10 degrees, a gentle curve, which holds an 8 to 11 degree error, flipped
  the map through every bend.
- Settings > Performance "Simplify the map in turns" (`TurnDeclutterPref`, pref
  `turn_declutter`, default on). Dial `debug.vela.tune.turnDeclutter 0` turns it off, read at
  each drive's start.
- A hand gesture hides nothing: strokes with rests between would flip the set twice per stroke.
- While the camera is off the car the cross-street bubble layers are hidden. The turn and exit
  callouts stay.

#### Camera-idle work

MapLibre reports camera idle after every `moveCamera`, so under a follow ticker the idle listener
fires every frame. Its work (the viewport pass, the building-overlay gate, the warm-up schedule)
runs at most once per `IDLE_WORK_GAP_MS` (1 s) while the camera moves, plus once
`IDLE_WORK_TRAIL_MS` (250 ms) after the last idle event.

- The nav road-name pass does not run below zoom 14.6: its query returns every road name in
  every loaded tile on the main thread.

#### Tile level of detail

MapLibre 13.6.1 (OpenGL artifact). `setTileLodPitchThreshold(30 degrees)`,
`setTileLodMinRadius(1)`, `setTileLodScale(6)`, zoom shift 0, set once at map creation: above
the threshold, tiles away from the view point load at lower zoom. The engine's default of 60
degrees never engages at the drive's 55. Zoom strokes over a tilted drive view in San Francisco
ran 51 fps and about 190 map requests with it off, 55 and about 95 on.

#### Work beside the map

The render thread shares the fast cores with the rest of the app, so background work that never
ends shows as dropped map frames (33 against 57 fps on a 117 km demo drive from Davis at 3x; a
4 km route shows none of it).

- Plate cameras on the shown routes are worked out once per set of routes (`flockOnRoute`).
  Only a set over `CONTROLS_ONSCREEN_CAP` is cut to the view, from the list in hand.
- An on-device route search ends when its caller stops waiting (4.5).
- Starting a drive cancels the chooser's background fetches (`cancelChooserPrefetch`).
  `HiddenWebView.request` stops a page whose request was cancelled or timed out, and logs each
  page asked, answered, given up and destroyed under `VelaWeb`.
- The streamed speed limit is read, not mounted (`data/StreamedSpeedLimit`): the tile under the
  car at the archive's deepest zoom, decoded by `core/util/MvtLines`, nearest tagged line within
  20 m, twelve tiles kept, an empty tile asked again after 60 s. Mounted as an invisible layer,
  every tile the tilted view covered was requested.
- Whether a downloaded region answers the limit is decided by its boundary (`limitsOnPhone`),
  not the engine's box. Dial `debug.vela.tune.streamLimits 1` reads the streamed limits over a
  downloaded region too.
- `offline/ReleaseRedirects`, in the map's HTTP client and the speed-limit reader's, keeps the
  signed address a release file redirects to until a minute before its `se` time, at most 30
  minutes. Anything but a success drops it. Without it each range read is two round trips.

#### Idle and background cost

MapScreen reads the sheet's edge and the puck's screen position through `SheetEdge` and
`PuckScreen`: composition sees threshold booleans and layout reads the position. Also gated: the
speed readout recomposes on a new digit only; a newly closed place updates the places filter,
not the sources; the nav notification re-posts only when what it shows changes (`notifKey`); the
screen-on flag drops while a drive is paused; the compass sensor is registered only on the bare
map in the foreground; the last-known position is written at most once a minute or after 100 m
(`LocationProvider`); the voice's AudioTrack is paused after each prompt; the day and night
theme ticker waits in the background.

#### Measuring

- Frame rate: `debug.vela.fps` and `scripts/map-fps.sh` (section 13). A `VelaFps` line carries
  the second's zoom range and bearing change, so a dip at a turn reads off the log.
- Layer bisect: `debug.vela.hide`, re-applied every 250 ms while set, so layers nav adds at
  Start are hidden too.
- Thread CPU first: `adb shell top -H -b -n 2 -d 5 -p <pid> -o TID,%CPU,CMD -s 2`. Coroutine
  workers on both Default and IO are named `DefaultDispatcher-worker`.
- `debug.vela.tune.demoSpeedup <n>` runs a demo drive at n times real time, as a trip replay
  runs. `debug.vela.tune.camTurnTau <s>` replaces `CAM_BRG_TAU_TURN`.
- Traps: the travel mode is sticky, and a demo started in walking mode skips the driving
  declutter. A first zoom past z17 dips whatever is hidden; that is the next level's tiles.
  Measure a long route and alternate A and B runs.

Measured and not worth doing:

- One synthesis thread (`debug.vela.tune.synthThreads 1`): prompts about 30 percent slower, the
  dips at turns unchanged.
- A slower turn swing (`camTurnTau` 0.8): 35 to 45 fps on the worst dip over three turns, too
  few to call.

### 4.8 Route line rendering

#### The geometry drawn

- A hybrid route (`GOOGLE_HYBRID`) carries `Route.drawPolyline` from `HybridRoute.drawLine`,
  which is OpenStreetMap geometry where it can be: the roads are drawn from OpenStreetMap, and
  Google's line sits a few meters off their center on curves. Where Google's line runs along
  the open route, that route's own line. Inside a stretch where they differ, the matched road
  shape (Valhalla, only when the match has no off-line part). A piece whose ends are over 25 m
  from Google's or whose length is off by a third keeps Google's line.
- MapScreen draws `roundBends(removeZigzags(straightenCircles(line)))` (`core/nav/RouteSmoothing`).
  Guidance keeps the router's line.
- The arrow sits on the drawn line. Its heading, and so the camera's, comes from `puckLine`,
  `straightenJogs` of the drawn line, so neither turns into a median jog and back out.
  `RouteSmoothing.alongOriginal` and `mapAlong` map a distance on the drawn line to the same
  place on `puckLine`. On 39 captured routes the arrow is never more than 1.4 m from the line.
  Riding `puckLine` it was over 3 m off on 29 of them, up to 11.7 m.

The rules only drop or cut router vertices, and never touch a bend that keeps turning one way.

- `straightenCircles`: the median-jog rule at traffic-circle size (`CIRCLE_MAX_SPAN_M` 45,
  `CIRCLE_MAX_JOG_M` 8). A route straight through a small circle follows half the ring.
- `removeZigzags`: drops a vertex whose segments are both under 35 m, that turns 15 degrees or
  more with an opposite turn of 15 or more next to it, within 4 m of its neighbors' line. Up to
  four passes, at most 5.3 m of movement.
- `roundBends`: bends under `ROUND_MAX_TURN_DEG` (45) are cut twice, by at most
  `ROUND_MAX_CUT_M` (8 m) or a quarter of the shorter side. Junction corners stay exact.
- The median-jog rule (the arrow's heading only): a stretch up to 140 m that leaves a straight
  road by 1 to 12 m and rejoins it within 2 m, on the same heading in and out within 3 degrees
  and along the chord within 7, becomes its chord. On the drawn line it put the stripe on the
  median of a divided road (up to 11.3 m off).

`RouteSmoothingTest` bounds them over 39 captured Google lines: no line longer, no length change
over 0.5 percent, no router point more than 12 m from the smoothed line.

#### The moving cut

The line is inserted above all road and bridge geometry and below labels: under the first symbol
layer after the last `bridge_*` layer. The style's first symbol layer, the one-way arrow, sits
under the bridges.

- During a drive the route is a traversed full line, a far tail, a 3 km ahead window and a
  400 m cut piece (`ROUTE_CUT_LAYER`) on top. The split is geometry, with traffic spans
  remapped onto each piece: MapLibre bakes a `line-gradient` into 256 texels and has no line trim.
- Per frame only paint changes: the cut piece's gradient carries the cut at 1.6 m per texel.
  Route geometry is never moved per frame or on a short timer.
- The cut piece slides about every 300 m. A piece or window that already reaches the route's
  end does not slide or re-anchor, or the last `NAV_CUT_SLACK_M` and `NAV_WINDOW_SLACK_M` of
  every drive re-upload per frame.
- With the driven trail off (`RouteTrail`, default off) the ahead line's gradient is transparent
  up to one texel before the cut piece's end, so the piece paints over nothing.
- Every moving piece is double-buffered (`ROUTE_*_B`). A re-anchor uploads into the hidden
  copy, tagged with a generation number (`ROUTE_GEN_PROP`) and drawn at `ROUTE_PENDING_OPACITY`
  (0.004; at 0 MapLibre never tiles the source). One frame after `querySourceFeatures` finds
  the tagged geometry, the copy goes to full opacity and the other is hidden. Re-anchored in
  place, the new gradient painted the old geometry for a frame.
- The far tail is not waited on; it is usually off screen, where tiles never load.
- A copy outside `projection.visibleRegion` (read at most once per frame) swaps at once. The
  query is a blocking round trip to the render thread, 3 to 45 ms on a Pixel 4a.
  `ROUTE_PENDING_MAX_PASSES` (40) is the backstop for a copy on screen whose tiles never report.
- A cut piece under `ROUTE_PENDING_MIN_DP` (4 dp) long on screen swaps at once too. MapLibre's
  tiler drops a line shorter than 0.375 px of its tile, so below about z6 the 400 m piece is in
  no tile, and waiting on it costs all 40 queries at every slide. A few dp under the arrow cannot
  be seen to flash. The window still waits at every zoom: its swap shows the far tail with it,
  which zoomed out is most of the route on screen.
- While the camera is detached, a cut piece off screen is not repainted per frame.
- The six piece layers have no opacity transition (`lineOpacityTransition` 0); the default
  300 ms fade dipped the route on every swap.
- A change of color, trail setting or traffic spans repaints every piece in place
  (`paintReset`) and never re-anchors: paint lands at once and geometry a few frames later.

#### Colors

- A piece takes its base color from whether the route has traffic spans, not whether the piece
  does (`routeGradient(routeHasSpans =)`): free-flow blue when the route has any, the route's
  overall traffic color when it has none. Judged per piece, the 400 m under the car drew amber
  beside a blue line ahead.
- A paused drive draws the ahead line in `ROUTE_PAUSED_COLOR` (`#9C8AD6`), no spans, one color
  end to end. A slate gray vanishes into the dark map's road fill.
- The route preview draws each traffic stretch as its own line (`ROUTE_TRAFFIC_LAYER`, one
  feature per span, colored by level, from the driven fraction on). A gradient texel on a 30 km
  trip is about 120 m, too coarse for a short jam.
- Alternates draw below the selected route, nearly as wide (6.3), as a faded blue fill on
  `ALT_ROUTE_LAYER` inside a darker outline on `ALT_ROUTE_EDGE_LAYER` (`line-gap-width`):
  `#7FA9F0` in `#3566C4` on the light map, `#7C9FE0` in `#0E2247` on AMOLED, `#DCE7FF` in
  `#2F7BF0` on the dark map, where a mid blue is the roads' own family. A plain gray reads as
  one more road on a dense grid.

### 4.9 Road furniture, cameras and alerts

#### Traffic controls

- Signals, stop signs, level crossings and speed humps come from the per-region road-features
  bake, with Overpass as the fallback only where no region exists.
- Same-kind nodes within `CONTROLS_CLUSTER_M` (45 m) merge to their centroid. OSM maps one node
  per approach, and at 30 m a wide four-way drew two lights.
- Icon size runs from 0.98 at z15.5 to 1.95 at z19.
- The browse map fetches them from z16 and draws them from `CONTROLS_BROWSE_SHOW_ZOOM` (z19).
  Navigation draws from z15.4, just under the camera's 15.8 floor.
- A level crossing or hump within 25 m of a light or stop sign is drawn offset down and left
  (`CONTROL_NUDGE_PROP`). Camera badges nudge up and right.
- OSM maps signals more consistently than stop signs (Delaware's bake: 2,331 signals, 2,144
  stops), so a residential area can show lights and no signs.

During a drive only controls on the route are drawn, tested on raw nodes before clustering,
since a cluster's centroid sits in the junction's middle. The corridor fetch is 120 m wide.

- A light, hump or crossing counts within `SIGNAL_ON_ROUTE_M` (12 m) of the route
  (`RouteProjection.signalIsOnRoute`). The cross street's approach, the other carriageway and
  the parallel street are farther. Direction is not tested: a middle node is on two roads.
- A stop sign counts within `STOP_ON_ROUTE_M` (20 m) when its road orientation (0 to 179, from
  the bake) is within 40 degrees of the route's bearing there (`RouteProjection.stopIsOnRoute`,
  `alignedWithRoad`). Direction alone keeps the sign on the parallel street; distance alone
  keeps the one facing the cross street. A null orientation keeps the sign.
- The set is keyed per driven route, so a same-course heal never refetches, and capped at
  `CONTROLS_ROUTE_CAP` (800) nearest the start.
- While a corridor set is loaded the viewport path neither refetches nor runs its zoom-clear
  branch, and the viewport job re-checks ownership after its settle delay, or a box set
  replaces the corridor set 350 ms later.
- Corridor queries run off the main thread through `SegmentIndex` (line simplified to 3 m,
  segments in 0.01-degree cells). A whole-region parse reads the inflated bytes once and parses
  decimals by hand into primitive arrays.

#### Plate cameras

- `FlockCameras` ships a bundled floor plus a hosted update: a gzipped `lat, lon, operator,
  direction` TSV, about 129,000 points in the bundled July 2026 snapshot, loaded off the main
  thread into flat arrays with a 0.1-degree grid. The loader prefers the higher version and
  deletes a download the bundled floor has passed.
- With a route up (chooser or drive) only cameras on a shown route draw (`FlockCameras.along`,
  45 m and facing the road). With no route the layer shows the viewport's.
- Badges cluster at 40 m: below z16 one badge per cluster, from z16 one badge and a facing cone
  per head. The badge carries no count.
- Direction (`CameraFacing`): the camera's facing against the nearest non-degenerate route
  segment's bearing as undirected lines, `min(d, 180 - d) <= 50` degrees. A camera with no
  facing counts. Applied to route counts, the avoid re-rank, the alerts and the route bar.
- "Avoid surveillance cameras" (`FlockRouteAlert`, also the pickers' "Avoid cameras" chip, which
  refetches the routes) re-ranks the alternates on offer. The fewest-camera route leads within
  a detour of at most the lesser of 25 percent of the ETA and 10 minutes. It does not route
  around cameras.
- "Try side streets around cameras" (off by default, under the re-rank, drive mode only):
  `CameraDetour` clusters each route's cameras (join 40 m, nearest first, at most 3) and tries
  the points 150 m left and right of the road at each. A candidate whose count drops within the
  same cap is kept and the next cluster builds on it. Per trip: at most 6 route requests, 4 on
  one route; a cluster within 60 m of one already tried is skipped. `CameraDetour.choose` is
  the one rule for both: fewest cameras, ties to the faster, beating the leader within the cap.
- A kept route leads the list and carries its waypoint plan (`Route.detourPlan`). A drive on it
  holds the detour points as silent stops, which every reroute and recheck routes through and
  nothing speaks or lists. `NavSession.withSilentVias` puts those still ahead back in after a
  mid-drive stops edit.

#### Alerts

- Camera alerts (`CameraAlerts.due`): `LEAD_SECONDS` 12, floored at `MIN_LEAD_M` 150 and capped
  at `MAX_LEAD_M` 600, silent below `MOVING_FLOOR_MPS` 2.0, once per camera per route, never
  for a camera behind. Cameras within 40 m along the route are one alert. A speed camera alert
  is spoken and shown as a heads-up card for 6 s. A plate camera alert is the card, the voice,
  or both, by its two settings.
- Speeding alert (off by default): after 4 s continuously over the limit, re-armed after 8 s
  back under, at most once per 45 s, with the badge's 5 km/h tolerance.

#### Street callouts

Cross-street labels are points Vela places, not line-center labels on the basemap layer.

- The pass runs once per 400 m of progress or when the upcoming turn targets change, never on a
  short timer. A quantum is marked done only once something was placed.
- Per crossing street: where it meets the route window (first proper crossing, else a
  T-junction end within 25 m, 60 m for a next-turn target), then `NAV_XLABEL_OFFSET_M` (35 m)
  up the street, on a ladder of offsets on both sides, the first with clearance. Points carry a
  `tier` for the class split and zoom gates.
- Clearance runs from the route to the bubble's anchor, the tip of its tail. The chip is wider
  than the anchor, so the gap on screen is under `NAV_XLABEL_CLEAR_M` (44 m). Below
  `NAV_XLABEL_MIN_CLEAR_M` (26 m) no callout is drawn.
- A callout covers nothing. A candidate within `NAV_XLABEL_AVOID_M` (30 m) of a drawn light,
  stop sign or camera is skipped (`crossLabelPoint(avoid=)`), and the cross-street layers sit
  below the camera badges, the controls' collision claim and the POI layer, so they yield.
- The street the next turn enters (turn, slight, sharp and roundabout-exit maneuvers that name
  a road or ref) gets a blue callout on `NAV_TURN_LAYER` (`#1A66D9`), `TURN_CALLOUT_AHEAD_M`
  (30 m) into it. It never yields, and steps along the street (`TURN_CALLOUT_STEPS_M`, up to
  60 m more) until no light, stop sign or camera is within 30 m. That street leaves the white
  cross-street callouts.
- A passed callout is let go when its anchor projects within 28 dp of the nav bar's top edge
  (`navBarTopPx`) or off either side, checked every `NAV_XLABEL_TICK_MS` (80 ms), with
  `NAV_XLABEL_DROP_BEHIND_M` (600 m) as the backstop.
- A let-go callout fades on `NAV_ROADLABEL_FADE_LAYER`, a collision-free layer whose constant
  opacity falls to 0 over `NAV_XLABEL_FADE_MS` (1.2 s). It is filled `NAV_XLABEL_HANDOFF_MS`
  (250 ms) before the main layers let go. A data-driven opacity on the main layers would re-run
  their placement every tick.
- The main layers' filter is one `atM` threshold, lifted on re-upload over any callout whose
  street was already let go within 60 m.
- `atM` is measured along the current route line, so a new line starts the threshold over
  (`resetNavLabelsForLine`, at the top of the label effect). It used to be reset only when
  navigation started, and a reroute, a healed route, an added stop or an accepted faster
  route left every white callout of the new line hidden for as far as the old line had been
  driven.

### 4.10 Trips, replay and demo mode

- The trip format is `core/replay/TripLog`: a `META` header, then route blocks and
  `lat,lng,t,bearing,speed` fixes. Every fix also carries its provider and the engine's
  off-route hit count.
- Trips are segmented. The start route and every mid-drive swap is its own `RP`/`RD`/`M` block,
  active from the fix where it appears. Auditing or replaying a multi-block trip against one
  merged route corrupts it.
- `RD` carries the route's provenance flags and source name.
- An `M` line's text may be followed by tab-separated fields: the road the turn enters, its
  ref, and the step's duration. A replay needs the first two for the callout and the shield.
  Remaining time is the sum of the step durations ahead; a file without them has the route's
  time shared out by step length at parse.
- A `T` line after `RD` carries the congestion spans (`level:startMeters:lengthMeters;...`), so
  a replay paints the traffic the drive was shown.
- Every nav decision is a `K` line written through `NavSession.onNote`, never with a
  coordinate. An `eta:` note every 30 s records minutes and kilometers left, the step, the
  traffic ratio, the number of congestion stretches and the route's source.
- `TripScrub` drops unknown line kinds, so a new line kind needs a decision there on whether it
  is safe to share. It keeps or drops an `M` line's extra fields with the line, and drops `T`:
  a shared copy's route is trimmed and the offsets no longer fit.
- Replay is hermetic: `NavSession.replayMode` suppresses live reroute and recheck fetches, and
  recorded swaps play back through `replaySetRoute`.
- The map scales the puck's clocks by `replaySpeedup`: the chosen speed for a trip replay and 1
  for a demo drive, unless `debug.vela.tune.demoSpeedup` is set.
- `NavReplay` runs fixes through the real `NavEngine` and checks cards and voice against the
  maneuver positions, flagging silent turns, early announcements and wrong distances.
  `TripLog.audit(csv)` is the one-call entry. The on-demand harnesses take `-DvelaTrip=<abs.csv>`
  and `-DvelaSeg=<n>`, forwarded to the test JVM in `core/build.gradle.kts`.
- Demo drive (pref `demo_drive`, off by default): `DemoTrace.fromRoute` turns a planned route
  into one `ReplayFix` per second and runs it down the same hermetic path. It is presented as
  real navigation: the replay controls are hidden and End cancels the demo job, whose `finally`
  resumes live GPS. A demo that reaches the end of its route stays on the arrival card, as a
  real drive does, and Done ends it through `stopNav`.
- `LocationProvider.pinned` holds the simulated position (`SimLocation.onChange` sets it in
  `VelaApp`). While it is set, or a replay runs, `updates()` asks the phone for no fixes: it
  emits the pinned point once a second (provider `SIM_PROVIDER`) and mirrors the replay's fixes
  (`REPLAY_PROVIDER`), and `lastKnown()` answers with the pinned point. Every collector follows,
  the car screens included, so a head unit never shows where the phone really is.

- During a recorded drive `MapPerf` is sampled once a second and writes a `K` note for a UI
  stall of `STALL_MS` (250) or more, under `LOW_FPS` (20) map frames during a gesture, or a
  main-thread pass of 100 ms or more, at most one per `MIN_GAP_MS` (2 s). A `camera:` note marks
  the camera leaving or rejoining the car. A summary is written every 10 s.
- Other `K` notes: build and settings, each spoken line's wait and render time, and network,
  thermal, memory, screen and GPS changes. No note holds a coordinate, a name or spoken text.
- A trip replay runs at 1x, 3x (default) or 10x, pauses, and seeks. A seek restarts it and runs
  silently to the chosen moment.

### 4.11 Tap-to-stop during a drive

`MapPoiPrefs.navTapPlaces`, off by default. Its setter also turns places on and remembers
whether it had to, so turning it off restores what was there. While it is on, the drive-nav
places filter widens from fuel only to `NAV_DRIVE_GROUPS`, and a tap on a place does not select
it. The place becomes `navTapCandidate`, which MapScreen renders as `NavStopOffer` above the nav
bar.

- The first tap only offers. The card's button is the only thing that changes the drive, so one
  stray touch at speed re-routes nobody.
- The card prices the stop. One route through the candidate is fetched, bounded at
  `NAV_DETOUR_TIMEOUT_MS` (8 s), and `DetourEstimate.minutesAdded` compares it with the drive's
  live remaining time. The candidate goes first in the waypoint list, where `NavSession.addStop`
  puts it. A difference under `MIN_SHOW_S` (20 s) or over `MAX_PLAUSIBLE_S` (3 h) shows no
  figure. The fetch changes nothing in the session.
- The card dismisses itself after 10 s, or 25 s under `dpadMode`, shown as a countdown ring
  around the close button. The countdown is keyed on `navTapOfferTick`, which every offer
  bumps, since a second tap on the same place leaves the state equal.
- Within `NAV_STOP_MATCH_M` (60 m) of a stop ahead the card adds "Remove stop" beside "Add
  stop", which drops the next occurrence through `applyStops`. Adding the place again stays
  possible.
- The offer is drawn on the map as a red "+" teardrop (`PoiIcons.CANDIDATE_PIN`), by the effect
  that draws numbered stops and the destination flag.

---

## 5. Places

### 5.1 Sources

Settings > Places > "Place icons on the map" (`MapPoiPrefs.placesSource`, pref
`map_places_source`), also in the map's Layers menu.

| Value | Label | Draws | Google while browsing |
| --- | --- | --- | --- |
| `open` | Vela data (default) | Vela's baked archive (5.2) | None |
| `both` | Both | The archive, plus what Google adds | One fan-out (5.4) once panning stops |
| `google` | Google | Google's ambient places | A fan-out per pan or zoom |

- The fleet default is `calibration.json` `defaultPlacesSource`, pushed into
  `MapPoiPrefs.setRemoteDefault` at init and after each refresh. An explicit pick always wins.
- Where no archive covers the view, every value behaves as `google`.
- "Show places" is the master switch for the ambient layer, the open layer and the basemap POI
  tiers (`refreshPlacesOverlays`; `onPoiPrefsChanged` applies it at once).
- "Parks, schools and civic places" off drops the `park`, `edu` and `civic` groups from the
  ambient pool and the open layer.
- `MapPoiPrefs.lookupTappedPlaces` (default on): whether a tapped open place is looked up on
  Google.

#### Both mode

Google wins a twin: its point is the storefront and its rank comes from review counts.
`hideOpenTwins` is debounced (400 ms and 2 s after an upload, camera still for
`TWIN_PASS_STILL_MS`, 700); a pass costs 80 to 190 ms in Manhattan on a Pixel 4a.

- It queries rendered icons only. A twin has the same normalized name within
  `DEDUPE_SAME_NAME_M` (150 m), or passes `PlaceNames.sameBusiness` within `DEDUPE_NAME_M`
  (80 m) or `PlaceNames.sameFuelLot`.
- Twins leave the open icon and dot layers. A hidden id whose Google partner is gone is
  released.
- An open icon matching a permanently closed Google listing within 80 m, with no open listing
  of that name within 150 m, joins the persisted closed set (`CLOSED_OPEN_PLACES_CAP`, 2,000).
- Offline nothing is hidden.

#### Basemap POI tiers

`poi_r1`, `poi_r7` and `poi_r20` hide when "Show places" is off, in a route preview, over an
archive whose `rev` is at least `tuning.placesOneSetRev` (20260923; it carries OSM's landmarks
itself), and, outside navigation, when the ambient pool covers the view or several search
results are up. Over an older archive they stay, or its parks would vanish, and their
vegetation exclusion (wood, forest, tree, grass, wetland) must not cover park or garden. In
drive navigation they show fuel only.

Over an older archive `osmFillIn` drops OSM points whose name matches a drawn open icon within
80 m (`OSM_AREA_TWIN_M`, 1,500 m, for area classes). It is viewport-only, rendered-only and
grow-only (`OSM_EXCLUDE_MAX` 1,500 names; each `setFilter` re-lays the source), and throttled to
once per 2.5 s after a fifth of a screen or 0.4 zoom of movement. A rendered query costs 37 to
55 ms in a dense view. Never build a `Regex` inside a per-feature loop.

### 5.2 The places bake

`tools/build-places-region.sh <id> S W N E out.pmtiles [release] [local.parquet]` runs DuckDB
over Overture Places (public S3 parquet or a local extract) and writes PMTiles (tippecanoe
`-Z11 -z17`).

#### Sources

- Overture: businesses only; its park, school, campus, housing and transit rows are dropped.
  Categories are the `categories.primary` set, and a release with `taxonomy` is mapped back
  through `tools/overture-taxonomy-map.csv`.
- AllThePlaces: the newest weekly run (`data.alltheplaces.xyz/runs/latest.json` unless `ATP_RUN`
  pins one; the last pinned id is the fallback). Rows come from the world PMTiles
  (`pmtiles extract --bbox`, tippecanoe-decode, jq), filtered and mapped by `isbiz` and
  `osmcat`. Confidence 0.85. Chains carry OSM-syntax `hours`.
- OSM business nodes: the Geofabrik extract through `osmium tags-filter` and geojsonseq (strip
  the 0x1e separator before jq), same macros. Confidence 0.8; the id is the node id. Ways and
  relations stay out: a building centroid is the same guess as a parcel point.
- A row is added only where no row of the same brand or the same two leading name words sits
  within about 150 m. A dropped duplicate still gives its hours, phone and website.

#### Name keys and folds

- `snapkey` mirrors `PlaceNames.normalized` and keeps letters of every script (`[^\p{L}\p{N}]`
  is the separator, like `PlaceNames.PUNCT`); a Latin-only key made every name rule a no-op in
  non-Latin regions.
- The core key is the snap key minus the words in `tools/place-generic-words.txt`
  (`PlaceNames.GENERIC`, pinned equal by `PlaceNamesTest`). A street number or one word under
  five letters is not a core.
- Rows with the same snap key within about 60 m collapse onto one leader. Fuel rows also key
  by house number (`fuel@<number>`).
- Rows with the same core key within 60 m fold too, when every word of one snap key is in the
  other. A shared core alone folded the Empire State Building into "Empire Beauty School".
- Leader: the row standing for a landmark, then not a kiosk, confidence, more fields.
- The OVERLAP family (5.5) stays app-side: it needs the kinds and the pool's shared words.

#### Positions

Preference: OSM, the AllThePlaces locator, Overture's parcel point. Tenants never move.

- `osm_snap` moves a row onto an OSM node agreeing on the whole or the core name within about
  150 m (120 m for a chain). Mutual best match only.
- An Overture row moves onto a same-name locator point 30 to 120 m away. Under 30 m the two
  agree (median 7.4 m on Davis chains).
- Overture stacks a building's tenants on one parcel point (17% of Davis rows). A tenant whose
  address names a unit snaps to the Overture address point with that house number and unit
  within about 200 m.
- The rest of a stack spreads on a golden-angle ring of 10 to 20 m (8 m plus 2 m per row, capped
  at six), best row in place.

#### Landmarks

OSM landmarks, points and polygons, are baked in: tourism museum/attraction/gallery/zoo/theme
park/aquarium/viewpoint; amenity place_of_worship/school/college/university/library/hospital/
townhall/community_centre/theatre/arts_centre/courthouse/police/fire_station; leisure park/
stadium/sports_centre/water_park/garden/nature_reserve; historic monument/memorial/castle/
ruins/archaeological_site. A second osmium pass (`wr/wikidata`, then
`wr/building wr/man_made=tower`; osmium cannot AND two tags) adds named buildings with a
Wikidata link as `landmark_and_historical_building`.

- A landmark that matches existing rows by snap key is not added; one row stands for it
  (`markbest`: its own category, else a landmark category, confidence, distance).
- Notability orders `lrank` and `xrank`: outline size (log10 of the area in m² minus 2, 0 to
  3), Wikidata (1.5), fame (0.6 x log2(1 + name languages), capped at 3).
- Wikidata credit needs a name in three or more languages when the object is only a building
  (`markcat0` null).
- `landmark` is 1 for anchor categories (hospital, university, stadium, mall, zoo, museum,
  airport) and for a park, attraction, historic, civic or religious place with Wikidata credit,
  an outline of a hectare or more, or fame of 1.5. A landmark is never a tenant.

#### Tenants and kiosks

- Flagged `tenant`: a department of an anchor within about 200 m (address, brand or name-head
  match), a kiosk category or name, or an anchor brand's fuel station or convenience shop
  within about 275 m.
- A department or forecourt loses 2 prominence points. A tenant bakes at minzoom 17, except
  fuel, which stays visible for driving.
- The demotion is a semi-join (`EXISTS`), not a LEFT JOIN: a tenant matching two anchors is
  otherwise emitted twice.
- A forecourt row named exactly like its anchor gets " Fuel", " Charging" or " Market"
  appended, or store and pumps draw under one label. No other name is rewritten.

#### Closed places

Overture's confidence is not a sign of life, so three signals delete rows before scoring:

- Foursquare, by exact id (`FSQ_SQL`): for a row whose first Overture source is Foursquare,
  `sources[1].record_id` is Foursquare's id. Rows with a `date_closed` are dropped, unless a
  live second source lists them (an OSM business of the same name key within about 150 m, or
  a chain locator match). The newest `foursquare/fsq-os-places` release is read when
  `FSQ_HF_TOKEN` is set (a repository secret in `places-overlays.yml`), else a public mirror
  of the 2025-02-06 release (`FSQ_BASE`). `FSQ_CLOSED=off` skips it.
- OSM lifecycle tags (`GONE_SQL`): named nodes and ways tagged `disused:` or `was:` + `shop` /
  `amenity` / `tourism` / `leisure`, or `abandoned:` or `closed:` + the first three, with no
  live tag. A non-OSM row with the same name key within about 80 m is dropped, unless a live
  OSM business of that name is there.
- Wikidata: an OSM object whose item has a dissolved or demolished date (P576) in the past is
  removed and joins the closed list. P3999 is set on venues that reopened and is not used.
  `WD_CLOSED=off` skips it.

Overture's `update_time` (its import date) and a chain's own locator (incomplete per brand) are
not used. Foursquare OS Places is Apache 2.0, notice in
`tools/licenses/FSQ-OS-PLACES-NOTICE.txt`. No Foursquare record is published in an archive.

#### Prominence

A category prior, plus 1.6 for a brand, 0.5 for a website, 0.4 for a phone, 0.2 for an address,
plus `(confidence - 0.5) * 1.6`, plus `srcbonus`, plus the landmark's outline size.

| Prior | Categories |
| --- | --- |
| 4.5 | Anchors: hospitals, universities, supermarkets, museums, malls, airports |
| 3.2 | Hotels, pharmacies, banks, attraction-type landmarks |
| 2.6 | Food |
| 2.2 | Everyday services, parks, schools, places of worship |
| 1.6 | No category |
| 1.0 | Otherwise |
| 0.5 | Offices |

- `srcbonus`: 0.6 for an OSM pair, 0.6 for a chain-locator match, 0.8 for a Wikidata link on
  the paired OSM node, 2.0 for a landmark with Wikidata credit.
- An Overture row with confidence under 0.75 that no second source lists (no `srcbonus`, no
  outline) is capped at 3.0 and is never a landmark: closed and miscategorized places sit there.

#### Ranks and minzoom

Cell ranks: `frank` (about 100 m), `rank` (400 m), `crank` (1.6 km), `xrank` (6.5 km). `lrank`
is the landmarks' own budget per 1.6 km cell and is not written to tiles.

| First match | Minzoom |
| --- | --- |
| Tenant other than fuel | 17 |
| Landmark, `xrank` 1 | 11 |
| Landmark, `xrank` <= 3 | 12 |
| Landmark, `lrank` <= 4 | 14 |
| Landmark, `lrank` <= 10 | 15 |
| `crank` 1 and prominence >= 6 | 13 |
| `crank` <= 2, or prominence >= 5 and `crank` <= 6 | 14 |
| `rank` <= 3, or prominence >= 4.5 and `rank` <= 8 | 15 |
| `rank` <= 12, or prominence >= 3.5 and `rank` <= 24 | 16 |
| Else | 17 |

The cell budgets are caps. When prominence bypassed them, a z16 tile in central Tokyo carried
963 places (Davis: 86).

#### Tile properties

`id`, `name`, `name_en`, `class`, `group`, `icon`, `prominence`, `confidence`, `brand`, `addr`,
`loc`, `website`, `phone`, `hours`, `src`, `origin`, `landmark`, `tenant`, `mark`, and the
ranks `frank`, `rank`, `crank`, `xrank`.

- `src` stays `overture` for every row because the tap gate keys on it. `origin` (`overture`,
  `atp`, `osm`) tells the datasets apart.
- `mark` (0 or 1, always written): an OSM landmark or building, or the row standing for one.
- `name_en`: a non-Latin place's English name, from the OSM row's tags, its name pair, or the
  chain dictionary `endict` (side table `names_en`). Shown under a Latin-script UI and used by
  the twin test.

#### Script and workflow rules

- Every row-to-row rule is a hash join; a correlated subquery or an OR of tests goes quadratic
  over a state.
- Every S3 read prunes on `bbox`, never on the geometry: on Kentucky the geometry filter was
  504 s of a 570 s bake, `bbox` under 4 s.
- DuckDB runs with `-bail` and `.timer on`, and the bake step sets pipefail (its output goes
  through `tee`), so a crashed bake fails its job.
- The main heredoc is unquoted: a backtick in a SQL comment executes as a command.
- DuckDB is pinned (1.5.4), and the toolchain cache key names the pinned versions. DuckDB 1.5.6
  crashes (`INTERNAL Error: Failed to cast expression to type`) on `row_number() OVER (...)`
  filtered to the first row over a join with a distance filter; those statements use `arg_min`,
  `first(x ORDER BY ...)` or `DISTINCT ON`.
- A seventh of the catalog rebakes nightly, so an OSM edit reaches the map within a week;
  `only=<region>` ships one region. A full nightly rebake would offer every download a fresh
  copy daily.
- `scripts/archive-churn.py` hashes two archives' tiles and builds a `zstd --patch-from` delta;
  `places-churn.yml` bakes a region against OSM extracts N days apart. Kentucky over seven days:
  1.3% of tiles, 3.2% of bytes, a 4.4 MB delta against 183 MB.

### 5.3 What draws, by zoom

Density is decided by rank, not by collision. The named numbers are calibration dials.

| From zoom | Icon and label when |
| --- | --- |
| 11 | In the tile |
| 13 | `crank` <= 2, or prominence >= 6.0 |
| 15 | `rank` <= 1, or prominence >= 5.0 |
| 16 | `rank` <= `openRankZ16` (3), or prominence >= `openPromZ16` (5.5) |
| 17 | `rank` <= `openRankZ17` (8), or prominence >= `openPromZ17` (5.0) |
| 17.5 | `frank` <= `openIconCapNear` (8), or prominence >= 6.0 |
| 18.5 | `frank` <= `openIconCapClose` (16), or prominence >= 5.0 |
| 19.5 | `frank` <= `openIconCapMax` (40), or prominence >= 4.5 |

- From z13 to z17.5 a landmark always passes.
- A tile with no `frank` falls back to `rank` <= 4 times the cap.
- A tenant other than fuel gets no icon from z17.5 to z18.5, so the store owns the block.
- Default-group and health-group places need the top `openGenericBlockTop` (3) of their block or
  `openGenericMinProminence` (4.0), else they stay dots until `GENERIC_ICON_ZOOM` (20.3).
- In a packed area such a place past `rank` `openGenericHideRank` (`GENERIC_HIDE_RANK`, 120)
  draws no dot either, from z17 to `GENERIC_REVEAL_ZOOM` (19.5). The rule applies only where
  `mark` exists and is 0 and `landmark` is 0, so it never hides a named building.
- Labels use the icon steps. From z17.5 only `rank` <= `openLabelCap` (20), prominence >= 3.0
  or a landmark gets a name: a label is glyph layout plus a collision pass over four anchors.
- Everything below the cut draws as a category-colored dot. Dots thin by opacity steps, because
  a filter cannot read zoom: none below z15, `rank` <= 6 at z15, <= 15 at z16, all from z17.
- Icon overlap is allowed from z18 on the open layer and z17 on the ambient layer. Below those,
  Google's extras lose collision to the open icons.
- Route preview: the basemap tiers, the open layer, transit icons, stop badges and saved pins
  all hide. The destination draws as its own pin with its name; the flag pin marks only a trip
  ending at "your location".
- Drive navigation: the open layer is fuel only, with no dots, hidden for the first
  `NAV_PLACES_HOLD_MS` (7 s). With "Tap places while driving" on it shows `fuel` and `food` with
  `frank` <= `NAV_DRIVE_BLOCK_TOP` (2).
- The ambient GeoJSON source is maxzoom 18. Past a source's maxzoom every overscaled tile lays
  out all its parent's features: maxzoom 12 gave 22 fps where 18 gives 43 (Manhattan, Pixel 4a).

### 5.4 Google ambient ranking

`ambientProminence = ln(reviews + 1) * (0.6 + rating / 10) + (categoryPrior - NEUTRAL_PRIOR) *
PRIOR_WEIGHT`, with `NEUTRAL_PRIOR` 2.2 and `PRIOR_WEIGHT` 0.9. Review count dominates, the
prior makes a supermarket outrank the sushi counter inside it, and a place with no category
sinks.

- The on-screen set is capped by zoom, `ambientCapMin` (45) at z14 to `ambientCapMax` (140) at
  z17.5.
- The collision sort key is `(10 - prominence) * 1000 + i`, never the list index: the streamed
  pool re-ranks as terms land, and an index key reshuffles placement on every partial upload.
- Sticky ranking (`AmbientStability`): a settled view is painted several times with different
  review counts. `remember` freezes each place's prominence at its first rich paint; an all-zero
  pool is the slim flavor and is not remembered. `prominenceOf` feeds the view filter, the cap,
  the collision order and the icon sizes. `reset()` runs on the re-query gate.
- Slim flavor: a fresh session's first seconds return rating without review count.
  `nearbyPlaces` detects it (at least 3 rated, a majority without counts) and refetches once
  about 1.2 s later, healed places first so `distinctBy` keeps the rich copy. Before diagnosing
  a flat ambient layer, check whether the pool's counts are null.
- Fan-out: `nearbyPlaces` fires 15 category requests (8 on the lean path), each parsed whole
  into a JSON tree of roughly 30 MB in a dense area. A `Semaphore(4)` (`ambientFanoutPermits`,
  read at construction) bounds them; unbounded, a fresh launch held about 400 MB of parse trees
  (Pixel 9). Do not unbound it.
- Partial paints escalate their batch (25 places, then 50 more, then 80 more once 100 are
  painted), because each re-runs whole-layer placement. A partial never shrinks the painted set.
- Offline or with Google off (`googleOff()`), `maybeLoadAmbientPois` returns after the cache
  repaint, so a visited area keeps its dots. On a flaky link each request would hang to the
  call timeout.
- An empty `nearbyPlaces` result is never cached: each term swallows its network error into an
  empty list. On null or empty the painted set stays, the freshest covering entry is served
  whatever its age, and `lastAmbientCenter` stays unset so the next settle retries.
- `ambient_cache.json` holds 32 areas of 200 slim places (14-day TTL, write-through from
  non-empty results, blank areas dropped at load). Entries carry their fetch span and a hit is
  within `span * 0.45`. The cache repaint is unconditional.
- A fetch under `AMBIENT_FRESH_MS` (3 min) old that still covers the view is served as is:
  Google's ranking jitters between identical requests, and the tap-frame camera shift trips the
  moved gate.

### 5.5 Tap resolution

A tap queries a box 24 dp around the finger. Each class picks the feature nearest the tap in
screen pixels, never render-stack order. First match wins:

1. the parking pin;
2. a search-result pin;
3. a saved pin;
4. a grayed alternate route line;
5. a business or a canonical GTFS stop icon, competing by distance. A class priority let a
   few-pixel dot steal a tap on an icon;
6. a house-number label;
7. an unnamed POI icon, reverse-geocoded at the tap;
8. a custom map's or drawing's line or area (the line wins);
9. a building footprint, reverse-geocoded at the tap;
10. nothing. Only a long press drops a raw coordinate pin.

What is drawn under the finger wins: before step 5 the tap asks what is rendered at the
finger's own pixel on the business icon layers, and when something is, only those compete. A
pin is anchored at its tip, about 40 px below its blob, so distance to the point favors a
tenant's dot. With no icon under the finger the box rules run. During navigation a tap opens
nothing.

A house-number tap keeps the tapped number. The reverse geocode (Nominatim) supplies street and
city, and a regex replaces its house number, which is often the neighbor's. The tile's road
name vetoes a mismatched geocode street unless the geocode's house number is exact.

#### Resolving a named place

`MapViewModel.onPoiTap` searches the tapped name at the tapped point. A non-transit tap never
adopts a listing whose category is transit or map furniture (`JUNCTION_CATEGORIES`). The pool
is the first non-empty of:

1. listings within the 1.5 km cap that are the same business (`PlaceNames.sameBusiness`, the
   listing's town and `localGeneric` as generic words);
2. the cross-script second search (below);
3. listings of the tapped kind within `NO_NAME_MATCH_M` (60 m);
4. `kindBesideAnchor`: one extra search for the tapped kind around the nearest name-agreeing
   listing on the lot, nearest same-kind hit within 60 m of that anchor;
5. anything within 60 m.

Then:

- The house number gates it. When the tapped row and a candidate both have one and they
  differ, the candidate is out of every fallback and out of name matches beyond `SAME_LOT_M`
  (120 m). On the lot an agreeing number wins. A missing number decides nothing.
- A live listing beats a permanently closed one.
- On the lot, listings whose name is the tapped name (`PlaceNames.same`) beat the loose pool,
  and among them those of the tapped kind: a brand's store, fuel station and pharmacy all agree
  loosely.
- The nearest wins, unless one within 35 m has `reviews >= 2 * nearest.reviews + 5`.
- The pick must be within 1.5 km of the tap, 30 km for a settlement label, unbounded for a
  transit stop. Otherwise the label keeps its own name and point.
- A permanently closed pick hides the open pin for good, unless a live listing of that name is
  within 150 m.
- `NameScript.prefer(uiLang, google, label)` keeps the map's label as the sheet title when
  Google's name is not in the app language's script and the label is.
- `openPlaceCache` remembers each open place's listing (an LRU of 500, persisted to
  `open_place_links.json`). It is dropped when the app updates or the region's archive changes:
  a link made by an older rule outlives the fix. The recorded closures are kept.
- Offline or with Google off (`googleOff()`), the tap runs no search, reviews or photos. An open
  place shows its tile data or the remembered listing.
- The `VelaTap` log line carries each gate's counts (`cross=N`) and no coordinates.

#### The same-business rule

`core/util/PlaceNames` is one rule for the tap resolve, the Both-mode twin hiding and, mirrored
in SQL, the bake's dedupe. `PlaceNamesMatchTest` pins it.

- `normalized` folds accents (and ß, æ, ø, œ, ł, đ, ё), drops parentheticals, legal forms (LLC,
  Inc, GmbH, SARL, S.r.l., B.V., ООО, Kft), chain tails ("by Wyndham"), a leading "The" and a
  trailing store number, reads "&" as "and", keeps a possessive on its word, expands
  abbreviations (St, Ave, NY, Dr, Str., ул.) and joins dotted initials ("U.S." is "us").
- `match(a, b, extraGeneric)` answers EXACT (normalized equal), VARIANT (one name is the other
  plus only generic words: "CVS", "CVS Pharmacy"), OVERLAP or NONE.
- OVERLAP: nested with non-generic extra words ("SpeeDee-Midas" over "SpeeDee"); two
  identifying words in common; a brand prefix of two or more words; the shorter name as a
  phrase inside the longer; or the shorter's identifying words all inside the longer when the
  longer leads with them.
- A nested or subset match needs a strong core: two identifying words, or one of five letters
  that is not an ordinal. A four-letter brand (Lidl, Aldi, Rewe) carries it when it leads both
  names and the longer adds exactly one identifying word.
- A single shared identifying word is not a match ("Arroyo Park", "Arroyo Pool"), and a name
  of only generic words matches nothing by overlap.
- `GENERIC` is the union of one table per language (en fr de es it pt nl sv pl ru uk hu he,
  about 1,850 words): names on a map belong to the region, not the phone. A caller adds the
  town out of an address and `localGeneric(names)`, the words three or more names in the pool
  share.
- Plurals fold pairwise. A name glued into one word of eight letters or more is read as the
  words that spell it.
- Scripts written without spaces (Han, kana, Hangul, Thai) are compared as strings by
  `cjkMatch`: descriptor affixes (店, 支店, 薬局, 銀行, 有限公司, 지점, สาขา) are stripped, equal
  cores are a VARIANT, a core inside the other an OVERLAP, and a core under two characters
  matches nothing.
- `same` is EXACT only; `agree` is anything but NONE.
- `sameBusiness(a, kindA, b, kindB)` refuses an OVERLAP between two known, different icon
  groups (a fuel station and the pizza place on its lot). EXACT and VARIANT cross kinds
  ("Safeway Pharmacy", "Safeway"). Two names that reduce to one four-letter word match when
  their kinds agree.
- `sameFuelLot(kindA, kindB, distance, numberA, numberB)` calls two fuel stations within
  `FUEL_LOT_M` (30 m, short of a road plus two setbacks) one station whatever their names. Two
  known house numbers that differ refuse it. Ambient features carry `hn` for it.
- Not in the rule: a non-fuel department at the same point with a different name, and Google's
  two profiles for one business.

Cross-script: Google answers in the app's language where the archive has the local script, and
no name rule bridges that. When nothing agrees and the label's script
(`NameScript.scriptLanguage`: kana or Han inside Japan `ja`; Han elsewhere `zh-CN`, `zh-TW` over
Taiwan, Hong Kong and Macau; Hangul `ko`; Cyrillic `ru`; Hebrew `iw`; Thai `th`; Arabic `ar`;
Greek `el`) is not the app's language, `MapViewModel.crossScriptCandidates` runs the search
again with `hl=<that language>` (`MapDataSource.search(lang)`), keeps the listings that agree,
and fetches the nearest one's copy in the app's language by its own name. The copy replaces it
when the feature ids match. Two requests at most.

### 5.6 Search and results

- A query runs three pages of 20 over the viewport window. When the user is inside the window
  and it is over 1.5 times `NEARBY_SPAN_M` (2.5 km), or no window size is known, one extra page
  runs over a 2.5 km window around the user and leads the list.
- `SearchPb.build` stretches the template's window to the viewport span, floored at `MIN_SPAN_M`
  (1 km) and capped at 500 km.
- Ranking and shown distances use `rankFrom`: the user's location within about 50 km of the
  viewport, else the viewport center. The request bias stays the viewport. `plausibleBias()`
  discards a point near `0,0`.
- A name match "near you" that replaces a far view's results must not be permanently closed and
  must be within `HOME_NAME_MAX_M` (40 km) of the user.
- The "more results" row pulls the next three pages. It goes when a pull adds fewer than five or
  the query changes.
- "Search this area" keeps the results inside the view, padded by 10% per side
  (`AreaNarrow.PAD`), or the whole answer when none fall inside.
- Filters are local to the fetched results and also drop the map pins.
- One quick-category list (`ui/QuickCategories`) serves the map chips, search along route and
  in-nav search. Every query must be one the offline store expands.
- The camera frames the first `SEARCH_FIT_LEAD` (12) results; an open list or custom map is
  framed whole. Pins are median-centered, and outliers past 4 times the median spread (minimum
  40 km) are dropped. The fit consumes `lastCameraTarget`, or the recenter branch re-fires on
  the stale center.
- A search from a close zoom holds its view: no fit under `HOLD_VIEW_SPAN_M` (2.5 km) with at
  least `HOLD_VIEW_HITS_NAME` (1, a name: `SearchKind.isName`) or `HOLD_VIEW_HITS_KIND` (3)
  results in the visible strip.
- Opening a place flies to it at `browseZoom` (15.5, a dial) or the current zoom, whichever is
  closer; it never zooms out. A link's own zoom (`geo:...?z=`, `/@...,15z`) wins.
- Result markers are red circles; rated food results get a rating bubble. Pins collide by
  result order, and losers draw as small dots.
- Result cards show the name, a facts line, the open-or-closed line, amenity ticks, up to three
  photos from the search reply, and buttons that act without opening the place (Directions,
  Call, Website, Share, Menu). The address shows only when the name repeats, the place is
  unrated, or there is no photo strip. No extra request is made per result. A closed place
  reads in plain red on dark surfaces (`themedStatusColor`).
- A result's distance is from the point its search ranked from. A tapped map place and a saved
  or recent place are looked up around their own point, so `MapViewModel.fromHere` sets their
  distance from the user's position, or none without a fix.

#### Suggestions and addresses

- Network suggestions come from Google's search-as-you-type request (`MapDataSource.suggest`,
  the keyless `/s?tbm=map&suggest=p` call biased to the viewport). A row without a location
  runs as a search. When it fails or is empty, or Google is off, the search endpoint and
  OpenStreetMap race answers.
- Local suggestions (recent queries and places, saved and list places, opted-in contacts) are
  computed synchronously per keystroke and are all that shows offline. Contacts load into
  memory once. They dedupe against network rows by name plus coarse location, and by feature id.
- Every row except a contact has a fill-in arrow.
- `AddressQuery.parse` reads a leading house number and the street's first word that is not a
  direction or a street type; `matches` needs both as whole words of a result.
- A typed address is searched over at least `ADDRESS_SEARCH_SPAN_M` (40 km). When no matching
  result lies that near the view, the suggest request geocodes it. Matching rows lead, by
  `AddressQuery.score` (a point per typed street word, minus one for a different direction or
  street type: "1451 W Covell Blvd" before "1451 East Covell Boulevard"), then nearest.
- The on-device geocoder's estimates (ids `addr~`, `OfflineAddressStore.isApproximate`) are
  dropped when another result has the address. Bracketing house numbers over `INTERP_MAX_GAP_M`
  (300 m) apart are not blended; the nearer one's point is used.
- A place named by a street line whose address holds only the town shows and copies the two
  joined (`Place.fullAddress`).
- Typed coordinates drop a pin: `MapLinkParser.parseBareCoordinate` matches the whole string,
  requires a decimal point in both halves and range-checks.

#### Without Google, from a wide view

- "Near", for answering a name from Vela's own data alone, is capped at
  `GOOGLE_FREE_NEAR_CAP_M` (25 km): from a whole-world view every hit was inside the view and
  the open geocoder was never asked.
- Over a view wider than `GOOGLE_FREE_WIDE_M` (50 km) a name search leads with the geocoder's
  ranking and keeps at most five own hits, only those carrying every typed word. A top hit
  named exactly as typed opens on its own.
- Photon is asked in English when it does not speak the UI language. A Photon hit with a name
  and a kind (not a bare house or street) takes that name, its kind the category.

#### Saved rows and links

- A saved pin or address is a point (`SavedPlace.isPoint`: `bare`, a `pin:` id, or a name that
  is the first line of its own address) and reopens as saved, with no search. Saving a place
  that is not a listing (`Place.isListing()` false) sets `bare`; so does a contact's address.
- A star or a list entry made from a map label before its listing was known (the tap still
  resolving, no connection, Google off) is kept under the label's id (`LabelPlace`: `poi:` plus
  the name's hash for a basemap label, `overture:` plus the feature's id for an open place).
  The star is `bare` with an id that still matches its name (`SavedPlace.awaitsListing`; a
  renamed one no longer matches and stays a point). The list entry has no feature id
  (`ListPlace.awaitsListing`).
- Such a place takes its listing when it is next opened online with Google on
  (`MapViewModel.relinkKept`, one `searchOnce` by its name), or when a tap on its label (the
  one it was made during, or a later one) resolves to a live listing that agrees by name. On
  open, `keptLabelListing` accepts a listing whose name agrees, within `KEPT_LABEL_MAX_M`
  (250 m), open, with a feature id and no other house number than the kept address; a transit
  stop or a junction is never taken; the same name leads, then the nearest. The listing is
  written back (`linkSaved`, `linkListing`): the star loses `bare` and takes the listing's
  point and address, and every list entry kept from that label takes the feature id and
  address. The kept name, note and icon are not changed, and a sheet opened from the kept
  place keeps its id and name, so the star and the list marks hold. A basemap label's id is
  shared by every place of that name, so only a copy within `LabelPlace.SAME_LABEL_M` (30 m)
  of the opened one is written.
- A star put on a sheet that has a feature id before its details loaded (a picked suggestion)
  loses `bare` when that sheet becomes a listing (`mergeDetails`: same id, same name).
- None of this asks anything at launch, offline or under "Use Vela without Google". With "Look
  up tapped places on Google" off, an `overture:` entry opened from a list is not looked up.
- Any other saved or recent place takes a listing's details only from one within 30 m, or
  within 250 m whose name agrees (`PlaceNames.agree`).
- Rename (`SavedActions.rename`) is on the pinned rows and in the sheet's save menu. The
  listing's name is taken only when it agrees with the saved one.
- A location link's action is a setting (`LinkAction`, `link_action`): show the place
  (default), open the route chooser, or start navigation. Start also applies to directions
  links and passes the precise-location and notification gates.
- Directions links open the route chooser on the destination: `maps?saddr=&daddr=[&dirflg=]`
  (the last `to:` place is the destination), `maps/dir/?api=1&destination=&origin=&waypoints=&travelmode=`,
  `maps/dir/A/B/C/@...` (an empty A is "from here") and the `google.navigation:q=|ll=&mode=`
  intent. `MapLink.directions` routes `openDeepLink` to `openDirectionsLink`: a coordinate
  becomes a reverse-geocoded pin, a name runs the "navigate to" search
  (`openDirectionsOnResult`).
- The places between the start and the end are `MapLink.stops`, at most
  `SavedRoutes.MAX_STOPS`. A path-form link from a desktop browser carries each place's own
  coordinate in its `data=` blob (`MapLinkParser.dirPins`: a `1m<n>` field per place, with
  `1d<lng>` and `2d<lat>` among its `n` fields, and `3e<0-3>` for the mode). The pins are used
  only when the blob lists exactly the path's places; such a place is taken as named and
  pinned, with no lookup (`linkPin`), and the trip is fetched once. A name with no pin is
  searched near the destination and the trip rerouted (`applyLinkTrip`).
- A point dragged onto the route on a desktop sits in the blob inside the block of the place
  before it (`3m4`, `1m2`, `1d<lng>`, `2d<lat>`, `3s<id>`), and comes out as a stop marked
  `MapLink.via`, in travel order, at most `LINK_VIAS_MAX` (12). The view model keeps them as
  the trip's `LinkPlan`: `route` goes through the stops and the dragged points together and
  stamps the result's `detourPlan`, so a drive passes them as silent stops
  (`NavController.navStopsFor`). They hold while the trip is the link's (same end, same given
  start, same stops in order) and are dropped at the first edit.
- The link's mode, its stops, and a start more than `LINK_ORIGIN_HERE_M` (150 m) from the fix,
  apply once to the next `routeToSelected`. The mode is not made sticky.
- A whole Google Maps address in the search box (`MapLinkParser.isMapsUrl`: one token on a
  Google host with a maps path) opens as that link. It used to be searched as text.

#### Google My Maps

A link `google.com/maps/d/<viewer|edit|embed>?mid=<id>` (`MapLinkParser.myMapId`) opens like a
shared list. `MapDataSource.importMyMap` fetches the KML
(`https://www.google.com/maps/d/kml?mid=<id>&forcekml=1`) and the public viewer page, with no
key and no session; it is refused under "Use Vela without Google". `core/data/MyMapKml` reads
it with regexes, each compiled once in the object and none per placemark. Both fetches use
`GoogleMapsDataSource.largeReplyHttp`, the shared client with a 90 s call timeout
(`LARGE_REPLY_TIMEOUT_S`) and a 60 s read timeout: a large map's KML runs to megabytes, and
the shared 12 s timeout cuts it off.

- `<Folder>` is a layer (`mapLayer`, `ListPlace.layer`). `<Point>` placemarks become places,
  the description as the note. `<LineString>` and `<Polygon>` (outer ring) become `MapShape`s
  in the map's `<LineStyle>` / `<PolyStyle>` colors (aabbggrr), through `<StyleMap>`.
- A marker keeps its color (`<IconStyle>`, `Place.pinColor`, `ListPlace.color`) and up to 12
  photos (`gx_media_links` and `<img>`, `ListPlace.photos`).
- Caps: 2,000 places, 500 shapes, 150,000 shape points.
- Saving an import (a custom map or a shared list) goes through `importTarget`. The same import
  saved again refreshes its list and keeps the name, icon, color and sound set on it: the list
  is found by the id its source gives (`ImportedList.sourceId`, the map's id or the list's
  link), or, for a list saved before imports carried a source, by the title's id while it
  still has that title. A list that only shares the name is never replaced, so two maps both
  called "Untitled map" are two lists.
- Icons: the KML names an icon only by number (`icon-1577-FFD600`). `MyMapKml.iconNames` reads
  the full names from the viewer page and `MyMapKml.iconUrl` builds the address on
  `mt.googleapis.com/vt/icon`, kept as `Place.pinIconUrl` / `ListPlace.iconUrl`. The plain pin
  (1899), an unnamed icon or a failed viewer fetch keeps Vela's pin. `MyMapIcons` loads the
  images (counted as Google requests), and the saved-pin layer draws them
  (`MapMarker.drawn = false` keeps them out of the red result pins).
- A layer with exactly one line and 2 to `MAX_ROUTE_STOPS` (12) points, the line's ends within
  `ROUTE_END_M` (250 m) of the first and last, is a directions layer. Its points become
  `MapShape.stops` and the list gets a row (id prefix `shape-route:`). `openShapeTrip` routes
  from the user through every stop when the first is within 50 km, skipping it within 150 m,
  and otherwise from the first stop.
- The result is an `ImportedList` with `shapes`; a map with no pins gets `ShapesOnlySaveBar`.
  Saving stores `PlaceList.shapes`, and `VelaMapView.ensureShapes` draws them (one GeoJSON
  source, a fill layer, a line layer, two label layers), except while navigating.
- Two or more layers get a Layers chip (`MapLayers`, `MapViewModel.toggleListLayer`,
  `PlaceList.hiddenLayers`; before saving, `pendingHiddenLayers`). A hidden layer is neither
  listed nor drawn.
- A tapped shape opens a sheet (`MapViewModel.openShape`) with its name, its measure on the
  address line (`core/util/ShapeMeasure`, `formatArea`) and its description. The measure is not
  the category, which would make the sheet a business listing.
- Not carried: the base map style and videos.

#### Drawing

- The lists dialog's Draw button sets `MapUiState.drawing` (`DrawState`). Every map tap and
  long press then adds a point (`drawDots`, `onDrawTap`), ahead of all tap resolution. Back
  removes the last point, then leaves.
- `DrawBar` (reached through `ShapeBottomBar`, because MapScreen is at the method size limit)
  offers Line or Area, six colors (`DRAW_COLORS`), the live measure, a name, Undo and Save.
- The typed name is kept in `DrawState.name` (`MapViewModel.drawSetName`). The bar leaves
  composition whenever another sheet takes the bottom slot, such as a place opened from search
  while drawing, and starts from the kept name when it returns.
- A line needs 2 points and an area 3; at most 2,000 points. A finger within 26 dp of a point
  drags it, and a tap removes it. Points cannot be inserted between two existing ones.
- Save appends a `MapShape` (width 4, an area filled at 25% opacity) to the list
  `list:drawings` ("My drawings").
- The unfinished shape and a dot per point (`vela-shapes-dots`) draw from a source of their own
  (`ensureShapeDraft`, `vela-shapes-draft-src`, a fill and a line layer directly above the saved
  shapes' fill and line), so a dragged point uploads that one shape. On the saved shapes' source
  every move event would re-upload and re-tile all of them. `MapSurface` passes the unfinished
  shape as the last of `shapes`; `VelaMapView` takes it off the saved set.
- The shape sheet's menu has "Edit this drawing" (`DrawState.editOf`, saved back in place) and
  "Delete this drawing" (`ShapeActions.delete`, `MapViewModel.deleteOpenedShape`).

### 5.7 Saved places, lists, parking and imports

- List membership matches on `ListPlace.matches` (id or stable feature id), never a bare id: a
  Google place's id derives from its name hash and a coarse coordinate.
- `SavedPlace.of(Place)` carries an optional address, defaulted null so older payloads decode.
  Every store's JSON sets `ignoreUnknownKeys`, so a downgrade survives.
- `SavedPlace.pinned` and `SavedRoute.pinned` (default false) decide what the search page
  shows; the bookmark button's sheet has the pin toggles.
- `SavedPlace.icon` and `ListPlace.icon` hold a glyph name or `emoji:X`. The pin draws
  `ListPlace.icon ?: PlaceList.icon` and `SavedPlace.icon ?: "bookmark"` in the list's color.
  "Choose icon" calls `MapViewModel.setPlaceIcon`, which writes every list copy and the
  quick-save.
- Recents are timestamped under new preference keys. The legacy keys are read once and left in
  place for a downgraded build.
- Several places at once: a long press on a card of an open list, or on a Saved places row in
  "Your lists", starts a selection (the select button is the key path). The sheet's title
  becomes `BulkBar` (`ui/map/ListBulk.kt`): the count, All, Move to list (any other list, or a
  new one named on the spot) and Remove behind a confirm. The stores write once per action
  (`PlaceListStore.removePlaces`, `movePlaces`, `addPlaces`, `SavedPlaceStore.removeAll`); a
  move to a list that is gone moves nothing. Moving a saved place puts it in the list, with
  its own icon, and takes the star off. Only rows on show are counted and acted on, so a row
  picked and then hidden by a filter or a layer switch is left alone. A new search closes the
  open list (`openListId`).
- A list's sound: `PlaceList.alert` is a key of `PassAlerts.SOUNDS` (`ping`, `bell`, `double`,
  `low`, `name`) or null, set in the list editor, where picking one plays it. Each fix, live
  or simulated, goes to `PassAlerts.Tracker.onFix` (`core/nav/PassAlerts.kt`) with the places
  of every list that has a sound. A place sounds when the fix comes within `RADIUS_M` 150 of it
  at `MIN_SPEED_MPS` 2 or faster, once until the car is `EXIT_M` 250 away, and at most once in
  `AGAIN_MS` 15 minutes. Several at once sound the nearest only. The first fix of a session
  marks the places it is beside without sounding. During a drive, a place within 150 m of the
  destination or a remaining stop is skipped. The tone plays on the guidance stream through
  `VoiceGuide.placeTone` and `name` is spoken, so muting the voice mutes both. The status card
  shows the place and its list. Fixes only arrive while the map is open or a drive is
  running; nothing asks for background location. Log tag `VelaPassAlert` (sound and count,
  never the name).
- Parking is one tap on the P button, with a history. Settings > Map "Parking button"
  (`ParkingButton`, default on) hides the button while no spot is saved. With it on, the
  arrival card of a drive has "Save parking spot" (`ParkingActions.saveHere`), which saves the
  current position as the button does. A walk, a ride or a transit trip does not offer it.
  "Find my car" opens the spot as a sheet whose facts line gives the distance from you and how
  long ago it was saved (`ParkingActions.parkedAt`). On a key-first device the arrival card opens
  with focus on Done, and each of its buttons draws the focus ring.
- Imports accept GPX, KML and GeoJSON including Google Takeout (`core/data/PlaceImport`). GPX
  is latitude first; KML and GeoJSON are longitude first. A KML placemark with several
  coordinate tuples is skipped. Only name, coordinate and a given address are taken. Ids derive
  from the rounded coordinate, so a re-import adds nothing; `0,0` and out-of-range points are
  dropped. The picker keeps `*/*` last, because Android reports `.gpx` as an octet stream.
  `ImportResult` distinguishes added, nothing-new, wrong-format and unreadable. Never wrap the
  launcher in a bare `runCatching`: with no documents provider the button then does nothing.
- Trip shortcuts: the route card's menu pins the trip as a launcher shortcut. Its intent
  (action `app.vela.action.OPEN_TRIP`) carries names, latitudes and longitudes as parallel
  arrays (NaN means "your location") and the mode. Nothing is stored in the app.

#### Saved routes

"Save this route" in the route chooser's menu stores a `SavedRoute` (encoded 1e-5 polyline,
ends, mode, name; `SavedRouteStore`, prefs `vela_saved_routes`). Settings > Saved places lists
them. They are not in the saved-places export.

- A later trip with no stops, the same mode, a start within `SavedRoutes.ORIGIN_M` (1 km) and
  an end within `DEST_M` (250 m) gets it offered. A route already on offer that is the same way
  (every 25 m sample within `OFF_M`, 60 m, both directions) takes its name.
- Otherwise the router is asked for the trip through vias on the saved line, mid-stretch where
  it leaves the fastest route: stretches under `MIN_RUN_M` (150 m) are ignored, one more via
  per `VIA_EVERY_M` (2.5 km), at most `MAX_VIAS` (8). The result carries `savedName` and the
  vias as its `detourPlan`, which the drive keeps as silent stops through every reroute.
- A matching saved route leads the list, selected, with `SavedRouteChip`. The reorder
  recomputes the camera counts, and provisional naming replaces its route by identity, not
  index.
- A run: saving a trip with stops and "Stop at these places" on stores `SavedRoute.stops`, at
  most `SavedRoutes.MAX_STOPS` (10). A run is never offered as an alternate. `openSavedRoute`
  starts it in its own mode from "Your routes" or the Settings list.
- A trip takes at most 10 stops: `addStop` refuses the eleventh and `applyTrip` truncates.
- "Add stop" in the trip editor (`beginPickStopFromEditor`) comes back to the editor when the
  place is picked or the pick is cancelled, with the new stop as the last row before the
  destination. "Add stop" on the route card still lands on the route.
- Opening a saved route sets `openSavedRouteId`, and the card's menu offers "Save changes to
  <name>" (`updateOpenSavedRoute`).
- During a drive `NavController` keeps one fix per `DRIVE_TRACE_STEP_M` (15 m). On arrival
  `SavedRoutes.droveOwnWay(trace, planned)` (at least `MIN_DRIVE_M`, 500 m, and a real stretch
  off the planned route) fills `drivenRouteOffer`, and the arrival card offers to save the
  trace. `debug.vela.tune.drivenOfferAlways` forces the offer.

---

## 6. Map rendering

The engine is MapLibre Native Android 13.6.1, artifact `android-sdk-opengl`. The plain
`android-sdk` artifact is Vulkan since 13.0.0.

### 6.1 Basemap and fonts

The basemap is OpenFreeMap Liberty (`MapStyle.LIBERTY`), loaded from its live URL so tile paths
follow OpenFreeMap's current snapshot. Vela's zoom reads about one level lower than Google's for
the same visible area (512-pixel tiles). Compare against Google by visible area, never by zoom
number.

Map labels draw in Google Sans Flex layered over Roboto and Noto, composited per glyph by
`scripts/build-map-fonts.sh`: Flex where it has the glyph (Latin, extended Latin, Vietnamese),
Roboto for Cyrillic and Greek, Noto for every other script. The folders keep the Noto stack names
(`Noto Sans Regular`, `Noto Sans Bold`, `Noto Sans Italic`), so the only style change is the
`glyphs` URL. The set is the `map-fonts` release, served from Pages at `BuildConfig.MAP_FONTS_URL`.

`ui/map/MapFonts` builds the style the map loads:

- At launch it uses the cached patched style (`files/style/liberty-roboto.json`) if there is one,
  then refreshes it: probe the font host, fetch the live Liberty JSON, point `glyphs` at the font
  host, write the cache.
- A font host that fails the probe evicts the cache, and the map loads plain Liberty in Noto. A
  style whose glyph URLs fail draws no labels at all.
- A failed style fetch keeps the last cache for at most 7 days (`STALE_EVICT_MS`). A cached style
  pins a tile snapshot, and OpenFreeMap retires old snapshots.
- Only Liberty is patched.

Under an installed offline basemap the style is built from JSON: the patched cache, else the
bundled `assets/styles/liberty-roboto.json`. `withLocalBasemap` points its layers at the local
archive, maps the asset's Roboto stacks to the Noto names (`GlyphPackStore.stackName`), and serves
glyphs and the sprite from `file://`. `GlyphPackStore` unzips the `map-fonts` release into
`glyphs/` under `StorageLocation.root` (about 200 MB) and copies the bundled sprite to
`files/sprites/`. `asset://` hangs the same way an unreachable host does.

The bundled asset pins a dated tile snapshot, so it is used only where the tile source is
replaced. It is one minified line: edit it with a script that re-dumps with
`separators=(',',':')`.

### 6.2 Palettes

`applyMapTheme(style, dark, amoled)` runs one palette function, then the passes every palette
shares (`widenStreets`, the road edges, label colors, the hide list, borders).

| Map state | Function |
| --- | --- |
| AMOLED | `applyAmoled`, which runs `applyDark` and then overrides colors |
| Dark, Classic | `applyClassicDark` |
| Dark, Modern | `applyDark` |
| Light, Classic | `applyClassicLight` |
| Light, Modern | `applyLight` |

- `MapColors` picks Modern or Classic: pref `map_palette`, and with no pick calibration's
  `defaultMapPalette` (`modern`). AMOLED ignores the pick.
- The map's light or dark is its own setting: `AppTheme.mapMode` (`MapThemeMode` FOLLOW / LIGHT /
  DARK, pref `theme_map_mode`, Settings > Appearance > Map). `isMapDark()` resolves the nav
  day/night override first, then the map mode, then the app theme. `isMapAmoled()` is true only
  while the map follows an app in `ThemeMode.AMOLED`. The map surface, its route colors and the
  scale bar take these. Sheets, cards, bars and settings keep `isAppInDarkTheme()`.
- `styleKey` carries the style URI, the dark and AMOLED flags, the palette, satellite, the puck
  style, the house-number level and the offline basemap archive. A change in any of them reloads
  the style.
- Every layer is colored in all five functions. An unstyled runtime `LineLayer` renders black, and
  a Liberty layer no dark function names keeps its light colors at night.
- `applyAmoled` sets colors only. Zoom gates and extrusion geometry come from `applyDark`.
- The functions take a `StyleLayers`, so the Android Auto snapshotter (`SnapshotterHost`) gets the
  phone's theme (`StyleHost`).

Modern is sampled from the Google app. Classic is the earlier look: white roads and yellow
motorways by day, neutral slate by night. In the table "land" is that palette's land color and
"at 0.9" is a fill opacity.

| Element | Modern light | Modern dark | AMOLED | Classic light | Classic dark |
| --- | --- | --- | --- | --- | --- |
| Land (`background`) | `#f8f7f7` | `#162640` | `#000000` | `#f2f1ee` | `#2b2f36` |
| Water (`water`, `waterway_river`) | `#90daee` | `#000d2a` | `#04080C` | `#90daee` | `#2f4a63` |
| Park (`park`) | `#d3f8e1` | `#0d3847` | `#050E0A` | `#cfeccd` | `#2c4a34` |
| Grass (`landcover_grass`) | `#d3f8e1` | `#0d3847` at 0.9 | `#050E0A` | `#d3f8e2` | `#2c4a34` at 0.9 |
| Wood (`landcover_wood`) | `#d3f8e1` | `#0d3847` at 0.95 | `#050E0A` | `#c9f2da` | `#274330` at 0.95 |
| Wetland (`vela-wetland`) | `#d3f8e1` | `#0d3847` at 0.9 | `#000000` | `#cdeff0` | `#26403c` at 0.9 |
| Pitches (`vela-pitch`, `landuse_pitch`, `landuse_track`) | `#a9eac2` | `#0d4956` | `#050E0A` | `#d3f8e2` | `#2c4a34` |
| Plaza and parking (`vela-plaza`) | `#dbe0e8` | `#2a3546` | `#0A0C0F` | `#ededed` | `#31363f` |
| Commercial and retail (`vela-commercial`) | `#fdf9ef` | `#1c2638` | `#000000` | land | `#31363f` at 0.5 |
| Campuses (`landuse_school`) | `#f0eded` | other landuse | other landuse | land | other landuse |
| Other landuse (remaining `landuse*`, `landcover*` fills) | land | `#2a3546` at 0.5 | `#000000` | land | `#31363f` at 0.5 |
| Buildings (`building`, `building-3d`) | `#e8e9ed` | `#1c3b69` | `#0A0C0F` | `#dde1e7` | `#383d45` |
| Building outline | `#d6d9e6` | `#2e3d6d` | `#14171A` | `#c4c9d1` | `#464c56` |
| Overlay footprints (`vela-ovl-*`), fill / outline | `#e2e3e9` / `#c4c9d1` | `#1c3b69` / `#2e3d6d` | dark value of the picked palette | `#dde1e7` / `#c4c9d1` | `#383d45` / `#464c56` |
| Minor, secondary, tertiary and link roads | `#aab9c9` | `#3d5a77` | `#1A1D22` | `#ffffff` | `#565b64` |
| Trunk and primary | `#aab9c9` | `#476789` | `#22262C` | `#ffffff` | `#6a707b` |
| Motorway and its links | `#8aa4c0` | `#476789` | `#22262C` | `#f9d27a` | `#9c7f4f` |
| Service and track | `#9bacbc` | `#2a4056` | `#111418` | `#ffffff` | `#565b64` |
| Casings | land, then road edges | land, then road edges | `#000000` | motorway `#f0b85a`, trunk and primary `#dadde2`, secondary and tertiary `#e4e6ea`, others land | land |
| Trails (`vela-trails`) | `#7fcdb0` | `#167055` | `#1A3A28` | `#7fcdb0` | `#167055` |
| Bike paths (`vela-bikeroutes`) | `#007b8b` | `#1f8f9c` | `#0D2D36` | `#007b8b` | `#1f8f9c` |
| Airport area (`aeroway_fill`) | Liberty's | `#1c2638` | `#0A0C0F` | Liberty's | `#31363f` |
| Runways and taxiways (`aeroway_runway`, `aeroway_taxiway`) | Liberty's | `#2a4056` | `#1A1D22` | Liberty's | `#565b64` |
| Label text / halo, every symbol layer | Liberty's | `#c3cad6` / `#1a2230` | `#D0D4DC` / `#000000` | Liberty's | `#cdd0d6` / `#1e2228` |
| Hillshade exaggeration, then shadow / highlight / accent | 0.32, `#6b7280` / `#ffffff` / `#9aa0a6` | 0.45, `#0a1018` / `#3a4a68` / `#0a1018` | 0.3, `#000000` / `#1A2030` / `#000000` | as Modern light | 0.4, `#0b0d10` / `#4a4d55` / `#0b0d10` |

- Road names (`highway-name-path`, `highway-name-minor`, `highway-name-major`) use the
  `Noto Sans Bold` stack and a 1.9 halo in every palette, white in the light ones, so a name stays
  legible over the route line. Other labels in the dark palettes get a 1.1 halo.
- Vela's own label layers (`vela-ambient`, `vela-places-*`, `vela-transit-stops`, `vela-markers`)
  take halo `#FFFFFF` by day, `#11161C` at night and `#000000` in AMOLED. Result labels are
  `#3C4043` by day and `#E8EAED` at night. Dot rings take the Modern land color.
- Bike paths are OSM `highway=cycleway`, split out of the trails layer, which keeps foot paths.
  On-street painted lanes are not in the tile schema and are not drawn.
- Borders: `boundary_2` (countries) is solid, `#9AA0A6` by day and `#8C95A3` at night.
  `boundary_3` is narrowed to admin levels 3 and 4 (states and provinces), dashed 3:2 from zoom 4,
  `#B4B8BC` by day and `#6F7887` at night. County and city limits are not drawn. Never put the
  boundary layer ids back in the hide list.

**Road edges** (`applyRoadEdges`, Modern light and dark only). Google's navigation map draws them.
By day each casing is a darker shade of its own fill, at night every casing is near-black. This
also splits the two halves of a divided road.

| Casings | Fill | Day | Night |
| --- | --- | --- | --- |
| Minor, secondary, tertiary, trunk, primary, link | `#aab9c9` | `#94a1af` | `#07090e` |
| Motorway and its links | `#8aa4c0` | `#5e75a0` | `#07090e` |
| Service and track | `#9bacbc` | `#8593a2` | `#07090e` |

Each edge is at least `ROAD_EDGE_DP` (1 dp) wide from z16, growing in from z13.5 (`edgeFloor` in
`widenStreets`).

**3D buildings.** Extrusions take the building fill color with the vertical gradient on, under a
style light of intensity `B3D_LIGHT` (0.25). At 0 every face draws one color and no wall reads as
a wall. At MapLibre's default 0.5 roofs draw about 40 percent brighter than the palette. At 0.25 a
roof stays within 1 to 3 percent of the palette color and a wall facing away from the light draws
up to 25 percent darker.

Extrusions show only with the 3D buildings setting on, satellite off, navigation off and the
camera tilted: on at `B3D_TILT_ON` (20 degrees), off below `B3D_TILT_OFF` (12). Seen straight
down, perspective leans tall buildings over the streets beside them. The change is an opacity
transition of `B3D_FADE_MS` (350 ms).

The layer starts hidden and costs nothing until the first tilt. From then on, on that style, the
tilt gate is paint only: flat, the layer stays visible at opacity 0, which MapLibre skips at
render, so tilting again re-lays out nothing. A visibility change re-lays out every basemap tile
(the flip cost in 4.7b), which a gate on visibility pays twice per tilt. The price of staying
visible: tiles at z17 and closer build the extrusion geometry while flat. The layer is hidden
outright with the setting off and when a drive starts: after the fade when it is on screen, at
once when it is at opacity 0, which for a drive is the frame the car-mode strip re-lays out the
basemap anyway. A walk or a ride keeps it at opacity 0.

### 6.3 Layer rules

#### MapLibre behavior

- A layer's `maxzoom` is exclusive. Liberty's `building` fill is minzoom 13, maxzoom 14, so the
  palette functions set min 16 and max 24. Min alone leaves an empty range.
- Draw order and placement order are the same thing. The topmost symbol layer places first.
- A layer at opacity 0 is skipped: it is not rendered, `queryRenderedFeatures` does not see it and
  its source is not tiled. A layer that must stay live while invisible draws at 0.004
  (`ROUTE_PENDING_OPACITY`).
- On a source that is tiled anyway, a visible layer at opacity 0 still has its geometry built in
  every tile in its zoom range (MapLibre 13.6.1 hands a tile every layer of its source whose
  visibility is not none, whatever the opacity), so showing it again is a paint change. The 3D
  buildings' tilt gate rests on this (6.2).
- A visibility or filter change re-lays out every tile of the layer's source, also when the
  layer is hidden. An opacity change does not.
- An 8-digit hex color string is rejected and falls back to opaque black.
- A `fill-pattern` from the style cannot be cleared. The layer is hidden and a flat twin drawn.
- The pmtiles path never cold-fetches a tile clamped two or more levels below the camera.
- Past a GeoJSON source's maxzoom every overscaled tile lays out all of its parent tile's
  features, so point sources set one. The route line sources set none.

| maxzoom | GeoJSON sources |
| --- | --- |
| 18 | ambient, markers, traffic controls, transit stops |
| 16 | plate cameras, their clusters, speed cameras, transit line labels, custom-map shapes, the shape being drawn |
| 14 | accuracy disc, transit lines |
| 12 | me, parking, saved, Street View |

- Every `setGeoJson` and `setProperties` is identity-gated by a `lastApplied` holder. Every such
  holder is reset in the style-reload block, or a theme, palette or satellite flip leaves sources
  empty.

#### Vela's basemap layers

`ensureLayers` adds these on the basemap source (`basemapSrc(style)`):

| Layer | Source layer and filter | From zoom |
| --- | --- | --- |
| `vela-wetland` | `landcover`, class wetland | 12 |
| `vela-plaza` | `transportation` polygons | any |
| `vela-pitch` | `landuse`, class pitch, playground, track or stadium | 13 |
| `vela-commercial` | `landuse`, class commercial or retail | 12 |
| `vela-trails` | `transportation` lines, class path, subclass path or bridleway | 14 |
| `vela-bikeroutes` | `transportation` lines, class path, subclass cycleway | 14 |

Trails and bike paths are 0.7 dp wide at z14, 1.6 at z16 and 4 at z19, below `road_minor`.

Hidden Liberty layers: `landcover_wetland` and `road_area_pattern` (patterned, replaced by the
twins), `road_path_pedestrian`, `bridge_path_pedestrian`, `bridge_path_pedestrian_casing`,
`tunnel_path_pedestrian`, `park_outline`, and the six `*_rail_hatching` layers.

Route shields are Vela's own images (`RoadShields`, `vela-shield-*`, one per ref length 1 to 6),
installed after the theme pass. They carry the sign's colors in every palette.
`vela-exit-shield` adds motorway exit numbers from z12.5.

A drive hides the basemap's three shield layers: every numbered road in view carried them, the
other carriageway's too. The driven road gets its own instead (`core/nav/RouteShields`, layer
`vela-nav-shields`): from the steps' refs and mid-leg renames, one shield `FIRST_AFTER_M`
(350 m) into each numbered stretch of `MIN_STRETCH_M` (900 m) or more, then every `EVERY_M`
(1,600 m), none within `END_CLEAR_M` (300 m) of its end. Computed once per route in
`MapSurface`, drawn with the same images, placed under the signs and callouts so those win a
collision. Hidden in the overview with the other `vela-nav-` layers.

#### Buildings

- The flat `building` fill draws from z16. `building-3d` draws from z17 at 30 percent of its
  height, growing to full height by z19 (`applyBuilding3dGeometry`). With extrusions at z16,
  dense-city towers lean over the roads.
- Overlay footprints (`vela-ovl-<i>`, minzoom 16) draw beneath `building`, so OSM wins wherever it
  has data. Their colors are in 6.2, their visibility in 6.4.

#### House numbers

- Two layers draw them: `vela-housenumber` (the basemap's `housenumber` source layer) and
  `vela-addr-<i>` (the address overlay). `vela-housenumber` is hidden while an address overlay is
  mounted, so an address never draws twice.
- Both gate on `houseNumberMinZoom()` (`HouseNumbers`, pref `housenumber_zoom`: near 18.3, normal
  17.8, far 17.3) and fade in over the next 0.6 levels (`houseNumberFade`).
- The address archives carry tiles only at z16-17, so `vela-addr-*` has minzoom 17 and its gate is
  the text field, empty below `houseNumberMinZoom()`. With the gate as the layer minzoom a cold
  source fetched nothing. With an opacity gate every hidden number still ran placement.
- Both carry `textIgnorePlacement(true)`: they yield to icons but never enter the collision index,
  so a number cannot evict an icon.
- `vela-addr-*` is inserted below `vela-controls-claim`: above the basemap labels, below the
  ambient icons.
- `vela-housenumber` carries a `within` filter, a box one screen wider than the view on every side
  (`restrictHouseNumbers`). The basemap tiles stop at z14, so past that every number in a tile
  draws each frame, on screen or not. The throttled idle work moves the box when the view comes
  within a quarter screen of its edge or has shrunk to under a fifth of the view it was built for.
  In a drive the box only moves below `HOUSE_NUMBER_NAV_MAX_MPS` (4.5 m/s): a filter change
  re-lays out every basemap tile, and the follow camera reaches the box's edge every few
  hundred meters.
  The box does not move while the layer is hidden (an address overlay mounted, the overview, a
  turn): the relayout would be for nothing on screen. The address-overlay effect moves it in
  the frame it shows the layer again; otherwise the next idle work does.
  Pixel 4a, Montreal: 33 to 50-59 fps at z19 on a dense residential block, 40 to 52 at z20.5
  downtown.

#### Symbol order

- Traffic controls draw in two layers. `vela-controls` always draws (`iconAllowOverlap` and
  `iconIgnorePlacement` true) and sits above the route pieces and bridge geometry, below the
  basemap text. `vela-controls-claim` (`iconOpacity` 0, `iconAllowOverlap` true,
  `iconIgnorePlacement` false) sits below `vela-ambient`, above the basemap labels, so street names
  shift away from sign positions and no Vela icon is evicted.
- Both dot tiers (`vela-ambient-dots`, `vela-places-dots-<i>`) sit below the basemap's first
  symbol layer, so a label's halo covers its dot.
- In a packed area a low-ranked generic open place is not drawn, dot included. The rule applies to
  a row whose tile carries `mark` 0 and no `landmark`, in group default or health, with prominence
  under `openGenericMinProminence` (4.0) and rank in its ~400 m cell past `openGenericHideRank`
  (`GENERIC_HIDE_RANK`, 120). Dots return at `GENERIC_REVEAL_ZOOM` (19.5), pins and names at
  `GENERIC_ICON_ZOOM` (20.3). It is for clutter and has no frame-rate effect. Midtown Sacramento
  draws the same with the rule on and off.

#### Road widths

Liberty grows a road's line 1.2x per zoom level while the map doubles, so its streets thin out
next to the buildings as the map zooms in. `widenStreets`, run for every palette, draws each
class at the wider of its base curve and a real width in meters:

    dp = min(cap, meters x roadWidthScale x 2^z / (78271.517 x cos 40))

An expression cannot read the latitude, so 40 degrees is fixed. Stops are every half level from z5
to z20. Roads, bridges and tunnels of a class share one curve.

| Class | `ROAD_WIDTH_M` | `ROAD_ONEWAY_WIDTH_M` | `ROAD_WIDTH_CAP_DP` |
| --- | --- | --- | --- |
| minor (`road_minor`, `bridge_street`, `tunnel_minor`, `tunnel_street`) | 10 | same | 34 |
| service_track | 3.5 | same | 14 |
| link, motorway_link | 8 | 6 | 28 |
| secondary_tertiary | 13 | 9 | 42 |
| trunk_primary | 17 | 11 | 52 |
| motorway | 22 | 13 | 62 |

- The base curve is Liberty's own, except minor roads: 0 at z12.5, 1.6 at z13, 4 at z14, 9 at
  z16, 18 at z20.
- A casing keeps the style's border width around the wider fill, and at least the road-edge floor
  (6.2).
- A piece whose tile `oneway` is set takes the one-way width: OSM draws a divided road as two
  ways, and at the whole road's width each the pair merged into one band about three times the
  route stripe. Minor and service roads keep their width, since a one-way street there is a whole
  street.
- Browse pans at z16.6 on a Pixel 4a measure the same with Liberty's widths and these (51-59 fps).
  Repeated runs of one build on that phone vary by 5 to 7 fps of median, so a smaller gap is
  noise.

#### Painted roads (developer test, not shipped)

`core/data/PaintedRoads`, `ui/map/PaintedRoadsLayer`, dial `debug.vela.tune.paintedRoads` (1 =
the California bake, 2 = built on the phone). Road markings from OpenStreetMap tags, drawn at
real scale from z16.5 above the roads and below the route line: medians, road surface (3.3 m a
lane; bike lanes 1.2 m, set 0.9 m outside), center and lane lines, crosswalks, stop lines
(`STOP_BACK_M` 8.5 m back at a junction) and one turn arrow per lane (`ARROW_BACK_M` 18 m
before the junction, `ARROW_M` 8, set by eye). Lines stop `JUNCTION_TRIM_M` (8 m) short of
intersections. The crosswalk dash is 0.4 of its width, since at 0.22 MapLibre's dash texture
draws nothing, and arrows are bitmaps, since as SDF icons the strokes thinned to nothing.

The dial is polled from an effect that restarts at every style load, for every user. Mode 2
fetches through one HTTP client per process (`paintedRoadsHttp`), built on the first fetch: a
client built in that effect would set up TLS on the main thread at each style load.

Bake: `scripts/bake-painted-roads.sh <pbf> <name> --upload` (osmium, `PaintedRoadsBakeTest` with
`-DvelaPaintIn/-DvelaPaintOut`, tippecanoe z15 layer `paint`) onto the `painted-roads` release.
A version query on `BAKED_URL` moves the map cache off old tiles after a rebake.

#### Street names

- `highway-name-minor` has min zoom 13.5. A symbol layer draws nothing below its own floor however
  wide the line under it. `highway-name-path` keeps Liberty's floor.
- `symbol-spacing` on `highway-name-minor` and `highway-name-major` (`roadNameSpacingExpr`), linear
  between stops:

| Zoom | Spacing, px | Constant |
| --- | --- | --- |
| up to 16 | 140 | `ROAD_NAME_SPACING_PX` |
| 18 | 300 | `ROAD_NAME_SPACING_CLOSE_PX` |
| 19 | 540 | |
| 20 | 960 | |
| 21 | 1500 | `ROAD_NAME_SPACING_MAX_PX` |

  At MapLibre's default 250 most blocks of a grid carry no name at street zoom. Past z16 a block
  fills the screen and 140 repeats each name along it. The tiles stop at z14, so further in a road
  repeats its name along its whole tile every frame. Pixel 4a, downtown Montreal: 47-49 fps at 140
  against 51-53 at 300 at the 50 m scale, and at z20.5 23 fps at 300 against 46 with street names
  hidden.
- `highway-name-path` is filtered to class path with subclass path, bridleway, cycleway or
  pedestrian, and never `indoor`. Liberty names every footway and indoor corridor while their
  lines are hidden: 26 fps at z20.5 in downtown Montreal, 43 with the layer hidden.

#### Route line

- `routeWidth()` is the wider of a pixel curve (7 / 11.2 / 11.5 / 11 / 17 / 20.4 dp at z10 / 14 /
  15 / 16 / 17.5 / 18.5) and `ROUTE_REAL_M` (8.5 m), capped at `ROUTE_CAP_DP` (32). At 8.5 m it
  covers a two-lane street or one carriageway with a little road showing each side.
- Alternates use `ALT_REAL_M` (7.65 m), capped at `ALT_CAP_DP` (30), with their edge
  (`vela-alt-route-edge`) inside that width. Both widths follow `roadWidthScale`.
- Each route piece has an outline layer `<piece>-ol` on the piece's own source: `#0e326a` by day,
  `#061a45` at night, `ROUTE_OUTLINE_DP` (1.25 dp) a side from z14, down to 0.5 dp at z11. All
  outlines sit below the lowest piece, so an outline's round cap never crosses a fill. Alternates
  sit below the outlines.
- `Style.routeSet` copies each piece's visibility and opacity to its outline. The outline's
  gradient is transparent before the piece's driven fraction, so the trail behind the arrow has
  none.
- A Pixel 4a demo drive measures a median 59 fps with road edges and outlines on and off.

#### Rasters

- The traffic raster (`vela-traffic`, opacity 0.6) sits above `building-3d`, else `building`, else
  below the first symbol layer, so footprints do not paint over the congestion colors. With
  satellite on it sits above the imagery.
- It carries a 900 ms `raster-fade-duration`. Google's tiles expire during a drive, and at the
  default 300 ms a replaced tile reads as the whole layer flickering.
- Satellite base imagery (Esri) caps at z19 and MapLibre stretches past it. From z17
  (`SAT_DEEP_PROBE_ZOOM`) `refreshSatDeep` probes Esri's availability index at the view center, 20
  then 21 then 22, stopping at the first missing level. `ensureSatelliteDeep` adds one raster
  layer above the base at the probed level, or Google's imagery to z21 where Esri stops at 19.
  "Use Vela without Google" leaves that fallback out.
- The deep layer cross-fades in, opacity 0 at z18.6 to 1 at z19.6, because the deeper tiles are a
  different capture program. Its minzoom is `SAT_DEEP_MIN_ZOOM` (18.6), so it loads no tiles while
  invisible.

#### Attribution

A tappable "© OpenStreetMap" label sits bottom-left under the scale bar in every map state. It is
lifted over the nav bar, the minimized results bar and the free-drive speed box, and moves into
the map strip in landscape. It opens `openstreetmap.org/copyright`. Never gate it on a chrome
state: the ODbL asks for the credit wherever the map shows. MapLibre's own info button and logo
are off. The satellite credit is a separate centered line, which adds Google where the Google
deep layer shows.

#### Dials

"adb" is `setprop debug.vela.tune.<key>` (`AppTune.local`).

| Dial | Default | Read from | Scales or switches |
| --- | --- | --- | --- |
| `roadWidthScale` | 1.0, clamped 0 to 3 | adb, then calibration | road, route and alternate meters. 0 leaves the base curves |
| `roadEdge` | on | adb, then calibration | road edges |
| `routeOutline` | on | adb, then calibration, at style load | route outlines |
| `roadNameSpacing` | 140 | adb | name spacing up to z16 |
| `roadNameSpacingClose` | 300 | adb | name spacing at z18 |
| `roadNameSpacingMax` | 1500 | adb | name spacing at z21 |
| `openGenericMinProminence` | 4.0 | adb, then calibration | packed-area rule |
| `openGenericHideRank` | 120 | adb, then calibration | packed-area rule |
| `overlayCoverFrac` | 0.18 | calibration | overlay gate threshold (6.4) |

### 6.4 The building-overlay gate

The overlay is occluded overdraw where OSM is dense, so a gate probes rendered OSM coverage per
viewport and shows the overlay only where OSM is sparse. Four rules:

1. Probing is bounded by time. `queryRenderedFeatures` is a synchronous round trip from the main
   thread to the render thread, and idle events can fire per frame. Camera-idle and render-idle
   events mark the verdict dirty and call the gate. The gate probes at most once per
   `OVL_GATE_MIN_GAP_MS` (1.2 s), at z16 and above, and only when the view's cell or integer zoom
   changed or a render finished since the last verdict. Probing straight from an idle event froze
   a Pixel 4a to about 1 fps.
2. Coverage is measured by area, never by feature count: tile generalization merges a dense
   downtown block into two or three giant polygons. Twelve screen points (a 3 by 4 grid) are
   tested against `building` and `building-3d`. A hit fraction of `OSM_COVER_FRAC` (0.18) or more
   hides the overlay.
3. Layers are born hidden and revealed only after a finished render
   (`addOnDidBecomeIdleListener`). A "sparse" verdict before tiles land looks the same as a real
   gap, so it is discarded and asked again. Hiding is always safe at once. A call blocked by the
   time floor schedules one deferred retry, or an uncommitted verdict sticks forever on an idle
   map.
4. One probe per animation frame (`ovlProbeRun`). Run back to back, the 12 probes stalled the main
   thread for 45 to 193 ms on a Pixel 4a. Spread over 12 frames the verdict lands about 200 ms
   later, which nothing waits for. A gate call while a sequence is in flight returns. The time
   floor applies while navigating too, and during a drive the gate probes only while the camera
   follows the car. A detached camera is the user's pan, and the drive's timer (4.x) probes again
   once the camera is back on the car.

Gate state is composable-scoped, because `getMapAsync` can register listeners twice.

---

## 7. Offline data

### 7.1 What a region download contains

`MapViewModel.downloadRoutingGraph` pulls a region as one flow under one progress card:

1. The routing file, `obf/<id>.obf`.
2. The region's place pack, `poipacks/<id>.db` (rules below).
3. The places archive, `places/<id>.pmtiles`, when `MapPoiPrefs.placesWithDownloads` is on
   (default on).
4. The basemap archive, `basemap/<id>.pmtiles`.
5. After the basemap archive: the glyph pack and the world low-zoom basemap, if missing.

The status says "ready" once, after the last piece, or "incomplete" when one failed. A places
or basemap piece that never arrived shows as an update on the region's row (7.3).

Saving an area with the whole-region box checked runs the same download for the smallest
region covering the area's center and also pulls the smallest covering building overlay
(`downloadRoutingForArea`). Address and maxspeed overlays are only streamed. Road features
download per region the first time the map or a route needs them (4.9).

GraphHopper graphs are retired. `LegacyGraphs.purge` deletes `filesDir/graphs` at launch when it
exists and shows one status line asking the user to download those regions again.

| Dataset | Workflow and script | Release | Manifest | Rebaked | On the phone |
| --- | --- | --- | --- | --- | --- |
| Routing `.obf` | `obf-regions.yml`, `scripts/build-obf-region.sh`, `VelaObfShim` | `obf-regions` | `obf-manifest.json` | 90 days | `obf/` |
| Place pack (SQLite) | `poi-packs.yml`, `scripts/build-poi-region.sh`, `poipack_build.py` | `poi-packs` | `poi-pack-manifest.json` | 30 days | `poipacks/` |
| Places archive (PMTiles) | `places-overlays.yml`, `tools/build-places-region.sh` | `places-overlays` | `places-overlay-manifest.json` | a seventh of the catalog daily | `places/`, or streamed |
| Basemap archive (PMTiles) | `basemap-tiles.yml`, `tools/build-basemap-region.sh` (planetiler) | `basemap-tiles` | `basemap-manifest.json` | 30 days | `basemap/` |
| World low-zoom basemap | `world-lowzoom.yml` | `basemap-tiles`, asset `basemap-world.pmtiles` | none (`WORLD_BASEMAP_URL`) | on dispatch | `basemap/world.pmtiles` |
| Grid cells (7.6) | `grid-cells.yml`, `scripts/build-cells-region.sh` | `cells-<region>` | `cells-manifest.json` on `grid-cells` | 30 days | parts in `obf/`, `poipacks/`, `places/` |
| Road features | `road-features.yml`, `scripts/build-road-features.sh`, `road_features_tsv.py` | `road-features` | `road-features-manifest.json` | 30 days | `filesDir/roadfeatures/` |
| Building overlay | `building-overlays.yml`, `scripts/build-overlay-region.sh` | `building-overlays` | `building-overlay-manifest.json` | 90 days | `overlays/`, or streamed |
| Address overlay | `address-overlays.yml`, `scripts/build-address-region.sh` | `address-overlays` | `address-overlay-manifest.json` | 90 days | streamed |
| Maxspeed overlay | `maxspeed-overlays.yml`, `scripts/build-maxspeed-region.sh` | `maxspeed-overlays` | `maxspeed-overlay-manifest.json` | 90 days | streamed |
| ALPR cameras | `flock-cameras.yml`, `scripts/build-flock-cameras.py` | `flock-cameras` | `flock-manifest.json` | Mondays 08:17 UTC | bundled asset, refreshed at launch |
| Glyphs | `scripts/build-map-fonts.sh` | `map-fonts`, asset `map-fonts.zip` | none | by hand | `glyphs/<stack>/<range>.pbf` |

The glyph set is also unpacked to GitHub Pages (`/fonts`) for the online map. The releases
`obf-tools` (the pinned `mapcreator.zip` the obf bakes use), `obf-runtime`, `cronet-runtime`,
`tts-runtime` and `asr-models` hold build and voice files, with URLs in the scripts and code.

A manifest is `{regions: [...]}` with rows `{id, name, url, sizeMb, bbox: [S,W,N,E]}`. Obf,
pack, places and basemap rows add `rev`. Obf rows add `installedMb` and `hh`. Pack rows add
`installedMb`, `updatedAt`, `counts` and an optional `delta`. Places and basemap rows take an
optional `delta`. Road-feature rows are `{id, name, url, sizeKb, bbox, count, updatedAt}`.

#### The place pack of a region

`RegionPacks.packFor` returns the pack with the region's own id, else its parent's. A piece of
a split country or state names the parent in parentheses ("Northern California (California)",
"Bayern (Germany)"). The parent pack's name must match and its box must hold the piece's
center, which keeps Andorra from getting Spain's pack.

- Pieces share the parent pack. It is deleted with the last installed piece that uses it,
  updated through any of them, and counted once in a group's size.
- A shared parent up to `RegionPacks.AUTO_PARENT_MAX_MB` (600, zipped) comes with a piece's
  download. A bigger one (Germany's is 1.9 GB) waits for "Get places", whose row shows the
  parent and its size.
- When a piece has a pack of its own but the parent's is the one installed, the parent keeps
  serving it.
- Saving an area finds the routing region holding the area, then its pack the same way
  (`downloadOfflinePois`). Only an area no pack covers is filled from Overpass.

#### Manifest merges

Each bake job uploads one file per region and emits a manifest entry. A merge job publishes the
manifest.

- Basemap, places and grid cells derive the manifest from the files on the release:
  `scripts/repair-basemap-manifest.sh` (run by `merge-basemap-manifest.sh`),
  `scripts/repair-places-manifest.sh` (run by `merge-places-manifest.sh`) and
  `scripts/merge-cells-manifest.sh`. An existing row is reused only when the size is unchanged
  and the asset was not uploaded after the row's rev. The run's own entries win for the regions
  it baked. A new basemap row's box is read from the archive's first 127 bytes; a places row's
  box comes from `tools/places-regions.json`. The upload retries, and the script lists the
  release again afterward and rebuilds when a file landed meanwhile. These merges have no
  concurrency group: GitHub cancels a job pending in a group when a newer one joins, after its
  archives are already uploaded.
- Routing, place packs, road features, buildings, addresses and maxspeed replace rows by id in
  the existing manifest, serialized by a concurrency group (`obf-regions-manifest`,
  `poi-packs-manifest`, `road-features-manifest`, `building-overlays-manifest-merge`,
  `address-overlays-manifest-merge`, `maxspeed-overlays-manifest-merge`).
- An address source whose rows carry no house number fails its region with that message
  (`build-address-region.sh`), and the published overlay stays. Delaware's source has been
  that way since October 2026.

#### Infrastructure releases

Every tag that does not start with `v0.` is file hosting whose assets exist nowhere else. A
cleanup that deletes or edits releases selects by the tag pattern `v0.*`, never by the
prerelease flag or age. Anything that lists releases pages through them or bounds by tag,
because the repository holds hundreds.

### 7.1a Where the files live

`offline/StorageLocation` roots the folders in `StorageLocation.FOLDERS` (`obf`, `poipacks`,
`places`, `basemap`, `overlays`, `glyphs`, `cells`) at `filesDir` or at the app's folder on a
removable SD card (`getExternalFilesDirs`, no permission). Pref `offline_storage` is `internal`
(default) or `sd`. The choice is in Settings > Offline maps and shows only when a card is
mounted or the card is the chosen location.

- Every store reads the root on each access. `:core`'s `ObfRouteEngine` reads it through
  `OfflineRoot.dir`.
- With the card chosen and missing, the root is internal storage.
- Voices, speech models, road features, the sprite and caches stay internal.
- The obf and PMTiles stores keep `index.json` (id and box) and `revs.json` (installed rev)
  beside the files. Packs keep `revs.json`. PMTiles stores also keep `dead.json` (7.3).

`MapViewModel.moveOfflineStorage` refuses during a download, an update or a drive, and when
the target lacks the data's size plus 64 MB. It closes the routing readers, the place packs and
the map's archive sources, copies with a length check per file, and deletes the source only
after every file copied. A failed copy removes the partial destination. MapLibre's database
moves in two steps (point MapLibre at a temporary folder, copy `mbgl-offline.db`, reopen at the
destination), because MapLibre's path change opens a new database and carries nothing over.

`adb shell setprop debug.vela.sdtest true` makes the shared-storage app folder stand in for a
card.

#### Deleting

- A region (`deleteRoutingGraph`): the routing file, the pack unless another installed piece
  uses it, the region's cells, the places and basemap archives of the same id, and any archive
  whose box center lies inside the box `routingRegionBox(id)` returns.
- A saved area: MapLibre removes the area's rows, then `OfflineMaps.packDatabase`
  (`OfflineManager.packDatabase`, a VACUUM) runs. MapLibre keeps saved areas and the browsing
  cache in one SQLite file, and a delete alone frees no bytes. Clear map cache packs too.
- "Delete all offline data" (`deleteAllOfflineData`, behind a confirm naming the total): every
  saved area, routing file, pack, places and basemap archive, building overlay, cell, the road
  features, the glyph pack and any legacy graph folder. It then sweeps the store folders for
  files no index reaches (keeping `index.json`, `revs.json`, `dead.json`), clears the browsing,
  place and Street View caches and packs the database. Voices and speech models stay.

Settings > Offline maps reports storage through `offlineStorageBreakdown`. The map figure
counts MapLibre's database, `overlays/`, `basemap/`, `glyphs/` and `roadfeatures/`. "Offline
places" counts `poipacks/` and `places/`.

### 7.2 Catalog and selection

#### Catalogs

| File | Rows | Bakes |
| --- | --- | --- |
| `tools/routing-regions.json` | Every Geofabrik country-level extract, US states, Canadian provinces, and first-level sub-areas of the countries Geofabrik divides: `id`, `name`, `group`, `pbf_url`, optional `big`, `skip_obf` | obf, place packs, road features, basemap, maxspeed, grid cells |
| `tools/places-regions.json` | `id`, `name`, `bbox` (the OSM extract is matched by id in the routing catalog) | places |
| `tools/overlay-regions.json` | Groups `us`, `world`, `chunk`; rows carry `qkprefix` where relevant | building overlays |
| `tools/address-regions.json` | One row per OpenAddresses source | address overlays |

A dispatch takes region ids, a list of groups, or `all-sub` (every `<country>-sub` group). A
matrix holds at most 256 jobs, so catalog-wide bakes run as shards or group sets. `skip_obf`
marks whole-country rows too large for the obf bake. Their sub-area rows cover them, and the
obf and cells bakes skip them. After adding a catalog row, run `scripts/region-polys.py`.

#### The routing file bake

`scripts/build-obf-region.sh` indexes a roads-only extract (`bake_obf_filter`: highway ways,
ferry and shuttle-train routes, turn restrictions, route relations) with `VelaObfShim` at
`JAVA_HEAP` 12g, the most a 16 GB runner allows. It then adds the highway hierarchy
(`bake_obf_hh`, 4.5). A region where that step fails ships without it. The file is served raw,
so `sizeMb` equals `installedMb`.

Indexing holds every node of its input at once. A roads-only extract over `OBF_SPLIT_MB`
(250 MB) is cut into strips of at most that size across its longer side with
`osmium extract --strategy smart`, which keeps a road or turn restriction that crosses a cut
whole in both strips. Each strip is indexed alone and `BinaryInspector -c` joins them into one
file. `OBF_SPLIT=<n>` forces n strips and `OBF_OUT=<file>` keeps the result unpublished.
Delaware forced into 3 strips matched the unsplit file on the `ObfCellsProbeTest` trips in
distance, time and step count.

#### Which region covers a point

`RoutingRegion.covers` and `PmtilesRegionStore.Region.covers` test the region's boundary
polygon (`RegionPolys`, `assets/region_polys.json`) and fall back to the box for an id with no
polygon. A box wider than `WORLD_SPAN` (350 degrees) and shorter than `WORLD_BAND` (120
degrees) never covers by itself: it is an extract crossing the antimeridian. Among covering
regions the smallest box wins.

The polygon file is baked by `scripts/region-polys.py` from the Geofabrik `.poly` beside each
extract, simplified to about 5 km, and loaded once at app start. Every region-for-a-point
decision goes through these two functions: downloads for the view, the streaming unions, the
routing offer, updates, the saved-area pack lookup, the road-features region and the Offline
settings row.

A region download pulls the places or basemap archive with the region's id (`archivesFor`).
Without one it pulls every archive whose box center lies inside the region, and without any of
those the smallest archive covering the region's center.

#### Places layer

`PmtilesRegionStore.sourcesFor(center, manifestUrl, view)` uses installed archives only while
one of them holds the center. It then mounts every installed archive whose box touches the view,
minus any archive nested inside another mounted one, nearest to the center first, at most
`MAX_MOUNTED` (8). Two nested archives would draw the overlap twice. `Pick.rev` is the oldest
mounted rev. With the center outside every installed archive, the smallest manifest region
covering the center streams.

#### Basemap, online

Online the map always streams OpenFreeMap and `pickBasemapArchive` mounts no file. Mounting one
online reloaded the whole style at every edge of the downloaded data.

`offline/LocalBasemapTiles` is an interceptor in MapLibre's HTTP client
(`HttpRequestUtil.setOkHttpClient`, installed after `MapLibre.getInstance`). It answers a
request for an OpenFreeMap tile from an installed region file when the file is baked to
`FULL_MAP_ZOOM` (14) and the tile's four corners, padded by `EDGE_PAD_DEG` (0.05 degrees), lie
inside that region's polygon. The shipped polygons are simplified to about 5 km, which the pad
absorbs. Border tiles, shallow bakes and the world file stream.

#### Basemap, offline

Offline here is `offline || !isValidated()`: a network that never reached the internet counts
as offline. `BasemapTileStore.installedFor(center, mounted, corners, keepMounted = true)`
returns the first of:

1. The mounted archive, while its roads reach the center tile, one of the eight tiles around it
   or a viewport corner.
2. The smallest installed archive whose box holds the center or a corner and whose roads reach
   one of those tiles.
3. An archive in view that the probe could not read.
4. The world archive.

The probe is `PmtilesReader.hasRoads` at `COVERAGE_PROBE_Z` (12): whether the tile carries the
`transportation` layer. Tile presence cannot answer, because a bake emits tiles across its
whole box from planetiler's global base data. Definite answers are memoized per archive and
tile. The pick runs off the main thread on camera idle and after every download or delete.

- The world archive (`BasemapTileStore.WORLD_ID`, z0-7, about 11 MB) holds Natural Earth water,
  coastlines, boundaries and place labels and no roads. It is kept out of the candidate list
  and pulled once with the first basemap download (`ensureWorld`).
- A shallow archive (max zoom under `FULL_MAP_ZOOM`, read from byte 101 of the PMTiles v3
  header) is used only offline. The bake drops one zoom level when a region would pass the
  2 GiB release asset limit.
- The mounted archive is part of the style key, so a swap reloads the style. Swaps keep a floor
  of `BASEMAP_SWAP_COOLDOWN_MS` (2 s), and a newer camera idle cancels a pending pick.

#### Overlays

Building and address overlays stream from up to the three smallest covering regions, because
a neighbor's smaller box can cover a point its data does not reach. Installed building
overlays are always mounted, and when one of the three is installed the others are not
streamed. The maxspeed overlay is not mounted where an installed region or cell answers the
limit on the phone.

### 7.3 Freshness

A manifest row's `rev` is an integer that only grows. The obf, basemap, places and cells bakes
stamp the UTC bake date as `YYYYMMDD`. Place packs count up, one past the live manifest's rev
for that region, which is the `fromRev` their deltas key on. A road-features file is
downloaded again when the manifest's `updatedAt` differs from the stored stamp.

`MapViewModel.refreshRegionUpdates` lists each installed region's update kinds: `routing` (a
newer rev than the installed one), `places` and `map` (a newer archive whose box center lies
inside the region, or one the region should have and lacks). The row's Update button
(`updateRegion`) refreshes the pack when its rev is newer, then the places and basemap
archives, then the routing file. Routing files have no patches and download whole.

#### Place pack deltas

`poipack_delta.py` writes, per table, the rows to delete and to insert (`del_<table>`,
`ins_<table>`, one SQL EXCEPT each). The bake publishes `<id>.delta.zip` and
`delta: {fromRev, url, sizeMb}` only when the delta is under half the full zip.
`PoiPackStore.applyDelta` runs when the installed rev equals `fromRev`. In one transaction it
deletes by full-row match (NULL-safe `IS`, through each table's index), inserts, and checks
every table's row count against the manifest's `counts` before committing. A delta that fails
falls back to the whole pack.

Tables: `poi(id, name, lat, lng, category, address, phone, website, hours)`,
`streetname(sid, street, street_norm)`, `addr(hn, sid, city, lat, lng)`,
`streetpt(sid, lat, lng)`; `PRAGMA user_version` is 2. `sid` is the SHA-1 of the normalized
street name truncated to a positive 63-bit integer, and a collision fails the build. A counter
would renumber millions of rows on a rebuild and make the delta as large as the pack.
`TABLE_COLUMNS` in `PoiPackStore`, `poipack_build.py` and `poipack_delta.py` must stay in step.

#### Archive patches

A week of edits moved 1.3% of Kentucky's tiles (3.2% of its bytes, a 4.4 MB patch against a
183 MB archive), so places and basemap rebakes publish patches. The producer
(`scripts/pmtiles-make-patch.py`) and the reference applier (`pmtiles-apply-patch.py`) share one
reader (`velapmtiles.py`). The phone's applier is `offline/PmtilesPatch` (magic `VELAPTCH`,
version 2).

- Format. The patch carries the new directory's tile ids, run lengths and tile lengths, plus
  one flag per entry: the tile rides in the patch, or the archive already holds it under that
  id. It names no offsets. The applier resolves entries against its own file and reads the
  tile-data offset from the archive's own header, so a patch applies to an archive that earlier
  patches or a compaction rearranged.
- Apply, in place. Append the changed tiles, append the rebuilt directory, sync, verify, then
  rewrite the 127-byte header last. An interrupted apply leaves the old archive intact, and the
  free space needed is the patch's size. The result is unclustered, which MapLibre reads and
  `pmtiles verify` rejects.
- Verify. The fingerprint is SHA-256 over sorted tile ids, run lengths and tile hashes
  (Android has no blake2b). It is computed against the new directory while the header still
  describes the old archive. A mismatch truncates the file back and the caller downloads the
  region whole.
- Publish (`places-overlays.yml`, `basemap-tiles.yml`). The bake diffs against the published
  archive, applies the patch to a copy and checks the fingerprint. It uploads
  `<places|basemap>-<id>.<fromRev>.vpatch` and adds `delta: {fromRev, url, sizeMb}` to the row
  only when that passes and the patch is under a third of the archive. A bake with the same rev
  as the published one makes no patch; the `rev` input overrides the date stamp. A rebake after
  a bake-script change usually fails the one-third test. `scripts/archive-churn.py`
  (`places-churn.yml`) measures the churn between two bakes of one script.
- Dead space. A patch leaves the replaced tiles behind, and `dead.json` holds those bytes per
  archive. Past a fifth of the file (`DEAD_LIMIT_DIVISOR` 5) `PmtilesCompact` rewrites the
  archive in tile-id order to a temporary file, checks its fingerprint against the original and
  swaps it in. The result is the published layout (header, directory, metadata, clustered tile
  data, no leaves) and needs room for a second copy. Past half the file in dead space
  `updateWithDelta` refuses the patch and the region downloads whole.
  `scripts/pmtiles-compact.py` is the same code: a patched 3.36 MB places archive compacted to
  3,259,268 bytes against 3,259,285 for a fresh download of that rev, same fingerprint (the
  17 bytes are the leaf directory a fresh bake writes).
  `adb shell setprop debug.vela.compact true` compacts after every patch. `PmtilesCompactTest`
  runs a fixture from `scripts/pmtiles-test-fixture.py` through the real producer and applier.

#### Automatic updates

`app.vela.ui.RegionUpdates`, pref `region_update_mode`: `off`, `wifi` (default) or `mobile`.
`wifi` means an unmetered network as the system reports it.

`maybeAutoPatch` runs `AUTO_PATCH_DELAY_MS` (1 min) after start, every `AUTO_PATCH_POLL_MS`
(3 h), and `AUTO_PATCH_NET_DELAY_MS` (15 s) after a validated network appears. It works at most
once per `AUTO_PATCH_EVERY_MS` (20 h; stamp `region_autopatch_at`, written only after a
manifest was read) and never during a drive. It applies every published patch whose `fromRev`
matches an installed places archive, basemap archive or pack, and pulls installed grid cells
with a newer rev again. An archive is never downloaded whole automatically. A pack whose delta
fails to apply is.

A tapped Update tries the patch first, then downloads the whole file over the installed copy.
Every attempt is recorded in the diagnostics ring (kind `delta`) and logcat `VelaDelta` with the
bytes and the reason for any fallback.

The progress card names the stage (`MapUiState.updateStage`): 0 a download with its percent;
1 an update being checked and written, indeterminate because neither the pack transaction nor
the fingerprint pass reports progress; 2 the whole file after an update that could not be
applied.

#### Manifest caches

`PmtilesRegionStore.manifest` runs on every camera idle. It caches a fetch for
`MANIFEST_TTL_MS` (1 h), so a long-lived process still sees a new rev, and a failed fetch for
`MISS_MEMO_MS` (10 min). `CellStore` caches both for 10 min. `RegionCatalog.manifest` keeps the
last obf catalog on disk, so the Offline maps page lists regions with no signal.

#### Bake schedule

The Actions token has 1,000 API requests an hour for the whole repository. Overlapping bakes
spend it and fail other workflows with HTTP 403, so the bakes have no crons of their own.
`bake-conductor.yml` (hourly at :05) runs `scripts/bake-conductor.py` over
`tools/bake-schedule.json`. Each hour it:

1. Settles the run it started last. Success marks the job fresh. Failed regions are retried
   alone (a dispatch with the job's `retryInput`, at most `maxRetries` 3 per cycle). A run whose
   only failure is a non-region job is rerun with `gh run rerun --failed`.
2. Publishes a staged manifest when its checks pass (below).
3. Starts at most one bake: a pending retry first, else the most overdue job. It starts nothing
   while a scheduled workflow is running or queued or with fewer than `reserve` (400) requests
   left, and does nothing at all under 60.

Its state is `state.json` on the `bake-conductor` release, and its run never fails. Cadences
(`everyHours`): a seventh of the places catalog every 24 h (`slice` is the UTC weekday); place
packs, road features, basemap and grid cells every 30 days; routing files, buildings, addresses
and maxspeed every 90 days. ALPR cameras keep their own weekly cron.

The routing bake merges into `obf-manifest-staging.json` (`staging=true`), which the app never
reads. `flip()` copies it over `obf-manifest.json` once all three routing jobs finished a clean
cycle (every region baked, retries included) and staging passes its checks: no live region
missing, no rev going backward, every row's file on the release, something newer. The old live
manifest is kept as `obf-manifest-previous.json`. A cycle that gave up with regions missing
never flips.

Every bake downloads its extract through `scripts/fetch-pbf.sh`: a plain download, then the
redirects walked one hop at a time with a trailing slash dropped from a file name, then the
newest `<region>-YYMMDD.osm.pbf` in the folder listing. This survives a mirror that redirects
`-latest.osm.pbf` in a circle.

### 7.4 Download discipline

- Every multi-megabyte download runs through `MapViewModel.downloadLaunch(label)`. The work
  runs on the app-lifetime `DownloadWork.scope` and the refcounted `dataSync` foreground service
  (`DownloadService`) keeps the process alive. `viewModelScope` is canceled when the task is
  swiped away. The area tile save is MapLibre's own download and holds the service directly.
- Every large download uses a client derived with `callTimeout(0)` and a 60 s read timeout
  (120 s for the Overpass address body). The shared client's 12 s call timeout aborts a large
  body and the error is swallowed. Manifest fetches stay on the shared client.
- A download lands in a temporary file, is checked (PMTiles magic and more than 127 bytes,
  `SQLite format 3`, an obf over 1024 bytes) and is renamed over the installed copy, so a failed
  update keeps the old file.
- Every download can be canceled. Store functions take `active: () -> Boolean`, polled per
  chunk. The view model holds per-kind cancel flags, reset at each start, and a cancel is not
  reported as a failure. The area tile save cancels by detaching the observer, setting the
  region inactive and deleting the partial. Any UI that shows progress shows a cancel.
- Whole-parent downloads queue their pieces (`regionQueue`) and run one at a time. Cancel
  clears the queue.
- Sizes shown are installed sizes: the manifest's `installedMb`, else the download size for an
  obf and the zip times 2.35 for a pack, plus the region's places and basemap archives
  (`regionExtrasMb`). A region over `CONFIRM_MB` (1024) installed confirms first.

#### Offline detection

`MapViewModel.observeConnectivity`, `core/net/NetHealth`.

- The offline flag latches only when the system has no usable network and none of Vela's own
  requests got a response within `NetHealth.FRESH_MS` (15 s), or one has failed to reach its
  host since. The check waits at least 3 s and until that window ends.
- Any HTTP response clears the flag at once (an interceptor first on the shared client and
  after the local-tile hook on MapLibre's).
- The system check uses the callback's last default network when `getActiveNetwork()` returns
  null, which Android does for an app whose access is blocked (backgrounding, doze, standby).
  `onBlockedStatusChanged` and every activity resume re-check.
- Tapping the offline marker calls `recheckConnectivity`: `reportNetworkConnectivity`, one HEAD
  to the calibration host (no Google contact), then a status line.

### 7.5 Offline basemap rules

Each rule prevents a blank map.

1. `withLocalBasemap` removes the `openmaptiles` source from the style JSON and points its
   layers at `vela-basemap`. The archive is added under that id after the style loads, and the
   layers are re-attached. A source declared in the JSON or handed to the style builder never
   gets past the z0 tile.
2. A labeled tile completes only once every glyph range and the sprite resolve, and with no
   signal a remote host never answers. Both are served from `file://`: glyphs from the pack in
   `glyphs/` (`GlyphPackStore`, `PACK_VERSION` 2; an older pack is replaced once per run on an
   unmetered validated network), the sprite from `filesDir/sprites/`, copied from the APK. The
   new pack is unzipped to `glyphs.new` beside the old one and swapped in by rename, with the
   old pack moved to `glyphs.old` first and put back if the swap fails: a rename from the
   cache folder fails when the maps are on the card.
3. A process that starts offline on the remote style leaves the engine's shared glyph and
   sprite managers with requests that never answer, for every later style. The archive is
   therefore picked before the first style load (`refreshBasemapArchive(seed)` at init).
4. Every helper that reads the basemap source goes through `basemapSrc(style)`, or the offline
   map comes up light and bare.

### 7.6 Grid-cell downloads

A catalog region is also published as grid cells, so the area picker can download the part of
a region a frame touches. The bake is 7.6.1 to 7.6.4, the app side 7.6.5.

#### 7.6.1 Grid

Cells are the tiles of one global grid at `STEP` degrees (`cells_grid.py`, 0.5 by default),
each clipped to the region's header box (clamped by `clamp-bbox.py`). A tile with no part of
the region's polygon in it is not a cell, and a cell with no road and no pack row is dropped.

The key is the tile's SW corner, fixed width: `[ns]DD.D[ew]DDD.D`, for example `n38.5w075.5`.
The cell id is `<region>.<key>` (`delaware.n38.5w075.5`); region ids hold no dot, so the first
dot splits them.

A release holds 1000 assets, the zips plus the fragment. A region with more than `MAX_CELLS`
(999) cells is regridded at double the step until it fits (`CELL_STEP` sets the start; past
8 degrees the bake fails). The app never assumes a cell size: every cell carries its box.

#### 7.6.2 Bake

`scripts/build-cells-region.sh <region> [local.pbf]`. Shared steps are in `scripts/bake-lib.sh`,
which the obf and pack bakes also source.

1. The extract is cut to the union of the obf and pack tag filters (`BAKE_OBF_EXPR`,
   `BAKE_PACK_EXPR`). `osmium extract -s complete_ways` then writes `CELLS_BATCH` (4) cells per
   pass. A way crossing a cell edge is whole in both cells. osmium's id sets take about 2 GB
   per dense cell (Northern California: one dense cell peaks at 3.7 GB, five at 11.8 GB), so
   4 per pass fits a 16 GB runner.
2. Per cell, `CELL_JOBS` at a time (default 2, CI 3): the routing obf (`bake_obf_filter` and
   `VelaObfShim`, heap `CELL_HEAP`, default 3g, CI 4g; skipped with no highway way) and the
   place pack (`bake_pack_filter` and `poipack_build.py`; skipped with no POI, address or
   street-point row).
3. The places slice is `pmtiles extract --region` of the region's places archive, with the
   region polygon from `app/src/main/assets/region_polys.json` clipped to the cell. The places
   archive is baked by box, so a box cut would put the neighbor's places into border cells. A
   region with no polygon is cut by the cell box. Low-zoom tiles overlap cells, so slices
   repeat some tiles.
4. `<cell-id>.zip` holds `<cell-id>.obf`, `<cell-id>.db` and `places-<cell-id>.pmtiles`, each
   only when present. The obf and PMTiles entries are stored, the pack deflated.

#### 7.6.3 Hosting and manifest

One release per region, tag `cells-<region>`, holds the zips and the fragment
`cells-<region>.json`. It is created with `--target` the repository's root commit
(`CELLS_RELEASE_TARGET` in `scripts/bake-lib.sh`). GitHub sorts releases by the target commit's
date and Obtainium reads only the first 100, so hundreds of data releases created on HEAD
would push the app's releases off that page. `promote-stable.yml` and `fdroid-repo.yml` page
through the listing. `cells-manifest.json` on the `grid-cells` release indexes every region:

```json
{ "version": 1,
  "regions": [ { "id": "delaware", "name": "Delaware (state)", "rev": 20260927,
                 "cells": [ { "id": "delaware.n38.5w075.5", "bbox": [38.5, -75.5, 39.0, -75.0],
                              "url": ".../releases/download/cells-delaware/delaware.n38.5w075.5.zip",
                              "sizeMb": 10.81, "installedMb": 15.94, "rev": 20260927,
                              "parts": ["obf", "pack", "places"] } ] } ] }
```

`bbox` is `[S,W,N,E]` of the clipped cell. `sizeMb` is the zip and `installedMb` the unpacked
parts, decimal MB rounded up to 0.01. `rev` is the bake date, and a region's `rev` is its
newest cell's. `parts` names what the zip holds (`obf`, `pack`, `places`); without it the app
assumes all three. The fragment has the shape of a region row.

`scripts/merge-cells-manifest.sh [fragments-dir]` derives the manifest from the releases (7.1)
through one paginated release listing. Per `cells-*` release it takes the fragment's rows,
narrowed to cells whose zip is on the release, with `sizeMb` from the listing. A zip with no
fragment row gets its box from its key, as a 0.5 degree cell, and its rev from its upload date.
The run's own fragments win for their regions. The upload retries five times, 5 to 19 s apart,
and the merge lists the releases again and rebuilds, up to three passes, when an asset landed
meanwhile. `DRY_RUN=1` writes the manifest locally.

#### 7.6.4 Upload and workflow

Zips go up 100 per `gh release upload` call and the fragment last, so the merge never lists a
cell whose zip is missing. One request per asset bounds a catalog-wide bake at about a thousand
cells an hour.

- `rate_wait` stops at `RATE_RESERVE` (200) requests left and waits for the reset, up to an
  hour. The reserve is for the repository's other workflows.
- An upload refused for the rate limit backs off 5, 10, 20, then 30 minutes a try
  (`RATE_BACKOFF`, eight tries). The release's view and create go through the same backoff
  (`ensure_release`), because a refused view reads as "no release" and the create then fails.
- Other workflows' GitHub calls go through `scripts/gh-retry.sh` (`gh_retry`: 60 s doubling to
  15 min, `GH_RETRY_TRIES` 6).

`grid-cells.yml` is dispatch only and bakes four regions at a time. Inputs: `regions`, `group`
(or `all-sub`), `all`, and `shard` a or b. An empty selection bakes nothing, and `skip_obf` rows
are skipped. The merge job has no concurrency group.

`ObfRouteEngine` hands the router every installed file whose box intersects the trip's padded
box, so a trip over several cell files routes like one over the region file.
`ObfCellsProbeTest` (`-DvelaCells=<dir with cells/ and whole/>`) checks four Delaware trips
across cell edges: the distance is within 2% of the whole-region file's.

#### 7.6.5 App side

`offline/CellStore` reads the manifest (`BuildConfig.CELLS_MANIFEST_URL`, override
`-PcellsManifestUrl`) and installs cells. A cell's parts go into the stores a region download
fills, under the cell's id: `ObfStore.installFile`, `PoiPackStore.installFile` (SQLite magic
check) and `PmtilesRegionStore.installFile` (PMTiles magic check). Routing, offline search and
the places layer (`sourcesFor`, 7.2) therefore read a cell with no cell-specific code.
`cells/index.json` lists the installed cells (id, region, name, box, rev, installed MB) so the
Downloaded group can show and delete them per region.

`areaDownloadPlan` fills `AreaPlan.cells` with the region's cells whose box intersects the
frame and are not installed, and `cellsMb` with their `installedMb` sum. Both are empty where
the whole region is installed or the region has no cells. The card shows a cells checkbox above
the whole-region one. The two exclude each other and cells are the default. Unticking cells
leaves both off: the whole region is ticked by hand.

`downloadPickedArea(withCells = true)` runs `downloadCells`: one cell at a time under the region
download card (`routingDownloadingId` = `CELLS_DOWNLOAD_ID`, name "<region>, part k of n"),
canceled by the card's Cancel. A canceled or failed cell leaves the earlier ones installed and
the status says so (`mapvm_cells_incomplete`). The zip streams through `ZipInputStream`. Each
entry is staged in `cells/<id>.tmp/` and handed to its store. A part that fails to install is
dropped, and the cell is listed when at least one part landed, so it can always be deleted.
Progress is the zip's bytes.

When a region download completes with its pack and places archive, the region's installed
cells are deleted. Otherwise offline search answers from the cell pack and the region pack and
lists every place twice. The files must be on the phone (`regionPlacesInstalled`): the download
steps also report success when nothing is published for the region, when its pack is a large
shared one that does not ride along, and when a manifest cannot be read.

`deleteCellRegion(regionId)` removes every cell of the region from all three stores.
`deleteRoutingGraph(id)` and "Delete all offline data" remove cells too (7.1a).

`refreshRegionUpdates` compares every installed cell's rev with the manifest's (`newerCells`)
into `MapUiState.cellUpdates`. The Downloaded row says how many pieces have a newer version and
its Update pulls them again whole (`updateCells`). `maybeAutoPatch` does the same under the
update setting (7.3).

---

## 8. Transit

Google plus Transitous (MOTIS over GTFS and GTFS-Realtime, `core/data/transit/Transitous.kt`).

| Piece | Source |
| --- | --- |
| Departure boards | Transitous: every route at a stop, realtime lateness, agency colors. Google's place page, which embeds as little as one route at a hub, is the fallback. |
| Stop icons | Transitous positions from z15; OSM basemap icons without coverage. A tapped icon opens its board by stop id. |
| Route stop list | The run's own stops (`/trip` by the tapped run); the Google itinerary as fallback. |
| Directions | Google's page, for traffic-aware times; `Transitous.plan` when Google is off or answers nothing. |

#### Lines on the map

"Highlight transit lines" draws a plain highlight from the basemap's `transportation` layer
(class `rail` one color, class `transit` another) and above it each line's own color from
`/api/experimental/map/routes` (`Transitous.linesInBox`). The plain highlight hides for a kind
(metro, train) while colored lines of that kind are in view. Both hide while a non-transit route
is on screen (`nonTransitRouteUp` in `MapSurface`): orange and red lines read as traffic.

- The endpoint has no mode parameter. The phone drops buses, coaches and boats, cuts a shape at
  every gap over `CHORD_SPLIT_M` (4 km; a subway bridge is one hop of 2 to 2.5 km), drops a run
  whose points average over `CHORD_MAX_M` (700 m) apart as a chord, and thins the rest to 4 m.
  An unreadable reply is "no lines".
- The request's `zoom` is 8 (long-distance and regional rail) under `TRANSIT_LINES_METRO_ZOOM`
  (10.5), 12 from there. Nothing is asked under `TRANSIT_LINES_MIN_ZOOM` (8), on a constrained
  link, or during a drive.
- Lines come per grid cell: `TRANSIT_LINE_CELL_METRO_DEG` (0.05) at subway level,
  `TRANSIT_LINE_CELL_RAIL_DEG` (0.5) for rail only. A view padded by a quarter takes at most
  `TRANSIT_LINE_CELLS_PER_VIEW` (24), nearest first, three at a time, each drawn as it lands;
  `TRANSIT_LINE_CELLS_KEPT` (96) stay in an LRU. A line returned by two cells draws once. Pixel
  4a over Midtown Manhattan: a four-step zoom-out takes 19 cells in 4.5 s.
- Lines sharing a stretch draw as side-by-side strands, one per color, at most 4.
- Metro stretches carry their lines' letters (`MapLine.labels`: route `shortName` and color) from
  z13 on `TRANSIT_LABELS_LAYER`, below the business icons: "6X" folded into "6", numbers before
  letters, at most six.
- A stretch gets one pill per line color, filled with it and rimmed in the map's land color
  (`ensureTransitPill`), in both themes. Letters sit two spaces apart in black or white by
  contrast (`transitInk`: WCAG ratio, white favored 1.6x). Several colors split the stretch
  equally, a pill at the middle of each part (`lineSlice`).
- Settings (`TransitLayer`): lines metro / trains, stop icons bus / metro / train, all on by
  default. A stop's kinds come from its Transitous `modes`.

#### Stops and boards

- Boards refresh every 30 s while the sheet is open. Google-fallback boards are one-shot: a
  refresh there is a whole page load.
- A multi-bay center merges through its parent station.
- `Transitous.mergeDirectionalPairs` folds same-name stops within `PAIR_MERGE_M` (160 m) to their
  midpoint with the other ids as siblings, because US GTFS names both curbs alike and has no
  direction field. The board merges stop times across siblings, fetched in parallel; its
  `(route, headsign)` rows keep the directions apart. Direction-suffixed names never merge.
- "Same name" is `Transitous.stopKey`: case, ordinals, "&" / "/" / "at", street-type and compass
  abbreviations and cross-street order do not count.
- Before that pass, stops within `COLOCATED_M` (3 m) fold whatever their names: one corner in
  several feeds, or a station complex's several parents. An all-caps merged name shows in title
  case (`Transitous.displayName`). Midtown Manhattan: 78 icons become 55.
- Where Transitous stops are drawn, the basemap's OSM bus icons hide by filter (rail and airport
  stay).
- A looping run boards at the tapped lap. Some agencies publish a day of laps as one trip (a
  Davis Unitrans line: 589 stops, 6:55 AM to 9 PM). `Transitous.buildTripStep` treats every stop
  within `LAP_SAME_STOP_M` (30 m) of the nearest as a pass, boards at the pass nearest the tapped
  departure time (the first without one), and shows one lap.
- `TransitStopCache`: each successful viewport fetch overwrites its area in a 24-area on-disk
  LRU, so visited areas keep their stops offline.
- `TransitBoardCache`: the newest 48 boards on disk, keyed by stop coordinate to about 10 m, with
  a 40 m near-match. Offline, a stop shows its last board and when it was seen
  (`stopDeparturesCachedAt`). A live board replaces it.
- The board fetch is gated on category: multilingual gate words must match and exclusion words
  (fuel, EV, emergency, broadcast) must not, because "station" is in all of them. Both regexes
  are remotely overridable.
- A tap the basemap class marks as transit, with no Google stop listing, gets a Transitous board
  by proximity.
- A transit-named place that resolves to an "Intersection" re-resolves to the co-located stop:
  search `<name> bus stop`, then `bus stop`, nearest live transit listing within 250 m (a real
  pair measured 89 m).

#### Directions

- `WebDirectionsFetcher` loads Google's `maps/dir` page in a hidden WebView, in the app's
  language (the reviews page's rule). Dial `transitAppLanguage` 0 returns every install to
  English.
- Its `data` is `!4m2!4m1!3e3`, or with n option entries `!4m{n+3}!4m{n+2}!2m{n}!<entries>!3e3`:
  the `!4m` wrappers are descendant counts. Entries in order: route preference `!4e{v}` (2 fewer
  transfers, 3 less walking, nothing for best), preferred vehicles `!5e{k}`, the time block.
- `!8j` in the time block is a local clock: Google reads the seconds as wall-clock-as-UTC, so
  the phone's zone offset is added.
- The payload is taken up to 6,000,000 characters (a long intercity trip in Japanese passes
  1.5 MB). A cut payload does not parse, and the open planner's trips show instead.
- Times are formatted from each tuple's epoch and zone, not the page's clock text.
- A line is its badge, or an agency icon filename when the badge has no text (subway bullets). A
  line icon is operator-scoped and contains a slash; the generic vehicle icon is bare.
- Mode comes from icon filenames only: mode words in nearby text also occur in place names.
- A ridden leg in Japan has no generic vehicle icon. Its operator icon's path decides:
  `shinkansen` a train, `metro` or `subway` a subway, `jp2ltr` or `jp-jr` a train, any other a
  generic ride, never a walk.
- In a trip summary an operator icon entry followed directly by a text entry is one line. The
  icon's file name is the line's name only when no text follows.
- `trip[13]` is `[seconds, "10 min", seconds]` where the service has a headway; the card shows
  "Every 10 min".
- `Transitous.plan` (`/api/v1/plan`) asks for up to 5 itineraries, with `arriveBy` for arrive-by
  and last-available and `transitModes` from the vehicle chips. Its reply parses into the same
  itinerary shape (walk and ride legs, lines with agency colors, board and alight stops with
  codes and realtime-vs-timetable times, headsigns), so the chooser, the map drawing and guidance
  are unchanged. Walk legs carry no distance text; their steps come from the walk router on
  demand. `TransitOrder.byPreference` applies the route preference the planner lacks.
- Durations Vela formats itself, the planner's included, use the platform's short units in
  every language but English ("6 h 16 min").
- In the chooser an expanded row frames its legs; with none expanded the camera frames the
  trip's own points (start, stops, destination). With the Google-style picker on, the transit
  tab keeps the classic body under the picker's header.
- The preview draws a ride along its real path where one is known, else straight lines through
  its stops. Paths are looked up when a trip is expanded (at most five rides, never offline or on
  a constrained link) and held in `ui/map/TransitShapes`. `Transitous.legShape` takes the open
  planner's leg geometry for a ride whose two ends are within 300 m of the stops, and sends no
  `maxTransfers`: the public instance answers nothing with `maxTransfers=0`. A bus without a
  path takes the road route through its stops when that is at most 1.8 times their
  straight-line chain.

---

## 9. Voice, dictation and language

### 9.1 Spoken guidance

Guidance text comes from per-language `NavStrings` tables in `:core/i18n`, switched by
`NavStringsRegistry`. Both routers feed them: `RouteGeometry.osrmPhrase` and
`OfflinePhrases.phrase` map maneuvers onto the OSRM `(type, mod)` pair.

Speech defaults to an in-process neural voice: the bundled sherpa-onnx runtime with a downloaded
Piper model (`PiperSynth` behind the `:core` `NeuralSynth` seam). About 40 catalog voices install
to `filesDir/piper/<id>/`; the installed set is derived from the filesystem, so a partial
download self-heals. The selection is `voice_model`, the speaker per voice `voice_speaker_<id>`.

- R8 must keep `com.k2fsa.sherpa.onnx.**`: JNI resolves classes by original name.
- `app/voice/PlaceDictionary` swaps the `en_dict` in an English voice's `espeak-ng-data` for the
  app's (`assets/pronunciation/en_dict`, 228 KB, version = the first 16 hex of its SHA-256)
  before the engine loads, once per voice and again when the asset changes. The voice's own file
  stays as `en_dict.stock`; the dial `placeDictionary` 0 restores it.
- That dictionary is eSpeak NG's source (rhasspy/espeak-ng at the voices' commit; the engine is
  1.52-dev in sherpa-onnx 1.13.3) plus `tools/pronunciation/places.tsv`: 3,484 place names from
  the US, UK, Australia, Canada, Ireland and New Zealand with local pronunciations from English
  Wikipedia (CC BY-SA 4.0, credited in Settings > About), American-only and British-only
  readings split by eSpeak's `?3` / `?!3` flags. `scripts/build-espeak-dict.sh <espeak-ng-data>`
  rebuilds it and stops unless the source reproduces the stock file byte for byte. The location
  guard skips the list (`scripts/check-location.sh` and `location-guard.yml`, kept in step).
- A line renders phrase by phrase (`SpeechText.speechFragments`) and `PiperSynth.speak` hands
  each phrase to the `piper-play` thread as it is made, so the first word waits for one phrase
  (Pixel 4a, 52 characters: first audio at 0.29 s, all rendered at 0.70 s). Prepared lines
  (`prepare`) render whole.
- When the next phrase is not ready the player writes `STARVE_MS` (60) of silence: an underrun
  makes AudioFlinger drop the track, which aborts the process. Audio is written in about 200 ms
  pieces with a generation check between them, which bounds an interrupt.
- Pauses are spliced silence, 0.16 s at a comma and 0.32 s at a period, tuned at the default
  0.8x and multiplied by 0.8 / speed (`gapFrames`). The runtime's own silence scale is a no-op.
- Every fragment gets terminal punctuation before synthesis, or the last consonant is swallowed.
- A Piper voice speaks one language. When `NeuralSynth.voiceLanguage` differs from the text's,
  `VoiceGuide` asks `NeuralSynth.voiceFor(lang)`, which loads an installed voice of that language
  (`VelaPiper.languageOverride`, never persisted, cleared when guidance returns to the
  selection's language). Failing that, a system TTS in that language speaks. Failing that, it
  stays silent and fires `langUnavailable`, whose card offers a one-tap download of the
  language's recommended voice (`PiperCatalog.defaultFor`, `MapUiState.statusVoiceDownloadId`).
  A selected voice of another language than the app's is never heard, so its row in the voice
  library says which language directions are spoken in and which one the voice needs
  (`VoiceRow`, issue 701).
- `showStatus` cards dismiss after `STATUS_AUTO_MS` (10 s) with a draining bar
  (`InfoCard(autoMs)`), frozen while focused. A card with a fix (`statusVoiceAction`) stays.
  `flashStatus` cards drain over their own duration.
- Audio focus is refcounted through the utterance callbacks. A system TTS `speak()` returning
  `ERROR` fires no callback, so that path rolls back its acquire. A failed `onInit` clears the
  pending queue.
- A fresh focus grant leads the first sample by `FOCUS_LEAD_MS` (350 ms), the time a player that
  pauses on a duck takes to stop. Focus held from the previous prompt (`FOCUS_HOLD_MS` 1500)
  speaks at once.
- Text is fixed before the phonemizer. `SpeechText.spokenNumbers` expands three-digit street
  ordinals ("128th" as "one twenty eighth", space not hyphen). `expandForSpeech` rewrites
  `<XX>-<n>` refs to "State Route n" and puts a comma before " toward ".
  `SpeechText.spokenClock` (English) spells out clock times, which otherwise read as a
  measurement. A new spoken string with numbers, units or punctuation needs the same look, and
  a test.
- `voice_volume` is a gain over the neural voice's float PCM, hard-clipped at full scale. The
  system TTS path takes `KEY_PARAM_VOLUME` capped at 1.0: Android can only attenuate.
- A voice install or a delete-fallback never speaks. Only an explicit library pick auditions.

- The voice prepares the lines `NavEngine.upcomingPrompts` predicts for the current and next
  spoken turn (far at max(400 m, 35 s), near at max(150 m, 10 s), and turn-now), up to
  `MAX_PREPARED` (8), at background priority.
- A line asked to be spoken drops every prepare still queued and ends the one being rendered at
  its next phrase. A phrase is one engine call and cannot be cut, so the line waits for the
  phrase in hand, and the worker renders that phrase at the waiting line's priority
  (`PiperSynth.boostBackground`): the default for an imminent turn, the speaking priority
  otherwise. A voice load started by a warm-up or a prepare is raised the same way. A prepared
  line that was on its last phrase is kept.

### 9.2 Foreign scripts

A road name can be in a different script than the guidance language.

- `SpokenScript.forVoice(text, voiceLang, dict)` substitutes a real Latin name from the
  dictionary first, then falls back to ICU transliteration. Each voice keeps its own script. CJK
  is never romanized, because ICU reads Han as pinyin.
- `SpokenScript.forDisplay` does the same for the banner and step list with no ICU fallback: a
  vowel-less skeleton reads as broken, so an unmapped name keeps its local script.
- The dictionary is the basemap's `name:en` and `name:latin` per road, collected by the pass that
  places the crossing labels, plus `Route.roadNamesLatin` from the obf route.
- The dictionary query re-runs every 2 s until three passes in a row add nothing, because a
  drive starts with low-zoom tiles that carry major roads only. It is a synchronous
  render-thread round trip that materializes every loaded road name on the main thread (Pixel 4a
  at the nav zoom: 100 to 430 features, 14 to 58 ms). When a full pass adds nothing and the
  dictionary is still empty, the region's names are all Latin and the warm-up settles at once.
- The navigation opener is held up to `OPENER_MAX_WAIT_MS` (2.5 s), retrying every 200 ms, until
  the dictionary covers its road. An opener with no non-Latin letter never waits.
- Road labels use `roadLabelTextField()`: `coalesce(name:en, name:latin, name)` for a
  Latin-script UI, the local `name` otherwise. Nav bubbles filter on the canonical `name` and
  display the Latin form.
- Place, POI and water labels (`PLACE_LABEL_LAYERS`) take the UI language's `name:<lang>` first
  (`placeLabelTextField()`). Place names are data and are never transliterated.
- `SpokenScript.applyDict` returns in O(length) when the text has no character the reader cannot
  read, and digests each dictionary once per instance. A full scan twice per fix was a 60 ms
  main-thread stall at 1 Hz.

### 9.3 Dictation

Two tiers, both keyless.

- Tier 1, in process: `AsrRecognizer` over the same sherpa-onnx runtime, with three downloadable
  engines (`AsrEngine`): Whisper tiny int8 multilingual (58 MB, the default), SenseVoice (154 MB;
  en, zh, ja, ko, yue) and Moonshine (101 MB, English). Each archive carries its own Silero VAD.
  Recognizers cache on `"<engineId>|<lang>"`. Whisper is pinned to the app language: auto-detect
  transcribes noisy far-field audio into the wrong script. Recording takes
  `AUDIOFOCUS_GAIN_TRANSIENT`, so media pauses instead of ducking.
- Tier 2, handoff: `ACTION_RECOGNIZE_SPEECH` to an installed voice-input app, which records, so
  Vela needs no microphone permission for it. Only apps that register the activity count; an IME
  or a `RecognitionService` cannot be launched this way. The component is the user's pinned one,
  then Android's default, then the first installed app, so the system chooser never interrupts.

`RECORD_AUDIO` is requested at the point of use. `listen()` returns a typed result (`Text`,
`NoSpeech`, `Failed(reason)`) and the UI explains every failure.

The model load is crash-sentineled: a truncated or corrupt archive makes sherpa-onnx abort
natively, which cannot be caught, and warm-up runs at startup, so a bad model would crash every
launch. A strike counter is raised before
every native load and cleared after. Two stranded loads quarantine that engine and delete only
its directory. One is forgiven, because a process killed mid-load strands the counter too.

### 9.4 Interface language

The picker has 18 languages (`AppLocale.SUPPORTED`). Estonian (`et`) has a partial translation
from Weblate and English spoken guidance.

`AppLocale` holds the language (empty means follow the system) and is applied in both
`MainActivity.attachBaseContext` and `VelaApp.attachBaseContext`. When following the system it
also restores `Locale.setDefault` to the captured device locale: the override is process-global
and otherwise leaves date formatting and the scrape's `hl` in the previous language. Changing
the language calls `recreate()`.

- Strings live in `res/values/strings.xml` (US English) plus one folder per translated language.
  `values-en-rGB` carries only the strings whose British wording differs.
- Counts use `<plurals>` with each language's CLDR categories.
- A string that doubles as a logic key is not in `strings.xml`. Where a display label and a logic
  key must both exist they are split, and the key stays English.
- The scrape's `hl` follows the app language only where a status keyword table exists
  (`SearchParser.STATUS_LANGS`). Any other locale stays `hl=en`, because an unreadable status
  string leaves open/closed null. `gl` follows the phone's region.
- Hyphenated language codes resolve through `Locale.forLanguageTag`. `Locale(String)` makes a
  bogus lowercase language that matches nothing.
- Names, streets and reviews are data and are never translated.
- The repository is US English. The exceptions are `values-en-rGB` and data strings that match
  a foreign source's spelling (OSM's `neighbourhood` place class, OSM tag values such as
  `fitness_centre`, the MOTIS wire field `cancelled`), marked where they appear.

- Clock: Settings > Appearance > Clock (`clock_mode`) is the device's 12/24-hour setting, or
  either for Vela alone. `Clock24.on` and `ClockFormat.use24h` carry it. Transit directions
  times come from the payload's epoch and time zone (`ClockFormat.at`); `ClockFormat.show`
  converts the text of the one page still fetched in English, Google's stop-board fallback.

### 9.5 Query intents

Every submitted query, typed or dictated, goes through `core/search/QueryIntents.parse(text,
lang)` first: Home, Work, NavigateTo, Route(from, to), Search with filler stripped, or Eta. Null
means a plain search, so the parser cannot make a query worse. Word tables cover every app
language but Estonian, with English as a fallback in each.

- A bare verb counts only before home or work or an explicit "from A to B".
- A bare "X to Y" is a route only when X is not a question word or a verb.
- `routeBetween` runs the whole phrase as a search first, so a place whose name contains "to"
  stays a place.

A fuzzy pass runs after the exact passes miss, in spaced languages only. Tolerance is per word
and only over the vocabulary, never the free text. Accents fold. One edit is allowed from four
letters in a leading or trailing phrase and from five in a whole-phrase match, two from eight. A
single-word phrase never fuzzes, and a multi-word phrase must match word for word, so "home
depot" cannot collapse to "home".

---

## 10. User interface

### 10.1 Sheet physics

The place sheet, the results sheet and both route pickers share one drag grammar. A new sheet
reuses it.

- The sheet is a hand-driven `Animatable` (the height for the sheets, the body height for the
  pickers), dragged 1:1 from the handle and, through a nested-scroll connection, from content
  scrolled to its top.
- Every drag goes through `sheetDragGestures` (`ui/place/PlaceSheet.kt`). It tracks velocity on
  integrated drag deltas, because `change.position` is local to a node that moves as the sheet
  resizes and reads as zero. The release velocity is the larger of the tracked value and travel
  over time.
- Release projects the fling with `exponentialDecay(frictionMultiplier = 1.6f)` and takes the
  detent nearest the projected end. A release faster than `FLING_COMMIT_DPS` (180 dp/s) moves at
  least one detent in its direction.
- When the coast reaches the detent, the sheet rides `animateDecay` with the `Animatable` bounds
  clamped there, so the detent stops the coast. Otherwise it settles on a spring. The
  Google-style picker always settles on the spring.
- The animated value is read in a layout or `graphicsLayer` block, never in composition, so a
  frame re-measures the sheet without recomposing it.
- A scrollable region with zero scroll range never starts a drag. A minimized card whose content
  fits has its own drag surface.
- `SheetFold` folds a section with the sheet's own height fraction. Its fade uses
  `CompositingStrategy.ModulateAlpha`: a plain alpha below 1 renders offscreen, which cost 20 ms
  of GPU per frame on a Pixel 4a (frame p50 32.1 ms against 16.7 ms).
- A map pan minimizes the sheets. `onUserPan` fires for a gesture camera move only, never a
  programmatic one, and not in picture-in-picture.
- A sheet that takes a tick (`minimizeTick`) remembers the value it mounted with. A
  `LaunchedEffect` also runs on first composition, and a remounted sheet would replay a stale
  tick.

### 10.2 Chrome

#### Layout rules

- Nothing above the place sheet's action pills moves after the first frame. The photo strip and
  the rating row reserve their space while `detailsLoading` is true and the place has a non-blank
  `category`. The test is on the category because a place tapped on the open places layer has no
  Google id until details land.
- The place sheet's tab row is always a `ScrollableTabRow`, so each tab is as wide as its label.
  A fixed row clips long labels at large font sizes.
- Chrome hides on the sheet's measured top edge (`SheetEdge`), not on the expanded flag. A short
  place flips the flag while its wrap-capped card never grows.
- Top-of-map cards (status, downloads, the update card, notices, the faster-route offer) stack in
  one top-center column, each with its own dismiss. During a drive the column hangs off the turn
  card's measured bottom edge (`navBannerBottomPx`).
- In landscape, route and drive chrome is a left column. Both sheets, the route pickers, the
  endpoints card, the turn card and the nav bar take a start alignment plus
  `landscapeColumn(...)`, which caps the width at `sidePanelWidth()` and pads the display cutout
  on the start edge. The camera takes `cameraLeftInset` in place of a bottom inset. New route or
  drive chrome without this spans the screen.
- `sidePanelWidth()` is 0.56 of the screen width, between `SIDE_PANEL_WIDTH_MIN` (400 dp) and
  `SIDE_PANEL_WIDTH_MAX` (600 dp).
- Picture-in-picture: everything after the map call is inside one `if (!pipUi)` gate. The map's
  compass and all gestures are off in PiP, because the system's taps on the window reach the map
  as gestures and detach the follow camera.
- `MapScreen` takes no new call or parameter. It is at the JVM method-size limit ("Method too
  large" in the debug compile) and at ART's verifier limit, which fails on the phone. New
  floating pieces go in `MapFloaters` (the replay controls, the Street View preview) or
  `NavCorner` (the speed box and Re-center), each of which decides for itself when to draw.

- UI font: Settings > Appearance > Font offers Google Sans Flex (the default; SIL OFL 1.1, in
  `res/font`, license in `assets/licenses`), the system font (pref `ui_font_system`), or a
  user file (`filesDir/fonts/ui.ttf`), through `velaTypography(family)`. Scripts Flex lacks
  fall through to the system font.

#### Browse map

- Landscape browse chrome is one line. The condition (`landscapeOneLine`) does not include
  `!searchOpen`: focusing the bar flips `searchOpen`, and moving the bar to another subtree would
  remount and blur it, which flips `searchOpen` back.
- In landscape the place card may fill the height, the search bar hides while the place panel or
  the results list is up, and "Search this area" centers in the map beside the panel.
- The locate, parking and layers buttons share `mapButtonColor`, `mapButtonInk` and
  `mapButtonRing`: a gray circle (`#3C4043`) with a white glyph and a faint ring in the dark
  themes, white with a gray ring in light, `primaryContainer` and no ring under wallpaper colors.
  The locate glyph is blue. A saved parking spot tints the glyph, not the button.
- `VelaProgressBar` (`ui/VelaProgress.kt`) is the only progress bar: rounded ends, null progress
  means indeterminate. `VelaProgressBarOf` takes the value as a lambda, so an animating bar is
  read in the draw phase.
- The replay controls have a grab bar and drag up and down the screen.

`CategoryChipsPref` (Settings > Map): the chip row, one "Categories" chip that opens them in a
`VelaMenu`, or none. Decided inside `CategoryChips`.

#### Drive chrome

- The nav bar is flush with the screen's bottom edge, top corners rounded. `navBarLook()` sets
  its colors: white with dark inks in the light theme, `NavBarColor` (`#101214`) with light inks
  in the dark themes, the theme's `surfaceContainerHigh` with its own inks under wallpaper
  colors. The step sheet it opens into follows through `LocalStepsOnDark`.
- The bar holds End, the trip figures and a right slot. End and Pause are 60 dp ringed
  `NavBarButton`s. `FitDuration` sets the time's digits semibold and its units regular and
  shrinks to fit, with distance and arrival beneath. The handle is a flat grab bar that also
  opens the step list on a tap.
- The floating buttons (`NavFab`: overview, mute, search) and `NavRecenterPill` wear the bar's
  colors, or `primaryContainer` when `wallpaperColorsInUse()` is true. That requires the Material
  You setting on and a phone scheme that passed the sanity check. Where the scheme fell back to
  Vela's own, a wallpaper-colored button would come out teal.
- Pause sits in the bar's right slot when `PauseInBar` is on (pref `nav_pause_in_bar`, default
  on) and the device is not keypad-first. The right-edge stack then has a plain mute button.
- The step-list button takes the slot when `PreferButtons` is on (pref `prefer_buttons`, default
  off) or the device is keypad-first. With both wanted the bar carries both and the figures
  shrink. With neither, the slot is a 54 dp spacer that keeps the figures centered against End.
- With pause out of the bar, the stack has `NavHoldControls`: one 56 dp button for pause and
  mute. On a running drive the first tap slides mute out beside it for `OPEN_MS` (6 s) and a
  second tap pauses. While paused, one tap resumes. A long press mutes. The slide-out is the key
  path to mute. The glyph shows pause or resume, the accent fill a held drive, a crossed speaker
  a muted one.
- The "Then" tab hangs off the turn card's lower left in the card's color and names the next
  step. The card's lower-left corner is square while the tab shows, and the lower-right too when
  the tab is as wide as the card. Both widths are measured.
- Away from the car (panned, pinched, previewing a step), `NavRecenterPill` takes the speed box's
  place and the road name hides.
- The road name (`RoadLabel`, pref `road_label`) defaults to `PUCK`: a pill under the arrow,
  clamped to the window. The other values are `BAR` (centered above the bar), `IN_BAR` (the bar's
  handle row) and `OFF`.
- What it says is `core/nav/roadLabelAt`, on the phone and in the car. `roadLabel(name, ref,
  heading)` picks the road's own name when it has one, and its number on an Interstate, on a
  named freeway (`isFreewayName`: the name ends in Freeway or Motorway, or starts with
  Autoroute, Autostrada, Autopista, Autovia or Autobahn), or where the name only says the
  number again ("State Route 9"). An expressway, a parkway or a turnpike keeps its name. The
  number stays on the turn card as the current road's chip.
- A number is followed by a compass letter when a guide sign gave one: "I 80 E".
  `signedHeading` reads it from the text of the step that entered the road ("toward I 80 East:
  Sacramento"), else from the steps before it that are unnamed or already on that route, up to
  `SIGN_LOOKBACK` (8) steps. The word before the number must be a route prefix. A router that
  sends "I 80 East" as the number or the name is read the same way, as is a name that restates
  the number with a direction. A sign that names both directions ("I 5 North, I 5 South") is
  settled by the leg's bearing, which must be within `HEADING_PICK_DEG` (60) of one of them. No
  letter shows after a plain turn onto a numbered road, or when the leg runs more than
  `HEADING_MAX_OFF_DEG` (135) from the one signed direction. Bearings are only read on a leg
  of `HEADING_CHECK_MIN_M` (300 m) or more. The letter is never taken from the bearing alone:
  a route signed east can run north. A name never gets a letter.
- "Searching for GPS" sits above the arrow, with bottom center as the fallback before a puck
  position exists.
- Landscape: the turn card and the bar sit in the left column, `NAV_LAND_EDGE_DP` (8 dp) from the
  safe edge. The speed box and the Re-center pill sit just right of the bar's measured right edge
  (`navBarRightPx`). The step list's height floor is 64 dp (200 dp in portrait), so it stops
  under the banner.
- The route bar (`RouteBarStrip`) draws in portrait only and never in PiP. It shows the next
  `RouteBar.WINDOW_M` (5,000 m): scaled to a long trip, nearby marks collapse into one pixel.
- Search along the route is a page (`NavSearchChips`): the whole screen in portrait, the left
  column over a dimmed map in landscape, where a tap on the dimmed part closes it. It has a
  search field, `QuickCategories.forDrive()` as tiles three to a row with fuel and charging
  first, and the six most recent searches. It is drawn last in `MapScreen`.
- The drive buttons and the speed box use `Modifier.popIn()` (a short scale and fade) when they
  return after the step list closes.

- Road-name pill: its width is the room between the speed readout's right edge
  (`speedBoxRightPx`) plus 8 dp and the FAB column, at least 96 dp. Names over 16 characters
  are shortened by `RoadNameShort`, and the text shrinks to 80 percent before it is ellipsized.

#### Route picker

- `RoutePicker.googleStyle` (pref `route_picker_google`, Settings > Navigation) is on by default.
  A choice made under the old key `exp_google_chooser` carries over. The calibration field
  `classicRoutePicker` sets the default for anyone who never touched the switch.
- On: `GoogleStyleDirectionsPanel` (`ui/place/GoogleChooser.kt`). The mode is the title, the mode
  tabs carry each mode's time, one summary shows the selected route with the alternates behind an
  "other routes" line and as time bubbles on the map, and a bottom bar has Start, Add stops and
  Share. It has three states: minimized, summary and list. Tapping a step in the list drops
  the picker to the summary, so the map shows the step, and `startNav` clears the previewed
  step.
- Off: the classic `DirectionsPanel`, which lists every route. Transit always uses it.
- In drive mode both pickers show one "Avoid" label and four chips: Tolls, Highways, Ferries,
  Cameras. Cameras is the `FlockRouteAlert` switch and refetches through
  `RouteActions.camerasChanged`.
- The endpoints card (`RouteTopCard`) menu has "Add to home screen". Its dialog takes a name (24
  characters), a glyph (`PoiIcons.SHORTCUT_ICON_KEYS`) and a themed look that copies the
  launcher's themed icons (pref `trip_shortcut_themed`), then pins the trip through
  `TripShortcut.pin` (5.7).

#### Spoken street names

Settings > Voice "Say street names" (`ui/SpokenRoadNames`, pref `spoken_road_names`, default on)
is mirrored into the `:core` flag `nav/SpokenRoadNames`, which `NavEngine` reads. Off, the voice
announces the turn without the street. The banner, the step list and the road pill keep the name.

- `Maneuver.instructionNoRoad` is built by the same per-language template as `instruction` with
  the road left out, so the word order stays right. The OSRM, Valhalla, obf and `LineNamer`
  builders fill it. It is null for Google's abbreviated steps, where speech keeps the full
  instruction.
- Every spoken site reads `Maneuver.spokenInstruction()`: the calls in `NavEngine`, plus the nav
  opener and the faster-route line in `NavSession`. Those two keep the named form for their
  banner and card.
- Code that rewrites an instruction after the router built it rewrites both forms, or the switch
  undoes the rewrite. `consolidateExits` does. The traffic-light cue is added at speak time to
  whichever form is spoken (`NavEngine.lightLead`). `SpokenRoadNamesTest` pins both.

#### Live update notification

On API 36 and above the nav notification asks to be promoted to a live update
(`promoteToLiveUpdate` in `service/NavigationService.kt`), which puts the drive in the status
bar chip and on the lock screen.

- It is a `ProgressStyle` scaled to the route's length in meters. The tracker is the nav puck,
  the maneuver glyph is the large icon, the segments are the route's traffic spans, each
  remaining stop is a point, and the chip's critical text is the distance to the next turn.
- Below API 36, with no route, or if anything throws, the notification is unchanged.
- The system raises the chip only while the app holds
  `android.permission.POST_PROMOTED_NOTIFICATIONS`, which the user can turn off in the app's
  notification settings. Without it the notification still posts in the shade.
- The channel `vela_nav_drive` has DEFAULT importance with no sound and no vibration. A LOW
  channel is filed as silent, and a phone that hides silent notifications on the lock screen
  would hide the drive there. A channel's importance is fixed once it exists, so the old LOW
  channel is deleted on first run.

### 10.3 D-pad operation

The whole UI works with a five-key D-pad and no touchscreen.

- Every interactive element is a focus target with a visible ring in key-driven input
  (`Modifier.dpadHighlight`), and every gesture has a key path.
- Every screen opens with something focused (`rememberDpadAutoFocus`), because Compose's own
  focus recovery is nondeterministic. Two exceptions: the bare map opens unfocused, and when
  Settings is the first thing in a session to take focus the first key press lands on Back.
- A Compose `DropdownMenu` popup and an `AlertDialog` cannot be pre-focused. `VelaMenu` renders a
  raw `Dialog` with its first item focused on a D-pad-first device and the ordinary
  `DropdownMenu` under touch. `VelaDialog` is a raw `Dialog` that focuses the dismiss button.
  Their items take focus through `.focusable()` plus `.onKeyEvent` plus `pointerInput`, because
  the nested focusable inside `.clickable` does not accept `requestFocus` in a dialog window.
- `Modifier.dpadFieldEscape` makes up and down leave a text field.
- D-pad code calls the touch paths (the same tap lambda, gesture flags and zoom override), and
  every D-pad affordance is gated on `dpadMode` or `noTouch`, so touch behavior does not change.
- A device is D-pad-first (`rememberDpadFirstDevice`) only with no touchscreen or with a
  physical, non-virtual `SOURCE_DPAD` device. The framework's virtual input device reports
  `KEYBOARD | DPAD` on nearly every phone, and counting it breaks the search bar. A keypad phone
  with a fake touchscreen gets D-pad behavior on its first key press through `rememberDpadMode`.
- `AdaptiveDensity.wrap`, chained first in both `attachBaseContext`s, shrinks the density so a
  narrow screen reports `MIN_WIDTH_DP` (360 dp) of width. At 360 dp and above it does nothing.
- `adb shell input text` and `keyevent` flip the live input mode to Keyboard, which disables the
  unarmed search field. That is the test tool's effect.
- Zoom keys (`ui/ZoomKeys`, prefs `zoom_key_in` and `zoom_key_out`, 0 for none): two key codes
  set in Settings > Navigation by pressing the key. `MainActivity.dispatchKeyEvent` zooms one
  level on the key's first down while the map is showing. A printing key is offered after
  `super`, so a text field keeps it. A non-printing key is offered before. The arrows, OK,
  Enter, Back, Home, Menu and Power cannot be assigned.

Per-surface audits and the contributor procedure are in `docs/dpad.md`. The regression suite is
`dpad_test_suite/` (`run_all.sh`, `audit_static.sh`, `audit_dynamic.sh`). CI runs
`audit_static.sh`, which reads the source and needs no device.

### 10.4 Settings

Settings is hub and spoke. `SettingsScreen` hosts a Navigation Compose `NavHost` with the map and
one destination per `SettingsSection`. `SettingsHub` holds the category rows and the search.
`SettingsScaffold` holds the focus plumbing every page builds on.

- The map is `MAP_ROUTE` (`main/map`), a transparent destination over the composed map, so the
  camera and the shared `MapViewModel` survive. Each page is `settings/<section>` in
  locale-independent lowercase. Navigation matches routes case-insensitively, so the map and Map
  settings have distinct paths.
- Back from a spoke returns to the hub, then the map. Opening Voice from Offline replaces the
  spoke. The voice-library shortcut puts the hub under Voice. The host is composed after the map,
  so its back handling wins while Settings is open.
- The search is a static `SEARCH_INDEX` of label resource to section. A match opens the spoke
  and scrolls to the row (`Modifier.settingsAnchor(label)` plus `LocalSettingsHighlight`). A new
  row label goes in the index. A group whose rows can be empty still renders, with an empty-state
  hint, or its index entry leads to nothing.
- A page requests keypad focus only once its entry is RESUMED (`LocalSettingsPageActive`), so a
  page sliding in does not take focus from the one being left.
- Settings > Places leads with "Place icons on the map" (`MapPoiPrefs.placesSource`, 5.1), then
  what the map draws for places, then the place-page toggles (10.8).
- No blocking IPC or IO runs in a composable body. A `PackageManager` query in composition
  re-runs on every recomposition. Load such data with `produceState` plus `withContext(IO)`.

#### Page transitions and predictive back

- `PageTransitions` (pref `page_transitions` in `vela_settings`, default on, Settings >
  Appearance) switches off every animation in this list. Back still works, and dragging and
  detent settling are unaffected.
- Settings pages slide in over 250 ms while the outgoing page moves a quarter width. Back
  reverses it, mirrored for RTL. Navigation Compose owns predictive back progress, completion and
  cancellation.
- `BottomOverlay.of` maps `MapUiState` to one stable key: `ARRIVAL`, `NAV_STOPS`, `STEPS`,
  `NAV_STOP_OFFER`, `NAV_CONTROLS`, `TRIP_EDITOR`, `DIRECTIONS`, `CLASSIC_DIRECTIONS`, `PLACE`,
  `SHAPES`, `RESULTS` or `NONE`. `SheetTransition` keeps the outgoing `MapUiState` snapshot
  (`BottomOverlayFrame`) until its exit ends. Content slides up over 250 ms and down over 200 ms,
  by its measured height. A detail fetch or a place change under the same key does not replay
  the entrance.
- `STEPS`, `SHAPES` and `NONE` do not slide, and `STEPS` to or from `NAV_CONTROLS` is not
  animated here. The nav bar and the step list share one height animation in `StepsSheet`, whose
  back handler finishes the close animation before clearing the state.
- Transit guidance and the transit route-detail sheet use `SheetTransition` too.
- Outgoing content ignores pointer input, is hidden from accessibility, and reads
  `LocalSheetActive` as false, which disables its own back handlers.
- Predictive back translates the current sheet by its measured height times the gesture
  progress. Completion settles to the bottom over 160 ms, runs the close action and skips the
  ordinary exit. Cancellation returns over 180 ms and changes no selection, route or guidance
  state. Gesture state belongs to the content key, so a reopened sheet starts at zero.
- Area picking, pick-on-map, transit guidance, open search, the open alternatives list and a
  minimized route picker keep their own back priority, as do the place sheet and the step list
  during a drive. A minimized route picker restores itself on Back (`RoutePanelBack`) and the
  route closes on the next. Back during a drive always asks before ending it. `NavEndConfirm`
  decides only whether the End button asks.
- `NavigationChromeTransition` keeps the outgoing top chrome when browsing and navigation swap.
  The search bar and the turn banner fade and slide over 220 ms on entry and 160 ms on exit. The
  map, the camera controller and the guidance session stay live.
- The manifest sets `enableOnBackInvokedCallback`. Android 13 and 14 need the system developer
  option to show predictive back.
- Sheet cards have 4 dp shadow elevation, the nav bar and the driving step sheet 6 dp. Close and
  share actions on the pickers and the step list are `HeaderCircleButton`: a 36 dp circle with an
  18 dp icon.

### 10.5 Full-screen viewers

The photo gallery and the full reviews page render in the activity's own edge-to-edge window. A
Compose `Dialog` window re-asserts inset-fitted params, so it cannot cover the system bars, and
it keeps stale bounds after a rotation. A call site posts a request to `GalleryOverlay` or
`FullReviewsOverlay` (`ui/place/PlaceSheet.kt`), and `PlaceOverlays()`, the last child of
`VelaRoot`'s root box, draws it above the map, the sheets and Settings. The gallery is drawn
after the reviews page, so a review photo opens on top of it. The gallery sets white status bar
icons while it is up and draws a top gradient under the status bar.

### 10.6 Android Auto

`app/car/` is a navigation-category `CarAppService` (`VelaCarAppService`): the manifest service
with the `NAVIGATION` and `FEATURE_CLUSTER` categories, `automotive_app_desc.xml` with
`<uses name="template"/>`, the `androidx.car.app.*` permissions and `minCarApiLevel=1`. It uses
the same `:core` singletons as the phone (`CarDeps`).

- The host validator is `ALLOW_ALL_HOSTS_VALIDATOR`. The gate is on the phone: on connect the
  Android Auto app asks the Play Store for the app's owners and denies a package Play never
  installed (`CAR.VALIDATOR: Package DENIED`). The "Unknown sources" developer setting and the
  install fields do not change that. On a stock Pixel an install routed through Google's own
  package installer passes. The desktop head unit skips the check.
- A car with an instrument cluster opens a second session (`DISPLAY_TYPE_CLUSTER`). It gets
  `ClusterSession`, one bare `NavigationTemplate`, because that display rejects the place-list
  template. Its content comes from `updateTrip()`.
- `VelaCarSession` runs its own AOSP location feed into the shared `NavSession` (GPS fixes at 50
  m accuracy or better, or the simulated position), so navigation runs with the phone UI closed. It handles
  `androidx.car.app.action.NAVIGATE` geo intents by opening the route preview. A drive started
  from the car speaks with the Piper voice when it is installed and chosen (the service attaches
  the synth).
- `VelaCarSession` is the one place that pushes `ActiveNavCarScreen`: it watches
  `NavSession.state` and, when a drive starts on the car or on the phone, pops to the landing
  screen and pushes the drive screen. `onCreateScreen` returns the drive screen over the landing
  screen when the car connects during a drive.
- Screens: `MainCarScreen` (`PlaceListNavigationTemplate`) to `SearchCarScreen`
  (`SearchTemplate`) to `RoutePreviewCarScreen` (`RoutePreviewNavigationTemplate`) to
  `ActiveNavCarScreen` (`NavigationTemplate`). The landing strip also opens `SavedCarScreen`
  (Home, Work and every saved place, capped at the host's list limit) and `CarSettingsScreen`
  (spoken directions and the tolls, highways and ferries avoids, written to the phone's
  `vela_settings` prefs and `RoutingPrefs`). The route preview passes the `RoutingPrefs` avoids
  to `directions`.
- The landing list has six rows. At most `MAX_DESTINATIONS` (3) are destinations. Nearby
  categories fill the rest (`NearbyCarScreen.driving()`: gas, EV charging, restaurants, coffee,
  parking), ending in "More nearby" when they do not all fit. `NearbyCarScreen` lists the six
  nearest results with a distance span, and a row previews a route. Each row carries a
  numbered pin (`CarMapRenderer.pinBitmap`), and `showResults` draws the same pins on the map
  and frames them with the car until the screen is left. A category row's marker is a
  `Row.IMAGE_TYPE_SMALL` image, because the host tints an icon to one color.
- Location permission can be missing when a car connects before onboarding ran. The session's
  feed and the renderer wait on `CarLocationAccess.granted`, the landing list leads with an
  "Allow location" row that calls `CarContext.requestPermissions`, and the renderer draws a world
  view (`WORLD_CENTER` 20,0 at `WORLD_ZOOM` 1.5) until the first fix. With no center the car map
  stays black.
- The turn card needs `NavigationManager.navigationStarted()`, `updateTrip()`, and an icon on
  the step's `Maneuver`: Android Auto draws no card for a maneuver without one. `updateTrip()`
  is sent when the step, its shown distance, the minutes left or the distance left (in 100 m)
  changes, and at most once a second (`TRIP_MIN_GAP_MS`). The host drops faster updates.
  `ManeuverMapper` maps Vela maneuvers to car `Maneuver`, `Step` and `Trip`, sets the phone's
  glyph as the icon (`NavGlyphs.bitmap`), and reads the roundabout direction and exit number
  from the route's geometry.
- On the drive screen a paused drive shows a `MessageInfo` ("Paused") in place of the turn card,
  and the strip has Pause/Resume. A turn farther than `CONTINUE_FAR_M` (1,500 m) leads the card
  with "Continue on" the current road (`Maneuver.roadAt`) under a straight arrow and shows the
  turn as the next step. A
  search icon opens `AlongRouteCarScreen`: the quick categories as rows, a pick searches around
  the car, and a result becomes the next stop through `NavSession.addStop`.
- The preview draws the selected route in blue over the other listed routes in gray
  (`showPreview(route, others)`), with a red dot at the destination. The drive draws the same
  dot, and the line ahead in the phone's paused lavender while the drive is paused.
- Map action strips: recenter, zoom in, zoom out on the landing map; overview (re-frame the
  selected route), zoom in, zoom out on the preview; recenter, zoom in, zoom out and an overview
  toggle (`toggleOverview`, exempt from the pan auto-recenter) on the drive.
- `CarBridge` carries the phone controller's spoken alerts (cameras, speeding, closing soon) to a
  `CarToast`, and the route's lights, stop signs and speed cameras to the renderer. A drive
  started with the phone UI closed has no controller and gets neither.
- Settings > Navigation "Show speed and speed limit" (`SpeedDisplay`, default on) hides the speed
  badge on the phone and the car, and skips the car's limit lookup. The spoken speeding alert is
  a separate setting. The car's limit sign follows the phone's: the "SPEED LIMIT" rectangle in
  miles, the red-ring disc in kilometers, and a red number past 3 mph or 5 km/h over.
- The guidance voice is band-limited by the protocol: the Android Auto guidance stream is 16 kHz
  mono.

The map:

- The map is MapLibre's public `MapSnapshotter` rendered to a bitmap and drawn on the car
  surface. One `CarMapRenderer` lives for the session. A renderer per screen freezes the map.
- `onSurfaceAvailable` releases the `Surface` it held before taking the new one. A screen change
  delivers a new object for the same buffer queue, and the old object keeps the queue's CPU
  connection until it is released or collected. Until then `lockCanvas` fails on the new one
  and no frame is drawn. A failed lock logs once under `VelaCar`.
- It resolves the same patched style file as the phone map (a plain style URL leaves the car on
  Noto) and takes the phone's palette through the `StyleLayers` interface
  (`applyMapTheme(SnapshotterHost(...), dark, amoled)`). The palette is applied from the first
  snapshot callback, because the style observer never fires for a style handed over as JSON, and
  that first frame is discarded. It is re-applied when the car's day/night changes.
- `QuietSnapshotter` drops the library's overlay, and the renderer draws its own single
  OpenStreetMap credit. The credit and the speed badge stay inside the host's stable area, or the
  visible area when the host reports no usable stable area.
- During a drive the renderer takes GPS fixes, the simulated position and a demo drive's or
  replay's fixes, snaps the puck to the route, glides it between
  the roughly 1 Hz fixes with `FollowEstimator`, and eases the heading and the speed-tiered zoom
  (`ZOOM_EASE` 0.06 per `TICK_MS` 70 ms tick). The puck is the phone's puck bitmap rotated by
  heading minus camera bearing, framed at `PUCK_DOWN` (0.72) of the visible area's height while
  following. Meters per pixel assume 512 px tiles.
- A pan moves the center by the finger's travel in meters (`shiftCenter`). Reading the new center
  off the last snapshot's `latLngForPixel` adds the visible-area offset to every scroll event. A
  pinch keeps the point under the fingers in place. A fling decays exponentially (`FLING_TAU_S`
  0.35 s, stops under `FLING_STOP_PX_S` 40 px/s), stepped by the render ticker and stopped by
  recenter, zoom, overview and route preview. The map recenters `RECENTER_MS` (6 s) after a pan.
- From z13.5 the renderer draws the `CarBridge` lights, stop signs and speed cameras as dots,
  plus the plate cameras along the route from the bundled set.

### 10.7 Street View

The panorama is rendered in-app (`app/streetview/`, `ui/place/StreetViewScreen.kt`). Google's
WebGL embed serves a stripped shell that renders black on ANGLE. Do not retry it.

#### Requests

- Metadata by location is a POST to `Calibration.STREETVIEW_SEARCH_URL` (Google's
  `MapsJsInternalService/SingleImageSearch`, content type `application/json+protobuf`) with the
  JSON body `streetViewSearchBody` (`{LAT}`, `{LNG}`, `{RADIUS}`; remotely replaceable). It is
  asked at 50 m, then 200 m (`STREETVIEW_RADII_M`): a store behind its lot has imagery within
  that. The body asks for Google's own imagery only (`[2,1,2]`), because the tile loader cannot
  show user photo spheres. The reply is bare JSON.
- The old GET, `GeoPhotoService.SingleImageSearch`, has answered "decommissioned" since
  2026-10-05 and is tried only after the POST fails. Builds from before that date lack the POST
  and cannot be repaired remotely.
- Metadata by pano id comes from `photometa/v1`, whose response nests the pano node one level
  deeper and carries a `)]}'` guard. Both lookups are authorized by a
  `Referer: https://www.google.com/maps/` header. The address, copyright and position are inside
  the pano node, not at the root.
- Tiles come from `streetviewpixels-pa.googleapis.com/v1/tile`. The `/v1/thumbnail` path answers
  only with the full parameter set Google's own pages send (`cb_client`, size, yaw, pitch, field
  of view). Bare, it returns 403.

#### Rendering

- `StreetViewTiles` stitches the highest level at most 4096 px wide and `PanoramaView` textures
  it onto a GLES2 sphere. Never stitch the full 16384x8192 level: about 400 MB of texture.
- The tile pyramid is not one shape. Modern panoramas are `512 * 2^z` wide. Captures from before
  about 2016 are `416 * 2^z` and some have only four levels. The grid is sized from the
  panorama's own level dimensions, or the loader requests tiles past the edge and paints black
  bands. A stitch that is not 4096x2048 is scaled to it, which is exact for an equirect.
- The sphere is viewed from inside (culling off) with natural U (`uv = u`). A flipped U mirrors
  the panorama, which reads as backwards signage.
- Compass frame: Google puts the capture heading at the texture center (u = 0.5) and the
  renderer's yaw 0 looks at u = 0.75, so compass bearing B is renderer yaw
  `B - captureHeading - 90`. Use `setCompass(panoHeading, faceCompass)`. Never pass a compass
  bearing as a raw yaw, and never overwrite the pano's own `headingDeg`, which is the texture
  reference.
- Zoom is the horizontal field of view. A fixed vertical field of view narrows the view when the
  pane grows.

#### Which panorama

- The search response's Street View thumbnail URL carries the pano id and the camera yaw, and
  both are used as given. For an entry with no thumbnail the fallback is geometric: the nearest
  pano, and when that is not on the address's street, probes at `STREET_PROBE_RADII_M` (24 m and
  40 m) along both perpendiculars for one that is. Geometry alone mis-picks.
- Walking fetches the neighbor by pano id, never by nearest location, or a walk lands on a
  different-year capture at the same spot. Time travel resolves the historical pano's own
  metadata, because epochs differ in pyramid shape and in heading by up to 180 degrees.

#### Viewer

- The viewer is a top-aligned pane over the live map (0.55 of the height, or full screen), not a
  dialog. It reports its pose so the map draws the puck and a view cone. The camera eases to the
  panorama on each hop only, never per yaw frame. A tap on the visible map moves the panorama
  there.
- A `SurfaceView`'s window hole does not follow a pure-Compose resize, so the full-screen toggle
  recreates the view (`remember(full)` inside `key(view) { AndroidView(...) }`) and re-feeds the
  texture and the current yaw.
- Viewed panoramas are kept in `core/data/StreetViewCache` (`files/svcache`: one metadata JSON
  and one stitched JPEG per pano, `MAX_BYTES` 150 MB, oldest dropped) under the "keep viewed
  places" setting (`OfflinePlaces`). The network is asked first and the saved copy is used only
  when it gives nothing. Deciding "offline" up front showed "no imagery" on streets that have it
  whenever the offline guess was wrong. With neither, an offline phone is told the spot was not
  viewed before. The cache is cleared by its own button, by "Clear history" and by "Delete all
  offline data", and is read off the main thread.
- A preview floats on the map for an open place whose search reply names a pano
  (`StreetViewThumb` in `MapFloaters`): above the card's left corner in portrait, beside the
  panel in landscape. It is one `/v1/thumbnail` request with the reply's pano id and yaw. It
  follows the photo settings, and it is not drawn with Google off, offline, when the picture
  fails to load, or while the place sheet itself is off screen (`SheetEdge.shown`, set by the
  sheet's own position reports and cleared when it leaves: the search page keeps the place
  selected).

### 10.8 Content gating

`ShowReviews` and `LoadPhotos` (`ui/PlaceContent.kt`) gate the fetch as well as the render, so
off means no scrape traffic. `HideAdult` sets the `:core` `CategoryFilter` flag, which filters at
the `search` and `nearbyPlaces` seam on category only, never name, with multilingual keyword
lists. It also drops the bars chip from the quick categories, which the filter would empty.
`HideExternalLinks` hides the website pill and row, the OpenStreetMap link on the source line and
the book/order action. The Street View pill opens the in-app viewer, so that switch leaves it
alone. It is hidden only when Google is off. A new review, photo or external-link surface goes
behind the matching holder.

---

## 11. Remote resilience

At launch `CalibrationStore` loads its cached bundle, then fetches `calibration.json` and the
detached `calibration.json.sig` from
`https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/`. It adopts the remote bundle only if:

1. the signature verifies (ECDSA P-256 with SHA-256 against `PINNED_PUBLIC_KEY`);
2. every endpoint host is `google.com` or `www.google.com`;
3. `version` is higher than the active one (`Calibration.DEFAULT.version` stays 1).

Otherwise the active bundle stands. A cache that fails the same checks falls back to
`Calibration.DEFAULT` for one launch.

`parseBundle` takes the compiled default for a missing key. A `Calibration` field it does not
read is not remote, so grep `CalibrationStore` for a new field's name. The bundle carries:

- Requests: endpoint URLs, pb templates, `photosProto`, `reviewFeedProto`,
  `streetViewSearchBody`, the search-as-you-type request (`suggestEndpoint`, `suggestPb` with
  `{SPAN} {LNG} {LAT} {W} {H}`) and the RPC header value `rpcContext`.
- Positions: `paths`, `directionsPaths`, `suggestPaths`, `stopBoardIndices`.
- The browser identity fields.
- Word tables: `statusClosedWords`, `statusOpenWords`, `transitCategoryWords`,
  `transitExcludeWords`, `reviewWords`, and the review scrape's CSS `reviewSelectors`.
- Fleet defaults: `defaultVoiceId`, `defaultVoiceSpeaker`, `defaultVoiceSpeed`,
  `defaultMapPalette`, `defaultPlacesSource`, `classicRoutePicker`.
- `tuning`: name-to-number dials read with `Calibration.tune(key, compiledDefault)`. View code
  reads `CalibrationStore.latest`. A switch that opens the app for inspection reads
  `AppTune.localOn`, the adb property alone, so no bundle can set it.
- `notices`: `id`, `level`, `title`, `body`, `url`. Level `urgent` is a modal dialog, any other
  a dismissable card on the map.
- `transformsJs`: JavaScript run by `JsSandbox` in Rhino with `optimizationLevel = -1` (ART
  cannot run Rhino's bytecode), `initSafeStandardObjects` (no Java or IO) and a 2 s kill switch.
  Keep rules are in `core/consumer-rules.pro`. `JsTransforms` exposes `parseSearch(rawResponse)`
  and `transformPlaces(placesJson)` over the flat `PlaceJson` contract. No script, a missing
  function or any error leaves the compiled Kotlin result. Logic it cannot express ships as a
  release.

#### Daily health check

`.github/workflows/google-health.yml` runs at 14:20 UTC and on dispatch. A failed scheduled run
mails the maintainer. Nothing is posted.

- `GoogleHealthProbeTest` runs the app's request builders and parsers with the repository's
  `calibration.json` from the Davis fixture (search, directions with and without avoid-highways,
  autocomplete, the review feed, photos) and prints `HEALTH|check|OK|BLOCKED|DRIFT|detail`.
- `DRIFT` (an unreadable answer, or the avoid flag ignored) fails the run. `BLOCKED` (403, 429,
  the sorry page, a consent wall: a datacenter IP's treatment) is a warning.
- The review-feed check retries after 3 s and 8 s. A withheld reply (a payload ending
  `true,[true]`) is `BLOCKED`.
- `scripts/check-chrome-ua.py` fails the workflow when the claimed Chrome major is behind a
  Windows stable major that has been out 7 days, or ahead of stable.

Locally: `./gradlew :core:testDebugUnitTest --tests '*GoogleHealthProbeTest' -DvelaLive=true --rerun-tasks`.

---

## 12. Degoogled constraints

Regressing one of these blocks a release.

- Location is AOSP `LocationManager`, never `FusedLocationProviderClient`.
- `LocationListener` is an explicit object, never a SAM lambda. On Android 10 and below the
  other three methods have no defaults, and a present-but-disabled provider throws
  `AbstractMethodError` at launch.
- Voice is AOSP `TextToSpeech` with a selectable engine, or the bundled neural voice. Google TTS
  is never required.
- Dictation is on-device or an installed recognizer app, never a cloud speech API.
- No GMS: no FCM, Firebase, Play Integrity or fused location. Push, if ever, is UnifiedPush.
- No static Google API key in any build variant.
- EU consent: `SOCS` and `CONSENT` are pre-seeded, and a `Set-Cookie` that would downgrade
  `CONSENT` to `PENDING` is dropped.
- The hidden WebViews run Google's JavaScript with no account, for data only a browser engine
  is served. No sign-in is offered.
- Onboarding is welcome, the Google choice, location, notifications, voice. The choice page
  (`GoogleChoice` in `WelcomeScreen.kt`) sets `GoogleFree` before `MapScreen` is composed, so
  no request to Google is made before the answer. "Use Google" is preselected. "Choose what
  Google is used for" under Continue opens `GoogleUsesSection` as a first-run page, with the
  same switches as Settings > Privacy. The same page asks about plate cameras (`CameraChoices`):
  "Route around them" sets `FlockRouteAlert`, "Warn me as I get close" sets the card and the
  spoken warning of `FlockNavAlert`. Both start off and are written on Continue.
- Settings > Privacy lists every switch that decides what Google is asked
  (`GoogleUsesSection`, shown while Google is on): the place source, what a place page loads,
  and what a route and a drive ask. Each row is a shared composable in
  `ui/settings/sections/GoogleUses.kt`, drawn again on its own page with its explanation. The
  list shows labels only.
- Permissions are asked in context. A
  coarse-only grant gets a one-time explainer and a true accuracy circle. Navigation needs fine
  location and offers an upgrade dialog. A permanently denied locate tap opens system settings.
- `MemoryPressure` passes `onTrimMemory` to registered releasers, and a new large or native
  allocation registers one. The speech model goes on a severe trim and after 120 s idle, never
  during a decode, where freeing it is a use-after-free. The neural voice goes on a critical
  trim only, since a reload mid-drive delays a prompt. MapLibre's native caches, every hidden
  WebView and the image cache go on a severe trim.
- `MemoryPressure.lowRam` selects a smaller image cache, no speech warm-up, no speculative
  WebView warm-up and an 8-term ambient fan-out. It is `LowRamMode.classify`: a Go-configured
  device, a 32-bit process (the limit is address space, whatever the RAM), a heap class up to
  128 MB, total RAM up to 2048 MB, or both of those unreadable.
- `ConstrainedNetwork` reads `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED` and `TRANSPORT_SATELLITE`
  by name through reflection. A platform without them reports false. On a constrained link the
  photo walk is skipped and the ambient fan-out takes the lean path.

---

## 13. Performance model

MapLibre's render thread serializes symbol placement, every `setGeoJson` and `setProperties`,
and drawing. The main thread hosts the camera tickers, all JNI style calls and Compose
recomposition. Neither can be parallelized, so the rules send each less work.

- Identity-gate every source upload and property write, and reset the gate holders on style
  reload.
- Never move route geometry per frame or on a short timer (section 4.8).
- Never re-place a layer for an unchanged value. A `setFilter` re-lays the whole source, and a
  `setGeoJson` re-places its whole layer.
- Per-frame work goes in the layout or draw phase, never composition. A value read per frame
  lives in a `mutableFloatStateOf` holder, not `MapUiState`.
- Gate high-frequency writes to `MapUiState`. The scale bar reports past a 1 percent change,
  the compass heading past 2 degrees and at most every 200 ms.
- A `queryRenderedFeatures` call in a dense view costs 37 to 55 ms even when it returns nothing.
  Debounce it, gate it on camera stillness, and never run it unthrottled from an idle event.
- A dense GeoJSON source needs a high `maxzoom` (section 5.3). Ambient places, traffic controls
  (up to 800 on a route), transit stops and result markers are 18, the camera layers 16, the
  accuracy circle 14, and the puck, parking, saved places and Street View 12.
- Build release for anything a person will feel. On a Pixel 4a the same place tap, sheet drag
  and review scroll gave 14.8 percent janky frames (90th percentile 69 ms) in debug and 1.1
  percent (28 ms) in release.
- `dumpsys gfxinfo` counts the Compose chrome and cannot see the map, which draws on its own GL
  thread. `VelaMapView` logs the map's frames once a second under `VelaFps` when the system
  property `debug.vela.fps` is set (read at map creation, so restart). `scripts/map-fps.sh` runs
  the loop and prints min, p10, median and max. A Pixel 4a holds 40 to 55 fps panning at browse
  zoom.
- A phone throttles after a few minutes. Check `dumpsys thermalservice` and alternate A and B
  runs.
- `:app:generateBaselineProfile` (`baseline-profile.yml`) writes
  `app/src/release/generated/baselineProfiles/baseline-prof.txt` on a Gradle-managed emulator,
  and a release build bakes in the profile it finds there. Never run it on a connected phone:
  the harness uninstalls the app, which deletes saved places, trips and grants.
- The journey (`BaselineProfileGenerator`) grants the app's permissions, leaves the welcome
  screen, takes the "no" side of each first-run prompt, pans the map and walks Settings. A
  prompt left up covers the map and the run records that prompt alone. After changing the
  journey, check that the file has `MapScreen` and `SettingsHub` entries.
- On a Pixel 4a a cold start reaches its first frame in about 1,100 ms with nothing compiled
  and 830 to 900 ms with the profile compiled. The libraries' own rules give most of that. The
  app's rules add about 50 ms and take the 99th-percentile frame of a first Settings visit from
  62 ms to 53 ms. GrapheneOS compiles the whole app at install (`speed`), so there the profile
  changes nothing.

---

## 14. Privacy, diagnostics and location hygiene

### 14.1 What leaves the phone

`PRIVACY.md` is the user-facing accounting and must agree with section 1.4. In the default
configuration browsing the map does not contact Google. Search, opening a place, a driving route
and transit directions do. Routes also reach FOSSGIS, transit boards Transitous, reverse
geocoding Nominatim. With "Use Vela without Google" on, only the open services are asked.

### 14.1a State files

- The offline stores' `index.json`, `revs.json` and `dead.json`, the region catalog cache and
  the diagnostics trim are written with `core/util/AtomicFiles` (temp file, `fsync`, rename). A
  torn index would read as nothing installed and the next write would save that. Readers see the
  old file or the new one, which covers `ObfRouteEngine` reading `obf/index.json` without the
  store's lock.
- The closed-open-places set is capped at `CLOSED_OPEN_PLACES_CAP` (2000, oldest dropped): every
  id is in a filter the places layers evaluate per feature.
- Viewed places (`files/placecache`, `core/data/PlaceCache`: one JSON per place, 400 files, 30
  days) and viewed panoramas (`files/svcache`) are plain files under one setting. An offline
  search result has no Google id, so a saved copy also matches on the same normalized name
  within `SAME_SPOT_M` (60 m). Nothing is saved while Google is off. "Clear history" and "Delete
  all offline data" empty both.

### 14.2 Diagnostics

- `DiagLog` is an opt-in breadcrumb ring, off by default, kept in a bounded
  `filesDir/diag_log.jsonl` so a report survives the process death that usually precedes it. The
  file is trimmed to the ring at load and every `CAP` (300) appends, and deleted on opt-out.
  `DiagExporter` shares it as a JSON file when the user asks. Nothing is uploaded.
- `DiagScrub`: a plain export rounds coordinate-shaped decimals to 2 places (about 1 km).
  "Redact places in exports" rounds to 1 place (about 10 km), replaces quoted text, drops a
  navigation start's destination label, keeps only the host of a URL, blanks `cid` values and
  drops page-text detail. Counts, zoom levels and error text stay. A breadcrumb that can carry a
  name or an address needs a case in `DiagScrubTest`.
- A page probe logs the path only up to `/@`, where Google puts a coordinate derived from the
  session.
- `SettingsDump.line` puts the settings on one line in a crash report and at the start of a
  recorded drive: switches, numbers and short option names. Free text and any key that could
  hold a position, an address, a contact, a name, a provider or a font are skipped.
- A recorded drive's notes say what it ran on and fetched. `data:` (at the start and on change)
  lists which of routing, the map, places, buildings, addresses, speed limits and signs come
  from the phone and which are streamed, as counts with no region named. `net 10 s:` counts the
  map's network requests by host, or by release for Vela's own files (`diag/NetCount`, an
  interceptor after the local-tile hook, never a path). `signs:` says whether the route's own
  set or the whole-view fallback filled the layer.
- Speed-limit and building overlays are not streamed where the phone has the data
  (`refreshMaxspeedOverlay`, `refreshBuildingOverlays`), and during a drive the whole-view sign
  path draws only what is on the route (`controlsOnRoute`).
- `NavTrace` (off by default) records one row per navigation frame: time, along-route progress,
  speed, bearing window, chord bearing, display bearing, camera bearing, frame dt. It holds no
  position, so unlike a recorded trip it can be attached to a public issue.

### 14.3 Sharing a trip

`TripScrub` trims a shared trip. Every fix within the chosen radius (200, 400 or 800 m,
`DEFAULT_RADIUS_M` 400) of the start, the end, the destination and the user's Home and Work is
deleted, and the middle keeps full precision. Rounding would protect the ends weakly and destroy
the geometry.

- An endpoint appears in six places and all are handled: the fixes, the `META` destination, the
  `META` label, the maneuvers, the route polyline's start and the spoken lines.
- Timestamps rebase to zero.
- An `S`, `J`, `B` or `K` event survives only if a fix within `EVENT_NEAR_MS` (3 s) survived.
- A route block whose polyline trims to nothing drops its `RD` and `M` lines.
- A trip that trims to nothing is never sent raw. A batch share leaves it out and counts it.

### 14.4 Location hygiene

Test coordinates, screenshot corners, sample addresses and "checked on a drive to X" lines add
up, in permanent public history, to where the author lives. Before a place, address or
coordinate enters the repository, ask whether it was chosen for a reason anyone could have. A
place that is the subject of a report, such as the business whose hours parse wrong, is fine.

- Locality is input, never output. Use it to reason, query and reproduce, and write up the
  mechanism only. A bug that cannot be explained without the place is not root-caused yet.
- Fixtures: Davis and Sacramento, California (box `38.30,-122.00` to `38.90,-121.20`, address
  `1451 W Covell Blvd, Davis, CA 95616`), San Francisco (`37.7749,-122.4194`) for a big city,
  `37.0,-122.0` for an abstract grid. Another area needs a reason, and a grid at the author's
  own latitude is not one.
- Screenshots use Simulate my location and Simulate driving. Check the corners: recents, place
  labels and street names.
- Recorded trips, diagnostics exports and adb dumps hold raw GPS. Never attach them to an issue,
  a commit or a CI artifact.
- `.github/workflows/location-guard.yml` scans added diff lines and commit messages against the
  repository secret `LOCATION_TERMS` (one term per line) and never prints the matched text.
  `scripts/check-location.sh` runs the same scan before a push. Neither sees issue bodies,
  comments, release notes or screenshots.

### 14.5 Attribution

No commit message, pull request body, issue comment, release note or file carries an AI
co-author trailer, a "generated with" line or any other AI attribution. This overrides any
tooling default. `scripts/check-writing.sh` checks commit messages for it, in the pre-push hook
and in `location-guard.yml`.

---

## 15. Build, release and distribution

- Toolchain: AGP 9.4.1 (Kotlin built in, no `kotlin-android` plugin), Kotlin 2.4.20, KSP 2.3.12,
  Gradle 9.8.0, Hilt 2.60.1, OkHttp 5.5.0, MapLibre 13.6.1, Compose, Java 17, compileSdk 37 in
  every module, targetSdk 35, minSdk 26, a version catalog, R8 in the `release` build type. The
  root `build.gradle.kts` pins the build tools' own libraries, none of which is in the app. CI
  validates the Gradle wrapper jar.
- Channels. A push to `main` or `canary` builds and tests only. The nightly prerelease
  `v0.5.<run>` is cut by `ci.yml` at 10:30 UTC when `main` has moved, or on dispatch, titled
  `Vela <version> nightly` with notes that open "Nightly build." `promote-stable.yml` (Mondays
  16:00 UTC) promotes the newest nightly to stable: same tag, same signed APK, no rebuild,
  retitled `Vela <version>` with the notes regenerated. Each push to `canary` replaces the one
  release on the fixed tag `canary`, which is not a `v0.*` tag. Its notes carry the
  `versionCode:` line the updater reads.
- `release.yml` is the manual path to a channel: input `channel` nightly dispatches `ci.yml`
  on `main` and waits for the nightly, and stable then calls `promote-stable.yml` with that
  tag. It refuses any ref but `main`, a `main` that moved after the run started, and a `main`
  that is not at the optional `sha`. `promote-stable.yml` takes `tag` (the nightly expected to
  be newest; another one stops the run) and `whats_new` (the hand-written list that leads the
  notes).
- versionName is `0.5.<run>` (`0.4.<run>` until 2026-10-08; the minor is a name, and the run
  number orders releases across minors) and versionCode `(2000 + run) * 10` plus a chip digit. The run
  number must stay in `ci.yml`: another workflow would restart the count.
- Never name a release `v0.5.0`. The updater takes the run number from the tag, reads code 2000
  and never offers it. Keep local development builds below versionCode 1000.
- Release notes are the commit subjects since the previous `v0.[0-9]*` tag
  (`scripts/changelog.sh`, which leaves out docs-only commits). The in-app dialog shows the body
  as it is, so a stable's notes lead with a hand-written "What's new" list.
- Docs-only pushes skip CI (`paths-ignore`). A mixed push builds. An `fdroid/metadata` change
  alone also starts no index rebuild.
- Nightlies keep a rolling 30. Stables are never pruned: they are the changelog, the bisect
  range and the record of reach.

#### One APK per chip type

With the repository variable `ABI_SPLITS` `true`, CI builds with `-PabiSplits` and a release
carries `<prefix>-arm64.apk`, `-armv7.apk`, `-x86.apk`, `-x86_64.apk` and `<prefix>-all.apk`
(prefix `vela-maps` or `vela-maps-canary`, `scripts/stage-apks.sh`). Without it, one APK.

- Chip digit on the versionCode: all-in-one 0, armv7 1, arm64 2, x86 3, x86_64 4, so moving from
  the all-in-one APK to a chip APK of the same build is an upgrade.
- The all-in-one name sorts first: GitHub lists assets by name and updaters older than
  `update/ApkChoice` take the first `.apk`.
- The single APK leaves out Cronet's x86 libraries (108.4 MB). A per-chip build keeps them:
  arm64 74.3 MB, armv7 35.4, x86 41.2, x86_64 41.5, all-in-one 121.9.

#### In-app updater

- `SelfUpdater` compares on the scale `2000 + run`. `legacyCode` divides a code of 20000 or
  more by ten.
- Stable reads `releases/latest`, nightly lists tags through `git/matching-refs/tags/v0.` (once
  per check; `appReleaseTags` keeps each run's real tag name) and reads `releases/tags/<tag>`,
  canary reads its rolling tag. The releases list is never fetched:
  the data releases carry about 450 assets each and made a check 4 to 9 MB.
- A stable check with nothing newer is 1 request. Nightly and canary checks are 2 to 4. An
  offered update adds at most `HISTORY_MAX_RELEASES` (8) for the notes of the versions between
  installed and offered. Every check logs one `VelaUpdate` line.
- `ApkChoice` picks the file for `Build.SUPPORTED_ABIS`. The download uses a client with no call
  timeout. `ApkCheck` requires the zip magic bytes, the release's size and its SHA-256 when
  GitHub publishes one.
- The system installer gets the file through the FileProvider and enforces same package and
  same signature.
- The launch check (pref `self_update_check`) runs at most every 20 hours. "Not now" pins the
  dismissed code in `update_dismissed_code`.
- `InstallSource`: Android Auto lists a sideloaded navigation app only when its install source
  is Play, which AAEnabler and King Installer set and a self-install overwrites. When the
  installing or initiating package is Play, the downloaded APK is offered as a file for that
  tool. "Update anyway" installs it and loses the listing.

#### F-Droid and GitHub Pages

- `fdroid-repo.yml` rebuilds the signed repository (latest stable plus the newest nightly) and
  deploys it to GitHub Pages. `CurrentVersionCode` is pinned to the stable's highest
  versionCode, read off its APKs, so default clients update weekly.
- Files are renamed by tag, and the all-in-one APK is dropped where chip APKs exist.
- `fdroidserver` comes from `tools/fdroid-requirements.txt`, which fixes every version, because
  the job holds the index key.
- It triggers on `workflow_run` of CI and the promotion: a release created by CI's own token
  fires no event for other workflows.
- The landing page (`site/`), the map fonts and the docs site ride the same Pages artifact.
  Never add a second Pages workflow, since `actions/deploy-pages` replaces the whole site. Never
  re-run only a failed deploy job. Dispatch a fresh run.
- The docs site (`/Vela/docs/`) is the repository's Markdown built with MkDocs Material by
  `scripts/build-docs-site.py --strict` and `site/mkdocs.yml`, toolchain pinned in
  `site/requirements-docs.txt` (MkDocs 2 drops the plugin and theme system). A page is published
  only if it is in `PAGES` and in the nav. No request leaves the site: system fonts, no
  `repo_url`, a local search index. `?q=` opens search. A push to `main` that changes Markdown,
  `docs/**` or `site/**` redeploys.

#### Keys

The release keystore is outside the repository (`~/.vela-signing/`), with CI secrets
`VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`. Losing it means installed
builds can never update. The certificate's SHA-256 is in README and FDROID.md
(`apksigner verify --print-certs`) and is not the F-Droid index fingerprint. The calibration key
is separate and also never committed. The `MAPTILER_KEY` secret reaches `BuildConfig` only.

#### Other workflows

- `weblate-automerge.yml` merges a Weblate pull request once CI passes and
  `scripts/weblate-automerge-check.py` finds only `strings.xml` translations with matching
  placeholders. Anything else waits for a person. Off switch: repository variable
  `WEBLATE_AUTOMERGE=off`.
- `security.yml` runs mobsfscan, exports an SBOM and reviews new dependencies on pull requests.
  `scorecard.yml` runs OpenSSF Scorecard. Neither gates a release.
- `issue-triage.yml` comments on a new issue with earlier ones that share its wording and labels
  a bug report from an outdated build `incomplete`. It never closes one.
- `download-stats.yml` snapshots release download counts, repository traffic and per-region
  asset counts weekly into `docs/stats` (`scripts/download-stats.sh`). They are byproducts of
  hosting and they expire. `docs/stats/README.md` says how to read them.
- `bake-conductor.yml` starts the data bakes (section 7.3).

#### On a device

Build release at the installed build's version code (`-PappVersionCode=`), `adb install -r`,
then `am force-stop` before `am start`: installing over a running app keeps the old dex. Never
`adb uninstall`. It deletes saved trips and grants.

#### MapScreen size limits

`MapScreen` is at the JVM's 64 KB method limit and at ART's lower verifier limit. Direct
composable calls and their arguments count. Content lambdas do not.

- A debug compile failing with "method too large" is fixed by moving a call with a long argument
  list into a private composable.
- Past the verifier's limit the release build compiles and fails on the phone:
  `VerifyError: Verifier rejected class` at launch on Android 14, and on Android 16 `MapScreen`
  stops recomposing after its first frame with nothing logged. After changing its direct calls,
  install a release build and read the log for `VerifyError`, or dispatch
  `old-android-smoke.yml`.
- Split out so far, private composables in `MapScreen.kt`: `MapSurface`,
  `BoxScope.BuildingDebugBadge`, `BoxScope.NavTurnBanner`, `SearchEntryHost`, `PipNavOverlay`. A
  split block takes its locals as parameters, and a value one child needs is resolved in the
  child (`isMapDark()` in `MapSurface` and `ScaleBarReader`).

---

## 16. Where the rest lives

| File | Holds |
| --- | --- |
| `README.md` | What Vela is, what reaches Google, install |
| `FEATURES.md` | A short list of what the app does |
| `SPEC.md` | This reference |
| `docs/README.md` | The docs site's index page |
| `docs/book/` | Each subsystem explained, 13 chapters |
| `ROADMAP.md`, `docs/ROADMAP-HISTORY.md` | Open work; what shipped or was dropped |
| `PRIVACY.md` | What each service receives |
| `SECURITY.md` | Security checks, reporting a vulnerability |
| `CONTRIBUTING.md` | How to contribute |
| `FDROID.md` | The F-Droid repository |
| `CLAUDE.md` | Maintainer rules and traps |
| `docs/FAQ.md` | First questions |
| `docs/BUILDING.md` | Building from source |
| `docs/TRANSLATING.md` | Languages and translating |
| `docs/dpad.md` | Use without a touchscreen |
| `docs/ANDROID-AUTO.md` | Android Auto for users |
