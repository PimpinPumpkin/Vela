# Vela Maps roadmap history

What shipped, and what was tried and dropped, with the reason. Open work is in
[`ROADMAP.md`](../ROADMAP.md). What the app does today is in [`FEATURES.md`](../FEATURES.md)
and how it works in [`SPEC.md`](../SPEC.md).

An entry describes what shipped at the time. Where something later replaced it, the entry
says so.

## Shipped

### October 2026

- A stop added from the "Stops" sheet comes back to the sheet, so building a trip no longer
  goes through the menu to reorder (issue 702).
- The first run asks about plate cameras on the same page as the Google question: route
  around them, and warn near them. Both start off.
- A pinch that twists zooms (it did nothing below a fast pace), and a pinch can turn the map
  in the same gesture. A small turn of the browse map goes back to north.
- A voice of another language than Vela's says on its row that it is not the one heard
  (issue 701).
- Pinching out during a drive flattens the camera (`navTiltCap`), which ends the half-second
  stalls of a tilted city-wide view. Zooming back in tilts it again.
- Settings > Offline maps no longer grows a tall empty box in languages with long button
  labels (issue 698); the cache sizes are read off the main thread.
- Small fixes from the October audit: the cut veil takes the palette's land color, a classic
  `daddr` link keeps a plus code, "Place icons" applies at once, the Street View preview goes
  with its sheet, and unticking "just this area" no longer ticks the whole region.
- "Traffic-light guidance" works: the switch is read, the lights are marked on the route, and
  the voice names only the ones still ahead. Since July the switch had done nothing and the
  lookup ran on every drive unused.
- A list with one place reads "1 place" (`lists_place_count` and `map_save_list` are plurals
  in every language file).
- The current road name shows a named freeway by its number, and adds the compass direction
  the signs gave ("I 80 E").
- A list can have a sound that plays when a drive passes one of its places (`PassAlerts`).
- Several places can be selected in a list or in Saved places, then removed or moved to
  another list.
- The arrival card of a drive offers "Save parking spot", and a simulated drive ends on the
  arrival card as a real one does. The parked car's sheet shows its distance and how long ago
  it was saved.
- Screenshots retaken with the current interface, in the README and on the site.
- Docs pass. The maintainer notes were cut to rules and traps. The dated log they replaced is
  in the git history of `CLAUDE.md`.
- Sheets and Settings pages slide in and out and follow a back swipe (`ui/SheetTransition`,
  pull request 672).
- A car with an instrument-cluster display gets its own Android Auto session
  (`ClusterSession`). Such cars crashed Vela before.
- The first run asks whether to use Google and says what each answer gives and sends, before
  the map loads. Settings > Privacy lists every Google switch in one place, and the first run
  can open the same list. The welcome page no longer claims searches stay on the device.
- The app's own baseline profile ships. The generator used to stop at the welcome screen, so
  no run had recorded the map, and an ignore rule kept the file out of git.
- D-pad: the static audit passes and runs in CI. Twelve text fields no longer trap focus, the
  default route chooser opens on the Drive tab, and a tap-to-close scrim is no longer a focus
  stop.
- Android Auto fixes found on the Desktop Head Unit: the map froze after a screen change until
  the old surface was collected, the turn card was missing because maneuvers carried no icon, a
  drive started on the phone never opened the car's drive screen, and the car ignored the
  simulated location.
- The places bake removes closed places: rows that Foursquare, OpenStreetMap tags or
  Wikidata mark closed (SPEC 5.2).
- Roads draw near their real width at street and navigation zoom, bridges and tunnels
  included, and street names repeat every 140 px (`ROAD_NAME_SPACING_PX`, was 250) so each
  block is named. Pans measure the same on a Pixel 4a. Dial `roadWidthScale`.
- Driving routes follow Google's line, and the steps come from open data (`HybridRoute`).
  Where Google's line and the OSRM route share the road the steps are OSRM's. Where they
  differ, the stretch is map-matched on FOSSGIS Valhalla (`ValhallaRouter.match`), then named
  from the map tiles, then given bare turns.
  Google knows about closures and traffic. A wrong street name is never shown.
- The updater treats an install as set up for the car when either install record names Play
  (`InstallSource.setForCar`). Which record the car reads has changed between Android Auto
  versions.

### September 2026

- Reroute on the phone first. When a downloaded region covers the trip (`RouteEngine.covers`),
  an urgent reroute starts the on-phone route beside the open router and takes it if the
  open router has not answered in 2.5 s (`PHONE_FIRST_ONLINE_WAIT_MS`).
  The online route returns through the existing recheck and the faster-route offer, so no
  new latch was added.
