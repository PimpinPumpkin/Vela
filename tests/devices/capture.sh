#!/usr/bin/env bash
# tests/devices/capture.sh - AUTOMATED visual-proof capture for a target feature phone.
#
# "Verify visually" is a hard rule (AGENTS.md), and doing it by hand for every surface x every device
# is slow and unrepeatable. This drives the app through the key surfaces at a device's REAL geometry
# and saves labeled screenshots into tests/devices/<id>/screenshots/auto/ - so the visual evidence is
# one command, reproducible, and reviewable in the PR. It complements the auditors (which assert focus
# + no-clipping); this produces the pixels a human signs off on.
#
#   bash tests/devices/capture.sh <device-id>          # fresh first-run + core surfaces
#   bash tests/devices/capture.sh <device-id> --warm   # skip pm clear (already past onboarding)
#   bash tests/devices/capture.sh all                  # every device in the matrix
#
# Device geometry table - keep in sync with tests/devices/README.md.  id | WxH | density
DEVICES="
kyocera-e4810|240x320|160
tcl-flip-2|240x320|160
sonim-xp3|240x320|160
sonim-x320|480x854|320
"
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../dpad/lib.sh"; source "$HERE/../dpad/nav.sh"
[ "$(command -v adb)" ] || { echo "adb not found"; exit 2; }
$ADB get-state >/dev/null 2>&1 || { echo "No device."; exit 2; }

restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

capture_one() {
  local id="$1" warm="${2:-}" geom dens WxH out i=0
  geom="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $2}')"
  dens="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $3}')"
  [ -z "$geom" ] && { echo "unknown device '$id' (see the table in this script)"; return 1; }
  out="$HERE/$id/screenshots/auto"; mkdir -p "$out"; rm -f "$out"/*.png
  echo "== $id : $geom @ ${dens}dpi -> $out =="
  $ADB shell wm size "$geom"   >/dev/null 2>&1
  $ADB shell wm density "$dens" >/dev/null 2>&1
  $ADB shell settings put global vela_force_dpad 1 >/dev/null 2>&1

  # shot <label> - numbered, labeled screenshot into the device's auto/ dir.
  shot() { i=$((i+1)); local n; n="$(printf '%02d' "$i")"; $ADB exec-out screencap -p > "$out/$n-$1.png"; echo "  $n-$1.png"; }

  if [ "$warm" != "--warm" ]; then
    $ADB shell pm clear "$PKG" >/dev/null 2>&1
    for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
      $ADB shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
    done
    launch_fresh 6
    on_screen "Get started" && shot "firstrun-welcome"
    key "$K_OK" 2; on_screen "Not now" && shot "firstrun-voice-dialog"      # voice prompt
    key "$K_OK" 2; on_screen "Not now" && shot "firstrun-offline-dialog"    # offline prompt
    key "$K_OK" 2; shot "firstrun-landed-map"                               # bare map
  else
    launch_fresh 6
  fi

  # 2nd launch: diagnostics consent (launches >= 2), then dismiss it. (contains-match: the title is
  # "Help improve Vela?" - an exact on_screen would miss the trailing '?').
  launch_fresh 6
  on_screen_contains "Help improve Vela" && { shot "consent-dialog"; key "$K_OK" 2; }
  goto_map; key "$K_DOWN"; shot "bare-map"

  # search overlay
  goto_map; focus_search_bar; key "$K_OK" 1.5; shot "search-overlay"; key "$K_BACK" 1

  # Settings (opens focused; DOWN enters the content list)
  if open_settings; then shot "settings-open"; key "$K_DOWN" 0.8; shot "settings-down-navigates"; key "$K_BACK" 1
  else echo "  (Settings: harness could not script-navigate the gear; capture it by hand)"; fi
  echo "  done: $id"
}

if [ "${1:-}" = "all" ]; then
  printf '%s\n' "$DEVICES" | while IFS='|' read -r id _; do [ -n "$id" ] && capture_one "$id" "${2:-}"; done
else
  [ -n "${1:-}" ] || { echo "usage: capture.sh <device-id|all> [--warm]"; exit 2; }
  capture_one "$1" "${2:-}"
fi
