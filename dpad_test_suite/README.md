# D-pad test suite

Reproducible, on-device checks that Vela stays fully operable with a **5-key D-pad** (↑ ↓ ← → +
OK, plus BACK) and **no touchscreen** — the scripted version of the manual `adb` checks used while
building the D-pad support. See [`../docs/dpad.md`](../docs/dpad.md) for the design.

Each test drives the app with **only** `adb shell input keyevent` and asserts on the **focused
element** (read from `uiautomator dump`), so it catches the two things that matter for D-pad:
*is the right thing focused when a screen opens*, and *does navigation reach/activate it*.

## Requirements

- A connected device or emulator (`adb`), and `python3` on the host.
- The Vela app installed (build a release APK and `adb install -r` it).
- The device is best driven with a real or virtual **D-pad**; the tests only send D-pad keys.

## Run

```sh
cd dpad_test_suite
./run_all.sh                      # setup + all tests, prints a pass/fail summary
./run_all.sh 01 02                # only tests whose name starts 01 / 02
ADB="adb -s emulator-5554" ./run_all.sh   # pick a device
```

`run_all.sh` first calls `setup.sh` (grants location + notifications, installs a mock GPS provider
at Brooklyn so search/routing have a fix — override with `VELA_LAT`/`VELA_LNG`, and **forces
D-pad-first** via `settings put global vela_force_dpad 1`). The force flag matters since the
2026-07-08 detection fix: an ordinary touchscreen dev phone is correctly NOT D-pad-first, so without
it the auto-focus/ring/arm behaviour is off and the suite can't exercise the D-pad path. Real D-pad
phones (touchless / physical DPAD) don't need it. Run **`./teardown.sh`** when done to clear the flag
and reset any screen change. Then it runs `tests/*.sh` in order; each prints `PASS:`/`FAIL:` lines
and a per-suite verdict. Exit code is non-zero if any suite failed (usable in CI once a device is attached).

## What's covered

| Test | Asserts |
|---|---|
| `01_map_opens_on_search` | the bare map opens ambient (nothing focused, not engaged); the first ↓ lands on the search bar — no BACK-to-move |
| `02_settings_autofocus` | Settings opens already focused on the back button (the original "opened un-focused" bug) |
| `03_welcome_and_dialog_autofocus` | first-run Welcome opens focused on Get-started; each onboarding `VelaDialog` opens focused on "Not now" |
| `04_place_sheet_and_menu_autofocus` | the place sheet opens focused on its handle; the ⋮ overflow (`VelaMenu`) opens focused on its first item; ↓ walks it; BACK closes the menu not the sheet |

## Writing a new test

Drop a `NN_name.sh` in `tests/`. Source the libs and use the helpers:

```sh
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
goto_map                                  # cold launch to the bare map
key "$K_DOWN"                             # press one D-pad key (K_UP/DOWN/LEFT/RIGHT/OK/BACK)
assert_focus_text "Set as Home"           # assert the focused element's text
assert_focus_ytop_between 30 140 "search bar"   # …or its position (for icon/handle targets)
assert_nothing_focused "bare map"; assert_ime_hidden
report                                    # prints the tally; exit status = all-passed
```

Key helpers (in `lib.sh`): `focused` / `focused_text` / `focused_desc` / `focus_ytop`,
`find_text` / `on_screen`, `assert_focus_text` / `assert_focus_desc` / `assert_focus_ytop_between`
/ `assert_nothing_focused` / `assert_something_focused` / `assert_on_screen` / `assert_not_on_screen`
/ `assert_ime_hidden`, and `key` / `keys` / `launch_fresh` / `shot`. Vela-specific navigation
(`goto_map`, `run_coffee`, `open_first_place`, `dismiss_onboarding`) is in `nav.sh`.

## Notes / limits

- **Live-data tests** (`04`, anything past a search) need a working network for Google scraping; on
  a filtered/offline network they self-skip rather than fail.
- **Deep navigation is inherently timing-sensitive** (sheet detents, search latency). The helpers use
  generous settles; bump the `key`/`launch_fresh` sleeps on a slow device.
- These assert **focus & navigation**, not pixels. For a visual check, `shot out.png` grabs a
  screenshot at any point.
- Menus/dialogs are `VelaMenu`/`VelaDialog` (raw-Dialog based) specifically so they *can* be
  auto-focused — a stock Compose `DropdownMenu`/`AlertDialog` cannot (see docs/dpad.md).
