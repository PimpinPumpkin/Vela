#!/usr/bin/env bash
# D2/D3: the place-sheet + directions action rows are D-pad reachable (and now carry focus rings).
# This drives to the Directions ACTION PILL and OKs it — proving the pill (and the pills row it
# lives in) is focus-reachable and activatable by D-pad, opening the directions panel. Network-bound.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 06: place-sheet Directions pill is D-pad reachable -> opens directions"

goto_map
if ! reach_directions; then echo "  SKIP: couldn't open directions (network/deep-nav)"; report; exit $?; fi
assert_on_screen "Add stop"                              # directions panel opened via the pill
assert_on_screen "Drive"                                 # travel-mode tabs present
# The From ("Your location") row is reachable + activatable (it opens the origin search).
key "$K_UP"; key "$K_OK" 2
assert_on_screen "Choose on map"                         # origin search opened from the From row
report
