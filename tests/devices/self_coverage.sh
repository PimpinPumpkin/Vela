#!/usr/bin/env bash
# tests/devices/self_coverage.sh - the IN-PROCESS self-coverage tour (SelfTourTest), per device.
#
#   bash tests/devices/self_coverage.sh <device-id>          # e.g. kyocera-e4810 / sonim-x320
#   bash tests/devices/self_coverage.sh all
#   VELA_PKG=app.vela.restricted.debug ... self_coverage.sh <id>   # run against another flavor
#
# ~10x faster than full_coverage.sh for the surfaces it covers (36s vs ~6min at 240x320) and
# STRICTER: real focus-state assertions per D-pad step, exact pixel-bounds clip checks, direct
# flavor assertions - while keeping the SAME sources of truth (accessibility tree + real
# framebuffer stills + real-dispatcher input, on the R8-minified build). scrcpy records the whole
# run; stills land in <id>/screenshots/selftour[-restricted]/ for the MANDATORY eyeball pass.
#
# HARD RULES (AGENTS.md): every geometry, every time; the existing gates (audit_static,
# audit_dynamic, small-screen matrix, full_coverage) remain MANDATORY until a surface is covered
# here strictly better - this suite AUGMENTS, it does not replace yet.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-adb}"
PKG="${VELA_PKG:-app.vela.debug}"
DEVICES="
kyocera-e4810|240x320|160
sonim-x320|480x854|320
"
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

run_one() {
  local id="$1" geom dens out rec scrpid rc
  geom="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $2}')"
  dens="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $3}')"
  [ -z "$geom" ] && { echo "unknown device '$id'"; return 1; }
  case "$PKG" in *restricted*) out="$HERE/$id/screenshots/selftour-restricted" ;; *) out="$HERE/$id/screenshots/selftour" ;; esac
  mkdir -p "$out"; rm -f "$out"/*.png
  echo "######## SELF-COVERAGE: $id ($geom @ ${dens}dpi, $PKG) ########"
  $ADB shell wm size "$geom" >/dev/null; $ADB shell wm density "$dens" >/dev/null
  # continuous pixel record for the whole run (kept beside the stills; ~1-2 MB/min)
  rec="$out/run-recording.mkv"; rm -f "$rec"
  scrcpy --no-display --record "$rec" --max-fps 10 --bit-rate 2M >/dev/null 2>&1 & scrpid=$!
  # Mock GPS is a RUN PRECONDITION (content surfaces hard-fail without a fix): arm it per leg -
  # relying on an earlier session's provider state produced false-green runs whose content
  # surfaces silently never executed (caught the day skips became failures).
  bash "$HERE/../dpad/setup.sh" >/dev/null 2>&1
  $ADB shell pm clear "$PKG" >/dev/null
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    $ADB shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
  done
  local T0=$(date +%s)
  $ADB shell am instrument -w -e class app.vela.tour.SelfTourTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 \
    | grep -E "OK \(|FAILURES|Tests run|Error in|junit|AssertionError|Exception" | sed 's/^/  /'
  rc=$?
  echo "  elapsed: $(( $(date +%s) - T0 ))s"
  # pull the stills BEFORE anything else clears app storage (a later pm clear wipes them)
  $ADB pull "/sdcard/Android/data/$PKG/files/selftour/" "$out/_pull" >/dev/null 2>&1 \
    && mv "$out"/_pull/selftour/*.png "$out"/ 2>/dev/null; rm -rf "$out/_pull"
  kill "$scrpid" 2>/dev/null; wait "$scrpid" 2>/dev/null
  echo "  stills + recording: $out"
  return $rc
}

rc=0
if [ "${1:-}" = "all" ]; then
  printf '%s\n' "$DEVICES" | while IFS='|' read -r id _; do [ -n "$id" ] && run_one "$id" </dev/null; done
else
  [ -n "${1:-}" ] || { echo "usage: self_coverage.sh <device-id|all>"; exit 2; }
  run_one "$1"; rc=$?
fi
exit "$rc"