- Camera detours look at every candidate route, under one budget of `CameraDetour.MAX_REQUESTS`
  (6) requests a trip and 4 a route. A cluster within 60 m of one already tried is skipped.
  The avoid-cameras re-rank costs no requests and the detour pass does, so they stay two
  switches.
- "Try side streets around cameras" (Settings > Navigation > Cameras, off by default). A via
  150 m to either side of a camera cluster lets the router find a parallel street. A
  candidate is kept when its camera count drops and its cost stays inside the detour limit.
  Opt-in and capped because it multiplies requests to FOSSGIS. With it, Google is asked for
  a trip through its stops, so a trip with stops has traffic-aware times.
- Grid cells in the app: downloads by 0.5 degree cell (`CellStore`), a cells option in
  Download an area, per-region rows in Downloaded, and per-cell updates by rev.
  The places layer mounts every installed archive the view touches. A whole-region download
  drops that region's cells, so search has no duplicate rows. Cell releases are created on
  the root commit so they sort under the app releases.
- Hidden pages send their requests through the app's own network client (`webProxy`), on for
  every install through the remote settings. "Block Google's page telemetry" is a separate
  setting, off by default.
- Transit directions with Google off, from the Transitous planner.
- On-tap options: "Live traffic only when I tap" keeps routes on the open router until Show
  traffic is tapped (`RouteTrafficOnTap`), and reviews can load on a tap (`ReviewsOnTap`).
- Place pages show the business's own posts, read from the place reply (`PlaceUpdate`). The
  separate `/maps/preview/localposts` endpoint was not needed.
- Delta updates for downloaded archives, on over Wi-Fi by default (`RegionUpdates`).
  A patch appends the changed tiles and a rebuilt directory to the PMTiles file and writes
  the header last, so the extra disk is the patch and an interrupted update leaves the old
  archive whole. A content fingerprint is checked after applying. On Kentucky a week of OSM
  edits changed 1.3% of tiles: a 4.4 MB patch against a 183 MB archive.
- Google requests go over Cronet (calibration `useCronet`). OsmAnd's bundled protobuf is
  relocated at build time so both fit. The AAR is packed from Chromium's official builds by
  `scripts/build-cronet-aar.sh` and hosted on the `cronet-runtime` release.
  Maven's Cronet had stopped at version 143.
- A place tap's first photos and its details come from one plain request each, with a retry.
  Photos carry dates. Reviews still come from the hidden page.
- Landmark fame by language count. The bake adds the number of languages OSM names a
  landmark in to its notability, so a famous landmark with a small footprint keeps its place
  against large parks.
- Dense-city frame rate. The one-set dial (`placesOneSetRev`, default 20260923) hides
  Liberty's `poi_r*` layers over archives that carry OSM's landmarks. New York on a Pixel 4a
  pans at 36 to 58 fps (was 20 to 37), Tokyo at 35 to 58 (was 20 to 45), Davis at 52 to 59
  (was 43 to 59). Earlier in the month the Google places source got a higher GeoJSON maxzoom.
- "Use Vela without Google" (Settings > Privacy, `ui/GoogleFree`, mirrored into `NoGoogle`).
- The navigation notification is an Android 16 live update, with the route as a progress bar
  (`NavigationService`).
- The Google-style route chooser (`GoogleStyleDirectionsPanel`) became the default.
- The places bake merges AllThePlaces chain locators and OSM business nodes into the
  Overture rows.
  Of Overture's 2,693 Davis rows, 1,537 came from Meta and 30 from AllThePlaces, so a
  business with no Facebook page or Bing entry was missing.
- GraphHopper retired. Offline routing is OsmAnd's router over `.obf` region files
  (`ObfRouteEngine`), which also supplies the speed limit and romanized road names.
  For the same extract the obf routing section is about a quarter of the GraphHopper graph
  (Berlin: 26.9 MB against 105 MB). Avoid options are router parameters with no baked
  profiles, and bike and foot profiles come with it.
- Region downloads include the basemap, baked per region with planetiler.
- Avoid tolls and highways work online: Google's directions request carries the flags
  (`DirectionsPb.withAvoid`).
- Architecture review items: route provenance as one field (`Route.source`), the shared
  `HiddenWebView` base, `NavController`, and the search gates (SPEC 2).
- A single-place Google Maps short link shared to Vela opens the place.
- `-PappId=<id>` builds Vela under another package id, for the Android Auto ownership
  experiment and for side-by-side test builds.

### August 2026

