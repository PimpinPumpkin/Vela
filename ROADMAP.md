# Vela Maps roadmap

What is still open. [`FEATURES.md`](FEATURES.md) lists what the app does and
[`SPEC.md`](SPEC.md) how it is built. What shipped, and what was tried and does not work, is in
[`docs/ROADMAP-HISTORY.md`](docs/ROADMAP-HISTORY.md).

Add an idea here when it comes up. When an item ships or is proven dead, move it to the history
file in the same commit.

## North star

A Google Maps replacement that needs no Google account, key or Play services, matches Google
Maps feature for feature, and over time depends less on Google by growing Vela's own data.
GPLv3. Every new data flow is opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## The order from here

Set 2026-10-07.

1. Finish and verify what is in flight. The week's bug reports, then a check of everything on
   the main phone.
2. Android Auto without Google Play. The next big push. A car lists only navigation apps that
   Play installed. The next section has the details.
3. The Compose toolkit upgrade. core-ktx 1.19, navigation-compose 2.10, hilt-navigation-compose
   1.4 and the newer material3 pull Compose from 1.7 to 1.10 or 1.11 across the whole app. It
   waits until it can be its own job: a full pass over the sheets, the gestures and D-pad
   operation, on both test phones.
4. Read places from Vela Almanac, a separate open dataset of US places with dated evidence of
   what is still open. It replaces the three closure steps in the places bake and later the
   merge itself. Blocked on its first published files. The bake then reads one file per
   state.
5. F-Droid's own catalog. The official catalog builds every app from source and cannot take
   the prebuilt voice runtime (sherpa-onnx) or the prebuilt Cronet. The work is a build
   flavor that compiles or leaves out each of them, and reproducible output. Not started.

