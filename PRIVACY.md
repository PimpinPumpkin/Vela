# Vela Maps - Privacy

> What leaves your phone, where it goes, and what it doesn't. Written to be honest
> rather than reassuring: Vela scrapes Google's public web endpoints, so Google does
> see some of your requests - but as a logged-out browser would, not tied to an account.

## TL;DR

- **"Degoogled" means what it means for NewPipe.** Nothing of Google runs on or is
  installed on your phone: no Play Services, no Google SDK, no account, no API key.
  Google's public servers still answer the app's anonymous requests - that is the whole
  design, a libre front end to Google's data, not a claim of zero Google contact.
- **There is no Vela server.** Vela has no backend, no account, no analytics, no crash
  reporting, no ad SDK. Nothing you do is sent to *us* - there is no "us" to send it to.
- **Browsing the map does not touch Google at all, by default.** The businesses you pan
  past are **Vela data**: an open-data build of Overture Maps and AllThePlaces, positioned
  with OpenStreetMap, baked in Vela's own repository and streamed from its releases (and
  carried offline with a downloaded region). Panning, zooming and looking around send
  Google nothing. Settings > Places > "Places come from" is where that lives, set to
  **Vela data** out of the box; **Both** adds one Google request per settled view, and
  **Google** asks on every pan. This is the part people most often assume works the other
  way round.