- Every country that Geofabrik subdivides has sub-region rows in the catalog
  (`<country>-sub`), and the bake workflows take a list of groups.

### July 2026

- Street View in the app. Vela finds the nearest panorama, fetches the image tiles and draws
  them on a sphere (`PanoramaView`, `StreetViewParser`), with walk arrows, the capture date
  and a spot's older captures.
- Share to Vela from any app's share sheet (`MapViewModel.openSharedText`). A Google Maps
  list link imports the list, a `geo:` or maps link opens like a deep link, and other text is
  searched.
- Transit on open data. Transitous is the first source for departure boards, stop icons and
  the stop timeline of a run. Google keeps transit directions for its traffic-aware times.
- The posted speed limit beside your speed, from OSM maxspeed.
- Avoid tolls and avoid highways as sticky chips in the route chooser, honored by the
  on-phone router. Online came in September.
- Cycleways draw in teal (`vela-bikeroutes`).
- Every place draws as a small category-colored dot under the icons, so a place that loses
  the icon collision stays visible.
- Map labels in Roboto from a self-hosted glyph pack (`scripts/build-map-fonts.sh`,
  `ui/map/MapFonts`), with plain Noto as the fallback.
- Voice search on the phone: a downloadable Whisper tiny model (about 58 MB) on the same
  sherpa-onnx runtime as the voice. An installed voice-input app is the alternative.
- The in-app updater, settings to hide reviews and skip photos, and a 3D buildings toggle.
- Android Auto: Vela in the car launcher with the map, the puck, the route, the maneuver
  card, search and route start.
- Save my parking spot: a long press on the locate button, a chip while one is set, and walk
  directions back to it.
- Google Maps saved lists import from a pasted share link, with the owner's notes. Local
  lists with icons, colors, notes and file backup.
- D-pad operation across the whole app (`docs/dpad.md`).
- Whole-region offline place packs: a CI-baked SQLite file of the region's OSM places,
  addresses and streets, rebuilt monthly and updated in place through row-level deltas.
  Typed addresses geocode offline: exact, interpolated, then the street.
- A temporary closure set by the owner shows as a banner (`Place.temporarilyClosed`).
  A place closed by a paper sign on the door has no signal anywhere in Google's data.
- Open building and house-number overlays: Microsoft footprints and OpenAddresses numbers as
  per-region PMTiles (`OverlayTileStore`), streamed where OSM is thin and downloadable.
  Traffic lights and stop signs at close zoom, now from baked road features.
- The voice library: about 40 Piper voices to download and switch between. The voice runs
  in-process on sherpa-onnx; the default is HFC Female.
- Guidance noise cut: the lane diagram shows within `LANE_SHOW_M` (800 m) of the maneuver,
  and the "then" line only when the next maneuver follows within `COMPOUND_M` (500 m).
- Slower alternates show "+N min" in the route chooser.
- Multi-stop trips: add, reorder, a spoken arrival at each stop, and reroutes through the
  stops not yet reached.
- Photo category tabs in the gallery (All, Menu, Food and drink, Vibe), from the place
  page's own tabs (`WebPhotoFetcher`).
- Google's reviews page in a WebView (`ReviewsPanel`), carved, themed and locked to that
  page. Reviews moved from the reviews RPC to a scrape of the place page
  (`WebReviewsFetcher`), which also carries each review's photos.

### June 2026

- The open router (OSRM) became the route source, with street-named steps, highway numbers,
  exit numbers and sign text, and Google added traffic. Replaced in October by the hybrid
  route.
- Offline routing on the phone, first on GraphHopper with one graph per region, baked by a
  CI matrix and hosted on a GitHub release. The smallest region that covers the trip is
  used. Replaced in September by the obf engine.
- A per-lane diagram from OSRM lane data, and highway and exit shields on the turn banner
  (`parseRouteRef` infers the network from the ref prefix).
- Traffic colors on the route line, from the congestion spans in the directions reply
  (`Route.trafficSpans`). The whole-map traffic layer moved to a setting.
- Popular times on the place page.
- The typical travel-time range ("usually X to Y") from the directions reply
  (`Route.typicalRangeSeconds`), shown for a future departure.
  A per-departure prediction is not available (see Dead ends).
- "People also search for", attribute chips and the reserve, order or book button on the
  place page.
- A custom origin: the From row in the route chooser is editable, with "Your location" to
  reset.
- Diagnostics: a local breadcrumb log the user exports by hand (`core/diag/DiagLog`).
- Trip recording and replay (`TripStore`, `TripLog`). A trip stores the route that was
  driven, so a replay follows the same line.
