# Sonim X320 (XP3 Plus 5G) - findings

- **Screen:** 2.95" internal TFT, 480x854 PORTRAIT, ~332 dpi (external 1.77" cover display not used
  by the app). Rugged flip phone.
- **Note:** higher resolution than the other targets, but at ~320 density its LOGICAL width is only
  ~240dp - so it's the same UX class, and `AdaptiveDensity` scales it up to ~360dp like the others.
- **Emulate:** `adb shell wm size 480x854; adb shell wm density 320`
- **Auditor:** `VELA_SMALL=480x854 VELA_SMALL_DPI=320 bash tests/small_screen/audit_smallscreen.sh`
  (the clipping check works in physical px, so it must run at the real 480x854 geometry).

## Status: NOT YET TESTED

Good generality test for AdaptiveDensity (different px resolution, same ~240dp logical width). To be
driven as a real user (fresh install, D-pad only) and confirmed here with its own screenshots.
