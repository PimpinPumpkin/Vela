# Vela - maintainer notes

Vela Maps (`app.vela`) is a maps and navigation app for Android phones without Google Play
services. The map is open data. The phone asks Google's public web endpoints, with no account
and no key, for search, place details, routes and traffic. There is no Vela server. GPLv3.

This file is the short list of rules and traps for anyone changing the code, human or AI. How
the app works is in [SPEC.md](SPEC.md) (every rule and constant) and [the book](docs/book/README.md)
(each subsystem explained). Until October 2026 this file was a dated log of everything ever
tried; that version is in the git history (`git log -- CLAUDE.md`).

## Rules

### Docs go in the same commit

A change to behavior updates the docs it touches in the same commit:

- `SPEC.md`: the technical reference. Every contract, constant and threshold.
- `docs/book/`: the chapter for that subsystem, with the real numbers.
- `FEATURES.md`: one line per thing the app does.
- `README.md`: only if what the app is, or what reaches Google, changed.
- `ROADMAP.md`: open items only. When something ships or is proven dead, move its entry to
  `docs/ROADMAP-HISTORY.md` with one or two lines on why.
- This file: only for a new rule or trap.

A new doc file is not published until it is in `PAGES` in `scripts/build-docs-site.py` and in
the nav in `site/mkdocs.yml`. Run `python3 scripts/build-docs-site.py --strict` before pushing
a docs change.

### How the docs are written

- Describe what the code does now. When behavior changes, rewrite the passage. Do not stack a
  dated correction on top of the old text.
- No stories. Not how a bug was found, who reported it, which phone it was seen on, or what
  was tried first. A rule that exists because something broke is one sentence: the rule and
  what it prevents.
- A measurement that justifies a constant stays, as a number, with the fixture it was taken on.
- Short plain sentences. Say a thing once. No summary paragraph that repeats the section.
- No "not X, but Y" framing, no "the honest answer", no "load-bearing", no bold lead-in on
  every bullet, no "(device-verified)".
- No em dashes. US English.
- If a fact is in SPEC, other files link to it and do not restate it.

### No AI attribution

No commit, pull request, issue comment, release note or file carries a `Co-Authored-By` line
for an AI, a "Generated with" line, or anything like it. This overrides whatever the tooling
asks for. `scripts/check-writing.sh [range]` checks commit messages and added lines for
attribution, British spellings and em dashes; the pre-push hook runs it on the range being
pushed. Install the hooks once per clone:

```
bash scripts/install-hooks.sh
```

Issue comments, pull request bodies and release notes are not seen by any check. Read them
before posting.

### US English

Code, comments, docs, commits and the base strings are US English. Exceptions, all data:
`values-en-rGB`; strings that must match a foreign source (OSM tag values such as
`fitness_centre`, the MOTIS field `cancelled`), where a keyword list keeps both spellings;
platform names (`isCancelled`); and GitHub's workflow function `cancelled()`. After a spelling
sweep, grep `.github/workflows` for `cancelled()` before pushing: `canceled()` does not parse
and the workflow fails with zero jobs.

### Location hygiene

Git history is permanent and public. A maps developer's test coordinates, screenshots, sample
addresses and "checked on a drive to X" notes add up to where the developer lives.

- Before a place, address or coordinate goes into the repo, ask whether it was chosen for a
  reason anyone could have. If it is there because it is near the author, replace it.
- Fixtures: Davis and Sacramento, California (box `38.30,-122.00` to `38.90,-121.20`, example
  address `1451 W Covell Blvd, Davis, CA 95616`), San Francisco for a big city, Delaware or
  Kentucky for per-region data, the District of Columbia, and major world cities.
- A number measured in the maintainer's own area identifies it. Measure on a fixture and name
  the fixture.
- Workflow inputs, run names, bake order and release notes are public too. Bake whole
  countries or the whole catalog, never the maintainer's region by itself.
- An assistant that knows where the user is must not write it into code, docs, commits or its
  own notes. Use it while reproducing a bug; write up the mechanism only.
- Screenshots use Settings > Diagnostics > Simulate my location and Simulate driving. Check
  the corners: recents, labels and street names.
