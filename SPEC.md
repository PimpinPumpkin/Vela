# Vela Maps — Specification

> The single authoritative description of **what Vela is, how it's built, and every
> load-bearing decision** — the target to rebuild against if the codebase is ever
> lost or reimplemented. Paired with [`FEATURES.md`](FEATURES.md) (the exhaustive
> feature catalogue), [`README.md`](README.md) (the public overview + calibration
> walk-through) and [`CLAUDE.md`](CLAUDE.md) (build rules + gotchas). When behaviour,
> calibration, or architecture changes, **update this file in the same commit.**

Last reviewed: 2026-06-18.

---

## 1. What it is

Vela Maps (`app.vela`) is a **degoogled, keyless Google-Maps replacement for
Android** — "the NewPipe for Maps." It gives Google-parity search, places, routing,
traffic-aware ETAs and turn-by-turn navigation on a phone with **no Google Play
Services** (GrapheneOS / no-GMS ROMs), distributed via F-Droid/Obtainium, GPLv3.

### Ethos / non-negotiables
- **No Vela backend.** Every install talks to Google directly from the user's own
  IP, behaving like one logged-out browser. No shared API key, no server farm.
- **No static shared Google API key, ever.** Per-user `GoogleSession` bootstrap only
  (this is the NewPipe legal footing).
- **Open basemap, scraped intelligence.** The map tiles are open vector tiles
  (keyless OpenFreeMap), recoloured at runtime. Only POIs/routing/traffic are scraped
  from Google's *public web* endpoints — the same ones `maps.google.com` calls.
- **Degoogled at runtime** (hard rules, §6): AOSP location/TTS only, no FCM/Firebase/
  Fused/Play Integrity.
- **Maintenance is a lifestyle.** Google rotates these endpoints; the extractor is
  built to be **re-calibrated and even hot-patched without an app update** (§5).

### Non-goals
- Street View (panorama tiles are key-gated), photo author/date (not in the photos
  RPC), turn-by-turn *offline* routing (heavy native engine), account sync. See
  `FEATURES.md` "Known debts."
- **Popular/busy times — DONE keyless (2026-06-19), not a non-goal.** Earlier I wrongly
  ruled it sign-in-gated: the histogram (`[84]`) is stripped from the keyless **OkHttp**
  search (bot-degraded, like photos/transit), but a **warmed hidden WebView's same-origin
  search returns the full response with `[84]`** (`WebPopularTimesFetcher` + `Popular-
  TimesParser`, same trick as photos). **Load-bearing nuance:** the WebView query must be
  **specific (name + address)** — a bare-name search still comes back as a 20-result
  `[64]` list trimmed of `[84]`, whereas name+address resolves to the single focused
  result (`[0][1][0][14]`) that keeps it. Lesson: a "needs login" call from the OkHttp
  response alone should be re-checked through a real WebView engine.

---

## 2. Architecture

Two Gradle modules, strict boundary:

- **`:core`** — the UI-agnostic *extractor* (NewPipeExtractor pattern). Models, the
  `MapDataSource` seam, the Google scraper, parsers, pb builders, polyline codec, the
  pure nav engine, location/voice/haptics abstractions, the remote-config layer, and the
  opt-in **diagnostics** ring (`core/diag/DiagLog` — off by default; the scraper + nav
  record breadcrumbs only when enabled; `app/diag/DiagExporter` shares them as a JSON
  bundle, user-initiated, never auto-uploaded — the no-backend half of the telemetry plan).
  **No MapLibre or Android-UI types may leak in** (convert `LatLng` at the view edge).
- **`:app`** — the Compose UI + MapLibre Native 11.8.0 + the foreground nav service +
  the two hidden WebViews. Root package `app.vela`, app class `VelaApp`, config
  `VelaConfig`.

### Key seams
- **`MapDataSource`** (`:core/data`) — the one interface the UI depends on.
  - `MockMapDataSource` (default; keeps the whole app usable offline) and
  - `google/GoogleMapsDataSource` (the real scraper). Flip with
    `VelaConfig.USE_GOOGLE_SOURCE`.
