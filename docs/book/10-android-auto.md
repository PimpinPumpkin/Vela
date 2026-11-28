# 10. Android Auto and the car screen

## What you see

Connect the phone to a car, by cable or wirelessly, and Vela can appear in the car's launcher as a
navigation app. It opens on a list: Home, Work, recent and saved places, then nearby gas, charging,
food, coffee and parking. From there you search or pick a place, preview up to three routes with
their times, and start the drive.

The drive screen is the map with your arrow and the route, a turn card with lane arrows, the
arrival estimate, and two round buttons, mute and end; pausing and the search along the route are
done on the phone. A second row on the map recenters and shows the rest of the route. The posted limit sits in
the bottom corner when Vela knows it; your speed sits beside it only if "Your speed on the car
screen" is on under Settings > Navigation, since the car has a speedometer of its own. "Lane
guidance on the car screen", there and in the car's settings, turns the lane arrows off. The turn
card then does not grow as a turn comes near, which would make Zoom to the next turn pull back out.

The car opens on the map alone, the way Google Maps' car screen does: tilted, turning with the
car (parked too) and zooming out with speed, with the limit sign in the corner and Search, a list
button, Saved and Settings along the top. The list button brings up Home, Work, the recents and
nearby categories beside the map, and the car button on that list puts it away again. Search
with nothing typed lists the same destinations with their addresses, like Google's Recent tab.
With "Turn the map as you drive" off on the phone, or "Keep north up" on, the map stays north up. The traffic layer from the phone's map shows here when
it is on, and the car's settings have a switch for it.

