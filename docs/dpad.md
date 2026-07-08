# D-pad-only operation (no touchscreen required)

Vela is fully drivable with a **5-key D-pad** (↑ ↓ ← → + OK) plus BACK. Touch is a
bonus, not a requirement. This document is the authoritative record of the design, the
findings that shaped it, every change made, and the rules for keeping the work easy to
merge with upstream.

## Why this is mostly free in Compose — and where it isn't

**Finding 1: Compose gives D-pad focus traversal for free on anything `clickable`.**
Every `Modifier.clickable`, Material button, chip, switch, checkbox, dialog button and
dropdown item is already a focus target: arrows move focus spatially, OK activates.
Roughly 90% of Vela's UI (search rows, place-sheet actions, settings, dialogs, route
picker, nav controls) needed **no operability work** — only focus *visibility* work.

**Finding 2: five things were genuinely touch-only.** These were the real gaps:

1. **The map itself** — pan/zoom/tap/long-press are all gestures on the MapLibre view.
2. **The search overlay's focus semantics** — two traps (below).
3. **Sheet handles** — the place sheet's expand/collapse handle was a `detectTapGestures`
   + drag detector (no focus target, no key activation). The results list and the
   directions panel already had button/`clickable` alternatives.
4. **The nav maneuver banner** — stepping through upcoming turns was swipe-only.
5. **The full-screen photo viewer** — paging was swipe-only.

**Finding 3: focus visibility is the other half.** Material's default focus indication
(a faint ripple state layer) is too subtle on Vela's fixed-grey sheets. Without an
obvious ring you can't tell where you are, which makes the app *technically* operable
but practically unusable.

## Design

### Core helpers — `app/ui/DpadFocus.kt` (new file)

- `rememberDpadFirstDevice()` — the D-pad is a PRIMARY input, so default to a D-pad-first
  UI (affordances shown persistently, initial focus/engage placed for the user). Detection
  is **multi-signal** because feature phones lie:
  - **`FEATURE_TOUCHSCREEN` is useless** — the tested MTK keypad phone reports
    `touchscreen=finger` (a tiny `mtk-tpd` panel), so a no-touch check alone is FALSE.
  - **`KeyCharacterMap.deviceHasKey(DPAD_CENTER)` returned FALSE** on that phone — don't
    rely on it.
  - **The winning signal: an `InputDevice` whose sources include `SOURCE_DPAD`** — BUT on
    the real phone that flag lives ONLY on the framework's **Virtual** aggregate device
    (id −1, `src=0x301`); the physical `mtk-kpd` reports plain `0x101` (KEYBOARD). So the
    enumeration must **include virtual devices** (an earlier `!isVirtual` filter is exactly
    what made detection fail and showed "literally nothing with the D-pad" — proven by
    on-device logging, see §Proven).
- `rememberDpadMode()` — `dpadFirst || inputMode == Keyboard`. Always true on a D-pad-first
  device; on a touch phone with an occasional keyboard it flips reactively (affordances
  appear on the first key press, melt away on the next tap).
- `Modifier.dpadHighlight(shape)` — a 2 dp primary-colour focus ring, drawn only while the
  element (or a descendant — Material buttons host their own focus node, and
  `onFocusEvent.hasFocus` covers both) holds focus **and** the UI is key-driven (honours
  `dpadFirst` directly, since a D-pad-first phone may still read `inputMode == Touch` until
  the first key event). Never appears under touch.
- `Modifier.dpadFieldEscape()` — makes a text field **escapable** by D-pad: UP/DOWN move
  focus to the previous/next form control instead of being swallowed by the field's own
  cursor handling. A single- or multi-line `TextField`/`BasicTextField` otherwise eats the
  vertical arrows, **trapping focus on the field so nothing below it is reachable** (measured
  on-device: the Settings "Try the voice" / variant-jump / routing-filter / voice-search
  fields, and the reviews search field — DOWN sat blinking in the field, the controls under
  it never got focus). Fires via `onPreviewKeyEvent` (root→leaf) so it wins before the field
  consumes the key, and swallows the matching key-up so the field never sees a half event.
  Returns false at a list edge where focus can't move, so the field still behaves normally
  there. Inert under touch (gated on `rememberDpadMode`). Apply to any text field sitting in
  a vertical list of focusable controls. (The search bar's field solves the same trap inline —
  see Trap C below — because it also needs the BACK/close handling in the same key handler.)

