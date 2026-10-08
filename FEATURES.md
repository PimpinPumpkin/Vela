# Vela - features

What Vela Maps does today, one line per feature. How each one works is in [SPEC.md](SPEC.md) and
[docs/book/](docs/book/README.md).

## The map

- An open vector map from OpenStreetMap with buildings, house numbers, borders, highway shields and
  exit numbers.
- "Place icons on the map" draws businesses and landmarks from Vela data (open data, the default),
  Google, or Both.
- "Show places", "Parks, schools and civic places", "Transit stops" and "Place icon size" control
  what is drawn.
- Tap a place, a house number or a building to open it. Long-press to drop a pin.
- Tilt the map for 3D buildings. "Tilt with two fingers" and "3D buildings" turn those off.
- Pinch and turn in one gesture. A small turn goes back to north when you let go.
- A layers button for satellite, live traffic, transit lines and terrain shading. "Show layers
  button" hides it.
- "Highlight transit lines" draws subway, tram and train lines in their own colors.
- Traffic lights, stop signs, railway crossings and speed humps show at close zoom.
- "House numbers" sets how far out numbers show. "Fill missing buildings" draws buildings the map
  lacks.
- A blue dot with a heading cone, or a wide circle when the fix is approximate.
- Driving with no route, the map turns heading-up and shows your speed and the limit.
- Names in the app language where the data has them, or romanized.
- A compass, a scale bar and a locate button. "Prefer buttons over swipes" adds zoom buttons.

## Places

- A place page with name, category, rating, review count, price level, address, phone, website and
  distance.
- Open or closed status, the week's hours, holiday hours, and department hours in a large store.
- Tabs for Overview, Reviews, Photos, Menu, Updates (the owner's posts) and About (description and
  attributes).
- Reviews with reviewers' photos, a star breakdown, sorting and search. "Read all reviews" opens
  Google's full page.
- Photos in a grid by category with a full-screen viewer, and popular times by day and hour.
- Buttons for Directions, Start, Call, Website, Save and Share, plus Order or Reserve where offered.
- Share sends a Google Maps link, a geo: pin, coordinates or the address, or opens another map app.
- A Google Maps directions link opens as the trip it describes, with its start, its stops and its
  travel mode. Paste one from a desktop browser into the search box, or tap it in any app.
- "People also search for" and "Also at this location" rows open related places.
- A tapped map place opens at once with open data and names its source while Google's listing loads.
- Closed places are marked permanently or temporarily closed. Gas stations show their fuel price.
- Street View in the app: look around, walk between panoramas, view older captures.
- "Show reviews" and "Load photos" turn those off. "Load reviews only when I tap" and "Load photos
  only when I tap" defer them.
- "Wait for popular times" and "Load all photos and reviews" trade more Google requests for fuller
  pages.
- "Hide adult categories" drops bars, clubs and casinos. "Hide website & external links" removes
  outside links.

## Search

- Search by name, category or address, biased to the area in view.
- Suggestions as you type, your own recent and saved places first. A row's arrow fills the box.
- Results as pins and cards with rating, open status, photos and action buttons.
- Filters for Open now, rating, price and Wheelchair accessible. Sort by relevance, rating or
  distance.
- "Search this area" after a pan, and "More results" at the end of the list.
- A name search lists the chain's other branches. "Find other locations automatically" turns that
  off.
- "Category shortcuts" such as restaurants, gas and EV charging, as a row, one button, or hidden.
- Commands, typed or spoken, such as "take me home", "nearest pharmacy" and "what's my ETA".
- Voice search on the phone with three recognition engines, or through a voice app. "Voice search
  mic" hides it.
- "Search your contacts" suggests a contact's saved addresses. Off by default.
- With a route planned or a drive running, search looks along the route.
- Map links from other apps open in Vela. "Links from other apps" picks the place, directions or
  navigation.
- Offline, search covers downloaded regions. With Google off, it uses open data.

## Routes