Whether Vela is listed depends on how it was installed. Android Auto refuses an app that Google
Play does not own, and Vela is not on Play. [The install gate](#the-install-gate) says what is
known. [docs/ANDROID-AUTO.md](../ANDROID-AUTO.md) is the user guide.

A car that runs Android itself (Android Automotive) involves no projection. Vela runs there as the
ordinary app and can fill the map panel on the car's home screen.

## Where the data comes from

Android Auto projects. The phone does all the work and the head unit shows what the phone's
Android Auto app sends it. Vela's car code is a service inside the ordinary app
(`VelaCarAppService`), bound by Android Auto when the car connects.

- The car screens use the phone's own navigation session, location provider, map data source,
  saved and recent stores, voice and router, handed to them as `CarDeps`. The car has no routing
  or guidance logic of its own. [Chapter 4](04-navigation.md) covers the drive.
- The map is the phone's Liberty style on OpenFreeMap vector tiles, with the Roboto-patched style
  file when the phone has one, rendered by MapLibre on the phone. Tiles inside a downloaded region
  are read from that file through the phone map's hook (`LocalBasemapTiles`).
- Routes come from the phone's directions call ([chapter 5](05-routing.md)). Search and nearby
  places come from the phone's search ([chapter 6](06-search.md)).
- Lights, stop signs, level crossings, speed humps and speed cameras along the route come from the
  phone's navigation controller through `CarBridge`. Plate cameras come from the bundled camera
  set ([chapter 3](03-cameras.md)).
- The speed limit is read from a downloaded region's routing file ([chapter 8](08-offline.md)).

## How it is decided

### What Vela declares

Vela is a navigation-category templated car app. The manifest declares `VelaCarAppService` with
the `androidx.car.app.category.NAVIGATION` and `FEATURE_CLUSTER` categories,
`automotive_app_desc.xml` with `<uses name="template"/>`, the `NAVIGATION_TEMPLATES` and
`ACCESS_SURFACE` permissions, and `minCarApiLevel` 1, the oldest car API. A second intent filter
takes `androidx.car.app.action.NAVIGATE` with a `geo:` URI. A URI with coordinates opens on a
route preview to them. A free-text query (`geo:0,0?q=Starbucks`) opens the car search with the
query filled in and its results loading, the way a submitted search does. The landing list sits
under it.

A templated app hands the host a template (a list, a search box, a navigation screen). The host
draws it in the car's style and enforces its limits, such as six rows in a place list. The app
draws only the map, on a surface the host provides.

The host validator allows any host (`ALLOW_ALL_HOSTS_VALIDATOR`). The sample allowlist rejects
head units it does not know, and Vela then shows in the launcher and refuses to open.

### One navigation loop

The process has one `NavSession`. The phone's view model and the car session both feed it, so a
route started on the phone shows on the car and the other way round. `VelaCarSession` watches the
session and opens the drive screen when a drive starts, whichever side started it. A car that
connects in the middle of a drive opens on the drive screen, with the landing list under it.

Each projection is a `VelaCarSession`. It collects fixes from the location provider and passes
them to the session under the phone's gate: GPS provider only, accuracy 50 m or better. They go
through the same position filter as the phone's fixes, with their accuracy and course, so a
reflection off a building at a red light does not reroute the car either. While the phone app is
open its feed is the drive's and the car's stands down; each fix reaches the engine once. This is
the feed under Android Auto, Android Automotive, Gearslip and myCar alike. With
"Simulate my location" on, the pinned point counts as the fix, and a demo drive started on the
phone moves the car's arrow too. Guidance
therefore runs with the phone's screen off and the app never opened. In that case the service
does what the phone's view model would have done: it attaches the Vela voice, applies the "Spoken
directions" setting, and opens the downloaded place packs.

A car can connect before Vela was ever opened on the phone, so location was never asked for. Both
location collectors, the session's and the map's, wait on `CarLocationAccess.granted`. The landing
list leads with an "Allow location" row that raises Android's permission prompt on the phone, and
the map shows the whole world until the first fix.

### The map is snapshots

The host gives a navigation app a `Surface`, and MapLibre's live map is a View that needs a
window. `CarMapRenderer` uses MapLibre's `MapSnapshotter`, which renders a camera position to a
bitmap off screen. The renderer draws the bitmap on the car surface with a `Canvas`, then draws
its own layers on top:

- The route, gray behind the arrow and blue ahead, with Google's congestion spans in amber, red
  and dark red.
- Dots for lights, stop signs, level crossings, speed humps and cameras, from zoom 13.5.
- During a drive, turn arrows for the next `CAR_ARROWS` (3) turns, cut by the phone's
  `TurnArrowGeometry` and drawn its way: a white shaft with an indigo edge and a triangle head,
  under the light and stop sign glyphs, as on the phone, so the sign at a turn shows on top of
  its arrow. Settings > Navigation "Turn arrows" turns them off here too.
- The speed badge and the limit sign while navigating and on the map-only landing, unless Settings >
  Navigation "Show speed and speed limit" is off.
- One credit line, `© OpenStreetMap`. `QuietSnapshotter` overrides the library's overlay, which
  prints every tile source's attribution and stays when the logo is turned off.

A Vela process that dies while the car is connected (an update, a crash, a low-memory kill) can
leave the host's surface connected to it. Every lock from the new process then fails ("already
connected" in the log) and the car keeps showing the dead process's last frame, an old route line
included, with the buttons doing nothing. Setting the surface callback again hands back the same
surface. When the locks have failed for `STUCK_SURFACE_MS` (2 s), the car session shows
`MapReloadCarScreen`, a loading list titled "Loading the map", and goes back as soon as the host
drops the surface. A list, because the host keeps the map surface alive behind a message screen.
Back on the map the host builds a new surface and the map draws again.

The style itself carries what the phone adds to Liberty at runtime. The snapshotter can set a
layer's properties but cannot add one, so `CarStyle` writes the phone's extra layers into the
patched style's JSON before it loads: the flat fills for pedestrian areas and parking lots (the
`vela-plaza` twin of Liberty's dotted pattern), wetlands, sports fields, commercial blocks, park
trails and bike paths, the style light, and Google's traffic raster, hidden until wanted. They
carry the phone's layer ids, so the palette colors them the same way. The traffic raster is shown
with no route when the phone's Traffic layer is on, as on the phone, and the place icon tiers take
the phone's filters (no vegetation icons; fuel only during a drive). Until the patched style exists
on a fresh install, the plain style URL loads without any of this.

A snapshot takes roughly 100 to 300 ms, so the car map moves in eased steps. The render loop ticks
every `TICK_MS` (70 ms) and asks for one snapshot at a time. A request that arrives while one is
in flight marks the map dirty, and the next snapshot starts when the current one lands.

All screens share one renderer and switch its mode: following (the landing screen and the
drive), preview (the chosen route framed in blue, the other listed routes in gray under
it, a red dot at the destination, and each route's time in a bubble on its own stretch, as
Google's car preview labels them: blue on the chosen route, white on the others) and results (numbered pins framed with the car). A renderer per
screen freezes the map, because the host does not deliver the surface again to a new callback. The
snapshotter is kept across screens while the surface size is unchanged, since a new one reloads
the style and the map flashes.

On a screen change the host hands over a new `Surface` object for the same buffer queue. The
queue takes one CPU connection, and the old object holds it until it is released, so the renderer
releases the old `Surface` when the new one arrives. Left to the garbage collector, every frame
on the new one fails to lock and the map stands still until a collection happens to run.

### Framing and following

The templates cover part of the surface. The host reports the visible area, which is uncovered
now, and the stable area, which is never covered. The arrow is framed in the visible area.

A route preview, search results and the drive's overview are fitted to the fit area
(`fitArea`): the visible area kept clear of the map buttons down the right side and the action
strip along the top, even while they are hidden. The visible area takes them in and out as they
show and hide, so a route framed against it ran under the buttons and a tap moved the map. The
zoom fits the box into that area with a fifth to spare (`CarFraming.fitZoom`); it used to come
from the box's span alone, the same on every screen, which put most of a route under the card on
an 800 by 480 unit. When the area changes size, as when the route list replaces its loading
state, the view is framed again. The preview frames every route offered, not the picked one
alone. Following again, the map takes back the zoom it had before the framing; the landing used
to stay zoomed out on the preview's route. The
speed badge and the credit go in the stable area, because on a tall screen the map buttons stack
over a corner of the visible area. Meters per pixel use MapLibre's 512 px tiles (78271.517 at
zoom 0). The 256 px figure doubles the offset and drops the arrow off the bottom edge.

The view is heading-up when it follows the car: during a drive, on the map-only landing parked
or moving, and with no route elsewhere once the smoothed speed passes `DRIVE_LATCH_MPS` (2.5 m/s)
with a course known. The latch holds through a red light and lets go when the follow ends.
Heading-up, the camera turns onto the arrow's heading, tilts to 55 degrees and puts the arrow
`PUCK_DOWN` (0.86) of the way down, about where Google Maps keeps its car, so more road shows
ahead. On the list card, after a pan, with the phone's "Keep north up" on, and with no route
while its "Turn the map as you drive" is off, the view is north up, flat and centered on the
arrow, which still turns with the car. The camera's bearing and tilt ease in, so the switch rotates the map rather than
snapping it.

Under tilt the ground nearer the camera is drawn larger, so the flat offset would put the arrow
too low. `CarFraming.targetOffset` solves MapLibre's perspective for the ground offset that lands
the arrow on the wanted row, from the camera's distance to its target (half the height over the
tangent of half the field of view, 36.87 degrees).

