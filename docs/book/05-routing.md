# 5. Routing

## What you see

You pick a destination and the chooser shows up to four routes, fastest first, each with an
arrival time, a distance and a line colored by congestion. You pick one and drive it with every
turn named. Add a stop and the plan bends through it. Tick "Avoid tolls" and the route leaves the
toll road. Lose signal in a downloaded area and routing keeps working.

Behind that, one request can touch four different routers, and the answer you drive is often
stitched together from two of them: the roads and turns from an open router, the arrival time and
the red stretches from Google. This chapter is about which one answers, when, and how the pieces
are joined.

## Where the data comes from

- **The open router (primary).** OSRM, on the FOSSGIS community server
  (`routing.openstreetmap.de`, fair use, no key), one backend per mode: `routed-car`,
  `routed-bike`, `routed-foot`. It answers with full geometry and every turn, with street names,
  highway refs, exit numbers and destinations. OpenStreetMap data, ODbL.
- **Google's keyless directions (traffic, and the fallback).** The same `/maps/preview/directions`
  request the Google Maps web page makes, with no key and no account. It supplies the in-traffic
  time, the typical range ("usually 25 to 35 min"), the congestion spans along its own line, and
  its own alternates. How the request is built and kept current is [chapter 7](07-talking-to-google.md).
- **The on-phone router.** OsmAnd's pure-Java router (GPLv3, vendored) over `.obf` region files
  you download. The files are baked in CI from Geofabrik extracts and hosted as GitHub release
  assets; see [chapter 2](02-data-and-rebakes.md) for the bakes and [chapter 8](08-offline.md) for
  what a region download holds.
- **Valhalla, for bikes.** The FOSSGIS Valhalla server (`valhalla1.openstreetmap.de`), the OSRM
  servers' sibling, used only for safety-weighted bicycle routes.

Transit directions are a different story and deliberately stay with Google; that is
[chapter 9](09-transit.md).

## How it is decided

### Why the open router leads

Google's keyless answer comes back with **abbreviated steps** on longer routes: a 6-mile route
returned 2 of about 10 turns. Its line is complete, but a banner that skips eight turns is not
navigation. OSRM gives every turn. So since 2026-06-28 the rule is: **turns and geometry from the
open router, traffic from Google**, with Google as the router only when the open one cannot
answer.

A planning request (you tapping Directions) fires both at once, so the traffic round trip costs
nothing extra:

```
OSRM_TRIES = 3            // FOSSGIS blips on mobile; backoff 200 ms, 400 ms
Google tries = 3          // googleDirectionsRetried; backoff 300 ms, 600 ms
OSRM_PRECISION = 6        // polyline6: a 0.11 m grid instead of polyline's 1.11 m
```

Google got the same retry ladder on 2026-07-14. With one shot, a single empty keyless reply cost
the whole fetch its traffic, its jam avoidance and its alternates, and the picker led with
trafficless routes that read minutes faster than anything real.

### Which engine answers, in order

For a driving trip with no stops:

1. **OSRM** answers, and Google answered too: the OSRM route, with Google's traffic laid over it
   (below). If Google's course went elsewhere, OSRM is led along Google's line instead.
2. **OSRM** answers, Google did not: the OSRM route alone, trafficless. The drive's recheck heals
   it later ([chapter 4](04-navigation.md)).
3. **OSRM is down**, a downloaded region covers both ends: the on-phone route. Complete named
   turns, no traffic.
4. **OSRM is down**, no region: Google's own route, tagged `GOOGLE_ABBREVIATED` so the recheck can
   swap in full steps the moment OSRM is back.
5. Nothing: no route.

Every route carries a `RouteSource` saying which of these produced it (`OSRM`, `OSRM_VIA_SNAP`,
`GOOGLE_ABBREVIATED`, `GOOGLE_PROVISIONAL`, `OBF`, `VALHALLA`, and a few for old trip files), and
the trip log records it, so a replay says which router drew the line.

The on-phone router also takes over in two cases that are not about the network: an avoid with no
Google answer (see avoids below), and a bike trip in a downloaded area (see bikes below).

### Reroutes run on a deadline

