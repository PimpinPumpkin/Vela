#!/usr/bin/env bash
# dpad_test_suite/run_all.sh — run the whole D-pad suite against a connected device and report.
# Each test exits 0 (all its checks passed) or 1 (something failed); this tallies suite results.
#
#   ./run_all.sh                 # run everything
#   ./run_all.sh 01 02           # run only tests whose filename starts with 01 / 02
#   ADB="adb -s emulator-5554" ./run_all.sh
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$D/lib.sh"

if ! $ADB get-state >/dev/null 2>&1; then echo "No device via '$ADB'. Connect one or set ADB='adb -s <serial>'."; exit 2; fi
if ! $ADB shell pm path "$PKG" >/dev/null 2>&1; then echo "App '$PKG' is not installed. Build+install a release APK first."; exit 2; fi

echo "Device: $($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')  |  app: $PKG"
bash "$D/setup.sh"

# select tests (optional prefixes as args)
mapfile -t TESTS < <(ls "$D"/tests/*.sh | sort)
if [ "$#" -gt 0 ]; then
  sel=(); for f in "${TESTS[@]}"; do for pre in "$@"; do [[ "$(basename "$f")" == "$pre"* ]] && sel+=("$f"); done; done
  TESTS=("${sel[@]}")
fi

NP=0; NF=0; FAILED=()
for t in "${TESTS[@]}"; do
  echo; echo "=== $(basename "$t") ==="
  bash "$t"; rc=$?
  if [ "$rc" -eq 0 ]; then echo "  => SUITE PASSED"; NP=$((NP + 1)); else echo "  => SUITE FAILED"; NF=$((NF + 1)); FAILED+=("$(basename "$t")"); fi
done

echo; echo "==========================================="
echo "SUITES: $NP passed, $NF failed"
[ "${#FAILED[@]}" -gt 0 ] && echo "Failed: ${FAILED[*]}"
[ "$NF" -eq 0 ]
