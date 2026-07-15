#!/usr/bin/env bash
# tests/dpad/lib.sh - shared helpers for reproducible on-device D-pad tests.
#
# Every test drives the app with ONLY a 5-key D-pad (via `adb shell input keyevent`) and asserts
# on the focused element (read from `uiautomator dump`). This is the scripted, repeatable version
# of the manual adb checks used while building the D-pad support (docs/dpad.md).
#
# Requires: a connected device/emulator (adb), python3 on the host, and the app installed.
# Override the device with `ADB="adb -s <serial>"`.
set -uo pipefail

ADB="${ADB:-adb}"
# Package under test. AUTO-DETECT the installed build so the suite works whether you sideloaded the
# RELEASE (app.vela) or the DEBUG (app.vela.debug - applicationIdSuffix) APK; prefer .debug when both
# are present (the dev workflow). Override with VELA_PKG. This mismatch was a silent, load-bearing bug:
# a hardcoded app.vela made launch_fresh force-stop/monkey a NON-EXISTENT package (no-op), so Vela
# never launched and the auditor drove whatever app was already foreground.
PKG="${VELA_PKG:-$($ADB shell pm list packages 2>/dev/null | grep -oE 'app\.vela(\.restricted)?(\.debug)?$' | sort | tail -1)}"
PKG="${PKG:-app.vela}"

# ---- D-pad keycodes -------------------------------------------------------------------------
K_UP=19; K_DOWN=20; K_LEFT=21; K_RIGHT=22; K_OK=23; K_BACK=4; K_HOME=3

# key <code> [settle_seconds]  - press one key and wait for the UI to settle.
key() { $ADB shell input keyevent "$1" >/dev/null 2>&1; sleep "${2:-0.4}"; }
# keys <code>...  - press several keys in sequence (default settle each).
keys() { for c in "$@"; do key "$c"; done; }

# launch_fresh [settle_seconds]  - force-stop + cold launch.
launch_fresh() {
  # VERIFY the app actually reaches the foreground and RETRY if not. A BACK press during a traversal
  # exits Vela to the home screen; a stray key there can launch a NEIGHBOURING app, and a single
  # monkey launch doesn't always re-take foreground (device-seen: the auditor ended up driving another
  # app's About screen). Confirm PKG is the resumed activity before returning.
  local i
  for i in 1 2 3; do
    $ADB shell am force-stop "$PKG" >/dev/null 2>&1
    sleep 1
    $ADB shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep "${1:-3.5}"
    $ADB shell dumpsys activity activities 2>/dev/null | grep -q "ResumedActivity.*$PKG/" && return 0
  done
  return 0   # give up gracefully; the surface checks will fail loudly if it truly never launched
}

# warm_up - a throwaway launch to clear COLD-START (dexopt, first-frame, initial map-tile load) before
# the first real surface test, so it isn't racing a cold app - device-seen: right after install the
# first surface flaked until the app warmed. Cheap insurance at the top of an auditor run.
warm_up() { launch_fresh 6 >/dev/null 2>&1; $ADB shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1; }