A reroute mid-drive is **urgent**: one attempt per source, no retry ladder, and the fetch carries
the navigation session's deadline into it so no single stage can eat the whole budget.

```
URGENT_OSRM_TIMEOUT_MS   = 6_000    // the urgent reroute's one open-router call, all in
URGENT_DEFAULT_BUDGET_MS = 16_000   // an urgent fetch with no deadline passed gets this
URGENT_GOOGLE_GRACE_MS   = 2_500    // once OSRM has a route, Google gets this long, then trafficless
LADDER_OSRM_TRY_MS       = 8_000    // the escalated retry's per-try open-router timeout
LADDER_OSRM_SHARE        = 0.55     // share of its budget the open router may use
LADDER_SNAP_RESERVE_MS   = 6_000    // room kept for the traffic snap after waiting on Google
RouteBudget.MIN_TRY_MS   = 1_500    // never start an attempt with less than this left
```

When the open router comes back empty inside a bounded fetch, `RerouteFallback.pick` takes
Google's answer if it is already in, otherwise races Google against the on-phone router and takes
whichever produces a route first. Both run on unstructured scopes on purpose: the native router
and a blocking HTTP read ignore cancellation, and a structured child would hold the reroute open
until they finished, which is the wait the deadline exists to remove. (A diagnostics export showed
reroutes taking 18 to 40 seconds while OSRM had answered in seconds; this is the fix.)

An urgent reroute skips the divergence snap below, unless an avoid is on. The lean route lands in
seconds, and the two-minute recheck upgrades it.

### The heading on a reroute

After a wrong turn, a reroute computed a few tens of meters down the wrong road is entitled to say
"make a U-turn", because from there going back often is the fastest path. The driver carries on,
and is told to turn around again. So a reroute pins the **first** waypoint to the direction the
car is pointing:

```
bearings=<heading>,65       // BEARING_TOLERANCE_DEG = 65 either side, first waypoint only
```

Wide enough to absorb GPS heading noise and a car mid-turn, narrow enough to exclude a 180 degree
answer. Every later waypoint gets an empty entry (the count must match or OSRM rejects the whole
request). The on-phone router gets the same heading as OsmAnd's `initialDirection`, in compass
radians, as a soft preference. Google has no heading parameter. A planning fetch sends none,
because the way a parked car faces is not a routing constraint, and a fix with no heading sends
none either.

### Putting Google's traffic on an open route

`applyTraffic(route, google)` does three things to an OSRM route:

1. **Calibrates the free-flow time.** OSRM's speed model has no signal timing, so on a signalized
   arterial its time runs far under Google's typical for the same road (a reporter's diagnostics:
   OSRM 16 minutes where Google's typical was 30). When the route follows Google's course, its
   durations (route, legs, every maneuver, so the remaining-time sums in the drive agree) are
   rebased onto Google's typical:

   ```
   cal    = (googleTypical * distanceScale) / osrmFreeFlow,  clamped 0.5 .. 3.0
   factor = googleInTraffic / googleTypical,                 clamped 0.5 .. 4.0
   ETA    = osrmFreeFlow * cal * factor
   ```

   On a same-course route that works out to Google's own in-traffic time, scaled by the distance
   ratio. The traffic ratio stays traffic versus typical, so the color does not turn red just
   because OSRM was optimistic.
2. **Computes one calibration per response.** The basis is whichever route follows Google's
   course: the top OSRM route if it does, otherwise the via snap below. Every OSRM route in the
   reply gets that one factor, because the alternates share the speed model's bias, and
   calibrating each separately would re-rank them unfairly.
3. **Carries the congestion spans.** On a same-course route Google's spans map by fraction along
   the line. On anything else (a divergent route, an alternate, a trip with stops),
   `RouteGeometry.transferSpans` paints only where the two routes share the road:

   ```
   stepM = 25     // each span's stretch sampled every 25 m on Google's line
   tolM  = 35     // a sample within 35 m of the other route marks that spot
   gapM  = 80     // a break longer than this ends a colored run
   minM  = 40     // a run shorter than this is dropped
   ```

   The projection goes through a 0.005 degree cell grid (`SegmentGrid`), so a ten-hour route with
   tens of thousands of vertices stays cheap. Roads Google did not drive stay uncolored.

