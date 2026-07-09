# Vela Maps — Roadmap

> Where Vela is going. [`FEATURES.md`](FEATURES.md) is what's **shipped**;
> [`SPEC.md`](SPEC.md) is **how it's built**; this file is **what's planned** and the
> bigger bets. Keep it current — add ideas here the moment they come up.

Last updated: 2026-07-08.

## Recently shipped
- **Whole-state offline place packs + self-updating packs.** Downloading a state pulls a
  CI-baked SQLite of the entire region's OSM POIs/addresses/streets, so offline search works
  Organic-Maps-style anywhere in the state. Packs rebuild monthly from fresh OSM and installed ones
  update in place through small row-level deltas. Offline typed-address geocoding shipped alongside
  (house-precise, interpolated, street fallback).
- **Android Auto, first cut.** Vela appears in the car launcher (AA "Unknown sources" for
  sideloads): live map, puck, route, and the current-maneuver card from the same NavSession the phone
  runs. Car-side search/route-start is the follow-up.
- **Open building + house-number overlays.** Microsoft footprints (ODbL) and OpenAddresses
  numbers as per-region PMTiles, streamed over the map by default where OSM is thin, downloadable for
  full offline. Traffic lights + stop signs draw at close zoom (keyless Overpass).
- **In-app updater + content toggles.** A PipePipe-style updater checks the newest GitHub
  release (about daily, Settings toggle), downloads the APK and hands it to Android's installer. Plus
  Settings switches to hide reviews and skip photo loading, and a 3D-buildings toggle for weaker GPUs.
- **In-process neural voice (Piper) — DONE, device-verified.** Vela bundles the sherpa-onnx VITS
  runtime and downloads a **Piper** voice itself (progress bar), running it in-process as the default nav
  voice — near-Siri quality, no standalone TTS app. Default = **HFC Female** (`en_US-hfc_female-medium`,
  ~67 MB). System engines stay selectable for override. **Next:** validate the arm64 `.so` load on a
  16 KB-page GrapheneOS device; revisit on-demand delivery (dynamic feature) to shrink the base APK.
  (Nav-string localization + per-language voice pairing SHIPPED.)
- **Voice browser — DONE, device-verified.** Settings → Voice → **Voice library** downloads and
  switches between ~40 curated Piper voices, each in its own `filesDir/piper/<id>/`; per-voice speaker
  prefs; race-free switch; in-place migration of the old single-voice install. **Next bets:** (1)
  host the catalog (`PiperCatalog`) on the signed `calibration.json` so new voices ship without an APK —
  would also require **pinning the download host** (`github.com`) in the calibration allowlist so a
  compromised bundle can't redirect a "voice" download at an attacker binary; (2) a preview-without-
  switching ▶ (a second transient `OfflineTts`) so you can audition before committing; (3) a shared
  `espeak-ng-data` dir across voices (~10 MB saved per voice).

## North star

A degoogled, keyless Google-Maps replacement that reaches **parity** with Google
Maps and, over time, **leans less on Google** by growing Vela's own data layer
(starting with traffic). Privacy-first, F-Droid, GPLv3 — every new data flow is
opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## Recently shipped (2026-06-28 → 30)

The big recent landings — detail in [`FEATURES.md`](FEATURES.md) / [`SPEC.md`](SPEC.md), full
journeys below under Big bets / Known-hard:

- **Open router (OSRM) is now PRIMARY** — complete street-named turn-by-turn incl. **highway `ref`s /
  exit numbers / sign destinations**; Google demoted to the live-traffic overlay + jam-reroute + fallback.
- **Offline routing on-device (GraphHopper)** — a **135-region world catalog** (all US states, Canada,
  Europe, +) built by a race-safe CI matrix, hosted on GitHub, downloaded per region; smallest-covering
  region selection; combined map+routing area download; a location-aware, filterable picker.
- **Navigation** — a **real per-lane diagram** (OSRM lane data), highway/exit shields on the banner,
  OSRM retry (fewer nameless fallbacks), and the traversed-grey trail tightened under the arrow.
- **Nav guidance de-noised** — the lane diagram only shows within ~0.5 mi of the maneuver
  (`LANE_SHOW_M`) and the "then &lt;next&gt;" compound line only when the next maneuver closely follows
  (`COMPOUND_M`, `isCompoundNext`) + carries the next step's shield. Also fixed: business name leaking
  into the place address (`stripNamePrefix`).
- **Alternate-route deltas** — the directions picker shows a **"+N min"** tag on each
  slower alternate (fastest keeps "Fastest"), so alternates are weighable at a glance.
  *Remaining lane idea (future):* highlight the **continuing** lanes for a compound maneuver (which of the
  exit lanes also serve the immediately-following keep/merge) — OSRM gives no cross-step lane linkage, so it
  needs a careful heuristic; deferred rather than fake it.
