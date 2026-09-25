# 4. Navigation

## What you see

A green banner with the next turn, an arrow that follows you, a bar with the time and distance
left, and a voice that tells you what to do. Behind it, every GPS fix runs the same loop: where
am I on this route, what is the next instruction, is anything worth saying, have I gone wrong.

Around that loop:

- a **Pause** button, which is the one thing Google Maps will not let you do; while paused the
  route line turns lavender;
- a **faster-route offer** that settles itself after ten seconds instead of waiting on you;
- a **step list** that opens on the step you are on, with a divider at every stop;
- the **road you are on**, under the arrow, above the bar or inside it, as you choose;
- a **"Searching for GPS" chip** pinned just above the arrow when the fixes stop;
- a **speed-limit badge**, and an opt-in voice that says when you are over it;
- on Android 16, the drive as a **live update**: a chip in the status bar and a route bar on the
  lock screen.

## Where the data comes from

- **The route** comes from the open OSRM router, with Google supplying the traffic-aware ETA
  and acting as a fallback, and the OsmAnd-format file on the phone when a region is downloaded.
  [Chapter 5](05-routing.md) says which engine answers when. This chapter only covers what the
  drive does with a route once it has one, and how a reroute asks for a new one against a clock.
- **Your position** is the phone's own GPS through the plain Android location service. Network
  (Wi-Fi and cell) fixes are dropped during navigation, and a fix whose reported accuracy is worse
  than 50 m never reaches the loop. Nothing about your position leaves the phone except the start
  point a reroute or a live-traffic recheck has to carry, and the rechecks can be turned off
  (Settings > Navigation, "Live traffic re-checks while navigating").
- **The speed limit** is OpenStreetMap's `maxspeed`, read from the downloaded region's route file
  under the arrow, else from the hosted speed-limit tiles online.
- **Lights, stop signs, crossings and cameras** on the drive come from the per-region bakes in
  [chapter 2](02-data-and-rebakes.md), all on the phone; the camera rules are
  [chapter 3](03-cameras.md).

## How it is decided

### The per-fix loop

Every location update, in order: project the fix onto the route, advance the step, recompute the
remaining distance and time, emit whatever events that produced (speak, vibrate, arrived,
reroute), announce any stop that was just passed, and then consider a live-traffic recheck.

Anything that stops navigation stops all of it, because everything is downstream of that call.
That is the mechanism the pause uses.

### Off route

The off-route corridor is **accuracy-scaled and mode-relative**: it widens with the fix's own
reported accuracy, so a noisy fix in a city canyon does not read as a wrong turn, and walking and
cycling ride tighter than driving because the path is narrower.

```
corridor, drive  = 18 + 2.0 x accuracy, clamped 24..70 m
corridor, bike   = 12 + 1.9 x accuracy, clamped 18..55 m
corridor, walk   =  8 + 1.8 x accuracy, clamped 15..50 m
                   (accuracy clamped 3..40 m; 12 m assumed when the fix has none)
far off          = 2 x corridor, capped 110 / 75 / 60 m (drive / bike / walk)
OFF_ROUTE_HITS   = 3        // hits before the drive counts as off route
HEADING_OFF_DEG  = 60       // moving this far against the route's direction is a hit
moving floor     = 2.0 / 1.0 / 0.6 m/s (drive / bike / walk)
```

A fix inside the corridor, heading the right way, resets the count. A fix outside it adds one.
Two things add two: a moving fix past the far distance, and a moving fix heading against the
route that is already a quarter of the corridor off the line, which is what a left taken instead
of straight looks like on the second fix after the turn. A stationary fix holds the count unless
it is past the far distance, so parking-lot creep still gets rerouted without a red light doing
it. The heading term exists because a wrong turn onto a road that runs close beside the planned
one stays inside the corridor for blocks.

While the drive is off route the voice says nothing but "Rerouting": turn prompts computed
against a route you are not on name streets that are not there.

### Rerouting

```
REROUTE_COOLDOWN_MS         = 10_000   // minimum gap between adopted reroutes
REROUTE_FETCH_TIMEOUT_MS    = 20_000   // deadline of a lean (urgent) attempt
REROUTE_ESCALATE_AFTER      = 2        // failed attempts before the full ladder
REROUTE_LADDER_TIMEOUT_MS   = 40_000   // deadline of an escalated attempt
REROUTE_STUCK_GRACE_MS      = 5_000    // past deadline + this, a running attempt is dead
REROUTE_FINISH_RESERVE_MS   = 4_000    // kept back from the fetch for naming and adopting
REROUTE_SPEAK_MIN_MS        = 30_000   // "Rerouting" is spoken at most this often
BACK_ON_COURSE_HITS         = 2        // on-route fixes that discard a reroute in flight
```

