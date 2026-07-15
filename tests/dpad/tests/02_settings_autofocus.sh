#!/usr/bin/env bash
# Settings must open ALREADY focused (on the back button, top of screen) - the original bug was it
# opened with nothing focused, wasting the first keypress (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 02: Settings opens focused on the back button"

goto_map
focus_search_bar          # DOWN -> search bar
key "$K_RIGHT"            # -> settings gear (right end of the bar)
key "$K_OK" 1.5          # open Settings
assert_on_screen "Appearance"                        # we're in Settings
assert_focus_ytop_between 30 130 "Settings back button (top-left)"
report