- The offline navigation auditor (`core/nav/NavReplay`, `TripLog.audit`) replays a trip's
  fixes through `NavEngine` and reports silent turns, early announcements and wrong card
  distances.
- Building footprints visible: the tiles already had them, colored almost like the land.

## Dead ends

Things that were tried or investigated and do not work. Each has the reason, so nobody tries
it again without new evidence.

### Data Google does not give a logged-out session

- Live traffic incidents (crashes, closures, construction). The directions reply and the
  driving page carry none. Google draws them from its binary `/maps/vt` tiles, a private
  format that would have to be reverse engineered and tracked. Waze's `live-map/api/georss`
  feed is behind reCAPTCHA scoring: four approaches in August 2026, all 403. Official
  government feeds remain, one region at a time (ROADMAP).
- Live busyness ("busier than usual"). Stripped from every anonymous reply. The typical-week
  bars are all that arrives.
- Questions and answers on a place page. The logged-out page renders no such section.
- A "recently opened" badge. It is absent from every anonymous reply, for a month-old business
  too.
- Photo uploader names. The gallery reply has each photo's URL, date and source tag and no
  name. Google's viewer does a profile lookup per photo.
- EV charger detail (price, kW, plug availability). A charging station comes back with its
  type marker only, where a gas station carries its price. OpenChargeMap would be the open
  source for it.
- A per-departure ETA. The directions reply has no time-of-day curve, the web client's
  request has no time field, injected fields are ignored or return 400, and the page's
  "Leave now" menu does not open for a script. Six attempts. One capture from the Android
  Google Maps app with "Depart at" set would reopen it.
- The old reviews RPC (`listentitiesreviews`). It never returned a reviewer's photos, and
  since July 2026 it returns 404 for everyone. Do not recalibrate it.
- A native review feed. The request Google's page makes (`qv9Egd`) carries a token that the
  page's script mints per request (`X-maps-bgkey`). Without it the reply is empty or holds
  five reviews. Reviews stay on the page scrape and `nativeReviewFeed` stays off.
- Result filters on attributes other than wheelchair access. Vegetarian, reservations and
  the like exist only in each place's About data, so a result list cannot be filtered on
  them.
- A menu link button. The menu URL is in one reply and missing from the next for the same
  place, and its path will not pin. The gallery's Menu tab covers the need.
- Gallery videos. A busy place's 50-photo gallery carried none. They would need a separate
  source and a player dependency.
- Photo dates mined from the place page's embedded state. It holds no photo URLs when the
  page is walked.
- Reviews in an iframe. Google sends `X-Frame-Options: SAMEORIGIN`. A WebView works.

Once listed as dead and since fixed. Do not trust older notes that say otherwise:

- Popular times. Google answers a place's first request stripped and the repeat in full.
- The photo gallery request (`hspqX`) and photo dates. Both answer once the request carries
  `x-maps-diversion-context-bin` (`Calibration.rpcContext`).
- Avoid tolls and highways on Google. The flags sit in the directions request's feature
  block, where the first probes did not look.
- Street View. It works when Vela draws the tiles (below).

### Routing and navigation

- Always snapping a route through sampled points of Google's line. With dense OSRM vias, a
  via that lands on a turn is swallowed and about 1 named turn in 10 is lost. A sampled
  point on a flyover or the far carriageway makes out-and-back spurs and loops. The hybrid
  route map-matches only the stretches that differ.
- OSRM `/match` on the public server. It accepts 10 coordinates and answers `TooBig` beyond
  that. At that spacing its confidence is about 0.01.
- Naming turns from map-tile street names by thresholds alone. Over 90 routes in six cities
  no setting reached zero wrong names: the best had 5 wrong of 350, with the cross street's
  name landing on the turn. Tiles are used after the map match, in strict mode.
- `exclude=` on FOSSGIS OSRM. Rejected for toll, motorway and ferry. Avoid options go to
  Google online and to the on-phone router offline.
- GraphHopper as the offline engine. It worked from June to September 2026. Its graphs were
  about four times the size of the obf routing data, avoid options needed profiles baked
  into every graph, and a trip had to fit inside one region's graph. Its map matcher was
  meant to name Google's line and never shipped.
- Other engines for the phone, compared in June 2026. Valhalla has no maintained Android
  binding that exposes map matching, BRouter's data has no street names, and Mapbox needs a
  token.
- Stop signs filtered by distance to the driven line. A clustered sign sits at the
  junction's center, so an 11 m gate removed nearly every sign. Gate by the node's direction
  tag or the way it belongs to.
