#!/bin/bash
# Copy the release build's APKs to the names a GitHub release carries, and print them one per line.
#   stage-apks.sh <prefix> <single-name>
# A per-chip build (-PabiSplits) gives <prefix>-all.apk (the all-in-one fallback, which sorts
# first so updaters older than ApkChoice pick it) plus <prefix>-arm64/-armv7/-x86/-x86_64.apk.
# A plain build gives the one APK as <single-name>, exactly as releases did before the split.
set -euo pipefail
prefix="$1"; single="$2"
out=app/build/outputs/apk/release
if [ -f "$out/app-universal-release.apk" ]; then
  cp "$out/app-universal-release.apk" "$prefix-all.apk";   echo "$prefix-all.apk"
  cp "$out/app-arm64-v8a-release.apk" "$prefix-arm64.apk"; echo "$prefix-arm64.apk"
  cp "$out/app-armeabi-v7a-release.apk" "$prefix-armv7.apk"; echo "$prefix-armv7.apk"
  cp "$out/app-x86-release.apk" "$prefix-x86.apk";         echo "$prefix-x86.apk"
  cp "$out/app-x86_64-release.apk" "$prefix-x86_64.apk";   echo "$prefix-x86_64.apk"
else
  cp "$out/app-release.apk" "$single"; echo "$single"
fi
