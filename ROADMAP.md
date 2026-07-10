# Vela Maps - Roadmap

> What is planned and the bigger bets. [`FEATURES.md`](FEATURES.md) is what is shipped;
> [`SPEC.md`](SPEC.md) is how it is built. Add ideas here as they come up.

## North star

A degoogled, keyless Google-Maps replacement that reaches parity with Google Maps and,
over time, leans less on Google by growing Vela's own data layer (starting with traffic).
Privacy-first, F-Droid, GPLv3; every new data flow is opt-in and documented in
[`PRIVACY.md`](PRIVACY.md). What has already landed lives in [`FEATURES.md`](FEATURES.md).

## Near-term

- **D-pad hardware pass.** Base and full-function D-pad support is shipped (see
  [`docs/dpad.md`](docs/dpad.md)); next is a real-device tuning pass (pan step, OK-hold
  threshold, focus-ring visibility, traversal order) and pixel-verifying the full-screen
  reviews WebView page-scroll on an unfiltered network.
- **Explore / Nearby.** A bottom-sheet of nearby restaurants and things to do, reusing the
  keyless categorised POI search ranked by distance and rating. Events are the sparse part
  (no keyless Google feed; likely OSM plus a public events source later). Start as "Nearby."
- **Place-page parity gaps** (vs Google Maps):
  - *Feasible keyless:* "mentioned in reviews" topic chips (they render logged-out, parse
    from the reviews page); a focused name lookup on address-snap / list opens so "people
    also search for" shows there too.
  - *Login-gated, out of scope:* Q&A (renders only logged-in), writing reviews, contributions,
    timeline.
  - *Deferred:* a menu-link button (the menu URL's positional path is unstable; the photo
    "Menu" category tab covers it), per-photo posted dates on gallery tiles.

## Big bets

### Opt-in telemetry (planned, strictly opt-in, off by default)

The local halves are shipped: **developer diagnostics** (Settings -> Diagnostics, an in-memory
breadcrumb log the user can export and share, no backend, no auto-upload) and **trip recording
plus replay** (a separate, more-invasive opt-in that records GPS traces for replay and offline
nav audit). The open bet is **Vela's own traffic data**: crowd-source anonymized speed/route
traces from opted-in users into a Vela traffic layer, blended with Google's and eventually
replacing it where coverage is good. This departs from today's "no telemetry, no backend"
stance, so it must earn trust: opt-in only with clear consent and easy delete; minimize and
anonymize (pseudonymous token, snap-to-road, drop the first and last ~100 m); it needs the first
Vela backend (something self-hostable); and [`PRIVACY.md`](PRIVACY.md) updated in the same change.
It could ride the existing signed config channel.

### Vela traffic layer

Depends on the telemetry above. Aggregate opted-in traces into per-segment speed vs free-flow,
then a traffic overlay and traffic-aware ETAs that do not need Google. Start as a supplement to
Google's traffic tiles, grow as coverage allows.

## Known-hard / blocked

- **Traffic incidents** (crashes / construction / closures). Vela has aggregate congestion
  colouring but not discrete incident cards. Google renders incidents from its proprietary binary
  `/maps/vt` tiles (not standard MVT), so scraping them is a large, fragile reverse-engineering
  effort. The pragmatic path is open **DOT / 511 feeds** (structured JSON, degoogled-pure, but
  fragmented per state/metro and sometimes token-gated): a pluggable provider, starting with one
  region. Deferred for now since congestion colouring already shows where it is slow.
- **Predictive per-departure ETA.** Confirmed unreachable keyless: the response carries no
  time-of-day duration curve, our `pb` is byte-identical to Google's web client, direct time-field
  injection is ignored or 400s, and the web "Leave now" control is un-automatable. It is
  login / Android-app-only. Shipped instead: the honest "usually X to Y" spread from
  `summary[10][4]`. The only true unblock is capturing one real depart-at request via mitmproxy on
  the Google Maps Android app, then plumbing `departureTime` through `MapDataSource.directions`.
- **Owner posts / local posts** (deferred pending a calibration sample). `Place.temporarilyClosed`
  ships (status-text match -> banner plus hours suppression); the remaining piece is owner POSTS,
  whose keyless endpoint (`/maps/preview/localposts`) needs a live capture from a business that has
  them.
- **In-app Street View.** The keyless consumer pano works in a desktop browser but renders black in
  an Android WebView (ANGLE GL driver plus a bot-degraded SPA shell). Shipped: an "open externally"
  pill (`ACTION_VIEW` the keyless pano). In-app panos would need open imagery (Mapillary / KartaView)
  with a free token.
- **Online clean map-matching (GraphHopper Phase 2).** Offline routing shipped (GraphHopper, see
  FEATURES / SPEC). The open bet is using the same on-device engine, in downloaded regions, to
  map-match Google's traffic-smart polyline and recover street-named turns, replacing option 3's
  lossy dense-via snap. Public map-matching infra cannot do it cleanly (FOSSGIS `/match` caps at 10
  coords, Valhalla times out), which is why it is on-device or nothing.
- **Minor / parked:** gallery videos (rare, and would need a gated video source plus a player),
  photo contributor names (a per-contributor profile lookup per photo), the Roboto font (no keyless
  glyph host, Noto Sans stays), and offline highway refs (a graph rebuild).

## Resilience

The signed `calibration.json` channel already hot-pushes config, field paths, user notices, and
sandboxed JS parse-logic with no app update (see SPEC section 5). Fix future breakages there first.
