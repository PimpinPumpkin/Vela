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
  `./gradlew :core:testDebugUnitTest --tests '*auditSharedTripLog' -DvelaTrip=<abs.csv> --rerun-tasks`
  then read the report from the test-results XML `system-out`
  (`core/build/test-results/testDebugUnitTest/*.xml`). The property passthrough lives in
  `core/build.gradle.kts` (`tasks.withType<Test>` forwards `velaTrip`) — without it the test JVM
  never saw `-D` and the harness silently skipped. It flags silent/missed turns, too-early
  announcements, and lying card distances — built so a travel log can be analysed without knowing
  where it broke. **Trips are SEGMENTED**: every route the drive used (start + each reroute/
  faster-route swap) is its own `RP/RD/M` block, activated at the fix where it appears;
  `TripLog.parse().segments` carries them, audit + in-app replay are segment-aware, and replays
  are HERMETIC (`NavSession.replayMode` — no live reroute/recheck fetches, recorded swaps play
  back via `replaySetRoute`; the map view scales the puck's clocks by `replaySpeedup`). Never
  audit/replay a multi-block trip against a single mashed route — that was the "arrow on another
  street / arrived mid-replay" corruption. NB replays of OLD trips faithfully play back the dirty
  fixes the old pipeline recorded (BeaconDB teleports) — judge the engine on fresh recordings.
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
- **Basemap layer gotchas (`VelaMapView.ensureLayers`/`applyLight`/`applyDark`, OpenFreeMap Liberty).**
  (1) **`maxzoom` is EXCLUSIVE** — the bundled `building` FILL layer is `minzoom 13 / maxzoom 14`, so
  `setMinZoom(14f)` alone collapses its range to empty and the flat footprints never paint (you'd see only
  the faint `building-3d` extrusion). The fill needs a matching **`setMaxZoom(24f)`** to re-open the top;
  keep it. `building-3d` (fill-extrusion) is gated to **z16+** on purpose (the flat fill carries the
  browse-zoom footprint look; extrusion is the per-pixel-expensive part on a Pixel 5a). (2) **House
  numbers** render via the runtime `vela-housenumber` SymbolLayer (OMT `housenumber` source-layer, `minZoom 16`) —
  OpenFreeMap **does** serve that source-layer (verified vs the live TileJSON + z14 tiles), so it works;
  coverage is OSM `addr:housenumber` (partial), not a render bug. (3) The runtime loads the style from the **LIVE** URL `MapStyle.LIBERTY.uri =
  https://tiles.openfreemap.org/styles/liberty` (`fromUri`), and offline downloads use the same URL — both
  **auto-follow OpenFreeMap's current tile snapshot**, so there is NO dated-path/blank-basemap risk. The
  bundled `liberty-roboto.json` asset (which DOES pin a dated `planet/<snapshot>` path) is **parked +
  unused** — the `asset://`/`fromJson` path in `VelaMapView` is dead code kept only as reference (a bundled
  copy blanked the vector tiles on-device; see the project memory). Don't be misled by the stale path in
  that asset. Verify basemap edits on-device in **both** themes.
- **Localization (i18n) is three layers, one control (`AppLocale`, `ui/`, same process-wide reactive
  holder shape as `AppTheme`).** `AppLocale.language` = "" (follow system) or a code; Settings → Language
  picks it. (1) **Spoken nav** — the GENERATED turn-by-turn text is a per-language `NavStrings` table in
  `:core` (`core/i18n`), switched by `NavStringsRegistry`; `AppLocale.apply()` drives it. **The chosen neural
  voice must actually speak that language** — `VoiceGuide` guards on `NeuralSynth.voiceLanguage` and, on a
  mismatch, falls back to a system TTS in the target language (or stays silent + fires a "get a matching voice"
  hint) rather than reading, e.g., Russian nav text through the English Piper model (see the voice bullet under
  Degoogled constraints). (2) **UI chrome** —
  all ~330 user-facing `:app` strings live in `res/values/strings.xml` (English) + `res/values-<lang>/` for
  the 10 translated languages (fr de es it pt nl ru pl sv uk), referenced via `stringResource`/`getString`.
  The runtime switch is `AppLocale.wrap(context)` (overrides the Configuration locale, **no-op when following
  the system** so the default path is untouched) applied in **both** `MainActivity.attachBaseContext` (Compose
  UI) and `VelaApp.attachBaseContext` (ViewModel/notification `getString`); changing the language calls
  `recreate()`. (3) **Google POI content** — the scrape's `hl=en` is rewritten to the app/system language
  at request time (`GoogleMapsDataSource.localized()`, no-op for English) so categories/hours/status/price
  come back localized. The **open/closed BOOLEAN is parsed from the localized status TEXT against a
  per-language keyword table** (`SearchParser.parseOpenNow(status, lang)`, `lang` = the same
  `Locale.getDefault()` that set `hl=`; CLOSED words are matched FIRST — "Opens 5 AM" / "Ouvre à 07:00" /
  "Fechado" / "Opent om 9:00" are prefix-cousins of the open words, and open-first matching is exactly
  what painted a closed Starbucks green). **Do NOT resurrect the numeric status-code path**
  (`openFromCode`, paths `statusCodeRich`/`statusCodeSimple`, removed 2026-07-04): a live EN capture
  proved those ints are span/style markers, not open/closed codes (closed pharmacies carried "open" 6,
  an Open-24-hours place carried 13/4 and rendered red) — the hl=fr pin agreeing was a coincidence.
  `placeStatusColor(status, openNow)` colours from the boolean and refuses to green English text that
  literally reads closed even if fed `openNow=true`. `gl` (region) still `us` — GPS-region `gl`
  is a follow-up. **Dual-purpose literals stay inline on purpose** —
  strings that double as a logic key (place "Open"/"Closed" → status-colour parser, the map category chips /
  search-along-route chips are also the query, review sort/tab labels branch a `when`) are NOT in strings.xml;
  they localize only once display text is split from the logic key. **Names/addresses/reviews are DATA — never
  translated.** Adding a user-facing string means: add it to `values/strings.xml` AND all `values-<lang>/`,
  and match the `%1$s`/`%2$d` placeholder TYPE to the arg (Int → `%d`, else `%s`; a `%d` fed a String crashes).

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
  The bundle also carries **`defaultVoiceId`** (String — the Piper voice a fresh install
  downloads + activates), **`defaultVoiceSpeaker`** (int — only tunes libritts_r's 904
  variants) and **`defaultVoiceSpeed`** (float — spoken-directions speed), so a favourite
  voice/speaker/pace can be pushed as everyone's default with a version bump + re-sign, no
  app release (a user's own `voice_model`/`voice_speaker`/`voice_speed` pick still wins).
  Shipped defaults (calibration **v10**): voice **HFC Female** (`en_US-hfc_female-medium`),
  speaker 14 (libritts only), speed **0.8×** — matched in the compiled `Calibration.DEFAULT`
  + `VelaPiper.DEFAULT_VOICE_ID`. NB the neural voice lengthens pauses at periods by
  **splitting the utterance on sentence boundaries and splicing silence in-app**
  (`PiperSynth.splitSentences`/`joinWithGaps`) — sherpa-onnx's `silenceScale` config is
  a measured no-op on the Piper/VITS path, don't reach for it. Spoken text also runs through
  `SpeechText.spokenNumbers` in `VoiceGuide.forSpeech` — 3-digit **street ordinals** ("128th" →
  "one twenty-eighth") are pre-expanded so the neural G2P doesn't mangle them into "one, hundred
  and 28th" (only 100–999; 1–2 digit + 4-digit+ are left for espeak). And `NavEngine` **does not
  announce the DEPART maneuver** — `NavSession.start` speaks it once ("Starting navigation. Head
  east on F St"); the engine skips it (it's at distance ≈ 0) and advances silently, else the opener
  gets clipped by a re-announced "head out".
  **Multiple downloadable voices (voice browser, 2026-07-03).** `VelaPiper` is no longer one hardcoded
  model — it's one engine (`ENGINE_ID = "vela.piper"`) that holds ANY of many Piper voices, each in its
  own `filesDir/piper/<id>/` dir (`<id>.onnx` + `tokens.txt` + `espeak-ng-data/`, the sherpa
  `vits-piper-<id>` archive layout). The **installed set is derived from the filesystem** (`installedVoiceIds`,
  keeps only complete dirs → a partial download self-heals), the pick persists in **`voice_model`**, and
  **speaker choice is per-voice** (`voice_speaker_<id>`; the legacy global `voice_speaker` is migrated onto
  libritts_r). The browsable catalog is `PiperCatalog` in `:core` (pure data, unit-tested, ~40 curated
  voices across 11 languages — en_US/en_GB plus the 10 i18n languages; URL = `…/tts-models/vits-piper-<id>.tar.bz2`). `PiperSynth.ensureLoaded` reloads when
  the selected voice changes; `PiperSynth.reloadVoice()` is the SINGLE switch trigger — it bumps the
  generation counter (aborting any in-flight utterance) then tears down + rebuilds on the same serial
  worker, so `tts` is never freed mid-`generate()`. `MapViewModel.migrateFlatLayoutIfNeeded` (first thing
  in `init`) relocates the old flat single-voice install in place (rename, copy-fallback, verify-gated,
  re-runnable) — never re-downloads. **Any large download (voice model, routing graph, building overlay)
  MUST NOT use the shared OkHttp client** — its `callTimeout(12s)` (scrape-bounding) aborts the body read
  mid-stream, `runCatching` eats it, and the asset SILENTLY never installs (this is exactly what hid the
  197 MB overlay for a whole debug cycle — no crash, no log, just no footprints). `KokoroInstaller`,
  `RoutingGraphStore` and `OverlayTileStore` each derive a `downloadHttp` with `callTimeout(0)` +
  `readTimeout(60s)` for the body; only the tiny manifest fetch stays on the shared short-timeout client.
  Settings → Voice → **Voice library** is the browser; the
  multi-speaker variant picker (Advanced) only shows when the SELECTED catalog voice has >1 speaker.
  **To ship a pb/endpoint fix WITHOUT an app release:** edit the drifted field in
  `calibration.json`, **bump `version`**, **re-sign** (`./scripts/sign-calibration.sh`),
  commit `calibration.json` + `calibration.json.sig` to `main` — users pick it up on
  their next launch (raw.githubusercontent caches ~5 min). Keep the compiled
  `Calibration.DEFAULT`'s field VALUES (paths, endpoints, voice defaults) in sync with
  `calibration.json` when you cut a release — but `DEFAULT.version` intentionally STAYS `1` (the
  remote bundle's higher `version` must always win the adopt-if-newer check; the shipped
  `calibration.json` is at v10, `DEFAULT.version` at 1 — that gap is by design, not drift). **Phase 2 (done): the search parser's positional
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

- Location: AOSP `LocationManager` only — never `FusedLocationProviderClient`. **Fix discipline
  (2026-07-04 audit, don't regress):** NETWORK (BeaconDB) fixes are DROPPED during nav and used in
  browse only when GPS has been quiet ≥12 s (`NETWORK_FIX_QUIET_MS`, OsmAnd's `useOnlyGPS` pattern) —
  they're 100-1000 m off and teleported the dot/reroutes; inter-fix `dt` comes from
  `loc.elapsedRealtimeNanos` (monotonic — `loc.time` mixes GNSS UTC with the network system clock and
  a negative dt bypassed the outlier gate); fixes with accuracy >50 m never feed `NavSession`; the
  `minDistanceM=0f` registration MUST stay 0 (a distance filter starves fixes at a standstill — the
  frozen-speedo/creeping-puck bug). Measured speeds pass a SYMMETRIC accel-bounded gate against the
  last ACCEPTED value (`gateMeasuredSpeed`, 2-fix persistence escape, shared with replay) — one-sided
  spike filters self-latch (a down-glitch to 0 then rejects every real speed as an up-spike forever).
- Nav guidance discipline (2026-07-04 audit): prompt/turn-now distances SCALE WITH SPEED in
  `NavEngine` (max(fixed, v×T); `spoken` stores band SLOTS not metres), one prompt per update speaking
  the TRUE distance, silent catch-up past maneuvers >75 m behind, proximity arrival (crow ≤40 m) +
  no rerouting within 150 m of the destination or while stationary, off-route measured on the
  windowed/anchored projection (never whole-polyline min), reroutes are single-flight + cooldown +
  latch-clear-on-failure (a failed fetch must NOT kill rerouting — the event is edge-triggered), and
  ETA sums the remaining STEP durations × traffic ratio (never remaining/avg-speed). The route line's
  driven/ahead cut is a GEOMETRY split (`ROUTE_AHEAD_LAYER` suffix over a traversed-grey full line) —
  MapLibre bakes line-gradients into a 256-texel texture, so a gradient stop can never render a crisp
  cut and there is no `line-trim-offset` in MapLibre; don't "simplify" it back to a gradient.
- Nav drive-report fixes (2026-07-05): (1) **Route line z-order** — the route line inserts BELOW the first
  symbol layer, but Liberty's first symbol is `road_one_way_arrow` (~idx 61) which sits UNDER the `bridge_*`
  layers (~63-82) → bridges painted over the route on bridges (it "vanished"). `VelaMapView.ensureLayers`
  anchors instead to the first symbol AFTER the last `bridge_*` layer (a real label), so the route draws above
  all road+bridge geometry, still below text. (2) **Exit consolidation** — OSRM splits one exit into ramp +
  fork/merge steps, each spoken separately ("Take exit 15"…"Keep right"…"Merge"). `RouteGeometry.consolidateExits`
  folds a ramp's immediately-following, <500 m-gapped FORK/MERGE run into the ramp maneuver (sums distances so
  they still tile the polyline; stops at any real turn / far gap) → one prompt. Unit-tested. (3) **Feet steps**
  — `formatDistance` (banner) + all 11 `NavStrings.spokenDistance` (voice) round feet Google-style: 50 ft at/above
  100 ft, 10 ft below. (4) **Voice K/C** — `EnNavStrings.expandForSpeech` rewrites `<XX>-<n>` (CA-99, SR-99) →
  "State Route n" so espeak's G2P doesn't mangle the bare 2-letter code's onset. (6) **Continue/straight lane silence** — a CONTINUE/STRAIGHT speaks its lane preface ONLY for a GENUINE fork (an "off" lane that itself goes straight-ish, e.g. "use the left 2 lanes to stay on I-80"); a plain turn bay at an intersection (off lane marked only left/right) while you sail straight through is silenced (`Route.continueHasGenuineFork` gates `NavEngine`'s escape hatch) — Google stays silent there and the road-just-renames case had been over-speaking. (5) **Traffic-light landmarks
  ("pass the light, then turn") — NOT built, feasible + planned:** OSM `highway=traffic_signals` is keyless via
  Overpass (verified dense: 300 in a small Seattle bbox), mirror `OverpassPois`; count signals between the
  vehicle and the next turn along the polyline; add a clause in `NavStrings`. Needs a real-drive calibration of
  the snap threshold + how many lights to mention, so ship it OFF by default. The neural voice's occasional
  attack-clip at sentence starts is a model-level Piper limit, separate from the CA-99 fix.
- Heading (browse-cone facing direction when stopped, where GPS course is noise): raw
  `SensorManager` `TYPE_ROTATION_VECTOR` (`core/location/HeadingProvider`) — a plain
  Android sensor, not GMS. **Navigation never uses it** (the nav heading comes from the
  matched road); it's pushed to state only in browse + only on a real change, so it can't
  spam recomposition during nav.
- Nav-puck speed fusion: raw `TYPE_LINEAR_ACCELERATION` + `TYPE_ROTATION_VECTOR`
  (`core/location/MotionProvider` → world-frame accel; `core/location/SpeedKalman` fuses it
  with GPS speed — accel predicts between fixes, each fix measures). Collected ONLY during
  nav, written into a plain array (never compose state — sensor-rate recomposition). Missing
  sensors degrade to `a = 0` = the old constant-speed dead reckoning.
- Voice: AOSP `TextToSpeech`, engine-selectable — never hard-depend on Google TTS. **Plus an
  in-process neural option (Piper):** Vela bundles the **sherpa-onnx** runtime (arm64 `.so`, from the
  `tts-runtime` release AAR — gitignored, fetched in CI, NOT committed) and downloads a **Piper VITS**
  voice into `filesDir/piper/<id>/`, run in-process by `app/voice/PiperSynth` (sherpa `OfflineTts` +
  `AudioTrack`) behind the `:core` `voice/NeuralSynth` seam (the AAR can't live in the `:core` library
  module). The default is **HFC Female** (`en_US-hfc_female-medium`, ~67 MB); it becomes the default
  voice once present. **Non-obvious, all device-only (compiler-clean):** R8 MUST `-keep class
  com.k2fsa.sherpa.onnx.**` (JNI resolves classes by original name); and you must generate the WHOLE
  utterance before `AudioTrack.play()` (streaming underruns → AudioFlinger drops the track → SIGABRT).
  **A Piper voice is a SINGLE-language model** — reading another language's nav text through it is
  gibberish (the "English voice read Russian after a language override" bug). `NeuralSynth.voiceLanguage`
  exposes the loaded voice's lang (id prefix, `en_US-hfc_female` → "en"); `VoiceGuide.speakNow` compares it
  to the language the nav text is GENERATED in (`NavStringsRegistry.current().locale`) and, on a mismatch,
  routes to **Android `TextToSpeech` in the target language instead** (`speakViaSystem`, lazily creating a
  default engine as the fallback — the system `tts` is NOT shut down when the neural voice is active). If the
  system TTS has no voice for that language either, guidance stays **silent** (never mangles it through the
  wrong voice) and fires `langUnavailable(lang)` → `MapViewModel` flashes a "get a &lt;language&gt; voice in
  Settings → Voice" hint. So switching the app/system language to one whose voice isn't downloaded degrades
  gracefully, it doesn't read the new language through the old model.
  **(History: earlier iterations bundled Kokoro (`KokoroSynth`) and Matcha; both were removed after
  on-device A/B — Kokoro was ~0.4× realtime even on a Pixel 9. `MapViewModel` reclaims their old model
  dirs and sanitizes stale `vela.kokoro`/`vela.matcha` prefs to Piper. `project_vela_kokoro_tts` memory
  is that historical record, not the current design.)**
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
- **Routing is OPEN, not Google (2026-06-28).** Turn-by-turn comes from **FOSSGIS OSRM**
  (`RouteGeometry.route`, `steps=true`, per-mode `routed-car`/`-bike`/`-foot`) — complete,
  street-named maneuvers + real geometry. **Highways identify by `ref` not `name`** — `parseOsrmRoute`
  captures `ref`/`destinations`/`exits` (not just `name`) and `osrmPhrase` uses them ("Take exit 72B
  toward …"); `Maneuver.ref` feeds the banner shield even when the text shows a name (fixed 2026-06-30 —
  before, highway steps were nameless + shield-less). **`routeOsrm` retries 3× w/ backoff** — a transient
  community-server blip otherwise drops nav to Google's abbreviated (nameless) steps. Google's keyless
  `/maps/preview/directions` returns
  **abbreviated** steps for longer routes (a 6-mi route came back with 2 of ~10 turns), so it's
  demoted to (a) the **live-traffic source** — `GoogleMapsDataSource.applyTraffic` scales OSRM's
  free-flow duration by Google's in-traffic/typical ratio and maps its congestion spans onto the
  OSRM geometry — and (b) the **fallback router** when OSRM is unreachable. The two are fetched in
  parallel. Rationale: routing is a solved open-data problem; Google's edge is traffic/POIs/hours/
  reviews, not routing. **`OSRM_BASE` is the FOSSGIS community server (fair-use) — point at a
  self-hosted OSRM/Valhalla before any real release.** (This retired the keyless-step parsing as the
  primary path + the Nominatim "fill the missing road name" hack.)
- **Traffic-AWARE routing (option 3, 2026-06-28).** OSRM's free-flow route ignores live traffic, so
  when Google *rerouted around a jam* its path differs from OSRM's. `directions()` detects this
  (`RouteGeometry.divergent` — sample Google's polyline, true if any point strays >700 m from OSRM's
  line) and, only then, re-runs OSRM **through ~12 points sampled off Google's polyline**
  (`sampleVias` → `routeVia`) so we follow Google's jam-avoiding path *with* full OSRM street-named
  steps. Multi-waypoint OSRM returns one leg per via with spurious `arrive`+`depart` at each boundary
  — `parseOsrmRoute` filters all but the true first-depart/last-arrive. Free-flow routes (the common
  case) stay pure OSRM, untouched. The traffic-snapped route leads **only when it earns it** — its live
  ETA must be ≤ OSRM free-flow best × `SNAP_ETA_MARGIN` (1.2), else a divergent-but-not-faster snap steps
  aside for OSRM's clean route (fixed 2026-06-30 — the old code always led with the snap on divergence, the
  "fucky reroute"). The `directions` diag logs `snapKept`/`gEta`/`osrmFF` to tune the margin from real
  side-by-side data. **Per-alternate re-rank (2026-07-01):** each Google route in `root[0][1]` carries its
  OWN `duration_in_traffic` (`parseRoute` reads `summary[10][0][0]` per route), so the returned list is now
  **sorted by live in-traffic ETA — fastest leads, Google-style.** (Earlier note that this was "impossible"
  was wrong: it's only true for the OSRM-only alts, which share `gTop`'s ratio; Google's alts carry real
  per-route traffic.) **Sort key = the EXACT value the picker shows (2026-07-05, supersedes the earlier
  `* gRatio` "common-axis" attempt):** `compareBy({ durationInTrafficSeconds ?: durationSeconds }, { provisional })`.
  `RouteOption` displays `durationInTrafficSeconds ?: durationSeconds` and tags the min-SHOWN route "Fastest", so
  the sort MUST use the same expression — else (as `* gRatio` did) the top/selected route and the "Fastest"-tagged
  route diverge and the fastest-shown route isn't at the top (a real-drive bug, fixed). The axis is already fair
  without the fudge factor: PRIMARY routes go through `applyTraffic` (their `durationInTrafficSeconds` = free-flow
  × the top Google route's ratio) and Google's alternates carry their own per-route `duration_in_traffic`, so a
  route only falls back to raw `durationSeconds` when there's genuinely no traffic signal for it — and then
  sorting/showing that free-flow time is self-consistent. Do NOT bake an estimate onto `durationInTrafficSeconds`
  to "fix the axis" — `Route.hasLiveTraffic` keys off its nullness. Provisional routes are the stable tie-break.
  **Alternates = GOOGLE's own alternate routes, NAME-ON-PICK (2026-06-30):** we fetch all of Google's
  routes but used only the top; `directions()` now returns the named primary + each distinct Google route
  as a **provisional** `Route` (`Route.provisional` — polyline + live ETA now, turn-by-turn deferred),
  `dedupeRoutes`, prefers them over OSRM's free-flow alts, caps at `MAX_ROUTES`=4. Picking a provisional
  alternate (`MapViewModel.selectRoute` → `MapDataSource.nameRoute`, also on `startNav` as a safety) NAMES
  it — currently by snapping its polyline through OSRM (`routeVia`, guarded to reach dest) + re-applying
  Google's traffic. So only the route you drive gets snapped, and the picker loads fast. **Next = swap
  `nameRoute`'s snap for on-device GraphHopper MAP-MATCH where the region's downloaded** (wobble-free); the
  snap stays the fallback. (NB: MapLibre vector tiles only cover the on-screen area, so they can't name a
  whole long route — a universal-clean version would need fetching+decoding the route's MVT tiles.)
- **Why not "always snap to Google's path"?** (measured 2026-06-28, the serverless question.) Google's
  keyless **polyline is complete** (decoded from `root[0][7][i]`) even though its *step text* is
  abbreviated — so we *can* always trace it. But doing it cleanly needs **map-matching**, and the
  public infra won't reliably give it: FOSSGIS **`/match` caps at 10 trace coords** (11+ → `TooBig`;
  confidence ~0.01 at that sparsity) and public **Valhalla `/trace_route` times out**. The serverless
  fallback — dense-waypoint `/route` (40–100 vias, no cap) — *does* reproduce Google's path exactly,
  **but a via landing on a turn gets swallowed into a via arrive/depart → ~1-in-10 named turns lost**
  (measured: dropped "turn right onto Village Green Drive"). That turn-loss is the exact bug we fixed,
  so we do **not** always-snap. Clean always-snap (and offline routing) is gated on an **on-device
  engine** — see the next bullet. Option 3 is the public-server stopgap and stays as the online/fallback
  path. **No backend needed for any of this** (the serverless constraint holds).
- **On-device routing engine = GraphHopper (`core/data/RouteEngine` + `GraphHopperRouteEngine`).**
  Pure-JVM, runs on ART — **validated end-to-end on a Pixel 5a** (`:ghprobe`, a throwaway instrumented
  test; delete once this is wired). Chosen over Valhalla (no maintained Android map-matching binding) /
  BRouter (no street names) / Mapbox (token-gated). It's wired as a `:core` dep
  (`libs.graphhopper.mapmatching`, **OSM-import deps excluded** — osmosis/protobuf/woodstox/xmlgraphics
  are Android-hostile + only needed to *build* graphs, which we do off-device). **Three ART workarounds,
  all in `GraphHopperRouteEngine` — don't remove:** (1) **`graph.dataaccess=MMAP`** (default RAMDataAccess
  static-inits a JDK-16 `VarHandle` method ART lacks); (2) **override `createWeightingFactory()`** to a
  hand-rolled `SpeedWeighting`+access-block (v11 compiles custom models via **Janino** → JVM bytecode ART
  can't load); (3) **swallow `close()`** (MMAP unmap uses `Unsafe.invokeCleaner`, absent on Android — keep
  one engine for the process lifetime). **R8:** `consumer-rules.pro` keeps `com.graphhopper.**` + hppc/jts/
  jackson wholesale (GraphHopper resolves a lot reflectively) and `-dontwarn`s the excluded/absent refs —
  release build is clean (**but +~10 MB APK; tighter keeps / on-demand delivery is a later optimisation**).
  Graphs are built off-device, one per region, and (Phase 1b) downloaded alongside the offline tiles;
  `RouteEngine` is selected by connectivity + graph-presence. **Speed needs Contraction Hierarchies:**
  plain flexible A* with the interpreted `SpeedWeighting` was **7.6 s** for a 24-mi trip on a Pixel 5a;
  **CH prepared on the SAME `SpeedWeighting`** (the engine declares `setCHProfiles`, `tools/graphbuilder`
  builds it) → **188 ms**. Graphs MUST be built with CH on that weighting (CH bakes the build-time
  weighting), to **internal** storage (FUSE external was I/O-bound). **`SpeedWeighting` ETA gotcha:** it
  reports time as `distance_m/speed` as if `car_average_speed` (km/h) were m/s — 3.6× too fast — so the
  engine AND `graphbuilder` override `calcEdgeMillis` to `distance_m·3600/kmh`; keep them identical.
  **Encoded values = `car_access, car_average_speed, road_access, max_speed`** — the string is byte-identical
  in `GraphBuilder.java` and `GraphHopperRouteEngine.kt` (a mismatch fails graph load); keep it so. `max_speed`
  (added 2026-07-04) is the OSM `maxspeed` posted limit (km/h), a **passive stored column** (`OSMMaxSpeedParser`
  auto-registers; NOT in the weighting/CH, so it doesn't change routes) read by the speed-limit badge via
  `GraphHopperRouteEngine.currentRoadLimit(lat,lng)` — a `LocationIndex` snap + `EdgeIteratorState.get` off the
  **base graph** (CH-safe). **Adding/removing an encoded value is a BREAKING graph-format change**: old graphs
  lack the EV and `getDecimalEncodedValue` THROWS — `currentRoadLimit` swallows it (badge hidden, no crash),
  but to actually light the badge up you must **re-bake + re-host every region graph** via `routing-graphs.yml`
  (verified: a Monaco rebuild carries `max_speed` + CH cleanly). Existing installs keep their old graphs until
  re-downloaded (no version-discriminator yet — a manifest `schema` bump so they auto-update is a follow-up).
  **Status: DONE end-to-end, on-device verified, graphs HOSTED + multi-region.** `RoutingGraphStore` (`:app`)
  downloads region CH graphs from a manifest (`BuildConfig.ROUTING_MANIFEST_URL`, override `-ProutingManifestUrl=`
  for local testing) into `filesDir/graphs/<id>/`, merging each into `filesDir/graphs/index.json`
  (`[{id,bbox:[S,W,N,E]}]`); `GraphHopperRouteEngine` lazy-loads a `GraphHopper` per region and routes a trip on
  the **smallest region whose bbox covers BOTH endpoints**, falling through to the next-smallest if that
  graph can't make the trip (`inBox`, unit-tested). Smallest-first because Geofabrik extract boxes carry a
  buffer that spills across borders (British Columbia's box dips into Seattle) — the same rule drives the
  picker's "covers your location" label + the tiles→routing combine, so all three agree. Settings → **Offline** (one
  section: a **Map area** subhead = viewport tile download, and a **Routing regions** subhead = the picker) is
  a location-aware picker (regions covering the GPS fix sort first + flag "covers your location"; a name
  filter appears once the catalog is large); downloading
  offline map *tiles* for an area ALSO pulls that area's routing region (`MapViewModel.downloadRoutingForArea`).
  `directions()` uses the engine when OSRM is empty. A trip must fit ONE region's monolithic graph (cross-region
  → online).
  **Hosting + world catalog (DONE 2026-06-30):** graphs + `routing-manifest.json` are assets on the
  **`routing-graphs` GitHub release** (fixed-tag prerelease, never the "Latest" the APK tracks). The catalog is
  **`tools/routing-regions.json`** (135 regions, grouped by continent; `big:true` = country-sized). CI
  **`.github/workflows/routing-graphs.yml`** is a **race-safe matrix**: `prep` (group/ids → matrix) → parallel
  `build` (each region: `graphbuilder` CH graph → upload its own `<id>.zip` + emit a manifest *entry* artifact,
  via `scripts/build-routing-region.sh MANIFEST_MODE=emit`) → one `merge` (`scripts/merge-routing-manifest.sh`
  folds all entries into the manifest in a single replace-by-id upload — parallel jobs never clobber it; a
  `concurrency: routing-graphs-manifest` guard also serializes whole runs so back-to-back dispatches queue
  instead of racing two merge jobs). Public
  -repo Actions minutes are free, so a continent builds per dispatch. **bbox MUST come from `osmium -g
  header.boxes`** (declared extract region) — `data.bbox` (node extent) is polluted by outlier nodes and made
  Oregon falsely cover WA. Build one region locally: `scripts/build-routing-region.sh <id> "<name>" <pbf-url>`
  (all-in-one), or the graph alone: `./gradlew :tools:graphbuilder:run --args="region.osm.pbf out-dir"`. Local
  manifest test: serve a manifest+graph, `adb reverse tcp:8099 tcp:8099`, build with
  `-ProutingManifestUrl=http://127.0.0.1:8099/manifest.json` (localhost cleartext allowed by
  `res/xml/network_security_config.xml`; all other traffic stays HTTPS).
- **Open building-footprint overlay (`app/offline/OverlayTileStore` + `VelaMapView`, DONE 2026-07-04,
  device-verified in Silver Firs).** Fills the map's building gaps where OSM is thin (a suburb the
  Microsoft→OSM import never reached) with **Microsoft US Building Footprints (ODbL)**. Off-device, CI bakes
  ONE `.pmtiles` per US state (`scripts/build-overlay-region.sh` → tippecanoe `-l building -Z14 -z16
  --drop-densest-as-needed`; `-Z14` not `-Z12` — starting at z12 ballooned WA to 271 MB, z14 → 197 MB) →
  `building-overlays` GitHub release + `building-overlay-manifest.json`, matrix workflow
  `.github/workflows/building-overlays.yml` (clone of the routing one, `MANIFEST_MODE=emit` +
  `scripts/merge-overlay-manifest.sh`), catalog `tools/overlay-regions.json`. In-app: `OverlayTileStore` is a
  single-file sibling of `RoutingGraphStore` (`filesDir/overlays/<id>.pmtiles` + `index.json`; PMTiles-magic
  guard). **The overlay STREAMS online — no download needed to see houses (2026-07-05).** `refreshBuildingOverlays`
  runs on every camera-idle (`onViewport`) and emits, per view, a list of full `pmtiles://` URIs: a
  **`pmtiles://file://<abs-path>`** for any DOWNLOADED region (offline), and **`pmtiles://https://…<region>.pmtiles`**
  for the smallest-covering region in view that ISN'T downloaded — MapLibre 11.7+ reads that hosted archive by
  **HTTP range requests** (verified: GitHub release assets 302→release-assets host with `accept-ranges: bytes`,
  MapLibre follows the redirect), fetching only the visible tiles, so footprints appear as you pan. The manual
  **`MapViewModel.downloadOverlayForArea`** (still smallest-covering-box, pulled alongside the area's tiles) is now
  ONLY for going fully offline. Render: `VelaMapView`'s `LaunchedEffect(buildingOverlays, styleRef, darkTheme)`
  adds each URI as a `VectorSource` (used verbatim — the URI already carries `pmtiles://file://` or
  `pmtiles://https://`) + a `FillLayer` `setSourceLayer("building")` **`addLayerBelow` the OSM `building` layer**,
  themed to the exact OSM building fill/outline (`#323f54`/`#3f4e66` dark, `#dde1e7`/`#c4c9d1` light) so overlay
  footprints are indistinguishable from real OSM ones and OSM still wins wherever it has data. `buildingOverlays`
  is de-duped so panning within one region doesn't churn the map sources. **The load-bearing DOWNLOAD bug was NOT
  the render** — it was the `callTimeout(0)` rule above: the 197 MB body aborted at the shared client's 12 s cap,
  silently (that only ever mattered for the offline download; streaming reads a few KB/tile). Device-verified:
  Silver Firs from the local file + downtown/suburban Reno (Nevada, not downloaded) streamed from the hosted
  `nevada.pmtiles` (131 range requests, no PMTiles errors). NB GitHub release hosting works but isn't a CDN — a
  real deployment should host the PMTiles behind a CDN for snappier range reads. `OVERLAY_MANIFEST_URL`
  BuildConfig overridable `-PoverlayManifestUrl=` like routing. BREAKING-ish: an overlay is DATA (ODbL), orthogonal
  to the app's GPLv3, obligation met by tippecanoe `--attribution` + the release publishing derived tiles under ODbL.
  **World catalog (`tools/overlay-regions.json`, 250 regions):** TWO Microsoft sources picked by each row's
  `source`, both handled by the ONE build script (`SOURCE` env): **`us-legacy`** = a US state's single
  `.geojson.zip` (Microsoft US Building Footprints, 51 states+DC); **`ms-global`** = a world country's
  quadkey-partitioned GeoJSONL from Microsoft's **Global ML Building Footprints** (`global-buildings/dataset-links.csv`
  → `awk` the country's `Location` rows → curl+gunzip each `.csv.gz` into one ndjson → tippecanoe `-P`; ~199
  countries). Country **bboxes are the union of the dataset's own z9 quadkey tiles** (self-consistent with where
  footprints exist); US-state bboxes are Geofabrik extract bounds. **Big countries are CHUNKED** (>1500 MB
  compressed source → India, Brazil, Russia, Germany, Japan, …18 of them): the catalog splits each into
  sub-national pieces by **quadkey PREFIX** (`qkprefix`; adaptive recursive split until each chunk ≤ ~1500 MB —
  India → 24 pieces), the build script's awk filters the country's rows to that prefix, and each chunk gets its
  own union bbox so the **app's smallest-covering-box rule picks the piece covering the user** (no app change,
  and it fits CI disk + hosts under GitHub's 2 GB/asset limit). Only the whole-US aggregate + continental
  aggregates + duplicate Locations (CzechRepublic→Czechia, DemocraticRepublicoftheCongo→CongoDRC) are dropped.
  The catalog is 361 regions — **over GitHub's 256-job matrix cap** — so each row carries a `group` (`us` / `world`
  / `chunk`) and dispatch is **one group at a time** (`-f group=world`); run-level concurrency is OFF so groups
  build concurrently, only the merge job serialises. The app/manifest are source-AGNOSTIC — the emitted manifest
  row is always `{id,name,url(asset),sizeMb,bbox}`, so no app change was needed for countries OR chunks.
