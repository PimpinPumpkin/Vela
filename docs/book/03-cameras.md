# 3. Surveillance cameras

## What you see

License-plate readers (ALPR; Flock is the best-known brand) draw as purple badges, on by default.
A corner with several heads draws one badge, and from zoom 16 each head with a known facing adds
a cone showing which way it looks.

Settings > Navigation > Cameras has the switches. All but the first are off by default.

- Surveillance cameras: the plate camera layer.
- Speed cameras: amber badges for fixed speed cameras. Under it, Warn me out loud says "Speed
  camera ahead".
- Avoid surveillance cameras: each route shows its count ("3 cameras on this route"), and one
  that passes fewer leads when the extra time is small. Under it, Try side streets around
  cameras builds routes on the streets beside the cameras.
- Plate camera heads-up shows a card. Say when a plate camera is ahead says "License plate
  camera ahead", or "cameras" for a group.

A route that leads for its count says "Passes 2 fewer cameras than the fastest route", and the
alternates pane tags the route with the fewest. The Cameras chip in the route pickers' Avoid row
is the Avoid surveillance cameras switch. Flipping it fetches the routes again.

During a drive the road-ahead bar marks the route's cameras ([chapter 11](11-drive-chrome.md)).
On Android Auto each warning is also a toast, and the car map draws the route's plate cameras
while the layer is on ([chapter 10](10-android-auto.md)).

## Where the data comes from

