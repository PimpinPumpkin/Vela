# 5. Routing

## What you see

You pick a destination and the chooser shows up to four routes, fastest first, each with an
arrival time, a distance and a line colored by congestion. You pick one and drive it with every
turn announced. Add a stop and the route goes through it. Tick "Avoid tolls" and the route
leaves the toll road. Lose signal in a downloaded area and routing keeps working.

On a drive, the line is Google's route and the turn instructions come from open data. Other
modes and situations differ, as described below. The full list of thresholds is in
[SPEC section 4](../../SPEC.md).

## Where the data comes from

- Google's keyless directions: the `/maps/preview/directions` request the Google Maps web page
  makes, with no key and no account ([chapter 7](07-talking-to-google.md)). It returns the
  route line, the typical and in-traffic times, congestion spans along the line, and
  alternates. Its step list is shortened on longer trips (a 6-mile route came back with 2 of
  about 10 turns), so its steps are used only when nothing else answers.
- The open router: OSRM on the FOSSGIS community server (`routing.openstreetmap.de`, fair use,
  no key), with a backend each for car, bike and foot. It returns every turn with street
  names, exit numbers, sign text and lanes. It knows nothing about traffic or closures.
- Valhalla on the FOSSGIS server (`valhalla1.openstreetmap.de`): map matching of a line another
  router drew, and safety-weighted bicycle routes.
- The map's own vector tiles (zoom 14), from a downloaded region's basemap file when it holds
  the tile, else from OpenFreeMap. `RoadNameTiles` reads street names and shapes from them.
- The on-phone router: OsmAnd's Java router (GPLv3, vendored) over the `.obf` region files you
  download ([chapter 2](02-data-and-rebakes.md), [chapter 8](08-offline.md)).

Transit directions are [chapter 9](09-transit.md).

## How it is decided

### Driving online: Google's line

A planning request asks Google and the open router at the same time, each up to three times
with a short jittered backoff. The line you drive is Google's top route, because Google knows
about closures and traffic and the open router does not. The steps come from open data.
`GoogleMapsDataSource.directions` runs the request and `HybridRoute` joins the pieces.

### Where the two routes share the road

`HybridRoute.stretchesFor` walks Google's line in 20 m steps and marks each point more than
`OFF_M` (15 m) from the open route. At 25 m, a parking aisle 18 m from a street counted as the
street. A marked run of at least `MIN_RUN_M` (40 m) becomes a stretch. Each stretch is widened
by `PAD_M` (90 m) and its ends are kept clear of corners, so the turn off the shared road and
the turn back onto it fall inside it.

Outside the stretches the two routes are on the same road, and the open router's steps are used
as they are, with lanes, exit numbers and sign text. A step is used only if the open route
arrives at it and leaves it along Google's line (80 m before, up to 400 m after). A step that
fails gets a stretch of its own, so no step is dropped without something covering its place.

With no stretches at all, the open router's route is the route, with Google's time and traffic
colors on it.

### Steps on a stretch

Where Google's line leaves the open route, the steps come from the first of these that works:

1. A map match. The stretch goes to Valhalla (`ValhallaRouter.matchWithEdges`), which snaps the
   line onto the road network and returns the steps of the roads it matched: street names, exit
   numbers, sign text. Valhalla also returns a confident route for a line that is on no road,
   so the answer is kept only if it stays within `MATCH_OFF_M` (22 m) of Google's line and the
   two lengths agree within 6%. The first and last 150 m of a trip are not compared, since a
   line often loops out of a parking lot. Where a kept match strays for a short way, the turns
   are read from the corners of Google's line, without names. OSRM's own matching on the public
   server takes 10 coordinates at most, so it cannot do this.
2. The map tiles. `LineNamer` in strict mode takes the turns from the bends of Google's line. A
   turn gets a street's name only when the street is within `STRICT_MAX_OFF_M` (12 m), no
   street with another name is about as close, and the line stays on it for `STRICT_RUN_M`
   (60 m).
3. The bends alone: "Turn right".

Tiles come second because, on 90 test routes, naming from the tiles alone put a wrong name on
1.4% of named turns and left about a quarter bare, and no threshold setting reached zero. With
the match first, a study against known steps gave 615 of 648 named turns the same name, 25 no
name and 1 a different name.

