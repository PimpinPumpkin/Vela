# 10. Android Auto and the car screen

## What you see

Plug the phone into a car (or pair it wirelessly) and Vela can show up in the car's launcher as a
navigation app. The car screen gets a landing list (Home, Work, recent and saved places), a
search box, a route preview with up to three routes and their times, and a drive screen: the map
with your arrow and the route, a turn card with lane arrows, the arrival estimate, and a row of
round buttons for mute, pause, search along the route and end. A second row of buttons on the map
recenters, zooms and toggles an overview of the rest of the drive. The speed you are doing sits in
the bottom corner, with the posted limit beside it when Vela knows it.

Whether it shows up at all is not Vela's call. On the car head units most people have, Android
Auto lists a navigation app only when Google Play installed it, and Vela is not on Play. That gate
is its own section below, because it is the thing people ask about first.

## Where the data comes from

- **Nothing runs on the head unit.** Android Auto projects: the phone does all of the work and the
  car shows what the phone's Android Auto app sends it. Vela's car code is a service inside the
  ordinary Vela app, bound by Android Auto when the car connects.
- **The car screens use the phone's own instances** of the navigation session, the location
  provider, the map data source, the saved and recent stores, the voice and the router. The car
  adds no routing, guidance or voice logic of its own, so a route started on the phone appears on
  the car and the other way round. How the drive itself runs is [chapter 4](04-navigation.md).
- **The map picture** is the same Liberty style on OpenFreeMap's vector tiles the phone draws, with
  the same Roboto-patched style file when the phone has one, rendered by MapLibre on the phone.
  The snapshotter shares MapLibre's tile cache with the phone map.
- **Lights, stop signs and speed cameras** along the route come from the phone's navigation
  controller through a small bridge (`CarBridge`); plate cameras come straight off the bundled
  camera set ([chapter 3](03-cameras.md)).
- **The speed limit** on the car is the offline one only, read from a downloaded region's routing
  file.

## How it is decided

### What Vela declares

Vela is a **navigation-category templated car app**. The manifest carries a `CarAppService`
(`VelaCarAppService`) with the `androidx.car.app.category.NAVIGATION` and
`androidx.car.app.category.FEATURE_CLUSTER` categories, `automotive_app_desc.xml` with
`<uses name="template"/>`, the `NAVIGATION_TEMPLATES` and `ACCESS_SURFACE` permissions, and a
second intent filter for `androidx.car.app.action.NAVIGATE` with `geo:` URIs, so "navigate to" from
the assistant or another app opens straight on a route preview.

```
minCarApiLevel = 1        // the oldest car API, the widest set of hosts
carApp         = 1.4.0    // androidx.car.app and app-projected
```

The host validator allows any host (`ALLOW_ALL_HOSTS_VALIDATOR`). The standard release allowlist
rejected hosts it did not recognize, which showed up as Vela appearing in the launcher and then
refusing to open.

"Templated" is the important word. A templated app does not draw its own interface: it hands the
host a template (a list, a search box, a navigation screen) and the host draws it in the car's own
style, and it enforces the template's rules while doing so. The one thing a navigation app draws
itself is the map, onto a raw surface the host gives it.

### The session and its location feed

Each projection is one session (`VelaCarSession`). It starts its own collector on the location
provider and feeds fixes into the shared navigation session, so guidance keeps running with the
phone's screen off and the phone app never opened. It uses the same fix gate as the phone:

```
GPS provider only, accuracy <= 50 m    // coarser fixes never drive guidance
```

The phone's view model is the other feeder of the same session. They do not run together in
projection, and the session's update is atomic, so a double feed is redundant rather than harmful.

### Why the car map is snapshots

The host hands a navigation app a `Surface`, not a View, and MapLibre's live map is a View that
wants a real window. The first cut (2026-07-08) hosted a `MapView` inside a `Presentation` on a
virtual display bound to that surface. It was replaced the same day by MapLibre's public
`MapSnapshotter`: an off-screen map that renders a camera position to a `Bitmap`. `CarMapRenderer`
draws that bitmap onto the car surface with a plain `Canvas`, then draws the route, the arrow, the
speed badge and the credit on top.