- Recorded trips, diagnostics exports and adb dumps hold raw GPS. Never attach them to an
  issue or a commit. The in-app Share on a trip trims the ends (`core/replay/TripScrub`).
- A log line never carries a coordinate, typed text or a place name. A page probe logs
  `location.pathname.split('/@')[0]`: Google puts the session's location after `/@`.
- `scripts/check-location.sh` runs in the pre-push hook against a private list at
  `~/.vela-location-terms` and fails the push when a term matches. It never says which. CI
  runs the same test from the `LOCATION_TERMS` secret. After editing the list:
  `gh secret set LOCATION_TERMS < ~/.vela-location-terms`.

### What Vela never does

- No Google Play services, Firebase, Fused location, Play Integrity or any Google SDK.
- No static Google API key. Requests are made the way a logged-out browser makes them.
- No Vela backend, account or telemetry.
- No proprietary assets in the repo (fonts, map data, voices).

## Build

```
./gradlew :app:assembleRelease          # what goes on a phone
./gradlew :app:compileDebugKotlin       # compile check
./gradlew :core:test :app:testDebugUnitTest
```

- Run release builds on a device. A debug build drops frames (14.8% janky against 1.1% on a
  Pixel 4a for the same gestures) and reads as a performance bug.
- Never uninstall Vela from a test phone: it deletes saved places, trips and permission
  grants. Install over it with `adb install -r`, with `-PappVersionCode=` set to the installed
  build's code. A side-by-side test build is `-PappId=app.vela.dev`.
- A fresh clone needs the vendored binaries first (OsmAnd router jars, the Cronet AAR, the
  sherpa-onnx AAR). `docs/BUILDING.md` lists them.
- Toolchain versions are in `gradle/libs.versions.toml` and `gradle.properties`. compileSdk
  37, targetSdk 35, minSdk 26, Java 17. AGP 9 builds Kotlin in: there is no
  `org.jetbrains.kotlin.android` plugin and no `kotlinOptions`.
- Do not take core-ktx 1.19, navigation-compose 2.10 or hilt-navigation-compose 1.4 in
  passing. They pull Compose from 1.7 to 1.10 across the app, which is its own job.
- The Gradle setup action stays on v5. From v6 its caching is closed source under Gradle's
  terms. Every action in every workflow is pinned to a commit SHA, and a new workflow declares
  `permissions:`.
- Library versions held for the build tools' sake (lint, the plugin classpath, the baseline
  profile module) are at the top and bottom of the root `build.gradle.kts`.

### MapScreen is at two size limits

`MapScreen` is at the JVM's 64 KB method limit and at ART's verifier limit. The debug compile
catches the first ("Method too large"). Nothing at build time catches the second: the release
build compiles, then dies at launch on Android 14 with `VerifyError`, and on Android 16 the
screen silently stops recomposing.

- Add no new call or parameter to `MapScreen`. Put new pieces in a child composable or a
  holder object (`RouteActions`, `SavedActions`, `ShapeActions`, `MapFloaters`, `NavCorner`).
- After any edit to it, build debug and release, install the release build, and read logcat
  for `VerifyError`.

## Releases

- `canary` is the working branch. Pushing it replaces the one rolling `canary` release.
- `main` builds and tests on push. A daily job (10:30 UTC) cuts a nightly prerelease
  `v0.4.<run>` when main has moved. Mondays 16:00 UTC the newest nightly is promoted to stable.
  Do not dispatch CI per merge.
- Releases are cut inside `ci.yml` because the version code is `(2000 + run number) * 10`
  plus a chip digit. Another workflow would restart the count.
- Commit subjects are the changelog users read in the app. Write them as plain sentences
  about what changed for the user. Start a docs-only commit with `Docs:`; it is left out, as
  are changes that only touch docs paths.
- A stable's notes lead with a short hand-written "What's new" list. The in-app dialog shows
  the release body as it is.
- `release.yml` ("Release now") takes main's head to a nightly or a stable in one manual run
  and is the only by-hand path: it cuts through `ci.yml` and promotes the build it cut, with
  the What's new list as an input. It is the maintainer's to start, or to approve each time.
- Never name a release `v0.4.0` by hand. The updater reads the run number out of the tag.
- Contributor pull requests are read in full and tested before they land, and are taken by
  cherry-pick with the author kept.