Valhalla returns no lane arrows. For a matched stretch, `laneDetail` asks the open router to
drive the matched path. It takes the open router's steps when the two paths agree to within
8 m, and otherwise borrows its lanes turn by turn.

The tiles are not left until the match has failed. One second into a match that has not
answered, the tiles under the stretch are read as well (0.3 s on a reroute), so the names are
already there if the match is late. A matcher in good health answers a town stretch in 0.35 to
1.2 s and costs no tile request.

All of this gets `HYBRID_WAIT_MS` (5.5 s). Past that, the route goes out on Google's line with
what each stretch has by then: its match if that finished, else names from the tiles already
read, else bare turns. One slow stretch does not cost the others their names. The open router's
own route never replaces a stretch, because it is the route through whatever Google went
around. A route that goes out past the deadline, or with any stretch bare, is marked short of
names, and the drive asks for it again ([chapter 4](04-navigation.md)).

### No wrong street names

"Turn right" sends nobody the wrong way. "Turn right onto Smith Street" at John Street does. So
a street name that cannot be confirmed is left out.

Valhalla's step text skips the short pieces inside a junction when it picks a name, and it
named one left turn after a bridge 120 m further on. So Vela asks the same server for the
matched road pieces (`trace_attributes`) and checks every turn against them (`checkedRoad`):

- A name is said only if the path is on that street within the step's first half and within
  `LEAD_MAX_M` (60 m), and stays on it for `HOLD_M` (20 m) or half the step. If the path is on
  another street for `RENAME_HOLD_M` (40 m), that name is said. Otherwise the turn has no name.
- Valhalla counts staying on a numbered route as going straight, even through a corner. Where
  the matched line turns 60 degrees or more with no step near and the street name changes
  there, Vela adds the turn.
- At a roundabout, Valhalla's enter step carries the ring's own name. Vela says the street you
  leave by.
- A turn onto the street the driver was just told is said without the name. What counts is the
  name the driver has heard, not the one on the map: a long road that takes the cross street's
  name for its last block still gets "Turn right onto X" at that corner.

If the road pieces do not come back, matched turns go out without street names. The open
router's turn names get the same check against the pieces under its own line. That request
gets `OPEN_NAMES_WAIT_MS` (1.5 s), and with no answer its names stand.

### Joining the pieces

`HybridRoute.stitch` places each stretch step on Google's line by its position, in the order
the matcher gave. Adding up step lengths put a turn 100 m early by the end of a long stretch.
A left or right from a stretch is dropped if Google's line does not bend there. Where a stretch
step and an open step describe the same junction, the open one is kept for its lanes. The
result is tagged `GOOGLE_HYBRID`.

The line drawn on the map is OpenStreetMap geometry where there is some, so it sits on the
roads the map draws. Guidance runs on Google's line.

A stretch with no matched shape would be drawn on Google's own line, which runs in the driving
lane: a meter or two to one side of the street's middle, enough to hang off a street drawn
from OpenStreetMap. `RoadCenter` nudges it over. Each point of the line, read every 6 m, moves
sideways onto the nearest road within 9 m that runs the line's way. Past 9 m, or between two
roads about equally near, the point stays where Google put it: there the map and the line
disagree about more than a lane, and Google's line is the one that knows where the route goes.
The moves are averaged over 18 m each way, so the line eases onto a road and off it. The roads
come from the tiles the naming already read, so nothing is fetched for it, and it runs once
when the route is built, never while drawing.

`StepAudit` checks every hybrid and logs one line: each left or right must sit where the line
bends that way, and each sharp corner must have a step.

If the pieces cannot be joined, the older via snap runs when Google's line strays more than
700 m from the open route (see Alternates). Failing that, the open route is driven.

### When a source does not answer

- Google does not answer: the open router's routes, with free-flow times, no traffic colors
  and no Google alternates.
- The open router does not answer: the on-phone route when a downloaded region covers the
  trip, with complete named turns and no traffic. Without a region, Google's routes with
  Google's own short step lists (`GOOGLE_ABBREVIATED`).
- Neither answers and no region covers the trip: no route.

During a drive, the recheck swaps in full steps, street names or traffic once the missing
source answers ([chapter 4](04-navigation.md)). Every route carries a `RouteSource` tag naming what produced
it, and the trip log records it.

