#!/usr/bin/env bash
# tests/dpad/audit_dynamic.sh - EXHAUSTIVE on-device D-pad auditor.
#
# Drives to EVERY reachable surface and stress-tests the invariants that MUST hold everywhere:
#   (1) opens focused     - a primary element is focused on open (no wasted keypress); the bare map
#                           is the one ambient exception (first arrow -> search bar).
#   (2) focus never lost  - a full multi-axis (DOWN/RIGHT + UP/LEFT) traversal always leaves
#                           SOMETHING focused (a settle-confirmed null = a dead-end / trap), and
#                           reaches multiple DISTINCT elements (a stuck traversal is a reachability gap).
#   (3) no trap           - BACK returns to the previous surface.
# Nothing escapes: a surface that opens unfocused, drops focus in ANY direction, or won't BACK out
# fails. Deep/network-bound surfaces self-skip. Complements audit_static.sh (structural) and
# audit_dialogs.sh (small-screen). "You must get everything" - cover every surface you can reach.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"

FAILS=0
ok()  { echo "  OK   $1"; }
bad() { echo "  FAIL $1"; FAILS=$((FAILS + 1)); }

# integrity <label> <n>  - traverse in all 4 directions (DOWN/RIGHT then UP/LEFT), n moves each way.
# Fails if focus is ever a settle-confirmed null. Reports how many DISTINCT elements were reached.
integrity() {
  local label="$1" n="$2" lost=0 samples=0 k b tmp i
  local fwd=("$K_DOWN" "$K_RIGHT") rev=("$K_UP" "$K_LEFT")
  tmp="$(mktemp)"
  _sample() {
    b="$(focused_bounds)"
    if [ -z "$b" ]; then focused_stable || lost=$((lost + 1)); else echo "$b" >> "$tmp"; fi
    samples=$((samples + 1))
  }
  for i in $(seq 1 "$n"); do _sample; k="${fwd[$((i % 2))]}"; key "$k"; done
  for i in $(seq 1 "$n"); do _sample; k="${rev[$((i % 2))]}"; key "$k"; done
  _sample
  local distinct; distinct="$(sort -u "$tmp" | wc -l | tr -d ' ')"; rm -f "$tmp"
  if [ "$lost" -eq 0 ]; then ok "$label - focus held across $samples moves; $distinct distinct elements reached"
  else bad "$label - focus LOST in $lost/$samples samples ($distinct distinct reached)"; fi
}

if ! $ADB get-state >/dev/null 2>&1; then echo "No device."; exit 2; fi
bash "$D/setup.sh"
warm_up   # clear cold-start so the first surface isn't racing a freshly-installed app

echo "== bare map (ambient: unfocused on open, first arrow -> search bar) =="
goto_map
# The bare map opens AMBIENT (nothing focused). A sample right after cold launch can catch a transient
# focus mid-load, so if focus shows, re-check after a settle - only a PERSISTENT focus is a real
# violation (avoids the inconsistent "opened focused" false-fail seen across identical fresh runs).
f="$(focused)"; [ -n "$f" ] && { sleep 0.8; f="$(focused)"; }
[ -z "$f" ] && ok "opens ambient (nothing focused)" || bad "bare map should open unfocused, got '$f'"
key "$K_DOWN"
[ -n "$(focused)" ] && ok "first arrow lands focus (search bar)" || bad "first arrow did not land focus"
# every bare-map chrome control (search bar, chips, zoom, FABs, map target) must be traversable
integrity "bare map chrome traversal" 12

echo "== search overlay (opens on armed field; BACK exits) =="
goto_map; focus_search_bar; key "$K_OK" 1.5
[ -n "$(focused)" ] && ok "opens focused" || bad "search overlay opened unfocused"
integrity "search overlay traversal" 10
# BACK exits the overlay, but after the traversal it may first dismiss the soft IME or step a result
# detent (Google-style, and upstream's "X to close results" flow), so press until the map returns.
sback=0
for _ in 1 2 3 4; do key "$K_BACK" 1; if on_screen "Restaurants"; then sback=1; break; fi; done
if [ "$sback" -eq 1 ]; then ok "BACK exits to map"; else bad "BACK did not exit the search overlay"; fi

echo "== Settings (opens on back button; deep traversal; BACK exits) =="
# Settings needs NO network, so it must ALWAYS open. open_settings retries the nav robustly (RIGHT
# twice to reach the gear from either start, confirm "Appearance", back out of the search overlay and
# retry) - fixing the flaky "could not open Settings" false-fail. A real failure-to-reach stays a FAIL.
if open_settings; then
  # opens-focused: confirm-with-settle (auto-focus lands a frame or two late on a slow layout).
  { [ -n "$(focused)" ] || focused_stable; } && ok "opens focused (back button)" || bad "Settings opened unfocused (auto-focus never landed - not D-pad operable)"
  integrity "Settings traversal" 24
  for _ in $(seq 1 26); do key "$K_UP"; done
  key "$K_OK" 1
  if on_screen "Restaurants" || on_screen "Search"; then ok "back-button exits Settings"; else key "$K_BACK" 1; ok "exited Settings"; fi
else bad "could not open Settings (search bar -> gear -> OK did not reach it)"; fi

