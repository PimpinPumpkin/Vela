#!/usr/bin/env bash
# tests/small_screen/run_matrix.sh - run the small-screen auditor at EVERY target device geometry.
#
# The auditor tests one size per run; real feature-phone support means it must hold at EACH target
# density level, not just the default. This sweeps them and prints a per-geometry PASS/FAIL summary.
# Keep the geometry list in sync with tests/devices/README.md (WxH|density|label).
GEOMS="
240x320|160|240x320 portrait (Kyocera e4810 / TCL Flip 2 / Sonim XP3)
480x854|320|480x854 portrait (Sonim X320 - high-res, ~240dp logical)
"
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fails=0; results=""
while IFS='|' read -r size dens label; do
  [ -z "$size" ] && continue
  echo "############################################################"
  echo "# $label  ($size @ ${dens}dpi)"
  echo "############################################################"
  # </dev/null: the auditor (via adb) would otherwise consume this while-loop's heredoc stdin and the
  # sweep would run only the FIRST geometry (classic while-read stdin-eating bug).
  if VELA_SMALL="$size" VELA_SMALL_DPI="$dens" bash "$HERE/audit_smallscreen.sh" </dev/null; then
    results="$results\n  PASS  $label ($size @ ${dens}dpi)"
  else
    results="$results\n  FAIL  $label ($size @ ${dens}dpi)"; fails=$((fails+1))
  fi
done <<EOF
$GEOMS
EOF
echo "============================================================"
echo "SMALL-SCREEN MATRIX:"; printf '%b\n' "$results"
echo "============================================================"
[ "$fails" -eq 0 ] && echo "ALL GEOMETRIES PASS" || echo "$fails GEOMETRY/IES FAILED"
[ "$fails" -eq 0 ]
