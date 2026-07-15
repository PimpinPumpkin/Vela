#!/usr/bin/env bash
# First-run flow, D-pad-first:
#   (a) the Welcome screen opens focused on Get-started, and OK advances;
#   (b) each onboarding dialog (VelaDialog) opens focused on its safe "Not now" button.
# Clears app data to force the first run, then re-grants location so no system permission dialog
# interrupts (that dialog is AOSP, out of scope). docs/dpad.md.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 03: first-run Welcome + onboarding dialogs auto-focus"

$ADB shell pm clear "$PKG" >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION   >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
launch_fresh 3.5

# (a) Welcome - Get-started (a filled button at the bottom) is focused on open.
assert_on_screen "Get started"
assert_focus_ytop_between 400 660 "Welcome Get-started button (bottom)"
key "$K_OK" 2                                    # advance past Welcome

# (b) first onboarding dialog - its dismiss button is focused.
assert_on_screen "Not now"
assert_focus_text "Not now"                       # dialog auto-focused the safe side
key "$K_OK" 1.2                                   # dismiss -> next dialog (if any)
report