# ---- focus inspection -----------------------------------------------------------------------
# ui_dump  - dump the current UI to /sdcard/ui.xml, RETRYING when it comes back implausibly small.
# uiautomator intermittently returns ONLY the root node (nodeCount ~1) when it races an IME / scroll
# / transition animation - device-verified on the search overlay (1 node mid-transition, 30 once
# settled). A single dump therefore causes phantom "no focus" / "text not found" false-fails. Retry
# until the tree is real (>= MIN nodes) or attempts run out. Every dump-based check goes through this.
ui_dump() {
  local i cnt
  for i in 1 2 3 4 5 6; do
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    cnt="$($ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -oE '<node' | wc -l | tr -d ' ')"
    [ "${cnt:-0}" -ge 3 ] && return 0
    sleep 0.4
  done
  return 0   # give up gracefully; the caller treats an empty/thin tree as no-match
}
# focused  - prints "bounds|text|desc" of the currently-focused node, or empty if none.
focused() {
  ui_dump
  $ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re
d = sys.stdin.read()
for m in re.finditer(r"<node [^>]*focused=\"true\"[^>]*>", d):
    s = m.group(0)
    b = re.search(r"bounds=\"([^\"]*)\"", s)
    t = re.search(r"text=\"([^\"]*)\"", s)
    c = re.search(r"content-desc=\"([^\"]*)\"", s)
    print((b.group(1) if b else "") + "|" + (t.group(1) if t else "") + "|" + (c.group(1) if c else ""))
    break
'
}
# focused_stable  - 0 (true) if something is focused, RE-CHECKING once after a short settle. A
# transient null sampled mid-scroll-animation (the focused row briefly off-screen) is not a real
# focus loss; a genuine focus clear persists. Use this in traversal integrity, not raw `focused`.
focused_stable() {
  [ -n "$(focused)" ] && return 0
  local t
  # A null sample during a fast traversal is almost always a dump racing a UI update / scroll
  # animation; re-check with growing settles. Only a null that survives ~1.5s is a real focus loss.
  for t in 0.3 0.5 0.7; do sleep "$t"; [ -n "$(focused)" ] && return 0; done
  return 1
}
focused_bounds() { focused | cut -d"|" -f1; }
focused_text()   { focused | cut -d"|" -f2; }
focused_desc()   { focused | cut -d"|" -f3; }
# focus_ytop  - the top Y of the focused node ([x1,y1][x2,y2] -> y1), or -1 if nothing focused.
focus_ytop() {
  local b; b="$(focused_bounds)"
  [ -z "$b" ] && { echo -1; return; }
  echo "$b" | sed -E 's/^\[[0-9]+,([0-9]+)\].*/\1/'
}

# find_text <exact>  - bounds of the first node whose text== <exact> (empty if not found).
find_text() {
  ui_dump
  $ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re
d = sys.stdin.read(); want = sys.argv[1]
for m in re.finditer(r"<node [^>]*>", d):
    s = m.group(0); t = re.search(r"text=\"([^\"]*)\"", s); b = re.search(r"bounds=\"([^\"]*)\"", s)
    if t and t.group(1) == want:
        print(b.group(1) if b else ""); break
' "$1"
}
# on_screen <exact>  - 0 (true) if a node with that exact text exists.
on_screen() { [ -n "$(find_text "$1")" ]; }
# find_text_contains <substr>  - bounds of the first node whose text CONTAINS <substr>.
find_text_contains() {
  ui_dump
  $ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re
d = sys.stdin.read(); want = sys.argv[1]
for m in re.finditer(r"<node [^>]*>", d):
    s = m.group(0); t = re.search(r"text=\"([^\"]*)\"", s); b = re.search(r"bounds=\"([^\"]*)\"", s)
    if t and want in t.group(1):
        print(b.group(1) if b else ""); break
' "$1"
}
# on_screen_contains <substr>  - 0 (true) if any node's text contains <substr> (partial match).
on_screen_contains() { [ -n "$(find_text_contains "$1")" ]; }
# ycenter <bounds>  - the vertical centre of an [x1,y1][x2,y2] bounds string.
ycenter() { echo "$1" | sed -E 's/^\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\].*/\1 \2/' | awk '{print int(($1+$2)/2)}'; }
# focus_and_ok <exact>  - press DOWN until the focused row vertically contains the node with that
# text, then OK it. RE-FINDS the target each iteration, so it works even when the target starts
# BELOW the fold (a long Settings list scrolls it into view as we walk). Matches by position because
# the focused clickable Row often has no text of its own. Returns non-zero if unreached in 30 presses.
focus_and_ok() {
  local want="$1" i tb cy fb fy1 fy2
  for i in $(seq 1 55); do   # deep enough to reach lower Settings sections (Voice library / Offline / Saved places)
    tb="$(find_text "$want")"
    if [ -n "$tb" ]; then
      cy="$(ycenter "$tb")"
      fb="$(focused_bounds)"
      fy1="$(echo "$fb" | sed -E 's/^\[[0-9]+,([0-9]+)\].*/\1/')"
      fy2="$(echo "$fb" | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]$/\1/')"
      if [ -n "$fy1" ] && [ "$cy" -ge "$fy1" ] && [ "$cy" -le "$fy2" ] 2>/dev/null; then key "$K_OK" 2; return 0; fi
    fi
    key "$K_DOWN"
  done
  return 1
}

