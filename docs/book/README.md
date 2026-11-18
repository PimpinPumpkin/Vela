# The Vela book

The book explains how Vela works, one chapter per subsystem, for curious users and new
contributors. [SPEC.md](../../SPEC.md) is the reference, with every rule and constant. The book
carries only the numbers needed to follow the behavior. Where the two disagree, check the code.

Every chapter has the same four parts:

1. What you see: the behavior, in a user's words.
2. Where the data comes from: the source, its license and its host.
3. How it is decided: the rule and its constants.
4. Limits: what it gets wrong and what a fix would take.

## Chapters

| # | Chapter | Covers |
| --- | --- | --- |
| 1 | [Places on the map](01-places.md) | Where the pins come from and which are drawn |
| 2 | [Data and rebakes](02-data-and-rebakes.md) | The hosted datasets and when each is rebuilt |
| 3 | [Surveillance cameras](03-cameras.md) | The camera data, the avoid rule, the warnings |
| 4 | [Navigation](04-navigation.md) | The per-fix loop, rerouting, traffic rechecks |
| 5 | [Routing](05-routing.md) | Whose route you drive (Google's line for driving) and where the turn instructions come from (open routers) |
| 6 | [Search](06-search.md) | Suggestions, search on Enter, offline search |
| 7 | [Talking to Google](07-talking-to-google.md) | Keyless requests, the browser identity, sessions |
| 8 | [Offline](08-offline.md) | What a download holds and what works with no signal |
| 9 | [Transit](09-transit.md) | Departure boards, stops, transit directions |
| 10 | [Android Auto and the car screen](10-android-auto.md) | The car screens, the snapshot map, the install gate |
| 11 | [The drive's chrome](11-drive-chrome.md) | The turn banner, street callouts, the road-ahead bar |
| 12 | [Releases](12-releases.md) | Channels, version codes, the in-app updater |
| 13 | [Settings and page motion](13-settings.md) | The Settings pages, Back, page transitions, keypad focus |

## The engines

The names the chapters use for Vela's main parts, and where each is explained.

| Name | What it does | Chapter |
| --- | --- | --- |
| `HybridRoute` | A drive on Google's line: the open router's turns where the two routes share the road, turns named from the map where they part | [5](05-routing.md) |
| OSRM | The open router, online on the FOSSGIS server: every turn with names, exits and lanes, no traffic | [5](05-routing.md) |
| `ObfRouteEngine` | OsmAnd's router on the phone, over the downloaded region files | [5](05-routing.md), [8](08-offline.md) |
| Valhalla | Bike-safe routes, and fitting a line another router drew to the roads | [5](05-routing.md) |
| `LineNamer`, `RoadNameTiles` | Name each stretch of Google's line from the map's vector tiles | [5](05-routing.md) |
| `NavEngine` | Runs on each fix: where you are on the route, what to say, whether you left it | [4](04-navigation.md) |
| `NavSession` | The one drive the phone and the car screen share: reroutes, rechecks, stops | [4](04-navigation.md) |
| `RouteHeal` | When a recheck's route replaces yours, and the way back to Google's line after a reroute | [4](04-navigation.md) |
| `PositionFilter` | The fused position guidance steers by: the GPS fix and its Doppler speed and course | [4](04-navigation.md) |
| `GuidanceFix` | Shares that filter between the phone's feed and the car screen's | [4](04-navigation.md), [10](10-android-auto.md) |
| `GoogleMapsDataSource` | Every request to Google: search, place details, routes, traffic | [7](07-talking-to-google.md) |
| Calibration | The signed remote settings file: request templates, word tables, dials | [7](07-talking-to-google.md) |
| `OfflinePoiStore`, `OfflineAddressStore` | Places and addresses from the downloaded packs | [6](06-search.md), [8](08-offline.md) |
| Transitous | Departure boards and stops | [9](09-transit.md) |
| Piper | The Vela voices, run on the phone by sherpa-onnx | [2](02-data-and-rebakes.md) |
| `StepAudit` | Checks that a route's steps match its line: each turn where the line bends that way, no bend left unsaid | [5](05-routing.md) |

## Not written yet

- The map itself: label sizes, day and night styles, 3D buildings, frame rate, the layers panel
  (its tiles are in SPEC 10.2, Browse map).
- The route chooser: the two pickers, alternates, the camera and toll badges.
- Voices and listening: Vela voices, speech engines, voice commands.
- Keypad phones and the D-pad. For now, see [docs/dpad.md](../dpad.md).
- Trips, diagnostics and sharing: what a trip holds and what Share trims.

A behavior change updates its chapter in the same commit, or adds a line here if it has none.
