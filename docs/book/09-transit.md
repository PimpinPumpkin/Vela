# 9. Transit

## What you see

Zoom in to street level and small blue bus badges appear at the stops, with their names from a
little closer in. Tap one and the sheet opens on its **departure board**: one row per route and
direction, the route's pill in the agency's own color, the next departure in bold with a
countdown, a green dot when that bus or train is being tracked live, and the next few times under
it. Every row has a **Stops** action that opens the route's whole stop list as a timeline, with
the stops the run already passed grayed out above you, live times in green or red, and canceled
calls struck through. Tap any stop in that list and you are on its board, so you can walk down a
line one stop at a time.

Ask for directions in transit mode and you get a list of itineraries: when you leave, when you
arrive, the lines you ride, the walks between them. Expand one and it draws on the map. Start it
and a bottom pane guides you leg by leg, speaking each one (which line to take, where to board,
where to get off) and moving on by itself when you reach the end of a leg.

With no signal, a stop you have tapped before still shows its board, marked with when it was last
seen.

## Where the data comes from

Two sources, deliberately split:

- **Boards, stop icons and route stop lists** come from **Transitous** (`api.transitous.org`), the
  community-run, keyless public-transit service built on the world's open GTFS and GTFS-Realtime
  feeds and served by the MOTIS engine. It is to transit what the FOSSGIS OSRM server is to road
  routing: the agencies' own published data, no account, fair-use hosting. Vela identifies itself
  with its real version string on every call, per the Transitous policy.
- **Transit directions** come from **Google**, read out of Google's own directions page loaded in
  a hidden browser view.
- **Google is also the fallback board** for stops Transitous does not cover, read the same way out
  of the stop's own Google place page.