- Directions by car, foot, bike or transit, from where you are or any start you pick.
- A drive follows Google's route, which knows traffic and closures. Street names, lanes and exits
  come from open data. With Google off the route comes from open routers, and keeps off farm tracks.
- Up to four routes with time, distance and traffic. Tap one on the map to switch.
- "Google-style route picker" (the default) shows one route at a time. Off, a classic panel lists
  them all.
- Add stops by search, on the map or by long-press, and drag to reorder. A stop added from the
  "Stops" sheet comes back to it, so you can drag it into place and add the next.
- Avoid tolls, highways, ferries or cameras. Set Leave now, Depart at or Arrive by.
- Walking uses the open foot router. "Bike routes prefer bike lanes and quiet streets" is on by
  default.
- A step list before you start. Tap a step to see it on the map.
- "Save this route" keeps a route and offers it as "Your route" for the same trip.
- "Add to home screen" pins a trip as a shortcut. The share button sends the trip as text.
- A warning when the destination or a stop closes around your arrival.
- "Live traffic only when I tap" plans on the open router until you tap Show traffic.

## Navigation

- A turn banner with arrow, distance, road name, highway shields, exit numbers and lanes. Swipe it
  to look ahead.
- Spoken directions with street names and lanes. "Say street names" off shortens them.
- A bottom bar with time left, distance and arrival time. Swipe it up for the step list.
- Off the route, or driving against it, Vela reroutes with a chime, a buzz and a banner.
- A faster route is offered when traffic changes. "Take faster routes automatically" accepts an
  unanswered offer.
- The route line is colored by congestion. "Road behind you" keeps the driven part in gray.
- Your speed and the posted limit. "Show speed and speed limit" hides them.
- "Road ahead bar" is a strip with the traffic, lights, stop signs, crossings and cameras still
  ahead.
- "Current road name" goes above the bottom bar, under the arrow, inside the bar, or off. A
  freeway shows its number, with the compass direction when the signs give one ("I 80 E").
- Street-name callouts where cross streets meet your route, with your turn and exit highlighted.
- The number of the road you are on, as a shield on the route line about every mile.
- Pause holds the drive with no rerouting, voice or offers. "Pause button on the navigation bar"
  moves it.
- An overview button fits the rest of the route, and Re-center returns to the drive.
- The compass switches heading-up and north-up. "Start drives north-up" sets the default.
- "Navigation icon" is an arrow, a car, a UFO, a pirate ship or a rubber duck, with "Arrow size" and
  "Arrow colors".
- "Vibrate on turns", "Keep screen on while navigating" and "Ask before ending navigation".
- A notification, a picture-in-picture mini map and, on Android 16, a live update carry the drive in
  the background.
- "Turn card in the mini map" sets the mini map's layout.
- If the app is closed mid-drive, the next launch offers to resume.
- In a tunnel Vela keeps estimating your position.
- Stops and arrival are announced, with the trip's time and distance at the end.
- "Tap places while driving (experiment)" offers a tapped place as a stop, with the time it adds.
- During a drive the only places on the map are gas stations. "Simplify the map in turns" hides more
  while turning.
- "Traffic-light guidance" adds spoken cues like "pass the traffic light, then turn left", for
  lights still ahead of you. English only, off by default.

## Transit

- Trips with departure and arrival times, lines in agency colors, walks, a countdown and live
  delays.
- Chips for Leave now, Depart at, Arrive by and Last available, preferred vehicles, fewer transfers
  or less walking.
- Expand a trip for each leg's stops, times, alerts and fare, drawn on the map along its real track.
- A walk leg expands to turn-by-turn walking directions.
- Start a trip for a leg-by-leg guide that speaks each leg and moves on at its end.
- Tap a stop on the map for its departure board, with a countdown, a Live mark and each run's stop
  list.
- Offline, a stop shows the last board you saw there.
- With Google off, trips come from the open Transitous planner.

## Cameras and road alerts

