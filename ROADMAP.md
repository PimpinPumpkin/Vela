# Vela Maps — Roadmap

> Where Vela is going. [`FEATURES.md`](FEATURES.md) is what's **shipped**;
> [`SPEC.md`](SPEC.md) is **how it's built**; this file is **what's planned** and the
> bigger bets. Keep it current — add ideas here the moment they come up.

Last updated: 2026-06-18.

## North star

A degoogled, keyless Google-Maps replacement that reaches **parity** with Google
Maps and, over time, **leans less on Google** by growing Vela's own data layer
(starting with traffic). Privacy-first, F-Droid, GPLv3 — every new data flow is
opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## Near-term (next up)

- **Higher-res README/store screenshots** refreshed to the current UI.
- **Stability pass** — smoke-test the core flows; fix the *Start → launcher* quirk
  (nav keeps running in the foreground service but the activity backgrounds).
- **Geocoding: prefer the local match for a bare address** — *to investigate* (spotted
  2026-06-20 while debugging trip replay): a recorded "1107 S 12th St" trip from CA had
  its destination geocoded to a **1107 S 12th St in Missouri** (~2,240 km away), giving a
  cross-country route. Either search isn't biasing a bare-address query to the user's
  viewport, or there's no nearby match and it fell through to a far one. Confirm whether a
  local match exists, then bias address queries to `near`/viewport like POI search does.
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
- **Explore (nearby things to do)** — a Google-Maps-Explore-style surface: nearby
  restaurants / things to do / events, as cards on a bottom sheet from the bare map.
  Data: our keyless POI search already returns categorised places (reuse the
  category chips + `/search?tbm=map`), ranked by distance + rating; "events" is the
  harder, sparser part (no keyless Google events feed — likely OSM/OpenStreetMap +
  a public events source later, or skip v1). **Plan, not now** (per request). Start
  as "Nearby" (categories + top-rated around you); grow toward Explore.
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
   First-run prompt offers it separately from diagnostics. **Follow-up:** auto-route
   to the trip's destination on replay so turns run without manually starting nav, and
   a Stop-replay control on the map (currently auto-completes).
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
- **Offline routing** — a heavy native engine (Valhalla/GraphHopper). Multi-session.
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
