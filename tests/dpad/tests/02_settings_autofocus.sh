#!/usr/bin/env bash
# Settings must open ALREADY focused (on the back button, top of screen) - the original bug was it
# opened with nothing focused, wasting the first keypress (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 02: Settings opens focused on the back button"

goto_map
# PURE KEY navigation on purpose - nav.sh's open_settings taps the gear by content-desc, and a
# TOUCH open must NOT auto-focus (touch UX stays byte-identical), so it cannot test this. The
# search bar is field, MIC, gear: two RIGHTs reach the gear from the field. If the first DOWN
# landed on the gear already (density-dependent), the second RIGHT is a no-op at the row end -
# either way OK opens Settings. Retry once via the mic-less count if Appearance did not show.
focus_search_bar          # DOWN -> search bar
key "$K_RIGHT"            # -> voice-search mic
key "$K_RIGHT"            # -> settings gear (rightmost)
key "$K_OK" 1.5           # open Settings
if ! on_screen "Appearance"; then
  key "$K_BACK" 1.5       # back out of whatever opened (voice dialog / search overlay)
  goto_map
  focus_search_bar
  key "$K_RIGHT"
  key "$K_OK" 1.5
fi
assert_on_screen "Appearance"                        # we're in Settings
assert_focus_ytop_between 30 130 "Settings back button (top-left)"
report
