# Vela Maps - Privacy

What leaves your phone, where it goes, and what does not. Vela asks Google's public web
endpoints for some things, so Google does see some of your requests, the way it sees a
logged-out browser's. They are not tied to an account.

## In short

- **Nothing of Google's runs on your phone.** No Play Services, no Google SDK, no account, no
  API key. Google's public servers answer the app's anonymous requests. That is the design, the
  same as NewPipe's for YouTube. It is not a claim of zero Google contact.
- **There is no Vela server.** No backend, no account, no analytics, no crash reporting, no ad
  SDK. Nothing you do is sent to the project.
- **Browsing the map does not reach Google, by default.** The businesses you pan past are
  Vela data: Overture Maps and AllThePlaces, positioned with OpenStreetMap, built in Vela's
  repository and streamed from its releases, or read from a downloaded region. Settings >
  Places > "Place icons on the map" is set to **Vela data** out of the box. **Both** adds one
  Google request each time the map settles, and **Google** asks on every pan.
- **Vela asks Google directly from your phone** for searching (autocomplete as you type, and
  the search when you submit), for opening a place (hours, reviews, photos, busy times), and
  for driving routes and their traffic. Google sees your IP address, the search text, and the
  map area or the trip's endpoints. It does not see a Google account, and no key labels the
  traffic as Vela's. The one possible label is a header Android's WebView adds; see below.
- **One switch turns Google off:** Settings > Privacy > "Use Vela without Google". The first run
  asks the same question before the map loads. With Google on, the Privacy page lists every
  smaller switch in one place: reviews, photos, tapped-place lookups, the traffic requests.
  With the main switch on, search
  uses OpenStreetMap (the Photon geocoder) and your downloaded regions, routes come from the
  open routers with no live traffic, and no request goes to a Google host. One exception:
  opening a shared short link (`maps.app.goo.gl/...`) asks Google's link shortener where it
  points, once, with no cookies. "Open shared Google Maps links" under the same switch refuses
  those links.
- **A few open services** get small, specific requests (map tiles, routing, address lookups,
  transit boards, terrain). The table lists them.
- **Your places, history and settings stay on the phone.** There is no cloud sync.

## What each service receives

