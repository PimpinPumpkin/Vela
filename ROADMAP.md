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

- **Busy / popular times** — a "busy now / usually busy at…" indicator on the place
  sheet (Google-style bar chart). Reachable keyless if the histogram is in the
  search/place response (investigating); else via the hidden WebView like photos/transit.
- **Higher-res README/store screenshots** refreshed to the current UI.
- **Stability pass** — smoke-test the core flows; fix the *Start → launcher* quirk
  (nav keeps running in the foreground service but the activity backgrounds).

## Big bets

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