- **`GoogleSession`** — bootstraps a logged-out session (cookies via an in-memory
  `InMemoryCookieJar` pre-seeded with Google's `SOCS`/`CONSENT` consent cookies so an
  EU session isn't bounced to `consent.google.com`).
- **Process-wide reactive holders** (a `mutableStateOf` mirror + `SharedPreferences`,
  `init()`-ed in `VelaApp`): `ui/Units` (metric/imperial), `ui/theme/AppTheme`
  (Light/Dark/System — read via **`isAppInDarkTheme()`**, never `isSystemInDarkTheme()`),
  `ui/Traffic` (overlay on/off), `ui/Onboarding`.

### Data flow (search example)
`MapScreen` → `MapViewModel.search()` → `GoogleMapsDataSource.search()` →
build `pb` (`SearchPb`) + `GET` → **optional JS override** (`JsTransforms`, §5) →
`GoogleResponse.parse` → `SearchParser.parse` (positional indices from
`Calibration.paths`) → **optional JS post-process** → `SearchResult` → UI.

---

## 3. The extractor (calibrated contract)

> These field numbers / array indices are what *drift* when Google reshapes things.
> The **live source of truth is [`calibration.json`](calibration.json)** (fetched at
> runtime, §5); `Calibration.DEFAULT` is the compiled fallback and must be kept in
> sync at release. The README §"How the scraping works" has the prose walk-through.
> `PbBuilder` grammar and `PolylineCodec` are **stable**; field indices are **not**.

### Endpoints (all keyless, google.com only — host-allowlisted)
| Purpose | Request |
|---|---|
| Search | `GET /search?tbm=map&q=<q>&pb=<SearchPb>` |
| Directions (turn-by-turn) | **PRIMARY: FOSSGIS OSRM** `route/v1?steps=true` (`routed-car`/`-bike`/`-foot`) — full street-named maneuvers + geometry |
| Directions (traffic ETA + fallback) | `GET /maps/preview/directions?pb=<DirectionsPb>` — Google's live-traffic ETA/spans, overlaid on the OSRM route; also the fallback router |
| Directions (traffic-aware path, option 3) | when Google's route diverges >700 m from OSRM's (jam reroute), OSRM `route/v1` **through ~12 vias sampled off Google's polyline** → Google's path with full OSRM steps. `/match` map-matching would be cleaner but FOSSGIS caps it at 10 coords — see "Why dense-via, not map-matching" |
| Reviews | `GET /maps/preview/review/listentitiesreviews?pb=!1m2!1y<HIGH>!2y<LOW>!2m2!2i0!3i20!3e1!5m2!1svela!7e81` |
| Photos (full gallery) | `POST /maps/_/MapsWizUi/data/batchexecute?rpcids=hspqX` (proto in calibration) |
| Transit | hidden WebView on `/maps/dir/<o>/<d>/data=!4m2!4m1!3e3` (see below) |
| Reverse-geocode | OSM **Nominatim** `/reverse` (NOT Google — Google `tbm=map` won't reverse a lat,lng) |
| (legacy) Google route geometry fallback | when OSRM is down, Google's directions + OSRM geometry fill |

### Search response (`root[64][i]`, each entry rooted at `[1]`)
name `[1][11]` · full address `[1][39]` (fallback: join components `[1][2]`) · rating
`[1][4][7]` · reviews `[1][4][8]` · lat `[1][9][2]` · lng `[1][9][3]` · category
`[1][13][0]` · website `[1][7][0]` · phone `[1][178][0][0]` · price `[1][4][2]` (a
dollar *range* "$10–20", not a 1–4 level — the tier flag lives in the `[1][4][9]`
bucket tree; `SearchParser.priceLevelOf` derives a 1–4 from the label for the filter) ·
open-status `[1][203][1][8][0]` · rich status `[1][203][1][4][0]` · **feature id**
`[1][10]` (`0x..:0x..` → reviews) · place id `[1][78]` · photos
`[1][72][0][i][6][0]` (FIFE URLs, re-size `=w500-h350`; **moved from `[105]` 2026-06-27 — fix shipped via calibration v7**) · featured review
`[1][142][1][0][1][0][0]` · About sections `[1][100][1]` (title `[s][1]`, items
`[s][2][j][1]`) · **editorial one-liner** `[1][32][1][1]` · **owner "From the owner"
blurb** `[1][154][0][0]` · weekly hours `[1][203][0]` (fallback `[1][118][0][3][0]`; 7
entries from today, name `[0]` + text `[3][0][0]`). A **far/specific address** is a
single geocoded result at `[0][1][0][14]` (same schema), not a `[64]` list. **"People
also search for"** is a sibling of a focused result at `root[2][11][0]` (path `similar`),
each entry `[featureId, name, [[_,_,lat,lng], …, rating@6]]` — `SearchParser.parseSimilarPlaces`
attaches it to the primary place (focused searches only; absent from multi-result lists). The
`[84]` histogram **and** `[32]`/`[154]` descriptions are trimmed from the keyless/list
response → fetched lazily via the WebView (`WebPopularTimesFetcher` → `PlaceDetails`).
The same focused re-fetch **backfills the fields a *summary* node drops** — a suite/
multi-tenant address snaps to a light node missing review count / full hours / address /
phone / price / attributes; the focused result is a full node, so `PopularTimesParser`
lifts them into `PlaceDetails` (**feature-id-gated**) and `MapViewModel.fetchPlaceDetails`
merges them into any blank field (additive; no match → unchanged).

### Directions response (`root[0][1][r]`, summary `[0]`)
distance m `[2][0]` · typical dur s `[3][0]` · **traffic dur s `[10][0][0]`** ·
overall traffic level `[10][2]` · **typical spread `[10][4]` = `[lowSec, highSec, label]`**
(Google's own depart-time hint, "usually 1 hr 8 min to 1 hr 27 min" → `Route.typicalRangeSeconds`) ·
**per-route geometry** = delta-encoded E7 arrays at
`[0][7][i]` (lat deltas `[i][0]`, lng deltas `[i][1]`, first element absolute E7;
`[i][4]` is elevation, NOT traffic) — index-aligned with summaries, so alternates draw
real roads. **Per-segment live traffic** at `route[3][5][0]` (note: hangs off the route
node, NOT the `[0]` summary) = `[level, startMeters, lengthMeters]` spans, only the
non-free-flow stretches → `Route.trafficSpans` → the route line's colour bands. Steps
are `<step maneuver=… meters=…>` markup; lane hints ("Use the right 2 lanes…") split
into `Maneuver.laneHint`. Each maneuver is placed at `cumulativeStepMeters/polyLength`
along the geometry — **except the final (ARRIVE), which is pinned to the route end**:
step distances total a few % short of the geometry, so the cumulative undershoots and
once placed "arrive" ~15 km before a 134 km route's end (firing the 25 m arrival trigger
there — a real test-drive bug, fixed 2026-06-20).
**Predictive per-departure field: confirmed unreachable keyless** (2026-06-20, 6th probe):
the response has no time-of-day curve, our `pb` is byte-identical to Google's live web
client, injected time fields are ignored/400, and the web depart-time control is
un-automatable → it's login/Android-app-only. We surface the typical spread (`[10][4]`,
above) as the honest keyless stand-in; a true per-minute ETA needs one captured real
depart-at request (mitmproxy on the Android app — see `ROADMAP.md`).

### Hours node (`[1][203][0]`) — date-specific, holidays baked in (observed 2026-07-01)
Each day entry is `[name, dow(1=Mon..7=Sun), [Y,M,D], ranges, flag, flag, special?]` — a **rolling next-7-days**
list keyed to the ACTUAL date, so **holiday overrides are already in it**: a Jul-4 bank showed
`["Saturday",6,[2026,7,4],[["Closed"]],1,2,["4th of July hours","4th of July",1]]`. `ranges` = `[[text,[[openH],[closeH]]], …]`
(MULTIPLE per day — split shifts); `special[1]` = a holiday label ("4th of July"). `readHours` joins all
ranges and appends `" · <label>"`; `OpeningHours` strips the label, so the open/closed FALLBACK is holiday-aware
for free (it computes from today's date-specific, holiday-adjusted entry). Google's live status STRING stays
PRIMARY (it's the only source for an owner's ad-hoc "closed today", which is NOT in the scheduled ranges).

### Directions — untapped capabilities (observed on the wire, not from the binary)
A running "port these into our own code" list, derived purely from the response we already receive
(clean-room = protocol observation, never decompilation):
- **Per-route in-traffic ETA → RE-RANK (DONE 2026-07-01).** Each route's `[10][0][0]` is *its own*
  `duration_in_traffic` (not one shared figure — the earlier "can't re-rank" note was wrong). `directions()`
  now sorts the returned list by it, so the fastest-right-now route leads, Google-style.
- **Overall per-route traffic grade `[10][2]`** — a coarse congestion score per alternate; candidate for
  colouring the ETA chip / a "heavy traffic" badge on a route without walking every span.
- **Lane guidance inside Google's `<step>` markup** — Google's own per-step lane data (we currently take
  lanes only from OSRM); worth capturing so the *Google-path* alternates get lane diagrams too.
- **Route-preference request flags** (avoid tolls / highways / ferries) — `pb` REQUEST fields in
  `DirectionsPb`, not response; a known-hard TODO, needs one captured toggle to pin the field.
- **Not-yet-parsed response nodes:** route warnings/advisories, toll cost, per-step road-name/ref (would
  give named turns without OSRM), and the rest of the `[10]` node beyond `[0]/[2]/[4]`.
- **The nav "polish" is NOT in this protocol** — GPS-jitter attenuation, accelerometer-fused dead
  reckoning, junk-fix rejection, buttery puck — that's client-side **sensor fusion** (Kalman/complementary
  filter + IMU dead-reckoning + accuracy-gated outlier rejection + map-match smoothing), documented in
  public literature, NOT Google's binary. Vela already does the biggest lever (map-match snap during nav);
  the gaps (IMU dead-reckon, accuracy gating) build straight from raw `SensorManager`. Spec it from the
  literature + on-device tuning when prioritised — no disassembly, fully portable.

### Traffic-aware path (option 3) — why dense-via, not map-matching (measured 2026-06-28)
Google's `[0][7][i]` polyline is **complete** even when its steps are abbreviated (3.3 km of
steps seen on a 6.4 km line), so we *can* trace Google's exact jam-avoiding path. The clean way
is **map-matching** (snap a trace → roads + turns), but public infra won't give it: FOSSGIS
**`/match` caps at 10 coords** (`TooBig` past that; ~0.01 confidence that sparse) and public
**Valhalla `/trace_route` times out**. So `RouteGeometry` snaps via **dense-waypoint `/route`**
instead — `sampleVias` takes ~12 interior points of Google's line, `routeVia` routes OSRM through
them (no coord cap; path reproduced exactly, 0 U-turn artifacts). Tradeoff, measured: a via that
lands *on* a turn is encoded as a via arrive/depart, not a turn — **~1-in-10 named turns lost** at
60 vias. So we keep vias modest (12) and gate the whole thing behind real divergence
(`divergent`, >700 m), leaving the free-flow majority as pure-OSRM with perfect turns. We also
**only LEAD with the snapped route when it earns it** — its live in-traffic ETA must be within
`SNAP_ETA_MARGIN` (×1.2) of OSRM's free-flow best, so a divergent-but-not-actually-faster snap steps
aside for OSRM's clean route instead of being forced to the top (the `directions` diag logs `gEta`/`osrmFF`
to tune this from real side-by-side data). A true per-alternate re-rank isn't possible — Google returns one
live-traffic figure, so the overlay scales every route by the same ratio and can't reorder the alternates. The
cleaner unconditional "Google routes, OSRM names turns" wants **on-device map-matching** — now shipped as
the offline router (next para); using it to clean up the online snap is the Phase-2 follow-up (`ROADMAP.md`).