The cost is frame rate. The renderer's own note puts a snapshot at roughly 100 to 300 ms, so the
car map moves in steps that are smoothed by easing rather than at 60 fps. For a map that follows
a car it reads as a moving map; for a map you pan with a finger it is visibly slower than the
phone.

```
TICK_MS = 70     // the render loop's cadence; the snapshot time caps the real rate below it
```

A render asks for one snapshot at a time. A request that arrives while one is in flight marks the
map dirty, and the next snapshot starts as soon as the current one lands, so the map is never more
than one frame behind and snapshots never pile up.

### One renderer for the whole session

Every car screen uses the **same** renderer and only switches its mode: browse (north-up, centered
on you, no route, so a finished drive's line does not linger), preview (the chosen route framed in
blue), and nav (heading-up, following). Per-screen renderers froze the map: handing the host a new
surface callback does not re-deliver the surface, so the new renderer never received one. The
renderer also keeps its snapshotter across screen changes when the surface size is unchanged,
because recreating it reloaded the whole style on every transition and the map flashed.

### Framing: the visible area, the stable area, the arrow

The templates cover part of the surface with their cards and button rows. The host reports two
rectangles, and the renderer uses each for what it is for:

- The **visible area** is what the templates are not covering right now. The arrow is framed inside
  it: while following in nav the arrow sits `PUCK_DOWN` of the way down the visible area, so you
  see the road you are driving into; otherwise the view centers in it.
- The **stable area** is the part no template ever covers, in any state. The speed badge and the
  credit live there, because on a tall head unit the map's button row stacks over the bottom
  corner of the visible area and the badge drew under it (seen on a real unit, 2026-09-22). A
  stable area smaller than 40 px either way is ignored in favor of the visible area.

```
PUCK_DOWN = 0.72                  // fraction of the visible area's height, following in nav
meters per pixel = 78271.517 * cos(lat) / 2^zoom    // MapLibre's 512 px tiles, not 256
```

The 256 px constant put the look-ahead at twice the intended offset and the arrow fell off the
bottom edge.

The arrow is the phone's own puck bitmap, not one drawn by the car code, scaled to the car
screen and multiplied by the Puck size setting (Settings > Navigation):

```
puck = shortSide / 8 * PuckStyle.scale(), clamped to 24..220 px
PuckStyle.scale() = 1.0 normal, 1.25 large, 1.5 extra large
```

A fixed size was wrong on every screen at once: a 22 px radius was a fifth of the height of a
480 px head unit, and a fortieth of the short side read too small. It turns by your heading minus
the camera's bearing, so it points straight up in heading-up nav and along your course in a
north-up view.

### Theme

**The palette is applied from the first snapshot.** The car map uses the same `applyMapTheme` the
phone runs, through a small interface that lets it act on a snapshotter instead of a live style.
The obvious hook, the snapshotter's style-loaded observer, never fires in practice: a style handed
over as JSON finishes parsing before the observer is attached. So the renderer treats the first
returned snapshot as proof the style is loaded, applies the palette, throws that frame away and
renders a themed one. Every car map before that fix (2026-09-22) was stock Liberty under a
darkening filter. The observer is still attached as a free second chance, and the darkening filter
is kept only for the moment before the palette lands.

**Which look, light or dark:**

| Phone theme (Settings > Appearance) | Car map |
| --- | --- |
| Light | light |
| Dark, AMOLED | dark (AMOLED gets the true-black palette) |
| Auto | the sun the phone already computes |
| System | the car's own day/night signal |

Google's app follows the car. Vela has a theme setting, so an explicit choice is honored on the
car too; only "System" defers to the head unit. A driver who had set Vela to dark got a light car
map because the head unit said day, which is why the rule exists.

**Re-theming.** The car flips day and night on its own. Before every render the renderer checks
whether the look it applied still matches, and re-applies the palette if not, so a drive that
starts in daylight goes dark with the car.

The credit is one line, `© OpenStreetMap`, the phone's own text. The library's overlay printed
every tile source's attribution as a watermark, and turning the logo off does not remove it, so
the snapshotter's overlay hook is overridden to draw nothing (`QuietSnapshotter`).

