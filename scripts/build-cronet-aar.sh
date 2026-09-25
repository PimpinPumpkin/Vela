#!/usr/bin/env bash
# Packs Chromium's own prebuilt Cronet for one Chrome for Android version into a single AAR the app
# builds against (app/libs/cronet-<version>.aar, gitignored; CI fetches it from the `cronet-runtime`
# infra release, .github/workflows/cronet-runtime.yml publishes it).
#
# The files come from the public `chromium-cronet` bucket that Chromium's official Cronet builders
# upload to for every version (https://storage.googleapis.com/chromium-cronet/android/<v>/Release/
# cronet/). Nothing is compiled here: it is the same binary Chromium ships, repackaged. Maven's newest
# cronet-embedded is 143, a dozen majors behind the Chrome Vela claims to be.
#
# Usage: scripts/build-cronet-aar.sh [version] [out-dir]   (version blank = Chrome for Android stable)
set -euo pipefail

V="${1:-}"
OUT="${2:-app/libs}"
if [ -z "$V" ]; then
  V=$(curl -fsS 'https://chromiumdash.appspot.com/fetch_releases?channel=Stable&platform=Android&num=1' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["version"])')
fi
case "$V" in *[!0-9.]*|"") echo "bad version: $V" >&2; exit 1 ;; esac

BASE="https://storage.googleapis.com/chromium-cronet/android/$V/Release/cronet"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# The embedded (native) implementation, as Maven's cronet-embedded bundles it. Left out:
# cronet_impl_util_java (a subset of impl_common, duplicate classes), the platform provider, sources
# and test assets. The HttpEngine provider stays: NativeCronetProvider references it.
JARS="cronet_api cronet_impl_common_java cronet_impl_native_java cronet_impl_native_sentinel_java cronet_shared_java httpengine_native_provider_java"
CFGS="cronet_impl_common_proguard cronet_impl_native_proguard cronet_shared_proguard httpengine_native_provider_proguard"
# All four app ABIs: the all-in-one APK drops the x86 copies (app/build.gradle.kts), the per-chip
# x86 APKs keep theirs.
ABIS="arm64-v8a armeabi-v7a x86 x86_64"

fetch() { curl -fsS --retry 3 -o "$2" "$BASE/$1"; }
for j in $JARS; do fetch "$j.jar" "$j.jar"; done
for c in $CFGS; do fetch "$c.cfg" "$c.cfg"; done
fetch VERSION VERSION
fetch LICENSE LICENSE
got=$(awk -F= '{printf "%s.", $2}' VERSION | sed 's/\.$//')
[ "$got" = "$V" ] || { echo "VERSION file says $got, wanted $V" >&2; exit 1; }

mkdir -p aar/jni classes
for a in $ABIS; do
  mkdir -p "aar/jni/$a"
  fetch "libs/$a/libcronet.$V.so" "aar/jni/$a/libcronet.$V.so"
  head -c 4 "aar/jni/$a/libcronet.$V.so" | od -An -tx1 | tr -d ' \n' | grep -q '^7f454c46' || { echo "$a .so is not ELF" >&2; exit 1; }
done

for j in $JARS; do (cd classes && unzip -qo "../$j.jar"); done
(cd classes && zip -qr -X ../aar/classes.jar .)
cat $(for c in $CFGS; do echo "$c.cfg"; done) > aar/proguard.txt
cat >> aar/proguard.txt <<'EOF'
# Annotation-only references Chromium's build provides and an app does not, and a platform API newer
# than the app's compile SDK that Cronet only calls on devices that have it.
-dontwarn javax.annotation.**
-dontwarn android.app.privatecompute.**
EOF
cp LICENSE aar/LICENSE
: > aar/R.txt
cat > aar/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.chromium.net.cronet">
    <uses-sdk android:minSdkVersion="23" />
</manifest>
EOF

mkdir -p "$OLDPWD/$OUT" 2>/dev/null || true
DEST="$(cd "$OLDPWD" && mkdir -p "$OUT" && cd "$OUT" && pwd)/cronet-$V.aar"
(cd aar && zip -qr -X "$DEST" .)
echo "$DEST"