Plate cameras are OpenStreetMap nodes tagged `surveillance:type=ALPR`, most of them surveyed by
the [DeFlock](https://deflock.me) project. `scripts/build-flock-cameras.py` bakes the world into
one gzipped TSV of latitude, longitude, operator and facing in degrees. The APK bundles a July
2026 snapshot of about 129,000 cameras, 93% with a facing, so the layer works on first launch.

The `flock-cameras` workflow rebakes the file every Monday at 08:17 UTC
([chapter 2](02-data-and-rebakes.md)). At launch `FlockCameras` loads the newer of the bundled
and downloaded copies, then downloads the hosted one if its version is higher. Until the file
has loaded, Overpass answers (`OverpassAlprCameras`), with a 120 m corridor for route counts.

Speed cameras are OSM `highway=speed_camera` nodes in the per-region `road-features` file,
rebaked every 30 days. The phone downloads its region's file once and answers from memory
(`RoadFeatures`). Overpass is asked only where no region exists.

Plate camera counts and alerts make no request. Each try of the side-street pass is a full
directions request to the open router and to Google, so it is off by default and capped at six
per trip.

## How it is decided

### On your route

A plate camera is on a route when it is within 45 m of the line (`FlockCameras.along`) and faces
along it. A 120 m corridor counted cameras on the parallel street a block over. A roadside
reader sits within a lane or two of its road, and the far side of a divided highway is mostly
beyond 45 m.

`CameraFacing` compares the facing with the bearing of the nearest route segment as undirected
lines, and the camera counts when they differ by at most `MAX_AXIS_DIFF_DEG` (50). A reader
aimed at the oncoming lanes still reads your road. One aimed down a cross street does not. A
camera with no facing counts.

Counts are per head, so a corner with three heads that see the road is three cameras. The same
test feeds the avoid rule, the side-street pass, the warnings and the bar. The browse map draws
every camera.

### The avoid rule

With Avoid surveillance cameras on, `refreshFlockOnRoute` counts every route, and
`CameraDetour.choose` takes the one with the fewest cameras, ties to the faster. It leads only
if it passes fewer cameras than the fastest route and costs at most
`min(25% of the fastest ETA, 600 s)` more, on traffic ETAs where they exist.

The winner moves to the top and is selected, and the "Fastest" tag stays on the fastest row.
When nothing qualifies, the order stands and the counts still show. Counts carry the epoch of
their route set, so a newer directions request discards them.

### Side streets around cameras

The avoid rule only chooses among routes the routers offer. Try side streets around cameras
(driving only) makes new ones in `tryCameraDetour`:

1. Each route that passes cameras, in list order, has its cameras grouped into clusters of heads
   within 40 m along it. The first `MAX_CLUSTERS` (3) are used, skipping any within
   `SAME_CLUSTER_M` (60 m) of one tried on an earlier route.
2. Each cluster gets two points, `OFFSET_M` (150 m) left and right of the road. That reaches the
   next street in a city block, and on a rural road with nothing beside it the point snaps
   straight back.
3. The trip is requested through the left point, then the right if the left did not help, merged
   into your stops in travel order. A result is kept when it passes fewer cameras inside the
   avoid rule's cap, and the next cluster builds on it.
4. Requests stop at `MAX_REQUESTS_PER_ROUTE` (4) per route and `MAX_REQUESTS` (6) per trip.
5. The leader and each route's best result go through `CameraDetour.choose`, and the winner goes
   to the top.

The code has no road network and relies on the router's snap. A point that lands on the same
road returns the same route and fails on its count. Each try is a multi-stop trip, so its time
includes live traffic through the point. Logcat tag `VelaFlockRoute` prints the counts and each
try.

The kept route carries its waypoints (`Route.detourPlan`), and a drive started on it holds them
as silent stops ([chapter 4](04-navigation.md)). Reroutes and traffic rechecks route through
them, so a wrong turn does not lead back past the cameras. They are never spoken or listed, and
a stops edit keeps them.

### The warnings

The card and both voices share `CameraAlerts.due`. The lead distance is speed times
`LEAD_SECONDS` (12), clamped to `MIN_LEAD_M` (150) and `MAX_LEAD_M` (600), because 200 m is
ample in town and two seconds on a freeway. Nothing fires below `MOVING_FLOOR_MPS` (2.0). An
alert fires once per route, never for a camera behind you, and the nearest wins when two are
due.

Plate cameras are projected onto each driven route once, from memory, and heads within 40 m
along it become one alert in the plural. The projection is keyed on the route's ends and length,
so a traffic refresh of the same course does not repeat an alert. A drive started right after
launch waits up to 60 s for the dataset. The card shows for 6 s. Both switches work with the map
layer off.

The speed camera warning needs the Speed cameras layer on and has no card. Its cameras come from
a 150 m corridor of the road-features file and must project onto the route within 40 m. They
carry no direction.

A warning turned on mid-drive applies to the current route at once. Spoken lines follow the
spoken-directions setting. A recorded-trip replay raises no camera alerts.

### On the map and the bar

Nothing is fetched or drawn below zoom 11 (`FLOCK_MIN_ZOOM`). Badges appear from zoom 13 while
browsing and from zoom 11 with a route up, so a route overview shows its cameras. Heads within
`FLOCK_CLUSTER_M` (40 m) share a badge. Badges always draw, and street names move aside. A badge
within 25 m of a drawn light or stop sign shifts up and right so both show. The map takes at
most `CONTROLS_ONSCREEN_CAP` (400) heads, nearest the center first.

With the route chooser open or a drive running, the layer shows only the cameras on the shown
routes. That set is computed once per set of routes. Recomputing it as the view moved kept a
processor core busy for the whole drive.

The road-ahead bar marks a plate camera within 40 m that passes the facing test, and a speed
camera within 40 m. [SPEC section 4.9](../../SPEC.md) has the remaining thresholds.

## Limits

- Coverage is what volunteers have mapped. A removed camera stays until someone edits OSM. Speed
  cameras are fixed installations only, since no keyless source has mobile traps.
- A camera with no direction tag counts on every pass, which over-counts. A node tagged with
  several directions keeps only the first (`norm_direction` in the bake script).
- Neither Google's directions nor OSRM accepts an area to avoid, so the avoid rule picks among
  offered routes, and a heavily covered corridor often has none inside the cap. To steer by
  hand, long-press the map while planning to route through that point.
- The side-street pass is greedy and shares six requests across routes, so with three routes
  that pass cameras the later ones may get one cluster or none. Where no parallel street lies
  within about 150 m, nothing is offered.
- The road-ahead bar reads the map layers. With a layer off it has no marks of that kind, and
  speed camera marks come from the padded view box, so one farther along the route is missing
  until the view reaches it. The alerts use per-route sets, and giving the bar those would close
  the gap.
- Warning about speed cameras while driving is restricted in some countries, so the spoken
  warning is its own switch.