| Service | When | What it gets |
|---|---|---|
| **Vela's own place data** (GitHub releases) | browsing the map with "Vela data" | your IP, and which byte ranges of a file you read, which implies your rough map area |
| **google.com** (autocomplete) | as you type in the search box, each time you pause, from the second character | your IP, the text so far, the map view (center and span), and a cookie that lasts only while Vela runs. When the map shows somewhere 50 km or more from you, a second request centered on your position |
| **google.com** (search) | every search you submit, and a tap on a place on the map | your IP, the query (for a tap, the place's name), the map view (for a tap, the tapped spot). When you search from inside the view, a second request over about 2.5 km around your position so nearby places rank first. The same short-lived cookie |
| **google.com** (places on the map) | only with "Place icons on the map" on **Both** (one request after you stop panning) or **Google** (as you pan) | your IP, the map area |
| **google.com** (place details, photos, reviews) | opening a place | your IP, the place's name and address or Google's id for it, and the Google session cookie (below) |
| **google.com** (directions) | planning a driving route, a walking route once, and a cycling route when the bike-lanes setting is off or its routers have no answer | your IP, and the coordinates of the start, the end and any stops. The start is your position when you route from "Your location". Google's answer is the route you drive |
| **google.com** (directions, during a drive) | when you leave the route, and every 2 minutes or so for traffic | your IP, your current position and the destination. Settings > Navigation > "Live traffic re-checks while navigating" turns the periodic one off |
| **google.com** (hidden pages) | the features only a browser engine gets, listed under "The hidden pages" | your IP, the page's address (the place, or a transit trip's endpoints), the Google session cookie, and whatever Google's own page script collects |
| **google.com / googleapis.com** (Street View) | opening Street View, walking between panoramas, going back in time | your IP, the place's coordinate or a panorama id, then image tile coordinates |
| **google.com/maps/vt** (traffic layer) | "Live traffic overlay" on (Settings > Map, off by default) | your IP, the tile coordinates in view |
| **mt1.google.com** (satellite close-ups) | satellite view, zoomed in past where Esri has imagery | your IP, tile coordinates |
| **maps.app.goo.gl** | opening a shared short Google Maps link | your IP and that link. No cookies |
| **OpenFreeMap** | viewing the map | your IP, which map tiles you pan over |
| **Esri World Imagery** | satellite view (off by default) | your IP, tile coordinates, and the view's bounds (to label the imagery date) |
| **Photon** (komoot's OpenStreetMap geocoder) | typing text that starts with a house number, and every search with "Use Vela without Google" on | your IP, the typed text, a bias point (the map center or your position) |
| **OSM Nominatim** | long-pressing to drop a pin, tapping a house number, a building or an unnamed icon | your IP, that one coordinate |
| **Transitous** (open transit data) | transit stops at street zoom, colored rail lines while "Highlight transit lines" is on, every departure board (refreshed every 30 s while open), and the real path of a transit trip you expand | your IP, the map area, a stop's id or coordinate, or the trip's two stops |
| **AWS** (terrain tiles) | hillshade relief | your IP, tile coordinates |
| **FOSSGIS OSRM** | every route you plan and every reroute | your IP, the start, end and stop coordinates, and during a reroute your position and heading. Its turn instructions are used wherever its roads match the route |
| **FOSSGIS Valhalla** | a driving route where Google's path and OSRM's differ; bike routes with "Bike routes prefer bike lanes and quiet streets" on (the default) and no downloaded region | your IP. For a drive, the coordinates along the stretches that differ, to get their street names. For a bike route, the start, end and stops |
| **Overpass** (OpenStreetMap) | only where no baked region file covers the spot: traffic lights and stop signs, the opt-in speed camera layer, and with "Traffic-light guidance" on the lights along a route when its drive starts | your IP, a bounding box |
| **raw.githubusercontent.com, api.github.com, GitHub Pages** | at launch (settings file, map fonts), about once a day for the update check, once after an update for the What's new notes | your IP. A plain file download |
| **GitHub release files** | downloading regions, voices and app updates, and streaming the place, building and house-number layers as you browse | your IP, and which file or byte range is fetched, which implies your rough map area |
| **GitHub release files** (UK fuel prices) | a gas station in the UK shows up in your results or on a place page without a price; at most every 3 hours after that | your IP. The file is the same one nationwide for everyone, so it says only that you looked at a UK gas station |

None of these receives a Google account, a name, a device id or your contacts.

Settings > Privacy > "Live traffic only when I tap" keeps a planned route on the open routers
alone until you tap Show traffic on the route list. Until then Google is not asked.

## Google, specifically

Google sees the most. Per request it receives your IP address, the search text or the
coordinates, the map area, a browser's User-Agent, the consent cookies (`SOCS` / `CONSENT`,
seeded so the EU consent wall does not block you; they carry no identity) and Google's own
logged-out session cookie. Vela sends these requests over Cronet, Chrome's network stack.

What Google does not get from Vela:

- **A Google account.** Vela never signs in.
- **An API key.** Vela's requests are not stamped as coming from an app. The exception is the
  hidden pages below: Android's WebView adds the app's package name
  (`X-Requested-With: app.vela`) to every request it makes itself, and apps cannot turn that
  off. Vela sends those pages' requests over its own network stack instead, which leaves the
  header off. A request Vela cannot carry that way still has it.
- **A device id.** What Google keeps is its own session cookie, the same thing any logged-out
  browser gets. There are two:
  - Searches, autocomplete and directions carry a cookie that exists only in memory and starts
    over every time Vela starts.
  - Opening a place and the hidden pages carry the WebView's cookie, which is kept on disk like
    a browser's. While a session lasts, Google can link the places opened under it into one
    history with no name attached. **Settings > Privacy > Google session** starts a new one
    every week by default, every day, or every time Vela opens, and has a button to start one
    now.

