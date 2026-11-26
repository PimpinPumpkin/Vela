# 11. The drive's chrome

## What you see

This chapter covers what is drawn on and around the map during a drive. What the drive says, and
when it reroutes, is [chapter 4](04-navigation.md).

- Around the map: a turn banner on top, a bottom bar with End, the trip figures and Pause, the
  step list behind it, round buttons on the right edge, the compass, a speed box, the current
  road's name, and an optional road-ahead bar on the left edge.
- On the map: the route line, the arrow, white callouts for the cross streets ahead, a blue one
  on the street you turn onto, a green one with your exit number, the lights and stop signs
  on your route, and the route number of the road you are on as a shield on the line.
- Outside the app: a picture-in-picture mini map, the notification, and on Android 16 a live
  update.

## Where the data comes from

- Callout names are read from the basemap tiles already loaded (`transportation_name`). Nothing
  is fetched. A Latin-script app language gets `name:en`, else `name:latin`, else the local name,
  or whatever Settings > Map > Names on the map chose instead.
- The road-ahead bar draws Google's congestion spans for the route ([chapter 5](05-routing.md))
  and the road features and cameras already fetched along it
  ([chapter 2](02-data-and-rebakes.md), [chapter 3](03-cameras.md)).
- Lights and stop signs come from the per-region road-features file, which records the
  orientation of the road each stands on, or from Overpass, without orientation, where no region
  is baked.
- The time a tapped stop adds is one extra route request. Everything else shows the drive's own
  state ([chapter 4](04-navigation.md)).

## How it is decided

Every rule and constant is in [SPEC section 4](../../SPEC.md) (4.7 to 4.11, the map) and
[SPEC section 10](../../SPEC.md) (10.2, the chrome).

### The turn banner

`ManeuverBanner` (`ui/nav/NavOverlays.kt`) shows the maneuver's glyph, its distance and the
instruction, and pulls up to three shields out of the step: the exit tab, the maneuver's route
number and route numbers in the text. Lane arrows and the "Then" tab appear within
`max(800 m, speed x 30 s)` of the turn, so a highway exit shows its lanes about a kilometer out.
Off route the headline reads "Rerouting". In landscape the banner and the bar are a column on
the left.

