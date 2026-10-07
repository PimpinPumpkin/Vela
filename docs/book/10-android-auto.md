# 10. Android Auto and the car screen

## What you see

Connect the phone to a car, by cable or wirelessly, and Vela can appear in the car's launcher as a
navigation app. It opens on a list: Home, Work, recent and saved places, then nearby gas, charging,
food, coffee and parking. From there you search or pick a place, preview up to three routes with
their times, and start the drive.

The drive screen is the map with your arrow and the route, a turn card with lane arrows, the
arrival estimate, and a row of round buttons for mute, pause, search along the route and end. A
second row on the map recenters, zooms and shows the rest of the route. Your speed sits in the
bottom corner, with the posted limit beside it when Vela knows it.

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
route preview to them. A free-text query opens the landing list.

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
them to the session under the phone's gate: GPS provider only, accuracy 50 m or better. With
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
- The speed badge and the limit sign while navigating, unless Settings > Navigation "Show speed
  and speed limit" is off.
- One credit line, `© OpenStreetMap`. `QuietSnapshotter` overrides the library's overlay, which
  prints every tile source's attribution and stays when the logo is turned off.

A snapshot takes roughly 100 to 300 ms, so the car map moves in eased steps. The render loop ticks
every `TICK_MS` (70 ms) and asks for one snapshot at a time. A request that arrives while one is
in flight marks the map dirty, and the next snapshot starts when the current one lands.

All screens share one renderer and switch its mode: browse (north up, centered on you, no route),
preview (the chosen route framed in blue, the other listed routes in gray under it, a red dot
at the destination) and nav (heading up, following, the line ahead lavender while paused). A renderer per screen freezes
the map, because the host does not deliver the surface again to a new callback. The snapshotter
is kept across screens while the surface size is unchanged, since a new one reloads the style and
the map flashes.

On a screen change the host hands over a new `Surface` object for the same buffer queue. The
queue takes one CPU connection, and the old object holds it until it is released, so the renderer
releases the old `Surface` when the new one arrives. Left to the garbage collector, every frame
on the new one fails to lock and the map stands still until a collection happens to run.

### Framing and following

The templates cover part of the surface. The host reports the visible area, which is uncovered
now, and the stable area, which is never covered. The arrow is framed in the visible area. While
following in nav it sits `PUCK_DOWN` (0.72) of the way down, so more road shows ahead. The speed
badge and the credit go in the stable area, because on a tall screen the map buttons stack over a
corner of the visible area. Meters per pixel use MapLibre's 512 px tiles (78271.517 at zoom 0).
The 256 px figure doubles the offset and drops the arrow off the bottom edge.

The arrow is the phone's puck bitmap, an eighth of the screen's short side times the Arrow size
setting. It rides the phone's between-fix estimator (`FollowEstimator`) and snaps to the route
when a fix is within `SNAP_MAX_M` (40 m) of it. Heading is the GPS course when you move faster
than `STOPPED_MPS` (1 m/s) and the bearing accuracy is 45 degrees or better. Otherwise it is the
bearing of the route segment you are on, or failing that the last heading. The bearing to the
nearest route point flickers on a parked car's GPS jitter and swings the view.

Nav zoom runs in five steps from 17.5 below 15 km/h to 15.2 at 100 km/h and up, eased between
steps. A pan, a pinch or a zoom button stops following until `RECENTER_MS` (6 s) has passed or you
press recenter. A pan moves the center by the finger's travel in ground meters. Reading the new
center off the last snapshot adds the framing offset to every scroll event. A pinch zooms about
the fingers, and a fling glides to a stop. The overview button frames the remaining route north
up and holds it until you press it again or recenter.

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
| `MainCarScreen` | `PlaceListNavigationTemplate` | The landing list: up to `MAX_DESTINATIONS` (3) of Home, Work, recents and saved places, then nearby categories, then "More nearby". Buttons for Search, Saved and Settings. |
| `NearbyCarScreen` | `PlaceListNavigationTemplate`, `ListTemplate` | The six nearest results for a category, each with a numbered pin on its row and the same pin on the map, which frames them with the car. Opened with no category, it lists the phone's quick categories. |
| `SavedCarScreen` | `ListTemplate` | Home, Work and every saved place, up to the host's list limit. |
| `CarSettingsScreen` | `ListTemplate` | Spoken directions and the three avoids, written to the preferences the phone uses. |
| `SearchCarScreen` | `SearchTemplate` | Up to six rows. Contacts lead with up to two when contact search is on. |
| `RoutePreviewCarScreen` | `RoutePreviewNavigationTemplate` | Up to three driving routes with live-traffic times, and Go. |
| `ActiveNavCarScreen` | `NavigationTemplate` | The drive. |
| `AlongRouteCarScreen` | `ListTemplate` | Quick categories, then up to six results around the car. A pick becomes the next stop. |

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
message. Go names a provisional route first, the way the phone does.

The drive's button row holds four actions, the template's cap: mute, pause or resume, search along
the route, and end. A faster-route offer that saves a minute or more takes the mute slot. The
buttons are icons, because the host draws a titled action as a text pill across the map. End is
red and carries the primary flag. The host throws on a background color on any other action.

Search along the route is two lists because the host refuses typing while the car moves.

The alerts the phone speaks (a camera ahead, speeding, a destination that closes before you
arrive) also show as a car toast, so a muted car still gets them.

### The turn card and the cluster

The host draws the turn card, and needs three things first:

1. `NavigationManager.navigationStarted()`, called after the navigation callback is set. Without
   it the host shows the arrival estimate and no turn card. It must be balanced by
   `navigationEnded()`. The screen ends it on arrival, on stop and when it is destroyed, or the
   host stays in a navigating state for the next session.
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
- Past `CONTINUE_FAR_M` (1,500 m) the card reads "Continue on" and the road you are on, with a
  straight arrow, and the turn is the "then" step. Closer in, the turn leads.
- Lanes are drawn as a bitmap of arrows, valid lanes white and the rest dimmed, for up to eight
  lanes.
- Distances round to 10 below 100 ft or m and to 50 above, and switch to miles at 1,000 ft and to
  kilometers at 1,000 m. Zero is allowed so the host can say "now".

A paused drive shows "Paused" in place of the turn card.

### The voice

The voice is the phone's, sent to the car as Android Auto's guidance audio. A drive started from
the car picks the engine the phone would: the Vela voice when it is installed and no other engine
is chosen.

### Android Automotive

Android Automotive is Android running in the car itself. Vela's templated screens belong to
Android Auto, and on Automotive the ordinary app runs. Two manifest declarations serve it:

- Each launcher alias has a second intent filter with `MAIN`, `DEFAULT` and
  `android.intent.category.APP_MAPS`. Automotive starts whatever answers it in the map panel of
  its home screen. The start is an implicit intent, which only matches a filter that declares
  `DEFAULT`, so the category cannot sit on the launcher filter alone. On a phone the same filter
  answers "open maps" requests.
- `MainActivity` and both aliases carry `distractionOptimized`. Without it the car grays Vela out
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
- Search along the route searches around the car and sorts by distance. It does not follow the
  route ahead, and a pick always becomes the next stop.
- Only typed search has an offline fallback. Nearby and search along the route need a connection.
- The route preview is driving only and shows three routes at most.
- A drive started from the car with the phone app never opened has no navigation controller, so
  it gets no corridor dots and no alert toasts.
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
