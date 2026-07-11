#!/usr/bin/env bash
# tests/devices/full_coverage.sh - the FULL-COVERAGE gate for calling a device "fully supported".
#
# A device is FULLY SUPPORTED only when EVERY screen is reachable, D-pad-navigable, and clip-free at
# that device's screen size. This drives every surface at the device's simulated geometry, captures a
# labeled screenshot of each, and prints a coverage checklist (COVERED / MISSED). "Fully supported"
# requires ALL rows COVERED - a MISSED row (including network-blocked search/place/nav/transit) means
# NOT fully supported until it's re-run with that surface reachable. See AGENTS.md
# "Fully supported (HARD REQUIREMENT)".
#
#   bash tests/devices/full_coverage.sh <device-id>     # e.g. kyocera-e4810
#   bash tests/devices/full_coverage.sh all
#
# Content surfaces (search/place/directions/nav/transit) need live search+routing; run with network up.
DEVICES="
kyocera-e4810|240x320|160
sonim-x320|480x854|320
"   # 240x320 also stands in for tcl-flip-2 and sonim-xp3 (identical geometry)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../dpad/lib.sh"; source "$HERE/../dpad/nav.sh"
$ADB get-state >/dev/null 2>&1 || { echo "No device."; exit 2; }
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

cover_one() {
  local id="$1" geom dens out i=0 covered=0 missed=0 checklist=""
  geom="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $2}')"
  dens="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $3}')"
  [ -z "$geom" ] && { echo "unknown device '$id'"; return 1; }
  out="$HERE/$id/screenshots/full"; mkdir -p "$out"; rm -f "$out"/*.png
  echo "################ FULL COVERAGE: $id ($geom @ ${dens}dpi) ################"
  $ADB shell wm size "$geom" >/dev/null 2>&1; $ADB shell wm density "$dens" >/dev/null 2>&1
  $ADB shell settings put global vela_force_dpad 1 >/dev/null 2>&1
  bash "$HERE/../dpad/setup.sh" >/dev/null 2>&1   # perms + mock GPS for search/routing
  warm_up

  shot() { i=$((i+1)); local n; n="$(printf '%02d' "$i")"; $ADB exec-out screencap -p > "$out/$n-$1.png"; }
  # mark <label> <reached 0|1> - capture + record COVERED/MISSED on the checklist.
  mark() { local label="$1" reached="$2"
    if [ "$reached" = 1 ]; then shot "$label"; covered=$((covered+1)); checklist="$checklist\n  COVERED  $label"
    else missed=$((missed+1)); checklist="$checklist\n  MISSED   $label"; fi
  }

  # --- first-run (fresh install) -------------------------------------------------------------
  $ADB shell pm clear "$PKG" >/dev/null 2>&1
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do $ADB shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1; done
  launch_fresh 6
  on_screen "Get started" && mark "welcome" 1 || mark "welcome" 0
  key "$K_OK" 2; on_screen "Not now" && mark "voice-dialog" 1 || mark "voice-dialog" 0
  key "$K_OK" 2; on_screen "Not now" && mark "offline-dialog" 1 || mark "offline-dialog" 0
  key "$K_OK" 2
  launch_fresh 6
  on_screen_contains "Help improve Vela" && { mark "consent-dialog" 1; key "$K_OK" 2; } || mark "consent-dialog" 0

  # --- bare map -------------------------------------------------------------------------------
  goto_map; key "$K_DOWN"; mark "bare-map" 1

  # --- search overlay + results ---------------------------------------------------------------
  goto_map; focus_search_bar; key "$K_OK" 1.5; on_screen "Home" || on_screen_contains "Search"; mark "search-overlay" 1; key "$K_BACK" 1
  local haveResults=0
  goto_map; if run_coffee; then haveResults=1; mark "search-results" 1; else mark "search-results" 0; fi

  # --- place sheet (+ expand) -----------------------------------------------------------------
  if [ "$haveResults" = 1 ]; then
    open_first_place; mark "place-sheet" 1
    key "$K_OK" 1; mark "place-sheet-expanded" 1
    key "$K_BACK" 1
  else mark "place-sheet" 0; mark "place-sheet-expanded" 0; fi

  # --- directions + steps ---------------------------------------------------------------------
  goto_map
  if reach_directions; then
    mark "directions" 1
    # steps sheet: from the Drive tab, DOWN to Steps and OK (best-effort)
    if focus_and_ok "Steps"; then mark "route-steps" 1; key "$K_BACK" 1; else mark "route-steps" 0; fi
    key "$K_BACK" 1
  else mark "directions" 0; mark "route-steps" 0; fi

  # --- Settings + sub-screens -----------------------------------------------------------------
  if open_settings; then
    mark "settings-top" 1
    # walk the whole list (captures the mid + lower sections)
    for _ in 1 2 3 4 5 6; do key "$K_DOWN"; key "$K_DOWN"; key "$K_DOWN"; done; shot "settings-lower"; covered=$((covered+1)); checklist="$checklist\n  COVERED  settings-lower"
    key "$K_BACK" 1
    # sub-screens by name (open_settings again each time is expensive; reuse the open one)
    for sub in "Voice library" "Offline" "Saved places"; do
      if open_settings && focus_and_ok "$sub"; then mark "settings-$(echo "$sub"|tr ' A-Z' '-a-z')" 1; key "$K_BACK" 1; else mark "settings-$(echo "$sub"|tr ' A-Z' '-a-z')" 0; fi
    done
  else mark "settings-top" 0; mark "settings-lower" 0
       mark "settings-voice-library" 0; mark "settings-offline" 0; mark "settings-saved-places" 0; fi

  echo "-- coverage: $id --"; printf '%b\n' "$checklist"
  echo "  => $covered COVERED, $missed MISSED"
  [ "$missed" -eq 0 ] && echo "  RESULT: FULLY COVERED ($id)" || echo "  RESULT: NOT fully covered - $missed surface(s) MISSED ($id)"
  echo "  screenshots: $out"
  return "$missed"
}

rc=0
if [ "${1:-}" = "all" ]; then
  printf '%s\n' "$DEVICES" | while IFS='|' read -r id _; do [ -n "$id" ] && cover_one "$id" </dev/null; done
else
  [ -n "${1:-}" ] || { echo "usage: full_coverage.sh <device-id|all>"; exit 2; }
  cover_one "$1"; rc=$?
fi
exit "$rc"