### The other releases are the download backend

Every tag that does not start with `v0.` is file hosting: voices, speech models, the Cronet
and sherpa builds, routing regions, place packs, the places and basemap archives, overlays,
map fonts, road features, cameras, grid cells. Those files exist nowhere else.

- Anything that deletes or edits releases selects by the tag pattern `v0.*`. Never by
  "prerelease" or age. A cleanup that did otherwise took four offline features down.
- The repository has hundreds of releases. Page through `gh release list`, or bound it by tag.
- A release that is one of many (the `cells-<region>` set) is created on the root commit.
  GitHub sorts by the target commit's date and Obtainium reads the first hundred.
- The Actions token has 1,000 API requests an hour for the whole repository. GitHub calls in
  a workflow go through `scripts/gh-retry.sh`, and a bake leaves 200 for everything else.
- Bakes have no schedules of their own. `bake-conductor.yml` starts one at a time from
  `tools/bake-schedule.json`. A new bake is an entry there.
- Every OSM extract download goes through `scripts/fetch-pbf.sh`.
- A manifest is derived from the files on its release (`scripts/repair-*-manifest.sh`), never
  from one run's own output. A pending job in a concurrency group is canceled when a newer
  run joins, after its files are already uploaded.
- Under `set -o pipefail`, `gh api --paginate ... | head -1` dies with exit 141. Use `sed -n 1p`.

### GitHub Pages is one artifact

`fdroid-repo.yml` deploys the landing page (`site/`), the F-Droid repository (`/repo`), the map
fonts (`/fonts`) and the docs site (`/docs`) together. A second Pages workflow would replace
all of it. Never rerun only a failed deploy job; start a fresh run.

### Keys

- App signing: `~/.vela-signing/vela-release.jks`, repo secrets `VELA_KEYSTORE_*`. The
  certificate's SHA-256 is published in the README.
- F-Droid index: `~/.vela-signing/fdroid.p12`.
- Remote settings: `~/.vela-signing/vela-calibration.key`. The public half is pinned in
  `CalibrationStore.PINNED_PUBLIC_KEY`.

None of these is ever committed.

## Remote settings (`calibration.json`)

The request templates, response paths, word tables, fleet defaults and tuning dials are in a
signed file the app fetches at launch (SPEC 11).

- To ship a fix without a release: edit `calibration.json`, raise `version`, run
  `./scripts/sign-calibration.sh`, commit the file and its `.sig` to main.
- `Calibration.DEFAULT.version` stays 1 so that any published file wins.
- A new field in `Calibration` also needs reading in `CalibrationStore.parseBundle`. Grep for
  the field name there. Fields have been added and never parsed.
- A new Google request goes into calibration the day it ships.
- Before pushing a change, run the health probe against it:
  `./gradlew :core:testDebugUnitTest --tests '*GoogleHealthProbeTest' -DvelaLive=true`.
- Read the live file before saying a dial is off. Compiled defaults and fleet values differ.
- A dial that opens the app up for inspection (WebView inspector, network log, reply dumps)
  reads `AppTune.localOn`, which only adb can set.

## Layout

- `:core` has no UI. It holds everything that talks to Google and the open services, routing,
  navigation, parsing, and the offline stores. `:app` is the Compose interface. MapLibre and
  Android UI types stay out of `:core`.
- `core/data/MapDataSource` is the one seam. `GoogleMapsDataSource` implements it.
- `:app` cannot be read from `:core`. A setting that must act inside `:core` is mirrored into
  a flag there (`NoGoogle`, `LowRamMode`, `LowDataMode`, `CategoryFilter`, `RoutingPrefs`).
- Settings are process-wide holders (`ui/AppTheme`, `ui/Units`, `ui/PlaceContent.kt` and the
  like), started in `VelaApp`. A holder that is not started there reads its default on every
  launch.
- `MapViewModel` is large. Navigation lives in `NavController`, reached through `NavController.Host`.
- A hidden Google page is a subclass of `web/HiddenWebView`. Never copy the WebView plumbing.
- `NavSession` counts stops only while the engine's route (`_state.route`) is the very object
  in `planRoute`. Replace both together or neither.
