# How Vela works - each capability and the method behind it

The one-screen map of *what Vela does* and *how*, with the entry point to read next.
Deeper detail is in [`SPEC.md`](../SPEC.md); this is the index into it.

| Capability | Method (how) | Start here |
|---|---|---|
| **Basemap** | Open vector tiles (OpenFreeMap / Protomaps) via MapLibre - keyless, no Google | `core/data/tiles/`, `app/ui/map/VelaMapView.kt` |
| **Search, places, reviews, hours** | Per-user keyless scrape of `google.com` `pb` endpoints (browser-like session token); responses are positional arrays walked by calibrated index paths | `core/data/google/`, SPEC §3 |
| **Photo gallery** | Hidden **anonymous WebView** same-origin-fetches the gallery RPC (OkHttp gets a bot-degraded reply) | `app/web/WebPhotoFetcher.kt` |
| **Transit directions** | Hidden WebView reads Google's directions SPA (traffic-aware itineraries) | `app/web/WebDirectionsFetcher.kt`, `core/…/TransitParser` |
| **Departure boards** | **Transitous** (open GTFS + GTFS-Realtime, keyless) primary; Google place-page blob as fallback | `core/data/transit/Transitous.kt` |
| **Turn-by-turn routing** | **FOSSGIS OSRM** (open) - complete street-named steps incl. highway `ref`/exit/lanes; retried on blips | `core/data/RouteGeometry.kt` |
| **Traffic ETA + jam reroute** | Google's directions overlaid on the OSRM route; re-runs OSRM through Google's path only when they diverge (option 3) | `GoogleMapsDataSource.directions`/`applyTraffic` |
| **Offline routing** | On-device **GraphHopper** CH graphs, one per region, downloaded from a 135-region world catalog | `core/data/GraphHopperRouteEngine.kt`, `app/offline/RoutingGraphStore.kt`, `tools/routing-regions.json` |
| **Offline address geocoding** | Typed street address → coordinate → GraphHopper route with no signal; keyless OSM `addr:housenumber` + named-road centrelines indexed on area download (house-precise, interpolated, or street-level fallback) | `core/data/OfflineAddressStore.kt`, `core/data/OverpassPois.kt` |
| **Offline place packs** | Downloading a state also pulls its whole-region place pack (CI-baked SQLite of every OSM POI/address/street), so offline search covers the entire state, Organic-Maps-style. Packs rebuild monthly from fresh OSM; installed ones update in place with small row-level deltas | `app/offline/PoiPackStore.kt`, `core/data/OfflinePacks.kt`, `scripts/build-poi-region.sh`, `scripts/poipack_delta.py`, `.github/workflows/poi-packs.yml` |
| **Open building overlay** | Microsoft building footprints (ODbL) as per-region PMTiles, rendered beneath OSM to fill areas OSM never mapped; world catalog of 51 US states + ~185 countries (US + Global ML sources) | `app/offline/OverlayTileStore.kt`, `app/ui/map/VelaMapView.kt`, `scripts/build-overlay-region.sh`, `tools/overlay-regions.json` |
| **Open house-number overlay** | OpenAddresses address points as per-state PMTiles, streamed + rendered as a house-number SymbolLayer where OSM lacks `addr:housenumber` | `app/ui/map/VelaMapView.kt`, `scripts/build-address-region.sh`, `tools/address-regions.json` |
| **Navigation (banner, voice, haptics)** | Pure `NavEngine` turn logic (unit-tested) → maneuver banner (lane diagram / shields), AOSP TTS, direction-coded vibration | `core/nav/`, `app/ui/nav/`, `core/voice/`, `core/feedback/` |
| **Android Auto (full nav)** | Navigation-category CarAppService with car-side search, route preview with live-traffic alternates, and active turn-by-turn (turn card, "then" step, lane diagram, cluster support). The map renders through MapLibre's MapSnapshotter with route map-matching and smoothed puck motion. Sideloaded nav apps need AA's "Unknown sources" developer toggle on to appear (media apps do not, which is the usual "why does that other sideload show up" confusion) - see [ANDROID-AUTO.md](ANDROID-AUTO.md) | `app/car/` (`VelaCarAppService.kt`, `CarMapRenderer.kt`, `screen/`) |
| **Location & heading** | AOSP `LocationManager` + raw rotation-vector sensor - never GMS/Fused | `core/location/` |
| **D-pad-only operation** | The whole UI works with a 5-key D-pad, no touchscreen (touch is a bonus): key-drivable map (arrows pan, OK-at-crosshair taps, hold-OK drops a pin, on-screen zoom buttons), focus rings, key alternatives for every gesture | [`dpad.md`](dpad.md), `app/ui/DpadFocus.kt`, `app/ui/map/MapDpadController.kt` |
| **Lists & Google Maps list import** | Local place lists (icon + colour, notes per place, export/import to a file); pasting a Google Maps share link previews the list's places with a one-tap Save | `core/data/PlaceListStore.kt`, `core/data/google/parse/EntityListParser.kt` |
| **Parking spot memory** | One tap on the P button saves where you parked (teal pin, walking directions back); long-press for the history so an accidental overwrite never loses the car | `core/data/ParkingStore.kt` |
| **Fix drift without an app update** | ECDSA-signed remote `calibration.json` (pb templates, field-index paths, JS transforms) + notices, verified against a pinned key | `core/config/CalibrationStore.kt`, SPEC §5 |
| **Distribution** | Code push to `main` → signed nightly prerelease (docs-only changes don't cut releases); weekly promote to stable (same APK); Obtainium tracks stable by default, nightlies via the prerelease toggle; a self-hosted F-Droid repo serves both channels ([FDROID.md](../FDROID.md)) | `.github/workflows/ci.yml` + `promote-stable.yml` + `fdroid-repo.yml` |


## Transit data: who provides what

Vela's transit stack deliberately mixes two sources, each doing the thing it is genuinely better at.
This split matters for debugging and for knowing what breaks when one side has an outage.

| Piece | Source | Why |
|---|---|---|
| **Departure board** on a stop | **Transitous first** (open GTFS + GTFS-Realtime via the community MOTIS server, keyless). Google's anonymous place page is the fallback. | The open feeds return **every** route at a stop with realtime lateness and the agency's own route colours, and a multi-bay transit center merges through its parent station. Google's anonymous page embeds as little as one route at big hubs. |
| **Transit directions** (picking a bus route A to B) | **Google** (hidden WebView reading the directions page). | Google's itinerary ETAs can reflect live road traffic on the bus's route; GTFS-Realtime only knows a bus is *currently* late, not that it *will be* slowed. Riders comparing route options benefit from the prediction, so Google keeps this job. A Transitous fallback for outages is planned. |
| **Stop icons on the map** | OSM basemap tiles today; moving to Transitous stop positions (canonical GTFS) with OSM as the offline floor. | OSM stop data is community-maintained and sometimes marks stops agencies do not serve; GTFS positions come from the agencies themselves. |
| **Route stop list** (tap a route on the board) | Google itinerary reuse today; moving to the GTFS trip's own stop sequence. | The trip data is exact - the actual run you tapped, every stop it makes - where the itinerary reuse guesses at a matching leg. |
| **Realtime lateness** | GTFS-Realtime through Transitous (the green live dot and adjusted times on boards). | Straight from the agency feed; no scraping involved. |

How the fallback behaves: when Transitous has no coverage for an agency, boards silently fall back to
Google's page and show whatever it embeds. When Google's scrape breaks (the standing risk of any
keyless path), boards keep working because they no longer depend on it. Directions currently have no
fallback beyond Google's own OSRM-assisted paths; that is the next reliability gap to close.

Offline: stop icons render from downloaded map tiles, and region place packs carry stop names for
offline search. Boards and directions are online by nature - schedules are realtime data.
