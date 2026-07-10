# TCL Flip 2 - findings

- **Screen:** 2.8", 240x320 PORTRAIT, ~143 dpi. A flip phone; the main screen is portrait.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: NOT YET TESTED

Same 240x320 portrait geometry as the Kyocera e4810, so the Kyocera findings likely apply (adaptive
density fits the chrome; DOWN-from-Back focus-clear in Settings still to fix). To be driven as a real
user (fresh install, D-pad only) and confirmed here with its own screenshots.