The arrow is the phone's puck bitmap, an eighth of the screen's short side times the Arrow size
setting. It rides the phone's between-fix estimator (`FollowEstimator`) and snaps to the route
when a fix is within `SNAP_MAX_M` (40 m) of it. Heading is the GPS course when you move faster
than `STOPPED_MPS` (1 m/s) and the bearing accuracy is 45 degrees or better. Otherwise it is the
bearing of the route segment you are on, or failing that the last heading. The bearing to the
nearest route point flickers on a parked car's GPS jitter and swings the view.

Zoom follows speed whenever the view follows a moving car, from 17.2 at rest to 15.0 at 110 km/h
and up, on a straight line, eased. With the phone's "Zoom to the next turn" on, a drive also zooms
out until the next maneuver sits 40 px under the top of the visible area, no farther than the
phone's floor, turns the camera toward it up to 90 degrees as the phone does (chapter 11), and
lies flatter that far out. The car's map keeps the view centered and moves the arrow low by
moving the camera's target, so the zoom is worked out for an arrow under the center: a tilted
view reaches less road above a low row than above the middle. A fix that reports no speed takes the distance since the last
fix over the time between them, because a fix read as parked held the zoom at its closest, and
one with no bearing takes the direction from the last fix once it has moved a few meters. A pan,
a pinch or a zoom button stops following until `RECENTER_MS` (6 s) has passed or you press
recenter. A pan moves the center by the finger's travel in ground meters. Reading the new center
off the last snapshot adds the framing offset to every scroll event. A pinch zooms about the
fingers, and a fling glides to a stop. The overview button frames the remaining route north up and
holds it until you press it again or recenter.

[SPEC section 10.6](../../SPEC.md) lists the rest of the car constants.

### Theme

