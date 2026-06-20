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

### Opt-in telemetry  *(planned — deliberate, careful)*

Two goals, **strictly opt-in**, off by default:

1. **Developer diagnostics — ✅ SHIPPED (2026-06-19, local-only).** Settings →
   Diagnostics (off by default) keeps an in-memory breadcrumb log (searches, routes,
   parser drift, nav start/reroute/arrival) the user can **Export debug session** and
   hand to a dev via the share sheet. **No backend, no auto-upload** — user-initiated +
   user-routed (`core/diag/DiagLog`, `app/diag/DiagExporter`). The remaining piece here
   is optional: a one-tap upload sink (needs the backend below) instead of manual share.
2. **Vela's own traffic data (the long game).** Crowd-source anonymized speed/route
   traces from opted-in users to build a **Vela traffic layer**, blended with Google's
   and eventually replacing it where coverage is good — the first real step off Google.

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
- **Predictive depart-time ETA** + **avoid tolls/highways** — need the directions
  `pb`'s departure-time field, and it resists discovery (re-confirmed 2026-06-19, 5th
  attempt). What's known now:
  - The keyless `/maps/preview/directions` endpoint **is traffic-aware for *now*** —
    a live probe gave typical 1408 s vs traffic 1267 s for Davis→Sac — so only the
    *future-departure* field is missing.
  - **It's a nested field, not a top-level append.** Probing 6 candidate top-level
    fields (`!8j`/`!7j`/`!9j`/`!19j`/`!8i`/`!8m2…` with a Monday-rush timestamp) left
    the traffic ETA *unchanged* — they were parsed-and-ignored, so the field lives
    inside a specific sub-message we can't guess blind.
  - The web client **never fires the endpoint on a depart-time change** (embeds the
    route in `APP_INITIALIZATION_STATE`; only `gen_204` telemetry fires), and the
    "Leave now ▾" control ignores synthetic clicks — so Chrome automation can't capture it.
  - **Unblock (≈2 min, manual):** capture ONE real request that carries a future
    departure. Easiest path that still fires the GET is **mitmproxy on the Android
    Google Maps app** (set Depart-at, grab the `/maps/preview/directions?pb=` request),
    or any session where devtools shows that GET. Hand me the `pb`; I diff it against
    `DirectionsPb.DEFAULT_TEMPLATE` to find the field, then plumb `departureTime` through
    `MapDataSource.directions` + a depart-at picker re-fetch.
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
