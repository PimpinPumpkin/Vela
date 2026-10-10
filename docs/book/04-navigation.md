# 4. Navigation

## What you see

A banner with the next turn, an arrow that follows you, a bar with the time and distance left,
and a voice. On a trip with stops the bar's figures are for the next stop, named under them,
with the whole trip on a line below. Around them:

- Pause, in the bottom bar. While paused the route line turns lavender.
- A faster-route offer that settles itself after ten seconds.
- A step list that opens on the step you are on, with a divider at every stop.
- The name of the road you are on.
- A "Searching for GPS" chip above the arrow when the fixes stop.
- A speed-limit badge, and an optional voice that says when you are over the limit.
- On Android 16, the drive as a live update: a chip in the status bar and a route bar on the lock
  screen.

How these are drawn is [chapter 11](11-drive-chrome.md).

## Where the data comes from

- A drive starts on the route the chooser planned. A reroute or a traffic recheck asks for a new
  one the same way planning does (`MapDataSource.directions`). [Chapter 5](05-routing.md) says
  which route comes back. This chapter covers what the drive does with a route, and how a reroute
  asks for one against a deadline.
- Your position is the phone's GPS through Android's own location service (`LocationProvider`).
  Network (Wi-Fi and cell) fixes never reach guidance. They may move the dot only after GPS has
  been silent for 12 s (`NETWORK_FIX_QUIET_MS`). A GPS fix with a reported accuracy worse than
  50 m moves the dot and is not fed to the loop.
- The map screen's view model feeds the fixes, and under Android Auto the car session does.
  `NavigationService` keeps the process alive and mirrors the drive into the notification. A
  drive still works when the foreground service cannot start.
- Beyond the map and speed-limit tiles for the area on screen, your position leaves the phone as
  the start of a reroute, of a traffic recheck, and of the detour estimate for a tapped place.
  Settings > Navigation > "Live traffic re-checks while navigating" turns the rechecks off.
- The speed limit is OpenStreetMap's `maxspeed`, from the downloaded region's file, else from
  the hosted speed-limit tiles.
- Lights, stop signs, crossings and cameras come from the per-region bakes in
  [chapter 2](02-data-and-rebakes.md), downloaded for the route's region when the drive starts.
  Where no region is baked, the drive asks Overpass once for the route's corridor. The camera
  rules are [chapter 3](03-cameras.md). Whether a stop sign is yours or the cross street's is
  [chapter 11](11-drive-chrome.md).

## How it is decided

Every threshold is in [SPEC section 4.6](../../SPEC.md). The ones below are the ones that explain
what you see.

### The per-fix loop

`NavSession.onLocation` runs for every fix. `NavEngine.update` projects the fix onto the route,
counts it toward off route, picks any prompt that is due, and advances the step or arrives. The
session then publishes the new state, speaks and buzzes, starts a reroute if one was asked for,
announces any stop just passed, and considers a traffic recheck.

`NavEngine` is pure: a route, the previous state and a fix go in, the next state and a list of
events come out. Speaking, rerouting, stop cues and arrival all follow from that one call. Pause
returns before it.

Progress along the route only moves forward. A fix is matched inside a window around the
progress so far, so a route that passes over itself cannot jump to a later leg.

### What the voice says, and when

```
far prompt   = max(400 m, speed x 35 s)
near prompt  = max(150 m, speed x 10 s)
turn now     = speed x 2.5 s, clamped 25..90 m   // also where the step advances
PASSED_SLACK_M = 75    // a maneuver this far behind was passed in a gap: advance silently
```

Each step gets at most a far and a near prompt, then the short turn-now line. At 30 m/s (about
67 mph) the far prompt comes 1,050 m out and the near one 300 m out. In town the 400 m and 150 m
floors apply. A prompt speaks the real distance, so a turn 40 m into a short step is not
announced as "in 400 meters".