### Alternates and the order of the list

The other rows are mostly Google's own alternates, since those account for traffic. They
arrive provisional (`GOOGLE_PROVISIONAL`): the line and the in-traffic time are Google's, and
the steps are placeholders until you pick the route. The open router's second and third routes
are offered only when its top route follows Google's course (no sampled point more than 700 m
off). Otherwise their times are uncalibrated and would sort ahead of Google's. Its top route
is never offered beside Google's line, since it is the way Google chose not to go.

Rows are sorted by the time each row shows (`durationInTrafficSeconds ?: durationSeconds`), so
the row tagged "Fastest" is the top row. On a tie, a named route goes before a provisional
one. A route whose sampled points all lie within 150 m of an earlier row is dropped, and the
list stops at `MAX_ROUTES` (4).

Picking a provisional route names it (`nameRoute`) with the via snap: the open router is
routed through 12 points sampled on Google's line. Twelve, because a point that lands on a
turn swallows the turn, and at 60 points about one named turn in ten went missing. The snapped
route is refused if a point moved more than `VIA_SNAP_MAX_M` (40 m), if it ends more than
500 m from the destination, if it is longer than Google's route by more than 5% plus 400 m, or
if it has a spur: an out-and-back with a turn on it, which a point snapped onto a side street
produces. When the snap is refused, Google's line is kept and `LineNamer` names its turns from
the tiles (`GOOGLE_LINE_NAMED`, no lanes or sign text). When that fails too, the route is
driven on Google's short steps. The route keeps the Google time the list showed, so picking it
cannot move the row.

### Stops

Google is asked for the trip through the stops: `DirectionsPb.withWaypoints` adds one waypoint
group per stop between the origin and the destination. The open router is routed through the
stops at the same time. A trip with stops gets one route, because neither router returns
alternates for one.

This path does not build the hybrid. It compares the two routes the older way:

- `RouteGeometry.stopsOnLine` checks that Google's line passes every stop, in order, within
  `STOP_ON_LINE_M` (250 m), wide because a stop is often set back in a parking lot. A reply
  that misses a stop is the direct trip, used only to compare speeds.
- Google's line stays within 700 m of the open route: the open route, with Google's time and
  traffic colors.
- Google went another way: the via snap, leg by leg, with 12 points per leg and the real stop
  between them. A stop may snap farther than 40 m. The snap is used when Google's time is
  within `SNAP_ETA_MARGIN` (1.2) of the open route's calibrated time, or when an avoid is on.
- Otherwise the open route, its speed rebased on Google's.
- The open router does not answer: each leg on the phone, joined into one route
  (`chainOnDevice`). If a leg fails, Google's own route, which has lost the stops if Google
  ignored them.

During a drive, a stop you add goes first, ahead of the stops still to come, and the drive
replans once from where you are (`NavSession.addStop`). The stops editor's Done and "Remove
next" use the same path (`setStops`). The replan skips the reroute cooldown and runs as a
planning fetch with all its retries. The new list is the plan at once, so if the fetch fails
the next reroute or recheck routes through it.

### Avoid tolls, highways and ferries

The three switches apply to driving and ride every fetch, including the reroutes, rechecks and
stop changes the drive makes itself. Avoiding cameras is [chapter 3](03-cameras.md).

Online they ride on Google's request, and Google's route is the avoiding route.
`DirectionsPb.withAvoid` sets the flags Google's own web page sends, in the request's `!6m`
feature block. The open router's own routes are not offered, because they were computed
without the avoid.

The open router cannot avoid anything. The FOSSGIS profiles were built without excludable
classes and a request carrying `exclude=` is rejected whole, so `OSRM_SUPPORTS_EXCLUDE` is
false and the parameter is never sent.

When Google does not answer and a region is downloaded, the on-phone router computes the
avoiding route from the road attributes, within `AVOID_ONDEVICE_TIMEOUT_MS` (4 s). If nothing
honored the avoid, the routes are tagged `avoidNotHonored`, and the chooser shows "may still
use tolls, highways, or ferries" when every route carries the tag.

### Times and traffic colors

