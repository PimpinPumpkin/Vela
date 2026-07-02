# Vela Maps — Roadmap

> Where Vela is going. [`FEATURES.md`](FEATURES.md) is what's **shipped**;
> [`SPEC.md`](SPEC.md) is **how it's built**; this file is **what's planned** and the
> bigger bets. Keep it current — add ideas here the moment they come up.

Last updated: 2026-06-30.

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
- **Offline routing on-device (GraphHopper)** — a **137-region world catalog** (all US states, Canada,
  Europe, +) built by a race-safe CI matrix, hosted on GitHub, downloaded per region; smallest-covering
  region selection; combined map+routing area download; a location-aware, filterable picker.
- **Navigation** — a **real per-lane diagram** (OSRM lane data), highway/exit shields on the banner,
  OSRM retry (fewer nameless fallbacks), and the traversed-grey trail tightened under the arrow.
- **Nav guidance de-noised (2026-07-01)** — the lane diagram now only shows within ~0.5 mi of the maneuver
  (`LANE_SHOW_M`) and the "then &lt;next&gt;" compound line only when the next maneuver closely follows
  (`COMPOUND_M`, `isCompoundNext`) + carries the next step's shield; both used to render for maneuvers miles
  ahead (reported noise). Also fixed: business name leaking into the place address (`stripNamePrefix`).
- **Alternate-route deltas (2026-07-01)** — the directions picker now shows a **"+N min"** tag on each
  slower alternate (fastest keeps "Fastest"), so the alternates are weighable at a glance (Google-style);
  device-verified on Everett→Tacoma (Fastest via I-405 & I-5, +1 min via I-405, +13 min via I-5).
  *Remaining lane idea (future):* highlight the **continuing** lanes for a compound maneuver (which of the
  exit lanes also serve the immediately-following keep/merge) — OSRM gives no cross-step lane linkage, so it
  needs a careful heuristic; deferred rather than fake it.
- **Traffic snap earns its lead** — the option-3 reroute only leads when its live ETA beats OSRM's
  free-flow best (`SNAP_ETA_MARGIN`), so a divergent-but-not-faster snap no longer wins.

*Still to validate on real drives:* route-speed parity vs Google (the snap-guard threshold is tunable
from the `directions` diag), offline highway refs (a graph rebuild — parked).

## Near-term (next up)

