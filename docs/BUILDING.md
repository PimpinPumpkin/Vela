# Building and running Vela

To install the app, use the Obtainium or F-Droid badge on the [README](../README.md) or an
APK from [Releases](https://github.com/PimpinPumpkin/Vela/releases). This page is for building
from source.

## Build and run

Standard Android toolchain (JDK 17; the Gradle wrapper fetches Gradle 9.8.0 and AGP 9.4.1).

Three pieces are not in git and have to be fetched once before the first build, the same way
CI does it. They are prebuilt binaries with no Maven artifact, hosted on this repo's own
infrastructure releases:

```bash
# the neural voice runtime (sherpa-onnx), about 57 MB
mkdir -p app/libs
curl -fSL -o app/libs/sherpa-onnx-1.13.3.aar \
  https://github.com/PimpinPumpkin/Vela/releases/download/tts-runtime/sherpa-onnx-1.13.3.aar

# Cronet, Chromium's network stack, for the version gradle.properties pins (about 15 MB); or run
# scripts/build-cronet-aar.sh, which packs the same AAR from Chromium's own published build
v=$(sed -n 's/^vela.cronetVersion=//p' gradle.properties)
curl -fSL -o "app/libs/cronet-$v.aar" \
  "https://github.com/PimpinPumpkin/Vela/releases/download/cronet-runtime/cronet-$v.aar"

# OsmAnd's router and obf reader, for offline routing
mkdir -p core/libs
for f in osmand-java.jar osmand-shared-jvm.jar gnu-trove-osmand.jar kxml2-vela.jar; do
  curl -fSL -o "core/libs/$f" \
    "https://github.com/PimpinPumpkin/Vela/releases/download/obf-runtime/$f"
done
```

If `.github/workflows/ci.yml` names a newer file than this page does, trust the workflow.

```bash
# debug build (compile check / local install)
./gradlew :app:assembleDebug

# the build that goes on a phone (R8 and resource shrinking).
# A debug build drops frames on the map and reads as a performance bug.
./gradlew :app:assembleRelease

# unit tests for the pure logic in :core (parsers, nav engine, routing, the name rules)
./gradlew :core:test
```

Release signing comes from environment variables (`VELA_KEYSTORE_PATH`,
`VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`). Without them a local build is signed with the
debug keystore, so `adb install` still works. Do not hand out a local build with a
`-PappVersionCode` above the release line: the next real release then looks like a downgrade.
`-PappId=app.vela.dev` builds a copy that installs beside the real app.

## A custom edition that coexists with the official build

The app id is `app.vela` and the official releases are signed with the maintainer's key (its
certificate SHA-256 is in the README). Two consequences for a fork:

- Android refuses to install one `app.vela` over another signed with a different key. A build of
  your own, signed with your own key or the debug key, cannot replace the official `app.vela`
  build and the official APK cannot replace yours. Back up first (Settings > Diagnostics, and the
  system backup covers the files listed in `res/xml/backup_rules.xml`); a reinstall across keys
  wipes the app's data.
- The in-app updater follows the release feed in `VelaLinks` (the official repository by default)
  and offers a newer version by its version code. A local build's version code is 1, so the
  updater always reports the latest official release as newer. That is a version and feed
  difference, not a missing feature in your build.

To run a fork build beside the official app and keep your own updates, build a separate edition:

```bash
# a distinct id (installs side by side; the file provider, backup and deep links already
# derive from ${applicationId}) and your own keystore and version code
VELA_KEYSTORE_PATH=~/my-vela.jks VELA_KEYSTORE_PASSWORD=… VELA_KEY_ALIAS=vela \
  ./gradlew :app:assembleRelease -PappId=app.vela.byd -PappVersionCode=<n> -PappVersionName=<name>
```

Keep the same id and key for every later build of that edition, or its own updates stop
installing too. Point its updater at your own fork's releases with a signed `calibration.json`
`links` push (SPEC 11.4), or leave update checks off, so it does not offer the official APK (which
would not install over it anyway). Do not try to obtain or reuse the maintainer's private key.

## What CI does

Every push to `main` or `canary` builds and tests. A push to `canary` replaces the rolling
canary build. A daily job publishes a signed nightly prerelease (`v0.5.<run>`) when `main` has
moved, and a weekly job (Mondays) promotes the newest nightly to stable. The F-Droid repository
index is rebuilt after both. Docs-only pushes skip CI. [Chapter 12 of the book](book/12-releases.md)
has the details.

## Architecture

Two Gradle modules. `:core` has no interface code: the models, the requests to Google and
their parsers, the open routers and the on-phone router, the navigation engine, and the remote
settings. `:app` is the Compose interface over MapLibre. `core/data/MapDataSource` is the seam
between them. The module tree is in [`SPEC.md`](../SPEC.md) section 2.

Toolchain: JDK 17, Gradle 9.8, AGP 9.4, Kotlin 2.4. The app compiles against SDK 37, targets
35 and runs on Android 8 (SDK 26) and up.