- **One at a time, never forever.** Only one reroute runs, and a new one waits out the cooldown
  after the last adoption so a GPS fix biased toward a parallel road cannot cause a storm. But a
  fetch stuck in a socket read outlives its own deadline, so the single-flight rule is bounded:
  past the deadline plus the grace, the stuck attempt is abandoned and a fresh one starts.
- **Lean first, then thorough.** The first two attempts are single shots with no retries, because
  on a weak link the full retry ladder used to outrun the deadline and get canceled just before it
  succeeded. After two failures in a row the attempt switches to the full ladder with the longer
  deadline. The streak resets on any adopted route and on every new drive.
- **The deadline travels into the fetch.** The attempt hands the router `budgetMs`, its deadline
  minus the finish reserve, and each stage takes only its share. A lean attempt gives the open
  router one try with `URGENT_OSRM_TIMEOUT_MS = 6_000`; an escalated one gives it up to three
  tries of `LADDER_OSRM_TRY_MS = 8_000` inside `LADDER_OSRM_SHARE = 0.55` of the budget. When the
  open router answers, a lean attempt waits at most `URGENT_GOOGLE_GRACE_MS = 2_500` more for
  Google's traffic and otherwise goes without it. When the open router gives nothing,
  `RerouteFallback.pick` takes Google's route if it is already back, else races Google against the
  downloaded region's engine for whatever time is left and takes the first answer.
- **It keeps pointing where you are going.** The reroute sends your heading with the start point,
  so the answer is "given that you are going this way, what now" rather than "turn around".
  Planning a route sends none: which way a parked car faces is not a routing constraint.
- **A failure never ends rerouting.** A failed attempt, and a request the cooldown turned away,
  both clear the off-route latch on the location thread, so the next few deviated fixes ask again
  on their own.
- **Back on course wins.** If two moving fixes in a row put you back on the original line while
  the fetch is out, the new route is thrown away when it lands.
- **Nothing half-built is driven.** A reroute, a recheck and an added stop all pass their answer
  through the same check: a provisional Google alternate is named first, and a route with only
  Google's abbreviated steps loses to a full-stepped one from the same reply, even a slower one.

Every attempt leaves a line in the diagnostics ring and in the recorded trip:
`reroute adopted: <source> in N ms` or `reroute FAILED (streak s, deadline 20 s after N ms)`.
Ending the drive mid-fetch logs `nav ended with a reroute in flight for N ms` to the diagnostics
ring, and the routing side logs which stage gave nothing and which fallback answered.

The first attempt of a burst plays a two-note chime, says "Rerouting" and buzzes; the silent
retries after it do none of those.

### Live traffic rechecks and faster routes

While driving, Vela re-asks for the route periodically so the arrival time tracks reality:

```
RECHECK_INTERVAL_MS           = 120_000   // every ~2 minutes
DEGRADED_RECHECK_INTERVAL_MS  =  20_000   // faster while the route is degraded
DEGRADED_FAST_TRIES           = 6         // ~2 minutes of fast healing, then back to normal
MIN_RECHECK_DISTANCE_M        = 1_500     // stop bothering near the destination
FASTER_THRESHOLD_S            = 90        // only offer a faster route that saves real time
SAME_COURSE_M                 = 250       // a candidate within this of the current line is the SAME route
MIN_PLAUSIBLE_ETA_FRACTION    = 0.4       // a candidate under 40% of the time left is a bad route
```

When the candidate is the same course, its fresh ETA recalibrates the arrival time you are shown
(a multiplier clamped 0.5 to 2.5, reset on every route swap) rather than being offered as an
alternative. A same-course candidate also **heals** a degraded route: full steps replace Google's
abbreviated ones, live traffic replaces none, never the other way round. "Degraded" is what puts
the recheck on the fast cadence.

A candidate that is a genuinely different course is offered only when it saves more than 90
seconds, has live traffic and real steps, and is not implausibly short. A trafficless candidate
never counts: free-flow time always looks faster than traffic-aware time. A route that skips one
of your remaining stops is never offered. A dismissed candidate comes back only if it beats the
dismissed saving by another minute.

Turning off "Live traffic re-checks" stops all of this; reroutes still happen, because they are
what navigation is.

### The faster-route offer

