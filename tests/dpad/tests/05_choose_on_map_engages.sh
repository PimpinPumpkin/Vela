#!/usr/bin/env bash
# D1 regression (2026-07-08): "Choose on map" must open ALREADY drivable - the centre map target
# auto-focused AND the map auto-engaged, so arrows pan immediately to place the pin. The bare-map
# change removed the global auto-engage; this is the pick-mode-scoped restoration. Before the fix,
# pick mode opened with nothing focused, not engaged, and no visible affordance - undrivable.
# Deep + network-bound; self-skips if it can't reach pick mode. docs/dpad.md.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 05: Choose-on-map opens with the map auto-focused + engaged"

goto_map
if ! open_choose_on_map; then echo "  SKIP: couldn't reach pick mode (network/deep-nav)"; report; exit $?; fi
assert_on_screen_contains "Move the map"                 # the pick overlay banner ("...to set the start/stop")
assert_focus_ytop_between 190 260 "centre map target auto-focused in pick mode"
# Engaged proof: with the map engaged, arrows PAN (the target keeps focus); if it were NOT engaged,
# an arrow would traverse focus away to another control. So after arrowing, the target is still it.
key "$K_DOWN"; key "$K_RIGHT"
assert_focus_ytop_between 190 260 "map target still focused after arrows (engaged, panned - not traversed)"
key "$K_BACK" 1
assert_not_on_screen_contains "Move the map"             # BACK cleanly cancels the pick
report