The card leads with where you are going and is laid out the way Google's is: a large arrow on
the left, then the distance, the exit tab and shields on a row of their own, and the place in
large type. A turn shows the road ("W Covell Blvd", the "Blvd" smaller) and one small line for
what follows it ("toward Downtown"). "Turn right onto" is not shown; the arrow says it. An exit
or a ramp shows the exit tab ("4A" in an outlined box with an arrow to the exit's side) and the
route shields ("5 NORTH"), a street on the sign beside them ("519B J St") and the sign's towns
in large type, joined as Google joins them ("Sacramento / Reno"). A town keeps its whole name
("South Lake Tahoe"). "Take exit 4A toward I 5 S" is not repeated as words, since the chips say
it. A ramp toward both directions of one route gets one shield with no direction. A historic
route such as Historic US 40 gets no shield. Lanes sit on a darker strip across the card's foot.
The lane arrows are Google's, measured off its card. Every arrow a lane allows is drawn whole from
the lane's center and they lie over one another, the lit one on top, so a straight-or-right lane
is a straight arrow with the right arrow's bend coming off its shaft, and a turn looks the same in
every lane. A short dash under the arrows parts the lanes.
A screen reader hears the whole instruction from the arrow. A roundabout keeps "take the 2nd
exit onto" above the road. The road is found by its own text in any language; the sign
destinations need the language's "toward" marker (`TurnCardText`, `NavStrings.signDestination`),
which English and Romanian have. Other languages, the first and last steps, and a step neither
rule fits keep the distance row and the sentence.

A ramp, a fork or a keep shows the slight-left or slight-right arrow, the way you go and nothing
else. A picture with both branches looked like a road sign offering either one. The step list,
the mini map, the notification and Android Auto take their glyphs from the same table
(`maneuverGlyph`).

With large text the card stays within a third of the screen in portrait. Past that its text steps
down, never below the default size, and tries one step back up at the next maneuver. The distance
stays on one line and shrinks to fit, the instruction stops at three lines, and the route shields
grow with the text so a three-digit number fits its badge.

### The bottom bar and the step list

The bar is white in the light theme, near black in the dark ones, and the theme's surface with
Material You colors on (`navBarLook()`). Its right slot holds Pause by default ("Pause button on
the navigation bar"), or the step-list button with "Prefer buttons over swipes" on or on a keypad
phone. With both wanted, the trip figures shrink to fit.

A swipe or a tap on the grab bar opens the step list on the current step, with the driven steps
grayed above it and a divider at each stop.

With a stop ahead the figures are the stop's, a third line names it ("To Davis Food Co-op",
cut short when long) and a fourth has the whole trip ("Trip 12 min · 6:12 PM"); the stops row at
the top of the step list has the trip in full ([chapter 4](04-navigation.md)).

### The buttons and the road name

The right edge has 56 dp buttons for the route overview, mute and search along the route, in the
bar's colors, or the wallpaper palette when it is really in use (`wallpaperColorsInUse()`). With
Pause out of the bar, mute and pause share one button (`NavHoldControls`): a tap slides the three
voice choices out for 6 seconds, a second tap pauses, and a long press steps the voice. Search
opens a page with a search
field, category tiles led by fuel and charging, and recent searches.

The compass shows for the whole drive, and a tap switches between heading-up and north-up.
"Keep north up" (Settings > Map) holds every drive north-up and flat, so the compass has nothing
to switch and fades at north as it does on the browse map. It also turns off two-finger rotation
everywhere, and the map goes back to north at the next camera rest if anything else turns it.
North-up, the arrow sits in the middle of the map between the turn card and the bar, because the
road ahead can run toward any edge of the screen.

"Current road name" (Settings > Navigation) puts the name under the arrow (the default), above
the bar, inside the bar, or nowhere. Under the arrow it stays clear of the speed readout, so a
long name beside a wide readout sits to its right instead of behind it. Away from the car (a
pan, a pinch, a step preview) the name
hides and a Re-center pill takes the speed box's place. It shows the road's own name where it
has one, as the street signs do: "W Covell Blvd", with the route number on the turn card's chip.
An Interstate, a named freeway ("Capital City Freeway" reads "US 50"), or a road whose name is
only its number in words, shows the number. An expressway or a parkway keeps its name, because
many are known by nothing else; "Road numbers first" under Navigation puts the number on every
road that has one, for the dedication names ("Pearl Harbor Memorial Expressway") no sign shows,
and the directions of the next route say the number too. Street words are abbreviated the way a
sign does it, on the pill, on the turn card and its "then" line and on the car screen; the voice
says them in full.

A number gets a compass letter when the signs on the way onto the road gave one: "I 80 E" after
a ramp signed "I 80 East". The letter comes only from sign text in the route steps. A turn onto
a numbered road at an ordinary junction has no sign in the data, so the number shows alone. It
is not worked out from the direction of travel, since Interstate 80 East runs north along the
bay. The direction of travel only chooses between the two when one sign names both.

### Street callouts

A pass reads the loaded road names and keeps the streets that meet the route from 200 m behind to
2,200 m ahead: those that cross it and those that end within 25 m of it, since most side streets
end at an arterial. The road you are on is left out per step, so the road you turn onto next is
still named.

The pass runs once per 400 m of progress, or when the next two turns change. Each run pulls every
loaded road name onto the main thread, so it never runs on a short timer. A pass that places
nothing retries every 2 seconds, four more times, for tiles that land late.

Each callout is a point Vela places (`crossLabelPoint`), because the basemap's own label sits
mid-tile, often a block from the route. It goes `NAV_XLABEL_OFFSET_M = 35` m up the street from
the crossing, or further out, up to 105 m, until its anchor is `NAV_XLABEL_CLEAR_M = 44` m clear
of the route. A spot within 30 m of a drawn light, stop sign or camera is skipped. With less
than `NAV_XLABEL_MIN_CLEAR_M = 26` m of room the street gets no callout.

Main roads draw from z14. Tertiary and minor streets fade in over z15.2 to 15.7, so they do not
pop in and out as speed moves the zoom.

A passed callout rides down the screen until its anchor comes within 28 dp of the bar's top edge
or leaves a side. It then fades over 1.2 s on its own layer (`NAV_ROADLABEL_FADE_LAYER`), which
has one opacity for the whole layer. An opacity per callout would be data-driven and re-run
symbol placement on every tick.

"Passed" is a distance along the route line, so it starts over whenever the line changes: a
reroute, an added stop, a faster route you accept. When it did not, the white callouts stayed
hidden after the change for as far as you had already driven.

The street the next turn enters gets a blue callout with its tail on the corner. The bubble reaches
away from the turn, right of the corner for a left and left of it for a right, so it never sits on
the turn arrow. A light, stop sign or camera on the corner moves the tail along the cross street,
away from the turn. A ramp, fork or keep whose
instruction names a numbered exit gets a green one `EXIT_CALLOUT_AHEAD_M = 70` m past the
maneuver point, on the ramp. `ExitLabel.of` finds the number beside an exit word from a
per-language table. A bare number is usually a route number and never counts. These two never
yield in a collision. The white ones yield to cameras, lights, stop signs and place icons.

### The road-ahead bar

"Road ahead bar" (Settings > Navigation, off by default) draws the next `WINDOW_M = 5_000` m of
the route as a strip on the left edge (`RouteBarStrip`). Scaled to a whole long trip, everything
nearby lands in one pixel. With under 400 m left there is no bar.

- Congestion is painted on the track in amber, red and dark red.
- Lights and stop signs sit on the track as the map's own glyphs, the light's housing and the
  stop sign's octagon, 16 dp tall. Cameras, level crossings and speed humps are badges beside
  it, so a badge never covers congestion.
- A mark must lie within 40 m of the line, and a plate camera must face the route
  ([chapter 3](03-cameras.md)).
- Marks within `PIN_MERGE_M = 60` m merge, and the kind that says most wins: camera, crossing,
  hump, stop sign, light. A plate camera on a signal mast would otherwise hide behind the light.

The bar is portrait only, since in landscape the left side is the chrome column.

### Whose stop sign it is

OpenStreetMap maps one stop sign per approach, and the fetch takes a 120 m corridor around the
route, so it collects the cross streets' signs and the next street's. A drive draws only the
controls on its route, tested before nodes within `CONTROLS_CLUSTER_M = 45` m merge, because a
merged node sits in the middle of the junction.

- A stop sign counts within `STOP_ON_ROUTE_M = 20` m of the line when its road runs within 40
  degrees of the route's bearing there (`RouteProjection.stopIsOnRoute`). Orientation alone
  keeps the parallel street's sign. Distance alone keeps the one facing the cross street. On a
  Davis route the route's own signs sat 0 to 8 m from the line and the cross streets' 12 to 25 m.
- A sign whose road is not known counts within `STOP_NO_BEARING_M = 10` m, between those two
  bands. That covers the live Overpass path, which carries no road, and the bake's empty cases.
- The bake reads a sign's road from drivable ways only. A crosswalk through the stop line runs
  along the main road and would make the side street's sign look like yours. A sign on a junction
  node holds the most minor road through it. An all-way stop (`stop=all`), or a sign where two
  equal streets cross, gets no road.
- A light, level crossing or hump counts within `SIGNAL_ON_ROUTE_M = 12` m. Orientation is not
  asked, because a node in the middle of a junction is on two roads.

They draw from z15.4, just under the camera's 15.5 floor, above the route line.

Turn arrows (Settings > Navigation, on by default) are short white arrows with a thin dark
indigo edge drawn inside the blue line through each turn ahead, 30 m before the maneuver point to
30 m past the corner, from z14, the way Google draws them. They are cut from the line the map
draws, so they sit on it, and follow a wavy road within half a meter. A corner is rounded into an
arc of up to 5 m radius, so the arrow curves through the turn the way the car does. The arrow is
about three quarters of the line's width, with a round tail and a plain triangle head whose base
runs into the shaft. The corner is where the line turns, which for a fork or a ramp is past the
point the route puts the maneuver at, so the arrow runs on through the split. A continue, a merge
and a roundabout's entry get none.

The part of an arrow the car has driven over is not drawn. As the car reaches an arrow its tail
shortens under the car every frame, through the corner and out the other side, until the head
goes too; nothing of the arrow is ever behind the car. The two arrows the car can be on (the one
it is driving through and the next) each have a source of their own, and the cut is a paint
change on them, never new geometry (SPEC 4.7, Turn arrows).

### The route line during a drive

The line is as wide as a two-lane street (`ROUTE_REAL_M = 8.5` m), with a dark blue outline of
`ROUTE_OUTLINE_DP = 1.25` dp a side. Where two roads' centers meet at a junction the line turns
on a curve that starts 10 m before the corner and ends 10 m after it, a right angle passing
3.5 m inside the corner, so the arrow on the line goes round the turn the way the car does
(SPEC 4.8, The geometry drawn). It is split into pieces so that no geometry moves per frame:

```
full line     the whole route in gray, uploaded once per route
ahead window  NAV_WINDOW_M = 3_000 m of road ahead
far tail      the rest of the route past the window
cut piece     NAV_CUT_M = 400 m under the arrow, slid forward about every 300 m
```

The edge between driven and ahead is paint: the cut piece's `line-gradient`, which MapLibre bakes
into 256 texels, 1.6 m each over 400 m. Per frame only that gradient changes. Moving geometry for
the cut, even every 150 ms, drops a map frame each time and the whole map vibrates. A gradient
over the whole route smears the cut across `routeLength / 256` meters.

- A slide uploads into a hidden second copy of the piece and swaps once the map's tiles report
  it, so a new gradient is never painted on old geometry. A cut piece off screen, or under
  `ROUTE_PENDING_MIN_DP` (4 dp) long on it, swaps at once: zoomed far out it is in no tile, and
  each check is a blocking call to the render thread (SPEC 4.8).
- A change of color, of the trail setting or of traffic repaints the pieces where they lie
  (`paintReset`). Re-anchoring them showed a strip of the wrong color behind the arrow, because
  paint lands at once and geometry a few frames later.
- "Road behind you" (Settings > Navigation, off by default) keeps the driven part gray. Off,
  nothing is drawn behind the arrow.
- A paused drive draws the line ahead in `ROUTE_PAUSED_COLOR` (`#9C8AD6`, lavender). The arrow
  and the camera are then the bare map's (the free-drive follow): a paused drive is usually off
  its route, where an arrow held to the route is wrong. The camera keeps the drive's
  angle and heading through the pause (SPEC 4.6, Pause).

### The arrow

The arrow is a Compose overlay placed at its point's screen position right after each camera
move. As a map symbol it landed on time or a frame late at random, because a source update is
asynchronous and the camera move is not. Its position is a filtered estimate of meters along the
route, fed by the fused position guidance steers by ([chapter 4](04-navigation.md)), and it is
drawn on the route line itself. Its heading comes from a copy of
the line with short median jogs straightened, so the arrow and the camera hold their heading
through one.

The arrow is drawn where the car is now, not where the last fix put it. A GPS fix reaches the
app about 150 ms after it was measured, so the puck carries each fix forward by its speed times
that age. In a turn the arrow's heading is the line's direction across at least 12 m either side
of a point 0.3 s ahead, because a car starts its arc that far before the corner's point and the
heading's own smoothing takes that long to land. The camera aims 0.4 s further on still, the lag
of its own easing, and once it has swung more than 12 degrees it keeps its quick easing until it
has caught up. On a mock turn from a stop that took the map's lag from 1.5 s to 0.5 s and the
arrow's from 1.0 s to 0.7 s (SPEC 4.7).

By default the arrow is turned by the rate the phone's gyroscope measures (Settings > Diagnostics
> "Turn the arrow with the gyroscope"; off goes back to the line ahead). The line ahead turns
where its corner is drawn, which can be a second before or after the car turns; the gyroscope
turns when the car does. The road's direction at the arrow pulls the heading back, slowly in a
turn and quickly on a straight, which also cancels the gyroscope's drift. Turning into a corner
there is no pull: the road's direction at the arrow turns about a second after the car, and
pulling toward it left the arrow behind the car through the turn. Stopped, the gyroscope is
ignored, since a phone picked up at a light turns too.

