#!/usr/bin/env bash
# The place sheet opens focused on its drag handle, and its overflow (⋮) menu — a VelaMenu, which
# renders a raw-Dialog chooser under D-pad — opens focused on its first item ("Set as Home"). This
# is the deep-navigation test; it needs a live network for the Coffee search, so if results don't
# load it reports what it could and skips the rest. docs/dpad.md.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 04: place sheet handle + overflow-menu auto-focus (deep nav)"

goto_map
if ! run_coffee; then echo "  SKIP: Coffee results did not load (network?) — nothing to assert"; report; exit $?; fi
open_first_place
assert_focus_ytop_between 270 360 "place sheet drag handle"

# Navigate to the ⋮ (More options) and open the menu:
#   handle -> DOWN (photo) -> DOWN (action row, lands on Save) -> RIGHT RIGHT (More) -> OK
keys "$K_DOWN" "$K_DOWN"
keys "$K_RIGHT" "$K_RIGHT"
key "$K_OK" 1.5
assert_focus_text "Set as Home"                    # VelaMenu chooser auto-focused item 0
key "$K_DOWN"; assert_focus_text "Set as Work"     # DOWN walks the chooser
key "$K_BACK" 1; assert_not_on_screen "Set as Work" # BACK closes the menu (not the sheet)
report
