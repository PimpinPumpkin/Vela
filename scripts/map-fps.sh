#!/usr/bin/env bash
# The MAP's frame rate during a repeatable pan, on a device.
#
#   scripts/map-fps.sh [serial] [label]
#
# Not gfxinfo: the map draws on its own GL thread in a SurfaceView, so gfxinfo counts the Compose
# chrome and cheerfully reports "Total frames rendered: 0" for a pan that visibly stutters (checked
# 2026-09-18). The numbers come from MapLibre's own end-of-frame callback, which the app logs once
# a second under VelaFps when the system property `debug.vela.fps` is set. The app reads that
# property when it creates the map, so this restarts the app.
#
# TRAPS from earlier perf sessions: the 4a throttles after minutes of scrubbing, so watch the
# thermal line and let it cool between runs; and a cold map is not what you are measuring, hence
# the wait before the gestures.
set -euo pipefail
D="${1:-$(adb devices | awk 'NR==2 {print $1}')}"
LABEL="${2:-run}"
# ONE RUN IS NOT A MEASUREMENT. Repeated runs of the IDENTICAL build vary by 5 to 7 fps of median
# on a 4a, which is wide enough to invent a regression that is not there (done on 2026-09-18: a
# street-width change was reported as 40-55 fps down to 29-53, and three runs a side showed the two
# distributions sitting on top of each other). Run this at least three times a side and compare the
# spreads. Batching the runs inside one invocation does not work either: the idle seconds between
# them are logged at 59 fps and drag the median up.

echo "thermal: $(adb -s "$D" shell dumpsys thermalservice 2>/dev/null | grep -m1 'Thermal Status' | tr -d '\r' || echo unknown)"
adb -s "$D" shell setprop debug.vela.fps true
adb -s "$D" shell am force-stop app.vela
adb -s "$D" shell am start -n app.vela/.MainActivity >/dev/null 2>&1
sleep 12
adb -s "$D" logcat -c

for _ in 1 2 3 4 5 6 7 8; do
  adb -s "$D" shell input swipe 800 1500 300 900 320
  adb -s "$D" shell input swipe 300 900 800 1500 320
done
sleep 2
adb -s "$D" logcat -d -s VelaFps:D 2>/dev/null > /tmp/velafps.txt
python3 - "$LABEL" <<'PY'
import re, sys
label = sys.argv[1]
fps = [int(m.group(1)) for m in re.finditer(r"VelaFps\s*:\s*(\d+) fps", open("/tmp/velafps.txt").read())]
# Drop the first second (the map is still catching up after the restart) and any second with no
# frames at all, which is an idle gap between gestures rather than a stall.
fps = [f for f in fps[1:] if f > 0]
if not fps:
    print(f"{label}: no frames seen. Is the app the one with the probe, and did it restart?")
    raise SystemExit
fps.sort()
print(f"{label}: {len(fps)} seconds of panning across the runs")
print(f"  fps  min {fps[0]}  p10 {fps[len(fps)//10]}  median {fps[len(fps)//2]}  max {fps[-1]}")
print(f"  seconds under 30 fps: {sum(1 for f in fps if f < 30)} of {len(fps)}")
print("  (one run proves nothing: run it three times a side, and ignore a median gap under ~6 fps)")
PY
