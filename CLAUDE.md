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
  libritts_r). The browsable catalog is `PiperCatalog` in `:core` (pure data, unit-tested, ~23 curated
  en_US/en_GB voices; URL = `…/tts-models/vits-piper-<id>.tar.bz2`). `PiperSynth.ensureLoaded` reloads when
  the selected voice changes; `PiperSynth.reloadVoice()` is the SINGLE switch trigger — it bumps the
  generation counter (aborting any in-flight utterance) then tears down + rebuilds on the same serial
  worker, so `tts` is never freed mid-`generate()`. `MapViewModel.migrateFlatLayoutIfNeeded` (first thing
  in `init`) relocates the old flat single-voice install in place (rename, copy-fallback, verify-gated,
  re-runnable) — never re-downloads. **Model downloads MUST NOT use the shared OkHttp client** — its
  `callTimeout(12s)` (scrape-bounding) aborts any 67–131 MB voice download; `KokoroInstaller` derives a
  `downloadHttp` with `callTimeout(0)`. Settings → Voice → **Voice library** is the browser; the
  multi-speaker variant picker (Advanced) only shows when the SELECTED catalog voice has >1 speaker.
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
- Nav-puck speed fusion: raw `TYPE_LINEAR_ACCELERATION` + `TYPE_ROTATION_VECTOR`
  (`core/location/MotionProvider` → world-frame accel; `core/location/SpeedKalman` fuses it
  with GPS speed — accel predicts between fixes, each fix measures). Collected ONLY during
  nav, written into a plain array (never compose state — sensor-rate recomposition). Missing
  sensors degrade to `a = 0` = the old constant-speed dead reckoning.
- Voice: AOSP `TextToSpeech`, engine-selectable — never hard-depend on Google TTS. **Plus an
  in-process neural option (Kokoro):** Vela bundles the **sherpa-onnx** runtime (arm64 `.so`, from the
  `tts-runtime` release AAR — gitignored, fetched in CI, NOT committed) and downloads the ~126 MB
  `kokoro-int8-multi-lang-v1_0` model into `filesDir/kokoro`, run in-process by `app/voice/KokoroSynth`
  (`OfflineTts`+`AudioTrack`) behind the `:core` `voice/NeuralSynth` seam (the AAR can't live in the
  `:core` library module). Becomes the default voice once present. **Non-obvious, all device-only
  (compiler-clean):** R8 MUST `-keep class com.k2fsa.sherpa.onnx.**` (JNI resolves classes by original
  name); the multi-lang model needs `lang="en"` or it native-`exit()`s; its Chinese `lexicon`/`dictDir`
  SIGABRT on Android (omit — English uses espeak `dataDir`); and you must generate the WHOLE utterance
  before `AudioTrack.play()` (streaming underruns → AudioFlinger drops the track → SIGABRT). See
  `project_vela_kokoro_tts` memory.
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
  per-route traffic.)
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
