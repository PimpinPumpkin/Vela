#!/usr/bin/env bash
# dpad_test_suite/nav.sh — Vela-specific D-pad navigation helpers, sourced by tests.
# All movement is D-pad-only (the point of the suite). Kept separate from lib.sh (generic).

# dismiss_onboarding — OK through the one-time onboarding dialogs. Each VelaDialog auto-focuses its
# "Not now" button, so a single OK dismisses it; repeat for the chain (voice / offline / …).
dismiss_onboarding() {
  for _ in 1 2 3 4 5; do
    if on_screen "Not now"; then key "$K_OK" 1.2; else break; fi
  done
}

# goto_map — cold launch to the bare map (onboarding dismissed). Leaves nothing focused (by design).
# Robust to first-run: if the Welcome screen shows (e.g. a prior test cleared data), its auto-focused
# Get-started advances past it, then the onboarding dialogs are dismissed.
goto_map() {
  launch_fresh 3.5
  if on_screen "Get started"; then key "$K_OK" 2; fi
  dismiss_onboarding
}

# focus_search_bar — from the bare map (nothing focused), the first DOWN lands on the search bar.
focus_search_bar() { key "$K_DOWN"; }

# run_coffee — from the bare map, run the "Coffee" category chip search; waits for results.
# Leaves focus in the results list. Returns 0 if "N results" appeared.
run_coffee() {
  focus_search_bar          # search bar
  key "$K_DOWN"             # -> category chips row
  # land on a chip, then OK. The Coffee chip is the middle one; RIGHT once from the first.
  key "$K_OK" 5            # OK the focused chip -> search
  for _ in 1 2 3 4 5 6; do
    if $ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -q 'results"'; then :; fi
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if $ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -qE 'text="[0-9]+ results"'; then return 0; fi
    sleep 1
  done
  return 1
}

# open_first_place — from the results list, open the first result's place sheet (handle focused).
open_first_place() {
  keys "$K_DOWN" "$K_DOWN" "$K_DOWN"   # search field -> filter chips -> first result row
  key "$K_OK" 2.5                       # open place sheet
}
