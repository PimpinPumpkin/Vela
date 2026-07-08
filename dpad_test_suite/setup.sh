#!/usr/bin/env bash
# dpad_test_suite/setup.sh — one-time device prep so the tests run headless:
# grant location permission and install a mock GPS provider (Brooklyn by default, so search /
# routing have a real fix). Re-runnable. Override the fix with VELA_LAT / VELA_LNG.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION   >/dev/null 2>&1
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
$ADB shell appops set com.android.shell android:mock_location allow  >/dev/null 2>&1
$ADB shell cmd location providers add-test-provider gps              >/dev/null 2>&1
$ADB shell cmd location providers set-test-provider-enabled gps true >/dev/null 2>&1
LAT="${VELA_LAT:-40.6250}"; LNG="${VELA_LNG:--73.9500}"
# NB: no --time (a stale/zero time is rejected by Vela's fix-freshness gate; the provider stamps now).
$ADB shell cmd location providers set-test-provider-location gps --location "$LAT,$LNG" --accuracy 5 >/dev/null 2>&1
echo "setup: perms granted, mock GPS provider at $LAT,$LNG"