A phone in a wobbly mount twists without the car turning, so two things hold the arrow to the
road. It can only point where the route goes from a second behind it to a second and a half
ahead, which on a straight is a couple of degrees either way. And it can only turn as fast as
the phone is being pushed sideways: a car turning at speed pushes, a mount twisting does not.
The camera leads the arrow by a third of a second of the measured turn, inside the same limits.
The nav trace's `gyro_dps` column says which way each frame was drawn, and `camera_target_deg`
where the camera was aiming. A trip saved with the trace on keeps that drive's trace, and
sharing trips from the list sends it with them.

Between fixes the arrow glides along the route, so at a corner a car drives straight through it
went round the turn for a second or two. With the gyroscope on, the arrow waits at a sharp
corner (35 degrees or more) 3 m past it, pointing along the road it is on, until the gyroscope
feels the car turn that way or a fix lands on the road the corner turns onto. On a mock drive
straight past a left turn the arrow kept pointing ahead and waited at the corner until the
reroute; turned with no gyroscope signal, it waited 1 s for the next fix (SPEC 4.7).

The arrow only moves forward, so carrying on at its modeled speed through a stop can leave it
past the car. Between fixes the model slows at the braking the last two fixes' speeds show, or the
accelerometer's if that is harder, so the arrow stops with the car: on a mock stop at 2.5 m/s²
it ran 5.6 m past and then slid back, and now stops 0.7 m past and stays. A real drive showed the
slide back at every stop. Braking harder, at 3.3 m/s², it stops about 2 m past and stays: only
past 4 m, stopped for a second and three fixes, does it glide back onto where the fixes put the
car, since sitting a little ahead at a light reads as nothing and moving backwards does not. The first fix of a pull-away is taken at
once when its own speed says the car is moving.

