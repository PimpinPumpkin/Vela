# D-pad-only operation (no touchscreen required)

Vela works with five keys (up, down, left, right, OK) plus Back, and no touchscreen. That covers
keypad phones, Android TV boxes, and any phone with a remote, a game controller or a keyboard
attached. Touch behavior is unchanged by any of it.

SPEC 10.3 has the short version of this page.

## Rules

1. Every screen, sheet and dialog opens with something focused. Attach `rememberDpadAutoFocus()`
   to its primary control, or `Modifier.dpadAutoFocus(requester)` when the target may be off
   screen. Compose's own focus recovery is nondeterministic, and an unfocused screen wastes the
   first key press. The bare map is the one exception (see Traps).
2. Every control is focusable and shows a ring. Put `Modifier.dpadHighlight(shape)` on every
   `clickable`, `toggleable` and `selectable`. Material's own focus tint is too faint to see on
   the sheets.
3. Every gesture has a key path. A drag, swipe, pinch or long press needs a focusable control or
   a key handler that does the same thing, or a keypad user cannot reach it.
4. D-pad code calls the touch paths. Key handlers call the lambdas, flags and state the gesture
   handlers use (`handleTap`, `gestureMove`, `navUserZoom`), so a fix to touch applies to keys.
5. D-pad affordances are gated on `rememberDpadMode()` or `rememberDpadFirstDevice()`, so a
   touch phone with no key input looks and behaves as before.
6. Menus and dialogs are `VelaMenu` and `VelaDialog`. A Compose `DropdownMenu` or `AlertDialog`
   cannot be focused before the first key press. Any other window is a raw `Dialog` that focuses
   an explicit `.focusable()` element.

`dpad_test_suite/audit_static.sh` checks rules 2, 3 and 6, and CI fails a build that breaks one.

## How it is built

### Detection

`rememberDpadFirstDevice()` (`detectDpadFirst`) is true only when the device has no touchscreen
(`FEATURE_TOUCHSCREEN` absent) or a physical, non-virtual `InputDevice` reports `SOURCE_DPAD`. On
such a device focus is placed before any input and rings show from the first frame.

`rememberDpadMode()` is `dpadFirst || inputMode == InputMode.Keyboard`. On any other device it
turns on at the first key press and off at the next tap.

Some keypad phones report a touchscreen and expose their D-pad only through the framework's
virtual device, so nothing at startup separates them from an ordinary phone. They get rings, the
map target and the key paths on the first key press, but no pre-placed focus and the stock
`DropdownMenu`: the auto-focus helpers and the `VelaMenu` chooser act on D-pad-first devices only.

### Helpers

All in `app/src/main/java/app/vela/ui/DpadFocus.kt`.

| Helper | Use |
| --- | --- |
| `rememberDpadAutoFocus(vararg keys)` | Returns a `FocusRequester` for a screen's primary control and requests focus when the screen appears. Retries 20 times, 50 ms apart, because a new focus node is often not attached on the first frame. `keys` request again when the content swaps |
| `Modifier.dpadAutoFocus(requester)` | Requests every 50 ms for up to 2 s until `onFocusEvent` confirms focus landed. The no-argument form makes its own requester |
| `Modifier.dpadHighlight(shape, ringColor)` | A 3 dp orange ring (`0xFFFF6D00`) while the element or a descendant has focus and input is key-driven. Orange because a teal ring vanished on a switch that is on. `ringColor` is for a control whose own fill would hide the orange |
| `Modifier.dpadClickable` | `clickable` without Material's gray focus fill while input is key-driven. A plain `clickable` shows the fill and the ring together. Pair it with `dpadHighlight` |
| `DpadRingBox(shape)` | Rings a Material button or chip at its visible height. `dpadHighlight` on the control itself rings its padded 48 dp box |
| `VelaSwitch` | A `Switch` whose ring hugs the track |
| `Modifier.dpadFieldEscape()` | Up and down leave a text field for the control above or below |
| `Modifier.dpadSwallowHorizontal()`, `Modifier.dpadContainVertical()` | Consume a left or right press that no child handled, and stop up and down from leaving a scroll container |
| `Modifier.dpadRowSibling(requesters, index)` | Left and right across a row of siblings by `requestFocus` |
| `rememberDpadFocusKeeper()`, `Modifier.dpadFocusKept`, `DpadFocusHandoff` | Keep focus on a row whose control replaces itself (Download, then progress, then Delete) |

### The map

`app/src/main/java/app/vela/ui/map/MapDpadController.kt` is the key-to-camera seam: `panBy`,
`zoomBy`, `selectAtCenter` and `longPressAtCenter`. `VelaMapView` wires its callbacks to the tap
lambda, the long-press path, the `gestureMove` flag and the navigation zoom override, so a key
pan detaches the follow camera and arms "Search this area" as a finger pan does.