- A route progress bar scaled to the whole remaining trip. On a long drive every nearby mark
  collapses into the bottom pixel. The bar shows a 5 km window (`RouteBar.WINDOW_M`).
- Subtracting a noise floor from accelerometer samples in the speed filter. It weakens a
  real brake by the same amount. `SpeedKalman` applies a suppression gain (`ACCEL_NOISE`).
- Frame drops at the start of a drive. Deferring the declutter, slowing the zoom, hiding
  Vela's symbol layers until the fly-in settles, tile workers at background priority and
  skipping the overview fit each gained nothing. Holding the open places layers back for the
  first 7 s did (`NAV_PLACES_HOLD_MS`).

### Places data

- Closing chain branches that are missing from the chain's own locator. Locator data is
  incomplete per brand: 7 points for a brand with 11 open stores in the test box, so the
  rule demoted open stores. Built, measured, removed.
- Closing places whose website is dead. In the District of Columbia dead links ran 18% for
  doubtful places and 16% for confident ones, so a dead site says nothing.
- Overture's `update_time` or confidence as a sign of life. `update_time` is the import
  date: a rule that spared recently updated rows spared 1,473 of 1,527 closed ones in the
  District of Columbia. Closed restaurants sit at confidence 0.92 to 0.99.
- Merging Microsoft's footprints into the basemap archive. Only 12% of Delaware's Microsoft
  footprints are in OSM, and the merged archive is 42.4 MB against 42.5 MB for the two
  apart. The render-time coverage gate also has to stay while the online basemap is
  OpenFreeMap's.
- Parcels. Per-county scraping, a backend and mixed licenses. Out of scope.
- Bulk map features from public Overpass, per viewport. Nominatim's maintainer asked Vela
  off public Overpass for bulk work. Road features are baked per region, and any remaining
  query goes through `OverpassEndpoints.run`.

### Map and frame rate

- Measuring map frame rate with `dumpsys gfxinfo`. The map draws on its own GL thread, so
  gfxinfo reports zero frames for a pan that stutters. Use `debug.vela.fps` and
  `scripts/map-fps.sh`.
- Swapping the `poi_r20` layer's `in` filter for a `match` lookup. No change. The cost is
  the layer.
- `zstd --patch-from` or block sync for archive updates. Both build a new file, so an update
  needs about twice the archive's size free. The shipped patch appends in place.
- `pmtiles verify` on a patched archive. It rejects the file because the header lengths no
  longer cover the dead space. MapLibre and the reference Go reader read it.
- Python's `http.server` for testing a PMTiles URL. It ignores Range and returns the whole
  body, and MapLibre crashes with "incorrect header check".
- Font inheritance at runtime in MapLibre. Not possible. The glyph pack is built ahead.

### Interface and voice

- Hiding the system bars under a Compose dialog. The dialog window re-asserts its
  inset-fitted parameters and strips remain. The photo gallery leaves the bars visible and
  draws a gradient under the status bar.
- Embedding Google's Street View page in a WebView. Google serves a stripped shell and it
  renders black. The `/v1/thumbnail` image endpoint returns 403; `/v1/tile` is the one that
  works.
- A wider "never load Google's pages" switch. Dropped because it reads the same as "Use Vela
  without Google".
- Kokoro and Matcha voices. Both were bundled, compared on phones and removed. Kokoro ran at
  about 0.4x realtime on the fastest test phone.

### Android Auto and distribution

- Passing the car's install check by setting the installer field alone
  (`adb install -i com.android.vending`), with a stub package named like Google's installer,
  or with the "Unknown sources" switch. The car still refuses. Re-signing Android Auto
  itself breaks its bindings to Play services and it crashes.
- Declaring another app category. Android Auto lets a sideloaded app draw on the car screen
  only in entertainment categories, and a "game" grays out when the car moves. The
  sideloaded apps that draw while driving bundle Google's unreleased car toolkit, which
  cannot be redistributed.
- The Desktop Head Unit as a test of the install check. It never asks Play, so a pass there
  proves nothing about a car.
- Wireless Android Auto adapters. They run the phone's Android Auto app and meet the same
  check.
- A Vela server that does the Google fetching, so a Play build is clean. It puts every
  user's Google requests on one address, which the per-user design exists to avoid, and it
  moves the scraping to a server under the publishing account.
- Installing the Play build and sideloading the full build over it. Play App Signing
  re-signs, so one cannot replace the other.
- A Play build that gains the Google half after review, by a remote flag or an add-on APK.
  Code downloaded outside Play and behavior that differs from what review saw both break
  Play policy, and both are enforced on the developer account.