"Navigation icon" (Settings > Navigation) swaps the arrow for a car, a UFO, a pirate ship or a
rubber duck, which in a drive are 3D (`ui/map/Puck3D.kt`): low-poly meshes for the car, UFO and
ship, and a smooth, glossy duck ray cast per pixel on a worker thread. A tap on the arrow
while navigating opens the same choice over the map; a pick shows at once and Cancel puts back
the old one. The new icon replaces the map symbol's image in place, so the map does not reload.

### The camera

The camera follows the arrow, tilted 55 degrees, with the arrow 72.5 percent down the map. Its zoom
runs from 18.5 at a standstill to 15.8 at 30 m/s. The bearing eases over 1.6 s for a small error and 0.35 s past 25 degrees, so noise in
the line does not swing the map. A pinch sets a zoom that holds until a pan or Re-center. The
camera lets go of the map as soon as a second finger touches it, and a slow pinch zooms.
Pinching out flattens the camera, fully by zoom 12.5, and zooming back in past 15 tilts it
again: tilted at 55 degrees, a view of a whole city reaches the horizon and stalled the map for
up to a second on a Pixel 4a.

The frame follows the chrome (`NavFraming`, SPEC 4.7). At the default display and font size
nothing changes. With a large display size or large text:

- The arrow rises until the road-name pill under it clears the bar, the pill above the bar, or a
  speed box wide enough to reach under the arrow, by 8 dp. It never goes above 55 percent down.