- "Surveillance cameras" draws mapped license plate readers and which way each faces. On by default.
- "Avoid surveillance cameras" shows each route's camera count and prefers fewer cameras for a small
  detour.
- "Try side streets around cameras" also tries the streets beside each camera.
- "Plate camera heads-up" shows a card near a camera. "Say when a plate camera is ahead" speaks it.
- "Speed cameras" draws fixed speed cameras. "Warn me out loud" announces one ahead and shows a
  card.
- "Speeding alert" says when you have been over the posted limit for a few seconds.
- Plate camera data ships with the app, so it works offline.

## Offline

- "Download an area" frames part of the map and saves its map, places, addresses and routing.
- "Entire states & countries" is a catalog of whole regions to download.
- With no signal the map draws, places open, and search finds names, categories and typed addresses.
- Offline routes cover driving, walking and cycling, with spoken turns, speed limits and avoid
  options, and are labeled offline.
- "Update downloaded regions" applies small updates on Wi-Fi (the default), on mobile data too, or
  never by itself.
- "Store downloaded regions on" moves offline data to an SD card or back.
- A storage breakdown, "Clear map cache" and "Delete all offline data".
- "Include places with downloads" adds place data to a region. "Keep viewed places for offline"
  keeps the places you opened.
- A globe with a slash in the search bar marks offline. Tap it to check again.
- Downloads run in the background and each has a Cancel.
- On a satellite or data-limited connection Vela skips photos and asks for less.

## Voice and languages

- Spoken directions use a neural voice that runs on the phone. The Vela voice downloads once.
- The "Voice library" has about 40 Piper voices. Any text-to-speech voice on the phone works too.
- "Spoken directions" is the on and off switch, the same as mute in a drive.
- "Guidance volume", "Voice speed" and "Test voice".
- The voice follows the guidance language, which is Vela's language. Vela offers a download when
  a language has no voice, and a voice of another language says so on its row.
- English voices say about 3,500 place names the local way. Street names in another script are read
  romanized.
- 18 languages, including Chinese, Japanese and right-to-left Hebrew. Estonian is partly translated.
- "Follow system language", or pick one for Vela alone.
- "Units" for miles or kilometers. "Clock" for 12-hour or 24-hour time.

## Saved places, lists and sharing

- Save any place with the bookmark, and set Home and Work.
- Lists with a name, color and icon or emoji, in your order. A saved place can carry a note, an icon
  and your own name.
- Saved places show on the map in their list's color, and can be pinned to the search page.
- Export saved places and lists to a file and import them. Import also reads GPX, KML and GeoJSON.
- Select several places in a list or in Saved places (long press one, or the select button), then
  remove them or move them to another list or a new one.
- A list can have a sound. Driving past one of its places plays it and shows the place on a card.
  Choose a tone or "Say its name" in the list's editor. It works while Vela is open or guiding.
- A shared Google Maps list link opens with the owner's notes and can be saved as a list.
- A Google My Maps link opens with its pins, lines and areas, and can be saved.
- Draw a line or an area on the map from Your lists.
- The parking button saves where you parked, then offers Find my car, which shows how far the car
  is and when you parked. "Parking history" keeps past spots. When a drive ends, the arrival card
  offers to save the spot too.
- Recent searches and places can be removed one by one or cleared together.

## Privacy controls

- No account, no ads, no analytics and no Vela server.
- The first run asks whether to use Google, and can open the list of what Google is used for.
  The same page asks whether to route around plate cameras and warn near them.
- Settings > Privacy has every switch that decides what Google is asked, in one list.
- "Use Vela without Google" stops every request to Google. Reviews, photos, live traffic and Street
  View go away.
- "Open shared Google Maps links" still lets a short link resolve when Google is off.
- With "Place icons on the map" on Vela data, panning sends nothing to Google where Vela has place
  data.