That is the Everything setting. "How much the voice says" in Settings, Voice has two shorter
ones (`SpokenDetail`). Brief says each maneuver once, with no street name: "In 150 meters, turn
left". An exit, fork or keep is said from the far distance and keeps its exit number, because
that is what the sign overhead reads; a turn is said from the near distance; nothing is repeated
at the turn. Highway exits only is Brief for exits, forks, keeps, U-turns and the arrival, and
silence for the rest. The route carries no road class, so a turn off a fast road is told apart
from a turn in town by speed: at 80 km/h (50 mph) or more it is spoken. Roundabouts and merges
stay silent. The buzz at a turn stays in all three.

The card moves to the next step when the car is at the turn: 2.5 s ahead at speed, and no more
than 5 m ahead at a crawl. Waiting at a stop line before a turn, the card still shows that
turn. The voice says "turn left" 25 m out either way.

- The first prompt for a step leads with lane guidance when the step has lanes.
- In English a later prompt for the same step drops the sign's "toward ..." tail.
- A merge gets only the near prompt. The destination gets one near prompt, and so does each stop
  (see Stops below).
- A continue or a straight-on is silent unless its lanes show a real fork.
- The first instruction is spoken once by the drive's opener ("Starting navigation. Head east on
  ..."). The engine skips it.
- Off route, the voice says only "Rerouting". Prompts computed against a route you are not on
  name streets that are not there.

"Say street names" (Settings > Voice, on by default) decides whether the voice names the road.
Off, it says "Turn left" where it would say "Turn left onto Maple Street". Nothing on screen
changes. The nameless form comes from the same per-language template with the road left out, so
the word order stays right. Google's short steps have no template, so the voice keeps their full
text.

Prompts duck other audio (`VoiceGuide`). Focus is held for `FOCUS_HOLD_MS = 1500` after a line
so music does not come back up between two prompts, and a phone call that takes focus silences
guidance. Vela's own voice synthesizes the next lines ahead of time (`NavEngine.upcomingPrompts`).

Two turns within 130 m of each other are said on one line: "Turn right onto Elm Street, then
turn left onto Oak Avenue", when the first is announced and again at the first turn. The second
turn is then said only at the turn itself. Said apart, its approach line landed on top of the
first turn's line. Dutch, Swedish and Japanese do not have the joining line yet and say the two
apart.

Two wording rules live in the routers and are heard here:

- The on-phone router sometimes labels a road's own bend as a turn. A turn it flags
  `skipToSpeak`, or a left or right that measures under `STRAIGHT_TURN_DEG = 20`, becomes a
  silent continue (`ObfRouteEngine.spokenType`). Roundabouts keep their type.
- A roundabout is worded from its measured turn (`RouteGeometry.roundaboutMod`). OSRM's own
  modifier called a 140 degree left "straight". "Straight" is used only within
  `RB_STRAIGHT_DEG = 30`, and with no measured geometry it is dropped, leaving "take the 2nd
  exit".

### Off route

The corridor widens with the fix's reported accuracy, so a noisy fix in a city canyon does not
read as a wrong turn. Walking and cycling get a tighter one because the path is narrower.

```
corridor, drive  = 18 + 2.0 x accuracy, clamped 24..70 m
corridor, bike   = 12 + 1.9 x accuracy, clamped 18..55 m
corridor, walk   =  8 + 1.8 x accuracy, clamped 15..50 m
                   (accuracy clamped 3..40 m; 12 m assumed when the fix has none)
far off          = 2 x corridor, capped 110 / 75 / 60 m (drive / bike / walk)
moving floor     = 2.0 / 1.0 / 0.6 m/s (drive / bike / walk)
OFF_ROUTE_HITS   = 3      // hits before the drive counts as off route
HEADING_OFF_DEG  = 60     // heading this far against the route's direction is a hit
```

For driving, a 5 m fix gives a 28 m corridor, a fix with no accuracy figure 42 m, and a 30 m fix
70 m.

Each fix changes a hit count:

- Inside the corridor and heading the right way: the count resets.
- Outside the corridor: one hit.
- Moving and past the far distance: two hits.
- Moving against the route's direction: one hit even inside the corridor, and two once the fix is
  a quarter of the corridor off the line. A wrong turn onto a road that runs beside the planned
  one stays inside the corridor for blocks, and distance alone never catches it.