- The zoom pulls back by how much shorter the map between the turn card and the arrow is than on
  the same phone at its default size. Above 0.8 of the default nothing changes, so a lane strip or
  a "Then" tab at default size leaves the zoom alone. Below 0.6 the pull-back keeps the same
  stretch of road in view, at most 1.5 levels and never below z15.5, where lights and stop signs
  still draw. On a 1080 x 2340 px, 420 dpi phone set to 546 dpi with the card capped at a third,
  that is about 0.8 levels.
- The edges are measured where they are drawn, and a change is taken only once it has held for
  400 ms and moved a dp or more, so dragging the bar or a card animating in does not move the
  camera.

"Zoom to the next turn" (Settings > Navigation, off by default) is for long stretches between
turns. Heading-up, the camera zooms out until the next turn sits 72 dp under the turn card, room for
the turn's callout, or 72 dp under the top of the map when nothing covers it, and the speed zoom
takes over once the turn is near enough for it. "Zoom out as far as" sets how far out it may go:
Town (z12, the default), Region (z10) or Far (z8). At z12 the map still draws motorways through
minor roads and the route. A turn farther than the floor allows sits off the top until it is
nearer. Street names, lights and stop signs do not draw that far out. Below
z15 the camera lies flatter, the way it does for a pinch, and the zoom allows for that, as it
allows for the pitch MapLibre holds the camera under (52.6 degrees on a Pixel 4a, less on a
denser screen). A turn off to the side pulls the camera toward it, up to 90 degrees off the
heading, once the framing has taken the zoom a level out, and the camera turns back as the turn
nears, so the arrow points off vertical by that much on the way and straight up through the turn.
On a 1080 x 2340 px, 420 dpi phone the speed zoom at 30 m/s frames a turn 1.02 km ahead, a turn
3 km ahead frames at about z12.8 with the camera at 8 degrees, and z12 frames one 5.1 km ahead,
flat. The car screen runs the same zoom and turn on the phone's switch. The numbers are in SPEC
4.7, Framing.

