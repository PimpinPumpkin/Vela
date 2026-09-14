# Architecture: what to change next, and why

Status: proposal, 2026-09-13. Nothing here is urgent enough to stop shipping. Each item is a
refactor, not a rewrite, and each is sized so it can land as one reviewable PR.

The foundations are right and stay: MapLibre for the basemap, the Google scrape with the signed
calibration channel, OSRM for turn-by-turn with Google for traffic, obf for offline routing,
Transitous for transit, Kotlin + Compose + Hilt, the `:core` / `:app` split. What follows are the
places where a month of feature waves left a shape that now costs bugs.

## 1. Route provenance is one field, not four booleans

Today a `Route` carries `provisional`, `abbreviatedSteps`, `offline` and `hasLiveTraffic`, and
every consumer has to know which combinations mean what. Two of this week's bugs were exactly
that: the nav session drove a provisional Google alternate raw, and the same route looked
healthy to the faster-route fence because it was not tagged abbreviated.

Proposal: `Route.source: RouteSource` with values `OSRM`, `OSRM_VIA_SNAP`, `GOOGLE_NAMED`,
`GOOGLE_ABBREVIATED`, `GOOGLE_PROVISIONAL`, `OBF`, `GRAPHHOPPER`, plus derived properties
(`drivable`, `hasRealSteps`) so a consumer asks one question. The four booleans become computed
from the source during a transition release, then go. The trip file already records the flags;
it would record the source name instead.

Size: one PR in `:core` plus the call sites in GoogleMapsDataSource and NavSession. Risk: low,
mechanical, fully unit-testable.

## 2. One base fetcher for the hidden WebViews

Six fetchers (`WebReviewsFetcher`, `ReviewsPanel`, `WebPhotoFetcher`, `WebDirectionsFetcher`,
`WebStopDeparturesFetcher`, `WebPopularTimesFetcher`) each own a WebView lifecycle, a desktop
UA, consent cookies, a request blocker, a watchdog, a timeout and, in one case only, console
error logging. The review scrape was dead for a week because that one case was not it.

Proposal: `HiddenWebView` in `app/web`: builds the view, seeds consent, blocks telemetry, logs
console errors under one tag, runs a script with a total deadline, reports a probe line, and
tears down under memory pressure. Each fetcher becomes its script plus its parser. The retry
ladder added to the review page this week (reload, fresh session, fail) lives there once and
every scrape gets it.

Size: one PR per fetcher after the base lands, each verifiable on the device the way the review
page was. Risk: medium; the scrapes are the product, so each conversion needs a device check.

## 3. Carve the nav out of the three god files

`MapViewModel` (6,600 lines), `VelaMapView` (6,500) and `MapScreen` (5,000) own everything. The
init-order crash, the road-features query on the main thread, the picker results that never
showed and the puck padding written from two places are all the same shape: state whose owner
is the file, not a type.

Proposal, in order of payoff:

1. `NavController` (in `:app`, wrapping `NavSession`): the nav-only state and actions now spread
   across MapViewModel (start / stop / demo / replay / reroute plumbing / corridor fetches /
   route bar / camera flags). MapViewModel keeps a reference and forwards.
2. `NavCamera` in `VelaMapView`: the follow ticker, the puck overlay, the padding and zoom
   eases, as one class with one `frame()` entry point, so the PiP and landscape cases stop being
   special branches inside a 500-line effect.
3. `SearchController`: query, suggestions, results, the three pickers and their gates
   (`resultsShown`, `pickingResults`, `searchOpen`) as one state machine with tests, instead of
   nine booleans read from five places in MapScreen.

Size: three PRs, each a week of careful moving with device checks after each. Risk: medium; the
moves are mechanical but the device is the only real test for the camera piece.

## 4. Rules in prose become rules in code

CLAUDE.md is 4,000 lines and much of it is invariants: "never gate rerouting on job liveness
alone", "the me-source has one owner while following", "the layer must be added below
vela-controls". They work when read and fail when not, and the puck jitter list is the proof:
the same cause was re-derived twice.

Proposal: a standing rule that a CLAUDE.md paragraph describing a trap comes with either a unit
test in `:core`, a lint rule, or a type that makes the wrong state unrepresentable. The
existing ones get converted opportunistically when touched; the doc keeps the why, the test
keeps the what. A `docs/INVARIANTS.md` index maps each rule to its test.

Size: ongoing; no single PR. Risk: none.

## 5. Infrastructure with an owner

The app depends on FOSSGIS OSRM, Transitous and Photon with no agreement and no fallback except
the on-device engines. The Overpass block this week was the warning shot.

Proposal: one small OSRM instance for North America and Western Europe (a single VPS with the
car/bike/foot profiles fits the budget of a hobby project), used first with FOSSGIS as the
fallback, or the obf router promoted to primary once HH lands. Either removes the one failure
that takes turn-by-turn from every user at once. The nav diag lines now record which router
answered, so the decision can be made on data from real drives.

Size: infra, not code, plus one constant. Risk: a monthly bill.

## What I would not change

The scrape-first product thesis, the calibration channel, the replay and audit harnesses, the
per-language word tables, the obf bake pipeline, Transitous for boards. These are where the
project is ahead of the comparable apps.