- Stationary: the count holds, unless the fix is past the far distance. A red light cannot cause
  a reroute. Creeping out of a parking lot still can.

### Rerouting

```
REROUTE_FETCH_TIMEOUT_MS   = 20_000   // deadline of a lean attempt
REROUTE_LADDER_TIMEOUT_MS  = 40_000   // deadline of an escalated attempt
REROUTE_ESCALATE_AFTER     = 2        // failed attempts before escalating
REROUTE_FINISH_RESERVE_MS  = 4_000    // held back from the fetch for naming and adopting
REROUTE_COOLDOWN_MS        = 10_000   // minimum gap after an adopted reroute
REROUTE_STUCK_GRACE_MS     = 5_000    // past deadline + this, a running attempt is dead
REROUTE_SPEAK_MIN_MS       = 30_000   // "Rerouting" is spoken at most this often
BACK_ON_COURSE_HITS        = 2        // on-route fixes that discard a reroute in flight
```

Going off route asks for a reroute. `NavSession.rerouteGate` decides whether it starts:

- One fetch runs at a time. A request while one is out is dropped.
- A request inside the cooldown is dropped, so fixes biased toward a parallel road cannot cause
  a reroute storm.
- A fetch still running past its deadline plus the grace is abandoned and a new one starts. The
  fetch runs on its own scope so the deadline can abandon it. `withTimeoutOrNull` cannot
  interrupt a blocked socket read or the on-phone router, and a fetch stuck there used to block
  every later reroute.

The first two attempts are lean: one try per source, because the full retry ladder can outrun
the deadline on a weak link. After two failures in a row the attempt uses the full ladder and
the longer deadline. The streak resets on any adopted route and on every new drive.

An attempt asks for a route the way planning does ([chapter 5](05-routing.md)), with a budget
and a heading added.

The budget is the deadline minus the finish reserve. It travels into the fetch (`RouteBudget`),
and each source gets a share:

- A lean attempt gives the open router one try of `URGENT_OSRM_TIMEOUT_MS = 6_000`. An escalated
  one gives it up to three tries of `LADDER_OSRM_TRY_MS = 8_000` inside
  `LADDER_OSRM_SHARE = 0.55` of the budget.
- When the open router answers, a lean attempt waits `URGENT_GOOGLE_GRACE_MS = 2_500` more for
  Google. If Google is not back, the open router's route goes out with no traffic.
- When the open router gives nothing, `RerouteFallback.pick` takes Google's route with its own
  short steps if it is already back. Otherwise it races Google against the on-phone router for
  the time left and takes the first route.
- If a downloaded region covers both ends of the trip, a lean attempt starts the on-phone route
  alongside the open router and takes it when the open router has not answered within
  `PHONE_FIRST_ONLINE_WAIT_MS = 2_500`. The compute gets `PHONE_FIRST_ONDEVICE_WAIT_MS = 4_000`
  past that, never past the deadline. A trip with stops chains its legs on the phone the same
  way.

A route adopted with no traffic, with short steps, or with turns that went out without their
street names is degraded. The recheck below replaces it once the sources recover.

The heading is the fix's course at the start point. Without it, a reroute computed a few tens of
meters down the wrong road often says to turn around. The open router and the on-phone router
both take it. Google's request has no heading, so a Google route that starts against yours is
set aside when the open router answered, unless going on would cost minutes more than turning
around (book 5). A stopped car has no fresh course and sends none.
Planning sends none.

The answer is checked before it is driven (`NavSession.driveable`). It must end within
`REACH_TOLERANCE_M = 500` of the destination. A provisional Google alternate is named first, and
a route with only Google's short steps loses to a full-stepped one from the same reply. A
recheck and a stops edit go through the same check.

If two moving fixes in a row are back on the original line while the fetch is out, the new route
is thrown away when it lands.

A failed attempt, and a request the cooldown dropped, both clear the off-route latch on the
location thread. The next few off-route fixes then ask again, so a failure never ends rerouting.

The first attempt of a burst plays a falling two-note chime, says "Rerouting" and buzzes. The
retries after it are silent. Every attempt writes a line to the diagnostics ring and the
recorded trip: `reroute adopted: <source> in N ms`, or `reroute FAILED (streak s, deadline 20 s
after N ms), will retry while off-route`.