Traffic incidents from official feeds (issue #688) are out of this list. They belong in a
sister project beside Vela Almanac, which Vela would read as data.

## Less to Google

The direction for the Google half: keep what only Google has, and send it less. Candidates,
none built:

- Traffic re-checks from a point ahead. A drive asks Google for a fresh route from its live
  position about every two minutes (`NavSession.maybeRecheck`). Asking from a junction a
  kilometer or two down the route gives the same traffic for the road ahead without the live
  position. The time to that junction is the phone's own estimate, and an offered faster route
  has to be joined to the stretch still being driven.
- Routes computed on the phone with Google's traffic. The on-phone router already answers
  offline. Fed congestion read from the traffic overlay's tiles, it could answer online too,
  and Google would see which map tiles were loaded and never a start, an end or a position. Open
  questions: what the tiles encode per road, how far out they must be fetched for a long trip,
  and how the times compare with Google's own.
- A first run that starts from less. Someone who picks "Use Google" gets every default at once,
  the two-minute re-check included. The same holds for someone who starts without Google and
  turns it on later.

## Android Auto without Google Play

What is known:

- Android Auto lists a navigation app only when Play installed it. Its "Unknown sources"
  switch does not cover navigation apps.
- What the check reads is not settled. One car log shows Android Auto asking Play for each
  app's owners and denying a package that has none. A later report says the record that
  counts is who started the install (`initiatingPackageName`).
- Nothing run from adb changes who started the install. On Android 14 `-i` sets the installing
  package and the initiating package stays the shell.
- It works with a root installer that runs the install as Play, and on stock Android with an
  installer that goes through Google's package installer, which GrapheneOS does not ship.
  Aftermarket head units with their own receiver list Vela after King Installer plus an ADB
  install.
- An in-app update replaces the install record, so on a phone set up for the car the updater
  offers the APK as a file (`InstallSource.setForCar`).
- The Desktop Head Unit skips the install check. It tests the car screens only.

To do, in order:

1. The ownership experiment. `-PappId=<id>` builds Vela under the id of an app the phone's
   Play account once installed. If the car lists that sideload, the check is Play's library
   record alone. If not, the signing certificate counts too. Needs a real car and a stock
   phone signed in to Play.
2. Settle on the pinned issue (#179) which install record the car reads.
3. Run the instrument-cluster session (`ClusterSession`) in a car with a cluster display. No
   car has run it. The Desktop Head Unit's cluster option forwards turn data only, and that
   works.
4. Check the car map gestures (pan, pinch, fling) on a head unit. The Desktop Head Unit takes
   taps only.
5. Test one Android "AI box" dongle (Carlinkit, Ottocast and similar): a small Android device
   on the car's USB port that shows its own screen on the head unit, with no install check.
   Wireless Android Auto adapters do not help. They still run the phone's Android Auto app.

A phone-side sender is the other route: an app that speaks the Android Auto protocol to a
real head unit in place of Google's app, so no install check is in the loop. The open
implementations (aasdk, openauto, the Rust `android-auto` crate) are all the head unit side.
The sender is Gearslip (Seb3thehacker), a separate project. It can be distributed only if
the head unit accepts a certificate we can generate. Key material taken from Google's app or
from head unit firmware cannot be shipped.

Vela's side of that is a small bound service that hands a projection client frames at a size
it names, takes input back, and publishes the nav state. `CarMapRenderer` already renders to
a bitmap and `NavSession` publishes what `ManeuverMapper` reads. Whether to hand out frames
or a URL for a WebView is open. Nav state and input are the same for both.

## Next up

Small items, one pull request each.

- Grid cells for the whole catalog. The bake conductor runs the US group and two world shards
  (`tools/bake-schedule.json`). Next: confirm from `cells-manifest.json` that every region has
  cells.
- A name index for the places archive. Offline search reads the archive in rings out to
  about 3 km (`PlacesArchiveSearch.MAX_RINGS`). Beyond that only the OSM place pack answers,
  and OSM lacks whole chains. Bake a per-region sidecar (name, key, category, lat, lng) and
  query it after the pack.
- Region downloads pull the building overlay. A saved map area downloads the Microsoft
  footprints (`downloadOverlayForArea`) and a whole-region download does not, so a downloaded
  state has no houses where OSM is thin. No bake change.
- More places sources for the open bake: chamber-of-commerce member lists and municipal
  business-license registers, one scraper per source. The survey under "Vela's own record of
  what is still open" says how thin those registers are.
- The neural voice's phonemizer. espeak, in front of the Piper model, misreads text that is
  not prose: "5:49 PM" came out as "five foot nine". `SpeechText` works around it case by
  case. The fix is a model that normalizes its own input and is fast enough on a Pixel 4a.
- Why one basemap layer stalls a dense city. With Liberty's `poi_r20` layer drawn, New York at
  100 ft runs at 0 to 25 fps on a Pixel 4a, and at 59 with it hidden. Vela hides it over
  region archives of rev 20260923 or later (`placesOneSetRev`). Elsewhere the stall remains.
  The cause is unknown.
- Offline timetables per region, an open question. Cached boards cover only stops opened
  online. A bake of GTFS stop times is tens of MB for a mid-size state, plus feed discovery
  per agency and a weekly refresh.
- Review topic chips on the inline Reviews tab. Google's "mentioned in reviews" chips show
  only on the full-screen reviews page (`PanelControls` in `PlaceSheet.kt`).
- "People also search for" after an address or list open. It arrives only with a focused
  search, so it needs a focused name lookup.
- Menu tab reliability. Classify gallery walks that return no tabs. Find out whether the
  gallery request (`hspqX`) can filter by category, so the Menu tab needs no page walk.
- Lanes that continue into the next maneuver, highlighted on a compound maneuver. OSRM gives
  no lane linkage across steps, so it needs a careful heuristic.
- State and province shield shapes from the OpenStreetMap Americana set. `ui/map/RoadShields`
  draws one plain badge for every state route.
- A "download this region" nudge when an avoid option is on, Google cannot be reached and no
  downloaded region covers the trip.
- Parking: a note or photo on the saved spot.
- A resumed drive keeps its stops. After the app is killed mid-drive, the resume offer restores
  the destination, label and mode only: the remaining stops and a saved route's hidden points
  are gone (`NavController.persistNav`).
- On-street bike lanes. Painted lanes (`cycleway=lane`) are not in the OpenMapTiles schema and
  need a baked layer, never a per-viewport Overpass query. The painted-roads test under
  "Richer roads" draws them.
- Street View: walking can step to a neighbor from a different year (the neighbor graph
  carries no date), higher-zoom tiles on pinch-in, and hiding the pill where there is no
  coverage.
- Map label font. To match the app font exactly, rebuild the glyph pack from the app's font
  file (`scripts/build-map-fonts.sh`) and republish `map-fonts`. MapLibre cannot inherit a
  font at runtime.
- D-pad hardware pass on a real keypad phone: pan step, OK-hold threshold, focus ring
  visibility, traversal order, page scroll on the full-screen reviews page. Device tests 04 to
  06 in `dpad_test_suite/` count key presses for one screen size and need target-seeking
  navigation, as tests 01 and 02 have.
- Voice library. Host the catalog (`PiperCatalog`) on the signed `calibration.json` so new
  voices need no app release, with the download host pinned in the allowlist. A preview
  button. One shared `espeak-ng-data` folder (about 10 MB a voice). Larger dictation models.
- Japanese offline voice. Piper has no Japanese phonemizer, so Japanese guidance uses the
  system voice. Kokoro int8 multi-lang (about 126 MB) needs the multi-file sherpa plumbing
  restored, and it ran at about 0.4x realtime when last bundled, so measure first.
- Explore: a sheet of nearby restaurants and things to do from the bare map. Events have no
  keyless source.
- Timed speed limits from a downloaded region. The hosted tiles apply `maxspeed:conditional`;
  the obf path reads OsmAnd's plain limit, so a downloaded region shows 100 where the tiles show
  130 at night. Needs the obf bake to keep the tag and `currentRoadLimit` to read it.

### Built, not yet checked on a drive

Each of these passed its unit tests and has not been seen or heard where it matters. Take one
off the list when it has been.

- Street names filling in mid-drive (`RouteHeal`). A simulated drive never re-checks, so it
  needs a real one. A trip's notes show it: a route whose totals line says `namingLate` or
  `bare=1`, then `recheck upgraded route for names`.
- A route built past the naming deadline (`StretchNamer.inHand`): tile names where the match was
  late, and a finished match kept. Seen on a phone only with the matcher switched off
  (`debug.vela.tune.noMatch`), never with a late one.
- The nudged route line on a street. It was seen on a phone over a parking-lot stretch, where
  Google's line was already near the middle. The totals line says `nudged=<meters>`.
- Two turns said on one line. Not heard on a phone. Dutch, Swedish and Japanese have no joining
  line (`NavStrings.thenNext`), and the joined line is not among the lines the voice renders
  ahead of time (`NavEngine.upcomingPrompts`).
- The next stop on Android Auto, and the trip's own time beside it. Built, not run on the
  Desktop Head Unit.
- A reroute after a wrong turn on a trip with stops. The simulated drive cannot leave its route.
- UK fuel prices on a phone in the UK.

### Open from the October 2026 audit

Found reading everything since 0.4.1912 and not fixed yet.

- The arrow's swap to the map's own symbol during a gesture (`puckGestureSwap`) is off: the
  car vanishes for several frames at each end of a pinch. The overlay is used throughout.
- A replay seek feeds every earlier fix through the engine in one main-thread block. The
  catch-up shares the fix filters and the session with the main thread, so it cannot simply move
  to another thread, and run in slices the map and the turn card would play every earlier route
  and step. It needs a pass over a copy of that state, installed in one step.
- A star put on a picked suggestion or a list row, with the sheet closed before its details
  load, is still kept as a point: the mark comes off only when that suggestion or list row is
  opened again and its details load, not when the place is opened from Saved. A star put on a
  tapped map label un-fills when the tap resolves, because the sheet takes Google's id
  (`isSaved` compares ids).
- Map: where the basemap itself draws the house numbers (no address overlay mounted), their box
  still changes its filter about every three quarters of a screen of panning at house-number
  zoom, and each change re-lays out every basemap tile. A free drive with no route does not hold the
  box back by speed as a drive with a route does. A wider box means fewer moves and more numbers
  drawn per frame; which wins needs measuring on a phone.

### From the first drives on 0.5

- Match Google's line on the phone when the region is downloaded. Street names and lanes for
  the stretches where Google leaves the open route come from a public matching server, and a
  late answer leaves tile names or bare turns. The on-phone router's data could do the match
  with no server. Not started.
- UK fuel prices. The price file is not part of a region download, so offline it is whatever
  the phone last fetched, and it stops showing after two days. If the archive it comes from
  ends, the fallback would be the retailers' own price feeds.
- One settings panel for everything drawn on the map. The switches for what the map draws are
  spread over several settings pages.
- The four Texas parts need their place pack, places, basemap, road features and cells baked
  before the routing manifest lists them, or their downloads pull the whole state's archives.

## On the radar

- One APK per chip type. Built and off (`update/ApkChoice`, SPEC 15). Once a build with
  `ApkChoice` has been the stable for about three weeks, set the repository variable
  `ABI_SPLITS` to `true`, point the README install button at
  `releases/latest/download/vela-maps-arm64.apk`, and say in the release notes which file to
  take. An ARM phone then downloads 74 MB instead of 108.
- Both-mode twins across scripts. With place icons set to Both, the twin pass compares
  Google's English names with the archive's local names. Over Tokyo on an English phone 38%
  link (65% under `hl=ja`), so open icons draw beside their Google twin. Options: run the
  ambient fan-out in the region's language, or bake a romanized name into the archive.
- Departure-time ETAs. Parked until someone captures one directions request from the Android
  Google Maps app with "Depart at" set. The history file lists what was tried.
- Ideas from the October interface round, none built: a thumbnail or a review line on result
  cards; the car icon changed from the drive bar; a dotted walking line from a building's
  door to the start of the route; a place photo from the business's own website when Google
  is off; Material 3 Expressive.
- iOS. Not started. `:core` is plain Kotlin and would move to Kotlin Multiplatform. MapLibre
  and sherpa-onnx have iOS builds. The interface would be rewritten.
- A download of just the route's corridor (#732): map tiles, places and routing data within a
  few kilometers of a planned route instead of a whole region. Not started. The region packs
  are baked per region, so a corridor would be cut from them on the phone or baked on request.
- A driving mode to start in (#727): the map opened in the drive's layout with no destination,
  big buttons, the speed badge and alerts on. Not started. The pieces exist in the drive
  screen; the work is a mode without a route and a setting to open in it.

## Big bets

### Serving our own map tiles

Vela already bakes the whole world's basemap: 448 PMTiles archives, about 93 GB, counted on
the `basemap-tiles` release on 2026-09-25. The app draws from them wherever a downloaded
region covers the view. With nothing downloaded, the online basemap is OpenFreeMap's. Two
things stop Vela streaming its own:

- Seams. Our archives are per region and OpenFreeMap is one planet, so a pan across a
  boundary would swap sources mid-gesture. The fix is one planet-sized archive. A GitHub
  release asset caps at 2 GB and the planet is about 90.
- Release hosting is not a CDN. A map session pulls hundreds of tiles.

The missing piece is hosting: PMTiles on object storage behind a CDN, with a bill and
somebody carrying it. Do this when the project is ready to run infrastructure. With it, the
Microsoft building merge reaches streaming users and the render-time coverage gate
(`runOvlGate`) can be deleted, downloaded and streamed maps share one schema, and Vela stops
depending on OpenFreeMap's donated bandwidth.

### Richer roads

Google's street detail without the 3D. Roads at their real width and street names on every
block shipped. What is left:

- Road markings. A developer test draws center lines, lane lines, bike lanes, crosswalks,
  stop lines, turn arrows and medians from OSM tags (`core/data/PaintedRoads`, dial
  `paintedRoads`, a California bake by `scripts/bake-painted-roads.sh`). Open before
  shipping: arrows sized by eye, lane counts where OSM has none, painted islands and gores,
  stop lines at give-way signs, and a bake for every region.
- Lanes and medians in the basemap. The OpenMapTiles schema has no lane count, so width by
  lanes needs a basemap baked with an extended schema (a planetiler profile with `lanes` and
  `divider`). Downloaded regions could get it first. Online needs our own tiles.

### Contributing back to OpenStreetMap

Vela takes its basemap, routing data, addresses, road features and half its places from OSM
and gives nothing back. The API is the easy part: notes are a plain POST with no account, and
editing is OAuth 2.0 with PKCE against the 0.6 API.

The firewall comes first. OSM forbids data derived from Google, and Vela shows Google's
places beside OSM's. No OSM edit may be pre-filled, suggested or autocompleted from anything
that came from Google, and the code has to enforce it:

- The edit path reads only fields that came from the OSM tile, the open places bake, or what
  the user typed.
- A place that carries a Google feature id can open a note and never a tag edit.
- The two paths share no model object.

Nothing starts until the Data Working Group has been written to. They would act if a
Google-derived edit got in, and the firewall has to be agreed before there is code.

The shape follows StreetComplete: bounded questions about something the user is standing in
front of, never a free-form tag editor. In order of safety: a note anywhere; "is this still
here" on a place from the bake; hours, phone and website on a place with no Google listing
open; a missing house number. Nothing that moves geometry. A later shape is a one-tap fix
("this place is gone") that a Vela-side service verifies against the business's own website
and files under OSM's automated-edit guidelines.

Every changeset carries `created_by=Vela <version>` and an editable comment, uses the app's
own OAuth client, and can be undone. Testing runs against `master.apis.dev.openstreetmap.org`.
Notes alone could ship first.

### A Google Play listing

Not the route for Android Auto for now. It would also reach people who never install an APK
by hand.

The shape that works is a compile-time `play` flavor with the Google extractor left out of
the APK. What remains is a complete OpenStreetMap app: routing, open places, offline packs,
the geocoder, speed limits, the basemap, transit, road features and cameras. It loses place
pages and traffic, and the listing describes that app.

Enabling the Google half after review breaks Play policy, which is enforced on the developer
account. So there are two distributions: the full app on GitHub, Obtainium and F-Droid, and
the Play build with a link to the project site. An in-app APK downloader is not allowed.

Work, roughly in order:

- A flavor with the Google extractor, the hidden pages and the place-page surfaces compiled
  out, and search and place paths falling back to the offline stack.
- `REQUEST_INSTALL_PACKAGES` and the in-app updater removed from that flavor.
- The Data Safety form, a privacy policy URL, a content rating, and the background location
  declaration with its demo video.
- Package id. Play App Signing re-signs, so a Play install and a GitHub install cannot
  replace each other. Choose one id or two before the first upload. It cannot change later.
- Listing copy and screenshots that never imply a Google affiliation.

### Opt-in telemetry

Diagnostics and trip recording are local, opt-in and have no upload. What is left is Vela's
own traffic data: speed and route traces from opted-in users. This departs from "no
telemetry, no backend", so:

- Opt-in only, with a clear consent screen, an easy off and "delete my data".
- No account. A pseudonymous device token at most. Trim the first and last 100 m or so of
  each trace and send speed and heading along road segments.
- It needs the first Vela backend or a privacy-preserving collector, self-hostable.
- [`PRIVACY.md`](PRIVACY.md) changes in the same commit.

### Vela's own record of what is still open

The long game for place data, starting in the USA. Overture adds places and almost never
removes one. The bake removes what Foursquare, OpenStreetMap and Wikidata mark closed (SPEC
5.2), but the Foursquare copy readable without an account stops at February 2025.

What is missing is one dated fact per place: a public record showed it open, or closed, on a
date. So the project is a ledger keyed by the ids the bake already carries, fed by public
registers, and read by the bake as confirm or close. Its first version adds no places.

US permit and commerce data, surveyed 2026-10-06. There is no national collection of business
licenses. About 100 of roughly 19,500 municipalities publish a live feed, covering about a
tenth of the population. In the order worth building:

1. National sector registers, all anonymous bulk downloads: FDIC bank branches (78,061 offices
   with coordinates and dated closings), USDA SNAP retailers (250,628 stores with coordinates
   and authorization dates), CMS hospitals, NCES public schools, NPPES health providers, the
   IRS nonprofit file. Full US coverage, a few percent of all places each.
2. State alcohol and food licenses. California and five other states publish daily lists
   with status and dates, and one more a weekly restaurant and hotel list. Over 40% of the
   population.
3. Health inspections, about a fifth of the population, with trade names and coordinates.
4. Big-city license feeds (New York City, Los Angeles, Chicago, San Francisco, the District
   of Columbia and about twenty more), one adapter each.

Licenses can confirm or close a place and cannot supply places: of the District of
Columbia's 76,331 active licenses, 45% are housing rentals and 24% carry a trade name. A
record is matched by address plus a loose name to a place the bake already has. An active
match counts as seen alive. A removal needs an explicit closed status with a recent date and
a second signal, since an expired license is usually late paperwork.

City layers often state no license, so derive closure facts from them and never republish
the rows.

Open questions: whether Overture's BrightQuery rows carry a status or a last-seen date;
whether to take Foursquare's current releases, which need an account and carry a logo clause;
and how well one city's license feed matches the bake.

First step: FDIC and SNAP as two adapters in the places bake, confirm and close only,
measured on the District of Columbia and Sacramento. National registers outside the USA
(France's Sirene, the UK Food Standards Agency's list) come after the US shape is proven.

### Vela traffic layer

Depends on the telemetry above. Opted-in traces become per-segment speed against free flow,
then a traffic overlay and ETAs that need no Google. It starts as a supplement to Google's
traffic and replaces it where coverage is good.

## Architecture work

- Finish carving the large files. `NavCamera` out of `VelaMapView`: the follow ticker, the
  puck overlay and the padding and zoom eases as one class with one `frame()` entry point.
  `SearchController`: query, suggestions, results, the three pickers and their gates as one
  tested state machine. The camera piece has to be judged on a drive.
- Rules in prose become rules in code. A SPEC paragraph that describes a trap gets a unit
  test, a lint rule, or a type that makes the wrong state impossible, when someone touches it.
- A router with an owner. Turn-by-turn depends on the FOSSGIS community servers with no
  agreement, and the only fallback is the on-phone router. A self-hosted router asked first,
  with FOSSGIS as the fallback, removes that single point of failure. The engine to host is
  Valhalla (decided 2026-10-03): it serves car, bike and foot, the map matching the drive
  route uses, and the avoid options FOSSGIS OSRM refuses. Its tiles are memory-mapped, so a
  free ARM box (4 cores, 24 GB, 200 GB disk) fits North America plus Europe. It would be the
  first server Vela runs, so `PRIVACY.md` changes the day it ships and request logging stays
  off. A free tier can be reclaimed, and someone has to update the tiles.

## Not planned

Asked for, possible, and not being built. Each says what it would take.

- An arrival time fitted to the driver's own pace (under the limit, or 5 or 10 over). Vela's
  times are Google's: one figure for the route and colored congestion stretches, with no speed
  per road, and Vela knows the limit only of the road the car is on. A driver's pace matters
  only where traffic is free, so a flat percentage is wrong in traffic, and the two-minute
  re-check pulls the time back to Google's. It becomes possible when Vela computes its own
  route times (Less to Google, routes computed on the phone).
- Android 7. The minimum is Android 8.0 (`minSdk` 26). No library is in the way: of the 85 in
  the build the highest minimum is Android 7.0 (`androidx.webkit`), Cronet needs 6.0 and
  sherpa-onnx 5.0. The app's own code is: `java.time` in 14 files (it needs the build's
  back-port switched on), notification channels, the foreground service start, picture in
  picture, pinned shortcuts, audio focus and vibration calls with no version check, and a
  launcher icon that exists only in the Android 8 form. After that come a lint pass at the lower
  minimum, an Android 7 emulator in the smoke test, and a look at how Google's pages render in a
  WebView that old.

## Not going to happen

These need a Google login or a Vela server, and the project's promise is that neither exists.

- Contributing reviews, photos or map edits to Google. Needs a Google account.
- Live location sharing and shared ETAs. Needs a rendezvous server.
- Location history or a timeline. An anti-goal.
- Live "busier than usual" popular times. Google strips the live histogram from every
  anonymous request. The typical-week bars are all that is available.
- A shared log linking Google listings to open place ids (declined 2026-09-18). It saves one
  request on a first tap, and the place is fetched anyway for its rating, hours and photos.
  It costs a backend, and a contribution channel that shows which places a user tapped.