The car map runs the phone's `applyMapTheme` on the snapshotter. The snapshotter's style-loaded
observer never fires for a style passed as JSON, because parsing finishes before the observer is
attached. The renderer takes the first returned snapshot as proof that the style is loaded,
applies the palette, discards that frame and renders again.

| Theme on the phone | Car map |
| --- | --- |
| Light | light |
| Dark, AMOLED black | dark, with the true-black palette for AMOLED |
| Day and night | follows the sun as the phone computes it |
| Follow system | the car's own day or night signal |

An explicit choice on the phone holds on the car, so a driver who set Vela to dark does not get a
light map because the head unit says day. Before each render the renderer checks that the look it
applied is still the current one and applies the palette again if it changed, so the map goes
dark with the car.

### The screens

| Screen | Template | What it holds |
| --- | --- | --- |
| `MainCarScreen` | `NavigationTemplate`, `PlaceListNavigationTemplate` | The landing: the map alone with Search, a list button, Saved and Settings. The list button swaps in the destinations card: up to `MAX_DESTINATIONS` (3) of Home, Work, recents and saved places, then nearby categories, then "More nearby", and a car button that puts it away. Beside the card on a map narrower than `NARROW_MAP_DP` (960 dp), Search is the icon alone: on an 800 by 480 unit the titled pill reached over the card. |
| `NearbyCarScreen` | `PlaceListNavigationTemplate`, `ListTemplate` | The six nearest results for a category, each with a numbered pin on its row and the same pin on the map, which frames them with the car. Opened with no category, it lists the phone's quick categories. |
| `SavedCarScreen` | `ListTemplate` | Home, Work and every saved place, up to the host's list limit. |
| `CarSettingsScreen` | `ListTemplate` | Spoken directions, lane guidance, the three avoids and the Traffic layer, written to the preferences the phone uses. Six rows, the car library's default list limit. |
| `SearchCarScreen` | `SearchTemplate` | Up to six rows. Contacts lead with up to two when contact search is on. |
| `RoutePreviewCarScreen` | `RoutePreviewNavigationTemplate` | Up to three driving routes with live-traffic times, and Go. |
| `ActiveNavCarScreen` | `NavigationTemplate` | The drive. |
| `MapReloadCarScreen` | `ListTemplate` | "Loading the map" for a moment, when the map surface is stuck. |

The place list template throws past six rows (`MAX_ROWS`), so the landing list is cut before it is
built. A category row carries the map's own marker as a small image (`Row.IMAGE_TYPE_SMALL`). As
an icon the host tints it, and the marker becomes a white blob.

While you type in search, the autocomplete answers with one small request, 300 ms after the last
key, for a 20 km window around the car (`SUGGEST_SPAN_M`). The full search runs when you submit or
pick a bare query row such as "Starbucks". A full search per keystroke queues requests behind
OkHttp's per-host limit, because canceling a coroutine does not abort a call already on the wire.
With no signal, or an empty answer, typed search reads the downloaded place packs and addresses.

The route preview passes the avoid settings to the directions call and waits up to 15 s for a
first fix. The template refuses a list without a Go action, so an empty result shows a plain
message. Go names a provisional route first, the way the phone does. A row's time is rounded to
the minute before it goes to the host, which drops the seconds, so the row and the bubble agree.

The drive's button row holds two actions, the voice and end. The voice button steps on, alerts
only and off, as the phone's speaker button does, and its glyph is the mode: a speaker, a bell, a
crossed-out speaker. A faster-route offer that saves a minute or more goes first, beside the
voice button rather than in its place, so the car can always mute. The other buttons are icons, because the host draws a titled action as a text pill across the map. End is
red and carries the primary flag. The host throws on a background color on any other action.

A gas station's price is a second text line on its row in search and nearby: Google's in the US, the UK government's Fuel Finder in the UK ([chapter 2](02-data-and-rebakes.md)).
The rows show at once; a UK list asks for the Fuel Finder file (downloading it the first time) and
redraws with the prices, unless a newer search has replaced it.

The alerts the phone speaks (a camera ahead, speeding, a destination that closes before you
arrive) also show as a car toast, so a muted car still gets them.

### The turn card and the cluster

The host draws the turn card, and needs three things first:

1. `NavigationManager.navigationStarted()`, called after the navigation callback is set. Without
   it the host shows the arrival estimate and no turn card. It must be balanced by
   `navigationEnded()`. The screen ends it on arrival, on stop and when it is destroyed, or the
   host stays in a navigating state for the next session. The callback's `onStopNavigation`
   ends the drive when another navigation app starts one. The host also calls it when the car
   disconnects, after the session and the screen are gone, and then the drive goes on on the
   phone, as Google Maps' does: before, a loose cable or a wireless dropout ended guidance.
   A call to a host that is already gone throws; the drive screen and the session drop such a
   throw from their state updates, which had ended the app on the phone.
2. `updateTrip()` with the current step and the destination. This is the host's navigation data
   channel. It also feeds the instrument cluster and a head-up display. Vela sends it when
   something it shows has changed, and at most once a second. The drive screen is rebuilt on
   every navigation state, several times a second, and the host drops updates sent that fast.
3. An icon on the step's maneuver. Android Auto draws no card for a maneuver without one, so
   `ManeuverMapper` sets the glyph the phone's banner shows.

A car with a cluster display opens a second session for it, and that display accepts the
navigation template only. `VelaCarAppService` gives it a `ClusterSession` with one bare
`NavigationTemplate`. The turn and the arrival shown there come from the trip data above. The main
session's place list is not allowed on that display and crashes the app there.

`ManeuverMapper` turns Vela's maneuvers into car maneuvers:

- A roundabout takes its direction of travel from the route's geometry when it has one, and
  counter-clockwise otherwise. The exit number comes from the router, with a floor of 1 because
  the car API throws on a roundabout with none.