### Following Google around a jam

When Google's live route takes a different path from OSRM's free-flow one, Google is usually
routing around something. Vela then drives OSRM *through Google's line* so you get Google's path
with OSRM's named turns:

```
divergent: thresholdM = 700    // any of 5 points sampled on Google's line > 700 m from OSRM's
sampleVias: count = 12         // interior points of Google's line fed to OSRM as vias
```

Twelve, not more: a via that lands on a turn is swallowed into a via arrive/depart, and at 60 vias
about one named turn in ten went missing. Map-matching would be cleaner and is not available on
public servers (FOSSGIS `/match` caps at 10 coordinates; public Valhalla `/trace_route` times
out).

A snapped route has to pass every guard or it is thrown away:

```
VIA_SNAP_MAX_M      = 40      // any sampled via OSRM moved farther than this: refused
SNAP_REACH_M        = 500     // its last point must be this close to the destination
SNAP_LENGTH_SLACK   = 1.05    // no longer than Google's course x 1.05 ...
SNAP_LENGTH_SLACK_M = 400     // ... + 400 m
```

and the **spur test**, which catches the case the others miss: a via that snapped onto an
off-ramp or a side street moves only a few meters and adds little length, but produces an
out-and-back "appendix" the puck then drives while the car goes straight (a real drive, 121 m out
and back). `RouteGeometry.spurAt` projects the snapped route onto Google's line and looks for a
stretch that travels without progressing:

```
SPUR_MIN_M             = 80     // a stretch at least this long ...
SPUR_PROGRESS_FRACTION = 0.45   // ... that advanced less than 45% of the distance traveled
SPUR_NORMAL_FRACTION   = 0.8    // progress at or above 80% counts as normal and resets the stretch
SPUR_END_SLACK_M       = 300    // first and last 300 m exempt (approaches differ legitimately)
SPUR_TURN_NEAR_M       = 150    // refused only if a turn or U-turn sits within 150 m of the spur
```

The last rule exists because a loop ramp that OSM draws in full and Google's line cuts across has
the same shape, but carries a merge, never a turn.

Then the snap has to **earn the top slot**:

```
SNAP_ETA_MARGIN = 1.2    // Google's live ETA <= calibrated OSRM best x 1.2, or OSRM's route leads
```

Before 2026-06-30 any divergence put the snap first, and a longer, wobblier path could lead
without being faster. The comparison uses the *calibrated* OSRM time: against OSRM's raw
free-flow, a jam-avoiding snap lost to a fiction every time. With an avoid on, the gate is
skipped, because Google's avoiding course is slower than the unrestricted one by design.

### Google's alternates, named when you pick one

The picker's alternates are **Google's own**, because those are the traffic-aware choices worth
having. They arrive **provisional** (`GOOGLE_PROVISIONAL`): Google's line and Google's per-route
in-traffic time are real, but the turns are placeholders. Nothing is spent naming a route you
never drive. When you pick one, `nameRoute` snaps its line through OSRM with the same 12 vias,
checks it reaches within `SNAP_REACH_M` of the destination, and remaps the congestion spans onto
the new geometry.

It **keeps the route's original Google time**. The picker sorted and showed that figure, and
swapping in a recomputed one at the moment you pick could jump the row past its neighbor. If the
snap fails, the route is driven on Google's abbreviated steps, tagged so the drive's recheck
upgrades it when it can. The navigation session names a provisional route itself too, for the
three fetches it makes on its own (reroute, recheck, added stop), and inside a reroute's deadline
falls back to the reply's own full-stepped OSRM route if naming runs long.

### The order in the picker

```
sort key   = durationInTrafficSeconds ?: durationSeconds
tie-break  = provisional last (a fully named route leads over a look-alike)
dedupe     = 4 points along a route all within 150 m of an earlier one: same route, dropped
MAX_ROUTES = 4
```

The sort key is **exactly the figure the chooser prints**. An earlier attempt sorted on an
adjusted value, and the route tagged "Fastest" was not the one on top. The axis is fair without
any fudge: OSRM routes carry the calibrated time times Google's traffic ratio, Google's alternates
carry their own. A route with genuinely no traffic signal sorts and shows its free-flow time, which
is at least consistent.