Nothing is baked or hosted by Vela for transit. The offline copies are caches on the phone,
filled as a side effect of using the app (see [Offline copies](#offline-copies) below), not
downloads like the regions in [chapter 2](02-data-and-rebakes.md).

## How it is decided

### Why the boards are not Google's

Vela started with Google's boards and moved off them on 2026-07-13, for one reason: Google's
anonymous place page embeds as little as **one route** at a busy stop or hub. A corner where Google
gave one route showed every route from Transitous, with the agencies' official pill colors and
live countdowns. The open feeds return every route that serves a stop, carry a
realtime flag per run, and name the agency's route color, so the board is complete by
construction rather than by luck.

### Finding the board for a stop

There are three ways into a board, and they differ only in how the stop is found.

1. **A tapped stop icon** (the Transitous layer) already knows its stop id. The board is fetched
   by id with no search, no name matching and no Google lookup at all (`Transitous.boardFor`).
2. **A place sheet for a transit stop** (a search result, or a basemap stop resolved to a Google
   listing) looks the stop up by **proximity** at the place's own coordinate
   (`Transitous.board`): every stop inside a box around the point, nearest first.

   ```
   radiusM = 200.0    // Transitous.stopsNear: the box around the tapped point
   ```

3. **A hinted tap with no listing.** The basemap says the tap was on a transit stop, but Google
   resolved nothing (the Jerusalem stops in public issue #71 were the case that exposed it). The
   tap itself is enough: Transitous needs only the coordinate, so the board is fetched by
   proximity and the sheet is no longer a dead end with a name and nothing else.

In every case the board asks for the stop's **parent station** when it has one. A parent-station
id aggregates every child stop and bay under it, so a multi-bay transit center comes back as one
merged board without Vela having to know which bays exist.

```
GET /api/v1/stoptimes?stopId=<parent or stop id>&n=50
```

### What goes on the board

The raw stop times are grouped by **(route short name, headsign)**, so each route gets one row per
direction. Then, per row:

- **Canceled calls and canceled runs are dropped** from the board. (They do show in the stop list,
  below, where seeing them is the point.)
- The shown time is the **realtime departure** when the feed has one, else the timetable time.
- The **green dot** means the feed is live-tracking this run. It is the feed's own flag, not a
  guess from whether the time moved, so an on-time tracked bus is green too.
- **Duplicates collapse on the departure minute.** Two agencies can both publish the same physical
  stop, and the curb-pair merge below can feed the same run in twice; the board showed every bus
  listed twice until same line plus same minute was treated as the same bus.
  The trip ids differ between the agency copies, so they cannot be the key.
- Times print in the **stop's own time zone**, in 12-hour form, or 24-hour when the phone's clock
  setting says so.
- Rows sort by their next departure, soonest first.

The sheet shows the first 24 rows, the next departure in each with its countdown and a day marker
when it is not today, and five more times under it with an "N more" expander. The countdowns tick
every 30 seconds.

### Keeping the board fresh

While the sheet is open, a Transitous board re-queries every **30 seconds**, the same cadence as
the countdown clock, and swaps in place. The loop parks while the app is in the background
(backgrounding with a stop open used to keep polling), refreshes on the first tick after you come
back, and ends itself the moment you select something else. That is one small JSON call per
half-minute per open stop, which is what fair use of a community server looks like.

A Google-fallback board is **fetched once and never refreshed**: every refresh there would be a
whole browser page load.

### The Google fallback board

When Transitous has no board for the place (no coverage, no stop within the box, or a network
failure), a transit stop that carries a Google feature id falls back to Google. A place with no
Google listing (an open-data or OpenStreetMap stop, or any tap with Google off) still gets the
Transitous board, since that needs only the coordinate; only the Google fallbacks are skipped. The stop's own
place page is loaded in a hidden browser view (`WebStopDeparturesFetcher`, the `?cid=` page with
`hl=en&gl=us`), and the board is read out of the page's embedded state. Opening "See departure
board" on Google fires no separate request, so the board is simply part of the place payload, and
it survives a logged-out session.

The parser (`StopDeparturesParser`) handles the two **shapes** Google uses:

- **Station shape**: the stop's services grouped by line, then by direction, then the departures.
  A New York subway hub was the calibration capture.
- **Busy bus stop shape**: every upcoming departure listed flat, each tagged with its own route
  badge (the "14R" pill). The parser reads badge, headsign and times off each entry and groups by
  (route, direction), so 25 separate "route 14" entries become one "14" row.

The container path is positional (the transit node sits at `place[62]`, remotely repairable
through signed calibration), and the leaves are matched by shape: a departure is a tuple of live
epoch, time zone, clock string and timetable epoch; a headway is a `[seconds, "20 min"]` pair
whose label must agree with the seconds; a pill is a short label with a hex fill. A moved leaf
degrades one line, never the board. Realtime here means Google's live epoch differs from its
timetable epoch.

A place that resolves to Google's "Intersection" entity (a stop named for its corner) has no
board of its own. Vela searches for the stop listing near the corner and takes the nearest live
transit listing within **250 m**. A junction's own point sits back from the stops on each
approach, and a real co-located stop can sit just past 80 m from it, which is why a tighter radius
never found one.

### Canonical stops on the map

From zoom 15, the stops in view come from Transitous and draw as their own layer:

```
TRANSIT_STOPS_MIN_ZOOM = 15.0   // badges from here; names appear at zoom 17
```

The fetch covers the view padded by half its size on every side, waits **350 ms** for the map to
settle, and is not repeated until the view's center leaves the middle half of the last box. The
icons hide during a drive, and the layer has its own toggle in Settings.

These are **canonical stops**: the positions the agencies publish in their GTFS feeds, one icon
per station. Bays collapse onto their parent station (the board asks for the parent anyway).
Wherever this layer has stops, the basemap's own OpenStreetMap bus icons are filtered out, so one
stop cannot draw twice at slightly different corners. Rail and airport icons stay on the basemap.
Where the layer has nothing, the basemap icons come back.

A tap lands on a stop icon or a nearby business by **distance on screen**, not by class, so a fuel
station on a corner does not open the stop beside it or the other way round.

### Why two curbs merge into one icon

A US stop at an intersection is usually two stops: one on each curb, one per direction. GTFS names
both **identically** and carries **no direction field** (checked against the raw feed), so the map
drew two overlapping badges with the same name, and each tap showed only half the departures.

`Transitous.mergeDirectionalPairs` folds stops with the **same name** that sit close together into
one icon at the pair's midpoint, carrying the other stop ids as siblings:

```
PAIR_MERGE_M = 160.0   // same-named stops closer than this become one icon
```

The merged icon's board queries the representative and every sibling, and the (route, headsign)
grouping splits the directions back into separate rows, the way Google shows a stop. Names that
differ (a "NB Station" and "SB Station" pair) never merge, and the same name across town stays two
stops. Guessing direction from geometry was rejected: the feed has no bearings, and diagonal
streets make any guess unreliable. The proximity path does the same fold, so a place sheet at one
curb also gets both directions.

### The tap-through: a route's stop list

Tapping **Stops** on a board row fetches, in this order:

1. **The GTFS trip itself.** Every Transitous departure carries the id of the run it belongs to.
   Vela takes the first one on the row and asks for that run:

   ```
   GET /api/v1/trip?tripId=<id>
   ```

   The answer is the actual run: every stop it calls at, each with a realtime and a timetable
   time, and the feed's cancellation flags per stop and for the whole run. `buildTripStep` turns
   it into the timeline:

   - The timeline **boards at the stop nearest the one you tapped** (by distance, so it works from
     a canonical stop and from a Google listing on another corner).
   - The stops the run **already called at** go above it, grayed, and the list opens scrolled to
     your stop.
   - Tapping at the **terminus** boards at the origin instead, since an arrivals-only view has no
     ride left.
   - A stop whose time moved shows the timetable time crossed out beside the live one: **red when
     late, green when on time or early**, with a "Live" or "Scheduled" word under it.
   - A **canceled** stop, or every stop of a canceled run, is struck through and marked
     "Canceled".

2. **The itinerary fallback**, used for Google-fallback boards (their departures carry no trip id)
   and when the trip fetch fails. Vela geocodes the row's headsign near the stop, preferring a
   candidate that is itself a station or terminal (a bare "Richmond" is a San Francisco district
   and a far-off city), asks Google for transit directions there, and picks the ride leg that
   matches the tapped line: by label first (a board's "N" matches an itinerary's "N-Judah", but a
   "1" never matches a "10"), then by how close its boarding stop is to yours, within 500 m. The
   stops and times come from that leg. It is a reconstruction rather than the run itself, so it
   has no prior stops and no cancellation flags.

Tapping a stop in the timeline opens it as a transit stop, which fetches its board, which has its
own **Stops** rows.

### Transit directions stay with Google

Everything above moved to open data. Directions did not, on purpose.

**Why Google:** a Google transit itinerary's arrival time accounts for live and historical road
traffic on the bus's own route. GTFS-Realtime knows only how late a vehicle is right now. For a bus
trip across town at rush hour, that is the difference between an honest arrival time and the
timetable's.

**Why a hidden browser view:** Google serves a real transit itinerary set only to a genuine browser
engine. A plain HTTP request for the transit mode is silently answered with a **driving** reply;
the detection is on the connection fingerprint, not the headers, so no header set fixes it. A
WebView is Chromium, so Vela loads the desktop directions page anonymously, the way a logged-out
browser would, and reads the itinerary payload out of the page's embedded state.

```
https://www.google.com/maps/dir/<origin>/<destination>/data=!4m2!4m1!3e3?hl=en&gl=us

TOTAL_TIMEOUT_MS = 20_000   // one directions page, start to parsed
SETTLE_MS        =  1_800   // wait after page-finish before reading the state
```

The page fills its state a beat after it finishes loading, so the reader polls up to 12 times at
600 ms and takes the **longest** guarded string it finds (a small stub sits beside the real
payload), requiring it to be over 5,000 characters. `TransitParser` then reads it, calibrated
against a live Davis to Sacramento capture: departure and arrival, duration, distance, agency,
line badges, and a per-leg tree of boarding, intermediate and alighting stops with realtime and
timetable times, service alerts and the agency's phone number. Walk legs get their end points so
turn-by-turn walking steps can be fetched on demand from the ordinary walk router.

Four rules in that request and parse were each learned from a bug:

- **`!8j` is a local clock, not a Unix timestamp (#433).** "Depart at" and "Arrive by" add a time
  block (`!2m…!6e{0 depart, 1 arrive, 2 last available}!7e2!8j<seconds>`) before `!3e3`. Google
  reads the `!8j` seconds as the wall-clock time written as if it were UTC. Sending the true epoch
  shifted every schedule by the zone offset: riders on British summer time saw buses an hour
  early, a UTC+3 rider three hours. The western US looked right only because a seven-hour shift
  lands on a different part of the day's service, which nobody noticed. The phone's zone offset is
  now added, standing in for the origin's.
- **The `!4m` wrappers are descendant counts.** The options group grows with its entries, so the
  outer and inner `!4m` numbers are computed from them. A wrong count is not an error; Google just
  quietly answers for "now".
- **Preferred vehicles (#431)** ride in the same options group as `!5e{k}` entries, in Google's own
  numbering: `0` bus, `1` subway, `2` train, `3` tram and light rail. A bus-only rider gets the
  slower all-bus itinerary instead of the train. Changing the choice re-routes.
- **Subway bullets (#284).** New York's subway draws its lines as bullets, not text pills, so the
  line's whole identity is an agency icon such as `us-ny-mta/2.png`. The parser missed it, every
  subway leg parsed to no line, and the trip rendered as a row of empty walking steps. The line
  name now comes from the icon **filename**: an operator-scoped path (with a slash) is a line, a
  bare filename (`subway2.png`, `bus2.png`) is only the vehicle icon. The mode is read from
  filenames only, never from nearby words, because a "2" train to Flatbush Av was reported as a bus
  (Flatbush contains "bus").

When a trip's summary lists interchangeable lines as separate badges (S1, S11, S12 on one direct
ride), they merge into one slash-joined badge so a direct trip does not read as two transfers.

### On the map: the itinerary preview

Expanding an itinerary in the chooser draws it (#233):

- **Ride legs** as lines in the agency's color (a default blue when the agency sends none) running
  **stop to stop** through the boarding, intermediate and alighting stops.
- **Stops** as white dots ringed in the line color, larger at boarding and alighting.
- **Walk legs** as dotted gray links.

All of it sits below the route line and the map labels, and the camera frames the whole trip.
Collapsing a row clears the drawing only if that row still owns it.

### Step-by-step transit guidance

Starting an itinerary opens the guidance pane (#232): the bottom 48% of the screen, with the whole
trip still drawn above it and the camera framed on the leg you are on, re-framing at every step.
Each leg is spoken as it begins: a walk says how long (spelled out in English guidance, "10
minutes" rather than "10 min"); a ride says the line, its direction, where to board and where to
get off. Next and Back step by hand.

The leg **advances by itself** when your position reaches its end: the alighting stop for a ride,
the walk's destination for a walk. The advance is **latched**:

```
TRANSIT_ARM_M    = 90.0   // the leg arms only after you have been this far from its end
TRANSIT_ARRIVE_M = 40.0   // then it advances when you come this close
```

Without the arming step, standing at a transfer hub where two leg ends sit less than 40 m apart
would cascade through several legs at once, and a short final walk would announce arrival before
you set off. Every advance, automatic or by hand, disarms the new leg until you leave its end zone.
The last leg ends in "arrived".

This is not the drive's navigation from [chapter 4](04-navigation.md): there is no off-route
detection and no rerouting, only your position against the end of the current leg.

### Offline copies

Two caches, both filled by normal use:

```
TransitStopCache.MAX_AREAS    = 24     // viewport areas of canonical stops, newest kept
TransitBoardCache.MAX_ENTRIES = 48     // boards, one per stop, newest kept
TransitBoardCache.NEAR_M      = 40.0   // an offline tap matches a board fetched within this
```

- Every successful stop-layer fetch **overwrites its area** on disk, so the places you actually
  visit keep fresh canonical stops. Offline, the layer reads the cached area under the view; a
  failed fetch never blanks stops already drawn. An area never visited falls back to the basemap's
  icons. The merged curb pairs are stored merged.
- Every board fetched is kept, keyed by the stop's coordinate rounded to about 10 m. Offline, the
  sheet shows it with **"Last seen ... No connection, so times may have changed."** The routes,
  headsigns and colors are right; the times are whatever they were. A live board replaces it and
  clears the note.

## Limits

- **With "Use Vela without Google" on, transit directions are unavailable.** Every hidden browser
  page is google.com, so the switch makes each one return nothing before it loads. The directions
  request comes back empty and the chooser says "No transit routes found"; the transit time on the
  mode chips stays blank. **Departure boards still work**, because Transitous is not Google: stop
  icons, boards, the 30-second refresh, the GTFS stop lists and the offline caches are all
  unaffected. What goes with the switch is the Google fallback (stops Transitous does not cover
  show no board) and the itinerary fallback for a stop list. The planned fix is a transit route
  from Transitous' own planner (`/api/v1/plan`) when Google is off; it is listed as still wanted
  in the [roadmap](../../ROADMAP.md) under Google-off per feature, and it is **not built**. Even
  built, it would be the fallback, not the primary, for the traffic reason above.
- **Only stops you have tapped have boards offline**, and their times are old. A per-region
  timetable bake is an open question in the roadmap, priced at tens of megabytes for a mid-size
  state and a few hundred for California, with no realtime at all.
- **Transit directions do not work offline**, and there is no on-phone transit router.
- **Google boards do not refresh**, and on those boards the countdown runs from the timetable
  epoch when Google sends one.
- **The Google fallback reads an English, US-shaped page** (`hl=en&gl=us`) whatever the app
  language, because its clock parsing expects 12-hour English times.
- **The itinerary is drawn as straight chords between stops.** The keyless payload carries no
  track geometry, so a bus line crosses blocks between stops rather than following the street.
- **The itinerary fallback for a stop list is a reconstruction.** It depends on geocoding the
  headsign and on Google proposing a trip that rides the tapped line; when neither lands, the
  sheet says "Route details unavailable".
- **The transit chip time ignores preferred vehicles** when it is fetched in the background from
  another mode; the list itself honors them once transit is selected.
- **Transit guidance does not re-plan.** Miss a connection and the pane keeps guiding the trip you
  started; ask again for a new one.
- **The time zone of a scheduled trip is the phone's, not the origin's.** Planning a trip in
  another zone from home sends the wrong local clock.