The offer does not wait for you. A bar drains inside the card for ten seconds, the same length
however the phone is driven, and it **freezes while focus is anywhere on the card**, which is what
reaching for it looks like on a phone driven by keys. A longer window for key-driven phones was
tried and argued down: someone driving with keys is less likely to answer at all, so extra time
mostly means the interruption sits on screen longer, while stopping the clock when they reach for
it gives time to exactly whoever wants it.

At zero it acts. By default it takes the route, which is what Google does and what the offer is
for. Turn "Take faster routes automatically" off and an unanswered offer is dismissed instead.
The clock is keyed on the offer itself, so the ETA moving or the speed ticking cannot hand the
driver their ten seconds back. What it never does is sit on the map waiting.

### Guidance

```
far prompt   = max(400 m, speed x 35 s)
near prompt  = max(150 m, speed x 10 s)
PASSED_SLACK_M  = 75     // a maneuver this far behind was missed in a gap: advance silently
ARRIVE_PROX_M   = 40     // arrival by straight-line distance to the destination
DEST_ZONE_M     = 150    // no rerouting this close to the destination
```

Each step gets at most a far and a near prompt, each speaking the true distance. In English a
later prompt for the same step drops the sign's "toward ..." tail, and a merge gets only the near
prompt. The
first instruction ("Head east on F St") is spoken once by the drive's opener; the engine skips it.

**"Say street names"** (Settings > Voice, on by default) drops the road from what is spoken:
"Turn left" instead of "Turn left onto Maple Street". Nothing on screen changes. The nameless form
comes from the same per-language template as the full one with the road left out, so the word
order stays right in languages where the name is not at the end. Google's abbreviated steps have
no template to rebuild from, so on those the voice keeps the full instruction.

**Offline turns that are not turns.** The downloaded-region router sometimes labels a road's own
bend as a turn. Two rules fold those into a silent rename, the way the online router would:

```
skipToSpeak                   // OsmAnd's own "do not announce" flag: a CONTINUE
STRAIGHT_TURN_DEG = 20        // a left or right with less measured turn than this: a CONTINUE
```

The second one exists because the router was probed emitting "Turn left" with under one degree
of actual turn where a one-way carriageway rejoins its two-way continuation, with the skip flag
off. Roundabouts keep their type either way: the exit is the instruction.

### Stops

A stop counts as passed when progress along the route comes within `STOP_ARRIVE_TOL_M = 25` of
it, and the voice says "You've reached <stop>". Every reroute and recheck routes through the stops
still ahead, never straight to the destination. A reroute that could not include them is adopted
anyway (being guided beats being lost), says so, and keeps them in the plan for the next attempt;
a faster-route offer that skips one is never made.

**Removing the next stop.** The step list carries an "Edit route" row on every drive, and with
stops ahead it has a "Remove next" button. It asks first ("Remove <stop> from this drive?"), then
`removeNextStop` replans once through the rest (`applyStops(stops.drop(1))`), the same path as the
stops editor's Done. How that replan runs is in [chapter 5](05-routing.md#stops).