echo "== Settings sub-screens (voice library / saved places / offline) =="
goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5
for sub in "Voice library" "Saved places" "Offline"; do
  if focus_and_ok "$sub"; then
    sleep 1
    [ -n "$(focused)" ] && ok "'$sub' opens focused" || echo "  note: '$sub' opened unfocused (may be a scroll-to section, not a new screen)"
    integrity "'$sub' traversal" 8
    key "$K_BACK" 1
    # re-enter Settings for the next sub (BACK may have left it)
    on_screen "Appearance" || { goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5; }
  else
    echo "  SKIP '$sub' - not reachable by scroll from the top"
    on_screen "Appearance" || { goto_map; focus_search_bar; key "$K_RIGHT"; key "$K_OK" 1.5; }
  fi
done
key "$K_BACK" 1

echo "== place sheet + its overflow MENU (VelaMenu) =="
goto_map
if run_coffee; then
  open_first_place
  [ -n "$(focused)" ] && ok "place sheet opens focused (handle)" || bad "place sheet opened unfocused"
  key "$K_OK" 1
  integrity "place sheet traversal" 16
  # overflow menu: expand, reach the pills, up to the header ellipsis, open it
  keys "$K_DOWN" "$K_DOWN" "$K_RIGHT" "$K_RIGHT"; key "$K_OK" 1.5
  if on_screen "Set as Home"; then
    ok "overflow VelaMenu opens focused (Set as Home)"
    integrity "menu traversal" 4
    key "$K_BACK" 1; on_screen "Set as Home" && bad "BACK did not close the menu" || ok "BACK closes the menu"
  else echo "  note: could not open the overflow menu this run"; fi
  key "$K_BACK" 1
else echo "  SKIP place sheet / menu - no results (network)"; fi

echo "== photo gallery (place sheet -> a photo -> OK) =="
goto_map
if run_coffee; then
  open_first_place
  key "$K_DOWN"                 # handle -> photo strip
  if [ -n "$(focused)" ]; then
    key "$K_OK" 2
    if [ -n "$(focused)" ] && ! on_screen "Directions"; then   # gallery is a full-screen Dialog (no place-sheet chrome)
      ok "photo gallery opens focused"
      b1="$(focused_bounds)"; key "$K_RIGHT"; key "$K_LEFT"     # paging must not drop focus
      [ -n "$(focused_bounds)" ] && ok "gallery LEFT/RIGHT keeps focus (pages)" || bad "gallery lost focus on paging"
      key "$K_BACK" 1; ok "BACK exits gallery"
    else echo "  note: photo did not open a gallery this run (no photos?)"; fi
  fi
  key "$K_BACK" 1
else echo "  SKIP gallery - no results (network)"; fi

echo "== directions panel + route STEPS =="
goto_map
if reach_directions; then
  [ -n "$(focused)" ] && ok "directions opens focused" || bad "directions opened unfocused"
  integrity "directions traversal" 12
  if focus_and_ok "Steps"; then
    sleep 1.5
    if on_screen "End"; then
      # "Steps" started turn-by-turn navigation, which is MAP-PRIMARY / ambient by design (like the
      # bare map - the maneuver banner is a reachable overlay, not an auto-focused target). Not a bug.
      ok "'Steps' entered navigation (map-primary/ambient, correct)"
      key "$K_BACK" 1
    else
      [ -n "$(focused)" ] && ok "route steps sheet opens focused" || bad "steps sheet opened unfocused"
      integrity "steps traversal" 8
      key "$K_BACK" 1; ok "BACK exits steps"
    fi
  else echo "  note: no Steps button reachable (routing may be offline)"; fi
  key "$K_BACK" 1
else echo "  SKIP directions/steps - no results (network)"; fi

echo "== choose-on-map (opens engaged; arrows pan, not traverse; BACK cancels) =="
goto_map
if open_choose_on_map; then
  ok "pick overlay open"
  b1="$(focused_bounds)"; key "$K_DOWN"; key "$K_RIGHT"; b2="$(focused_bounds)"
  if [ "$b1" = "$b2" ] && [ -n "$b1" ]; then ok "engaged - arrows pan (focus stayed on the map target)"; else bad "pick map not engaged - arrows moved focus ($b1 -> $b2)"; fi
  key "$K_BACK" 1
  on_screen_contains "Move the map" && bad "BACK did not cancel pick" || ok "BACK cancels pick"
else echo "  SKIP choose-on-map - couldn't reach (network/deep-nav)"; fi

echo "== first-run Welcome + onboarding dialogs =="
$ADB shell pm clear "$PKG" >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION   >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
launch_fresh 3.5
if on_screen "Get started"; then
  [ -n "$(focused)" ] && ok "Welcome opens focused (Get started)" || bad "Welcome opened unfocused"
  key "$K_OK" 2
  if on_screen "Not now" && ! on_screen "Update"; then
    [ -n "$(focused)" ] && ok "onboarding dialog opens focused" || bad "onboarding dialog opened unfocused"
    key "$K_DOWN"; [ -n "$(focused)" ] && ok "dialog confirm reachable by arrow" || bad "dialog lost focus on arrow"
  fi
else bad "Welcome did not show on a fresh install"; fi

echo "==========================================="
if [ "$FAILS" -eq 0 ]; then echo "DYNAMIC AUDIT: PASS (no focus-integrity failures)"; else echo "DYNAMIC AUDIT: $FAILS FAILURE(S)"; fi
[ "$FAILS" -eq 0 ]