`MapScreen` puts a 140 dp focusable target at the center of the screen. Focused, it shows a pill
reading "OK: move the map" and the arrows keep moving focus. OK engages it, which draws a
crosshair and a ring at the screen edge:

| Key | While engaged |
| --- | --- |
| Arrows | Pan by 22% of the view, repeating while held |
| OK | Tap whatever is under the crosshair. In Choose on map, confirm the pick |
| OK held 500 ms | Long press at the crosshair: drop a pin, or set the Choose on map point |
| Plus, minus, zoom keys | Zoom one level |
| Back | Disengage. Focus stays on the target |

The target is mounted only while the map is the primary surface (`SearchGates.mapTargetHidden`).
It unmounts under search, a place sheet, directions, the step list, the arrival card or an open
results sheet, and stays during a drive and over a minimized results bar. Choose on map keeps it
mounted and, on a D-pad-first device, focuses and engages it at once. `ChooseOnMapOverlay` draws
the pin and banner there in place of the pill and crosshair.

Settings > Navigation > "Zoom in key" and "Zoom out key" give zoom to two keys of the user's
choice, such as 2 and 5 on a keypad (`ui/ZoomKeys.kt`). They zoom one level from anywhere on
the map, engaged or not, and rest while a Settings page is open. `MainActivity.dispatchKeyEvent`
offers a key that types only after nothing else took it, so the same key still types in a text
field, and offers a key that does not type (volume, camera) first. The arrows, OK and Back
cannot be assigned.

A plus and minus pill sits in the bottom-right stack above the parking button, in D-pad mode or
with Settings > Navigation > "Prefer buttons over swipes" on. It shows only on the bare map and
in the area picker (`AreaPickOverlay`).

### Key paths for gestures

| Gesture | Key path |
| --- | --- |
| Sheet drag (place, results, classic directions) | OK on the handle steps or toggles the sheet |
| Maneuver banner swipe | Left and right on the focused banner step through upcoming turns. OK returns to live guidance |
| Drag the navigation bar up for the step list | OK on the handle above the figures. Also a list button when "Prefer buttons over swipes" is on or the device is D-pad-first |
| Long press to mute (`NavHoldControls`) | The first OK slides the speaker choice out beside the button (it steps on, alerts only, off). A second OK on the button pauses |
| Photo viewer swipe | Left and right |
| Long press on a suggestion row | The trailing overflow button opens the same menu |
| Drag to reorder stops (`StopsEditorSheet`) | Up and down buttons on each row in D-pad mode |
| Street View drag | OK engages look-around: arrows turn the view, plus and minus zoom, OK moves to the nearest link ahead |
| Long press on the parking button (`ParkingControl`) | OK on the button. With no spot saved and a history to open, it asks: Save parking spot, or Parking history. With a spot saved, the menu has Earlier spots |

### Settings

Every Settings page, hub and spokes, renders through `SettingsScaffold`
(`ui/settings/SettingsScaffold.kt`). Build a new page on it. It does three things:

- Focuses the Back button on open, and requests again every 50 ms while nothing on the page has
  focus and no key has been pressed, because focus can be taken away after it first lands. The
  hub passes `autoFocusBack = false` when returning from a spoke and focuses that spoke's row.
- Bridges the top bar and the content. Directional search cannot cross from a `TopAppBar` to the
  column and clears focus, so down from Back and up from the first row go by `requestFocus`. The
  content lambda receives a `topRow` modifier. Attach it to the page's first focusable control
  or the bridges dead-end.
- Applies `dpadSwallowHorizontal` to the column and the Back button, and `dpadContainVertical`
  to the column.

The rows are in `SettingsComponents.kt`: `SettingsGroup`, `ToggleRow`, `SelectableRow`,
`GroupDivider`, `PageIntro`, `Hint`, `CollapsibleSectionTitle`. Each interactive row is one focus
stop. SPEC 10.4 covers the hub, the routes and the search.

### Small screens

`AdaptiveDensity.wrap` runs first in both `attachBaseContext` calls (`MainActivity`, `VelaApp`).
On a screen narrower than `MIN_WIDTH_DP` (360 dp) it lowers the density until the screen reports
360 dp of width, so standard Material layouts fit a 240 px keypad phone. At 360 dp and above it
returns the context untouched.

## Traps

Detection:

- Do not count the framework's virtual input device or
  `KeyCharacterMap.deviceHasKey(DPAD_CENTER)`. Both say D-pad on nearly every phone. Counting the
  virtual device (`KEYBOARD | DPAD`) put ordinary phones in keypad mode: a tap no longer opened
  the search field and the zoom buttons appeared.

Focus:

