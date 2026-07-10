# Sonim XP3 (XP3800) - findings

- **Screen:** 2.6" primary internal screen, 240x320 PORTRAIT, ~154 dpi. Rugged flip phone.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: NOT YET TESTED

Same 240x320 portrait geometry as the Kyocera e4810 and TCL Flip 2, so the Kyocera findings likely
apply. To be driven as a real user (fresh install, D-pad only) and confirmed here with its own
screenshots.