# scroll_to <exact>  - reach a row DEEP in a scroll list FAST: bulk-press DOWN in batches (no dump),
# checking on_screen only ONCE per batch until the text is visible. ~12 dumps instead of ~50, so a
# 30-row-deep Settings section takes ~8s not ~60s (focus_and_ok times out on those). Leaves the list
# scrolled so the row is on screen; does NOT press OK. Returns non-zero if never reached.
scroll_to() {
  local want="$1" batch i
  for batch in $(seq 1 16); do
    on_screen "$want" && return 0
    for i in 1 2 3 4; do key "$K_DOWN" 0.22; done   # bulk scroll (slow enough for focus to advance)
    sleep 0.4                                        # settle the scroll animation before the on_screen check
  done
  on_screen "$want"
}
# scroll_focus_ok <exact>  - scroll_to the row, then fine-step focus ONTO it (its centre within the
# focused bounds, same match as focus_and_ok) and OK it. For a clickable/collapsible deep row.
scroll_focus_ok() {
  local want="$1" i tb cy fb fy1 fy2
  scroll_to "$want" || return 1
  for i in $(seq 1 10); do
    tb="$(find_text "$want")"
    if [ -n "$tb" ]; then
      cy="$(ycenter "$tb")"; fb="$(focused_bounds)"
      fy1="$(echo "$fb" | sed -E 's/^\[[0-9]+,([0-9]+)\].*/\1/')"
      fy2="$(echo "$fb" | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]$/\1/')"
      if [ -n "$fy1" ] && [ "$cy" -ge "$fy1" ] && [ "$cy" -le "$fy2" ] 2>/dev/null; then key "$K_OK" 2; return 0; fi
    fi
    key "$K_DOWN"
  done
  return 1
}

