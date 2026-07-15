# Sonim X320 (XP3 Plus 5G) - findings

- **Screen:** 2.95" internal TFT, 480x854 PORTRAIT, ~332 dpi (external 1.77" cover display not used
  by the app). Rugged flip phone.
- **Note:** higher resolution than the other targets, but at ~320 density its LOGICAL width is only
  ~240dp - so it's the same UX class, and `AdaptiveDensity` scales it up to ~360dp like the others.
  This makes it the generality test for `AdaptiveDensity`: a different pixel resolution, same logical
  width, same result.
- **Emulate:** `adb shell wm size 480x854; adb shell wm density 320`
- **Auditor:** `VELA_SMALL=480x854 VELA_SMALL_DPI=320 bash tests/small_screen/audit_smallscreen.sh`
  (the clipping check works in physical px, so it must run at the real 480x854 geometry).

## Status: FULLY COVERED at SIMULATED 480x854 @ 320dpi (20/20 surfaces; not real hardware)

`bash tests/devices/full_coverage.sh sonim-x320` (with a mock GPS fix) reports **20 COVERED, 0
MISSED / RESULT: FULLY COVERED** - every surface reachable, D-pad-navigable and clip-free at the
device's geometry. Screenshots in [`screenshots/full/`](screenshots/full/):

| # | Surface | Screenshot |
|---|---|---|
| 01 | First-run Welcome ("Get started" focused) | 01-welcome.png |
| 02 | Voice-download dialog ("Not now" focused) | 02-voice-dialog.png |
| 03 | Offline-maps dialog ("Not now" focused) | 03-offline-dialog.png |
| 04 | Diagnostics consent dialog (tallest; both checkboxes + descriptions + 2 buttons on-screen) | 04-consent-dialog.png |
| 05 | Bare map (all 3 category chips + full chrome, adaptive density) | 05-bare-map.png |
| 06 | Search overlay | 06-search-overlay.png |
| 07 | Search results | 07-search-results.png |
| 08 | Place sheet | 08-place-sheet.png |
| 09 | Place sheet expanded | 09-place-sheet-expanded.png |
| 10 | Directions | 10-directions.png |
| 11 | Route steps | 11-route-steps.png |
| 12 | Settings top | 12-settings-top.png |
| 13 | Settings lower | 13-settings-lower.png |
| 14 | Settings -> Voice library | 14-settings-voice-library.png |
| 15 | Settings -> Offline | 15-settings-offline.png |
| 16 | Settings -> Saved places | 16-settings-saved-places.png |
| 17 | Voice search (capture sheet / system recognizer) | 17-voice-capture-sheet.png |
| 18 | Parking spot saved | 18-parking-saved.png |
| 19 | Parking hub menu | 19-parking-menu.png |
| 20 | Parked-car sheet | 20-parking-car-sheet.png |

Confirms `AdaptiveDensity` works on logical dp, not pixels - so it generalizes across resolutions: at
480x854 @ 320dpi it scales the same ~240dp logical width up to ~360dp and the whole app fits exactly
as it does at 240x320.

### Reaching the deep Settings sub-sections
Voice library, Offline and Saved places sit near the bottom of a long list. Per-row D-pad polling was
too slow here (a `uiautomator dump` is ~2.6s on this device) and overshot the non-focusable section
headers, so `full_coverage.sh` reaches them with a controlled-drag scroll (`swipe_up_to` in
`tests/dpad/lib.sh` - short 700ms drags that can't fling a thin header past between checks) from a
fresh Settings each, then frames the header. The screenshots capture each sub-section rendering
clip-free; the EXPANDED pickers (voice catalog, offline region list) are entered and D-pad-navigated
by `tests/dpad/audit_dynamic.sh`.

### Iterating
Re-capture a single phase without redoing the whole ~13-min tour, e.g. the Settings block:
`PHASES=settings bash tests/devices/full_coverage.sh sonim-x320` (writes the same 12..16 frames,
leaves the rest). Phases: `firstrun map search place directions settings voice parking`. Only a full
run (no `PHASES`) prints the FULLY COVERED verdict.
