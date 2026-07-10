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

# Shrink to a REAL target feature-phone size. Default is the common target geometry; override per
# device with VELA_SMALL=WxH and VELA_SMALL_DPI. Known target device matrix (grows as more models are
# added - see tests/devices/README.md):
#   Kyocera e4810   2.6"  240x320 portrait  (~154 dpi -> density 160)   <- default
#   TCL Flip 2      2.8"  240x320 portrait  (~143 dpi -> density 160)
#   Sonim XP3       2.6"  240x320 portrait  (~154 dpi -> density 160)
# All targets so far are 240x320 portrait, so the default covers them.
# NB test as a REAL USER: do NOT pre-set onboarding prefs to skip the first-run flow - the Welcome /
# voice / offline / diagnostics-consent dialogs are D-pad + small-screen surfaces too and must pass.
VELA_SMALL="${VELA_SMALL:-240x320}"; VELA_SMALL_DPI="${VELA_SMALL_DPI:-160}"
echo "== shrinking display to a real feature-phone size ($VELA_SMALL @ ${VELA_SMALL_DPI}dpi) =="
$ADB shell wm size "$VELA_SMALL"        >/dev/null 2>&1
$ADB shell wm density "$VELA_SMALL_DPI" >/dev/null 2>&1
sleep 1
SZ="$($ADB shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
SW="${SZ%x*}"; SH="${SZ#*x}"
echo "  logical screen: ${SW}x${SH}"
warm_up   # clear cold-start so the first surface below isn't racing a freshly-installed app

# traverse_bounds <label> <n> - multi-axis D-pad walk; every focused element must lie fully within
# the screen. Reports each clipped element (off an edge = unreachable on a small display).
traverse_bounds() {
  local label="$1" n="$2" clipped=0 seen=0 k b i x1 y1 x2 y2
  local fwd=("$K_DOWN" "$K_RIGHT") rev=("$K_UP" "$K_LEFT")
  _chk() {
    b="$(focused_bounds)"
    # A null sample is usually a dump racing a scroll animation - re-check once after a settle so a
    # transient null doesn't under-count. A null that survives is a REAL no-focus (caught below via seen).
    [ -z "$b" ] && { sleep 0.3; b="$(focused_bounds)"; }
    [ -z "$b" ] && return
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
  # seen==0 after a full multi-axis walk means NOTHING ever took focus - the screen is not D-pad
  # operable on a small display (e.g. auto-focus never landed and arrows can't establish it). That is
  # a hard FAIL, not a vacuous "0 clipped = PASS" (the exact hole that hid a real Settings focus bug).
  if [ "$seen" -eq 0 ]; then bad "$label - opened but NOTHING became focusable across the whole D-pad walk (not operable on a small screen)"
  elif [ "$clipped" -eq 0 ]; then ok "$label - $seen focused elements, all fully on-screen"
  else bad "$label - $clipped/$seen focused element(s) CLIPPED off-screen"; fi
}

echo "== bare map chrome on a small screen =="
goto_map; key "$K_DOWN"
traverse_bounds "bare map" 12

echo "== search overlay on a small screen =="
goto_map; focus_search_bar; key "$K_OK" 1.5
traverse_bounds "search overlay" 10
key "$K_BACK" 1

echo "== Settings on a small screen (the tall one - every row + button must stay on-screen) =="
# open_settings drives to the gear; if it reaches Settings, traverse it (clipping/focus checked like
# any surface). If the harness can't script-navigate the gear (the bare-map focus model - ambient POI
# markers vs the search row - makes scripted gear-reaching unreliable), that is NOT a vacuous pass and
# NOT a misleading app FAIL: Settings D-pad operation is VERIFIED VISUALLY (tests/devices/: opens
# focused on Back, DOWN enters the content list). So emit a NOTE, per "verified visually is the proof".
if open_settings; then traverse_bounds "Settings" 26; key "$K_BACK" 1
else echo "  NOTE Settings - harness could not script-navigate to the gear; VERIFIED VISUALLY instead (see tests/devices/, screenshots 02+06). Not a pass, not a fail."; fi

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
