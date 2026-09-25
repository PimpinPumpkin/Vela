# 11. The drive's chrome

## What you see

Everything drawn on and around the map while a route is being driven, as opposed to the loop
that decides what to say ([chapter 4](04-navigation.md)):

- **street callouts**, small white bubbles naming the streets you are about to cross, placed a
  short way up each street from the route and let go once they have ridden down to the bottom
  bar;
- a **green exit callout** with the number of the exit you are about to take, sitting on the ramp;
- an optional **road-ahead bar** down the left edge: the next few kilometers of your route as a
  strip, with traffic painted on it and badges for what is coming up;
- **stop signs and lights** on the road, with the stop signs that belong to the street crossing
  yours left off;
- the **route line** itself: color ahead of the arrow, gray or nothing behind it, lavender while
  the drive is paused;
- a **map that stops drawing** when the car is parked with a route up;
- the **stops controls**: tap a place to offer it as a stop, "Remove stop" for a place already on
  the trip, and "Edit route" and "Remove next" at the top of the step list;
- the **buttons**: End, the trip figures and pause in the bottom bar, and a column of round
  buttons up the right edge.

## Where the data comes from

- **Street names for the callouts** come from the basemap's own `transportation_name` features
  (OpenStreetMap through OpenMapTiles), read out of the tiles already loaded on the phone. Nothing
  is fetched for them. For a reader of a Latin-script language the bubble shows the tile's
  `name:en`, else `name:latin`, else the local name, the same rule the browse map's street labels
  use.
- **The exit number** is read out of the maneuver's own instruction text, which the router wrote
  in the app language.
- **The road-ahead bar** draws only what the drive already has: the congestion spans Google gave
  for the route ([chapter 5](05-routing.md)), and the lights, stop signs, crossings, humps and
  cameras already fetched along the route for the map ([chapter 2](02-data-and-rebakes.md),
  [chapter 3](03-cameras.md)).
- **Stop signs and lights** come from the per-region road-features bake, which also records the
  direction of the road each sign stands on; where no region exists, from Overpass.
- **The detour figure** on a tap-to-stop offer is one extra route request through the tapped
  place, through the same routers as any other route.

## How it is decided

### The street callouts

**Which streets get one.** A pass reads the loaded road-name features of six classes and keeps
the ones that meet the route ahead:

```
major tier   = motorway, trunk, primary, secondary
minor tier   = tertiary, minor
window       = 200 m behind to 2,200 m past the start of the current 400 m quantum
crossing     = the street's line properly crosses the window
T-junction   = else, either END of the street within 25 m of the window
next turns   = the next two maneuvers' roads use 60 m instead of 25 m
cap          = 60 streets per pass
```

The T-junction rule exists because on an arterial most side streets end at your road instead of
crossing it: the strict crossing test read their shared endpoint as touching nothing, and the whole
layer went mute on exactly the roads where the names matter. Only the two ends count, never every
vertex, or a street running parallel would name itself. The wider radius for the next two turns is
there because a turn target often meets the route at a shared vertex, which a crossing test can
miss, and that is the one name you most need.

The road you are driving is never called out: every road already entered by a maneuver up to the
current step is excluded, by name, by route number and by the bare digits of the route number
(the tile says "5" where the router says "I 5"). The exclusion is per step on purpose. Excluding
the whole route hid the road you are about to turn onto.

**When the pass runs.** Once per 400 m quantum of progress, or when the next two turns' roads
change, and never on a short timer. The first version ran every 4 seconds, and each run pulls
every loaded road-name feature onto the main thread and re-places a symbol layer, so it was
itself a periodic frame hitch. A pass that finds no road tiles at all does not count and tries
again 2 seconds later. One that finds roads but places nothing retries too, and gives the quantum
up after four more tries (about 10 seconds), because a cross street's tile often lands a beat
after the rest.

**Where the bubble goes.** Each callout is a point Vela computes, not the basemap's own label.
Placing on the basemap's road-name line put a bubble at the middle of that street's piece of
tile, often a block from the route. `crossLabelPoint` finds where the street meets the window
(the first proper crossing in route order, else the touching end), then walks up the street
from there:

```
NAV_XLABEL_OFFSET_M     = 35      // meters up the street from the crossing
NAV_XLABEL_OFFSETS      = 1.0, 1.4, 1.8, 2.4, 3.0   // x the offset: 35, 49, 63, 84, 105 m
NAV_XLABEL_CLEAR_M      = 44      // stop at the first rung with this much room from the route
NAV_XLABEL_MIN_CLEAR_M  = 26      // below this, no callout at all
```

Each rung is tried on both sides of the route and the side with more room wins. The first rung
that clears 44 m ends the search; if none does, the roomiest candidate is kept, and if even that
is under 26 m the street gets no bubble. A chip over the road you are driving is worse than a
missing name.

Clearance is measured to the bubble's **anchor**, which is the tip of its tail. The chip body
sits above that point and is much wider, so the gap you see is always smaller than the number,
and smaller still with the camera tilted. At 30 m measured, chips still drew over the blue line on
a real drive. The rungs are close together because the room a step buys depends on the angle the
street crosses at, and a coarse ladder pushed a perpendicular street's bubble a whole block out
to win a few meters. This is a walk-back, not a reset: the callouts were moved close to the route
on purpose when line placement had them a block away.

**Tiers and zooms.** The nav camera zooms with speed, from 18 crawling to 15.5 at highway speed,
so the two tiers are gated on zoom:

```
major tier   drawn from z14
minor tier   armed at z15, fades in over z15.2 to 15.7
textPadding  26       // wide collision padding: fewer, sparser bubbles
```

A hard cut made the whole minor tier pop in and out as speed crossed the line; the fade spreads
that over half a zoom level. The minor tier used to start at z16, which is where the camera sits
only at town speeds, so cross streets vanished on any faster road. While the callouts are up, the
basemap's own line-following street names are hidden, since both drew the same name twice.

### How the callouts leave

As of 2026-09-25 a passed callout **rides down the screen with the map** instead of disappearing
at its crossing. Each callout carries `atM`, its distance along the route. The main layers show
every callout above one threshold, and a separate loop moves that threshold:

```
NAV_XLABEL_TICK_MS         = 80       // how often passed callouts are checked
margin                     = 28 dp    // above the bottom bar's measured top edge
NAV_XLABEL_DROP_BEHIND_M   = 600      // backstop: this far behind, it goes regardless
NAV_XLABEL_HANDOFF_MS      = 250      // fade layer filled this long before the main layer lets go
NAV_XLABEL_FADE_MS         = 1_200    // fade to nothing
```

Every 80 ms the callouts the puck has already passed (usually none to two) are projected to the
screen. One is let go when its anchor reaches 28 dp above the top of the bottom bar, which the
bar measures for itself and hands down as `navBarTopPx` (the bottom of the map when it has not
been measured yet), or when it goes more than 28 dp past either side of the screen. Everything at
or behind the newest let-go callout goes with it, since the filter is a single threshold. The old
rule dropped each bubble 12 m before its own crossing, on the 2 second label loop, so bubbles
vanished early and several at once.

**The fade is its own layer.** A let-go callout is copied onto `NAV_ROADLABEL_FADE_LAYER`, a
small source with collision off (a bubble on its way out must not push away the ones ahead), and
that layer's opacity falls from 1 to 0 over 1.2 seconds. It is one constant opacity for the whole
layer, stepped by the tick. The obvious alternative, an opacity on the main layers computed from
each callout's distance, is a data-driven paint property, and changing one re-runs the layer's
symbol placement just as changing its filter does; every tick would have paid for a full
placement. Even the filter is moved as rarely as possible: re-filtering every 25 m cost a 126 ms
worst-case main-thread message on a Pixel 4a, against 37 ms when it moves only as callouts are let
go.

**The order of the hand-off.** The fade layer is filled first, and the main layers' threshold
moves 250 ms later, so the two draw the same bubble for a beat. The first cut did it the other
way round: the main layer dropped the bubble, and the fade layer drew it again a few frames later
(its GeoJSON upload is parsed off the main thread). The bubble blinked out and back, which read
as a flicker.