### The MapLibre `MapView` steals D-pad keys — the load-bearing fix

**Finding 0 (the reason "nothing happened"):** MapLibre's `MapView` calls `requestFocus()`
on itself and overrides `onKeyDown` to handle hardware keys (`DPAD_CENTER` = zoom in,
arrows = scroll). On a keypad phone it therefore grabs focus and **swallows every D-pad key
before Compose focus ever sees it** — arrows did nothing visible, OK zoomed the map, and no
Compose focus ring ever appeared (proven on-device: OK zoomed 2000→1000 mi). Fix
(`VelaMapView.kt`): make the `MapView` and its descendants **non-focusable**
(`isFocusable = false`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`), unconditionally
and re-asserted in the `AndroidView` update block (MapLibre re-enables it on surface
recreation). Touch gestures don't need view focus, so nothing is lost; keys now flow to the
Compose focus system and `MapDpadController` drives the map instead.

### The map — `MapDpadController` (new file) + a focusable centre target

`app/ui/map/MapDpadController.kt` is the key→camera seam. `VelaMapView` wires it up in
`getMapAsync` alongside the touch listeners; MapScreen owns the key handling. The
controller deliberately **reuses the exact same code paths as touch**:

- `selectAtCenter()` calls the *same* tap-resolution lambda the click listener uses
  (search-result pins → ambient POI dots → alternate-route lines → basemap POIs →
  unnamed-POI reverse geocode). The touch listener body was only *named* (`handleTap`)
  — not moved or reindented — so upstream edits to it merge cleanly.
- `longPressAtCenter()` → the same `onMapLongPress` path (drop pin / set a
  Choose-on-map point).
- `panBy()` sets the same `gestureMove` flag a drag sets (so "Search this area" and
  camera-idle viewport updates fire) and detaches the nav follow-camera exactly like a
  finger pan.
- `zoomBy()` during nav adopts the manual nav-zoom override exactly like a pinch
  (`navUserZoom`); in browse it counts as a user gesture.

**MapScreen's map target**: a 140 dp focusable box at the screen centre, with a
**two-stage** model:

- **Focused** (pill: "OK: move the map"): a *normal* focus stop. Arrows keep
  traversing the chrome — search bar, chips, zoom buttons, FABs, sheets all stay
  reachable. OK **engages** map control.
- **Engaged** (crosshair + screen-edge ring):

| Key | Action |
|---|---|
| ↑ ↓ ← → | pan by 22% of the view (auto-repeats when held) |
| OK (short) | "tap" whatever is under the crosshair; in Choose-on-map mode, confirm the pick |
| OK (held ≥ 500 ms) | long-press at the crosshair (drop pin / set pick directly) |
| +/− or zoom keys | zoom (bonus for devices that have them) |
| BACK | **disengage** — focus stays on the target, arrows traverse the chrome again |

> **History (v1 trap, fixed same day):** v1 had a single stage — the map consumed all
> four arrows whenever it merely *held focus*, and BACK cleared focus to nowhere, after
> which Compose's focus restoration put the next key press right back on the map. Result:
> "no way to grab the search bar or switch focus to anything but the map". The two-stage
> model is the fix: panning is an explicit OK-entered mode, and leaving it never clears
> focus. Don't regress this — a focusable that consumes arrows must always be a mode you
> *enter*, not a place focus merely lands.

Long-press detection uses the native key event's `repeatCount`/`eventTime − downTime`
(≥ 500 ms while held), so the pin drops *while holding*, like touch. On a D-pad-first
device the app **starts focused + engaged**, so the first key press already pans the
map; one BACK hands the arrows to the chrome.

**Panel-aware (proven fix):** the map target is shown/focusable ONLY when the map is the
primary surface. With a list/sheet/panel/search open (`mapTargetHidden`), it unmounts so
the panel owns focus — a centre crosshair + focus stop floating over the results list stole
DOWN traversal into the rows (measured: DOWN from the results header jumped to the zoom `+`
button, never reaching a result). Returning to the bare map re-acquires + re-engages it via
`LaunchedEffect(dpadFirst, mapTargetHidden)` (retries because the focus node may not be
attached on the first frame). Nav keeps the map primary (the banner is an overlay), so you
can still pan to look around during nav.

**Choose-on-map is the one exception (sweep fix, 2026-07-07).** "Choose on map" (setting a
route origin/stop by placing a pin) opens with `directionsOpen` still true underneath, which
`mapTargetHidden` would normally treat as a panel and unmount the map target — but this mode
*requires* the map be pannable so the user can position the pin. Without the exception the
arrows only moved focus to the cancel **✕** and the pin couldn't be moved (measured). So
`mapTargetHidden` now has a leading `state.pickOnMap == null &&` guard: while a pick is in
progress the map target stays mounted **and auto-engaged**, so arrows pan immediately and OK
confirms the pick (hold-OK sets it directly). Two matching cosmetic tweaks: the "OK: move the
map" focused pill is **suppressed** during a pick (`mapFocused && state.pickOnMap == null`) —
there the arrows already pan and OK *confirms* rather than *enters* map control, so that pill
would be a lie — and the crosshair/pin + "Move the map to set…" banner come from the existing
`ChooseOnMapOverlay` instead.

**Zoom buttons**: pinch has no 5-key equivalent, so a D-pad-mode `+`/`−` pair sits mid-right.
Shown **only while browsing the bare map** (not during search / results / place sheet /
directions / nav) — mid-right they sit in the vertical focus path of those panels and
intercepted DOWN into their rows (measured). Behind a panel the map is covered anyway; zoom
it via the engaged crosshair after closing the panel.

### The search overlay — the "can't get out of search" trap (MapScreen + SearchBar)

**Trap A: focusing the field opened the overlay.** `searchOpen` keys off field focus, so
merely *walking* focus across the search bar flipped the whole screen into the search page.
Fix (`SearchBar.kt`): in `dpadMode` the field is unfocusable (`focusProperties { canFocus }`)
until **armed** — OK on the search **text region** arms + focuses the field. Un-focusing
disarms. Touch phones: `dpadMode` false ⇒ byte-identical.

> The arm `clickable` goes on the text region, NOT the whole Card. A first version put it on
> the Card, which made the entire bar one focus stop and **swallowed the Settings gear inside
> it — the gear became unreachable by D-pad** (measured: RIGHT stayed on the bar, never
> reaching the gear). With it on the text region, the gear / clear / back IconButtons stay
> independently focusable (Settings proven reachable + navigable on-device).

**Trap B: no way back out (the reported bug, root-caused on-device).** Two compounding
causes, both fixed:
1. A derived focus-latch (`searchHold && searchTreeFocus`) kept `searchOpen` true after
   `clearFocus()` because focus never fully left the overlay tree — so BACK could never
   close it. **Replaced with an explicit `searchExpanded` boolean**: opened on field focus,
   closed on touch-blur / BACK / once a search runs or a place is picked
   (`LaunchedEffect(results, selected, picking…)`). Deterministic; no latch to get stuck.
2. A shown soft IME holds an active InputConnection that **swallows the BACK key**, and
   `BasicTextField`'s built-in "BACK clears focus" ate the `KeyDown` before a `KeyUp`
   handler could run — so it took THREE presses to escape (measured: IME-hide, blur, close).
   Fix: in `dpadMode` don't raise the IME (the keypad types on hardware keys straight into
   the focused field), and catch BACK on the field via **`onPreviewKeyEvent` + `KeyDown`**
   (fires before the field's own handling). Now it's the platform-standard **two presses**
   (IME window eats the first to hide itself, the next closes) — deterministic, proven
   returning to the map.

**Trap C: DOWN couldn't leave the field (sweep fix, 2026-07-07).** With the field armed and
focused, the entry rows below it (Home / Work / saved places / recent searches / live
suggestions) were **unreachable** — a single-line `BasicTextField` swallows DOWN as a
cursor move, so focus never left the field. Fix: the field's existing `onPreviewKeyEvent`
handler gained a `DirectionDown` case that calls `focusManager.moveFocus(Down)` and consumes
the key, handing focus into the rows. (It lives inline in `SearchBar` rather than reusing the
generic `dpadFieldEscape` because it shares the handler with the BACK/close logic above; the
effect is identical.) Now: OK arms → type → DOWN walks into the suggestions → OK opens one.

Touch phones are unaffected: all of the above is gated on `dpadMode`.

### Gesture alternatives

- **Nav maneuver banner** (`NavOverlays.kt`): the banner is focusable; ←/→ walk the
  upcoming steps (the key mirror of the swipe), OK resumes live guidance while previewing
  (via the preview-mode `clickable`, which OK activates). The ←/→ key handler sits *before*
  that clickable in the modifier chain (key events bubble leaf→root, i.e. from the focused
  node up to its ancestors); a standalone `focusable()` exists only when the clickable
  doesn't, so the banner is always exactly one focus stop. **Proven on-device**: RIGHT →
  next step, RIGHT → further, LEFT → back, OK → resume.
- **Place sheet handle** (`PlaceSheet.kt`): the tap-only `detectTapGestures` became a
  real `clickable` (focusable, OK toggles peek/expanded; identical under touch). The
  drag detector is untouched. Collapse-to-dismiss stays on BACK (already handled by
  MapScreen's peel `BackHandler`).
- **Directions panel** (`PlaceSheet.kt`): the handle is `clickable` and gained the focus
  ring. **Sweep fix (2026-07-07):** with up to 4 route alternates the mode tabs + route
  list + depart options pushed the **Start** button off the bottom of the screen with no
  way to reach it — a real layout bug that affects **touch too**, not just D-pad. The
  `AnimatedVisibility` body is now a `verticalScroll` Column capped at ~58% of screen
  height (`heightIn(max = screenHeightDp * 0.58)`), so the From/To header stays visible
  above and focusing (or tapping) Start scrolls it into view.
- **Photo viewer** (`PlaceSheet.kt` `PhotoGallery`): grabs focus on open (it's a
  `Dialog`, its own focus scope); ←/→ page through photos; BACK dismisses (Dialog
  default). Pinch-zoom has no key equivalent (accepted — see limitations).
- **Text fields** (Settings + reviews search): `Modifier.dpadFieldEscape()` so UP/DOWN
  leave the field instead of being trapped in it (see Core helpers, and Trap C for the
  search bar's inline equivalent).
- **Results list / sheets**: expand/collapse already had focusable buttons (chevron,
  "Hide results" bar); they gained rings. Scroll happens implicitly as focus walks the
  rows (`LazyColumn`/`verticalScroll` bring-into-view).

### Focus visibility pass

`dpadHighlight` applied to: search bar card, suggestion/shortcut/saved rows, search
result rows, "Hide results" bar, category chips, both re-center FABs, the zoom
buttons, steps-sheet rows, both sheet handles, the maneuver banner. Material
buttons/switches/dialogs keep their built-in focus indication (adequate on those
components; extend the pass if a spot proves hard to see).

## Everything checked, file by file

| Surface | Verdict |
|---|---|
| `WelcomeScreen` | **made scrollable** — its fixed `weight(1f)`-spacer layout pushed the Get-started button off the bottom of a small (480×640) screen with no way to scroll to it, so a D-pad user couldn't SEE it (focusable-when-clipped, but invisible). Now `verticalScroll` + `heightIn(min = screen)`; button reveals + activates on-device. |
| Onboarding prompts (`VelaRoot`) | AlertDialogs + buttons — natively focusable (proven: "Offline maps" dismissed via D-pad) |
| `SearchBar` | armed-field design + BACK-out + DOWN-escape into the entry rows (Traps A/B/C above) |
| Search entry page (shortcut/saved/recent rows, menus) | `clickable` rows + `DropdownMenu`s — operable natively; rings added |
| Search results list | rows/chips/chevron operable; top-sheet drag has button equivalents; rings added |
| Map | `MapDpadController` + centre target (above) |
| Place sheet | handle fixed; action buttons/tabs/rows are Material or `clickable` — operable |
| Directions panel | handle ring; rows/chips/buttons (incl. stop reorder) are buttons — operable; body scroll-capped so **Start** is reachable with 4 alternates (sweep fix, helps touch too) |
| Route steps sheet | rows `clickable`; rings added |
| Nav (banner, controls, faster-route card, arrival card) | banner keys added; the rest are Material buttons |
| Choose-on-map | map stays pannable to position the pin (the `pickOnMap` exception, sweep fix); OK confirms; hold-OK sets directly; Set/Cancel buttons focusable |
| Settings | rows/switches/±steppers focusable; text fields got `dpadFieldEscape` so UP/DOWN escape them (sweep fix); no gesture-only widgets (no sliders) |
| Trip replay pill, notices, info cards | buttons — operable |

## Proven on-device (MTK "M5" keypad phone, Android 13, no touch used)

Every flow below was driven **with `adb input keyevent` D-pad keys only** and verified by
screenshot. Device facts that shaped the design were captured by on-device logging, not
assumed: `touchscreen=finger` (lies), `SOURCE_DPAD` only on the Virtual device,
`deviceHasKey=false`, keypad center = `KEYCODE_DPAD_CENTER` (scancode 232), `BACK` = 158.

- **Launch**: crosshair + zoom buttons + engaged edge-ring appear immediately (dpad-first).
- **Map**: ← ↑ → ↓ pan (crosshair fixed, map slides under it); BACK disengages to the "OK:
  move the map" pill; arrows then traverse the chrome.
- **Traversal + rings**: map → category chip → search bar → results rows → place-sheet rows,
  every stop showing a visible focus ring.
- **Search**: OK arms the field, hardware typing enters text, live suggestions load, and
  **two BACK presses return to the map** (the reported trap — fixed).
- **Category search → results → place**: OK on a chip returned 20 live results; OK on a row
  opened the place sheet (photos, actions, hours all focus-reachable).
- **Directions → nav**: OK on Directions computed a live route; OK on Start began turn-by-turn.
- **Nav banner**: RIGHT/RIGHT walked steps ("Turn left onto Avenue K" → "…Nostrand Ave" →
  "Arrive"), LEFT went back, OK resumed live guidance.
- **End**: focus reached the End button; OK returned to the route preview.

**Full-function sweep (2026-07-07).** Beyond the flows above, *every* surface of the app was
driven end-to-end by D-pad — map browse, search entry, results filters, the whole place
sheet, the directions panel, navigation controls, and every Settings section — specifically
to find anything still touch-only. That exhaustive pass surfaced the five refinements marked
"sweep fix" here: (1) the search field's DOWN-escape (Trap C), (2) `dpadFieldEscape` on the
Settings + reviews text fields, (3) Choose-on-map staying pannable to place the pin, (4) its
cosmetic pill suppression, and (5) the directions-panel scroll cap so **Start** is reachable
with 4 alternates. Nav was re-verified through Start → banner ←/→ step preview → voice-mute
toggle → End. Nothing gesture-only remains outside the documented limitations below.

(GPS was mocked via a `gps` test provider to give routing a valid origin; the network's
content filter otherwise leaves routing without a usable fix on this device.)

## Known limitations / follow-ups (also on the ROADMAP)

- **Text entry relies on hardware keys.** In `dpadMode` Vela focuses the field but does
  NOT raise the soft IME — the keypad's physical keys type straight into the focused field
  (verified). A device with neither a hardware keyboard nor a D-pad-navigable IME would have
  no way to type; that's out of scope (such a device isn't "D-pad operable" to begin with).
- **Live reviews panel** (`ReviewsPanel`, a WebView) — WebViews do their own key
  navigation; readable but its scroll-sync was built for touch. Not exercised on-device.
- **Photo pinch-zoom** has no key equivalent (view-only; ←/→ paging works).
- **Map tilt** (two-finger drag) has no key equivalent; browse/nav don't need it.
- **Tuning** still worth a pass: pan step (0.22 of view), OK-hold threshold (500 ms),
  focus-ring visibility in both themes. Values felt right on the test device.
- On a touch phone in `dpadMode` (external keyboard), the first *tap* on the search field
  can be eaten while the input mode flips back to Touch (tap again). Cosmetic; D-pad-first
  devices unaffected.

## Merge-with-upstream policy (how this stays rebasable)

The whole feature follows these rules — keep following them when extending it:

1. **New behaviour lives in new files**: `DpadFocus.kt`, `MapDpadController.kt`,
   `docs/dpad.md`. Upstream can't conflict with files it doesn't have.
2. **Edits to shared files are additive and anchored**, never restructuring: one
   contiguous, commented import block per file ("docs/dpad.md" marker); new modifiers
   *inserted into* existing chains; new state vars added next to related ones. The one
   near-refactor (naming the map click listener `handleTap`) changed only the lambda
   header and three `return@` labels — the 50-line body is untouched, so upstream
   diffs to it apply cleanly.
3. **Touch paths are never forked** — D-pad code calls the same lambdas/flags/state
   the gesture handlers use. Upstream fixes to tap resolution, reroute-on-pan,
   nav-zoom overrides etc. automatically apply to D-pad input.
4. **Everything is gated on `dpadMode`/`noTouch`** where it could change touch
   behaviour; with a touchscreen and no key input, the UI is byte-identical.

If a pull does conflict, the conflicts will be in the small anchored insertions —
re-apply them around the upstream change and re-read this file's design section to
confirm the invariants still hold.
