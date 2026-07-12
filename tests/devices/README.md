# Target device matrix - D-pad + small-screen support

This fork's reason to exist is 100% D-pad operation on real feature phones. This folder tracks
every device we commit to supporting, with the exact screen geometry to test against and the
per-device findings (with screenshots).

**Scope caveat (read this):** all results here are from **SIMULATING** the screen size (`wm size` /
`wm density`) on a test device - **nothing has run on the actual phones**, so real-hardware quirks are
unconfirmed. And "verified" below means the **core surfaces** (first-run flow, bare map, search, place
sheet, directions, Settings) fit and are D-pad-navigable at that simulated size - NOT full D-pad
coverage of every screen. Targets collapse to two sizes (240x320 and 480x854), so a phone's status is
its size's status.

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
| [TCL Flip 2](tcl-flip-2/findings.md) | 2.8" | 240x320 | portrait | ~143 (160) | covered (240x320) | same emulated geometry as Kyocera |
| [Sonim XP3 (XP3800)](sonim-xp3/findings.md) | 2.6" | 240x320 | portrait | ~154 (160) | covered (240x320) | same emulated geometry as Kyocera |
| [Kyocera DuraXV](kyocera-duraxv/findings.md) | - | 240x320 | portrait | ~160 | covered (240x320) | same emulated geometry as Kyocera e4810 |
| [Sonim X320 (XP3 Plus 5G)](sonim-x320/findings.md) | 2.95" | 480x854 | portrait | ~332 (320) | verified | driven at 480x854@320; AdaptiveDensity generalizes |

Every target so far is **240x320 portrait** (the "320x240" written on some spec sheets is the same
panel in portrait). So the auditor default (240x320) covers them all; densities differ slightly with
physical size but all round to ~160. More models will be added as they are named. Goal: perfect D-pad
+ screen-size compatibility across all of them.

## Tooling

- **`bash tests/devices/full_coverage.sh <id>`** - the FULL-COVERAGE gate. Drives EVERY surface
  (first-run + all dialogs, bare map, search + results, place sheet + expanded, directions + steps,
  Settings + sub-screens: Voice library / Offline / Saved places) at the device's simulated geometry,
  captures a labeled screenshot of each into `<id>/screenshots/full/`, and prints a COVERED/MISSED
  checklist. **A device is "fully supported" only when this reports FULLY COVERED (0 MISSED) and you
  have looked at the frames** (AGENTS.md hard rule). Content surfaces need live search+routing - run
  with the network up and a mock GPS fix over any city that has data (`VELA_LAT=<lat> VELA_LNG=<lng>`,
  defaults to a built-in one).
  - **Per-feature phases - never run FULL just to test one feature.** The gate is phased:
    `PHASES="<names>" bash tests/devices/full_coverage.sh <id>` re-captures only those surface
    groups, writing the same numbered frames and leaving the rest in place. Current phases:
    `firstrun map search place directions settings voice parking`. **Rule: every new feature adds
    its surfaces as its OWN phase in the same PR**, so verifying it on any device is one short
    reproducible run - the FULL tour stays reserved for the fully-supported verdict.
  - **HARD RULE - every target geometry, every time.** A phase is verified only when it has run
    green AND its frames were checked by eye at EVERY geometry in the matrix (both 240x320@160 and
    480x854@320 today). One size never stands in for the other; a new geometry joins the loop the
    day it lands in the matrix.
- **`bash tests/devices/capture.sh <id>`** - lighter AUTO-capture of the first-run flow + core surfaces
  into `<id>/screenshots/auto/` (`--warm` skips the fresh `pm clear`; `capture.sh all` does every
  device). Use `full_coverage.sh` for the support gate; `capture.sh` for a quick visual refresh.
- **`bash tests/small_screen/run_matrix.sh`** - runs `audit_smallscreen.sh` at EVERY target geometry
  (240x320@160 and 480x854@320) and prints a per-geometry PASS/FAIL summary. Use it to confirm every
  density level is green in one shot.
- The auditors auto-detect the installed build (`app.vela` vs `app.vela.debug`), retry the uiautomator
  dump when it races an animation, verify the app actually reached the foreground, and warm up cold
  starts - so a passing run means the app, not the harness.

## Adding a target device

1. Add a row to the table above (model, screen, resolution, orientation, ~dpi/density).
2. Add its geometry to the `DEVICES` table in `capture.sh` and (if a new size) the `GEOMS` list in
   `tests/small_screen/run_matrix.sh`.
3. `mkdir tests/devices/<id>` and add a `findings.md` (copy an existing one).
4. Run `capture.sh <id>` and `run_matrix.sh`, then VERIFY the screenshots by eye and record results in
   the findings. Same-geometry devices can reference the reference device's findings (see TCL Flip 2).
