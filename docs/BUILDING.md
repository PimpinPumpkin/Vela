# Building and running Vela

Most people don't need this page: grab the app from the Obtainium or F-Droid
badge on the [README](../README.md), or an APK from
[Releases](https://github.com/PimpinPumpkin/Vela/releases). This is for
building from source.

## Build & run

Standard Android toolchain:

```bash
# debug build (compile check / local install)
./gradlew :app:assembleDebug

# the real distribution build - R8 + resource shrinking.
# Always ship release: debug builds visibly lag during map scroll/nav.
./gradlew :app:assembleRelease

# unit tests for the pure logic (polyline codec, nav engine)
./gradlew :core:test
```

Release signing comes from CI env vars (`VELA_KEYSTORE_PATH`,
`VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS`); local builds fall back to the
debug keystore so `adb install` still works.

**CI**: every push to `main` builds, tests, and publishes a signed nightly
prerelease (`v0.4.<run>`); a weekly job promotes the newest nightly to the
stable release, and the F-Droid repo index rebuilds off both. The release
pipeline details (secrets, channels, versioning) live in
[`CLAUDE.md`](../CLAUDE.md). Out of the box the app talks to the live Google
source over the keyless OpenFreeMap basemap; `MockMapDataSource` is the
offline fallback.

## Architecture

Two Gradle modules with a strict boundary (AGP 8.7.3, Kotlin 2.1, Compose, Hilt,
R8 release builds): **`:core`** is the UI-agnostic "extractor" in the
NewPipeExtractor mold - models, the Google scraper and parsers, the open routers,
the pure nav engine, and the remote-config layer - and **`:app`** is the Compose
UI over MapLibre. `MapDataSource` is the load-bearing seam between them: Mock for
offline dev, Google today, and a future Overture/OSM source or self-hostable
backend drops in the same way. The full module tree and every seam are in
[`SPEC.md`](../SPEC.md).
