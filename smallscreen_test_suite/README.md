# Small-screen compatibility test suite

Reproducible, on-device checks that Vela works on a **tiny feature-phone display** — the same class
of device (Qin F21 and friends) that is also D-pad-driven, so this suite is the small-screen twin of
[`../dpad_test_suite/`](../dpad_test_suite/) and reuses its device helpers.

The rule it enforces: on a small screen, **nothing may be clipped off-screen or overflow** — every
surface's controls, every dialog's buttons, and every menu's options must stay fully visible and
reachable by D-pad. A control pushed past an edge exists but is unusable.

## Requirements

- A connected device or emulator (`adb`), `python3` on the host, and the app installed.
- The suite shrinks the display with `wm size`/`wm density` and **restores it on exit** (even on
  failure, via a trap). If a run is killed hard, reset manually: `adb shell wm size reset && adb shell wm density reset`.

## Run

```sh
cd smallscreen_test_suite
./run_all.sh                 # both audits; restores the screen on exit
./audit_smallscreen.sh       # surfaces only
./audit_dialogs.sh           # dialogs only
```

## What's covered

- **`audit_smallscreen.sh`** — shrinks to a small-phone size, then drives every surface with the
  D-pad (bare map, search overlay, Settings, place sheet, directions, Welcome + onboarding dialog)
  and asserts that EVERY focusable element's bounds stay fully within the screen across a full
  multi-axis traversal. Any element clipped off an edge fails.
- **`audit_dialogs.sh`** — shrinks the screen and verifies each dialog's buttons stay on-screen and
  focus-reachable even when the body is tall (the dialog must scroll, not shove its buttons off the
  bottom). Distinguishes the bare-map UpdateCard (not a dialog) from real `VelaDialog`s.

## Relationship to the app rules

The structural half lives in the D-pad suite and CLAUDE.md: `VelaDialog`/`VelaMenu` cap their height
and scroll, and `../dpad_test_suite/audit_static.sh` fails any raw `Dialog` whose content has no
scroll container and isn't full-screen. This suite is the on-device proof that it actually holds on a
feature-phone display. Run it after any change to a dialog, menu, or a screen's layout.
