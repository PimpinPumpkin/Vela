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

1. **Developer diagnostics (now-useful).** When a user hits a bug — a wrong route, a
   bad ETA, a parse failure — let them **share that session** (the route, the request
   that drifted, logs) so it's debuggable without guesswork. Think "attach a trace to
   a bug report," not always-on tracking.
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

- **Busy / popular times** — *session-gated, not reachable keyless* (investigated in
  depth 2026-06-18). The histogram lives at place node `[84]` but Google **strips it
  from the keyless `/search?tbm=map` response** — it ships only to a full/logged-in
  session (proven: a logged-in browser capture has `[84]`; the same request with
  `credentials:'omit'` and the real on-device keyless session both lack it). The
  photos/transit WebView trick **doesn't rescue it either**: the anonymous headless
  WebView's maps SPA never renders the place panel (state stays a 31 KB shell, DOM
  223 chars), and the place-detail RPC it would use (`/maps/preview/place`) needs a
  per-page-load session token and returns a 1.2 KB stub without it (even logged-in).
  Unlike photos/transit — which a *logged-out* browser genuinely receives — popular
  times needs a real signed-in session. The model + parser + UI are **built and
  dormant** (`Place.popularTimes`, `SearchParser.parsePopularTimes`, PlaceSheet's
  `PopularTimesSection`): they auto-light-up if `[84]` ever appears in a keyless
  response, but won't today. **Unblock path:** an *opt-in Google login* (a deliberate
  departure from the keyless principle — would also unlock other gated data); decide
  before building. See SPEC §"Gated / not keyless".
- **Predictive depart-time ETA** + **avoid tolls/highways** — need a manual devtools
  capture of the directions `pb`'s departure-time field; the live web no longer fires
  the `/maps/preview/directions` GET on changes, so Chrome automation can't capture it
  (see memory). Needs a one-off manual capture.
- **Offline routing** — a heavy native engine (Valhalla/GraphHopper). Multi-session.
- **Street View** — key-gated on Google; the aligned path is open imagery
  (Mapillary/KartaView) with a free token, which is sparser.
- **Gallery videos** — feasible but low-value (uncalibrated, expiring stream URLs, a
  player dependency); parked.
- **Roboto font** — no keyless glyph host serves it; Noto Sans stays.

## Resilience (built — extend as needed)

The signed `calibration.json` channel can already hot-push **config, field paths,
user notices, and sandboxed JS parse-logic** with no app update (see SPEC §5). Future
breakages should be fixed there first.