### Is the arrival time still Google's when the route is not?

Mostly, and where it is not, it is built from Google's numbers:

| The route you drive | Its arrival time |
| --- | --- |
| OSRM, same course as Google | Google's in-traffic time, scaled by distance |
| OSRM snapped along Google's line | Google's in-traffic time, scaled by distance (the snap is the calibration basis) |
| OSRM alternate in the same reply | OSRM x the reply's one calibration x Google's traffic ratio |
| OSRM top that left Google's course, no usable snap | OSRM free-flow x Google's traffic ratio, uncalibrated |
| A Google alternate, named on pick | Google's own per-route in-traffic time, kept through the naming |
| Google's route while OSRM is down | Google's own |
| Trip with stops, on (or snapped to) Google's course through them | Google's through-the-stops time |
| Trip with stops, Google's detour through them not worth it | OSRM x a speed ratio x Google's traffic ratio |
| Trip with stops, Google ignored them | OSRM x a speed ratio x Google's traffic ratio |
| On-phone, Valhalla bike, or Google turned off | The router's own time, no traffic |

Once you are driving, the two-minute recheck keeps the number honest from a same-course candidate
([chapter 4](04-navigation.md)).

### Stops

Since 2026-09-21 Google is **asked for the trip through the stops**. Before that, Vela only ever
asked Google for the direct trip and used it to calibrate a speed, so every trip with stops was
the open router's free-flow choice with a ratio on it.

`DirectionsPb.withWaypoints` adds one top-level waypoint group per stop between the origin and
destination groups, the same repeated field the origin and destination already are, so no group
count has to change. Checked live from a plain client: Davis to Sacramento direct answered 15.3 mi
/ 21 min with three alternates; through Woodland it answered one route, 45 min, with per-leg
distances. **A trip with stops gets one route.** Neither router offers alternates for one.

The open router is routed through the stops in parallel (`routeVia`), and then:

- **The guard.** `RouteGeometry.stopsOnLine` checks that Google's line actually passes every stop,
  in order, within `STOP_ON_LINE_M = 250` of a vertex. A stop is often set back from the road (a
  parking lot, a driveway), so the tolerance is generous; the direct trip misses by kilometers.
  A reply that misses a stop is treated as the direct trip it is (a recalibrated template without
  the placeholder groups would produce exactly that), and the diagnostics line reads
  `googleStops=IGNORED`. That case falls back to comparing average speeds (`speedCal`, clamped
  0.5 to 3.0), so the extra distance of the stops cancels out.
- **Same course**: the open route, with Google's real time and spans.
- **Google took another course**: the open router is snapped along Google's line leg by leg
  (`sampleViasThrough`: 12 samples per leg with the real stop between them). The stops are exempt
  from the 40 m via refusal, because a stop in a lot is a stop, not an appendix. Same reach,
  length, spur and ETA-margin rules as a single trip.
- **Open router down**: each leg on the phone, stitched into one route (`chainOnDevice`; any leg
  that fails means no offline route), and only then Google, which if it ignored the stops loses
  them (the diagnostics line says `STOPS DROPPED`).

**Adding a stop mid-drive** (`NavSession.addStop`) puts the new stop *first*, ahead of the stops
still to come, and replans once from where you are. It is user-ordered, so it skips the reroute
cooldown and the "back on course, never mind" discard, and it cancels any deviation reroute in
flight. The stop joins the plan immediately: if the fetch fails, the list is kept and the next
reroute or recheck routes through it. The replan is a planning fetch, not an urgent one, so it
gets the full retry ladder and the divergence snap. When it lands, the ETA calibration from the
old route is reset, because the fresh route carries fresh traffic. The stops editor's Done goes
through the same path (`setStops`), and an unchanged list fetches nothing.

