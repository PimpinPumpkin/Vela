# Vela Maps - Roadmap history

The roadmap as it stood on 2026-09-21, before it was cut down to open items. Everything here
either shipped (the reasoning and the dates are kept because [`FEATURES.md`](../FEATURES.md)
lists the result, not the path), was investigated and found dead (kept so nobody re-chases it),
or was superseded by a later decision. Nothing in this file is planned; the live plan is
[`ROADMAP.md`](../ROADMAP.md). The big bets that are still open (self-hosted tiles, contributing
to OpenStreetMap, a Play listing, telemetry and a Vela traffic layer) stay in the live file and
are not repeated here.

Headings and dates are the originals; entries are in the order they were written, which is
roughly the order they were worked on.

## Recently shipped
- **Cronet for Google requests (2026-09-23).** Search, directions, place data and the batchexecute
  RPCs go over Chromium's network stack (HTTP/2 and HTTP/3 like Chrome) instead of OkHttp, behind
  calibration `useCronet`; OsmAnd's bundled protobuf is relocated at build time so both coexist.
- **A place tap without hidden pages (2026-09-23).** Photos, reviews and details (popular times
  included) come from one plain request each, with retries; the whole gallery pages at 10 per
  request. Cronet turned out not to be needed for popular times: Google answers a place's first
  request stripped and the repeat in full, through OkHttp as well.
- **Landmark fame by language count (2026-09-23).** The places bake adds how many languages OSM
  names a landmark in to its notability, so a famous small-footprint landmark (the Berliner
  Fernsehturm) no longer loses its cell's slots to big parks; the widest zooms' anchors use the same
  score. Was the roadmap's "Landmark fame beyond outline size".
- **Transit on open GTFS (2026-07-12/13).** Transitous (community MOTIS) is the primary source for
  departure boards, canonical stop icons on the map (offline-cached per area), and the tap-through
  stop timeline, which now reads the actual GTFS run: passed stops gray out, moved times show
  struck-through, canceled runs drop. Boards refresh every 30 s while open. Same-named curb pairs
  draw as one icon with both directions on one board. Google keeps transit directions on purpose
  (traffic-aware ETAs).
- **Speed limit on the road (2026-07-11).** OSM maxspeed streams in everywhere online; the posted
  sign sits next to your speed in one Google-style box.
- **Whole-state offline place packs + self-updating packs (2026-07-07).** Downloading a state pulls a
  CI-baked SQLite of the entire region's OSM POIs/addresses/streets, so offline search works
  Organic-Maps-style anywhere in the state. Packs rebuild monthly from fresh OSM and installed ones
  update in place through small row-level deltas (a whole-state test delta was 5.6 KB against the 143 MB
  pack). Offline typed-address geocoding shipped alongside (house-precise, interpolated, street fallback).
