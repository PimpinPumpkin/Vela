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

## Not written yet

- The map itself: label sizes, day and night styles, 3D buildings, frame rate.
- The route chooser: the two pickers, alternates, the camera and toll badges.
- Voices and listening: Vela voices, speech engines, voice commands.
- Keypad phones and the D-pad. For now, see [docs/dpad.md](../dpad.md).
- Trips, diagnostics and sharing: what a trip holds and what Share trims.

A behavior change updates its chapter in the same commit, or adds a line here if it has none.