A new set from the next quantum recomputes every `atM`, so a street already let go can come back
a meter above the threshold. The threshold is lifted over any callout whose street was let go
within 60 m of it, or passed bubbles reappeared for a beat at every re-upload. A minor-tier
callout is only handed to the fade layer at zoom 15.5 or closer, where that tier is showing.

### The exit callout

When the step being guided is a ramp, fork or keep, and its instruction names a numbered exit,
the map draws the number in a green twin of the street bubble. `ExitLabel.of` finds the number
after a word from a table covering the app languages ("exit", "sortie", "Ausfahrt", "salida",
"uscita", "afrit", "zjazd", "kijárat" and others, including Cyrillic and Hebrew), or before it
for Chinese and Japanese ("12B出口"). A bare number with no exit word never counts, because in an
instruction it is usually a road's route number. The label is at most 12 characters, uppercased,
with spaces around a dash or slash removed ("12A-B").

The bubble sits `EXIT_CALLOUT_AHEAD_M = 70` m down the route past the maneuver point, on the
ramp rather than the freeway beside it (the maneuver point is where the ramp leaves). It is the
one callout that never yields in a collision: it is the next thing you must do. The exits you
drive past keep the basemap's own green exit shields.

### The road-ahead bar

"Road ahead bar" (Settings > Navigation, off by default) draws the road ahead as a vertical strip
on the left edge, opposite the buttons:

```
WINDOW_M          = 5_000    // the bar shows the next 5 km, or the rest of the trip if less
MIN_REMAINING_M   = 400      // less than this left: no bar, the banner covers it
PIN_MERGE_M       = 60       // marks closer than this along the route become one
corridor          = 40 m     // a mark must sit this close to the line to count
```

**The window.** The first cut scaled the strip to the whole remaining trip. On a long interstate demo
drive everything within the next few miles landed in the bottom pixel and the bar read as a gray
stick. It now shows the next 5 km at a readable scale, with the span written at the top ("5 km"),
and that label is blank once the window reaches the destination, since the top is then the end.

**The marks.** Congestion from the route's traffic spans is painted on the track, amber for
moderate, red for heavy and dark red for severe, trimmed to the window. Lights and stop signs are
small dots on the track, too frequent in a town to badge. Plate cameras, speed cameras, level
crossings and speed humps are round badges in their own lane to the right of the track, so a
badge never covers the congestion under it. A plate camera only counts when it faces the route
(the direction rule in [chapter 3](03-cameras.md)); a speed camera carries no direction, so being
within the corridor is enough.

**Which mark wins a merge.** When marks fall within 60 m of each other, the one that says the
most is kept: camera, then level crossing, then speed hump, then stop sign, then light. Plate
cameras hang on signal masts, and keeping the first mark by distance hid every one of them behind
the light's dot.

**What it will not show.** Anything the map does not know. There are no incidents on it, because
every keyless incident source was probed and is dead, and a confident bar with nothing real
behind it would be worse than no bar.

**Why portrait only.** In landscape the route chrome is already a left column taking half the
screen, and a permanent strip there would crowd it further. It is also never drawn in
picture-in-picture. The marks are projected onto the route once per route (keyed on the route and
the identity of the mark lists), off the main thread; each nav tick only rebuilds the window.

### Stop signs and lights: yours or theirs

OpenStreetMap maps one stop sign per approach to a junction. A corridor around your route
therefore collects the signs on the street that enters your road, which you never stop for. The
road-features bake records, for each sign, the direction of the road it stands on (0 to 179
degrees, undirected), and the drive keeps a stop sign only when that road lines up with yours:

```
RouteProjection.alignedWithRoad(routeBearing, roadDeg, toleranceDeg = 40)
```

The route's bearing is taken where the sign projects onto the route (within 120 m). A sign with
no recorded bearing is kept: a region baked before the column existed, and the live Overpass
fallback, behave exactly as they did. Lights, level crossings and speed humps are not gated. A
signal controls the whole junction, so a light mapped on the crossing street still marks a light
you will meet. After the gate, same-kind nodes within `CONTROLS_CLUSTER_M = 45` m merge into one,
and the drive keeps at most `CONTROLS_ROUTE_CAP = 800`, nearest the start.

