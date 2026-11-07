# 9. Transit

## What you see

From street zoom the map shows a blue badge at each transit stop: round with a bus at a bus stop,
a larger rounded square with a train or a subway car at a station. Tap one and the sheet opens on
its departure board: one row per route and direction, with the route's pill in the agency's
color, the next departure and a countdown (green when the vehicle is tracked live), and the
times after it. Tap Stops on a row for the run's stop list: stops already passed are gray, live
times are green or red, canceled calls are struck through. Tap a stop there to open its board.

Directions in transit mode list trips with their times, lines and walks. A trip's card shows
its first four lines and a count of the rest. Chips set the time
(Leave now, Depart at, Arrive by, Last available), the vehicles to prefer, and fewer transfers
or less walking. A picked time is kept while the sheet is swiped down and back, until its time
has passed or another destination is chosen. Expanding a trip draws it on the map, and it stays
expanded while the sheet is swiped down and back. Starting it opens a pane that guides you
leg by leg, speaks each one and moves on when you reach a leg's end; the last card's button
reads Done and closes the pane.

"Highlight transit lines" draws rail lines in their own colors. With no connection, a stop whose
board you opened before still shows it, marked with when it was last seen.

With "Bike share" on in Layers, each docked bike-share station shows from street zoom as a teal
badge with its free bikes beside it, gray when it has none. Tap one for its free docks and how
old the counts are. They refresh every minute while Vela is on screen.

With the "Bike share" chip on in the Bike tab, a trip that leaves now also gets bike-share
trips: a docked bike taken from one station and returned to another, alone or before or after a
ride. Up to two show above the bike routes under their own heading, with the system's pill in
teal and the free bikes at the station where the bike is taken.

## Where the data comes from

| Piece | First | Fallback |
| --- | --- | --- |
| Departure boards | Transitous | Google's page for the stop |
| Stop icons | Transitous | the basemap's OpenStreetMap icons |
| Stop list of a run | Transitous | a matching ride from transit directions |
| Transit directions | Google's directions page | the Transitous planner |
| Path of a ride on the map | the Transitous planner | a road route through the stops (bus), straight lines (rail) |
| Colored rail lines | Transitous | the basemap's plain rail highlight |
| Bike-share stations | Transitous, from the operators' GBFS feeds | none |
| Bike-share trips | the Transitous planner, on the same feeds | none |

Transitous (`api.transitous.org`) is a community-run service with no key or account. It serves
the GTFS and GTFS-Realtime feeds that agencies publish, through the MOTIS engine. Every call
carries Vela's real version string (`VelaConfig.VELA_UA`) and the app language, so stop names
come back in that language where a feed has them.

The Google pieces are read from Google's pages in a hidden WebView
([chapter 7](07-talking-to-google.md)). "Use Vela without Google" skips them.

