# 4. Navigation

## What you see

A green banner with the next turn, an arrow that follows you, a bar with the time and distance
left, and a voice that tells you what to do. Behind it, every GPS fix runs the same loop: where
am I on this route, what is the next instruction, is anything worth saying, have I gone wrong.

And since 2026-09-18 there is a **Pause** button, which is the one thing Google Maps will not let
you do.

## Where the data comes from

- **The route** comes from the open OSRM router by default, with Google as the traffic-aware
  fallback and OsmAnd-format files on the phone when a region is downloaded. See the routing
  chapter (planned) for which engine answers when.
- **Your position** is the phone's own GPS. Nothing about it leaves the phone except the
  anonymous position a reroute or a live-traffic recheck has to carry.
- **The speed limit, lights, stop signs, crossings and cameras** on the drive come from the
  per-region bakes in [chapter 2](02-data-and-rebakes.md), all on the phone.

## How it is decided

### The per-fix loop

Every location update, in order: project the fix onto the route, advance the step, recompute the
remaining distance and time, emit whatever events that produced (speak, vibrate, arrived,
reroute), announce any stop that was just passed, and then consider a live-traffic recheck.

Anything that stops navigation stops all of it, because everything is downstream of that call.
That is the mechanism the pause uses.

### Off route, and rerouting

The off-route corridor is **accuracy-scaled and mode-relative**: it widens with the fix's own
reported accuracy, so a noisy fix in a city canyon does not read as a wrong turn, and walking and
cycling ride tighter than driving because the path is narrower. A few consecutive fixes outside
the corridor are what trigger a reroute, not one.

Reroutes are rate-limited so a bad patch of GPS cannot cause a storm:

```
REROUTE_COOLDOWN_MS      = 10_000    // minimum gap between adopted reroutes
REROUTE_FETCH_TIMEOUT_MS = 20_000    // one reroute fetch's deadline
```

A failed reroute clears the engine's off-route latch on the location thread, so the next few
deviated fixes retry naturally instead of the drive getting stuck insisting it is lost.

### Live traffic rechecks and faster routes

While driving, Vela re-asks for the route periodically so the arrival time tracks reality:

```
RECHECK_INTERVAL_MS           = 120_000   // every ~2 minutes
DEGRADED_RECHECK_INTERVAL_MS  =  20_000   // faster while the route is degraded
DEGRADED_FAST_TRIES           = 6         // ~2 minutes of fast healing, then back to normal
MIN_RECHECK_DISTANCE_M        = 1_500     // stop bothering near the destination
FASTER_THRESHOLD_S            = 90        // only offer a faster route that saves real time
SAME_COURSE_M                 = 250       // a candidate within this of the current line is the SAME route
```

That last one matters: when the candidate is the same course, its fresh ETA recalibrates the
arrival time you are shown rather than being offered as an alternative. A candidate that is a
genuinely different course and saves more than 90 seconds is offered, not taken.

### Pause

Pause holds the drive where it is. Precisely:

- the route, the stops and the figures stay exactly as they are;
- no engine update, so no off-route detection, no reroute, no arrival, no stop cues;
- no voice, no live-traffic recheck, no faster-route offer;
- the puck keeps following you, because it is drawn from the raw fix;
- the arrival clock keeps sliding, on a 30 second tick, because what the stop is costing you is
  the one number that should keep moving while you stand still.

**Resuming** does what you would want after a stop: if the stop took you off the route, it
reroutes once from where you are; if you are still on the route, it carries on and speaks the
current instruction so the drive picks back up out loud.

**It also resumes itself** when you drive away, because forgetting to un-pause is the obvious way
this bites, and driving on behind a frozen banner is worse than never having paused. Two
conditions, in order:

```
autoResumeArmed    // set by the stop itself: a fix that is stationary, or off the route
AUTO_RESUME_HITS = 3   // then three consecutive fixes that are BOTH moving and back on the route
```

The arming step is not optional. Without it, pausing while still rolling down the route resumed
itself three fixes later, which is a pause button that does not pause (caught on device the day
it was built). With it, a pause taken at speed holds until you actually stop or leave the line.

Pause is reachable from the map's nav controls and from the notification, because the phone is
usually in a cradle and the decision to pull in is made from behind the wheel.

On the map it sits in the bottom bar, in the slot to the right of the trip figures. That slot
is otherwise empty, there only to balance the End button on the left, and putting pause there
leaves mute as a plain button with the other map controls, so neither is behind a pop-out.

The step list button claims the same slot for anyone who has asked for buttons over gestures, and
on a phone driven by keys. Pause then goes back to the map controls, sharing one button with mute:
the first tap slides mute out beside it for six seconds and the second tap, on the same target,
pauses; a long press mutes on the spot. That is also what the setting restores for anyone who
prefers it. The step list is reachable in both layouts, because the bar's chevron is a real button
as well as a handle. And while paused, one tap resumes wherever the control lives: the glyph
already says what the tap will do.

## Limits

- **Distance left does not account for your detour.** It stays the route's remaining distance
  while paused, because Vela cannot know how far you are about to wander. The arrival time does
  move, since it is remaining drive time plus now.
- **Auto-resume needs speed from the fix.** A provider that reports no speed leaves the arming
  step to the off-route condition alone.
- **Android Auto has no pause control yet**; the phone and the notification do.
- **A long stop does not re-plan.** Resume gives you the same route, rerouted from where you are
  if you moved. If traffic changed while you sat, the next scheduled recheck is what notices.