| Route | Time shown | Traffic colors |
| --- | --- | --- |
| Google's line with open steps | Google's in-traffic time | Google's |
| Open route on Google's course | Google's in-traffic time, scaled by distance | Google's |
| Other open route (an alternate, stops off Google's course) | Free-flow time times calibration times traffic ratio | Where it shares Google's road |
| Google alternate | Google's time for that route | Google's for that route |
| No Google answer, on-phone route, bike route | The router's own time | None |

`applyTraffic` puts Google's numbers on an open-router route. The calibration is Google's
typical time over OSRM's free-flow time for the same course (clamped 0.5 to 3.0), and the
traffic ratio is Google's in-traffic time over its typical time (clamped 0.5 to 4.0). The
calibration exists because OSRM's speed model has no signal timing and runs well under
Google's typical time on streets with traffic lights. One is computed per reply, from the open
router's top route when it follows Google's course, and applied to every open route in the
reply. Calibrating each route separately would reorder them.

`RouteGeometry.transferSpans` moves colors onto a line with different geometry: a sample of
Google's span within 35 m of the other route colors that spot. Road Google did not drive stays
uncolored.

### Reroutes

A reroute during a drive is the same decision under a deadline: one try per source, 1.5 s for
the stretches, no lane detail, and the on-phone route taken early where a region covers the
trip. [Chapter 4](04-navigation.md) has the deadlines.

A reroute starts the way the car is pointing. The open router gets `bearings=<heading>,65` on
the first waypoint: wide enough for GPS noise and a car mid-turn, narrow enough to exclude a
U-turn. The on-phone router gets the heading as a soft preference. Google's request has no
heading field, so a Google route that sets off more than 120 degrees against the car's heading
is set aside when the open router answered. Without these, a reroute computed just past a
missed turn says "make a U-turn", and so does the next one. A planning fetch sends no heading.

Setting a route aside has a price limit (`RouteGeometry.forwardChoice`). A forward Google route
is taken only when it is within 3 minutes, or 8 percent, of the best one. With none, the open
router's own route is used when it is that close to the best route's time without traffic: it
was asked with the heading and usually goes round the block. When going on costs more than that
either way, the best route stands and the car is told to turn around. Without the limit a
forward alternate 20 minutes longer was taken over the best route.

### Walking

Walking routes come from the open foot router. Google has no traffic to add on foot and its
walking steps are shortened. `walkRoutes` also asks Google for its walk when planning and puts
it first in the list only when it is at least 15% shorter (`WALK_GOOGLE_SHORTER`), which
happens where OpenStreetMap lacks a path or a crossing. On Davis and Sacramento trips the open
walk matched or beat Google's. On a Dhaka trip Google's was 4.6 km against 5.6 km.

Google's walk keeps its own line, and `LineNamer` names its turns from the tiles. At least half
the line must be named or the route is dropped, and a road bridge's name never labels the
street under it. The line is never snapped through the foot router. Points on Google's line
land on the far sidewalk or a flyover deck, and the snapped route doubled back at each one (20
to 110% longer on Davis, Sacramento and Dhaka walks).

### Bikes

With Settings > Navigation > "Bike routes prefer bike lanes and quiet streets" on (the
default, `RoutingPrefs.bikeSafe`), a bike trip is routed for safety:

1. The on-phone bicycle profile, where a region covers the trip. It prefers signed cycle
   routes and lanes. It gets 6 s, or 3 s on a reroute.
2. Otherwise Valhalla with bicycle costing and `use_roads` at 0.1. On a Davis trip that gave
   26 steps along a cycleway corridor, against four turns down a county road at 0.9. A trip
   without stops also gets two alternates. Its steps are translated into OSRM's grammar, so
   guidance reads the same.
3. If neither answers, or with the setting off: the fastest route from the OSRM bike backend,
   with Google's bike routes as alternates.

Bike routes carry no traffic.

### Offline: the on-phone router

For driving and walking, `ObfRouteEngine` answers when the open router cannot. It returns one
route, with no alternates and no traffic.

The router is given every installed region file whose box intersects the trip's padded box
(a quarter of the trip's span, at least about 30 km), so a route can cross from one file into
the next. A region's box can contain a point its data does not. So each end of the trip must
have a road within `ENDPOINT_SNAP_M` (2 km) in those files, and the route found must start and
end that close to the trip's ends. Without this the router joins the nearest roads it has,
tens of kilometers from each end.

Memory limits the plain search. It has no precomputed shortcuts, and past `MEMORY_MB` (256) it
fails instead of slowing down. The app already runs near its heap ceiling, so the budget stays
there. Car profile on a desktop at 256 MB: 57 km in 5.65 s, 151 km fails.

OsmAnd's highway hierarchy (HH) removes that limit. The bake writes precomputed shortcuts
between the main roads into the region file: a car set, a second car set for avoiding
highways, and a bicycle set. Avoiding tolls or ferries filters the default car set. On a dense
136 MB region file at 256 MB, a 256 km drive that ran out of memory after 27 s on the plain
search took 0.5 s and 32 MB with HH.

The router falls back to the plain search when a file has no HH (a region downloaded before
the rebake) or when the trip crosses into a second file. Walking always uses the plain search,
which failed past about 28 km on that file. Where the app waits a fixed time for this router
(an avoid, a bike route, a reroute), the search is stopped when the time is up.

### Use Vela without Google

Settings > Privacy > "Use Vela without Google" makes the Google directions call return empty
before anything is sent. Every caller reads that as "Google did not answer": routes come from
the open routers alone, with free-flow times, no Google alternates and no Google fallback. An
avoid is honored only by the on-phone router in a downloaded area. "Live traffic only when I
tap", in the same section, does the same until you tap Show traffic on the route list.

### Saved routes

"Save this route" in the chooser's ⋮ menu stores the route's line, its two ends, the travel
mode and a name. A later trip with no stops, in the same mode, that starts within 1 km of the
saved start and ends within 250 m of the saved end is offered the saved route (`SavedRoutes`):

- If a route already on offer goes the same way (every 25 m sample within 60 m, in both
  directions), that row takes the saved name.
- Otherwise the router is asked for the trip through points on the saved line, one in the
  middle of each stretch of 150 m or more where it leaves the fastest route, at most 8. The
  drive keeps the points as silent stops, so a traffic re-check keeps you on your route.

Leaving a saved route lets go of it. The reroute drops the hidden points and goes the fastest
way to the destination, and Vela says so once: "You left your route. Taking the fastest way."
Real stops are kept. It used to route back to the next hidden point, which was often behind
the car. A camera detour is let go the same way, without the announcement.

A matching saved route leads the list, selected, with a "Your route" chip and a live traffic
time.

A trip with stops can be saved two ways, set by "Stop at these places" in the save dialog. On,
it is a run: the stops are real stops, it is never offered as an alternate, and you start it
from "Your routes" with every stop loaded. Off, the stops only shaped the line. A trip takes
at most 10 stops. Edit, rename, pin and delete are in Settings > Saved places.

During a drive the app keeps one fix per 15 m in memory. On arrival, if the drive was at least
500 m long and left the planned route for a real stretch, the arrival card offers "Save the
way you drove". A replayed trip never offers it.

## Limits

- The open router and the matcher are community servers with no guarantee. When OSRM does not
  answer, a drive gets the on-phone route or Google's short steps. When Valhalla does not
  answer in time, stretches fall to tile names, with no lanes or sign text, and to bare turns
  only when the tiles did not load either. That is more common on a reroute, where the
  stretches get 1.5 s. Bare turns last until the drive's next recheck that comes back named,
  about 20 seconds. Self-hosting both would fix this and would allow `exclude=`.
- The open router's turn names go out unchecked when the road pieces under its line are late.
  On the 90 test routes the check removed 5 turn names and kept 457.
- Trips with stops and picked alternates still use the via snap. On a trip with stops, Google's
  line is followed only when it strays more than 700 m, so a detour of a few blocks around a
  closure is missed. A picked alternate whose snap is refused is named from the tiles in the
  lenient mode, which can put a neighboring street's name on a turn. Building both with
  `HybridRoute` would fix it.
- With Google down or off, only a downloaded region can honor an avoid, and the on-phone
  attempt gets 4 s.
- The open router's alternates share one calibration and Google's single traffic ratio, so two
  of them cannot be ranked by live traffic.
- Long offline trips need HH: a region downloaded since the rebake, both ends in one file, by
  car or bike. Otherwise the plain search covers a metro area. A trip that leaves the installed
  regions has no offline route.
- No departure-time planning for driving, walking or cycling. The keyless request has no
  departure field, so "Depart at" and "Arrive by" only shift the arrival clock the chooser
  works out.