**Multi-stop (waypoints, DONE 2026-07-01).** `directions(origin, dest, mode, waypoints)` — when `waypoints`
is non-empty it reuses the same `routeVia` machinery (OSRM through `origin → stops… → dest`, the per-via
arrive/depart already filtered into one continuous trip) and overlays Google's in-traffic ratio for the ETA
(`applyTrafficRatio` — scales duration only; the direct-path congestion spans wouldn't align to a through
path so they're dropped). A waypointed trip returns a **single** route (the alternates/divergence-snap logic
is skipped). The app holds the stops as `MapViewModel.directionsWaypoints` (a stop-pick mode mirrors the
origin picker). **Follow-ups DONE 2026-07-01:** `NavEngine.stopMarks(route, stops)` projects each waypoint
onto the route line → its along-route metre "pass mark" (or null if >150 m off the line); `NavSession` holds
the stops + marks + a `passedStops` counter and fires a **per-stop voice cue** ("You've reached &lt;stop&gt;")
in order as `traveledM` passes each mark (unit-tested `NavStopMarksTest`; `NavEngine` stays pure). `reroute`
and the faster-route `maybeRecheck` now call `directions(loc, dest, mode, remainingStops)` where
`remainingStops = stops.drop(passedStops)` — so going off-route (or taking a faster route) keeps the stops
you haven't reached, instead of dropping them; the `reaches(dest)` guards are unchanged (the route still ends
at the same final dest). The panel has up/down **reorder** arrows (`moveStop`).

