#!/usr/bin/env bash
# tests/dpad/nav.sh - Vela-specific D-pad navigation helpers, sourced by tests.
# All movement is D-pad-only (the point of the suite). Kept separate from lib.sh (generic).

# dismiss_onboarding - OK through the one-time onboarding dialogs. Each VelaDialog auto-focuses its
# "Not now" button, so a single OK dismisses it; repeat for the chain (voice / offline / …).
dismiss_onboarding() {
  # POLL with settles - don't break on the first absence. The diagnostics consent card ("Help
  # improve Vela?") and some prompts appear a BEAT LATE (SPA-style), so breaking the instant
  # "Not now" isn't yet on screen let a late card slip through and block later navigation (the
  # "could not open Settings" false-fail). Keep OK-ing "Not now" whenever it shows; stop only
  # after two consecutive settled misses.
  local misses=0
  for _ in $(seq 1 8); do
    if on_screen "Not now"; then key "$K_OK" 1.2; misses=0
    else misses=$((misses + 1)); [ "$misses" -ge 2 ] && break; sleep 0.6; fi
  done
}

# goto_map - cold launch to the bare map (onboarding dismissed). Leaves nothing focused (by design).
# Robust to first-run: if the Welcome screen shows (e.g. a prior test cleared data), its auto-focused
# Get-started advances past it, then the onboarding dialogs are dismissed.
goto_map() {
  launch_fresh 3.5
  if on_screen "Get started"; then key "$K_OK" 2; fi
  dismiss_onboarding
}

# focus_search_bar - from the bare map (nothing focused), the first DOWN lands on the search bar.
focus_search_bar() { key "$K_DOWN"; }

# open_settings - robustly open Settings from anywhere. The first DOWN can land on the search FIELD or
# the gear depending on focus order (and adaptive density shifts it), and RIGHT-from-field only reaches
# the gear when the field held focus - so a single "DOWN,RIGHT,OK" flakily opened the SEARCH overlay
# instead (the "Settings unreachable" false-fail). Press RIGHT twice (reaches the rightmost gear from
# either start), confirm "Appearance", and retry the whole nav (backing out of the overlay) if not.
open_settings() {
  local a b y1
  for a in 1 2 3 4; do
    goto_map
    key "$K_DOWN"
    # The first arrow can land in the top search row OR (when ambient POI markers are present) on a
    # map marker. If we're in the MAP (focused y1 well below the top bar), go UP to the search row.
    b="$(focused_bounds)"; y1="$(echo "$b" | sed -E 's/^\[[0-9]+,([0-9]+)\].*/\1/')"
    [ -n "$y1" ] && [ "$y1" -gt 100 ] 2>/dev/null && key "$K_UP"
    key "$K_RIGHT"; key "$K_RIGHT"          # -> rightmost of the search row = the gear (opens Settings)
    key "$K_OK" 1.5
    for _ in 1 2 3; do on_screen "Appearance" && return 0; sleep 0.5; done
    key "$K_BACK" 1                          # opened the search overlay instead? back out, retry
  done
  return 1
}

# run_coffee - from the bare map, run the "Coffee" category chip search; waits for results.
# Leaves focus in the results list. Returns 0 if "N results" appeared.
run_coffee() {
  focus_search_bar          # search bar
  key "$K_DOWN"             # -> category chips row
  # land on a chip, then OK. The Coffee chip is the middle one; RIGHT once from the first.
  key "$K_OK" 5            # OK the focused chip -> search
  for _ in 1 2 3 4 5 6; do
    ui_dump
    if $ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -qE 'text="[0-9]+ results"'; then return 0; fi
    sleep 1
  done
  return 1
}

# open_first_place - from the results list, open the first result's place sheet (handle focused).
open_first_place() {
  keys "$K_DOWN" "$K_DOWN" "$K_DOWN"   # search field -> filter chips -> first result row
  key "$K_OK" 2.5                       # open place sheet
}

# reach_directions - from a bare map, open the first Coffee result and its Directions panel (Drive
# tab focused). Returns non-zero if results never loaded. Leaves the directions panel open.
reach_directions() {
  run_coffee || return 1
  open_first_place
  key "$K_OK" 1                          # expand the sheet so the action pills are on screen
  keys "$K_DOWN" "$K_DOWN" "$K_DOWN"     # -> the action-pills row (lands on Call, the middle pill)
  key "$K_LEFT"                          # -> Directions (the leftmost, emphasised pill)
  key "$K_OK" 5                         # open the directions panel
  on_screen "Add stop"                   # a directions-panel-only row
}

# open_choose_on_map - drill all the way to the Choose-on-map pick overlay (edit the origin ->
# "Choose on map"). Returns 0 iff the "Move the map" pick overlay is showing. Deep + network-bound.
open_choose_on_map() {
  reach_directions || return 1
  key "$K_UP"                            # Drive tab -> the "Your location" (From) row
  key "$K_OK" 2                         # open the origin search overlay (pickingOrigin)
  on_screen "Choose on map" || return 1
  focus_and_ok "Choose on map" || return 1
  sleep 1
  on_screen_contains "Move the map"      # banner: "Move the map to set the start/stop" (substring)
}