- **Traffic snap earns its lead** — the option-3 reroute only leads when its live ETA beats OSRM's
  free-flow best (`SNAP_ETA_MARGIN`), so a divergent-but-not-faster snap no longer wins.

*Still to validate on real drives:* route-speed parity vs Google (the snap-guard threshold is tunable
from the `directions` diag), offline highway refs (a graph rebuild — parked).

## Near-term (next up)

- **D-pad polish** (base support SHIPPED + full-function sweep done — see `docs/dpad.md`): a real-device
  pass on D-pad hardware to tune the pan step / OK-hold threshold / focus-ring visibility and traversal
  order; **pixel-verify the full-screen reviews WebView's ↑/↓ page-scroll on an unfiltered network** (the
  handler is wired + reach/exit are proven, but the test device's content filter throttles the reviews
  carve so the loaded page never renders there); consider an on-screen key-hint pill while the map target
  is focused.

- ~~Visible-WebView reviews panel~~ — **SHIPPED (experimental, default ON,
  Settings toggle)**: Google's live reviews pane embedded in the place sheet's Reviews tab,
  CSS-carved, theme-matched, tracker-blocked, navigation-locked; native scraped list is the
  automatic fallback. (An iframe remains impossible — `X-Frame-Options: SAMEORIGIN`,
  verified — but a WebView isn't an iframe.) Remaining polish ideas: hide the "Get pickup
  or delivery / Order online" promo block inside the panel; make the panel height adaptive
  instead of 560 dp; extend the same treatment to the photo gallery.

- ~~Higher-res README screenshots~~ — **DONE** (all 9 recaptured at
  1080×2400 on-device, current UI). Store screenshots when there's a store listing.
- **Stability pass** — core flows smoke-tested on-device (fresh install →
  search → route → transit → nav, no crashes). Still open: the *Start → launcher* quirk
  (nav keeps running in the foreground service but the activity backgrounds).
- ~~Custom directions origin~~ — **DONE + device-verified (in-panel editable From).** The
  directions panel's **From** row is tappable → opens search → the pick becomes the origin
  (`directionsOrigin: Place?`, route falls back to live location when null). The picker overlay
  is driven by `searchOpen = searchFocused || pickingOrigin` and pick-mode is reset explicitly.
  A **"Your location" reset row** at the top of the picker drops a custom origin back to
  live GPS. ~~Follow-up: editable origin while *reversed*~~ — **DONE**: the edit pencil moves
  to the "To" row when reversed (where the custom endpoint then sits), via a parallel
  `onEditDestination` on the directions panel.
- ~~**Real highway shields in the nav banner**~~ — **v1 SHIPPED.** Interstate
  (red-top/blue) + US-route (white) shield silhouettes drawn as Compose `Canvas` paths, a
  neutral white marker for state/provincial routes, network **inferred from the ref prefix +
  a state/province set** (`parseRouteRef`, unit-tested; `I`→interstate, `US`→US route, a
  2-letter state/province code → state, else the plain bordered chip) — no OSM lookup. `ROUTE_RE`
  broadened to capture `XX-NN` state/province refs. **Remaining:** per-state/province *shapes*
  (a California spade vs Ontario's crown) from the **OpenStreetMap Americana** set
  ([ZeLonewolf/openstreetmap-americana](https://github.com/ZeLonewolf/openstreetmap-americana)),
  and broadening the ref capture once the **travel logs** show the real ref formats Google emits.
- **Explore (nearby things to do)** — a Google-Maps-Explore-style surface: nearby
  restaurants / things to do / events, as cards on a bottom sheet from the bare map.
  Data: our keyless POI search already returns categorised places (reuse the
  category chips + `/search?tbm=map`), ranked by distance + rating; "events" is the
  harder, sparser part (no keyless Google events feed — likely OSM/OpenStreetMap +
  a public events source later, or skip v1). **Plan, not now** (per request). Start
  as "Nearby" (categories + top-rated around you); grow toward Explore.
- **Place-page parity gaps** (vs Google Maps). Summary-node enrichment (review count / full
  hours / address / phone / price / attributes, backfilled from the focused re-fetch) closed the
  worst gaps. Remaining, by cost:
  - *Cheap — already in the focused node we now fetch, just lift + render:*
    ~~**"People also search for"**~~ — **DONE** (`root[2][11][0]`, focused searches; tappable cards).
    ~~**richer attribute groups**~~ — **DONE** (`attributeHighlights` → overview chip row, reuses
    parsed About). ~~**reserve / order / book action links**~~ — **DONE** (`actionLabel`/`actionUrl`
    at `[1][75][0][0][5]` → prominent button; unit-tested). Remaining: **menu link** — **NOT in `[75]`**
    (that node held only "Order online" + "Join waitlist"). The restaurant carries a real menu URL
    (distinct from the website) **and** menu **photos** (googleusercontent `gps-cs-s`, alt "Food menu…"
    — these feed the photo-**categories** Menu tab, DONE). **Menu-link button DEFERRED** — the menu URL
    appears **inconsistently** across Google's responses and its positional path won't pin reliably
    (fragile calibration). Lower priority now that the menu is accessible via the shipped photo "Menu"
    category tab. A link button would need a stable-path capture first; parked.
    Coverage follow-up: similar-places only rides *focused* searches today — to show it on
    address-snap / list-tap opens too, do a focused name lookup on open (the OkHttp focused
    search carries `[2][11][0]`; the WebView enrichment response does not).
  - **Q&A (questions & answers) — LOGIN-GATED, not keyless.** Scraped the logged-out `?cid=` page in a
    real WebView (the tactic that works for reviews/photos) on **both an attraction (Space Needle) and a
    business (Home Depot)**: **no Q&A section renders at all** — zero "question" text in the DOM. Google
    serves Q&A only to a **logged-in** session — same "needs login" bucket as predictive depart-time and
    login-gated popular-times. **Not achievable within the degoogled/keyless posture** (no login); the
    `WebQuestionsFetcher` scaffold was removed. Would only unblock via an opt-in Google login, which the
    project deliberately avoids.
  - *Medium — a separate keyless RPC:* **"mentioned in reviews" topic chips** / review keyword summary
    (these DO render logged-out — the "helpful employees / mask policy, mentioned in N reviews" radios —
    so this one is feasible: parse those from the reviews page).
    ~~**photo categories** (menu / food / vibe tabs in the gallery)~~ — **DONE**: the tabs are in
    the `?cid=` page DOM, so `WebPhotoFetcher` visits each category tab + tags photos, and the sheet shows
    All/Menu/Food&drink/Vibe filter chips (`Photo.category`/`Place.photoCategories`).
  - *Photo posted-dates — DEFERRED:* gallery TILES (`.aHpZye`) carry only the image URL; the date is only
    shown for a **focused** photo (lightbox), so per-photo dates would need opening each in the lightbox
    (N interactions). Low yield; `Photo.postedText` + the viewer's date caption already exist (unused) for
    if a per-tile date source ever appears.
  - *App-level:* ~~**multi-stop directions** (waypoints)~~ — **DONE** (an "Add stop" row in the
    directions panel; routes OSRM straight through the stops via `routeVia` + Google traffic ratio, single
    route). ~~Follow-ups: per-stop arrival announcement, reorder, reroute-through-remaining~~ — **also
    DONE**: per-stop "You've reached &lt;stop&gt;" voice cue (`NavEngine.stopMarks`, unit-tested),
    reroute-through-remaining (off-route reroute + faster-recheck go through unreached stops, reaches-dest
    guards intact), and up/down reorder arrows (`moveStop`).
    **avoid tolls/highways** (a directions-`pb` options field — see Known-hard), **explicit lists/labels** for
    saved places.
  - *Not feasible keyless / out of scope:* Street View (key-gated — see Known-hard),
    satellite imagery (no open keyless source), account features (your contributions,
    timeline, writing reviews — degoogled by design), flights/hotels booking tabs.
  Recommended order: the *cheap* group first (one parser+UI pass, reuses the
  enrichment plumbing), then Q&A, then review-topic chips.
- ~~Traffic browse-overlay — keep, drop, or rebuild?~~ — **RESOLVED: hidden in Settings.** Keep it but
  **move the toggle off the map into Settings → Map** so it doesn't clutter — nav's per-segment route
  colouring is the primary traffic view; the whole-map raster is now an opt-in browse aid in Settings,
  subdued (below POIs, 0.6 opacity). Not dropped entirely (still useful for scanning a wider area), not
  rebuilt (no keyless vector congestion source).

## Big bets

### Buildings  *(done — keyless, no key, no infra)*

Real building footprints render now. They were **already in our tiles** — the
OpenMapTiles `building` + `building-3d` layers (OSM data, much of it imported from
Microsoft's footprints) — Vela just coloured them a hair off the land so they were
~invisible; bumped the contrast + added an outline. Gap-filling: **Microsoft footprints
(US + Global ML) ship as per-region PMTiles** (`OverlayTileStore`, CI-baked, 361-row
catalog), streamed under the OSM buildings by default and downloadable for offline — so
thin-OSM suburbs render houses now. 3-D massing at high zoom is on via `building-3d`.
**Parcels: not pursuing** (lot/assessment data — a per-county scraping + backend commitment
with licensing heterogeneity; out of scope).

### Opt-in telemetry  *(planned — deliberate, careful)*

Goals, **strictly opt-in**, off by default:

1. **Developer diagnostics — ✅ SHIPPED (local-only).** Settings → Diagnostics (off by
   default) keeps an in-memory breadcrumb log (searches, routes, parser drift, nav
   start/reroute/arrival) the user can **Export debug session** and hand to a dev via the
   share sheet. **No backend, no auto-upload** — user-initiated + user-routed
   (`core/diag/DiagLog`, `app/diag/DiagExporter`). Optional remaining piece: a one-tap
   upload sink (needs the backend below) instead of manual share.
2. **Trip recording + replay — ✅ SHIPPED (local-only).** Settings → "Save my trips" (a
   **separate, more-invasive** opt-in — it's your exact routes) records each navigation's
   GPS trace to a file (`app/replay/TripStore`); a trip replays on the map at 3×
   (`LocationProvider.replay`) to test turn-by-turn without driving. First-run prompt offers
   it separately from diagnostics. Replay auto-routes to the trip's destination and runs
   real turn-by-turn, with a **Stop replay** control on the map. Trips also have a **Share**
   button (FileProvider) so a drive can be pulled off a *release* build for debugging. **The
   navigated route is saved INTO the trip** (`RP`/`RD`/`M` lines, `core/replay/TripLog`), so a
   replay drives the exact blue line the user saw (not a fresh re-route), and the trip can be
   **audited offline**. ✅ **Offline nav auditor — SHIPPED.** `core/nav/NavReplay` replays a
   trip's GPS fixes back through the real `NavEngine` and **diffs what the cards + voice said
   against where the maneuvers actually are on the route** — per-maneuver: announced how far
   out, turn-now fired?, worst card-distance error, nearest approach; flags silent/missed
   turns, miles-too-early announcements, and lying card distances. One call:
   `TripLog.audit(csv).summary()`, or the harness `:core:testDebugUnitTest --tests
   '*auditSharedTripLog' -DvelaTrip=<csv>`. Unit-tested end-to-end.
3. **Vela's own traffic data (the long game).** Crowd-source anonymized speed/route
   traces from opted-in users to build a **Vela traffic layer**, blended with Google's
   and eventually replacing it where coverage is good — the first real step off Google.
   The trip recorder above is the on-device half of the trace capture this would need.

**This is a departure from today's "no telemetry, no backend" stance**, so it must *earn* trust:
- **Opt-in only**, clear consent screen, easy off + "delete my data," never on by default.
- **Minimize + anonymize**: no account, pseudonymous device token at most; trim precise
  start/end points (snap to road, drop the first/last ~100 m like other traffic apps);
  send speed/heading along road segments, not "user X went from home to work."
- Needs **the first Vela backend** (or a privacy-preserving collector) — pick something
  self-hostable; a thing to run/secure/subpoena-proof, the opposite of the current no-server design.
- **Update [`PRIVACY.md`](PRIVACY.md) in the same change** — it currently says "no telemetry".
- Could ride the existing **signed channel** for config (endpoint, sample rate, kill-switch).

### Vela traffic layer

Depends on the telemetry above. Aggregate opted-in traces → per-segment speed vs.
free-flow → a traffic overlay + traffic-aware ETAs that don't need Google. Start as a
*supplement* to Google's `/maps/vt` tiles, grow as coverage allows.

## Known-hard / blocked

- **Owner updates / local posts in the place sheet** *(deferred pending a calibration sample)*. Ground
  truth from a live probe: a restaurant physically closed (paper sign on the door) during its listed
  hours carried **NO closure signal anywhere in Google's data** — no temporarily-closed status, no owner
  post; even google.com/maps showed plain hours. **No app can be resilient to a paper sign the owner never
  enters into any system.** What we CAN do (and shipped): first-class `Place.temporarilyClosed`
  (multilingual status-text match) → place-sheet banner + hours suppression, so when an owner DOES set the
  formal closure Vela is loud about it. The remaining piece — owner POSTS ("closed for renovation until…")
  — has a discovered keyless-family endpoint, **`/maps/preview/localposts?…&pb=…`**, but its `pb` grammar
  needs a live capture from a business that actually has owner posts; none found in a bounded hunt (posts
  are rare + Google demotes temp-closed places in search). Revisit when we encounter one — standard
  calibration pipeline. The full-screen reviews panel (live Google profile) shows Updates already when
  they exist.

- ~~Busy / popular times~~ — **DONE keyless.** The histogram is place node `[84]`; the keyless **OkHttp**
  `/search` is bot-degraded (TLS-fingerprint, like photos/transit) and strips it. A real browser engine
  isn't degraded — **but** even in the WebView a **bare-name** search returns a 20-result `[64]` list
  that's *also* trimmed of `[84]`. The fix is the **query**: a **specific name + address** search (e.g.
  `In-N-Out Burger 1020 Olive Dr Davis CA`) resolves to a *single focused result* whose `[0][1][0][14]`
  node keeps `[84]`. `WebPopularTimesFetcher` warms google.com→maps (an established NID matters), builds
  that specific query into both the `pb` and `q=`, then same-origin-fetches it; `PopularTimesParser` reads
  `[84]`.
- **Predictive per-departure ETA** — still needs the directions `pb`'s departure-time field; confirmed
  unreachable keyless (6th attempt, deepest yet) with a real-browser fetch loop + the live web client as
  oracle:
  - **Read side is dead.** The 810 KB keyless response carries route geometry + current/typical durations
    but **no embedded time-of-day duration curve** — so the web UI is *not* computing future ETAs from
    pre-shipped data.
  - **Our `pb` template is byte-identical to Google's live web client** (115 tokens) — there's no hidden
    time field we're merely omitting; the client sends none for "now".
  - **Direct injection is ignored or 400s.** `!8j`/`!8m1`/`!8m3`/`!21m1` accepted but ETA unchanged for a
    Monday-8am stamp; `!8m2…`/`!9m2…`/`!7m2…` HTTP 400. Nested-field guessing stays a dead end.
  - **The web "Leave now ▾" control is genuinely un-automatable** — neither CDP-level clicks nor keyboard
    activation open its menu (`aria-expanded` never flips), so even a real browser can't be driven to emit
    a depart-time request. **Conclusion: predictive per-departure is login/Android-app-only**; transit
    (already fetched via the WebView) is the only keyless mode honouring a chosen time.
  - **Shipped instead: the typical best→worst spread.** Google's planning hint lives at directions
    `summary[10][4] = [lowSeconds, highSeconds, label]` ("usually 1 hr 8 min to 1 hr 27 min"); parsed into
    `Route.typicalRangeSeconds`, shown in the depart-time chooser as an honest arrival/leave **window** for
    a future "Depart at" / "Arrive by" plus an always-on "usually X–Y" line. Not per-minute predictive, but
    real keyless data instead of false precision.
  - **Only true-predictive unblock (≈2 min, manual):** capture ONE real request carrying a future departure
    — **mitmproxy on the Android Google Maps app** (set Depart-at, grab the `/maps/preview/directions?pb=`
    GET). Diff it against `DirectionsPb.DEFAULT_TEMPLATE`, find the field, plumb `departureTime` through
    `MapDataSource.directions` + a re-fetch.
- **Avoid tolls/highways** — same family (a directions `pb` options field); deferred.
- **Per-review uploaded photos** — the `listentitiesreviews` RPC returns **only the reviewer's avatar**,
  never their uploaded photos (all `/a/…ACg8oc` / `/a-/…ALV-`, zero `/gps-cs`·`/geougc`·`/p/AF1Qip`).
  SHIPPED since: reviews moved off that RPC entirely to the WebView DOM scrape of the place's `?cid=` page
  (`WebReviewsFetcher`), which carries each review's real uploaded photos — the place sheet shows them
  today. This entry stays only as the record of why the RPC path was dead.
- **Photo contributor name** — the gallery `hspqX` RPC gives each photo's URL + **posted date**
  (`[21][6][8]`, shown as "Photo · May 2026") + an upload-source tag, but **not the contributor's name**
  (every string field on a user photo is the url / photo-id / feature-id / source tag). Google's viewer
  resolves "Photo by Kevin" via a **separate per-contributor profile lookup** keyed by an id we'd have to
  fish out and request per photo — N extra round-trips for a name. Deferred as low-value; the date covers
  the useful half. `Photo(url, postedText)` has room for an `author` field if it's ever worth the lookup.
- ~~Per-segment route traffic during nav (Google-parity)~~ — **DONE.** The congestion data is in the
  directions response: `route[3][5][0]` is a list of `[level, startMeters, lengthMeters]` spans (only the
  non-free-flowing stretches; gaps are free-flow). `DirectionsParser` reads it into `Route.trafficSpans`;
  `MapScreen` converts metre offsets → fractions; `VelaMapView.routeGradientStops` paints the route line
  per segment over the driven-grey gradient (free-flow blue base, amber = level 1, red = level 2, dark red
  = 3+). The whole-map raster stays off during nav — the route now carries the traffic, like Google.
  *(Level→colour mapping is the best read of the 1/2 grades seen; trivially flipped if a heavy drive shows
  otherwise.)*
- **Individual traffic incidents (crashes / construction / closures) — no clean keyless source yet.**
  Google shows discrete incident icons/cards ("Crash ahead", "Road closed"); Vela today has only the
  aggregate **congestion spans** (`route[3][5]`, per-segment colour). The raw keyless
  `/maps/preview/directions` (OkHttp) carries congestion grades but **no per-incident objects/text** (zero
  incident text on a 25-mi Sacramento probe). Three candidate paths:
  1. **WebView / Google — bottoms out at binary tiles, NOT a clean read.** Unlike photos/transit/
     popular-times, the **driving page `APP_INITIALIZATION_STATE` has no incidents either** (zero incident
     text across two captures). Intercepting the WebView's network (`shouldInterceptRequest`): the only
     traffic-related requests are **`/maps/vt/…` — Google's proprietary binary vector tiles**
     (`/maps/vt/pb=!…!2m2!1e1!…` = the traffic layer). So incidents render from the **`vt` tile stream**,
     Google's own obfuscated protobuf (NOT standard MVT — MapLibre can't decode it). Getting them this way =
     **fetch the route's `vt` traffic tiles + reverse-engineer their binary schema + track its changes**: a
     large, fragile RE effort, breaks whenever Google reshapes the tile.
  2. **Open DOT / 511 incident feeds** — the **degoogled-pure** alternative (open government data, no Google
     scrape): structured incidents w/ lat-lng, category, severity, headline — **already JSON**, no binary
     RE. Fits Vela's ethos better; the user is in **WSDOT** territory (a well-documented free feed →
     live-testable). Cost: feeds are **fragmented** (per-state/metro APIs, differing shapes) and often
     **token-gated** (free, but a key → the optional-user-token model we use for `MAPTILER_KEY`, never
     committed). Pluggable provider + start with one region, grow coverage.
  3. **Defer** — congestion colouring already covers "where's it slow"; discrete incidents are polish.
  **Status:** Google path's true cost now known (binary `vt`-tile RE, high + fragile); the open-feed path is
  likely the pragmatic way to actually ship incidents despite its fragmentation.
- **On-device map-matching (GraphHopper) — the "Google routes, the engine names the turns" unlock.**
  > **✅ SHIPPED as the OFFLINE ROUTER** — Phase 1 is done end-to-end + on a 135-region world catalog (see
  > "Recently shipped" up top, `SPEC.md` §Offline routing, `FEATURES.md`). This entry is the **engineering
  > record**; kept for reference. Still OPEN = **Phase 2**: use the same on-device engine for *online* clean
  > always-snap (map-match Google's polyline → replace the option-3 via-snap).

  Beyond going offline, this makes **clean always-snap** routing possible: take Google's traffic-smart path
  and use an on-device engine only to recover street-named turns. **Why we can't do it cleanly on public
  infra (measured), and that NO self-hosting is required:**
  - Google's keyless **polyline is complete** (decoded `[0][7][i]`), so the path is fully traceable.
  - The clean tool is **map-matching** (trace → roads+turns). FOSSGIS **`/match` caps at 10 coords**
    (`TooBig` past that, ~0.01 confidence that sparse); public **Valhalla `/trace_route` times out**.
  - The serverless fallback, **dense-waypoint `/route`** (40–100 vias, no cap), reproduces Google's path
    *exactly* with 0 U-turn artifacts — **but a via landing on a turn is swallowed into a via arrive/depart
    → ~1-in-10 named turns lost**. Turn-loss is the exact bug we fixed, so always-snapping that way is a
    regression.
  - **Shipped instead:** option 3 — snap only on real traffic divergence, with modest (12) vias.
    Public-server-clean, keeps perfect turns on the free-flow majority.
  - **The unlock = on-device map-matching.** Engine research compared GraphHopper / Valhalla / BRouter /
    Mapbox: **GraphHopper wins** — **pure JVM (no NDK)**, so it runs on Android with no native cross-compile
    (GrapheneOS-friendly), its **map-matching module is embeddable + Apache-2.0**, and it returns street
    names per edge (`EdgeIteratorState.getName()` / `street_name` path detail). Valhalla's Meili has **no
    maintained Android binding** (would mean owning a C++/JNI Meili surface); BRouter has **no street names**
    and no map-matching; Mapbox is token/MAU-gated. **JVM spike PASSED:** GraphHopper v11, fed a bare 26-pt
    downsampled polyline (Monaco), recovered **7/7 ground-truth street names in 34ms** — "scraped polyline →
    complete named turns" with no turn-loss.
  - **Sizing — MEASURED, favourable.** A full metro (Washington DC, 21 MB extract) builds to a **15 MB**
    GraphHopper graph folder (single car profile, no-CH) — *smaller* than the basemap tiles for the same
    area. A whole US state ≈ 10×. We **import off-device** (CI/desktop) and ship the prebuilt graph.
  - **ON-DEVICE: VALIDATED end-to-end** (`:ghprobe`, throwaway instrumented test, **PASSED on a Pixel 5a /
    Android 14**): GraphHopper v11 loaded a prebuilt Monaco graph in 137 ms, routed 1938 m / 11 instructions,
    map-matched a bare polyline → 10 street names in 1.37 s. GraphHopper v11 runs on ART but needs **three
    workarounds**:
    1. **`graph.dataaccess=MMAP`** (via `GraphHopperConfig`, no public DAType setter). The default
       `RAMDataAccess` static-inits a `VarHandle.withInvokeExactBehavior()` (JDK-16) that **ART lacks** →
       `NoSuchMethodError`. `MMapDataAccess` doesn't use it. (RAM_STORE & MMAP share on-disk format, so the
       desktop-built graph loads as MMAP with no rebuild.)
    2. **Dodge Janino.** v11 mandates custom-model profiles, compiled to JVM bytecode by Janino → ART can't
       load it. Fix: subclass `GraphHopper`, override the **`protected createWeightingFactory()`** to return
       a hand-rolled `SpeedWeighting` (Janino-free) **plus an access block** (`if !car_access → ∞`, mirroring
       car.json's `multiply_by 0`). (Plain `SpeedWeighting` ignores access → `ConnectionNotFound`.)
    3. **Swallow `close()`** — MMAP unmap goes through `Unsafe.invokeCleaner`, absent on Android. Harmless:
       the app keeps one engine for the process lifetime and never per-route closes.
    The OSM-**import** deps (`osmosis-osm-binary`, `protobuf-java`, `jackson-dataformat-xml`/`woodstox`/StAX,
    `xmlgraphics-commons`) are **excluded** from the app — we ship prebuilt graphs. The `:ghprobe` module is
    the reference recipe; delete it once the `:core` integration ports these three workarounds.
  - **Phasing.** **Phase 1 = GraphHopper as the OFFLINE router** (online OSRM+option-3+Google-traffic when
    connected, GraphHopper A→B with named turns when not) — the big win. **Phase 2 (optional) = online
    clean-turn map-matching** — in *downloaded* regions, run GraphHopper match on Google's polyline to
    replace option 3's lossy dense-via. Both need the same runtime, selected by connectivity + graph-presence.
  - **Phase 1a/1b-i — DONE: engine integrated, R8-proven, wired into `directions()`.** `core/data/RouteEngine`
    seam + `GraphHopperRouteEngine` (the 3 ART workarounds, translates GraphHopper's path → Vela
    `Route`/`Maneuver`, DRIVE/car). `graphhopper-map-matching` is a `:core` dep (import-only deps excluded);
    `consumer-rules.pro` keeps graphhopper/hppc/jts/jackson for R8; APK cost **~+10 MB** (tighter keeps /
    on-demand delivery is a later optimisation). `RouteEngine` is provided via Hilt (`CoreModule`) and
    injected into `GoogleMapsDataSource`; `directions()` falls back to it **only when OSRM came back empty**
    (offline / FOSSGIS down) — online behaviour unchanged. Unit-tested (`GraphHopperRouterTest`),
    Pixel-5a-verified offline.
  - **Phase 1b PERF — SOLVED: metro graph + Contraction Hierarchies + internal storage.** Two perf traps:
    1. **Storage** — a whole-state graph on **FUSE-mapped external storage** was I/O-bound. Internal storage
       (`filesDir`/`cacheDir`) loads fast (a 53 MB metro graph: 168 ms). External was only ever the
       adb-pushable *test* path; production downloads to internal.
    2. **Routing algorithm** — plain flexible A* with the interpreted `SpeedWeighting` override is fine on
       desktop (102 ms) but **7639 ms on the Pixel 5a**. Fix = **Contraction Hierarchies**, prepared on the
       *same* `SpeedWeighting` (CH bakes the build-time weighting, so it must match the engine's query
       weighting). **On-device CH route: 188 ms** for a 21-mi trip with 18 named steps (40× faster).
    A metro CH graph ≈ 53 MB (~21 MB zipped).
  - **`tools/graphbuilder` (DONE)** — standalone JVM tool (not an app dep) that builds a per-region CH graph
    matching the engine's exact config. `./gradlew :tools:graphbuilder:run --args="region.osm.pbf out-dir"`.
  - **Phase 1b-ii — DONE: per-region download + END-TO-END offline routing.** `RoutingGraphStore` (`:app`)
    fetches a manifest (`{"regions":[{id,name,url,sizeMb}]}` at `BuildConfig.ROUTING_MANIFEST_URL`) and
    downloads + unzips a region's CH graph into internal `filesDir/routing-graph` (progress %, atomic swap,
    marker file). Settings → **Offline routing (beta)** lists regions with Download / Installed-delete.
    Pixel-5a-verified: downloaded the Sacramento metro graph → offline → complete named route, ~200 ms, correct
    ETA. (Fixed a bug: GraphHopper's `SpeedWeighting` reports time as if `car_average_speed` were m/s, so
    ETAs were 3.6× too fast — engine + `graphbuilder` override `calcEdgeMillis` to `distance_m·3600/kmh`.)
    Local testing via a manifest host over `adb reverse` + a localhost-cleartext `network_security_config`
    (production traffic stays HTTPS-only).
  - **Multi-region — DONE.** Install **several** region graphs; the engine reads `filesDir/graphs/index.json`
    (`[{id, bbox:[S,W,N,E]}]`, written by `RoutingGraphStore` on install) and routes each trip on the
    **first installed region whose box covers both endpoints**, with a lazily-loaded `GraphHopper` per
    region. (A GraphHopper graph is monolithic — a trip must fit one region; cross-region trips fall to
    online.) `inBox` selection is unit-tested. **Sizing:** metro ≈ 21 MB, US state ≈ 160 MB, the whole
    planet as ONE graph ≈ **30 GB+ → infeasible on a phone**. So world coverage = the OsmAnd/Google-Offline
    model: a catalog of state/country graphs, download your slice. *Remaining:* region granularity for big
    countries (split by state), cross-region trips. **Re-download of an already-loaded region needs an app
    restart** (the engine caches the old graph).
  - **Graph HOSTING — LIVE.** Region CH graphs + `routing-manifest.json` are published as assets on the
    **`routing-graphs` GitHub release** (a fixed-tag *prerelease*, so it never becomes the "Latest" the APK
    tracks). `ROUTING_MANIFEST_URL` defaults to `releases/download/routing-graphs/routing-manifest.json`.
  - **World catalog + parallel build pipeline — DONE.** The catalog is a curated **`tools/routing-regions.json`**
    (135 regions: all 50 US states, Canadian provinces + Mexico, ~36 European countries, and starter
    Asia/Oceania/South-/Central-America/Africa; `big:true` flags country-sized graphs), grouped so a whole
    continent builds in one dispatch. The **`routing-graphs` GitHub Action** is a **race-safe matrix**: a
    `prep` job turns a `group` (or explicit `ids`) into a build matrix, parallel jobs each build their
    region's CH graph + upload only their own `<id>.zip` + a manifest *entry* artifact, and one `merge` job
    folds every entry into `routing-manifest.json` in a single upload (replace-by-id, so re-runs never
    clobber siblings). `scripts/build-routing-region.sh` (with `MANIFEST_MODE=emit` for the matrix) +
    `scripts/merge-routing-manifest.sh` are the two halves; the script also does all-in-one single-region
    builds locally. **THE ENTIRE CATALOG IS BUILT + HOSTED — 137 regions live**, ~22 GB of CH graphs as
    static release assets (no backend). *Still open (minor):* the largest single-country graphs are big
    downloads (Germany/France ≈ 1.2 GB) — optionally split giant countries into Geofabrik subregions later;
    cross-region trips.
    - *bbox fix:* region boxes come from `osmium fileinfo -g header.boxes` (the declared extract region),
      **not** `data.bbox` (raw node extent — outlier nodes blew Oregon's box across WA + CA, so it falsely
      "covered" Sacramento in the picker).
    - *border-overlap fix:* even clean `header.boxes` boxes carry a Geofabrik buffer that spills across
      borders (British Columbia's box dips into Sacramento), so the picker, the tiles→routing combine, and the
      engine all pick the **smallest** box covering you (falling through to the next-smallest if a graph
      can't make the trip) instead of the first.
- **Street View** — the Google Maps EMBED API is key-gated, but the CONSUMER pano
  (`…/maps/@?api=1&map_action=pano&viewpoint=<lat>,<lng>`, Google's documented deep link) is **keyless —
  verified in a real desktop browser** (snapped to the nearest pano, full interactive WebGL, no key wall).
  **BUT it does NOT render in an Android WebView** (ARM Mali-G715 + **ANGLE** GL driver): tried a Compose
  Dialog, a Dialog with forced `FLAG_HARDWARE_ACCELERATED`, without the `.alpha()` fade, AND hoisted into
  the main hardware-accelerated window — **black pano in all four.** A JS probe proved the WebGL context
  inits, the canvas is full-size, the URL resolves to a real panoid, no key wall — yet the pano composites
  to black, AND `document.body` came back at only ~182 chars: Google serves the Street-View SPA a minimal
  shell in the WebView (the same TLS/bot defense that degrades our OkHttp scrapes — but here the WebView
  doesn't rescue it the way it does for the `?cid=` reviews/photos pages). The keyless static thumbnail
  (`streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=…`) 403s on a direct fetch (needs browser
  session/referer). **Options:** (a) an "open externally" pill → `Intent.ACTION_VIEW` the pano URL (reliable
  + keyless but leaves Vela); (b) resolve the panoid + fetch the thumbnail via a background WebView with
  session (uncertain, thumbnail 403 unresolved); (c) open imagery (Mapillary/KartaView) with a free token,
  sparser but truly renders. Option (a) SHIPPED: the place sheet has a Street View pill that opens the
  keyless pano externally. In-app panos (b/c) stay parked.
- **Gallery videos** — parked, low value. The full `hspqX` gallery for a busy place (In-N-Out, 50 photos)
  carried **zero video entries** (no `googlevideo.com`/`.mp4`/`m3u8`), so videos are rare in the first
  place; supporting them would need a separate (likely gated) video source + a player dependency
  (ExoPlayer/media3) + handling expiring stream URLs. Skip unless a specific place with videos motivates it.
- **Roboto font** — no keyless glyph host serves it; Noto Sans stays.

## Resilience (built — extend as needed)

The signed `calibration.json` channel can already hot-push **config, field paths,
user notices, and sandboxed JS parse-logic** with no app update (see SPEC §5). Future
breakages should be fixed there first.
