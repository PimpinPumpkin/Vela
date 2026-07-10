#!/usr/bin/env bash
# tests/dpad/teardown.sh - undo the test overrides setup.sh applied, returning the device to
# normal: clears the force-D-pad flag and resets any screen size/density change. Run after a suite
# session (run_all.sh leaves the flag set so consecutive audits share it).
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
$ADB shell settings delete global vela_force_dpad >/dev/null 2>&1
$ADB shell wm size reset >/dev/null 2>&1
$ADB shell wm density reset >/dev/null 2>&1
echo "teardown: cleared vela_force_dpad, reset screen size/density"
