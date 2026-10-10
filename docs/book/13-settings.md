# 13. Settings and page motion

## What you see

Settings opens over the map as a list of twelve pages with a search field above it.

- Appearance: theme, font, interface size, map colors, units, clock, language, directions
  language, Page transitions.
- Map: traffic, transit lines, terrain, tilt, "Keep north up", 3D buildings, map buttons, house
  numbers, names on the map (English then romanized, English then local, or local only), where
  the map opens.
- Places: "Place icons on the map", which places the map draws, what a place page loads.
- Navigation: route picker, road-ahead bar, arrow, speed limit, zoom keys, cameras
  ([chapter 3](03-cameras.md)), traffic rechecks.
- Voice: spoken directions, street names, the voice library, speed and volume.
- Search: voice search, on-device speech engines, contact search.
- Saved places: export and import of places and lists, saved routes, parking history.
- Offline maps: area and region downloads, automatic updates, storage
  ([chapter 8](08-offline.md)).
- Privacy: "Use Vela without Google", every switch for what Google is used for in one list, the
  Google session, the request counter, Clear history
  ([chapter 7](07-talking-to-google.md)).
- Performance: what loads ahead of time, how the map is drawn.
- Diagnostics: diagnostics export, saved trips, the location and driving simulators.
- About: version, update channel and checks ([chapter 12](12-releases.md)), map data credits,
  support, the project on GitHub.

Typing in the search field replaces the list with up to ten matching rows. Tapping one opens
its page, scrolls to the row and tints it for a moment.

Back from a page returns to the list, then to the map. A back swipe previews the page
underneath until you release or cancel. Sheets on the map follow a back swipe down with the
finger. Settings > Appearance > Page transitions turns the motion off and leaves Back working.

On a keypad phone every page opens with its Back button focused, and returning to the list
focuses the row you came from.

## Where the data comes from

Nothing here uses the network. Most settings are stored in the `vela_settings` preference file,
which Android backup includes. The motion switch is `PageTransitions`, key `page_transitions`,
on by default. The search index is compiled into the app and matches labels in the app's
language.

## How it is decided

### Pages and Back

`SettingsScreen` hosts a Navigation Compose `NavHost` drawn over the map, and the host owns the
back gesture. The start destination is `MAP_ROUTE` (`main/map`), which is empty and
transparent. `MapScreen` stays composed under it, so the camera is where you left it. The list
is the hub, `settings/hub`, and each page is `settings/<section>` from the `SettingsSection`
enum. Navigation matches routes without regard to case, so the map and the Map page have
different paths.

Opening a page pops back to the hub first, so the stack is never deeper than map, hub, page.
Voice opened from Offline maps replaces Offline, and the map's no-voice notice pushes the hub
before Voice. Back from Voice reaches the hub either way.

### Search

`SEARCH_INDEX` in `ui/settings/SettingsHub.kt` maps each row label to its page, and a new row
is added to it by hand. A query is a case-insensitive substring match on those labels. The
chosen label travels with the destination entry, and the row built with that label
(`Modifier.settingsAnchor`) scrolls into view.

### Where the map opens

Settings > Map > "Where the map opens" has four choices: "Where I am" (the default), "Where I
left the map", "Home" and "A place I choose". Home is listed only while a Home is saved. "Use
the current map view" saves the middle of the map and its zoom, as the map sits under Settings,
and picks "A place I choose".

"Where I am" opens on the last known position and follows the phone. With location off or
denied it opens where the map was left, and moves to the first fix if one arrives. The other
three open on their view and stay there until the locate button is tapped or a drive starts. A
link from another app, a shared place and a pinned trip open on their own place whatever the
choice.

The view the map was left on is saved when Vela leaves the screen. It is kept with the last
known position, outside Android backup. The choice and the picked view are in `vela_settings`.
[SPEC 4.7](../../SPEC.md) has the rules.

### Motion

A Settings page slides in over 250 ms while the page under it moves a quarter of the width the
other way. Back reverses it, and a right-to-left language mirrors it.

Map sheets go through `SheetTransition`, 250 ms up and 200 ms down. It keeps a snapshot of the
outgoing sheet until the slide ends, because the state that sheet showed is already cleared.
Each kind of sheet has one stable key (`BottomOverlay`), so place details that arrive late do
not replay the entrance. A canceled back swipe returns the sheet and changes nothing.

The step list keeps its own animation, shared with the navigation bar. When browsing and
navigation swap, the top controls fade and slide (`NavigationChromeTransition`). The map and
the guidance session stay live.

`PageTransitions` switches all of this off. It does not affect dragging a sheet by hand, the map
camera or the photo viewer. [SPEC 10.4](../../SPEC.md) has every duration and exception.

### Keypad focus

Every page is built on `SettingsScaffold`. On a phone with no touchscreen, or with a physical
D-pad, it requests focus on Back every 50 ms while nothing is focused and no key has been
pressed, because the window can take focus away just after the page opens.

Down from Back goes to the page's first control and Up from it returns to Back. Both are wired
by hand, because Compose's own directional search clears focus at the edge of the top bar. Left
and Right with no target are swallowed for the same reason.

A page asks for focus only once its entry is RESUMED (`LocalSettingsPageActive`), so a page
sliding in does not take focus from the one being left. [docs/dpad.md](../dpad.md) covers the
rest of keypad operation.

## Limits

- A saved map view keeps its center and zoom. A map left turned or tilted opens north-up and
  flat.
- The search finds only the row labels listed in `SEARCH_INDEX`, which is kept by hand. A row
  inside a collapsed section, such as Voice speed, is not listed.
- Android 13 and 14 show the back preview only with the system's predictive back developer
  option on.
- When Settings is the first thing in a session to take focus, focus requests do nothing until
  a key is pressed. That first press lands on Back.
- Open search, picking an area or a point on the map, the alternates list, a minimized route
  picker, and the place sheet and step list during a drive handle Back themselves and do not
  follow the swipe. Back during a drive asks before ending it.