- ~~Visible-WebView reviews panel~~ — **SHIPPED 2026-07-01 (experimental, default ON,
  Settings toggle)**: Google's live reviews pane embedded in the place sheet's Reviews tab,
  CSS-carved, theme-matched, tracker-blocked, navigation-locked; native scraped list is the
  automatic fallback. (An iframe remains impossible — `X-Frame-Options: SAMEORIGIN`,
  verified — but a WebView isn't an iframe.) Remaining polish ideas: hide the "Get pickup
  or delivery / Order online" promo block inside the panel; make the panel height adaptive
  instead of 560 dp; extend the same treatment to the photo gallery.

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
    link** — probed on-device 2026-07-01 (Olive Garden): **NOT in `[75]`** (that node held only
    "Order online" + "Join waitlist" across 2 groups). The restaurant *does* carry a real menu URL
    (`olivegarden.com/menu/classic-entrees?…`, distinct from the website) **and** menu **photos**
    (googleusercontent `gps-cs-s` images with alt "Food menu (Front side)" / "Drink menu (Back side)"
    — these feed the photo-**categories** Menu tab, DONE). **Menu-link button DEFERRED** — the menu URL
    appears **inconsistently** across Google's responses (found in one capture, absent in the next on the
    same Olive Garden) and its positional path won't pin reliably (fragile calibration). **Lower priority
    now that the menu is accessible via the shipped photo "Menu" category tab** (the actual menu photos).
    A link button would need a stable-path capture first; parked.
    Coverage follow-up: similar-places only rides *focused* searches today — to show it on
    address-snap / list-tap opens too, do a focused name lookup on open (the OkHttp focused
    search carries `[2][11][0]`; the WebView enrichment response does not).
  - **Q&A (questions & answers) — LOGIN-GATED, not keyless (triaged 2026-07-01).** Scraped the logged-out
    `?cid=` page in a real WebView (the tactic that works for reviews/photos) on **both an attraction
    (Space Needle) and a business (Home Depot)**: **no Q&A section renders at all** — zero "question" text
    anywhere in the DOM (the only topic-ish chips are review keywords like "helpful employees, mentioned in
    37 reviews"). So Google serves Q&A only to a **logged-in** session — same "needs login" bucket as
    predictive depart-time and the login-gated popular-times path. **Not achievable within the degoogled/
    keyless posture** (no login); the `WebQuestionsFetcher` scaffold was removed. Would only unblock via an
    opt-in Google login, which the project deliberately avoids.
  - *Medium — a separate keyless RPC:* **"mentioned in reviews" topic chips** / review keyword summary
    (these DO render logged-out — the "helpful employees / mask policy, mentioned in N reviews" radios seen
    in the Q&A triage — so this one is feasible: parse those from the reviews page).
    ~~**photo categories** (menu / food / vibe tabs in the gallery)~~ — **DONE 2026-07-01**: the tabs are in
    the `?cid=` page DOM, so `WebPhotoFetcher` visits each category tab + tags photos, and the sheet shows
    All/Menu/Food&drink/Vibe filter chips (`Photo.category`/`Place.photoCategories`). Device-verified.
  - *Photo posted-dates — DEFERRED (triaged 2026-07-01):* gallery TILES (`.aHpZye`) carry only the image
    URL; the date ("Photo - Dec 2022") is only shown for a **focused** photo (lightbox), so per-photo dates
    would need opening each in the lightbox (N interactions). Low yield; `Photo.postedText` + the viewer's
    date caption already exist (unused) for if a per-tile date source ever appears.
  - *App-level:* ~~**multi-stop directions** (waypoints)~~ — **DONE 2026-07-01** (an "Add stop" row in the
    directions panel; routes OSRM straight through the stops via `routeVia` + Google traffic ratio, single
    route; device-verified). ~~Follow-ups: per-stop arrival announcement, reorder, reroute-through-remaining~~
    — **also DONE 2026-07-01**: per-stop "You've reached &lt;stop&gt;" voice cue (`NavEngine.stopMarks`,
    unit-tested), reroute-through-remaining (off-route reroute + faster-recheck now go through unreached
    stops, reaches-dest guards intact), and up/down reorder arrows (`moveStop`).
    **avoid tolls/highways** (a directions-`pb` options field — see Known-hard), **explicit lists/labels** for
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
- **Individual traffic incidents (crashes / construction / closures) — INVESTIGATED 2026-07-01, no clean
  keyless source yet.** Google shows discrete incident icons/cards ("Crash ahead", "Road closed"); Vela
  today has only the aggregate **congestion spans** (`route[3][5]`, per-segment colour). Probed the raw
  keyless `/maps/preview/directions` (OkHttp) on a 25-mi Seattle route: **423 KB, zero incident text**
  (no "crash"/"accident"/"construction"/"closure"/"closed" anywhere) and `route[3][5]` empty off-peak — so
  the OkHttp directions payload carries congestion grades but **no per-incident objects/text**. Three
  candidate paths:
  1. **WebView / Google — CHOSEN by the user, then INVESTIGATED (2026-07-01): it bottoms out at binary
     tiles, NOT a clean read.** Unlike photos/transit/popular-times (where the driving/transit page's
     `APP_INITIALIZATION_STATE` carried the data OkHttp stripped), the **driving page state has no incidents
     either** — captured it in the anonymous WebView twice (444 KB, then 471 KB), **zero incident text both
     times**. Then intercepted the WebView's network (`shouldInterceptRequest`): the **only** traffic-related
     requests are **`/maps/vt/…` — Google's proprietary binary vector tiles** (`/maps/vt/proto`,
     `/maps/vt/pb=!…!2m2!1e1!…` = the traffic layer). So incidents render from the **`vt` tile stream**, which
     is Google's own obfuscated protobuf (NOT standard MVT — MapLibre can't decode it). Getting incidents this
     way = **fetch the route's `vt` traffic tiles + reverse-engineer their binary schema + track its changes**:
     a large, fragile RE effort, the same "decode Google's `/maps/vt`" lift the route-naming note flags — an
     order of magnitude more than the page-state reads, and it can break whenever Google reshapes the tile.
  2. **Open DOT / 511 incident feeds** — the **degoogled-pure** alternative (open government data, no Google
     scrape): structured incidents w/ lat-lng, category, severity, headline — **already JSON**, no binary RE.
     Fits Vela's ethos better than scraping Google, and the user is in **WSDOT** territory (a well-documented
     free feed → live-testable). Cost: feeds are **fragmented** (per-state/metro APIs, differing shapes) and
     often **token-gated** (free, but a key → the optional-user-token model we use for `MAPTILER_KEY`, never
     committed). Pluggable provider + start with one region (like the routing catalog grew), grow coverage.
  3. **Defer** — congestion colouring already covers "where's it slow"; discrete incidents are polish.
  **Status: Google path's true cost now known (binary `vt`-tile RE, high + fragile). Given that, the
  open-feed path is likely the pragmatic way to actually ship incidents despite its fragmentation — surfaced
  to the user with the recalibrated tradeoff. All probes removed; nothing half-built shipped.**