### Traffic rechecks and faster routes

```
RECHECK_INTERVAL_MS           = 120_000   // every 2 minutes, spread by plus or minus 25%
DEGRADED_RECHECK_INTERVAL_MS  =  20_000   // while the route is degraded
DEGRADED_FAST_TRIES           = 6         // then back to the normal interval
MIN_RECHECK_DISTANCE_M        = 1_500     // no rechecks this close to the destination
SAME_COURSE_M                 = 250       // a candidate within this of the line is the same course
FASTER_THRESHOLD_S            = 90        // an offer must save more than this
MIN_PLAUSIBLE_ETA_FRACTION    = 0.4       // a candidate under 40% of the time left is a bad route
```

While driving, the session asks for the route again from where you are. It skips the recheck
while off route and while an offer is on screen. What happens next depends on the candidate:

- Same course, with live traffic: its arrival time corrects yours. The session keeps a
  multiplier on the remaining time (`etaScale`, clamped 0.5 to 2.5, reset on every route swap).
  Without it the arrival time would carry the traffic measured at the last route fetch for the
  rest of the drive.
- Same course, and the current route is degraded: the candidate replaces it silently when it is
  better in steps, street names or traffic and worse in none (`RouteHeal`). A route whose names
  missed the planning deadline is asked for again about every 20 seconds, six times, and gets
  its names within the first answer that has them.
- Another course: it is offered when it saves more than 90 seconds, has live traffic and full
  steps, passes every remaining stop, and takes between 40% and 90% of the time left. A
  candidate without traffic never counts, because free-flow time always looks faster. A
  dismissed candidate comes back only when it beats the dismissed saving by another minute.

The offer is a card with a bar that drains for ten seconds, after a rising two-note chime and a
spoken line. Focus anywhere on the card stops the clock, which gives a phone driven by keys the
time it needs. At zero the route is taken. With "Take faster routes automatically" off, the
offer is dismissed at zero. It never stays on screen waiting for an answer.

Turning off "Live traffic re-checks" stops all of this. Reroutes still happen.

### Losing GPS

When the fixes stop while the drive is on the route and moving (a tunnel, a parking structure),
`NavController.tunnelDeadReckonLoop` keeps the drive going on an estimate:

```
DR_START_MS     = 3_500    // gap in the feed before the estimate starts
DR_DECAY_S      = 60       // the assumed speed decays with this time constant
DR_MIN_SPEED    = 1.5      // m/s; below this it holds position, and it never starts from a stop
DR_MAX_M        = 3_000    // cap on blind travel
NAV_STARVED_MS  = 10_000   // no guidance-quality fix for this long: show the chip
```

It feeds one synthetic fix a second along the route through the normal loop, so the banner, the
voice and the arrow keep working. The first real fix takes over. Synthetic fixes are not written
to a recorded trip.

### Pause

Pause holds the drive where it is:

- The route, the stops and the figures stay as they are.
- No off-route detection, reroute, arrival, stop cue, voice, recheck or offer.
- The arrow keeps following you, because it is drawn from the raw fix.
- The arrival clock keeps moving, on a 30 second tick.
- The bar says "Paused", the line ahead turns lavender (`ROUTE_PAUSED_COLOR`), and the screen
  is allowed to sleep.

Resuming checks where you are. Off the route, it reroutes once from there. On it, it speaks the
current instruction.

A paused drive resumes itself when you drive away, because driving on behind a frozen banner is
worse than never pausing:

```
autoResumeArmed        // set by a fix that is stationary or off the route
AUTO_RESUME_HITS = 3   // then this many fixes in a row that are moving and on the route
```

Without the arming step, a pause taken while still rolling along the route would resume three
fixes later.

Pause is in the bottom bar, on the notification beside End, and on the Android Auto action
strip. The phone is usually in a cradle when the driver decides to pull in.

### Stops

A stop counts as passed when progress comes within `STOP_ARRIVE_TOL_M = 25` of its mark on the
route, and the voice says "You've reached <stop>".

