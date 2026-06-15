# Carto — project guide for Claude

Degoogled Google-Maps replacement for Android (the "NewPipe for Maps"). Open
vector tiles for the basemap; the device scrapes Google's public web endpoints
per-user (no backend, no shared API key) for POIs, routing and traffic-aware
ETAs. Targets GrapheneOS / no-GMS ROMs; F-Droid distribution. GPLv3.

## Build

- **Always build release** for anything run on-device — debug builds visibly lag
  during map scroll/nav (same lesson as Arcana). R8 lives in the `release`
  buildType. Use `./gradlew :app:assembleDebug` only as a compile check.
- `./gradlew :core:test` runs the pure-logic unit tests (polyline, nav engine).
- Toolchain mirrors Arcana/Callguard exactly: AGP 8.7.3, Kotlin 2.1.0, Gradle
  8.11.1, compileSdk 35, minSdk 26, Java 17, Compose + Hilt + version catalog.
- Release signing from env: `CARTO_KEYSTORE_PATH` / `CARTO_KEYSTORE_PASSWORD` /
  `CARTO_KEY_ALIAS` (default alias `carto`); falls back to debug keystore locally.

## Layout

- `:core` is the UI-agnostic "extractor" (NewPipeExtractor pattern). `:app` is
  the Compose UI. Don't let MapLibre or Android UI types leak into `:core`
  (convert `LatLng` at the view boundary).
- The one seam is `core/data/MapDataSource`. `MockMapDataSource` is the default
  and keeps the entire app usable offline; `google/GoogleMapsDataSource` is the
  real scraper.

## Working on the scraper

- The `pb` request *grammar* (`PbBuilder`) and `PolylineCodec` are correct and
  stable. The **field numbers, response array indices, and session regexes are
  NOT** — they're marked `CALIBRATE:` and must be pinned from a live capture of
  `maps.google.com` (devtools/mitmproxy). Never trust a remembered `pb` layout.
- Turn the real source on with `CartoConfig.USE_GOOGLE_SOURCE = true` after
  calibrating. Parsers throw `CalibrationNeededException` (routine, non-fatal)
  when shapes drift; the UI surfaces it as a notice.
- **Never embed a static Google API key.** Per-user `GoogleSession` bootstrap
  only — that's what keeps the NewPipe legal footing.

## Degoogled constraints (hard rules)

- Location: AOSP `LocationManager` only — never `FusedLocationProviderClient`.
- Voice: AOSP `TextToSpeech`, engine-selectable — never hard-depend on Google TTS.
- No GMS: no FCM/Firebase/Play Integrity/Fused. If push is needed later, use
  UnifiedPush; crash reporting via ACRA/self-hosted Sentry.

## Naming caveat

"Carto" collides with CARTO (carto.com). Renaming now is one `applicationId` +
one `rootProject.name`; decide before any public release.