- **Android Auto (2026-07-08).** Vela appears in the car launcher (AA "Unknown sources" for
  sideloads): live map, puck, route, and the current-maneuver card from the same NavSession the phone
  runs, plus car-side search and route start (see docs/ANDROID-AUTO.md).
  **Open (agenda, pinned #179):** on most real head units it still does not show up, because
  Android Auto only lists navigation apps installed from Google Play and Vela is sideloaded; the
  "Unknown sources" switch and the installer spoof both get undone by the next in-app update.
  Ideas from the thread to decide between: an AAAD-style install path, or a Play-listed shell app
  that carries the Android Auto entitlement with the Google-scraping half delivered as a separate
  add-on APK from GitHub (Nova/CoMaps pattern). **Decision 2026-09-13: the Play split goes on the
  roadmap.** The shell would be map + offline routing + OpenStreetMap places, everything Play can
  carry; the Google-reading half stays a GitHub add-on. Hurdles, honestly: a developer account and
  identity verification, Play review of a navigation app, a two-APK build with a plugin seam, and
  keeping the shell useful on its own. Not started. Aftermarket head units with their own receiver
  (a user's EKIY M13A PRO, 2026-09-13) already list sideloaded Vela after King Installer + an ADB
  install; factory Android Auto (gearhead) does not, and that is the case the split is for.
- **iOS (on the radar 2026-09-13, not started).** Feasibility: `:core` is plain Kotlin (routing,
  parsers, nav engine, i18n) and would move to Kotlin Multiplatform with the Android-only bits
  (SQLite stores, WebView bridges, LocationManager) behind expect/actual seams; MapLibre has an
  iOS SDK, sherpa-onnx ships iOS builds for the neural voice and dictation, and the hidden-page
  scrapes map onto WKWebView. The Compose UI would be rewritten (SwiftUI or Compose Multiplatform)
  and CarPlay is its own approval. A second app's worth of work; listed so nobody thinks it is
  off the table.
- **Open building + house-number overlays (2026-07-04/05).** Microsoft footprints (ODbL) and OpenAddresses
  numbers as per-region PMTiles, streamed over the map by default where OSM is thin, downloadable for
  full offline. Traffic lights + stop signs draw at close zoom (keyless Overpass).
- **In-app updater + content toggles (2026-07-08).** A PipePipe-style updater checks the newest GitHub
  release (about daily, Settings toggle), downloads the APK and hands it to Android's installer. Plus
  Settings switches to hide reviews and skip photo loading, and a 3D-buildings toggle for weaker GPUs.
- **On-device voice search (Vela Voice STT) - DONE 2026-07-10, device-verified.** The search-bar mic
  transcribes IN-PROCESS with a downloadable Whisper tiny model (same bundled sherpa-onnx runtime as
  TTS, `asr-models` release hosting, ~58 MB), with an installed voice-input app as the intent-handoff
  alternative and a download offer when neither exists. Possible follow-up: bigger model tiers in the
  same catalog (tiny is the speed/size tradeoff).
- **In-process neural voice (Piper) - DONE, device-verified.** Vela bundles the sherpa-onnx VITS
  runtime and downloads a **Piper** voice itself (progress bar), running it in-process as the default nav
  voice - near-Siri quality, no standalone TTS app. Default = **HFC Female** (`en_US-hfc_female-medium`,
  ~67 MB). The old F-Droid voice-engine *installers* were removed; system engines stay selectable for
  override. *(Earlier iterations shipped Kokoro then Matcha; both were removed after on-device A/B - 
  Kokoro was ~0.4× realtime even on a Pixel 9. History in [`FEATURES.md`](FEATURES.md).)* **Next:**
  validate the arm64 `.so` load on a 16 KB-page GrapheneOS device; revisit on-demand delivery (dynamic
  feature) to shrink the base APK. *(Nav-string localization + per-language voice pairing SHIPPED - see
  the localization entry / `FEATURES.md`.)*
- **Japanese spoken voice - system-TTS today, Kokoro maybe later.** Piper/espeak-ng has no Japanese
  phonemizer, so there is no bundled Vela Japanese voice (unlike Chinese, which got the Huayan Piper
  voice). Japanese turn-by-turn is spoken by the phone's own system TTS (Google's Japanese voice is
  good), and the missing-voice pill now deep-links system voice settings so users can add one. A
  fully-**offline** Japanese neural voice would mean bundling **Kokoro** int8 multi-lang (~126 MB, also
  covers Chinese), which needs the multi-file sherpa Kokoro plumbing restored (it was deleted when
  Kokoro/Matcha were removed) and an on-device speed re-check first - Kokoro was ~0.4× realtime on a
  Pixel 9, though short nav lines may be acceptable. Deferred pending appetite for the size + a device
  speed test.
- **Voice browser - DONE 2026-07-03, device-verified.** Settings → Voice → **Voice library** downloads and
  switches between ~40 curated Piper voices (Lessac, HFC Female/Male, Ryan, LibriTTS-R, GB voices, a GLaDOS
  novelty…), each in its own `filesDir/piper/<id>/`; per-voice speaker prefs; race-free switch; in-place
  migration of the old single-voice install. Details in [`FEATURES.md`](FEATURES.md). **Next bets:** (1)
  host the catalog (`PiperCatalog`) on the signed `calibration.json` so new voices ship without an APK - 
  would also require **pinning the download host** (`github.com`) in the calibration allowlist so a
  compromised bundle can't redirect a "voice" download at an attacker binary; (2) a preview-without-
  switching ▶ (a second transient `OfflineTts`) so you can audition before committing; (3) a shared
  `espeak-ng-data` dir across voices (~10 MB saved per voice).

## North star

A degoogled, keyless Google-Maps replacement that reaches **parity** with Google
Maps and, over time, **leans less on Google** by growing Vela's own data layer
(starting with traffic). Privacy-first, F-Droid, GPLv3 - every new data flow is
opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## Recently shipped (2026-06-28 → 30)

The big recent landings - detail in [`FEATURES.md`](FEATURES.md) / [`SPEC.md`](SPEC.md), full
journeys below under Big bets / Known-hard:

- **Open router (OSRM) is now PRIMARY** - complete street-named turn-by-turn incl. **highway `ref`s /
  exit numbers / sign destinations**; Google demoted to the live-traffic overlay + jam-reroute + fallback.
- **Offline routing on-device** - first on GraphHopper (2026-06-30), on the OsmAnd obf engine since
  2026-09-15 (GraphHopper retired): a **414-piece world catalog** (every Geofabrik country plus the
  sub-areas of the big ones) built by a race-safe CI matrix, hosted on GitHub, downloaded per region;
  smallest-covering region selection; combined map+routing area download; a location-aware, filterable picker.
- **Navigation** - a **real per-lane diagram** (OSRM lane data), highway/exit shields on the banner,
  OSRM retry (fewer nameless fallbacks), and the traversed-gray trail tightened under the arrow.
- **Nav guidance de-noised (2026-07-01)** - the lane diagram now only shows within ~0.5 mi of the maneuver
  (`LANE_SHOW_M`) and the "then &lt;next&gt;" compound line only when the next maneuver closely follows
  (`COMPOUND_M`, `isCompoundNext`) + carries the next step's shield; both used to render for maneuvers miles
  ahead (reported noise). Also fixed: business name leaking into the place address (`stripNamePrefix`).
- **Alternate-route deltas (2026-07-01)** - the directions picker now shows a **"+N min"** tag on each
  slower alternate (fastest keeps "Fastest"), so the alternates are weighable at a glance (Google-style);
  device-verified on Davis→Sacramento (Fastest via I-80 & US-50, +1 min via I-80 Bus, +13 min via CA-84).
  *Remaining lane idea (future):* highlight the **continuing** lanes for a compound maneuver (which of the
  exit lanes also serve the immediately-following keep/merge) - OSRM gives no cross-step lane linkage, so it
  needs a careful heuristic; deferred rather than fake it.
- **Traffic snap earns its lead** - the option-3 reroute only leads when its live ETA beats OSRM's
  free-flow best (`SNAP_ETA_MARGIN`), so a divergent-but-not-faster snap no longer wins.

*Still to validate on real drives:* route-speed parity vs Google (the snap-guard threshold is tunable
from the `directions` diag), offline highway refs (shipped 2026-07-13 with NO rebake - GraphHopper had
been storing street_ref/street_destination on every edge all along, the engine just never read them;
worth an offline drive past a signed exit to hear it).

## Near-term (next up)

- ~~Save my parking spot~~ - **SHIPPED 2026-07-08, device-verified end to end.** Long-press
  the locate button to save the spot (toast confirms); a small "P Parked" chip sits above
  the button while one is set, survives app restarts, taps into WALK directions to a
  "Parked car" destination, and clears with a hold. Follow-up ideas: offer to save
  automatically when a drive ends; distance/age on the chip; a note or photo.

- **Import Google Maps saved lists** (issue #1) - **SHIPPED 2026-07-08,
  device-verified**: paste a share link into the search bar → the list's places land as
  results with a Save-list pill (opt-in; nothing saved until tapped) and the owner's
  per-place notes shown on each sheet (extraction documented in
  SPEC: share link resolves logged-out, page embeds a complete getlist request, parser
  unit-tested). First-class LOCAL lists shipped the same day (create/rename/icon+color,
  import INTO a list, per-place notes, file backup). Remaining: a share-TO-Vela intent
  so the link never needs copying.

- **D-pad polish** (base support SHIPPED + full-function sweep done 2026-07-07 - see
  `docs/dpad.md`): a real-device pass on D-pad hardware to tune the pan step / OK-hold
  threshold / focus-ring visibility and traversal order; **pixel-verify the full-screen
  reviews WebView's ↑/↓ page-scroll on an unfiltered network** (the handler is wired +
  reach/exit are proven, but the test device's content filter throttles the reviews carve so
  the loaded page never renders there); consider an on-screen key-hint pill while the map
  target is focused.

- ~~Visible-WebView reviews panel~~ - **SHIPPED 2026-07-01 (experimental, default ON,
  Settings toggle)**: Google's live reviews pane embedded in the place sheet's Reviews tab,
  CSS-carved, theme-matched, tracker-blocked, navigation-locked; native scraped list is the
  automatic fallback. (An iframe remains impossible - `X-Frame-Options: SAMEORIGIN`,
  verified - but a WebView isn't an iframe.) Remaining polish ideas: hide the "Get pickup
  or delivery / Order online" promo block inside the panel; make the panel height adaptive
  instead of 560 dp; extend the same treatment to the photo gallery.

- ~~Higher-res README screenshots~~ - **DONE 2026-06-21** (all 9 recaptured at
  1080×2400 on-device, current UI). Store screenshots when there's a store listing.
- **Stability pass** - core flows smoke-tested on-device 2026-06-21 (fresh install →
  search → route → transit → nav, no crashes). Still open: the *Start → launcher* quirk
  (nav keeps running in the foreground service but the activity backgrounds).
- ~~Custom directions origin~~ - **DONE + device-verified 2026-06-20 (in-panel
  editable From).** The directions panel's **From** row is tappable → opens search →
  the pick becomes the origin (`directionsOrigin: Place?`, route falls back to live
  location when null). Chose the in-panel treatment over Google's top-bar From/To.
  **Bug found + fixed on first device test (0.2.132):** the picker overlay was driven
  by `searchFocused` (tied to the text field's focus), but it was opened *without*
  focusing the field - so `clearFocus()` (every close path) was a no-op and the overlay
  got stuck (no feedback on tap, couldn't back out). Now the overlay is driven by
  `searchOpen = searchFocused || pickingOrigin` and pick-mode is reset explicitly.
  Verified: pick reroutes (In-N-Out→Sac = 19 min), back cancels cleanly. A **"Your
  location" reset row** sits at the top of the picker to drop a custom origin back to
  live GPS (added 0.2.133). ~~Follow-up: editable origin while *reversed*~~ - **DONE
  2026-06-20**: the edit pencil moves to the "To" row when reversed (where the custom
  endpoint then sits), via a parallel `onEditDestination` on the directions panel.
- ~~**Real highway shields in the nav banner**~~ - **v1 SHIPPED 2026-06-27.** Interstate
  (red-top/blue) + US-route (white) shield silhouettes drawn as Compose `Canvas` paths, a
  neutral white marker for state/provincial routes, network **inferred from the ref prefix +
  a state/province set** (`parseRouteRef`, unit-tested; `I`→interstate, `US`→US route, a
  2-letter state/province code → state, else the plain bordered chip) - no OSM lookup, as
  agreed. `ROUTE_RE` broadened to capture `XX-NN` state/province refs. **Remaining:**
  per-state/province *shapes* (a California spade vs Ontario's crown) from the **OpenStreetMap
  Americana** set ([ZeLonewolf/openstreetmap-americana](https://github.com/ZeLonewolf/openstreetmap-americana)),
  and broadening the ref capture once the **travel logs** show the real ref formats Google emits.
- **Explore (nearby things to do)** - a Google-Maps-Explore-style surface: nearby
  restaurants / things to do / events, as cards on a bottom sheet from the bare map.
  Data: our keyless POI search already returns categorized places (reuse the
  category chips + `/search?tbm=map`), ranked by distance + rating; "events" is the
  harder, sparser part (no keyless Google events feed - likely OSM/OpenStreetMap +
  a public events source later, or skip v1). **Plan, not now** (per request). Start
  as "Nearby" (categories + top-rated around you); grow toward Explore.
- **Place-page parity gaps** (vs Google Maps; 2026-06-21 audit). The new
  summary-node enrichment (review count / full hours / address / phone / price /
  attributes, backfilled from the focused re-fetch) closed the worst gaps. Remaining,
  by cost:
  - *Cheap - already in the focused node we now fetch, just lift + render:*
    ~~**"People also search for"**~~ - **DONE 2026-06-21** (`root[2][11][0]`, focused
    searches; tappable cards, device-verified). ~~**richer attribute groups**~~ - **DONE
    2026-06-21** (`attributeHighlights` → overview chip row, reuses parsed About).
    ~~**reserve / order / book action links**~~ - **DONE 2026-06-21** (`actionLabel`/`actionUrl`
    at `[1][75][0][0][5]` → prominent button; verified against a "Book online" node + unit-tested;
    a restaurant capture would confirm reserve/order land in the same slot). Remaining: **menu
    link** - probed on-device 2026-07-01 (Olive Garden): **NOT in `[75]`** (that node held only
    "Order online" + "Join waitlist" across 2 groups). The restaurant *does* carry a real menu URL
    (`olivegarden.com/menu/classic-entrees?…`, distinct from the website) **and** menu **photos**
    (googleusercontent `gps-cs-s` images with alt "Food menu (Front side)" / "Drink menu (Back side)"
 - these feed the photo-**categories** Menu tab, DONE). **Menu-link button DEFERRED** - the menu URL
    appears **inconsistently** across Google's responses (found in one capture, absent in the next on the
    same Olive Garden) and its positional path won't pin reliably (fragile calibration). **Lower priority
    now that the menu is accessible via the shipped photo "Menu" category tab** (the actual menu photos).
    A link button would need a stable-path capture first; parked.
    Coverage follow-up: similar-places only rides *focused* searches today - to show it on
    address-snap / list-tap opens too, do a focused name lookup on open (the OkHttp focused
    search carries `[2][11][0]`; the WebView enrichment response does not).
  - **Q&A (questions & answers) - LOGIN-GATED, not keyless (triaged 2026-07-01).** Scraped the logged-out
    `?cid=` page in a real WebView (the tactic that works for reviews/photos) on **both an attraction
    (a landmark) and a business (Home Depot)**: **no Q&A section renders at all** - zero "question" text
    anywhere in the DOM (the only topic-ish chips are review keywords like "helpful employees, mentioned in
    37 reviews"). So Google serves Q&A only to a **logged-in** session - same "needs login" bucket as
    predictive depart-time and the login-gated popular-times path. **Not achievable within the degoogled/
    keyless posture** (no login); the `WebQuestionsFetcher` scaffold was removed. Would only unblock via an
    opt-in Google login, which the project deliberately avoids.
  - *Medium - a separate keyless RPC:* **"mentioned in reviews" topic chips** / review keyword summary
    (these DO render logged-out - the "helpful employees / mask policy, mentioned in N reviews" radios seen
    in the Q&A triage - so this one is feasible: parse those from the reviews page).
    ~~**photo categories** (menu / food / vibe tabs in the gallery)~~ - **DONE 2026-07-01**: the tabs are in
    the `?cid=` page DOM, so `WebPhotoFetcher` visits each category tab + tags photos, and the sheet shows
    All/Menu/Food&drink/Vibe filter chips (`Photo.category`/`Place.photoCategories`). Device-verified.
  - *Photo posted-dates - DEFERRED (triaged 2026-07-01):* gallery TILES (`.aHpZye`) carry only the image
    URL; the date ("Photo - Dec 2022") is only shown for a **focused** photo (lightbox), so per-photo dates
    would need opening each in the lightbox (N interactions). Low yield; `Photo.postedText` + the viewer's
    date caption already exist (unused) for if a per-tile date source ever appears.
  - *App-level:* ~~**multi-stop directions** (waypoints)~~ - **DONE 2026-07-01** (an "Add stop" row in the
    directions panel; routes OSRM straight through the stops via `routeVia` + Google traffic ratio, single
    route; device-verified). ~~Follow-ups: per-stop arrival announcement, reorder, reroute-through-remaining~~
 - **also DONE 2026-07-01**: per-stop "You've reached &lt;stop&gt;" voice cue (`NavEngine.stopMarks`,
    unit-tested), reroute-through-remaining (off-route reroute + faster-recheck now go through unreached
    stops, reaches-dest guards intact), and up/down reorder arrows (`moveStop`).
    ~~avoid tolls/highways~~ (SHIPPED 2026-07-11 as on-device graph profiles - see Queued near-term),
    ~~explicit lists/labels for saved places~~ (SHIPPED 2026-07-08 - local lists with icons/colors/notes).
  - *Not feasible keyless / out of scope:* account features (your contributions,
    timeline, writing reviews - degoogled by design), flights/hotels booking tabs.
    (Street View used to sit here as "key-gated" - it shipped keyless 2026-07-15, see below.)
  Recommended order: the *cheap* group first (one parser+UI pass, reuses the
  enrichment plumbing), then Q&A, then review-topic chips.
- ~~Traffic browse-overlay - keep, drop, or rebuild?~~ - **RESOLVED 2026-06-19:
  hidden in Settings.** Decision (yours): keep it but **move the toggle off the map
  into Settings → Map** so it doesn't clutter - nav's per-segment route coloring is
  the primary traffic view; the whole-map raster is now an opt-in browse aid in
  Settings, subdued (below POIs, 0.6 opacity). Not dropped entirely (still useful for
  scanning a wider area), not rebuilt (no keyless vector congestion source).


- ~~Split the remaining big countries into first-level sub-regions the way Germany now is~~
  (DONE 2026-08-15, issue #254: every country Geofabrik sub-divides has its `<country>-sub`
  rows in the catalog; the workflows take a list of groups or `all-sub`).

### Buildings  *(done - keyless, no key, no infra)*

Real building footprints render now. They were **already in our tiles** - the
OpenMapTiles `building` + `building-3d` layers (OSM data, much of it imported from
Microsoft's footprints) - Vela just colored them a hair off the land so they were
~invisible; bumped the contrast + added an outline (2026-06-19). No key, no new
data. The gap-filling actually happened (2026-07-04): **Microsoft footprints (US + Global ML) ship as
per-region PMTiles** (`OverlayTileStore`, CI-baked, 361-row catalog), streamed under the OSM
buildings by default and downloadable for offline - so thin-OSM suburbs render houses now. 3-D massing at high zoom is already on via `building-3d`. **Parcels: not
pursuing** (lot/assessment data - a per-county scraping + backend commitment with
licensing heterogeneity; out of scope by decision 2026-06-19).


### Opt-in telemetry  *(planned - deliberate, careful)*

Goals, **strictly opt-in**, off by default:

1. **Developer diagnostics - ✅ SHIPPED (2026-06-19, local-only).** Settings →
   Diagnostics (off by default) keeps a local breadcrumb log (searches, routes,
   parser drift, nav start/reroute/arrival) the user can **Export debug session** and
   hand to a dev via the share sheet. **No backend, no auto-upload** - user-initiated +
   user-routed (`core/diag/DiagLog`, `app/diag/DiagExporter`). The remaining piece here
   is optional: a one-tap upload sink (needs the backend below) instead of manual share.
2. **Trip recording + replay - ✅ SHIPPED (2026-06-19, local-only).** Settings → "Save
   my trips" (a **separate, more-invasive** opt-in - it's your exact routes) records
   each navigation's GPS trace to a file (`app/replay/TripStore`); a trip replays on
   the map at 3× (`LocationProvider.replay`) to test turn-by-turn without driving.
   First-run prompt offers it separately from diagnostics. ~~Follow-up: auto-route +
   Stop-replay control~~ - **DONE 2026-06-20**: replay auto-routes to the trip's
   destination and runs real turn-by-turn (torn down when it ends), with a **Stop replay**
   control on the map. Trips also have a **Share** button (FileProvider, like the diag
   export) so a drive can be pulled off a *release* build for debugging - still
   user-initiated, never auto-uploaded. **The navigated route is now saved INTO the
   trip** (`RP`/`RD`/`M` lines, `core/replay/TripLog`), so a replay drives the exact
   blue line the user saw (not a fresh re-route), and the trip can be **audited offline**.
   ✅ **Offline nav auditor - SHIPPED (2026-06-27).** `core/nav/NavReplay` replays a
   trip's GPS fixes back through the real `NavEngine` and **diffs what the cards + voice
   said against where the maneuvers actually are on the route** - per-maneuver: announced
   how far out, turn-now fired?, worst card-distance error, nearest approach; flags
   silent/missed turns, miles-too-early announcements ("exit in 6 mi that didn't exist"),
   and lying card distances. So a shipped travel log can be analyzed **without the user
   remembering where it broke** - one call: `TripLog.audit(csv).summary()`, or the
   on-demand test harness `:core:testDebugUnitTest --tests '*auditSharedTripLog'
   -DvelaTrip=<csv>`. Unit-tested end-to-end (clean-drive measurements + the flag logic +
   a full CSV round-trip).
3. **Vela's own traffic data (the long game).** Crowd-source anonymized speed/route
   traces from opted-in users to build a **Vela traffic layer**, blended with Google's
   and eventually replacing it where coverage is good - the first real step off Google.
   The trip recorder above is the on-device half of the trace capture this would need.

**This is a departure from today's "no telemetry, no backend" stance**, so it must be
done so it *earns* trust rather than spends it:
- **Opt-in only**, clear consent screen, easy off + "delete my data," never on by default.
- **Minimize + anonymize**: no account, pseudonymous device token at most; trim precise
  start/end points (snap to road, drop the first/last ~100 m like other traffic apps);
  send speed/heading along road segments, not "user X went from home to work."
- Needs **the first Vela backend** (or a privacy-preserving collector) - pick something
  self-hostable; this becomes a thing to run/secure/subpoena-proof, the opposite of the
  current no-server design, so weigh it.
- **Update [`PRIVACY.md`](PRIVACY.md) in the same change** - it currently (truthfully)
  says "no telemetry"; that line changes the day this ships.
- Could ride the existing **signed channel** for config (endpoint, sample rate, kill-switch).


## Known-hard / blocked

- **Owner updates / local posts in the place sheet** *(investigated 2026-07-06 against a
  temporarily-closed local Thai restaurant - deferred pending a calibration sample)*. Ground truth from the live probe: a restaurant
  physically closed (paper sign on the door) during its listed hours carried **NO closure signal
  anywhere in Google's data** - no temporarily-closed status, no owner post; even google.com/maps
  showed plain hours ("Closed · Opens 11:30 AM Tue"), and its active "Order pickup online"
  integration keeps the profile looking operational. **No app can be resilient to a paper sign the
  owner never enters into any system.** What we CAN do (and shipped): first-class
  `Place.temporarilyClosed` (multilingual status-text match) → place-sheet banner + hours
  suppression, so when an owner DOES set the formal closure Vela is loud about it. The remaining
  piece - owner POSTS ("closed for renovation until…") - has a discovered keyless-family endpoint,
  **`/maps/preview/localposts?…&pb=…`** (found in the place page's endpoint table next to
  `/maps/preview/place`), but its `pb` grammar needs a live capture from a business that actually
  has owner posts; none found in a bounded hunt (posts are rare + Google demotes temp-closed places
  in search). Revisit when we encounter one - capture the RPC via devtools/Claude-in-Chrome, then
  it's the standard calibration pipeline. The full-screen reviews panel (live Google profile) shows
  Updates already when they exist.

- ~~Busy / popular times~~ - **DONE keyless 2026-06-19** (the 2026-06-18 "login-gated"
  conclusion was *wrong*). The histogram is place node `[84]`; the keyless **OkHttp**
  `/search` is bot-degraded (TLS-fingerprint, like photos/transit) and strips it, which
  fooled me. A real browser engine isn't degraded - **but** there was a second catch
  that nearly fooled me twice: even in the WebView, a **bare-name** search returns a
  20-result `[64]` list that's *also* trimmed of `[84]` (the "Usually"/"No wait" markers
  I first saw were a false positive - review text, not a histogram). The real fix is the
  **query**: a **specific name + address** search (e.g. `In-N-Out Burger 1020 Olive Dr
  Davis CA`) resolves to a *single focused result* whose `[0][1][0][14]` node keeps
  `[84]` (confirmed via live Chrome capture: bare name → 20 results, no histogram;
  name+address → one result with it). `WebPopularTimesFetcher` warms google.com→maps (an
  established NID matters), builds that specific query into both the `pb` and `q=`, then
  same-origin-fetches it; `PopularTimesParser` reads `[84]`. Lesson: when "needs login"
  comes from the OkHttp response only, try a WebView - and check the *query shape* too.
- **Predictive per-departure ETA** - still needs the directions `pb`'s departure-time
  field; re-confirmed unreachable keyless **2026-06-20 (6th attempt, deepest yet)** with a
  real-browser fetch loop + the live web client as oracle. Findings, now thorough enough
  to stop guessing blind:
  - **Read side is dead.** The 810 KB keyless response carries route geometry + the
    current/typical durations but **no embedded time-of-day duration curve** - so the web
    UI is *not* computing future ETAs from pre-shipped data.
  - **Our `pb` template is byte-identical to Google's live web client** (115 tokens,
    diffed against the page's own fired request) - there's no hidden time field we're
    merely omitting; the client sends none for "now".
  - **Direct injection is ignored or 400s.** Re-tested `!8j`/`!8m1`/`!8m3`/`!21m1`
    (accepted but ETA unchanged for a Monday-8am stamp) and `!8m2…`/`!9m2…`/`!7m2…`
    (HTTP 400). Nested-field guessing stays a dead end.
  - **The web "Leave now ▾" control is genuinely un-automatable** - neither CDP-level
    clicks nor keyboard activation open its menu (`aria-expanded` never flips), so even a
    real browser can't be driven to emit a depart-time request. Confirms the old "ignores
    synthetic clicks" note. **Conclusion: predictive per-departure is login/Android-app-
    only**; transit (already fetched via the WebView) is the only keyless mode honoring a
    chosen time.
  - **Shipped instead (2026-06-20): the typical best→worst spread.** Google's own planning
    hint lives at directions `summary[10][4] = [lowSeconds, highSeconds, label]` ("usually
    1 hr 8 min to 1 hr 27 min"); parsed into `Route.typicalRangeSeconds`, shown in the
    depart-time chooser as an honest arrival/leave **window** for a future "Depart at" /
    "Arrive by" plus an always-on "usually X–Y" line. Not per-minute predictive, but real
    keyless data instead of false precision.
  - **Only true-predictive unblock (≈2 min, manual):** capture ONE real request carrying a
    future departure - **mitmproxy on the Android Google Maps app** (set Depart-at, grab
    the `/maps/preview/directions?pb=` GET). Hand me the `pb`; I diff it against
    `DirectionsPb.DEFAULT_TEMPLATE`, find the field, plumb `departureTime` through
    `MapDataSource.directions` + a re-fetch.
- ~~Avoid tolls/highways~~ - SHIPPED 2026-07-11 as on-device graph profiles (v2 rebake), not a pb field; see FEATURES.
- **Per-review uploaded photos** - the `listentitiesreviews` RPC (our reviews source)
  returns **only the reviewer's avatar**, never their uploaded photos (verified 2026-06-20
  against Tartine + Bottega Louie: 60 image URLs, all `/a/…ACg8oc` / `/a-/…ALV-`, zero
  `/gps-cs`·`/geougc`·`/p/AF1Qip`). The old parser swept the avatar at `[12][1][3]` into
  the photo strip - now fixed to collect UGC-by-URL-shape only, so it shows nothing here
  rather than a face. SHIPPED since: reviews moved off that RPC entirely to the WebView DOM scrape of the place's
  `?cid=` page (`WebReviewsFetcher`), which carries each review's real uploaded photos - the
  place sheet shows them today. This entry stays only as the record of why the RPC path was dead.
- **Photo contributor name** - the gallery `hspqX` RPC gives each photo's URL + **posted
  date** (`[21][6][8]`, now shown as "Photo · May 2026") + an upload-source tag, but **not
  the contributor's name**: verified 2026-06-20 (every string field on a user photo is the
  url / photo-id / feature-id / source tag - no name anywhere). Google's viewer resolves
  "Photo by Kevin" via a **separate per-contributor profile lookup** keyed by an id we'd
  have to fish out and request per photo - N extra round-trips for a name. Deferred as
  low-value; the date covers the useful half. `Photo(url, postedText)` has room for an
  `author` field if it's ever worth the lookup.
- ~~Per-segment route traffic during nav (Google-parity)~~ - **DONE 2026-06-19.** The
  congestion data was hiding in plain sight in the directions response: `route[3][5][0]`
  is a list of `[level, startMeters, lengthMeters]` spans (only the non-free-flowing
  stretches; gaps are free-flow). `DirectionsParser` reads it into `Route.trafficSpans`;
  `MapScreen` converts meter offsets → fractions; `VelaMapView.routeGradientStops` paints
  the route line per segment over the driven-gray gradient (free-flow blue base, amber =
  level 1, red = level 2, dark red = 3+). Calibrated against Davis→Sac + Berkeley→SF
  (Bay-Bridge approach = one long level-2 span). The whole-map raster stays off during
  nav - the route now carries the traffic, like Google. *(Level→color mapping is the
  best read of the 1/2 grades seen; trivially flipped if a heavy drive shows otherwise.)*
- **EV charger detail (price / kW / plug availability) - INVESTIGATED 2026-07-10, keyless-stripped.**
  Gas stations carry their live price in the keyless search response (shipped: the fuelPrice path),
  but EV charging stations come back with the type marker only - no price, no connector counts, no
  availability (probed live: Electrify America / EVgo / Tesla Supercharger nodes all empty beyond
  the marker). Same class of gap as popular times: the data exists logged-in. Revisit if a keyless
  surface appears; OpenChargeMap could fill it as an open-data source.
- **Individual traffic incidents (crashes / construction / closures) - INVESTIGATED 2026-07-01, no clean
  keyless source yet.** Google shows discrete incident icons/cards ("Crash ahead", "Road closed"); Vela
  today has only the aggregate **congestion spans** (`route[3][5]`, per-segment color). Probed the raw
  keyless `/maps/preview/directions` (OkHttp) on a 25-mi the metro route: **423 KB, zero incident text**
  (no "crash"/"accident"/"construction"/"closure"/"closed" anywhere) and `route[3][5]` empty off-peak - so
  the OkHttp directions payload carries congestion grades but **no per-incident objects/text**. Three
  candidate paths:
  1. **WebView / Google - CHOSEN by the user, then INVESTIGATED (2026-07-01): it bottoms out at binary
     tiles, NOT a clean read.** Unlike photos/transit/popular-times (where the driving/transit page's
     `APP_INITIALIZATION_STATE` carried the data OkHttp stripped), the **driving page state has no incidents
     either** - captured it in the anonymous WebView twice (444 KB, then 471 KB), **zero incident text both
     times**. Then intercepted the WebView's network (`shouldInterceptRequest`): the **only** traffic-related
     requests are **`/maps/vt/…` - Google's proprietary binary vector tiles** (`/maps/vt/proto`,
     `/maps/vt/pb=!…!2m2!1e1!…` = the traffic layer). So incidents render from the **`vt` tile stream**, which
     is Google's own obfuscated protobuf (NOT standard MVT - MapLibre can't decode it). Getting incidents this
     way = **fetch the route's `vt` traffic tiles + reverse-engineer their binary schema + track its changes**:
     a large, fragile RE effort, the same "decode Google's `/maps/vt`" lift the route-naming note flags - an
     order of magnitude more than the page-state reads, and it can break whenever Google reshapes the tile.
  2. **Open DOT / 511 incident feeds** - the **degoogled-pure** alternative (open government data, no Google
     scrape): structured incidents w/ lat-lng, category, severity, headline - **already JSON**, no binary RE.
     Fits Vela's ethos better than scraping Google, and **Caltrans/511** publishes a well-documented
     free feed (live-testable). Cost: feeds are **fragmented** (per-state/metro APIs, differing shapes) and
     often **token-gated** (free, but a key → the optional-user-token model we use for `MAPTILER_KEY`, never
     committed). Pluggable provider + start with one region (like the routing catalog grew), grow coverage.
  3. **Defer** - congestion coloring already covers "where's it slow"; discrete incidents are polish.
  4. **Waze live-map alerts - PROBED 2026-08-08, DEAD.** The community-report feed (crashes, hazards,
     police, closures) would have been the perfect fit, and its `live-map/api/georss` endpoint was
     historically an open keyless GET. No longer: it now sits behind **reCAPTCHA Enterprise token
     scoring**. Probed four ways, all 403: plain HTTP client; a cold real browser on the direct URL;
     a same-session fetch from inside the loaded live-map page; and a REAL desktop Chrome session,
     where even the page's OWN api calls (reverse-geocoding) 403'd because the reCAPTCHA bootstrap
     was blocked upstream - which is exactly the failure mode this app's audience would hit, since
     degoogled users routinely block Google endpoints and Waze's anti-bot IS Google infrastructure.
     Building a safety feature on "execute Google reCAPTCHA in a hidden WebView and pass its score
     every session" is fragile by design and unrepairable via calibration when scores drop. Don't
     re-chase without evidence the gate changed.
  **Status: Google path = binary `vt`-tile RE (high + fragile); Waze = reCAPTCHA-gated (dead). The
  open DOT/511 feed path is the only live option and it is fragmented + token-shaped. Deferred; the
  congestion spans keep covering "where's it slow". Static OSM road aids (railway level crossings,
  speed humps) remain buildable any time as a sibling of the lights/stop-signs layer.**
- **On-device map-matching (GraphHopper) - the "Google routes, the engine names the turns" unlock.**
  *(Historical record. GraphHopper was the offline router from 2026-06-30 to 2026-09-15, then retired for
  the OsmAnd obf engine; the map-matching phase never shipped and would now be built on the obf side.)*
  > **✅ SHIPPED as the OFFLINE ROUTER (2026-06-30)** - Phase 1 is done end-to-end + on a 135-region world
  > catalog (see "Recently shipped" up top, `SPEC.md` §Offline routing, `FEATURES.md`). This long entry is
  > the **engineering record of how it was un-blocked**; kept for reference. Still OPEN = **Phase 2**: use the
  > same on-device engine for *online* clean always-snap (map-match Google's polyline → replace the option-3
  > via-snap). The rest below is history.

  *(Engine chosen 2026-06-28: **GraphHopper**, NOT Valhalla - see below. Multi-session.)* Beyond going
  offline, this is what makes **clean always-snap** routing possible: always take Google's traffic-smart
  path and use an on-device engine only to recover street-named turns - *Google picks the road, the open
  engine names it*. **Why we can't do it cleanly on public infra today (measured), and that NO
  self-hosting is required:**
  - Google's keyless **polyline is complete** (decoded `[0][7][i]`), so the path is fully traceable.
  - The clean tool is **map-matching** (trace → roads+turns). FOSSGIS **`/match` caps at 10 coords**
    (`TooBig` past that, ~0.01 confidence that sparse); public **Valhalla `/trace_route` times out**.
  - The serverless fallback, **dense-waypoint `/route`** (40–100 vias, no cap), reproduces Google's
    path *exactly* with 0 U-turn artifacts - **but a via landing on a turn is swallowed into a via
    arrive/depart → ~1-in-10 named turns lost** (measured: dropped "turn right onto Village Green
    Drive"). Turn-loss is the exact bug we just fixed, so always-snapping that way is a regression.
  - **Shipped instead:** option 3 - snap only on real traffic divergence, with modest (12) vias (see
    `SPEC.md` / `FEATURES.md`). Public-server-clean, keeps perfect turns on the free-flow majority.
  - **The unlock = on-device map-matching.** Engine research (2026-06-28) compared GraphHopper / Valhalla
    / BRouter / Mapbox: **GraphHopper wins** - it's **pure JVM (no NDK)**, so it runs on Android with no
    native cross-compile (GrapheneOS-friendly), its **map-matching module is embeddable + Apache-2.0**,
    and it returns street names per edge (`EdgeIteratorState.getName()` / `street_name` path detail).
    Valhalla's Meili is great but **no maintained Android binding exposes map-matching** (Rallista/
    valhalla-mobile is route-only) → would mean owning a C++/JNI Meili surface; BRouter has **no street
    names in its data** and no map-matching; Mapbox is token/MAU-gated. **JVM spike PASSED (2026-06-28):**
    GraphHopper v11, fed a **bare 26-pt downsampled polyline** (no street info, Monaco), recovered **7/7
    ground-truth street names in 34ms** - proving "scraped polyline → complete named turns" with **no
    turn-loss** (names per road-segment, not per via). `/tmp/ghspike` (throwaway).
  - **Sizing - MEASURED 2026-06-28, favorable.** A full metro (Washington DC, 21 MB extract) builds to a
    **15 MB** GraphHopper graph folder (single car profile, flexible/no-CH), import 3.7 s on desktop - 
    *smaller* than the basemap tiles for the same area, and downloads the same way. A whole US state ≈ 10×.
    So "ship/download a routing graph per region" is comfortably in line with the offline-tile download we
    already do. We **import off-device** (CI/desktop) and ship the prebuilt graph; the phone only loads it.
  - **ON-DEVICE: VALIDATED end-to-end 2026-06-28** (`:ghprobe`, throwaway instrumented test, **PASSED on a
    Pixel 5a / Android 14**): GraphHopper v11 **loaded** a prebuilt Monaco graph in **137 ms**, **routed**
    1938 m / 11 instructions, and **map-matched** a bare polyline → **10 street names in 1.37 s**. So
    GraphHopper v11 *does* run on ART - but needs **three workarounds**, each found + fixed live:
    1. **`graph.dataaccess=MMAP`** (via `GraphHopperConfig`, no public DAType setter). The default
       `RAMDataAccess` static-inits a `VarHandle.withInvokeExactBehavior()` (JDK-16) that **ART lacks** →
       `NoSuchMethodError`. `MMapDataAccess` doesn't use it. (RAM_STORE & MMAP share on-disk format, so the
       desktop-built graph loads as MMAP with no rebuild.)
    2. **Dodge Janino.** v11 *mandates* custom-model profiles, compiled to JVM bytecode by Janino → ART
       can't load it (`"Cannot compile expression: can't load this type of class file"`). Fix: subclass
       `GraphHopper`, override the **`protected createWeightingFactory()`** to return a hand-rolled
       `SpeedWeighting` (Janino-free) **plus an access block** (`if !car_access → ∞`, mirroring car.json's
       `multiply_by 0`) - ~12 lines, no fork. (Plain `SpeedWeighting` ignores access → `ConnectionNotFound`.)
    3. **Swallow `close()`** - MMAP unmap goes through `Unsafe.invokeCleaner`, absent on Android. Harmless:
       the app keeps one engine for the process lifetime and never per-route closes.
    Dependency hygiene confirmed: the OSM-**import** deps (`osmosis-osm-binary`, `protobuf-java`,
    `jackson-dataformat-xml`/`woodstox`/StAX, `xmlgraphics-commons`) are **excluded** from the app - we ship
    prebuilt graphs, so they're never on the load/route/match path, and it dexes + runs clean without them.
    **Net: GraphHopper v11 on Android is PROVEN.** The `:ghprobe` module was the reference recipe; DELETED 2026-07-11 (this section is the surviving record); it
    once the real `:core` integration ports these three workarounds.
  - **Phasing.** **Phase 1 = GraphHopper as the OFFLINE router** (the hybrid the user described:
    online OSRM+option-3+Google-traffic when connected, GraphHopper A→B with named turns when not) - the
    big win (offline nav at all), and most of the value. **Phase 2 (optional) = online clean-turn
    map-matching** - in *downloaded* regions, run GraphHopper match on Google's polyline to replace option
    3's lossy dense-via with no-turn-loss naming (the original always-snap). Both need the same on-device
    runtime, selected by connectivity + graph-presence.
  - **Phase 1a - DONE 2026-06-28: engine integrated + R8-proven.** `core/data/RouteEngine` seam +
    `GraphHopperRouteEngine` (the 3 ART workarounds ported from `:ghprobe`, translates GraphHopper's path
    → Vela `Route`/`Maneuver`, DRIVE/car for now). `graphhopper-map-matching` is a `:core` dep (import-only
    deps excluded); `consumer-rules.pro` keeps graphhopper/hppc/jts/jackson for R8. **`:app:assembleRelease`
    (R8) builds clean**, `:core` unit tests green (`GraphHopperRouterTest` covers the sign/phrase mapping).
    Cost: **APK 45.7 MB (~+10 MB)** - tighter keeps / on-demand (dynamic feature, like the voice engines) is
    a later optimization.
  - **Phase 1b-i - DONE 2026-06-28: wired into `directions()` + release runtime proven on-device.**
    `RouteEngine` is provided via Hilt (`CoreModule`, pointing at the per-region graph in app-scoped external
    files) and injected into `GoogleMapsDataSource`; `directions()` falls back to it **only when OSRM came
    back empty** (offline / FOSSGIS down) - online behavior unchanged. On-device proof (release build,
    Pixel 5a): with wifi+data OFF and a real whole-state graph present, the app **loaded the graph from external
    storage and invoked the engine** (observed: 486 MB resident / climbing CPU during the compute) - so the
    R8 *release* runtime + external-storage load + offline wiring are all confirmed.
  - **Phase 1b PERF - SOLVED 2026-06-29: metro graph + Contraction Hierarchies + internal storage.**
    Two on-device perf traps, both measured + fixed:
    1. **Storage** - a whole-state graph (250 MB) on **FUSE-mapped external storage** was I/O-bound
       (25.8% CPU). Internal storage (`filesDir`/`cacheDir`) loads fast (a 53 MB metro graph: **168 ms**).
       External was only ever the adb-pushable *test* path; production downloads to internal.
    2. **Routing algorithm** - plain flexible A* with our interpreted `SpeedWeighting` override is fine on
       desktop (102 ms) but **7639 ms on the Pixel 5a** (slow ART + per-edge virtual calls). Fix =
       **Contraction Hierarchies**, prepared on the *same* `SpeedWeighting` (CH bakes the build-time
       weighting, so it must match the engine's query weighting - it does). **On-device CH route: 188 ms**
       for a 21-mi trip with 18 named steps (40× faster; `:ghprobe` `metroGraphRoutesFastFromInternalStorage`).
    Engine + `:ghprobe` + the new `tools/graphbuilder` all build CH on the shared weighting. A metro CH graph
    ≈ 53 MB (~21 MB zipped). **On-device offline routing is now proven FAST + usable.**
  - **`tools/graphbuilder` (DONE)** - standalone JVM tool (not an app dep) that builds a per-region CH graph
    matching the engine's exact config. `./gradlew :tools:graphbuilder:run --args="region.osm.pbf out-dir"`.
  - **Phase 1b-ii - DONE 2026-06-30: per-region download + END-TO-END offline routing, on-device verified.**
    `RoutingGraphStore` (`:app`) fetches a manifest (`{"regions":[{id,name,url,sizeMb}]}` at
    `BuildConfig.ROUTING_MANIFEST_URL`) and downloads + unzips a region's CH graph into internal
    `filesDir/routing-graph` (progress %, atomic swap, marker file). Settings → **Offline routing (beta)**
    lists regions with Download / Installed-delete; `directions()` already falls back to the engine when
    OSRM is empty. **On-device, full flow PASSED** (release build, Pixel 5a): downloaded the 21 MB the metro
    metro graph → went offline → got a complete route, **21.8 mi via the crosstown arterial, named turn-by-turn, ~200 ms,
    correct 28-min ETA**. (Found + fixed a real bug: GraphHopper's `SpeedWeighting` reports time as if
    `car_average_speed` were m/s, so ETAs were 3.6× too fast - engine + `graphbuilder` now override
    `calcEdgeMillis` to `distance_m·3600/kmh`.) Tested via a local manifest host over `adb reverse` +
    a localhost-cleartext `network_security_config` (production traffic stays HTTPS-only).
  - **Multi-region - DONE 2026-06-30.** Install **several** region graphs (download what you travel); the
    engine reads `filesDir/graphs/index.json` (`[{id, bbox:[S,W,N,E]}]`, written by `RoutingGraphStore` on
    install) and routes each trip on the **first installed region whose box covers both endpoints**, with a
    lazily-loaded `GraphHopper` per region. (A GraphHopper graph is monolithic, so a trip must fit inside one
    region - cross-region trips fall to online.) Manifest entries carry a `bbox`; `inBox` selection is
    unit-tested. **Sizing for "cover the world" (measured CH-graph downloads):** metro **≈ 21 MB**, US state
    **≈ 160 MB**, the whole planet as ONE graph ≈ **30 GB+ → infeasible on a phone**. So world coverage =
    the OsmAnd/Google-Offline model: a catalog of state/country graphs, download your slice. *Remaining there:*
    region granularity for big countries (split by state), and cross-region trips (bigger regions or a future
    merged graph). **Re-download of an already-loaded region still needs an app restart** (the engine caches
    the old graph) - fine for the add-regions common case.
  - **Graph HOSTING - LIVE 2026-06-30.** Region CH graphs + `routing-manifest.json` are published as assets on
    the **`routing-graphs` GitHub release** (a fixed-tag *prerelease*, so it never becomes the "Latest" the APK
    tracks). `ROUTING_MANIFEST_URL` defaults to `releases/download/routing-graphs/routing-manifest.json`.
    Seeded with **a whole state (147 MB), a metro region (21 MB), Washington DC (6 MB)**. **Verified end-to-end on
    a Pixel 5a with a production build** (no localhost): fetched the GitHub manifest → downloaded the state graph
    (147 MB) from the release → routed a ~22 mi metro trip offline (28 min, named turn-by-turn).
  - **World catalog + parallel build pipeline - DONE 2026-06-30.** The catalog is now a curated
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
    HOSTED - 137 regions live** (135 catalog + the metro/DC metros; all 50 US states, 13 Canadian provinces +
    Mexico, 36 European countries incl. Germany/France/UK whole, 10 Asia, Australia/NZ, 9 South America, 7
    Central America, 7 Africa), ~22 GB of CH graphs as release assets. Every region built first try on the
    12 GB-heap runner - no OOMs. *Still open (minor):* the largest single-country graphs are big downloads
    (Germany/France ≈ 1.2 GB) - optionally split giant countries into Geofabrik subregions later; cross-region
    trips (a trip must fit one region's monolithic graph). **Serverless throughout - static release assets, no
    backend.** On-device verified end-to-end on a Pixel 5a: full 135-region picker, name filter, correct
    location-aware ordering.
    - *bbox fix (2026-06-30):* region boxes come from `osmium fileinfo -g header.boxes` (the declared extract
      region), **not** `data.bbox` (raw node extent - outlier nodes blew Oregon's box across two neighboring states, so it
      falsely "covered" the metro in the picker). All catalog builds use the corrected script.
    - *border-overlap fix (2026-06-30):* even clean `header.boxes` boxes carry a Geofabrik buffer that spills
      across borders (British Columbia's box dips into the metro), so the picker, the tiles→routing combine, and
      the engine all now pick the **smallest** box covering you (the engine falls through to the next-smallest
      if a graph can't make the trip) instead of the first.
- **Street View - IN-APP, keyless, SHIPPED 2026-07-15 (`streetview-inapp`).** The earlier
  "reverted, renders black" conclusion was testing the WRONG approach: embedding Google's OWN
  WebGL SPA in a WebView (which Google serves a stripped shell → black on ANGLE). The right way,
  what every open Street View viewer does, is to render the imagery OURSELVES: resolve the nearest
  pano's metadata keyless (the JS Maps API's `GeoPhotoService.SingleImageSearch`, `pb` in
  calibration.json, `StreetViewParser`), fetch the equirect TILES keyless
  (`streetviewpixels-pa.googleapis.com/v1/tile`, referer-gated - the OLD note tried `/v1/thumbnail`
  which 403s; `/v1/tile` is what the consumer viewer uses and it works), stitch a zoom level, and
  texture it onto a GL sphere (`PanoramaView`, GLES2, drag-to-look + pinch-zoom). The place sheet's
  Street View pill now opens this in-app viewer (no more external hand-off). Device-verified on the
  4a: real imagery, correct orientation, drag + close all work. **v2 SHIPPED 2026-07-15:** sharper
  tiles (zoom 3), faster panning, the capture date in the attribution, on-screen **walk arrows**
  (fetch the neighbor by pano id via `photometa/v1` so the year matches the picture), and **time
  travel** through a spot's older captures (a clock chip lists the dates, all keyless from the same
  response). **Remaining polish:** walking can step to a different-year neighbor (Google keeps you
  in-epoch - the neighbor graph carries no per-pano date to filter on, so it's a known quirk),
  exact initial-heading alignment, higher-zoom tiles on pinch-in, coverage-gate the pill.
- **Gallery videos** - parked, low value (re-checked 2026-06-19). The full `hspqX`
  gallery for a busy place (In-N-Out, 50 photos) carried **zero video entries** (no
  `googlevideo.com`/`.mp4`/`m3u8`), so videos are rare in the first place; supporting
  them would need finding a separate (likely gated) video source + a player dependency
  (ExoPlayer/media3) + handling expiring stream URLs - high effort for a feature most
  places don't have. Skip unless a specific place with videos motivates it.
- **Roboto font** - DONE 2026-07-11: self-hosted glyphs (Roboto composited over Noto per
  glyph, built by `scripts/build-map-fonts.sh`) served from the repo's GitHub Pages; the
  app patches the live Liberty style's glyphs URL at launch (`ui/map/MapFonts`) with a
  probe-and-fall-back to plain Noto. Needs the `map-fonts` release published + one
  fdroid-repo.yml run to light up.


## Queued near-term

- **Try side streets around plate cameras automatically (issue #600, some1ataplace, 2026-09-20;
  SHIPPED 2026-09-21 as Settings > Navigation > Cameras > "Try side streets around cameras", off by
  default, see FEATURES).** Kept here for the reasoning.** With "Avoid surveillance cameras" on, every route the routers offer can
  still pass cameras, and the reporter's workaround is to long-press "route through here" on a
  parallel street by hand every trip. The machinery exists: the camera positions, `routeVia` through
  invisible points (the divergence snap already does it), the per-route camera count and the
  existing detour limit (the lesser of 25% or 10 minutes). The shape: for each camera cluster on the
  best route, drop ONE via about 150 m to either side of the route at the cluster and let the open
  router's snap find whatever road is there (the same road means the detour collapses and is
  rejected by the count; a parallel street means a real detour); keep a candidate only when its
  camera count drops and its cost is inside the limit, then put it at the top with its badge. The
  spur guards (`hasSpur`, `VIA_SNAP_MAX_M`) already reject a via that landed somewhere silly.
  STEP ONE LANDED THE SAME DAY: Google is now asked for the trip THROUGH the stops (it never was;
  every trip with stops was the open router's free-flow choice with Google's direct-trip speed
  painted on), so a detour via handed to `directions(waypoints=)` is routed and priced by Google
  with traffic, and the open router only names the turns. What is left is the candidate placement
  and the count-and-limit loop.
  TWO RULES. It is opt-in and capped (a few clusters, a handful of extra requests), because a
  three-cluster route becomes up to seven open-router requests instead of one and FOSSGIS is
  fair-use infrastructure. And the candidate goes through `applyTraffic` like every other route,
  so its ETA is Google's in-traffic figure and the compare against the limit is honest; a free-flow
  detour "within 10 minutes" of a traffic-aware route is not a fair comparison. Offline the obf
  engine chains through points already. The reporter's other ideas (a settable detour limit, a
  visible deletable stop instead of an invisible one) are worth their own issues.
  **FOLLOW-UP, the holistic pass (user 2026-09-21):** the shipped pass only detours the route that
  LEADS after the camera re-rank, and the two stages can disagree. A route with three cameras all
  on one arterial with a parallel street beside it detours better than the one-camera route whose
  camera sits on a bridge, but the one-camera route wins the re-rank and the pass never looks at
  the other. The holistic version runs the cluster/offset pass on every drivable candidate, scores
  each result by cameras left plus time added, and leads with the best; the cost is the request
  budget (six per route, not six in total), so it wants a shared cap or a cheap pre-screen that
  skips a route whose cameras sit where the geometry offers no parallel road. Whether the two
  toggles then become one switch is the same decision: today "avoid" costs no requests and "side
  streets" costs a handful, which is why the second is nested and off.

- **Android 16 Live updates for the nav notification (issue #595, DodoLeDev, 2026-09-18).** Android
  16 promotes an ongoing activity into the status bar chip and onto the lock screen
  (`Notification.ProgressStyle` plus the promoted-ongoing request), and Google's own example for it
  is turn-by-turn navigation. Vela already runs a foreground nav service with a turn notification,
  so the content exists; this is about presenting it the way the platform now expects. Fits the
  ground rules: no backend, no key, no new data.
  The real cost is the TOOLCHAIN, not the feature: the APIs need compileSdk 36 and the project is on
  compileSdk 35 with AGP 8.7.3, so this pulls in an AGP and Gradle bump, a targetSdk decision, and a
  pass over everything a targetSdk change alters (notification behavior, foreground service types,
  permissions). That is a release-sized change to make on purpose rather than as a side effect, and
  it needs a device on Android 16 to verify against. Worth doing, once somebody is ready to own the
  upgrade.

- **Bake the Microsoft footprints INTO the basemap archive (MEASURED 2026-09-18; the premise did
  not hold).** The idea: subtract the footprints OpenStreetMap already has, merge the rest into the
  basemap archive as the same `building` layer, and drop both the second download and the
  render-time coverage gate. Prototyped on Delaware against the real archives:

  | | |
  | --- | --- |
  | Microsoft footprints already mapped in OSM | **12%** (900 sampled against all 112,408 OSM buildings in the state) |
  | basemap archive / overlay / merged | 20.5 MB / 22.0 MB / **42.4 MB** |
  | `tile-join` of the two | 6 seconds, layers merge by name |

  So subtraction removes about an eighth, not most, and a merged archive is roughly DOUBLE. That is
  not a surprise in hindsight: US OSM building coverage is thin, which is the whole reason the
  overlay exists. The merge saves no bytes at all (both sides are already compressed tiles: 42.4
  merged vs 42.5 apart).

  What it would still buy: one source instead of two on the phone, and a downloaded REGION would
  gain real buildings, which today it does not get at all (only a saved viewport area pulls the
  overlay). What it cannot buy: deleting `runOvlGate`, because ONLINE the basemap is OpenFreeMap's
  and the overlay still has to stream separately; the gate could only be skipped when a merged
  local archive is in use.

  **Parked behind self-hosted tiles** (see Big bets): most of the value, including deleting the
  coverage gate rather than skipping it, only arrives when the online basemap is ours too.

  **Cheaper route to the same offline result:** have a region download pull the building overlay
  the way a saved area already does. Identical bytes, no bake change, no format coupling between
  the two datasets. The merge is then a tidiness win (one file, one manifest) rather than a size
  one, and worth doing only if the second source is itself the problem.

- **The neural voice's phonemizer is the weak link (2026-09-18, from a drive).** espeak's G2P sits
  in front of the Piper model and it reads text that is not prose: "5:49 PM" came out as a height,
  "five foot nine", in the closing-soon warning. The pattern is old and the workarounds are stacking
  up: street ordinals are spelled out, "I-80" and "CA-99" are expanded, "take exit 186" is rewritten
  because it mis-voweled, " toward " gets a comma to break the clause, every fragment gets terminal
  punctuation so the model does not swallow the last consonant, and now clock times are spelled out
  (`SpeechText.spokenClock`). Each one is right on its own and together they are a signal: we are
  patching the TEXT because we cannot fix the phonemizer. Options, roughly in order of cost:
  a better-behaved model in the same runtime (Kokoro was measured too slow in 2026, worth
  re-measuring on current phones), a model whose front end does its own normalization, or training
  one. Anything chosen has to keep the constraints that killed the last attempt: in-process, no
  network, arm64, and fast enough on a Pixel 4a to speak a turn before you reach it. Until then, any
  new spoken string that carries numbers, units or punctuation needs a look at what espeak does with
  it, and a test in `SpeechTextTest`.

- **Delta updates for downloaded archives (measured 2026-09-18, WORTH BUILDING).** Place packs
  already update through row-level deltas; the places and basemap PMTiles archives do not, so a
  rebaked region offers a full few-hundred-MB download, and with a seventh of the catalog rebaking
  nightly that offer now comes round weekly. Measured with `scripts/archive-churn.py` +
  `.github/workflows/places-churn.yml` (bake a region twice, one OSM extract a week apart, compare
  every tile and build a real zstd patch):

  | region | tiles changed | bytes changed | zstd delta vs full |
  | --- | --- | --- | --- |
  | Kentucky, 7 days | 1.3% (3,352 of 261,686) | 3.2% | **4.4 MB of 183 MB (2.4%)** |
  | Andorra, 6 days | 11% | 25% | 0.4 MB of 1.7 MB (22%) |

  A week of edits leaves 98.7% of a state's tiles byte-identical; Andorra's 22% was a small archive
  exaggerating what one edit touches, which is why the state number was worth waiting for. So a
  weekly refresh costs 4 MB instead of 183.

  Shape, following the place packs: the bake fetches the previously published archive, writes
  `places-<id>.<fromRev>.zpatch` beside the new one, and the manifest row gains
  `delta: {fromRev, url, sizeMb}`; the app takes the patch only when `installedRev == fromRev` and
  falls back to a full download otherwise, exactly as `PoiPackStore.applyDelta` already does.

  **The patch applies IN PLACE, which is the whole design (2026-09-18).** `zstd --patch-from` and
  rsync-style block sync both assemble a new file, so applying one wants about 2x the region free:
  370 MB for this state, over a gigabyte for California. A PMTiles archive does not have to be
  rebuilt to be updated. The 127-byte header carries the root-directory, leaf-directory and
  tile-data offsets, so a patch can append the changed and new tile blobs, append rebuilt
  directories after them, and flip the header pointers last. Extra disk is the patch, not the
  archive, and it is crash-safe by construction: until that final 127-byte write the file is still
  the old archive with unused bytes on the end. The cost is dead space where the replaced tiles were
  (about the changed bytes per update, so ~3% a week here), cleared by an occasional compaction or a
  plain re-download.

  **Existing tooling, checked 2026-09-18:** go-pmtiles carries `makesync` and `sync`, hidden from
  `--help` and marked experimental. `makesync` works and writes a small block-hash sidecar (1,228
  bytes, 92 blocks, for a 1.7 MB archive), so if a block-sync route is ever wanted the format need
  not be invented. `pmtiles sync --dry-run` panicked with a nil pointer dereference on the first
  local pair it was given. Either way the applier is ours: it runs on a phone, in Kotlin, and no
  Android library patches PMTiles.

  **Acceptance criterion (user 2026-09-18): a delta-updated archive must be indistinguishable from
  a fresh download.** The failure mode to design out is drift, where an archive that has taken
  twenty patches is subtly not what a fresh bake would give you and nobody can tell. Two parts:
  the manifest publishes a content fingerprint (a hash over the sorted tile ids and tile hashes,
  which `archive-churn.py` already computes on both sides), the app recomputes it after applying
  and falls back to a full download when it does not match, so an archive can never quietly
  diverge; and dead space from replaced tiles is tracked, with a compaction (or a plain
  re-download, which is the same bytes) once it passes a threshold. Without both, deltas are not
  worth shipping: the point is saving bandwidth, not accumulating a slightly wrong map.

  **VALIDATED ON THE DEVICE 2026-09-18.** `scripts/pmtiles-append-patch.py` patches a real 152 MB
  region archive the way the design says: append the rewritten tile blobs, append the rebuilt
  directory, flip the header last. MapLibre read it - 127 range requests, no crash, places drawing
  from a file whose root directory sits 153 MB in - so the append-in-place design holds and the 2x
  disk is avoidable. Three things the test taught that reading the spec did not:
  - A real archive has LEAF directories (48 on that one); the applier has to walk them. The
    prototype collapses them into one root, which MapLibre accepted, and which is fine for a LOCAL
    archive since nothing range-fetches it.
  - `pmtiles verify` REJECTS the result, because the header's length fields no longer account for
    the whole file once there is dead space in it. The reference Go reader and MapLibre both read
    it happily. Worth knowing before someone runs verify on a user's archive and panics.
  - The first attempt crashed MapLibre with "incorrect header check" and it was the TEST HARNESS:
    python's http.server ignores Range and answers a range request with the whole body. The control
    (an unpatched archive over the same server) crashed identically, which is the only reason the
    layout did not get the blame. Serve ranges when testing a pmtiles URL.

  **Still to validate before building:** appending leaves the archive UNCLUSTERED, and the app's local
  archives are read by MapLibre's own PMTiles implementation, not by ours. Unclustered archives are
  legal (go-pmtiles ships a `cluster` command to re-optimize them) and Vela's `PmtilesReader` already
  handles arbitrary offsets, but MapLibre's reader is a third implementation. Prototype: mutate an
  archive, `pmtiles verify` it, load it on the 4a. If MapLibre refuses, the fallback is to keep the
  archive clustered by writing a new file, which is where the 2x came from.

  Bake side: emit the patch against the previously published rev (the archive is already downloaded
  for the manifest rebuild), publish it beside the archive, and add `delta: {fromRev, url, sizeMb}`
  to the manifest row, exactly as the place packs already do.

- **Reroute on the phone first (deferred 2026-09-16).** When a downloaded region covers the drive,
  compute the reroute with the on-device engine at once (no network), then swap in the
  traffic-aware online route when it arrives through the existing heal path. Evidence: a shared
  diagnostics export (issue #557) shows two urgent reroutes timing out at 20 s while the open router
  hung and the escalated ladder taking another 105 s, and issue #258 reports the same pattern in
  cities. Held back because every latch back onto the online route is new bug surface.
  Since 2026-09-17 the on-device engine is a bounded FALLBACK inside a reroute (after the open
  router fails fast, raced against Google's answer), which covers the stalled-router case; the
  "phone first, heal later" order is still open.
- **Use Vela without Google (deferred 2026-09-16).** One master switch plus individual toggles,
  including "no Google routing or live traffic" for people who want Google places but not Google
  directions. Places from Vela data, taps not looked up, search from place packs plus an open OSM
  geocoder online, directions from the open router online and the on-device engine offline, no
  reviews, photos, Street View or popular times. The open question is transit directions (a
  Transitous plan route) and free-flow ETAs without traffic.
- **Dense-city frame rate (2026-09-16).** The close-zoom "slowness in every mode" was the phone
  throttling. Cool, the biggest single cost in Midtown was the Google places layer (fixed with a
  higher GeoJSON maxzoom). What remains at one zoom step in is label placement (43 fps; 60 with every
  symbol layer hidden). Next levers: fewer Google labels at street zoom, fewer basemap labels.

- **More places sources for the open bake (queued 2026-09-15).** Overture's Davis rows come from
  Meta 1,537 / BrightQuery 494 / Microsoft 366 / Foursquare 260 / AllThePlaces 30 / DAC 6 (of
  2,693), so a business with no Facebook page and no Bing entry is simply absent, chains included.
  Next: merge AllThePlaces directly at bake time (the open project that scrapes every chain's own
  store locator weekly and publishes it as CC0 GeoJSON; Overture only takes a sliver), keyed by
  name plus distance against the Overture rows so a locator point fills a gap and never doubles a
  listing. After that, the long tail with no web presence at all: chamber-of-commerce member
  lists and municipal business-license registers where a city publishes them as open data, one
  scraper per source, in the same shape as an AllThePlaces spider. The goal is the small
  independent places Overture misses, not another copy of what it has.
- **Docs audit and cleanup (queued 2026-09-15).** README, FEATURES, ROADMAP, PRIVACY, CLAUDE and
  docs/ have grown by accretion for three months: shipped items still listed as plans, plans
  superseded by later decisions, features described three times in three tenses, and a FEATURES
  file that reads as a changelog. One pass to state what the app does today in one place, move
  the history into a changelog, prune the roadmap to what is actually still open, and make the
  contributor notes in CLAUDE.md findable by topic instead of by date.
- **Talk to the OpenStreetMap community about one-tap contributions (queued 2026-09-15).**
  Vela already links an open (Overture) place to the same business elsewhere and can tell when a
  listing is closed, moved, renamed or missing. The plan: a Vela-side service that, on a user's
  tap ("this place is gone", "this place is missing", "wrong hours"), verifies the claim against
  the business's own website or social page, never against Google, and files it into OSM as a
  note or a reviewed edit under the OSM import and automated-edit guidelines, with the OSM
  Foundation and the Data Working Group consulted BEFORE anything is pushed. Licensing is the
  whole question: Overture places are CDLA-Permissive 2.0 and a business's own site is a primary
  source, both fine for ODbL; anything traced from Google is not and never enters the pipe. Until
  that conversation happens, Vela's in-app fixes stay local (the closed-listing hide list).
- ~~Avoid tolls / avoid highways~~ - LIVE 2026-07-11: sticky chooser chips, honored by the
  on-device graphs (all 135 regions rebaked with avoid CH profiles; the app reads the v2
  manifest). The public OSRM rejects exclude, so an online-only trip falls back to a normal
  route; a possible follow-up is nudging "download this region to use avoid offline" when a
  toggle is on with no covering graph.
- On-street bike lanes: dedicated cycleways (OSM highway=cycleway) now render in Google's teal
  via `vela-bikeroutes` (2026-07-11). On-street painted lanes (`cycleway=lane` on a road way) are
  not in the keyless OMT tile schema, so a full "bike lanes everywhere" accent would need an
  Overpass layer (a sibling of `OverpassTrafficSignals`) fetching `cycleway`/`bicycle=designated`
  ways in the viewport. Deferred; the off-street network covers most of what Google shows.
- Menu photo dates: CLOSED as a calibration fix (desktop capture, 2026-07-11). A live
  `maps.google.com` capture of the on-load `hspqX` RPC proves the endpoint + field-index matrix
  are byte-identical to `calibration.json`'s `photosProto` - nothing drifted, so a bump is a
  no-op. The RPC is bot-gated to zero photos: Vela's ftid form, the captured per-page photo-token
  form, and the genuine page's own fresh-token request all returned an empty photo list from an
  automated browser (same TLS/behavioral degradation OkHttp hits). The live gallery the app shows
  comes from the WebView DOM walk (categories, no dates). Dates would need the RPC answered inside
  a trusted non-automated session the keyless model can't mint - not pursued. The in-app date-join
  plumbing stays ready + inert (see CLAUDE.md).
- Ambient POI dot tiers like Google - DONE 2026-07-11: a circle layer under the ambient
  icons draws every place as a small category-colored dot; collision losers stay visible
  as dots and upgrade to icons on zoom-in.
- Map label font trickle-down: map text now renders from the self-hosted Roboto glyph pack
  (matches the app font today); true inheritance means regenerating that pack from the same
  font file the app ships (`scripts/build-map-fonts.sh` is the hook) and republishing the
  `map-fonts` release. Runtime inheritance is not possible in MapLibre.
- Restaurant menu reliability: instrument the gallery walk to classify tab-less fetches,
  stop caching a tab-less result forever, and separate device render timing from Google-side
  variance.
- Performance pass: frame profiling of dense-marker pans and the POI sheet in/out churn.

---