- The card leads with the next turn however far off it is, as the phone's does, and shows its
  lanes from 800 m out. The turn after it shows as "then" only by the phone's rule (`thenShown`):
  it follows within 500 m and the first turn is within 800 m. The road you are on is a pill at
  the bottom of the car map, under the arrow, as on Google's car map and the phone. Road names on the card are abbreviated as on the phone's pill ("Russell Rd
  NE"); "Road numbers first" applies here too.
- The card is Google's car teal (`TURN_CARD`). The host draws an app's card color about 20% darker,
  so the color set is #008A8B, which lands on Google's #006D6F.
- Lanes are a bitmap of the phone card's lane arrows, up to eight lanes: in a lane you can use,
  the arrow for this turn is white, and everything else is gray, with a short dash between lanes.
  With lane guidance off (`CarLanes`) no step carries lanes.
- The time left is colored by traffic with the route line's thresholds: red when the live time
  runs more than 40% over the typical time, yellow over 15%, green below. A route without live
  traffic leaves it the host's default color.
- Distances round to 10 below 100 ft or m and to 50 above, and switch to miles at 1,000 ft and to
  kilometers at 1,000 m. Zero is allowed so the host can say "now".

A paused drive shows "Paused" in place of the turn card.

### The voice

The voice is the phone's, sent to the car as Android Auto's guidance audio. A drive started from
the car picks the engine the phone would: the Vela voice when it is installed and no other engine
is chosen. The car's speaker button and the phone's set the same mode, and every line Vela says
goes through that one switch ([chapter 4](04-navigation.md)).

Gearslip, which stands in for Android Auto on the phone, cannot take another app's navigation
audio. It reads each turn on Vela's turn card aloud itself, with the phone's text-to-speech, on
the car's guidance channel. That voice is Gearslip's, and Vela's mute does not reach it: no car
API carries a mute, so both buttons can show muted while it speaks. Gearslip's own car setting,
Sound and voice > Spoken directions, turns it off.

### Android Automotive

Android Automotive is Android running in the car itself. Vela's templated screens belong to
Android Auto, and on Automotive the ordinary app runs. These manifest declarations serve it:

- A hidden alias pair, `.MapsPanel` and `.MapsPanelGeneric`, answers `MAIN`, `DEFAULT` and
  `android.intent.category.APP_MAPS`. Automotive starts whatever answers it in the map pane of
  its home screen. The start is an implicit intent, which only matches a filter that declares
  `DEFAULT`. On a phone the same filter answers "open maps" requests. `AppNameAlias` enables the
  one whose label matches the launcher's name ("call it just Maps").
- `MainActivity` is `singleTask`, `resizeableActivity` and `allowEmbedded`. Automotive 10 and 11
  embed the pane with an ActivityView, which starts an app that does not allow embedding full
  screen instead. Automotive 14 embeds it either way. One task holds the map wherever it was
  started from: the pane, the app list, a link or a share.
- `MainActivity` and every alias carry `distractionOptimized`. Without it the car grays Vela out
  of its app list once the car is in gear.

### The install gate

What is established:

- When the phone connects, the Android Auto app asks the Play Store who owns each app, and it
  refuses an app Play does not own. The phone's log shows both steps:

  ```
  Finsky: PlayGearheadService app.vela, app owners empty
  CAR.VALIDATOR: Package DENIED; failed all other checks [app.vela]
  ```

- Some installers make the install look like Play's work. On some setups the car then lists Vela:
  a rooted phone, or stock Android.
- Android keeps two records of an install: who performed it (`installingPackageName`) and, from
  Android 11, who started it (`initiatingPackageName`). The car reads the second as well as the
  first. `adb install -i com.android.vending` sets only the installer and leaves the shell as the
  initiator, and the car still refuses the app. On Android 14 every install made from adb leaves
  the shell as the initiator, whichever installer `-i` names, and `pm set-installer` is refused.
  Shizuku installs as the shell user, so it should leave the same records.
- An in-app update replaces both records, and the car drops Vela until it is installed the same
  way again. So when either record names Play (`InstallSource.setForCar`), the updater holds the
  downloaded APK back, says "This update will drop Vela from Android Auto", and offers the file to
  save for that installer. "Update anyway" installs it. Settings > About shows who installed Vela,
  and who started the install when that differs.
- The Desktop Head Unit does not run the ownership check. It lists a plain sideload, so it
  previews the screens and proves nothing about the gate.

Vela has no code that gets past the check. A Google Play listing is not the route for now. The
open work is in [ROADMAP](../../ROADMAP.md) and the pinned issue #179.

### Previewing on the Desktop Head Unit

Google's Desktop Head Unit runs the car screens on a computer.

1. Install it with `sdkmanager "extras;google;auto"`. It lands in the SDK under
   `extras/google/auto/`.
2. In Android Auto's settings on the phone, tap the version row ten times for developer mode, then
   pick "Start head unit server" from the overflow menu.
3. Run `adb forward tcp:5277 tcp:5277`.
4. Start the head unit with `-c <config>/default.ini` and keep its standard input open, for
   example with a fifo. With no config it drops the connection after the TLS handshake ("Failed to
   read from transport"), and it exits when its input closes.
5. Accept Android Auto's first-run prompts on the phone.

Turn on "Simulate my location" first. The car map centers on the phone's position, and the
simulated one is the only way to keep a real address out of a screenshot. The console takes
`tap <x> <y>`, `screenshot <file>`, `day` and `night`. After reinstalling Vela, quit the head
unit and start it again.

A config with `instrumentcluster = true` makes Android Auto forward the turn data to the head
unit's cluster. Version 2.0 of the head unit has no cluster display, so it never opens
`ClusterSession`.

Gearslip, a separate project, has a "Car preview" debug mode that renders the same screens on the
phone. It says nothing about the gate either.

## Limits

- On a head unit that runs the ownership check, a plain sideload of Vela is not listed.
- The map moves in snapshot steps, well under the phone's frame rate. A live map needs a
  View-backed renderer, which the template surface does not offer.
- Only typed search has an offline fallback. Nearby needs a connection.
- The route preview is driving only and shows three routes at most.
- A drive started from the car with the phone app never opened has no navigation controller, so
  it gets no corridor dots and no alert toasts, and it records no trip.
- The speed limit sign needs a downloaded region. The phone falls back to an online limit overlay
  and the car does not.
- The speed badge is GPS speed. The manifest asks for `CAR_INFO`, and nothing reads the car's own
  speed yet.
- Approximate location counts as granted on the car, but guidance takes only GPS fixes of 50 m or
  better.
- Vela sends the cluster the current step and the destination. What a car's cluster or head-up
  display does with them is up to the car.
- Android Auto's guidance stream is 16 kHz mono, so every navigation voice sounds duller in the
  car than on the phone, Google's included. A head unit set to play prompts over the phone-call
  link makes it 8 kHz. No setting in Vela changes either.
- The Automotive declarations were checked on the emulator, which trusts the
  `distractionOptimized` mark from any app. A production car also wants an install source it
  trusts, so a sideloaded Vela may still be grayed out while driving.

Not yet seen on a real head unit: the icon-only buttons, the badge and credit in the stable area,
the palette applied from the first snapshot, category markers on list rows, autocomplete search,
finger pan, pinch and fling, the "Allow location" row, the Nearby, Saved and Settings screens, and
the cluster session.