- `MapViewModel` and `NavController` properties that an `init` collector touches are declared
  above `init`. `viewModelScope` is `Main.immediate`, so a collector's first pass runs inline,
  before anything declared below exists.

## Traps

### Kotlin and Android

- In a Kotlin raw string, `${'$'}{x}` is not a template. It emits the literal text.
- A `$name` followed by a CJK character parses as one identifier. Write `${name}`.
- In `strings.xml` a raw apostrophe fails the release resource merge, and a raw double quote
  is silently removed. Escape both. A warm Gradle daemon hides this; CI does not.
- Match a placeholder's type to its argument. `%d` given a String crashes.
- Android's regex is ICU. `\p{IsHan}` and an unbalanced `}` throw on a phone and pass every
  JVM test. Use `Character.UnicodeScript` or `\p{Han}`, and `Regex.escape` for literal text.
- A `Regex` that throws in an `object` initializer takes the whole object down for the life of
  the process. Never build a `Regex` inside a per-item loop either.
- A literal `*/` inside a KDoc comment (a wildcard mime type, `del_*/ins_*`) ends the comment.
- `android.location.LocationListener` is implemented as an explicit object with all four
  callbacks. The lambda form crashes on Android 10 and below.
- `Locale("zh-TW")` makes a bogus language. Use `Locale.forLanguageTag`.
- Read the device locale from `Resources.getSystem()`. `Locale.getDefault()` is the in-app
  language once `AppLocale.wrap` has run.
- The 12 or 24 hour clock is a device setting, not the locale. Use `Clock24` / `ClockFormat`.
- `InputStream.readNBytes` is API 33. minSdk is 26.
- An early `return@forEachIndexed` inside a composable lambda can crash the Compose compiler.
- `withTimeoutOrNull` only interrupts at a suspension point. A blocking socket read or the
  offline router's native compute outlives it. Run those on an unstructured scope so the
  deadline can abandon them, and never gate later work on that job's liveness alone.
- In app unit tests `org.json` is a stub and `android.util.Log` throws unless
  `isReturnDefaultValues` is set.
- aapt un-gzips and renames an asset ending in `.gz`. Use a neutral extension.
- `PolylineCodec` handles 5, 6 and 7 decimals. Keep its accumulators 64-bit.
- A "changed since last frame" test against a value that starts as NaN is never true. Test
  `isNaN()` first.
- `File.renameTo` fails across volumes, and the cache folder is always internal. Stage a
  download beside its target, and move the old copy aside before the swap, never delete it.
- A point at a distance along a line is `RouteProjection.pointAt` (a binary search). A scan
  from the start inside a loop over the same line is quadratic on a long route.

### Network

- The shared OkHttp client has a 12 second call timeout. A download through it stops
  mid-body, the error is swallowed, and the file silently never installs. Every large
  download uses a client with `callTimeout(0)`.
- On a Google host `callTimeout(0)` is not unlimited: the Cronet transport bounds it at 30 s.
  A Google reply of megabytes takes a client with a set deadline
  (`GoogleMapsDataSource.largeReplyHttp`).
- Parse a large response from the stream into a small DTO. Reading the body to a string and
  building a JSON tree holds five to ten times the wire size.
- Every request that can reach Google goes through the shared client, which counts it and
  hands it to Cronet. A separate client breaks the count and the browser identity.
- Google requests send the calibrated browser identity (`calibration.current().userAgent`,
  `BrowserHeaders`). OSRM, Valhalla, Nominatim, Photon, Overpass and Transitous get
  `VelaConfig.VELA_UA`, the honest one. Never mix them.
- Never add a Google request value every install sends identically. Derive counters, callback
  names, spans and viewports per session (`RequestShape`, `BrowserViewport`).
- Timed Google requests are spread with `core/util/Jitter`.
- Never probe Google's review feed from the maintainer's own connection.
- Any new Overpass query goes through `OverpassEndpoints.run`. Nominatim's maintainer asked
  Vela off public Overpass for bulk work; baked files are the first source.
- A long download runs through `MapViewModel.downloadLaunch`, which outlives the screen and
  holds a foreground service, and it shows a Cancel wherever it shows progress.
