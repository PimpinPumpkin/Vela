# 3. Surveillance cameras

## What you see

Mapped license-plate readers (ALPR, of which Flock is the best known brand) draw on the map out
of the box, as a purple badge. A corner that mounts several heads draws one badge with a small
"x3" beside it, and from street zoom each head that has a known facing fans a cone out of that
badge, so you can see which ways the corner watches.

Everything else lives in **Settings > Navigation > Cameras**, and all of it is off by default:

- **Avoid surveillance cameras.** Each route in the picker shows how many cameras it passes
  ("3 cameras on this route"), the alternates pane names the route with the fewest, and Vela
  quietly moves a lower-camera route to the top when the extra time is small.
- **Try side streets around cameras**, shown only while the row above is on. When the leading
  route still passes cameras, Vela does what people do by hand: it puts a point on the street
  beside each camera, routes the trip through it, and offers the result at the top of the list if
  it passes fewer cameras for little extra time. If you drive it, the detour holds through every
  reroute, and nothing on screen or in the voice mentions the extra points.
- **Plate camera heads-up** (a card) and **Say when a plate camera is ahead** (the voice says
  "License plate camera ahead", or "cameras" for a group). Two separate switches.
- **Speed cameras** (a layer, amber badges) and, nested under it, **Warn me out loud** ("Speed
  camera ahead").

With the road-ahead bar on, cameras on your route also show up as marks on the bar. On Android
Auto the same warnings arrive as a toast on the car screen, and the car map draws the plate
cameras along the route.

## Where the data comes from

The plate camera positions are OpenStreetMap data, largely surveyed by the community
[DeFlock](https://deflock.me) project, which maps ALPR installations and pushes them to OSM as
`surveillance:type=ALPR` nodes. The whole world is about 124,000 points, small enough to keep on
the phone: a gzipped TSV of latitude, longitude, operator and facing in degrees (empty when the
node has no direction tag). Vela ships a bundled snapshot in the APK so the layer works on first
launch with no downloads, and refreshes it from the `flock-cameras` release **weekly** (Mondays
08:17 UTC, see [chapter 2](02-data-and-rebakes.md)). The loader compares versions and keeps the
newer of the bundled and downloaded copies. In the few seconds before the file finishes loading
at startup, a live Overpass query stands in.

Speed cameras are OSM `highway=speed_camera` nodes. They ride along in the per-region
`road-features` bake, rebuilt monthly (half the catalog on the 4th, half on the 6th, 07:45 UTC); the phone downloads the file for the
region you are in once and answers from memory. Only a place no region covers still asks Overpass.

No request is made to any camera service while you drive. The one thing in this chapter that
costs network requests is the side-street pass: each try is an ordinary directions request, to the
same open router and to Google, as any trip you plan. That is why it is opt-in and capped.

## How it is decided

### What counts as a camera "on your route"

A plate camera counts when it sits close to the line and faces along it:

```
FlockCameras.along(meters = 45)        // corridor either side of the route line
CameraFacing.MAX_AXIS_DIFF_DEG = 50
```

The corridor was 120 m until it badged camera-free routes with a camera on a parallel street a
block over, or on a frontage road. A roadside reader sits within a lane or two of the road it
watches, and a divided highway's far carriageway is mostly past 45 m.

The direction test takes the route segment nearest the camera and compares its bearing with the
camera's facing as lines, not arrows (a bearing and its reverse are the same axis): `d = |facing -
bearing| mod 180`, and the camera counts when `min(d, 180 - d) <= 50`. A reader pointed at the
oncoming lanes of your road is still reading your road. A camera at a junction you cross, pointed
down the cross street, does not count. A camera with no direction tag counts, since there is
nothing to rule it out.

The same rule decides the route counts, the avoid re-rank, the side-street pass, the warnings and
the route bar marks. The map layer still draws every camera.

Counts are per head. The map merges a corner into one badge, but a corner with three heads that
all see your road is three cameras on your route.

### The avoid rule

With **Avoid surveillance cameras** on, every route in the answer is counted, and then:

- the candidate is the route with the **fewest cameras**, ties broken by the faster one;
- it must beat the fastest route on camera count;
- its extra time must be within the cap below.

```
cap = min(0.25 * fastest ETA, 600 s)   // ETA in traffic where Google gave one
```

If it qualifies, that route leads the list and becomes the active one; the "Fastest" tag stays on
the genuinely fastest row, so the trade is legible rather than hidden. If nothing qualifies, the
fastest route stands and the counts are still shown, so the choice remains yours. With the
alternates pane open, the route with the fewest cameras is labeled "fewest cameras" whenever the
counts differ. The counts are stamped against the route set they were computed for, so a newer
directions request throws away a stale count instead of badging the wrong rows. Logcat tag
`VelaFlockRoute` prints `counts=[...]`.

### Side streets around cameras

The re-rank can only choose among the routes the routers offer. When the route that leads after it
still passes cameras, **Try side streets around cameras** (driving only) makes new ones:

```
CameraAlerts.group(joinM = 40)         // heads within 40 m along the route are one cluster
CameraDetour.MAX_CLUSTERS = 3          // clusters tried, nearest the start first
CameraDetour.OFFSET_M     = 150        // how far off the road each candidate point sits
CameraDetour.MAX_REQUESTS = 6          // directions requests per trip, at most
```

1. The lead route's cameras are placed along it and grouped into clusters. The first three from
   the start are kept.
2. Each cluster gets two points, 150 m to the driver's left and right of the road at the cluster.
   150 m reaches the next street over in a city block, and is near enough that a rural road with
   nothing beside it snaps straight back.
3. For each cluster in turn, the trip is re-requested through the left point, then the right. The
   point is merged into your own stops in travel order (`CameraDetour.mergePlan`; a stop is placed
   by where it falls along the lead route, within 250 m of it).
4. A result is kept when its whole line passes fewer cameras than the best so far and its time is
   inside the **same cap** as the re-rank, measured against the same fastest route. Once a
   cluster's point is kept, the right point is not tried, and the next cluster builds on the kept
   plan. The pass stops at six requests.

Nothing here knows the road network. The router's own snap does that work: a point that lands on
the same road folds back into the same route, the count does not drop, and it is rejected; a point
that lands on a parallel street is a real detour.

The compare is honest because Google now routes a trip through its stops. Every candidate is a
multi-stop trip, and Google prices it with live traffic through those points, not as the direct
trip with a speed ratio painted on (see the multi-stop section of the SPEC). A kept route goes on
top of the list with its camera badge and is selected; the original routes stay below it. Logcat
`VelaFlockRoute` prints one line per trip:
`detour: clusters=N requests=N kept=true|false cameras A -> B`.

The kept route carries its full ordered waypoint list (`Route.detourPlan`). A drive started on it
turns the detour points into **silent stops**: every reroute and traffic recheck routes through
them, so a wrong turn does not send you straight back past the cameras, but passing one is never
spoken, and the stops row, the stops editor and the leg dividers never show them. Editing the stops
mid-drive keeps the detour: the silent points still ahead are put back in route order around the
edited list. A stop added from search along the route goes first, ahead of them.

### The warnings

The card, the plate-camera voice and the speed-camera voice all fire on the same timing:

```
CameraAlerts.LEAD_SECONDS     = 12     // aim to warn twelve seconds out
CameraAlerts.MIN_LEAD_M       = 150    // floor, so a slow road still gets a useful warning
CameraAlerts.MAX_LEAD_M       = 600    // cap, so a motorway warning is not forgotten before it arrives
CameraAlerts.MOVING_FLOOR_MPS = 2.0    // below this you are parked or crawling; say nothing
```

Lead distance is `speed * 12 s`, clamped to that 150 to 600 m band. Distance alone would be
wrong: 200 m is ample in town and about two seconds on a motorway. Each alert fires once per route
and never for a camera already behind you; if two are due, the nearest wins.

Plate cameras are grouped before they are timed: heads within 40 m of each other along the route
are one alert (`CameraAlerts.group`), worded in the plural when there is more than one. The
projection runs once per driven route, from the dataset already in memory, keyed on the route's
ends and length so a same-course traffic heal does not re-arm an alert you already heard. A drive
started in the first seconds after launch waits up to 60 s for the dataset rather than going the
whole route without alerts. The card shows for 6 s.

The two plate-camera switches are independent of each other and of the map layer, because the
dataset is loaded either way. Speed cameras work differently: the spoken warning needs the speed
camera layer on, and it has no card. Its cameras come from the road-features file for a 150 m
corridor, and each one must project onto the route within 40 m; they carry no direction, so
distance is the only test. Turning either warning on mid-drive picks up the current route at once.

Every spoken camera line follows the normal spoken-directions setting, so muting directions mutes
it.

### On the map and on the bar

```
FLOCK_MIN_ZOOM        = 11     // below this nothing is fetched or drawn (plate and speed cameras)
FLOCK_CLUSTER_M       = 40     // heads merged into one badge
FLOCK_DETAIL_ZOOM     = 16     // from here: the "xN" count and the facing cones
CONTROLS_ONSCREEN_CAP = 400    // badges handed to the map, nearest the center first
```

Below street zoom the clustered badges draw from z13 while browsing and from z11 while a route is
up, so a route overview still shows its cameras. The badges never hide each other, and street
names below them move out of the way. Clustering changes only what is drawn; counts stay per head.

The road-ahead bar marks a plate camera within 40 m of the route that passes the facing rule, and a
speed camera within 40 m. When a camera and a light share a mast, the bar keeps the camera, because
it says more.

## Limits

- **Coverage is what volunteers have mapped.** An unmapped camera is invisible to Vela, and a
  camera that has been removed stays until someone edits OSM. Contributing through DeFlock or OSM
  is the only way that improves. Speed cameras are fixed installations only; mobile traps need a
  live crowd feed that the keyless model has no source for.
- **Direction tags are optional.** An untagged camera counts on every pass, which over-counts
  rather than under-counts, deliberately.
- **The re-rank is route choice, not evasion.** It picks among the routes the router already
  offers, and the 25% / 10 minute cap means a heavily covered corridor often has no acceptable
  alternative.
- **The side-street pass is greedy and narrow.** It detours only the route that leads after the
  re-rank, and only its first three clusters. The two stages can disagree: a route whose three
  cameras sit on an arterial with a parallel street beside it could detour to zero, while the
  one-camera route whose camera sits on a bridge wins the re-rank and gets tried instead. Running
  the pass over every candidate is on the roadmap; the cost is the request budget. Where no
  parallel street exists within about 150 m, every try snaps back and nothing is offered, which
  is the right answer, not a failure.
- **A detour costs requests** to a fair-use community router and to Google, up to six per trip,
  which is why it is nested and off by default.
- **The route bar reads what the map has loaded.** Its plate camera marks come from the map
  layer's current set, so with the Surveillance cameras layer off, or zoomed out past its floor,
  the bar shows no plate cameras. The card and voice alerts do not have this gap.
- **Nothing here is legal advice** and nothing here defeats a camera you drive past. The feature
  tells you where they are and prefers a road with fewer of them. Warning about speed cameras
  while driving is restricted in some countries, which is why the spoken half is its own switch.
