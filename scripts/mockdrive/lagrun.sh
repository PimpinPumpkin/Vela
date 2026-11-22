#!/bin/bash
# lagrun.sh <drive on the device> <tag> <out folder> <dest lat,lng>: starts Vela's drive to dest from
# where the mock fixes hold, plays the drive, and keeps the VelaNavTrace lines in <out>/<tag>.logcat
# and MockDrive's send times in <out>/<tag>.mock for lagcheck.py. Needs the gps test provider
# (MockDrive.java), both Diagnostics simulators off, and a hold file at the drive's first point in
# /data/local/tmp/hold.txt. Set ANDROID_SERIAL.
set -u
DRIVE=$1; TAG=$2; OUT=$3; DEST=$4
adb shell setprop debug.vela.tune.navTraceLog 1
adb shell pkill -f MockDrive >/dev/null 2>&1
adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/mockdrive.dex app_process / MockDrive /data/local/tmp/hold.txt' > /data/local/tmp/hold.log 2>&1 &"
sleep 3
adb shell am force-stop app.vela; sleep 1
adb shell am start -n app.vela/.MainActivity >/dev/null; sleep 6
adb logcat -c
adb shell "am start -a android.intent.action.VIEW -p app.vela -d 'https://www.google.com/maps/dir/?api=1&destination=$DEST&travelmode=driving'" >/dev/null
sleep 9
# Tap Start by its text.
b=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o 'text="Start"[^>]*' | grep -o 'bounds="[^"]*"' | head -1 | tr -c '0-9\n' ' ')
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
sleep 4
adb logcat -v epoch -s VelaNavTrace:D > "$OUT/$TAG.logcat" 2>/dev/null &
LC=$!
adb shell pkill -f MockDrive
adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/mockdrive.dex app_process / MockDrive $DRIVE' > /data/local/tmp/$TAG.log 2>&1 &"
n=$(adb shell cat "$DRIVE" | wc -l | tr -d " ")
sleep $((n + 6))
kill $LC
adb shell cat "/data/local/tmp/$TAG.log" > "$OUT/$TAG.mock"
adb shell setprop debug.vela.tune.navTraceLog "''"
