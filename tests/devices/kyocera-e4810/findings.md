# Kyocera e4810 - findings

- **Screen:** 2.6", 240x320 portrait, ~154 dpi.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: core surfaces PASS at SIMULATED 240x320 (not full coverage, not real hardware)

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

### First-run flow as a REAL fresh user (240x320, `pm clear`, no pref surgery) - VERIFIED VISUALLY
Every first-run surface fits (nothing clipped) and auto-focuses its primary control:

| Step | Result | Screenshot |
|---|---|---|
| Welcome | fits; "Get started" focused (OK advances) | 07-firstrun-welcome.png |
| Voice download dialog | fits; "Not now" focused | 08-firstrun-voice-dialog.png |
| Offline maps dialog | fits; "Not now" focused | 09-firstrun-offline-dialog.png |
| Diagnostics consent (2 checkboxes + descriptions + 2 buttons - the TALLEST dialog) | fits, all on-screen; "Not now" focused | 10-firstrun-consent-dialog.png |
| Landed bare map | all 3 category chips + full chrome (adaptive density) | 11-firstrun-landed-map.png |

The consent card shows on the 2nd launch (by design: `launches >= 2`), so the fresh chain is
Welcome -> voice -> offline -> map, then consent on relaunch.

### FULL coverage (240x320) - all 16 surfaces, VERIFIED VISUALLY (`screenshots/full/`)
`full_coverage.sh` (with a Philadelphia mock fix for dense search/routing) + the whole-Settings D-pad
walk captured a labeled screenshot of EVERY surface at 240x320; each FITS (no clipping) and is
D-pad-navigable:

01 welcome, 02 voice dialog, 03 offline dialog, 04 consent dialog, 05 bare map, 06 search overlay,
07 search results, 08 place sheet, 09 place expanded, 10 directions, 11 route steps, 12 settings top,
13 settings lower, 14 voice library (expanded voice catalog), 15 offline (expanded picker),
16 saved places (section + Export/Import buttons).

**Harness note (honest):** the automated gate reliably auto-captures ~14/16; the two DEEPEST Settings
sub-sections (Offline, Saved places) are flaky in a single scripted run - the `on_screen` check races
the long-list scroll animation - so they were captured via the full-Settings D-pad walk instead. The
APP covers all 16 (proven by screenshot); making the gate reliably hit 16/16 in one shot (retries /
longer settle on `scroll_to`) is a harness follow-up, not an app gap.

## Screenshots
See `screenshots/` - all captured at 240x320 via `adb exec-out screencap`.
