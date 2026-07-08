#!/usr/bin/env bash
# smallscreen_test_suite/run_all.sh — run the whole small-screen compatibility suite.
# Shrinks the display to a feature-phone size and checks that every surface and every dialog shows
# all its content/controls without clipping anything off-screen, driven by D-pad only. Restores the
# real screen on exit. Reuses the device helpers in ../dpad_test_suite (one source of truth).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A=0; B=0
echo "########## SMALL-SCREEN SURFACE AUDIT ##########"; bash "$D/audit_smallscreen.sh" || A=1
echo; echo "########## SMALL-SCREEN DIALOG AUDIT ##########"; bash "$D/audit_dialogs.sh" || B=1
echo; echo "==========================================="
if [ "$A" -eq 0 ] && [ "$B" -eq 0 ]; then echo "SMALL-SCREEN SUITE: PASS"; else echo "SMALL-SCREEN SUITE: FAILURES"; fi
[ "$A" -eq 0 ] && [ "$B" -eq 0 ]