The voice also says a stop is coming, the way it says the destination is:

```
STOP_THEN_M     = 300   // the last turn before a stop names it when the stop is this close past it
LOT_DEST_BACK_M = 400   // the turn into a parking lot is looked for this far before a stop or the end
```

- The last maneuver the voice speaks before the stop names it after itself, on its first line and
  again at the turn: "In 400 meters, turn left onto Covell Boulevard, then Davis Food Co-op will be
  on your right". A stop farther past its turn, or with no spoken turn before it, gets its own line
  at the near distance: "In 150 meters, Davis Food Co-op will be on your right". Either way it is
  said once, and never while off route.
- The side is where the stop's pin sits against the road at the stop (`NavEngine.stopSide`). A
  pin within 8 m of the line, or a line that bends more than 45 degrees there (a corner), gives
  "will be ahead" instead of a side that could be wrong.
- A way into a lot ends with the turn off the street and often a turn or two between the
  aisles, all with no name. Vela looks at up to three such turns in the last 400 m before the
  stop and reads the map's own tiles along the first 60 m past each (`ParkingLotTurn`). The
  first whose tiles show a parking aisle, reached by service roads and no street or alley, is
  the turn into the lot: it says "Turn left into the parking lot", and when it is also the last
  turn before the stop, "Turn left into the parking lot, then Davis Food Co-op is on your right".
  No router marks a parking aisle, and a road with no name is just as often a driveway or an
  unnamed lane, so anything less certain keeps the plain "Turn left".
- The end of the trip gets the same test against the destination. It is settled when the route
  is planned, so the step itself reads "Turn left into the parking lot" on the banner, in the
  step list, in the voice and on the car. A turn between the aisles after it stays a plain
  "Turn right", and the usual arrival lines follow.
- Brief puts the stop on its turn's one line. Highway exits only stays quiet for the turn in town
  and says the stop's own line, as it says the destination's.

While a stop is ahead, the bottom bar's time, distance and arrival clock are for that stop, with
"To <stop>" under them and the whole trip under that ("Trip 1 hr 5 min · 3:40 PM"). The step
list's stops row has it in full: "Whole trip: 1 hr 5 min · 42 mi · arrive 3:40 PM". The
notification and Android Auto show the stop's figures too. A screen reader hears the bar as one
line with both. A reroute, a faster route, a stop added or the stops edited mid-drive all carry
the figures over to the new route at once. The stop's
time is the trip's remaining time less the time past the stop's mark, worked out over the
maneuvers' own legs and traffic ratio the way the trip's is (`NavSession.nextStop`,
`NavEngine.secondsBeyond`), so the two always add up. Past the last stop, or when the next stop
has no mark on the route, the bar shows the whole trip again.

- A stop added during the drive from the phone goes last. The stops editor, which is open
  during a drive, drags it into place. A stop added from the car's search goes next, because
  the car has no editor.
- Every reroute and recheck routes through the stops still ahead.
- A reroute that could not include them is adopted anyway and says so, because being guided
  beats being lost. The stops stay in the plan for the next attempt.
- A stop more than 150 m from the line has no mark. It counts as passed only once a later stop
  is reached (`NavEngine.stopsPassed`), so every stop survives the moments between a stops edit
  and the new route. When no later stop has a mark either, it is reached within
  `STOP_NEAR_PIN_M = 250` of its pin, which is how a stop in the middle of a mall or a park
  ends.
- Progress that jumps more than `STOP_SKIP_JUMP_M = 250` past the next stop in one fix, at more
  than `STOP_SKIP_SPEED_MPS = 70`, is a skip (`NavEngine.stopSkipped`). This is a driver who
  kept going after an edit, on a road the route uses later. Nothing is announced and the drive
  reroutes through the stop. A pause, or a GPS gap in a garage at the stop, is not a skip: the
  ground was covered at a speed a car makes.
- Stops are only counted while the route being driven is the one their marks were measured on,
  so the traffic-light pass leaves the session's routes alone. With its copy swapped in, no
  stop is counted and reroutes and re-checks go back through stops already visited.
  The hidden points of a saved route or a camera detour are the exception: driving past the
  stretch they are on and rejoining the route drops them, with no reroute back.