### Following, zoom and pan

The arrow does not jump from fix to fix. It rides the phone's between-fix estimator, which
integrates your speed along your course and folds each fix in as a correction over about a second.
While navigating it is also snapped onto the route when a fix is close enough, so it rides the
road:

```
SNAP_MAX_M   = 40.0    // map-match to the route within this distance, else the raw fix
STOPPED_MPS  = 1.0     // below this the GPS course is noise
BEARING_EASE = 0.22    // per tick
ZOOM_EASE    = 0.06    // per tick, so a zoom tier change takes about a second
RECENTER_MS  = 6000    // after a pan or pinch, snap back to following
```

Heading while navigating comes from, in order: the GPS course when moving faster than
`STOPPED_MPS` with a bearing accuracy of 45 degrees or better; else the bearing of the route
segment you are on; else the last heading held. A nearest-vertex bearing flickered between
neighboring points on parked-car jitter and swung the whole view.

The nav zoom tightens as you slow down:

| Speed | Zoom |
| --- | --- |
| under 15 km/h | 17.5 |
| under 40 km/h | 17.0 |
| under 70 km/h | 16.3 |
| under 100 km/h | 15.7 |
| 100 km/h and up | 15.2 |

The zoom buttons step one level (range 2 to 20), and a pan or pinch stops following until
`RECENTER_MS` has passed or you press recenter. The overview button frames the remaining route
north-up and stays put until you press it again or recenter.

### What the car map draws

- **The route**, split at the arrow: gray behind, blue ahead, with Google's congestion spans
  painted amber, red and dark red over the blue.
- **Corridor dots** from zoom 13.5: lights, stop signs, level crossings and speed humps from the
  phone's corridor fetch, speed cameras, and plate cameras along the route when the camera layer
  is on.
- **The speed badge**, in your units, with a round limit sign beside it when the offline graph has
  the road's limit.

The spoken alerts the phone raises (a camera ahead, speeding, a destination closing before you
arrive) also arrive as a car toast, the template's one transient surface, because a muted car
heard none of them.

### The screens

**Landing (`MainCarScreen`, `PlaceListNavigationTemplate`).** Home, Work, recents and saved places,
de-duplicated by location. The template throws if handed more rows than its cap:

```
MAX_ROWS = 6
```

**Search (`SearchCarScreen`, `SearchTemplate`).** While you type, the autocomplete answers (one
small request, biased to where the car is); the full search runs only when you submit, or when you
pick one of the bare query rows the autocomplete returns ("Starbucks"). Contacts, when contact
search is on, lead with up to two rows. Up to six rows in all.

```
debounce       = 300 ms
SUGGEST_SPAN_M = 20_000.0   // the autocomplete's window around the car, a town
```

It used to run the full search on every keystroke: three result pages plus the nearby pass per
letter. Canceling a coroutine does not abort an HTTP call already on the wire, so a typed word
queued a dozen requests behind OkHttp's per-host limit and the spinner waited for all of them.
Now a superseded keystroke's request is canceled for real (the cancellation is rethrown, never
turned into an empty list), and only a submit shows the spinner; while typing the previous rows
stay up until the next answer replaces them.

**Route preview (`RoutePreviewCarScreen`, `RoutePreviewNavigationTemplate`).** Driving routes from
the same directions call the phone makes ([chapter 5](05-routing.md)), with live-traffic times. It waits up to 15 s (30 polls,
500 ms apart) for a first fix. The template takes at most three routes and requires a duration or
distance span on every row, and it refuses a non-loading list without a Go action, so an empty
result shows a plain message instead. Pressing Go names a provisional route first, the way the
phone does, and starts the drive with the Vela voice when it is installed and chosen.

**Drive (`ActiveNavCarScreen`, `NavigationTemplate`).** Covered in the next two sections.

**Search along the route (`AlongRouteCarScreen`, two `ListTemplate`s).** The phone's quick
categories as rows, each with the map's own category marker; a pick searches around the car and
lists up to six results by distance; a result becomes the **next** stop through the same call the
phone's in-drive search uses. It is two lists rather than a search box because the host refuses
typing while the car is moving.

