#!/usr/bin/env bash
# dpad_test_suite/audit_dialogs.sh — EXTREME small-screen dialog audit.
#
# Shrinks the display to a feature-phone size and verifies that a dialog shows ALL of its content
# and BUTTONS without clipping any off-screen — the buttons must stay on-screen and focus-reachable
# even when the body is tall (it must scroll, not shove the buttons past the bottom edge). This is
# the on-device counterpart to audit_static.sh's "every Dialog has a scroll container" check, and it
# targets the tester's Qin-F21-class tiny screens (user 2026-07-08).
#
#   ./audit_dialogs.sh          # runs; restores the original screen size on exit
set -uo pipefail
DPAD="$(cd "$(dirname "${BASH_SOURCE[0]}")/../dpad_test_suite" && pwd)"; source "$DPAD/lib.sh"; source "$DPAD/nav.sh"; D="$DPAD"

if ! $ADB get-state >/dev/null 2>&1; then echo "No device."; exit 2; fi
FAILS=0
ok()  { echo "  OK   $1"; }
bad() { echo "  FAIL $1"; FAILS=$((FAILS + 1)); }

# Screen height in px (for the "button is on-screen" bound).
SCREEN_H() { $ADB shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2; }

# Always restore the real screen on exit.
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

echo "== shrinking display to a feature-phone size (360x480) =="
$ADB shell wm size 360x480 >/dev/null 2>&1
sleep 1
H="$(SCREEN_H)"; echo "  screen height now ${H}px"

# assert_focus_on_screen <label> — the currently-focused element's BOTTOM edge is within the screen
# (a button shoved off the bottom by an un-scrolling dialog fails this), and something IS focused.
assert_focus_on_screen() {
  local b y2
  b="$(focused_bounds)"
  if [ -z "$b" ]; then bad "$1 — nothing focused (dialog opened unfocused or clipped)"; return; fi
  y2="$(echo "$b" | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]$/\1/')"
  if [ -n "$y2" ] && [ "$y2" -le "$H" ] 2>/dev/null; then ok "$1 — focused button on-screen (y2=$y2 <= $H): $b"; else bad "$1 — focused element BELOW the screen (y2=$y2 > $H) — clipped: $b"; fi
}

echo "== first-run onboarding dialogs (VelaDialog) on the small screen =="
$ADB shell pm clear "$PKG" >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION   >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
launch_fresh 3.5
if on_screen "Get started"; then key "$K_OK" 2; fi          # past Welcome
# Each onboarding dialog auto-focuses its dismiss button; that button must be on-screen (not clipped).
for i in 1 2 3 4; do
  # A real onboarding VelaDialog has "Not now"; the bare-map UpdateCard ALSO has "Not now" but pairs
  # it with "Update" and is a map overlay (deliberately not auto-focused) — stop when we reach it.
  if on_screen "Not now" && ! on_screen "Update"; then
    assert_focus_on_screen "onboarding dialog $i"
    # the confirm side must be reachable too (arrow to it, still on-screen)
    key "$K_DOWN"; assert_focus_on_screen "onboarding dialog $i (after arrow to confirm)"
    key "$K_OK" 1.2
  else
    break
  fi
done

echo "== a Settings confirmation dialog (delete-voice / consent) on the small screen =="
goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5
if on_screen "Appearance"; then
  # Diagnostics export shows a consent VelaDialog with checkboxes (the tallest dialog) — reach it.
  if focus_and_ok "Export debug logs" || focus_and_ok "Export logs" || focus_and_ok "Diagnostics"; then
    sleep 1
    if on_screen_contains "iagnostic" || on_screen "Cancel"; then
      assert_focus_on_screen "diagnostics consent dialog"
      shot "$D/../../scratchpad/dlg_small.png" 2>/dev/null || true
      key "$K_BACK" 1
    else
      echo "  (no consent dialog surfaced — export may be immediate; skipping)"
    fi
  else
    echo "  (couldn't reach a Settings confirmation dialog to test)"
  fi
fi

echo "==========================================="
if [ "$FAILS" -eq 0 ]; then echo "DIALOG AUDIT: PASS (all dialog buttons stayed on-screen)"; else echo "DIALOG AUDIT: $FAILS FAILURE(S)"; fi
restore
[ "$FAILS" -eq 0 ]
