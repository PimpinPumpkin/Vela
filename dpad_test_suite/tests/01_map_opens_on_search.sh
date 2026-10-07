#!/usr/bin/env bash
# The bare map never opens engaged: either nothing is focused (the display was in touch mode) or
# the search bar is (key mode), and focus on the search bar is one press away at most. A map that
# opened engaged would need BACK before any arrow could reach the bar (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 01: bare map opens unengaged, search bar within one press"

goto_map
assert_not_on_screen_contains "OK: move the map"   # the pill the map target shows while it has focus
focus_search_bar
assert_focus_ytop_pct 0 15 "search bar (top of screen)"
report
