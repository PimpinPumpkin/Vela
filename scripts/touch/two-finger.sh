#!/usr/bin/env bash
# Plays a two-finger gesture on the connected phone (see TwoFinger.java for the arguments).
# Builds the helper on first use. Needs the Android SDK (ANDROID_HOME or ~/Library/Android/sdk).
#   scripts/touch/two-finger.sh 760 1000 620 1140 320 1440 460 1300 50 20    a slow pinch
#   scripts/touch/two-finger.sh 800 900 640 1100 280 1500 440 1300 40 12 60 4 2    zoom out four pinches and back, twice
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
out="${TMPDIR:-/tmp}/vela-two-finger"
if [ ! -f "$out/classes.dex" ] || [ "$here/TwoFinger.java" -nt "$out/classes.dex" ]; then
  mkdir -p "$out"
  jar="$(ls -d "$sdk"/platforms/android-* | sort -V | tail -n 1)/android.jar"
  d8="$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)/d8"
  javac -source 8 -target 8 -nowarn -cp "$jar" -d "$out" "$here/TwoFinger.java" 2>/dev/null ||
    { echo "two-finger: TwoFinger.java did not compile" >&2; exit 1; }
  "$d8" --output "$out" "$out/TwoFinger.class"
fi
adb push "$out/classes.dex" /data/local/tmp/vela-two-finger.dex >/dev/null 2>&1
adb shell "CLASSPATH=/data/local/tmp/vela-two-finger.dex app_process / TwoFinger $*"