### The turn card and the cluster

The host draws the turn card, not Vela, and it needs two separate things before it will:

1. **`NavigationManager.navigationStarted()`**, after the navigation callback is set. Without it the
   host shows the arrival estimate and never the turn card, which is exactly what the first
   version did. The call must be balanced with `navigationEnded()` (a second start throws), so the
   screen tracks it and ends it on arrival, on stop, and when the screen is destroyed, or the host
   stays wedged in a "navigating" state for the next session.
2. **`updateTrip()`** with a `Trip`: the current step with its distance and time, and the
   destination with its estimate. This is the host's navigation data channel, separate from the
   template, and it is what feeds the instrument cluster and a head-up display through the
   `FEATURE_CLUSTER` category. Without it the host logged that it had no navigation source.

`ManeuverMapper` translates Vela's maneuvers into car maneuvers. Two details worth knowing:

- **Roundabouts** take their direction of travel from the route's own geometry when it has one,
  and counter-clockwise otherwise; the exit number comes from the router. The car API throws on a
  roundabout without an exit number, so 1 is the floor, not a guess.
- **Far turns** lead with the road you are on:

```
CONTINUE_FAR_M = 1_500.0   // past this, the card says "Continue on <road>" and the turn is the "then" step
```

The road is the phone's rule for its road-name pill: the road the last maneuver entered, following
its silent renames. Up close, the turn leads and the one after it shows as "then". Lane guidance
is drawn as a bitmap of arrows (valid lanes white, the rest dimmed) plus the lane data the host
uses for placement, for up to eight lanes.

Distances round like the phone's: to 10 ft (or m) below 100, to 50 above, switching to miles at
1,000 ft and to kilometers at 1,000 m, with 0 allowed so the host can say "now".

A paused drive replaces the turn card with a "Paused" message; pausing itself is the phone's pause
([chapter 4](04-navigation.md)).

### The button rows

```
action strip:   4 actions   // the template's cap
map strip:      recenter, zoom in, zoom out, overview
```

The drive's strip is mute (or a faster-route offer when one saves at least a minute, which takes
the mute slot because there is no fifth slot), pause or resume, search along the route, and end.
Every one is **icon-only** (`ic_car_*`): titled actions are drawn by the host as text pills across
the top of the map, and on a real head unit they read as "Mute Pause End" written over the road.
End is a red X, and it has to carry the primary flag, because the host throws when a background
color is set on anything else.

### The voice

The voice is the phone's, sent to the car as Android Auto's guidance audio. A drive started from
the car picks the same engine the phone would (the Vela voice when it is installed and no other
engine was chosen), and the service attaches the neural voice itself when the phone UI never ran,
which it did not do before 2026-09-21.

It will still sound duller in the car than on the phone, and that is the protocol:

```
Android Auto guidance stream = 16 kHz mono
```

Every navigation voice is band-limited on the car, Google's included. A head unit set to play
navigation prompts over the phone-call link makes it 8 kHz.

### The install gate

This section says what was observed and what does and does not work. It is not a guide to getting
past Google's checks.

**What a car log showed** (2026-09-22: a GrapheneOS Pixel 9 with sandboxed Play, Android Auto 17.4,
"Unknown sources" on, and the install fields reading Play as the installer): when the phone
connects, the Android Auto app asks the Play Store who owns each app. Play answered

```
Finsky: PlayGearheadService app.vela, app owners empty
CAR.VALIDATOR: Package DENIED; failed all other checks [app.vela]
```

and the same two lines for CoMaps and Organic Maps. The check is **Play's own install record**,
not the installer field on the phone.

**What that rules out:**

- **Installer spoofing.** Setting the install source to Play does not pass, and neither would a
  stub package named like Google's installer: the question goes to Play, and Play never installed
  the app.
- **The "Unknown sources" developer toggle.** It was on in that log and did not cover a navigation
  app.
- **GrapheneOS.** No path is known. The one method seen to work on a stock phone (below) goes
  through a Google package GrapheneOS does not ship.

**What has been seen to work:**

- **A stock Pixel**, with KingInstaller's method that routes the install through Google's own
  package installer (`com.google.android.packageinstaller`): Vela was listed.
