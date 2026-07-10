#!/usr/bin/env bash
# tests/dpad/audit_smallscreen.sh - EXTREME small-screen + D-pad compatibility auditor.
#
# Feature phones are BOTH tiny-screen AND D-pad-driven, so this ties the two together: it shrinks
# the display to a feature-phone size, then drives every surface with ONLY the D-pad and asserts
# that EVERY element the D-pad can focus stays FULLY ON-SCREEN (its bounds are inside [0,0]-[W,H]).
# A control clipped off an edge is unreachable/unreadable on a small phone even though it exists.
# This is the small-screen twin of audit_dynamic.sh (same surfaces, same multi-axis traversal), and
# extends audit_dialogs.sh (which only checked dialog buttons) to the WHOLE app. Strict: any clipped
# focusable fails. Deep/network surfaces self-skip. Restores the real screen on exit.
set -uo pipefail
DPAD="$(cd "$(dirname "${BASH_SOURCE[0]}")/../dpad" && pwd)"; source "$DPAD/lib.sh"; source "$DPAD/nav.sh"; D="$DPAD"

if ! $ADB get-state >/dev/null 2>&1; then echo "No device."; exit 2; fi
FAILS=0
ok()  { echo "  OK   $1"; }
bad() { echo "  FAIL $1"; FAILS=$((FAILS + 1)); }
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; $ADB shell settings delete global vela_force_dpad >/dev/null 2>&1; }
trap restore EXIT
# A feature phone is small-screen AND D-pad, so force D-pad-first (see tests/dpad/setup.sh).
$ADB shell settings put global vela_force_dpad 1 >/dev/null 2>&1

# Shrink to a small phone. Read back the ACTUAL logical size for the on-screen bounds test.
echo "== shrinking display to a small-phone size =="
$ADB shell wm size 360x640 >/dev/null 2>&1
$ADB shell wm density 200 >/dev/null 2>&1
sleep 1
SZ="$($ADB shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
SW="${SZ%x*}"; SH="${SZ#*x}"
echo "  logical screen: ${SW}x${SH}"

# traverse_bounds <label> <n> - multi-axis D-pad walk; every focused element must lie fully within
# the screen. Reports each clipped element (off an edge = unreachable on a small display).
traverse_bounds() {
  local label="$1" n="$2" clipped=0 seen=0 k b i x1 y1 x2 y2
  local fwd=("$K_DOWN" "$K_RIGHT") rev=("$K_UP" "$K_LEFT")
  _chk() {
    b="$(focused_bounds)"; [ -z "$b" ] && return
    seen=$((seen + 1))
    x1="$(echo "$b" | sed -E 's/^\[(-?[0-9]+),.*/\1/')"
    y1="$(echo "$b" | sed -E 's/^\[-?[0-9]+,(-?[0-9]+)\].*/\1/')"
    x2="$(echo "$b" | sed -E 's/.*\]\[(-?[0-9]+),-?[0-9]+\]$/\1/')"
    y2="$(echo "$b" | sed -E 's/.*,(-?[0-9]+)\]$/\1/')"
    if [ "$x1" -lt 0 ] || [ "$y1" -lt 0 ] || [ "$x2" -gt "$SW" ] || [ "$y2" -gt "$SH" ] 2>/dev/null; then
      echo "    CLIPPED off-screen: $b (screen ${SW}x${SH})"; clipped=$((clipped + 1))
    fi
  }
  for i in $(seq 1 "$n"); do _chk; k="${fwd[$((i % 2))]}"; key "$k"; done
  for i in $(seq 1 "$n"); do _chk; k="${rev[$((i % 2))]}"; key "$k"; done
  _chk
  if [ "$clipped" -eq 0 ]; then ok "$label - $seen focused elements, all fully on-screen"; else bad "$label - $clipped/$seen focused element(s) CLIPPED off-screen"; fi
}

echo "== bare map chrome on a small screen =="
goto_map; key "$K_DOWN"
traverse_bounds "bare map" 12

echo "== search overlay on a small screen =="
goto_map; focus_search_bar; key "$K_OK" 1.5
traverse_bounds "search overlay" 10
key "$K_BACK" 1

echo "== Settings on a small screen (the tall one - every row + button must stay on-screen) =="
goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5
if on_screen "Appearance"; then traverse_bounds "Settings" 26; key "$K_BACK" 1; else echo "  SKIP Settings"; fi

echo "== place sheet on a small screen =="
goto_map
if run_coffee; then
  open_first_place; key "$K_OK" 1
  traverse_bounds "place sheet" 16
  key "$K_BACK" 1
else echo "  SKIP place sheet - no results (network)"; fi

echo "== directions panel on a small screen =="
goto_map
if reach_directions; then traverse_bounds "directions" 12; key "$K_BACK" 1; else echo "  SKIP directions - no results (network)"; fi

echo "== onboarding dialog on a small screen (buttons must not fall off) =="
$ADB shell pm clear "$PKG" >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION   >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
launch_fresh 3.5
if on_screen "Get started"; then
  traverse_bounds "Welcome" 6
  key "$K_OK" 2
  if on_screen "Not now" && ! on_screen "Update"; then traverse_bounds "onboarding dialog" 4; fi
fi

echo "==========================================="
if [ "$FAILS" -eq 0 ]; then echo "SMALL-SCREEN AUDIT: PASS (nothing clipped off-screen)"; else echo "SMALL-SCREEN AUDIT: $FAILS FAILURE(S)"; fi
restore
[ "$FAILS" -eq 0 ]
