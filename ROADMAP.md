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

Traffic incidents from official feeds (issue #688; Germany's Autobahn API, Mobilithek and the
Hamburg traffic feed in issue #833) are out of this list. They belong in a sister project beside
Vela Almanac, which Vela would read as data.

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

- Google by choice on the place sheet. A tapped place asks Google at once, and the switches that
  hold it back are in Settings ("Look up tapped places on Google", "Load reviews only when I
  tap", "Load photos only when I tap"). The sheet could open on what the open data knows (name,
  address, phone, OpenStreetMap hours) with one clear control to load Google's hours, reviews and
  photos, so asking Google is something the user does and sees. It is the change reviewers ask
  for most (see "What reviews and forums keep raising").
- A request count that explains itself. Settings, Privacy shows how many requests went to
  Google in seven days. A light afternoon of testing reads as more than a thousand, with nothing
  to say that one place sheet or one drive is many requests. Show it per action ("a place: about
  N", "a drive: about N an hour"), and use the same numbers to find what to cut.

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

## What reviews and forums keep raising

The same points come back in every review and thread about Vela, in several languages. They are
kept here so a decision about wording, defaults or a feature can be checked against them. Where
one has turned into work, the item is named.

- **It is a copy, not an alternative.** Vela keeps the Google Maps habit (the star rating picks
  the restaurant, Google's traffic picks the road) and removes only the account. The dependence
  is made comfortable, not cut. Position: this is what Vela is for. It is a way off the Google
  Maps app for someone who would otherwise keep it, and the north star is to need Google less
  over time, not to pretend it is not there. Apps built on open data alone take the other road,
  and for anyone they already serve they are the better choice.
- **Asking Google should be a visible choice.** Reviewers who accept the trade still object that
  it happens on a tap, without the user deciding. Before Vela they opened Google Maps in a
  browser, on purpose, and knew they had. Open item: "Google by choice on the place sheet" under
  Less to Google.
- **It takes from Google's users and gives nothing back.** Google's traffic exists because its
  users report their position; Vela reads the result and contributes none. True, and it cannot
  be fixed toward Google without an account. What Vela can give back goes to the open side:
  "Contributing back to OpenStreetMap" under Big bets. One user has already added their local
  transit agencies to Transitous because Vela showed the gap.
- **Google can turn it off.** By changing its pages, by serving anonymous sessions less, or
  through its lawyers, given how closely Vela follows the look. The first is handled by the
  signed calibration file and the second is shown in the app when it happens. The answer to all
  three is the same: with Google off Vela is still a working maps app, and every item under
  "Less to Google" and "Vela's own record of what is still open" makes that version better.
- **"Degoogled" promises more than the app does.** A packet capture shows Google hosts, so the
  word gets called false. The README and site now say what is not on the phone (Google code, an
  account, a key) and call Vela a hybrid of open maps and Google. Still to change: the welcome
  tagline and the About hint in the app, and the F-Droid repository description. `PRIVACY.md`
  should also list every host a capture will show, including the ones Google's own pages load in
  the hidden WebViews.
- **It is written with AI.** Stated in `SECURITY.md`, and the templates ask which model helped.
  Reviewers who raise it mostly conclude the risk is bounded because there is no server, no
  account and no credential to lose. The automated checks in `SECURITY.md` exist because this
  was asked.
- **One person maintains it.** Raised as a durability risk, fairly. "Opt-in telemetry" and "A
  Google Play listing" under Big bets are the two things that would change what one person can
  support.

Things reviewers got stuck on, each small:

- Street View could not be found on a place (#724). Fixed: the place sheet's photo strip starts
  with the Street View tile.
- A typed search returned a different place from the one autocomplete offered for the same
  text.
- Android Auto was reported as missing, which in a car is true today: see "Android Auto without
  Google Play" above.

## In progress

Nothing on a branch at the moment (2026-10-12). Contributor pull requests are read in full and
built here before they land; what each one still needs is under "Built, not yet checked on a
drive".

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
- A Takeout CSV import runs as a plain coroutine: no Cancel, no foreground service, and the
  list is written only at the end, so a 2,000-row file is a quarter hour the process must
  survive. Run it through `downloadLaunch` with a Cancel on the status card.
- The blind-travel hold short of a turn (#745) holds the arrow, but the engine still advances
  the step and says the turn line from the held point once the reckoned speed is over 6 m/s.
- A stop whose pin finds no mark on the line pro-rates its countdown over two legs.
- The car map does not follow a map look change while connected; the look loaded with the
  surface stays until the next size change. Offline with a look that was never cached, the
  car gets no style at all, where the phone falls back to the bundled one. A session that
  started offline never picks up the patched style until the process restarts.
- The first-line shortening measures speech in characters, so a Japanese, Chinese or Hebrew
  line is read as short and keeps its sign tail.
- Duplicate names in the open places bake (#742). Overture carries one unbranded name at
  several points with the same confidence, so a pin lands on the wrong one. Two fixes at the
  bake: one row per unbranded name in a region, and a corrections list Vela maintains.
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
- Side streets' stop signs left off a drive (#805): the bake's road for a sign from drivable ways
  and the most minor road at a junction, and the 10 m limit for a sign with no road. Needs a
  rebaked region driven down a main street, and one drive with no region (the Overpass path).
- The approach prompt that is not said twice three seconds apart on a short step, and the first
  line of a short step that drops its sign tail so the line at the turn does not cut it. From a
  2026-10-11 trace; unit-tested, not yet heard on a drive.
- The car map's heading-up, tilted follow with no route, drive mode, the zoom by speed, the
  traffic raster and the phone's extra fills (#749, pull request #763). Seen on the Desktop Head
  Unit with a simulated drive and with a mock trace at 100 km/h and no route: heading-up, tilt
  55, zoom 15.2 to 15.7. Snapshot time at that speed, median and 90th percentile: 37 and 51 ms
  with the traffic raster off, 40 and 65 ms with it on, five frames over 300 ms in 80 s against
  two; the raster is not what slows the car map. Not seen: a real car or head unit, and the
  rebuild of the car style when the patched file appears after a plain load, which needs a
  first launch with the car already connected.
- The project's links from one place (`VelaLinks`) with a signed `links` push to move them
  (SPEC 11.4). Unit-tested; no bundle with `links` has been pushed yet, so a move has not been
  rehearsed.
- Driving mode on the phone (#727): the drive's bar with an X, the locate and voice buttons, the
  speed box from the first fix, the search bar alone, the speeding alert from the fixes, and
  "Where the map opens" > "Driving mode". Seen on a 4a in portrait (the way in from the layers
  panel and from the setting, the voice choices, the X, the credit above the bar, a simulated
  drive started from it and ended back in it) and on a tablet emulator in both orientations. Not seen: the speeding
  alert, location off, the screen turning off, a real tablet, or Android Auto connected.
- Camera alerts in driving mode (#727): the look ahead along the fix's course. Seen on a 4a
  fed mock fixes at 34 mph toward a plate camera facing the road: the scan found it every 4 s,
  and the Heads up card and the spoken line came about 150 m before it, once. Not seen: a
  speed camera (needs a baked region on the phone), a camera past a bend, the car screen, or
  a real drive.
- Contrast map colors (`CONTRAST_DARK`): seen on a 4a at zoom 12.6 and 15.5 on the Davis
  fixture, online, in the dark theme. Not seen: the car screen, a drive with the route line and
  traffic over the tan streets, the satellite layer, the AMOLED theme (which ignores it), the
  offline basemap, or terrain shading.
- The map looks (#764): the offline fallback and the no-network guard were seen once, for Dark
  and Fiord online then offline over one region (Dark is now named Black).
- The lists sheet (#765): a physical keypad, and the sheet with one list, where a pencil stands
  in for the handle. Open: the overflow button on a recent search row cannot be reached by key,
  since it sits inside the focused row as on every `SuggestionRow`, so a recent row has no key
  path to Remove from history.
- Romanian guidance (#766): the phrases need a native speaker. After the merge the transit words
  need a signed settings push: `stație`, `gară` and `autogară` into `transitCategoryWords`,
  `benzinărie` and `încărcare` into `transitExcludeWords`, since a pushed list replaces the
  compiled one. The status words need no push.
- Takeout lists (#767): a real Takeout export, and the import carrying on after Settings is
  left with its outcome on the status card.
- Timed speed limits from `maxspeed:conditional` (#728, Malse-Makker): the parser is
  unit-tested on the Dutch tags and the badge still reads the streamed tiles on a simulated
  drive. Nothing changes on a phone until the maxspeed tiles are rebaked with the tag kept, so
  the 130 at night has not been seen.
- Bridge openings in the Netherlands (#729, Malse-Makker): seen on a Pixel 4a against the live
  NDW feed on simulated drives, a planned opening's card with the right clock time, an open
  bridge's card and spoken line, and the chime in Alerts only. Not seen: a real drive.
- The three map buttons on the AMOLED theme (#769), every menu's corner (#771), a recent row's
  swipe to its menu (#772) and a renamed address keeping its street (#736): seen on a Pixel 4a.
  Not seen: Home and Work set from a plain address after the change.
- Square map buttons (#797): seen on a 4a, the locate and parking buttons with Round and with
  Square, and the setting reached from the settings search. Not seen: the keypad highlight on
  the square shape, the AMOLED theme, or the car screen.
- A reroute after a wrong turn on a trip with stops. The simulated drive cannot leave its route.
- UK fuel prices on a phone in the UK.
- Saved pins at every zoom (#791): seen on a 4a at zoom 7, where a pin 100 km from the fix
  drew at near full size. Not seen: the smaller pins below zoom 7 and a pile collapsing to the
  pins that fit, which needs saved places in more than one city.
- The layers panel as picture tiles (#782, `LayersPanel`). Opened on a 4a in portrait (dark
  theme) and on a tablet emulator in landscape (light theme): it fits under the button without
  scrolling, and the tiles switch the look, satellite and traffic by touch. Not yet seen: the
  AMOLED theme, a keypad walk across the rows, and the panel on a shorter screen, where it
  would scroll.
- "Zoom to the next turn" (#781): the zoom that frames the next maneuver under the turn card,
  unit-tested on the 1080 x 2340 fixture. Seen on a 4a in simulated drives: zoom 16 to 17.9
  through town, out to 12 and flat once the next maneuver was 8.9 miles off on I 80, back in
  from 12.4 to 17.2 over the four minutes before an exit, and the turn 27 dp above its row
  while the camera held 52.6 degrees against a framing for 55 (fixed, SPEC 4.7 Framing), and
  after the fix the turn on its row, 24 dp under the card within 2 dp, through the tilted
  stretch. A driver's screenshot showed the callout half under the card at 24 dp; the margin
  is 72 dp. Not seen: a real road with it, a reroute with it on, and the floor dial below 12.
- A saved address or pin lists the businesses on it under "Also at this location" when it is
  reopened. Not yet seen on a phone: a saved address that a shop has moved into.
- Lights and stop signs as the map's own glyphs on the road-ahead bar (#828): seen on a 4a in a
  simulated drive through town, the light housings on the track beside a camera badge. Not seen:
  a stop sign's octagon on the bar, and the two 16 dp marks a few meters apart.
- Wide fixes and a phone at rest (#818, #819): a fix wider than 30 m under 3 m/s gives no course
  and no speed, the car's heading holds while stopped instead of re-asking the route segment,
  and the accelerometer says when the phone is at rest. Unit-tested (`StillnessTest`). The car's
  route heading is read at the engine's progress along the route, as OsmAnd and Organic Maps do,
  and the segment nearest the fix only off the route; seen on the desktop head unit in a demo
  drive through town, the arrow along the route up to the turn. Not yet seen: a real stop at a
  light with a poor fix, on the phone or the head unit; a demo drive cannot make a wide fix.
- Streamed overlays read by Vela instead of MapLibre (#827): the address, building and places
  overlays of a region that is not downloaded come through `StreamedPmtiles`. Seen on a 4a
  (2026-10-18): a Davis block at z18.6 drawing the overlay's house numbers, with its tiles asked
  of `pmtiles.vela.invalid`. Not seen: footprints in a suburb OSM has not drawn, the open places
  layer with no region downloaded.
- Compass words after a route number cut to their letter on screen (#837 follow-up): "toward
  US 169 North" on the card and the car's step reads "toward US 169 N" (`RoadNameShort
  .routeCardinals`, `RoadNameShortTest`). Seen on a 4a in a simulated drive: "Take the ramp on
  the right toward I 80 W". Not seen on the car.
- The debug export's `input` line (#744): not seen; written for the head unit's report.
- One list exported on its own (#822): a share button in the list editor's title row, seen on
  a 4a: it opens the share sheet with `vela-list-<name>.json`, and Delete, Cancel and Save keep
  their row. A file of that shape picked under Saved places > Your lists > Import came back as
  the list with its two places and its pins on the map. Not seen: the exported file itself
  going round, since the share sheet on the rig has no file target.
- Names under saved pins from z14 (#823): seen on a 4a in dark and light mode at z14 to z16:
  three pins in a downtown grid with their names under them beside the open-places labels, a
  pin on top of the basemap's own label for the same place keeping the pin and losing the name,
  a nine-pin list across a city center with every name that had room and the crowded ones
  dropped, and names of six words wrapping to two or three lines at the 8 em width.
- The drive's `location:` note (#818): providers on, and GrapheneOS's network location, SUPL,
  PSDS and geocoder choices. Not yet seen: a trip export carrying it; the GrapheneOS values need
  a GrapheneOS phone.
- "Zoom out as far as" (#781): seen on a 4a, the three choices under the switch, Town picked
  by default, Region picked and kept. Not seen: a drive with Region or Far picked, where the
  map may go out to zoom 10 or 8.
- The camera turned toward the next turn, and Zoom to the next turn on the car screen (#781):
  unit-tested (`TurnYawTest`). Seen on a 4a in a simulated drive from Davis to the airport with
  the Town floor: heading-up through town with no yaw, the camera held 45 degrees toward the
  far junction through the looping on-ramp, then on the freeway the junction 21 km out kept in
  the center column (x 542 of 1080) with the camera 10 degrees off the road's heading, at 59
  fps. Seen on the Desktop Head Unit (1280 x 720) in the same drive: zoom 12 and flat on the
  freeway with the camera 19 to 31 degrees toward the junction, back to zoom 15.2 and 55
  degrees of tilt as it neared, the yaw fading from 43 to 23. Not seen: a real road, the yaw
  fading out through a turn in town at the speed zoom, and a head unit in portrait.
- Whisper base and small for voice search (#798): seen on a 4a, both in the picker under
  Search with their sizes, base downloaded and unpacked in half a minute, made active, and the
  mic opened on it. Unit-tested: the first download offered is tiny for English and base
  for any other app language. Not seen: a spoken search through base or small, how German
  comes out, or the offer itself on a phone in another language.
- The lead-in before spoken lines (#802): 300 ms of silence at the start of every line, on Vela
  Voice (`PiperSynth.LEAD_IN_MS`) and on the phone's engine (`playSilentUtterance`, whose
  utterance the focus listener skips). Seen on a 4a: a simulated drive's prompts through the speaker, whole, four lines in a row with their timings in the log.
  Not seen: a Bluetooth head unit, where the clipping was reported, or the phone's own engine.
- The turn card's Then line wraps to a second line (#799). Not seen on a drive here, which needs
  a compound turn onto a long street name; the change is the line count.
- Update all for downloaded regions (#801): seen on a 4a with four regions pending. The button
  showed the count, the regions updated in turn under one card, and a place pack whose delta did
  not fit fell back to the whole file as a row's own Update does. Not seen: Cancel mid-run, or
  a drive started during the run.
- The transit guidance pane's last card ends with Done (#811): seen on a 4a, a four-leg bus trip stepped to its end, the card's Back and Done, and Done back on the map.
- An expanded transit route stays expanded across a swipe of the sheet (#812, `TransitExpanded`): seen on a 4a, a bus itinerary expanded, the sheet dropped to its card by its handle and raised again with the legs still open.
- One Share on the route picker (#814): seen on a 4a, Start and Add stops below, Route options, Share and Close in the header.
- Saved routes named from a named start (#815): seen on a 4a, a trip from a named start saved under both names by default, and the list row with the start and the end under it; a route from the phone's own position still reads Route to the end.
- Drawings and custom maps above the satellite imagery (#816): seen on a 4a, a three-point line
  and its area drawn over the photo, and with the Vela look as before. Not seen: the satellite
  turned on with a saved custom map already showing, which goes through the same re-seat.
- Turn arrows on the route (#804): seen on a 4a in a simulated drive, a white arrow in a dark
  casing through the right turn onto a cross street and the turns after it, the passed one gone
  with its step, and after the corner was moved onto the line's own bend, the arrow through a
  freeway on-ramp bending left with the ramp and the blue turn bubble's tail on the corner;
  unit-tested on an L-shaped route, a keep-left whose split lies 60 m past the point and a lane
  change before a corner. Not seen on a phone: a fork found past its point, a lane change
  smoothed out, a roundabout exit, the switch under Navigation turning them off mid-drive, or
  the car screen, which has none.
- The car's speed badge and limit sign sized with the screen (#817): seen on the desktop head
  unit's 800 by 480 profile in a demo drive, the badge and the sign at the template's button
  size. Not seen: the 1280 by 720 profile with the new sizes, or a real head unit.
- The traffic layer's tiles stop at z16 (#800): seen on a 4a at z17.5 over a downtown, two z16
  requests for the view where there were fourteen at z18, the bands scaled and even. Not seen:
  the reporter's phone, where close-up tiles never arrived.
- Each installed region's row ends with its newest bake's date (#801): seen on a 4a, two regions
  reading "updated Oct 1, 2026". Not seen: a region whose routing file is newer than its pack.
- The car's speed badge off by default (`CarSpeed`, under Show speed and speed limit): seen on
  the desktop head unit's 800 by 480 profile in a demo drive, the limit sign alone in the corner.
  Not seen: the switch turned on, or a unit with the badge and the sign side by side after it.
- The car's drive screen cut to mute and end up top, recenter and overview on the edge, and the
  route at 20 px in the phone's blue (user 2026-10-15, after Google's car screen side by side):
  seen on the desktop head unit at 1280 by 720 in a demo drive. Not seen: a real unit, or the
  landing and preview screens, which keep their zoom buttons.
- The camera's turn toward the next turn capped at 90 degrees instead of 45 (#781, a tester
  found turns dragged off the side of the screen on curving roads): unit-tested
  (`TurnYawTest`). Not seen: a drive where the next turn lies more than 45 degrees off the
  heading with the yaw in full.
- One accessibility list on a place page instead of two (#832): the chip row above the rating
  card is gone, the About sections below carry the attributes. Seen on a 4a: a grocery's page,
  its four accessibility items once, under About.
- A simulated drive from a chosen start (#835): Start drives the planned line from the start
  picked in the chooser; a real drive still re-plans from the car when the chosen start is
  farther than 150 m away. Seen on a 4a: a drive from a grocery 500 m from the dot began in its
  parking lot, on the 3.1 mi route the chooser showed.
- Road numbers first (#836, Settings > Navigation, off by default): a numbered road goes by its
  number on the pill, the car's Continue on line and the directions of the next route. Unit-tested (`RoadLabelTest`). Seen on a 4a: with the switch on, a simulated drive's card read
  "Turn left onto CR E6" for West Covell Boulevard with the number's chip beside it; off, a
  boulevard with a number reads by name.
- One Save and one Share on a place page (#830): both are pills in the action row and the
  header keeps only the X, as Google lays it out; the Save pill opens the menu the header's
  bookmark had (quick save, lists, note, icon, name, Home, Work). Seen on a 4a: a grocery's
  page with the X alone up top, Directions, Start, Call, Save, Share, Website and Street View
  in the row, the Save menu (Save, Save to list, Set as Home, Set as Work) and the Share menu
  off their pills.
- Street words abbreviated everywhere on screen (#837): the pill for every name, the turn card
  and its "then" line, the car's step and Continue on; the voice keeps the full words. Unit-tested (`RoadNameShortTest`). Seen on a 4a in a simulated drive: "Turn left onto G St" on the
  then line and "G St" on the pill.
- The car's speaker button stepping on, alerts only and off (#842): seen on the desktop head
  unit at 1280 by 720 in a simulated drive, the glyph going speaker, bell, crossed speaker and
  back to speaker. Not seen: the phone's own button following a change made in the car, or a
  real head unit.
- The phone's light and stop sign glyphs on the car map (#844): `CarControlGlyphTest` for the
  size; seen on the desktop head unit at 1280 by 720, the traffic lights through a downtown at
  about road width. Not seen: a stop sign on the car map, the 800 by 480 profile, or a real
  head unit.
- The rating card's Show reviews loading the reviews when they wait for a tap (#845): seen on a
  4a with "Load reviews only when I tap" on, one tap opening the Reviews tab with the reviews
  loading. Not seen: a place whose Reviews tab is already open when the card is tapped.
- Street View links opening the viewer (#847): `MapLinkParserTest` for the three link shapes;
  seen on a 4a, a web map Street View link handed to Vela opening the viewer on that block. Not
  seen: the `map_action=pano` and `layer=c` links on a phone, a link with a pano id, or the
  kav app itself.
- A pair of close turns said in a line that ends before the second turn's own (user,
  2026-10-17): `NavEngineChainTest`. Not seen or heard: a real drive, or a trip export of the
  reported one, so whether the cut was this or the second turn's queued approach line is open.
- Offline address search (user, 2026-10-17): the pack's street ids read as 64-bit, and the
  word lists (`OfflineStreetWordsTest`). Seen on a 4a with `debug.vela.tune.offline 1` against
  the Northern California pack: a Davis address typed plain, with its town after a comma, with
  town and ZIP and no comma, and half typed ("Covell Bl") all led with the address and the
  store on it; before the id fix all four found no address. Not seen: a phone with its network
  really off, or the user's own area.
- The Street View thumbnail for every Google listing (user, 2026-10-18): seen on a 4a, the
  Davis Food Co-op, which had none, now shows one; four other businesses kept theirs. Not
  seen: a listing whose nearest pano is across a wide road or behind the building.
- Save to phone for a recorded trip (user, 2026-10-18): seen on a 4a, the system save dialog
  opening in Download with the trip's file name, and the full trace (15.9 KB, every line kind)
  landing there for `adb pull`. Not seen: GrapheneOS's own picker on the P9.
- The Street View window as a 360° button when there is no picture (user, 2026-10-18): seen on
  a 4a on a saved point, the figure over "360°", and a tap opening the viewer on that block.
- The scale bar riding over the free-drive speed box instead of hiding (#848): seen on a 4a at
  rest, the bar through a restart and three pans. Not seen: a phone moving without a route,
  with Show speed on (the bar and credit raised) or off (both in place).
- A 700 ms lead-in on Bluetooth outputs (#802, 300 ms still clipped some lines for one
  reporter): not seen or heard; there is no Bluetooth output on the test rig. Which output the
  reporter uses is unconfirmed.
- A Google Takeout list import saved as it goes (#761: a 600-row list was lost when Android
  stopped the app part way): seen on a 4a, a 63-row test list killed at row 36 kept the 12
  places found before its first save. Not seen: a real Takeout file imported again, which should
  take the rows it already matched with no lookup.

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
  is off; Material 3 Expressive; a search bar at the bottom of the screen (#747: the hard part
  is a layout for the buttons and the bar that still works in landscape and with the sheets).
- iOS. Not started. `:core` is plain Kotlin and would move to Kotlin Multiplatform. MapLibre
  and sherpa-onnx have iOS builds. The interface would be rewritten.
- A download of just the route's corridor (#732): map tiles, places and routing data within a
  few kilometers of a planned route instead of a whole region. Not started. The region packs
  are baked per region, so a corridor would be cut from them on the phone or baked on request.
- Live vehicle positions on the map (#757): Transitous serves GTFS-Realtime positions where an
  agency publishes them, a minority; drawn with an age label where they exist and nothing
  elsewhere. Not started.
- Quick search buttons of the user's own (#779): a typed query saved as a chip beside the built-in
  ones. Not started. The order and hidden set are keyed by the chip's query, so a custom chip
  would fit the same store; the open questions are the icon and what the offline store expands
  for a word it does not know.
- `?cid=` links (#796): `maps.google.com/?cid=<id>` names nothing and Google answers it with a
  page, not a redirect, so opening one needs the hidden page load the reviews panel already
  does, reading the place off the page's final URL. Not started; short share links open.
- The car's layers follow the phone's switches while connected (#749): the car builds its map
  once per connection, so a Traffic layer switched on afterward shows only after a reconnect.
  Not started.
- Contrast by day: the Contrast map colors draw as Modern in light mode. A light palette with
  the same intent (warm streets, roads a few steps from the land) is not drawn. Not started.
- An e-ink look (#789): a map look in black lines on white with no shading beside the five
  under Map look, and a high-contrast app theme without grays. The nearest today is Positron
  with the light theme. Not started; needs someone with an e-ink phone to look at it.
- A hiking and cycling look (#808): trails dashed by type (path, footway, track, cycleway,
  steps), paved against unpaved, mountain-bike difficulty, bicycle and foot access, and peaks,
  all of which the tiles carry today; an elevation profile for a route from the terrain tiles
  the shading already uses. Not in the tiles: the hiking difficulty grade, cliffs, contour lines,
  which need a bake of their own. Not started.
- Transit routing over OpenStreetMap's route relations (#809): the bus and tram lines mappers
  have drawn carry their stops and order, and Organic Maps routes over them with no timetable.
  Google's feed misses whole cities (Niš has its coaches and none of its city lines), and
  Transitous has no feed there either, so a planner over the relations in the downloaded
  region would answer where neither does, without times. Its own engine; not started.
- Snapping the dot to the road with no route (#803): the browse and driving-mode dot is the raw
  fix, and a weak head unit GPS puts it a lane or a row of houses off. Vela already finds the
  road under the car for the speed limit; a snap onto it while moving, released at junctions
  and when the fix leaves the road, is the shape. Waiting on the reporter's recording and his
  unit's accuracy figure. Not started.
- A swinging heading at a standstill and a speed readout that jumps at a steady speed (#818,
  #819, one Pixel 10 Pro, phone and car): the fix handler takes a bearing over 0.5 m/s and
  derives one from 3 m of movement, which may be too loose for that unit's fixes. Needs a
  diagnostics export of such a drive before the gates move; never seen on the project's own
  phones or head unit.
- Street furniture on the map (#813): benches, bins, drinking water, toilets, shelters, hydrants
  and the like at z17 and up. The tiles carry waste baskets, drinking water, toilets, shelters,
  playgrounds, pitches and bicycle parking, so those are a layer with icons of our own; benches,
  hydrants, fountains, single trees and retaining walls wait for our own tiles. Not started.
- Fuel and chargers along a route (#834): a count and the longest gap between `amenity=fuel`
  or `amenity=charging_station` places near the line, on the route chooser, so two routes
  through sparse country can be compared before one is taken. The places come from the
  downloaded place pack or the open places tiles, so it works offline; the gap is measured
  along the line. Not started.

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

### A faster shortest-path search, kept as research

catalinb13 ported the 2025 sorting-barrier single-source shortest-path algorithm (Duan, Mao,
Mao, Shu and Yin) to Kotlin and ran it over the obf region files as a second engine (#695,
2026-10). It is kept on the branch `research/sorting-barrier-sssp`, unmerged, with the author's
measurements in the pull request. Measured here on the Delaware file: a route took 1.0 to 2.8 s
against 67 to 396 ms from the shipped engine, the cached graph held 404 MB of a 512 MB heap
against about 12 MB, and the graph it builds skips turn restrictions and access tags and keeps
rail and ferry lines, so a car was routed along a railway. To become an engine it would need
the search run under the lock, OsmAnd's routing rules applied when the graph is built, and a
memory gate on the file size, and it would still be slower. The interesting part is the
algorithm, and the branch is where to read it.

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

### If GitHub goes away

GitHub is the right home today: free unlimited Actions for a public repository, releases that
served 74 TB of region files at no cost, the Obtainium and F-Droid audience, and a record of
keeping projects that toe a line. It is also one account that can be closed. What that would
break, measured 2026-10-11, worst first.

- Installed apps. Builds before `VelaLinks` carry `api.github.com` and
  `raw.githubusercontent.com` as literals, and no push can reach them. Builds from 2026-10-12
  on read the settings file from the website's copy when GitHub does not answer, so a signed
  `links` push there moves them. The updater reads GitHub's release JSON; Forgejo's has the
  same fields, GitLab's does not.
- The data releases: 56,470 files, 369 GB, about 18 TB served a month. No git host replaces
  that (GitLab's free storage is 10 GB). An object store with free egress (Cloudflare R2, about
  six dollars a month at this size) does. Every manifest embeds absolute `github.com` asset
  URLs, so a move is a manifest rewrite or an app-side rebase onto the `releases` link. The
  93 GB basemap and 82 GB building bakes need big-disk runners to regenerate.
- The channels that pin a URL: Obtainium points at the repository, F-Droid clients at the
  Pages address. The F-Droid key is local, so the fingerprint survives; every user would add
  the new address by hand. The APK is 116 MB, over Cloudflare Pages' 25 MB file limit, so a
  mirror of the F-Droid repository lives on R2, not Pages.
- CI: 29 workflows, hourly to weekly crons, at least 55 runner hours a month, ten secrets (all
  regenerable or kept locally), the `gh` CLI in 41 scripts and workflows, artifact, Pages,
  CodeQL and Scorecard actions. GitLab's free tier is 400 minutes a month. Forgejo Actions runs
  the same YAML nearly unchanged on a self-hosted runner; GitLab means rewriting it.
- Fonts on Pages fail softly: the app probes the host and falls back to OpenFreeMap's Noto
  glyphs. The website is on velamaps.org already; the docs would redirect there.
- Issues, pull requests, discussions, every `#123` in commits and docs, the Weblate project,
  issue triage, download stats and the dashboard. Forgejo's importer keeps issue numbers.

Not touched: the three signing keys (local only), velamaps.org, OpenFreeMap, the Piper voices
(another account's releases), the donation page.

Insurance, cheapest first. The first is built (2026-10-12: the site workflow publishes
`calibration.json` and its signature to velamaps.org, and `CalibrationStore` reads that copy
after GitHub's); the rest are not:

1. A second settings-file address off GitHub (velamaps.org), published by the same workflow
   as the site, so a closed account becomes one signed `links` push. Built. Not yet seen: an
   app reading the copy with GitHub blocked.
2. The F-Droid repository mirrored on R2 behind a velamaps.org address, listed in the index as
   a mirror so clients fail over by themselves, and in the README.
3. Manifest asset URLs rebased app-side onto the `releases` link, so a storage move is a push,
   not a rebake.
4. A weekly export of issues and pull requests to R2.
5. A GitLab pull mirror of the git history.

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