**Offline routing (on-device, DONE 2026-06-30).** When OSRM is unreachable, `directions()` routes fully
on the phone via **GraphHopper** (`core/data/GraphHopperRouteEngine`, pure-JVM on ART — three workarounds:
MMAP data-access, a Janino-free `SpeedWeighting` factory, swallowed `close()`; Contraction Hierarchies →
~200 ms). Region **CH graphs are built off-device** (`tools/graphbuilder`, same weighting + CH) and **hosted
as GitHub-release assets** — a **137-region world catalog** (`tools/routing-regions.json` → a race-safe
GitHub-Actions build matrix → `routing-manifest.json`). The app downloads regions into `filesDir/graphs/<id>/`
(by picker, or bundled with an offline-tiles area download) and routes a trip on the **smallest installed
region box covering both endpoints** (boxes overlap at borders; falls through to the next-smallest). A trip
must fit one region's monolithic graph; cross-region falls online.

### Reviews / Photos / Transit (the hard ones)
- **Reviews**: DOM-scraped from the place's own `?cid=` page in a hidden anonymous desktop-UA WebView
  (`WebReviewsFetcher`) — the keyless `listentitiesreviews` RPC is **dead** (404) and only ever served
  avatars anyway. `?cid=` = the `LOW` half of the `0xHIGH:0xLOW` feature id as unsigned decimal. **Three
  things that were silently capping it at ~3, fixed 2026-07-01:** (1) the hidden WebView is never attached,
  so it was **0×0**; Google's review list is **virtualized/lazy-loaded off the scroll viewport**, so at
  0×0 it renders the chrome (rating histogram, topic filters) but **no review cards** — `measure`+`layout`
  the WebView to a real **1200×3200 offscreen viewport** and the `m6QErb` scroll pane becomes real +
  pages. (2) cards are **`.jJc9Ad`** each with a unique **`data-review-id`**; the scraper selects those
  directly and **accumulates across scroll windows de-duped by review-id** (the panel recycles DOM nodes,
  so one snapshot = ~10; the union = the list). (3) **on busy business pages** (food/retail — attractions
  were unaffected) the list takes **~8 s to render** after the tab click, and the idle-termination
  (`atBottom && noGrow`) mistook that blank pre-render window for "done", bailing with only the 3 overview
  cards. Two guards fix it: open via the **`[role="tab"]` "Reviews" tab**, clicked-until-`aria-selected`
  (a click on a not-yet-hydrated tab retries rather than no-opping; a selected-but-loading list is never
  re-clicked — re-clicking restarts its render, which regressed busy pages back to 3), and **gate the
  idle-bail on cards being rendered at bail time** (`cardsNow`, checked per tick — NOT a once-latched
  flag: the overview's 3 preview cards latch just before the tab click blanks the panel, and that
  timing hole still bailed with 3 on unlucky loads; a no-tab/no-button layout gets a 14-tick leash
  then returns what's rendered). Per card: author
  `.d4r55`, text `.wiI7pd`, rel-date `.rsqaWe`, star aria, avatar + **uploaded photos** (googleusercontent
  background-images, avatars/ALV-/ACg8oc filtered). A "More reviews" button is the fallback for layouts
  with no Reviews tab; dwells at the bottom to let the lazy-loader page in more, caps at 50. **Per-review
  photos ARE delivered this way** (the old RPC's avatars-only limitation doesn't apply to the rendered
  page). Device-verified: Taco Bell **3 → 50**, Pike Place Chowder **3 → 37**, Space Needle **37 → 46**.
  The place sheet adds a **"Search reviews"** box (≥5 loaded) that live-filters the loaded set by
  text/author. While the scrape runs (~10–40 s on busy places) the scraper **streams its running count**
  over the bridge (`onProgress`) and the tab shows "Loading reviews… N of ~min(count,50)" with a
  determinate bar — real progress, not a bare spinner.
- **Photos**: the full gallery is **scraped from the place's own `?cid=` page** (2026-06-28),
  *not* the `hspqX` RPC — that RPC is **bot-degraded per-session** to a Street-View-only reply
  (an on-device log showed byte-identical degraded replies across retries, so retrying never
  recovers it). `app/web/WebPhotoFetcher` loads the `?cid=` page in a **hidden, anonymous,
  desktop-UA WebView**, lets Google's JS render it, and a self-polling script scrapes
  `googleusercontent` photo URLs from the DOM (avatars + Street View filtered, de-duped by
  image id, clicks the "Photos" affordance + scrolls to surface more) — **same pattern as
  `WebReviewsFetcher`**; a rendered page is far harder to bot-degrade than a naked RPC POST.
  ~9–25 photos/place, on-device-verified; a shimmer row (`MapState.photosLoading`) shows while
  it's in flight. Gotchas: desktop UA (mobile → `intent://`), block non-http(s) redirects,
  `Handler` not `View.postDelayed` (headless WebView never attaches). No contributor name from a DOM
  scrape (that was an `hspqX`-only field). **Categories (2026-07-01):** Google keeps the gallery tabs
  (Menu / Food & drink / Vibe / By owner) in the `?cid=` DOM, so the scraper **visits each tab** (click →
  scroll → tag its photos) then sweeps "All" for the rest, returning `category⇥url` lines → `Photo.category`
  / index-aligned `Place.photoCategories` → filter chips on the sheet. **Posted-dates deferred:** the tiles
  carry only the URL; the date is per-**focused** photo (lightbox), too costly to harvest per tile.
- **Transit**: a plain keyless transit request is silently downgraded to *driving*, so
  `app/web/WebDirectionsFetcher` navigates the `/maps/dir/…/data=…!3e3` page and reads
  `window.APP_INITIALIZATION_STATE`. The payload is the **longest** `)]}'`-guarded
  string under slot `[3]` (poll for it; a stub sits alongside). `TransitParser`: trips
  `root[0][1]`, each trip summary at `trip[0]`, legs at `trip[1][0][1]`.

### Hybrid model (load-bearing)
Basemap POI icons/labels are **OSM** (OpenFreeMap), the opened sheet is **live
Google** — the two can disagree (an OSM `drinking_water` node that's really a "Primo
Water" kiosk inside a store). `onPoiTap` searches the OSM name on Google and, among
listings within 35 m, picks the most-reviewed **only when it clearly dominates**
(`canonical.reviews >= 2·nearest.reviews + 5`) so co-located-but-distinct shops aren't
wrongly merged; "Also at this location" lists the others.

### Traffic overlay
Two layers. **(1) Whole-map raster** — `/maps/vt` PNG tiles on `www.google.com`
(public, keyless, not bot-gated; the `incidents` params carry the data, NOT the
map-version epoch); `VelaMapView.ensureTraffic` adds a `RasterLayer`. A manual toggle on
the bare map; **off during navigation** (it washed every road). **(2) Per-segment route
line** — the directions JSON *does* carry per-segment congestion after all
(`route[3][5][0]`, see above); `VelaMapView.routeGradientStops` paints it onto the route
line as Google-style colour bands over the driven-grey gradient. So during nav the route
itself shows the traffic, not the whole map.

---

## 4. Map / UI

- **Basemap**: OpenFreeMap **Liberty**, loaded **by URL** (`fromUri`, the only thing
  that renders vector tiles on-device — a bundled/`fromJson` style blanks the vector
  source). Recoloured at runtime in `VelaMapView.applyLight/applyDark` to a Google-clean
  look (white roads, faded casings = outline-free, soft-yellow motorways, neutralised
  landuse, killed fill-patterns). Hillshade relief from the keyless terrarium DEM
  (`encoding="terrarium"`). Custom Google-style POI markers (`PoiIcons`, Material-icon
  glyphs over coloured circles), nameless POIs hidden, transit density tuned to z16+.
  Font is **Noto Sans** (Roboto is definitively blocked keyless — no server serves the
  glyphs + bundling blanks the map; don't re-chase).
- **MapTiler** Streets is wired as an alternative (needs the `MAPTILER_KEY` secret,
  never committed) but **off** (`USE_MAPTILER=false`) for keyless full control.
- **Compose UI**: place sheet (fixed Google-grey palette via `ui/SheetPalette`, NOT
  Material-You — deliberate), full-screen search page (Home/Work + saved + recent
  places + recent searches), directions panel (alternates, swap, depart-time chooser,
  search-along-route), nav overlays (maneuver banner with shields/lanes, speedometer,
  re-center). MapLibre camera shifts its optical centre up via bottom padding so sheets
  don't occlude the pin/route.
- **Navigation**: foreground `NavigationService` + shared `@Singleton NavSession`
  (survives backgrounding/screen-off, persistent notification, ~2-min faster-route
  re-check, arrival summary). Spoken `VoiceGuide` (AOSP TTS, best offline voice) +
  direction-coded `Haptics`. Heading-up tilted camera, blue-dot + heading cone.
  `NavEngine` (pure, unit-tested) tracks **monotonic forward progress** (`NavState.traveledM`,
  windowed projection in `projectAlong` — not global-nearest) so "remaining" and "distance to
  next turn" stay **along-route** and honest on routes that pass near themselves
  (switchback / cloverleaf / out-and-back); off-route it holds rather than snapping to a far leg.
- **Nav puck motion model** (`VelaMapView`, `NavPuck`): the displayed position during
  nav is decoupled from the raw GPS fix. A `withFrameNanos` ticker glides the puck
  **monotonically forward along the route** by metres-along (`cumLengths`/`pointAtMeters`),
  **dead-reckoned** and **eased** (τ≈0.25 s), with **heading smoothed** (`smoothBearing`,
  τ≈0.2 s). The dead-reckoned speed is a **1-D Kalman fusion** (`core/location/SpeedKalman`,
  pure + unit-tested): each GPS fix is the measurement update, and between fixes the
  **accelerometer steers the prediction** — `MotionProvider` (`core/location`, raw
  `TYPE_LINEAR_ACCELERATION` + `TYPE_ROTATION_VECTOR`, no GMS) emits world-frame horizontal
  acceleration, `forwardAccel()` projects it onto the travel bearing, and the ticker runs
  `kalman.predict(a, dt)` per frame — so braking collapses the modelled speed immediately
  instead of the puck gliding at the stale fix speed into the monotonic-progress trap (the
  "puck sits ahead of me when I stop" bug). The advance is the **integral** of that speed
  (`reckonedM += v·dt`, reset per fix, blind-capped at 2 s). Missing sensor → `a = 0` →
  the old constant-speed reckoning. Each fix is snapped (`snapToRoute`, §honest-snap) then
  its metres-along advance is **plausibility-clamped** (`speed·Δt·2.5 + 60 m`) so a
  self-approaching route can't teleport the puck to a far leg. The **follow-camera targets the puck's smoothed point** (`NavPuck.drawn`), not
  the raw fix, so map + puck move as one. The ticker owns `ME_SRC` while navigating;
  `applyData` only drives it in browse / off-route. **Heading/speed are derived from
  consecutive fixes** (`MapViewModel`, `bearingBetween`) when a `Location` lacks them — gated
  on real movement — so the puck still points and dead-reckons on bearing-less fixes. (Whole
  nav stack verified on-device 2026-06-21 via a mock-GPS drive of a real Davis→Sacramento route.)
- **Deep links**: `MainActivity` is `singleTop` with intent-filters for `geo:` and
  Google-Maps web links; `MapLinkParser` (`:core`, pure-Kotlin, unit-tested) →
  `openDeepLink`. Sharing a place emits a keyless `geo:` pin too.
- **Trip recording + offline nav audit**: opt-in trip recording writes each drive to a local
  CSV (`:app/replay/TripStore` does the IO); the **format is canonical in `:core`**
  (`replay/TripLog`: `META` header, the navigated route as `RP`/`RD`/`M` lines, then
  `lat,lng,t,bearing,speed` fixes). Saving the route means a replay drives the **exact route
  the user navigated** (not a re-route), and `nav/NavReplay` can replay the fixes back through
  the real `NavEngine` to **diff cards/voice against the maneuver positions** — per-maneuver
  announce-distance / turn-now / worst card-error / nearest-approach, flagging silent turns,
  too-early announcements and lying distances. `TripLog.audit(csv)` is the one-call entry;
  unit-tested clean-drive + flag-logic + CSV round-trip. (Pure `:core`, Android-free.)

---

## 5. Remote resilience (push fixes without an app update)

The differentiator: when Google breaks something, ship the fix **over a signed channel**,
not an APK. `CalibrationStore` (`:core/config`) fetches **`calibration.json` + a detached
`calibration.json.sig`** from the repo raw URL at launch and adopts the remote only if
all hold:

1. **Signature verifies** — ECDSA-P256/SHA-256 against `PINNED_PUBLIC_KEY` (embedded;
   private key `~/.vela-signing/vela-calibration.key`, **never committed**).
   `BundleSignature.verify` (unit-tested). A bad/missing signature → ignored (keep
   last-good). An unsigned/older cache → compiled `DEFAULT` for one launch.
2. **Host-allowlist** — every endpoint host ∈ {`www.google.com`, `google.com`}.
3. **Version is newer** than the active one.

Sign after every edit with **`scripts/sign-calibration.sh`** (self-verifies); commit
`calibration.json` **and** `calibration.json.sig` together. Propagation: ~5 min
(raw CDN cache); a transient skew between the two files just means the device keeps
last-good until both are fresh (safe).

What the signed bundle can carry, in escalating power:
- **Config** (always): `pb` templates, endpoint URLs, the `hspqX` photo proto, and the
  search parser's positional **field-index `paths`**. → a *moved field / endpoint / pb*
  is a one-line edit + version bump + re-sign.
- **Notices**: a `notices` array (`id`/`level`/`title`/`body`/`url`) → dismissable,
  level-tinted cards on the bare map (`MapViewModel.refreshNotices`, dismissed ids in
  `vela_notices` prefs). → push "search is down, fix coming."
- **Parse logic** (`transformsJs`): a JavaScript bundle run in a **Rhino sandbox**
  (`JsSandbox`: `org.mozilla:rhino-runtime`, **interpreted `optimizationLevel=-1`** —
  ART can't run Rhino's bytecode gen; **`initSafeStandardObjects`** = no Java/IO exposed;
  R8-keep in `core/consumer-rules.pro`). `JsTransforms` exposes two search hooks over a
  flat `PlaceJson` contract: **`parseSearch(rawResponse)`** (full re-parse of a reshaped
  response, used instead of the compiled parser) and **`transformPlaces(placesJson)`**
  (post-process). **Compiled Kotlin is always the fallback** (no script / missing fn /
  any error → unchanged). → a *response-shape* change is fixable remotely too.

Security posture: the signature is the floor that makes pushing *code* safe; the sandbox
means even a (hypothetically) malicious script can only transform the JSON string it's
handed — no filesystem, network, or device access.

---

## 6. Degoogled constraints (hard rules — do not regress)

- Location: AOSP `LocationManager` only — never `FusedLocationProviderClient`.
- Voice: AOSP `TextToSpeech`, engine-selectable — never hard-depend on Google TTS.
- No GMS: no FCM/Firebase/Play Integrity/Fused. Push (if ever) = UnifiedPush; crash
  reporting = ACRA/self-hosted.
- EU consent: pre-seed `SOCS`/`CONSENT` cookies; never let `Set-Cookie` downgrade
  `CONSENT` to `PENDING`.
- The two hidden WebViews (photos, transit) run Google's JS **anonymously** — an
  accepted, scoped tradeoff for data only a real engine is served.

---

## 7. Build, release, signing

- Toolchain: **AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1,
  compileSdk 35, minSdk 26, Java 17**, Compose + Hilt + version catalog. **R8 in the
  `release` buildType** — always build release for on-device (debug lags the map).
- **CI** (`.github/workflows/ci.yml`): every push to `main` builds + tests + signs the
  APK and publishes a normal versioned GitHub release **`v0.2.<run>`** (versionName
  `0.2.<run>`, versionCode `2000+run`). Obtainium tracks the latest.
- **APK signing**: release keystore `~/.vela-signing/vela-release.jks` (alias `vela`,
  password in `credentials.txt`); CI secrets `VELA_KEYSTORE_BASE64` /
  `_PASSWORD` / `VELA_KEY_ALIAS`. **`CN=Vela Maps`**. **Keystore lives outside the repo
  — back it up; losing it = can never update installed builds.**
- **Calibration signing**: separate EC key `~/.vela-signing/vela-calibration.key`
  (§5). Also never committed.
- **MapTiler key**: `MAPTILER_KEY` CI secret → `BuildConfig` only, **never committed**.
- On-device dev loop (gotchas): build a release at **≥ the currently-installed
  versionCode** (Obtainium auto-updates the device to each CI release, so an
  old-vc local build is a downgrade `install -r` rejects); **`am force-stop` before
  `am start`** (install over a running app keeps the old dex); screenshots are
  device-px (1080 wide) vs the ~270px display (×4 coords).

---

## 8. Feature catalogue

See **[`FEATURES.md`](FEATURES.md)** for the exhaustive, ticked list (search/places,
reviews, photo gallery, directions + alternates + swap + depart-time +
search-along-route, drive/walk/bike/transit, turn-by-turn with shields/lanes/voice/
haptics/speedometer/**per-lane diagram**, traffic overlay, **offline on-device routing** (GraphHopper,
137-region world catalog), offline basemap + POI (the offline SQLite POI index keeps OSM
address/phone/website/opening_hours, not just name+category), Home/Work shortcuts,
saved/recent places, deep links, scale bar, in-app theme, the resilience layer above).