**The limited view.** Google gives some signed-out sessions, new ones especially, a trimmed
answer: fewer photos per page, popular times missing, and a "More reviews" button that loads
nothing. It is decided per session, not per IP address. Vela notices and says so in one line on
the place sheet and under Settings > Privacy > Google session. It usually lifts by itself.
Starting a new session rarely helps, because new sessions start out limited, which is why the
default is a week.

**Compared with the Google Maps app:** there you are normally signed in, so Google ties every
search, route and stop to your account. With Vela it is closer to `google.com/maps` in a
private browser window: Google sees the IP and the individual requests and cannot link them to
an account. Your IP is still visible to Google and to every service in the table. To hide that
too, run Vela over a VPN or Tor.

| What Google gets | Google Maps app | Google Maps web | Vela |
| --- | --- | --- | --- |
| Tied to your Google account | Yes, always signed in | Yes unless incognito | Never. There is no login |
| A persistent device identifier | Yes (device and ad IDs via Play Services) | Browser cookies | No account, no app key. A logged-out session cookie that Vela replaces weekly by default, and an IP like any website visitor |
| Your precise GPS position | Continuously while open, plus Location History if enabled | While the tab is open | Never while browsing. Searches send the map area and can ask about a small area around you. A route from your location sends that point. During a drive, reroutes and the optional traffic re-check send your current position |
| Every pan and zoom of the map | Yes, their servers render the map | Yes | No, with "Place icons on the map" on Vela data (the default). On Both, one places request with the map area after you stop panning |
| Your searches | Yes, saved to your account history | Yes | The text reaches Google anonymously, as you type and when you search |
| Place pages you open | Yes | Yes | The lookup reaches Google anonymously, under the session cookie above |
| Turn-by-turn routes | Yes, full trip telemetry | Yes | Google is asked for the route between two points, and again during the drive for reroutes and the optional traffic re-check. Your GPS trail as a whole never leaves the phone |
| Saved places, home, work | Stored on their servers | Stored on their servers | Stored only on your phone |
| Ad profile building | Feeds your ads profile | Feeds your ads profile | Nothing to attach it to |
| Works with no Google contact at all | No | No | Yes: "Use Vela without Google" online, and downloaded regions offline |

## The hidden pages

Some of Google's data is only served to a real browser engine. For these Vela loads a
`google.com/maps` page in a hidden, logged-out WebView and reads the result:

- **Reviews:** a place's first reviews, loaded when you scroll to them, and the full-screen
  reviews page when you open it. Settings > Places > "Show reviews" turns reviews off, and
  "Load reviews only when I tap" waits for a button.
- **Photos, as a fallback:** the first photos and each "More photos" page are plain requests.
  The gallery page, which also carries the Menu tab, is loaded only when those come back empty
  or cannot page further.
- **Popular times and missing details:** only when the search reply and a plain follow-up
  request both came back without them.
- **Transit directions**, and a run's stop list when the open transit data has none.
- **Departure boards** where Transitous has no coverage for the stop.

Settings > Performance > "Load all photos and reviews" (off by default) makes every place you
open walk its whole gallery and up to 50 reviews, which means more page loads.

This is the one place Google's own JavaScript runs on your phone. It runs with no login, but
like any browser visit to Google it can set cookies or fingerprint the browser, and it sends
Google's usual page telemetry. That telemetry goes out by default, because a browser that never
sends it looks less like a person, which feeds into whether a session gets the limited view.
**Settings > Privacy > "Block Google's page telemetry"** answers those calls on the phone
instead. Nothing Vela shows depends on them. If you never open a Google place, transit
directions, or a board outside Transitous's coverage, no hidden page loads.

**You can see the numbers.** Settings > Privacy > "Requests to Google" counts every request
Vela sends to Google, today and over the last week, by purpose, including what the hidden pages
load. It is counted on your phone and sent nowhere. The only Google traffic it cannot see is
map tiles the map draws itself (the traffic layer and some satellite imagery).

## What stays on your phone

Stored locally and never transmitted:

- Saved places, Home and Work, your lists, saved routes, recently viewed places, recent
  searches, parking history