Start cuts to the car: the camera jumps to the nav zoom, flat, and tilts in with
`NAV_START_TILT_TAU_S = 3` s. A flight down from the route overview loads a set of tiles at every
zoom it crosses. On a Pixel 4a, seconds 2 to 4 after Start ran 6 to 20 fps flying and 29 to 59
cutting.

A phone in a driveway can be farther from the route than the arrow's 22 m snap tolerance. Until
the car reaches the road the icon is drawn off the line at the fused position, and the map still
tilts in.

The overview button is a toggle. It cuts to a fit of the remaining route, and a second press or
Re-center cuts back and tilts in like a start. Each cut fades off a veil in the map's land color
over 320 ms (`CUT_FADE_MS`), so the new tiles do not pop. Swiping the turn card or tapping a step
in the list while the overview is up leaves it for that step, and "tap to resume" goes back to
the car. A tap in the step list also drops the list, so the map shows the step.

### What the map hides

In a drive, place icons are cut to fuel ([chapter 1](01-places.md)), and the basemap's street
names and one-way arrows are hidden. For the first `NAV_PLACES_HOLD_MS = 7` s the place icons are
hidden entirely, so their sources request no tiles while the start cut loads.

In a turn, `TurnDeclutter` hides every symbol layer except street names, shields, exit numbers,
the callouts and the arrow, because symbol placement re-runs on every frame the camera rotates.
On a Pixel 4a Davis demo drive at 3x, turn seconds ran 40 to 49 fps with all layers and 48 to 60
without. The hide starts when the bearing error holds `START_DEG = 15` for three frames and ends
after 1 s under `CALM_DEG = 4`. "Simplify the map in turns" (Settings > Performance, on by
default) turns it off.

The overview hides places, minor street names, house numbers, lights and signs, cameras, transit
stops, the callouts and 3D buildings. A new layer that should vanish there needs its id prefix in
`OVERVIEW_HIDE_PREFIXES`.

### A parked drive draws nothing

Every write in the per-frame loop is gated on change: the location dot is uploaded only when it
moved or turned, and the camera is written only when one of its values changed. After 60 frames
with nothing written and the arrow under 0.3 m/s, the loop waits `NAV_IDLE_TICK_MS = 120` ms
between checks. On a Pixel 4a a parked drive draws 0 map frames and uses about 15% CPU. Without
the gates it drew 59 fps and held about 93% of a core. A new per-frame write in that loop needs
the same gate.

### Picture-in-picture

Leaving the app during a drive shrinks it to a 3:4 mini map. All chrome is behind one `!pipUi`
gate. "Turn card in the mini map" (on by default) shows the turn card across the top and the time
left along the bottom. Off, one bar along the bottom carries the road, the distance to the turn
and the arrival time. The compass and all gestures are off in the window, because the system's
taps on it reach the map as gestures and detach the camera.

### The notification and pause

`NavigationService` mirrors the drive into a notification with the maneuver's glyph, its
distance, the trip figures, and Pause and End. On Android 16 it asks to be promoted to a live
update: a status-bar chip with the distance to the turn, and on the lock screen a bar scaled to
the route with traffic and a point for each stop.

A paused drive shows "Paused" in the bar, turns the line ahead lavender and lets the screen
sleep. What pause holds, and the live update's conditions, are in [chapter 4](04-navigation.md).

While a drive runs, Vela shows over the lock screen, so the power button brings back the route,
not the PIN prompt ("Show the drive on the lock screen", Settings > Navigation, on by default).
Pausing or ending the drive puts the lock screen back over it, and Settings asks for the unlock.

"Wake the screen before each turn" (on by default, under that switch) lights a dark screen
about 25 seconds before each turn at the route's pace: about 200 m out in town, 750 m on a
freeway, never more than 1 km. It shows the glance screen: black, with the turn's arrow, the
distance, the instruction and the lanes in large type. Black costs an OLED screen almost nothing
and does not glare at night. It lets go 4 seconds after the turn, and the screen goes dark at the
phone's own screen timeout, since an app cannot turn the screen off. A touch, a key or the power
button brings back the whole drive and its keep-awake. Not on foot, and not while a car screen
is up.

### Stops during the drive

The step list's first row is the stops row. With no stops it reads "Edit route" and opens the
stops editor. With stops it lists them and offers "Remove next", which asks first. The editor's
Add stop opens search along the route. Each edit replans once ([chapter 4](04-navigation.md)).