- **Open house-number overlay (`VelaMapView` + `scripts/build-address-region.sh`, DONE 2026-07-05,
  device-verified in Silver Firs).** Microsoft footprints have geometry but **no addresses**, so house numbers
  come from a SECOND overlay: **OpenAddresses** address POINTS → per-state `.pmtiles` (`-l address`, keep the
  `number` prop) → `address-overlays` GitHub release + `address-overlay-manifest.json` (`ADDRESS_MANIFEST_URL`,
  `-PaddressManifestUrl=`). Data source = OpenAddresses batch API: `/api/data?source=us/<st>/statewide&layer=addresses`
  → its current `job` → `https://v2.openaddresses.io/batch-prod/job/<job>/source.geojson.gz` (GeoJSONL of Points
  with `number`/`street`; **42 US states have a `statewide` source**, the rest are county-only). Render:
  `VelaMapView`'s `LaunchedEffect(addressOverlays, …)` adds a `VectorSource` (the URI) + a **`SymbolLayer`**
  `setSourceLayer("address")`, `textField(get("number"))`, `textFont(["Noto Sans Regular"])`, size 10, grey +
  white halo, **minZoom 16** (matches the basemap `vela-housenumber` layer; collision thins dense blocks) —
  placed ON TOP (addLayer) so numbers read over the building fills. **Streams online exactly like buildings**
  (`MapViewModel.refreshAddressOverlays(center)` on every camera-idle → smallest-covering region's
  `pmtiles://https://…` URI; reuses `overlayStore.manifest()` which is manifest-URL-agnostic). **NOT** the
  building overlay (different data + a Symbol not Fill layer + its own release/manifest). CI:
  `.github/workflows/address-overlays.yml` (clone of building-overlays), catalog `tools/address-regions.json`.
  **The house numbers fill the exact gap the basemap `vela-housenumber` (OSM `addr:housenumber`) leaves in new
  suburbs** — verified real Silver Firs numbers (13713, 13801, 14008…) rendered over the MS footprints.