Adding or removing a stop mid-drive replans once through the new list (`NavSession.setStops`).
The order is the user's, so the replan skips the cooldown and the back-on-course discard and
cancels a reroute in flight. An added stop goes first. If the fetch fails the list is kept, and
the next reroute or recheck routes through it. The controls (search along the route, the stops
editor, "Remove next", tapping a place) are in [chapter 11](11-drive-chrome.md). How the replan
is routed is in [chapter 5](05-routing.md).

When "Try side streets around cameras" builds a detour ([chapter 3](03-cameras.md)), its points
ride along as `NavStop.silent` stops. Every reroute and recheck routes through them, and they
are never spoken, listed or shown as a divider. The stops editor sees only the visible stops,
and the silent ones still ahead are put back in route order on Done.

**Closing soon.** When a drive starts (`NavController.maybeWarnClosingSoon`), a place that
closes less than an hour after you arrive, or before you arrive, gets one warning: "<place>
closes at 9:00 PM and you arrive around 8:40 PM". It is shown for 15 seconds, spoken, and sent
to the car screen.

- Stops are checked in order, then the destination. Only the first problem is warned about.
- The closing time is read from the place's own status text. A place with none is never warned
  about.
- The destination is checked only when the selected place is within 200 m of the route's end.
- A route keeps no per-leg times, so a stop's arrival is the trip's time scaled by how far along
  the line the stop sits (`stopArrivals`).
- A stop added during the drive is checked on the replanned route, which it waits up to 20
  seconds for.

### Arrival

The drive arrives when any of these holds:

- You are within `ARRIVE_RADIUS_M = 25` of the end, measured along the route.
- You are within `ARRIVE_PROX_M = 40` of the destination in a straight line. Routers snap the
  destination to the road, and you may park beside it.
- You are stopped with 50 m or less left and within 60 m in a straight line.
- You are stopped with `END_PARK_ALONG_M = 60` or less left and within 40 m in a straight
  line, whatever turns remain. The first three are tested on the last step only, and a car
  parked just short of a last turn into a lot never gets to it.

The voice says which side the destination is on when the route knows it, else "You have
arrived". Within `DEST_ZONE_M = 150` of the destination nothing reroutes, so parking short of
the snapped endpoint counts as arriving. If you drive back out of the zone still off route, the
held reroute fires.

On arrival the foreground service stops and leaves a notification you can swipe away, and a
recorded trip is saved. The arrival card shows the trip's time and distance. After a drive it
has "Save parking spot", which does what the map's parking button does, unless that button is
turned off in Settings.

### Resuming after the app was killed

A drive saves its destination, label and travel mode when it starts, with a timestamp it
refreshes every five minutes. If the process dies mid-drive, the next launch offers to resume
while that timestamp is under an hour old (`RESUME_MAX_AGE_MS`). Resume waits up to
`RESUME_FRESH_FIX_WAIT_MS = 8_000` for a fix newer than the one on screen. A cold start shows
the position where the process died, and a route from there draws the line over road already
driven. Then it plans a fresh route.

### The speed-limit badge and the speeding alert

With a downloaded region, the fix is snapped to the nearest road within `LIMIT_SNAP_M = 25` and
its forward `maxspeed` is read. No limit, and anything 150 km/h or over, shows blank. The lookup
reruns after 18 m of travel, off the main thread. An untagged stretch keeps the last limit for
`SPEED_LIMIT_FORGET_M = 300`, so the badge does not flicker between tagged segments and does not
carry a 45 onto the side street you turned onto. Where the region has no limit, or there is no
region, the badge reads the hosted tiles: the nearest tagged road within 20 m, from the one tile
the car is in.

"Speeding alert" (Settings > Navigation, off by default) says "You're over the speed limit" once
you have been more than 5 km/h over the badge's limit for 4 s (`holdMs`). The 5 km/h matches the
point where the badge turns red. It can speak again after 8 s back under the limit (`rearmMs`),
and never more often than every 45 s (`minGapMs`).

