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

PKG="${VELA_PKG:-app.vela}"
ADB="${ADB:-adb}"

# ---- D-pad keycodes -------------------------------------------------------------------------
K_UP=19; K_DOWN=20; K_LEFT=21; K_RIGHT=22; K_OK=23; K_BACK=4; K_HOME=3

# key <code> [settle_seconds]  - press one key and wait for the UI to settle.
key() { $ADB shell input keyevent "$1" >/dev/null 2>&1; sleep "${2:-0.4}"; }
# keys <code>...  - press several keys in sequence (default settle each).
keys() { for c in "$@"; do key "$c"; done; }

# launch_fresh [settle_seconds]  - force-stop + cold launch.
launch_fresh() {
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  $ADB shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep "${1:-3.5}"
}

# ---- focus inspection -----------------------------------------------------------------------
# focused  - prints "bounds|text|desc" of the currently-focused node, or empty if none.
focused() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
  for i in $(seq 1 30); do
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