# swipe_up_to <exact> [maxswipes]  - fling a scroll view UP (content moves up) until <exact> is on
# screen. FAST + reliable for sections DEEP in a very long list where per-row DOWN polling is both slow
# (a uiautomator dump is ~2.6s on a keypad phone) and fragile - a batch of DOWN presses can overshoot a
# NON-focusable section header (e.g. a plain SectionTitle) that only flashes past between checks, so the
# poll never sees it and runs to the bottom. A ~half-screen swipe moves less than one header+body block,
# so the on_screen check after each swipe can't skip it. This is a SCREENSHOT-COVERAGE reach (used by
# full_coverage.sh to frame a surface); D-pad REACHABILITY of every element is enforced separately by
# audit_dynamic.sh. Returns non-zero if unreached.
swipe_up_to() {
  # max default 72: the deepest section (Saved places) sits ~4 sections above the bottom of the LONGEST
  # (standard-flavor) Settings list, which on the smallest 240x320 geometry is ~50 short (26%) swipes
  # down - 40 undershot it. The per-swipe on_screen check makes a high max SAFE (it stops the instant the
  # text appears, so it can't overshoot); a high max only costs extra swipes when the target is genuinely
  # absent, which never happens for the known sections these calls target.
  local want="$1" max="${2:-72}" i sz w h
  sz="$($ADB shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)"
  w="${sz%x*}"; h="${sz#*x}"; : "${w:=480}"; : "${h:=800}"
  # SHORT + SLOW swipes. Short (~26% of the screen) so a section's header+body block spans SEVERAL
  # on_screen checks. SLOW (700ms = a controlled drag, NOT a 200ms fling) so it moves ~exactly the swipe
  # distance with no momentum - a fast fling coasts a whole screen past the finger lift and flings a
  # single-row section header clean past between checks (device-seen: Voice library / Saved places
  # consistently overshot with a 220ms fling; a slow drag lands on them every time).
  # x = LEFT GUTTER (~13% of width, floored at 24px), NOT centre: a centre-column drag lands on an
  # interactive widget (the Voice-search-mic Switch row / the Download button in the Search section) that
  # SWALLOWS the drag, stalling the list mid-scroll so deep sections (Offline / Saved places) never come
  # into view - device-reproduced on standard @240x320: 40 centre swipes stuck at Search, one gutter
  # swipe reached Saved places. The gutter holds only left-aligned label text, which never eats a drag; a
  # pure-vertical swipe there can't trigger the horizontal back-gesture either.
  local x=$((w*13/100)); [ "$x" -lt 24 ] && x=24
  local y1=$((h*63/100)) y2=$((h*37/100))
  for i in $(seq 1 "$max"); do
    on_screen "$want" && return 0
    $ADB shell input swipe "$x" "$y1" "$x" "$y2" 700 >/dev/null 2>&1
    sleep 0.5
  done
  on_screen "$want"
}
# nudge_up  - one small swipe (~30% of the screen) so a section just brought to the bottom edge by
# swipe_up_to is framed with its body/buttons instead of clipped at the fold. No dump.
nudge_up() {
  local sz w h
  sz="$($ADB shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)"
  w="${sz%x*}"; h="${sz#*x}"; : "${w:=480}"; : "${h:=800}"
  local gx=$((w*13/100)); [ "$gx" -lt 24 ] && gx=24   # left gutter, same reason as swipe_up_to
  $ADB shell input swipe "$gx" $((h*62/100)) "$gx" $((h*34/100)) 200 >/dev/null 2>&1
  sleep 0.5
}
# tap_desc <exact>  - tap the centre of the node with that exact content-desc (one dump). For icon
# buttons, which carry a contentDescription but no text (the mic, the P button). Returns non-zero
# if absent.
tap_desc() {
  local b cx cy
  ui_dump
  b="$($ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re
d = sys.stdin.read(); want = sys.argv[1]
for m in re.finditer(r"<node [^>]*>", d):
    s = m.group(0); t = re.search(r"content-desc=\"([^\"]*)\"", s); bb = re.search(r"bounds=\"([^\"]*)\"", s)
    if t and t.group(1) == want:
        print(bb.group(1) if bb else ""); break
' "$1")"
  [ -z "$b" ] && return 1
  cx="$(echo "$b" | sed -E 's/^\[([0-9]+),[0-9]+\]\[([0-9]+),[0-9]+\]$/\1 \2/' | awk '{print int(($1+$2)/2)}')"
  cy="$(ycenter "$b")"
  { [ -z "$cx" ] || [ -z "$cy" ]; } && return 1
  $ADB shell input tap "$cx" "$cy" >/dev/null 2>&1; sleep 1; return 0
}
# tap_center <exact>  - tap the centre of the node with that exact text (one dump to locate it). For
# expanding a collapsible header in a coverage capture. Returns non-zero if the text isn't on screen.
tap_center() {
  local b cx cy
  b="$(find_text "$1")"; [ -z "$b" ] && return 1
  cx="$(echo "$b" | sed -E 's/^\[([0-9]+),[0-9]+\]\[([0-9]+),[0-9]+\]$/\1 \2/' | awk '{print int(($1+$2)/2)}')"
  cy="$(ycenter "$b")"
  { [ -z "$cx" ] || [ -z "$cy" ]; } && return 1
  $ADB shell input tap "$cx" "$cy" >/dev/null 2>&1; sleep 1; return 0
}