With the voice muted, whether by the mute button or by turning spoken directions off, the
speeding, speed camera and plate camera alerts play two short notes instead of speaking. They
are the sound a driver who turned the turns off still wants; every other prompt and chime stays
silent.

### The step list, the road name and the notification

The step list opens on the step you are on. Steps already driven sit above it, grayed. A divider
names each stop where its leg begins.

The road name is the road entered by the last maneuver you passed, following any rename along
the way, and on an unnamed ramp the road the ramp leads onto. Whether it shows the name or the
number is in [chapter 11](11-drive-chrome.md). "Current road name" (Settings > Navigation) puts
it under the arrow (the default), above the bottom bar, inside the bar, or nowhere. Under the
arrow it follows the arrow and is clamped to stay on screen. Above the bar it stays centered
and has room for a long name. Inside the bar it takes no map space.

The notification shows the current maneuver's glyph, the distance to it, the time and distance
left and the arrival time, with Pause and End. When the voice speaks while the app is in the
background, a silent heads-up shows the turn. On Android 16 the notification asks to be promoted
to a live update: a status-bar chip with the distance to the next turn, and on the lock screen a
bar scaled to the route, with the arrow where the car is, traffic colored like the route line
and a point for each stop ahead.

- The app must hold `POST_PROMOTED_NOTIFICATIONS`, or the chip never appears.
- The channel has default importance with no sound and no vibration. At low importance the
  system files it as silent, and a phone that hides silent notifications on the lock screen
  hides the live update.
- Promotion is a request. Below Android 16, with no route, or when the system declines, the
  ordinary notification stays.

### Testing without driving

Two switches in Settings > Diagnostics make every nav screen testable at a desk:

- "Simulate my location" pins the location dot to the map center at the moment it is turned on.
  Directions start from there and no GPS is read. Center the map on a fixture area (Davis) first.
- "Simulate driving" (`demo_drive`) makes Start drive the planned route along a synthetic trace,
  one fix a second, through the replay path a recorded trip uses. It drives the way a car would:
  each step at the pace the route expects, slower through turns and bends, pulling away at the
  start and braking at the end, so the voice has the time it has on a real drive. Add a stop or
  edit the stops during it and the car follows the new route. End stops it. Left to run, it
  stops on the arrival card as a real drive does.

A simulated drive and a replayed trip never reroute or recheck (`NavSession.replayMode`). Both
switches reach the car screens too. Turn both off before a real drive.

## Limits

- Paused, the distance left stays the route's remaining distance, because Vela cannot know how
  far you will wander. The arrival time does move.
- A long pause does not re-plan. Resume gives the same route, rerouted from where you are if you
  moved. Traffic that changed while you sat is noticed by the next scheduled recheck.
- The Android Auto feed (`VelaCarSession`) passes a fix's position and speed only. With no
  accuracy its fixes use the default 42 m corridor. With no heading they never count the heading
  rule, they reroute with no heading, and inside the corridor they reset the off-route count,
  which slows detection of a wrong turn onto a parallel road. A fix with no speed arms the
  auto-resume and never fires it, so resume by hand there. Passing accuracy, course and a derived
  speed the way the phone's feed does would fix all three.
- A stop's arrival time is an estimate by distance. The trip's time is spread evenly along the
  line, so traffic that sits mostly before or after a stop skews it. That is good enough for a
  one-hour warning window and not for minutes. Keeping per-leg times would fix it.
- A stop's side is the side its pin is on. A pin set on the far side of a divided road, or behind
  the building, gives that side.
- "Into the parking lot" needs the lot's aisles mapped in OpenStreetMap and present in the map's
  z14 tiles. Where they are not, the turn stays a plain "Turn left".
- A reroute that cannot pass every remaining stop says it could not include your stops. The
  check counts the camera detour's silent points, so a drive with no visible stops can hear it.
- A drive resumed after the app was killed has no stops, visible or silent. Only the destination
  is saved.
- The on-phone router takes the reroute heading as a soft preference, in the angle convention
  read from the OsmAnd library. It has not been confirmed on a device.
- Speed limits are only as good as OpenStreetMap. Many roads carry no `maxspeed` tag, and there
  the badge is blank.