**The distance gate that was reverted.** The first attempt kept a stop sign only if it sat within
11 m of the driven line. On a real drive it removed nearly every sign, because a clustered control
is drawn at the center of the junction, not in your lane, so the ones you do stop for were as far
from the line as the ones you do not. It was reverted the same day. A better answer than the
bearing would read each node's own intent (its `direction` tag, or which way it belongs to), which
needs carrying through the bake and the Overpass parse.

During a drive the lights and signs draw from z15.4, just under the camera's 15.5 floor, and are
drawn above the route line's cut piece: anchored on the ahead line alone, the ones nearest the
driver were exactly the ones the blue line painted over.

### The route line during a drive

The line you drive is several pieces, and only one of them changes every frame:

```
full line     the whole route in traversed gray, uploaded once per route
ahead window  NAV_WINDOW_M = 3_000 of road ahead, re-anchored within NAV_WINDOW_SLACK_M = 500 of its end
far tail      the rest of the route past the window, re-uploaded with the window
cut piece     NAV_CUT_M = 400, starting NAV_CUT_BACK_M = 20 behind the arrow,
              slid forward when the arrow is within NAV_CUT_SLACK_M = 100 of its end
```

**The cut is paint, not geometry.** Where gray meets color under the arrow is drawn by the cut
piece's `line-gradient`. MapLibre bakes a gradient into 256 texels, which over 400 m is 1.6 m
each, a few pixels. Per frame (whenever progress moves more than half a pixel), only that
gradient changes; the piece's geometry slides about every 300 m. Moving geometry for the cut, even
on a 150 ms timer, dropped a map frame at a steady 6.7 Hz for the whole drive, a vibration of the
entire map that was first reported as the arrow jittering. A whole-route gradient is not the
answer either: 256 texels over a long route smears the cut into a ramp many meters long.

**The trail.** "Road behind you" (Settings > Navigation, off by default) keeps the driven part
gray. Off, the full gray line is hidden, the ahead window's gradient is transparent up to a texel
before the cut piece's end, and the cut piece paints nothing before the arrow and color after it,
so the only thing drawn under the piece is the piece. Doing the cut on the ahead window's own
gradient instead gave 12 m texels, and the line disappeared in 12 m chunks.

**Repaint, never re-anchor.** A change of color (pausing turns the line
`ROUTE_PAUSED_COLOR = #9C8AD6`, a muted lavender, and resuming turns it back), a flip of the trail
setting, or new traffic spans on the same line all set `paintReset`, which repaints every piece
where it lies: the window, the cut piece and the far tail. Until 2026-09-25 they set the
style-reload flag instead, which uploaded new cut and window pieces from new anchors. A paint
change applies at once while a GeoJSON upload lands a few frames later, so for those frames the
new fractions were painted on the old, longer pieces, and a strip of blue or lavender showed
behind the arrow on every pause and resume. Only a style reload re-anchors now. The lavender was
chosen over a slate gray, which vanished into the dark map's road fill.

Walking and cycling lines are dashed, and a dashed MapLibre line takes no gradient, so they keep
their plain style with no moving cut.

### A parked drive draws nothing

A route left up in a parked car used to redraw the map at 59 frames a second and hold about 93%
of a core on a Pixel 4a, and the phone ran hot. Three gates fixed it on 2026-09-25:

```
writeMe             // the location dot is uploaded only when it moved more than 1e-8 degrees
                    // or turned more than 0.1 degree; before the arrow engages (a parked car
                    // never engages) it used to go up every frame with the same point
camera tolerance    // the follow camera is written only when target moves more than 1e-7 deg,
                    // bearing 0.01 deg, zoom 0.0005, tilt 0.01, top padding 0.0005 of the
                    // height, or the landscape inset 0.5 px
idle                // after 60 frames with nothing written and the puck under 0.3 m/s,
                    // the loop waits NAV_IDLE_TICK_MS = 120 between checks instead of every frame
```

Any movement brings the loop straight back to every frame. Measured on the 4a: 0 map frames and
about 15% CPU parked, and still 59 fps on a demo drive. Any new per-frame write in that loop has
to be change-gated the same way, or this comes back.

### Stops during the drive