What it does to the plan: it becomes one route through every remaining stop. Passed stops are
dropped from every later reroute and recheck (`stops.drop(passedStops)`), and each stop gets an
along-route mark so its cue is spoken once, in order (`STOP_ON_ROUTE_M = 150`: a stop farther than
that from the new line gets no cue on it). The camera detour's silent side-street points
([chapter 3](03-cameras.md)) are put back into an edited list where they fall along the route, so
editing stops does not throw the detour away. The tap-a-place-to-add-it card, and how it prices
the detour before you commit, belongs to the drive's chrome.

### Avoid tolls, highways and ferries

The three chips are a driving option and ride every fetch, including the reroutes, rechecks,
added stops and naming the drive does on its own. What each engine can honor:

| Engine | Tolls | Highways | Ferries | How |
| --- | --- | --- | --- | --- |
| Google keyless | yes | yes | yes | flags in the pb's `!6m` feature block |
| FOSSGIS OSRM | no | no | no | `exclude=` rejected for every value |
| OSRM snapped to Google | follows Google | follows Google | follows Google | the vias force Google's avoiding course |
| On-phone obf | yes | yes | yes | routing.xml parameters at calc time |
| Valhalla (bike) | not sent | not sent | not sent | driving option only |

**Google.** The flags were found by capturing Google's own web client with the boxes ticked (the
July note that Google had no keyless avoid was wrong). Inside the `!6m` block's `!2m` submessage,
`!1b1` avoids highways and `!2b1` avoids tolls; `!7b1` avoids ferries as a direct child of the
outer `!6m`. `DirectionsPb.withAvoid` places them by pattern and fixes up the group counts, so a
recalibrated template keeps working as long as the block survives (`avoidSupported`). Checked live:
Davis to Sacramento with highways avoided goes from 15.3 mi / 21 min on the interstate to 27.1 mi
/ 46 min on county roads; Galveston to Crystal Beach with ferries avoided goes from a 16.7 mi
ferry route to a 116 mi road route.

**OSRM cannot exclude.** The public FOSSGIS profiles were built without excludable classes, and
the server answers `InvalidValue` (probed 2026-07-11 and again 2026-08-24). Sending the parameter
400s the whole request, so `OSRM_SUPPORTS_EXCLUDE = false` keeps it off; flip it for a self-hosted
OSRM built with the classes.

So online, **Google's route is the avoiding route**. OSRM's plain route diverges from it, the snap
follows Google's course with named turns, the ETA-margin gate is skipped, and OSRM's unrestricted
routes are not offered beside it. If the snap fails, Google's own abbreviated route wins over a
plain one that ignores the avoid.

**The on-phone router is the avoid router only when Google did not answer**, with
`avoid_toll`, `avoid_motorway` and `avoid_ferries` passed at calc time (no baked profiles). The
car profile's highway parameter is `avoid_motorway`; `avoid_highway` in OsmAnd's routing.xml is
the horse-riding profile's. The attempt is bounded:

```
AVOID_ONDEVICE_TIMEOUT_MS = 4_000
```

Past that, the online chain answers and the result is tagged `avoidNotHonored`. The chooser shows
the "may still use tolls, highways, or ferries" note only when **every** route carries that tag,
so a toggled avoid is never ignored silently. Today it shows mainly on a trip with stops whose
open-router route could not be led along Google's avoiding course.

### Bikes route for safety

With **Settings > Navigation > Bike routes prefer bike lanes and quiet streets** on (the default,
`RoutingPrefs.bikeSafe`), a bike trip skips the fastest-route chain above:

1. The on-phone **obf bicycle profile** where a region covers the trip. It prefers signed cycle
   routes and lanes and needs no network. Bounded like the avoid path:

   ```
   BIKE_ONDEVICE_TIMEOUT_MS = 6_000
   BIKE_ONDEVICE_URGENT_MS  = 3_000
   ```

2. Otherwise **Valhalla** with bicycle costing:

   ```
   USE_ROADS    = 0.1       // near zero: hunt for cycleways, lanes, quiet streets
   USE_HILLS    = 0.5       // so a flat detour is not taken to absurd lengths
   BICYCLE_TYPE = "Hybrid"
   alternates   = 2         // plain trip only; stops are sent as "through" points
   ```

   Probed on the Davis fixture: the same trip came back as 26 maneuvers along a cycleway corridor
   at 0.1 and as four turns down a county road at 0.9. Valhalla's maneuvers are translated into
   the OSRM grammar, so the banner, voice and step list read exactly as they do for any route.
