# Vela Maps

A degoogled maps & navigation client for Android - *what NewPipe is to YouTube,
for Google Maps.* Open vector tiles for the basemap, the device itself scraping
Google's public web endpoints (per-user, no backend) for the things only Google
does well: POI quality, routing, and **traffic-aware ETAs**. Built to run on
GrapheneOS and other no-GMS ROMs.

## Screenshots

| Navigation | Map & search | Place details | Directions | Search results |
|:-:|:-:|:-:|:-:|:-:|
| <img src="docs/screenshots/05-navigation.png" width="150"> | <img src="docs/screenshots/01-map.png" width="150"> | <img src="docs/screenshots/03-place.png" width="150"> | <img src="docs/screenshots/04-directions.png" width="150"> | <img src="docs/screenshots/02-search.png" width="150"> |

| Public transit | Light theme - map | Light theme - place |
|:-:|:-:|:-:|
| <img src="docs/screenshots/06-transit.png" width="150"> | <img src="docs/screenshots/08-map-light.png" width="150"> | <img src="docs/screenshots/09-place-light.png" width="150"> |

*Turn-by-turn with lane guidance, route shields and the speedometer; the keyless
OpenFreeMap basemap wearing Google's own sampled colors and Roboto labels, with
rated POI icons and the tiered dots; live place data; the directions panel with
alternates, traffic in plain words and the avoid toggles; and the in-app
light/dark themes (decoupled from the OS).*

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/PimpinPumpkin/Vela)&nbsp;&nbsp;[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid (Vela's own repo)" height="54">](FDROID.md)

Tap a badge - no Play Store, no account. Obtainium auto-tracks the
**weekly stable** release; turn on "include prereleases" and you get the
**nightly** channel instead (every push to main). The F-Droid badge is
**Vela's own repository**, not the f-droid.org catalog - add
`https://pimpinpumpkin.github.io/Vela/repo` to any F-Droid client and it serves
the same signed APKs, weekly stable by default (fingerprint and nightly-channel
setup in [FDROID.md](FDROID.md)). Or grab an APK straight from
[Releases](https://github.com/PimpinPumpkin/Vela/releases).

---


## What you get

- **Live traffic, straight from Google.** Vela reads the same real-time traffic
  Google Maps shows, so ETAs are traffic-aware, the fastest route leads the list,
  and every option says light, moderate or heavy traffic in plain words next to
  its green/amber/red time. Turn-by-turn navigation rides on it: lane diagrams,
  exit shields, a speedometer with the posted limit, and automatic reroutes when
  a jam builds ahead.
- **Real info about places (points of interest, or POIs).** Hours with holidays
  included, reviews you can search, photo galleries, busy times, phone and
  website, with a warning if a place would be closed when you arrive.
- **Zero Google on your phone, and almost zero in your life.** No Play Services,
  no account, no app key, no ads, no telemetry. Google never sees your GPS trace, your map browsing, or who
  you are. The full breakdown is in the [Privacy](#privacy) section below.
- **Flock cameras, on the map.** Mapped ALPR surveillance cameras (the
  community DeFlock project's OpenStreetMap data) draw out of the box, and the
  optional **Settings → Map → Avoid surveillance cameras** counts the cameras on
  each route and quietly picks a lower-camera option when the detour is small.
  The dataset lives on your phone and refreshes itself weekly.
- **Vela Voice.** Spoken turn-by-turn from a neural voice that runs entirely on
  your phone, and a mic that transcribes your search on-device too. Nothing you
  say ever touches a cloud speech service.

  🔊 **Hear it** - the actual in-app voice at the default pace:

  https://github.com/user-attachments/assets/17f246e4-51c8-4d01-998b-dcd7f29dc15f

- **Parking memory.** Tap **P** when you park, tap the pin later for walking
  directions back, long-press for your parking history.
- **Material You theming, if you want it.** An optional toggle tints Vela's
  chrome with your wallpaper colors (Android 12+); off by default, and the map
  itself stays clean either way.
- **Offline maps and routing.** Download a state or country once and its maps,
  turn-by-turn routing, and every place in it stay searchable with no signal -
  typed street addresses included.
- **Live gas prices.** Search for gas and every station's current price is right
  on its map marker, in the result list, and on the place page.
- **Live public transit.** Departure boards and station-by-station stop timelines
  come from open GTFS feeds (the schedules and realtime updates transit agencies
  publish, served by the community Transitous project), supplemented with Google
  for traffic-aware transit directions. Tap a stop for live times, tap a route
  for every stop it makes.
- **Lists**: the bookmark button next to the category chips opens **Your lists**.
  Create one there, or add any place from its page (⋮ → Save to list), with a note
  per place. Lists back up to a file from Settings.
- **Import a Google Maps list**: paste a `maps.app.goo.gl` share link into the
  search bar. The list's places show up as results with the owner's notes; tap
  **Save list** to keep a local copy.
- **Fixes itself when Google moves things.** The scraping recipes live in a signed
  config the app checks at launch - when Google shifts a field or an endpoint, a
  repair ships to every install in minutes, no update needed. The same channel can
  push a heads-up notice ("search is down, fix coming") straight onto the map.
- **The rest.** Android Auto, 15 languages (incl. Hebrew, the first RTL locale),
  in-app light/dark, full D-pad operation for keypad phones, place lists, and a
  built-in updater with weekly-stable or nightly channels.

The complete running feature list lives in [FEATURES.md](FEATURES.md).


## Why a degoogled app uses Google

A phone without Google Play Services cannot run Google Maps, and the open map
datasets fall well short on search, reviews, hours, and live traffic. So Vela is
a thin client over Google's public web endpoints. It asks them the same way a
logged-out browser does, once per user, with no account, no shared API key, and
no server in the middle. NewPipe does the same for YouTube. There are no ads, and
your searches, saved places, and history stay on the phone. If you run GrapheneOS
or another no-GMS ROM, this gets you working maps back.

The map itself, the streets, the labels, and the house numbers all come from OpenStreetMap. Google is only used for places, search, routing, and traffic. So street names and house numbers can differ from what Google Maps shows, and how much detail you see offline depends on how well OpenStreetMap covers your area. I'm thinking of ways to improve OSM and fill the gaps in the data. Stay tuned.

## Privacy

There is **no Vela backend, no account, and no telemetry**. Vela fetches from Google
directly from your phone like a logged-out browser - Google sees your IP, query, and
map area, but **not a Google account or any app key**, much like using
`google.com/maps` in an incognito window. Your saved places, history, and settings
never leave the device. **[Read the full breakdown of exactly what each service
receives → `PRIVACY.md`](PRIVACY.md).**

The short version: Google shrinks from *knowing who you are and everywhere you go* to
*occasionally answering an anonymous question*. And the parts that matter most while
driving - your GPS trace, your map browsing - never reach Google at all.

| What Google gets | Google Maps app | Google Maps web | Vela |
| --- | --- | --- | --- |
| Tied to your Google account | Yes, always signed in | Yes unless incognito | Never - there is no login |
| A persistent device identifier | Yes (device + ad IDs via Play Services) | Browser cookies | No account, no app key; just an IP like any website visitor |
| Your precise GPS position | Continuously while open, plus Location History if enabled | While the tab is open | Never sent. Position stays on the phone; searches send the map area you are looking at |
| Every pan and zoom of the map | Yes - their servers render the map | Yes | No - map tiles come from OpenFreeMap, so Google never sees you browse |
| Your searches | Yes, saved to your account history | Yes | The query text reaches Google anonymously, only when you search |
| Place pages you open | Yes | Yes | The place lookup reaches Google anonymously |
| Turn-by-turn routes | Yes, full trip telemetry | Yes | Routing runs on open OSRM/GraphHopper; Google sees one anonymous ETA check, never your live position |
| Saved places, home, work | Stored on their servers | Stored on their servers | Stored only on your phone |
| Ad profile building | Feeds your ads profile | Feeds your ads profile | Nothing to attach it to |
| Works with no Google contact at all | No | No | Yes - downloaded regions search, route, and navigate fully offline |

Full per-request detail is in [PRIVACY.md](PRIVACY.md).

## How it works

Every capability, the keyless method behind it, and the file to read first is laid out in
**[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)** - the one-screen index into the codebase
(basemap, the Google scrape, open routing, the offline stack, remote self-repair, …).
Deeper still is [`SPEC.md`](SPEC.md).

## Docs - where to look

| File | What's in it |
|---|---|
| [`README.md`](README.md) | This - what Vela is, why, and the privacy comparison |
| [`docs/HOW-IT-WORKS.md`](docs/HOW-IT-WORKS.md) | Every capability, the keyless method behind it, and the file to read first |
| [`docs/BUILDING.md`](docs/BUILDING.md) | Building from source, module architecture, and the release pipeline |
| [`FEATURES.md`](FEATURES.md) | The full, categorised list of every shipped capability (the encyclopaedia) |
| [`SPEC.md`](SPEC.md) | The authoritative **rebuild spec** - architecture, extractor contract (pb layouts + response indices), resilience layer, hard constraints |
| [`ROADMAP.md`](ROADMAP.md) | Planned work + big bets (opt-in telemetry, a Vela-own traffic layer, giant-country graph splits, …) |
| [`PRIVACY.md`](PRIVACY.md) | Exactly what each Google endpoint receives, per request |
| [`CLAUDE.md`](CLAUDE.md) | Build rules, module layout, and the hard-won gotchas - for contributors (human or AI) |
| [`docs/dpad.md`](docs/dpad.md) | D-pad / no-touchscreen operation - design, findings, per-surface audit, and the merge-with-upstream policy |
| [`docs/CALIBRATION.md`](docs/CALIBRATION.md) | The Google extractor + the signed remote-repair channel, in depth |
| [`docs/MAP-STYLE.md`](docs/MAP-STYLE.md) | Basemap, fonts, custom layers and theming details |

## The Google extractor & calibration

The full write-up moved to [`docs/CALIBRATION.md`](docs/CALIBRATION.md): the per-user
extractor, the signed remote-repair channel, and the recalibration playbook.

## Degoogled / GrapheneOS notes

- **Location:** AOSP `LocationManager`, never `FusedLocationProviderClient`. On
  GrapheneOS, enabling PSDS (Settings → Location) drops the cold GPS fix from
  ~30s to a few seconds - Vela shows a one-time tip when it notices a slow fix.
- **Voice:** the neural nav voice and Whisper voice search both run entirely
  on-device (bundled sherpa-onnx runtime, models downloaded in-app); any system
  TTS engine can be selected instead. Details in [FEATURES.md](FEATURES.md).
- **No GMS anywhere:** no Fused location, no FCM, no Firebase, no Play
  Integrity. Everything (MapLibre, OkHttp, Compose, Hilt) is pure AOSP.

## Map style

Details moved to [`docs/MAP-STYLE.md`](docs/MAP-STYLE.md).

## Roadmap

Everything shipped so far is in [FEATURES.md](FEATURES.md) (the complete list) and the
release notes of each build. Still open (details in [ROADMAP.md](ROADMAP.md)):

- [ ] Restaurant menu reliability (Google tags menu photos inconsistently; digging in)
- [ ] Satellite imagery layer (open Esri World Imagery)
- [ ] Optional offline highway refs
- [ ] Embedded Mapillary/KartaView street imagery (needs a token; Street View currently opens Google's keyless pano in the browser)
- [ ] F-Droid submission + reproducible build

**Not going to happen** - anything that needs you to sign in to Google or hand data to a
backend: contributing reviews/photos/edits, live location sharing and share-ETA, a location
timeline, and the login-gated data Google strips from anonymous requests (live busyness).
Vela's whole point is that no account and no server ever sees you.


## A note on the name

**Vela Maps** (`app.vela`) - the navigator's constellation "the Sails", and
"sail" in several languages.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first - it covers the hard rules (no
backend, no static Google keys, degoogled runtime, the `:core`/`:app` module
boundary, docs-in-the-same-commit, and translations for all 14 locales) and how to
send a change. There is no separate code-of-conduct document by design: keep it
about the code. Security issues go through [`SECURITY.md`](SECURITY.md) (GitHub
private vulnerability reporting), not a public issue.

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

Vela is Free Software: you can use, study, share, and improve it at your will. You may use, modify, and redistribute this project only if your modifications remain open-source under the same license.