**Tap to add a stop** ("Tap places while driving (experiment)", Settings > Navigation, off by
default). The drive normally strips the places layer down to fuel icons, for the frame rate and
for a map that reads at a glance. With this on, the places layer shows the fuel group (gas and charging) and the food
group, and only the best two per ~100 m block (`NAV_DRIVE_BLOCK_TOP = 2`), because every icon is
placement work on a moving camera and the 4a dropped frames with a wider set. Turning it on also
turns on places, and turning it off undoes that only if it was the one that turned them on.

A tap only offers the place. The card shows the category, the distance ahead and, once it lands,
what the stop adds: one route through the place is fetched within `NAV_DETOUR_TIMEOUT_MS = 8_000`,
with the place first among the stops because that is where adding it puts it, and
`DetourEstimate.minutesAdded` compares it with the drive's own live remaining time. Under
`MIN_SHOW_S = 20` seconds or over `MAX_PLAUSIBLE_S = 3` hours apart, the card shows no figure
rather than "+0 min" or a broken fetch's number. The card's Add button is the second tap and the
only thing that changes the drive, so a stray touch at speed cannot re-route anyone. A ring around
the close button runs down over 10 seconds, or 25 on a phone driven by keys, and dismisses the
card at zero; it restarts on every offer (`navTapOfferTick`), including a second tap on the same
place. A red "+" pin marks the offered place on the map.

**Remove stop.** When the tapped place lies within `NAV_STOP_MATCH_M = 60` m of a stop still
ahead, the card grows a "Remove stop" button beside Add. It removes the nearest-ahead occurrence
of that stop (the same place added twice keeps its later visit) in one replan.

**Edit route and Remove next.** The top of the step list is a stops row on every drive. With no
stops it reads "Edit route" and "Add a stop along the way", so the stops editor is reachable
before a stop exists. With stops it lists them, and carries "Remove next", which asks first
("Remove <stop> from this drive?") and then drops the next stop with one replan, the same path as
the editor's Done. Silent detour stops ([chapter 4](04-navigation.md)) are never listed and never
removed by it.

### The buttons

The bottom bar is End on the left, the trip figures in the middle and, by default, pause on the
right ("Pause button on the navigation bar"); with "Prefer buttons over swipes" or on a keypad
phone the step-list button shares that slot and the figures shrink to fit. The right edge carries
a column of 56 dp buttons, 16 dp from the edge: Re-center (only while the camera is detached,
a step is being previewed, or you pinched the zoom), the route overview, mute (or the combined
pause-and-mute button when pause is not in the bar), and search along the route. The column is
aligned to its right edge so the combined button's pop-out grows left without sliding the others.
The road name sits above the bar, inside it, under the arrow or nowhere ("Current road name").
[Chapter 4](04-navigation.md) has the reasons for each placement.

## Limits

- **Callouts only name what the tiles have loaded.** A cross street whose tile lands more than
  about 10 seconds after the rest waits for the next 400 m, and a dense grid can hit the 60-street cap
  on a pass.
- **The clearance is to a point, not to the chip.** A long name on a tilted camera can still
  touch the line where a street crosses at a shallow angle; the fix would be measuring the chip's
  box on screen, which costs a projection per callout per pass.
- **The exit callout needs a numbered exit in the words.** A ramp whose instruction names no
  exit, or phrases it in a way the word table does not know, gets no bubble.
- **Offline, a region needs its road-features file already on the phone.** The file comes down
  the first time a drive or a street-zoom view needs it while online, not with the region
  download, so offline in a region never visited that way the drive shows no lights or signs at
  all ([chapter 8](08-offline.md#limits)).
- **The stop-sign gate needs a baked bearing.** Regions baked before the bearing column, and every
  sign from the Overpass fallback, keep every sign. Lights are never gated. Signs on a road that
  bends at the junction can fall outside the 40 degrees.
- **The road-ahead bar knows no incidents** and is portrait only. It can only show furniture that
  OpenStreetMap maps, which is uneven: many places map lights far more consistently than stop
  signs.
- **The tap-to-stop figure compares two different sources.** The baseline is the drive's own
  calibrated remaining time and the candidate is a fresh fetch, so the minutes are an estimate,
  hidden entirely when the two are too close to mean anything.
- **Walking and cycling lines have no moving cut**, because a dashed line cannot carry a
  gradient.