- **On-device map-matching (GraphHopper) — the "Google routes, the engine names the turns" unlock.**
  > **✅ SHIPPED as the OFFLINE ROUTER (2026-06-30)** — Phase 1 is done end-to-end + on a 137-region world
  > catalog (see "Recently shipped" up top, `SPEC.md` §Offline routing, `FEATURES.md`). This long entry is
  > the **engineering record of how it was un-blocked**; kept for reference. Still OPEN = **Phase 2**: use the
  > same on-device engine for *online* clean always-snap (map-match Google's polyline → replace the option-3
  > via-snap). The rest below is history.

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
    OSRM is empty. **On-device, full flow PASSED** (release build, Pixel 5a): downloaded the 21 MB Seattle
    metro graph → went offline → got a complete route, **21.8 mi via NE 45th St, named turn-by-turn, ~200 ms,
    correct 28-min ETA**. (Found + fixed a real bug: GraphHopper's `SpeedWeighting` reports time as if
    `car_average_speed` were m/s, so ETAs were 3.6× too fast — engine + `graphbuilder` now override
    `calcEdgeMillis` to `distance_m·3600/kmh`.) Tested via a local manifest host over `adb reverse` +
    a localhost-cleartext `network_security_config` (production traffic stays HTTPS-only).
  - **Multi-region — DONE 2026-06-30.** Install **several** region graphs (download what you travel); the
    engine reads `filesDir/graphs/index.json` (`[{id, bbox:[S,W,N,E]}]`, written by `RoutingGraphStore` on
    install) and routes each trip on the **first installed region whose box covers both endpoints**, with a
    lazily-loaded `GraphHopper` per region. (A GraphHopper graph is monolithic, so a trip must fit inside one
    region — cross-region trips fall to online.) Manifest entries carry a `bbox`; `inBox` selection is
    unit-tested. **Sizing for "cover the world" (measured CH-graph downloads):** metro **≈ 21 MB**, US state
    **≈ 160 MB**, the whole planet as ONE graph ≈ **30 GB+ → infeasible on a phone**. So world coverage =
    the OsmAnd/Google-Offline model: a catalog of state/country graphs, download your slice. *Remaining there:*
    region granularity for big countries (split by state), and cross-region trips (bigger regions or a future
    merged graph). **Re-download of an already-loaded region still needs an app restart** (the engine caches
    the old graph) — fine for the add-regions common case.
  - **Graph HOSTING — LIVE 2026-06-30.** Region CH graphs + `routing-manifest.json` are published as assets on
    the **`routing-graphs` GitHub release** (a fixed-tag *prerelease*, so it never becomes the "Latest" the APK
    tracks). `ROUTING_MANIFEST_URL` defaults to `releases/download/routing-graphs/routing-manifest.json`.
    Seeded with **Washington (147 MB), Seattle metro (21 MB), Washington DC (6 MB)**. **Verified end-to-end on
    a Pixel 5a with a production build** (no localhost): fetched the GitHub manifest → downloaded Washington
    (147 MB) from the release → routed Everett→Seattle offline (28 min, 21.8 mi via NE 45th St).
  - **World catalog + parallel build pipeline — DONE 2026-06-30.** The catalog is now a curated
    **`tools/routing-regions.json`** (135 regions: all 50 US states, Canadian provinces + Mexico, ~36 European
    countries, and starter Asia/Oceania/South-/Central-America/Africa; `big:true` flags country-sized graphs),
    grouped so a whole continent builds in one dispatch. The **`routing-graphs` GitHub Action** is now a
    **race-safe matrix**: a `prep` job turns a `group` (or explicit `ids`) into a build matrix, parallel jobs
    each build their region's CH graph + upload only their own `<id>.zip` + a manifest *entry* artifact
    (nothing shared), and one `merge` job folds every entry into `routing-manifest.json` in a single upload
    (replace-by-id, so re-runs update in place and never clobber siblings). Public-repo Actions minutes are
    free, so this scales to the planet without touching a dev machine. `scripts/build-routing-region.sh`
    (now with `MANIFEST_MODE=emit` for the matrix) + `scripts/merge-routing-manifest.sh` are the two halves;
    the script still does all-in-one single-region builds locally. **THE ENTIRE CATALOG IS NOW BUILT +
    HOSTED — 137 regions live** (135 catalog + Seattle/DC metros; all 50 US states, 13 Canadian provinces +
    Mexico, 36 European countries incl. Germany/France/UK whole, 10 Asia, Australia/NZ, 9 South America, 7
    Central America, 7 Africa), ~22 GB of CH graphs as release assets. Every region built first try on the
    12 GB-heap runner — no OOMs. *Still open (minor):* the largest single-country graphs are big downloads
    (Germany/France ≈ 1.2 GB) — optionally split giant countries into Geofabrik subregions later; cross-region
    trips (a trip must fit one region's monolithic graph). **Serverless throughout — static release assets, no
    backend.** On-device verified end-to-end on a Pixel 5a: full 137-region picker, name filter, correct
    location-aware ordering.
    - *bbox fix (2026-06-30):* region boxes come from `osmium fileinfo -g header.boxes` (the declared extract
      region), **not** `data.bbox` (raw node extent — outlier nodes blew Oregon's box across WA + CA, so it
      falsely "covered" Seattle in the picker). All catalog builds use the corrected script.
    - *border-overlap fix (2026-06-30):* even clean `header.boxes` boxes carry a Geofabrik buffer that spills
      across borders (British Columbia's box dips into Seattle), so the picker, the tiles→routing combine, and
      the engine all now pick the **smallest** box covering you (the engine falls through to the next-smallest
      if a graph can't make the trip) instead of the first.
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