Vela bakes and hosts nothing for transit. The offline copies are caches filled by normal use
([Offline copies](#offline-copies)), unlike the region downloads in
[chapter 2](02-data-and-rebakes.md).

## How it is decided

### Why the sources are split

Google's anonymous place page embeds as little as one route at a busy stop. The open feeds return
every route at a stop, with a realtime flag per run and the agency's color, so boards come from
Transitous first.

Directions go the other way because Google's arrival times account for live and typical road
traffic on the bus's route. GTFS-Realtime knows only how late a vehicle is now.

### Finding the board for a stop

A tapped stop icon knows its stop id (`Transitous.boardFor`). A place sheet gets a board when its
category reads as a transit stop (`isTransitCategory`: word lists per language that calibration
can replace, with exclusions so a gas station is not a station, and a compiled list of bike words
so a bike-share station is not one either). `Transitous.board` then takes
the nearest of the stops within 200 m of the place. A tapped basemap stop with no Google listing
gets its board the same way.

The request names the stop's parent station when it has one. A parent id covers every bay, so a
transit center comes back as one board.

### What goes on the board

`Transitous.buildBoard` groups departures by route short name and headsign, one row per
direction, sorted by next departure.

- Canceled calls and runs are left off.
- The time is the realtime departure when the feed has one, else the timetable time, in the
  stop's time zone and the app's 12 or 24 hour clock.
- Green is the feed's own realtime flag, so a tracked vehicle that is on time is green too.
- Two departures on one row at the same time are one vehicle. A stop can arrive through two
  agencies' feeds or a merged curb pair, and the trip ids differ between the copies.

The sheet shows up to 24 rows, each with five more times and an expander. While it is open the
board is fetched again every 30 seconds. The loop waits while the app is in the background and
ends when you select something else.

### The Google fallback board

When Transitous has no board, a stop with a Google listing falls back to Google.
`WebStopDeparturesFetcher` loads the stop's place page (`?cid=`, `hl=en&gl=us`) and
`StopDeparturesParser` reads the board from the page's embedded state. It is fetched once,
because a refresh would be a page load.

The parser handles a station (line, direction, departures) and a busy bus stop (every departure
flat, each with its own route badge). The transit node is looked for at `place[62]`, which
calibration can move, and then by shape. Its parts are matched by shape, so one that moves costs
a single line.

A stop named for its corner can resolve to Google's "Intersection" entity, which has no board.
Vela then takes the nearest open transit listing within 250 m, because a junction's point sits
back from the stops on its approaches.

### Stops on the map

Stops are fetched from zoom 15 (`TRANSIT_STOPS_MIN_ZOOM`), drawn from 16 and named from 17, one
icon per station with bays collapsed onto their parent. The fetch covers the view padded by half
on each side and repeats only when the view's center leaves the middle half of that box. A
request that is out is left to land before the next view is asked (`ViewFetch`, as for bike-share
stations below).

While the layer has stops, the basemap's own bus icons are filtered out so a stop cannot draw
twice at slightly different corners. Rail and airport icons stay on the basemap.

The icons hide during a drive. Settings has a "Transit stops" switch with three under it, for bus
stops, subway and tram stops, and train stations (`TransitLayer.showsStop`). A tap goes to the
stop or the business beside it, whichever is nearer on screen.

### Bike-share stations

Transitous reads the GBFS feeds that bike-share operators publish, and its `rentals` call answers
every rental station in a box. Vela keeps the bike stations from that answer; car-share and
scooter stations come in it too. So do the parking spots of free-floating bike and scooter
operators, which publish them as stations. They are left out by the operator's name (Dott,
Voi, Lime and the like head their provider group, where a public system's group is named after
the system), by a spot that also takes scooters and counts no docks, and by a "station" with
more bikes than any station holds. Without the names, central Paris drew one operator's
parking spots ten to one against Vélib's stations. The layer is
off by default because it is a request of its own, once a minute while it is on.

The fetch follows the stops' rules: from zoom 15, the view padded by half on each side, again only
when the view leaves that box. On top of that the box around the view is asked again every 60 s
while the app is on screen, and not at all during a drive, with "Use less data" or offline. A
request that is out is left to land, and only then is a view it no longer covers asked for:
while the map follows a moving fix the view comes once a second, sooner than a busy city answers,
and a request canceled for each new view never landed. In central Lyon
one answer at zoom 15 on a phone is 13.7 KB, with 58 bike stations among 218 rental stations.
The box asks for docked systems' stations only, named by a small providers-only lookup kept for
ten minutes: central Paris is 176 stations in 9.2 KB that way, against 4,251 in 241 KB with every
operator's.

Free docks need care. A feed with several bike models can give one free-dock count and have it
repeated under each model, so the station's count is the largest of them, not their sum. Where a
feed gives no dock count (Lyon's does not), the sheet shows bikes only.

A tapped station opens a light sheet with its counts and when they were fetched. The minute
refresh updates an open sheet while its station is in the fetched box. The counts are never
saved with a place. [SPEC 8](../../SPEC.md#8-transit), Bike-share stations, has the rules.

### Merging stops

A US stop at an intersection is usually two stops, one per curb. GTFS gives both the same name
and no direction, so unmerged they draw as two overlapping badges with half the departures each.

`Transitous.mergeDirectionalPairs` folds same-named stops within 160 m (`PAIR_MERGE_M`) into one
icon at their midpoint. Names are compared by `stopKey`, which ignores case, ordinals,
abbreviations and the order of the cross streets. Stops within 3 m (`COLOCATED_M`) fold first
whatever their names, because one corner can appear in several feeds. Around Bryant Park in New
York this took 78 icons down to 55.

The merged board asks for every id, and the headsigns put the two directions on separate rows.
Names that differ ("NB Station", "SB Station") never merge. Direction is never guessed from
geometry, because the feed has no bearings.

### The stop list of a run

Each Transitous departure carries its run's id. Tapping Stops fetches that run (`/api/v1/trip`):
every stop it calls at, with realtime and timetable times and canceled flags.
`Transitous.buildTripStep` boards the timeline at the stop nearest the one you tapped and puts
the stops already called at above it. A tap at the terminus boards at the origin, since no ride
is left.

Some agencies publish one trip for a whole day of laps (one Davis Unitrans trip in September 2026
had 589 stops). Every stop within 30 m (`LAP_SAME_STOP_M`) of the nearest counts as a pass, and
the timeline shows the one lap whose pass is nearest in time to the tapped departure.

Google's boards carry no run ids. For those, and when the trip request fails, Vela geocodes the
headsign near the stop, asks for transit directions there, and takes the ride on the tapped line
that boards nearest. The label must equal the line's name or its first token, so "1" does not
match "10". This list has no earlier stops and no canceled flags.

### Transit directions

`MapViewModel.transitTrips` asks Google first, and the planner (`Transitous.plan`) when Google is
off or returns nothing.

Google serves transit itineraries only to a real browser engine. A plain HTTP request in transit
mode gets a driving route back, measured from OkHttp and curl. `WebDirectionsFetcher` loads the
desktop directions page logged out and reads the payload from the page's embedded state, taking
the longest candidate because a short stub sits beside it.

```
https://www.google.com/maps/dir/<origin>/<destination>/data=!4m2!4m1!3e3?hl=<language>&gl=us
```

- The page is asked for in the app's language, so names read as a local rider expects. The
  tuning dial `transitAppLanguage` at 0 returns every phone to English.
- Options go in one group before `!3e3`, in order: `!4e2` fewer transfers or `!4e3` less walking,
  `!5e{k}` per preferred vehicle (0 bus, 1 subway, 2 train, 3 tram), then the time block ending
  in `!8j<seconds>`. The `!4m` wrappers count their contents. Given a wrong count, Google answers
  for "now" with no error.
- `!8j` is the local wall-clock time written as if it were UTC, so the phone's zone offset is
  added. The true Unix time shifts every schedule by that offset.

`TransitParser` reads the payload:

- Times are formatted from each tuple's epoch and zone. The clock text beside them changes with
  the page language.
- A line is its text pill, or the file name of an operator's icon when there is no pill
  (`us-ny-mta/2.png` is the 2). An operator's path has a slash. A bare file name such as
  `bus2.png` is the vehicle icon.
- A pill can have no color. The trip summary lists some lines that way, and the trip card then
  draws them in the colors their ride leg gives.
- The vehicle type comes from icon file names only, because a stop name can contain "bus". Where
  a leg has only an operator icon, as in Japan, its path decides: JR and the lettered lines are
  trains, a `metro` or `subway` operator is a subway.
- Interchangeable lines on one direct ride (S1, S11, S12) merge into one badge, so the trip does
  not read as two transfers.

A walk leg's steps come from the walk router when the leg is opened.

The planner's trips take the same form. They lack a fare, service alerts and walk distances. The
vehicle chips become its `transitModes`, and `TransitOrder.byPreference` sorts its answers
because it has no route preference.

### Bike-share trips

With the "Bike share" chip on the Bike tab and a trip that leaves now, `BikeShareTripSearch` runs
beside the bike routes. It never runs for a later time, and the sheet says bike share is only for
leaving now: the planner picks stations by their bikes and docks as of the request. Flipping the
chip or changing the time asks for the bike-share trips again and leaves the bike routes alone. The trips sit in `BikeShareTripsShown`, a holder the sheet and the
map read, since MapScreen takes no new parameter.

1. `Transitous.bikeShareGroups` asks which systems run around the trip, for providers only. A
   system counts when it rents bicycles and is not a free-floating operator, by the same list as
   the station layer. With none, nothing more is asked.
2. `Transitous.planBikeShare` asks the planner for trips with `RENTAL` before, after or instead
   of transit, on those systems only. Without that list it also rides free-floating bikes: central
   Paris came back with Lime, Voi and Dott bikes ahead of its one Vélib' ride. This is a request
   of its own because with `RENTAL` as a direct mode the planner drops the transit trips slower
   than the fastest bike ride.
3. A rental leg becomes a ride on the system's line, from the pickup station to the return
   station. A trip with any other rental leg is dropped, and a later run of the same trip counts
   once.
4. Once the trips are on screen, their stations' free bikes and docks are asked for, one small
   box per group of stations, for those systems only. The plan names its stations without an id,
   so each is found by name, or by position within 30 m. A trip shows the free bikes at its
   pickup, and its bike leg the free docks at its return where the feed gives them: amber at two
   or fewer, red at none. "Use less data" skips this step.

The planner itself takes a bike only from a station with one, and returns it only where a dock
is free, when the feed says so. Most feeds on the public instance give no dock counts, and there
any station that takes returns will do. [SPEC 8](../../SPEC.md#8-transit), Bike share in
directions, has the rules.

### The trip on the map

Expanding a trip draws its rides in the agency's color (blue when there is none), its stops as
ringed white dots and its walks as dotted gray links, under the map labels. A bike-share ride
draws along its own path, teal when its system gives no color, with dots at its two stations.

The planner's trips carry their own paths. Google's have stops and no track, so a ride first
draws as straight lines through its stops. `MapViewModel.shapeTransitLegs` then looks up each
ride's path, for at most 5 rides (`TRANSIT_SHAPE_MAX_LEGS`), never offline or on a constrained
link:

1. `Transitous.legShape` asks the planner for a trip between the two stops and takes the path of
   a ride whose ends are within 300 m of them (`LEG_SHAPE_STOP_M`).
2. A bus with no path gets the road route through its stops (`busRoadPath`), refused when it is
   over 1.8 times the stops' straight-line chain, which is what a stop placed across a barrier
   produces.
3. Rail with no published path keeps its straight lines.

`TransitShapes` holds the paths by step object, because swapping in a copied itinerary would
collapse its row.

### Rail lines in their own colors

With "Highlight transit lines" on (off by default), the basemap's rail data is drawn as a plain
two-color highlight. Above it, `Transitous.linesInBox` supplies track in each line's own color:
trains from zoom 8, subway, tram and light rail from 10.5, with the lines' short names from 13.
Lines that share track draw side by side. The plain highlight for a kind hides while colored
lines of that kind are in view. Two switches pick trains, or subway and tram. The lines are
hidden while a driving, walking or cycling route is on screen. The request grid, and which
stretches of a reply are drawn, are in [SPEC section 8](../../SPEC.md).

The colored lines of every map cell you have looked at are kept on the phone
(`TransitLineCache`). A view seen before draws from there before any request goes out, which
is also what shows with no connection or on a constrained link. A kept cell is fetched again
once it is a week old. The first look at a new area still waits for the service: over upper
Manhattan on a Pixel 4a, 6.5 s for four cells, against 41 ms for six cells read back after a
restart in airplane mode.

### Guidance

`TransitNavSheet` takes the bottom half of the screen, with the trip drawn above it and the
camera on the current leg. Each leg is spoken as it begins. Next and Back step by hand, at the
bottom of the sheet on every card; the remaining legs scroll in the room above them.

A leg advances by itself when you reach its end. It must first arm by being more than 90 m from
that end (`TRANSIT_ARM_M`), then advances within 40 m (`TRANSIT_ARRIVE_M`). Arming keeps a
transfer hub, where two leg ends are close together, from running through several legs at once.
Unlike a drive ([chapter 4](04-navigation.md)) there is no off-route detection and no rerouting.

Guidance runs a foreground service of its own, `TransitGuideService` (issue #880), so it keeps
its fixes and its voice with the screen off. Its notification, on the drive's silent channel,
carries the leg in hand: a ride as "Take 42A towards ..." over "Get off at ... · 4 stops left",
a walk as "Walk 9 min" over the stop it leads to, with an End button. The stops left are counted
from the stop nearest the phone along the leg's stop list (`TransitProgress.stopsLeft`). At the
stop before the rider's (or within `ALERT_M`, 600 m, of it when the leg has no stop list) a
heads-up says "Get off at the next stop" once per leg and the voice says it too
(`TransitProgress.nextIsYours`). On arrival the ongoing card gives way to a dismissable "You have
arrived", which Done in the app clears. The view model writes the card (`TransitGuide.notice`);
a start the system refuses leaves guidance working in the app, as before.

A bike-share leg is spoken by its two stations, where to take a bike and where to return it, and
advances at the return station. Its length opens to cycling steps from the bike router, the way a
walk leg opens to walking steps. Its counts are the ones fetched with the trip and are not asked
again on the way. Its notification names the same two stations, with no "next stop" heads-up.

### Offline copies

- `TransitStopCache` keeps the stops of the 24 most recent areas (`MAX_AREAS`), overwritten by
  each successful fetch. Offline, the layer reads the area under the view, merged stops included.
  An area never visited shows the basemap's icons.
- `TransitBoardCache` keeps the 48 most recent Transitous boards (`MAX_ENTRIES`). Offline, a tap
  within 40 m (`NEAR_M`) of one shows it with the time it was seen. A live board replaces it.

## Limits

- Offline boards exist only for stops whose Transitous board you opened online, and their times
  are old. A timetable bake per region is an open roadmap item, estimated at tens of megabytes
  for a mid-size state, with no realtime.
- Transit directions need a connection. There is no on-phone transit router.
- The planner's times are the timetable plus current lateness, without Google's traffic history.
- The plain-request downgrade was measured before Google requests moved to Cronet. Nobody has
  tried the transit request over Cronet with the page's own headers
  ([chapter 7](07-talking-to-google.md)), so the page load is the only path.
- Google's boards do not refresh, count down from the timetable time, and are always fetched in
  English because the parser finds departures by their 12-hour clock text.
- Rail with no published path draws straight between stops.
- The stop list built from directions fails when the headsign does not geocode or Google
  proposes no trip on the tapped line. The sheet then says "Route details unavailable".
- The transit time on the mode chip, fetched in the background from another mode, ignores
  preferred vehicles and the route preference.
- Guidance does not re-plan after a missed connection.
- A scheduled trip is sent to Google in the phone's time zone, which is wrong for a trip planned
  in another zone.
- A merged stop keeps one member's modes, so the stop-kind switches can hide an icon that
  another kind of vehicle also serves.
- The colored-lines endpoint is experimental upstream. A changed reply reads as no lines.
