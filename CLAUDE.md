# Vela — project guide for Claude

Degoogled Google-Maps replacement for Android (the "NewPipe for Maps"). Open
vector tiles for the basemap; the device scrapes Google's public web endpoints
per-user (no backend, no shared API key) for POIs, routing and traffic-aware
ETAs. Targets GrapheneOS / no-GMS ROMs; F-Droid distribution. GPLv3.

## ⚠️ Docs discipline (read first)

**Every change updates the docs in the same commit.** Hard rule for all
collaborators (human or Claude). When you change behaviour, calibration,
features, or structure, update — in the *same* commit:
- `README.md` — status, architecture, calibrated request/response paths
- `FEATURES.md` — tick/retire the affected items
- `SPEC.md` — the authoritative rebuild spec (architecture / extractor contract /
  resilience / constraints); update when a load-bearing decision or path changes
- `ROADMAP.md` — planned work + big bets (opt-in telemetry, Vela's own traffic layer,
  popular times, …); add new ideas here as they come up
- `CLAUDE.md` — this file (build rules, layout, gotchas)
- the `project-vela` memory note if a load-bearing fact changed

Stale docs are treated as a bug. Code-only commits are not OK; if a change
genuinely needs no doc edit, say why in the commit.

## Build

- **Always build release** for anything run on-device — debug builds visibly lag
  during map scroll/nav. R8 lives in the `release`
  buildType. Use `./gradlew :app:assembleDebug` only as a compile check.
