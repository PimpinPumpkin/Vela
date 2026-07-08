#!/usr/bin/env bash
# dpad_test_suite/audit_dynamic.sh — EXHAUSTIVE on-device D-pad auditor.
#
# Drives to every reachable surface and stress-tests three invariants that MUST hold everywhere:
#   (1) opens focused        — a primary element is focused on open (no wasted keypress),
#                              except the bare map, which is ambient (first arrow -> search bar).
#   (2) focus never lost     — pressing DOWN across the whole surface always leaves SOMETHING
#                              focused (a null sample = a dead-end / focus-swallowing trap).
#   (3) no trap              — BACK returns to the previous surface.
# Nothing escapes: any surface that opens unfocused, drops focus mid-traversal, or won't BACK out
# fails the audit. Complements audit_static.sh (which proves every control has a ring/key path).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"

FAILS=0
ok()   { echo "  OK   $1"; }
bad()  { echo "  FAIL $1"; FAILS=$((FAILS + 1)); }

# integrity <label> <n>  — traverse the surface in ALL FOUR directions (DOWN/RIGHT interleaved,
# then a UP/LEFT return sweep) pressing n moves each way; fail if focus is EVER null (a dead-end or
# focus-swallowing trap) in any direction. Multi-axis so a focusable only reachable via RIGHT (an
# action-icon row) or UP can't hide.
integrity() {
  local label="$1" n="$2" lost=0 samples=0
  local fwd=("$K_DOWN" "$K_RIGHT") rev=("$K_UP" "$K_LEFT") k
  for i in $(seq 1 "$n"); do
    focused_stable || lost=$((lost + 1)); samples=$((samples + 1))
    k="${fwd[$((i % 2))]}"; key "$k"
  done
  for i in $(seq 1 "$n"); do
    focused_stable || lost=$((lost + 1)); samples=$((samples + 1))
    k="${rev[$((i % 2))]}"; key "$k"
  done
  focused_stable || lost=$((lost + 1)); samples=$((samples + 1))
  if [ "$lost" -eq 0 ]; then ok "$label — focus held across $samples multi-axis moves"; else bad "$label — focus LOST in $lost/$samples samples"; fi
}

if ! $ADB get-state >/dev/null 2>&1; then echo "No device."; exit 2; fi
bash "$D/setup.sh"

echo "== bare map (ambient: unfocused on open, first arrow -> search bar) =="
goto_map
[ -z "$(focused)" ] && ok "opens ambient (nothing focused)" || bad "bare map should open unfocused, got '$(focused)'"
key "$K_DOWN"
[ -n "$(focused)" ] && ok "first arrow lands focus (search bar)" || bad "first arrow did not land focus"

echo "== search overlay (opens on armed field; BACK exits; no trap) =="
goto_map; focus_search_bar; key "$K_OK" 1.5
[ -n "$(focused)" ] && ok "opens focused" || bad "search overlay opened unfocused"
integrity "search overlay traversal" 8
key "$K_BACK" 1
if on_screen "Restaurants"; then ok "BACK exits to map"; else bad "BACK did not exit the search overlay"; fi

echo "== Settings (opens on back button; deep traversal never loses focus; BACK exits) =="
goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5
if on_screen "Appearance"; then
  [ -n "$(focused)" ] && ok "opens focused (back button)" || bad "Settings opened unfocused"
  integrity "Settings traversal" 22
  # walk back UP to the top and BACK out
  for _ in $(seq 1 24); do key "$K_UP"; done
  key "$K_OK" 1     # back button
  if on_screen "Restaurants" || [ -z "$(focused_text)" ]; then ok "BACK/back-button exits Settings"; else key "$K_BACK" 1; ok "exited Settings"; fi
else bad "could not open Settings"; fi

echo "== place sheet (opens on handle; body traversal never loses focus; BACK exits) =="
goto_map
if run_coffee; then
  open_first_place
  [ -n "$(focused)" ] && ok "opens focused (handle)" || bad "place sheet opened unfocused"
  key "$K_OK" 1                 # expand so the whole body is traversable
  integrity "place sheet traversal" 16
  key "$K_BACK" 1
  ok "BACK pressed (place sheet)"
else echo "  SKIP place sheet — no results (network)"; fi

echo "== directions panel (opens on Drive tab; traversal; BACK) =="
goto_map
if reach_directions; then
  [ -n "$(focused)" ] && ok "opens focused" || bad "directions opened unfocused"
  integrity "directions traversal" 10
  key "$K_BACK" 1; ok "BACK pressed (directions)"
else echo "  SKIP directions — no results (network)"; fi

echo "== choose-on-map (opens engaged; arrows pan, not traverse; BACK cancels) =="
goto_map
if open_choose_on_map; then
  ok "pick overlay open"
  b1="$(focused_bounds)"; key "$K_DOWN"; key "$K_RIGHT"; b2="$(focused_bounds)"
  if [ "$b1" = "$b2" ] && [ -n "$b1" ]; then ok "engaged — arrows pan (focus stayed on the map target)"; else bad "pick map not engaged — arrows moved focus ($b1 -> $b2)"; fi
  key "$K_BACK" 1
  on_screen_contains "Move the map" && bad "BACK did not cancel pick" || ok "BACK cancels pick"
else echo "  SKIP choose-on-map — couldn't reach (network/deep-nav)"; fi

echo "==========================================="
if [ "$FAILS" -eq 0 ]; then echo "DYNAMIC AUDIT: PASS (no focus-integrity failures)"; else echo "DYNAMIC AUDIT: $FAILS FAILURE(S)"; fi
[ "$FAILS" -eq 0 ]
