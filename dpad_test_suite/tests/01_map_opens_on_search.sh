#!/usr/bin/env bash
# The bare map is the one intentionally-unfocused screen: it opens ambient (nothing focused, map
# NOT engaged), and the user's first arrow lands on the search bar — never an engaged map that
# needs BACK to leave (docs/dpad.md, 2026-07-08).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 01: bare map opens ambient, first DOWN -> search bar"

goto_map
assert_nothing_focused "bare map on open (map is ambient, not engaged)"
focus_search_bar                                   # first DOWN
assert_focus_ytop_between 30 140 "search bar (top of screen)"
report