- An empty answer from the nearby-places fan-out is a failure, not a result. Never cache it.

### WebViews

- Every WebView calls `WebViewIdentity.apply`. Never set `userAgentString` by hand.
- A script that calls a bridge goes through `JsNames.of`, which swaps in the per-process names.
- A new WebView-built fetcher calls `SessionRotation.consumeCacheClear`.
- Google's tab and button labels embed the place name. Strip it (`STRIP_PLACE_NAME_JS`) before
  testing a label for a word.
- A hidden WebView is 0 by 0 unless given an offscreen viewport, sized in CSS pixels times
  density.
- Pause a hidden WebView when its fetch is done. A loaded Google page keeps a compositor and
  timers running.
- A Compose dialog cannot be made to cover the system bars. A full-screen viewer leaves the
  bars visible and draws a gradient under the status bar.

### The map (MapLibre)

- `maxzoom` on a layer is exclusive.
- Past a GeoJSON source's `maxzoom`, every visible tile lays out all of its parent's
  features. A dense source gets maxzoom 16 to 18.
- An 8-digit hex color string is rejected and falls back to opaque black. A layer at opacity
  0 is skipped at render and returns nothing to `queryRenderedFeatures`. An invisible but
  queryable layer is black at opacity 0.004.
- An unstyled `LineLayer` draws black. A new twin layer is colored in every palette function.
- A `fill-pattern` from the style cannot be cleared. Hide the layer and add a flat twin.
- An image added under a name the sprite also has loses to the sprite. Use `vela-` names.
- Draw order and collision order are the same thing: the topmost symbol layer places first.
  A layer that must not evict icons goes below them.
- A filter change or a data-driven paint change re-runs placement for the whole layer. Do not
  key either on drive progress.
- The style's own source id changes when a downloaded basemap is mounted. Reach the basemap
  source through `basemapSrc(style)`.
- Liberty's layers that the palette functions do not name keep their light colors in dark
  mode. Name new ones.
- `querySourceFeatures` and `queryRenderedFeatures` block on the render thread. Gate them by
  time and distance, never per frame or per idle event.
- Camera-idle fires after every `moveCamera`, 60 times a second while a ticker follows.
- A SurfaceView's window hole does not follow a Compose resize. Recreate the view.
- An interceptor on MapLibre's HTTP client is installed after `MapLibre.getInstance`.
- The pmtiles path never cold-fetches a tile two or more levels under the camera. A layer
  whose archive stops at z17 arms at z17 and gates visibility by opacity.
- The bundled style JSON is one minified line. Edit it with a script that re-dumps compact.
- Never keep a `Layer` object from `style.layers` past the call that fetched it. Keep the id
  and the style, and look the layer up again on that same style: after a reload the old
  objects point into a style that is gone.
- `setAllGesturesEnabled(true)` turns every gesture on, tilt included. Apply a gesture setting
  after it.

### Frame rate

- Measure before changing physics. Record the screen and take a Perfetto trace. A hitch at
  the GPS fix rate is main-thread work.
- `dumpsys gfxinfo` cannot see the map, which draws on its own GL thread. Use
  `debug.vela.fps` (read at map creation) and `scripts/map-fps.sh`.
- `debug.vela.hide "<prefix or type:symbol>"` hides layers for bisecting.
- Check thread CPU first: `adb shell top -H -b -n 2 -d 5 -p <pid> -o TID,%CPU,CMD`.
  MapLibre's native threads inherit the name of the Java thread that started them.
- Test in a dense city at replay speed, with cool-downs. The 4a throttles after a few minutes
  and every number drops.
- A layer A/B (`debug.vela.hide`) alternates its arms in a balanced order and has a placebo arm
  with some other layer hidden. The first sweep into a zoom level pays for cold tiles, so the
  arm that always goes first looks worse. Compare total frames over identical gestures.
- A `withFrameNanos` loop needs an idle exit, and every write in it is change-gated. A parked
  drive must draw nothing.
- Anything that must sit still on screen while the map moves is a Compose overlay projected
  from the camera, not a GeoJSON symbol updated per frame.