- `./gradlew :core:test` runs the pure-logic unit tests (polyline, nav engine).
- **Auditing a real drive.** A saved trip stores the navigated route too (`core/replay/TripLog`
  format, shared by `:app`'s `TripStore` writer and the `:core` reader). To diff what the nav
  cards/voice said against the plotted route from a shared trip CSV, call `TripLog.audit(csv)`
  (→ `NavReplay.Report.summary()`) or run the on-demand harness:
  `./gradlew :core:testDebugUnitTest --tests '*auditSharedTripLog' -DvelaTrip=<abs.csv> --info`
  (skipped when `-DvelaTrip` is unset). It flags silent/missed turns, too-early announcements,
  and lying card distances — built so a travel log can be analysed without knowing where it broke.
- CI in `.github/workflows/ci.yml` (single workflow): every push to `main`
  builds + tests the APK (uploaded as an artifact) and publishes a **normal
  versioned GitHub release** `v0.2.<run>` (versionName `0.2.<run>`, versionCode
  `2000+run`) — kept as a revision history; Obtainium tracks the latest with no
  pre-release toggle. (Switched off the rolling-nightly scheme 2026-06-16 — it
  confused Obtainium. Bumped `0.1.<run>`/`1000+run` → `0.2.<run>`/`2000+run` on
  2026-06-18 after local dev builds were hand-set with `-PappVersionCode` in the
  1000s, got installed on a test phone, and left it *ahead* of the release line —
  Obtainium then saw the next release as a downgrade. **Keep local dev builds
  below 1000**, e.g. `-PappVersionCode=1`, so the release line always wins.)
  Release signing uses repo secrets `VELA_KEYSTORE_BASE64`,
  `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS` (set; keystore at `~/.vela-signing/`,
  outside the repo — back it up). Without them the APK is debug-signed. Version
  override: `-PappVersionName`/`-PappVersionCode`. An optional `MAPTILER_KEY`
  secret → `BuildConfig.MAPTILER_KEY` (`-PmaptilerKey`) switches the basemap to
  MapTiler Streets (Google-like, with a dark variant by system theme); empty
  locally → keyless OpenFreeMap. **Never commit the MapTiler key** — CI-secret +
  BuildConfig only.
- Toolchain: AGP 8.7.3, Kotlin 2.1.0, Gradle
  8.11.1, compileSdk 35, minSdk 26, Java 17, Compose + Hilt + version catalog.
- Release signing from env: `VELA_KEYSTORE_PATH` / `VELA_KEYSTORE_PASSWORD` /
  `VELA_KEY_ALIAS` (default alias `vela`); falls back to debug keystore locally.

## Layout

- `:core` is the UI-agnostic "extractor" (NewPipeExtractor pattern). `:app` is
  the Compose UI. Don't let MapLibre or Android UI types leak into `:core`
  (convert `LatLng` at the view boundary).
- The one seam is `core/data/MapDataSource`. `MockMapDataSource` is the default
  and keeps the entire app usable offline; `google/GoogleMapsDataSource` is the
  real scraper.
- **Light/dark is `AppTheme` (`ui/theme/AppTheme.kt`), not the OS.** Read the
  in-app theme with the composable **`isAppInDarkTheme()`** — never call
  `isSystemInDarkTheme()` directly in app UI (it ignores the user's Light/Dark/
  System choice in Settings → Appearance). `AppTheme.mode` is a process-wide
  reactive `mutableStateOf` (same shape as `ui/Units`), persisted to
  `vela_settings`, `init()`-ed in `VelaApp`; flipping it recomposes the theme and
  reloads the map style (`VelaMapView`'s styleKey carries `dark=`).

## Working on the scraper

- The `pb` request *grammar* (`PbBuilder`) and `PolylineCodec` are correct and
  stable. The **field numbers, response array indices, and session regexes are
  NOT** — they're marked `CALIBRATE:` and must be pinned from a live capture of
  `maps.google.com` (devtools/mitmproxy). Never trust a remembered `pb` layout.
- Turn the real source on with `VelaConfig.USE_GOOGLE_SOURCE = true` after
  calibrating. Parsers throw `CalibrationNeededException` (routine, non-fatal)
  when shapes drift; the UI surfaces it as a notice.
- **Never embed a static Google API key.** Per-user `GoogleSession` bootstrap
  only — that's what keeps the NewPipe legal footing.
- **Remote calibration (`calibration.json` at the repo root).** The `pb`/proto
  templates and endpoint URLs (search, directions, reviews, **photos** —
  `photosEndpoint`/`photosProto` for the `hspqX` gallery RPC) are remotely
  updatable: `CalibrationStore` (in `:core`,
  `config/`) fetches `calibration.json` from the repo's raw URL at launch and
  adopts it when its `version` is higher than the bundled `Calibration.DEFAULT`,
  provided every endpoint host is on the allowlist (`www.google.com`/`google.com`).
  **To ship a pb/endpoint fix WITHOUT an app release:** edit the drifted field in
  `calibration.json`, **bump `version`**, **re-sign** (`./scripts/sign-calibration.sh`),
  commit `calibration.json` + `calibration.json.sig` to `main` — users pick it up on
  their next launch (raw.githubusercontent caches ~5 min). Keep
  `Calibration.DEFAULT` (the compiled fallback) and `calibration.json` in sync when
  you cut an actual release. **Phase 2 (done): the search parser's positional
  field-index paths are remote too** — the `paths` object in `calibration.json`
  (`name`, `address`, `rating`, `photos`, `featureId`, … as `[i,j,…]` arrays,
  relative to a result entry whose place node is `[1]`; `results`/`single` are
  root-relative). So a "Google moved field X to a new index" fix is also just an
  edit + version bump.
- **Signed channel (mandatory).** The bundle is **ECDSA-P256/SHA-256 signed**
  (`calibration.json.sig`, detached, base64) and the app verifies it against the
  **public key pinned in `CalibrationStore.PINNED_PUBLIC_KEY`** before adopting —
  so a repo/CDN compromise can't push config *or code* to devices. The private key
  lives at `~/.vela-signing/vela-calibration.key` (**never commit it**; the public
  half is safe to embed). `scripts/sign-calibration.sh` signs + self-verifies;
  `BundleSignature.verify` (`:core`) is the unit-tested verifier. A bundle that
  fails verification is ignored (app keeps the last-good config). An unsigned/older
  cached copy falls back to the compiled `DEFAULT` for one launch.
- **Notices.** `calibration.json` carries a `notices` array (`id`/`level`/`title`/
  `body`/`url`) shown as dismissable cards on the bare map (`MapViewModel.refreshNotices`,
  dismissed ids in `vela_notices` prefs) — push "search is down, fix coming" with no
  app update. Rides the same signed channel.
- **Phase 3 (done): remote parse *logic*** via `transformsJs` — a signed JS bundle
  run in a **Rhino sandbox** (`JsSandbox`, interpreted/`optimizationLevel=-1` for ART,
  `initSafeStandardObjects` so it can't reach Java/IO; `org.mozilla:rhino-runtime`,
  R8-keep in `core/consumer-rules.pro`). `JsTransforms` exposes two search hooks —
  `parseSearch(rawResponse)` (full re-parse of a reshaped response) and
  `transformPlaces(placesJson)` (post-process) — over the flat `PlaceJson` contract;
  **compiled Kotlin is always the fallback** (no script / missing fn / any error →
  unchanged). So a *response-shape* change can be hot-fixed too, not just a moved
  field. Wired in `GoogleMapsDataSource.search`. Verified on-device (a pushed
  `transformPlaces` marked the first result; cleared after).

## Degoogled constraints (hard rules)

- Location: AOSP `LocationManager` only — never `FusedLocationProviderClient`.
- Heading (browse-cone facing direction when stopped, where GPS course is noise): raw
  `SensorManager` `TYPE_ROTATION_VECTOR` (`core/location/HeadingProvider`) — a plain
  Android sensor, not GMS. **Navigation never uses it** (the nav heading comes from the
  matched road); it's pushed to state only in browse + only on a real change, so it can't
  spam recomposition during nav.
- Voice: AOSP `TextToSpeech`, engine-selectable — never hard-depend on Google TTS.
- Nav feedback: spoken guidance (`VoiceGuide`) + **direction-coded haptic turn cues**
  (`core/feedback/Haptics`, `NavEvent.Haptic`); toggle in Settings → Navigation.
- EU consent: `InMemoryCookieJar` (CoreModule) pre-seeds Google's `SOCS`/`CONSENT`
  cookies so a cookieless EU session isn't bounced to `consent.google.com` — don't
  strip those, and don't let a `Set-Cookie` downgrade `CONSENT` to `PENDING`.
- No GMS: no FCM/Firebase/Play Integrity/Fused. If push is needed later, use
  UnifiedPush; crash reporting via ACRA/self-hosted Sentry.
- **Photos use a hidden WebView** (`app/web/WebPhotoFetcher`). The full gallery RPC
  (`hspqX`) serves real photos only to a real browser engine — OkHttp gets a
  bot-degraded Street-View-only reply (TLS-fingerprint detection, not headers).
  The WebView loads `maps.google.com` **anonymously (no login)** and same-origin-
  fetches the RPC. This is the one place we run Google's JS — an accepted tradeoff
  for richer photos (lazy, best-effort, OkHttp fallback). Gotchas: **desktop UA**
  (mobile UA → Google deep-links to `intent://`), block non-http(s) redirects, and
  use a `Handler` not `View.postDelayed` (a headless WebView never attaches).
- **Public transit uses the same hidden WebView** (`app/web/WebDirectionsFetcher`).
  A plain `/maps/preview/directions` GET with the transit flag (`!3e3`) is silently
  downgraded to a *driving* reply (same TLS-fingerprint bot-detection as photos), so
  the WebView instead navigates the `/maps/dir/<olat>,<olng>/<dlat>,<dlng>/data=!4m2!4m1!3e3`
  page and reads the itinerary set out of `APP_INITIALIZATION_STATE`. **Gotchas:**
  the directions payload is the **longest** `)]}'`-guarded string under slot `[3]`
  (a ~1.7 KB stub sits alongside the ~165 KB real one — take the longest, and poll
  for it: the SPA fills it a beat after page-finish). `TransitParser` (`:core`,
  takes the raw string so `:app` stays out of kotlinx.serialization, like
  `PhotosParser`) reads `root[0][1]` = trips, each trip's **summary at `trip[0]`**
  (one level deeper than you'd guess — `trip[1]` is the per-stop leg tree, a future
  drill-down). Calibrated + device-verified Davis→Sacramento 2026-06-18.

## Name

Vela Maps (`app.vela`). "Vela" was clearance-checked and is free of maps-app and
trademark collisions.
