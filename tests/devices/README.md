# Target device matrix - D-pad + small-screen support

This fork's reason to exist is 100% D-pad operation on real feature phones. This folder tracks
every device we commit to supporting, with the exact screen geometry to test against and the
per-device findings (with screenshots) from driving the app as a real user.

## How to test a device (as a REAL user, verified VISUALLY)

Feature phones are BOTH tiny-screen AND D-pad-only, so both must hold at the device's real size.

1. Emulate the device geometry on any test phone:
   `adb shell wm size <WxH>; adb shell wm density <dpi>` (restore with `wm size reset; wm density reset`).
2. Drive it as a REAL user - do NOT pre-set prefs to skip onboarding. A fresh install shows Welcome,
   the voice/offline prompts, and the "Help improve Vela?" diagnostics consent BEFORE the map; those
   are D-pad + small-screen surfaces too and must pass. `adb shell pm clear app.vela.debug` to reset
   to a true first-run.
3. D-pad only: `adb shell input keyevent` 19/20/21/22 (arrows), 23 (OK), 4 (BACK).
4. VERIFY VISUALLY: `adb exec-out screencap -p > frame.png` and LOOK - focus ring present and on the
   right element, nothing clipped off-screen, text readable. `uiautomator` cannot see some Compose
   screens (the search overlay dumps as a single node), so the screenshot is the ground truth, not a
   focus dump or a passing script.
5. Save frames under `tests/devices/<model>/screenshots/` and record what works / what's broken in
   the model's `findings.md`.

The scripted auditor `tests/small_screen/audit_smallscreen.sh` defaults to the smallest target and
takes `VELA_SMALL=WxH VELA_SMALL_DPI=<dpi>` to sweep each device.

## Devices

| Model | Screen | Resolution | Orientation | ~dpi (density) | Status | Notes |
|---|---|---|---|---|---|---|
| [Kyocera e4810](kyocera-e4810/findings.md) | 2.6" | 240x320 | portrait | ~154 (160) | verified | 6/7 auditor surfaces pass; Settings verified visually; adaptive density fits all chips |
| [TCL Flip 2](tcl-flip-2/findings.md) | 2.8" | 240x320 | portrait | ~143 (160) | not yet tested | flip phone |
| [Sonim XP3 (XP3800)](sonim-xp3/findings.md) | 2.6" | 240x320 | portrait | ~154 (160) | not yet tested | rugged flip |
| [Sonim X320 (XP3 Plus 5G)](sonim-x320/findings.md) | 2.95" | 480x854 | portrait | ~332 (320) | not yet tested | rugged flip; high-res but ~240dp wide |

Every target so far is **240x320 portrait** (the "320x240" written on some spec sheets is the same
panel in portrait). So the auditor default (240x320) covers them all; densities differ slightly with
physical size but all round to ~160. More models will be added as they are named. Goal: perfect D-pad
+ screen-size compatibility across all of them.