- **Traffic lights + stop signs drawn on the map (`OverpassTrafficSignals.fetchControlsInBox` + `VelaMapView`,
  2026-07-05).** OSM `highway=traffic_signals` (a stoplight icon) and `highway=stop` (a red STOP octagon) as a
  non-interactive `SymbolLayer` (`vela-controls`, icons `vela-signal`/`vela-stop`) drawn **beneath** the POI dots
  + pins, `minZoom 16` + collision so a dense grid stays legible. Data is keyless Overpass (sibling of the
  `fetchAlong` nav-landmark fetch + `OverpassPois`), fetched by `MapViewModel.refreshTrafficControls` from
  `onViewport` **only at z ≥ `CONTROLS_MIN_ZOOM` (16)**. Controls are STATIC, so it fetches a box padded 50%
  beyond the viewport and **reuses it while the center stays in the inner half** (`controlsBox`) — panning/driving
  through an area triggers no refetch, sparing the fair-use Overpass server; only nearing the box edge refetches
  (single-flight + 350 ms settle). The layer/updater are identity-gated like markers/ambient (`lastAppliedControls`)
  so a nav speedo tick doesn't re-tessellate them. No app setting (zoom-gated); no PMTiles/CI (live Overpass, unlike
  the building/address overlays). NB the `TRAFFIC_*` constants in `VelaMapView` are a DIFFERENT thing — Google's
  live-traffic raster overlay; the controls use `CONTROLS_*`. Needs a real-drive glance to confirm density/size feel.
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