- Route geometry is never moved per frame. The moving cut is a paint change on a short piece.
- An `alpha` below 1 on a `graphicsLayer` renders offscreen. Use
  `CompositingStrategy.ModulateAlpha`.
- Read fast-moving values in the layout or draw phase. Give composition threshold booleans.
- No blocking IPC or file IO in a composable body. Use `produceState` on `Dispatchers.IO`.
- A new large or native allocation registers a releaser with `MemoryPressure`.
- Do not unbound the nearby-places fan-out (four parses at a time). Unbounded, it filled the
  heap and stalled every allocation.

### Compose

- A Compose `DropdownMenu` or `AlertDialog` cannot be given focus. Use `VelaMenu` and
  `VelaDialog`.
- Every screen opens with something focused (`rememberDpadAutoFocus`), every control has a
  focus ring (`dpadHighlight`), and every gesture has a key path. D-pad code calls the touch
  paths. `docs/dpad.md` has the rest. CI runs `dpad_test_suite/audit_static.sh`.
- D-pad detection counts a touchless device or a physical D-pad only. The virtual input
  device reports a D-pad on every phone.
- `adb shell input text` flips the app into keyboard mode. Type test input by tapping keys.
- A `LaunchedEffect` keyed on a tick fires on first composition too. A sheet that takes a
  tick remembers the value it mounted with.
- A drag list keeps one modifier chain and varies the values. Changing the chain when the
  drag starts kills the gesture.
- An `IconButton` is 48 dp at least, whatever `Modifier.size` says.
- Read light or dark with `isAppInDarkTheme()`, and for things drawn on the map
  `isMapDark()`. Never `isSystemInDarkTheme()`.
- Read wallpaper colors with `wallpaperColorsInUse()`, not the switch.
- Chrome and transient surfaces take `MaterialTheme.colorScheme`. Content inside a place or
  results sheet takes `SheetPalette`. On a colored container use that container's own
  on-color.
- Every chip is a stadium pill (`CircleShape`). A confirm button is a filled pill.
- A sheet is a hand-driven `Animatable` read in the layout phase (`sheetDragGestures`,
  `SheetFold`). Do not add another gesture system.
- New content above the place sheet's action row reserves its space from the first frame.
- `LocalConfiguration` is provided by `MainActivity`, which handles rotation itself.
- Icons are `Sym` and `SymOutlined`, generated by `scripts/gen-symbols.py --rewrite`.
- New map chrome goes inside the `!pipUi` gate and gets the landscape column treatment
  (`landscapeColumn`).

### Android Auto

- Every `Maneuver` sent to the car has an icon. The host draws no turn card for one without.
- When a new car `Surface` arrives, release the old one. It holds the buffer queue's connection.
- A row image that has colors is `Row.IMAGE_TYPE_SMALL`. The host tints `IMAGE_TYPE_ICON`.
- Only `VelaCarSession` pushes the drive screen.
- `updateTrip()` goes out at most once a second. The host drops faster ones.

### Data and bakes

- A rule in `tools/build-places-region.sh` that relates rows to rows needs an equality to
  hash on. An OR of tests or a correlated subquery does not finish on a state.
- The script's DuckDB block is an unquoted heredoc. A backtick in a SQL comment runs as a
  shell command.
- Never edit a bake script while a bake is running. Bash reads it as it goes.
- Prune a cloud parquet read on its `bbox` column. A test on computed coordinates reads the
  world.
- DuckDB, tippecanoe, go-pmtiles and planetiler are pinned. Move a pin only after a local bake.
- A new rule names the old Overture category. `tools/overture-taxonomy-map.csv` maps the new
  names back.
- `PlaceNames` is the one same-business rule, shared by the app and the bake.
  `tools/place-generic-words.txt` is generated from it; a test keeps them equal.
- Check a bake wave by counting file dates on the release, not by the run's color.
- A region is chosen by its real boundary (`RegionPolys`), not its bounding box. After adding
  a catalog row run `scripts/region-polys.py`.
- A store of downloaded data takes its folder from `StorageLocation.root` on each access and
  adds it to `StorageLocation.FOLDERS`.
- A new preference file or user data file is not backed up until it is in both
  `res/xml/backup_rules.xml` and `data_extraction_rules.xml`.