- "Look up tapped places on Google" off keeps taps to Vela's own data.
- "Google session" starts a new anonymous session weekly, daily or at every launch.
- "Block Google's page telemetry" stops the background reports Google's pages send.
- "Requests to Google" counts what the phone sent today and this week, by purpose.
- "Live traffic re-checks while navigating" off stops position updates to Google during a drive.
- "Clear history" wipes recent searches, recent places, parking history and recorded trips.
- Location is optional for browsing and search. Navigation asks for precise location.
- "Google Maps links" shows whether Android hands such links to Vela.

## Look and settings

- Themes are follow the system, Light, Dark, "AMOLED black" and "Day and night". "Day and night
  while navigating" applies it to drives only.
- "Map" sets the map light or dark apart from the app. "Map colors" is Modern or Classic.
- "Material You colors" tints buttons and accents from your wallpaper.
- "Font" is Google Sans Flex, the system font or your own font file. "Interface size" scales the
  interface.
- "Page transitions" turns sliding motion off. "Call it just Maps" renames the launcher entry.
- Settings is a hub of pages with a search box that jumps to the row.
- "Load voice search at startup", and "Compatibility rendering" for phones whose map crashes at
  startup.

## Android Auto

- Vela can appear in the car's launcher, depending on how it was installed. See the [Android Auto
  guide](docs/ANDROID-AUTO.md).
- The home list has Home, Work, recent and saved places, then nearby gas, charging, food, coffee and
  parking. Nearby results are numbered on the list and on the map.
- Search from the car, preview up to three routes, and start the drive.
- The drive screen has the map, a turn card with lanes, the arrival estimate, your speed and the
  limit.
- Buttons for mute, pause, search along the route and end, plus recenter, zoom and overview.
- Camera, speeding and closing-soon alerts appear as car toasts.
- The car's settings cover spoken directions and avoiding tolls, highways and ferries.
- The phone and the car share one drive. A trip started on either shows on both.
- A car with an instrument-cluster display gets the turns there.
- A car that runs Android itself can show Vela in its map panel.

## Phones without a touchscreen

- Every screen works with a D-pad and an OK key, and every control shows a focus ring.
- On the map, arrows pan, OK opens what is under the crosshair, holding OK drops a pin, and buttons
  zoom.
- "Zoom in key" and "Zoom out key" give zoom to any two keys, such as 2 and 5 on a keypad.
- Each gesture has a key path, including the step list, the banner's look-ahead and Street View.
- [docs/dpad.md](docs/dpad.md) has the details.

## Updates and install

- Vela needs no Google Play services. Location comes from the phone's own GPS.
- Install from Vela's F-Droid repository, with Obtainium, or from the APK.
- The app checks for a release about once a day. "Check for updates on launch" turns that off.
- "Update channel" is Stable (weekly), Nightly (daily) or Canary (development builds).
- The update card lists each skipped release's changes. "Show what's new after updates" shows them
  afterward.
- About shows the version and which app installed Vela.
- Project notices, such as "search is down", reach the map without an app update.
- "Support Vela" opens the donation page. The app asks once, after a week.

## Recorded trips and diagnostics

- "Save my trips (for replay)" records drives on the phone, off by default. Replay, rename or delete
  them. "Select trips", or a long press on one, picks several to share or delete together.
- Sharing a trip first trims the points near its start, its end, Home and Work.
- Diagnostics, off by default, keeps a local log that "Export debug session" shares where you
  choose.
- "Redact places in exports" hides searches, destinations and place names.
- After a crash, "Export crash report" shares the saved report.
- "Simulate driving (demo)" and "Simulate my location (demo)" run the app without real GPS, on the
  car screen too.
- "Nav smoothness trace" and "Building overlay debug" are developer aids.

## Not in Vela

These need a Google sign-in or a Vela server, and Vela has neither.

- Contributing reviews, photos or map edits.
- Live location sharing and shared arrival times.
- A timeline of where you have been.
- Live busyness. Popular times shows the typical week.
- Syncing saved places between devices through an account.