**Closing soon, per stop.** When the drive starts (`NavController.maybeWarnClosingSoon`), each stop
still ahead is checked at its own arrival, the route's legs added up to it, and then the
destination. A place that closes within an hour of that arrival, or before it, gets one warning:
"<place> closes at 9:00 PM and you arrive around 8:40 PM" (or "closes at ..., before you arrive
around ..." when it will already be shut), flashed for 15 seconds, spoken, and sent to the car
screen. Only the first problem is warned about. A stop added during the drive
(`warnClosingForAddedStop`) waits up to 20 seconds for the replanned route and checks its first
leg, which is the way to that stop. The closing time is read from the place's own status text; a
place with none is never warned about.

**Silent stops.** When "Try side streets around cameras" builds a detour
([chapter 3](03-cameras.md)), the drive starts with the detour points as `NavStop.silent` stops:
routed through by every reroute and recheck like any stop, but never spoken, never listed, never
a divider in the step list. Adding a stop mid-drive keeps them, and so does an edit in the stops
editor: the editor only ever sees the visible stops, so the silent ones still ahead are put back
in route order when you tap Done.

**Tap to add a stop** (Settings > Navigation, "Tap places while driving", off by default). Fuel,
food and charging places stay on the map during the drive, and a tap only offers the place: a
card shows what the stop adds, from one route through it fetched within `NAV_DETOUR_TIMEOUT_MS =
8_000` and compared with the drive's own live remaining time. A difference under 20 seconds or
over 3 hours shows nothing, rather than "+0 min" or a broken fetch's figure. The card's button is
the second tap, and the only thing that changes the drive. The card dismisses itself after 10 s,
or 25 s on a phone driven by keys, and a red "+" pin marks where the offer is.

### Pause

Pause holds the drive where it is. Precisely:

- the route, the stops and the figures stay exactly as they are;
- no engine update, so no off-route detection, no reroute, no arrival, no stop cues;
- no voice, no live-traffic recheck, no faster-route offer;
- the puck keeps following you, because it is drawn from the raw fix;
- the arrival clock keeps sliding, on a 30 second tick, because what the stop is costing you is
  the one number that should keep moving while you stand still;
- the bar says "Paused", and the line ahead turns from traffic blue to a muted lavender
  (`ROUTE_PAUSED_COLOR = #9C8AD6`), so the hold shows on the map and not only in the bar. A slate
  gray was tried first and vanished into the dark map's road fill.

**The color change repaints in place.** The line is drawn in pieces (the stretch ahead, a short
piece around the arrow that carries the moving cut, and the tail), and a color change has to
reach all of them, or only the piece around the arrow changes and the rest stays blue. It does so
through `paintReset`: new gradients on the pieces where they already are, nothing re-uploaded. The
same path handles the driven-trail setting and new traffic on the same line. It used to re-anchor
instead, uploading new pieces from new starting points; the new gradients applied at once while the
new geometry landed a few frames later, so for those frames the new colors were stretched over the
old, longer pieces, and a strip of blue or lavender showed behind the arrow on every pause and
resume. Re-anchoring is kept for a style reload, where the layers come back empty.

**Resuming** does what you would want after a stop: if the stop took you off the route, it
reroutes once from where you are; if you are still on the route, it carries on and speaks the
current instruction so the drive picks back up out loud.

**It also resumes itself** when you drive away, because forgetting to un-pause is the obvious way
this bites, and driving on behind a frozen banner is worse than never having paused. Two
conditions, in order:

```
autoResumeArmed      // set by the stop itself: a fix that is stationary, or off the route
AUTO_RESUME_HITS = 3 // then three consecutive fixes that are BOTH moving and back on the route
```

The arming step is not optional. Without it, pausing while still rolling down the route resumed
itself three fixes later, which is a pause button that does not pause (caught on device the day
it was built). With it, a pause taken at speed holds until you actually stop or leave the line.

**Where the button is.** Pause is on the map, on the notification (beside End) and on the Android
Auto action strip, because the phone is usually in a cradle and the decision to pull in is made
from behind the wheel.

On the map it sits in the bottom bar, in the slot to the right of the trip figures ("Pause button
on the navigation bar", on by default). That slot is otherwise empty, there only to balance the
End button on the left, and putting pause there leaves mute as a plain button with the other map
controls, so neither is behind a pop-out.

Anyone who has asked for buttons over gestures gets the step list button in the bar as well, and
the trip figures shrink to fit both. On a phone driven by keys pause goes back to the map
controls, where the key path is, and so does turning the setting off. There it shares one button with mute: the first tap slides mute out beside it for six seconds
and the second tap, on the same target, pauses; a long press mutes on the spot. The step list is
reachable in every layout, because the bar's chevron is a real button as well as a handle. And
while paused, one tap resumes wherever the control lives: the glyph already says what the tap
will do.

### Losing GPS

When the fixes stop while you are on the route and moving (a tunnel, a parking structure), the
drive keeps going on an estimate instead of freezing:

```
DR_START_MS     = 3_500    // feed gap before the estimate starts
DR_DECAY_S      = 60       // the assumed speed decays with this time constant
DR_MIN_SPEED    = 1.5      // m/s; below this it holds position, and never starts from a stop
DR_MAX_M        = 3_000    // cap on blind travel
NAV_STARVED_MS  = 10_000   // no guidance-quality fix this long: show the chip
```

The estimate feeds one synthetic fix a second along the route through the normal loop, so the
banner, the voice and the arrow keep working, and the first real fix takes over. Synthetic fixes
are never written into a recorded trip.

The "Searching for GPS" chip is pinned just **above the arrow**, because the arrow's dot is what
has gone gray; the road-name pill takes the space under it, so the two never meet. Before the
arrow has a screen position the chip falls back to bottom center.

### Standing still costs nothing

A drive left running in a parked car (at a long stop, or with the phone forgotten in the cradle)
used to redraw the map at 59 frames a second and hold about a whole core of a Pixel 4a, which is a
phone that runs hot. Nothing on screen was changing: the loop that moves the arrow and the camera
simply wrote both every frame. Now it writes only what changed.

- **The dot** (`writeMe`) is uploaded only when its point or bearing moved. Before the arrow
  engages, which in a parked car it never does, the same point went into the map every frame.
- **The camera** is written only when some part of it moved past a tolerance: about a centimeter
  of target, a hundredth of a degree of bearing or tilt, a few ten-thousandths of a zoom level,
  half a pixel of side inset.
- **The loop slows down** once more than 60 frames in a row have written nothing and the puck is
  under 0.3 m/s: it then waits `NAV_IDLE_TICK_MS = 120` between checks instead of running every
  frame. Any movement puts it straight back on every frame.

Measured on the 4a: 0 map frames and about 15% CPU parked with a route up, against 59 fps before,
and still 59 fps on a demo drive. A new per-frame write in that loop has to be gated the same way,
or this comes back.

### Resuming after the app was killed

If the process dies mid-drive, the next launch offers to resume for up to an hour
(`RESUME_MAX_AGE_MS`). Resume waits up to `RESUME_FRESH_FIX_WAIT_MS = 8_000` for a fix newer than
the one the app launched with, because that launch position is where the process died: routing
from it drew the new line back over the road driven since. Past the wait, the launch position is
what there is.

### The speed-limit badge and the speeding alert

The badge reads the road under the arrow from the downloaded region's route file: the fix is
snapped to the nearest road within `LIMIT_SNAP_M = 25` and its forward `maxspeed` is read, with no
limit and anything 150 km/h or over shown as blank. The lookup reruns only after about 18 m of
travel, off the main thread. An untagged stretch keeps the last known limit for up to
`SPEED_LIMIT_FORGET_M = 300`, so the badge does not flicker between tagged segments, but it does
not carry a 45 onto the residential street you turned onto. Where the region file has no limit
(or there is no region), the badge reads the hosted speed-limit tiles instead.

"Speeding alert" (Settings > Navigation, off by default) says "You're over the speed limit" once
you have been over the badge's limit for a while:

```
tolerance   = 5 km/h     // the badge's own red threshold, so the two never disagree
holdMs      = 4_000      // over the limit this long before it speaks
rearmMs     = 8_000      // back under this long before it can speak again
minGapMs    = 45_000     // never more often than this
```

### The step list and the road name

The step list opens on the step you are on. Steps already driven sit above it, grayed, one scroll
up, and the stops still ahead sit between the two. A divider row names each stop where its leg
begins, since a via route has no arrive or depart step of its own to show where one leg ends. The
list grows to just under the turn banner, and dragging the bar up opens it in one continuous
sheet: the bar's figures stay as the sheet's header.

The road you are on is the one entered by the last maneuver you passed, following any rename
along the way, with its route number first when it has one. "Current road name" (Settings >
Navigation) puts it above the bottom bar (the default), under the arrow, inside the bar's handle
row next to its chevron, or nowhere.

### The notification and the Android 16 live update

The drive's notification shows the current maneuver's glyph and carries Pause and End. On Android
16 and later it asks to be promoted to a **live update**: a chip in the status bar with the
distance to the next turn, and on the lock screen a bar whose scale is the route in meters, with
the nav puck as the tracker where the car is, the traffic spans colored like the route line and a
point for each stop still ahead.

Three things were needed for it to show up. The app must hold
`POST_PROMOTED_NOTIFICATIONS`, or the styled notification posts and the chip never appears. The
channel is DEFAULT importance with no sound and no vibration, the same as Google Maps' own: at LOW
the system files it as silent, and a phone that hides silent notifications on the lock screen hid
the live update exactly where it is useful. And it is a request: below Android 16, with no route,
or if the system declines, the notification is exactly what it was before.

## Limits

- **Distance left does not account for your detour.** It stays the route's remaining distance
  while paused, because Vela cannot know how far you are about to wander. The arrival time does
  move, since it is remaining drive time plus now.
- **Auto-resume needs speed from the fix.** A fix with no speed counts as standing still, so it
  arms the auto-resume but can never count toward the three moving fixes that fire it. On such a
  provider, resume by hand.
- **A long stop does not re-plan.** Resume gives you the same route, rerouted from where you are
  if you moved. If traffic changed while you sat, the next scheduled recheck is what notices.
- **Silent stops live only as long as the drive.** A reroute or a stops edit keeps them, but a
  drive resumed after the app was killed starts over with no stops at all, visible or silent.
- **The reroute's heading is not checked offline yet.** The downloaded-region router takes the
  departure heading too, but the angle convention was read from the library, not confirmed on a
  device.
- **Speed limits are only as good as OpenStreetMap.** Many roads carry no `maxspeed` tag, and
  there the badge is blank, which is the data rather than the lookup.