- Before anything in the window has held focus, `requestFocus` does nothing, and neither
  `moveFocus` nor a synthetic key event can put focus on the search bar. So after a touch the
  bare map opens with nothing focused, and the first arrow lands on the search bar, the first
  focusable. When the last input was a key, which is always so on a keypad phone, Android
  focuses the search bar itself as the window opens.
- `rememberDpadAutoFocus` stops as soon as `requestFocus` does not throw, which can be before
  focus lands. For a target below the fold use `dpadAutoFocus(requester)`.
- When the focused control leaves composition, Compose clears focus and recovers somewhere
  arbitrary, often the top of the screen. Request focus on the replacement (`DpadFocusKeeper`).
- In a `Column(verticalScroll)` a directional move with no target clears focus, and no arrow
  brings it back. `focusGroup()` alone does not help. Use `dpadSwallowHorizontal` and
  `dpadContainVertical`, and `dpadRowSibling` along a row: `moveFocus` clears focus at the ends,
  `requestFocus` does not.
- A clickable row with a focusable `RadioButton` or `Switch` inside is two focus stops, and a
  sideways move into the inner one clears focus. Make the inner control display-only
  (`onClick = null`, as in `SelectableRow`) or make it the only stop (`ToggleRow`, `VelaSwitch`).
- A text field consumes up and down as cursor moves, so nothing below it is reachable. Add
  `dpadFieldEscape()`. The search bar handles down inline, beside its Back handling.
- A focusable that consumes arrows must be a mode entered with OK and left with Back. The first
  map target panned whenever it held focus, and nothing else on the screen could be reached.
- On a small screen a fixed layout pushes the primary button off the bottom. Welcome scrolls,
  and on a D-pad-first device opens scrolled to the bottom so Get started can take focus.
  `VelaDialog` (90% of screen height), `VelaMenu` (85%) and the classic directions panel's body
  (58%) cap their height and scroll.

Search bar (`SearchBar.kt`, `MapScreen`):

- Focusing the text field opens the search overlay, so walking focus across the bar opened
  search. In D-pad mode the field is disabled and cannot take focus until armed. OK on the text
  region arms and focuses it, and losing focus disarms it. A field that was only unfocusable
  swallowed taps on phones with both a touchscreen and a keypad.
- A focused field counts as armed, however it got its focus. A tap focuses it with no arming,
  and the first key typed on a physical keyboard turns D-pad mode on: unarmed, the field was
  disabled under the cursor and everything after the first character was lost.
- The arming `clickable` is on the text region. On the whole card it made the bar one focus stop
  and the Settings gear unreachable.
- The overlay is open while `searchExpanded` is set or the field has focus (`SearchGates`). A
  state derived from focus inside the overlay never cleared, and Back could not close search.
- Back on the field is caught in `onPreviewKeyEvent` on key-down, before `BasicTextField` clears
  focus on it. With the soft keyboard up, the first Back hides the keyboard and the second closes.
- The soft keyboard follows the live input mode at arming: hidden after a key press, shown after
  a tap. Hardware keys type into the focused field, and a shown keyboard swallows Back.
- The text region insets its content 10 dp inside the ring. Without the inset the ring crosses
  the first typed letter.

Map:

- MapLibre's `MapView` takes focus and handles the arrow and center keys itself, so no key
  reaches Compose. `VelaMapView` sets it and its descendants non-focusable on every update,
  because MapLibre turns focus back on when the surface is recreated.
- The map target and the zoom buttons must not sit over a panel. As focus stops over the results
  list they took the down press from the header, and the first result was unreachable.
- Choose on map opens with directions still open underneath, so `mapTargetHidden` starts with
  `pickOnMap == null`. Without it the arrows only reach the cancel button and the pin cannot move.

Menus, dialogs and web pages:

- A `DropdownMenu` popup or an `AlertDialog` opens with window focus and no focused content until
  the first key event. `requestFocus` on an item, `moveFocus` and synthetic key events all fail.
  Only a raw `Dialog` holding an explicit `.focusable()` element takes focus on open.
- Inside a dialog window `requestFocus` does not land on a Material button or on the focus node
  nested in `.clickable`. `VelaDialog` buttons and `VelaMenu` items are `.focusable()` with OK in
  `.onKeyEvent` and touch in `pointerInput`. Adding `.clickable` would add a second focus stop.
  The Welcome button is built the same way.
- A WebView's own key handling hops between links. The full-screen reviews page maps up and down
  to page scrolling and requests focus when the page finishes loading.

adb:

- `adb shell input text` and `input keyevent` flip the input mode to Keyboard, as a physical
  keyboard does: `rememberDpadMode` turns on and the focus rings appear. Typing into a field
  that already has focus keeps working.

## Surfaces

"Focused on open" applies on a D-pad-first device.

