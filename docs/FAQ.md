# Vela FAQ

Short answers to what people actually ask. Longer detail lives in
[PRIVACY.md](../PRIVACY.md), [README](../README.md) and
[SPEC.md](../SPEC.md). For the rule-by-rule version of any answer here -
what decides which places show, when the data is rebuilt, how the camera avoidance picks a
route - see [the book](book/README.md).

## Are the shops and restaurants on the map Google's?

Not by default. Settings > Places > "Places come from" has three choices:

- **Vela data** (the default): the businesses on the map come from open data baked into
  Vela's own map files, plus the shops mapped in OpenStreetMap. Panning around asks Google
  nothing and works with no connection.
- **Google**: the map's businesses come from Google, fetched once each time you stop panning.
- **Both**: Vela's data draws the map and one Google request tops it up with whatever it
  lacks. Where the two describe the same shop, Google's pin wins, because its coordinate is
  usually the storefront.

Whichever you pick, **searching** and **tapping a place for its hours, reviews and photos**
still go to Google, unless you are offline in a downloaded region. The switch under
"Look up tapped places on Google" turns the tap lookup off on its own.

## What does each part of the app actually use?

| What you see | Where it comes from | Reaches Google? | Works offline? |
| --- | --- | --- | --- |
| The map itself: roads, buildings, labels, house numbers | OpenStreetMap, served as vector tiles by OpenFreeMap, or from a downloaded region | Never | With a downloaded region |
| Businesses on the map | Vela's baked open data (Overture, AllThePlaces, OpenStreetMap), or Google, or both: your choice | Only in Google or Both mode | Vela data mode, in a downloaded region |
| A place's hours, reviews, photos, phone | Google, when you tap the place | Yes | No; the tile's own name, type and address still show |
| Search | Google; offline, Vela's own place and address data for the region | Yes when online | Yes, within a downloaded region |
| Turn-by-turn routes | The open OSRM router, or OsmAnd-format files on your phone when a region is downloaded | No | Yes, with a downloaded region |
| Live traffic and arrival times | Google | Yes | No; you still get a route and a free-flow estimate |
| Re-routes while driving | The open router first; Google's route as the fallback, and the phone's own data when there is no signal | Yes, unless the phone answers first | Yes |
| Speed limits, traffic lights, stop signs, level crossings | OpenStreetMap, baked per region | Never | Yes |
| Surveillance and speed cameras | OpenStreetMap and DeFlock, bundled or baked | Never | Yes |
| Transit departures | Transitous, an open GTFS service | Never | Cached areas only |
| Street View | Google | Yes | No |

There is no Vela server anywhere in that table, no account and no telemetry.

## Can I use Vela without Google at all?

Yes. **Settings > Privacy > "Use Vela without Google"** turns off every request to a Google
host in one go: places on the map come from Vela's own data, search uses OpenStreetMap and
the regions you have downloaded, routes come from the open router, and nothing else asks
Google either (no Street View, no traffic overlay, no satellite close-ups, no transit
directions, no reviews or photos). Download the region you live in under Settings > Offline
maps and the map, search, addresses, routing and navigation all work with no signal too.

What you keep: the map, search by name and address, turn-by-turn navigation with voice, speed
limits, cameras, stop signs and lights, transit departure boards, saved places and offline
routing.

One exception you can turn off: a short Google Maps link someone sends you (`maps.app.goo.gl/...`)
only says where it points when Google's link shortener is asked. Vela asks it once, with no
cookies, then opens the place with the open sources. Switch off **"Open shared Google Maps
links"** under the same setting to refuse those links instead. Shared lists can't open without
Google either way.

What you lose: reviews, photos, opening hours, live traffic and its arrival times, Street
View, transit directions, the satellite close-ups, and the long tail of businesses that only
Google knows about.

The four individual toggles the older recipe used (Places come from, Look up tapped places,
Live traffic re-checks, the traffic overlay) still exist; the switch does not change them, it
sits above them.

## Why is a shop missing, or in the wrong place?

In Vela data mode the businesses come from open datasets, and they are not perfect. Two
things are worth knowing:

- **A shop pinned in the parking lot** usually means the source put it at the middle of the
  property rather than at its door. Vela prefers OpenStreetMap's position when the same shop
  is mapped there, so fixing it in OpenStreetMap fixes it in Vela, and in every other map
  that uses OSM.
- **A missing shop** is a gap in the data, not a filter. Adding it to OpenStreetMap is the
  durable fix. Switching to Both mode fills the gap immediately from Google.

## Why does the app talk to Google at all?

A phone with no Google Play Services cannot run Google Maps, and the open datasets fall
short on search, reviews, hours and live traffic. Vela asks Google's public web endpoints
the same way a logged-out browser does: no account, no API key, no server in the middle.
The full breakdown of what each request carries is in [PRIVACY.md](../PRIVACY.md).

## What is marked "experiment"?

Settings label experiments plainly, and they are off by default. Today that means "Tap
places while driving" (Settings > Navigation). It works, but it is newer and rougher than
the rest. The Google-style route picker used to be here; it is the default now, and
Settings > Navigation > "Google-style route picker" turns it off for the classic panel.

## Does Vela work on a phone with no Google services?

That is what it is for. It runs on GrapheneOS, CalyxOS, /e/OS and any other de-Googled
Android, with no Play Services and no microG.

## Where do updates come from?

GitHub releases, through Obtainium, the F-Droid repo or a manual APK. There is a weekly
stable channel and a nightly one; the in-app updater follows whichever you pick under
Settings > About.
