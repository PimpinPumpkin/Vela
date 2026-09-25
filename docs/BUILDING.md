# Building and running Vela

Most people don't need this page: grab the app from the Obtainium or F-Droid
badge on the [README](../README.md), or an APK from
[Releases](https://github.com/PimpinPumpkin/Vela/releases). This is for
building from source.

## Build & run

Standard Android toolchain (JDK 17; the Gradle wrapper fetches Gradle 8.11.1 and AGP 8.10.1).

Two pieces are not in git and have to be fetched once before the first build, the same way
CI does it. They are prebuilt binaries with no Maven artifact, hosted on this repo's own
infrastructure releases:

```bash
# the neural voice runtime (sherpa-onnx), about 57 MB
mkdir -p app/libs
curl -fSL -o app/libs/sherpa-onnx-1.13.3.aar \
  https://github.com/PimpinPumpkin/Vela/releases/download/tts-runtime/sherpa-onnx-1.13.3.aar

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

# the real distribution build - R8 + resource shrinking.
# Always ship release: debug builds visibly lag during map scroll/nav.
./gradlew :app:assembleRelease

# unit tests for the pure logic in :core (parsers, nav engine, routing, the name rules)
./gradlew :core:test
```

Release signing comes from CI env vars (`VELA_KEYSTORE_PATH`,
`VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`); local builds fall back to the
debug keystore so `adb install` still works. Keep a hand-set
`-PappVersionCode` below 1000 so a local build never sits ahead of the
release line, and use `-PappId=app.vela.dev` to install a test build beside
the real app instead of over it.

**CI**: every push to `main` or `canary` builds and tests; a push never cuts
a release by itself. A daily cron publishes a signed nightly prerelease
(`v0.4.<run>`) when `main` has moved since the last one, every `canary` push
replaces the rolling canary build, a weekly job (Mondays) promotes the newest
nightly to the stable release, and the F-Droid repo index rebuilds off both.
Docs-only pushes skip CI. The release
pipeline details (secrets, channels, versioning) live in
[`CLAUDE.md`](../CLAUDE.md). Out of the box the app talks to the live Google
source over the keyless OpenFreeMap basemap; `MockMapDataSource` is the
offline fallback.

## Architecture

Two Gradle modules with a strict boundary (AGP 8.10.1, Kotlin 2.1, Compose, Hilt,
R8 release builds; `:app` compiles against SDK 36 and targets 35, minimum 26):
**`:core`** is the UI-agnostic "extractor" in the NewPipeExtractor mold - models,
the Google scraper and parsers, the open routers and the on-device obf engine, the
pure nav engine, and the remote-config layer - and **`:app`** is the Compose UI over
MapLibre. `MapDataSource` is the load-bearing seam between them: Mock for offline
dev, Google today (with the open places layer, the downloaded packs and the open
routers filling in around it), and a self-hostable source would drop in the same
way. The full module tree and every seam are in
[`SPEC.md`](../SPEC.md).
