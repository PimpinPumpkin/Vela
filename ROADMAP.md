# Vela Maps — Roadmap

> Where Vela is going. [`FEATURES.md`](FEATURES.md) is what's **shipped**;
> [`SPEC.md`](SPEC.md) is **how it's built**; this file is **what's planned** and the
> bigger bets. Keep it current — add ideas here the moment they come up.

Last updated: 2026-06-21.

## North star

A degoogled, keyless Google-Maps replacement that reaches **parity** with Google
Maps and, over time, **leans less on Google** by growing Vela's own data layer
(starting with traffic). Privacy-first, F-Droid, GPLv3 — every new data flow is
opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## Near-term (next up)

- ~~Higher-res README screenshots~~ — **DONE 2026-06-21** (all 9 recaptured at
  1080×2400 on-device, current UI). Store screenshots when there's a store listing.
- **Stability pass** — core flows smoke-tested on-device 2026-06-21 (fresh install →
  search → route → transit → nav, no crashes). Still open: the *Start → launcher* quirk
  (nav keeps running in the foreground service but the activity backgrounds).
- ~~Custom directions origin~~ — **DONE + device-verified 2026-06-20 (in-panel
  editable From).** The directions panel's **From** row is tappable → opens search →
  the pick becomes the origin (`directionsOrigin: Place?`, route falls back to live
  location when null). Chose the in-panel treatment over Google's top-bar From/To.
  **Bug found + fixed on first device test (0.2.132):** the picker overlay was driven
  by `searchFocused` (tied to the text field's focus), but it was opened *without*
  focusing the field — so `clearFocus()` (every close path) was a no-op and the overlay
  got stuck (no feedback on tap, couldn't back out). Now the overlay is driven by
  `searchOpen = searchFocused || pickingOrigin` and pick-mode is reset explicitly.
  Verified: pick reroutes (In-N-Out→Sac = 19 min), back cancels cleanly. A **"Your
  location" reset row** sits at the top of the picker to drop a custom origin back to
  live GPS (added 0.2.133). ~~Follow-up: editable origin while *reversed*~~ — **DONE
  2026-06-20**: the edit pencil moves to the "To" row when reversed (where the custom
  endpoint then sits), via a parallel `onEditDestination` on the directions panel.
- ~~**Real highway shields in the nav banner**~~ — **v1 SHIPPED 2026-06-27.** Interstate
  (red-top/blue) + US-route (white) shield silhouettes drawn as Compose `Canvas` paths, a
  neutral white marker for state/provincial routes, network **inferred from the ref prefix +
  a state/province set** (`parseRouteRef`, unit-tested; `I`→interstate, `US`→US route, a
  2-letter state/province code → state, else the plain bordered chip) — no OSM lookup, as
  agreed. `ROUTE_RE` broadened to capture `XX-NN` state/province refs. **Remaining:**
  per-state/province *shapes* (a California spade vs Ontario's crown) from the **OpenStreetMap
  Americana** set ([ZeLonewolf/openstreetmap-americana](https://github.com/ZeLonewolf/openstreetmap-americana)),
  and broadening the ref capture once the **travel logs** show the real ref formats Google emits.
- **Explore (nearby things to do)** — a Google-Maps-Explore-style surface: nearby
  restaurants / things to do / events, as cards on a bottom sheet from the bare map.
  Data: our keyless POI search already returns categorised places (reuse the
  category chips + `/search?tbm=map`), ranked by distance + rating; "events" is the
  harder, sparser part (no keyless Google events feed — likely OSM/OpenStreetMap +
  a public events source later, or skip v1). **Plan, not now** (per request). Start
  as "Nearby" (categories + top-rated around you); grow toward Explore.
- **Place-page parity gaps** (vs Google Maps; 2026-06-21 audit). The new
  summary-node enrichment (review count / full hours / address / phone / price /
  attributes, backfilled from the focused re-fetch) closed the worst gaps. Remaining,
  by cost:
  - *Cheap — already in the focused node we now fetch, just lift + render:*
    ~~**"People also search for"**~~ — **DONE 2026-06-21** (`root[2][11][0]`, focused
    searches; tappable cards, device-verified). ~~**richer attribute groups**~~ — **DONE
    2026-06-21** (`attributeHighlights` → overview chip row, reuses parsed About).
    ~~**reserve / order / book action links**~~ — **DONE 2026-06-21** (`actionLabel`/`actionUrl`
    at `[1][75][0][0][5]` → prominent button; verified against a "Book online" node + unit-tested;
    a restaurant capture would confirm reserve/order land in the same slot). Remaining: **menu
    link** (a Google-hosted menu URL — likely the same `[75]` actions node, different slot).
    Coverage follow-up: similar-places only rides *focused* searches today — to show it on
    address-snap / list-tap opens too, do a focused name lookup on open (the OkHttp focused
    search carries `[2][11][0]`; the WebView enrichment response does not).
  - *Medium — a separate keyless RPC:* **Q&A** (questions & answers),
    **"mentioned in reviews" topic chips** / review keyword summary, **photo
    categories** (menu / food / vibe tabs in the gallery).
  - *App-level:* **multi-stop directions** (waypoints), **avoid tolls/highways**
    (a directions-`pb` options field — see Known-hard), **explicit lists/labels** for
    saved places.
  - *Not feasible keyless / out of scope:* Street View (key-gated — see Known-hard),
    satellite imagery (no open keyless source), account features (your contributions,
    timeline, writing reviews — degoogled by design), flights/hotels booking tabs.
  Recommended order: the *cheap* group first (one parser+UI pass, reuses the
  enrichment plumbing), then Q&A, then review-topic chips.
- ~~Traffic browse-overlay — keep, drop, or rebuild?~~ — **RESOLVED 2026-06-19:
  hidden in Settings.** Decision (yours): keep it but **move the toggle off the map
  into Settings → Map** so it doesn't clutter — nav's per-segment route colouring is
  the primary traffic view; the whole-map raster is now an opt-in browse aid in
  Settings, subdued (below POIs, 0.6 opacity). Not dropped entirely (still useful for
  scanning a wider area), not rebuilt (no keyless vector congestion source).

## Big bets

### Buildings  *(done — keyless, no key, no infra)*

Real building footprints render now. They were **already in our tiles** — the
OpenMapTiles `building` + `building-3d` layers (OSM data, much of it imported from
Microsoft's footprints) — Vela just coloured them a hair off the land so they were
~invisible; bumped the contrast + added an outline (2026-06-19). No key, no new
data. If coverage ever needs filling, **Microsoft US Building Footprints** (~130 M,
ODbL, **free + keyless**) and **Overture** buildings are the open sources — but
they're bulk files you'd tile + host yourself (that's infra), so only worth it for
gaps. 3-D massing at high zoom is already on via `building-3d`. **Parcels: not
pursuing** (lot/assessment data — a per-county scraping + backend commitment with
licensing heterogeneity; out of scope by decision 2026-06-19).

### Opt-in telemetry  *(planned — deliberate, careful)*

Goals, **strictly opt-in**, off by default:

1. **Developer diagnostics — ✅ SHIPPED (2026-06-19, local-only).** Settings →
   Diagnostics (off by default) keeps an in-memory breadcrumb log (searches, routes,
   parser drift, nav start/reroute/arrival) the user can **Export debug session** and
   hand to a dev via the share sheet. **No backend, no auto-upload** — user-initiated +
   user-routed (`core/diag/DiagLog`, `app/diag/DiagExporter`). The remaining piece here
   is optional: a one-tap upload sink (needs the backend below) instead of manual share.
2. **Trip recording + replay — ✅ SHIPPED (2026-06-19, local-only).** Settings → "Save
   my trips" (a **separate, more-invasive** opt-in — it's your exact routes) records
   each navigation's GPS trace to a file (`app/replay/TripStore`); a trip replays on
   the map at 3× (`LocationProvider.replay`) to test turn-by-turn without driving.
   First-run prompt offers it separately from diagnostics. ~~Follow-up: auto-route +
   Stop-replay control~~ — **DONE 2026-06-20**: replay auto-routes to the trip's
   destination and runs real turn-by-turn (torn down when it ends), with a **Stop replay**
   control on the map. Trips also have a **Share** button (FileProvider, like the diag
   export) so a drive can be pulled off a *release* build for debugging — still
   user-initiated, never auto-uploaded. **The navigated route is now saved INTO the
   trip** (`RP`/`RD`/`M` lines, `core/replay/TripLog`), so a replay drives the exact
   blue line the user saw (not a fresh re-route), and the trip can be **audited offline**.
   ✅ **Offline nav auditor — SHIPPED (2026-06-27).** `core/nav/NavReplay` replays a
   trip's GPS fixes back through the real `NavEngine` and **diffs what the cards + voice
   said against where the maneuvers actually are on the route** — per-maneuver: announced
   how far out, turn-now fired?, worst card-distance error, nearest approach; flags
   silent/missed turns, miles-too-early announcements ("exit in 6 mi that didn't exist"),
   and lying card distances. So a shipped travel log can be analysed **without the user
   remembering where it broke** — one call: `TripLog.audit(csv).summary()`, or the
   on-demand test harness `:core:testDebugUnitTest --tests '*auditSharedTripLog'
   -DvelaTrip=<csv>`. Unit-tested end-to-end (clean-drive measurements + the flag logic +
   a full CSV round-trip).
3. **Vela's own traffic data (the long game).** Crowd-source anonymized speed/route
   traces from opted-in users to build a **Vela traffic layer**, blended with Google's
   and eventually replacing it where coverage is good — the first real step off Google.
   The trip recorder above is the on-device half of the trace capture this would need.

**This is a departure from today's "no telemetry, no backend" stance**, so it must be
done so it *earns* trust rather than spends it:
- **Opt-in only**, clear consent screen, easy off + "delete my data," never on by default.
- **Minimize + anonymize**: no account, pseudonymous device token at most; trim precise
  start/end points (snap to road, drop the first/last ~100 m like other traffic apps);
  send speed/heading along road segments, not "user X went from home to work."
- Needs **the first Vela backend** (or a privacy-preserving collector) — pick something
  self-hostable; this becomes a thing to run/secure/subpoena-proof, the opposite of the
  current no-server design, so weigh it.
- **Update [`PRIVACY.md`](PRIVACY.md) in the same change** — it currently (truthfully)
  says "no telemetry"; that line changes the day this ships.
- Could ride the existing **signed channel** for config (endpoint, sample rate, kill-switch).

### Vela traffic layer

Depends on the telemetry above. Aggregate opted-in traces → per-segment speed vs.
free-flow → a traffic overlay + traffic-aware ETAs that don't need Google. Start as a
*supplement* to Google's `/maps/vt` tiles, grow as coverage allows.

## Known-hard / blocked

- ~~Busy / popular times~~ — **DONE keyless 2026-06-19** (the 2026-06-18 "login-gated"
  conclusion was *wrong*). The histogram is place node `[84]`; the keyless **OkHttp**
  `/search` is bot-degraded (TLS-fingerprint, like photos/transit) and strips it, which
  fooled me. A real browser engine isn't degraded — **but** there was a second catch
  that nearly fooled me twice: even in the WebView, a **bare-name** search returns a
  20-result `[64]` list that's *also* trimmed of `[84]` (the "Usually"/"No wait" markers
  I first saw were a false positive — review text, not a histogram). The real fix is the
  **query**: a **specific name + address** search (e.g. `In-N-Out Burger 1020 Olive Dr
  Davis CA`) resolves to a *single focused result* whose `[0][1][0][14]` node keeps
  `[84]` (confirmed via live Chrome capture: bare name → 20 results, no histogram;
  name+address → one result with it). `WebPopularTimesFetcher` warms google.com→maps (an
  established NID matters), builds that specific query into both the `pb` and `q=`, then
  same-origin-fetches it; `PopularTimesParser` reads `[84]`. Lesson: when "needs login"
  comes from the OkHttp response only, try a WebView — and check the *query shape* too.
- **Predictive per-departure ETA** — still needs the directions `pb`'s departure-time
  field; re-confirmed unreachable keyless **2026-06-20 (6th attempt, deepest yet)** with a
  real-browser fetch loop + the live web client as oracle. Findings, now thorough enough
  to stop guessing blind:
  - **Read side is dead.** The 810 KB keyless response carries route geometry + the
    current/typical durations but **no embedded time-of-day duration curve** — so the web
    UI is *not* computing future ETAs from pre-shipped data.
  - **Our `pb` template is byte-identical to Google's live web client** (115 tokens,
    diffed against the page's own fired request) — there's no hidden time field we're
    merely omitting; the client sends none for "now".
  - **Direct injection is ignored or 400s.** Re-tested `!8j`/`!8m1`/`!8m3`/`!21m1`
    (accepted but ETA unchanged for a Monday-8am stamp) and `!8m2…`/`!9m2…`/`!7m2…`
    (HTTP 400). Nested-field guessing stays a dead end.
  - **The web "Leave now ▾" control is genuinely un-automatable** — neither CDP-level
    clicks nor keyboard activation open its menu (`aria-expanded` never flips), so even a
    real browser can't be driven to emit a depart-time request. Confirms the old "ignores
    synthetic clicks" note. **Conclusion: predictive per-departure is login/Android-app-
    only**; transit (already fetched via the WebView) is the only keyless mode honouring a
    chosen time.
  - **Shipped instead (2026-06-20): the typical best→worst spread.** Google's own planning
    hint lives at directions `summary[10][4] = [lowSeconds, highSeconds, label]` ("usually
    1 hr 8 min to 1 hr 27 min"); parsed into `Route.typicalRangeSeconds`, shown in the
    depart-time chooser as an honest arrival/leave **window** for a future "Depart at" /
    "Arrive by" plus an always-on "usually X–Y" line. Not per-minute predictive, but real
    keyless data instead of false precision.
  - **Only true-predictive unblock (≈2 min, manual):** capture ONE real request carrying a
    future departure — **mitmproxy on the Android Google Maps app** (set Depart-at, grab
    the `/maps/preview/directions?pb=` GET). Hand me the `pb`; I diff it against
    `DirectionsPb.DEFAULT_TEMPLATE`, find the field, plumb `departureTime` through
    `MapDataSource.directions` + a re-fetch.
- **Avoid tolls/highways** — same family (a directions `pb` options field); deferred.
- **Per-review uploaded photos** — the `listentitiesreviews` RPC (our reviews source)
  returns **only the reviewer's avatar**, never their uploaded photos (verified 2026-06-20
  against Tartine + Bottega Louie: 60 image URLs, all `/a/…ACg8oc` / `/a-/…ALV-`, zero
  `/gps-cs`·`/geougc`·`/p/AF1Qip`). The old parser swept the avatar at `[12][1][3]` into
  the photo strip — now fixed to collect UGC-by-URL-shape only, so it shows nothing here
  rather than a face. To actually show review photos we need the **media source**: 6
  pb-flag guesses (`!4m1!1b1`, `!6m1!1b1`, `!8b1`, `!2m3…!4e1`, sort `3e1`/`3e2`) all came
  back avatars-only, and the web client never fired a readable reviews-with-photos request
  (the photos on the place overview are embedded from the initial place load). Likely a
  different RPC (`listugcposts`?) or a media flag — needs one captured real request, same
  as depart-time. `ReviewsParser` already accepts UGC the moment it appears.
- **Photo contributor name** — the gallery `hspqX` RPC gives each photo's URL + **posted
  date** (`[21][6][8]`, now shown as "Photo · May 2026") + an upload-source tag, but **not
  the contributor's name**: verified 2026-06-20 (every string field on a user photo is the
  url / photo-id / feature-id / source tag — no name anywhere). Google's viewer resolves
  "Photo by Kevin" via a **separate per-contributor profile lookup** keyed by an id we'd
  have to fish out and request per photo — N extra round-trips for a name. Deferred as
  low-value; the date covers the useful half. `Photo(url, postedText)` has room for an
  `author` field if it's ever worth the lookup.
- ~~Per-segment route traffic during nav (Google-parity)~~ — **DONE 2026-06-19.** The
  congestion data was hiding in plain sight in the directions response: `route[3][5][0]`
  is a list of `[level, startMeters, lengthMeters]` spans (only the non-free-flowing
  stretches; gaps are free-flow). `DirectionsParser` reads it into `Route.trafficSpans`;
  `MapScreen` converts metre offsets → fractions; `VelaMapView.routeGradientStops` paints
  the route line per segment over the driven-grey gradient (free-flow blue base, amber =
  level 1, red = level 2, dark red = 3+). Calibrated against Davis→Sac + Berkeley→SF
  (Bay-Bridge approach = one long level-2 span). The whole-map raster stays off during
  nav — the route now carries the traffic, like Google. *(Level→colour mapping is the
  best read of the 1/2 grades seen; trivially flipped if a heavy drive shows otherwise.)*
- **On-device map-matching (GraphHopper) — the "Google routes, the engine names the turns" unlock.**
  *(Engine chosen 2026-06-28: **GraphHopper**, NOT Valhalla — see below. Multi-session.)* Beyond going
  offline, this is what makes **clean always-snap** routing possible: always take Google's traffic-smart
  path and use an on-device engine only to recover street-named turns — *Google picks the road, the open
  engine names it*. **Why we can't do it cleanly on public infra today (measured), and that NO
  self-hosting is required:**
  - Google's keyless **polyline is complete** (decoded `[0][7][i]`), so the path is fully traceable.
  - The clean tool is **map-matching** (trace → roads+turns). FOSSGIS **`/match` caps at 10 coords**
    (`TooBig` past that, ~0.01 confidence that sparse); public **Valhalla `/trace_route` times out**.
  - The serverless fallback, **dense-waypoint `/route`** (40–100 vias, no cap), reproduces Google's
    path *exactly* with 0 U-turn artifacts — **but a via landing on a turn is swallowed into a via
    arrive/depart → ~1-in-10 named turns lost** (measured: dropped "turn right onto Village Green
    Drive"). Turn-loss is the exact bug we just fixed, so always-snapping that way is a regression.
  - **Shipped instead:** option 3 — snap only on real traffic divergence, with modest (12) vias (see
    `SPEC.md` / `FEATURES.md`). Public-server-clean, keeps perfect turns on the free-flow majority.
  - **The unlock = on-device map-matching.** Engine research (2026-06-28) compared GraphHopper / Valhalla
    / BRouter / Mapbox: **GraphHopper wins** — it's **pure JVM (no NDK)**, so it runs on Android with no
    native cross-compile (GrapheneOS-friendly), its **map-matching module is embeddable + Apache-2.0**,
    and it returns street names per edge (`EdgeIteratorState.getName()` / `street_name` path detail).
    Valhalla's Meili is great but **no maintained Android binding exposes map-matching** (Rallista/
    valhalla-mobile is route-only) → would mean owning a C++/JNI Meili surface; BRouter has **no street
    names in its data** and no map-matching; Mapbox is token/MAU-gated. **JVM spike PASSED (2026-06-28):**
    GraphHopper v11, fed a **bare 26-pt downsampled polyline** (no street info, Monaco), recovered **7/7
    ground-truth street names in 34ms** — proving "scraped polyline → complete named turns" with **no
    turn-loss** (names per road-segment, not per via). `/tmp/ghspike` (throwaway).
  - **Sizing — MEASURED 2026-06-28, favourable.** A full metro (Washington DC, 21 MB extract) builds to a
    **15 MB** GraphHopper graph folder (single car profile, flexible/no-CH), import 3.7 s on desktop —
    *smaller* than the basemap tiles for the same area, and downloads the same way. A whole US state ≈ 10×.
    So "ship/download a routing graph per region" is comfortably in line with the offline-tile download we
    already do. We **import off-device** (CI/desktop) and ship the prebuilt graph; the phone only loads it.
  - **ON-DEVICE: VALIDATED end-to-end 2026-06-28** (`:ghprobe`, throwaway instrumented test, **PASSED on a
    Pixel 5a / Android 14**): GraphHopper v11 **loaded** a prebuilt Monaco graph in **137 ms**, **routed**
    1938 m / 11 instructions, and **map-matched** a bare polyline → **10 street names in 1.37 s**. So
    GraphHopper v11 *does* run on ART — but needs **three workarounds**, each found + fixed live:
    1. **`graph.dataaccess=MMAP`** (via `GraphHopperConfig`, no public DAType setter). The default
       `RAMDataAccess` static-inits a `VarHandle.withInvokeExactBehavior()` (JDK-16) that **ART lacks** →
       `NoSuchMethodError`. `MMapDataAccess` doesn't use it. (RAM_STORE & MMAP share on-disk format, so the
       desktop-built graph loads as MMAP with no rebuild.)
    2. **Dodge Janino.** v11 *mandates* custom-model profiles, compiled to JVM bytecode by Janino → ART
       can't load it (`"Cannot compile expression: can't load this type of class file"`). Fix: subclass
       `GraphHopper`, override the **`protected createWeightingFactory()`** to return a hand-rolled
       `SpeedWeighting` (Janino-free) **plus an access block** (`if !car_access → ∞`, mirroring car.json's
       `multiply_by 0`) — ~12 lines, no fork. (Plain `SpeedWeighting` ignores access → `ConnectionNotFound`.)
    3. **Swallow `close()`** — MMAP unmap goes through `Unsafe.invokeCleaner`, absent on Android. Harmless:
       the app keeps one engine for the process lifetime and never per-route closes.
    Dependency hygiene confirmed: the OSM-**import** deps (`osmosis-osm-binary`, `protobuf-java`,
    `jackson-dataformat-xml`/`woodstox`/StAX, `xmlgraphics-commons`) are **excluded** from the app — we ship
    prebuilt graphs, so they're never on the load/route/match path, and it dexes + runs clean without them.
    **Net: GraphHopper v11 on Android is PROVEN.** The `:ghprobe` module is the reference recipe; delete it
    once the real `:core` integration ports these three workarounds.
  - **Phasing.** **Phase 1 = GraphHopper as the OFFLINE router** (the hybrid the user described:
    online OSRM+option-3+Google-traffic when connected, GraphHopper A→B with named turns when not) — the
    big win (offline nav at all), and most of the value. **Phase 2 (optional) = online clean-turn
    map-matching** — in *downloaded* regions, run GraphHopper match on Google's polyline to replace option
    3's lossy dense-via with no-turn-loss naming (the original always-snap). Both need the same on-device
    runtime, selected by connectivity + graph-presence.
  - **Phase 1a — DONE 2026-06-28: engine integrated + R8-proven.** `core/data/RouteEngine` seam +
    `GraphHopperRouteEngine` (the 3 ART workarounds ported from `:ghprobe`, translates GraphHopper's path
    → Vela `Route`/`Maneuver`, DRIVE/car for now). `graphhopper-map-matching` is a `:core` dep (import-only
    deps excluded); `consumer-rules.pro` keeps graphhopper/hppc/jts/jackson for R8. **`:app:assembleRelease`
    (R8) builds clean**, `:core` unit tests green (`GraphHopperRouterTest` covers the sign/phrase mapping).
    Cost: **APK 45.7 MB (~+10 MB)** — tighter keeps / on-demand (dynamic feature, like the voice engines) is
    a later optimisation.
  - **Phase 1b-i — DONE 2026-06-28: wired into `directions()` + release runtime proven on-device.**
    `RouteEngine` is provided via Hilt (`CoreModule`, pointing at the per-region graph in app-scoped external
    files) and injected into `GoogleMapsDataSource`; `directions()` falls back to it **only when OSRM came
    back empty** (offline / FOSSGIS down) — online behaviour unchanged. On-device proof (release build,
    Pixel 5a): with wifi+data OFF and a real WA graph present, the app **loaded the graph from external
    storage and invoked the engine** (observed: 486 MB resident / climbing CPU during the compute) — so the
    R8 *release* runtime + external-storage load + offline wiring are all confirmed.
  - **Phase 1b PERF — SOLVED 2026-06-29: metro graph + Contraction Hierarchies + internal storage.**
    Two on-device perf traps, both measured + fixed:
    1. **Storage** — a whole-state graph (WA, 250 MB) on **FUSE-mapped external storage** was I/O-bound
       (25.8% CPU). Internal storage (`filesDir`/`cacheDir`) loads fast (a 53 MB metro graph: **168 ms**).
       External was only ever the adb-pushable *test* path; production downloads to internal.
    2. **Routing algorithm** — plain flexible A* with our interpreted `SpeedWeighting` override is fine on
       desktop (102 ms) but **7639 ms on the Pixel 5a** (slow ART + per-edge virtual calls). Fix =
       **Contraction Hierarchies**, prepared on the *same* `SpeedWeighting` (CH bakes the build-time
       weighting, so it must match the engine's query weighting — it does). **On-device CH route: 188 ms**
       for a 21-mi trip with 18 named steps (40× faster; `:ghprobe` `metroGraphRoutesFastFromInternalStorage`).
    Engine + `:ghprobe` + the new `tools/graphbuilder` all build CH on the shared weighting. A metro CH graph
    ≈ 53 MB (~21 MB zipped). **On-device offline routing is now proven FAST + usable.**
  - **`tools/graphbuilder` (DONE)** — standalone JVM tool (not an app dep) that builds a per-region CH graph
    matching the engine's exact config. `./gradlew :tools:graphbuilder:run --args="region.osm.pbf out-dir"`.
  - **Phase 1b-ii — DONE 2026-06-30: per-region download + END-TO-END offline routing, on-device verified.**
    `RoutingGraphStore` (`:app`) fetches a manifest (`{"regions":[{id,name,url,sizeMb}]}` at
    `BuildConfig.ROUTING_MANIFEST_URL`) and downloads + unzips a region's CH graph into internal
    `filesDir/routing-graph` (progress %, atomic swap, marker file). Settings → **Offline routing (beta)**
    lists regions with Download / Installed-delete; `directions()` already falls back to the engine when
    OSRM is empty. **On-device, full flow PASSED** (release build, Pixel 5a): downloaded the 21 MB Sacramento
    metro graph → went offline → got a complete route, **21.8 mi via NE 45th St, named turn-by-turn, ~200 ms,
    correct 28-min ETA**. (Found + fixed a real bug: GraphHopper's `SpeedWeighting` reports time as if
    `car_average_speed` were m/s, so ETAs were 3.6× too fast — engine + `graphbuilder` now override
    `calcEdgeMillis` to `distance_m·3600/kmh`.) Tested via a local manifest host over `adb reverse` +
    a localhost-cleartext `network_security_config` (production traffic stays HTTPS-only).
  - **Phase 1b-ii remaining (the deliberately-outward bits):** publish real region graphs — wire
    `graphbuilder` into **CI** (matrix over a region list → upload each CH graph + a `routing-manifest.json`
    as **GitHub release assets**, like the APK), so `ROUTING_MANIFEST_URL`'s default (the latest-release asset)
    resolves. Then offline routing is live for users. Plus: multi-region (the engine reloads on region switch;
    today one region + a process restart), and a region picker tied to the map view. **Serverless throughout.**
- **Street View** — key-gated on Google; the aligned path is open imagery
  (Mapillary/KartaView) with a free token, which is sparser.
- **Gallery videos** — parked, low value (re-checked 2026-06-19). The full `hspqX`
  gallery for a busy place (In-N-Out, 50 photos) carried **zero video entries** (no
  `googlevideo.com`/`.mp4`/`m3u8`), so videos are rare in the first place; supporting
  them would need finding a separate (likely gated) video source + a player dependency
  (ExoPlayer/media3) + handling expiring stream URLs — high effort for a feature most
  places don't have. Skip unless a specific place with videos motivates it.
- **Roboto font** — no keyless glyph host serves it; Noto Sans stays.

## Resilience (built — extend as needed)

The signed `calibration.json` channel can already hot-push **config, field paths,
user notices, and sandboxed JS parse-logic** with no app update (see SPEC §5). Future
breakages should be fixed there first.