- **Vela talks to Google directly from your phone**, the same way `maps.google.com`
  in a browser does, for **searching** (Google's own autocomplete as you type, and the
  search when you submit), **opening a place** (hours, reviews, photos, busy times) and
  **live traffic** on the routes you ask for. Google therefore sees your **IP address**,
  your **search text**, and the **map area** of those requests - but **not a Google
  account** (you're never signed in) and **no app/API key** that labels the traffic as
  "Vela" (the one label is a header Android's WebView adds, see below). Tapping a place is
  what asks about that place; Settings > Places > "Look up tapped places on Google" turns
  that off for places tapped on the map, leaving what the open data carries.
- **One switch turns Google off entirely:** Settings > Privacy > "Use Vela without Google".
  Search then uses OpenStreetMap (the Photon geocoder) and your downloaded regions, routes
  come from the open router with no live traffic, and no request goes to a Google host. The
  one exception: opening a shared short link (`maps.app.goo.gl/...`) asks Google's link
  shortener where it points, once, with no cookies; "Open shared Google Maps links" under
  the same switch refuses those links instead.
- A few **non-Google open services** get small, specific requests (map tiles, routing,
  address lookups, transit boards, terrain) - see the table.
- **Your places, history, and settings stay on the device.** Saved/Home/Work/recent
  places, preferences, and downloaded offline areas are local only; they're never
  uploaded anywhere.

## What each service receives

| Service | When | What it gets | What it does **not** get |
|---|---|---|---|
| **Vela's own place data** (GitHub releases) | browsing the map, in the default "Vela data" mode | your IP, and which archive byte-ranges you read (implies your rough map area); nothing goes to Google | the query text, your account, anything about what you tapped |
| **google.com** (autocomplete) | as you type in the search box, each time you pause (from the second character) | your IP, the text typed so far, the map viewport (center + span), a cookie that lives only while Vela runs; when the map shows somewhere 50 km or more from you, a second one centered on your position | your Google account, name, device ID, contacts |
| **google.com** (search/place) | every search you submit (Enter, or picking a suggested query), while typing only if the autocomplete fails or comes back empty, and tapping a place to look it up (not browsing) | your IP, the query text (for a tap, the place's name), the map viewport (for a tap, the tapped spot); when you search from inside that viewport, a second request over a ~2.5 km window around **your position** so nearby places rank first; a name typed while the map shows somewhere far from you can be asked once around your position too. Same short-lived cookie as autocomplete | your Google account, name, device ID, contacts |
| **google.com** (places on the map) | only with Settings > Places > "Places come from" on **Both** (one request after you stop panning) or **Google** (as you pan) | your IP, the map area you are looking at | account; nothing in the default "Vela data" mode |
| **google.com** (place details, photos, review feed) | opening a place | your IP, the place's name and address or Google's id for it, and the **Google session cookie** (see below) | account |
| **google.com** (directions) | planning a route (driving and walking; cycling too when the safe bike setting is off or its routers have no answer) | your IP, origin + destination (and stop) coordinates - the origin is your position when you route from "Your location" | account |
| **google.com** (directions, in-drive) | while NAVIGATING: on every off-course reroute, and every ~2 min for the live traffic re-check | your IP, your **current position** + the destination | account. The periodic re-check is what powers faster-route offers, the live arrival time and step recovery; it can be turned off in Settings → Navigation ("Live traffic re-checks while navigating"), leaving only the off-course reroutes, which navigation can't work without |
| **google.com** (hidden pages) | the few features only a browser engine gets, listed under "The hidden WebViews" below | your IP, the page's address (the place, or a transit trip's endpoints), the Google session cookie, and whatever Google's own page script collects | account |
| **google.com / googleapis.com** (Street View) | opening Street View on a place, walking between panos, going back in time | your IP, the place's coordinate or panorama id, then panorama ids and image-tile coordinates | account; no query text - the requests match what an incognito browser makes on Google's own Street View |
| **google.com/maps/vt** (traffic) | "Live traffic overlay" on (Settings > Map, off by default) | your IP, the tile coordinates you're viewing | anything tied to you beyond IP |
| **mt1.google.com** (satellite close-ups) | satellite view on, zoomed in past the level where Esri has imagery for the spot | your IP, tile coordinates | anything tied to you beyond IP |
| **maps.app.goo.gl** (link shortener) | opening a shared short Google Maps link | your IP, that link; no cookies | nothing else |
| **OpenFreeMap** | viewing the map | your IP, which map tiles you pan over | no search/place text - just tile coordinates |
| **Esri World Imagery** | satellite view on (off by default) | your IP, tile coordinates, and the view's bounds (to label the imagery date) | nothing else |
| **Photon (komoot, OpenStreetMap geocoder)** | typing text that starts with a house number; every search when "Use Vela without Google" is on | your IP, the typed text, a bias point (the map center, or your position) | nothing else |
| **OSM Nominatim** | long-pressing to drop a pin, tapping a house number, a building or an unnamed map icon | your IP, that one lat/lng | nothing else |
| **Transitous** (open GTFS transit data) | transit stop icons at street zoom, and every departure board you open (refreshed every 30 s while it is open) | your IP, the map area, or the stop's id or coordinate | nothing else |
| **AWS (terrarium DEM)** | hillshade relief | your IP, tile coordinates | nothing else |
| **FOSSGIS OSRM** | every route you plan, and every re-route while navigating | your IP, origin/destination (and waypoint) coordinates; during a re-route your current position and heading | the primary turn-by-turn router; Google is queried in parallel only for the traffic ETA |
| **FOSSGIS Valhalla** | bike routes with Settings > Navigation "Bike routes prefer bike lanes and quiet streets" on (the default), where no downloaded region covers the trip | your IP, origin/destination (and stop) coordinates | nothing else |
| **Overpass (OSM)** | only where no baked region file covers the spot: traffic-light/stop-sign icons, the opt-in speed-camera layer and "download this area" all read per-region files built from OpenStreetMap extracts first (the catalog covers every country Geofabrik publishes) | your IP, the bounding box | nothing else |
| **raw.githubusercontent.com, api.github.com, GitHub Pages** | at launch (config refresh, map label fonts), about once a day for the update check (Settings > About), and once after an update for the What's new notes | your IP, a plain file fetch | no data *about you* is sent - it's a download |
| **GitHub release assets** | downloading offline regions/voices and app updates, and streaming the place, building and house-number layers as you browse (small ranged tile reads) | your IP, which file or tile byte-range is fetched (implies your rough map area) | no query text, no account |

## Google, specifically

Because Vela scrapes Google rather than running its own maps stack, Google is the
service that sees the most. Concretely, per request Google receives **your IP
address, the search/route text or coordinates, the map area, a browser-like
User-Agent, the consent cookies** (`SOCS`/`CONSENT`, seeded so the EU consent wall
doesn't block you - they carry no identity) **and Google's own logged-out session
cookie** (below). Vela sends these requests over Cronet, Chrome's own network stack, so
at the connection level they look like Chrome rather than like an Android app's HTTP
library.

What Google does **not** get from Vela:
- **No Google account / sign-in.** Vela never logs in. There is no Gmail, no profile,
  no "your timeline."
- **No shared API key.** Vela's own requests aren't stamped as coming from an app called
  "Vela"; they look like an ordinary logged-out browser hitting `maps.google.com`. The one
  exception is the hidden WebView below: Android's WebView adds the app's package name
  (`X-Requested-With: app.vela`) to every request it makes, and apps cannot turn that off.
- **No device id or account id from Vela.** What Google does keep is its own **session
  cookie**, the same thing any logged-out browser gets. There are two:
  - Searches, autocomplete and directions carry a cookie that exists only in memory and
    starts over every time Vela starts.
  - Opening a place (its details, photos and reviews) and the hidden pages below carry the
    WebView's cookie, which is kept on disk like a browser's. While one of those sessions
    lasts, Google can link the lookups made under it (which places you opened) into one
    history with no name attached. Vela caps that: **Settings > Privacy > Google session**
    starts a new session every week by default, every day, or every time Vela opens, and
    has a button to start one now.

  A new session gets a shorter view from Google for a while (fewer reviews, popular times
  missing on some places), which is why the default is a week rather than every launch.
  "Use Vela without Google" avoids Google altogether.
- **Google's "limited view".** Google gives some signed-out sessions, new ones especially,
  a trimmed answer: fewer photos per page, popular times missing on some places, and a
  "More reviews" button that loads nothing. It is decided per session, not per IP address
  (phones sharing one connection have been seen getting different answers). Vela notices
  it and says so in one line on the place sheet and under Settings > Privacy > Google
  session, so a short list reads as Google's choice rather than a broken app. It usually
  lifts on its own; starting a new session rarely helps, because new sessions start out
  limited.

**Versus the official Google Maps app:** there, you're normally signed in, so Google
ties every search, route, and stop to your account and builds your Maps history and
location profile. With Vela it's closer to using `google.com/maps` in a **private /
incognito browser window**: Google still sees the IP and the individual requests, but
can't link them to a Google account or your real-world identity. The honest limit:
**your IP is still visible to Google** (and to every service in the table) - that's
inherent to fetching from them. If you want to hide that too, run Vela over a **VPN or
Tor**; it works over any network.

Row by row, the same comparison the README summarizes:

| What Google gets | Google Maps app | Google Maps web | Vela |
| --- | --- | --- | --- |
| Tied to your Google account | Yes, always signed in | Yes unless incognito | Never - there is no login |
| A persistent device identifier | Yes (device + ad IDs via Play Services) | Browser cookies | No account, no app key; a logged-out Google session cookie that Vela replaces weekly by default, and an IP like any website visitor |
| Your precise GPS position | Continuously while open, plus Location History if enabled | While the tab is open | Never while browsing - position stays on the phone. Searches send the map area you are looking at and, to rank places near you first, can ask about a small area around you (details in the table above); a route from your location sends that point as the start. While navigating, anonymous re-routes and the optional live-traffic re-check send your current position (toggleable in Settings, see the table above) |
| Every pan and zoom of the map | Yes - their servers render the map | Yes | Map tiles come from OpenFreeMap, so Google never renders your view. With "Places come from" on Vela data (Settings > Places, the default), browsing never touches Google; on Both, one anonymous places request carrying the map area goes to Google after you stop panning, to add businesses Vela's own data lacks |
| Your searches | Yes, saved to your account history | Yes | The text reaches Google anonymously, as you type (autocomplete) and when you search |
| Place pages you open | Yes | Yes | The place lookup reaches Google anonymously, under the logged-out session cookie described above |
| Turn-by-turn routes | Yes, full trip telemetry | Yes | Routing runs on open OSRM, or OsmAnd-format region files on the phone; Google is asked anonymously for the traffic ETA, plus your current position during in-drive re-routes and re-checks. Your GPS trail as a whole never leaves the phone |
| Saved places, home, work | Stored on their servers | Stored on their servers | Stored only on your phone |
| Ad profile building | Feeds your ads profile | Feeds your ads profile | Nothing to attach it to |
| Works with no Google contact at all | No | No | Yes - Settings > Privacy > "Use Vela without Google" online, and downloaded regions search, route, and navigate fully offline |

## The hidden WebViews

Some of Google's data is only served to a real browser engine, so for these Vela loads a
`google.com/maps` page in a **hidden, logged-out WebView** and reads the result:

- **Reviews:** the first page of a place's reviews when you open it (Settings > Places >
  "Show reviews" turns this off), and the full-screen reviews page when you open it.
- **Photos, as a fallback:** the first photos and each "More photos" page are plain
  requests; the gallery page (which also carries the Menu tab) is loaded only when those
  come back empty or cannot page further.
- **Popular times and missing details:** only as a last resort, when the search reply and a
  plain follow-up request both came back without them.
- **Public-transit directions**, and a route's stop list when the open transit data has none.
- **Departure boards** where Transitous has no coverage for the stop.

Settings > Performance > "Load all photos and reviews" (off by default) makes every place
you open walk its whole gallery and up to 50 reviews, which means more of these page loads.

This is the one place Google's own JavaScript runs on your device. It runs
**anonymously** (no login), but, like any browser visit to Google, that script *could* set
cookies or fingerprint the browser, and it sends Google's usual page telemetry (logging
pings, `gen_204` beacons, the account bar's background calls). That telemetry **goes out by
default**, because a browser that never sends it looks less like a person to Google, which
feeds into whether a session gets the limited view. **Settings > Privacy > "Block Google's
page telemetry"** answers those calls on the phone instead; nothing Vela shows depends on
them. Every request these pages make also carries Android's `X-Requested-With: app.vela`
header, which names the app. It's an explicit, scoped tradeoff for data a plain request
can't get; if you never open a Google place, transit directions or a board outside
Transitous's coverage, no hidden page loads.

**You can see the numbers.** Settings > Privacy > "Requests to Google" counts every request Vela
sends to Google, today and over the last week, by purpose, including everything the hidden pages
load after they open. It is counted on your phone and never sent anywhere. The only Google traffic
it cannot see is map tiles the map draws itself (the live-traffic layer and some satellite imagery).

## What stays on your device

Stored locally only (SharedPreferences / SQLite / MapLibre's offline store), never
transmitted:
- Saved places, Home/Work shortcuts, your lists, recently-viewed places, recent searches,
  parking history
- Settings (theme, units, voice engine, traffic toggle, keep-screen-on-while-navigating)
- Downloaded offline map areas + their offline POIs
- Dismissed-notice ids

There is no cloud sync. Uninstalling the app removes all of it.

**Contacts (optional, off by default).** If you turn on Settings > Search > "Search your
contacts", typing a contact's name suggests their saved postal addresses, shown with the
contact's photo and the label your address book gives the address ("Home", "Work"). The
contact list is read and matched entirely on this phone (the permission is asked when you flip
the toggle, never at install), and the photo is displayed straight from the phone's contact
store. Nothing about your contacts is uploaded; if you tap a suggestion, the ADDRESS text is
looked up exactly as if you had typed it yourself, which sends that one string to Google's search
like any other search (to Photon with "Use Vela without Google" on, or to the downloaded region's
address index when you are offline). The
result opens under the contact's name on this phone only; the name is never sent anywhere.
Opened contact places appear in your Recents on this phone like any other place you open.

## No tracking

Vela contains **no analytics, no advertising, no crash reporting, and no
Firebase/Play Services**, and **no telemetry of its own that runs without you turning it
on**. The one telemetry that does run by default is Google's, from inside the Google pages
Vela loads in hidden WebViews (see above); Settings > Privacy can block it. The app makes
network requests only to the services in the table above, only when a feature needs them.
It's GPLv3 - you can read every request the code makes in
[`core/data/google`](core/src/main/java/app/vela/core/data/google),
[`app/web`](app/src/main/java/app/vela/web) (the hidden WebViews) and [`SPEC.md`](SPEC.md).

## Voice search (optional)

The search-bar mic turns speech into a query one of two ways, both privacy-preserving:

- **On your phone (Vela's own model).** If you download one of the speech models
  (Settings -> Search; Whisper tiny, about 58 MB, is the default), tapping the mic records
  into Vela and transcribes **entirely on the device**. The model is downloaded once from
  Vela's GitHub releases; the audio is never written to disk and never
  leaves the phone; there is no account and no network request for the transcription. Vela
  asks for the microphone permission only the first time you tap the mic.
- **Another voice app.** If instead you use an installed voice-input app (for example FUTO
  Voice Input), tapping the mic hands off to that app through Android's standard
  speech-recognition intent. **That app records the audio, not Vela** - Vela sends it
  nothing and only gets the recognized text back to drop into the search box, so it needs
  no microphone permission for this path.

With neither available, tapping the mic offers to download Vela's own model. The whole
feature can be turned off in Settings -> Search.

## Diagnostics (opt-in, off by default)

Settings → Diagnostics → **Share diagnostics** is **off by default**. When you turn it on, Vela
keeps a short **local** log of what it did - your searches, the routes it computed, and
any "needs recalibration" hiccups - so that if something misbehaves you can **export it
and hand it to a developer** to debug. Specifics:

- **Nothing is uploaded by Vela.** The log lives only on your phone (a small local file while
  the opt-in is on). The only
  way it leaves is if *you* tap **Export debug session** and then choose where to send it
  (email, a chat app, Files…). You see it's a file; you pick the destination.
- **It contains** the breadcrumbs above - which can include your search terms, the
  start/end coordinates of routes you asked for, and **navigation breadcrumbs** (a
  start/arrival line with the destination name and the drive's distance + time, plus
  "GPS gap" markers noting where the signal dropped and for how long - for debugging a
  bad route or *tuning the turn-by-turn*), and for the full review page, Google's id for
  the place and how the page loaded (tabs seen, review counts, retries). No account, no contacts, no continuous
  location trail.
- **Turning it off wipes the log.** Exports also scrub coordinates down to ~1 km before the
  share sheet ever sees them, so a pasted report can't pinpoint you. **Redact places in exports**
  (same screen, shown whether or not diagnostics is on) goes further for a report you mean to post
  publicly: coordinates round to ~10 km and the searches, destinations, links and place names are
  replaced by `[redacted]`. The same switch makes every trip share start on the largest trim
  distance (see below).

## Trip recording (separate opt-in, off by default)

Settings → **"Save my trips"** is a **second, distinct switch**, also **off by default**
and **more revealing** than diagnostics - so it's deliberately separate, and the first-run
prompt asks for it on its own line.

- When on, Vela records the **GPS trace of each navigation** (the points along your drive
  + the destination) to a **file on your phone**, so a trip can be **replayed** later to
  test turn-by-turn without driving it again.
- This is your **exact routes and movement** - the most sensitive thing the app stores.
  **Vela never uploads it** - there is no auto-upload code path. The only way a trace
  leaves the phone is if *you* tap **Share** on a trip and choose where to send it (the
  same user-initiated FileProvider export as the diagnostics log) - useful for handing a
  drive to a developer to debug a bad route.
- **Share offers to trim the private ends first, and that is the default button.** A drive
  starts and ends where you do, so publishing a raw trace publishes your front door. Choosing
  **Share trimmed** deletes every recorded point within a distance you pick (200 / 400 / 800 m)
  of the start, the end, the recorded destination, and your **Home and Work** if you have set
  them, and with them the trip's name (trips are named after where they went, so it is usually
  an address), the destination coordinate in the file header, the spoken directions at either
  end, and the start of the saved route line. Timestamps are rebased to zero, so the file does
  not say *when* you drive either. The middle of the drive is kept at **full precision**, because
  that is the part a bug lives in. Rounding coordinates instead would protect the ends weakly
  (a 1 km round still names a block) while destroying what the trace is for. The dialog shows
  what it removed, and the first surviving coordinate, before anything leaves the device. The
  untrimmed file is still available behind **Share full trace**, and the copy on your phone is
  never modified.
- **Sharing several trips at once** (Select trips → Share) sends them as **one zip file**, every
  trip in it trimmed at one distance you pick for the batch. The dialog adds up what comes off
  before the zip is made; a trip too short to keep anything after trimming is left out, never
  sent untrimmed. The zip holds no untrimmed trace.
- Manage it in Settings → recorded trips have **Replay**, **Share**, **Rename** and **Delete**;
  turning the switch off stops new recording. Off by default; you choose to enable it.

A future, **separately-announced** opt-in may aggregate anonymized speed traces to build
Vela's own traffic layer - that one needs a server and a fresh consent screen, and this
file will change the day it ships. It does not exist today.

*Questions or something inaccurate here? Open an issue.*