- A change to a stored format reads the old key once and writes a new key. An older build
  then still finds its own data.
- A list or saved place is matched with `ListPlace.matches` (id or Google feature id), never
  by id alone.
- A new line kind in `TripLog` needs a decision in `TripScrub` on whether it is safe to share.

### Languages

- English strings go in `values/strings.xml`. Translations come from Weblate, whose pull
  requests merge themselves after a check. After one merges, merge `origin/main` into the
  working branch before the next push.
- A string for a feature still behind a switch is not `translatable="false"`. That flag is
  for the app name and the map credits.
- A count that can be 1 is a `<plurals>` with the right categories per language.
- Names, addresses and reviews are data and are never translated.
- Text that decides something (open or closed, transit categories, review page labels) is
  matched against per-language word tables that calibration can replace. When a language
  misbehaves, capture the real text before guessing a word.
- The open or closed state is parsed from the status text. The numeric codes beside it are
  styling.

## Testing on a phone

- Fake the location with Settings > Diagnostics > Simulate my location. Tap the switch, not
  the row. Put it back on Davis afterward.
- A drive with no GPS: Simulate driving. Turn both off for real use.
- A moving test without the simulator: add `gps` and `network` test providers before turning
  location on, feed fixes once a second, and turn location off before removing them.
- Another app language: `adb shell cmd locale set-app-locales app.vela --user 0 --locales ja`.
- Large fonts: `adb shell settings put system font_scale 1.3`.
- A fixed time of day for transit and opening hours: `setprop debug.vela.tune.demoClock 720`
  (minutes after midnight). Clear it afterward.
- Any tuning dial: `setprop debug.vela.tune.<key> <n>`.
- Low-memory path: `setprop debug.vela.lowram true`.
- A trip between two points without moving the simulated location: open
  `https://www.google.com/maps/dir/?api=1&origin=..&destination=..` as a view intent. Pass
  the whole `am start` command to `adb shell` as one quoted string, or the shell cuts the link
  at `&`, drops `-p app.vela`, and another maps app opens it.
- A simulated drive's fixes skip the live fix handler (`replaying`). Anything that should run
  per fix in a demo is also called from `NavController`'s replay collector, through `Host`.
- Two-finger gestures cannot be injected with `input`: use `scripts/touch/two-finger.sh`. Its
  `[repeat] [cycles]` arguments play a zoom sweep out and back in one process. A held back
  swipe can, with `input motionevent`. `setprop debug.vela.fps true` logs the camera
  zoom once a second (`VelaFps`), which is how to tell whether a pinch zoomed.
- Log tags worth knowing: `VelaDirections`, `VelaSteps` and `VelaCapture` (set to DEBUG),
  `VelaTap`, `VelaSearch`, `VelaTransit`, `VelaDelta`, `VelaUpdate`, `VelaWeb`, `VelaSession`,
  `VelaFps`, `VelaCar`, `VelaSim`, `VelaPassAlert`.
- The Android Auto desktop head unit shows Vela's car screens but skips the car's install
  check. Book chapter 10 has the setup.
- Restore whatever a test changed on the phone.

Checks to run before trusting a routing change: `core/nav/StepAudit`, then
`NamingStudyTest.replayCapturedLines` (`-DvelaStudy=1`), then a few real trips on a phone with
the step list read by hand. Probe tests that need a region file take `-DvelaObf=<folder>`.

## Where things are written down

| File | Holds |
| --- | --- |
| `README.md` | What Vela is, what reaches Google, install |
| `FEATURES.md` | What the app does, one line each |
| `SPEC.md` | The technical reference |
| `docs/book/` | Each subsystem explained |
| `ROADMAP.md`, `docs/ROADMAP-HISTORY.md` | What is open; what shipped or was dropped, and why |
| `PRIVACY.md` | What each service receives |
| `docs/FAQ.md` | The first questions people ask |
| `docs/BUILDING.md` | Building from source |
| `docs/TRANSLATING.md` | Languages and how to translate |
| `docs/dpad.md` | Operation without a touchscreen |
| `docs/ANDROID-AUTO.md` | Android Auto for users |
| `CONTRIBUTING.md`, `SECURITY.md`, `FDROID.md` | As named |