- Settings
- Downloaded regions and areas
- Places you opened while online, kept so they open offline ("Keep viewed places for offline")

Uninstalling the app removes all of it.

**Contacts (off by default).** With Settings > Search > "Search your contacts" on, typing a
contact's name suggests their saved postal addresses, with the contact's photo and the address
label from your address book. The contact list is read and matched on the phone; the
permission is asked when you flip the switch. If you pick a suggestion, the address text is
looked up as if you had typed it: it goes to Google's search, to Photon with "Use Vela without
Google" on, or to the downloaded region's address index offline. The result opens under the
contact's name on this phone only. The name is never sent anywhere.

## No tracking

Vela has no analytics, no advertising, no crash reporting, and no telemetry of its own that
runs without you turning it on. The one telemetry that runs by default is Google's, from inside
the Google pages Vela loads in hidden WebViews, and Settings > Privacy can block it. The app
makes network requests only to the services in the table, only when a feature needs them. It is
GPLv3: every request is in
[`core/data/google`](core/src/main/java/app/vela/core/data/google),
[`app/web`](app/src/main/java/app/vela/web) and [`SPEC.md`](SPEC.md).

## Voice search (optional)

The search bar's mic turns speech into a query one of two ways:

- **On your phone.** If you download one of the speech models (Settings > Search; Whisper tiny,
  about 58 MB, is the default), the mic records into Vela and transcribes on the device. The
  model is downloaded once from Vela's GitHub releases. The audio is never written to disk and
  never leaves the phone. Vela asks for the microphone permission the first time you tap the
  mic.
- **Another voice app.** With an installed voice-input app (FUTO Voice Input, for example),
  the mic hands off through Android's speech-recognition intent. That app records the audio.
  Vela gets the recognized text back and needs no microphone permission for this path.

With neither available, the mic offers to download Vela's own model. Settings > Search turns
the feature off.

## Diagnostics (off by default)

Settings > Diagnostics > "Share diagnostics" keeps a short local log of what the app did:
searches, routes computed, parsing failures, navigation events. It exists so you can export it
and hand it to a developer.

- **Vela uploads nothing.** The log is a small file on your phone. It leaves only when you tap
  "Export debug session" and choose where to send it.
- **It can contain** your search terms, the start and end coordinates of routes, a drive's
  destination name, distance and time, GPS-gap markers, and for the reviews page Google's id
  for the place and how the page loaded. No account, no contacts, no continuous location trail.
- **Turning it off deletes the log.** An export rounds coordinates to about 1 km. "Redact
  places in exports" goes further for a report you mean to post publicly: coordinates round to
  about 10 km, and searches, destinations, links and place names become `[redacted]`.

## Trip recording (off by default)

Settings > "Save my trips" is a second switch and more revealing than diagnostics.

- When on, Vela records the GPS trace of each drive to a file on your phone, so a trip can be
  replayed later to test turn-by-turn.
- This is your exact movement, the most sensitive thing the app stores. Vela never uploads it.
  A trace leaves the phone only when you tap Share on a trip and choose where to send it.
- **Share trims the private ends first, and that is the default button.** "Share trimmed"
  deletes every recorded point within a distance you pick (200, 400 or 800 m) of the start, the
  end, the recorded destination, and your Home and Work, along with the trip's name, the
  destination in the file header, the spoken directions at either end, and the start of the
  saved route line. Timestamps are rebased to zero. The middle of the drive is kept at full
  precision, because that is where a bug lives. The dialog shows what it removed, and the first
  remaining coordinate, before anything leaves the phone. The untrimmed file is behind "Share
  full trace", and the copy on your phone is never modified.
- **Several trips at once** (Select trips > Share) go out as one zip, every trip trimmed at one
  distance. A trip too short to keep anything is left out, never sent untrimmed.
- Recorded trips have Replay, Share, Rename and Delete in Settings.

There is no traffic-sharing or location-sharing feature. If one is ever added it will need a
server and a new consent screen, and this file will change the day it ships.

*Something inaccurate here? Open an issue.*
