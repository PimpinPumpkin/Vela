# Kyocera e4810 - findings

- **Screen:** 2.6", 240x320 portrait, ~154 dpi.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: D-PAD + SMALL-SCREEN VERIFIED (240x320)

### Works
- **Adaptive density fits the UI.** With `AdaptiveDensity` (the app scales its own density on small
  screens so it always has >= ~360dp of logical width), the bare map now shows ALL THREE category
  chips (Restaurants/Coffee/Gas) and the full chrome comfortably - before, only "Restaurants" + a
  sliver of Coffee fit. Before: ![cramped](screenshots/01-bare-map.png) After:
  ![adaptive](screenshots/04-bare-map-adaptive-density.png). Settings shows more per screen too:
  ![settings adaptive](screenshots/05-settings-adaptive-density.png)
- **Settings opens FOCUSED.** The Back button takes focus on open (the robust `dpadAutoFocus`
  fix) - no wasted first keypress. ![settings open](screenshots/02-settings-open-back-focused.png)

### Fixed
- **DOWN from the focused Back button used to CLEAR focus** instead of entering the content list
  (Compose can't cross from the TopAppBar into the scrolling Column and cleared). Fixed with an
  explicit DOWN-from-Back -> first-content-row bridge (`topRowFocus.requestFocus()`, mirror of the
  UP-from-top -> Back routing). Before: ![cleared](screenshots/03-settings-down-focus-cleared.png)
  After: ![fixed](screenshots/06-down-from-back-fixed.png) - DOWN now lands on "Follow system", next
  DOWN on "Light". Settings is fully D-pad navigable at 240x320.

### Auditor results (`tests/small_screen/audit_smallscreen.sh`, 240x320, measured)
Hard data from the last full run - every element focusable AND fully on-screen (no clipping):

| Surface | Focusable elements | On-screen |
|---|---|---|
| bare map | 25 | all |
| search overlay | 21 | all |
| place sheet | 33 | all |
| directions | 25 | all |
| Welcome (first-run) | 13 | all |
| onboarding dialog | 9 | all |

Auditor infrastructure fixes this required (all committed): `PKG` now auto-detects `app.vela.debug`
(it hardcoded `app.vela`, so `launch_fresh` was launching a non-existent package and driving whatever
app was foreground); `ui_dump` retries the uiautomator dump when it races an animation and returns only
the root node; `launch_fresh` verifies Vela reached the foreground and retries.

**Settings:** verified D-PAD-NAVIGABLE VISUALLY (screenshots 02 + 06: opens focused on Back, DOWN ->
Follow system -> Light). The auditor's automated navigation to the gear is best-effort and can flake
(the bare-map focus model - ambient POI markers vs the search row - makes scripted gear-reaching
unreliable); per the visual-verification rule the screenshots are the proof, so Settings is not gated
on the harness reaching it.

## Screenshots
See `screenshots/` - all captured at 240x320 via `adb exec-out screencap`.
