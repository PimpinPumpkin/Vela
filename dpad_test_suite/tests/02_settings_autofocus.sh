#!/usr/bin/env bash
# Settings opens with its back button focused. It used to open with nothing focused, which wasted
# the first key press (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 02: Settings opens focused on the back button"

goto_map
open_settings || fail "could not reach the Settings button with the arrows"
assert_on_screen "Appearance"                        # we're in Settings
assert_focus_ytop_pct 0 15 "Settings back button (top-left)"
report