3. If neither answers, the plain OSRM bike route.

No Google traffic on bike routes: bikes do not sit in car traffic, and Google's bike time models a
different route. Turn the setting off to get OSRM's fastest bike route.

### The on-phone router

`ObfRouteEngine` routes over every installed region file that intersects the trip's padded box,
so a route can cross from one region file into the next:

```
pad = max(0.27 degrees, a quarter of the trip's span)   // about 30 km minimum
```

The installed files together must cover both endpoints, or the trip is out of the data and goes
online. One route at a time (the routing context is single-use and serialized on one lock), one
route per answer, no alternates, no traffic.

Its weakness is **long routes**. The OsmAnd router computes dynamically, with no precomputed
shortcuts, and past its memory budget it throws rather than slowing down:

```
MEMORY_MB        = 256    // the app already runs near its largeHeap ceiling
NATIVE_MEMORY_MB = 64
```

Measured on a desktop against a baked Bavaria file with the shipped configuration, car profile:
4 km in 0.87 s; 57 km in 5.65 s; 151 km fails at 256 MB (4.6 s given 1024 MB); 348 km fails even
at 1024 MB (41.9 s given 3072 MB). The threshold depends on how dense the road network is, not on
a fixed distance. The fix is OsmAnd's precomputed hierarchy (HH), which the bake does not generate
yet. Until it does, offline routing is a city and metro feature, and intercity trips need a
signal.

### GraphHopper, retired

The first offline engine was GraphHopper over per-region contraction-hierarchy graphs, from
2026-06-30 to 2026-09-15. It was fast (a 24-mile route in 188 ms) but heavy: the same data as an
obf routing section measured about 4 times smaller. It was retired once the obf carried everything
it did (routes, the speed-limit badge, romanized road names). The first launch after that update
deletes the old graphs and asks you to download regions again. `RouteSource.GRAPHHOPPER` survives
only so old trip files read back. Retiring it also removed the only offline CH fallback, which is
why the long-route limit above matters.

### Without Google

**Settings > Privacy > Use Vela without Google** makes Google's directions call return empty
before it is sent. Every caller already reads that as "Google did not answer", so routing becomes
the open router alone: no traffic, no Google alternates, no Google fallback, and the avoid chips
honored only on the phone in a downloaded area. The arrival times are OSRM's free-flow, which runs
optimistic on signalized roads.

## Limits

- **The open router is a community server.** FOSSGIS is fair use with no guarantee. When it hangs,
  a drive gets Google's abbreviated steps or the on-phone route, and a planning fetch can wait out
  three tries first. Self-hosting OSRM would fix this and would also allow `exclude=`.
- **Online avoid depends on Google.** With Google down or turned off, only a downloaded region can
  honor an avoid, and the on-phone attempt gets 4 seconds.
- **With an avoid on, OSRM's own alternates can still appear** when the top OSRM route already
  follows Google's avoiding course (no toll on the main line anyway). They were computed without
  the avoid, and nothing tags them.
- **The alternate ETAs are one ratio.** OSRM's alternates all share Google's single traffic
  ratio and one calibration, so the picker cannot rank two OSRM alternates by live traffic; only
  Google's own alternates carry per-route traffic.
- **A divergent top with no snap is uncalibrated.** An urgent reroute skips the snap, so if the
  OSRM route leaves Google's course, its time is OSRM's free-flow times Google's ratio until the
  recheck corrects it.
- **Stops mean one route.** Neither router returns alternates for a trip with stops, so there is
  no choice to make in the picker.
- **A start on an on-ramp can snap wrong.** OSRM snaps a start point on a ramp to the surface
  street under it, heading hint or not, so a recheck fetched from a ramp can route the first
  stretch over local streets. Google snaps it correctly, which is why its alternate can lead then.
- **Offline is metro-scale** until the bake generates HH, and a trip that leaves the installed
  regions has no offline route at all.
- **No departure-time planning.** The keyless request cannot ask for a future departure; the
  "usually X to Y" range is the stand-in.
