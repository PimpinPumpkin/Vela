#!/usr/bin/env bash
# The body of .github/workflows/old-android-smoke.yml, run inside the emulator runner: install the
# x86_64 release APK, start the app through a geo: search (a Davis, CA fixture place), open the
# first result, and fail on a crash or a missing Cronet engine. Evidence goes to smoke-out/.
set -uo pipefail
API="${1:-?}"
OUT=smoke-out
mkdir -p "$OUT"

apk=$(ls app/build/outputs/apk/release/*x86_64*.apk | head -1)
echo "API $API, installing $apk"
adb install -r "$apk" || { echo "install failed"; exit 1; }
adb logcat -c

# A fresh install opens on onboarding: walk it (Get started, decline the permission prompts, skip
# the voice offer) until the map's own Settings button is on screen.
adb shell am start -n app.vela/.MainActivity
sleep 15
for i in $(seq 1 10); do
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  x=$(adb shell cat /sdcard/u.xml 2>/dev/null)
  echo "$x" | grep -q 'content-desc="Settings"' && { echo "onboarding done after $i step(s)"; break; }
  hit=""
  for label in "Get started" "DENY" "Deny" "Don't allow" "Skip" "Not now" "Later" "No thanks" "Use system voice" "OK"; do
    b=$(echo "$x" | grep -o "<node[^>]*text=\"$label\"[^>]*>" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    if [ -n "$b" ]; then
      set -- $(echo "$b" | tr -c '0-9' ' ')
      adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); hit="$label"; break
    fi
  done
  [ -n "$hit" ] && echo "tapped: $hit" || { echo "no known button, BACK"; adb shell input keyevent KEYCODE_BACK; }
  sleep 4
done

adb shell am start -a android.intent.action.VIEW -d "'geo:0,0?q=Davis Food Co-op 620 G St Davis CA'" app.vela
sleep 40
adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
adb shell cat /sdcard/u.xml > "$OUT/results.xml" 2>/dev/null
row=$(grep -o 'text="Davis Food[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]' "$OUT/results.xml" | grep -o '[0-9]*,[0-9]*\]$' | tr -d ']' | tail -1)
if [ -n "$row" ]; then
  adb shell input tap $(( ${row%,*} + 20 )) $(( ${row#*,} + 20 ))
  sleep 30
fi
adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
adb shell cat /sdcard/u.xml > "$OUT/sheet.xml" 2>/dev/null
adb exec-out screencap -p > "$OUT/screen.png"
adb logcat -d > "$OUT/logcat.txt"

fail=0
pid=$(adb shell pidof app.vela | tr -d '\r')
[ -n "$pid" ] || { echo "FAIL: app.vela is not running"; fail=1; }
if grep -E "FATAL EXCEPTION|Process: app.vela" "$OUT/logcat.txt" | grep -q .; then
  echo "FAIL: crash"; grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40; fail=1
fi
grep -E "VelaCronet: engine|VelaCronet: google over|engine unavailable" "$OUT/logcat.txt" | head -5
grep -q "VelaCronet: engine Cronet/" "$OUT/logcat.txt" || echo "WARN: Cronet engine line not seen"
grep -q "UnsatisfiedLinkError" "$OUT/logcat.txt" && { echo "FAIL: native library did not load"; fail=1; }
[ -n "$row" ] && echo "search row found" || echo "WARN: no search result row (network or UI)"
grep -o 'text="[^"]\+"' "$OUT/sheet.xml" | head -12
exit $fail