- **Aftermarket head units** with their own Android Auto receiver, which can be more lenient than
  a factory unit's: a user reported Vela listed after KingInstaller plus an ADB install.
- The older notes in [docs/ANDROID-AUTO.md](../ANDROID-AUTO.md) describe the toggle and the
  installer spoof as the usual fix; the car log above is newer and says the factory path checks
  more than either.

**Test tools that do not answer the question:**

- **Google's Desktop Head Unit** (the head-unit simulator) listed and ran a plain sideloaded Vela,
  with no installer claim at all, on a stock Android 14 phone with Play installed and no account
  signed in. Its log shows why that proves nothing: no ownership lookup happened, and Play noted
  Vela only as an untracked package. The Desktop Head Unit skips the ownership gate, so it is a
  **preview tool only**.
- **Gearslip's "Car preview"** (its debug mode; Gearslip is a separate project by a contributor on
  issue #179) renders Vela's car screens on the phone through the same host path a head
  unit gets. It is what found the theme bug above, and like the simulator it tells you nothing
  about the gate.

**What Vela does about it.** Nothing in this repository opens the gate, and it is settled that
nothing can (issue #179). What Vela does is not break the workarounds people already use. An
install that claims Play as its source loses that claim the moment Vela updates itself, and the
car drops Vela until it is reinstalled the same way. So when `InstallSource` sees Play recorded
as the installer on a build that is not distributed there, the in-app updater stops and says so
("This update will drop Vela from Android Auto"), and offers the downloaded APK as a file instead;
"Update anyway" installs it and loses the listing. Settings > About shows which package is
recorded as the installer, so you can check before and after an update.

**What is planned or was considered:**

- **The ownership experiment.** `-PappId=<id>` builds Vela under another package name (never a
  shipped build). Sideloaded under the id of an app the account once installed from Play, it
  answers one question: is the check Play's library record alone (it passes), or the signing
  certificate as well (it fails)? It has to run in a real car, since the simulator skips the check.
- **A "receiver" app on Play** that holds the car entitlement and shows what Vela renders. The
  problem is the review, not the code: a Play app that draws a map on a car screen has to declare
  the navigation category, which puts the receiver itself through Google's review of navigation
  apps, and an app whose map comes from a second app outside Play behaves differently from what
  that review saw. The roadmap's Play section explains why that risk lands on the whole developer
  account.
- **A Play listing of a Google-free flavor**, the honest version of the same idea, and a
  phone-side sender that talks to a head unit without Google's app in the loop. Both are in
  [ROADMAP](../../ROADMAP.md) under "A Google Play listing", with what each would take.

## Limits

- **The gate.** On a factory head unit with a stock Android Auto setup, a sideloaded Vela is not
  listed, and nothing in the app can change that. See above.
- **Not re-checked on a unit.** The last rounds of car work (the icon-only strips, the stable-area
  badge, the first-snapshot theme, the category markers, the new search) were built from photos
  and logs of a real head unit and checked in Gearslip's preview, but not yet on a head unit again.
- **The map is a slideshow with good easing.** Snapshot rendering caps the frame rate well below
  the phone's. A live car map needs a View-backed renderer the template surface does not offer.
- **Search along the route searches around the car**, sorted by distance, not along the route
  ahead, and a pick always becomes the next stop.
- **No route options on the car.** The preview is driving only, shows at most three routes, and
  has no avoid switches; it uses whatever avoid settings the phone has.
- **A drive started from the car with the phone app never opened** has no navigation controller on
  the phone, so it gets no corridor dots and no alert toasts.
- **The speed-limit sign needs a downloaded region.** The phone falls back to an online limit
  overlay; the car does not.
- **The cluster is only as good as the car.** Vela sends the current step and the destination
  through `updateTrip()`; what a given car's cluster or head-up display does with it is up to the
  car.
- **`CAR_INFO` is declared but unused.** The manifest asks for the car's own speed on Android
  Automotive, but nothing reads it yet; the speed badge is GPS speed.
- **The voice is band-limited** by the protocol, 16 kHz or 8 kHz as above, and no setting in Vela
  changes that.