"Tap places while driving (experiment)" (Settings > Navigation, off by default) widens the
drive's place icons from fuel to fuel and food, the best two per block of about 100 m
(`NAV_DRIVE_BLOCK_TOP = 2`), because every icon is placement work under a moving camera.

A tap only offers the place, as a card above the bar and a red "+" pin on the map.

- The card shows the category, the distance ahead and the time the stop adds: one route through
  the place, fetched within `NAV_DETOUR_TIMEOUT_MS = 8_000`, against the drive's live remaining
  time. A difference under 20 seconds or over 3 hours shows no figure.
- Only the card's buttons change the drive, so a stray touch at speed reroutes nobody.
- The card dismisses itself after 10 seconds, 25 on a phone driven by keys.
- Within `NAV_STOP_MATCH_M = 60` m of a stop still ahead, the card also offers "Remove stop".

### Driving mode

The drive's layout with no route, for a phone on a mount, a tablet or a head unit that has no
Android Auto. The layers panel ends with a "Driving mode" row, and Settings > Map > "Where the map
opens" > "Driving mode" starts Vela in it ([chapter 13](13-settings.md)).

- The map is the browse map's own follow, which turns heading-up and tilts once you move (unless
  "Turn the map as you drive" is off)
  ([SPEC 4.7, Free-drive follow](../../SPEC.md)). The location dot stays the dot: the arrow is
  drawn on a route. At driving speed the dot is moved onto the road it runs along, so a weak GPS,
  such as a head unit's, does not draw the car beside the street. It lets go at forks and turns.
- The bar is the drive's bar with an X where End sits, the mode's name and the time of day.
  The X is the only way out; Back works as it does on the plain map.
- The right-edge column has a locate button and the voice button with its three choices. The
  speed box sits above the bar from the first fix, standing still too, and the map credit sits
  between them. The search bar stays at the top with nothing under it; the category chips, the
  layers button, the parking button, the zoom buttons and the scale bar are gone.
- Landscape puts the bar in the left column, with the speed box beside it, and the buttons at
  the bottom edge, as a drive does.
- A search runs as usual. A place, its route picker and the drive cover the mode's chrome, and
  the mode is still on when they close, so a drive that ends returns to it.
- The screen stays on under "Keep screen on while navigating", and "Day and night while
  navigating" counts the mode as a drive.
- The speeding alert runs from the fixes. The speed camera and plate camera alerts, which a
  drive reads off its route, read a straight line about a kilometer ahead of the car instead,
  rescanned every few seconds, so a camera on the road you are on is announced with the same
  lead as in a drive. One past a bend is not seen until the bend is behind you.

`DrivingChrome` (`ui/nav/DrivingChrome.kt`) is composed from `MapFloaters`; `SearchGates`
decides when it draws and puts the browse buttons away while it does.

## Limits

- Callouts name only streets whose tiles have loaded. A tile that lands after the retries, about
  10 seconds, waits for the next 400 m. A pass keeps at most 60 streets, which a dense grid can
  reach.
- Callout clearance is measured to the tip of the bubble's tail, and the chip is wider. A long
  name under a tilted camera can still touch the line where a street crosses at a shallow angle.
  Measuring the chip's box on screen would fix it, at a projection per callout per pass.
- The exit callout needs a numbered exit in the instruction and an exit word the table knows.
- A stop sign with no recorded orientation (the Overpass fallback) is judged on distance alone,
  so the cross street's sign at a tight corner can show. A road that bends at the junction can
  fall outside the 40 degrees. Each sign's own `direction` tag would be a better test, and
  neither the bake nor the Overpass parse carries it.
- Offline, lights and signs need the region's road-features file, which only comes down while
  online ([chapter 8](08-offline.md#limits)).
- The road-ahead bar has no incidents, because no keyless incident source works. It shows only
  what OpenStreetMap maps, and lights are mapped more consistently than stop signs.
- The tap-to-stop figure compares the drive's calibrated remaining time with a fresh fetch, so
  the minutes are an estimate.
- Walking and cycling lines are dashed, and a dashed MapLibre line takes no gradient, so they
  have no moving cut.
- The look-ahead pull-back measures the map in a straight line. Under a 55 degree tilt the top of
  the view holds more road than the bottom, so a tall card hides more road than the ratio says.