| Surface | Focused on open | Leave |
| --- | --- | --- |
| Bare map | The search bar when the last input was a key. After a touch, nothing, and the first arrow lands on the search bar | Back disengages an engaged map |
| Search overlay | The armed field. Down reaches the rows | Back |
| Results sheet | The search bar keeps focus. Down reaches the filter chips and rows | Back clears the search |
| Place sheet | The drag handle | Back |
| Route chooser (`GoogleStyleDirectionsPanel`, the default) and the classic panel (`DirectionsPanel`) | The Drive tab | Back |
| Choose on map | The map target, engaged | OK confirms, Back cancels |
| Step list | The first step, or the current step during a drive | Back |
| Transit route detail (`RouteDetailSheet`) | The back arrow | Back |
| Navigation | The map stays primary. The banner, bar handle and buttons are focus stops | Back asks to end the drive |
| Search along the route (`NavSearchChips`) | The close button | Back |
| Photo viewer | The viewer | Back |
| Full-screen reviews | The back arrow, then the page once it loads | Back |
| Street View | The panorama | Back disengages, then leaves full screen, then closes |
| Settings hub | The Back button, or the row of the page just left | Back, to the map |
| Settings page | The Back button | Back, to the hub |
| Welcome, then the Google choice page | Get started, then Continue | OK. Back on the choice page returns to Welcome |
| The first run's "What Google is used for" list | The Back button, as on a Settings page | Back, to the choice page |
| `VelaDialog` | The dismiss button. Arrows reach confirm | Back |
| `VelaMenu` | The first item | Back |
| Time and date picker (`PickerDialog`), Your lists (`ListsSheet`), voice capture (`VoiceCaptureDialog`) | Their OK, New list and Done buttons | Back |
| List editor (`ListEditorDialog`) | The name field. Down reaches the icons, the colors and the buttons | Back |
| Place icon (`PlaceIconDialog`), parking history (`ParkingHistorySheet`) | Cancel, and the newest spot | Back |

### Known limitations

- Photo pinch zoom and map tilt have no key path.
- With key input the soft keyboard stays hidden, so typing needs hardware keys.
- The key scrolling of the full-screen reviews page has not been checked against a loaded page.

## Testing

The suite is in `dpad_test_suite/`. Run all three scripts after any change that touches focus.
`audit_static.sh` needs no device. The other two press keys on a connected device with
`adb shell input keyevent` and read the focused element from `uiautomator dump`.

| Script | Checks |
| --- | --- |
| `audit_static.sh` | Scans every `.kt` under `:app`. Fails on a `clickable`, `toggleable` or `selectable` with no `dpadHighlight`, a gesture modifier with no key path nearby, a bare `DropdownMenu` or `AlertDialog`, and `isSystemInDarkTheme()`. A gesture whose key path is in another composable passes with a `// dpad-ok: <where>` comment above it. Lists bare `.focusable()`, sliders, raw dialogs, unescaped text fields and those comments for a manual look. CI runs it |
| `run_all.sh` | Runs `setup.sh`, then the tests in `tests/` in order. Arguments select tests by prefix: `./run_all.sh 01 02` |
| `audit_dynamic.sh` | Tours the bare map, search, Settings, the place sheet, directions and Choose on map. Each must open focused (the bare map unfocused), keep focus through a run of down presses, and close on Back |

The tests assert, in order: the bare map opens with the map target unfocused and the search bar
at most one press away (01); Settings opens on Back (02); Welcome opens on Get started and the first
onboarding dialog on "Not now" (03); the place sheet opens on its handle and its overflow menu on
the first item (04); Choose on map opens engaged (05); the Directions pill and the From row are
reachable (06).

- The device must be D-pad-first. On a touch phone run
  `adb shell settings put global vela_force_dpad 1` before launch, and put it back to 0 afterward.
- Tests 04 to 06 and most of the dynamic audit need live search results and skip without them.
- Tests 01 and 02 measure positions as a share of the screen and find the Settings button by
  its description, so they pass on any screen. Tests 04 to 06 and the dynamic audit still count
  key presses for a small keypad screen. On a tall phone, where the results sheet has more
  filter chips, they land on the wrong control and fail.
- Test 03 runs `pm clear` on the package. On a phone whose data matters, install a side-by-side
  build and set `VELA_PKG=app.vela.dev`.
- `setup.sh` grants location and adds a `gps` test provider (`VELA_LAT` and `VELA_LNG` set the
  fix). Remove the provider when done.
- `ADB="adb -s <serial>"` picks a device.

## Fork and merge policy

The Settings hub and `SettingsScaffold`, `AdaptiveDensity`, the orange ring and Street View by
D-pad were ported from the vela-dpad fork by alltechdev and ars18. Work from the fork lands by
cherry-pick with the author kept, after it is read and tested like any other pull request. To
keep the two trees easy to exchange, D-pad behavior goes in its own files where it can, and an
edit to a shared file is a small insertion with a comment naming `docs/dpad.md`.