# ---- assertions -----------------------------------------------------------------------------
PASS=0; FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

assert_focus_text() {
  local got; got="$(focused_text)"
  if [ "$got" = "$1" ]; then pass "focused element is '$1'"; else fail "expected focus '$1', got '${got:-<none>}'"; fi
}
assert_focus_desc() {
  local got; got="$(focused_desc)"
  if [ "$got" = "$1" ]; then pass "focused element desc is '$1'"; else fail "expected focus desc '$1', got '${got:-<none>}'"; fi
}
# assert_focus_ytop_between <lo> <hi>  - focused node's top Y is within [lo,hi] (for icon/handle
# targets that have no text, e.g. the search bar or the sheet handle).
assert_focus_ytop_between() {
  local y; y="$(focus_ytop)"
  if [ "$y" -ge "$1" ] && [ "$y" -le "$2" ] 2>/dev/null; then pass "focus Y=$y in [$1,$2] ($3)"; else fail "expected focus Y in [$1,$2] ($3), got $y"; fi
}
# assert_focus_covers <exact-text> <label>  - the node with that text sits INSIDE the focused
# node's bounds. Compose menu/list items report focused=true on a WRAPPER whose text attribute
# is empty (the label is a child node), so assert_focus_text reads '<none>' even when focus is
# exactly right - match by geometry instead, the same containment focus_and_ok uses.
assert_focus_covers() {
  local tb cy fb fy1 fy2
  tb="$(find_text "$1")"
  if [ -z "$tb" ]; then fail "'$1' not on screen (so cannot be focused) ($2)"; return; fi
  cy="$(ycenter "$tb")"
  fb="$(focused_bounds)"
  fy1="$(echo "$fb" | sed -E 's/^\[[0-9]+,([0-9]+)\].*/\1/')"
  fy2="$(echo "$fb" | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]$/\1/')"
  if [ -n "$fy1" ] && [ "$cy" -ge "$fy1" ] && [ "$cy" -le "$fy2" ] 2>/dev/null; then
    pass "focus covers '$1' ($2)"
  else
    fail "expected focus covering '$1' ($2), focused bounds '${fb:-<none>}'"
  fi
}
assert_nothing_focused() {
  if [ -z "$(focused)" ]; then pass "nothing focused (as expected: $1)"; else fail "expected nothing focused ($1), got '$(focused)'"; fi
}
assert_something_focused() {
  if [ -n "$(focused)" ]; then pass "something is focused ($1)"; else fail "expected some focus ($1), got nothing"; fi
}
assert_on_screen() { if on_screen "$1"; then pass "'$1' is on screen"; else fail "'$1' not on screen"; fi; }
assert_not_on_screen() { if on_screen "$1"; then fail "'$1' still on screen"; else pass "'$1' gone"; fi; }
assert_on_screen_contains() { if on_screen_contains "$1"; then pass "text containing '$1' is on screen"; else fail "no text contains '$1'"; fi; }
assert_not_on_screen_contains() { if on_screen_contains "$1"; then fail "text '$1' still on screen"; else pass "'$1' gone"; fi; }

# IME state (for text-field-escape tests): 0 (true) if soft keyboard shown.
ime_shown() { $ADB shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"; }
assert_ime_hidden() { if ime_shown; then fail "soft keyboard still shown"; else pass "soft keyboard hidden"; fi; }

# ---- misc -----------------------------------------------------------------------------------
current_pkg() { $ADB shell dumpsys window 2>/dev/null | sed -nE 's/.*mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}\/]+).*/\1/p' | head -1; }
in_app() { [ "$(current_pkg)" = "$PKG" ]; }
shot() { $ADB exec-out screencap -p > "$1" 2>/dev/null; }

report() {
  echo "-------------------------------------------"
  echo "  $((PASS + FAIL)) checks: $PASS passed, $FAIL failed"
  [ "$FAIL" -eq 0 ]
}
