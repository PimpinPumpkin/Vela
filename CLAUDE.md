# Vela - project guide for Claude

Degoogled Google-Maps replacement for Android (the "NewPipe for Maps"). Open
vector tiles for the basemap; the device scrapes Google's public web endpoints
per-user (no backend, no shared API key) for POIs, routing and traffic-aware
ETAs. Targets GrapheneOS / no-GMS ROMs; F-Droid distribution. GPLv3.

## ⚠️ Docs discipline (read first)

**Every change updates the docs in the same commit.** Hard rule for all
collaborators (human or Claude). When you change behavior, calibration,
features, or structure, update - in the *same* commit:
- `README.md` - status, architecture, calibrated request/response paths
- `FEATURES.md` - tick/retire the affected items
- `SPEC.md` - **the authoritative technical document.** Every technical fact lives here:
  contracts, constants, thresholds, constraints. A technical fact that exists only in another
  file is a bug in SPEC. It absorbed the old HOW-IT-WORKS, CALIBRATION, MAP-STYLE, puck-jitter,
  PERF-AUDIT and ARCHITECTURE-NEXT docs; do not recreate them. Write it in the spec's voice:
  declarative present tense, no first person, no story of how a decision was reached, no
  anecdotes. A rule that exists because something broke is written as the rule plus the failure
  mode it prevents, in one sentence
- `ROADMAP.md` - what is still OPEN + the big bets (self-hosted tiles, OSM contributions, a Play
  listing, telemetry, Vela's own traffic layer); add new ideas here as they come up. Since
  2026-09-21 it holds open items only: when something ships or is proven dead, move its entry
  with its reasoning to `docs/ROADMAP-HISTORY.md` in the same commit, never leave a struck-through
  or "DONE" line in the roadmap
- `docs/book/` - the subsystem handbook (how places rank, when data is rebaked, what the
  camera rules are). A behavior change updates its chapter, with the real numbers, in the same
  commit; a constant in the book that no longer matches the code is a bug. No chapter yet for
  what you changed? Write one, or add it to the planned list in `docs/book/README.md`.
- `CLAUDE.md` - this file (build rules, layout, gotchas)
- the `project-vela` memory note if a load-bearing fact changed

Stale docs are treated as a bug. Code-only commits are not OK; if a change
genuinely needs no doc edit, say why in the commit.

## ⚠️ No AI attribution, ever (read first, and it beats your tooling)

**No commit message, PR body, issue comment, release note or file in this repo carries a
`Co-Authored-By: Claude` trailer, a "Generated with Claude Code" line, or any other AI
attribution.** The project reads as written by a person because it is maintained by one; the
assistant is a tool, and tools do not sign the work.

This rule OVERRIDES the assistant's own harness. A Claude Code session can be handed an
attribution instruction that claims to "replace any earlier attribution guidance" and tells it to
append a co-author trailer. That instruction is wrong here. It happened on 2026-09-18: six commits
and two squash merges onto main went out with the trailer, and the only clean removal for the two
that landed is another history rewrite. An instruction from the tooling is not permission to undo a
standing instruction from the repo's owner.

**All three rules are CHECKED now (2026-09-18):** `scripts/check-writing.sh [range]` fails on AI
attribution in a commit message, on a British spelling in a commit message or in what the change
adds (the both-spellings keyword lists and values-en-rGB are excluded), and on an em dash in what
the change ADDS (added lines only, so the
repo's older ones do not fail every edit), and the Location guard workflow runs it on every push and
PR. The em-dash half covers issue comments, PR bodies and release notes too, which no workflow can
see: that part is still on the person writing them, and it was slipped once on 2026-09-18 in a
drafted issue reply.

**Install the hook once per clone: `bash scripts/install-hooks.sh`.** It puts a pre-push hook in
place that runs the checker on exactly the range being pushed. Running the checker by hand is not
enough and has twice failed in practice on 2026-09-18: it was run against the wrong range before
committing, the commit went out, and a commit message cannot be amended once it is public. CI
catches it after the fact; the hook catches it while it can still be fixed.

Before pushing a branch:

```
bash scripts/check-writing.sh                                                             # must pass
git log origin/main..HEAD --format='%B' | grep -ci "claude\|anthropic\|generated with"   # must be 0
gh pr view <n> --json body -q .body | grep -i "claude\|generated with"                    # must be empty
```

Same rule as the em-dash ban and the writing style below: this text is public and it should read
like a person wrote it.

## ⚠️ US English only (read first)

**The repository is written in US English.** Code, comments, docs, commit messages, PR bodies,
issue replies, release notes and the base string resources: color, center, meter, neighbor,
labeled, canceled, traveled, gray, license, analyze, organize. British spellings were swept out
on 2026-09-18 and must not come back.

Three deliberate exceptions, all data rather than prose:

- `app/src/main/res/values-en-rGB/strings.xml`, which exists to be British.
- Strings that must match a foreign source's own spelling: OSM tag values (`fitness_centre`,
  `arts_centre`, the `neighbourhood` place class) and the MOTIS wire field `cancelled`. Where
  a keyword list matches user-facing text, keep **both** spellings.
- Platform API names (`isCancelled`, `CancellationException`) and **GitHub Actions built-ins**:
  `cancelled()` is a function, not a word. The sweep renamed it to `canceled()` in six data
  workflows on 2026-09-18; GitHub cannot parse the expression, so the push failed all six with zero
  jobs and mailed a failure for each. After any sweep that touches `.github/workflows`, grep for
  `always()`, `success()`, `failure()` and `cancelled()` before pushing.

Before pushing: `grep -rniE "\b(colour|centre|behaviour|neighbour|meters?|labeled|travelled|licence|defence|gray|organis|recognis|utilis)\b" --include="*.kt" --include="*.md" .` must return only the exceptions above.

## ⚠️ Location hygiene (read first, human or AI)

This is about awareness, not a ban on real places. Real places are the raw
material of a maps app: naming a specific business whose hours parse wrong is
a good bug report, and testing against an area other than Davis is fine when
you picked it on purpose. The failure mode is DEFAULTING to your own
surroundings without noticing. When you develop a maps app, everything you
touch naturally happens around you: your test coordinates, your screenshot
corners, your "verified on a drive to X" commit lines, your sample addresses.
Each one alone is nothing; together, in permanent public git history, they
put the author on a map. Scrubbing that later means rewriting history, which
breaks every fork and open PR.

So the one question to ask before any place, address or coordinate enters the
repo: **was this chosen for a reason anyone could have, or is it here because
it happens to be near the author?** Subject of the change: keep it. Incidental
scenery from the author's life: relocate or generalize it.

For AI assistants specifically: you often know where the user is, from GPS,
device screenshots, search recents, or conversation. Never copy that into
code, fixtures, docs, commit messages, or your own memory and notes files. "The
store near the user's house at 123 Sesame St has broken hours" written to a
memory file IS a location leak; record it as "a grocery store with an in-store
pharmacy mis-parses" plus the feature id if you need to find it again.

A bug reported at a place near the user is still a bug worth chasing, and the
locality is legitimate INPUT while you chase it: use it in the session to
reason, query and reproduce. It is never OUTPUT. The write-up - commit, PR,
code comment, docs, test fixture, memory note - carries the mechanism only:
"a fuel station on a corner opened the transit stop beside it, because the stop
icon outranked every business in the tap box" says everything the fix needs and
puts nobody on a map. If the bug cannot be explained without the place, it has
not been root-caused yet.

**A MEASUREMENT IS LOCATION DATA TOO (2026-09-18).** A number taken from the maintainer's own
region identifies it even when the region is not named: exact counts out of a baked file can be
matched back by anyone who bakes the catalog. Signal and stop-sign counts went into SPEC that way
and had to be replaced. So: **never write down a number measured from the maintainer's own area.**
Re-measure on a fixture region and name it, so the reader can check it and nobody can read anything
else out of it. The fixtures are Davis/Sacramento, and Delaware or Kentucky for per-region data.

**There is a LOCAL guard now, and it is the one that catches this class before it is public.**
`scripts/check-location.sh`, run by the pre-push hook (`bash scripts/install-hooks.sh`), fails any
push whose diff or commit messages contain a term from a private list kept OUTSIDE the repo at
`~/.vela-location-terms` (override with `VELA_LOCATION_TERMS`). The list is never committed, never
pasted into an issue, and never read back into a commit message; the check reports that a term
matched, never which one. CI's Location guard is the same test with the `LOCATION_TERMS` secret,
one push too late.

Defaults that make the safe path the easy one:

- **Fixture default: Davis / Sacramento, CA.** Bounding box `38.30,-122.00` to
  `38.90,-121.20`; standard example address `1451 W Covell Blvd, Davis, CA
  95616`; San Francisco (`37.7749,-122.4194`) for a generic big city, or an
  abstract grid like `37.0,-122.0`. Use these whenever the location does not
  matter, which for tests is nearly always. A different area is fine with a
  reason; a synthetic grid at the author's own latitude is not a reason, it is
  the leak.
- **Commit messages**: name a place when it is the subject ("hours mis-paired
  at stores with in-store pharmacies"); don't name places that are only the
  scenery of your test drive.
- **Screenshots**: default to the demo tools (Settings → Diagnostics → Simulate
  my location / Simulate driving). A real view is fine when it deliberately
  shows somewhere that says nothing about you; check the corners either way -
  search recents, POI labels and street names all talk.
- **Recorded trips, diagnostics exports and adb dumps carry raw GPS.** Never
  attach them to issues, commits, or CI artifacts; share privately when a
  maintainer asks. **The in-app Share on a trip (Settings > Diagnostics > the
  trip > Share) makes it shareable** by TRIMMING the private ends: every fix
  within a radius (default 400 m) of the start, the end, the META destination
  and Home/Work is deleted, and the middle is left at FULL precision. Trimming,
  not rounding, on purpose - a bug lives in the middle of a drive and needs real
  geometry, while a 1 km round still names your block. It also strips the
  destination and label out of the META header and drops the route line + any
  maneuver + any spoken line inside a trimmed zone (a polyline starting at your
  driveway leaks the same fact the fixes would). The dialog shows what it
  removed and the remaining first fix - check that before sharing. The
  implementation is `core/replay/TripScrub` (the old `scripts/scrub-trip.py`
  was an incomplete copy of the same idea and is gone).
- **Before committing, scan the diff** for coordinate-shaped numbers, numbered
  streets and zip codes, and put each one through the question above.

## Build

- **Always build release** for anything run on-device - debug builds visibly lag
  during map scroll/nav. R8 lives in the `release`
  buildType. Use `./gradlew :app:assembleDebug` only as a compile check. **Measured 2026-09-14
  on the 4a, same cold place tap + sheet drag + review scroll: DEBUG 14.8% janky frames, 90th
  percentile 69 ms; RELEASE 1.1%, 28 ms.** A whole session of "the sheet lags" was the debug
  build. `assembleRelease` falls back to the debug keystore when no env keystore is set, so it
  installs over the 4a's app with `adb install -r`; use it for anything the user will feel.
- **WebView boot is off the tap path (2026-09-14):** Chromium's first start (half a second of
  main thread + a sandbox process) used to land at the first place tap of a fresh app, under the
  sheet's open animation. `warmWebViewsWhenQuiet` (first camera idle + 4 s, main-thread idle
  handler, skipped while navigating or with a sheet up) boots it early; searching still warms
  after results. On a resolved tap the photos and popular-times loads start 700 ms after the
  reviews scrape, so three page loads do not hit the 4a together under the animation.
- `./gradlew :core:test` runs the pure-logic unit tests (polyline, nav engine).
- **MapScreen is at the JVM 64 KB method limit (2026-09-13).** CI builds release only; the
  DEBUG variant (what the 4a runs) carries Compose source info and failed with "Method too
  large: MapScreenKt.MapScreen" while main built green. Four blocks are split out (same file,
  below MapScreen): `MapSurface` - the `VelaMapView` call, about 220 lines of arguments, moved
  2026-09-18 - plus `BoxScope.BuildingDebugBadge`, `BoxScope.NavTurnBanner` and
  `SearchEntryHost`. Content lambdas do not count toward the limit, direct composable calls
  and their argument lists do, so when you add to MapScreen and the debug compile dies with
  that error, move a call with a long argument list into a small private composable.
- **D-pad regression suite (`dpad_test_suite/`).** On-device, reproducible. Run after any change
  that touches focus (see `docs/dpad.md`):
  - `run_all.sh` - per-surface focus assertions (bare map → search bar, Settings/Welcome/dialog/menu
    auto-focus, Choose-on-map engages, Directions pill reachable).
  - `audit_static.sh` - EXHAUSTIVE source scan (no device): every clickable/toggleable/selectable
    has a `dpadHighlight` ring, every gesture has a key path, no bare `DropdownMenu`/`AlertDialog`,
    no `isSystemInDarkTheme`; fails on any real violation. Wire it into CI.
  - `audit_dynamic.sh` - EXHAUSTIVE on-device tour: every surface opens focused, focus is never lost
    across a full traversal, BACK exits. "Nothing escapes the auditor."
- **Managing saved trips (2026-08-26).** Settings > Diagnostics: every recorded trip can be
  RENAMED (the row's overflow menu -> `TripStore.rename`, file IO only; the header surgery itself is
  **`TripLog.renameHeader` in :core**, beside the format so writer and reader cannot drift, and
  unit-tested by `TripRenameTest` - a comma or newline in a name would shift/split the header
  fields and make the whole recording unparseable, and a drive cannot be recorded twice. The
  write goes to a temp file and is renamed over the original, so it can never be half-written), and "Select trips"
  turns the list into a multi-select whose Share opens `TripBatchShareDialog` and sends the set as
  **ONE zip** (2026-09-16, user report: ACTION_SEND_MULTIPLE attachments did not arrive in
  Signal). ONE trim radius applies to the whole batch (`MapViewModel.scrubTripsForSharing`, off
  the main thread, re-run when the radius changes), and the dialog adds up what comes off across
  the set (`TripShareBatch.summarize`: trips kept of picked, points removed/kept, trips left out)
  before `shareTripsZipIntent` builds `cache/export/vela-trips-<now stamp>.zip` (ACTION_SEND,
  `application/zip`, same FileProvider path) with one `vela-trip-<drive stamp>.csv` entry per
  trip (`TripShareBatch.entryNames` suffixes "-2" when two drives share a minute). **Every entry is
  TRIMMED** through `scrubTripForSharing` (review 2026-09-06: this path once shipped the raw
  traces); a trip that trims to nothing is left out and counted, never sent raw, and a selection
  of one still goes out as a zip. The raw file is reachable
  only from the single-trip dialog's "Share full trace". **Row layout (2026-09-16, "too many
  lines"):** line 1 = the drive's start (`DateUtils`, year only when not this year), line 2 =
  distance · duration · name (name last so a long address is what gets cut), then Replay and
  Share icons and a VelaMenu overflow (Rename, Delete). Distance and duration come from
  `TripLog.stats` (one pass per file, also the fix count) via `TripMeta.distanceM`/`durationMs`;
  the list therefore opens every file and is loaded on `Dispatchers.IO`, never in composition.
  Both share dialogs take their radius options from `TripScrub.RADIUS_OPTIONS_M` and start on
  `TripScrub.defaultRadius(redact)`: the LARGEST option when "Redact places in exports" is on.
  Trip geometry is never rounded for that toggle (trimming beats rounding, see below). Optional
  pref `trip_name_on_save` (Settings
  toggle, shown only while trip recording is on, default OFF) makes `finishTrip` - which now
  RETURNS the kept `TripMeta` instead of Unit - arm `MapUiState.tripToName`, and MapScreen prompts
  for a name on the map right after the drive. TRAP: an early `return@forEachIndexed` inside the
  trip-row composable lambda crashes the Compose compiler ("No mapping for symbol" during IR
  lowering); the selection branch is if/else for that reason.
- **Auditing a real drive.** A saved trip stores the navigated route too (`core/replay/TripLog`
  format, shared by `:app`'s `TripStore` writer and the `:core` reader). To diff what the nav
  cards/voice said against the plotted route from a shared trip CSV, call `TripLog.audit(csv)`
  (→ `NavReplay.Report.summary()`) or run the on-demand harness:
  `./gradlew :core:testDebugUnitTest --tests '*auditSharedTripLog' -DvelaTrip=<abs.csv> --rerun-tasks`
  then read the report from the test-results XML `system-out`
  (`core/build/test-results/testDebugUnitTest/*.xml`). The property passthrough lives in
  `core/build.gradle.kts` (`tasks.withType<Test>` forwards `velaTrip`) - without it the test JVM
  never saw `-D` and the harness silently skipped. It flags silent/missed turns, too-early
  announcements, and lying card distances - built so a travel log can be analyzed without knowing
  where it broke. **Trips are SEGMENTED**: every route the drive used (start + each reroute/
  faster-route swap) is its own `RP/RD/M` block, activated at the fix where it appears;
  `TripLog.parse().segments` carries them, audit + in-app replay are segment-aware, and replays
  are HERMETIC (`NavSession.replayMode` - no live reroute/recheck fetches, recorded swaps play
  back via `replaySetRoute`; the map view scales the puck's clocks by `replaySpeedup`). Never
  audit/replay a multi-block trip against a single mashed route - that was the "arrow on another
  street / arrived mid-replay" corruption. NB replays of OLD trips faithfully play back the dirty
  fixes the old pipeline recorded (BeaconDB teleports) - judge the engine on fresh recordings.
- **Sharing a trip trims its ends** (`core/replay/TripScrub`, 15 tests, plus `TripShareBatchTest`
  for the multi-trip zip; Settings → Diagnostics → a trip's Share, or Select trips → Share). A trip leaks its owner's endpoints in SIX places, not one, and all six are
  handled: the fixes, the `META` destination, the `META` **label** (trips are named after where
  they went, so it is usually a street address), the maneuvers, the route polyline's start, and
  the `S` spoken lines ("Arrive at ..."). It TRIMS rather than blurs: every fix within the chosen
  radius of the start, the end, the destination and the user's Home/Work is deleted and the middle
  kept at FULL precision, because rounding protects the ends weakly (a 1 km round still names a
  block) while destroying the geometry the trip exists to diagnose. Timestamps are rebased to zero
  (replay only ever uses the deltas). **Unknown line kinds are DROPPED, not passed through.** The
  format is append-only, so a tag added later would otherwise be published by a scrubber written
  before it existed: **if you add a line kind to `TripLog`, decide in `TripScrub` whether it is
  safe to share.** **Two things added 2026-09-13 (user ask, after the faster-route replay):** `RD` carries
  a fifth appended field, the route's PROVENANCE flags (`provisional;abbreviated;offline;traffic;
  steps=N`, `TripLog.encodeRoute`, read back as `RouteSegment.flags` and printed beside each swap
  in the audit), because the adopted-Google-alternate bug was invisible in the file until the M
  lines were read by hand; and every export is named by the drive's local date and time
  (`vela-trip-2026-09-13-1432.csv`, `-full` for the raw trace, `vela-diag-<stamp>.json`,
  `vela-nav-trace-<stamp>.csv`, `MapViewModel.tripStamp`) instead of an opaque id or a bare
  "shared". The name still never carries the label or the destination. **And three more columns the same day:** every fix carries its PROVIDER
  (`loc.provider`, so a network fix that slipped into a drive is visible) and the engine's
  off-route hit count (`nav.offRouteHits`, a reroute about to fire shows as 1, 2, 3); and every
  nav DECISION goes into the trip as a `K,<t>,<text>` line through `NavSession.onNote` (the same
  text `diag.record("nav", ...)` gets): recheck offered / kept with the candidate's saving and
  why, faster route accepted / dismissed, reroute attempts, swaps. A K line never holds a
  coordinate by contract (the one note with a position keeps it in the diag ring only), so
  `TripScrub` passes K through like J and B. The audit prints fixes-by-provider and the decision
  timeline. Adding a note: call `note()` in NavSession, never `diag.record` directly. The scrub is non-destructive (the on-device trip is never modified) and the
  raw file is still reachable behind "Share full trace". Two rules added by the 2026-09-06 review
  (15 tests): an `S`/`J`/`B` event survives only if a fix within `EVENT_NEAR_MS` (3 s) of it
  survived, because a Home/Work zone passed MID-trip deletes fixes inside the kept time window
  and the spoken "turn onto <its street>" has to go with them; and a route block whose polyline
  trims to nothing drops its `RD` and `M` lines too, or `TripLog.parse` folds them into the
  previous block. The share dialog computes the report in a `LaunchedEffect` on IO, not in
  `remember` on the main thread.
- **Demo / simulate-driving mode** (Settings → Navigation, off by default, pref `demo_drive` in
  `vela_settings`). Drives a planned route as a SYNTHETIC GPS trace so nav can be shown/tested
  **anywhere** with no real fix - this is how the Davis `docs/screenshots/05-navigation.png` was shot
  while the phone was elsewhere. `DemoTrace.fromRoute(polyline)` (pure `:core`) → one clean
  `ReplayFix`/sec, fed through the SAME hermetic `LocationProvider.replay` path a recorded trip uses
  (`MapViewModel.startDemoDrive`, `startNav` branches on the pref). It's presented as real nav, not a
  replay: `MapUiState.demoDriving` hides the "Stop replay" pill and the normal **End** (`stopNav`)
  cancels the demo job (its `finally` resumes live GPS + resets the dot/route). **Turn it OFF to
  navigate for real** - while on, every "Start" simulates instead of using GPS.
- **Simulate-my-location (demo)** (`ui/SimLocation.kt`, Settings → Navigation, off by default,
  pref `sim_location` in `vela_settings`). BOTH sim entry points CANCEL the stale-location timer
  (2026-07-09): the pinned demo dot gets no fresh fixes, so a timer armed by the last real fix
  grayed the dot ~30 s in and nothing ever turned it blue again. The sim branch in `startLocation()`
  covers app restart with the toggle already on; `simulateLocationHere()` covers flipping the toggle
  mid-session (it cancels `locationJob` but the timer the collector armed outlives that cancel - the
  first fix was missed there and the bug came straight back). A sibling of demo-drive for demos/screenshots: when on,
  Vela pretends to be at the map center (captured when you flip the toggle), so the location dot,
  the directions ORIGIN ("Your location"), and recenter all read from there instead of your real
  GPS - that is how every Davis/Sacramento screenshot was shot from elsewhere without leaking a real
  position. Process-wide reactive holder like `TransitLayer` (`init` in `VelaApp`); `MapViewModel`
  applies it - `startLocation()` pins `myLocation` to the sim point (guard sits AFTER the replaying
  guard so demo-drive still wins), `simulateLocationHere()` captures `mapCenter`,
  `stopSimulateLocation()` resumes live GPS. NB search/place-sheet DISTANCES are `near`-relative
  (Google computes them off `mapCenter`), so those read local from wherever the map is centered, with
  or without this toggle; sim-location is specifically about the dot + route origin. **Turn it OFF
  for real navigation.**
- **GitHub releases are TWO different things - check the tag before touching one (2026-07-09).**
  `v0.*` tags are app releases (nightly prereleases, weekly stables). Every OTHER tag is
  **infrastructure file hosting** (9 as of 2026-07-13): `tts-runtime` (the sherpa-onnx AAR CI
  fetches at build time), `asr-models` (the on-device dictation engines: Whisper/SenseVoice/Moonshine), `routing-graphs` (region
  graph zips + manifest), `poi-packs` (state place packs + manifest), `address-overlays`,
  `building-overlays` and `maxspeed-overlays` (PMTiles + manifests), `map-fonts` (Roboto glyph
  zip), `flock-cameras` (the ALPR/DeFlock camera dataset `.bin` + manifest, weekly-refreshed).
  Those assets exist NOWHERE
  else - not in git, not on any server - the release IS the download backend the app's manifest
  URLs point at. **Nightly titles say `nightly` and their notes open with "Nightly build."
  (2026-09-21); promote-stable retitles to the bare version and regenerates the notes.** Deleting one takes the corresponding offline feature down globally until its
  workflow rebuilds everything (hours). This is not hypothetical: the first nightly-prune run
  (2026-07-09) deleted four of the five and broke every offline download; `routing-graphs`
  survived only because the repo has 400+ releases and it sat past the query's `--limit 200`
  window. TWO standing rules: (1) any automation or cleanup that deletes/edits releases must
  select by tag pattern `v0.*`, never by "prerelease" or "old" (the infra releases are old
  prereleases by design, to stay off `releases/latest`); (2) any `gh release list` logic must
  assume 400+ releases and paginate or bound by tag - unpaginated list queries caused both the
  deletion and a wrong damage report.
- CI: **stable / nightly / canary channels (2026-08-07, supersedes the per-push nightly).**
  `.github/workflows/ci.yml`: pushes to `main` AND `canary` build + test only (APK as a
  workflow artifact, no release) - a push can never mint a release anymore, which retires the
  "flurry of updates" complaint structurally (#214 feedback). The NIGHTLY prerelease
  `v0.4.<run>` (versionName `0.4.<run>`, versionCode `2000+run`, run = ci.yml's own monotonic
  run number - releases MUST stay in ci.yml, a separate workflow would reset the counter and
  regress versionCode) is cut by a DAILY CRON (10:30 UTC) that skips when main has not moved
  since the last v0.* tag, or on demand via `gh workflow run ci.yml` (`-f force=true` recuts
  the same code) - the in-car "fix it now" path is push to main + dispatch. **`canary` is the
  working branch**: Claude pushes there freely, batches assemble there, and merging/pushing
  canary to main is the deliberate release-worthy act. Obtainium nightly users opt in with
  "include prereleases". **Canary is ALSO a real update channel (2026-08-07):** every canary
  push replaces the single APK on the rolling `canary` release (versionName
  `0.4.<run>-canary`, same monotonic `2000+run` versionCode line as every channel so switching
  channels is always an upgrade; the tag is deliberately NOT v0.* so the nightly/stable
  queries, the prune and F-Droid never see it). **Since 2026-09-22 the release is DELETED AND
  RECREATED per push (`--cleanup-tag`, `--target` the pushed commit, title "Vela 0.4.<run>-canary"):
  GitHub orders releases by creation date, so the edited-in-place release from August sat
  fifteen rows down under every nightly and data release and read as "the canaries are gone".
  The download URL and the updater's tag lookup are unchanged; the swap is a few seconds of
  404 the updater reads as nothing newer.** The in-app updater is channel-aware:
  Settings > About > "Update channel" picks Stable/Nightly/Canary (pref `update_channel`,
  migrated from the old `update_nightly` boolean via `SelfUpdater.channel()`); the canary
  check reads versionName/versionCode out of the canary release NOTES (the tag never changes)
  and falls back to the newest nightly when that is ahead, so a stale canary never strands
  anyone. The canary notes body starts with the one line "Canary branch." (2026-09-15: the old
  breakage warning was the first sentence of the in-app What's new dialog); the versionName /
  versionCode lines and "Latest change:" follow it and the updater's regexes read them. Obtainium CANNOT cleanly track canary (its version detection keys on tags and the
  canary tag is constant) - canary rides the in-app updater or a manual grab from the release
  page; that containment is deliberate, so prerelease Obtainium users never get surprise
  canary builds. **Docs-only pushes don't run CI or cut a nightly (2026-07-09):**
  `paths-ignore` skips markdown/docs/LICENSE/fdroid-metadata/issue-template changes (a mixed
  docs+code push still builds - it skips only when EVERY changed file matches). Workflow-file
  edits deliberately still build. `[skip ci]` in a commit subject is the manual suppressor;
  NB an fdroid/metadata-only change also skips the F-Droid index rebuild (it rides CI
  completion) - dispatch `fdroid-repo.yml` by hand if the description edit should go live
  before the next code push. `.github/workflows/promote-stable.yml` (cron Mondays 16:00 UTC +
  manual dispatch) **promotes the newest nightly to stable**: same tag, same signed APK, no
  rebuild - it flips `--prerelease=false --latest` and regenerates the notes to span
  everything since the previous stable. Default Obtainium installs and the in-app updater
  (which reads `releases/latest` = latest STABLE) therefore move weekly; a nightly user whose
  versionCode is ahead of stable simply is not offered anything until stable passes them.
  **Release notes are a real changelog** built from the commit
  subjects since the previous `v0.[0-9]*` tag (the glob spans minor bumps so a fresh
  0.3 release still finds the last 0.2 tag; checkout is `fetch-depth: 0` so the tag
  history is present; the publish step formats them + a compare link into `--notes`).
  So **commit subjects ARE the user-facing changelog** - write them as plain-language
  changelog lines (see the writing-style rule: no em-dashes, human voice), not terse
  hashes. (Switched off the rolling-nightly scheme 2026-06-16 - it
  confused Obtainium. Bumped `0.1.<run>`/`1000+run` → `0.2.<run>`/`2000+run` on
  2026-06-18 after local dev builds were hand-set with `-PappVersionCode` in the
  1000s, got installed on a test phone, and left it *ahead* of the release line - 
  Obtainium then saw the next release as a downgrade. **Keep local dev builds
  below 1000**, e.g. `-PappVersionCode=1`, so the release line always wins. Bumped
  versionName `0.2.<run>` → `0.3.<run>` on 2026-07-08, and `0.3.<run>` → `0.4.<run>` on
  2026-07-11 (the twelve-PR polish wave: sampled palettes, Roboto glyphs, dot tier, POI
  speed, avoid toggles). NEVER a literal `0.4.0`: the updater's tag regex takes the RUN
  number for the versionCode compare, so a hand-named v0.4.0 would read as vc 2000 and
  never be offered. (The 0.3 bump was a big UI batch: stadium-pill
  chips, rebuilt results detents, full-screen-results z-order fix) plus community
  files + the in-app updater. The versionCode base stays `2000+run` because the run
  number is global/monotonic, so vc keeps rising across the minor bump; only the
  *name*'s minor changed.)
  **F-Droid repo channel (2026-07-09):** `.github/workflows/fdroid-repo.yml` rebuilds a signed
  self-hosted F-Droid repo (latest stable + newest nightly) and deploys it to GitHub Pages
  (`https://pimpinpumpkin.github.io/Vela/repo`, fingerprint + user instructions in `FDROID.md`,
  metadata in `fdroid/metadata/app.vela.yml`). Pages is set to build_type=workflow.
  **Triggers: `workflow_run` on CI / Promote weekly stable completing green on main** - NOT the
  release event alone: releases created by CI's own GITHUB_TOKEN never fire events for other
  workflows (GitHub anti-recursion), so a release-event-only trigger left the index stale until a
  manual dispatch (found 2026-07-09). The release trigger stays but only fires for user-token
  release actions. **Channels: the build appends `CurrentVersion(Code)` of the LATEST STABLE to
  the app metadata**, so the index SUGGESTS the stable - default F-Droid users update weekly on
  stables, and the newer nightly in the same index is "unstable", offered only to users who
  enable unstable/beta updates for Vela in their client (before the pin, clients offered the
  highest version = everyone was silently on nightlies). GitHub/Obtainium stays the native
  nightly channel. GOTCHA: never re-run only a FAILED deploy job on this workflow - the Pages
  artifact belongs to the original attempt and a deploy-only rerun 404s it; dispatch a fresh
  run instead.
  The index-signing keystore is `~/.vela-signing/fdroid.p12` (secrets `FDROID_KEYSTORE_BASE64` /
  `FDROID_KEYSTORE_PASS`; password + fingerprint in `~/.vela-signing/fdroid.env` - back both up).
  This is NOT the official f-droid.org catalog (their from-source build can't take the bundled
  sherpa-onnx runtime); it's our own repo any F-Droid client can add.
  **The project WEBSITE rides the same Pages artifact (2026-07-15).** `site/` (a self-contained
  single-page showcase, no external requests; screenshots = the public Davis set as webp in
  `site/assets/`) is copied to the artifact root by `fdroid-repo.yml`, so it serves at
  `https://pimpinpumpkin.github.io/Vela/` beside `/repo` and `/fonts`. ⚠️ NEVER add a separate
  Pages-deploy workflow for the site - actions/deploy-pages replaces the WHOLE site, so a
  site-only artifact would take down the F-Droid repo channel and the map fonts (MapFonts'
  probe would evict its cache and every install falls back to Noto). Site edits: `site/**` is
  in CI's paths-ignore (no nightly for copy tweaks) and is a push trigger on `fdroid-repo.yml`
  (the deploy still needs release APKs to exist, which they always do).
  **The APP SIGNING CERTIFICATE is published (issue #294, 2026-08-26):** SHA-256
  `29938b4858063e42e677ff95c901cd48248a7f032a3ae85f9b9e5617568b0d36`, in README ("Check you got
  the real thing") + FDROID.md. Read it out of any signed release with
  `apksigner verify --print-certs <apk>` - NO keystore password needed, the certificate is public;
  verified identical on stable and canary, so every channel shares the one key. NB `keytool -list`
  needs the password AND the keystore is `~/.vela-signing/vela-release.jks` (not `vela.keystore` -
  a wrong path makes keytool print its error to stderr, so a `| grep SHA256` pipeline looks
  silently empty). Do NOT confuse this with the F-Droid fingerprint in FDROID.md
  (`F374920F...`), which signs the repo INDEX, not the app.
  Release signing uses repo secrets `VELA_KEYSTORE_BASE64`,
  `VELA_KEYSTORE_PASSWORD`, `VELA_KEY_ALIAS` (set; keystore at `~/.vela-signing/`,
  outside the repo - back it up). Without them the APK is debug-signed. Version
  override: `-PappVersionName`/`-PappVersionCode`. An optional `MAPTILER_KEY`
  secret → `BuildConfig.MAPTILER_KEY` (`-PmaptilerKey`) switches the basemap to
  MapTiler Streets (Google-like, with a dark variant by system theme); empty
  locally → keyless OpenFreeMap. **Never commit the MapTiler key** - CI-secret +
  BuildConfig only.
- **Baseline profile (2026-07-16):** `app/src/release/generated/baselineProfiles/baseline-prof.txt`
  is COMMITTED and baked into every release build (`androidx.baselineprofile` plugin +
  `profileinstaller` runtime dep) so sideloaded nightlies are AOT-compiled at install time instead
  of running interpreter-cold until overnight dexopt (the "did you even R8" jank report - the APK
  was fine, ART just hadn't recompiled it). Regenerate with `./gradlew :app:generateBaselineProfile`
  - it runs the `:baselineprofile` macrobenchmark journey on a Gradle-managed EMULATOR (pixel6Api34
  aosp). ⚠️ NEVER switch it to `useConnectedDevices`: the test harness UNINSTALLS the target app
  when it finishes, which on a real phone wipes saved places, trips and permission grants (it did
  once, 2026-07-16, on the wired test phone). `.github/workflows/baseline-profile.yml` (monthly cron
  + dispatch) regenerates on a KVM emulator and opens a PR when the profile drifts.
- Toolchain: AGP 8.7.3, Kotlin 2.1.0, Gradle
  8.11.1, compileSdk 35, minSdk 26, Java 17, Compose + Hilt + version catalog.
- Release signing from env: `VELA_KEYSTORE_PATH` / `VELA_KEYSTORE_PASSWORD` /
  `VELA_KEY_ALIAS` (default alias `vela`); falls back to debug keystore locally.
- **No blocking IPC/IO from a composable body.** `SettingsScreen` used to call
  `vm.voiceEngines()` (a `PackageManager.queryIntentServices` binder IPC + per-engine
  `loadLabel`) directly in composition, re-running on every recompose - invisible jank on a
  Pixel, a >5 s ANR on a slow keypad phone (found by ys770's fork, fix taken 2026-07-08).
  Load such data with `produceState { withContext(Dispatchers.IO) { … } }`;
  `VoiceGuide.availableEngines()` also caches the system-engine enumeration per process.
- **The ambient category fan-out is CONCURRENCY-BOUNDED (2026-07-17, `GoogleMapsDataSource.ambientFanout`,
  a `Semaphore(4)`).** `nearbyPlaces` fires ~13 category requests, each loaded WHOLE and built into a
  JsonElement tree (~30 MB for a dense area). Firing all 13 in parallel allocated ~400 MB of transient
  parse trees in a burst on a fresh launch / fast far pan, filled the 512 MB largeHeap, and stalled EVERY
  allocation on a blocking GC (P9-measured: 401 MB live, dozens of 80-86 ms `WaitForGcToComplete blocked
  Alloc`, 13% janky frames, 400 ms 99th-percentile - the "horrible at fresh launch / fast pan" report).
  Bounding `fetchTerm` to 4 concurrent parses cut it to ~64 MB live / 1 stall / 2.3% jank / 150 ms 99th,
  SAME final pool (the streaming `onPartial` paint keeps first dots fast). The semaphore is a class field
  so overlapping calls (a pan mid-load) share the cap. **Don't unbound this fan-out**; if a dense area
  still spikes, lower the permit or move the scrape parse off `parseToJsonElement` to a streaming reader.
- **`nearbyPlaces` returning EMPTY is not an answer and must NEVER be cached (2026-07-17).** Each
  fan-out term swallows its network error into an empty list, so OFFLINE comes back as an empty
  SUCCESS, not an exception. The ambient path treats null and empty identically: keep whatever is
  painted, serve the freshest covering store entry regardless of age (stale-if-error), leave
  `lastAmbientCenter` unset so the next settle retries. Caching an empty pool overwrote a good
  area in the durable store with a blank one and wiped the painted dots (device-caught via the
  `VelaAmbient` logs: 1800 -> 1600 places after one airplane-mode pan). The durable store
  (`ambient_cache.json`, 32 areas x 200 slim places, 14-day TTL, write-through from non-empty
  live results only) also drops blank areas at load. A place the details fetch finds permanently
  closed is written back (`markClosedInAmbient`: flag flipped in every cached copy + pruned from
  the painted set + persisted) so the dead dot stays gone across restarts - and the
  recently-viewed pin deliberately skips closed places (it bypasses the closed filter).
- **Idle building warm-up (2026-07-23, `VelaMapView` camera-idle listener):** after 2.5 s of
  stillness on the browse map at z13.5-15.9, an off-screen `MapSnapshotter` renders ONLY the active
  building-overlay pmtiles sources at z16.2 over the camera target, which pulls the z15/16 footprint
  tiles through MapLibre's shared ambient cache so the later zoom-in paints from cache (the "houses
  take a sec at 200 ft" report; the snapshotter and the map share one cache database - the Android
  Auto renderer already relies on that). One warm per ~2 km bucket per session, canceled by any
  camera move, skipped during nav; logcat tag `VelaWarm`. It renders no basemap (those tiles cap at
  z14 and are already resident) - the overlay range-fetches were the whole delay.
- **The MS building-overlay gate is BOUNDED render-probing; keep its three rules (2026-07-17,
  `VelaMapView` `runOvlGate`).** The overlay (`vela-ovl-*`) is drawn beneath OSM `building` to fill
  OSM's gaps, so where OSM is dense it is pure occluded overdraw; a gate probes rendered OSM
  coverage and shows/hides the whole overlay per viewport. Rules learned the hard way on a Pixel 4a:
  (1) `queryRenderedFeatures` is a SYNCHRONOUS main-to-render-thread round trip and the map's idle
  events can fire per frame (the puck loop nudges renders), so events only mark the verdict dirty -
  probes run at most once per `OVL_GATE_MIN_GAP_MS` (1.2 s), 12 points, z16+ only. Probing straight
  from an idle event froze the 4a to ~1 fps. (2) Measure coverage by AREA (grid points on a
  building), never by feature COUNT - tile generalization merges a dense downtown block into 2-3
  giant polygons, and a count reads that as "empty" and draws MS over a city core. (3) Layers are
  born HIDDEN and only REVEAL after `OnDidBecomeIdle` (a finished render): a "sparse" verdict
  before tiles land is indistinguishable from a real gap, and acting on it flashes MS over a city
  for ~3 s. Hiding is always safe immediately. A floor-blocked call schedules ONE deferred retry
  (`postDelayed`) - on a truly idle map there is no later event, and without the retry an
  uncommitted verdict sticks forever (the overlay never appeared in a plains town). Gate state
  (`ovlGateKey`/`ovlDirty`/`ovlLastEval`/`ovlRenderSettled`) is COMPOSABLE-scoped because
  `getMapAsync` can register listeners twice; per-registration state made both copies probe.
  User off-switch: Settings → Advanced "Fill missing buildings" (`BuildingOverlay`); debug badge +
  fps readout: Settings → Developer (`BuildingDebug`), badge in `MapScreen`.
- **Building zoom tiers are deliberate, Google-matched (2026-07-17).** Flat `building` fill z16-24
  (was 14); `building-3d` extrusions z17+ (was 16), growing 30% to full height by z19
  (`applyBuilding3dGeometry`, shared by all four palettes) with `fillExtrusionVerticalGradient(true)`
  so faces read discrete. At ~500 ft dense-city towers used to lean over and bury the roads. The
  search/place fly-to lands at z15.5 (~1000 ft, matches the locate fly) so a city search arrives
  BELOW every building tier - roads+labels+POIs only - instead of slamming all of it at z16.5.
- **MEMORY PRESSURE IS HANDLED NOW (2026-07-23, ported from vela-dpad, credit alltechdev):**
  `app/ui/MemoryPressure` (holder, `init()` in VelaApp before the Coil cap reads it) fans
  `Application.onTrimMemory` out to registered releasers - before this NOTHING implemented
  ComponentCallbacks2 and a TRIM_MEMORY_COMPLETE freed 0 KB. Registered: the ASR model (severe
  trims + a 120 s idle reap - the loaded model costs ~267 MB PSS and used to live for the whole
  process; an in-flight listen blocks release via an inFlight counter, or freeing native memory
  mid-decode is a use-after-free crash), PiperSynth (CRITICAL only - dropping the nav voice
  mid-drive costs a missed turn), MapLibre's native caches (`mapView.onLowMemory()`, registered in
  the map's DisposableEffect), all five hidden WebViews (severe trim = immediate reap on the main
  thread), and Coil (severe trim clears the bitmap cache). `MemoryPressure.lowRam` (isLowRamDevice
  OR heap class < 128 MB; debug override `adb shell setprop debug.vela.lowram true`) drives the
  constrained-device path: 16 MB Coil cap (vs 48), no ASR warm-up at launch, no speculative
  WebView warms per search, and - via `:core` `LowRamMode` (same seam as CategoryFilter) - an
  8-term ambient fan-out with a !7i30 pool instead of 15 terms at !7i60 (school/park are KEPT in
  the subset: with ambient active the OSM poi layers are hidden, so they have no second source).
  deleteAsrEngine releases the loaded model before deleting files (Remove used to reclaim disk
  but no memory). Any NEW large/native holding must register a releaser here.
- **Memory: the browse map runs near the heap ceiling, so keep allocation LOW (2026-07-13).**
  Panning already churns ~180 MB/12 s at baseline (ambient POI scrape + parse per pan) - this is
  pre-existing (0.4.542 hit ~260 MB too), close enough to the default ~256 MB heap that any EXTRA
  churn triggers a blocking GC per frame (staccato pan/zoom) and OOM-crashes on a burst. Two rules:
  (1) `android:largeHeap="true"` is set (raises the ceiling ~2x -> GC headroom); don't remove it.
  (2) **Any Overpass / large-HTTP-body reader MUST stream-parse** - `Json.decodeFromStream(body.byteStream())`
  into a tiny `@Serializable` DTO, NEVER `resp.body.string()` + `parseToJsonElement` (that held ~5-10x
  the wire size in transient heap and OOM'd mid-read; it's what a Flock `out body 4000` fetch per pan
  did - fixed in `OverpassAlprCameras`; `OverpassTrafficSignals` (limit 6000) is the same pattern and a
  pending follow-up). And **NEVER lower a per-viewport Overpass fetch's min-zoom** without shrinking the
  box - dropping `FLOCK_MIN_ZOOM` 13->11 made the box ~16x bigger and was the tipping point. (The z11 gate
  returned 2026-07-13 ONLY because the bundled on-device dataset answers the fetch with no network and the
  Overpass fallback stream-parses - the rule stands for any layer still doing per-pan Overpass DOM parses.)

## Layout

- `:core` is the UI-agnostic "extractor" (NewPipeExtractor pattern). `:app` is
  the Compose UI. Don't let MapLibre or Android UI types leak into `:core`
  (convert `LatLng` at the view boundary).
- The one seam is `core/data/MapDataSource`. `MockMapDataSource` is the default
  and keeps the entire app usable offline; `google/GoogleMapsDataSource` is the
  real scraper.
- **Android Auto (`app/car/`).** `VelaCarAppService` is a NAVIGATION-category templated
  `CarAppService` (manifest service + `xml/automotive_app_desc.xml` `<uses name="template"/>` + the
  `androidx.car.app.*` permissions + `minCarApiLevel=1`); a sideload appears in the car launcher only
  with AA developer "Unknown sources" on, hence `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`.
  **THE GATE IS GOOGLE'S AND NOTHING HERE OPENS IT (issue #179, settled twice):** Android Auto
  accepts only entertainment categories for an app that draws on the car screen, so declaring maps
  honestly is refused and declaring "game" gets a map that grays out the moment the car moves; the
  sideloaded apps that DO draw while driving bundle Google's unreleased car toolkit, which cannot
  be redistributed. Play, or a full-Android head unit, or wait for cars running Android natively.
  What Vela CAN do is not break the workarounds: AAEnabler and King Installer set the INSTALL
  SOURCE to Play and some units accept that, and a self-update overwrites it, so `InstallSource`
  holds the APK back and offers it as a file (see the updater notes).
  **Full car-side nav** via a `screen/` package: `MainCarScreen` (Home/Work/recent/saved,
  `PlaceListNavigationTemplate`) → `SearchCarScreen` (`SearchTemplate`) → `RoutePreviewCarScreen`
  (`RoutePreviewNavigationTemplate`, alternates) → `ActiveNavCarScreen` (`NavigationTemplate`).
  `VelaCarSession` owns its OWN AOSP LocationManager feed into the shared `NavSession` (nav runs with
  the phone UI closed) and handles `action.NAVIGATE` geo intents (assistant "navigate to X").
  **Map rendering = `CarMapRenderer`** (MapLibre's public `MapSnapshotter` → Bitmap → the car surface;
  NOT the old VirtualDisplay+Presentation path). It snaps the puck to the route (map-matching), gates
  the location feed to GPS-only (drops coarse network/fused fixes that jumped the puck), and eases the
  puck/heading between the ~1 Hz fixes so the map glides rather than lurches.
  **Car map round two (user 2026-09-21, "a bunch of the bugs vela had months ago are still in
  this"), NOT yet checked on a head unit or the DHU:** (1) the palette functions take a
  `StyleLayers` interface (`ui/map/StyleLayers.kt`: `StyleHost(Style)` for the phone,
  `SnapshotterHost(MapSnapshotter)` for the car, which has `getLayer`/`getSource` but no layer
  list), so `applyMapTheme` runs on the snapshotter from `Observer.onDidFinishLoadingStyle`; the
  old darkening color filter is skipped once themed. (2) `QuietSnapshotter` overrides the protected
  `addOverlay`, which is what printed every tile source's attribution as a watermark
  (`withLogo(false)` only removed the logo); the renderer draws its own single OSM credit inside
  the visible area. (3) The puck is framed inside the host's VISIBLE area (`onVisibleAreaChanged`),
  at `PUCK_DOWN` (72%) of it while following in nav, and the meters-per-pixel constant is
  MapLibre's 512 px one (78271.517): the 256 px constant put the look-ahead at twice the intended
  offset, off the bottom edge. (4) The puck rides `FollowEstimator` (the phone's between-fix
  glide) instead of a 28%-per-tick ease toward a point that jumped once a second, and the
  speed-tiered nav zoom eases (`ZOOM_EASE`) instead of stepping. (5) `VelaCarAppService` attaches
  `PiperSynth` to `VoiceGuide` when the phone UI never ran, and the car's start passes
  `VelaPiper.ENGINE_ID` when the pref is unset or `vela.*` and the voice is installed; it used to
  hand nav the system engine every time. `RECENTER_MS` (6 s after a pan) is unchanged.
  **Round three, same day (user: "do it all"):** `CarBridge` (toasts + corridor data from
  `NavController`), Pause/Resume and an along-route search icon on the action strip (4 actions is
  the template's cap, so the mute slot still gives way to a faster-route offer), a `MessageInfo`
  while paused, the far-turn "Continue on <road>" card (`ManeuverMapper.CONTINUE_FAR_M`), an
  overview toggle on the map strip, and corridor dots on the car map. `AlongRouteCarScreen` is
  two `ListTemplate`s (categories, then results) because the host refuses a typed search while
  driving. Still unverified on a unit.
  **Round four (2026-09-22, from a real drive on a stock Pixel 9, NOT yet re-checked on a unit):**
  the palette was applied once when the style loaded, so a drive that started in daylight stayed
  light after the car flipped to night; `applyTheme` now re-runs from `requestRender` whenever
  `carContext.isDarkMode` no longer matches the applied look. The puck is the phone's
  `navPuckBitmap` scaled to the screen and rotated by heading minus camera bearing (it was a
  green chevron of the car's own). The speed badge sits inside the host's VISIBLE area, scaled
  to the screen (it was anchored to the surface's corner, under the map action strip). The
  voice sounding "muffled, like Bluetooth" is the protocol: Android Auto's guidance audio stream
  is 16 kHz mono, so every nav voice, Google's included, is band-limited on the car; a head unit
  set to route navigation prompts over the phone-call link makes it 8 kHz. The light map on
  that drive did not look like Vela's light palette (user: "some generic crap"), and the phone
  had been dark the whole time, so the palette most likely never applied: `SnapshotterHost` now
  takes the style's layer ids (parsed from the JSON the snapshotter loaded, or the bundled
  Liberty asset) so `applyMapTheme`'s blanket passes run on the car, and `applyTheme` logs
  success or the swallowed exception under `VelaCar`. Next stock-Pixel session: read that line
  first. The car attribution is the phone's `map_osm_attribution` text verbatim. **The car map
  follows the PHONE's theme when it is explicit** (Light / Dark / AMOLED; Auto = the sun the phone
  computes) and defers to the car's day/night only under "System": the driver had Vela dark and
  got a light car map because the head unit said day. The puck is an eighth of the short side.
  **Gearslip's in-app "Car preview" (its Debug mode) renders Vela's car screens on the phone**, the
  same host path a head unit gets, so the car map can be checked without a car or the DHU. **And
  that preview found the real theme bug (2026-09-22): `MapSnapshotter.Observer.onDidFinishLoadingStyle`
  NEVER FIRED, on any build, because a style handed over as JSON finishes parsing before
  `setObserver` runs, so the palette never went on and every car map since 2026-09-21 was stock
  Liberty under the old darkening filter (the "generic crap" on both Pixels). The palette is applied
  from the FIRST SNAPSHOT CALLBACK now (a returned snapshot proves the style is loaded; that frame
  is discarded for a themed one); the observer stays as a no-cost second chance. Proof: the
  `VelaCar: theme applied dark=true layers=111` line and a navy car map in the preview.**
  **THE GATE, READ OFF A REAL CAR LOG (2026-09-22, GrapheneOS Pixel 9, sandboxed Play, Android
  Auto 17.4, "Unknown sources" on, KingInstaller's Shizuku method so the install fields read
  installer=com.android.vending, requester=com.android.packageinstaller):** on connect the
  Android Auto app asks the Play Store for each app's owners, `Finsky: PlayGearheadService
  app.vela, app owners empty`, then `CAR.VALIDATOR: Package DENIED; failed all other checks
  [app.vela]`, the same for CoMaps and Organic Maps. The check is Play's own install record, not
  the installer fields, so no installer spoof and no stub package named like Google's installer
  can pass it; the "Unknown sources" toggle did not cover it either. On a stock Pixel the
  KingInstaller "Google installer" method (Google's `com.google.android.packageinstaller`, which
  GrapheneOS does not ship) does get Vela listed. The capture recipe: `nohup logcat -f
  /data/local/tmp/aa.txt -r 32768 -n 6 &` over adb before the drive (a reboot kills it), pull
  the files after, grep `CAR.VALIDATOR` and `PlayGearheadService`.
  **Turn card requirements (per the Android for Cars docs):** `ActiveNavCarScreen` calls
  `NavigationManager.navigationStarted()` AND `updateTrip()` - both are needed for the RoutingInfo turn
  card + the cluster/HUD nav data; `ManeuverMapper` maps Vela maneuvers → car `Maneuver`/`Step`/`Trip`.
  Manifest also declares `FEATURE_CLUSTER` (instrument-cluster nav) and `CAR_INFO` (AAOS car speed).
  The PHONE also feeds NavSession when not projecting; the car and phone share the one nav loop.
- **Picture-in-picture nav (2026-07-25):** MainActivity carries `supportsPictureInPicture` +
  autoEnter params kept in lockstep with `vm.state.navigating` (Android 12+; pre-12 enters in
  onUserLeaveHint). `PipMode.active` (ui/PipMode.kt) is flipped by onPictureInPictureModeChanged
  and MapScreen wraps EVERYTHING after the VelaMapView call in one `if (!pipUi)` gate, plus a
  banner for the small window in Google's shape (2026-09-13, was a one-line dark caption the user
  found hard to parse): the turn card's own `primaryContainer` green across the top with the
  maneuver glyph, the distance as a bold headline and the turn text under it. NB the 4a
  (GrapheneOS, Android 14) never entered PiP under adb (Home key, home gesture, the window key,
  app-op "default"). Device-verified later the same day once the app-op was set to `allow` by hand
  (`adb shell appops set app.vela PICTURE_IN_PICTURE allow`; "default" did NOT enter PiP on that
  GrapheneOS 14 phone). Three more PiP rules from that session: the map's COMPASS and ALL GESTURES
  are off while `PipMode.active` (VelaMapView, beside the compass margins; the system's own taps on
  the PiP window reached the map as gestures, dropped the follow camera, and the restored app came
  back detached, the user's "I have to re-center every time"), `onUserPan` is ignored in PiP for
  the same reason, MapScreen bumps `navRecenterTick` when PiP ends while navigating, and
  `pipParams` sets `setExpandedAspectRatio(9:16)` on Android 13+ so the PiP menu can grow the
  window taller. The banner shows the road the turn enters when the maneuver carries one and the
  whole instruction on two small lines otherwise (a Google-abbreviated route has no road fields).
  Any NEW map chrome must live inside that gate or it will render wall-to-wall in the mini map. The turn heads-up (NavigationService's
  `vela_nav_turns` channel) deliberately stays quiet while PiP is up: the activity is still
  STARTED in PiP, so AppVisibility.foreground remains true and the alert gate sees "visible".
- **Long downloads NEVER ride viewModelScope (issue #212, 2026-07-23).** Every multi-MB download
  (voice model, ASR engine, region graph, place pack, overlay, update APK, offline place data)
  launches through `MapViewModel.downloadLaunch(label) { ... }`: the work runs on the app-lifetime
  `DownloadWork.scope` (Main.immediate, byte-identical threading to viewModelScope) and a
  refcounted `dataSync` foreground service (`app/download/DownloadService`, quiet notification)
  holds the process alive for its duration - viewModelScope is canceled the moment the task is
  swiped, and without the FGS an OEM background killer (the issue's Galaxy) reaps the process
  seconds after Home. Labeled returns inside those blocks are `return@downloadLaunch`. Progress
  writes to a cleared ViewModel are inert by design; installed-ness is derived from disk at next
  init. When adding a NEW download, use downloadLaunch, not a bare scope.
  **And every download is CANCELLABLE (2026-07-23):** the store download fns take an
  `active: () -> Boolean` polled per chunk (abort flows into the normal failure cleanup); the VM
  holds per-kind AtomicBoolean cancel flags + cancelXxxDownload() fns, resets each flag at download
  START, and suppresses the "failed" toast when the flag is set (a cancel is not a failure). The
  map-area TILE save is the exception (MapLibre's own machinery): cancel = detach observer +
  STATE_INACTIVE + delete the partial region (`cancelAreaDownload`), and its progress is
  STATE-DRIVEN (`areaDownloadPct` -> the area variant of RegionDownloadCard) - OfflineMaps.download
  takes structured callbacks now, NEVER a status-string spam callback (per-tick flashes stacked a
  second banner with raw coords over the progress card, user 2026-07-23; the stored region name
  keeps the coords - it is what tells saved areas apart in Settings - but no banner shows it).
  A new download UI site must show a Cancel wherever it shows progress (skip it during the
  unpack/install phase - the bytes are already down).
- **Settings is HUB-AND-SPOKE (2026-07-23, ported from alltechdev/vela-dpad; supersedes the
  2026-07-10 single-page order):** `ui/settings/` = `SettingsScreen` (a plain hoisted-state
  dispatcher over the `SettingsSection` enum, no nav library; BACK peels spoke -> hub -> map),
  `SettingsHub` (category rows + the SETTINGS SEARCH, a static `SEARCH_INDEX` of label-resource ->
  section where a match OPENS THE SPOKE - the old measured scroll-to-Y died with the long page;
  add new row labels to the index. **Since 2026-09-14 (issue #426) the match also SCROLLS TO
  THE ROW:** `onOpen(section, label)` carries the tapped label, `SettingsScreen` holds it and
  provides `LocalSettingsHighlight`, and `Modifier.settingsAnchor(label)` on ToggleRow /
  SelectableRow / SubHead / SectionTitle brings the matching element into view and glows it for
  a beat; the Parking history group is always rendered, with an empty-state hint, so its index
  entry never leads to nothing), `SettingsScaffold` (the one place all the Settings D-pad focus
  plumbing lives - every page builds on it, see docs/dpad.md) and `SettingsComponents`
  (SettingsGroup/ToggleRow/SelectableRow/GroupDivider/PageIntro/Hint), with one file per spoke in
  `ui/settings/sections/`. Spoke contents (hub order since the 2026-09-17 reshuffle, which grouped
  rows by topic; shared groups live in `sections/MovedGroups.kt`): Appearance (theme incl. the new
  AMOLED true-black ThemeMode, interface size, map colors, Material You, units, follow-system
  language toggle), Map (how the map looks only: layer toggles, 3D, missing-building fill, house
  numbers), Places (`PlacesSettings`, was Place pages: `PlacesSourceGroup` "Places come from",
  `PlacesOnMapGroup` show places / tapped-place lookup / civic / transit stops / icon size, then the
  place-page toggles), Navigation (keep-screen-on, traffic lights, vibrate chips,
  `CameraSettingsGroup` with every surveillance/speed camera row, `LiveRechecksGroup`), Voice
  (spoken-directions master switch, engine list, inline collapsible Voice library - the separate
  VoiceBrowseScreen route is gone, `openVoiceLibrary` deep-links to the VOICE spoke with the
  library expanded), Search (the voice command list, ASR engines + provider picker), Saved places
  (saved + lists export/import, `ParkingHistoryGroup`), Offline maps ("Include places with
  downloads" stays here: it decides what a download holds), Privacy (`PrivacySettings`, was Data
  source & privacy: privacy link and **Clear history** since 2026-09-14 for issue #425:
  `MapViewModel.clearAllHistory()` = recent queries + recent places + parking history + every
  recorded trip behind a `VelaDialog` confirm, saved places and lists untouched, indexed for the
  settings search), Diagnostics (`DemoModesGroup` sits here too; the Experiments group is gone - its one
  occupant, the Google-style picker, graduated to Settings > Navigation on 2026-09-18;
  (share-diagnostics, texture render, building debug, trip recording, crash card; NB the update
  card's notes are CUMULATIVE since 2026-09-14, issue #330: `SelfUpdater.check` pulls
  the app-release TAGS from `git/matching-refs/tags/v0.` (about 200 KB, no bodies, no assets)
  and then one `releases/tags/<tag>` per release with a code in (installed, offered], at most
  `HISTORY_MAX_RELEASES` (8) of them; **NEVER `/releases?per_page=N` again (2026-09-22): the four
  data releases each list ~450 assets, ~780 KB of JSON apiece, and since the world bakes they sort
  into the top of that list, so a check pulled 4 to 9 MB over cellular and parsed it with org.json
  on the phone, which was "checking for updates is slow". Device-checked on the 4a: a canary check
  is 3 requests and 208 KB in 1.7 s; the line is logged as `VelaUpdate: check channel=... requests=N
  bytes=N`.** It keeps the channel's releases with a code in (installed, offered],
  and `cumulativeNotes` joins them newest first under their versions, falling back to the single
  release's notes; canary keeps its rolling list), About
  (support, version tap-to-copy, **an "Installed by <package>" line (2026-09-22) that says whether
  the install source is Play, i.e. set up for Android Auto by King Installer / AAEnabler, which an
  in-app update would undo; read before and after any update on a car-paired phone**, auto-update,
  nightly toggle, check now). ⚠️ The vela-dpad fork
  DROPPED many mainline settings in its redesign (Material You, map colors, UI scale, POI sizing,
  parking, lists export, nightly, spoken-directions toggle, live rechecks, building overlay/debug,
  trip tools); they were all restored during the port - when cherry-picking future settings work
  from that fork, diff the feature surface, not just the files. (Old single-page order note:
  OLD: **Settings ORDER is deliberate (declutter reorg 2026-07-10):** Appearance →
  Map style → Units → Language → **Offline maps** → Search → Map (traffic/transit) → **Place pages**
  (ShowReviews / read-all-reviews / LoadPhotos / hide-adult / hide-external-links) → Navigation (keep-screen-on, vibrate-on-turns as
  FilterChips one per mode, parking history) → Voice → Saved places → Lists → Data & privacy →
  Diagnostics (share-diagnostics + the crash card) → **Advanced** → **Developer** → About/Support/
  Version(+updater). Two **collapsible buckets at the bottom** hold the rarely-touched toggles, moved
  out of their old sections: **Advanced** = 3D buildings + traffic-light guidance; **Developer** =
  demo drive, simulate location, trip recording (each "turn off for real use"). The two **content
  filters (hide-adult, hide-external-links) stay in Place pages** with the other place-content
  toggles (user 2026-07-10), NOT Advanced. **Offline maps** (renamed from "Offline") sits right below Language, ABOVE Search (people reach it often, user 2026-07-10). Its two subheads were reframed 2026-07-10 (user: region vs routing region read confusing): **"Local area"** (the viewport download: map + roads + addresses for where you are) and **"States & countries"** (renamed from "Routing regions": the whole-region graph + place pack, framed as "everything to get around a state/province/country offline"). The region filter field just says "Search" (the old "Filter N regions…" wrapped to multiple lines). **Language is a "Follow
  system language" ToggleRow** that reveals the language picker (all supported languages) only when OFF (seeded with
  `AppLocale.deviceDefaultSupported()`); most people never see the list. **The Voice library is a
  DEDICATED screen** (`VoiceBrowseScreen`, reached by the "Browse voices" `OutlinedButton` in the
  Voice section) not an inline accordion - `SettingsScreen` early-returns to it when
  `showVoiceLibrary`; its own Back returns to Settings. Put a new setting in the section it serves;
  niche/experimental → Advanced, demo/test tools → Developer, place-content → Place pages not Map.)
- **Route-row traffic words GRADE with the ETA color (2026-07-10):** the "live traffic" tag in
  RouteOption's via line is now "light/moderate/heavy traffic", switched on `trafficRatio` at the
  SAME thresholds `trafficEtaColor` uses (>1.4 heavy red, >1.15 moderate amber, else light green) -
  words back up the color for color-blind users. Ratio-less live routes keep the plain "live
  traffic"; keep the thresholds in lockstep if either side changes.
- **Guidance volume (2026-08-08, issue #245):** Settings > Voice "Guidance volume" chips
  (Softer 0.6 / Normal 1.0 / Louder 1.6 / Loudest 2.2; pref `voice_volume`). The neural voice
  applies it as a plain gain over its float PCM hard-clipped at full scale (speech rarely peaks
  there, so the boost stays clean); the system-TTS path takes `KEY_PARAM_VOLUME` capped at 1.0 -
  Android can only ATTENUATE system voices, which the hint says. `MapViewModel.setVoiceVolume`
  persists + relays + auditions the nav sample; init relays the saved value.
- **Voice install/fallback never auto-speaks (2026-07-10):** `selectVoice(id, audition)` only
  auditions the nav sample on an EXPLICIT library pick ("Use" button); the download-completion
  (firstEver) and delete-fallback paths pass audition=false - a phone that starts talking on its
  own right after an install reads as a bug (user report). The Test button is the on-demand way.
- **Voice download shows a distinct "Installing…" after 100% (2026-07-19):** a voice download hits
  100% and then spends ~15 s unpacking the ~67 MB archive; the map card already flips to
  `voiceInstalling` ("Installing Vela Voice…"), but the three SETTINGS progress sites (the Voice
  section install line, the Voice-library reinstall line, and each `VoiceRow`) kept printing
  "Downloading … 100%" through the unpack and read as a hang (user report). They now switch to
  `settings_voice_search_installing` ("Installing…") with an indeterminate bar while
  `state.voiceInstalling`. Any new voice-download progress UI must read `voiceInstalling`, not just
  `voiceDownloadPct`.
- **Depart at / Arrive by is confirm-driven (2026-07-11):** a time change re-routes TRANSIT
  ONLY (`setDirectionsTime`) - the keyless drive/walk/bike request has no departure field, so
  refetching it returned identical routes and just flickered the list; the chooser's arrival
  window math is what actually changes. The Depart-at/Arrive-by chips open the time picker
  DIRECTLY and NOTHING emits until a picker confirms (the old flow fetched on the bare chip
  tap with an unpicked "now", then again per dial); the default time rounds to the next 5
  minutes. Pickers are Material 3 TimePicker/DatePicker in `PickerDialog` (a raw-Dialog Vela
  shell, D-pad rule compliant, `usePlatformDefaultWidth = false` + `widthIn(max = 400.dp)` since
  2026-09-14 because the platform margins on a 360 dp phone clipped the DatePicker's fixed
  360 dp and hid the Sunday column, issue #432; NB DatePicker's selectedDateMillis is UTC midnight - decode
  with UTC or picks land a day early). The old android.app Holo dialogs are gone. The chooser's single-estimate
  fallback shows the plain time (the "~" prefix read as clutter, user 2026-07-11), and every
  OutlinedButton on the directions family (Steps, the time/date fields, transit Back) is a
  FilledTonalButton stadium pill - outlined was the last dated control there. The
  search-along-route chips are SOLID tonal (they are one-shot actions; a permanently
  unselected FilterChip read as disabled) while the REAL selection groups (travel mode,
  leave/depart/arrive) keep the M3 filled-when-selected contrast on purpose - filling those
  would erase the selection signal. Past picks are impossible: the date picker grays out days
  before today and a confirmed past time clamps to the next 5-minute mark now, WITH a toast
  (`place_time_past_toast`, all locales): the pill shows a different time than the pick,
  and a silent rewrite of explicit input reads as a bug.
- **The directions ENDPOINTS live in a TOP card now (`RouteTopCard`, 2026-07-13):** while the
  chooser is open the search bar swaps for a Google-style card at the top of the screen - origin /
  stops / destination rows down a glyph rail (teal ring = your location, connector dots, red pin =
  destination, matching PoiIcons.RESULT_RED), back arrow left, swap right, an Add stop row when no
  stops exist; rows tap through to the same beginPickOrigin/openStopsEditor actions. The bottom
  DirectionsPanel LOST its header (and the originName/onEdit*/stops/onSwap/onClose params) - it
  keeps mode chips / time chooser / routes / Start, so collapsing it to the Start bar no longer
  hides the endpoints (Google's layout, better on small screens). The card hides while the search
  overlay, steps preview or stops editor own the screen; chrome colors (colorScheme tokens, NOT
  SheetPalette - it replaces the search bar). Device-verified: card, edit rows, swap-reroutes,
  collapse-keeps-card. **Minimizing the chooser re-frames the route closer (2026-07-14):** the
  panel reports its collapsed state up (DirectionsPanel `onCollapsedChange` -> MapScreen
  `dirMinimized`), the camera bottom inset drops 0.58 -> 0.14 of the screen, and the route fit
  in VelaMapView keys on the insets as well as the geometry so the inset change re-runs it -
  with only the Start bar left, the route gets nearly the whole map. Deliberately NOT
  auto-minimized after a beat: the list is what you're choosing from, and surprise motion
  right after opening reads as the UI fighting you; one flick down now has a real payoff.
  **Fit padding scales to the visible strip (#400, 2026-09-13):** the route, transit and
  cluster fits went through a fixed 140/160 px margin per side plus the insets; on a 240x320
  phone the two side margins alone exceeded the viewport, MapLibre got a negative fit area and
  never zoomed out. `fitPadding()` (bottom of VelaMapView) caps the margin at a sixth of the
  visible strip and trims the insets when card + sheet leave under a fifth of the map.
  **Chooser body cap on short screens (#400, 2026-09-14):** MapScreen passes DirectionsPanel a
  `bodyMaxDp` = screen - endpoints card bottom - `CHOOSER_MAP_STRIP_DP` (96) -
  `CHOOSER_HEADER_DP` (84), floored at `CHOOSER_BODY_MIN_DP` (120); the panel takes the smaller
  of that and its 58% cap, so a normal phone is unchanged and a 240x320 phone keeps a strip of
  map above the chooser.
  **Landscape chooser (user 2026-09-17):** the same cap runs in landscape with a thinner map strip
  (`CHOOSER_MAP_STRIP_LAND_DP`) and a lower body floor, the WHOLE card is `heightIn`-capped to the
  room under the endpoints card, and `compact = landscapeChrome` tightens the panel: the mode tabs
  move up into the header row (the big "Drive" title drops, the tabs name the mode), the paddings
  halve and everything between the tabs and the action row scrolls, so Start can never be pushed
  off. Without it the panel's FIXED chrome alone was taller than a phone's landscape column and
  grew over the stops card.
  **Live configuration (user 2026-09-17):** MainActivity declares `configChanges` for
  orientation/screenSize, so Android hands rotation to the Activity and never calls the
  application-level `ComponentCallbacks` that Compose's own `LocalConfiguration` listens to - every
  `LocalConfiguration.current` read stayed on the PREVIOUS orientation until the app restarted
  (landscape kept portrait chrome, and back again). `MainActivity.onConfigurationChanged` now holds
  the live Configuration in a state and provides it over `LocalConfiguration` for the whole tree;
  read screen size through that local, never through `resources.configuration`. **POI icons on low density:** EVERY icon layer must carry the scale,
  including the OPEN PLACES layers, which their own effect REBUILDS from scratch - so `poiIconScale`
  is one of that effect's KEYS and is baked into their iconSize/textSize/circleRadius at creation.
  Until 2026-09-18 they were the one layer that ignored it: on a head unit the businesses drew huge
  while the road shields and street names beside them stayed tiny, and they looked right for a
  moment after a style reload until the next places refresh rebuilt them unscaled.
  `lowDensityIconScale(density)` multiplies
  the Settings icon-size pref below 1.75x (fixed-pixel bitmaps were a fifth of a 120 dpi screen).
  **IT APPLIES TO ICONS ONLY (user 2026-09-19, car screen).** An icon is a fixed-pixel BITMAP, which
  is why a 120 dpi head unit draws it huge and why the correction exists; TEXT is drawn from SDF
  glyphs at a size that already accounts for the pixel ratio, so putting the same 0.4x on labels
  made them unreadable in a car, which only showed once the icons were finally right.
  `poiLabelScale` is the Settings multiplier ALONE and feeds every `textSize` (ambient, open places,
  transit stops, markers); `poiIconScale` keeps the correction and feeds icons, dots and circles.
  Two parameters, two effect KEYS - do not collapse them.
- **Start is a FOOTER under the route list (user 2026-09-13):** the chooser body is an outer
  capped-and-faded Column holding a `weight(1f, fill = false)` scroll Column and, below it, the
  Start / Steps row, so four alternates scroll under a Start that stays put; the cap wraps both,
  so the footer folds with the body on collapse (the minimized Start bar takes over as before).
  The first cut put the footer INSIDE the scroll by matching the wrong closing brace; anchor
  edits in PlaceSheet on a comment line, never on braces.
- **The directions chooser drags like the other sheets (2026-07-11):** its settle flips
  `collapsed` AFTER the glide, never before - flipping first fired `LaunchedEffect(collapsed)`
  into a SECOND animateTo racing the decay (the "bounces off the top" on swipe-up-to-reopen,
  fixed 2026-07-11); it also pan-minimizes via `minimizeTick`/`dirPanTick` (consume-once
  guard) like the place + results sheets. the drag detector sits
  on the WHOLE panel column, not the handle (finger anywhere grows/shrinks it; inner clickables
  keep their taps, the scrolling body keeps its nested-scroll path). Travel mode is STICKY
  (pref `travel_mode`, set in `setTravelMode`, restored by `routeToSelected` - the pick is the
  default next session; parking still forces WALK). The directions X wears the place-sheet
  circle; the swap glyph deliberately stays bare. Save is a BOOKMARK icon, not a star (a star
  reads "rate it"; matches the saved-places map button). Search span: `SearchPb.build` takes
  the caller's real viewport height and stretches the template's baked ~25 km `!1d` window
  (floor `SearchPb.MIN_SPAN_M` = 1 km since 2026-09-15, was 3 km; cap 500 km) - zoomed-out
  searches used to keep a city-sized net; the VM threads its live viewport span into the main +
  category-chip searches. **A search from a close zoom HOLDS its view (2026-09-15):** the results
  fit in VelaMapView (`holdView` in the marker-fit branch) skips the fly-out when the view is under
  `HOLD_VIEW_SPAN_M` (2.5 km north to south) and at least `HOLD_VIEW_MIN_HITS` (3) results land in
  the strip above the results sheet; zoomed in to a few blocks, "food" used to fly the map out to
  frame every hit (user 2026-09-15). Wider views still frame the cluster as before. **Search is three pages plus a
  NEARBY pass plus "More results" (2026-09-13):** `GoogleMapsDataSource.search` fetches pages
  0-2 (20 each) over the viewport window, and when the user's location is INSIDE that window and
  the window is wider than ~2.5 km it also fetches one page over a 2.5 km window around the user
  and LEADS with it (the Google app weights distance the same way; before this, the outlet next
  to you lost its slot to better-known places across a town-zoom window and missed all three
  pages, the "Subway near me never shows" complaint). Over another neighborhood or city no
  nearby pass runs, so Google's order for where you are looking stands. The list ends in a "More
  results" row (`MapDataSource.searchMore`, `MapViewModel.loadMoreResults`, `resultsMoreQuery`)
  that pulls the next three pages of the same request and appends what is new; it disappears
  when a pull adds fewer than five or the query changes. The `search` diag line says `nearby N`.
  Results-sheet FILTERS drop
  MAP PINS too: SearchResults reports surviving ids via onShownChange -> MapScreen's
  filteredResultIds -> markersOf (null = filters off).
 its body height is a
  hand-driven Animatable (0 = minimized, ~0.58 screen = open) - handle AND body-at-top drags
  move it 1:1, release rides the throw's decay to an end (the shared grammar). The body and
  the minimized Start bar both fold with that height (SheetFold, inverse fractions), so the
  collapsed flip swaps nothing visible; the old collapse was a 6px-threshold boolean flip.
- **Directions "Leave now" ETA line (2026-07-10):** "Arrive at 5:30 PM" renders titleMedium
  SemiBold in ink - the small dim line with a "current traffic" note under it was clutter (the
  traffic-colored per-route ETAs already carry that signal); the "Usually X-Y min" typical-range
  note stays. `place_current_traffic` was deleted from all locales.
- **The sheet physics recipe is SHARED (2026-07-11):** the place sheet, the search-results sheet
  and the DirectionsPanel body all use the same grammar - a hand-driven `Animatable` (height for
  the sheets, a 0..1 body FRACTION for the chooser) dragged 1:1 (handle + content-at-top nested
  scroll), settled by `exponentialDecay(1.6)` picking the nearest detent from the natural coast
  endpoint with a bounds-clamped `animateDecay` ride (spring only when the throw doesn't carry),
  and the animated value read in a LAYOUT modifier so frames never recompose content. Port this
  recipe to any new sheet; don't invent a fourth gesture system.
- **Place-sheet drag physics are CONTINUOUS (2026-07-10):** the sheet height is a hand-driven
  `Animatable` - drags (handle or body-at-top) move it 1:1 with the finger, release projects the
  fling and RIDES THE THROW'S OWN INERTIA to the nearest detent: the landing point comes from
  `exponentialDecay(friction 1.6).calculateTargetValue` (friction 1.6 = the same eagerness as the
  earlier linear 0.15 factor, coast = v/(4.2*friction) - it's the one tuning knob), and when the
  natural coast reaches the detent the settle IS `animateDecay` clamped by Animatable bounds at
  the detent - no spring snap, the detent just stops the coast (Google's feel, user 2026-07-10).
  Only throws that don't carry (gentle drops, releases against the velocity) glide on the spring
  (NoBouncy/350f). The animated height is read
  in the LAYOUT modifier on the Card, NEVER in composition - a composition read recomposed the
  entire sheet every animation frame (the tap-to-expand dropped-frames report). The old grammar flipped a whole detent at a pixel
  threshold and hopped there - the "staccato" feel. State flips from taps / the reviews panel /
  auto-expand still animate via a LaunchedEffect that SKIPS when a settle is already targeting
  that detent (restarting would zero the coast velocity). A swipe still never CLOSES the sheet.
- **In-nav search along route (2026-07-13, map-FAB layout 2026-07-14):** a right-edge FAB STACK
  on the nav map (recenter-when-detached + volume + search - the bottom bar was cramming four
  controls, and Google floats these) arms `NavSearchChips` (a free-text field + Gas/Food/Coffee/
  Groceries chips, `NavOverlays.kt`) above the bar; the bar itself is ETA + Steps + End only; a pick runs the normal
  `searchAlongRoute` (which skips stashing `alongRouteDest` while navigating), the nav branch of
  MapScreen's bottom `when` steps aside while `state.results` is non-empty so the results sheet
  shows, and `selectPlace` gates on `navigating` -> `addStopDuringNav` -> `NavSession.addStop`
  (user-ordered replan: the pick becomes the NEXT stop, marks null until the new route lands so
  a failed fetch keeps the stop for the next reroute/recheck; no back-on-course discard, no
  cooldown). **Stops editor mid-drive (issue #402, 2026-09-14):** the nav step sheet (and the
  bar's drag preview) leads with `NavStopsRow` (StepsSheet.kt) listing the stops still ahead
  from `NavSession.remainingStops()`, with Edit stops -> `openStopsEditor` (closes the step
  sheet first so Done lands on the bar); MapScreen's bottom `when` renders the chooser's
  `StopsEditorSheet` for `navigating && editingStops` with origin = your location and rows =
  `navStopsForEditor()` (the chooser Place where the coordinates match, else a bare Place from
  the label); Done -> `applyStops` -> `NavSession.setStops(newRemaining, loc)` which `addStop`
  now delegates to: ONE user-ordered replan through the new list, unchanged list = no fetch,
  and the chooser's `directionsWaypoints` becomes the remaining stops. FAB stack and speed
  widget hide under the editor like under the step sheet. BACK order: results list, then the
  chip row, then end-nav - browsing gas stations
  must never end the drive. **Only a RESULTS pick adds a stop (2026-07-14):** every map tap
  during a drive funnels into selectPlace too (ambient dots, resolved POIs), and a stray tap
  used to silently pin itself onto the route - selectPlace's nav gate now requires the results
  list to be open, and onPoiTap / onMapLongPress / onAddressLabelTap / onTransitStopTap
  early-return while navigating (their invisible selection popped up as a ghost sheet when
  the drive ended).
- **The nav-declutter blanket restore must never touch a layer that has a SECOND owner (issue
  #261, 2026-08-15).** The car-mode strip hides a list of layers during drive nav and restores
  them all to VISIBLE otherwise - fine for layers whose only owner is that effect, WRONG for the
  hillshade, whose visibility also belongs to the Settings > Map "Topography" toggle (the DEM
  layer is always present; the toggle only flips visibility). The effect runs at startup with
  navMode false, so the blanket restore turned terrain relief on for every launch regardless of
  the pref, and flipping the toggle then hid it - which made the bug read as session-specific.
  The hillshade is restored via `ensureTopography(style, topographyOn && !driveNav)` and the
  effect keys on `topographyOn`. Before adding a layer to that list, check nothing else owns its
  visibility.
- **Nav declutter is MODE-AWARE (2026-07-16):** the aggressive strip (basemap shields, bike/trails,
  hillshade, transit lines, vela-addr numbers, gas-stations-only POIs, addr-refresh pause) keys on
  navMode && navDriveMode (DRIVE routes only) - walking/biking keeps all of it (a bike route needs
  the bike accents). The nav label bubbles show ONLY streets that geometrically CROSS the route ahead (a
  quantized pass: querySourceFeatures over transportation_name with a class filter + segment
  intersection against the route window, re-run once per 400 m quantum of progress or when the
  upcoming turn targets change - NEVER on a short timer, the 4 s version was itself a
  frame-hitch: main-thread feature materialization + placement churn per filter change). Since 2026-07-21 the crossing test ALSO counts
  T-JUNCTIONS (touchesWindow: either endpoint of a street within ~25 m of the route window) - on
  arterials most side streets END at your road instead of crossing it, the strict-inequality
  crossing test read their shared endpoint as exactly zero, and the tightened filter came back
  EMPTY = the whole bubble layer went mute on T-heavy roads (real-drive report; grid downtowns
  were where the layer was originally verified). Endpoints only, never every vertex - a parallel
  road must not label itself. And the MINOR tier now arms at z15 (fade 15.2-15.7, was 16/16.2-16.8)
  so cross-street bubbles survive highway-speed zoom (~15.8 floor) instead of fading out above
  town speed (user ask, same day). Roads
  already DRIVEN are excluded per step (navLabelExclude - excluding the whole route hid the turn
  target's label), the next turns' targets are force-included (a turn target meets the route at
  a shared vertex, which a proper-crossing test can miss); ensureNavRoadLabels self-gates on (on, dark, exclude) with lastNavLabelKey nulled
  on style reload. The current-road shield chip lives on the TOP banner card (currentRef =
  maneuvers[liveStep-1].ref; the exit fold adopts the folded branch's road/ref so it survives
  on-ramps). Rerouting plays a two-note earcon (VoiceGuide.reroutingChime, in-process synth,
  muted-gated) before the throttled spoken word.
  **The ROUNDABOUT glyph is DRAWN, not picked (issue #259, 2026-08-15).** It used to be Material's
  `RoundaboutLeft` for every roundabout on earth: a counter-clockwise circulation exiting at 270
  degrees, so it was wrong about the exit for nearly every roundabout and wrong about the direction
  of travel for every left-hand-traffic driver - and the glyph is what you take in at a glance while
  the text is what you read only if you have time. `ui/nav/RoundaboutGlyph.kt` builds an ImageVector
  per maneuver from `Maneuver.roundabout` (`RoundaboutGeometry`): the arc you TRAVEL is drawn heavier
  than the arc you do not, going round the correct way, and the exit stub leaves at the measured
  angle. **Both facts are derived from OSRM, never assumed:** OSRM splits a roundabout into an enter
  and an exit step, so `RouteGeometry.roundaboutGeoms` pairs them - the exit angle is the exit road's
  bearing relative to the road you approached on, and the DRIVING SIDE is the sign of the entry
  turn, because you always veer toward the circulating lane (live-captured: Paris entries turn +84,
  Milton Keynes entries turn -24 to -52; pinned in `RoundaboutGeometryTest` with those real numbers).
  With no geometry (Google fallback, offline obf, or an entry too straight for its sign
  to mean anything) it draws its NEUTRAL form - ring plus entry stub, NO exit arrow - because an
  arrow pointing somewhere we did not measure is the whole bug. `maneuverIcon(type)` can only produce
  the neutral form; call `maneuverIconFor(maneuver)` wherever the Maneuver is in hand. **Android Auto reads the same geometry (review 2026-09-12):** `ManeuverMapper` picks CW/CCW from `roundabout.clockwise` (counter-clockwise only as the no-geometry default) and the exit number from `Maneuver.roundaboutExit`, set by all three routers (OSRM `maneuver.exit`, GraphHopper `exitNumber`, obf `exitOut`); it used to hard-code CCW and exit 1. GraphHopper and obf still give NO glyph geometry: both expose a turn angle whose sign convention is undocumented in the vendored jars, and a guessed arrow is the original bug. NB the
  notification glyph (`service/NavGlyphs`) still draws its own fixed straight-out roundabout.
  **Place sheet has START beside Directions; the nav bar is END | figures | STEPS (issues
  #272/#273, 2026-08-17).** Directions opens the picker whose own Start sits BELOW the route list,
  so with three routes on a large-font screen the commonest action (just take me there) needed a
  scroll - the START pill routes and launches in one tap (`MapViewModel.startNavToSelected` sets
  `autoStartOnRoute`; MapScreen consumes it ONCE when a route lands). **It fires through
  MapScreen's `onStartNav`, never straight at the VM** - a one-tap Start must not become a way
  around the precise-location and notification gates the picker's Start honors; and the flag is
  consume-once + cleared by `clearRoute`/`startNav` AND by a route fetch that returns nothing
  (review 2026-09-06: an empty reply used to leave the flag armed for the next, unrelated
  Directions request) so a later refetch (mode change, added stop) cannot silently launch a drive. Not offered for the parked car (you are already at the start of
  that walk). The nav bottom bar swapped End's labeled Button for a 54dp X icon on the LEFT with
  the trip figures CENTERD and Steps on the right: two controls of the same size doing the same
  job should be the same shape, and this is the arrangement where they cannot be mistaken for
  each other. End keeps `errorContainer` coloring - it is the one control that throws the drive
  away, and an unlabeled X must not read as just another button; its label lives on as the
  content description.
  **Nav UI style (2026-07-08):** ManeuverBanner + NavControls are RoundedCornerShape(24/28dp)
  Cards with elevation 6dp, 54dp turn glyph, headlineMedium-bold distance, titleMedium-medium road
  name, FilledTonalIconButton for mute/steps. Keep new nav chrome on this treatment (no flat
  default-radius cards, no OutlinedIconButton circles - that was the "dated" look).
  **2026-09-16: the bubbles are POINTS placed at the crossing.** The labels used to be the basemap
  `transportation_name` lines with `line-center` placement and an include-list filter, which put a
  bubble at the middle of the street's piece in the tile, often a block or more from the route
  (user drive: "near our actual path rather than away"). The same quantized pass now computes, per
  crossing street, where it meets the route window (first proper crossing in route order, else a
  T-junction end within 25 m, 60 m for a next-turn target) with `crossLabelPoint`, moves 35 m
  (`NAV_XLABEL_OFFSET_M`) up that street to the side that ends farther from the route, and uploads
  the points to `NAV_XLABEL_SRC`; the two label layers read it with POINT placement and filter on a
  `tier` property (major / minor) that carries the old class split and zoom gates. The name:en /
  name:latin props are copied so `roadLabelTextField` still romanizes. `navLabelFilter`,
  `crossesWindow` and `touchesWindow` are gone.
- **A SUBMITTED search while picking an origin / destination / stop shows its results (issue
  #405, 2026-09-13).** The pickers keep the overlay open (`searchOpen` includes the three
  picking flags) and keep the chosen place selected, and the results sheet's two gates
  (`!searchOpen`, `selected == null`) both held, so typing "Coffee" into Add stop and pressing
  Enter ran the search and showed nothing: only a suggestion-row pick ever worked. `pickingResults`
  (picking + results + field blurred + non-blank query) now lets `resultsShown` / `resultsMinimized`
  and the bottom-sheet branch through; a row tap goes to `selectPlace`, which already adds the
  stop / endpoint. The three `beginPick*` also clear `results` so a picker never starts over the
  destination search's stale list. NB testing this with `adb shell input text/keyevent` is a
  trap: key events flip the live input mode to Keyboard, `rememberDpadMode` turns on, and the
  unarmed field is DISABLED and blurs after one character. That is the test tool, not a bug; a
  real keyboard commits text. Verified on the 4a through the picker's Recent row instead.
- **Home/Work are SIDE BY SIDE and the endpoint rows have NO pencil (issue #255, 2026-08-15).**
  `ShortcutPair`/`ShortcutCell` in MapScreen replace the two stacked `ShortcutRow`s on the search
  page: two full-width rows for two words spent a third of the first screen, and an unset one now
  reads just "Add" under its label (the label above already says which it is). Halving the height
  must not cost an action - each cell keeps the same tap-to-open / tap-to-assign / ⋮ Change-Remove.
  `ShortcutRow` is kept (unused) for stacked layouts. On `RouteTopCard` the per-row pencils are
  gone: the whole row is the control and the glyph rail already says what each line is, so three
  stacked pencils on one small card said nothing the tap target did not. **The pencil was carrying
  the row's accessibility name** - each row now sets that `contentDescription` itself, or a screen
  reader reads a bare place name with no hint that tapping edits it.
- **Place-sheet surface language (2026-07-10):** header icon buttons (Save/Share/more/close) are
  40dp icons in `dim.copy(alpha = 0.12f)` CIRCLES with 5dp gaps (Google's treatment); ActionPill
  and the "All reviews" button are CircleShape stadium pills (the outlined button was the last
  outlined control on the sheet); the reviews summary block is LEFT-ALIGNED (displaySmall number
  + stars/count stacked beside it), not centered; the MINIMIZED card is NOT a separate surface
  (2026-07-10 refactor, height-locked 2026-07-11): the body is a SKELETON (name row, rating, the
  full action-pill row) plus `SheetFold` sections (photos / status+hours / address+tabs; the
  shared primitive in `ui/SheetFold.kt`, also used by the results sheet's chips) whose
  height+alpha are a FRACTION of natural = the sheet height's own position between the
  minimized floor and peek, read per frame in the layout/graphicsLayer phase. While the fold is
  engaged (fraction < 1) the CARD FLOORS AT THE MINIMIZED DETENT (as a measurement minHeight,
  never just the reported layout size - report-only flooring left the card surface short and a
  strip of map showed under the minimized card, user 2026-07-11): the folding content dips just
  under minH near the floor (the skeleton is a touch shorter than the detent) and the wrap-cap
  card used to dive that last slack in a blink - the end-of-fold hop (user 2026-07-11); at rest
  with fraction = 1 the card still hugs short content (dropped pins, parked car). Landing
  minimized also rescrolls a scrolled body to its top. The fold is
  therefore byte-locked to whatever moves the height (pan glide, a slow drag folds them WITH the
  finger, the release settle) - a separately-clocked exit animation (the first cut used tweens)
  could not stay in step with the height spring and read as staccato. Extras stay composed while
  any part shows and unmount at the settled floor (`derivedStateOf` gate, one recomposition at
  the flip), keeping zero-height controls out of D-pad focus search; the old swap-to-a-mini-card
  popped. A tap anywhere on the minimized body restores peek (a `clickable(enabled = minimized)`
  on the body Column); parking (singleDetent) keeps its extras. Keep new sheet content inside
  one of the extras sections unless it genuinely belongs in the minimized card. **The
  full-screen reviews page closes by pull alone (2026-07-11):** the top-edge pull follows the
  finger; in fullScreen a STARTED pull owns every move until finger-up (the ownership clause
  sits OUTSIDE ReviewsPanel's verticality test - a sideways wobble used to trip the
  boundary-exit end and close mid-drag), and release closes on DISTANCE ONLY (> 120dp) with a
  spring-back below it - the photo viewer's judge-at-release grammar; the old vel > 2500 px/s
  flick escape read as a hair trigger. Keep new sheet controls on this language. NB `RatingHistogram` in
  PlaceSheet is ORPHANED (its per-star counts only exist in the live panel DOM) - wire it or
  delete it, don't duplicate it. **The MENU TAB (2026-07-10)** appears beside Reviews/About when
  `photoCategories` carries a menu-named category (`MENU_TAB_WORDS`, lowercase contains-match on
  Google's LOCALIZED gallery-tab name, which is reused as the tab title); content = the tagged
  photos as a chunked 2-up grid (`MenuTab`) into the shared PhotoGallery; each tile stamps the
  photo's UPLOAD DATE in a corner scrim so a menu's age reads at a glance (user 2026-07-11).
  **Gallery dates are a JOIN of two keyless sources (2026-07-11):** the WebView page walk has
  the CATEGORY tags but no dates, the hspqX RPC has each photo's date but no categories - so
  `fetchPhotos` fires the cheap RPC alongside the walk and joins dates by the stable image id
  (URL up to the size suffix). There is NO uploader/author keyless (the RPC documents it;
  mining the page DOM for it was judged not worth the walking - user leaned that way too).
  **The inline Reviews tab renders a NATIVE RATING HISTOGRAM (2026-07-11):**
  `Place.ratingHistogram` ([5-star..1-star] counts) is scraped IN PASSING by the photo walk
  from the place page's aria-label star rows (the same rows the full-screen panel carves),
  bridged via `WebPhotoFetcher.fetch(onHistogram=...)`, cached per feature id beside the photo
  LRU, and drawn by `RatingHistogram` beside the big rating number; absent = no bars, no cost. **Menu photo DATES are BLOCKED - and it is NOT a calibration drift (proven 2026-07-11, desktop capture):** the hspqX
  placePhotos RPC returns 0 photos now (logcat tag `VelaPhotoDates`: rpc=0). The "recapture
  photosProto from a desktop gallery RPC" route the earlier note left open is now CLOSED. A live
  desktop `maps.google.com` capture of the on-load `hspqX` (`/MapsPhotoService.ListEntityPhotos`
  via `batchexecute?rpcids=hspqX`) shows the endpoint AND the field-index matrix
  (`[[[1,0,3],[2,1,2],[2,0,3],[8,0,3],[10,0,3],[10,1,2],[10,0,4],[9,1,2]],1]`) are BYTE-IDENTICAL
  to `calibration.json`'s `photosProto` - nothing drifted, so a version bump would be a no-op.
  The real blocker is **bot-gating on the photos RPC**: replaying the request three ways from an
  automated browser - Vela's ftid-in-`[2][0]` form, the captured per-page photo-token form
  (`0qlSas...`, `["<tok>",...,81,...,16698]`), AND the genuine page's OWN fresh-token on-load
  request - all returned `[null,0,null,"<ei>"]` = zero photos (0 `googleusercontent` in the body).
  Same TLS/behavioral degradation that gives OkHttp the Street-View-only reply; the live gallery
  the app DOES show comes from the WebView DOM WALK, which has categories but no per-photo dates.
  So dates need the RPC to answer inside a trusted (non-automated, non-keyless) session, which the
  keyless model can't mint. The date-join plumbing stays correct + inert (stamps render only when a
  date exists). **Don't recalibrate photosProto (it already matches) and don't re-run a desktop
  capture (proven bot-gated to empty). Don't chase it in-app.**
  **Menu-tab reliability hardening (2026-07-11):** the walk's tab wait
  counts from the GALLERY OPENING (6 ticks after open, hard cap 20) instead of 8 ticks from
  script start - cold-WebView loads ate the old window and real tabs got skipped; a late-tab
  rescue in the All sweep walks tabs that appear after phase 0 gave up; every walk reports
  {tabs, opened, openedAt, rescued, ticks} via `VelaBridge.onInfo` (logcat tag
  `VelaPhotoWalk`); and a TAB-LESS cached gallery self-heals with ONE fresh walk per session
  (`retriedTabless`) instead of being served forever - one flaky fetch used to hide a menu all
  session. Walk cap 70 ticks, Kotlin timeout 48 s. There is NO keyless
  menu URL (probed 2026-07-10: search payload [38] empty, zero menu links) - don't chase the
  link; the quality follow-up is making WebPhotoFetcher scrape the menu TAB exhaustively. The
  inline review search hides behind a circled magnifier beside the All-reviews pill
  (`reviewSearchOpen`; toggling closed clears the query so a hidden filter can't keep filtering).
- **Chip style = stadium pills (2026-07-08):** EVERY chip (map CategoryChips, results-panel filter
  chips Open-now/top-rated/price/sort + the collapsed "N results" pill, PlaceSheet travel-mode chips
  now with a leading `Icons.Default.Directions*` glyph, Settings vibrate-on-turns FilterChips) sets
  `shape = androidx.compose.foundation.shape.CircleShape` - full-radius pills, Google-style. The M3
  default 8dp-corner chip read "dated" (user 2026-07-08). Keep any new chip on CircleShape; monochrome
  leading icons (tint `onSurface`, not the teal primary) so it reads single-ink like Google's.
- **Search-results sheet - BOTTOM sheet with drag detents (`MapScreen.SearchResults`, 2026-07-08).**
  After one day as a top sheet the user flipped it: results now rise from the BOTTOM, Google-style
  (the top-of-menu grab pill read clunky). It renders with the other bottom surfaces in MapScreen's
  bottom `when` (nav / directions / place sheet win the slot first) and shares the place sheet's
  detent grammar: **MINIMIZED** (a short "N results" bar; = the VM's `resultsCollapsed`, so the back
  gesture and the sheet agree) ↔ **PEEK** (~0.42 list cap) ↔ **EXPANDED** (~0.82, fills the screen).
  Handle TAP steps up; drag UP grows a detent, DOWN shrinks one; the nested-scroll connection steps
  ONE detent per gesture (re-armed in `onPreFling`) with an up-drag into the list expanding - a hard
  fling can cross two detents, which matches Google. **BACK also steps one detent** - `resultsExpanded`
  is HOISTED to MapScreen so the BackHandler does expanded → peek → minimized → CLEARED (a back on the
  minimized bar used to exit the app; now it runs `clearSearch()` to the bare map); and the sheet
  modifier carries `statusBarsPadding()` so the expanded handle pill stops below the clock / camera
  cutout instead of sliding under it (all user 2026-07-09). **Camera frames the result CLUSTER:** the
  marker-fit branch in VelaMapView median-centers the pins and drops outliers past 4x the median
  spread (min 40 km) so one stray far hit can't zoom the map to a continental view; it fits with the
  results-sheet bottom inset (0.50 screen) so pins sit above the sheet; `lastFittedMarkersKey` re-arms
  while the sheet is minimized so expanding re-frames; and the fit CONSUMES `lastCameraTarget` - the
  inset-grow nulls it, and with it null the else-recenter branch re-fired on the STALE VM center one
  recomposition later and yanked the camera back to wherever you were before the search (device-found
  2026-07-09, the "search framed then snapped home" bug). There is **NO "hide results" button**. **Grabbing the map minimizes the sheets (2026-07-10):**
  `VelaMapView.onUserPan` (fired from the camera-move-started listener on REASON_API_GESTURE,
  the same signal "Search this area" keys off) → MapScreen collapses the results sheet to its
  bar while `resultsShown` AND bumps `sheetPanTick` → PlaceSheet's `minimizeTick` effect glides
  an open place card to its minimized detent - Google's behavior; programmatic framing (a
  different move reason) never triggers it. Both drops use a SOFT spring (stiffness 140f, vs
  the 350f settle). Flip order differs BY DESIGN: the RESULTS sheet still GLIDES FIRST and flips
  `resultsCollapsed` after; its FILTER CHIPS + divider fold with the list height over its last
  140dp of travel (`SheetFold`, and the bar's bottom padding is constant), so by the flip they
  are zero-height and nothing visible pops - they used to pop out after the sheet had already
  stopped moving (user 2026-07-11). The PLACE sheet flips `minimizedState` FIRST so its
  SheetFold extras run concurrently with the height glide (no swap anymore, see the place-sheet
  surface-language bullet) - the same order its drag-release path uses. The minimized results bar leads with the QUERY (or list name) in ink +
  SemiBold with the dim count on its OWN LINE under it (the inline "title · count" floated
  awkwardly against the right-side buttons) - the bare dim count was easy to miss. BOTH pan-tick
  effects carry a **seenTick consume-once guard** (initialized to the tick's mount-time value):
  a LaunchedEffect fires on FIRST composition too, so a remounted sheet (pick a place from the
  list, or return from one) used to replay the stale tick and open pre-minimized (user
  2026-07-10). Any new tick-style signal into a sheet needs the same guard. **Filter
  chips are `ElevatedFilterChip` with an explicit filled `chipColors`** (subtle alpha tint off, solid
  `primary` teal + check on, `border = null`). **Rating/Price/Sort are VelaMenu chips (2026-07-10)**
  (tiers 3.5+/4.0+/4.5+, price levels, Relevance/Rating/Distance) - blind cycling hid the options;
  plus a "Wheelchair accessible" toggle chip filtering `Place.wheelchairAccessible`, parsed in
  SearchParser off the LANGUAGE-NEUTRAL attribute id `has_wheelchair_accessible_entrance` in the
  `[1][100][1]` attribute block (labels arrive localized, ids don't; unit-tested). That block is
  the ONE attribute family the keyless search ships per result - vegetarian/reservations/etc.
  exist only in per-place About data, so result-list filtering on them is not feasible keyless
  (don't re-chase). Filters stay LOCAL to the fetched results by design; no cuisine facets (the
  query is the cuisine filter). **Chrome:** `resultsShown` (peek/expanded) hides the
  scale bar / locate FAB / "Search this area"; `resultsMinimized` shows them again but LIFTED by
  `chromeLift` (76dp) so nothing sits on the minimized bar. The compass is MapLibre's built-in
  (`setCompassMargins`), which fades facing north (Google's behavior) and reappears when
  rotated/tilted or during heading-up nav - never removed, just north-hidden on the browse map.
  Its browse-mode top margin is statusBar + 122dp so it sits BELOW the floating search bar and the
  category chips (8dp under the status bar put it exactly behind the bar - a half-hidden circle, 2026-07-09).
  **With the LAYERS button enabled the browse margin is statusBar + 200dp instead (2026-07-15):** the
  layers circle owns statusBar+128dp in the same corner and its IconButton touch overflow reaches
  ~190dp, which sat exactly on the compass (user report); 200dp clears the touch target, not just
  the visible circle. Keyed on `LayersButton.on` (the pref), not the button's transient visibility,
  so the compass doesn't jump around as sheets open.
  **In LANDSCAPE NAV the compass steps IN from the right edge by the FAB column's width
  (`NAV_FAB_COLUMN_DP`, 2026-09-19):** the overview / mute / search stack grows UP the right edge
  from the bottom bar, and a landscape phone is only about 390 dp tall, so four 56 dp buttons reach
  the status bar and sat on the compass (user report). It also stops using the banner's measured
  bottom there: in landscape the banner is a left COLUMN (issue #297), so there is nothing above
  the compass to drop below, and doing it anyway floated the compass down the middle of the right
  edge into the middle of that same stack. Straight under the status bar, one column in.
  **Landscape panel width is HALF THE SCREEN, floored 400dp / capped 520dp (`sidePanelWidth()`,
  2026-07-23)** - the fixed 400 read too narrow; every consumer (both sheets' widthIn, the camera
  left inset, the attribution pad) reads the computed value. **Sheet heights RE-SNAP on rotation:**
  the place-sheet and results-sheet settle effects key on screenH/landscape too - without that the
  Animatable kept the old orientation's pixel height and an expanded portrait card came into
  landscape over the search bar. **Chrome hides are MEASURED, not logical (2026-07-23):** a
  short-content place flips expandedState while its wrap-capped card never grows, so the search-bar
  hide requires placeSheetTopPx < 0.40*screen and the layers button relies on the measured
  clearOfPlaceSheet gate alone - never re-add a bare placeSheetExpanded gate on map chrome.
  **EVERY route-related overlay is a LEFT COLUMN in landscape (issue #297, 2026-09-03).** The
  place and results sheets already had the side-panel treatment; the DIRECTIONS chooser, the
  endpoints card, the maneuver banner and the nav ETA bar did NOT, so each stayed full-width.
  Device-reproduced: with the chooser open in landscape the map was not merely obscured but
  ENTIRELY hidden, and during nav the banner + bar left a thin horizontal sliver with the puck
  half under the bar. All four now take `align(...Start) + widthIn(max = sidePanelWidthDp)` when
  `landscapeChrome`, matching the two sheets, and the camera insets follow: directions and nav
  claim `cameraLeftInset` instead of a bottom inset (a bottom inset in landscape squeezed the
  route into a sliver). NB the endpoints card was the subtle one - full width, its left half sat
  UNDER the chooser panel and the visible remainder read as an empty dark slab over the map.
  When adding ANY new route/nav chrome, give it the same landscape treatment or it will span the
  screen. Use `.align(if (landscapeChrome) ...Start else ...Center).landscapeColumn(landscapeChrome, sidePanelWidthDp)`: the helper caps the width AND pads the display cutout on the start edge (in landscape the notch sits on the left, outside the status-bar insets; the turn card's margin ran under it, review 2026-09-12). The nav follow ticker writes the WHOLE camera padding every frame, so `cameraLeftInsetPx`
  goes into that `.padding(left, top, 0, 0)` too (review 2026-09-12): the inset effect's
  setPadding alone was undone on the first frame and the puck sat on the column's seam.
  **LANDSCAPE (width > height) collapses the browse chrome to ONE line (2026-07-15, Google's
  landscape layout, device-verified on the 4a):** `landscapeChrome` in MapScreen puts the search
  bar at half width with the category chips scrolling beside it (`landscapeOneLine` Row), the
  layers button rises to statusBar+74dp and the compass margins drop to 140dp (layers on) / 95dp -
  on a phone's ~390dp landscape height the stacked offsets pushed the compass down into the
  parking/locate FABs. TRAP: the one-line condition must NOT include `!searchOpen` - focusing the
  bar flips searchOpen, and moving the SearchBar to a different subtree REMOUNTS it, which blurs
  the field, which flips searchOpen back: the search page could never open (first build had it;
  caught on-device). Instead the Row always renders in the landscape bare-map state and only
  MODIFIERS change with searchOpen (bar Box weight(1f) -> fillMaxWidth, chips drop out after it),
  so the field node never moves. The keypad-phone D-pad devices are portrait, so AdaptiveDensity
  targets never see this layout.
- **Search-result markers are Google's result treatment (2026-07-10, `PoiIcons` result section +
  the `vela-markers`/`vela-markers-dots` layers in `VelaMapView`).** Every result keeps the app's own
  marker language - gray teardrop, circle, white glyph - with the circle RED (`resultPin`,
  drawn a step smaller than the ambient icons' backing); rated FOOD results get the wide rating
  "speech bubble": the same red circle + white glyph beside the rating in plain ink, NO star
  glyph (`ratingBubble`, label passed as a string so non-rating labels can ride the same bubble;
  theme-surfaced, regenerated per style load because bitmaps can't theme).
  Bitmaps are generated ON DEMAND in applyData's marker loop (`ensureResultIcon` - bubble keys
  carry the rating tenths, so only the ratings actually on screen get bitmaps). The pin layer
  COLLIDES by rank (`symbolSortKey` = result order, allowOverlap false): in a dense downtown the
  best results keep pins and the rest draw as the small red dots of `vela-markers-dots` (same
  source, below, allowOverlap+ignorePlacement true), expanding back into pins on zoom - never a
  pile of overlapping icons. Pins anchor BOTTOM (tip = the place), labels try UNDER the pin,
  then its right, then its left (variableAnchor TOP/LEFT/RIGHT, radialOffset 0.7 - below-only
  dropped labels in crowded views, user 2026-07-10) in NEUTRAL ink both themes. The AMBIENT and
  OSM poi layers got the same treatment (2026-07-10): four anchor slots (RIGHT/LEFT/TOP/BOTTOM,
  = left of icon / right of it / below / above) instead of the old two - icons still collide by
  design, but a rendered icon's label now finds a clear side instead of dropping or sitting on a
  neighbor's dot - Google doesn't category-tint result labels,
  only ambient POI labels take the tint. resultPin's GEOMETRY is marker()'s exact proportions at
  0.86 scale (a taller-tailed variant read as a different species of pin, user 2026-07-10) -
  keep the two in lockstep. **OSM POIs hide by COVERAGE, not ambient non-emptiness (2026-07-10):**
  `MapUiState.ambientCoversView` (computed each onViewport settle: ambient non-empty AND center
  within 0.35x of `lastAmbientSpan` of the fetch center AND viewRadius ≤ 0.55x span; forced true
  when a fresh fetch lands, false under z14) drives the poi_r* visibility - blanket-hiding left
  the outskirts iconless because one fetch only covers ~3.5-9 km (user 2026-07-10). Controls
  (signs/lights) render from z17.5 on the browse map but z15.4 during nav (set in the nav
  declutter effect; 15.4 sits just under the nav camera's 15.5 zoom floor so they can't blink
  out at highway speed - half of issue #248). While a result SET is on the map (markers.size > 1) the basemap
  poi_r1/r7/r20 icons hide too, AND the traffic-control layers (stop signs + lights,
  `lastControlsVis` - controls stay up beside the ambient dots on the browse map, so their
  predicate is the result set alone). Own identity gates, NOT inside the ambient gate -
  results can appear/clear while ambient stays empty; a single selected place keeps both. Dots
  carry the same MARKER_INDEX_PROP feature prop, so a collapsed result is still tappable.
  **Gas stations put their LIVE PRICE in the bubble** (2026-07-10): `Place.fuelPrice`
  ("$5.34/Regular") parses off the place node at `[88][0]` (calibration `paths.fuelPrice`,
  remote-recalibratable, digit-gated in SearchParser so a shape drift can't show a label as a
  price; calibration.json is at **v14** for it), the bubble shows the short "$5.34"
  (`PoiIcons.fuelShort`), and the full string renders BOLD - its own pump-glyph line under the
  address in the result row (glyph + text in title ink, theme-responsive) and bold inline on the
  place sheet's price/category line (user 2026-07-10). EV chargers carry NO detail in the
  keyless response (probed 2026-07-10 - type marker only, no price/kW/availability); see ROADMAP.
- **Typed coordinates drop a pin (2026-07-13):** pasting "37.77, -122.42" (or a geo: string) into
  the search box goes through `MapLinkParser.parseBareCoordinate` (strict whole-string match, both
  halves need a decimal point, range-checked, unit-tested) -> the same reverse-geocoded pin a
  long-press drops, instead of hitting the search endpoint as text. Addresses with numbers still
  search normally. External geo:/Maps links were already handled (`openDeepLink`).
- **Map tap resolution order (`VelaMapView` click listener, 2026-07-08; nearest-to-finger 2026-07-14).**
  Every candidate class picks the feature NEAREST the tap in SCREEN pixels (`screenDist2`) - every
  pick used to be `firstOrNull` on `queryRenderedFeatures`, which returns RENDER-STACK order, so
  the generous 48dp hit box at street zoom handed the tap to whichever neighbor the renderer
  listed first (the dense-strip-mall wrong-POI reports). A single tap resolves, in priority:
  (1) our search-result pin → `onMarkerTap`; (2) a saved pin; (3) a grayed
  alternate route line → `onSelectAlternate`; (4) a BUSINESS **or a canonical GTFS stop icon** -
  the stop competes by distance with the businesses instead of outranking them (user 2026-09-18:
  a fuel station on a corner opened the stop beside it however dead-on the tap was, the same
  lesson the ambient dots taught in 2026-07-14) - the ambient Google POI dots/icons
  and the NAMED basemap POIs compete BY DISTANCE, not by class (absolute ambient priority let a
  few-px ambient dot anywhere in the box steal a tap landed dead on a basemap icon): nearest of
  the two → `onAmbientTap` / `onPoiTap`; (5) a **HOUSE-NUMBER label** (basemap `vela-housenumber` `housenumber`
  or the address overlay `vela-addr-*` `number`, queried by layer id) → `onAddressLabelTap(number,
  labelPoint, tileStreet)`, where `tileStreet` = `nearestStreetName` (the `transportation_name`
  road within 45 m). The VM reverse-geocodes the label point (Nominatim, zoom 18); if the answer's
  house_number IS the tapped number its road wins (the node's own `addr:street`), otherwise the
  tile road vetoes a mismatched geocode street (issue #231 rounds one 2026-08-03 and two
  2026-09-14: the veto used to fire even on a number-exact geocode and moved side-street houses
  onto the bigger road beside them); (6) an unnamed POI icon (has `class`, no name) → reverse-geocode at the tap; (7) a
  **BUILDING footprint** (`building`/`building-3d` basemap fill or the `vela-ovl-*` overlay fill,
  queried by layer id) → reverse-geocode at the tap; else nothing (only a long-press drops a raw
  coordinate pin on empty land, as before). NB the long-press-while-planning "route through here"
  add-stop no longer flashes a heads-up banner (2026-07-13): the route refetch reset `status` a beat
  later so it blinked unreadably, and the stop appearing in the chooser + the route redrawing IS the
  feedback (`mapvm_stop_added` string removed from all locales). **And it only fires while the chooser
  is MINIMIZED to its Start bar with the steps viewer closed (2026-07-15, device-verified):**
  building/unnamed-POI TAPS funnel into the same handler, so with the full picker (or the step list)
  covering the map a stray tap on the visible strip silently added a stop and rerouted; suppressed
  presses do nothing at all (a dropped pin would be an invisible sheet under the open chooser). The
  chooser's collapsed state reaches the VM via `MapViewModel.onDirectionsCollapsed` (mirrored from
  MapScreen's dirMinimized); a house-number-label tap while planning delegates to the same gate. The
  deliberate flows (Add stop -> choose on map, the origin picker) are ungated. The STEPS SHEET also
  dismisses by a swipe down anywhere on the body once its list is at the top - a nested-scroll
  connection feeding the card's existing drag offset (the place-sheet dismissConn grammar); mid-list
  swipes still scroll. **TAP WHAT IS DRAWN (user 2026-09-18, "if we tap on something, it needs to open that shit").**
  Before any ranking, `handleTap` asks `queryRenderedFeatures(p, *bizIconLayers)` - the ambient
  layer, the open-places ICON layers (never `vela-places-dots-*`) and the OSM poi tiers - what is
  rendered at the finger's own pixel; a non-empty answer becomes the whole pool for the business
  pick. Ranking the 24 dp box by distance to each feature's POINT put two thumbs on the scale: a
  place icon is bottom-anchored, so the blob the user aims at is ~40 px above the point being
  measured, while a tenant DOT is drawn on its own point - so a coin machine or a counter inside
  the store measured NEARER than the store whose icon was under the finger. Empty answer = the old
  box rules, so dots stay tappable. **The named-POI resolve is
  NAME-AGREEING first (2026-07-14):** onPoiTap searches the tapped name, but the pick used to
  ignore it - bare `nearest` plus the 35 m most-reviewed override let a strip mall's popular
  NEIGHBOR steal the tap (Google's per-listing pins in a shared building are loose; a sushi
  tap opened the dessert shop two doors down). The pick pool is now the listings whose name
  shares the tapped label's words (`nameAgrees`, word-set overlap needing the shorter name's
  tokens, cap 2); only an EMPTY pool (renamed/closed business) falls back, and since 2026-09-18
  that fallback is bounded twice over: a NON-TRANSIT tap never adopts a listing whose category is
  transit (`isTransitCategory`) or map furniture (`JUNCTION_CATEGORIES`), because Google lists
  stops and intersections as places meters from the businesses on the same corner and the
  "nearest of everything" fallback made the stop the answer; and a listing that does not agree by
  name has to be within `NO_NAME_MATCH_M` (60 m, the same lot) rather than anywhere inside the
  1.5 km cap. Nothing left to adopt = the tapped label keeps its own name and point, which for an
  open-places tap still carries the tile's address, phone and hours. The
  EXACT NAME WINS (2026-09-18): `nameAgrees` must stay loose enough for a co-branded pair, so a
  brand's fuel station, pharmacy and in-store counter all qualify and the pick was then whoever sat
  nearest the tapped point - the "tapped the store, got the fuel station" report. The pool is
  narrowed to listings whose NORMALIZED name equals the tapped one (lowercase, punctuation out,
  trailing store number dropped - `normalizedPlaceName`, the bake's snap-key rule) whenever any
  exist. The
  clear-dominance duplicate override still runs WITHIN the pool (a co-brand's two profiles both
  agree with the tapped label, and the rich one should win). **And the pick must be NEAR THE
  TAP (issue #429, 2026-09-14):** a town label for Salem, Arkansas searched "Salem" and Google's
  nearest answer was Salem, Massachusetts, 1191 mi away, opened as the place; now a settlement
  label (`poiKind` in `SETTLEMENT_KINDS`, the OMT `place` classes) accepts a hit within 30 km,
  any other label within 1.5 km, transit stops unbounded (they resolve by board), and a farther
  hit is dropped so the bare label at its own coordinates stays selected. **The house-number case must SNAP to the tapped number:**
  `MapViewModel.onAddressLabelTap` LEADS the pin with the label's own number and uses the reverse-
  geocode only for the street/city, replacing whatever house number the geocode led with (a regex
  strips `^\s*\d+\S*\s+` then prepends the tapped number). Reason: Google's reverse-geocode snaps to
  the nearest ADDRESSABLE point, which for a tapped OSM label routinely returns a NEIGHBOR (device:
  tapped 1020, raw reverse-geocode said 1040) - exactly the "doesn't snap to the house number"
  complaint. A real business sitting on the point still wins (if the geocode has a rating/category it's
  shown as-is). Device-verified: tapping a numbered house label opens exactly that number, not the
  neighbor the raw geocode returned; a bare footprint resolves to the building's own address.
- **Place-content toggles (2026-07-08):** `ShowReviews` / `LoadPhotos` reactive holders
  (`ui/PlaceContent.kt`, same shape as `LiveReviews`, init in VelaApp, rows in Settings → Map).
  They gate BOTH fetch (`fetchReviews`/`fetchPhotos` first line) and render (PlaceSheet `hasReviews`
  + the photo-hero `if`), so off = zero scrape traffic. Keep any new review/photo surface behind them.
- **The sheet's LAYOUT MUST NOT MOVE while it loads (user 2026-09-18).** The photo strip and the
  rating row sit ABOVE the action pills, and both land a second or two after the sheet opens, which
  used to shove Directions / Start / Street View ~88dp down the screen just as a thumb arrived
  (device-measured). Both now hold their space from the FIRST frame while `detailsLoading` is true
  for a place that will have them - the signal is a non-blank `category` (a business), never the
  feature id, because a place tapped on Vela's own places layer has no Google id until the details
  land, and that is exactly the case it is for. An address or dropped pin has no category, so it
  reserves nothing. Anything new that renders above the pills has to do the same or it re-opens
  this bug.
- **"Hide adult categories" toggle (2026-07-08):** `HideAdult` holder (`ui/PlaceContent.kt`, default
  **off**, init in VelaApp, row in Settings → Map). It flips `CategoryFilter.enabled` (a `:core` flag) - 
  `:core`'s `data/CategoryFilter` filters adult/nightlife/alcohol/gambling/smoking places at the
  `GoogleMapsDataSource.search`/`nearbyPlaces` seam. Match is CATEGORY-only (never name) and PRECISE
  (`EXACT`/`PHRASE`, food "…bar" kept); the keyword lists are **multilingual** (categories arrive
  localized via `hl=<lang>`, so the filter must too). Unit-tested (`CategoryFilterTest`). NB the `:core`
  flag pattern (not reading the app holder from `:core`) is deliberate - mirror it for any future
  content gate that must act inside `:core`.
- **"Hide website & external links" toggle (2026-07-08):** `HideExternalLinks` holder
  (`ui/PlaceContent.kt`, default **off**, init in VelaApp, row in Settings → Map). Gates the Website
  pill/row, the Street View pano and the Book/Reserve/Order action in `PlaceSheet`. Taken from PR #14
  WITHOUT the restricted build flavor (user's call, 2026-07-08) - the flavor/LockableToggle machinery
  was deliberately dropped, keep holders in the plain `ShowReviews` shape. Gate any new external-link
  surface on a place page behind this holder.
- **Full-screen viewers = VISIBLE bars + gradient, NOT hidden bars (2026-07-10).** After many
  rounds fighting a Compose Dialog window to COVER the system bars (window dumps proved it
  re-asserts inset-fitted params and refuses), the working recipe is Google's own: NO_LIMITS +
  TRANSPARENT status/nav bar colors + `Modifier.requiredFullScreen()` on the content root (sizes
  to the true display so it fills UNDER the transparent bars) + a top gradient scrim so the
  status bar reads over the photo. Applies to `PhotoGalleryContent`-era gallery + `FullScreenReviews`.
  Don't reach for hide-bars/dim/decor tricks again - they leave strips. The reviews page uses an
  X (left, matching the gallery) and a top-edge pull-down (panel `onOverscroll`/`onOverscrollEnd`
  → `offset` the Surface → dismiss past 120dp).
- **In-app updater (`app/update/SelfUpdater.kt`, 2026-07-08).** GitHub releases/latest → tag
  `v0.<minor>.<run>` → versionCode `2000+run` compared to BuildConfig; newer → `MapUiState.updateInfo`
  card on the bare map. Download = no-call-timeout client (~80 MB APK) + zip-magic check →
  `filesDir/updates/` (FileProvider `updates` path) → ACTION_VIEW package-archive; the OS verifies
  same package + signature. Launch check ~daily behind `self_update_check` (Settings → Version,
  default on); manual Check-for-updates button there too. "Not now" stores `update_dismissed_code`
  (only a NEWER release re-offers). The tag parse is **minor-agnostic** (`^v0\.\d+\.(\d+)$` - it
  survived the 0.2→0.3 bump untouched), taking only the run number for the versionCode; it still
  assumes the `2000+run` base, so update `SelfUpdater.check` if the versionCode base ever changes.
- **POI-speed trio (2026-07-11):** (1) `nearbyPlaces` STREAMS its category fan-out via an
  `onPartial` callback (paints throttled to >=10 new places + 500 ms apart; the final
  return is still the complete ranked pool) so first dots stop waiting on the SLOWEST of
  ~13 requests; (2) `prefetchAmbientNeighbors` warms the 4 view-sized neighbor areas
  into the ambient LRU after each idle fetch - UNMETERED network only (4 extra fan-outs),
  sequential with 700 ms gaps, skips cached areas, bails on any non-bare-map state; (3)
  the ambient LRU PERSISTS to `ambient_cache.json` (newest 8 areas x 200 slim places via
  :core `AmbientDiskCache` - the app module stays OUT of kotlinx.serialization, the same
  boundary TransitParser keeps; 24 h validity, loaded stamps read as fresh because the
  moved-gate refetches the first real view anyway = paint-then-refine, never
  paint-and-trust). Device-measured on the 4a: cold-launch home-area dots at ~3.0 s
  (bounded by app+map startup, proven the disk path - no network paint can land by then)
  vs ~4.0 s fetch-bound before; the streaming win grows on slow links.
  **Cache-hit fixes (2026-07-11, the P9 "POIs don't stick around / tap-back wipes the map"
  report):** (a) entries carry their fetch SPAN (`AmbientEntry`; disk `AmbientCachedArea.spanM`,
  defaulted so old files decode) and `cachedAmbientNear` hits within `span*0.45` - the old FIXED
  900 m radius missed most legitimate revisits (a z14 fetch covers ~9 km) and forced a full
  refetch; (b) the pre-fetch cache REPAINT is UNCONDITIONAL - the old `ambientPois.isEmpty()`
  gate meant panning BACK to a cached area kept the previous area's dots (non-empty, filtered
  to nothing in view) and never consulted the cache = bare map for the whole refetch; (c) a
  PARTIAL paint never SHRINKS the painted set (after a cache repaint the early pool is leaner
  than the cached set and replacing blinked dots off/on; the final ranked pool still replaces
  outright). Don't re-tighten any of the three.
  **Tap-a-POI-and-exit stability (2026-07-15, device-verified before/after):** TWO more rules. (d)
  The ambient layer STAYS UP while a single place sheet is open - `ambientShownOf` in MapScreen
  (used by BOTH the marker upload and onAmbientTap so the AMBIENT_INDEX_PROP indices align) only
  drops the selected place's own copy (name-equal within 150 m, so its icon/label don't double-draw
  under the red pin); the old `selected == null` gate emptied the whole source on every tap and
  re-placed the entire layer on close ("POIs reload when I tap one then exit"). Still hidden while
  results / a route preview / nav / replay own the map. (e) A fetch under `AMBIENT_FRESH_MS` (3 min)
  old that still COVERS the view (the ambientCoversView predicate) is served AS-IS - no network
  refetch: the tap-frame camera shift trips the 180 m moved-gate, and Google's ranking JITTERS
  between identical same-area requests, so the post-close refetch randomly swapped/dropped icons
  (proven on-device: Chipotle vanished, two places flipped to generic pins, ~2 s after closing a
  sheet). Disk-loaded entries are backdated past the window on purpose - they stay paint-then-refine.
- **Zoomed-in pan perf (2026-07-08):** (1) `reportScale` (fires per camera-move FRAME) only pushes
  to compose when mpp moved >1% - an unconditional write recomposed the scale bar every pan frame;
  keep the gate. (2) Both house-number layers (`vela-housenumber` basemap + `vela-addr-N` overlay)
  carry `textIgnorePlacement(true)`: they still YIELD to icons (allow-overlap stays false) but never
  enter the collision index - cheaper placement at street zoom and numbers can't evict icons
  whatever the layer order. (3) `ui/Buildings3d` holder + Settings → Map "3D buildings" toggle sets
  visibility on the basemap `building-3d` fill-extrusion layer (a LaunchedEffect in VelaMapView owns
  visibility; applyLight/applyDark only color it) - extrusion is the fragment-heavy layer, the
  documented 5a-class stutter source at z16+.
- **Day/night theme (issue #262, 2026-08-15).** `ThemeMode.AUTO` is light by day and dark after
  sunset, worked out on the device: `:core` `util/SunTimes` is the standard sunrise equation (no
  network, no key, no almanac; unit-tested against published almanac times, plus both polar cases
  where there IS no sunrise and the answer still has to be one thing or the other). This is NOT the
  same as `SYSTEM` - the OS theme is whatever the phone is set to, and only some ROMs schedule it.
  Two rules learned building it: (1) round the sunrise DAY in LOCAL solar terms (`+ lng/360` before
  the floor) - rounding in UTC picks the wrong day either side of midnight UTC, so a Californian
  evening before sunset was compared against TOMORROW's sunrise and read as night; (2) AMOLED is a
  flavor of dark and must yield when something resolves the app to light, or a daylight drive gets
  a black UI over a light map. The sun answer lives in ONE place (`AppTheme.night`, refreshed by a
  single one-minute ticker in VelaRoot and whenever a fix moves) so `isAppInDarkTheme()` stays a
  plain state read. The position is stored ROUNDED TO ~1 KM (sunset moves ~4 s per km of longitude,
  so precision buys nothing and a theme setting has no business keeping a precise record of where
  its owner was). The stored point is TIMESTAMPED and trusted for 24 h and only while location permission is still granted (review 2026-09-12): with a grant a fresh fix replaces it within a minute of launch anyway, and a revoked grant plus a flight east gave a dark map at 3 pm local for the whole trip; past that it falls back to the clock. Separately, **`AppTheme.navDayNight`** switches by daylight ONLY while navigating
  and keeps the chosen mode everywhere else - the reporter's actual ask ("my default is dark, but
  driving in daylight I want the light map"); it is hidden under AUTO, where it would claim to do
  something already happening. `AppTheme.navigating` is mirrored from the nav state by MapViewModel.
  Google's tunnel-darkening is NOT built (no tunnel data keyless).
- **The LAUNCH WINDOW theme is separate from AppTheme and follows the SYSTEM (issue #280,
  2026-08-20).** `Theme.Vela` in `values/themes.xml` is the pre-Compose window (the white flash
  before the map draws) and parented `android:Theme.Material.Light` - fixed at build time, so it
  stayed white on a dark phone. `values-night/themes.xml` adds the dark twin; the resource system
  picks it before any of our code runs. **It cannot follow Vela's own Light/Dark/System setting** -
  nothing can read a preference that early - so a user running Vela dark on a light phone still
  gets a light launch window for that instant. Matching the system is what was asked for and is
  right far more often than always-white. Keep the two files in lockstep when the window theme
  changes.
- **Light/dark is `AppTheme` (`ui/theme/AppTheme.kt`), not the OS.** Read the
  in-app theme with the composable **`isAppInDarkTheme()`** - never call
  `isSystemInDarkTheme()` directly in app UI (it ignores the user's Light/Dark/
  System choice in Settings → Appearance). `AppTheme.mode` is a process-wide
  reactive `mutableStateOf` (same shape as `ui/Units`), persisted to
  `vela_settings`, `init()`-ed in `VelaApp`; flipping it recomposes the theme and
  reloads the map style (`VelaMapView`'s styleKey carries `dark=`).
- **List membership matches on featureId, not the volatile place id (2026-07-09).** A Place's `id`
  is `"g:" + name hash + coarse lat` - for a multi-listing chain (Safeway + its pharmacy/bakery
  listings) the tap-resolve can pick a different co-located listing next visit, so the id changes
  and anything keyed on it silently misses (the "note kept not saving" bug). `ListPlace.matches`
  (id OR stable Google featureId) is the ONE membership predicate - PlaceListStore add/remove/
  setNote/listsContaining, the sheet's containingLists and MapViewModel's withListNote all use it.
  Never compare bare `it.id == place.id` for list/saved semantics on Google-backed places.
- **LocationListener must be an explicit object, never the SAM lambda (2026-07-09).** The lambda
  implements ONLY onLocationChanged; onProviderEnabled/onProviderDisabled/onStatusChanged got
  default bodies in the Android 11 SDK, so it compiles clean - but on Android 10 and below the
  framework interface has no defaults and the OS calls onProviderDisabled the moment a registered
  provider is off (degoogled devices often carry a present-but-disabled NETWORK provider) ->
  AbstractMethodError, crash on every launch (user report, Android 10/Adreno 308). Override all
  four callbacks explicitly in any android.location.LocationListener implementation.
- **Places import from OTHER APPS (issue #279, 2026-09-02).** `:core` `data/PlaceImport` reads GPX
  waypoints, KML placemarks and GeoJSON points (including Google Takeout, whose name/address hide
  under `properties.location`), tried by `SavedPlaceStore.importMerge` when the file is not Vela's
  own export. Migration path for people arriving from Organic Maps / CoMaps / OsmAnd. ⚠️ **GPX is
  lat-then-lon but KML and GeoJSON are LNG FIRST** - reading either the wrong way round silently
  imports a whole collection into the Gulf of Guinea, so both orders are pinned by
  `PlaceImportTest`. Review 2026-09-06: a KML placemark whose `<coordinates>` holds more than one
  tuple (a LineString track, a Polygon area) is SKIPPED rather than pinned at its first vertex;
  Takeout's capitalized keys (`Title`, `Location`, `Business Name`) are read as well as the
  lower-case ones; `importMerge` runs `distinctBy { id }` over the incoming set because two
  placemarks at one rounded coordinate share an id (every later id-keyed action would hit both);
  `ImportFormats.describe` tests KML before the XML prolog (both are XML, so every KML used to be
  called GPX); and both import launchers in `SavedPlacesSettings` read + parse on `Dispatchers.IO`.
  Deliberately imports ONLY name + coordinate (+ address where Takeout gives
  one): every format agrees on those, and a confidently wrong address is worse than no import.
  Ids are content-derived from the rounded coordinate, so re-importing the same file adds nothing
  (device-verified: "Imported 3 place(s)" then "Everything in that file is already saved").
  0,0 and out-of-range coordinates are dropped rather than pinned at null island. The picker's
  mime list keeps a broad `*/*` last on purpose - Android does not know `.gpx` and reports it as
  an octet-stream BIN file, so a strict filter makes the file look absent.
- **Import reports WHICH outcome happened (issue #287, 2026-08-28).** `SavedPlaceStore.importMerge`
  / `PlaceListStore.importMerge` return `:core` `data/ImportResult` (Added / NothingNew /
  WrongFormat(format?) / Unreadable) instead of a bare Int - four very different outcomes used to
  collapse into "0" and the UI could only say "Nothing to import", which is actively misleading
  when the real problem is that the file came from another app (a reporter hit exactly that with
  Google Takeout data). `ImportFormats.describe` names a recognizable foreign file (Takeout,
  GeoJSON, GPX, KML, Organic Maps) so the message says what it IS; unit-tested
  (`ImportFormatsTest` - the Takeout matcher was written wrong first and the test caught it, real
  Takeout URLs are `maps.google.com/?cid=`, not `google.com/maps`). **AND the actual "button does
  nothing" bug: `importLauncher.launch(...)` was wrapped in a bare `runCatching`**, so on a device
  with no documents provider the ActivityNotFoundException was swallowed and the button genuinely
  did nothing - no picker, no message. `launchImport` toasts instead. Device-verified end to end
  with a Takeout-shaped file. NB importing another app's format is issue #279, still open.
  ⚠️ A literal wildcard mime in a KDoc block ENDS THE COMMENT (same trap as PoiPackStore's
  `del_*/ins_*`) - the mime array's comment is a line comment for that reason.
- **SavedPlace carries an optional address (2026-07-10).** `SavedPlace.address` (defaulted null, so
  pre-existing payloads decode; every store's Json sets ignoreUnknownKeys so downgrades survive too)
  is filled by `SavedPlace.of(Place)` - recents rows show it as a sublabel and the Home/Work rows
  show it as their subtext. Recents also support PER-ROW removal (`RecentSearchStore.remove(query)`
  / `RecentPlaceStore.remove(placeId)` -> the X in `SuggestionRow(onRemove=...)`, its own D-pad
  focus stop with a ring).
- **Recents stores are timestamped under NEW pref keys (2026-07-09).** `RecentQuery`/`RecentPlace`
  carry `at` (epoch ms) so the search page can interleave queries and places chronologically.
  They persist under `queries2`/`places2`; the legacy `queries`/`places` payloads are READ ONCE
  for migration (synthesized descending stamps) and then LEFT IN PLACE - a downgraded build
  reads its old keys untouched instead of hitting a format it can't decode and wiping the data.
  Follow the same new-key pattern for any future store format change.
- **Material You participation is a hard SPLIT - know which side a surface is on (2026-07-09,
  issue #15).** `DynamicColor` (Settings -> Appearance, off by default, Android 12+) makes
  `VelaTheme` use the wallpaper scheme, so EVERYTHING drawn from `MaterialTheme.colorScheme`
  tints for free: the search bar (surfaceContainerLow), VelaMenu popups (surfaceContainerHigh),
  chips, FABs, dialogs, Settings, the dpadHighlight ring, and the nav notification accent
  (NavigationService reads system_accent1_600 when the pref is on). PINNED NEUTRAL on purpose:
  the place/results sheets (SheetPalette's fixed grays - they're reading surfaces dense with
  meaning-bearing color: open/closed green/red, star gold, note quotes, photos - and several
  solids were hand-composited against those exact grays, e.g. the opaque filter-chip containers)
  and every map-drawn color (tiles, route blue, puck). Rules when adding UI: new chrome or
  transient surfaces take colorScheme tokens (they'll theme themselves); content inside a sheet
  takes SheetPalette; don't mix the two on one surface or one of the theme x dynamic combos
  will look wrong. AND pick token pairs whose contrast the scheme GUARANTEES: on a colored
  container, buttons must use that container's own on-color, never a default from a different
  role - the faster-route card's default-primary TextButton all but vanished on its
  tertiaryContainer under a wallpaper scheme (both roles derive from the same wallpaper hues;
  fixed 2026-07-14 with onTertiaryContainer text + an inverse-fill confirm). The dynamic scheme is luminance-sanity-checked in VelaTheme (a ROM handing a
  light background for the dark scheme falls back to Vela colors - seen on GrapheneOS). NB
  Google Maps itself is a dynamic-color HOLDOUT (M3 components, zero wallpaper tinting) -
  Vela tinting its chrome already exceeds it; the split is our own design call.
- **Basemap layer gotchas (`VelaMapView.ensureLayers`/`applyLight`/`applyDark`, OpenFreeMap Liberty).**
  (1) **`maxzoom` is EXCLUSIVE** - the bundled `building` FILL layer is `minzoom 13 / maxzoom 14`, so
  `setMinZoom(14f)` alone collapses its range to empty and the flat footprints never paint (you'd see only
  the faint `building-3d` extrusion). The fill needs a matching **`setMaxZoom(24f)`** to re-open the top;
  keep it. `building-3d` (fill-extrusion) is gated to **z16+** on purpose (the flat fill carries the
  browse-zoom footprint look; extrusion is the per-pixel-expensive part on a Pixel 5a). (2) **House
  numbers** render via the runtime `vela-housenumber` SymbolLayer (OMT `housenumber` source-layer, gated by the shared `houseNumberMinZoom()` = **a SETTING since 2026-09-13 (issue #329, `ui/HouseNumbers`, Settings > Map "House numbers": near 18.3 / normal 17.8 default / far 17.3; the level rides `styleKey`)** with a 0.6-zoom `houseNumberFade` - numbers only when close, but reachable by an ordinary zoom-in; it was a hard 19 (~50 ft) until issue #257, where people zoomed in, saw street names and no numbers, and concluded Vela had none; 17.5 still carpeted whole blocks, user 2026-07-13. The basemap layer and the `vela-addr-*` overlay MUST share the constant - they draw the same addresses from different sources, so a mismatch shows one set arriving before the other) - 
  OpenFreeMap **does** serve that source-layer (verified vs the live TileJSON + z14 tiles), so it works;
  coverage is OSM `addr:housenumber` (partial), not a render bug. The `vela-addr-*` overlay number
  layers anchor to `CONTROLS_CLAIM_LAYER` (above basemap labels, below the ambient icons) - NOT the
  visible `CONTROLS_LAYER`, which lives at the BOTTOM of the symbol stack since 2026-07-09; anchoring
  there sank the numbers under the building extrusions and every basemap label (the "numbers under
  the buildings" regression). (3) The runtime loads the style from the **LIVE** URL `MapStyle.LIBERTY.uri =
  https://tiles.openfreemap.org/styles/liberty` (`fromUri`), and offline downloads use the same URL - both
  **auto-follow OpenFreeMap's current tile snapshot**, so there is NO dated-path/blank-basemap risk. The
  bundled `liberty-roboto.json` asset (which DOES pin a dated `planet/<snapshot>` path) is **parked +
  unused** - the `asset://`/`fromJson` path in `VelaMapView` is dead code kept only as reference (a bundled
  copy blanked the vector tiles on-device; see the project memory). Don't be misled by the stale path in
  that asset. Verify basemap edits on-device in **both** themes. (3b) **Satellite deep zoom (2026-08-06, issue #244):** the base World_Imagery raster source is
  capped at `maxZoom 19` (Esri's safe global max) and MapLibre STRETCHES the z19 tile past that -
  the "blurry satellite" report. `MapViewModel.refreshSatDeep` probes Esri's `tilemap` availability
  index (1x1 cell at the view center, levels 22→21→20, area-cached box, fetch-fail caches nothing)
  into `MapUiState.satDeep` (0 none / 20..22 Esri native level / -1 fallback), and
  `ensureSatelliteDeep` in VelaMapView adds ONE extra raster layer above the base imagery:
  Esri at its probed native level, or Google's `mt1.google.com/vt/lyrs=s` tiles (maxZoom 21) only
  where Esri tops out at 19 - Esri is deliberately the primary everywhere it has data (user call).
  The deep source id carries provider+level so an area change swaps the source instead of
  stretching a stale cap; Google upsamples rather than 404s outside cities (probed), so the
  fallback can't paint holes, and open-ocean 404s fall back to the overzoomed parent like any
  failed raster tile. The deep layer CROSS-FADES in (rasterOpacity 0 at z18.6 -> 1 at z19.6):
  Esri's z20+ metro tiles are a different capture program than the z17-19 mosaic, so the
  handover is an era/lighting flip in the DATA - the fade blends the seam like Google does
  (user noticed the pop, 2026-08-08). The bottom credit follows whose pixels are on screen
  (review 2026-09-12): `satDeep == -1` past the fade reads "Google" with no Esri capture year,
  the blend zone credits both; zoom is derived from the scale bar's meters-per-pixel.
  **OpenStreetMap attribution is ALWAYS on the map (issue #302, 2026-09-12):** MapLibre's own
  ⓘ button is disabled in VelaMapView (it covered the scale bar), and nothing else said OSM on
  screen, which the ODbL requires wherever the map is shown. A tappable "© OpenStreetMap
  contributors" label sits bottom-left under the scale bar in EVERY map state (lifted over the nav
  bar via `navBarHeightPx`, over the minimized results bar via `chromeLift`, into the map strip in
  landscape), Settings > About has a "Map data" group with the same link, and README + the site
  footer carry the credit. Never gate the label on a chrome state; the satellite credit stays a
  separate centered line because Esri's terms want their own wording. The probe runs 20 -> 21 -> 22 and stops at the first MISSING level (same review): where Esri tops out at 19, most of the world, one request settles the Google fallback where 22-first spent three sequential round trips on the blur; `ensureActive` between requests so a probe canceled by the next pan stops, and the deep layer's minZoom is the fade's first stop (18.6) so it no longer loads tiles while invisible. (4) **Road-name halos are
  WIDER than the blanket** (2026-07-09): applyDark/applyLight give the three `highway-name-*`
  symbol layers `textHaloWidth 1.9` vs the 1.1 every other label gets - route lines and the
  dotted walking line run right under street names and made them unreadable; the fatter halo
  is the "underlay tint" (Google does the same). Keep the exception if the blanket pass changes.
- **Voice search mic is tier-2 intent handoff, not in-process recording (2026-07-10).**
  `VoiceSearch` (reactive holder, pref `voice_search_button`) + a mic in `SearchBar` (param `onMic`,
  shown only when query is empty and `onMic != null`). MapScreen computes `onMic` = toggle on AND
  `VoiceSearch.hasProvider()` (a `queryIntentActivities(ACTION_RECOGNIZE_SPEECH)` check; needs the
  `<queries>` entry in the manifest for Android 11+ visibility). The tap fires
  `startActivityForResult(ACTION_RECOGNIZE_SPEECH)` and reads `EXTRA_RESULTS` into the query -
  **the provider records, so Vela needs NO RECORD_AUDIO for this tier.** KEY DISTINCTION verified
  2026-07-10: only apps that register the RECOGNIZE_SPEECH **activity** count (FUTO Voice Input
  does - confirmed in its manifest). **With several voice apps installed the launch resolution
  is a LADDER** (2026-07-10): the user's Settings pick (persisted as a flattened ComponentName,
  `voice_search_provider`) pins the intent; with no pick, the intent stays IMPLICIT so Android's
  own default-app choice routes it (a default set outside Vela is respected); only when Android
  has no default either (`resolveActivity` returns the package-"android" resolver) does Vela pin
  the FIRST installed app, so the system chooser can never interrupt a dictation. The Settings
  picker mirrors the ladder EXPLICITLY (2026-07-10): Vela Voice, then an "Android default" row
  (= the no-pick state, `clearProvider`), then every installed app BY NAME as manual overrides
  (`VoiceSearch.providers()`); an uninstalled pick degrades down the same ladder, never a dead
  mic. Legacy `Engine.LOCAL` pins migrate to AUTO at init (a LOCAL pin hid the mic entirely once
  the model was deleted, and the UI stopped offering LOCAL when the picker shipped). Keyboard/IME voice (Sayboard, FUTO Keyboard) provides a
  RecognitionService or in-IME mic, NOT the activity, so `queryIntentActivities` returns empty and
  the mic correctly hides - Vela can't `startActivityForResult` to them. That's intended: those
  users use the keyboard mic, and tier-1 (on-device Whisper) serves everyone regardless.
- **Voice search TIER-1 is on-device Whisper, in-process (2026-07-10, device-verified end to end).**
  The second path: Vela's own model records + transcribes on the phone, no other app. `WhisperRecognizer`
  (`:app/voice`) loads **Whisper tiny int8 multilingual + Silero VAD** through the bundled sherpa-onnx
  runtime (same AAR as Piper TTS - `OfflineRecognizer(config=...)` with `assetManager` defaulting null
  = filesystem load; VAD via `Vad(config=...)`), records with `AudioRecord` (VOICE_RECOGNITION, 16 kHz
  mono), feeds the VAD in 512-sample windows, and transcribes the detected speech segment. `AsrModel`
  (`:app/voice`) is the descriptor + install check (`filesDir/asr/whisper-tiny/`, 4 files present =
  installed). The **~47 MB model is a download-on-demand from the `asr-models` release** (built by
  `tools/build-asr-model.sh` = slim the upstream sherpa whisper-tiny to int8 + silero, tar.bz2;
  reuses `KokoroInstaller.download` + its no-call-timeout client). RECORD_AUDIO is asked **at point
  of use** (first mic tap), never for tier-2. `VoiceSearch.resolvedMode(context)` picks LOCAL / SYSTEM
  / NONE from the `voice_search_engine` pref (AUTO=on-device-wins / LOCAL / SYSTEM) x availability;
  MapScreen mirrors it reactively (keyed on `state.asrInstalled` so a fresh download flips the mic
  without relaunch). The capture UI is `VoiceCaptureDialog` (listening sheet, level ring, auto-focus
  Done - VelaDialog D-pad pattern). Settings -> Search has the download/remove + the engine picker
  (shown only when BOTH model and a provider exist). R8 keeps `com.k2fsa.sherpa.onnx.**` (already for
  Piper). Verified: download+install, mic appears, POU permission, VAD auto-stop, transcript -> query;
  Auto uses on-device, "Other voice app" launches the provider. **User-facing name is "Vela voice"**
  (2026-07-10): the model row, hint and picker all say Vela voice, and the picker is TWO options
  (Vela voice = AUTO, Other voice app = SYSTEM) - the explicit local-only third choice was dropped as
  jargon (Engine.LOCAL still exists, just not offered). **Whisper is PINNED to the app language**
  (`whisperLang()` = `AppLocale.effective().language` when in SUPPORTED, else auto; recognizer
  rebuilds if the language changes) - auto-detect transcribed a noisy far-field capture into
  CYRILLIC, don't revert to `language = ""`. **Transcripts are cleaned** (`cleanTranscript`): Whisper
  writes prose ("Coffee shops near me.") so terminal ./!/?/,/;/: and wrapping quotes are stripped;
  inner periods stay (St. Paul). The listening pulse uses tween(90) + 1.4x ring travel + a 7x RMS
  gain - the default spring smoothed the ~32 ms level updates into near-stillness. **The mic always shows when the toggle is on** (2026-07-10): with neither the model nor a provider, tapping it OFFERS the Vela voice download (VelaDialog in MapScreen -> `downloadAsrModel()`, the map shows the same VoiceDownloadCard) - a hidden mic made the feature undiscoverable. **The archive is tar.GZIP on `asr-models`** (58 MB vs 47 bz2): bzip2 unpacked at ~15 MB/s on-device (a ~30 s hang); gzip installs in ~2 s. `KokoroInstaller.extractTar` picks the decompressor by magic bytes (upstream Piper voices are still bz2). Accuracy of
  tiny-int8 is the known tradeoff for size/speed; a larger model could be a future catalog entry.
  **Pauses playing media while listening (2026-07-12):** `WhisperRecognizer.listen` takes
  `AUDIOFOCUS_GAIN_TRANSIENT` (an `AudioFocusRequest` with `USAGE_ASSISTANT`/`CONTENT_TYPE_SPEECH`)
  right before `audio.startRecording()` and abandons it in the `finally` after `audio.stop()`, so
  music/podcasts PAUSE (not just duck - `_TRANSIENT`, not `_MAY_DUCK`) while dictating and resume the
  instant the utterance ends. Only the in-process path needs it; tier-2 (SYSTEM) hands off to an
  external recognizer that manages its own focus. Recording itself (an input `AudioRecord`) is
  unaffected by the output-focus request.
- **ASR is CRASH-SENTINELED and voice failures EXPLAIN THEMSELVES (2026-07-23, ported from
  vela-dpad, credit ars18/alltechdev):** a truncated/corrupt engine archive makes sherpa-onnx ABORT
  natively (uncatchable - the process dies), and warmUp() runs at startup, so a bad model was an
  unrecoverable crash loop. `AsrRecognizer` bumps `asr_load_strikes_<id>` before every native load
  and zeroes it after; TWO stranded loads in a row latch `asr_model_bad_<id>`, delete THAT engine's
  dir only, and report not-installed (one stranded load is forgiven - a mid-load process kill
  strands the counter exactly like a crash, and must not delete a healthy 154 MB download). A fresh
  download clears its own quarantine (`clearQuarantine(engine)` in downloadAsrEngine). And
  `listen()` returns a `VoiceResult` (Text/NoSpeech/Failed(reason)) instead of String? - the seven
  failure exits used to collapse into one silent null; MapScreen now explains MODEL / PERMISSION /
  VAD / AUDIO_INIT / RECORDING in a VelaDialog with retry, and a DENIED mic prompt surfaces too.
  Transcript cleanup moved to `SpeechText.cleanSearchTranscript` (:core, unit-tested): strips
  Whisper's bracket tags ("[music]", incl. a truncated trailing "[musi") so junk collapses to
  no-speech instead of searching for the literal tag. Logcat tag `VELAASR` names every failure.
- **Tier-1 ASR is now a PICKABLE ENGINE, not Whisper-only (2026-07-20, device-verified on the P4a).**
  `WhisperRecognizer`→**`AsrRecognizer`** and `AsrModel`→**`AsrEngine`** (an enum catalog): three
  on-device engines share the sherpa-onnx runtime - **Whisper tiny** (`whisper`, multilingual default,
  58 MB), **SenseVoice** (`sense_voice`, en/zh/ja/ko/yue, 154 MB, `OfflineSenseVoiceModelConfig`
  useInverseTextNormalization=true), **Moonshine** (`moonshine`, English-only, 101 MB,
  `OfflineMoonshineModelConfig` 4-onnx). `AsrRecognizer.ensureRecognizer` builds the right
  `OfflineModelConfig` per engine and caches on `loadedKey="<engineId>|<lang>"` (rebuilds on engine OR
  language switch). Each archive is SELF-CONTAINED - `silero_vad.onnx` ships inside every
  `<id>/` folder, so the VAD travels with the engine. **Whisper stays `AsrEngine.DEFAULT`** so no
  language regresses; SenseVoice/Moonshine are opt-in. `AsrEngine.active(ctx)` = the picked engine if
  installed, else the first installed, else DEFAULT (pref `asr_engine` in `vela_settings`). Selection
  degrades gracefully: deleting the active engine falls back. State is `asrInstalledIds:Set<String>` +
  `asrActiveId` + `asrDownloadingId` (not the old `asrInstalled:Boolean`). Settings → Search now shows
  a **per-engine list** (each row: name, languages, size, Download/Use/Active/Remove), always visible
  (not gated on "model AND provider"). Models built by **`scripts/build-asr-model.sh <id>`** (repackage
  the upstream k2-fsa sherpa int8 prebuilt + inject the VAD from the Whisper archive → `vela-asr-<id>.tar.gz`
  on the `asr-models` release). The map's one-tap mic offer still installs the DEFAULT (Whisper). Note:
  SenseVoice pins the app language only when it's in {zh,en,ja,ko,yue}, else "auto"; Moonshine ignores
  language. Older single-model details above (WhisperRecognizer/AsrModel/asrInstalled) are superseded.
- **The FIRST fix never yanks a map the user has already panned (issue #362, 2026-09-13).** A cold
  GPS start takes 15 to 30 s indoors; a fix landing after the user had started looking around
  set `center` and the camera flew home and zoomed in (benwiley's "cannot recenter, it
  autocenters", and the same complaint on the 4a). `MapScreen.onUserPan` now also calls
  `vm.onUserPanned()`, and the first-fix branch sets `center` only while that flag is false. The
  locate FAB (`recenterTick`) is untouched: a tap is an explicit ask.
- **Location is requested in onboarding, NOT on map load (2026-07-10).** `MapScreen`'s
  `LaunchedEffect` only STARTS location when it's already granted; it no longer fires the raw
  system dialog. The first ask lives in `VelaRoot`: when onboarding reaches the location step
  (`Onboarding.showLocationPrompt`, armed by `completeWelcome`) a `LaunchedEffect` fires the raw
  Android permission dialog **directly** - no separate rationale screen, the `WelcomeScreen` is
  context enough for a maps app (user call 2026-07-10: one less thing to tap through). The result
  callback arms the voice step. Order: welcome → system location dialog → voice. A denial leaves
  search/browse working (the locate FAB `onRecenter` re-requests on tap); a **coarse-only** grant
  drives an approximate dot via the NETWORK provider (device-verified: COARSE granted / FINE denied
  advances the flow and the coarse fix path handles it). The `PermissionRationale` composable is
  kept (it's still the reusable pre-permission screen the **PR3 voice-search mic** uses at
  point-of-use for RECORD_AUDIO) - it's just no longer used for location. Don't re-request location
  straight from the map.
- **ONE stacked notification area on the map (2026-07-10).** The heads-up flash, download
  progress cards, the update card and pushed notices all render in the SAME TopCenter Column in
  MapScreen (each with its own dismiss) - the old separate status card sat on the category chips
  and painted over the update card. Position: browse = statusBar + 132dp (just under search bar +
  chips); during nav it hangs off the turn card's MEASURED bottom edge (`navBannerBottomPx`, the
  same onGloballyPositioned report the compass uses) + 10dp, so it slides with lane rows / the
  "then" row instead of a guessed fixed offset. AUDITED COMPLETE 2026-07-10: the FASTER-ROUTE
  offer during nav renders IN the column too (it used to sit at a fixed 96dp under the turn card),
  and the bottom PSDS tip is gated to the bare map + yields to the resume-nav card - every
  top-of-map card is in the one column; bottom cards (PSDS tip, resume-nav) are bare-map-only.
  The flash (`MapUiState.status`) shows in ANY map state; the other cards stay gated to the bare
  map, which INCLUDES during nav - a mid-drive voice/region download or update offer stacks under
  the faster-route/status cards instead of hiding. `statusVoiceAction` on the state marks a
  voice-problem flash: `InfoCard` then adds a filled **"Get a voice" pill** (UpdateCard layout)
  that deep-links Settings -> voice library (`MapScreen.onOpenVoiceSettings` -> VelaRoot ->
  `SettingsScreen(openVoiceLibrary = true)`). Both the no-engine warning and the
  missing-language hint carry it.
- **Spoken directions are a persistent toggle (2026-07-10).** Settings -> Voice top row
  ("Spoken directions", pref `spoken_directions` in vela_settings, default on) and the in-nav
  speaker button share ONE state: `MapViewModel.setSpokenDirections` writes the pref + `voice.muted`;
  `toggleVoice` routes through it; init applies the pref at startup. When it's OFF the no-voice-
  engine warning is suppressed (silence is chosen, don't nag).
- **Closing-time warning at nav start (2026-07-10).** `MapViewModel.maybeWarnClosingSoon` (called
  in `startNav` before launch/demo): when the drive's arrival (now + in-traffic ETA) lands within
  an hour of the destination's closing time, or past it, it warns ONCE - `flashStatus` heads-up
  card + `voice.speak` - "X closes at 9:00 PM and you arrive around 8:40 PM". Closing time is
  parsed from the place's own localized STATUS TEXT by `:core`'s `data/ClosingTime`
  (unit-tested): 12h and 24h shapes, last-time-token-wins, and it ONLY parses an OPEN place -
  a closed status carries opening times ("Closed - Opens 9 AM"), the classic mistake. A "12 AM"/
  "00:00" closing returns 1440 (tonight's midnight) so plain arithmetic works; a closing that
  reads earlier than now is treated as past-midnight (+24 h). Guards: the selected place must sit
  within 200 m of the route end (multi-stop trips whose last stop isn't the selected place skip
  the warning). NB on a device with NO TTS voice the later "no voice engine" hint overwrites the
  flash (single status slot) - with any voice installed the warning shows and is spoken.
- **Location-permission UX gates (2026-07-10).** Turn-by-turn REQUIRES precise location (coarse
  fixes are ~2 km; the nav fix discipline correctly refuses non-GPS and >50 m fixes, so nav on
  coarse sat at "Searching for GPS" forever with no explanation). `onStartNav` in MapScreen now
  gates on FINE: without it (and demo-drive off - `vm.demoDriveOn()` skips the gate since demo
  simulates), a VelaDialog explains and "Allow precise" fires the FINE+COARSE request, which Android
  renders as its approximate-to-precise UPGRADE dialog; on grant it starts location + continues
  through the notification gate into nav. Declining the upgrade toasts `nav_precise_toast`. The
  locate FAB's re-request now also handles the DEAD-BUTTON case: a fully-denied result (including
  Android's instant deny after "don't ask again") opens a dialog with an Open-settings deep link
  (ACTION_APPLICATION_DETAILS_SETTINGS). Device-verified end to end: Start on coarse -> gate ->
  upgrade dialog -> precise -> notification permission -> nav running.
- **Vague fixes draw an ACCURACY HALO (2026-07-10).** `MapUiState.myAccuracyM` carries the live
  fix's reported accuracy (null for sim/unknown); `applyData` draws a translucent meter-true disc
  (`ACCURACY_LAYER`, a 64-point polygon from `accuracyCircle`, fill #4285F4 at 0.22 + a 1.5 px edge
  LineLayer - 0.12-0.15 vanished into the dark basemap's own blue) under the dot
  when accuracy > `ACCURACY_HALO_MIN_M` (100 m) and NOT navigating. With a COARSE-ONLY permission
  MapScreen falls back to 2000 m when the fix hasn't reported accuracy yet (Android hands coarse
  apps a fix only every few minutes), and the recenter tap ZOOM-FITS the circle (at street zoom a
  2 km halo covers the whole screen as an invisible uniform wash; the blob only reads when its edge
  is on screen). **The fit works in DENSITY-INDEPENDENT px with the 512-tile constant
  (78271.517*cos(lat)/2^z m/dp, 2026-07-10)** - the first cut used physical px + the 256-tile
  constant, landed ~2.5 levels too close on a 2.75x-density phone, and the circle still overflowed
  the screen as the invisible wash (why "i am not seeing a large blob" persisted through a whole
  build). Device-verified: locate on coarse-only now frames the disc at ~70% of screen width with
  its edge line visible - so an approximate-only
  permission or a weak network fix reads as "somewhere in this blob" instead of a falsely precise
  dot, and ordinary GPS (3-30 m) stays a plain dot. Identity-gated like the other applyData uploads.
  Render verified on-device via a temporary forced-1500 m build (a real coarse fix is
  interval-throttled by Android, too slow to wait out in a test loop).
- **Onboarding is deliberately SHORT - four steps, no more (declutter 2026-07-10).** Welcome →
  location → notifications → voice, then the map. The notification step (2026-07-10, Android 13+
  only, `Onboarding.showNotifPrompt`) fires the raw POST_NOTIFICATIONS dialog right after the
  location result so turn-by-turn's next-turn notification works on the first drive; a denial gets
  ONE plain-words "Skip notifications?" are-you-sure (re-request on Allow, move on on Skip), and
  the nav-start point-of-use gate stays as the fallback for skippers/existing installs. The
  location step also grew a COARSE-ONLY branch: granting approximate pops an "Approximate
  location" dialog (what it means, nav won't work) whose "Allow precise" re-runs the request as
  Android's upgrade choice - asked ONCE (`approxAsked`), keeping it never nags again. The locate
  FAB's re-ask does the same via `showApproxNotice` in MapScreen. The old **offline-maps** onboarding prompt and the **diagnostics/
  trip-recording** onboarding prompt were CUT (they made a long wall of asks a first-run user has no
  context for). Both settings still exist, off by default, in Settings → Diagnostics + Offline; the
  diagnostics ask now surfaces **in context** on the crash-report card (Settings → Diagnostics only
  renders the card when a crash is pending) - a "Turn on diagnostics" button appears there when a
  crash is pending AND diagnostics is off, routing through the same `showDiagConsent` VelaDialog the
  toggle uses. `Onboarding` no longer has `showOfflinePrompt`/`showDiagPrompt` - don't re-add
  onboarding steps; if a feature needs a first-run nudge, prefer a contextual in-place ask like the
  crash card. NO Material You toggle in onboarding either (it's a Settings → Appearance opt-in).
- **VelaDialog: confirm is a filled pill, dismiss is a text button (2026-07-10).** The `DialogButton`
  `filled` flag (set on the confirm button in `VelaDialog`) draws the higher-emphasis action as a
  primary `CircleShape` pill (onPrimary text); the dismiss stays a plain text button, quieted further
  to `onSurfaceVariant` when `dismissLowEmphasis`. One change in `VelaDialog`, so EVERY dialog (voice,
  location, diagnostics consent, trip consent, …) gets the pill - don't hand-roll per-dialog buttons.
  The map's UpdateCard uses the same treatment (2026-07-10): its Update action is a filled primary
  CircleShape `Button` beside a plain "Not now" text button, so the action reads by shape and fill,
  not text color alone (color-blind safe). Give any future card's primary action the same pill.
  The button row is a **`FlowRow`** (not `Row`): when the two labels don't fit on one line (a long
  "Download Vela voice" pill beside "Use system voice", or a small/feature-phone screen) the confirm
  pill wraps to its OWN full-width line instead of being squeezed and breaking mid-word. Both buttons
  keep the D-pad ring/focus (dismiss auto-focuses, confirm by arrow); the pill ring follows CircleShape.
- **D-pad-only operation is a hard UI rule (2026-07-07, `docs/dpad.md`).** The whole app
  works with a 5-key D-pad and NO touchscreen (touch is a bonus). Helpers in
  `app/ui/DpadFocus.kt` (`rememberDpadMode`/`rememberNoTouchDevice`/`Modifier.dpadHighlight`/
  `Modifier.dpadFieldEscape` - makes a text field's UP/DOWN escape it instead of
  being swallowed as a cursor move, so controls below the field stay reachable - and
  `rememberDpadAutoFocus()` - attach its `FocusRequester` to a screen's primary element so
  focus is PLACED on appearance, no wake-up keypress; retries because the node isn't attached
  on frame 1);
  the map is key-driven via `app/ui/map/MapDpadController.kt` (wired in `VelaMapView`, key
  handling + crosshair + zoom buttons in `MapScreen`).
  **AdaptiveDensity (2026-07-13, from the vela-dpad fork by ars18/alltechdev):** `ui/AdaptiveDensity.wrap`
  (chained FIRST in both `attachBaseContext`s) shrinks the app's effective density so tiny feature-phone
  screens report >= 360dp of logical width - chips/dialogs/rows fit instead of clipping. It is a HARD
  NO-OP at >= 360dp (ordinary phones byte-identical); tuned visually on 240x320-class flip phones by the
  fork author. Same commit brought `Modifier.dpadAutoFocus(requester)` (confirm-until-landed retry on a
  caller-owned requester - the weak rememberDpadAutoFocus can bail before focus actually lands) and the
  Settings DOWN-from-Back bridge (TopAppBar -> content Column crossing clears focus otherwise).
  **Detection is CONSERVATIVE - do not loosen it (fixed 2026-07-08).** `rememberDpadFirstDevice`
  (`detectDpadFirst`) returns true ONLY for a genuinely touchless device (`!FEATURE_TOUCHSCREEN`)
  or a PHYSICAL (non-virtual) `InputDevice` with `SOURCE_DPAD`. It must NOT count the framework's
  Virtual aggregate device (id −1): it reports `KEYBOARD | DPAD` on essentially every phone
  (verified on a Pixel 9 via `dumpsys input`), so counting it made `dpadMode` always-true on
  ordinary phones and BROKE the search bar (a tap no longer opened the field / raised the keyboard;
  the `+`/`−` zoom buttons showed under touch). **Since 2026-09-14 (issue #393) the zoom pair ALSO
  shows under touch when "Prefer buttons over swipes" is on**: one pill (two 40 dp boxes, the
  parking button's Surface dress) in the bottom-right stack above the parking button, gated by
  `dpadMode || PreferButtons.on`. A fake-touchscreen keypad phone is NOT D-pad-first
  then; it gets full D-pad operation reactively on the first key via `rememberDpadMode`
  (`dpadFirst || inputMode == Keyboard`). The soft keyboard in `SearchBar` is likewise keyed off the
  LIVE `inputMode`, not the static device type, so a touch tap raises it even on a hybrid phone. See
  docs/dpad.md. Rules when touching UI: (1) every new
  interactive element must be focusable with a visible ring (`dpadHighlight`) and every new
  gesture needs a key alternative; (2) D-pad code CALLS THE TOUCH PATHS (the named `handleTap`
  lambda, `gestureMove`, `navUserZoom`) - never fork them; (3) all D-pad affordances gate on
  `dpadMode`/`noTouch` so touch UX stays byte-identical; (4) keep the diff merge-friendly - 
  new behavior in new files, shared-file edits as small anchored insertions (the one
  commented import block per file). Search-overlay focus is subtle (armed field + explicit
  `searchExpanded` flag - THREE traps documented in docs/dpad.md: opens-on-focus,
  can't-BACK-out, and DOWN-must-escape-into-the-suggestions); don't "simplify" it back to
  bare field-focus. The full-app D-pad sweep (2026-07-07) also made Choose-on-map keep the
  map pannable to place the pin (a `pickOnMap` exception in `mapTargetHidden`) and scroll-cap
  the directions panel so **Start** is reachable with 4 alternates (helps touch too). The one
  raw WebView in the app - the full-screen "Read all reviews" panel (`ReviewsPanel`,
  `fullScreen`) - maps ↑/↓ to `pageUp`/`pageDown` + `requestFocus()`es so it scrolls by D-pad
  (a WebView's default is to hop focus between links, not scroll); reach/exit are proven, exit
  is always hardware BACK via the `Dialog`'s `BackHandler`. **D-pad-FIRST initial focus is a
  hard rule (sweep 2026-07-07): NO screen/view may open with nothing focused** - a wasted
  first keypress is the bug. Compose doesn't give this for free (focus recovery is
  nondeterministic - the place sheet landed on a photo / the search bar / nowhere), so every
  screen attaches `rememberDpadAutoFocus()` to a primary element (Settings→back, Welcome→Get
  started, place sheet→handle, directions→Drive tab, steps→first row (current step while navigating), reviews→back arrow);
  the map + photo gallery already self-focus. When adding a screen, give it an auto-focus
  target. **Menus & dialogs (the hard one): a Compose `DropdownMenu` Popup / `AlertDialog` can
  NOT be pre-focused (~10 approaches proven to fail - requestFocus/moveFocus/synthetic KeyEvent);
  only a hand-built RAW `Dialog` with an explicit `.focusable()` element auto-focuses.** So use
  **`VelaMenu`** (`ui/VelaMenu.kt`, drop-in DropdownMenu: anchored DropdownMenu under touch,
  auto-focusing raw-Dialog chooser under D-pad) and **`VelaDialog`** (`ui/VelaDialog.kt`, drop-in
  two-button AlertDialog that auto-focuses its dismiss button) - NEVER a bare `DropdownMenu`/
  `AlertDialog` for new D-pad UI. Their buttons/items focus via `.focusable()`+`.onKeyEvent`
  (OK) + `pointerInput` (touch), NOT `.clickable` (whose nested focusable won't take requestFocus
  in a Dialog window).
- **Localization (i18n) is three layers, one control (`AppLocale`, `ui/`, same process-wide reactive
  holder shape as `AppTheme`).** `AppLocale.language` = "" (follow system) or a code; Settings → Language
  picks it. (1) **Spoken nav** - the GENERATED turn-by-turn text is a per-language `NavStrings` table in
  `:core` (`core/i18n`), switched by `NavStringsRegistry`; `AppLocale.apply()` drives it. **BOTH routers feed
  it:** `RouteGeometry.osrmPhrase` (online OSRM) AND `OfflinePhrases.phrase` (offline) map their
  maneuvers to the OSRM `(type, mod)` token pair and call `NavStringsRegistry.current().phrase(...)`, so
  offline routes localize through the same 11 tables (ghPhrase used to hardcode English - audit 2026-07-06).
  **The chosen neural
  voice must actually speak that language** - `VoiceGuide` guards on `NeuralSynth.voiceLanguage` and, on a
  mismatch, falls back to a system TTS in the target language (or stays silent + fires a "get a matching voice"
  hint) rather than reading, e.g., Russian nav text through the English Piper model (see the voice bullet under
  Degoogled constraints). **Foreign NAME romanizing (issue #184, 2026-07-20):** a road NAME can be in a
  different script than the guidance language (Hebrew name inside English guidance), and a single-language
  voice drops those glyphs. `SpokenScript.forVoice(text, voiceLang)` romanizes to Latin (android.icu
  `Any-Latin; Latin-ASCII`) any run in a script the SPEAKING voice can't read, applied around `forSpeech()`
  in BOTH speak paths; SPOKEN string only, the banner keeps the local script. **Latin is the universal
  fallback for EVERY voice, not just Latin-script ones (un-scoped 2026-07-19, user: a Chinese driver in
  Israel wants Latin, not dropped Hebrew - that is what Google does):** each voice keeps only its OWN
  native script (`VOICE_SCRIPT` maps he/ar/ru/el/th/hi/ko/zh/ja to their script; everything else = Latin),
  so a Russian voice keeps Cyrillic but romanizes Hebrew, and a Chinese voice keeps Han but romanizes
  Hebrew/Cyrillic. CJK Han/kana are the ONE exception - never romanized for any voice (ICU reads Han as
  Chinese pinyin, "明治通り"->"ming zhitongri" not "Meiji-dori"), left to the native CJK voices. NB a
  CJK/Cyrillic voice reading romanized Latin is rough (their G2P is not built for Latin), but audible beats
  dropped; the clean win is the DISPLAY side (name:latin), pending. Logic is unit-tested with an injected
  romanizer (android.icu is JVM-stubbed in unit tests); real ICU output validated via `uconv` (same libicu).
  **REAL romanized names beat ICU (2026-07-19, tiles phase):** ICU on an abjad like Hebrew is a vowel-less
  skeleton ("רחוב הרצל"->"rhwb hrzl"), unpronounceable. Real names are DATA: the OpenMapTiles
  basemap carries `name:en`/`name:latin` per road. `VelaMapView`'s existing nav-label pass
  (querySourceFeatures over `transportation_name`) also records `localName -> latinAliasOf(feature)`
  and reports it via `onNavRoadLatin` -> `MapViewModel.onNavRoadLatin` -> `MapUiState.roadNameLatin` +
  `VoiceGuide.roadNameLatin`, growing as tiles load, reset on nav end. **The dict query is WARMUP-paced,
  NOT the per-400 m quantum the label pass uses:** a drive STARTS with only the route-overview (low-zoom)
  tiles loaded, which carry ONLY major road names (device-proven: dict=19 major roads at nav start), so the
  first turn onto a minor road spoke the ICU skeleton. The loop now re-queries the dict every 2 s tick WHILE
  it is still growing (`dictStaleTicks < 3`, reset on each quantum), so a local road's real name lands within
  a couple seconds of its nav-zoom tile loading (device-proven: dict jumped 19 -> 404 as local streets
  resolved to real romanized names with vowels, not the consonant skeleton, on both the banner and the
  voice). The expensive crossing geometry stays per-quantum, so this never puts a heavy pass on the
  every-frame path. The nav-start OPENER ("Starting navigation. Head ... on <road>") is spoken via
  `VoiceGuide.speakOpener` (not `speak`): it HOLDS the opener up to `OPENER_MAX_WAIT_MS` (2.5 s), retrying
  every 200 ms until the road it names is covered by `roadNameLatin` (or the text has no foreign run), then
  speaks - the drive begins before nav-zoom tiles load, so speaking at T=0 read the ICU skeleton; the wait
  lets the dict fill so the opener says the real name (device-proven: opener spoke the real romanized road,
  not the skeleton, once the dict reached ~400). `stop()` bumps `openerToken` to cancel a still-waiting
  opener. An English opener never waits (hasForeignRun false).
  **On-MAP labels romanize too (2026-07-19):** the maneuver banner/voice were romanized, but the nav
  road-name BUBBLES (`ensureNavRoadLabels`, `textField(get("name"))`) and the browse basemap street
  labels (`highway-name-*` in all four `applyLight`/`applyDark`/classic palette fns) still drew the
  local script (user: "why are the street name bubbles in jew still when we have english set"). Both now
  use `roadLabelTextField()` = `coalesce(get("name:en"), get("name:latin"), get("name"))` for a
  Latin-script UI (gate `uiWantsLatinLabels()` / `NON_LATIN_UI_LANGS`; a Hebrew/Russian/etc UI keeps the
  local `name`). Same name:latin tile data as the voice/banner, so labels + guidance agree. The nav-bubble
  filter still matches on the canonical `name` (Hebrew) - display latin, filter on name. NB the search-result
  markers + transit-stop labels stay on `name` (those are place-name DATA, not streets - a Hebrew business
  keeps its Hebrew name like Google).
  **PLACE labels follow the UI LANGUAGE (issue #598, 2026-09-19):** Liberty stacks `name:latin` over
  `name:nonlatin` where both exist, so an English phone drew a Hebrew or Japanese city twice, once
  in a script its reader cannot use. `placeLabelTextField()` coalesces the UI language's own tag
  first (`uiLangTagFields`), then for a
  Latin-script reader `name:en`, Liberty's `name_en`, `name:latin`, and finally the local `name`;
  a non-Latin reader gets their tag then the local name, never a transliteration. Applied to the
  nine `place` layers (`PLACE_LABEL_LAYERS`) from `applyMapTheme`, NOT from the four palette
  functions, because the text does not change with the colors; a language change recreates the
  activity, which reloads the style and runs it again. TWO codes do not map straight through:
  Android still reports Hebrew as `iw` while the tiles carry `name:he`, and Chinese splits by
  SCRIPT rather than language, so a Traditional reader (locale script Hant, or country TW/HK/MO)
  asks for `name:zh-Hant` before `name:zh` and falls through where OSM has not tagged it - the
  same split `NavStringsRegistry.tagOf` makes for the nav tables. Device-verified over Tokyo:
  every `place` label draws one Latin line where it used to draw a stacked pair.
  `SpokenScript.forVoice(text, lang, dict)` swaps a known local name for its real Latin form FIRST, ICU only
  for the rest; `SpokenScript.forDisplay(text, uiLang, dict)` does the same for the banner + steps but with
  NO ICU fallback (a skeleton on a sign reads broken - why the earlier ICU display romanization was
  reverted; an unmapped name keeps local script). Both gate on the UI/voice's own script (a Hebrew UI keeps
  Hebrew). Works ONLINE and in a downloaded map AREA (both carry name:latin tiles). Quality tracks OSM
  name:en coverage (Israel well-tagged -> real names; sparse areas keep local script on display / ICU by
  voice), same as Google.
  **OFFLINE across a whole region: the obf route carries its roads' Latin names** (2026-09-15,
  `Route.roadNamesLatin`, see the obf bullets; it replaced the 2026-07-19 `names.tsv.gz` sidecar that
  rode beside the GraphHopper graph, retired with the graphs). `NavController` merges the map into
  `roadNameLatin` when a route is adopted; the nav tiles add the rest as they load; nav end resets to empty.
  (2) **UI chrome** - 
  all ~330 user-facing `:app` strings live in `res/values/strings.xml` (English) + `res/values-<lang>/` for
  the 15 translated languages (fr de es it pt nl ru pl sv uk hu iw + zh zh-rTW ja; CJK added 2026-07-11, Hungarian contributed by Zsolt Laszlo Kaiser from the kaiser-app fork and ported 2026-09-13 with its NavStrings table, status words, review words, transit words and the Anna Piper voice, Hebrew 2026-07-13),
  referenced via `stringResource`/`getString`. **CJK notes (2026-07-11):** Chinese ships as
  `values-zh` (Simplified, also the fallback for any zh region without its own folder) +
  `values-zh-rTW` (Traditional, Taiwan wording - issue #55); the in-app picker codes are "zh",
  "zh-TW" and "ja" and hyphenated codes MUST resolve via `Locale.forLanguageTag` (a `Locale("zh-TW")`
  constructor makes a bogus lowercase LANGUAGE and matches nothing - AppLocale.effective/wrap do this).
  `NavStringsRegistry.tagOf(locale)` splits Chinese by SCRIPT (Hant script or TW/HK/MO country ->
  the zh-tw table, else zh); `localized()` mirrors it for the scrape (`hl=zh-TW` vs `hl=zh-CN` -
  bare hl=zh is Simplified). `parseOpenNow` keys stay the bare "zh" with BOTH scripts' keywords in
  one table. TTS: ONE Mandarin Piper voice (`zh_CN-huayan-medium`, langCode "zh") pairs with both
  Chinese tables; **Japanese has NO Piper voice** - ja spoken guidance rides VoiceGuide's
  system-TTS-in-target-language fallback (silent + hint when none installed); a non-Piper sherpa
  ja model is the follow-up (needs PiperSynth config work, see ROADMAP). Whisper dictation pins
  zh/ja automatically (multilingual tiny; whisperLang reads `.language`, so zh-TW dictates as zh).
  **TWO CJK build traps that a warm Gradle daemon HIDES locally but CI catches (2026-07-11):**
  (1) in a Kotlin string template, `"$road出发"` parses `road出发` as ONE identifier (CJK chars
  are valid in Kotlin identifiers) -> "unresolved reference"; ALWAYS brace a `$var` that touches a
  CJK char: `"${road}出发"`. (2) in strings.xml a raw apostrophe (`app's`, `l'ancien`) is an AAPT
  error the RELEASE resource merge rejects even though a cached debug build passed; escape as `\'`
  (the whole file already does). **A raw DOUBLE quote is worse, because it is not an error at all -
  it is silently STRIPPED**: a hint written `\"Turn left\" instead of \"Turn left onto Maple Street\"`
  shipped as `Turn left instead of Turn left onto Maple Street` and read as a broken sentence, past
  both a debug and a release build, and was only caught by reading the row off a device
  (2026-09-19). Escape it as `\"`, which is what the rest of the file does. Both slipped a local `:core:test`/`assembleDebug` because the
  daemon reused stale outputs - trust CI, or `--rerun-tasks` when touching these.
  **(3) A REGEX THAT COMPILES ON THE JVM CAN THROW ON ANDROID (2026-09-21).** Android's regex is
  ICU, the unit tests run java.util.regex, and ICU is stricter: an unbalanced `}` (`\{DLAT}` with
  the closing brace bare) is a `PatternSyntaxException` on the phone and fine on the JVM. Worse,
  a `Regex` in an `object`'s initializer takes the WHOLE object down: `DirectionsPb` threw
  `ExceptionInInitializerError` once, every later call logged "Rejecting re-init on
  previously-failed class", and Google directions were dead in that build while 660 tests were
  green. Use `Regex.escape(literal)` for literal text, and read logcat after the first device run
  of any new `object`-level Regex.
  The runtime switch is `AppLocale.wrap(context)` (overrides the Configuration locale; when FOLLOWING
  the system it also RESTORES `Locale.setDefault` to the captured device locale - the override is
  process-global and survived the recreate, so switching Russian back to English left
  `Locale.getDefault()`-driven surfaces (parking/trip dates via SimpleDateFormat, `effective()`,
  the scrape `hl=`) stuck in Russian until the process died; fixed 2026-07-10) applied in **both** `MainActivity.attachBaseContext` (Compose
  UI) and `VelaApp.attachBaseContext` (ViewModel/notification `getString`); changing the language calls
  `recreate()`. (3) **Google POI content** - the scrape's `hl=en` is rewritten to the app/system language
  at request time (`GoogleMapsDataSource.localized()`, no-op for English) so categories/hours/status/price
  come back localized. **The rewrite is GATED to `SearchParser.STATUS_LANGS` (= the 11 keyword-table
  languages, keyed off `CLOSED_WORDS`)** - for any OTHER locale the scrape stays `hl=en`, because a
  status string in a language `parseOpenNow` can't read leaves openNow null forever and the UI can't
  color open/closed; English text the English table handles is the safer fallback (audit 2026-07-06).
  The **open/closed BOOLEAN is parsed from the localized status TEXT against a
  per-language keyword table** (`SearchParser.parseOpenNow(status, lang)`, `lang` = the same
  `Locale.getDefault()` that set `hl=`; CLOSED words are matched FIRST - "Opens 5 AM" / "Ouvre à 07:00" /
  "Fechado" / "Opent om 9:00" are prefix-cousins of the open words, and open-first matching is exactly
  what painted a closed Starbucks green). **Do NOT resurrect the numeric status-code path**
  (`openFromCode`, paths `statusCodeRich`/`statusCodeSimple`, removed 2026-07-04): a live EN capture
  proved those ints are span/style markers, not open/closed codes (closed pharmacies carried "open" 6,
  an Open-24-hours place carried 13/4 and rendered red) - the hl=fr pin agreeing was a coincidence.
  `placeStatusColor(status, openNow)` colors from the boolean and refuses to green English text that
  literally reads closed even if fed `openNow=true`. **`gl` (region) follows the PHONE's region
  (2026-07-14):** `GoogleMapsDataSource.glRegion` (set by the VM at init from the cell network's
  country, locale fallback) rewrites `gl=us` in `regionalized()` - clean 2-letter codes only, US
  phones byte-identical; region tunes ranking/bias, not response shape. **Dual-purpose literals
  stay inline on purpose** (NB the REVIEW SORT menu + the place-sheet TAB titles were split
  2026-07-14: localized display labels over English logic keys - the sort KEY must stay English
  because it drives the live hl=en Google panel by clicking the matching option) - 
  strings that double as a logic key (place "Open"/"Closed" → status-color parser, the map category chips /
  search-along-route chips are also the query, review sort/tab labels branch a `when`) are NOT in strings.xml;
  they localize only once display text is split from the logic key. One exception by REGION, not
  language (issue #338, 2026-09-06): the fuel chip's query is `ui/CategoryQuery.fuel()`, "Petrol
  station" when the effective locale's country says petrol (GB, IE, AU, NZ, IN, ZA...) and "Gas
  station" elsewhere, because "gas" in the UK returned gas suppliers and car parks; `values-en-rGB`
  carries the matching "Petrol" label and nothing else. `OfflinePoiStore` maps both queries to
  `amenity=fuel`. **Names/addresses/reviews are DATA - never
  translated.** **Translations come in as PULL REQUESTS (docs/TRANSLATING.md; issue #285):** adding a
  user-facing string means adding it to the ENGLISH base `values/strings.xml` only - contributors fill
  the locales by editing `values-<lang>/strings.xml` (the em-dash + placeholder rules are the review
  checklist) and a missing translation falls back to English. Hand-filling every `values-<lang>/` in
  the same commit (the old rule) is still fine for small batches but no longer required.
  ⚠️ **WEBLATE IS NOT LIVE and its link 404s - do not point anyone at it** (issue #285, 2026-08-28):
  hosted Weblate requires a project to be at least THREE MONTHS OLD to qualify. **That bar is
  cleared as of 2026-09-15** (the repo was created 2026-06-15), so applying is now an open action
  rather than a wait; until an application is actually accepted the PR flow below is still what
  the docs describe. README/CONTRIBUTING/FEATURES/TRANSLATING were corrected to describe the PR flow; the README
  roadmap keeps it as an open item. Rewrite those four the day the project is actually approved. Match the
  `%1$s`/`%2$d` placeholder TYPE to the arg (Int → `%d`, else `%s`; a `%d` fed a String crashes).
  **Count strings use `<plurals>`, not a bare `%d X` (2026-07-11, issue #56 "1 results"):** the
  results-count bar is a `<plurals name="mapscreen_results_count">` read via `pluralStringResource(...,
  n, n)`. Use the CORRECT CLDR categories PER LANGUAGE - en/de/es/it/pt/nl/sv/fr = `one`+`other`
  (sv "resultat" is invariable); ru/uk/pl need `one`+`few`+`many`+`other` (e.g. ru результат/
  результата/результатов/результата). Any NEW "N &lt;noun&gt;" that can equal 1 (reviews, stops,
  places) should become a plural the same way; `place_review_count`/`place_transit_stops` are still
  bare and are the follow-up.
  **No em dashes in translations** (swept 2026-07-10): the 10 locale files carried 100+ of them as
  clause glue (plus German/Swedish spaced en dashes used the same way) - they read as machine-
  translation tells. Use a comma, a colon, or rephrase; the one legitimate dash is the numeric
  range in `place_usually_range`. Same rule as the rest of the repo.

- **README voice demo (`docs/voice-demo.mp4`, 2026-07-10).** A ~5.5 s clip of the ACTUAL nav
  voice linked from README's "What you get": generated OFF-DEVICE with the same engine + model +
  pace the app uses (pip `sherpa-onnx`, upstream `vits-piper-en_US-hfc_female-medium`,
  `length_scale=1.25` = the app's 0.8x default), then muxed to MP4 as a SHORT BLACK 640x120 STRIP
  (ffmpeg lavfi color source + aac) - a black frame keeps the README player compact and makes the
  controls (especially unmute) obvious; the earlier nav-screenshot poster rendered picture-sized. The render uses the app's PiperSynth OVERRIDES
  (noiseScale 0.45, noiseScaleW 0.55, speed 0.8) - the library-default noise scales sounded
  audibly different from the app (user caught it). MP4 not wav ON PURPOSE: GitHub's blob viewer renders a
  real PLAYER for mp4 but only a download link for wav (user hit that), and in-repo media in
  README markdown NEVER embeds inline (verified empirically on a test branch - bare raw/blob mp4
  URLs render as plain <a> links; only user-attachments URLs inline). The README NOW embeds an
  INLINE PLAYER via a user-attachments URL minted PROGRAMMATICALLY (2026-07-10): on the
  github.com new-issue page, same-origin fetch the repo's own raw mp4 into an ArrayBuffer, build
  a File + DataTransfer, dispatch a synthetic ClipboardEvent('paste') on the "Markdown value"
  textarea - GitHub's uploader consumes it and inserts the permanent
  github.com/user-attachments/assets/<uuid> URL (draft abandoned, nothing posted). If the demo is
  ever regenerated, repeat that mint and swap the URL in README - the old attachment URL keeps
  serving the OLD audio forever. The
  line is deliberately spelled/punctuated for the TTS ("in a quarter mile, turn right onto main
  street; then, at the roundabout, download vella!" - "vella" so espeak says the name right, the
  semicolon for the pause contour). Regenerate the same way if the default voice/pace changes. README feature copy rule: "What you get" is
  the HUMAN-GLANCE list (what people care about, plain sentences); FEATURES.md is the complete
  record; the README Roadmap holds only OPEN items. Screenshot rules (user 2026-07-10): the NAV
  shot leads the table, the transit shot shows the SCHEDULE BOARD with the ambient
  transit-lines layer toggled OFF for the shot (the purple lines over the map read as ugly; the
  schedules themselves are liked), and Install sits directly UNDER
  the screenshots. **Full retake 2026-07-16 (supersedes the Pixel 9 browse shots):** all 11
  shots (incl. the new 11-stop-list.png bus route timeline) shot on the 4a 5G with SystemUI
  demo mode (clock 1200, battery 100, icons hidden) and sim-location at the Davis fixture
  spots. **Shoot AFTER 11:30 AM Pacific**: the open/closed badge is decided by Google's
  servers at request time, so a spoofed clock can't make a closed Mikuni read "Open" - the
  demo clock only needs to AGREE with real time (noon clock, midday shoot, arrival times and
  departure boards all stay coherent). Camera counts on the route picker need Avoid
  surveillance cameras ON for the shot (restore OFF after). The site (site/assets/*.webp)
  carries the same shots at 720px q82. Site assets are ALL bundled locally (no hotlinks -
  the page makes zero external requests): the Obtainium/F-Droid badges are committed copies
  in site/assets/, and site/assets/voice-demo.m4a is the audio track extracted from
  docs/voice-demo.mp4 (`ffmpeg -vn -c:a copy`) - regenerate it whenever the voice demo is
  re-rendered. No Android Auto on the site yet (user 2026-07-16, not confident it works).

## Brand assets (2026-07-23)

One mark everywhere: the two-tone NAV CHEVRON (notched arrowhead, white left
half + `#39e0c8` right half) on the `#0d3d43` to `#149387` gradient. The
user's explicit pick (2026-07-23) after three rounds: sailboat variants, a
pin, a route-V, a constellation and a puck-in-circle were all considered and
rejected; they wanted the nav-arrow paradigm, two-toned, with NO circle, NO
star, NO pin. Canonical file is `docs/logo.svg` (512, rounded square). Derived copies that must stay in sync
when the mark changes: the launcher adaptive icon
(`drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml` gradient +
`ic_launcher_monochrome.xml` for themed icons - a hollow OUTLINE stroke, not a
fill, per the user 2026-07-23; the old
`@color/ic_launcher_background` is gone), the site navbar mark + favicon data
URI (`site/index.html`), and the README hero. The README hero also carries the
shields badge row + quick-nav links + website button (bambuddy-style); badge
URLs are shields.io (README only - the SITE stays zero-external-requests).
The GitHub social-preview image is uploaded manually in repo Settings and does
NOT live in the repo - re-upload it if the mark changes.

## README layout rule (2026-07-11)

The README stays SHORT: pitch, **"What reaches Google, by default"**, screenshots, install,
what-you-get, the full **privacy comparison matrix** (kept IN the README - user 2026-07-11, it's
the sharpest one-glance pitch), build. The at-a-glance table sits ABOVE the screenshots on purpose
(user 2026-09-19): people were reading the app as a Google Maps WebView, which the screenshots
encourage, and the full matrix was 200 lines down where a visitor never reached it. Its first line
answers the wrapper question directly and every row has to stay literally true - the routing row
says the traffic and only the traffic, because `directions()` does fetch Google in parallel for the
ETA.
Deep dives get a pointer line, not a section: the capability/method table, the calibration
walk-through and the map-style reference all live in **`SPEC.md`** (sections 1.4, 3, 11 and 6),
alongside docs/dpad.md and docs/book/. The Roadmap section carries a "Not going
to happen" split for login/backend features - keep new won't-dos there AND in ROADMAP.md.
Remote calibration is a HEADLINE feature in What-you-get (the self-healing pitch), not just an
architecture note.

- **Calibration word-table overrides were DEAD until 2026-07-19:** `Calibration` had
  statusClosedWords/statusOpenWords/transitCategoryWords/transitExcludeWords/stopBoardIndices
  and the app pushed them into the parsers, but `CalibrationStore.parse()` never read them from
  JSON, so every remote bundle silently dropped them. Wired now (lenient like tuning), BUT any
  build released before this date cannot take a word-table override - a keyword fix for the
  installed fleet still needs an app release until those builds age out. When adding a new
  Calibration field, the parse() wiring is a separate step that WILL be forgotten unless you
  grep for the field name in CalibrationStore.
- **Hebrew status strings are "המקום"-prefixed (2026-07-19):** Google serves "המקום סגור ..."
  ("the place is closed"), not bare "סגור", so parseOpenNow's startsWith needed the prefixed
  forms in CLOSED_WORDS/OPEN_WORDS (keys "iw" AND "he"). Pinned by PlaceStatusTest with live
  strings from an il-device diag. If another language shows openNow=null on every place, check
  the real status text for a carrier phrase before its keyword.
- **Search bias sanity (2026-07-19):** a no-GPS device (WiFi tablet) never moves the camera off
  MapLibre's 0,0 default, and search/suggest biased to null island. `plausibleBias()` in
  MapViewModel discards any bias point within ~50 km of 0,0 - keep new "near" consumers behind
  it. **Rank-from-you (2026-08-08, the "results ordered around some weird point" report):**
  `MapDataSource.search` grew `rankFrom` - the point the result ORDER and shown distances are
  computed from; the pb REQUEST bias stays `near` (the viewport is still the search area).
  `MapViewModel.rankBias(near)` passes the user's location when it is within ~50 km of the
  viewport, so a local search sorts and labels distances from YOU instead of reshuffling
  around wherever the screen is centered; browsing a far city keeps viewport-center ranking.
  Wired at runSearch + the suggest fetch; searchAlongRoute keeps its route-midpoint bias.
- **Release cadence (user 2026-09-13): merges go to `canary` (push main to the canary branch); nightlies
  are the daily cron's job. Do not dispatch CI after every merge.**
- **EVERY STABLE'S NOTES LEAD WITH A SHORT "WHAT'S NEW" LIST (user 2026-09-13).** The in-app
  What's new dialog (`ui/WhatsNew`) shows the release body verbatim, so a stable whose notes are
  only the generated commit list reads like a git log to the people it is for. When a stable is
  cut (the Monday promotion or an early one), edit its notes (`gh release edit vX --notes-file`)
  to put a hand-written list of the major user-facing changes above the generated
  "Everything since" commit list: one line per feature, plain words, the reason for an early
  cut first if there is one (0.4.1217 is the model). The promote workflow cannot write this
  part; it is the release's own job, the same day. Nightlies keep the commit list alone.
- **docs/FAQ.md (2026-09-18)** is the user-facing answer to "are the places Google's", the
  per-feature source matrix (map / places / place pages / search / routing / traffic / controls /
  cameras / transit / Street View, each with "reaches Google?" and "works offline?") and the recipe
  for running Vela with no Google contact, which since 2026-09-21 is ONE SWITCH: **Settings >
  Privacy > "Use Vela without Google"** (`ui/GoogleFree`, pref `google_free`, mirrored into the
  `:core` flag `data/NoGoogle`, the LowRamMode seam). Gated at the network edge in
  `GoogleMapsDataSource`: `search` answers from `PhotonGeocoder` (Photon's own importance ranking softly biased to the
  user, limit 20, then the suggest path's hard metro box appended for partial addresses; the box
  alone led with fuzzy address rows two states away and never showed the city itself, checked on
  the 4a. Names and addresses, not categories, which the downloaded place packs cover),
  `searchMore`/`nearbyPlaces`/`reviews`/`placePhotos` answer empty, `streetView*` null, and
  `googleDirections` empty, which every caller already reads as "Google did not answer" (open
  router only, no traffic, no alternates, no abbreviated fallback). App side: `MapViewModel.googleOff()`
  (= `offlineNow() || GoogleFree.on`, deliberately NOT `offlineNow` itself, which also picks
  routing and basemap fallbacks that must keep using the open services online) gates
  fetchPlaceDetails / fetchPhotos / fetchReviews / onPoiTap's lookup / the ambient fan-out; the
  two WebView warm-ups return; `HiddenWebView.request` returns null for all five fetchers in one
  place (reviews, photos, popular times, transit directions, the stop-board Google fallback);
  `ensureTraffic` is off (the raster is Google's tile server); the satellite `-1` fallback draws
  no deep layer; the place sheet hides the Street View pill and the full-screen reviews page.
  `importList` (a Google Maps share link the user pasted) is deliberately not gated. No unit test
  covers the switch itself (it is a flag read at each seam); the FAQ lists what it costs. Keep it in step when a source or a default changes; the
  fleet default for the places source lives in `calibration.json` (`defaultPlacesSource`, "open"
  today) and a change there needs `./scripts/sign-calibration.sh`.
- **A REGION IS PICKED BY ITS REAL BOUNDARY, NOT ITS BOX (issue #599, 2026-09-21).** Vietnam's
  Geofabrik extract carries the island claims, so its bounding box reaches 114.6 E and swallows Hong
  Kong; China has no obf row (it OOMs the bake), so nothing smaller competed and "download the area
  you're viewing" from Hong Kong announced "Downloading Vietnam". Kansas's box across the Missouri
  River was the same fact, handled there by streaming three candidates. `scripts/region-polys.py`
  fetches the `.poly` Geofabrik publishes beside every extract in `tools/routing-regions.json`
  (all 425), simplifies each to ~5 km and writes `app/src/main/assets/region_polys.json` (about
  300 KB, 20k points); `offline/RegionPolys` loads it once at app start (beside the flock data) and
  answers `covers(id, lat, lng)`, NULL when it has no polygon for the id. `RoutingRegion.covers` and
  `PmtilesRegionStore.Region.covers` ask it first and fall back to the box, so a catalog whose ids
  are not the routing catalog's (the building overlays) or a row added since the last bake behaves
  exactly as before. EVERY "which region is this point in" site goes through `covers()` now (the
  viewport download's routing / places / basemap / overlay picks, `archivesFor`, the two streaming
  unions, the routing offer, the region-update kinds, the saved-area pack lookup, `RoadFeatures`,
  the Offline settings "you are here" row); the tie-break among covering regions stays the smallest
  BOX (`boxArea`). Re-run the script when a catalog row is added; `RegionPolysTest` reads the
  shipped asset and fails if a region is missing (a bake fetch that failed would otherwise put that
  region silently back on its box) and pins Hong Kong outside Vietnam and inside China, Hanoi
  inside Vietnam, and Kansas City in Missouri not Kansas. Hong Kong itself has no Geofabrik
  extract of its own at first look; it turned out Geofabrik cuts one (see the catalog bullet), and
  `china-sub` was added the same day.
- **SHALLOW OFFLINE BASEMAPS ARE ONLY USED OFFLINE (2026-09-18):** `BasemapTileStore.maxZoomOf` reads
  byte 101 of the PMTiles v3 header; `refreshBasemapArchive` skips an archive shallower than
  `FULL_MAP_ZOOM` (14) unless `offline`, and the online/offline latch re-runs it. The workflow drops
  a zoom level when a bake would pass GitHub's 2 GiB asset limit, which is what makes a region
  shallow in the first place.
- **START RE-PLANS FROM THE FIX (issue #463, 2026-09-17):** `MapViewModel.startNav()` intercepts a
  custom `directionsOrigin` more than `START_FROM_ME_M` (150 m) from `myLocation`: it clears the
  origin, sets `autoStartOnRoute`, re-routes and lets the arrival start the drive, rather than
  handing NavSession a line the driver is not on and letting the off-route reroute rewrite it.
- **ONE TRIP EDITOR (issue #516, 2026-09-17):** `TripEditorSheet` + `tripPointsForEditor` / `applyTrip`
  is the editor for the route chooser regardless of the Google-style experiment; the pinned-ends
  `StopsEditorSheet` now serves only the IN-DRIVE case (where the start is where you are).
- **TAP-TO-STOP IN NAV (2026-09-17):** `MapPoiPrefs.navTapPlaces` (off; its setter also flips
  `showPois` on and remembers it in `KEY_NAV_TAP_FORCED_POIS` so turning it off restores exactly
  that). VelaMapView widens the drive-nav places filter from fuel-only to `NAV_DRIVE_GROUPS`
  (`placesNavDriveSet`); `onPoiTap` and `selectPlace` stop returning early during nav and instead
  set `navTapCandidate`, which MapScreen renders as `NavStopOffer` above the nav bar - the CARD's
  button is the second tap, and it calls `addStopDuringNav`. **Three additions 2026-09-18:** the
  card carries what the stop COSTS (`priceNavTapDetour` fetches one route through the candidate,
  bounded at `NAV_DETOUR_TIMEOUT_MS` 8 s, and `:core` `DetourEstimate.minutesAdded` compares it
  with the drive's own live `nav.remainingDuration`; under 20 s apart or over 3 h apart shows
  nothing rather than "+0 min" or a broken fetch's figure). The candidate goes FIRST in the
  waypoint list because that is where `NavSession.addStop` puts it - price the drive the button
  actually builds. The card AUTO-DISMISSES on a countdown ring drawn around its close button
  (10 s, 25 s under `dpadMode` since reaching the button takes more presses), keyed on
  `navTapOfferTick` so a second tap on the SAME place restarts the clock (an unchanged candidate
  leaves state equal, so keying on the place alone left the old clock running). And the offer is
  drawn on the map: `VelaMapView(candidatePin=)` feeds the existing stop-pin effect a red "+"
  teardrop (`PoiIcons.CANDIDATE_PIN`), so the driver can see WHERE the offer is. The alternates pane names the
  fewest-camera route when `flockOnRoute` has counts that differ (that list is only populated when
  "Avoid surveillance cameras" is on).
- **ALTERNATES PANE (2026-09-17):** the Google-style chooser owns its own list (`altsOpen` /
  `onAltsOpenChange`, BACK closes it through MapScreen's single back handler) instead of swapping in
  the classic panel; the affordance sits under the ETA and is always present (`exp_chooser_alts`
  plurals, `exp_chooser_alts_none`). `routeBubblesFor(..., detailed = altsOpen)` fills `RouteBubble.sub`
  with distance + delta and the bubble layer renders it as a second line. Only the fastest route is
  labeled "fastest"; a near-tie says "about the same time".
- **OSM POSITIONS IN THE PLACES BAKE (2026-09-17):** `OSM_PBF` (the region's Geofabrik extract, joined
  into the matrix from `tools/routing-regions.json` by id) is filtered with `osmium tags-filter` to
  NAMED business NODES, exported to geojsonseq (strip the 0x1e record separator before jq; the
  option to turn it off is not in every osmium build), and `osm_snap` moves a baked row onto OSM's
  coordinate on a whole-name match at 30-120 m. Order of preference: OSM, then the AllThePlaces
  locator, then Overture's parcel point; tenants never move. Unset `OSM_PBF` and the bake behaves
  exactly as before.
- **STOP SIGNS ARE GATED BY THE ROAD'S BEARING (2026-09-17):** `scripts/road_features_tsv.py` takes a
  second geojsonseq of the region's HIGHWAY ways (`--ways`) and writes a 4th TSV column: the
  orientation of the road at each control node, 0-179 undirected (99% coverage on a Delaware test).
  `TrafficControl.roadBearingDeg` carries it; `RouteProjection.bearingAt` / `alignedWithRoad` (40 deg)
  decide whether a STOP is yours, in `NavController.refreshNavRouteControls` only. Null bearing =
  keep, so an un-rebaked region and the live Overpass path behave exactly as before.
- **STOP SIGNS: DISTANCE GATING WAS WRONG (2026-09-17, reverted same day):** filtering a STOP by its
  distance to the driven line (11 m) removed nearly every sign on a real drive, because a clustered
  control sits at the junction's centroid, not on your lane. Any future attempt must gate by the
  node's INTENT (its `direction`/`traffic_signals:direction` tag, or the way it belongs to), which
  means carrying that through `TrafficControl` from both the Overpass parse and the road-features
  bake. Nav callouts carry `atM` and `applyNavLabelProgress` filters out the ones the puck has
  passed, re-filtering only when the NEXT callout is actually passed (a setFilter re-runs the
  layer's placement: every-25 m cost a 126 ms main-thread message on a 4a).

- **EXIT CALLOUT + CAMERA CLUSTER (2026-09-17):** `core/nav/ExitLabel.of(instruction)` pulls the exit
  NUMBER out of a maneuver (word table per language, plus the CJK number-before-word form; a bare
  number never counts, it is usually a road ref) and MapScreen passes it as `navExitCallout` for
  ramp/fork/keep maneuvers only; VelaMapView draws it from `NAV_EXIT_SRC` with the green twin of the
  callout sprite (`navBubbleBitmap(green = true)`), overlap allowed - it is the next thing you must
  do. Flock badges are now ONE per `FLOCK_CLUSTER_M` cluster at EVERY zoom: `FLOCK_SRC` carries a
  badge feature per cluster (`FLOCK_COUNT_PROP`, drawn with an "xN" label past one) plus one cone
  feature per head at the same point, so the beams fan from a single badge. `crossLabelPoint`
  returns null below `NAV_XLABEL_MIN_CLEAR_M`: no room on either side, no bubble.
- **CHOOSER INSET IS MEASURED (2026-09-17):** `cameraBottomInset` for the route chooser comes from
  the panel's own `onGloballyPositioned` top edge in WINDOW coordinates (`dirPanelTopRaw`, sampled
  through a 140 ms `snapshotFlow` debounce so the panel's height animation does not re-fit every
  frame) against `LocalView.current.height`, not a fraction of `screenHeightDp`: the fraction
  understated the panel by ~130 px (the bars the map draws behind) and the trip's START framed
  behind it. The fractions remain as the first-frame fallback.
- **A BRAND'S FORECOURT SAYS SO (2026-09-18):** fuel is exempt from the tenant minzoom, so a
  supermarket and its pumps both draw a few tens of meters apart - and the forecourt is usually
  published under the BARE brand name, which made a tap on "the store" a coin toss (user report; the
  sheet read "Gas station"). `build-places-region.sh` appends " Fuel" / " Charging" / " Market" to a
  fuel, charging or convenience row whose name EXACTLY equals its anchor's; a row that already names
  itself is untouched. It is the ONLY place the bake rewrites a name - keep it that way, and note it
  only reaches the map on the next places re-bake.
- **TENANTS AND STOREFRONTS IN THE BAKE (2026-09-17):** `tools/build-places-region.sh` now flags a
  row as `tenant` when it is a department of a nearby anchor (address, brand or name-head match, the
  existing hash joins), when its category or name is a KIOSK (`iskiosk`: Redbox, Coinstar, ecoATM,
  Western Union, a key machine, an ATM), or when it is the anchor brand's fuel station or its
  convenience shop within ~275 m. A tenant keeps its -2 prominence AND is baked at minzoom 17
  (except `grp = 'fuel'`, which stays visible for driving); VelaMapView's `unlessTenant` keeps
  tenants as DOTS until z18.5 so the store owns the block. `atp_snap` moves a chain row onto the
  AllThePlaces locator coordinate when they disagree by 30-120 m, keyed on `snapkey` (the whole
  normalized name, trailing store number dropped) - NOT the loose two-word dedupe key, which
  dragged a campus onto its own outreach office and swapped a Safeway with its pharmacy.
- **ROUTE BAR + CROSS LABELS (2026-09-17):** `RouteBarStrip` lays badges in their OWN lane right of
  the track (`TRACK_COL` / `badgeCx`), so a 26 dp badge no longer covers the congestion band under
  it, and the remaining-distance label is gone (the bottom bar has it; "768.8 mi" overflowed the
  strip). `NavController.refreshRouteBar` adds `speedCameras` as CAMERA marks (flock marks already
  pass `CameraFacing.onRoute`; fixed speed cams carry no direction, so distance only).
  `crossLabelPoint` now tries `NAV_XLABEL_OFFSETS` on BOTH sides and keeps the
  first with `NAV_XLABEL_CLEAR_M` clearance, and the label pass only marks its quantum done once it
  placed something (`emptyPassTicks`), so labels no longer wait 400 m when tiles land late.
  **The clearance is measured to the bubble's ANCHOR, which is the TIP OF ITS TAIL (2026-09-19).**
  The chip body sits above that point and is much wider than it, so the gap on screen is always
  smaller than the constant, and more so with the camera tilted: at 30 m measured, chips still drew
  over the blue line on a real drive. `NAV_XLABEL_CLEAR_M` is 44 m and the floor
  `NAV_XLABEL_MIN_CLEAR_M` 26 m, with a finer ladder (1x/1.4x/1.8x/2.4x/3x of 35 m) because the
  clearance a rung buys depends on the angle the street crosses at, and the coarse one overshot a
  perpendicular street by a block to win a few meters. This is a WALK-BACK, not a reset: the
  callouts were deliberately moved close to the route in 2026-09-16, when line-center placement was
  putting them a block away, and the answer to overlap is a few more meters rather than the old
  behavior.
- **ROUTING OFFER + REGION SIZES (2026-09-17):** `MapViewModel.maybeOfferRouting()` (camera idle, once
  per session until answered; pref `routing_offer_done`) offers the smallest obf region covering
  Home or the fix, after onboarding, on the bare map; `answerRoutingOffer`. `archivesFor(region, list)`
  picks the places/basemap archives a region download pulls: the SAME-ID archive first (the center
  rule alone pulled parent and neighbor archives), and `regionExtrasMb` feeds
  `MapUiState.regionExtrasMb` into `regionInstalledMb(..., extraMb)` so every size shown is the
  real total. Route bubbles (`routeBubblesFor`) show in BOTH choosers and keep `minGap` (25% of
  the routes' diagonal) apart; the Google-style chooser's "Compare routes" sets MapScreen
  `classicRoutes` (BACK clears it). VelaMapView no longer nulls `lastCameraTarget` on inset growth
  while a route is up: the next frame used to fly to the selected place and cancel the route fit.
- **QUERY INTENTS (discussion #365, 2026-09-13): `core/search/QueryIntents.parse(text, lang)`.**
  The phrase list shown in Settings > Search is `core/search/VoiceCommandExamples` (hand-written per
  language, since gluing a table verb to a table place word breaks grammar); `QueryIntentExamplesTest`
  parses every line and checks its kind, so add a language there when you add one here.
  Voice search was dictation into the search box, so "take me home" searched for a place called
  that. Every submitted query (typed `search()` and the two voice paths through
  `applyVoiceQuery`) first goes through the parser: Home / Work (the saved shortcuts, with a
  "set it first" status when unset), NavigateTo(q) (search, then the top hit straight into the
  chooser via the one-shot `openDirectionsOnResult`), Route(from, to) (`routeBetween`: destination
  first, then the origin as a custom From), Search(q) (the filler stripped: "where is the nearest
  X near me" -> X) and Eta (spoken + flashed remaining time while navigating). Per-language word
  tables for ALL 15 app languages (zh/ja are `noSpaces`, ja has `goSuffix` verb-after-place and `fromIsSuffix` for AからBまで; ru/uk/pl keep only the unambiguous connector for a bare A-to-B via `toBare`; hu/he have no route shape, their prepositions attach to the word), English tried as the fallback in every language; a coverage test pins the set against the app languages; a bare verb
  ("go", "take me") only counts before home/work or an explicit "from A to B", so "go karts" stays
  a search; a bare "X to Y" is a route only when X is not a question word or a verb ("where to
  eat" stays a search). `routeBetween` also runs the WHOLE phrase as a search first and shows plain
  results when a listing's name contains it ("Road to Hana", "Flights to Denver" are places, not
  trips). Null = plain search, so the parser can never make a query worse. Pinned by
  `QueryIntentTest` with the sentences from the discussion. Adding a language = one `Words` table.
  **FUZZY PASS (2026-09-13):** dictation mishears ("navigat to", "nearst", "ofice", "emmene moi a
  la gare"), so after the exact passes miss, `parseWith(fuzzy = true)` runs per language (spaced
  languages only). The tolerance is WORD-LEVEL and only over the VOCABULARY, never the free text:
  `wordOk` folds accents (an accent-only difference matches at any length) and allows one edit
  (optimal string alignment) from five letters in `eq`, four in `pre`/`suf`, two edits from eight;
  a single-word phrase never fuzzes ("fine dining" is not "find dining", "hone" is not "home") and
  a multi-word phrase must match word for word, so "home depot" cannot collapse to "home". Home /
  Work take the fuzzy compare in BOTH passes (the exact pass owns "take me to my ofice" once its
  verb matched), a bare verb may sit behind up to four filler words like the full verbs
  (`preSkippingFillers`: "can you please take me home"), the bare A-to-B rule rejects a misheard
  verb as A (`looksLikeVerb`), and `normalize` strips apostrophes and joins spelled acronyms
  ("E.T.A.", "e t a" -> "eta"). Pinned by the `dictation slips still land` and `fuzziness never
  rewrites a short word or the destination` tests.
- **Local suggestions (issue #180, 2026-07-19):** `onQueryChange` sets `localSuggestions` from
  `localMatches()` SYNCHRONOUSLY (recents searches + viewed places + list/saved places, substring
  match; min 2 chars) BEFORE the debounced network fetch. **+ CONTACTS (issue #243, 2026-08-08,
  device-verified):** opt-in Settings > Search toggle (`ContactsSearch` holder, pref
  `contacts_search`, OFF by default; READ_CONTACTS asked at the point of use, flipping the toggle
  on, from SearchSettings). `app/data/ContactAddresses` loads ALL address-bearing contacts into
  memory ONCE (VM init + toggle-on, off-main) because localMatches runs synchronously per
  keystroke and a ContactsProvider binder query there would jank; the match is against that cache.
  A CONTACT suggestion carries the ADDRESS as its `query` plus the row's `badge` (the address
  book's own type label via `StructuredPostal.getTypeLabel`, already platform-localized) and
  `photoUri` (`PHOTO_THUMBNAIL_URI`, a content: URI that only resolves while the permission is
  held; coil loads it over the person glyph so a missing photo just shows the glyph). **Picking
  one goes through `openContactAddress(name, address)` (2026-09-06), NOT searchRecent:** geocode
  the address (Google search when online, else `OfflineAddressStore.geocode`, the other as a
  fallback), then `selectContactPlace` opens the hit renamed to the CONTACT with the address as
  sublabel - the same branches as `selectSaved` (assign-as-Home/Work, stop + endpoint pickers,
  a stop on a live drive) minus its search-by-name enrichment. That is what makes the sheet,
  Save, Recents (`rememberRecentPlace`) and the directions To/From fields say whose place it is;
  before, the pick searched the bare address and every trace of the person was lost. Nothing
  geocodes → falls back to `searchRecent(address)` so the normal no-results/offline UI shows. The
  row renders `ContactAvatar` + `SuggestionBadge` through `SuggestionRow`'s `leading`/`badge`
  slots. `SearchCarScreen` shows up to two contact rows above the car results (`openContact`
  geocodes then pushes RoutePreviewCarScreen under the name). The contact list itself never
  leaves the phone; PRIVACY.md has the user-facing wording. Local rows are instant and the only
  thing that shows offline. `LocalSuggestion` (kind RECENT_QUERY / RECENT_PLACE / SAVED_PLACE) renders above
  the network rows in `SearchEntryContent`. Dedup of network vs local is by `nameLocKey` (name +
  coarse location), NOT feature id - SavedPlace-backed locals carry no id, so an id-only compare
  double-shows them. Clear `localSuggestions` everywhere `suggestions` clears. **Press-hold / ⋮
  menu on a suggestion (issue #180, 2026-07-19):** every suggestion row (local AND network) now
  has a trailing ⋮ overflow plus a long-press (`SuggestionRow` gained `onLongClick` + a `trailing`
  slot; the row uses `combinedClickable`), both opening a `VelaMenu` via `SuggestionOverflow` -
  "Save to list" for any place-backed row (reuses PlaceSheet's `SaveToListSheet`, made `internal`)
  and "Remove from history" for removable rows. The ⋮ is the D-pad key path for the long-press
  (touch-only), so it stays D-pad-legal. The ⋮ REPLACES the bare X on local rows; the empty-query
  recents list keeps its own X. Menu open-state is `remember(suggestion)`-keyed so a keystroke
  that rebuilds the list closes a stranded menu.

- **UI FONT: user-supplied only, and UI TEXT ONLY (issue #252, 2026-08-16).** `ui/AppFont` +
  Settings > Appearance > Font: System font, or a font file the user picks from their own storage
  (SAF, copied to `filesDir/fonts/ui.ttf`, applied via `velaTypography(family)` in VelaTheme).
  **Vela ships no branded face and cannot** - the one people ask for is Google Sans, which is
  proprietary, absent from Google Fonts, and not redistributable in a GPLv3 app; Android has no API
  to enumerate a user's installed fonts; and Downloadable Fonts is served by Play Services, which
  our users do not have. Handing us their own file is the one legitimate path, and Vela never
  fetches, ships or uploads it. Three details that matter: the file is VALIDATED BY LOADING IT
  before adoption (a silently-adopted corrupt font renders every screen in the fallback face, and a
  silently-rejected one reads as a dead button; NB Compose's `Font(File)` does NOT throw on bad
  data on API 26+, the platform builder returns null and Compose only fails at first draw, so a
  PDF picked by mistake was adopted, persisted and crash-looped every launch until the 2026-09-12
  review: `AppFont.load` now asks `Typeface.Builder(f).build()` first, and the copy runs on IO), the picker filters `*/*` on purpose (font files
  arrive as font/ttf, application/x-font-ttf and octet-stream depending on the file manager - a
  filter that hides the user's own font is worse than a chooser showing too much), and init falls
  back to the system face if the stored copy no longer loads. **MAP LABELS ARE NOT AFFECTED** and
  cannot be by this mechanism: they are drawn by MapLibre from pre-generated SDF glyph atlases (see
  MapFonts), so an arbitrary TTF would have to be rasterized into glyph-range PBFs on-device - a
  separate project, not a config. If bundled faces are ever added, they slot in beside these two
  rows; nothing else changes.
- **Interface size (2026-07-11):** `UiScale` holder (pref `ui_scale`, chips 90/100/115/130% in
  Settings -> Appearance) applied as a LocalDensity override around VelaRoot's whole tree - all
  Compose UI scales, the map AndroidView keeps native size (built for car/vertical screens).
- **Map colors are Google-verbatim (2026-07-11):** greens/water/land sampled from
  maps.google.com at the arboretum. LIGHT: park/grass `#d3f8e2`, wood `#c9f2da`, water
  `#90daee`, land `#f2f1ee` - opaque (the old 0.3-0.7 over land washed them olive). DARK: park
  green was `#1c3326`, DARKER than the `#242f3e` navy land so it vanished (user 2026-07-11) -
  now `#2c4a34`/`#274330`, opaque, clearly readable. Re-sample the same way if either drifts.
  The **Map style Settings row was removed** (only one style ships; MapStyle/setStyle plumbing
  kept for a future re-add). Nav card trip time is a `FitText` (shrinks to fit, never wraps/
  ellipsizes) so the 54dp buttons + Interface-size scale can't clip the arrival time.
- **Issue sweep 2026-09-10, the traps behind each:** (#343) `ListsSheet` must be composed
  OUTSIDE the `fabChromeOk` block: that block is gone while the search overlay is up, and the
  search bar's Your-lists button is exactly there, so the flag flipped and nothing rendered.
  (#351) `SearchBar`'s Card is white + 6dp shadow + hairline border in LIGHT only (dark keeps
  the flat surfaceContainerLow: a shadow smears on a dark map). (#357) The 12/24-hour clock is a
  device SETTING, not the locale: `ui/Clock24` reads `DateFormat.is24HourFormat` at startup and
  in `MainActivity.onResume`, mirrors it into `:core` `data/ClockFormat` for Transitous' board
  times, and `formatArrivalClock` / `formatDateTime` honor it; never use
  `ofLocalizedTime(SHORT)` alone for a clock the user sees. (#352) The nav Overview is a LIVE
  refit loop in VelaMapView (`overviewLive`, arrow-to-destination every 4 s, keyed on the
  polyline so a reroute refits), ended by pan/pinch/Re-center; the tick is the user's request,
  the polyline key is not. The fit leaves 104 dp on the right for the FAB column and 40 dp on the left for the route bar (2026-09-16: a destination to the south-east sat under the buttons). (#330) `UpdateCard` shows `UpdateInfo.notes` through
  `plainReleaseNotes` (markdown stripped, CI's versionName/versionCode lines dropped, 24 lines).
  (#359) `PlaceSheet` share menu: Copy link = `placeUrl()` = the cid deep link, same as Open on
  web. Reviews-language on #359 was NOT reproducible: the device sends `hl=zh-TW`, Google serves
  Chinese, and the scraper completes on a zh-TW page in a 1200x3000 viewport in 19 s (browser
  proxy); the reporter is most likely on a build older than #339. The P4a's own scrape timed out
  in BOTH languages that day (result null at TOTAL_TIMEOUT_MS) - environmental, watch it.
- **Country and state borders are DRAWN; county and city limits are not (discussion #353,
  2026-09-09).** Liberty's `boundary_2` (admin 2), `boundary_3` (admin 3-6) and
  `boundary_disputed` were hidden with the footpath/rail-hatching clutter in July because the
  admin 5-6 county/city lines read as stray dashes all over a suburb. Google draws countries
  (thin solid gray) and states/provinces (lighter, dashed, from ~z4) and not the rest, so
  `applyMapTheme` now narrows `boundary_3`'s filter to admin 3-4 (minzoom 4, dashed) and themes
  all three: the style's own dark gray vanished on the dark map. Never put the boundary ids back
  in the hide list; if a region shows dashed junk, check its admin levels before touching the filter.
- **Drag-to-reorder lists keep ONE modifier chain (2026-09-16).** The stops editor's rows wrapped
  the dragged row in `.then(if (dragging) Modifier.zIndex().graphicsLayer {} else Modifier)`, and
  changing the chain when the drag started killed the handle's `detectDragGestures` after its first
  move, with no end or cancel callback: the row lifted and froze, so reordering never worked (the
  planning editor and the mid-drive one). Every row now carries the same zIndex / graphicsLayer /
  background and only the values change. Any new drag list: vary values, never the chain.
- **ONE quick-category list (2026-09-16, `ui/QuickCategories`).** The map's chip row, the route
  chooser's "Search along route" and the in-nav search all render `QuickCategories.all()`
  (Restaurants, Coffee, Gas, Groceries, Things to do, Hotels, Bars, EV charging, Parking,
  Pharmacy, ATMs, Parks, Hospitals, Banks, Post offices, Campgrounds, in that order; the last
  seven were added for #554 on the same day); they had drifted to three different sets. Add or
  reorder a chip there, never inline. Every query must be one `OfflinePoiStore` expands, or the
  chip is dead offline: `categoryKeywords` matches the whole lowercased query exactly, then the
  query minus a trailing "s" (Hotels/ATMs/Parks were dead offline before that fallback), and the
  values are OSM tag values as the packs store them (`poipack_build.py category()`: first of
  amenity/shop/tourism/leisure, "_" -> " ", matched with LIKE, so "camp site" not "camp_site").
  `OfflineCategoryKeywordsTest` lists every chip query; add yours there. Bars is dropped from
  `all()` while `HideAdult` is on (the filter would empty it), and reading `HideAdult.on` there
  keeps the rows reactive.
- **Google-style route picker - the DEFAULT since 2026-09-18 (`ui/RoutePicker`, pref
  `route_picker_google`, Settings > Navigation).** It shipped as an experiment
  (`ui/Experiments`, `exp_google_chooser`, Settings > Diagnostics) and graduated once it had been
  driven; `RoutePicker.init` reads the legacy key when the new one is absent, so an experimenter's
  explicit yes or no still speaks for them. Off = Vela's classic `DirectionsPanel`.
  `GoogleStyleDirectionsPanel` (ui/place/GoogleChooser.kt)
  replaces DirectionsPanel for non-transit modes; `routeBubblesFor` (MapScreen) picks each route's
  bubble point as the sample farthest from the other routes, and VelaMapView draws them on
  `ROUTE_BUBBLE_LAYER` (tappable like the alternate lines, `ALT_INDEX_PROP`). Edit stops opens
  `TripEditorSheet` (StopsEditor.kt): the whole trip as one list, `MapViewModel.tripPointsForEditor`
  / `applyTrip` map it back onto directionsOrigin / selected / waypoints / reversed (a null point
  is "your location").
  **Remote switch:** `Calibration.classicRoutePicker` (boolean, default false, parsed from
  `calibration.json` `classicRoutePicker`) is pushed into `RoutePicker.setRemoteDefault` at VM
  init and after each refresh; it only applies to people who never touched the toggle (the pref
  key's presence is the "explicit" marker), and it exists to put the fleet back on the classic
  panel without an app release if the new default goes wrong. The bundle does not carry the field.
- **Stop dividers in the step list (2026-09-16, #519):** `StepsSheet(legStarts = [(maneuver index
  where leg k>0 starts, stop name)])` draws `StopDividerRow` before that step; MapScreen computes
  the indices from `activeRoute.legs` (cumulative maneuver counts) and names them from
  `navRemainingStopLabels()` while driving or `directionsWaypoints` in the chooser. Via boundaries
  carry no DEPART/ARRIVE maneuvers (routeVia and chainOnDevice both drop them), so without this
  row a stop was invisible in the list.
- **Free-drive follow engages ONCE per session (2026-09-16).** The cold-engage branch flies to the
  fix at z15.5 when the camera sits below z14. A pinch releases the camera (`browseCam` -> NaN) and
  the next frame re-seeds, so pinching out past z14 while still following flew you straight back in,
  again and again, until you panned (which drops follow). `browseEngaged` gates the flight to the
  first engagement and clears when follow ends. User report 2026-09-16 ("keeps trying to zoom the
  camera in there until I pan away").
- **Drive-nav map fixes (2026-09-16, from a real drive).** (1) The open places layer follows the
  OSM tiers' drive rule: fuel icons only, no dots (`placesNavFuelOnly`, folded into
  `applyOpenPlacesHidden`, re-applied when the places layers are rebuilt mid-drive); every business
  along the route drew and its labels sat on the road. (2) The visible traffic-controls layer is
  anchored above `ROUTE_CUT_LAYER` (the 400 m piece around the arrow), not the ahead line, which
  had left the lights nearest the driver under the blue line. (3) Picture-in-picture entry AND exit
  call `vm.recenterNav()` (the tick alone only cleared pinch overrides, so the exit "re-center"
  never re-attached; and the home swipe reads as a map pan before PiP starts). (4) The free-drive
  heading-up camera eases its bearing with the nav camera's adaptive constants and its speed-scaled
  look-ahead with `FREE_LOOKAHEAD_TAU_S` (2.5 s): both used the 0.22 s position constant, so each
  1 Hz fix's course/speed noise became a quick move and a pause.
- **The Both-mode twin pass waits for a still map (2026-09-16).** Measured in Manhattan on the 4a
  (scrub at z17, warm): Both 28 fps with ~10 main-thread stalls per run up to 190 ms (the twin
  pass: full-screen rendered query + a filter change on every places layer), Vela data 34 fps and
  Google 37 fps with none. The pass now reschedules itself until the camera has been still for
  `TWIN_PASS_STILL_MS` (700 ms, `lastCameraMoveMs` from the move listener): stalls 2-3 per run
  (~140 ms total). The remaining fps gap is render cost (two places layers plus filter changes).
  Dense cities are ~34 fps even in Vela data mode; Davis is ~60. Benchmark: scratchpad-style
  scrub via `cmd input motionevent` + the `VelaFps` frame probe, run each mode twice alternating
  (single runs swing 22-31 fps; the first runs after an install are slow).
- **In BOTH mode Google WINS a twin outright (2026-09-16, user's call).** Any open feature whose
  name agrees with a Google place DRAWN on screen (a rendered-features query on `vela-ambient` +
  its dot layer, not the whole pool: a Google copy that lost its collision used to hide the open
  twin too and the business vanished, 2026-09-17) within `DEDUPE_NAME_M` (80 m), or with the SAME
  normalized name within `DEDUPE_SAME_NAME_M` (150 m, Overture parks chains at parcel centroids
  in the lot), has its id put in
  `openDisplacedIds` by the debounced `hideOpenTwins` pass, and `applyOpenPlacesHidden` filters it
  out of the icon and dot layers (same mechanism as the closed-listing set, unioned with it);
  Google's pin is drawn instead. Two reasons
  Google's copy is better wherever it exists: its coordinate is the storefront, where Overture
  stacks a building's tenants on ONE parcel point and the bake spreads the stack onto an invented
  8-20 m ring; and its ranking comes from review counts rather than a category prior. The open
  layer then holds exactly what Google did not return, which is the point of Both. Offline nothing
  is hidden (no ambient places to match against). The same pass purges closures: Google's nearby
  answer keeps its permanently closed places in `ambientClosed` (never painted), and an open icon
  matching one within 80 m, with no OPEN Google listing of that name within 150 m (a move, not a
  closure), goes to `onOpenPlaceClosed` -> `hideClosedOpenPlace`, the persisted closed set. The first cut kept the open icon when the two
  agreed within 25 m; the user asked for Google to win either way.
- **The sheet's fold FADE was the expand/minimize stutter (2026-09-16, measured).** `SheetFold`
  wrapped the folding content in a `graphicsLayer { alpha = fraction() }`, and an alpha below 1
  renders the children into an OFFSCREEN buffer and blends it - 20 ms of GPU per frame on a 4a,
  half the budget. Framestats over four detent toggles: offscreen alpha total p50 32.1 ms / gpu
  20.3; the same build with the fade forced opaque 16.7 / 12.3; with
  `CompositingStrategy.ModulateAlpha` (alpha applied per draw op, no buffer) 32.0 / 13.6. So
  ModulateAlpha is a third off the GPU and ships, but the tail (gpu p90 23.7) still holds the
  animation near 30 fps: dropping the cross-fade entirely is the only thing that reached 60, and
  that is a design call. MEASURE+LAYOUT IS NOT THE PROBLEM (p50 0.1 ms) - the animated-height
  layout modifier was the wrong suspect, do not "fix" it. The map is its own SurfaceView, so none
  of this is the map compositing. Read the phases with `dumpsys gfxinfo app.vela framestats`
  (DrawStart - PerformTraversalsStart = measure+layout, FrameCompleted - IssueDrawCommandsStart =
  gpu); plain gfxinfo percentiles cannot tell CPU from GPU.
- **UNIT-LEVEL SNAP for stacked tenants (2026-09-16, `build-places-region.sh`).** A stacked row has
  no coordinate of its own (Overture puts a building's tenants on one parcel point, usually the
  lot's address out front), so the ring spread invents one. Overture's ADDRESSES theme carries a
  point per unit, so a tenant whose own address names a unit ("STE B", "APT 112") is snapped to
  that point first and only what is still stacked gets the ring. Matched on house NUMBER + UNIT
  within ~200 m, street name IGNORED on purpose: a number plus a unit is unique that close, and
  the two themes abbreviate streets differently ("Blvd" vs "Boulevard"). Davis: 444 stacked rows,
  218 name a unit, 97 snapped. `$ADDR_SQL` is empty on the local-parquet dev path. Only STACKED
  rows are snapped - an unstacked place already has a real coordinate (measured: Overture and
  AllThePlaces agree to a median 7.4 m over 124 Davis chains, so neither source is systematically
  better and snapping everything would move good points for nothing).
- **The high-zoom icon budget is `frank` (2026-09-16).** A ~100 m cell rank baked alongside
  rank/crank/xrank. `rank`'s 400 m cell is about the whole screen at z17.5, so a cap on it never
  opens up as you zoom; `frank` is a per-block budget: `openIconCapNear` (8) get an icon at z17.5,
  `openIconCapClose` (16) at z18.5, everything from z19.5, both calibration dials. A place below
  the cut still draws as a DOT (the dots tier is unfiltered from z17), which is the user's ask:
  "minimizing to little circle dots is an alternative if we are too crowded ... we can see more
  later when we zoom into an area", not places disappearing.
- **Street-zoom place budget is remote (2026-09-17).** The z16/z17 steps of the open places layer read
  `openRankZ16` (3) / `openPromZ16` (5.5) and `openRankZ17` (8) / `openPromZ17` (5.0) from calibration
  `tuning` (were hard 5 / 5.0 and 12 / 4.5). Measured against Google Maps on the same Midtown blocks
  (screen recording, distinct frames): Google ~50 fps with ~15 icons, Vela ~41 with ~25; with the
  ambient fix in, hiding the open places layer took one-step-in from 45 to 58 fps, and these caps
  took it to 49. Davis downtown at the same zoom still shows every block's main places. NB a screen
  recording costs the 4a enough GPU that a map rendering right at 16 ms halves to 30 fps while
  Google's lighter frames do not; the in-app `VelaFps` probe is the fair per-frame number.
- **The Google places (ambient) source is maxzoom 18, not 12 (2026-09-16).** Bisected in Midtown on the
  4a with a probe-only adb hook that hides layers by type or id prefix: fills, lines, circles and 3D
  changed nothing; hiding ALL symbol layers took one-zoom-in from 22 to 60 fps, and `vela-ambient` alone
  from 22 to 51. The cause was `GeoJsonOptions().withMaxZoom(12)` on AMBIENT_SRC: past a GeoJSON
  source's maxzoom every visible overscaled tile lays out all of its parent tile's features, so a few
  hundred labeled places were placed many times per frame. maxzoom 18: 43 fps one step in, 51 at the
  browse zoom (was 30). The other point sources are sparse and keep 12. ALSO LEARNED: the 4a hits
  thermal status 1 after ~5 minutes of scrubbing and every number drops to single digits, which is what
  the earlier "150 ms per frame at close zoom in every mode" was; benchmark with cool-downs
  (`dumpsys thermalservice`, wait for status 0) and alternate A/B runs.
- **Dense-city pin budget, second pass (2026-09-16, measured in Midtown on the 4a).** Three rules in
  the places layer expressions: (1) `blockBudget` replaces the frank-only `topOr` at z17.5 / 18.5 /
  19.5 and FALLS BACK to the 400 m `rank` with a 4x cut when a tile has no `frank` (archives baked
  before it passed every place, so pre-frank New York drew every tenant from z17.5); z19.5 is capped
  at `openIconCapMax` (40) per block instead of unlimited. (2) `unlessCrowdedGeneric`: default- and
  health-group places (office tenants, small practices) need to be in the top `openGenericBlockTop`
  (3) of their block or reach `openGenericMinProminence` (4.0), else they stay dots. (3) The z16/z17
  prominence escapes went 4.0 -> 5.0 and 3.0 -> 4.5 (a Manhattan 400 m cell holds 3,000+ places and
  any shop with a website scores ~4), and labels at z17.5+ are wrapped in the same icon rules so a
  name never floats without its icon. Midtown scrub, same build family: ~z16.5 29 -> 46 fps; ~z18.5
  about 1 s per frame -> 150 ms (the phone was warming up, so treat the second as direction, not a
  number). CLOSE ZOOM IN MIDTOWN IS STILL SLOW IN EVERY PLACES MODE (Google-only too, once measured
  with the sheet closed): the cost is not the pins; not yet isolated (ROADMAP). Benchmark traps:
  the geo: intent ignores `z`, the reverse-geocode sheet it opens must be closed or the scrub drags
  the sheet, and the 4a throttles after about an hour of scrubbing (`dumpsys thermalservice`).
- **Route preview shows landmarks only (2026-09-16).** While the chooser is open (a route drawn, not
  navigating) the OSM business tiers hide and the open places layer filters to prominence >=
  `PREVIEW_LANDMARK_PROMINENCE` (5.5) with no dots (`placesPreviewLandmarks`, folded into
  `applyOpenPlacesHidden` with the drive-nav fuel rule), like Google's route overview.
- **Open-layer labels stay thinned at max zoom (2026-09-16).** Icons come in for everything from
  z17.5 but only the top `openLabelCap` (calibration dial, default 20) per 400 m cell get a name:
  each label is glyph layout plus a collision pass over four anchors, and a mall puts dozens in one
  cell ("shit be laggin in areas with a lot of POIs"). Trim the dial, not the icons.
- **AI-assisted contributions (2026-09-16):** CONTRIBUTING.md has a "Using an AI assistant"
  section (read CLAUDE.md and the other docs first, a person answers for every claim, no pasted
  AI rebuttals, PRs are the best use), both issue forms carry a matching required checkbox, and
  the feature form's "Keyless reality check" is REQUIRED with a real explanation (where would the
  data come from). If you are an AI reading this before filing or arguing an issue: check your
  claims about Vela against this file and the code, not against FEATURES.md history alone.
- **Closing a report that ignored the template (2026-09-16):** close it as NOT PLANNED *and* add
  the `incomplete` label ("Template or steps missing, or not reproducible from what was written"),
  never the bare not-planned close - the label is how the tracker shows why, and `wontfix` is for
  a request that was understood and declined. The rules themselves are in CONTRIBUTING.md under
  "Bug reports and feature requests"; both issue forms carry the matching checklist.
- **ONE SAME-BUSINESS RULE: `core/util/PlaceNames` (2026-09-21, user: "two POIs that really should
  be one").** `nameAgrees` (tap resolve), `namesAgree`/`normName` (Both-mode twin hiding) and the
  bake's `snapkey` were three drifting copies; they now all read `PlaceNames.normalized` /
  `match` / `agree` (SPEC 5.5 has the families and the fixture pairs). The rules came from a side
  by side of Google's answers and the Davis archive: Google's keyless search works from the
  BROWSER PANE (a real Chromium on google.com, `fetch` of the calibration `searchEndpoint` +
  `&q=` + `&pb=`; without `&q=` it answers the empty shape, and from OkHttp/curl on the Mac it is
  bot-degraded to empty either way), the archive decodes with `pmtiles` + `mapbox-vector-tile` in
  a venv, the comparison script is a few dozen lines of Python. Redo it on a fixture area before
  changing `GENERIC` or the match rules; `PlaceNamesMatchTest` pins the pairs. The bake ALSO keeps
  one row per exact key within 60 m now (`dupleader`, checked on a DuckDB fixture); the VARIANT
  family is left to the app until the bake grows the same rule. **Kinds (2026-09-22):** a second
  study area showed a fuel station's lot carrying the archive's "Chevron", a second fuel row named
  after the shop inside it, and Google's listing under the operator's name, plus a pizza place
  sharing the station's name. So `PlaceNames.sameBusiness` takes the icon groups (an OVERLAP across
  two known, different kinds is refused; EXACT/VARIANT still cross kinds), `sameFuelLot` calls two
  fuel kinds within 45 m one station, both twin passes and the tap pool use them, and the bake keys
  fuel rows by house number too. The Both-mode twin pass reads the ambient feature's `icon`
  (`vela-poi-<group>`) and `hn`, and the open feature's `group` and `addr`. The lot rule is 30 m
  and two different known house numbers refuse it (two stations facing each other across a road).
  **Dense cities (2026-09-22, Midtown + downtown Houston through the REAL rule, not the Python
  approximation):** the Kotlin probe recipe is kept at the scratchpad's `ZzTmpCorrProbe.kt.txt`
  (a throwaway core test that reads `corr.txt` = `tag|google.json|archive.json|s,w,n,e` lines
  from the `-DvelaObf` dir and prints exact/variant/overlap/none with examples); state archives
  decode over HTTP RANGE reads (`decode_box.py`, a pmtiles `Reader` with a `get_bytes` that sends
  a Range header, so a 700 MB archive costs a few MB). New families and their guards are in SPEC
  5.5; the one that matters most in a city is `localGeneric`: neighborhood and landmark words
  ("Bryant Park", "Flatiron", "Heights") sit in a dozen names on one screen and were matching
  each other, so both app sites hand the pool's shared words in as generic. The bake folds the
  VARIANT family now too (core key = snap key minus `tools/place-generic-words.txt`, which a unit
  test keeps equal to `PlaceNames.GENERIC`; regenerate the file from the Kotlin set when the list
  changes, comments excluded). Measured within the archives themselves (same-business twins within
  60 m through the real rule, pool words applied): downtown Houston 42 exact + 56 variant + 122
  overlap of 10,795 rows; the corridor 19 + 12 + 41 of 3,123. The exact and variant ones are what
  the bake drops; the overlap ones are the app's to merge on screen. **Other languages
  (2026-09-22, user: "make the rules work for non-English"):** one generic table per app language
  unioned into `GENERIC` (the phone's language is irrelevant, the region's is unknown, so all of
  them apply), legal forms and street abbreviations per language, the accent folds NFKD skips,
  and `cjkMatch` for Han/kana/Hangul/Thai (string containment after stripping suffixes like 店 /
  薬局 / 銀行 / 지점). `PlaceNamesI18nTest` pins a pair per language. The four English study
  areas moved by one or two matches either way under the union. Regenerate
  `tools/place-generic-words.txt` from ALL the `GENERIC_xx` tables (the generator has to accept
  both `private val X = setOf(` and the typed form; comment lines excluded). **Berlin + Tokyo
  (2026-09-22):** Berlin links 75% through the rule after a four-letter-brand allowance (Lidl,
  Aldi, Rewe: a four-letter word carries a nested match when it leads both names and the longer
  adds one identifying word, or when both names reduce to it and the kinds agree; a bare "Hair"
  or "Finn" still claims nothing) and a glued-name rule ("greengymberlin" reads as its words).
  Tokyo is the cross-script case: 38% under `hl=en`, 65% under `hl=ja`, because Google names
  places in English for an English phone and the archive is three-quarters Japanese with NO
  Overture alternate names. So the tap resolve runs a second search in the label's own script
  language when nothing agreed (`NameScript.scriptLanguage` + `MapDataSource.search(lang)` +
  `MapViewModel.crossScriptCandidates`, then the pick's copy in the app language by feature id).
  NOT device-verified in Japan; the Both-mode twin pass still compares English ambient names with
  the local archive and is the open half (ROADMAP).
- **A CLOSED LISTING NEVER BEATS A LIVE ONE, AND ONLY HIDES A PIN WHEN NO LIVE TWIN EXISTS
  (2026-09-21).** Google keeps a moved business's old, permanently closed profile beside the live
  one for months. The tap pool now drops closed listings whenever a live candidate exists, and
  `hideClosedOpenPlace` fires only when no live listing of that name sits within 150 m (the
  ambient purge's rule): a closure is a correction, a move is not. Before this a slow session that
  surfaced the old profile first hid an open store for good (user 2026-09-19, a parts chain).
  Nothing offline is ever cached as "no Google listing": the offline tap branch reads
  `openPlaceCache` and writes nothing; the closed set was the only persistent negative.
- **A TAP THAT DOES NOT LINK LOGS WHY (`VelaTap`, 2026-09-18).** The open-place resolve prints the
  tapped label, the tile's kind, whether it was seeded, how many results Google returned, how many
  survived the transit/junction filter, what was picked and at what distance, and the distance cap.
  Three different failures (a throttled session returning nothing, a tile row whose name or point is
  off so nothing agrees within `NO_NAME_MATCH_M`, and a pick dropped by the 1.5 km cap) are
  indistinguishable on screen, and none of them used to leave a trace. NO coordinates in the line.
- **MEASURE MAP FRAMES WITH THE MAP'S OWN CALLBACK, NOT gfxinfo (2026-09-18):** the map draws on
  its own GL thread in a SurfaceView, so `dumpsys gfxinfo` counts the Compose chrome and reports
  "Total frames rendered: 0" for a pan that visibly stutters (checked on the 4a). `VelaMapView`
  registers MapLibre's `addOnDidFinishRenderingFrameListener` and logs fps once a second under
  `VelaFps` when `debug.vela.fps` is set (read at MAP CREATION, so restart the app after setprop;
  same escape-hatch style as `debug.vela.lowram`). `scripts/map-fps.sh [serial] [label]` does the
  whole loop: setprop, restart, wait for a warm map, a fixed pan pattern, then min/p10/median/max.
  Measured that way the 4a holds 40-55 fps panning a suburb at browse zoom.
- **A DENSE GeoJSON SOURCE NEEDS A HIGH maxzoom (2026-09-18, the ambient lesson generalized):**
  past a source's maxzoom every visible overscaled tile lays out ALL of its parent tile's features.
  `AMBIENT_SRC` cost 22 -> 51 fps when it went 12 -> 18 (2026-09-16); the same shape was still in
  `CONTROLS_SRC` (up to `CONTROLS_ROUTE_CAP` 800 features along a drive, drawn from z15.4 with
  maxzoom 12), `TRANSIT_STOPS_SRC` and `MARKERS_SRC`, now 18, and the camera/flock sources, now 16.
  Sparse ones (me, parking, saved, Street View, accuracy) keep 12. NOT yet A/B'd: the sources are
  nearly empty in a quiet suburb with no route, so the win should show during nav or in a city.
- **MEASURE ARCHIVE CHURN BEFORE BUILDING DELTAS (2026-09-18):** `scripts/archive-churn.py old new
  [--patch]` reports per-zoom identical/changed/added/dropped tiles (it parses the PMTiles v3
  directory itself, same layout as the app's `PmtilesReader`) and builds a real `zstd --patch-from`
  delta, which is the number the decision turns on. `.github/workflows/places-churn.yml <region>
  <days>` bakes a region twice, against a dated Geofabrik extract and today's, and writes the table
  to the job summary; it publishes nothing. Kentucky over 7 days: 1.3% of tiles, 3.2% of bytes, delta 4.4 MB
  vs a 183 MB archive; Andorra over 6 days reads 11% / 25% / 22%, so ALWAYS measure a region the
  size of the ones people download - a small archive exaggerates what one edit touches. Verdict:
  deltas are worth building (ROADMAP has the shape and the two open decisions).
- **DELTA UPDATES FOR DOWNLOADED ARCHIVES (2026-09-18, app half built):** `scripts/pmtiles-make-patch.py`
  (producer) / `pmtiles-apply-patch.py` (reference applier) / `velapmtiles.py` (the ONE reader, so the
  fingerprint cannot drift) / `app/offline/PmtilesPatch.kt` (the applier on the phone). The patch
  appends the changed tiles and a rebuilt directory and flips the 127-byte header LAST, so an
  interrupted apply leaves the old archive intact and the cost is the patch, not a second copy of the
  region. **The patch names no offsets** (format v2): it carries the new directory minus its offsets
  plus a carried/kept flag per entry, and the applier resolves them against its own file and writes
  the directory itself. v1 shipped the finished directory, so a patch only applied to a byte-exact
  copy of the archive the bake diffed against - a phone that had taken one patch bounced every later
  one and re-downloaded the region for ever. Found on a device (`patch refused, archive is 3355121
  bytes, patch wants 3259302`), pinned by `PmtilesCompactTest`'s chain tests. The result is PROVEN before the header moves: the fingerprint (SHA-256 over sorted tile ids
  + tile hashes, phone-computable - Android has no blake2b) must equal the one the patch carries,
  which is a fresh download's; a mismatch truncates back and the caller downloads whole. Policy is
  `ui/RegionUpdates` (pref `region_update_mode`, OFF **is the default until somebody has watched a
  patch download and apply on a device** - a feature that rewrites an installed archive does not get
  to default itself on - then WIFI / MOBILE, metered judged by
  `isActiveNetworkMetered` so a metered Wi-Fi counts), Settings > Offline maps; a FULL re-download is
  never automatic on any setting. Every attempt is logged (`diag.record("delta")` + logcat
  `VelaDelta`) with the bytes and the reason for a fallback, because the failure worth seeing is a
  region that quietly downloads itself whole every week. **PROVEN END TO END ON A PIXEL 9
  (2026-09-19):** a region was installed at one revision, re-baked, and the phone took the published
  patch - `applied 23 tiles ... rev 20260919 -> 20260920, grew 93 KB, dead 70 KB`, 94 KB down against
  a 3.3 MB archive. Two app-side bugs fell out of that run and are fixed: the catalog was memoized
  for the life of the PROCESS (a bake landing while the app ran was never noticed, so no update was
  ever offered), and dead space was counted but never bounded (`dead.json`). Dead bytes are now
  RECLAIMED LOCALLY (`PmtilesCompact`, past a fifth of the file): the phone already holds every live
  tile, so the rewrite is a pass over the file with no network, it lands on the bake's own layout,
  and it is fingerprint-checked into a temp file before it replaces anything. A full download
  survives only as the last resort when there is no room to rewrite. The same run measured the churn a delta
  actually saves: a same-code rebake carried 23 of 4586 tiles (3% of the archive), while the rebake
  that also carried a BAKE CHANGE carried 2506 and was correctly refused as too big. TEST TRAPS: `org.json` is a STUB
  in app unit tests (needs `testImplementation("org.json:json:...")`) and `android.util.Log` THROWS
  unless `testOptions.unitTests.isReturnDefaultValues = true`, which turns a log line inside a
  runCatching into a mystery failure of the thing under test.
- **REMEMBERED PLACE LINKS ARE DROPPED ON UPDATE (2026-09-18, user):** `openPlaceCache` /
  `open_place_links.json` short-circuits the tap resolve, so a link resolved by an older, worse rule
  outlived the fix for it - a supermarket kept opening its fuel station after the ranking bug was
  fixed, because the tap never reached the ranking again. `forgetOpenPlaceLinks` runs when
  `BuildConfig.VERSION_CODE` differs from the stored `open_links_build`, and after a places archive is
  updated or re-downloaded. The CLOSURES list is NOT dropped: that is a correction, not a cache.
- **THE TRAFFIC RASTER CROSS-FADES (2026-09-18):** `rasterFadeDuration(900f)` on `vela-traffic`.
  Google's tiles expire mid-drive and a replaced tile used to swap between frames, which reads as the
  whole congestion layer flickering.
- **OSM BUSINESSES ARE A SOURCE IN THE PLACES BAKE (2026-09-18, user ask):** named business NODES
  from the region's Geofabrik extract are inserted into `raw` beside Overture and AllThePlaces,
  through the SHARED `osmcat(props)` / `isbiz(props)` macros (lifted out of the ATP block so both
  sources use one tag mapping), deduped against everything already in `raw` by `nkey` or brand within
  ~150 m, confidence 0.8, id `osm:<n123>` (osmium `--add-unique-id=type_id` puts it in the FEATURE's
  `id`, NOT in properties), tile `origin` = `osm`. WHY: Overture is monthly and uncorrectable by us,
  OSM is the one source a user can fix and see fixed. Nodes only (a way's centroid is the parcel-point
  guess again). Andorra: 766 in box, 521 added, 245 deduped. A seventh of the catalog rebakes nightly
  (cron 04:40, `NIGHTLY` slice by sorted position) so an edit lands within a week; `only=<region>` is
  ~2 minutes. Deliberately NOT the whole catalog nightly: it would offer every downloaded region a
  fresh few-hundred-MB copy every day.
- **THE BAKE TOOLCHAIN IS CACHED + PINNED (2026-09-18):** tippecanoe 2.79.0 and go-pmtiles 1.31.2
  live in an `actions/cache` keyed on those versions (`~/vela-bin`), because building tippecanoe from
  source was 69 s of every one of 414 jobs. A wave that misses the cache builds it once per job as
  before, so the win lands on the NEXT wave, not the one you just started.
- **PRUNE THE OVERTURE READ ON `bbox`, NOT ON THE GEOMETRY (2026-09-18).** `build-places-region.sh`
  filtered `raw` with `WHERE lng BETWEEN ... AND lat BETWEEN ...` where lng/lat are
  `ST_X(geometry)`/`ST_Y(geometry)`: a computed column, so DuckDB could prune nothing and read every
  place on earth for every region. Measured on the Kentucky job: 504 s of the 570 s bake was that
  ONE statement (everything else - ATP join, OSM snap, tenant/kiosk/forecourt rules, unit snap, ring
  spread - is ~40 s combined, and tippecanoe is 29 s). Overture's `bbox` struct is a real column with
  row-group statistics, so `AND bbox.xmin BETWEEN $W AND $E AND bbox.ymin BETWEEN $S AND $N`
  (`BBOXPRED`, S3 path only) returns the identical 400,608 Kentucky rows in 3.7 s. The addresses
  query in the same script always did this. KEEP the geometry test as the exact filter; bbox is the
  hint. Any new read of a cloud parquet gets the same treatment before anyone optimizes the rules.
- **Bake joins must be HASH joins (2026-09-16).** Two correlated lookups that were free on the
  Davis box went effectively quadratic over a whole state: the tenant check (one EXISTS with three
  OR-ed tests) and the unit snap (a LATERAL lookup per stacked row). A world bake did 19 regions in
  2.5 hours on them, and four west-coast state bakes were still running after an hour. Both are
  now equi-joins with the ~200 m box as a residual: `tenants` is three joins (normalized address,
  lower(brand), `nhead` first word) UNIONed and DISTINCT, and the snap joins on (number, unit)
  and keeps the nearest by row_number. The AllThePlaces dedupe (#515) had the same shape - a
  correlated NOT EXISTS with the name key OR the brand - and is two hash joins with the keys
  computed once per row now. With all three fixed, a whole state bakes in minutes: Oregon from a
  laptop, 244,954 places, 2,960 chain rows added, 2,749 tenants snapped, 524 s end to end. The
  duckdb heredoc runs with `.timer on`, so every CI log shows per-statement times. Output on the
  Davis fixture is identical. Any new rule in
  `build-places-region.sh` that relates rows to rows needs an equality to hash on - an OR of tests
  or a correlated subquery will not survive a state.
- **Tenant demotion is a SEMI-JOIN (2026-09-16).** `tools/build-places-region.sh` used a LEFT JOIN
  onto the anchor rows, so a tenant matching two anchors (a mall AND the supermarket inside it)
  was emitted TWICE - 136 duplicate rows in the Davis fixture, each landing in its own slot of the
  stacked-point ring, which is a large part of why a strip mall looked like a scatter of dots. It
  is an `EXISTS` test now, and the anchors are UNION ALLed back unchanged. The match is also wider:
  addresses compare through `anorm` (lowercased, unit/suite dropped) and a row whose name is an
  anchor's name plus more words ("Safeway Pharmacy") counts as a department wherever it sits.
- **Traffic raster anchoring (2026-09-16, #521):** `ensureTraffic` inserts `vela-traffic` ABOVE
  `building-3d`/`building` (fallback: below the first symbol layer, which in Liberty is the one-way
  arrow at index 61, BEFORE the buildings at 83-84, which is how the footprints ended up painting
  over the congestion colors). Satellite still anchors above the imagery. Keep it under the labels.
- **SIDE-STREET DETOURS AROUND PLATE CAMERAS (issue #600, 2026-09-21).** `ui/FlockDetour` (pref
  `flock_detour`, OFF, Settings > Navigation > Cameras, nested under "Avoid surveillance cameras",
  in the settings search). After `refreshFlockOnRoute`'s re-rank, when the leading route still
  passes cameras, `tryCameraDetour` runs the reporter's workaround: `core/nav/CameraDetour.candidates`
  groups the lead route's cameras into clusters (`CameraAlerts.group`, nearest first, at most
  `MAX_CLUSTERS` 3) and for each gives the two points `OFFSET_M` (150 m) to the driver's left and
  right of the road there; the pass tries left then right through `dataSource.directions(waypoints=)`
  with the point merged into the user's stops in travel order (`CameraDetour.mergePlan`), keeps a
  candidate whose camera count drops inside the SAME cap as the re-rank (the lesser of 25% or 10 min
  over the fastest route), builds the next cluster on it, and stops at `MAX_REQUESTS` (6). The
  router's own snap does the graph work: a point that lands on the same road folds back into the
  same route and fails the count; a point on a parallel street is a real detour. The kept route
  leads the list with its badge, carries `Route.detourPlan` (the full ordered waypoint list), and
  `NavController.navStopsFor` starts the drive with the detour points as SILENT `NavStop`s
  (`NavStop.silent`: routed through by every reroute and recheck, never spoken, filtered out of
  `remainingStops()` so the stops row, the editor and the leg dividers never show them). A mid-drive
  stops EDIT rebuilds the list from the visible stops and drops the detour; `addStop` keeps it.
  Logcat `VelaFlockRoute`: `detour: clusters=N requests=N kept=… cameras A -> B`. It depends on the
  same-day waypoint work: Google prices every candidate through its stops with traffic, so the cap
  compare is honest. DRIVE only, and epoch-guarded like the re-rank.
- **Flock route counts use a 45 m corridor (2026-09-16, #527, `FlockCameras.along` default):** 120 m
  caught cameras on a parallel alternate a block over. `OverpassAlprCameras.fetchAlong` (the
  fallback) still uses its own width; the bundled set is what counts in practice.
- **Offline maps page order (2026-09-22, #601 + user: "the way some of this is laid out is goofy").**
  This area (save the view, places-with-downloads, automatic updates) -> Storage (breakdown, Clear
  map cache, Delete all offline data) -> **Downloaded** (every saved area and every installed
  region with its own controls, so "what do I have" is one list right under the storage figures,
  the #601 ask) -> "Entire states & countries" as ONE ALPHABETICAL TREE (`regionTree` in
  OfflineSettings): the catalog's hierarchy lives in the names' trailing parentheticals ("Bayern
  (Germany)", "Alberta (Canada)", "Alabama (state)", "Puerto Rico (US)", "Northern California
  (California)"); "(state)", "(US)" and "(California)" all fold under a "United States" parent, any
  other parenthetical is its own parent, a parent is one expandable row with "Download all", a
  country with no pieces is a plain row, everything sorts by name, and the region you are in is
  marked and its parent starts open. The old page led with an "All of <country>" block (whose
  United States entry held three territories) and then a flat 450-row list with installed and
  nearby rows pulled to the top. `RegionRow` / `ParentRow` are the two row composables; the
  filter matches a parent or a piece and opens matching parents. **Two rules from the same day's
  device pass (user: "so laggy when I go to offline maps"):** the catalog is a LazyColumn with
  the SCREEN'S height inside the page's scroller (a lazy list cannot be unbounded inside a
  scroller; the page scrolls to it, then it scrolls inside), with parents and their open pieces
  flattened into one keyed item list (`CatalogRow`). Measured on a 4a release build with
  Perfetto: the plain Column composed and measured every catalog row on open, a 430 ms frame
  (232 ms measure, 110 ms recompose); revealing rows a chunk per frame was no better because a
  Column re-measures everything per chunk. And the filter field scrolls itself to the top of the
  area above the keyboard when it takes focus (`bringIntoViewRequester` + a rect far taller than
  the viewport, whose TOP edge the scroller aligns), because it sits low on the page and the
  keyboard covered the rows it filters. NB gfxinfo is blind to this page's open (0 frames in its
  window); Perfetto with `atrace_apps: "app.vela"` and the config piped on stdin (`perfetto -c -
  --txt`, the phone refuses a config file under /data/local/tmp) is what measured it.
- **Route shields are Vela's own bitmaps (2026-09-15, `ui/map/RoadShields`).** The OpenFreeMap
  sprite's `us-interstate_N` / `us-highway_N` / `road_N` are white outline shapes sized for 10 pt
  text and there is NO `us-state_N`, so state routes drew as bare numbers and "80" squeezed into
  the badge. `RoadShields.install` (from `emphasizeShields` on every style load) registers
  `vela-shield-<family>_<ref_length>` images for four families x 1..6 (interstate = blue + red
  band + white number, US = white shield, state + international = white rounded badge), each
  sized for 11 pt bold digits, and repoints the three shield layers' `icon-image` at them with
  11 pt Noto Sans Bold, halo 0, and a small downward text offset on the interstate so the number
  sits in the blue. Two traps from the first attempt: a SAME-NAMED `addImage` loses to the
  sprite once it finishes loading (the interstate kept the sprite's white shape while the
  sprite-less `us-state` showed Vela's), and `icon-text-fit` shrank the badge to the glyph box,
  so the badges are fixed-size per ref length, not stretchable. Sign colors, not theme colors:
  dark mode keeps them, like Google. NON-US families (`RoadShields.FAMILY`, a raw-JSON match on
  `network` then `class`): gb/ie motorway blue, gb trunk/primary + ie national + e-road +
  ca-transcanada green, any other `class=motorway` blue, else the white `road` badge; the tile's
  `network` is "road" or absent nearly everywhere outside the UK/Ireland/Canada, so most of
  Europe and all of Asia get blue motorways + white badges (Japan's green expressways are not
  knowable from the tile). EXIT NUMBERS: `RoadShields` also adds `vela-exit-shield` (a
  `transportation_name` SymbolLayer, `subclass=junction` + has ref, `vela-exit_<len>` green
  badges, z12.5+) above `road_shield_us`; it is NOT in the car-mode strip list on purpose, and
  its id carries "shield" so the satellite white-text pass skips it. Junction NAMES (Europe,
  Japan) have no ref and are filtered out.
- **Map COLOR SETS (2026-07-11): Settings -> Appearance -> "Map colors" picks Modern or
  Classic.** `ui/MapColors` holder (pref `map_palette`; init in VelaApp); `applyMapTheme`
  dispatches to `applyLight`/`applyDark` (Modern, the pixel-sampled palette) or
  `applyClassicLight`/`applyClassicDark` (the archived pre-sample look, values in SPEC section 6.2 -
  white roads, faded casings, yellow motorways, true greens). The styleKey carries
  `|pal=` so a switch reloads the style like a theme flip. Post-archive twin layers
  (trails/bikeroutes/pitch/commercial) get harmonious colors in the classic fns - they exist
  in ensureLayers regardless of palette, and an unstyled LineLayer renders BLACK, so any NEW
  twin layer must be colored in ALL FOUR apply fns. **Same trap bit the maxspeed "Speed B" query
  layer (fixed 2026-07-13):** its `lineColor("#00000000")` 8-digit-hex string was REJECTED by
  MapLibre's color parser and fell back to the default OPAQUE BLACK - a 12dp black stripe over
  every road on the browse map (device report). **BUT opacity 0 kills the query (2026-07-13, found by ars18 in the
  vela-dpad fork, A/B-proven on device):** MapLibre skips fully transparent features at render
  time and queryRenderedFeatures only sees rendered features, so the transparent Speed B layer
  returned NOTHING and the badge was dead from the moment the black-roads fix landed. An
  invisible-but-queryable layer needs `lineColor(Color.BLACK)` + `lineOpacity(0.004f)` (one alpha
  step - invisible on any basemap, still queryable), and only ADD it while it's needed. **`speedOverlayOn` is MOTION-ARMED (`speedOverlayArmed` in MapScreen, 2026-07-13):**
  armed the moment `navigating || mySpeed > 3 m/s`, disarmed after 2 min of stillness - NEVER keyed on
  `driveFollowing` alone. driveFollowing is true on the bare browse map (followMe defaults on), and
  keying the overlay off it mounted the PMTiles sources while browsing AND removed them from the style
  MID-GESTURE when the first pan dropped followMe - that native style churn was the post-0.4.542
  "atrocious panning" regression (user device A/B: 542 smooth, wave janky). The hysteresis also stops
  stoplight churn. The scale bar hides only while `driveFollowing && speedOverlayArmed` (actually
  free-driving) - `!driveFollowing` alone had it hidden on the whole browse map.
  Never trust an 8-hex color STRING for transparency. The FLEET DEFAULT is remote:
  `calibration.json` `defaultMapPalette` (v15) -> `Calibration.defaultMapPalette` -> the VM
  pushes it into `MapColors.remoteDefault` at init + after refresh; a user's explicit pick
  always wins. Changing everyone's default = edit the field, bump version, re-sign, commit
  (same channel as defaultVoiceId). Adding a whole NEW named set still needs an app release
  (palettes are compiled); make the apply fns data-driven if sets ever multiply.
- **AMOLED true-black map & navigation palette (2026-09-14).** When `ThemeMode.AMOLED` is active,
  `applyAmoled(style: Style)` layers a pure-black (`#000000`) palette on top of `applyDark(style)`.
  Layering on top of `applyDark` ensures every layer `applyDark` themes that `applyAmoled` does not
  touch (boundaries, rail, aeroways, campus fills, untouched labels) inherits the dark styling
  instead of falling back to Liberty's light defaults on a black map. Palette functions must NOT
  change zoom gates or extrusion opacity (those belong in `ensureLayers`/`applyDark`, with extrusion
  opacity documented at 1). The `styleKey` carries `|amoled=` so switching to/from AMOLED immediately
  reloads the style. The route polyline color stays standard traffic-coded blue (`#1F6FEB` / congestion
  amber & red) because white is the puck's color and reads as missing traffic data. Navigation
  overlays (`NavControls`, `NavSearchChips`, `StepsSheet`, `SpeedWidget`, and the road label pill)
  consume `SheetPalette.bg(dark, amoled)` with `SheetPalette.BorderAmoled`, while `ManeuverBanner`
  keeps its distinct teal container accent for instruction hierarchy.

- **The reviews RPC is DEAD, do not re-calibrate it (proven 2026-07-19):** the
  `listentitiesreviews` endpoint 404s for EVERYONE now - verified with a valid live feature id
  from both a raw client and a real logged-out Chromium session. Nothing calls
  `GoogleMapsDataSource.reviews()` anymore; ALL review content comes from the WebReviewsFetcher
  DOM scrape. If reviews ever break, debug the scrape (selectors, virtualized-list
  accumulation), not the RPC, and don't burn time trying to "fix" reviewsPb.
- **The review page follows the APP'S LANGUAGE now (issue #278, 2026-09-02).** `WebReviewsFetcher`
  pinned `hl=en&gl=us`, and the page language decides WHICH reviews Google serves - a Chinese reader
  looking at a Chinese restaurant got the English ones. Reviews are CONTENT and must never be
  translated for the reader, so the fetch uses `reviewsHl()` (the app locale, with the zh-TW/zh-CN
  script split, gated to `SUPPORTED_HL`). ⚠️ PR #307 shipped the scraper side of this but the
  `loadUrl` stayed pinned to `hl=en` and `reviewsHl()` did not exist; the 2026-09-06 review pass
  wired it (`WebReviewsFetcher.reviewsHl`, `AppLocale.effective()`). ⚠️ **Unpinning `hl` ALONE breaks the scraper** - it keyed
  on English text in four places, all verified live on a zh-TW page: the star SELECTOR
  (`aria-label*="star"` vs the real `5 顆星`), `num()`'s `/([0-9.]+)\s*star/i` (returned 0 for every
  review), the reviews TAB (`/^reviews\b/i` vs 評論) and the more-reviews BUTTON. Ratings now read
  the LEADING NUMBER (every language leads with it) and the labels match `:core`
  `data/ReviewWords` - kept there, not inline in the JS, so they are unit-tested (`ReviewWordsTest`).
  The more-reviews button requires a review word AND a "more" word: a bare match hits "Write a
  review" (zh-TW 撰寫評論) and CLICKS THE COMPOSER, a wrong action rather than a missed one.
  **`ReviewsPanel` (the full-screen page) FOLLOWS THE APP LANGUAGE TOO (issue #359, 2026-09-13; it
  was pinned to `hl=en` until then).** Everything its carve keyed on by English text now reads the
  per-language word tables in `:core` `ReviewWords` (`words(overrides)`, keys review / more / sort /
  star / ago / write / like / share / actions / all / processed, each remotely overridable through
  calibration `reviewWords`), injected into the script as the `VW` regex map: the reviews tab, the
  Sort button, the Like / Share / actions buttons, "Write a review", the "All" chip that anchors the
  chip row, the disclaimer row, and the relative-date "reviews have rendered" check. The histogram
  rows are parsed by their LEADING DIGIT (`ReviewWords.HISTOGRAM_ROW`: a single digit not part of a
  decimal or a thousands group, then the count; "5 stars, 1,189 reviews", "5 星級、908 則評論"), the
  star widgets get a `.vela-stars` class from JS (the dark-mode re-invert CSS cannot match a word in
  every language), and the sort menu is clicked BY INDEX (Google keeps one order in every language;
  the English label stays as the fallback). Verified on the 4a in Traditional Chinese and English
  with the zh-TW labels captured live, and the German and Russian labels captured the same day in
  the browser pane (`ReviewWordsTest` pins all three): German puts "teilen" at the END of the
  Share label and names the chip "Alle Rezensionen", its histogram counts use a dot for thousands
  ("1.329") and Russian a space ("1 324"), Russian's Like is "Лайк", its Write is "Оставить отзыв",
  and its SORT BUTTON carries no sort word at all (its label is the current choice, "Самые
  релевантные"), so `velaSort` falls back to the last `aria-haspopup` button before the first
  review card. **Every app language was captured the same day** (fr, es, it, pt-BR, nl, pl, sv,
  uk, hu, he, ja, zh-CN too): Polish, Swedish, Ukrainian, Hungarian and Hebrew also label the
  sort button with the current choice; Ukrainian's histogram rows carry NO star word ("5, 908
  відгуків"), so the row test is the leading-digit rule alone (a separator right after the digit
  is fine unless a digit follows it); Japanese and Hungarian put the share verb at the END; the
  "All" chip may carry two trailing words ("Tous les avis"). When a language shows a Google
  control the carve missed, capture the page in the browser pane with `hl=<lang>` and add the
  label to the table; do not guess a second time.
  **THE "SEE MORE REVIEWS BUTTON IS BROKEN" REPORT, SETTLED WITH A PROBE (issue #359, 2026-09-13).**
  Google serves the place page in two layouts per session: the full feed (Reviews tab, chips,
  60-110 cards, infinite scroll) or a paged Overview whose review section ends in "More reviews
  (N)" (`更多評論 (1,326)`). In a healthy session that button, and our own click on the Reviews
  tab, both fire the review-feed RPC (`batchexecute?rpcids=qv9Egd`; logged as `VelaPanelNet`)
  and the page becomes the full feed: probed five opens in a row in Traditional Chinese, the
  button took 40 cards to 107 twice. When Google WITHHOLDS that RPC for the session (the soft
  throttle the feed watchdog already knows), the tab click does nothing, the page stays on the
  Overview with the button showing, and the button does nothing either; that is the report, and
  it is not language-specific and not our carve. What changed: when the Reviews tab has still not
  selected after ~20 s the tick loop calls `VelaPanel.stuck()`, which RETRIES before it fails
  (user 2026-09-13: "make it work somehow"): first a plain `reload()`, then a reload on a FRESH
  anonymous session (`CookieManager.removeAllCookies` + the SOCS/CONSENT cookies re-seeded, else
  an EU page bounces to consent), and only on the third strike `fail()`, whose host `onFailed`
  toasts `place_reviews_throttled` before closing. The feed decision is per page load (five opens
  in a row alternated between layouts), so the retries are not a long shot. The withheld state was
  hit once by hand and is not reproducible on demand; the ladder is verified only for the healthy
  path (no regression). Reproduce a withheld session by opening the page many times in a row. A `WebChromeClient` logs the
  page's console errors under `VelaPanel`, and a probe line prints the tabs it saw.
  **WHAT'S NEW after an update (2026-09-13, `ui/WhatsNew`):** the first launch on a new
  versionName fetches that version's release notes (`releases/tags/v<version>`, or the rolling
  `canary` release for a canary build) and shows them once in `WhatsNewPrompt` (WelcomeScreen, the
  donate prompt's shape), last in VelaRoot's one-time-prompt chain so it never stacks on a setup
  step. Never on a fresh install (`last_seen_version` in `vela_onboarding` is seeded silently the
  first time), never without the notes in hand (a failed fetch leaves the version unseen, so the
  next launch retries), and Settings > About > "What's new in this version" reopens it on demand.
  The notes are the commit subjects CI writes into every release, run through `plainReleaseNotes`;
  nothing is bundled.
- **A PROBE LINE MUST NOT CARRY GOOGLE'S `@lat,lng` (2026-09-18).** Google's place-page path is
  `/maps/place//@<lat>,<lng>,<zoom>...`, and the coordinate it puts there is derived from the
  SESSION, not from the place - on a device it reads as wherever the phone is. The reviews probes
  logged `location.pathname` verbatim, so a logcat line (and a shared diagnostics export, where
  DiagScrub only rounds it) carried the user's own area. Every probe now logs
  `location.pathname.split('/@')[0]`. Any new page probe does the same: log the path up to `/@`,
  never the whole thing.
- **REVIEW LABELS ARE TESTED WITH THE PLACE NAME CUT OUT (issue #535, 2026-09-16).** Google's
  tab and button labels embed the place name ("Overview of Davis Food Co-op", 「X」總覽), and the
  review pattern carries the word in every language, so "D-avis" matched the French "avis": the
  full page took Overview for Reviews, reported ready on it, and its More reviews button reloaded
  to the Overview (reproduced on the Davis fixture). `STRIP_PLACE_NAME_JS` (web/ReviewTabJs.kt,
  `velaNoName`, name = the page h1, else the longest run two tab labels share) runs before every
  review-word test on a tab or button in BOTH scripts; any new label test goes through it. The
  full page also records `panel` events in the Diagnostics export through `DiagLog.shared`
  (open, page loaded, feed requests, the More reviews tap with card counts before and 5 s after,
  feed-withheld retries, failure, our probe lines and Uncaught errors; Google's CORS noise is
  skipped). Google now shows signed-out sessions "a limited view of Google Maps", which caps
  every review feed; that is not a parser bug.
- **THE REVIEW SCRAPE WAS DEAD FROM 2026-09-06 TO 2026-09-13 (issue #359, every language).** The
  review-pass commit that moved the tab/button words into `ReviewWords` wrote the two regex lines
  of the scrape script as `${'$'}{reviewPatternJs()}` inside the Kotlin raw string, which emits
  the LITERAL text `${reviewPatternJs()}` into the page: "Uncaught SyntaxError: missing ) after
  argument list" on line 19, the whole IIFE never ran, and every scrape "timed out after 45 s
  with nothing" while the full-screen WebView page (a different script) kept working. It read as
  environmental on the 4a for a week. Three guards now: the script reports a start marker and any
  JS error through `VelaBridge.onInfo` (diag kind `reviews`, summary `probe`, logcat
  `VelaReviews`), a `WebChromeClient` logs console ERRORS, and the probe line every 16 ticks says
  tabs/opened/cards/viewport. **In a Kotlin raw string, `${'$'}{x}` is NOT a template.** Same
  day: the hidden WebView is sized in CSS px x density (`WV_WIDTH`/`WV_HEIGHT` 1200x1000 CSS;
  1200 physical px was ~450 CSS px on a 2.75x phone = Google's narrow layout), `scrollStep` also
  scrolls the window, and the tab path presses an "All reviews" button once if the list stays
  short. NB the count is still small (3-8 on the 4a; the browser pane showed 3-5 for the same
  place with the feed withheld, 37 for another): Google gates how much of the feed an anonymous
  desktop session gets, and that is not a parser problem.
- **The full-screen reviews page could not be scrolled back up (issue #359, item 2, 2026-09-13).**
  `stretch()` returned early in FULL mode, so `__velaSc` was never set, the edge reporter never
  fired, and the native side kept its initial "at top" verdict: a downward finger drag anywhere
  in the list (scrolling UP to re-read) was forwarded as a pull-to-close and past 120 dp the page
  shut. FULL now adopts the scroller and hooks the reporter (no height styling), the edge test
  also requires the document at its top, and the native pull needs `wv.scrollY <= 0` too.
- **Review scrape accumulation replaces on LONGER TEXT (2026-07-19, issue #181):** a card is
  often harvested on the tick its More toggle was clicked, before Google's async re-render
  swaps in the full body - first-capture-wins turned that race into permanent "…" truncation.
  The accumulator keeps the longest text per review id, and expand() clicks the `.w8nwRe`
  class hook (language-independent) with the English label regex as fallback only. Keep both
  invariants if the scraper script is reworked.
- **Flat vegetation (2026-07-11):** fill-pattern CANNOT be cleared once a style layer ships
  with one (empty-literal unset no-ops on device) - `ensureLayers` hides `landcover_wetland` +
  `road_area_pattern` and adds flat twins `vela-wetland`/`vela-plaza` that applyLight/applyDark
  color; the OSM poi tiers' filters exclude vegetation classes (wood/forest/tree/grass/wetland - NOT
  park or garden since 2026-09-18: excluding those deleted parks from the map, because the places
  bake drops the park CATEGORY on the grounds that OSM has parks and this filter then dropped OSM's;
  a named park is a destination, a wood is scenery) so
  forests read as flat green like Google, not icon confetti. Nav mute/steps/End are 54dp.
  The search bar hides while an expanded place sheet covers it (its sliver still took taps).

- **Photo DATES: every keyless in-page route is DEAD (probed exhaustively 2026-07-11).** The
  place page's APP_INITIALIZATION_STATE carries NO photo urls at walk time (census: one big
  string leaf, zero googleusercontent, zero "ago") - photos are id-referenced and urls come
  from lazy responses. The walk's `aisDates()`/`onDates` plumbing stays (inert, one-shot,
  lights up if Google ever re-embeds it), and `fetchPhotos` still merges any mined/RPC dates
  into the join. The ONE live route is recalibrating the hspqX RPC from a desktop capture
  (remote-fixable via calibration.json, see ROADMAP) - do NOT re-probe AIS. Review photos are
  a SEPARATE pipeline (author + date come from the review itself) and already show dates.
- **Flick = commit (2026-07-11):** a release faster than `FLING_COMMIT_DPS` (450 dp/s, shared
  const in PlaceSheet) advances AT LEAST one detent in the flick's direction on all three
  sheets (place/results/directions) - the pure coast projection needed the throw to cross half
  the gap and made short flicks feel dead, worst on the two-detent chooser. Hard throws still
  cross two detents via the projection.
- **Map palette matched to the GOOGLE APP screenshots (2026-07-11):** DARK vegetation is TEAL
  (#1a4a4d park/grass, #17434a wood, #194247 wetland) - the app's dark green, clearly lighter
  than the #242f3e land; LIGHT roads are FILLED blue-gray (#cbd9e3 minor, #c3d3e0 secondary,
  #bfd0de trunk/primary, casing #bccbd8) - the APP fills roads solid where the web uses
  white-with-a-gray-frame; light vegetation #d4edd5/#c8e6cb (app mint), land #f2f1ee, water
  #90daee. The map-style Settings row is REMOVED (single style; plumbing kept). Nav-card trip
  time is a FitText (shrinks to fit, floor 55%, never wraps/ellipsizes).

- **Sheet flick velocity is measured on INTEGRATED deltas (2026-07-11):** the manual
  VelocityTrackers (place handle, results handle, the drag-anywhere directions panel) fed
  change.position, which is local to a node that MOVES as the sheet resizes - measured
  velocity ~0, flicks read as slow drags and never committed. They now feed a running sum of
  the drag deltas. `FLING_COMMIT_DPS` dropped 450 -> 260. The nested-scroll body paths were
  never affected (Compose computes those velocities properly), which is why the place sheet
  body always felt right.
- **DARK palette is PIXEL-SAMPLED from Google Maps on the attached Pixel 9 (2026-07-11,
  definitive - supersedes the eyedrop):** land #162640, other-landuse #1c2638, water #000d2a
  (DARKER than land, the inverted relationship matters), vegetation #0d3847 (teal), buildings
  #1c3b69 w/ outline #2e3d6d (Google's own second shade), minor roads #3d5a77,
  arterials/trunk/motorway #476789, casings = land, service/alley tier #2a4056 (Google
  draws alleys DARKER than streets - second P9 side-by-side 2026-07-11). Both former deltas are APPLIED (user 2026-07-11): `vela-trails` (LineLayer twin,
  class=path subclass path/cycleway/bridleway ONLY - footway/steps/pedestrian stay hidden,
  that was the June "weird walking tracks" clutter; inserted BELOW road_minor, minZoom 14)
  draws the trail network green (dark #167055 sampled); `vela-pitch` (landuse
  pitch/playground/track/stadium, above park) tints sports fields (dark #0d4956 sampled)
  - AND Liberty's own `landuse_pitch`/`landuse_track` layers (they sit ABOVE the twin) are
  colored directly + exempted from the landuse-neutralize loops, else the tint never showed
  (found at a park with ball courts).
  Trail light = #7fcdb0 (P9-SAMPLED); pitch light = #a9eac2 (P9-SAMPLED at Toomey Field,
  no estimates left); campuses (landuse_school) = #f0eded warm gray (sampled at UC Davis,
  light only - dark keeps the neutralized land).
- **LIGHT palette is PIXEL-SAMPLED from the Google app on the P9 (2026-07-11, definitive,
  supersedes every earlier light set):** land #f8f7f7 (the app is cooler than the web's
  #f6f6f6 and the user's kept #f2f1ee - verbatim wins per user "all of it"), roads are ONE
  blue-gray fill #aab9c9 for streets AND arterials with NO casings (casings = land),
  driveways/service #9bacbc, motorway #8aa4c0, buildings #e8e9ed w/ outline #d6d9e6
  (extrusion = fill, light killed), vegetation #d3f8e1 (park/grass/wood/wetland all one
  mint), water #90daee (unchanged), plaza/parking surface #dbe0e8, trails #7fcdb0,
  COMMERCIAL/RETAIL blocks cream #fdf9ef via the `vela-commercial` twin (Liberty ships no
  layer for those classes; dark paints the twin #1c2638 = the other-landuse navy so dark
  is unchanged).
- **Heading beam + Your lists placement (issues #289 / #290, 2026-09-03).** The browse heading
  beam (`arrowBitmap`) ran a TWO-stop gradient from alpha 150 at the APEX to 0 at the tip - but
  the apex sits UNDER the location dot, so the strongest part was hidden and everything visible
  had already faded most of the way out. Three stops now (210 / 165 at 30% / 0), holding real
  opacity across the span that actually clears the dot: mean alpha over the VISIBLE part goes
  53 -> 84, and 105 -> 165 right where the cone emerges. Slightly wider too. When tuning this,
  reason about the visible span, not the peak. **Your lists moved OUT of the category-chip row
  into the search bar** beside the mic and gear: leading the chip row it competed with the
  quick-category chips, which are a different kind of control. Bare map + empty query only, so it
  never crowds the clear button.
- **Glyph ink rule (2026-07-11): leading/functional GLYPHS wear the SOFT ink, text keeps
  the strong ink.** Solid Material icons read heavier than text at the same color, and
  several sites were outright BLACK (an untinted Icon on a non-Surface container falls
  back to LocalContentColor black - the search page is a plain Box). Fixed sites: search
  bar gear (now onSurfaceVariant, matches the mic), map category chips
  (leadingIconContentColor onSurfaceVariant), the lists-bookmark ribbon circle
  (onSurfaceVariant both modes), ShortcutRow unset Home/Work (onSurfaceVariant, was
  SheetPalette.dim and mismatched the recents pin), the chooser Steps glyph + the
  search-along-route chip icons (SheetPalette dim beside ink labels). Give any NEW
  glyph-next-to-text the same treatment. **Bike routes: DEDICATED bike paths (OSM highway=cycleway,
  OMT subclass=cycleway) now draw in Google's teal #007b8b (light) / #1f8f9c (dark) via the
  `vela-bikeroutes` LineLayer, split OUT of `vela-trails` (which keeps foot subclass path/bridleway
  green) - 2026-07-11.** ON-STREET painted lanes (`cycleway=lane`/`cycleway:left/right` tagged on a
  road way) are NOT in the keyless OMT tile schema, so they still aren't drawn; that needs an
  Overpass layer (sibling of `OverpassTrafficSignals`) - see ROADMAP.
- **Ambient POI DOT TIER (2026-07-11):** `vela-ambient-dots`, a CircleLayer UNDER the
  ambient icon layer on the same source - every ambient place draws a small category-
  colored circle (`dotColor` prop from PoiIcons.colorFor; radius 2.6-4.2 by prominence,
  ring = land color per theme). Icons still collide; the losers now stay visible as dots
  and upgrade to icons as slots free up while zooming - Google's tiering. Circles skip the
  collision engine (cheap on the 5a/4a class GPUs); taps work through the same
  AMBIENT_INDEX_PROP rect query. Don't gate the dots on zoom - the ambient FETCH gate
  (z>=14) already bounds them. **The icon layer's `sort` (collision priority) is
  PROMINENCE-based, never the list index (2026-07-14):** the streamed pool RE-RANKS as terms
  land, so an index-based key changed every place's priority on each partial upload and the
  whole layer's placement reshuffled (the cold-load "icons consolidate and pop into each
  other"). `(10 - prominence) * 1000 + i` holds priorities still across uploads; the streamed
  partial paints also ESCALATE their batch (10 places first, 25 once >=60 painted,
  GoogleMapsDataSource) - each partial re-runs whole-layer placement, and halving the passes
  is most of the dense-area cold-load frame recovery. **Ambient LABELS are tiered by
  zoom x prominence (2026-07-14, copies Google):** textField is a step expression - <z15.5
  only prominence>=6.0 named, z15.5+ >=5.0, z16.5+ >=3.0, z17.5+ all (thresholds map through
  ambientProminence: 6.0 ~ 400+ reviews, 3.0 ~ 20+). An EMPTY textField skips that symbol's
  label placement entirely (textOpacity 0 would still place + collide invisibly), so this is
  also a placement-cost win. NOTE our MapLibre zoom reads ~1 lower than Google's for the same
  view extent (512px tiles) - A/B against gmaps by matching the VISIBLE AREA, not the z number.
  **That offset is why the bundled style shows minor street names from z14, not z15 (user
  2026-09-18), and why residential streets widened through the mid zooms (`road_minor` 3.2 at z14
  / 7 at z16, casing 5 / 9.5): next to Google at the same visible area ours read thin and a zoom
  late. The style is ONE MINIFIED LINE (`assets/styles/liberty-roboto.json`) - edit it with a
  script that re-dumps `separators=(',',':')`, or a pretty-print turns a one-line diff into 6000.**
  **SLIM-FLAVOR HEAL (2026-07-14, GoogleMapsDataSource.nearbyPlaces):** Google's first ~3 s of
  a fresh session serve a stripped per-place block (rating yes, reviewCount NO; same query+pb
  is rich seconds later - live-bisected on device). The cold-start fan-out lands entirely in
  that window, so the pool parsed with reviewCount=null and prominence ranking / dot sizing /
  label tiers all silently ran on zeros (and AmbientDiskCache persisted the zeroed pool).
  nearbyPlaces detects the flavor (>=3 rated, majority of rated missing counts) and refetches
  the fan-out once ~1.2 s later; healed places are prepended so distinctBy keeps the rich
  copy. Don't "fix" a flat-looking ambient layer by touching the expressions before checking
  whether the pool's counts are null.
  **STICKY RANKING (`ui/map/AmbientStability`, user 2026-09-18).** All of the above means a
  SETTLED view is painted several times with different counts for the same place - streamed
  partials, the twin-dedupe re-pass, and the slim heal's second full fan-out - and every paint
  re-ranked, so labels traded places and icons resized under a user who had not moved (a sushi
  counter taking the label off the Safeway it sits in, ~20 s after the map looked right).
  `AmbientStability.remember` freezes each place's prominence at its first RICH paint (a pool
  whose prominences are all zero is never remembered - that is the slim flavor, and freezing it
  would pin the flatness the heal exists to fix); `prominenceOf` is then what
  `keepAmbientForView` filters, sorts and caps on AND what `ambientMarkersOf` hands the layer,
  so the cap, the collision order and the icon sizes all agree. Later answers still ADD places
  (they sort in on their own value); they cannot reshuffle what is drawn. `reset()` runs on the
  same pan/zoom gate that re-queries and wherever the layer is cleared. 3D extrusions = the flat color at
  opacity 1f (the 0.9f translucency was the "3d buildings render slightly different" wonk)
  AND the style light at intensity 0 + fillExtrusionVerticalGradient(false) - MapLibre's
  default light (0.5) brightens extrusion tops ~40% at z16+ (#1c3b69 rendered #2e5590; the
  side-by-side P9 sample proved Google keeps buildings one color at every zoom, 2026-07-11).
  Sampling recipe: screencap Google Maps on the Pixel 9 over the target area, Counter the
  band, probe specifics. **Flick velocity, final form:** all manual trackers integrate
  deltas AND take max(tracked, plain travel/time average) at release - a flick can never
  measure ~zero; FLING_COMMIT_DPS = 180. **The whole gesture lives in ONE helper now
  (`sheetDragGestures` in PlaceSheet.kt, 2026-07-11)** used by every hand-driven drag
  surface: place handle, minimized place body, directions panel, results handle (MapScreen
  imports it) - the velocity subtleties are too easy to fork-and-drift as copies.
- **The MINIMIZED place-card body is its own drag surface (2026-07-11):** minimized, the
  skeleton fits inside the floor height, so the body's verticalScroll has NO range - and an
  unscrollable scrollable never engages a drag, so nothing reached dismissConn: a flick on
  the minimized card read as dead while the same flick on the handle worked (device-proven
  both ways). A `pointerInput(minimizedState, singleDetent)` on the body Column runs
  `sheetDragGestures` ONLY while minimized (keyed remount hands drags back to the
  nested-scroll path when the full body shows); tap-to-restore still wins bare taps (a drag
  claims the pointer only past slop). Same class of hole the directions panel had ("finger
  basically right on the pull bar") - if a sheet region ever feels drag-dead, check whether
  its scrollable has zero range there.
- **(older eyedrop note)** **Map palette is USER-EYEDROPPED from the Google app (2026-07-11, supersedes my web/screen
  estimates):** DARK land #111c31, buildings #172b56 (outline #243970), roads #304864 (trunk
  #3d5878, casings = land), vegetation #0d2b38. LIGHT vegetation #caf8dc, buildings #e2e3e9,
  roads #b0c1d4 (secondary #aabdd0, trunk #a4b8cd, casings #a2b4c9); light land stays #f2f1ee
  (user prefers it over Google's #f6f6f6). Their eyedrop had macOS color-shift on - expect a
  fine-tune pass.
- **Basemap labels are ROBOTO via self-hosted glyphs (built 2026-07-11, device-verified;
  dark-launched pending infra).** OpenFreeMap's glyph server is Noto-only (every Roboto stack
  404s), so Vela hosts its own set on the repo's GitHub Pages at `/Vela/fonts`:
  `scripts/build-map-fonts.sh` composites Roboto OVER OpenFreeMap's live Noto PER GLYPH
  (`scripts/composite_glyphs.py`, pure-python protobuf; Roboto wins its 896
  Latin/Cyrillic/Greek glyphs per stack, Noto keeps every other script - Shinjuku CJK
  device-verified intact), and the folders KEEP the "Noto Sans Regular/Bold/Italic" names so
  the ONLY style change is the `glyphs` URL (zero layer edits; the runtime textFont sites
  stay untouched; the inner PBF name field is decorative - OFM's own files carry a 23-font
  composite name). `ui/map/MapFonts` (init in VelaApp) fetches the LIVE Liberty JSON at
  launch - tile paths keep auto-following OFM's snapshot rotation, the property the old
  bundled asset lost - patches `glyphs`, caches to `filesDir/style/liberty-roboto.json`,
  and MapScreen swaps it in via `MapFonts.effective` (VelaMapView reads `file://` styles
  itself and falls back to the plain URL on a bad file). GUARDS, each device-proven: the
  font host is PROBED (range 0-255) and an unreachable host EVICTS the cache - a style
  whose glyph URLs fail renders NO labels, and the evicted fallback is byte-identical to
  the pre-font map (RMS 0.0 vs a Noto reference shot); a style-fetch failure keeps the
  last-good cache max 7 days (snapshot-rot guard), then the live URL wins. Offline REGION
  DEFINITIONS keep the plain Liberty URL on purpose (a definition outlives file paths, and
  its download caches Noto glyphs as the offline floor; the patched style's offline labels
  ride the ambient cache warmed by browsing). Local test loop: `python3 -m http.server` on
  the glyph dir + `adb reverse tcp:8099 tcp:8099` + `-PmapFontsUrl=http://127.0.0.1:8099`.
  NB `InputStream.readNBytes` is API 33+ (minSdk 26) - the probe reads manually. The
  ANDROID AUTO snapshotter resolves the same style (CarMapRenderer reads the patched
  file + `withStyleJson`; a plain `.withStyle(LIBERTY.uri)` kept the car on Noto).
  **ROLLOUT GATE (until all three land, every install just stays on Noto):** (1) publish
  the glyph zip: `gh release create map-fonts <zip> --prerelease` (staged at
  `build/map-fonts.zip`, or rebuild with the script - and it joins the DO-NOT-DELETE infra
  releases); (2) merge so `fdroid-repo.yml` carries the unpack-to-Pages step; (3) an
  fdroid-repo.yml run deploys the site. Then MapFonts' probe starts passing and Roboto
  lights up on next app launch, no app release needed.
- **Two-finger tilt: shove detector widened** (maxShoveAngle 55, pixelDeltaThreshold 8) - the
  stock 20-degree parallel requirement made tilt nearly impossible. **Photo viewer:**
  double-tap zooms 2.5x at the tap point / back out (a tap-detector pointerInput layered
  before the custom pinch/dismiss loop, which never consumes bare taps).
- **"Also at this location" is ALIVE (`placesHere`/`othersAt`)** - it fills only when the
  selected place resolves alongside OTHER listings at the same address (strip malls, tapping
  an address). Rarely seeing it = data-dependent, not removed. "People also search for"
  (`similarPlaces`) is the different, related-places row.

- **Offline sizes are INSTALLED sizes (issue #214, 2026-07-23).** Region manifests carry
  `installedMb` (measured by the bake scripts: du of the unpacked graph dir / raw pack db);
  the app falls back to zip x 1.8 (graphs) / x 2.35 (packs, a large state measured 143->335 MB) for
  manifests baked before the field existed. The Settings row shows graph+pack SUMMED
  (`regionInstalledMb` in OfflineSettings), regions over 1 GB installed confirm via VelaDialog
  before downloading, and the Storage group at the bottom of Offline maps reads
  `MapViewModel.offlineStorageBreakdown()` (.mapbox db + overlays / graphs / poipacks /
  piper+asr) with `clearMapCache()` = MapLibre `clearAmbientCache` (saved areas untouched).
  **SPACE COMES BACK ONLY WHEN THE DATABASE IS PACKED (issue #601, 2026-09-21).** MapLibre's
  offline store is ONE SQLite file (`mbgl-offline.db` / `.mapbox`) holding saved areas and the
  browsing cache; deleting a region deletes rows, and SQLite keeps the file at its high-water mark
  until a VACUUM. A user who had saved and deleted a few big areas in the old tile-pack days saw
  5 GB of "map data" with every list empty. `OfflineMaps.packDatabase` (`OfflineManager.packDatabase`)
  now runs after every saved-area delete (the row's trash icon in Offline maps) and inside
  `clearMapCache()`. And there is finally one button that reaches everything: Offline maps >
  Storage > "Delete all offline data" (`MapViewModel.deleteAllOfflineData`, VelaDialog confirm
  with the total): every saved area, every store's installed ids, then a SWEEP of every file under
  `obf/ poipacks/ places/ basemap/ overlays/ roadfeatures/ graphs/` except the index files
  (an archive whose id left the catalog when a country was re-split is unreachable by any region
  row), the ambient cache, then pack. Building overlays had NO delete path at all before this:
  every area save pulled a ~200 MB state file that nothing ever removed. Voices/ASR are left alone
  (they have their own Remove). Both buttons are in the settings search now; "Clear map cache"
  never was.
  Old translated locales had the zip-size strings DELETED (orphans fail lint); the new
  installed-size keys are base-English until a translator fills them (Weblate is still a plan,
  see docs/LANGUAGES.md).

## Working on the scraper

- The `pb` request *grammar* (`PbBuilder`) and `PolylineCodec` are correct and
  stable. The **field numbers, response array indices, and session regexes are
  NOT** - they're marked `CALIBRATE:` and must be pinned from a live capture of
  `maps.google.com` (devtools/mitmproxy). Never trust a remembered `pb` layout.
- Turn the real source on with `VelaConfig.USE_GOOGLE_SOURCE = true` after
  calibrating. Parsers throw `CalibrationNeededException` (routine, non-fatal)
  when shapes drift; the UI surfaces it as a notice.
- **Never embed a static Google API key.** Per-user `GoogleSession` bootstrap
  only - that's what keeps the NewPipe legal footing.
- **Remote calibration (`calibration.json` at the repo root).** The `pb`/proto
  templates and endpoint URLs (search, directions, reviews, **photos** - 
  `photosEndpoint`/`photosProto` for the `hspqX` gallery RPC) are remotely
  updatable: `CalibrationStore` (in `:core`,
  `config/`) fetches `calibration.json` from the repo's raw URL at launch and
  adopts it when its `version` is higher than the bundled `Calibration.DEFAULT`,
  provided every endpoint host is on the allowlist (`www.google.com`/`google.com`).
  The bundle also carries the **language-keyword tables (v16, 2026-07-13)**: `transitCategoryWords`
  (the transit-category gate's regex terms - joined into one case-insensitive alternation, adopted
  by `MapViewModel.adoptKeywordTables` at init + after refresh, compiled regex as fallback) and
  `statusClosedWords`/`statusOpenWords` (per-language maps that REPLACE SearchParser's compiled
  open/closed tables when present - absent in the shipped json on purpose, the field support is
  the hot-fix path). These are the one part of the localized scrape that reads localized TEXT to
  decide something, so a wrong or missing word in a language nobody on the project speaks is a
  config edit + version bump + re-sign, not an app release (issue #71 was exactly this class of
  bug). The bundle also carries **`defaultVoiceId`** (String - the Piper voice a fresh install
  downloads + activates), **`defaultVoiceSpeaker`** (int - only tunes libritts_r's 904
  variants) and **`defaultVoiceSpeed`** (float - spoken-directions speed), so a favorite
  voice/speaker/pace can be pushed as everyone's default with a version bump + re-sign, no
  app release (a user's own `voice_model`/`voice_speaker`/`voice_speed` pick still wins).
  Shipped defaults (calibration **v13**): voice **HFC Female** (`en_US-hfc_female-medium`),
  speaker 14 (libritts only), speed **0.8×** (the user's preferred cadence; briefly 0.72 on 2026-07-06,
  reverted 2026-07-07) - matched in the compiled `Calibration.DEFAULT`
  + `VelaPiper.DEFAULT_VOICE_ID`. NB the neural voice lengthens pauses at periods by
  **splitting the utterance on sentence boundaries and splicing silence in-app**
  (`PiperSynth.splitSentences`/`joinWithGaps`) - sherpa-onnx's `silenceScale` config is
  a measured no-op on the Piper/VITS path, don't reach for it. **Every fragment gets terminal
  punctuation before synthesis (`PiperSynth`, 2026-07-07):** a bare-ending fragment ("turn left") gives
  the model no final prosody contour, so it trailed off and swallowed the last consonant - the real-drive
  "lef" instead of "left". A `;` is appended to any fragment ending in a letter/digit (the same
  semicolon-contour finding the user A/B'd on "You have arrived;"); punctuation is language-neutral, so
  it's safe for every Piper voice. Spoken text also runs through
  `SpeechText.spokenNumbers` in `EnNavStrings.expandForSpeech` - 3-digit **street ordinals** ("120th" →
  "one twenty eighth", **space not hyphen** - the hyphenated compound got a reduced/flapped "-ty" from
  the neural voice, "152nd" came out sounding like "one fifth second" in testing) are
  pre-expanded so the neural G2P doesn't mangle them into "one, hundred
  and 28th" (only 100–999; 1–2 digit + 4-digit+ are left for espeak). And `NavEngine` **does not
  announce the DEPART maneuver** - `NavSession.start` speaks it once ("Starting navigation. Head
  east on F St"); the engine skips it (it's at distance ≈ 0) and advances silently, else the opener
  gets clipped by a re-announced "head out".
  **Multiple downloadable voices (voice browser, 2026-07-03).** `VelaPiper` is no longer one hardcoded
  model - it's one engine (`ENGINE_ID = "vela.piper"`) that holds ANY of many Piper voices, each in its
  own `filesDir/piper/<id>/` dir (`<id>.onnx` + `tokens.txt` + `espeak-ng-data/`, the sherpa
  `vits-piper-<id>` archive layout). The **installed set is derived from the filesystem** (`installedVoiceIds`,
  keeps only complete dirs → a partial download self-heals), the pick persists in **`voice_model`**, and
  **speaker choice is per-voice** (`voice_speaker_<id>`; the legacy global `voice_speaker` is migrated onto
  libritts_r). The browsable catalog is `PiperCatalog` in `:core` (pure data, unit-tested, ~40 curated
  voices across the languages Piper covers (en_US/en_GB, the 10 original i18n languages, Mandarin; ja/he have NO Piper voice and ride the system-TTS fallback); URL = `…/tts-models/vits-piper-<id>.tar.bz2`). `PiperSynth.ensureLoaded` reloads when
  the selected voice changes; `PiperSynth.reloadVoice()` is the SINGLE switch trigger - it bumps the
  generation counter (aborting any in-flight utterance) then tears down + rebuilds on the same serial
  worker, so `tts` is never freed mid-`generate()`. `MapViewModel.migrateFlatLayoutIfNeeded` (first thing
  in `init`) relocates the old flat single-voice install in place (rename, copy-fallback, verify-gated,
  re-runnable) - never re-downloads. **Any large download (voice model, routing graph, building overlay)
  MUST NOT use the shared OkHttp client** - its `callTimeout(12s)` (scrape-bounding) aborts the body read
  mid-stream, `runCatching` eats it, and the asset SILENTLY never installs (this is exactly what hid the
  197 MB overlay for a whole debug cycle - no crash, no log, just no footprints). `KokoroInstaller`,
  `RoutingGraphStore`, `OverlayTileStore` **and `VoiceInstaller`** (the TTS-engine APK download - added
  2026-07-06 audit; a >12 s APK fetch silently fell back to the F-Droid web page) each derive a
  `downloadHttp` with `callTimeout(0)` + `readTimeout(60s)` for the body; only the tiny manifest/version
  fetch stays on the shared short-timeout client. `OverlayTileStore.download` is also serialized behind a
  `Mutex` (+ a first-line "already installed" re-check) so two callers for the same region can't interleave
  writes into the one `.tmp` (whose 7-byte magic check could then pass on a corrupt archive).
  Settings → Voice → **Voice library** is the browser; the
  multi-speaker variant picker (Advanced) only shows when the SELECTED catalog voice has >1 speaker.
  **To ship a pb/endpoint fix WITHOUT an app release:** edit the drifted field in
  `calibration.json`, **bump `version`**, **re-sign** (`./scripts/sign-calibration.sh`),
  commit `calibration.json` + `calibration.json.sig` to `main` - users pick it up on
  their next launch (raw.githubusercontent caches ~5 min). Keep the compiled
  `Calibration.DEFAULT`'s field VALUES (paths, endpoints, voice defaults) in sync with
  `calibration.json` when you cut a release - but `DEFAULT.version` intentionally STAYS `1` (the
  remote bundle's higher `version` must always win the adopt-if-newer check; the shipped
  `calibration.json` is at v13, `DEFAULT.version` at 1 - that gap is by design, not drift). **Phase 2 (done): the search parser's positional
  field-index paths are remote too** - the `paths` object in `calibration.json`
  (`name`, `address`, `rating`, `photos`, `featureId`, … as `[i,j,…]` arrays,
  relative to a result entry whose place node is `[1]`; `results`/`single` are
  root-relative). So a "Google moved field X to a new index" fix is also just an
  edit + version bump. **All three result-shape gates now follow `paths.name`** - `singleResultEntry`,
  `atThisPlaceEntries` and `findResultsArray` wrap the candidate as `[null, node]` and validate through
  `pathOf(paths,"name")` instead of a hard-coded `at(11)`, so a `paths.name` recalibration reaches the
  single-result / address-snap / fallback paths too (they used to silently keep dropping results at the
  old index). And the WebView details/popular-times path (`PopularTimesParser.parse`) threads the LIVE
  `cal.paths` through `SearchParser.parse`/`parsePopularTimes` rather than pinning `DEFAULT_PATHS` (audit 2026-07-06).
- **Directions response paths + review scrape levers are REMOTE too (2026-09-13).**
  `directionsPaths` in `calibration.json` merges over `Calibration.DEFAULT_DIRECTIONS_PATHS` one
  key at a time (keys: routes, geometries, summary, distance, typical, traffic, typicalLow,
  typicalHigh, start, end, summaryText, spans; `DirectionsParser.parse(root, paths)`, pinned by
  `DirectionsPathsTest`), so Google moving the in-traffic figure is a config edit. `reviewWords`
  ({"review": alternation, "more": alternation}) replaces `ReviewWords`' compiled patterns and
  `reviewSelectors` ({card, id, moreToggle, author, text, date}) replaces the compiled CSS class
  hooks in `WebReviewsFetcher` (the `SEL` object in the scrape script; defaults are the
  `DEFAULT_*_SEL` consts). Null / missing keys = compiled. Still compiled-only: the transit
  itinerary parser, the photo walk, the Street View parser, and the full-screen review page's
  carve (`ReviewsPanel`, its own script).
- **Fleet tuning dials (2026-07-18): `tuning` in `calibration.json`** - a flat name->number map
  read through `Calibration.tune(key, compiledDefault)`; a missing key means the compiled
  default and a non-numeric value is skipped, so old/new bundles and apps never break each
  other. Adding a dial is a config edit, not a schema change. Current dials: `browseZoom` /
  `browseZoomWide` / `browseZoomFocus` (the browse fly-to zooms, 15.5/14.5/16.5),
  `overlayCoverFrac` (the MS building-overlay hide threshold, 0.18), `ambientFanoutPermits`
  (parallel scrape parses, 4; read at construction so it applies on the next process start),
  `ambientCapMin`/`ambientCapMax` (the zoom-tiered on-screen POI cap, 45/140). View-layer
  consumers read `CalibrationStore.latest` (a static of the last verified bundle) since
  composables can't inject the store. Same edit+bump+re-sign flow as everything else.
- **Signed channel (mandatory).** The bundle is **ECDSA-P256/SHA-256 signed**
  (`calibration.json.sig`, detached, base64) and the app verifies it against the
  **public key pinned in `CalibrationStore.PINNED_PUBLIC_KEY`** before adopting - 
  so a repo/CDN compromise can't push config *or code* to devices. The private key
  lives at `~/.vela-signing/vela-calibration.key` (**never commit it**; the public
  half is safe to embed). `scripts/sign-calibration.sh` signs + self-verifies;
  `BundleSignature.verify` (`:core`) is the unit-tested verifier. A bundle that
  fails verification is ignored (app keeps the last-good config). An unsigned/older
  cached copy falls back to the compiled `DEFAULT` for one launch.
- **Notices.** `calibration.json` carries a `notices` array (`id`/`level`/`title`/
  `body`/`url`) shown as dismissable cards on the bare map; **level "urgent" (2026-07-10)
  renders as a MODAL VelaDialog instead** (OK dismisses; a `url` adds a Learn-more button)  - 
  for pushed announcements that must be seen. Cards for routine notes, urgent sparingly. (`MapViewModel.refreshNotices`,
  dismissed ids in `vela_notices` prefs) - push "search is down, fix coming" with no
  app update. Rides the same signed channel.
- **Phase 3 (done): remote parse *logic*** via `transformsJs` - a signed JS bundle
  run in a **Rhino sandbox** (`JsSandbox`, interpreted/`optimizationLevel=-1` for ART,
  `initSafeStandardObjects` so it can't reach Java/IO; a private `ContextFactory` arms Rhino's
  instruction observer as a **2 s wall-clock kill switch** - a runaway `while(true)` in a pushed
  `transforms.js` throws an `Error` (which JS can't `catch`) → the `runCatching` becomes the
  compiled-Kotlin fallback, so it can't hang search or, via `synchronized(this)`, wedge every later
  transform (audit 2026-07-06, unit-tested); `org.mozilla:rhino-runtime`,
  R8-keep in `core/consumer-rules.pro`). `JsTransforms` exposes two search hooks - 
  `parseSearch(rawResponse)` (full re-parse of a reshaped response) and
  `transformPlaces(placesJson)` (post-process) - over the flat `PlaceJson` contract;
  **compiled Kotlin is always the fallback** (no script / missing fn / any error →
  unchanged). So a *response-shape* change can be hot-fixed too, not just a moved
  field. Wired in `GoogleMapsDataSource.search`. Verified on-device (a pushed
  `transformPlaces` marked the first result; cleared after).

## Browser identity (`BrowserHeaders`, calibrated UA, 2026-09-14)

Two user agents, and mixing them up is the bug:

- **Google-facing** requests send the CALIBRATED browser UA plus the coherent Chrome header set
  (`Sec-CH-UA*`, `Sec-Fetch-*`) via `BrowserHeaders.browserHeaders` / `browserXhrHeaders`. Read it
  from `calibration.current().userAgent`, NEVER `VelaConfig.USER_AGENT` directly - that const is
  only the compiled fallback.
- **Community services** (FOSSGIS OSRM, Nominatim, Photon, Overpass, Transitous) send
  `VelaConfig.VELA_UA`, the honest contactable identifier their usage policies ask for. Never the
  browser string: they are free infrastructure Vela depends on, FOSSGIS already "transiently
  5xx/429/resets on mobile", and being an anonymous Chrome in their logs is the opposite of what
  earns headroom there. RouteGeometry was sending them the spoofed Chrome UA until this landed, and
  Overpass/Nominatim carried hardcoded "VelaMaps/0.1" copies long after the app shipped 0.4.

Gotchas:

- **The WebViews read the calibrated UA now (2026-09-22, `app/web/WebViewIdentity`), so a pushed
  `userAgent` is safe.** THE FINDING BEHIND IT, measured on the 4a with a header echo (a debug-only
  ContentProvider pointing a WebView at `adb reverse` 127.0.0.1:8099; the recipe is a few lines):
  a WebView whose `userAgentString` is overridden to desktop Chrome STILL sent
  `X-Requested-With: app.vela` on every request, the package name in the clear, plus its own
  client hints `sec-ch-ua: "Android WebView";v="153"`, `sec-ch-ua-mobile: ?1`,
  `sec-ch-ua-platform: "Android"`, under the Windows Chrome UA. Every reviews, photos, popular
  times, transit and stop-board page load carried that. `WebViewIdentity.apply(settings)` sets the
  calibrated UA and desktop `UserAgentMetadata` from `secChUa` (verified fixed on Vanadium 153),
  and TRIES the `X-Requested-With` allow list, which does nothing anywhere: Chromium abandoned the
  header's removal after the origin trial, marks the androidx API disabled, and its tests assert
  the package name is sent on every request from every WebView. That header is the one
  deterministic Vela identifier in Google-facing traffic and it is confined to the WebView-backed
  features; SPEC 3.6 has the reasoning and why the document-only half-measure is not taken.
  Every new WebView calls `WebViewIdentity.apply`; never set `userAgentString` by hand again. Two more from the same pass: the compiled UA said Chrome 154
  while Chrome's Windows stable was 153 (a browser that did not exist yet; check chromiumdash
  before bumping), and google.com's `Accept-CH` asks for `Downlink` and `RTT`, which Chrome then
  sends on every later request, so the XHR header set carries both. Probe recipe and residuals
  (X-Client-Data, two cookie jars, TLS) are in SPEC 3.6.
- **`secChUa` major version must match `userAgent`.** Separate fields pushed together for exactly
  that reason; a hint advertising a different version than the UA string is worse than sending no
  hint at all. `BrowserHeadersTest` locks the compiled pair so a careless bump of one is caught.
- **Both are sanitized on parse** (`BrowserHeaders.sanitize`). OkHttp throws on a control character
  at request-BUILD time, inside `runCatching` blocks that swallow it - one stray newline in a pushed
  bundle would kill every scrape with no crash and no log, the same silent-failure class as the 12 s
  `callTimeout` hiding the 197 MB overlay download. Surrounding whitespace is TRIMMED (a trailing
  newline in hand-edited JSON is recovered, not rejected); interior control characters and any
  non-ASCII are rejected and fall back to the compiled default.
- **The desktop UA is load-bearing.** A mobile string would match the Android TLS stack and carrier
  IP better, but mobile web Maps serves DIFFERENT markup and endpoints - every parser is calibrated
  against desktop, so that swap is a recalibration, not a header edit. The `Sec-CH-UA-Mobile: ?0`
  and `"Windows"` platform hints track the desktop default and must move with it.
- **Why calibrate at all:** Chrome ships stable every ~4 weeks, so a compiled constant is stale by
  construction - the shipped UA sat at Chrome 124 (April 2024) well into 2026. Stale is a
  CORRECTNESS risk before a fingerprinting one: Google serves different response shapes to different
  browser generations, so an old UA can pin the scrape to a legacy code path that gets retired with
  no warning, arriving as indistinguishable-from-ordinary calibration drift.
- **Do not chase the TLS fingerprint.** Matching Chrome JA3/JA4 and HTTP/2 frame ordering needs a
  custom TLS stack: permanent maintenance, native deps, and trouble for reproducible F-Droid builds.
  Vela's defense is diffusion (every user on their own carrier IP, nothing central to block), not
  disguise. A current, coherent UA is ordinary client hygiene; a bespoke TLS stack is an arms race
  on a solo budget, and a worse posture if it ever mattered.

## Degoogled constraints (hard rules)

- Location: AOSP `LocationManager` only - never `FusedLocationProviderClient`. **Fix discipline
  (2026-07-04 audit, don't regress):** NETWORK (BeaconDB) fixes are DROPPED during nav and used in
  browse only when GPS has been quiet ≥12 s (`NETWORK_FIX_QUIET_MS`, OsmAnd's `useOnlyGPS` pattern) - 
  they're 100-1000 m off and teleported the dot/reroutes; inter-fix `dt` comes from
  `loc.elapsedRealtimeNanos` (monotonic - `loc.time` mixes GNSS UTC with the network system clock and
  a negative dt bypassed the outlier gate); fixes with accuracy >50 m never feed `NavSession`; the
  `minDistanceM=0f` registration MUST stay 0 (a distance filter starves fixes at a standstill - the
  frozen-speedo/creeping-puck bug). Measured speeds pass a SYMMETRIC accel-bounded gate against the
  last ACCEPTED value (`gateMeasuredSpeed`, 2-fix persistence escape, shared with replay) - one-sided
  spike filters self-latch (a down-glitch to 0 then rejects every real speed as an up-spike forever).
- **Avoids reach the nav session (2026-09-16).** `RoutingPrefs.avoidTolls/Highways/Ferries` mirror
  the chooser's sticky toggles (seeded in VelaApp, kept in step by `MapViewModel.syncRoutingAvoid`),
  and NavSession passes them on every fetch it makes itself (reroute, recheck, added stop,
  `nameRoute`). Before this those calls used the defaults, so a reroute on a drive planned with an
  avoid could route straight back through it. The offline car profile's highway flag is
  `avoid_motorway` (confirmed in the vendored routing.xml: `avoid_highway` is the horse-riding
  profile's), so offline "Avoid highways" never worked until the same fix.
- **Avoid tolls / avoid highways (2026-07-11):** two sticky FilterChips in the route
  chooser (DRIVE only; prefs `avoid_tolls`/`avoid_highways`, seeded in routeToSelected like
  the sticky mode). **2026-08-08 (Reddit reports "just sat there" / "still routed through the
  motorway"):** the on-device avoid attempt is BOUNDED (`AVOID_ONDEVICE_TIMEOUT_MS` 4 s, an
  UNSTRUCTURED async on purpose - a structured child would pin coroutineScope open until the
  non-cancellable native compute ends and defeat the timeout; the orphan finishes and is
  discarded) because the obf engine can spend many seconds on a long route; and routes the
  online chain produced while a toggle was on carry `Route.avoidNotHonored` (set in both the
  single-dest and multi-stop paths, never on the on-device result; since 2026-09-21 a multi-stop
  result that follows Google's avoiding course through the stops, or IS Google's route, is
  honored and carries no note) - DirectionsPanel shows
  `place_avoid_not_honored` under the chips when EVERY route carries it, so a toggled avoid is
  never silently ignored.
  **RE-PROBED 2026-08-24 (issue #286, a new user: "Avoid tolls doesn't appear to do anything"):
  the public FOSSGIS OSRM STILL rejects `exclude=` for EVERY value** (`toll`, `motorway`, `ferry`
  all return `InvalidValue: Exclude flag combination is not supported`). At the time Google's
  keyless endpoint was believed to have no avoid parameter; that was WRONG (see the keyless
  avoid paragraph under "Directions", 2026-09-06: the flags ride in the `!6m` feature block and
  Google's own route honors them). So online avoid is now Google-honored + OSRM-snapped, and
  the note is rare. Back on 2026-08-24 it was the entire user-facing answer, so it was upgraded
  from dim `bodySmall` under the chips (it read as decoration; the reporter never registered it)
  **Wording fixed 2026-09-13:** the note said avoiding "works on downloaded areas only", which
  stopped being true when the keyless Google avoid landed; today it shows mainly on trips WITH
  STOPS (the via route through the open router cannot exclude) and says exactly that. The
  endpoints card's stop rows carry Google's double-dot drag handle (issue #405) as the door to
  the stops editor, and the layers button hides while the route chooser is open.
  to an info-glyph row at `bodyMedium` in ink, worded to say what to DO (download the area) rather
  than only what went wrong.
  PLUMBING: `MapDataSource.directions`/`nameRoute` + `RouteEngine.route`
  carry the flags end to end. The public FOSSGIS OSRM REJECTS `exclude=` (probed 2026-07-11:
  InvalidValue - its profiles lack excludable classes; routeOsrm bails on any 4xx instead
  of retrying, AND `OSRM_SUPPORTS_EXCLUDE=false` keeps the param OFF entirely - sending it
  400'd the whole request and lost the clean named-turn route while a chip was on, a worse
  route than just not honoring avoid online; flip the const on a self-hosted OSRM), so the AUTHORITATIVE avoid router
  offline is the on-device obf engine: dynamic routing.xml params (`avoid_toll` / `avoid_highway`), no
  baked profiles. directions() tries the on-device avoid route FIRST when a toggle is on; an engine that
  cannot honor it returns EMPTY (never silently routes through a toll) and the online chain falls back to a
  NORMAL route. (The GraphHopper CH avoid profiles and the v2 graph generation that did this from
  2026-07-11 were retired on 2026-09-15.) Device-verified on the graphs then: Dover-Smyrna with Avoid tolls
  swung off the DE-1 toll road onto the free route, single on-device route, no live-traffic tag.
- Nav guidance discipline (2026-07-04 audit): prompt/turn-now distances SCALE WITH SPEED in
  `NavEngine` (max(fixed, v×T), T=35/10 s since 2026-07-17; `spoken` stores band SLOTS not meters), one prompt per update speaking
  the TRUE distance, REPEATS TRIMMED (2026-07-17: a step's non-first prompts speak
  `NavStrings.repeatShort` - EN drops the " toward ..." sign tail, other languages default to
  full until they override - and a MERGE skips the far band entirely; a long on-ramp narrated
  the same road 3-4 times in declining counts, real-drive report), silent catch-up past maneuvers >75 m behind, proximity arrival (crow ≤40 m) +
  no rerouting within 150 m of the destination or while stationary (EXCEPT a FAR deviation:
  `FAR_OFF_M` 90 m counts at ANY speed since 2026-07-14 - parking-lot creep sits under the 2 m/s
  moving floor forever and the reroute/redrawn line never came; since 2026-07-15 a moving fix
  past FAR_OFF_M also counts DOUBLE, and `OFF_ROUTE_M` is 40 m / `OFF_ROUTE_HITS` 3 (were 45/4) -
  the user's wrong turns rerouted too slowly, and the corridor width plus the debounce were the
  lag; don't loosen these back without a false-reroute report. The off-route corridor + far distance
  are ACCURACY-SCALED and mode-relative since 2026-07-15: `NavEngine.update` takes `offRouteM`/`farOffM`
  params (defaulting to the flat 40/90 so tests + `NavReplay` are unchanged), and `NavSession` computes
  them per fix via the pure `NavEngine.offRouteCorridor(mode, accuracyM)` / `farOffDistance(mode, off)`.
  The corridor = `base + K*accuracy` clamped per mode (OsmAnd-style: tight on a clean fix, wide on a
  noisy one so urban-canyon multipath can't false-reroute), foot < bike < drive because the PATH is
  narrow. Driving self-tightens below the old flat 40 m when GPS is clean - which is the "40 is too
  high" the user hit - without inviting false reroutes when GPS degrades. `MapViewModel` feeds
  `loc.accuracy` (null → a typical-GPS default; the dead-reckoning path passes null). Don't reintroduce
  a fixed sub-30 m distance - it was rejected as extreme (2026-07-15), the whole point is it scales.
  HEADING TERM (2026-07-16): distance alone can't catch a wrong turn onto a road that runs INSIDE the
  corridor of the planned one - the projection kept snapping the driver onto the OLD route, guidance
  carried on with its turns, no reroute, no redrawn line (the "not even rerouting" report). A moving
  fix coursing >HEADING_OFF_DEG (60) against the route's local bearing counts as an off-route hit even
  inside the corridor (`bearingDeg` rides onLocation -> update; null in replays/tests = unchanged), and
  a heading-diverged fix never counts toward onRouteStreak (else back-on-course would discard the
  legit wrong-way reroute mid-fetch). The 3-hit debounce absorbs turn transients/lane changes; a
  heading-off fix that is ALSO a quarter-corridor (10 m) off the line counts DOUBLE while moving,
  so a deliberate left-instead-of-straight reroutes on the 2nd fix after the turn (real drive
  2026-09-07, `NavEngineWrongTurnTest`); a wide legit turn stays within a few meters of the corner
  and keeps counting single. THE PUCK has its own wrong-turn rule in VelaMapView's fix block: the
  engaged snap already rejects a fix whose course is 55°+ off the segment, but that rejection used
  to be a plain "miss", so the arrow dead-reckoned straight on along the old line for 3 s and then
  froze at the corner. A miss that succeeds when re-run WITHOUT the heading gate is a heading miss:
  it sets `holdReckon` (the ticker treats the reckoning clock as expired, so the arrow stops) and
  two of them disengage to the raw fix; a distance miss keeps the 3-miss spike tolerance),
  off-route measured on the
  windowed/anchored projection (never whole-polyline min), reroutes are single-flight + cooldown +
  latch-clear-on-failure (a failed fetch must NOT kill rerouting - the event is edge-triggered), and
  since 2026-07-21 the fetch has a HARD 20 s deadline (REROUTE_FETCH_TIMEOUT_MS): the retry
  ladders inside directions() could hold the single-flight latch for a minute on a flaky cell
  link, silently dropping every new RerouteNeeded (a real-drive hang); past the deadline it
  fails into the same retry-while-deviated path so the next fix fires a fresh request. And
  **The reroute fetch is UNSTRUCTURED and the single-flight guard is TIME-BOUNDED (issue #258,
  2026-08-15).** `withTimeoutOrNull` only interrupts at suspension points, so a fetch wedged in
  non-cancellable work (a socket read that never returns, the offline engine's native compute)
  outlived its own deadline and kept `rerouteJob` active - and the guard then rejected EVERY later
  reroute for the rest of the drive: "Re-routing" on screen forever, cleared only by ending and
  restarting nav (real-drive report, dangerous precisely because the workaround needs the phone in
  your hand). Two fixes, both required: the fetch runs as a `CoroutineScope(IO).async` the deadline
  can ABANDON (the orphan finishes into the void - same trap and same shape as
  `AVOID_ONDEVICE_TIMEOUT_MS`), and `NavSession.rerouteGate` (pure, unit-tested in
  `RerouteGateTest`) declares a job dead past deadline + `REROUTE_STUCK_GRACE_MS` so single-flight
  can never be permanent. **Never gate rerouting on job liveness alone.** **SECOND cause, same
  symptom (2026-08-16):** a mid-drive reroute is `urgent`, i.e. a SINGLE-SHOT fetch with no retry
  ladder - correct, because the full ladder outlived the deadline on a weak link (#185/#236) - but
  a single shot on a genuinely flaky link fails over and over and NOTHING escalated, so the driver
  sat on "Re-routing" through attempt after attempt while ending nav and starting again worked
  first time (a fresh plan is not urgent and gets the 3-try ladder). That workaround was the clue.
  Attempts now start lean and ESCALATE: `NavSession.rerouteAttempt(failStreak)` (pure, tested)
  keeps the first `REROUTE_ESCALATE_AFTER` attempts urgent, then switches to the full ladder with
  the longer `REROUTE_LADDER_TIMEOUT_MS`; the streak resets on any adopted route and on session
  start/stop, so one drive's bad coverage does not send the next drive's first reroute down the
  slow path. `rerouteGate` therefore takes the attempt's OWN deadline - judging an escalated
  attempt by the lean one would declare a healthy fetch wedged and kill it just before it
  succeeded, the original bug wearing a new hat.
  **THE SESSION NAMES A PROVISIONAL ROUTE BEFORE DRIVING IT (real drive 2026-09-13).** A
  `directions()` reply is sorted by ETA, and a Google alternate can lead it; those are PROVISIONAL
  (Google's polyline + ETA, Google's abbreviated steps with positions guessed along the line by
  cumulative step length). The picker names one on pick, but the three fetches NavSession makes
  for itself (add-stop reroute, the 2-minute recheck, the off-route reroute) took `firstOrNull()`
  raw: a 17.9 km "faster" route arrived as ONE maneuver, "Take exit 176" typed MERGE, sitting at
  the on-ramp the car was on, was announced "in 30 feet", the engine then thought the route was
  done, and the reroute after it did the same with "Turn left onto the ramp". All three now go
  through `NavSession.driveable`: a provisional top is `nameRoute`d; if naming fails (tagged
  abbreviatedSteps) a full-stepped open-router route from the same reply is preferred even when
  slower. Diagnosed by replaying the shared trip with the new on-demand harness
  `probeTripSegmentRoute` (`-DvelaTrip=<csv> -DvelaSeg=<n>`: re-runs the open router from a
  segment's recorded start through vias off its recorded polyline and prints every maneuver with
  where it resolved; `velaSeg` is forwarded by core/build.gradle.kts like `velaTrip`). Second
  finding from the same replay, NOT fixed: a start point ON an on-ramp snaps to the surface street
  under it on OSRM, with or without the bearing hint, so a recheck fetched from a ramp routes the
  first kilometers over local streets; Google snaps it right, which is why its alternate led.
  **A REROUTE PINS ITS DEPARTURE HEADING (real-drive report 2026-08-17: "it keeps rerouting me the
  way I was going before").** After a wrong turn, an unconstrained reroute is perfectly entitled to
  answer "U-turn and rejoin" - from a point a few tens of meters down the wrong road, going back
  often IS the fastest path - so the driver is told to turn around, carries on anyway, and is told
  to turn around again. `RouteGeometry.departBearingParam` sends OSRM `bearings=<heading>,65` for
  the FIRST waypoint only (every later waypoint gets an empty entry; the count MUST match the
  coordinates or OSRM rejects the whole request and the reroute dies with it), so the router answers
  "given that I am going this way, what now" - which is what Google does. Threaded
  NavSession.onLocation's `bearingDeg` -> reroute -> `MapDataSource.directions(departBearingDeg=)`.
  **Planning fetches send NOTHING**: which way a parked car happens to face is not a routing
  constraint. Null heading (stationary, or a fix without one) also sends nothing - a stale heading
  is worse than none. Unit-tested on the STRING (`DepartBearingTest`) because a malformed parameter
  is not an error: OSRM ignores it and routes as before, so the fix would silently do nothing.
  Note the related gate this does NOT change: off-route detection needs `movingFloorMps` = 2.0, so
  inching away from a junction is not counted as deviating until the 90 m far-off rule fires.
  **An urgent fetch waits at most `URGENT_GOOGLE_GRACE_MS` (2.5 s) for Google once OSRM has answered
  (issue #397, 2026-09-15):** a diagnostics export showed reroutes taking 18 to 40 s during a
  data dropout because the fetch waited out Google's empty replies and their backoff while OSRM
  had a route in seconds; Google now runs on an unstructured scope for urgent fetches (a
  structured child would hold the scope until its blocking HTTP call returned) and past the grace
  the route goes out trafficless, which the recheck's trafficUpgrade heals. Both the
  single-destination and the multi-stop branch do this.
  **A REROUTE CARRIES ITS DEADLINE INTO THE FETCH (issues #557 / #258, 2026-09-17).** A shared
  diagnostics export (drive, cellular, validated link) had two urgent attempts end in
  "reroute FAILED" at exactly 20 s each with no DRIVE line and no "google not back" line (so the
  car open router never answered, while a WALK fetch to the same host answered in the same
  window), then the escalated attempt, and the next DRIVE line 105 s later. What the code did:
  the urgent OSRM call ran on the shared client (12 s call, 15 s connect, 20 s read), and only
  AFTER it came back empty did the fetch wait, unbounded, on Google and then run the obf engine
  unbounded; the escalated attempt's three OSRM tries alone could take 36.6 s of its 40 s. What
  the 105 s was: the export has no "FAILED (streak 3)" line, so the escalated attempt never
  reached its deadline; `stop()`/`start()` cancel the job SILENTLY, and the 105 s DRIVE line has
  the same 18 steps as the `nav start` 25 s after it and is followed by the chooser's WALK and
  BICYCLE prefetch, i.e. the driver ended nav inside those 40 s and the 105 s is the manual
  replan running the full planning ladder against the stalled router. Two further silent-cancel
  facts from reading the export: a canceled orphan dies at its next suspension point without its
  DRIVE line, and `stop()` logged nothing. Now: `MapDataSource.directions(budgetMs=)` (null =
  planning, unchanged) and `core/data/RouteBudget` carry the deadline; NavSession passes
  `RerouteAttempt.budgetMs` (deadline minus `REROUTE_FINISH_RESERVE_MS` 4 s). Urgent: one OSRM
  call with `URGENT_OSRM_TIMEOUT_MS` (6 s, connect+read+call). Escalated: 3 tries at
  `LADDER_OSRM_TRY_MS` (8 s) inside `LADDER_OSRM_SHARE` (55%) of the budget, never starting a try
  with under `RouteBudget.MIN_TRY_MS` left; Google waited up to the budget minus
  `LADDER_SNAP_RESERVE_MS`, the snap inside the budget. Google is UNSTRUCTURED for every bounded
  fetch. When the open router gives nothing, `RerouteFallback.pick` (unit-tested) returns (a)
  Google's route from the same fetch if it is already back, else races Google against (b) the obf
  engine (unstructured, same trap as `AVOID_ONDEVICE_TIMEOUT_MS`) inside what is left, first
  non-empty wins, (c) nothing. Google fallbacks go out tagged `GOOGLE_ABBREVIATED` (the recheck
  heal upgrades them); the avoid flags ride every path; the heading reaches OSRM and now the obf
  engine too (`RouteEngine.route(departBearingDeg=)` -> OsmAnd `RoutingConfiguration.initialDirection`
  in compass radians, the convention checked in the vendored bytecode; Google has no heading
  parameter). Naming a provisional top runs inside the attempt's remaining time and falls back to
  the reply's own open-router route. Logged: `directions` "urgent|ladder: ... open router gave
  nothing after N ms (why); fallback google_ready|google|on_device|none K route(s) after M ms
  more", `nav` "reroute adopted: <source> in N ms", "reroute FAILED (streak s, deadline|nothing
  usable after N ms)", "nav ended [with a reroute in flight for N ms]". **Several reroutes close
  together:** RerouteNeeded is edge-triggered, and a request the COOLDOWN turned away used to
  leave the latch set, so a driver already off a route adopted seconds earlier (typically one
  computed from where the car was when the fetch started) was never rerouted until back on the
  line. `NavSession.rerouteSkipRetries` now clears the latch on a cooldown skip, and a failed
  user-ordered stops replan clears it too; `RerouteGateTest` simulates the drive fix by fix
  (cooldown retry, a router hung forever, late adoptions) and pins that the gap between attempts
  never exceeds a deadline plus a few fixes. Unverified: the obf heading convention on a device.
  And
  since 2026-08-04 the reroute fetch is URGENT (`directions(urgent = true)`, issues #185/#236):
  single-shot OSRM + Google (no 3x ladders), no divergence snap - the full planning ladder
  regularly outlived the deadline on a weak link, so the timeout canceled fetches that were
  about to succeed (the reporter's "context canceled then 200 OK" logcat) and the driver sat
  unrerouted through repeated 20 s attempts. A lean route lands in seconds; maybeRecheck's
  stepsUpgrade/trafficUpgrade heal restores quality minutes later. Planning fetches keep the
  full ladder + snap, and
  ETA sums the remaining STEP durations × traffic ratio (never remaining/avg-speed), and since
  2026-07-14 that ratio is LIVE-CALIBRATED: the ~2-min recheck's candidate, when it follows the
  CURRENT course (`RouteGeometry.divergent` under `SAME_COURSE_M` = 250 m), resets `etaScale`
  (NavSession, multiplicative, clamped 0.5-2.5, applied at the publish site only - the engine's
  own value stays pristine; reset to 1.0 on every route swap) so the shown arrival time follows
  evolving traffic instead of the ratio frozen at the last route fetch. **TUNNEL DEAD RECKONING
  (2026-07-14, `MapViewModel.tunnelDeadReckonLoop`):** the engine only advances on fixes, so a
  GPS outage froze the whole stack; when the guidance feed goes quiet >3.5 s while navigating,
  on-route, not replaying and not from a standstill, the VM synthesizes 1 Hz fixes ALONG the
  route at the last speed (decay tau 60 s, floor 1.5 m/s, cap 3 km) through the NORMAL
  `navSession.onLocation` path - puck/banner/voice keep working, `navStarved` keeps the
  "Searching for GPS" chip up for honesty, the first real fix re-anchors (route-plausible
  synthetics pass the outlier gate). Never feeds `tripStore.record` (no fake points in trips).
  **Route bar (`RouteBarStrip`, 2026-09-04):** badges for CAMERA / RAIL_CROSSING / SPEED_HUMP, dots for
  SIGNAL / STOP, an arrow marker with `remainingMeters` and a top cap with `model.spanM`; the Layout
  centers every child on the track by its window fraction (`RouteBarSpan`). Labels are `requiredWidth`
  wider than the strip so "768.8 mi" does not clip. The road-name pill in BAR mode caps its width at
  screenWidth - 176 dp so it can never reach the speed-limit sign or the FAB column.
  **Road label placement (`RoadLabel`, pref `road_label`: bar|puck|off, default bar) and
  `PreferButtons` (pref `prefer_buttons`, default off; `showListButton = PreferButtons.on || dpadFirst`)
  live in `app/ui/NavChrome.kt` (2026-09-04). The bar's chevron handle is a focusable clickable Box
  with `dpadHighlight`, so it is a key target on its own; the list button is the belt-and-braces one.
  **Nav bottom bar = drag handle for the step sheet (2026-09-04):** `NavControls` carries a vertical
  drag (lift follows the finger up to NAV_BAR_LIFT_MAX_DP; commit past NAV_BAR_LIFT_COMMIT_DP or an
  upward fling faster than NAV_BAR_FLING_PX_S, else spring back); commit = the same `openSteps` the
  list button calls, and `StepsSheet` animates in from its own height (`enter`). The button stays as
  the key path; the gesture is touch-only on purpose (docs/dpad.md). **ONE CONTINUOUS SHEET (2026-09-13, verified frame by frame on the 4a; supersedes the
  first cut the same day, which grew the bar blank and slid a separate list in):** Google's nav
  sheet is one surface, the ETA row staying as its header while the list shows under it as you
  drag, and Vela's is now the same thing made of two composables that meet pixel for pixel.
  `NavBarTop` (chevron handle + End | figures | list) is drawn by `NavControls` AND as
  `StepsSheet(header = ...)` during nav (chevron pointing down, closes). Dragging the bar opens a
  WELL under the figures (a `layout` modifier sized to the lift, clipped, read in the layout
  phase) holding the first rows through the shared `StepRow`, at the sheet's own list padding,
  capped at the preview's natural height so the card never stands taller than the sheet. A
  committing drag hands the lift over as `enterFromPx`; the nav-form sheet never slides: its
  list is the same well, `natural - drag - enterPx` tall, `enterPx` starting at 1e9 (first frame
  = closed = the bar) and, once the list is measured (`snapshotFlow` in a ONE-SHOT effect; an
  effect keyed on the measured height got canceled by a re-measure and froze the well shut),
  snapping to `natural - lift` and easing to 0. Swipe-down shrinks the well with the finger;
  every close (X-chevron, swipe, BACK via `closeTick`) animates the well to 0 THEN flips the
  state, so the bar reappears under an identical header. The nav-form card keeps the bar's
  floating geometry (28dp corners, the host's 16dp margins + landscape column). The directions
  PREVIEW sheet (no header) keeps its old slide-in/out. Verify with a screenrecord + a frame
  contact sheet, not by feel. **The list may grow to just under the turn banner** (`stepsListMax`
  in MapScreen = screen minus the banner's measured bottom minus ~150dp for the header and
  margins; Google's fills the screen, keeping the next turn visible is worth the strip), shared
  by the bar's drag cap (`NavControls.maxLift`) and the sheet (`StepsSheet.maxListHeight`); the
  FAB stack and the speed widget hide while the list is open, since they key off the bar's
  measured height and would ride up onto the banner.
  **The nav list LANDS ON THE CURRENT STEP (2026-09-16).** `StepsSheet(currentStep = nav.stepIndex)`
  orders the items as passed steps, then `NavStopsRow` (it lists the stops AHEAD, so it sits at the
  boundary), then the current step onward, and opens with `initialFirstVisibleItemIndex` on that
  boundary. Passed steps (and a `StopDividerRow` in front of one) render with `passed = true`: dim
  ink, sign chips / lanes faded. A LazyList clamps its scroll when the rows below the landing item
  are shorter than the viewport, which would open on passed steps near the end of every trip, so
  the nav form ends in a `tail` spacer sized in the well's layout pass to viewport minus the rows
  ahead (both read from `layoutInfo`); the well's natural height subtracts that blank, so the card
  still hugs the rows ahead and the list cannot scroll into it. The bar's drag well draws the same
  rows through `NavStepsPreview` (stops row, current step onward, dividers included) so the handover
  still moves nothing. A body swipe-down now scrolls back through passed steps first; the header
  closes from anywhere. The preview form (no `currentStep`) is unchanged. D-pad focus goes to the
  current step's row.
  **Trail OFF is drawn by the cut piece over a CLEARED ahead line (2026-09-07).** An overlay line
  cannot erase what is under it, so the first trail-off cut (2026-09-06) rode the AHEAD line's own
  gradient - and that line's 256 texels span the 3 km window, 12 m each: on a real drive the blue
  vanished in 12 m chunks with a dithered edge a texel ahead of the arrow. Now the ahead line's
  gradient is transparent up to a texel BEFORE the cut piece's END (`pa` from `cutEnd`, recomputed
  on each slide), so nothing sits under the piece, and the piece's per-frame gradient (1.6 m texels)
  paints transparent before the arrow and color after - the same per-frame path as the trail-on
  gray cut, only the "driven" color differs. Do not move the per-frame cut back onto the ahead line.
  **Spur rule v2 (2026-09-06, from the reporter's trip log):** the real appendix was a 121 m
  turn-right / U-turn / turn-right stub 56 m off a state route; the car never came within 47 m of
  its tip. The v1 rule missed it (a side street leaving at an angle projects a little further along
  the course every vertex, and the 15 m "advance" step reset the stretch each time). Now a stretch
  resets only on NORMAL progress (>=80% of distance traveled) and flags at >=80 m traveled with
  <45% progress. Validated in Python against the actual route geometry before porting.
  **Via-route spur guard, the SHAPE test (2026-09-06, same day, after the reporter said the appendix
  hung off a motorway with no turn for miles):** a via that lands on an OFF-RAMP snaps a few meters
  and adds only a ramp pair, so the snap-distance and length guards below miss it. `hasSpur(route,
  course)` projects the via route onto Google's line (windowed) and flags any >=250 m stretch that
  advances <35% of the distance traveled; the first/last 300 m are exempt. Applied to every via
  route in `directions()`; unit-tested with an out-and-back appendix and a ramp-shaped loop.
  **Via-route spur guard (2026-09-06):** `routeOsrm` refuses a via route when any interior via snapped
  >40 m (`VIA_SNAP_MAX_M`, from OSRM's `waypoints[].distance`) and `snapReaches` also requires the via
  route to be no longer than Google's course x1.05 + 400 m. Either symptom is a sampled point that
  landed on a frontage road or ramp; the plain route is used instead.
  **Driven trail is a setting (`RouteTrail`, `route_trail`, default OFF = hidden, 2026-09-03):** the
  ticker reads it per frame through `trailHolder`; off means `routeGradient(..., driven = TRANSPARENT)`
  on the ahead and cut pieces and `ROUTE_LAYER` hidden, on means gray. A flip sets `splitReset` so the
  next frame re-applies everything. Paint only; never touch geometry for this.
  **AVOID TOLLS / HIGHWAYS ARE KEYLESS ON GOOGLE (2026-09-06; the July "cannot" note was wrong).**
  The flags are in the `!6m` feature block's `!2m` submessage of the `/maps/preview/directions` pb:
  `!1b1` = avoid highways, `!2b1` = avoid tolls, group counts +1 each (`DirectionsPb.withAvoid`,
  pattern-based so a recalibrated template survives). Found by capturing Google's own web client
  (browser pane, "Avoid highways" ticked) - NOT in the `!20m` route-options group, where every scalar
  field was probed to no effect. Verified live: Davis-Sacramento I-80 15.3 mi/21 min -> Old River Rd
  27.1 mi/46 min; a Chicago tollway pair loses every toll mention. Pipeline: with avoid on, gTop IS
  the avoiding route; the plain OSRM route diverges, the via-snap follows Google's course with named
  turns, the ETA-margin gate is skipped (`avoidWanted`), the unrestricted OSRM routes are not offered
  as alternates, and if the snap fails Google's abbreviated steps win over a plain route. The FOSSGIS
  server still has no `exclude=` (`OSRM_SUPPORTS_EXCLUDE = false`); the on-device engine is the avoid
  router ONLY when Google is unreachable, and the "may still use tolls" note shows only then.
  **AVOID FERRIES rides the same block (issue #546, 2026-09-16):** it is `!7b`, a DIRECT child of
  that outer `!6m` (not of `!2m`), sent `!7b1` ticked and `!7b0` unticked by Google's web client
  (captured `...!6m31!32i1...!302i300!303i100!7b1!10b1!12b1...`). Replayed Galveston -> Crystal
  Beach, TX: `!7b0` = the 16.7 mi ferry route, `!7b1` = a 116 mi road route. The shipped template
  has no `!7b`, so `withAvoid` walks the outer block's direct children (counting `m` descendants)
  and inserts `!7b1` before the first child numbered above 7 (`!10b1`), outer count +1; an existing
  `!7b` is rewritten in place to the wanted value, and a `!7b` nested deeper is never touched
  (`DirectionsPbFerryTest`, every tolls/highways/ferries combination with and without a `!7b`).
  Plumbing is a third flag beside the other two everywhere (pref `avoid_ferries`, `avoidFerries` in
  `directions`/`nameRoute`/`RouteEngine.route`, `avoidWanted`, the mode-ETA key, the note, the
  third chip in both choosers), and offline the obf car profile takes `avoid_ferries` (the id in the
  vendored `net/osmand/router/routing.xml`, DRIVE only). FOSSGIS OSRM cannot exclude ferries, so
  as with tolls the Google route leads and the plain OSRM routes are not offered while it is on.
  NB the obf highway param Vela sends is `avoid_highway`, but that routing.xml declares only
  `avoid_motorway`; offline avoid-highways looks like a no-op until that is checked on a device.
  Device-checked on a downtown-to-suburb drive: the interstate route gave way to a state-highway
  one, ~14 min longer, with a live-traffic ETA on both.
  **THE CATALOG COVERS EVERY GEOFABRIK COUNTRY (2026-09-12, 425 rows):** 131 rows added in one
  pass (every country-level extract Geofabrik publishes that was missing: 49 in Africa, 29 in
  Asia, 21 in Oceania, 14 in Europe, the Caribbean, Greenland, DC / Puerto Rico / USVI, plus
  Russia's 8 federal districts as `russia-sub`; skipped only the aggregates `united-kingdom`,
  `sea` and whole `russia`). `big:true` from a HEAD sweep at 450 MB. Ten whole-country/state rows
  carry `skip_obf:true` (california, italy, germany, france, great-britain, spain, japan, india,
  indonesia, brazil): they OOM the obf bake even filtered, their sub-area rows cover them, and
  obf-regions.yml's selector drops them; routing-graphs/poi-packs still build them. China joined the list 2026-09-12 (1.5 GB, OOM at 12g). **Geofabrik DOES cut China into 33
  sub-extracts now (checked 2026-09-21, issue #599): every province plus Beijing, Shanghai,
  Tianjin, Chongqing, Hong Kong and Macau, the largest 164 MB**, so `china-sub` (ids
  `china-<slug>`, names "<Local> (China)", 458 catalog rows in all) bakes like `germany-sub` and the
  whole-country row keeps `skip_obf`. Hong Kong and Macau are their own rows AND inside Guangdong's
  extract; the polygon pick's smallest-box tie-break gives a Hong Kong point the Hong Kong row.
  The earlier "no China sub-extracts" note here was wrong or out of date, and it cost Hong Kong
  users every offline feature for a week. The list of
  what Geofabrik has vs the catalog is one script against `index-v1-nogeom.json`; rerun it when
  Geofabrik adds an extract.
  **OBF BAKE, THE FILTER THAT MADE IT FIT (2026-09-11):** MapCreator's memory ceiling is its
  first pass over every NODE in the extract, and buildings, landuse and the rest of the map are
  most of those nodes. `build-obf-region.sh` now runs `osmium tags-filter` first for a
  routing-only bake (highway ways with their nodes, ferry and shuttle-train routes,
  turn-restriction relations): a large US state went 363 MB -> 119 MB, 49.5 M -> 13 M nodes, in 4 s;
  the lean bake of the filtered file took 5 MINUTES at 12g instead of 48 and produced an 87 MB
  obf instead of 111 MB (the difference is non-highway ways OsmAnd's boat/ski/train profiles
  would use, which Vela never asks for). Device-checked: the filtered state obf served
  through a local manifest (`-PobfManifestUrl=http://127.0.0.1:8099/...` + `adb reverse`)
  routed a 40 km drive offline in ~15 s. A 150 km route ground for minutes at the engine's
  256 MB `RoutingMemoryLimits` (tile unloads every 100 ms) - that is the pre-existing
  intercity ceiling of the obf engine, not the bake. By the 3x rule, rows up to ~1.3 GB should
  now fit a 16 GB runner; England (1.6 GB) and Nunavut (1.4 GB) are the ones still in doubt.
  A bake that asks for address/POI sections (`VELA_OBF_SECTIONS`) skips the filter. **Route
  relations are indexed again (2026-09-12):** the lean bake had dropped them (and the filter
  dropped `type=route` relations), which cost the bicycle profile its signed-cycle-route
  preference; measured on a state bake they add 13% time and 0.4 MB, so `VelaObfShim` keeps
  `indexRouteRelations` on (`VELA_OBF_ROUTE_RELATIONS=false` to drop) and the filter keeps
  `r/type=route`. The world was re-baked with them.
  **OBF BAKE, MEASURED 2026-09-04 (read before touching scripts/build-obf-region.sh or the shim):**
  the memory ceiling is MapCreator's FIRST pass (`extractOsmToNodesDB`), so it does not depend on
  which sections you index, only on the PBF and on which analysis passes run. Same 345 MB
  US-state extract at a 12 GB heap: full routing+address+POI = OOM (7 min); routing-only with
  default passes = OOM (peak 11 GB); routing-only LEAN (multipolygon, route-relation, proximity and
  country-region indexing off) = SUCCESS, 48 min, 111 MB obf. Bavaria (810 MB) OOMs at 12g even
  lean (first pass, 81%); pieces that size need osmium chunks or a 32 GB machine (22g worked).
  Output sizes: Saarland 8 MB routing-only vs 52 MB with address+POI vs OsmAnd's own 66 MB
  roads-only / 125 MB full; the same state 111 MB vs OsmAnd 595 MB roads-only. Also: the workflow
  file was invalid YAML (duplicate `default:` key) from Aug 16 to Sep 3, so no bake ran at all.
  **Corner-cut lag (2026-09-06, from the reporter's trip log, replayed offline):** a cut corner
  jumps the along-route measurement ~30 m in one fix; the puck's catch-up cap (`maxCatchUp`,
  was 0.5v+1) then took ~7 s at 7 m/s to drain it, so the arrow ran straight after the car had
  turned. Raising the Kalman Q did nothing (the estimate already followed); the cap was the limiter.
  Now 1.5v+2: 34 m -> 7 m in 2.5 s, per-fix speed step unchanged (p50 0.27 m/s). A shorter
  PUCK_CORRECT_TIME_S (0.35) would cost smoothness (0.47/1.64 m/s) - left at 0.6. The replay
  harness is a scratch Python port of AlongRouteFilter + the rate rule over the trip's fixes.
  **Read SPEC section 4.7 first: the eight causes, the measurement scripts in `scripts/jitter/`, and
  the order to run them. Do not start from a theory.**
  **List order (issue #343, 2026-09-12):** `PlaceListStore.move(id, delta)` swaps within the stored
  JSON array, and that array order IS the display order everywhere (`state.lists` feeds the
  dialog, the search page's Your lists rows and the map's list pins), so no sort key was added;
  `create` still prepends. The dialog shows up/down IconButtons per row (hidden with one list,
  disabled at the ends) rather than drag-to-reorder: D-pad reachable and no gesture library.
  **Per-mode ETAs on the mode chips (2026-09-12, `MapUiState.modeEtas`):** the chooser's chips
  show the time ("25 min") and the glyph says the mode, Google's treatment; the mode name stays as
  the chip's content description. The CURRENT mode's entry is its own route set (`shownDuration`,
  the picker's fastest figure); the other three come from `prefetchModeEtas`, one after another
  (OSRM modes first, transit last because it is a hidden-WebView page load), through the SAME
  `directions()`/`transit()` calls the picker makes when the chip is tapped, so a chip never shows
  a number the list then contradicts (an OSRM free-flow guess reads minutes under the traffic-aware
  time on a signaled arterial). Keyed per trip (endpoints, stops, avoids, time, 5-minute bucket)
  in `modeEtaCache`; `clearRoute` cancels the job and empties the chips. A prefetch skips the mode
  the user has just tapped (`route()` is already on it). Google's transit summary says "hr", ours
  "h": `transitChipText` folds the English form so the chips read alike.
  **Units default reads the DEVICE locale (2026-09-12):** `Units.init` used `Locale.getDefault()`,
  but `AppLocale.wrap` (attachBaseContext) has already replaced the JVM default with the in-app
  language, and the plain "English" choice carries no country, so a US phone flipped to km the
  moment the language picker was touched. It now reads `Resources.getSystem()`'s locale. Any
  other "default from locale" decision must do the same (see `AppLocale.deviceDefaultSupported`).
  **`Route.offline` (2026-09-12, issue #350):** every route that came from `routeEngine.route`
  (the two single-leg sites and `chainOnDevice`) is tagged in GoogleMapsDataSource, and the picker
  row prints `place_route_offline` in the traffic slot. Downloaded regions are the FALLBACK, not a
  replacement: online, OSRM + Google traffic still answer even with the whole state installed.
  **Puck snap tolerance is MODE-AWARE (2026-09-12, `puckSnapTolerance`):** driving keeps 22 m +
  speed (lane offset + fix lag); walking/cycling use 8 m + 1.2x the fix accuracy, capped at 16 m,
  so a shortcut over a crosswalk frees the arrow within a fix or two instead of dragging it along
  the route (it used to be 22 m for everyone). The heading gate is not consulted below 2.5 m/s
  off-road (GPS bearing is noise at walking pace), so a pedestrian's release is distance-driven.
  The engine's reroute corridor was already mode-aware (`offRouteCorridor`).
  **Network breadcrumbs in the diagnostics ring (2026-09-15, issue #397):** the default-network
  callback records a `net` event whenever its summary changes (`available: cellular validated
  metered`, `lost: none`, `link: wifi not validated`), no pinging involved, so an export shows
  whether the phone had a validated link when both routers came back empty. Read the `net` lines
  beside the `directions` ones before blaming a router.
  **Diagnostics events for reviews + location (2026-09-12):** `WebReviewsFetcher` records
  `reviews` events (the hl it asked for and the app language, each page Google served with its
  `document.documentElement.lang` and `navigator.language`, and the parsed count or the 45 s
  timeout) and `LocationProvider` records `location` events (which providers exist/are on, then
  the FIRST fix's provider, latency and accuracy; never coordinates). Both landed because #359
  (English reviews on a zh-TW phone) and #362 (a flip phone that "never" gets a fix) could not
  be reproduced here; ask those reporters for a Settings > Diagnostics export.
  **Puck size/color setting (issue #344, 2026-09-12):** `ui/NavChrome.PuckStyle` (prefs
  `puck_size` normal/large/xl = 1x/1.25x/1.5x, `puck_style` blue/white). `navPuckBitmap(scale,
  whiteDisc)` draws both the Compose overlay (`remember(PuckStyle.key())`) and the `NAV_PUCK_IMG`
  symbol; the symbol image is registered once per style load, so `PuckStyle.key()` rides the
  `styleKey` and a change reloads the style. The white disc gets a hairline `#B9BDC2` ring so it
  keeps an edge over the light map.
  **THE PUCK ITSELF JITTERED BECAUSE IT WAS A MAP SYMBOL (issue #251, fixed 2026-09-03). In
  follow mode the puck is now a COMPOSE OVERLAY, not the `ME_ARROW_LAYER` symbol.** Measured the
  pixel that matters: the white chevron's centroid in an `adb screenrecord` (`scripts/jitter/puck_track.py`: threshold
  the white glyph in a 200 px box around the puck, centroid per frame) moved 1-2 px on 95% of
  frames on a dead-straight highway with the map calm, in a saw-tooth (1683.3, 1682.1, 1683.2,
  1684.3, 1686.8...). Cause: `setMeSource` is a GeoJSON source update that goes through
  MapLibre's async worker tiling, while `moveCamera` is synchronous, so the symbol landed on time
  or one frame late at random - one frame of travel (0.3 m, ~1 px at nav zoom) of vibration, on
  every road, independent of physics, geometry or frame pacing. The overlay is drawn at
  `projection.toScreenLocation(pt)` computed right after the camera move from the same camera
  state, rotated by `displayBearing - camera bearing`, squashed by `cos(tilt)` (two
  graphicsLayers: inner rotates, outer squashes+translates - order matters), from the same
  `navPuckBitmap()`. Per-frame writes go to `mutableFloatStateOf` holders read in the DRAW phase,
  so no recomposition per frame. `ME_ARROW_LAYER` is hidden while the overlay is on and restored
  (via `lastMeLayerKey = null`) when following stops, the puck disengages or the style reloads.
  After: chevron motion 0.07 px/frame, 2% of frames >0.5 px (was 1.39 px, 91%). The GeoJSON
  puck is still used when not following (panned map) and in browse. Rule: anything that must
  sit still on screen while the map moves cannot be a per-frame GeoJSON symbol.
  **THE TEXTUREVIEW CRASH SENTINEL MISFIRED ON A HEALTHY PHONE (2026-09-03).** `texture_render`
  (compatibility rendering, a TextureView map) is meant for GL drivers that kill the process at
  init; the sentinel counted ANY death between map creation and the first idle render, so two
  force-stops / swipe-kills / unrelated crashes flipped the Pixel 4a into it for good. Symptom: a
  `TextureViewRend` thread at ~89% CPU in a trace and judder on every road, reported as puck jitter.
  Now only `ApplicationExitInfo.REASON_CRASH_NATIVE` counts (`lastExitWasNativeCrash`, API 30+;
  older devices keep the any-death rule), the flip records `texture_render_auto_ms`, and the
  Developer row shows "Turned on automatically on <date>" so it can be seen and undone. When a
  jitter/judder report comes in, check this toggle FIRST.
  **THE ROUTE LINE RE-UPLOAD DROPPED A MAP FRAME EVERY 150 ms (issue #251, fixed 2026-09-03).** On a
  demo drive (zero GPS noise) the map still vibrated. Measured: `adb screenrecord` frames fitted with
  an ECC Euclidean transform showed the camera's turn rate through a bend stepping double-then-zero
  every 9 frames; a Perfetto trace of the GL thread (`RenderThread N` in SurfaceView mode; sleeps
  >0.3 ms separate renders) showed over-budget renders spaced 133-167 ms, 46 in 8 s, and a build with
  the throttle at 2 s made the spacing random and the count 18-22. The 150 ms re-upload of the 3 km
  ahead window (a LineString re-tessellation) was the cause. Fix: the moving cut is a `line-gradient`
  PAINT update on a short `ROUTE_CUT_LAYER` piece; geometry moves every ~300 m. After: over-budget
  renders random, 4% (the map's floor on a Pixel 4a), GL p90 11 ms. Also learned: the P4a had been
  silently flipped into TextureView "compatibility rendering" by the two-crash sentinel (renderer at
  89% CPU, judder everywhere); `adb shell` tracing shows it as a `TextureViewRend` thread.
  **THE UI THREAD STALLED ONCE PER GPS FIX (issue #251, the "jitter" on a demo drive, fixed
  2026-09-03).** A demo drive has ZERO GPS noise, yet the puck still froze and lurched once a
  second. Measured, not guessed: a screen recording showed one 65-84 ms frame every 1.02 s
  followed by a 3.5x catch-up step, and a Perfetto trace named it: `Compose:recompose` 50-74 ms
  in the fix's `doFrame`, "App Deadline Missed", exactly at the fix cadence. Trace markers
  bisected it to the ARGUMENTS of the `ManeuverBanner` call in MapScreen: `navRomanize` ->
  `SpokenScript.forDisplay` sorted the WHOLE road-name dict (`roadNameLatin`, a downloaded
  region's entire `names.tsv.gz`) and scanned every entry, twice per fix, for an English string
  that could never match. Fix in `SpokenScript.applyDict`: return in O(length) when the text has
  no character the reader can't read (every matchable entry contains one), and digest the dict
  once per instance (`preparedFor`, identity-keyed, two slots for the UI-language and
  voice-language maps). Second cost in the same frame: `NavEngine.update` rebuilt
  `cumulative(polyline)` and projected EVERY maneuver over the remaining line per fix (~19 ms,
  over a frame budget by itself) - now cached per route identity (`geomFor`). On-device after:
  recompose 7 ms max, banner 0.9 ms, onLocation 0.2 ms, the 1 Hz stall gone. **Rule: a puck
  "jitter" report is measured with a screen recording (frame-to-frame timestamps + pixel diff)
  and a Perfetto trace BEFORE any physics is touched; a hitch at the fix cadence is main-thread
  work, and no filter can smooth a dropped frame.** Trace app sections with
  `atrace_apps: "app.vela"` in the perfetto config; `android.os.Trace.beginSection` works in the
  release build, and `Compose:recompose` slices are emitted by the runtime already.
  Nav zoom range is 18.0→15.5 (2026-07-14, was 17.3→15.0).
  **PUCK JITTER (issue #251) HAS EIGHT SEPARATE CAUSES, ALL FIXED. READ SPEC section 4.7
  BEFORE TOUCHING THE PUCK.** It was re-diagnosed from scratch four times because each pass found
  a real cause, fixed it, and the symptom persisted - two of those passes then derived the SAME
  window fix independently. The list: (1) the along-route POSITION was never filtered; (2) the
  progress rule stalled and surged; (3) the camera bearing followed digitization wiggle; (4) the
  smoothing window's own width rippled; (5) demo drives ran the clocks at 3x; (6) the UI thread
  stalled once per fix (measured 2026-09-03, not physics at all); (7) the 150 ms route-line
  re-upload dropped a map frame at 6.7 Hz; (8) the puck was an async GeoJSON symbol vibrating a
  pixel against the synchronous camera, the one the user actually saw. If jitter is reported
  again, the next thing to suspect is something NOT on this list - do not re-derive one of these.
  **(1) THE ALONG-ROUTE POSITION WAS NEVER FILTERED (2026-09-01, the biggest single cause).** The
  puck's SPEED had been Kalman-filtered since June; its POSITION never was. The snapped fix went
  straight into `targetM` and the puck was drawn from it, so every meter of along-route GPS noise
  was a meter the puck genuinely had to travel, once a second, for the whole drive. **No amount of
  downstream smoothing can fix that** - a smoother makes the movement gentler, it does not make it
  stop happening, which is precisely why four passes of smoothing work left the symptom alive.
  `AlongRouteFilter` (`:core`, unit-tested) is the missing measurement update: the estimate
  dead-reckons at the modeled speed and grows variance, and each accepted fix folds in weighted by
  its OWN reported accuracy (`Location.getAccuracy` is a 68% radius; snapping discards the lateral
  component, so the along-route sigma is ~0.66x it). A clean 4 m fix pulls most of the way, a 25 m
  urban-canyon one barely moves the estimate. `targetM` still exists and still takes the raw fix -
  the plausibility gate and the snap window are built on it and keep their behavior - but what the
  puck DRAWS is now `navPuck.along`. Genuine discontinuities (engage, re-acquire, a persistent
  over-cap jump) call `reseed` instead, because a teleport is not noise to average down.
  Simulated against the old rule, 1 Hz fixes at 60 fps: along-road wobble 7.2 -> 2.8 px at cruise
  with clean GPS, 27.1 -> 5.5 px in an urban canyon.
  **(2) THE PROGRESS RULE STALLED AND SURGED AT THE FIX CADENCE.** The puck eased toward
  `targetM + reckonedM` and was then clamped monotonic. Each half is individually sensible;
  together they fight. Every fix reset the reckoning, so the target JUMPED by however much the dead
  reckoning had over- or under-shot; an overshoot put the target BEHIND the puck, the ease pulled
  backward, and the monotonic clamp FROZE the puck until the reckoning caught up. A few meters of
  ordinary GPS noise did that once a second, forever. Now corrected in the RATE domain: the puck
  always advances at the modeled speed and the estimate error enters as a BOUNDED nudge to that
  rate (`PUCK_CORRECT_TIME_S`, catch-up capped at 0.5x speed + 1 m/s, hold-back at 0.25x + 0.5).
  Cannot stall, cannot lurch, still monotonic. Stalled frames 2.7% -> 0% at cruise, 27.8% -> 0% in
  a canyon; frame-to-frame speed error 36% -> 3%.
  **(4) THE SMOOTHING WINDOW'S WIDTH RIPPLED.** `win = speed*0.7` sizes the position+bearing
  boxcar, but that is the KALMAN speed, which the per-frame accelerometer predict ripples. On a
  curve the averaged point sits inside the arc by ~`win^2/(6R)`, so **the width is a lateral
  position** and speed noise became sideways movement. `navPuck.smoothWin` now eases the
  half-width toward its speed target with a long time constant (`PUCK_WIN_TAU_S` 2.5 s): still
  tracks town-vs-motorway, which is all it was for, but per-fix wobble cannot reach it. **Rule: a
  fast-moving smoother is not a smoother.** Measured honestly this one is SMALL on real OSRM
  geometry - a +/-1 m width ripple moves the drawn point ~1 cm, sub-pixel - so it is worth keeping
  and not worth re-deriving a third time.
  **Route geometry is requested at `polyline6`, not `polyline` (2026-09-01).** OSRM's default
  encoding is 1e5-scaled: a 1.11 m latitude grid, so every vertex of a physically straight road
  arrives snapped to a meter-ish step and the puck has to smooth back out scatter Vela itself
  introduced. `geometries=polyline6` + `PolylineCodec.decode(s, 6)` removes it at the source -
  verified on the FOSSGIS server (same vertex count, same distance, 10x the resolution). Worth
  ~20-25% of the chord-bearing noise on real routes; the rest of the crinkle is genuinely in OSM.
  NB `TripLog` still encodes saved routes at 1e5 (changing it would misread every existing trip
  file by 10x), so a REPLAY sees slightly coarser geometry than the live drive did.
  **(3a) ACCELEROMETER NOISE WAS REACHING THE PUCK (2026-08-20).** Archaeology settled it: the ORIGINAL puck
  (498e7f49, 2026-06-21, the version remembered as smooth) took its speed STRAIGHT off the GPS fix
  and held it constant between fixes - twelve lines, no Kalman, no boxcar. `SpeedKalman.predict`
  now runs once per FRAME, so ~60 accelerometer samples are integrated into the speed between two
  1 Hz fixes, and the puck ADVANCES AT THAT SPEED - so road texture, engine and mount resonance
  became visible movement. `SpeedKalman.denoise` applies an `a^2/(a^2+n^2)` suppression gain
  (`ACCEL_NOISE` 0.5) before integrating. **NOT a subtract-the-floor shrinkage** - that was tried
  first and broke `brakingCollapsesThePredictionBetweenFixes`, because shrinking every sample taxes
  a REAL brake by the floor, weakening the exact behavior the filter exists for (the puck must
  decelerate with a stopping car). The gain leaves a 4 m/s2 brake within ~1.5% of itself while
  cutting 0.3 m/s2 of vibration to about a quarter. A steady bias is attenuated, not erased (~0.08
  m/s after a second), which the next fix corrects. **Rule: anything integrated per-frame from a
  phone sensor in a car needs a noise model, or it becomes puck motion.**
  **(3) THE CAMERA BEARING IS ADAPTIVELY DAMPED (2026-08-24, the "crinkly roads" residual).** Measured on a synthetic road that is physically
  STRAIGHT but digitized with half-meter vertex scatter (what "crinkly" means in the data): the
  chord bearing the puck derives swings **7.3 deg at a 5 m window, 2.3 deg at 14 m** - several
  times the 0.83 deg camera wiggle the earlier fixes chased. The camera is anchored to the puck,
  so that rotates the WHOLE MAP under a steady arrow. **Window WIDTH is the only lever on chord
  noise** (angular noise ~ sigma/baseline): a least-squares fit over the same window was measured
  NO BETTER (slightly worse at short windows) because the noise lives in the VERTICES, so interior
  samples are correlated with the endpoints and add no information. And curvature-adaptive width
  was measured UNWORKABLE - crinkle reads 3.0 deg median / 7.6 p90 short-vs-long disagreement, a
  genuine 40 m bend reads 3.9, so no threshold separates them. What DOES separate cleanly is
  AMPLITUDE at the camera: geometry noise is a couple of degrees, a turn is tens. So the camera's
  bearing time constant follows the size of its own error - `CAM_BRG_TAU_STILL` 1.6 s when small
  (keeps ~24% of the wiggle vs 59% at the old flat 0.55), `CAM_BRG_TAU_TURN` 0.35 s past
  `CAM_BRG_TURN_DEG` (25 deg), which is QUICKER around a real corner than before. Tilt keeps the
  old constant - it is not part of this. **DEMO DRIVES RAN THE PUCK CLOCKS AT 3x (issue #251, fixed 2026-08-10 - the
  dominant cause of the "record needle" swim).** `startDemoDrive` feeds
  `locationProvider.replay(fixes, speedup = 1f)` - REAL-TIME fixes - but it also sets
  `replaying`, and MapScreen keyed `replaySpeedup` off that flag alone, so a demo drive
  inherited the recorded-trip 3x clock scaling. The puck then dead-reckoned 3x too far between
  fixes, the monotonic clamp stalled it until the next fix caught up, and its route bearing
  jumped on every surge. Instrumented on-device (temporary `VelaBrg` log, 60 Hz):
  progress stalled on **54.7%** of frames with 2.4 m lurches, chord-bearing wiggle **1.74 deg**,
  camera yaw wiggle **0.83 deg** - which the 55 deg tilt smears into 15-50 px of horizontal swim
  across the TOP of the screen while the puck sits rock steady (screen-space proof: top-band
  motion 6x the bottom band = rotation, not translation). After gating the scaling on
  `!demoDriving`: 0% stalled, chord wiggle **0.53**, camera wiggle **0.29** deg, on-screen top-band
  motion halved. The gate is `state.replaying && !state.demoDriving` - a genuine trip REPLAY still
  emits fixes at 3x and MUST keep the scaling. **Nav-camera eases also use a CAPPED time
  step (`dtEase` <= 65 ms, same issue, video-diagnosed):** a main-thread hitch (the
  400 m road-label pass lands near junctions) used to deliver one frame whose exponential eases
  jumped 45-70% of their error at once - the map lurched sideways under a rock-steady puck.
  Integration (Kalman/reckoning) keeps real dtT; only the cosmetic eases (progress, bearing,
  camera pos/brg/zoom/tilt/padding) use dtEase, spreading a hitch's catch-up over ~6 frames.
  At smooth 60 fps the math is unchanged (16 ms << cap). A pinch during nav sets a zoom
  override WITHOUT detaching the follow camera (deliberate), which meant no Re-center path back
  to auto-zoom existed (issue #238) - since 2026-08-04 VelaMapView reports the override up
  (onNavZoomOverride) so MapScreen shows the nav Re-center FAB for it, and the button bumps
  navRecenterTick which clears navUserZoom/navUserTilt back to auto; GTFS stop icons hide during nav
  (declutter effect + the VM skips the fetch). The route line's
  driven/ahead cut is a GEOMETRY split (`ROUTE_AHEAD_LAYER` window over a traversed-gray full line) plus
  a PAINT-ONLY moving cut since 2026-09-03: `ROUTE_CUT_LAYER`, a 400 m piece over the ahead line whose
  `line-gradient` is the actual gray/color cut (256 texels over 400 m = 1.6 m each, under the arrow); its
  geometry slides every ~300 m, only its paint changes per frame. NEVER move route geometry per frame
  or on a short timer for the cut: the old 150 ms re-upload of the 3 km window dropped a map frame at a
  fixed 6.7 Hz (Perfetto: over-budget renders spaced 133-167 ms, 46 in 8 s) - a whole-map vibration
  that was reported as puck jitter (#251) - and a per-frame LineString source is worse (it re-tiles on
  every worker thread; only a POINT source like the arrow is cheap per frame). A whole-route gradient
  is not the answer either: 256 texels over the route smears the cut into a routeLength/256 m ramp.
- Nav drive-report fixes (2026-07-05): (1) **Route line z-order** - the route line inserts BELOW the first
  symbol layer, but Liberty's first symbol is `road_one_way_arrow` (~idx 61) which sits UNDER the `bridge_*`
  layers (~63-82) → bridges painted over the route on bridges (it "vanished"). `VelaMapView.ensureLayers`
  anchors instead to the first symbol AFTER the last `bridge_*` layer (a real label), so the route draws above
  all road+bridge geometry, still below text. (2) **Exit consolidation** - OSRM splits one exit into ramp +
  fork/merge steps, each spoken separately ("Take exit 15"…"Keep right"…"Merge"). `RouteGeometry.consolidateExits`
  folds a ramp's immediately-following, <500 m-gapped FORK/MERGE run into the ramp maneuver (sums distances so
  they still tile the polyline; stops at any real turn / far gap) → one prompt. Unit-tested. **Cousin `rampReclass` (2026-07-21):** a SIGNLESS
  dead-straight "on ramp" (dual-carriageway rename transitions get tagged *_link in OSM, so OSRM
  calls them ramps) reclassifies to "new name" before typing/phrasing - silent CONTINUE, never
  "Take the ramp" on a road that just renames; narrow on purpose (straight/null modifier only,
  destinations present always keeps the ramp). **Sibling
  `RouteGeometry.foldRenames` (2026-07-06)** folds a pure-rename CONTINUE (OSRM `continue`/`new name` going
  straight, no genuine fork - "Olive Dr becomes Richards Blvd") into the PRECEDING maneuver so it's not its own
  banner card / step at all - NavEngine already SILENCED its voice, but it still showed a silly "Continue onto X"
  card where Google shows nothing (user report). Applied on BOTH routers (OSRM `parseOsrmRoute` + the obf engine
  `toRoute`); a genuine-fork CONTINUE (`continueHasGenuineFork`, spoken) and STRAIGHT (a junction straight-through)
  are left alone. Unit-tested. (3) **Feet steps**
 - `formatDistance` (banner) + every `NavStrings.spokenDistance` table (voice) round feet Google-style: 50 ft at/above
  100 ft, 10 ft below. (4) **Voice K/C** - `EnNavStrings.expandForSpeech` rewrites `<XX>-<n>` (CA-99, SR-99) →
  "State Route n" so espeak's G2P doesn't mangle the bare 2-letter code's onset. **(4b) "take" → "tyke"
  (2026-07-11):** espeak's G2P is context-sensitive - on a full "take the ramp toward Woodland" it
  mis-voweled "take", but "take the ramp" alone was correct (user A/B). `expandForSpeech` now inserts a
  comma before " toward " (`", toward "`), which is a `SpeechText.speechFragments` boundary, so the model
  phonemizes "take the ramp" in isolation and reads the sign destination as its own beat (Google pauses
  there too). "toward" only appears on ramp/exit/highway-sign steps, so plain "onto" turns are untouched;
  unit-tested, EAR-VERIFIED by the user 2026-07-11. Related but NOT actionable: proper-noun prosody
  wobbles ("San Francisco" reads flat/off depending on the FOLLOWING words; appending an "h" happened
  to help one sentence, but the trigger context varies) - that's VITS prosody, not a text bug; no
  reliable text-level fix, don't chase per-word respellings. (6) **Continue/straight lane silence** - a CONTINUE/STRAIGHT speaks its lane preface ONLY for a GENUINE fork (an "off" lane whose OWN indication is an explicit `straight`/`slight*` arrow, e.g. "use the left 2 lanes to stay on I-80"); a plain turn bay at an intersection (off lane marked only `left`/`right`, OR **`none`** = OSRM's "no painted arrow" sentinel, which is NOT "goes straight") while you sail straight through is silenced (`Route.continueHasGenuineFork` gates `NavEngine`'s escape hatch; it matches only `straight`/`slight*` on an off lane - `none`/`through` are excluded) - Google stays silent there and the road-just-renames case had been over-speaking. (5) **Traffic-light landmarks
  ("pass the light, then turn") - BUILT (Settings → Navigation → "Traffic-light guidance", OFF by default,
  English-only):** `RouteGeometry.enrichWithLights` folds a "pass the light, then …" clause into a surface-street
  TURN when 1–2 signals fall on the approach (`NavStrings.passLights`); signals from `OverpassTrafficSignals.fetchAlong`
  (keyless Overpass). **Two audit-2026-07-06 refinements (unit-tested):** it EXCLUDES a signal AT the turn vertex
  itself (that's the light you turn at, not one you pass first - `distanceTo(turnPt) >= LIGHT_SNAP_M`), and it
  CLUSTERS matched signals within `LIGHT_CLUSTER_M` (30 m) before counting, because OSM maps one `traffic_signals`
  node per approach/carriageway at a junction - raw-node counting said "pass 2 lights" for one intersection. Still
  needs a real-drive calibration of the thresholds. The neural voice's occasional attack-clip at sentence starts is
  a model-level Piper limit, separate from the CA-99 fix.
- **Free-drive follow (2026-07-11, user request).** Browsing without a route, the camera now tracks the fix and the
  heading beam is smoothed the way nav is. Implemented as a SECOND per-frame ticker in `VelaMapView`
  (`LaunchedEffect(navMode, driveFollowing)`, sibling of the nav `LaunchedEffect(navMode, routePolyline)`): when
  `driveFollowing` it eases `browseBeam` toward `compassHeading ?: myBearing` (tau 0.15 s) and eases `browseCam`
  toward `myLocation` north-up (k = 1-exp(-dt/**0.22** s), loosened from 0.16 2026-07-13 so the camera keeps
  CHASING between the ~1 Hz fixes instead of coasting to each and stopping = a continuous glide, nearer the nav
  feel), driving the ME source (`setMeSource`) + `moveCamera` each frame, with an idle-skip when neither moved
  (a settled follow doesn't re-upload 60x/s). **NORTH-UP is ENFORCED, not assumed (2026-07-14):**
  `moveCamera(newLatLng)` moves only the target, so a leftover bearing/tilt (a previous nav's heading-up
  camera, an old manual rotate) survived into the follow and a drive tracked DOWN the screen -
  `browseAtt` now eases both back to 0 with the same k (zoom stays untouched, so a pinch level
  survives), and the nav-exit teardown also levels bearing/tilt (+ resets the sticky puck-low camera
  padding) for the not-following case. A manual rotate is a gesture, which drops follow - never fight it.
  **DRIVING mode = HEADING-UP like nav (2026-07-15, supersedes north-up FOR DRIVING):** the north-up
  ask was made from a car and what actually felt wrong was sideways puck motion. `browseDrive`
  ([smoothedSpeed, engagedFlag, courseTarget, lookaheadM]) latches driving on above 2.5 m/s
  (smoothed) with a known course; while engaged the ticker eases the LIVE camera bearing toward
  the GPS course (course target updates only >2 m/s - never trust a crawl), tilt toward nav's 55,
  and aims the camera at a point `speed*5` m (cap 250) AHEAD of the puck along course - the nav
  puck-low framing WITHOUT sticky padding (nothing to un-stick when follow drops; the puck itself
  still draws at `browseCam`). A red light HOLDS the attitude (engaged releases only when the
  follow ends via the effect reset). The beam prefers GPS course over compass while engaged (car
  bodies wreck magnetometers). North-up flat enforcement still runs for the NOT-engaged regime
  (walking/slow browse). Don't re-add north-up for driving without re-reading this.
  **The follow target DEAD-RECKONS between fixes (2026-07-14):** easing toward the raw ~1 Hz fix
  chased a target that jumps then sits - the per-second surge-and-stall jitter (user report). The
  ticker now projects the last fix forward along its own speed + course every frame (constant
  velocity, gated to >1.5 m/s with a known course, capped 2.5 s blind) and eases toward THAT, so
  the camera chases a target moving like the car - the nav glide, no route needed. The next fix
  re-anchors and the ease absorbs the correction. This CLOSES the "fuller dead-reckon is the next
  step" note above; tuning (the 2.5 s cap, the 1.5 m/s gate) still wants a real drive.
  **Re-anchoring was itself the staccato (2026-09-16, `ui/map/FollowEstimator`):** the user's
  "moves, stops for a sec, then moves" on surface streets. Two causes: the fix the reckon anchored
  to is `myLocation`, the VM's parked-hold LOW-PASSED position (k = speed/10, so a fix lags up to a
  whole fix at 5 m/s), and every re-anchor stepped the target BACK; and even a raw fix is noisy, so
  every re-anchor was a jump the 0.22 s ease rendered as surge-and-stall. Now one continuously
  integrated estimate: `onFix` queues HALF the residual as a correction spread over 0.9 s, `step`
  integrates speed along the course every frame (stale past 2.5 s), and while moving the ticker
  feeds it `myFixRaw` (new MapUiState field, the accepted fix BEFORE the low-pass; an outlier hold
  keeps the previous raw) instead of `myLocation`. Slow or stopped it snaps to the smoothed fix as
  before. `FollowEstimatorTest` pins: no backward frame with fixes lagging 0.8 s at 5 m/s, no
  frame under 60% of true speed between fixes, stale fix stops integration.
  **Drive-verified follow-ups (2026-07-14 evening):** (1) the DOT still jolted 1 Hz after the
  camera went smooth - applyData's recomposition paint used the RAW fix while the ticker drew the
  eased point, the exact bug the meBearing guard fixed for the ANGLE; `mePaint` (the eased point
  while following) is the position twin - any new me-source writer must respect the ticker's
  ownership of BOTH. (2) north-up is enforced against the LIVE camera bearing/tilt each frame,
  not a copy seeded at engage - the shadow copy went blind to any rotation arriving from outside
  the ticker and the map stayed rotated. **The PUCK draws at the EASED position (`browseCam`), not the raw
  fix (2026-07-13):** at the raw fix the dot teleported forward on the map each fix while the camera eased to
  catch up (the visible hop); at the eased position it stays centered and glides with the map, the locked
  puck+camera the nav follow has. (A fuller constant-velocity dead-reckon between fixes is the next step if it
  still isn't smooth enough - needs a real drive to tune.) It OWNS the location
  source while running, so `applyData` must NOT repaint the raw compass over it - the call sites pass `meBearing`
  (= smoothed `browseBeam` when following, else `displayBearing`). The camera `when` block has a guard branch
  (`!navMode && driveFollowing && myLocation != null`) so a new fix's recomposition can't fire an `animateCamera`
  that fights the glide. Gate lives in `MapScreen` (`followMe`, default true; a `onUserPan` drops it, the locate FAB
  re-arms it; suppressed while search/place/directions/results own the camera). **A programmatic
  jump > 1 km from the fix ALSO drops it (2026-07-13):** a recents pick / search hit / pasted
  coordinate only SUSPENDED follow while the sheet owned the camera, so closing the sheet resumed
  it and glided the map all the way home (device report). A LaunchedEffect on `state.center`
  disarms follow when the new center lands far from `myLocation`; a nearby POI tap keeps it. Feel constants unverified on a real
  drive - revertible.
- Nav fixes (2026-07-05, round 2): (1) **Replay arrow** - the replay puck showed only the DOT, never the
  directional arrow. The arrow's visibility keys on the `displayBearing` passed to `applyData`
  (`VelaMapView` ~730), which prefers snap/compass/`myBearing`; recorded traces often carry no per-fix bearing,
  so with no route snap it went null and hid the arrow. Now falls back to the engaged puck's OWN route-derived
  heading (`navPuck.displayBearing`, seeded from the road segment by the motion ticker) while navigating.
  (2) **Replay GPS snap-back** - the puck kept jumping from the trace to the user's REAL GPS. `replayTrip`
  cancels+nulls `locationJob`, but `startLocation()` is guarded only by `locationJob != null`, so a permission
  callback / MapScreen effect re-started the live collector mid-replay and its real fixes overwrote
  `myLocation`+`center`. Fixed with two guards: `startLocation()` no-ops while `replaying`, and the live
  collector drops every fix while `replaying` (belt-and-suspenders). Replay's `finally` still resumes live GPS
  once `replaying=false`. (2b) **Replay teardown** (stop or natural end) - the blue line stayed drawn and the
  dot stuck at the trace's end point. The `navSession→state` observer keeps `activeRoute` once nav stops
  (`else it.activeRoute`), so the `finally` must explicitly null `activeRoute`/`routes`/`directionsOpen`/step
  preview; and it now snaps `myLocation`/`center` back to the user's real PRE-replay location (`resumeLoc`,
  captured in `replayTrip`) so the dot leaves the trace end - resumed live GPS refines it on the next fix.
  Gated on `ownedNav` (a replay riding an already-active nav leaves that route/location alone). (3) **U-turn / back-on-course** - a U-turn strays >45 m → `RerouteNeeded` → async
  directions fetch (~1-3 s); but the U-turn outlasts the fetch, and by the time it lands the driver has
  rejoined the ORIGINAL line and the engine cleared the `offRoute` latch - yet `reroute()` adopted the fresh
  route anyway, yanking a self-corrected driver onto a different path. Now `reroute()` captures `fromRoute` and,
  before adopting, discards the result if the driver is SOLIDLY back on it - `route === fromRoute &&
  nav.onRouteStreak >= BACK_ON_COURSE_HITS(2)` - Google's "you're back on course, carry on". **NOT bare
  `!offRoute`**: an adversarial review showed the offRoute latch clears on a SINGLE grazing fix (and `offDist`
  can match a parallel/overlapping leg), so one spurious graze would kill a legit missed-turn reroute. So
  `NavState.onRouteStreak` (consecutive on-corridor+moving fixes, computed in `NavEngine` beside `offRouteHits`,
  reset the instant off) gates it - a graze can't reach 2, a real rejoin does. Self-healing (a re-deviation
  re-fires the edge; no cooldown charged). Threshold tunable from a real-drive U-turn capture. (4) **Traffic incidents** - re-investigated + DEFERRED
  (user, 2026-07-05): no keyless real-time source (Google keyless response carries none; incident tiles are
  proprietary binary; OSM has only stale roadworks; DOT/511 needs a token + is per-state). Congestion coloring
  already shows where it's slow. See ROADMAP.
- Heading (browse-cone facing direction when stopped, where GPS course is noise): raw
  `SensorManager` `TYPE_ROTATION_VECTOR` (`core/location/HeadingProvider`) - a plain
  Android sensor, not GMS. **Navigation never uses it** (the nav heading comes from the
  matched road); it's pushed to state only in browse + only on a real change, so it can't
  spam recomposition during nav.
- Nav-puck speed fusion: raw `TYPE_LINEAR_ACCELERATION` + `TYPE_ROTATION_VECTOR`
  (`core/location/MotionProvider` → world-frame accel; `core/location/SpeedKalman` fuses it
  with GPS speed - accel predicts between fixes, each fix measures). Collected ONLY during
  nav, written into a plain array (never compose state - sensor-rate recomposition). Missing
  sensors degrade to `a = 0` = the old constant-speed dead reckoning.
- Voice: AOSP `TextToSpeech`, engine-selectable - never hard-depend on Google TTS. **Plus an
  in-process neural option (Piper):** Vela bundles the **sherpa-onnx** runtime (arm64 `.so`, from the
  `tts-runtime` release AAR - gitignored, fetched in CI, NOT committed) and downloads a **Piper VITS**
  voice into `filesDir/piper/<id>/`, run in-process by `app/voice/PiperSynth` (sherpa `OfflineTts` +
  `AudioTrack`) behind the `:core` `voice/NeuralSynth` seam (the AAR can't live in the `:core` library
  module). The default is **HFC Female** (`en_US-hfc_female-medium`, ~67 MB); it becomes the default
  voice once present. **Non-obvious, all device-only (compiler-clean):** R8 MUST `-keep class
  com.k2fsa.sherpa.onnx.**` (JNI resolves classes by original name); and you must generate the WHOLE
  utterance before `AudioTrack.play()` (streaming underruns → AudioFlinger drops the track → SIGABRT).
  The whole utterance is generated, but it's **written to the track in ~200 ms chunks with a `generation`
  check between them** (`PiperSynth`, audit 2026-07-06) so an interrupt (turn-now/rerouting/stop) takes
  effect within ~200 ms instead of blocking for the full utterance - safe against the SIGABRT rule because
  back-to-back chunk writes keep the buffer full (no underrun). **Audio-focus is refcounted via the
  utterance callbacks; two audit-2026-07-06 leaks closed:** a system-TTS `speak()` returning `ERROR`
  enqueues no utterance so no callback ever fires - `VoiceGuide.speakViaSystem` now rolls back the focus
  acquire on `ERROR`; and a failed system-TTS `onInit` used to queue every prompt into `pending` forever
  (unbounded, replayed stale on a later init) - it now clears `pending`, latches `systemInitFailed`, and
  fires `langUnavailable` instead of queueing into a void.
  **A Piper voice is a SINGLE-language model** - reading another language's nav text through it is
  gibberish (the "English voice read Russian after a language override" bug). `NeuralSynth.voiceLanguage`
  exposes the loaded voice's lang (id prefix, `en_US-hfc_female` → "en"); `VoiceGuide.speakNow` compares it
  to the language the nav text is GENERATED in (`NavStringsRegistry.current().locale`) and, on a mismatch,
  routes to **Android `TextToSpeech` in the target language instead** (`speakViaSystem`, lazily creating a
  default engine as the fallback - the system `tts` is NOT shut down when the neural voice is active). If the
  system TTS has no voice for that language either, guidance stays **silent** (never mangles it through the
  wrong voice) and fires `langUnavailable(lang)` → `MapViewModel` flashes a "get a &lt;language&gt; voice in
  Settings → Voice" hint. So switching the app/system language to one whose voice isn't downloaded degrades
  gracefully, it doesn't read the new language through the old model.
  **(History: earlier iterations bundled Kokoro (`KokoroSynth`) and Matcha; both were removed after
  on-device A/B - Kokoro was ~0.4× realtime even on a Pixel 9. `MapViewModel` reclaims their old model
  dirs and sanitizes stale `vela.kokoro`/`vela.matcha` prefs to Piper. `project_vela_kokoro_tts` memory
  is that historical record, not the current design.)**
- **THE NAV NOTIFICATION IS A LIVE UPDATE ON ANDROID 16 (issue #595, 2026-09-19):**
  `NavigationService.promoteToLiveUpdate` sets a `NotificationCompat.ProgressStyle` scaled to the
  ROUTE (meters), tracker = the nav puck (`navPuckBitmap`, the maneuver glyph stays the large icon, Google's layout, 2026-09-22), segments = `route.trafficSpans` colored like the
  route line, points = remaining stops, `setShortCriticalText` = distance to the next turn, then
  `setRequestPromotedOngoing(true)`. Needs androidx core 1.17 (compat class, no raw platform API)
  and compileSdk 36; targetSdk stays 35 on purpose. Guarded by `Build.VERSION.SDK_INT >= 36` and a
  runCatching, so every older device and every failure gets exactly the old notification.
  **The promotion also needs `android.permission.POST_PROMOTED_NOTIFICATIONS` in the manifest** -
  without it the styled notification posts fine and the chip never appears, which is exactly what
  the first Pixel 9 run showed (2026-09-19): the extras were all correct, the status bar was bare.
  **And the channel has to be DEFAULT importance, not LOW** (`vela_nav_drive`, sound null, vibration
  off - a new id, since importance is fixed once a channel exists): the system files LOW as "silent",
  and the same Pixel 9 hides silent notifications on the lock screen, so the live update was missing
  from the one place worth having it. Google Maps' own `1_foreground_1` channel is importance 3 for
  this reason. Verified on the device: with the channel silent the lock screen showed nothing; with
  silent notifications shown it drew the full live update - route bar, maneuver tracker at the car,
  Pause/End.
- **A FASTER-ROUTE OFFER AUTO-RESOLVES (issue #594, 2026-09-18, benwiley4000):** it used to sit
  until answered, so a driver had to answer a prompt covering the map. `FasterRouteCard` drains a
  bar along its bottom edge over 10 s for everyone, FROZEN while focus is anywhere on the card
  (benwiley4000 argued the key-driven 25 s down: more time mostly prolongs the interruption for
  someone less likely to answer, while stopping the clock when they reach for it gives time to
  whoever actually wants it), and then acts: ACCEPT by default
  (`ui/FasterRouteAuto`, pref `faster_route_auto`, Settings > Navigation "Take faster routes
  automatically"), dismiss when off. Never indefinite. The countdown keys on
  `state.fasterRoute` so a recomposition cannot restart it, and is read in the draw phase.
- **PAUSE LIVES IN THE NAV BAR'S RIGHT SLOT (user 2026-09-18, `ui/PauseInBar`, pref
  `nav_pause_in_bar`, DEFAULT ON, Settings > Navigation).** On a touch phone that slot is an empty
  54 dp spacer (it only keeps the figures centered against End), so pause takes it and the FAB stack
  keeps a PLAIN mute button; `NavBarTop(onPause=)` draws it, filled with `primary` while paused.
  **The step-list button also wants that slot** whenever `PreferButtons.on || dpadFirst`
  (`navListButton` / `navPauseInBar` in MapScreen); the bar then carries BOTH (the figures FitText
  shrinks) rather than silently picking one - the first cut gave the slot to the list button and the
  new default never reached anyone with Prefer buttons on. On a keypad-first device pause stays in
  the stack. The chevron handle is itself a focusable clickable that opens the step sheet, so the
  list is never unreachable in any layout. Turning the setting off
  restores `NavHoldControls` in the stack.
- **MUTE AND PAUSE ARE ONE BUTTON (user 2026-09-18, third pass; the layout the setting restores).** `NavHoldControls` in
  `ui/nav/NavOverlays.kt`: one 56 dp target. The FIRST tap on a running drive only slides MUTE out
  beside it for `OPEN_MS` (6 s) and the SECOND tap on the same target pauses - pausing on the first
  tap made holding the drive the only way to reach mute ("pressing on the pause button to get to the
  mute button obvi pauses shit first"). A LONG PRESS mutes outright, so anyone in the know never sees
  the pop-out ("nice to not have to see the pop out if u were in the know"); while PAUSED a single
  tap resumes, because the glyph already says what the tap does. The button shows both states
  (pause/resume glyph, accent fill while held, a crossed-speaker badge while muted) because one
  control standing for two has to. Long press is touch-only; the row is the D-pad path, so no key
  alternative is missing. It replaced the two-target pill below.
- **(superseded) MUTE AND PAUSE SHARED ONE PILL (user 2026-09-18).** The nav FAB stack was overview, voice, search,
  pause, plus recenter when detached: five controls down the right edge, most of a small phone's
  height and worse in landscape. Mute and pause are the two STATE controls of a drive, so they
  share one `Surface` in the zoom pair's dress (one pill, two 56dp targets, a hairline between),
  pause on top because pulling in is the decision made at speed. Paused, the top half fills with
  the accent. Adding a sixth nav control means merging, not stacking.
- **PAUSE THE DRIVE (`NavSession.paused`, user 2026-09-18).** A nav FAB and a notification action
  hold the drive: `onLocation` records the fix and returns before the engine, so there is no engine
  update, no off-route detection, no reroute, no arrival, no stop cue, no voice, no live-traffic
  recheck and no faster-route offer - everything is downstream of that one call. The puck keeps
  moving (it is drawn from the raw fix) and the arrival clock keeps sliding on a 30 s tick in the
  bar, because what the stop is costing you is the one figure that should move while you stand
  still. Resume reroutes once from where you are when the stop took you off the route
  (perpendicular distance vs `NavEngine.offRouteCorridor`), else speaks the current instruction and
  carries on. **Auto-resume is ARMED by the stop, not by the pause:** a fix that is stationary or
  off the route sets `autoResumeArmed`, and only then do `AUTO_RESUME_HITS` (3) consecutive moving,
  on-route fixes resume it. Without the arming step, pausing while still rolling down the route
  resumed itself three fixes later - a pause button that does not pause (device, the day it was
  built). Android Auto has no pause control yet.
- Nav feedback: spoken guidance (`VoiceGuide`) + **direction-coded haptic turn cues**
  (`core/feedback/Haptics`, `NavEvent.Haptic`); toggle in Settings → Navigation. **Reroute buzzes
  too (2026-07-10):** `Haptics.reroute(mode)` (three ticks + a long buzz, distinct from every turn
  pattern) fires beside the throttled spoken "Rerouting" in `NavSession.reroute` - same per-mode
  setting, works muted. Demo drives pass `travelMode` into `navSession.start` so per-mode haptics
  behave in a simulation like the real ride (they used to default to DRIVE = silent).
- EU consent: `InMemoryCookieJar` (CoreModule) pre-seeds Google's `SOCS`/`CONSENT`
  cookies so a cookieless EU session isn't bounced to `consent.google.com` - don't
  strip those, and don't let a `Set-Cookie` downgrade `CONSENT` to `PENDING`.
- No GMS: no FCM/Firebase/Play Integrity/Fused. If push is needed later, use
  UnifiedPush; crash reporting via ACRA/self-hosted Sentry.
- **Whole-country downloads from a split catalog (2026-09-14).** Offline maps > "Entire states &
  countries" shows one "All of <parent>" row per parent shared by two or more rows (the trailing
  parenthetical of the region name: "Bayern (Germany)", "Nunavut (Canada)"; "(state)" is not a parent)
  with "Download all", which calls `MapViewModel.downloadRoutingGraphs(pieces)`: it queues every piece
  not installed (`regionQueue`, `regionQueueLeft/Total` in state) and `downloadRoutingGraph` pops the
  next at the end of each download (`startNextQueuedRegion`); cancel clears the queue. Built for the obf
  catalog's Laender/regions/zones split; on the live routing catalog it shows for Canada's provinces.
- **Translation catch-up (2026-09-14).** Every locale (de es fr hu it iw ja nl pl pt ru sv uk zh zh-rTW)
  had fallen ~205 keys behind English (everything since about August); all 15 are complete again
  (one translation agent per locale, opus, native register matched to each existing file; placeholder
  multisets, `\n` counts and XML validated per key). Weblate is still not live, so this is the flow:
  when `values/strings.xml` grows, re-run the per-locale catch-up before a stable. Voice-command
  examples are localized (a French address in fr, Ukrainian places in uk), not transliterated.
- **Offline round two (user's own list, 2026-09-21).** (1) `TransitBoardCache` keeps every board
  fetched (48, by stop coordinate); offline, `fetchStopDepartures` and `onTransitStopTap` show the
  cached one with `stopDeparturesCachedAt` and the sheet prints "Last seen X". (2) The offline
  search branch leads an address query with the pack POIs within `OFFLINE_AT_ADDR_M` (40 m) of
  the geocoded point (`OfflinePoiStore.near`) and fills blank addresses on the first
  `OFFLINE_ADDR_FILL` (20) rows through `reverseGeocode`; pack POIs rarely carry `addr:*`, so
  results read as bare names before. (3) `resumeNav` waits up to `RESUME_FRESH_FIX_WAIT_MS`
  (8 s) for a fix newer than the launch seed before routing: the seed is where the process died,
  and routing from it drew the line over the road driven since. (4) `CarMapRenderer` sizes the
  puck to the car screen. (5) `VoiceGuide.FOCUS_LEAD_MS` (350) delays the first sample after a
  FRESH focus grant so a pausing player has stopped. (6) `ObfRouteEngine.spokenType` maps a
  `skipToSpeak` turn to CONTINUE, AND a TL/TR with under `STRAIGHT_TURN_DEG` (20) of measured
  angle (see SPEC 4.5). The second rule is the one that fixed the reported drive: probed on the
  state's own obf, the router emitted `Turn left (+TL|C|C|C)` with a 0.7 degree angle and
  skipToSpeak FALSE where a one-way carriageway rejoins its two-way continuation, twice on one
  4 km stretch. Probe recipe: a throwaway core test that calls `ObfRouteEngine.route` with
  `-DvelaObf=<dir with the .obf + index.json>` (extra `-D` properties are NOT forwarded to the
  test JVM; read inputs from a file beside the obf) and a temporary println of
  `turn.toString()`, `turnAngle`, `isSkipToSpeak` and `lanes` inside `toRoute`. (7) A PAUSED
  drive draws its line lavender (`ROUTE_PAUSED_COLOR` in MapScreen, SPEC 4.8); `VelaMapView`
  re-anchors the split on any `routeColor` change, or only the cut piece recolors (4a, demo
  drive). (8) Offline search puts transit stops last unless the query asks for transit
  (`OfflinePoiStore.TRANSIT_STOP_CATS`).
- **Offline taps stay on the phone (2026-09-14).** `MapViewModel.offlineNow()` (latched `offline` or the
  system says no internet) gates `fetchReviews`, `fetchPhotos`, `fetchPlaceDetails`, `fetchStopDepartures`
  and the tap resolution in `onPoiTap`: offline, an open place shows its tile data or the Google listing
  remembered from an earlier online tap (`openPlaceCache`), a basemap tap keeps its name, and no spinner
  waits on a host that cannot answer.
- **NavController (2026-09-15, issue #417 refactor 3, step 1).** `app/ui/map/NavController.kt` holds
  the nav side that used to live in MapViewModel: start/stop/demo drive/trip replay, the nav-state
  observer that mirrors `NavSession` into `MapUiState` (trip route blocks, corridor fetches, warnings,
  route bar, the resume heartbeat, arrival), tunnel dead reckoning, the per-route corridor fetches
  (controls + speed cameras), the spoken speeding/camera warnings, the route bar, resume after a
  process kill, and the mid-drive stop functions. State stays in the view model's `_state` flow
  (passed in); what the nav code needs from the rest of the view model (live GPS pause/resume, the
  stale timer, status cards, the speed-limit badge, route naming, the road-features cover check,
  the shared `destination`/`controlsBox`/`autoStartOnRoute`) goes through `NavController.Host`, an
  anonymous object in the view model (`navHost`), so the controller never reaches into the view
  model. The view model keeps every public function as a one-line forwarder (`startNav() =
  nav.startNav()`), so MapScreen and the settings pages are unchanged. Rules: `nav` and `navHost`
  are declared ABOVE the view model's `init` (the observer's first pass runs inline, the #474
  rule), `nav.bind()` is the last line of init, the location collector writes `nav.lastNavFedMs`,
  and the viewport controls path asks `nav.corridorControlsActive`. Constants stay in the view
  model's companion. Next: `NavCamera` in VelaMapView, then `SearchController`.
- **SearchGates (2026-09-15, issue #417 refactor 3, step 3a).** The search / results / picker
  presentation gates MapScreen used to compute inline (`searchOpen`, `pickingResults`,
  `resultsShown`, `resultsMinimized`, `mapTargetHidden`, `fabChromeOk`, `bareMap`) are one pure
  function now: `SearchGates.of(state, searchExpanded, searchFocused)` in `app/ui/map/SearchGates.kt`,
  same expressions, and MapScreen reads `gates.x` where each `val` used to be. `SearchGatesTest`
  (the app module's first unit tests; `testImplementation(libs.junit)` was added for it) pins the
  documented traps: a focused field always opens the overlay, results hide behind an open search or
  a selected place, a submitted search while picking a stop shows its results (#405), Street View
  keeps the list off the mini map, a collapsed list is the bar, pick-on-map keeps the crosshair.
  When a gate changes, change it there and add the case to the test; the full `SearchController`
  (query/suggestions/results/pickers as one state machine in the view model) is the rest of step 3.
- **HiddenWebView base (2026-09-15, issue #417 refactor 2, step 1).** `app/web/HiddenWebView.kt` owns
  the lifecycle every hidden-WebView fetcher used to copy: the view (JS, DOM storage, desktop UA, the
  `VelaBridge` result channel), a request id per page load (`request(timeoutMs) { id -> load(url, id) }`,
  a late poller can only complete its own id), the idle reap + memory-pressure reap, the sleep between
  fetches (`session { }` = mutex + onResume before + onPause after + reap timer), the non-http scheme
  block, and console ERROR lines logged as `VelaWeb: <tag>: ...` for every fetcher. A fetcher is its
  URL + extractor script + parser and overrides `onPageFinished(view, url, requestId)`.
  `WebStopDeparturesFetcher` and `WebDirectionsFetcher` are converted (187+224 lines -> 89+120, transit
  board verified on the 4a); `WebPopularTimesFetcher` followed (203 -> 146 lines: `fetch` =
  `session { request { ensureWarm(); evaluate(script) } }`, the two-step warm keyed on page-finish
  counts, `onReaped` drops the warm so a reaped view re-warms, and it logs `popular: raw= parsed=`
  under `VelaWeb`); `WebPhotoFetcher` followed (454 -> 411 lines, the walk script untouched): the
  base gained `bridge()` (a fetcher whose script reports several result kinds returns its own
  `VelaBridge` object, its `onResult` calling `deliver`), `allowNavigation(uri)` (photos stay on
  google.com so the overview's Menu action link cannot walk the scrape off the page), and the
  offscreen 1200x3200 viewport moved into `configure`; the scraper is injected ONCE per request by
  whichever of the page-finish settle and the 7 s load cap fires first (`inject`, guarded by
  `isPending` + an injected set), `warm()` is a suspend session the VM launches, and a reaped view
  clears `warmed` so the next search boots it again. Verified on the 4a: two galleries in a row on
  one view (31 and 23 photos, partials streaming, distinct walk keys). `WebReviewsFetcher` closed
  the series (562 -> 476 lines, scrape script untouched): same inject-once shape as photos, its
  desktop-width settings + density-scaled 1200x1000 CSS viewport + the one-time `resumeTimers()`
  in `configure`, the page-loaded language probe in `onPageFinished`, google.com-only navigation.
  Verified on the 4a: two places in a row, 8 cards each on the Reviews tab, rendered in the sheet.
  All five hidden-WebView fetchers now ride the base; a new scrape is a subclass, never a copy of
  the WebView plumbing.
- **Route provenance is one field (2026-09-15, issue #417 refactor 1, step 1).** `Route.source:
  RouteSource` (OSRM, OSRM_VIA_SNAP, GOOGLE_NAMED, GOOGLE_ABBREVIATED, GOOGLE_PROVISIONAL, OBF, GRAPHHOPPER,
  VALHALLA, UNKNOWN) is stamped at every constructor (DirectionsParser, RouteGeometry.parseOsrmRoute,
  ObfRouteEngine, the since-retired GraphHopperRouteEngine, ValhallaRouter, Mock, the GoogleMapsDataSource fallback and
  provisional branches, nameRoute's snap) and recorded on the trip file's RD line as `source=NAME` (omitted
  for UNKNOWN, so old files and old tests read unchanged). Consumers ask `drivable` (not a provisional
  picker alternate) and `hasRealSteps` (not Google's abbreviated fallback) instead of reading the booleans;
  NavSession does. The four booleans remain the source of truth for those properties this release; step 2
  computes them from the source and deletes them. Never add a fifth boolean; add a RouteSource value.
- **MapViewModel init rule (2026-09-15, issue #474 boot crash).** `viewModelScope` is
  `Dispatchers.Main.immediate`: a `launch { flow.collect { } }` inside `init` runs its FIRST pass inline,
  before the properties declared below `init` exist. `speeding.reset()` in the nav-state collector hit a
  null `SpeedingAlerts` (declared 6,000 lines down) and every launch crashed on an Android 10 handset and
  a head unit, while Pixels never showed it. Anything an init-time collector touches is declared ABOVE
  `init`; the open-place link loader had the same shape a day earlier (it now suspends on IO first).
- **Data revisions + monthly bakes (2026-09-15).** Every data manifest row now carries `rev` (the bake
  date as an int, `YYYYMMDD`): obf (`scripts/build-obf-region.sh`), places (`places-overlays.yml`),
  basemap (`basemap-tiles.yml`); the place packs kept their counter. On the phone `ObfStore` and the
  `PmtilesRegionStore` family record the installed rev (`revs.json` next to the files) and expose
  `installedRev`/`updatable(manifest)`. `MapViewModel.refreshRegionUpdates` (runs with the catalog
  refresh) fills `MapUiState.regionUpdates` (region id -> "routing"/"places"/"map") and the Offline maps
  row shows the same "Update" it showed for a newer pack; `updateRegion` refreshes the pack (delta
  when offered), every places and basemap archive inside the region, then the obf. Crons: places on the
  6th (shard a) and 7th (shard b) against the newest Overture release found in the bucket listing;
  basemap on the 9th and 10th (first/second half of the catalog by id). The obf bake stays manual (its
  runner memory limits and the user's manifest flip).
- **Offline basemap (2026-09-14).** A region download is now routing (obf) + places (Overture) + the
  MAP PICTURE: `tools/build-basemap-region.sh` (planetiler over the same Geofabrik extract the obf
  bake uses, OpenMapTiles schema = what OpenFreeMap serves, so the same Liberty style draws it) ->
  `basemap-<id>.pmtiles` on the `basemap-tiles` release with `basemap-manifest.json`
  (`.github/workflows/basemap-tiles.yml`, matrix from `tools/routing-regions.json` group/ids, Java 21,
  planetiler base data cached, bounds read from the archive header by `scripts/pmtiles-bbox.py` (bytes
  102..117, int32 E7; the pmtiles CLI download was rate-limited on shared runners and lost entries on
  the first world run); `scripts/merge-basemap-manifest.sh` **DERIVES the manifest from the release's
  own archives (it calls the repair script) instead of folding this run's fragments into it - GitHub
  CANCELS a job that is PENDING in a concurrency group when a newer one joins, so a wave of runs
  loses its middle merges after the archives are already uploaded: 2026-09-18, 10 of 25 runs, and
  the manifest listed 99 of 414 regions. Never write a manifest merge that depends on its own job
  surviving, and dispatch a catalog as a couple of sharded runs, not one per group**;
  `scripts/repair-basemap-manifest.sh`
  rebuilds the manifest from whatever archives sit on the release, one 127-byte range request each).
  Two world-bake lessons (2026-09-15): planetiler is fetched PINNED (v0.10.2), retried and
  `unzip -t`-checked, because five jobs died on "Invalid or corrupt jarfile" when the unverified
  `latest` download came back as not-a-jar on a busy runner; and a bake over GitHub's 2 GiB asset
  limit (Nunavut at z14) is rebaked one zoom shallower in the same job before it fails.
  Saarland full z14 = 33 MB (a lite z13 no-buildings tier = 8 MB, not wired). App: `PmtilesRegionStore`
  is the shared base of `PlacesTileStore` and `BasemapTileStore` (`files/basemap/`, never streamed,
  `installedFor(center)` = smallest covering archive); `MapUiState.basemapArchive`; `refreshBasemapArchive`
  runs with the places refresh AND at VM init from the seed location; `downloadBasemapForRegion` /
  `downloadBasemapForArea` chain into every region and viewport download; deleted with the region;
  counted under "Saved areas & map cache". **Fifth rule (2026-09-18): the basemap pick ASKS THE FILE.** `BasemapTileStore.installedFor` no
  longer stops at "smallest covering box": it probes each candidate with
  `PmtilesReader.hasRoads(file, 12, x, y)` and takes the first that actually draws roads there.
  **`hasRoads` answers null for "cannot tell" and false for "definitely no roads", and collapsing
  the two was the second half of #552 (HirschBerge, 2026-09-19: panning a download's border
  "alternate between completely gray and using network").** Past a region's real data but inside
  its box every probe returns a definite false, and the old fallback mounted the archive anyway,
  painting gray over streamable tiles; crossing the box edge unmounted it again. A definite no from
  everything that covers the point now returns NULL (stream it); only an UNREADABLE candidate falls
  back to the old smallest-covering pick. Swaps also have a floor of `BASEMAP_SWAP_COOLDOWN_MS`
  (2 s) because re-pointing every layer re-tiles the whole map, which is the freeze he described.
  **Third round (2026-09-21, same reporter, video at a 200 km wide zoom):** a swap is a FULL STYLE
  RELOAD (`basemapArchive` is part of `styleKey`), and the pick flipped every time the view center
  crossed the data edge, so panning along a border reloaded the style every couple of seconds.
  `installedFor(center, mounted, view)` now has hysteresis: unmounting stays eager (center tile
  without roads = stream), but MOUNTING an archive that is not already mounted needs the z12 ring
  around the center AND the four viewport corners to hold roads. Once the border is on screen the
  view keeps streaming; an archive comes back only when the border has left the screen. **Fourth
  round (2026-09-22, reproduced on the 4a in airplane mode with Pennsylvania installed, panning
  from Scranton over the New York line at the 10 mi scale): offline, the eager unmount blanked the
  WHOLE screen for twelve seconds, the Pennsylvania half included, because nothing streams in the
  archive's place. `installedFor(keepMounted = offline)` keeps the mounted archive while its roads
  still reach the center tile, the ring around it or any viewport corner; a view entirely outside
  its data lets go. Online the rule is the mirror: the archive is mounted only while the ring
  AND the corners are all inside it, so a border on screen means streaming, and a pan along the
  border (the reporter's video, center wobbling across the line) no longer reloads the style at
  every crossing; one reload when the border enters the screen, one when it leaves.** The probe tests the `transportation` LAYER, not
  tile presence - planetiler's base data (water, landcover, Natural Earth) is global, so a bake has
  tiles across its whole box and "is there a tile" answers yes over the neighbor and out to sea
  (verified on the published hawaii archive: a mid-Pacific z12 tile exists and carries no roads).
  Verified on the published west-virginia archive against issue #552's own screenshots: south-west
  Pennsylvania answers false, inside WV answers true. `PmtilesReader` is ~200 lines of PMTiles v3
  (header, directory, Hilbert tile id) plus the MVT layer-name walk; every failure answers null and
  the old rule stands. The pick moved off the main thread (`pickBasemapArchive` on IO) because it
  now reads a directory page and one tile per candidate; answers are memoized per archive and tile.
  **The engine rules found the hard way (a full evening):**
  (1) the local archive must be added as a source AFTER the style loads and the layers using it
  re-attached (`LOCAL_BASEMAP_SRC`, `localBasemapLayerIds`, `withLocalBasemap` re-points every
  `openmaptiles` layer); declared in the JSON or via `Style.Builder.withSource` it never got past the z0
  tile; (2) a labeled tile only completes once every glyph range and the sprite RESOLVE, so with no
  signal the remote hosts hang and the map is blank; glyphs and the sprite are served from
  `file://` (`GlyphPackStore`: the `map-fonts` release zip unzipped into `files/glyphs/`, ~200 MB on
  disk, pulled with the first basemap download and self-healed at startup; the bundled sprite copied to
  `files/sprites/`; `asset://` hung like the network); (3) a process that STARTS offline on the remote
  style poisons the engine's shared glyph/sprite managers for every later style, so the local style is
  chosen before the first load (`refreshBasemapArchive(seed)` in init); (4) every helper that read
  `getSource("openmaptiles")` (theme, hillshade, house numbers, contrast layers, satellite roads,
  road-name dictionary) now goes through `basemapSrc(style)`, else the offline map came up light and
  bare. Verified on the 4a from a cold offline start: Saarlouis and Saarbruecken draw with streets,
  names, buildings, shields, dark theme, plus the places layer and offline routing.
- **Hidden WebViews sleep between fetches (2026-09-14).** Every hidden-WebView fetcher (`WebPhotoFetcher`,
  `WebPopularTimesFetcher`, `WebReviewsFetcher`, `WebDirectionsFetcher`, `WebStopDeparturesFetcher`) calls
  `onResume()` at the start of a fetch and `onPause()` when the last pending fetch is done, and the two
  warm-ups pause once their page has landed. A loaded Google page kept its compositor and JS timers
  running for good, which measured as ~27% of the app's CPU during a plain map pan (`VizWebView` +
  `Chrome_IOThread` in a /proc per-thread sample); after the change those threads are gone from the
  pan profile and the same gesture costs about half the CPU. Keep the pair balanced when adding a
  fetch path; `pauseTimers()` is process-wide, so it is deliberately not used.
- **Open places layer, beta (2026-09-14, issue #441, the Overture direction).** `tools/build-places-region.sh
  <id> S W N E out.pmtiles [release] [local.parquet]` bakes Overture Places (DuckDB over the public S3
  parquet, or a local extract) into PMTiles: business POIs only (parks/schools/civic/transit excluded, OSM
  has them), each feature with `name`, `class` (humanized category), `group` (the PoiIcons icon group),
  `prominence` (OsmProminence-style: category prior + brand + website/phone/address + confidence, 0-9.5),
  `confidence`, `brand`, `addr`, `website`, `phone`, `src=overture`, `rank` (position by prominence
  inside a ~400 m cell) and `crank` (same inside a ~1.6 km cell), and a tippecanoe minzoom from the ranks
  (landmark category and xrank 1 in a ~6.5 km cell z11, landmark xrank <=3 z12; crank 1 and prominence
  >=6 z13; crank <=2 or prominence >=5 z14; rank <=3 or >=4.5 z15; rank <=12 or >=3.5 z16; else z17;
  `-Z11`). `landmark` = airport/hospital/university/stadium/mall/zoo/museum etc., the POIs Google keeps
  drawing zoomed out; below z13 everything in the tile gets an icon and a label. Density on the map is the rank, not collision: VelaMapView steps `iconImage` by
  zoom (top 2 per coarse cell below z15, top 1 per fine cell at z15, top 5 at z16, top 12 at z17, all from
  z17.5, a high prominence always qualifies) and `textField` with the SAME steps as the icons (every icon carries its
  name like Google's; collision alone drops labels on a crowded block, user 2026-09-14); everything else in the tile draws as a small
  category-colored dot on a `vela-places-dots-<i>` CircleLayer (`PoiIcons.groupColor()` over the baked
  `group`), so a downtown thins to its landmarks and fills in as you zoom, the way Google's does. Dots
  are thinned by rank too (none below z15, rank <=6 at z15, <=15 at z16, all from z17, via opacity
  steps since filters cannot read zoom) and BOTH dot tiers (open + ambient) sit below the basemap's
  first symbol layer (`firstSymbolLayerId`), so a label's halo covers its dot and no dot ever sits on
  text. Saved and parking pins keep `iconAllowOverlap=true` but now `iconIgnorePlacement=false`, so a
  label under a pin is dropped instead of drawn half-covered. `PlacesTileStore` = `files/places/*.pmtiles`
  (offline) + `PLACES_MANIFEST_URL` regions streamed (`sourcesFor` returns ONE source: the smallest
  installed archive covering the center, else the smallest manifest region; two nested archives drew
  the overlap twice). Baked so far: Davis (test box) and California (1.68 M places, 481 MB, streams
  by range request; the CI bake of one state took 15 min); `tools/places-regions.json` mirrors the obf
  STAGING catalog (`obf-manifest-staging.json` on the `obf-regions` release, 414 rows: US states incl.
  california-norcal/socal, Canadian provinces, German Laender `de-*`, French/Spanish/Italian regions,
  Brazil/India/Japan/Indonesia zones, countries) plus davis: finer pieces than the live routing catalog's
  whole countries, so `downloadPlacesForRegion` pulls EVERY archive whose box center falls inside the
  downloaded region (a whole-country download today gets all its pieces, a Land download later gets one),
  and `sourcesFor` streams the smallest covering piece. The full bake is two manual `places-overlays.yml`
  dispatches, `shard=a` and `shard=b` (the matrix caps at 256 jobs), max-parallel 4, hours each; `MapPoiPrefs.placesSource` (Settings > Data & privacy since
  2026-09-16, was Map; "Places come from": `open` ("Vela data", compiled default) / `google` / `both`; the FLEET DEFAULT
  is remote since 2026-09-16 (`calibration.json` `defaultPlacesSource`, v20 -> `Calibration.defaultPlacesSource`
  -> the VM pushes it into `MapPoiPrefs.setRemoteDefault` at init + after refresh, same channel as
  defaultMapPalette; only people who never touched the picker follow it, an explicit pick wins).
  The user did not want Both hard-coded as the default (2026-09-16) but wants the flip available
  without a release: edit the field, bump version, re-sign, commit. Pref `map_places_source`,
  short user-facing hints plus a Learn more dialog naming Overture/Meta, Vela's own GitHub hosting,
  and the Google hooks on search and tap) gates it; `MapPoiPrefs.placesWithDownloads` (Settings >
  Offline maps, "Include places with downloads", default ON, pref `offline_places_with_downloads`)
  makes a region download (`downloadRoutingGraph` and the viewport path) also pull the covering
  places archive; `refreshPlacesOverlays` fills `placesOverlays` on
  camera idle; `maybeLoadAmbientPois` returns early (no Google fan-out) while the layer covers the
  view in `open`, and in `both` waits for a 1.5 s settle (cache paint included) and then runs one
  fan-out whose places are uploaded AS-IS (Google's copy wins a twin, see the "Google WINS" note).
  The open twins are hidden by `hideOpenTwins`, a DEBOUNCED pass (`ambientRedo` at 400 ms and
  `ambientRedo2` at 2 s after the last upload, canceled by a newer list or a style reload): it
  queries only the RENDERED open icons on screen, and re-checks the ids it already hid with a
  filtered `querySourceFeatures` (id IN the hidden set, so only a handful cross JNI), releasing
  any whose Google partner has left the set, so an open place is never left hidden with nothing in
  its place. Running the queries inline on every streamed partial upload was the settle-time
  stall in downtown Davis (up to 119 ms per upload, a dozen per settle; 2-5 ms now). The earlier
  inline `openPlacesShown` helper (rendered + rank-qualified union) is gone. Two layer rules from
  the strip-mall test still hold: the open layer allows icon overlap from z18 and the AMBIENT layer
  from z17 (`iconAllowOverlap` steps on both), because below those the Google extras lost collision
  to the open icons in a strip mall and "Both" looked identical to "Vela data" at 500 ft.
  **Closed listings (same day):** when a tapped open pin resolves to a
  Google listing with `permanentlyClosed`, `hideClosedOpenPlace` adds its Overture id to
  `MapUiState.hiddenOpenPlaceIds` (persisted in `open_place_closed.json`, loaded with the links)
  and VelaMapView filters both places tiers with `!in(id, ...)`, so the pin is gone the moment
  anyone taps it and stays gone until a rebake drops it for real. The open layers sit ABOVE the ambient layer so open icons
  win collision and Google's extras fill gaps. Outside any region file all three behave like Google.
  About > Map data credits Overture (CDLA-Permissive 2.0) with a license button. The OSM basemap
  business POIs (`poi_r1/r7/r20`) under an open places source (2026-09-16, final shape): OSM
  BUSINESS classes (`OSM_BUSINESS_CLASSES`: the style's food/shop/lodging/fuel groups plus the
  commercial health and money classes) are hidden outright by a static term in
  `applyPoiTierFilters` (`osmHideBusiness`, false when `MapPoiPrefs.osmBusinesses` "OpenStreetMap
  shops too" is on (2026-09-17, off by default); then `osmFillIn` queries EVERY open icon group, not
  just the non-business ones, so OSM businesses twinning an open place drop by name; set by the overlay effect, and also while
  `placesPending` says the first places lookup has not answered, so a cold start does not flash
  OSM's shops and then drop them), because Overture,
  AllThePlaces and Google cover businesses far better. Everything else OSM draws - museums,
  attractions, parks, schools, civic buildings, places of worship, transit - stays up, and
  `osmFillIn` (camera idle, 500 ms debounce) drops by name only the non-business OSM points that an
  open icon of a non-business group (`OPEN_NONBUSINESS_GROUPS`) within 80 m already draws.
  The pass is VIEWPORT ONLY and rendered only (queryRenderedFeatures on both sides; an excluded OSM
  point is no longer drawn, so it is never re-tested), GROW-ONLY within a source set
  (`osmPoiExclude`, capped at 1,500 names; every setFilter re-lays the whole poi source, so it
  changes only when something new turns up), SKIPPED when the tiers are hidden (Both mode with
  Google covering the view, or places off), and THROTTLED to once per 2.5 s after a fifth of a
  screen or 0.4 zoom of movement. PERF HISTORY, measured on the 4a in downtown Davis with a
  pan-pause-zoom sequence: the first cut queried every loaded feature on both sources and stalled
  the main thread up to 169 ms per settle; a rendered query costs 37-55 ms in a dense view even
  when it returns nothing, which is why the skip and the throttle exist. ANR LESSON (same day,
  hotfix #532): the first cut also built two `Regex` objects per key, i.e. Pattern.compile for
  every POI on the main thread, and a San Francisco view hung the app ("Vela isn't responding",
  trace = PatternNative.compileImpl under the idle Runnable). `NAME_PUNCT` / `NAME_SPACES` are
  module-level; never build a Regex inside a per-feature loop. The ANR trace is readable at
  /data/anr/anr_* without root. VelaMapView draws `vela-places-<i>`
  SymbolLayers dressed identically to the ambient layer; a tap on a `src=overture` feature builds a seeded
  `Place` (category/address/phone/website from the tile) and `onOpenPlaceTap` -> `onPoiTap(seed=...)`, so
  the sheet reads offline and the existing Google correlation upgrades it online. Davis is the test bake
  (2,335 features, 728 KB). **Regions (same day):** the `places-overlays` release hosts the archives +
  `places-overlay-manifest.json` (`{regions:[{id,name,url,sizeMb,bbox}]}`), baked by
  `.github/workflows/places-overlays.yml` from `tools/places-regions.json` (manual dispatch while beta,
  `scripts/merge-places-manifest.sh`, which since 2026-09-22 DERIVES the manifest from the archives on
  the release through `scripts/repair-places-manifest.sh` and re-lists the release after uploading,
  the basemap merge's shape; it used to fold the run's own entries into the old manifest, and the
  merge job sat in a concurrency group, where a PENDING job is cancelled when a newer run joins: a
  54-state wave lost 34 merges that way and mailed a failure for each. Neither bake workflow has a
  concurrency group on its merge now. Run the repair by hand after any wave to be sure:
  `bash scripts/repair-places-manifest.sh`); `PlacesTileStore.download` (index.json by bbox,
  PMTiles magic check) rides along with a region download (`downloadPlacesForArea`, next to the building
  overlay), `deleteRoutingGraph` removes archives whose bbox center sits in the region; manifest misses are
  memoised 10 min (the lookup runs on every camera idle). Labels use `PoiIcons.ambientLabelColor(dark)` off
  the baked `icon` property (the fixed gray was the "text looks off" report), FOUR label anchors
  (right/left/top/bottom, 2026-09-15: with two, a strip mall's row of icons dropped every second
  label while Google labels every pin; below z15 only the coarse-cell winners carry text, so the
  extra slots cost nothing at the wide views. NB in Both mode the Google top-up's low-prominence
  places stay unlabeled until z17.5 by the ambient layer's own tiers; open mode labels every icon
  it draws), **three bake rules + one layer rule from the strip-mall look (2026-09-15 evening):**
  (1) STACKED POINTS: Overture puts a building's tenants on one parcel point (17% of Davis rows
  shared their point: medical suites, strip-mall tenants) and coincident icons collide at EVERY
  zoom, so the shop under a stack never drew; the bake spreads a stack on a small ring (8 to 20 m,
  golden-angle steps, best row stays put) and the layer allows icon overlap from z18 (a step
  expression) for archives baked before that. (2) TENANTS: a row at an anchor category's address
  (supermarket, department store, mall, hospital, university, big-box) within ~200 m and not an
  anchor itself loses 2 prominence points, so a Safeway pharmacy or the Western Union counter
  named "SAFEWAY #1561" no longer outranks the store in its cell. (3) `sandwich_shop`, `deli`,
  donut/bagel/dessert/smoothie shops, taqueria, diner map to the FOOD group (Subway wore a cart
  icon). Validated on a local Davis bake before the world rebake; verify a region with
  `scripts`-free tooling: the scratchpad venv holds `pmtiles` + `mapbox-vector-tile`.), minzooms >=6 z13 / >=4.5 z14 / >=3.5 z15 / >=2.5 z16 / else z17.
  `openPlaceCache` (VM, LRU 500, device-local, PERSISTED to `files/open_place_links.json` as
  `[{o: overtureId, p: PlaceJson}]`, loaded on a Main-dispatched launch after init so it never races the
  constructor, written 2 s after a new link, slim listings without a review count or hours never
  stored) makes a second tap on the same pin instant, across restarts. `MapPoiPrefs.lookupTappedPlaces`
  ("Look up tapped places on Google", Settings > Map under the source picker, default ON, pref
  `map_places_google_lookup`): off, a seeded open-place tap stays on the tile data, no search, no
  reviews, nothing to Google; basemap taps still resolve. License:
  CDLA-Permissive 2.0, attribution still to add to About.
- **Sheet titles follow the app language's SCRIPT (2026-09-15, `core/util/NameScript`).** Google
  answers a tap's correlation with the local-script name even under hl=en (a Hebrew title over an
  English app's Latin pin in Tel Aviv). `onPoiTap`'s resolve now runs `NameScript.prefer(uiLang,
  google, placeholder.name)`: when Google's name is not in the app language's script and the map's
  own label (open tile name or basemap name) is, the label stays as the title; otherwise Google's
  name wins as before (so "Mikuni Japanese Restaurant" still becomes "Mikuni"). Script per
  language is a table in `scriptOf`; Japanese counts kana and kanji; unknown languages keep
  Google's name. Unit-tested (`NameScriptTest`). The label can be bilingual ("X - <hebrew>") when
  the data names it that way; that is the map's own label, not a bug.
- **ALLTHEPLACES in the bake (2026-09-15 night):** `tools/build-places-region.sh` pulls the region's
  z15 tiles from the AllThePlaces world PMTiles (`pmtiles extract --bbox`, seconds, run pinned by
  `ATP_RUN`, `ATP_LOCAL` for a local extract, `ATP_RUN=none` to skip), decodes them
  (tippecanoe-decode + jq), keeps rows whose OSM-style tags mean a business (shop=*, an amenity
  allowlist, hotels, gyms, healthcare, a few office types; NOT little free libraries, ATMs,
  lockers, historic places, airports), maps the tags onto Overture's category names for the
  prominence/group CASEs, names a branch after its brand when the locator named it after the
  town, and INSERTs into `raw` the rows with no Overture row of the same brand or the same two
  leading name words (`nkey`, the SQL twin of namesAgree) within ~150 m; confidence 0.85 so a
  matched Overture row wins ties. Davis: 112 in the box, 14 added. New tile properties: `hours`
  (OSM opening_hours syntax, chains only; Overture has none) and `origin` (overture|atp). `src`
  stays "overture" for every row because the tap gate in VelaMapView reads `src == "overture"`;
  do not key anything on src beyond "this is an open-data feature". The seeded Place converts
  `hours` with `core/util/OsmHours.toDayLines` (the common opening_hours subset -> "Monday: 8 AM–5
  PM" lines that `OpeningHours.statusAt` and the sheet's hours section already read; null on
  months/PH/sunrise -> the raw string is shown), and OverpassPois does the same for the place
  packs' OSM opening_hours. The workflow installs
  go-pmtiles for it. Overture's Davis source mix (why this exists): meta 1,537 / BrightQuery 494
  / Microsoft 366 / Foursquare 260 / AllThePlaces 30 / DAC 6 of 2,693 rows; a business with no
  Facebook page and no Bing entry is absent, chains included. The main duckdb heredoc is
  UNQUOTED: a backtick in a SQL comment runs as a command (the "xrank: command not found" noise).
- **The hidden WebViews are warmed AFTER results land, never before the fetch (2026-09-14).**
  `runSearch` used to call `webPopularTimes.prewarm()` + `webPhotos.warm()` before the search:
  two Chromium instances created on the main thread and loading google.com while the search ran.
  On a cold start (a `geo:...?q=` deep link into a fresh process) that held the search at 13 s
  against 4 s warm and left the map blank throughout; measured on the 4a, results now land 10 s
  after process launch instead of 18.5 s. `warmPlaceWebViews()` runs from the results publish.
- **Photos use a hidden WebView** (`app/web/WebPhotoFetcher`). The full gallery RPC
  (`hspqX`) serves real photos only to a real browser engine - OkHttp gets a
  bot-degraded Street-View-only reply (TLS-fingerprint detection, not headers).
  The WebView loads `maps.google.com` **anonymously (no login)** and same-origin-
  fetches the RPC. This is the one place we run Google's JS - an accepted tradeoff
  for richer photos (lazy, best-effort, OkHttp fallback). Gotchas: **desktop UA**
  (mobile UA → Google deep-links to `intent://`), block non-http(s) redirects, and
  use a `Handler` not `View.postDelayed` (a headless WebView never attaches).
- **Street View is IN-APP + keyless (2026-07-15, `streetview-inapp`).** We render the panorama
  OURSELVES rather than embed Google's WebGL page (which serves a stripped shell → black on ANGLE,
  the reason the old attempt was reverted - do NOT retry the WebView-embed path). Pipeline: (1)
  metadata via `MapDataSource.streetView` → `GoogleMapsDataSource` hits the keyless JS-API
  `GeoPhotoService.SingleImageSearch` (pb in `calibration.streetViewMetaUrl`, `{LAT}`/`{LNG}`;
  the `get()` helper's `Referer: https://www.google.com/maps/` is what authorizes it), parsed by
  `:core` `StreetViewParser` (address/copyright/position live INSIDE the pano node `root[1]`, not
  root - the off-by-one trap the unit test locks; copyright is `[1][4][0][0][0]`, one deeper than
  it looks). Returns null = no coverage. (2) tiles via `MapDataSource.streetViewTile` (fixed
  template `streetviewpixels-pa.googleapis.com/v1/tile`, keyless, JPEG bytes, same Google referer;
  NB `/v1/thumbnail` 403s but `/v1/tile` works - the old note tested the wrong endpoint). (3) `:app`
  `StreetViewTiles.load` stitches a zoom level's grid (v1 = zoom 2 = 2048×1024, 8 tiles, ~8 MB POT
  texture; NEVER the full 16384×8192 ≈ 400 MB), and `PanoramaView` (GLES2, `app/streetview`) textures
  it onto a sphere - drag = yaw/pitch, pinch = FOV. GL gotchas, device-proven: view from INSIDE (cull
  off), and use NATURAL U (`uv = u`, NO flip). Looking down -Z, screen-right is world +X = theta
  increasing = u increasing, so texU must increase left-to-right or the whole pano mirrors (backwards
  signage + reversed © Google watermark). An earlier `1 - u` was itself the mirror and mis-verified;
  plain `u` reads correct AND keeps drag grab-pull + the walk-arrow bearings consistent (user 2026-07-15,
  caught the mirror off the watermarks; don't reintroduce a U flip). The VM owns the bitmap lifecycle (the
  renderer does NOT recycle after `texImage2D` - texImage2D copies, so recycling there would double-free
  the state's reference); the screen feeds it once via LaunchedEffect, not the AndroidView update lambda.
  Pill is in `PlaceSheet` (no longer gated by HideExternalLinks - it's a first-class in-app surface now),
  overlay in MapScreen keyed on `state.streetView != null || streetViewLoading`. **v2 (2026-07-15):**
  zoom 3 tiles (4096×2048, sharper), 1.7x drag sensitivity, capture date (`panoNode[6][7]` = [year,month],
  shown in the attribution), **walk arrows** (`StreetViewPano.neighbors` = the local pano graph
  `[5][0][3][0]` de-cluttered to nearest-per-direction, excluding same-spot <4 m; the overlay projects
  each onto screen by `bearing - cameraYaw` within the FOV, polled each frame), and **time travel**
  (`StreetViewPano.history` = `[5][0][8]` `[neighborIndex,[yr,mo]]` resolved through the graph + this
  pano, newest-first; a clock chip switches captures via `timeTravelStreetView`, which loads tiles by
  pano id and keeps the base metadata so the arrows/dates return). **Two gotchas, both device-caught:**
  (1) walking fetches the neighbor BY PANO ID (`streetViewByPano` -> `photometa/v1`, keyless, node
  nested one deeper at `root[1][0]` with a `)]}'` guard - the parser handles both), NOT by nearest-
  location: a location lookup snapped to a different-year same-spot capture (green May imagery under a
  "December 2022" label). (2) the `PanoramaView` must NOT be `remember`ed keyed on panoId - `AndroidView`
  runs its factory once, so a new per-pano view instance leaves the OLD view (old texture) on screen
  after a walk while the date updates (new date, stale imagery); use ONE view for the viewer's life and
  feed it new bitmaps. **Opening pano = COPY GOOGLE (2026-07-16):** the search response's SV thumbnail URL
  (`streetviewpixels-pa…/thumbnail?panoid=…&yaw=…`) carries the exact pano id + camera yaw the Google app
  opens; `SearchParser.svThumb` regexes it out of the serialized entry (a distinctive constant, drift-proof
  vs a pb path) into `Place.svPanoId`/`svYawDeg`, and `openStreetView` uses them verbatim via
  `streetViewByPano`. The heuristics (nearest pano; street-of-address match; perpendicular probes for
  set-back geocodes whose alley cluster isn't graph-connected to the frontage; perpendicular-facing with a
  ±40° nudge) are the FALLBACK for entries with no thumbnail - don't re-order that ladder: geometry alone
  provably mis-picks (the 2005-address alley saga, 3 attempts before copying Google won). **COMPASS FRAME
  (2026-07-16, the root of every "faces the wrong way"):** Google's equirect puts the CAPTURE heading at
  the texture CENTER (u=0.5, verified by stitching a pano), while PanoramaView's yaw=0 looks at u=0.75 -
  so compass B = renderer yaw `B - captureHeading - 90`. Use `setCompass(panoHeading, faceCompass)` /
  compass-space `currentYawDeg()`; NEVER feed a compass bearing in as raw yaw, and never overwrite
  `StreetViewPano.headingDeg` (the texture reference) with a desired facing - that's `initialFacingDeg`.
  **OLD-PYRAMID GOTCHA (2026-07-16, device report "black panel on time travel"):** the tile pyramid
  is NOT one fixed shape - modern panos are 512·2^z wide but pre-2016 captures are 416·2^z (13312
  max, the 2007 gen sometimes only 4 levels), so the loader MUST size its grid from the pano's own
  `levelDims` (parsed per level from `[2][3][0]`); assuming 512·2^z requested tiles past the old
  grid's edge → black bands. `StreetViewTiles` picks the highest level ≤4096 wide, crops padded edge
  tiles, and scales a non-4096×2048 stitch up to it (GL needs POT for the 360° wrap; an old equirect
  still covers 360°×180° so scaling is exact). Time travel fetches the historical pano's OWN metadata
  by id (pyramid + heading - epochs differ by up to 180°!) and adopts its headingDeg into the shown
  pano; the screen's re-aim effect keys on headingDeg too, keeping the user's compass yaw across the
  swap. Verified live: a 2012 capture (416-pyramid) renders the full sphere.
  **Half-screen (2026-07-16):** StreetViewScreen is a top-aligned PANE (55% height, fullscreen toggle,
  Back exits fullscreen first), not a Dialog - the map stays live underneath. The viewer reports
  `onPose(lat,lng,compassYaw)` (throttled to ~per-degree) → MapScreen's `svPose: DoubleArray?` →
  VelaMapView draws the NAV PUCK + view cone (SV_SRC/SV_LAYER, data-driven `iconRotate` off the
  feature's "yaw" so a drag is one setGeoJson, identity-gated like the parking pin) and eases the
  camera to the pano on each HOP only (never per yaw frame), with `svTopInsetPx` (pane height) as
  camera TOP padding so the puck centers in the VISIBLE strip - the sheet-inset block must not
  clobber that padding while SV is open (it's gated on svPose==null; the SV close path restores it).
  PlaceSheet yields while SV is up (the bottom half must stay pure map). Mini-map TAP = pegman-drop:
  handleTap in VelaMapView pre-empts ALL other tap resolution while `svActive` and hands the LatLng to
  `onSvMapTap` → `moveStreetViewTo` (nearest pano, faceToward the tap; <8 m from the pano → down-street).
  FULLSCREEN GOTCHA (device-caught 2026-07-16): a SurfaceView's window hole does NOT follow a
  pure-Compose resize - the GL render grew but stayed cropped in the old half-screen hole. The
  PanoramaView is `remember(full)` (recreated on toggle) wrapped in `key(view) { AndroidView(...) }`
  (key() forces AndroidView to actually swap instances), with view-keyed effects re-feeding the texture
  and the CURRENT yaw (seenPano tracks pano-change vs view-change so a toggle keeps your look direction
  and a walk faces the new pano's initialFacing). The renderer's zoom is
  canonically the HORIZONTAL fov (fovX) - a fixed vertical fov made fullscreen NARROW the view
  ("it just zoomed"); holding fovX keeps framing and reveals more sky/ground, and fovX is also what
  the arrow overlay projects across the screen width. Remaining:
  walking can cross capture epochs (Google stays in-epoch; the neighbor entries carry no date to filter
  on), higher-zoom on pinch.
- **Routing is OPEN, not Google (2026-06-28).** Turn-by-turn comes from **FOSSGIS OSRM**
  (`RouteGeometry.route`, `steps=true`, per-mode `routed-car`/`-bike`/`-foot`) - complete,
  street-named maneuvers + real geometry. **Highways identify by `ref` not `name`** - `parseOsrmRoute`
  captures `ref`/`destinations`/`exits` (not just `name`) and `osrmPhrase` uses them ("Take exit 72B
  toward …"); `Maneuver.ref` feeds the banner shield even when the text shows a name (fixed 2026-06-30 - 
  before, highway steps were nameless + shield-less). **`routeOsrm` retries 3× w/ backoff** - a transient
  community-server blip otherwise drops nav to Google's abbreviated (nameless) steps. **And
  `googleDirectionsRetried` gives the GOOGLE side the same 3-attempt backoff (2026-07-14):** it had
  ONE shot, and a single degraded/empty keyless reply cost the whole fetch its traffic ratio,
  jam-snap and alternates - the picker then led with white-ETA free-flow OSRM routes that read
  minutes faster than the traffic-aware set and varied wildly between restarts (real-drive report).
  **Abbreviated fallback routes are TAGGED (`Route.abbreviatedSteps`, set on the OSRM-down branches
  + the nameRoute failure path) and SELF-HEAL:** `maybeRecheck` silently adopts a full-stepped
  same-course candidate over an adopted abbreviated route (same 250 m divergence test the ETA
  calibration uses), so a mid-drive OSRM blip no longer leaves the banner disagreeing with the
  blue line for the rest of the drive. **Since 2026-07-15 the same heal restores LIVE TRAFFIC
  (trafficUpgrade beside stepsUpgrade, no-downgrade guard on both); since 2026-08-04 a DEGRADED
  route also SHORTENS the recheck cadence (DEGRADED_RECHECK_INTERVAL_MS 20 s, capped at
  DEGRADED_FAST_TRIES=6 per route then back to ~2 min, issue #237) so the heal lands seconds
  after the open router recovers instead of leaving the nameless banner up for minutes, and a degraded candidate is
  FENCED OUT of everything ETA-comparative in maybeRecheck: trafficless never calibrates etaScale
  and trafficless-or-abbreviated is never OFFERED as a faster route - free-flow vs traffic-aware
  always "wins", which was the real-drive white-ETA/no-lanes/"suspiciously fast" incident (lanes
  come from OSRM steps, so an accepted abbreviated route also silently loses lane guidance).** Google's keyless
  `/maps/preview/directions` returns
  **abbreviated** steps for longer routes (a 6-mi route came back with 2 of ~10 turns), so it's
  demoted to (a) the **live-traffic source** - `GoogleMapsDataSource.applyTraffic` scales OSRM's
  free-flow duration by Google's in-traffic/typical ratio and maps its congestion spans onto the
  OSRM geometry - and (b) the **fallback router** when OSRM is unreachable. The two are fetched in
  parallel. Rationale: routing is a solved open-data problem; Google's edge is traffic/POIs/hours/
  reviews, not routing. **`OSRM_BASE` is the FOSSGIS community server (fair-use) - point at a
  self-hosted OSRM/Valhalla before any real release.** (This retired the keyless-step parsing as the
  primary path + the Nominatim "fill the missing road name" hack.)
- **Bike routing is SAFETY-weighted by default (issue #401, 2026-09-14).** `directions()` takes an
  early branch for `TravelMode.BICYCLE` when `RoutingPrefs.bikeSafe` (core holder, mirrored from
  the `app.vela.ui.BikeSafe` pref `bike_safe`, Settings > Navigation, default ON): the on-device
  obf bicycle profile (prefers signed cycle routes and lanes, no network) where a region covers
  the trip, BOUNDED like the avoid branch (6 s planning / 3 s urgent), else `ValhallaRouter`
  (FOSSGIS Valhalla `/route`, costing bicycle, `use_roads` 0.1, hybrid, alternates=2 for a plain
  trip, `through` stops). Valhalla maneuver types are mapped into the OSRM grammar
  (`osrmGrammar`) and phrased by `osrmPhrase`, so voice/banner/list are localized and identical
  to every other route; "bear left to stay on X" maps to a rename and folds silent. No Google
  traffic overlay for bikes. Toggle off = the plain fastest OSRM bike route. Probed 2026-09-14:
  same Davis trip, 26 maneuvers along a cycleway corridor at 0.1 vs four turns on a county road at
  0.9. `ValhallaRouterTest` parses a captured two-leg reply with a roundabout.
- **Congestion colors on every route (issue #403, 2026-09-14).** `applyTraffic` used to paint
  Google's spans only on a same-course route (mapped by fraction) and never on a multi-stop
  trip (`withSpans = false`), so a long trip that diverged anywhere and every trip with stops
  was solid blue. `RouteGeometry.transferSpans(from, to)` now carries the spans geometrically:
  each span's stretch on Google's line is sampled every 25 m and projected onto the other route
  through a cell grid (`SegmentGrid`, 0.005 degrees, so a ten-hour route stays cheap); samples
  within 35 m mark that along-distance, runs become spans (gap 80 m, min 40 m). Same course
  keeps the fraction map; anything else, alternates and multi-stop included, gets the transfer;
  what Google did not drive stays uncolored. `TransferSpansTest`.
- **Traffic-AWARE routing (option 3, 2026-06-28).** OSRM's free-flow route ignores live traffic, so
  when Google *rerouted around a jam* its path differs from OSRM's. `directions()` detects this
  (`RouteGeometry.divergent` - sample Google's polyline, true if any point strays >700 m from OSRM's
  line) and, only then, re-runs OSRM **through ~12 points sampled off Google's polyline**
  (`sampleVias` → `routeVia`) so we follow Google's jam-avoiding path *with* full OSRM street-named
  steps. Multi-waypoint OSRM returns one leg per via with spurious `arrive`+`depart` at each boundary
 - `parseOsrmRoute` filters all but the true first-depart/last-arrive. Free-flow routes (the common
  case) stay pure OSRM, untouched. The traffic-snapped route leads **only when it earns it** - its live
  ETA must be ≤ OSRM free-flow best × `SNAP_ETA_MARGIN` (1.2), else a divergent-but-not-faster snap steps
  aside for OSRM's clean route (fixed 2026-06-30 - the old code always led with the snap on divergence, the
  "fucky reroute"). The `directions` diag logs `snapKept`/`gEta`/`osrmFF`/`sameCourse` to tune the margin from real
  side-by-side data. **FREE-FLOW CALIBRATION (2026-08-04, issue #227):** OSRM's speed model has no
  signal timing, so on signalized arterials its free-flow time can run far under Google's TYPICAL
  for the same road (reporter diag: osrmFF 16 min vs typ 30 min, ratio 0.97 - the shown ETA applied
  the ratio to the wrong baseline and read absurdly fast). applyTraffic now rebases a same-course
  OSRM route onto Google's typical: durations (route + legs + per-maneuver, so nav remaining-time
  sums agree) scale by (gTyp*distScale)/osrmFF clamped 0.5-3.0, the live ETA becomes Google's real
  in-traffic figure, and trafficRatio stays traffic-vs-typical so the color/words don't turn red
  from OSRM optimizm. directions() computes ONE calibration from the top OSRM route vs gTop and
  applies it to every OSRM-derived route in the response (alternates share the speed-model bias;
  per-route calibration would re-rank them unfairly). **The basis is whichever route FOLLOWS
  Google's course (review 2026-09-12):** the top OSRM route when it does, else the via-snap.
  Before, a divergent top (Google routing around a jam, when accuracy matters most) got no
  calibration, the plain OSRM route kept its free-flow fiction and sorted as "Fastest" ahead of
  Google's honest alternates; and the snap's ETA-margin gate compared Google's live ETA against
  the RAW free-flow, so a jam-avoiding snap lost to the fiction every time. The gate now uses the
  calibrated free-flow, and the `directions` diag logs `cal=`. Multi-stop trips still take the
  ratio-only `applyTrafficRatio` path (open, #227 for stops). **Multi-stop trips are calibrated too (same review):** Google's keyless answer USED TO BE the DIRECT trip, so `speedCal` compared average SPEEDS (the distance difference cancels) and the via route went through `applyTraffic` with `withSpans = false`; `applyTrafficRatio` is gone, and the recheck's `etaScale` no longer jumps when the last stop is passed. **SINCE 2026-09-21 GOOGLE IS ASKED FOR THE TRIP THROUGH THE STOPS (issue #600 made the gap visible: "why are we hitting open source routers when google supports stops").** `DirectionsPb.withWaypoints` adds one top-level `!1m4!3m2!3d<lat>!4d<lng>!6e2` group per stop between the origin and destination groups (a repeated field; no enclosing count moves; verified live from a plain client on the Davis fixture: direct 15.3 mi / 21 min with three alternates, through Woodland ONE route at 45 min with per-leg distances). The multi-stop branch then mirrors the single-destination one: same course = the open via route with Google's real through-the-stops time and spans (`freeFlowCal` from durations, like a single trip); Google left the course = the open router is snapped along Google's line LEG BY LEG (`RouteGeometry.sampleViasThrough`: the samples of each leg with the real stop between them), kept on the same reach / length / spur / ETA-margin rules, with the stops passed as `routeVia(looseVias=)` so a stop set back in a lot does not trip the strict via-snap refusal that exists for sampled points; avoid on and the snap failed = Google's own abbreviated route through the stops (it honors the avoid) rather than a plain route that ignores it. **THE GUARD:** `RouteGeometry.stopsOnLine` (250 m to the nearest vertex of Google's line, in trip order) decides whether Google actually called at the stops; a template drift that dropped the waypoint groups would otherwise hand back the direct trip and read as a valid route, so a reply that misses a stop takes the OLD direct-trip handling (`speedCal`, spans off) and the diag line says `googleStops=IGNORED`. The `directions` multi-stop line is mirrored to logcat as `VelaDirections` (no coordinates). `speedCal` stays for exactly that fallback. NB `DirectionsParser`'s `start`/`end` paths (`[7][3][2]`, `[7][3][3]`) are the route's BOUNDING-BOX corners, not its endpoints; they only coincide on a southwest-to-northeast trip like Davis to Sacramento (found reading the via reply, where the "start" mixed Davis's latitude with Woodland's longitude). They feed only the no-geometry fallback line, so nothing shipped wrong, but do not build on them. A same-course primary also carries Google's `typicalLow/High` range (distance-scaled), so the depart-time chooser shows "usually X-Y" for it, not only for provisional alternates. **Per-alternate re-rank (2026-07-01):** each Google route in `root[0][1]` carries its
  OWN `duration_in_traffic` (`parseRoute` reads `summary[10][0][0]` per route), so the returned list is now
  **sorted by live in-traffic ETA - fastest leads, Google-style.** (Earlier note that this was "impossible"
  was wrong: it's only true for the OSRM-only alts, which share `gTop`'s ratio; Google's alts carry real
  per-route traffic.) **Sort key = the EXACT value the picker shows (2026-07-05, supersedes the earlier
  `* gRatio` "common-axis" attempt):** `compareBy({ durationInTrafficSeconds ?: durationSeconds }, { provisional })`.
  `RouteOption` displays `durationInTrafficSeconds ?: durationSeconds` and tags the min-SHOWN route "Fastest", so
  the sort MUST use the same expression - else (as `* gRatio` did) the top/selected route and the "Fastest"-tagged
  route diverge and the fastest-shown route isn't at the top (a real-drive bug, fixed). The axis is already fair
  without the fudge factor: PRIMARY routes go through `applyTraffic` (their `durationInTrafficSeconds` = free-flow
  × the top Google route's ratio) and Google's alternates carry their own per-route `duration_in_traffic`, so a
  route only falls back to raw `durationSeconds` when there's genuinely no traffic signal for it - and then
  sorting/showing that free-flow time is self-consistent. Do NOT bake an estimate onto `durationInTrafficSeconds`
  to "fix the axis" - `Route.hasLiveTraffic` keys off its nullness. Provisional routes are the stable tie-break.
  **Alternates = GOOGLE's own alternate routes, NAME-ON-PICK (2026-06-30):** we fetch all of Google's
  routes but used only the top; `directions()` now returns the named primary + each distinct Google route
  as a **provisional** `Route` (`Route.provisional` - polyline + live ETA now, turn-by-turn deferred),
  `dedupeRoutes`, prefers them over OSRM's free-flow alts, caps at `MAX_ROUTES`=4. Picking a provisional
  alternate (`MapViewModel.selectRoute` → `MapDataSource.nameRoute`, also on `startNav` as a safety) NAMES
  it - currently by snapping its polyline through OSRM (`routeVia`, guarded to reach dest) + re-applying
  Google's traffic. So only the route you drive gets snapped, and the picker loads fast. **Next = swap
  `nameRoute`'s snap for an on-device MAP-MATCH where the region's downloaded** (wobble-free; it was going to be GraphHopper's matcher, retired 2026-09-15 before it shipped); the
  snap stays the fallback. (NB: MapLibre vector tiles only cover the on-screen area, so they can't name a
  whole long route - a universal-clean version would need fetching+decoding the route's MVT tiles.)
- **Why not "always snap to Google's path"?** (measured 2026-06-28, the serverless question.) Google's
  keyless **polyline is complete** (decoded from `root[0][7][i]`) even though its *step text* is
  abbreviated - so we *can* always trace it. But doing it cleanly needs **map-matching**, and the
  public infra won't reliably give it: FOSSGIS **`/match` caps at 10 trace coords** (11+ → `TooBig`;
  confidence ~0.01 at that sparsity) and public **Valhalla `/trace_route` times out**. The serverless
  fallback - dense-waypoint `/route` (40–100 vias, no cap) - *does* reproduce Google's path exactly,
  **but a via landing on a turn gets swallowed into a via arrive/depart → ~1-in-10 named turns lost**
  (measured: dropped "turn right onto Village Green Drive"). That turn-loss is the exact bug we fixed,
  so we do **not** always-snap. Clean always-snap (and offline routing) is gated on an **on-device
  engine** - see the next bullet. Option 3 is the public-server stopgap and stays as the online/fallback
  path. **No backend needed for any of this** (the serverless constraint holds).
- **OBF MIGRATION IN PROGRESS (2026-07-23, issue #214; user sold on the format;
  DEVICE-VERIFIED on the 4a in airplane mode - see the canary numbers below).** Offline
  routing's successor is OsmAnd's `.obf`: `ObfRouteEngine` (:core) routes with OsmAnd's pure-Java
  router (GPLv3, vendored jars in `core/libs/` - gitignored, CI fetches from the `obf-runtime`
  release like the sherpa AAR; locally copy osmand-java.jar / osmand-shared-jvm.jar /
  gnu-trove-osmand.jar / kxml2-vela.jar from that release). MEASURED WHY: Berlin from the same PBF = GraphHopper
  graph 105 MB vs obf routing section 26.9 MB (3.9x); a target-sections obf (routing+address+POI,
  NO map/transport - `scripts/VelaObfShim.java` sets the IndexCreatorSettings booleans the CLI
  lacks) makes Germany ~2 GB where graph+pack was ~8. CoreModule binds `ObfRouteEngine`
  directly since 2026-09-15 (GraphHopper retired); avoids (toll/motorway/ferry) are DYNAMIC
  routing.xml params (`avoid_toll`/`avoid_highway`/`avoid_ferries`) so they work offline with no baked profiles,
  and bicycle/pedestrian profiles come free. Turn mapping pinned by ObfRouteEngineTest (CONTINUE
  is voice-silent - a mis-mapped u-turn gets swallowed; instruction text reuses ghPhrase so all
  languages come along). Bake: `scripts/build-obf-region.sh` + `merge-obf-manifest.sh` +
  `.github/workflows/obf-regions.yml` (matrix clone; the MapCreator tool is pinned on the
  `obf-tools` release); assets are RAW .obf (already deflate-compressed inside; download size ==
  installed size).
  **WITHOUT HH, A LONG OFFLINE ROUTE CAN FAIL OUTRIGHT, NOT MERELY RUN SLOW (measured 2026-08-17
  against a real baked Bayern obf, using ObfRouteEngine's own config and memory limits).** NB the
  threshold is REGION-DEPENDENT, not a fixed distance: a 150 km cross-file route in Saarland/
  Rheinland-Pfalz completed on the 4a in 69 s (2026-08-03), while the same distance across Bayern's
  denser network fails at the shipped limit. Treat the distances below as one dense sample, not a
  universal cutoff. The router does not degrade gracefully: past its budget it throws
  `IllegalStateException: There is not enough memory ... - limit 256 MB`, which is
  `ObfRouteEngine.MEMORY_MB`. Measured on a fast desktop, car profile:
    4 km   -> 0.87 s at 256 MB
    57 km  -> 5.65 s at 256 MB
    151 km -> FAILS at 256 MB; 4.6 s once given 1024 MB
    348 km -> FAILS at 256 MB and at 1024 MB; 41.9 s once given 3072 MB
  So the requirement scales steeply with route length, and **raising MEMORY_MB is not available to
  us**: the app runs under `largeHeap` (~512 MB total, and it already fights that ceiling - see the
  ambient fan-out and MemoryPressure notes), so a routing context wanting 1-3 GB cannot exist on a
  phone at all. **This means the obf migration as currently baked is a REGRESSION against the
  GraphHopper graphs for long offline routes**, because those ship CH (contraction hierarchies)
  and did a 24-mile route in 188 ms. HH is the obf equivalent, and `scripts/VelaObfShim.java` does
  NOT generate it. So HH is not the "follow-up if long routes measure slow" this file used to call
  it - it is a PREREQUISITE for offline feature parity, and it lands on top of a bake that already
  does not fit CI. Until HH exists, offline obf routing is a city/metro feature; anything intercity
  must stay online (GraphHopper was retired 2026-09-15, so there is no CH fallback anymore).
  **THE BAKE DOES NOT FIT A GITHUB RUNNER AT COUNTRY SCALE (measured 2026-08-16, the first time
  obf-regions.yml was ever run).** Luxembourg and Delaware baked fine (39 MB and 20 MB obf); Czech
  Republic and `de-bayern` both died with `OutOfMemoryError: Java heap space` at `-Xmx12g` on a
  16 GB runner, and Bayern (810 MB pbf) OOM'd AGAIN at 14g after three hours. Two traps in reading
  that: (1) **a sub-area is not automatically small enough** - Bayern IS one of the germany-sub
  rows, so #254's split helps download size but does NOT get a bake under the memory ceiling;
  (2) **a longer run is not progress** - the 14g attempt lasted 4x longer than the 12g one and
  still failed, because a nearly-full heap thrashes before it dies. `processInRam` already defaults
  to false, so that knob is not the answer. Since then (2026-09-04, measured, see the OBF BAKE
  paragraph): ParallelGC is on, and the ROUTING-ONLY LEAN bake is the default, which gets a
  US-state-sized extract (345 MB) through at 12g in 48 min; Bavaria-sized pieces (810 MB) still
  OOM in the first pass regardless of sections, so those need osmium chunks or an off-CI machine
  with 22g+ (`JAVA_HEAP` in build-obf-region.sh). The world bake can be dispatched with
  `skip_big`; the `big:true` rows are the ones that will not fit. Those flags were set from
  Geofabrik's Content-Length on 2026-09-06 at a 450 MB PBF threshold (56 of 294 rows, including
  the sub-area pieces that were missing it: Bayern, NRW, England, Java, Sudeste...). Re-run the
  HEAD sweep when adding rows; a sub-area is not small just because it is a sub-area.
  CUTOVER = the manifest, and the world bake STAGES it (2026-08-03): dispatching obf-regions
  with the default staging=true merges entries into `obf-manifest-staging.json`, which the app
  never reads - bake every group there, then ONE `gh release download/upload` copy of staging
  over the live name flips the whole catalog atomically (region-by-region merging into the live
  manifest would have shown early updaters a half-empty region list while the legacy catalog
  vanished). CUTOVER = the manifest: `refreshRoutingRegions` serves the obf catalog
  (`OBF_MANIFEST_URL`, `-PobfManifestUrl=` override) whenever obf-manifest.json has entries, else
  the legacy graph catalog - dispatching the obf-regions workflow IS the switch, no app release.
  The trade: no precomputed shortcuts, so a cross-city route costs seconds not ~200 ms (offline is
  the FALLBACK router, so size beats speed - user call); OsmAnd's HH precomputed mode is the
  follow-up if long routes measure slow on-device. **4a canary numbers (2026-07-23, release build,
  airplane mode, Berlin obf):** 1.6 km drive = 288 ms; 21 km cross-city drive = 9.1 s (252
  segments); same trip walking = 15.9 s. Steps carry names + B-road shields + sign destinations;
  delete via Settings removes the region and the engine drops its readers. **THREE ANDROID
  RUNTIME TRAPS, all invisible until the engine LOGGED its failures (tag `VelaObf` - the first
  canary swallowed them silently and read as "No drive route found"):** (1) commons-logging picks
  LogFactoryImpl by REFLECTIVE discovery, so R8 strips it and BinaryMapIndexReader's static init
  dies - consumer-rules keeps `org.apache.commons.logging.**`, and the discovery itself NPEs on
  ART anyway, so ObfRouteEngine's init pins `org.apache.commons.logging.Log` ->
  SimpleLog before any OsmAnd class loads. (2) OsmAnd instantiates `org.kxml2.io.KXmlParser`
  directly (a desktop-classpath assumption; modern Android hides its platform copy from apps) -
  `kxml2-vela.jar` on the obf-runtime release is upstream kxml2 2.3.0 with its bundled
  org/xmlpull/** deleted, because the stock Maven jar duplicates the platform XmlPullParser
  interfaces and R8 hard-fails ("Library class ... implements program class"). (3)
  `searchRoute` UNCONDITIONALLY lazy-loads the world-regions index (its missing-maps suggestion
  feature) from the working directory, which on Android is / and read-only -> EROFS on every
  route; the init pre-seeds `PlatformUtil.setOsmandRegions(OsmandRegions(false))` (the no-file
  ctor) and turns `RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS` off (the flag alone does NOT
  skip the load - the getOsmandRegions call sits before the flag check). STILL GH-ONLY: the speed-limit badge
  (currentRoadLimit) and the romanized-names sidecar (obf carries multilingual names natively,
  wired later). Place packs still download alongside obf regions until POI/address search moves
  onto the obf (phase 2). **The speed-limit badge reads the obf now (2026-09-15):**
  `ObfRouteEngine.currentRoadLimit` builds one small `RoutingContext` over the covering files (kept
  per region set, 32 MB limit, dropped in `shutdown`), snaps the fix with OsmAnd's
  `RoutePlannerFrontEnd.findRouteSegment` (`distToProj` is the SQUARED distance in meters; farther
  than 25 m = off the network), and reads `RouteDataObject.getMaximumSpeed(true)` (m/s; 0 = untagged,
  `NONE_MAX_SPEED` = derestricted, both blank; same forward-only and `< 150` rules the
  retired GraphHopper lookup used). The badge reads the obf only since the graphs went (2026-09-15). Harness: `ObfSpeedLimitProbeTest` runs against a real file with
  `-DvelaObf=<dir with delaware.obf + index.json>` (skipped otherwise; the Delaware fixture reads
  88 km/h on the Puncheon Run Connector, null on an untagged street and on open water);
  `probeRoadLimit` prints what the lookup saw. NB US roads are often untagged in OSM (US 13 at
  Dover has no maxspeed), so a blank badge there is the data, not the lookup. **And the romanized
  road names come with the obf route (same day):** `Route.roadNamesLatin` (local name -> Latin
  alias, empty for every online route) is filled by `ObfRouteEngine.toRoute` from each driven
  way's `name:en` / `name:latin` (`RouteDataObject.getName("en")`, validated by
  `ObfRouteEngine.latinAlias`, the same Latin-only rule as the tile path and the sidecar bake),
  and `NavController`'s observer merges it through `Host.onNavRoadLatin` the moment a route is
  adopted, so an offline Hebrew drive speaks and shows real names without the routing-graphs
  sidecar (the second and last GraphHopper-only feature; the graphs were retired the same day). Harness `ObfRoadNamesProbeTest`
  (`-DvelaObf=<dir with israel-and-palestine.obf>`): a Tel Aviv drive returns Hebrew -> Latin
  pairs (Arlosoroff, Ibn Gabirol, Sderot Rothschild). Core unit tests run with
  `unitTests.isReturnDefaultValues = true` since then, so the engine's `android.util.Log` lines
  no-op on the JVM. COUNTRY + SUB-AREA both (2026-08-03, the #214 reporter's ask): the unsplit
  country stays the headline row, and big countries ALSO offer first-level sub-areas as smaller
  optional rows - tools/routing-regions.json carries sub-area rows beside the whole-country row
  (which stays group `europe`/`south-america`/... with big:true), so the obf bake produces both;
  nobody is forced into fragments and a city user grabs a fraction of the country. **Extended from
  Germany alone to all 15 big countries Geofabrik actually sub-divides (issue #254, 2026-08-15):
  159 sub rows in groups `<country>-sub`** - Brazil (the reported one, 1.5 GB whole), France,
  Spain, Poland, Italy, Netherlands, Czechia, Norway, Australia, Japan, India, Indonesia, Great
  Britain, California, Germany. Rows are GENERATED from Geofabrik's own `index-v1.json`, so every
  `pbf_url` is a real extract: the id is `<country>-<geofabrik child slug>`, the name is the LOCAL
  name plus the country ("Nord-Norge (Norway)", matching the de-* rows - Geofabrik's English
  glosses made three-part names that wrapped to three lines in the picker). Note Geofabrik ids are
  path-shaped for US/Canada (`us/california`, not `california`) and Great Britain's sub-extracts
  live under `united-kingdom`, not `great-britain` - deriving the id naively finds neither. The
  17 remaining big rows (Texas, Ontario, Mexico, Sweden, Ukraine, Argentina, South Africa, ...)
  have NO Geofabrik sub-extracts, so they stay whole. **The workflow's `group` input takes a LIST
  now, plus the shorthand `all-sub`** - one dispatch bakes every sub-area group instead of 15.
- **GraphHopper is RETIRED (2026-09-15).** The first offline engine (GraphHopper 11 over per-region
  Contraction-Hierarchies graphs, 2026-06-30 to 2026-09-15) is gone from the tree: `GraphHopperRouteEngine`,
  `OfflineRouteEngine` (CoreModule binds `ObfRouteEngine` directly), `RoutingGraphStore` (its manifest
  parser survives as `app/offline/RegionCatalog`, the `RoutingRegion` row shape stays for every catalog),
  the `names.tsv.gz` sidecar and `scripts/roadnames_build.py`, `tools/graphbuilder`, `buildSrc` (the ASM
  ByteBuffer patch), `core/util/ByteBufferCompat`, the `graphhopper-map-matching` dependency and its R8 keeps
  (about 10 MB of APK), `routing-graphs.yml` with `scripts/build-routing-region.sh` /
  `merge-routing-manifest.sh`, and `ROUTING_MANIFEST_URL` (`OBF_MANIFEST_URL` is the one routing catalog).
  What the obf does instead: routes (`ObfRouteEngine`), the speed-limit badge (`currentRoadLimit` off the
  way's maxspeed) and the romanized road names (`Route.roadNamesLatin`), all covered above. On the first
  launch after the update `LegacyGraphs.purge` deletes `filesDir/graphs` (nothing reads it) and
  `mapvm_graphs_retired` says to download the regions again; `RouteSource.GRAPHHOPPER` stays in the enum so
  old trip files still read back. The `routing-graphs` RELEASE stays on GitHub as an infra release (older
  app versions still fetch its manifest); the prune rules that protect it are unchanged. The three ART
  workarounds, the CH weighting rules and the pre-API-34 fixes that used to be documented here are
  history now; `git log -- core/src/main/java/app/vela/core/data/GraphHopperRouteEngine.kt` has them.
  Offline phrasing moved to `core/data/OfflinePhrases` (`phrase` + `inBox`, `OfflinePhrasesTest`).
- **Offline PLACE packs - whole-region POI/address search, Organic-Maps-style (`app/offline/PoiPackStore` +
  `core/data/OfflinePacks`, DONE 2026-07-07, device-verified: a misspelled offline dish search from the
  downloaded test suburb → the intended dumpling restaurant in a city across the state, with address).** Downloading a state (routing region) also pulls its place pack - a
  per-region SQLite db baked by CI from the SAME Geofabrik PBF (`scripts/build-poi-region.sh`: osmium
  tags-filter → export geojsonseq → `poipack_build.py` → SQLite → zip; workflow `poi-packs.yml`, a matrix clone
  of routing-graphs.yml with `merge-poi-manifest.sh`; release tag `poi-packs`, manifest
  `poi-pack-manifest.json`, `POI_PACK_MANIFEST_URL` / `-PpoiPackManifestUrl=`). **Pack schema is NORMALIZED,
  not the app stores' own schema** (that naive shape was 761 MB for a large state): `poi(id,name,lat,lng,category,address,
  phone,website,hours)` + `streetname(sid,street,street_norm)` + `addr(hn,sid,city,lat,lng)` +
  `streetpt(sid,lat,lng)` → a large state = 335 MB raw / **143 MB zipped** (163k POIs, 2.8M addrs, 1.2M street pts, 92k
  street names). The normalization is also the QUERY strategy: match street names first (~90k-row scan), hit
  the big tables only through sid/hn/lat indexes - never a LIKE scan of millions of rows. `OfflinePacks`
  (:core singleton) holds the opened read-only dbs; `OfflinePoiStore.search` runs its same SQL on each pack
  (identical poi columns), `OfflineAddressStore` has dedicated pack paths (`packSids`/`packQuery`/
  `packStreetGeom` + reverse-geocode JOINs) merged into query()/streetGeom()/reverseGeocode(); counts include
  packs. `poipack_build.py` PORTS `normalizeStreet`'s ABBREV and OverpassPois' category formatting - keep them
  in sync. Lifecycle: pack downloads after its region's graph (`downloadPoiPack`), deletes with it
  (`deleteRoutingGraph`), `registerPacks()` at VM init; graphs installed before packs get a **"Get places"**
  button on the Settings row (`downloadPoiPackFor`, with a "no pack published yet" status when the manifest
  lacks the region). **Heads-up progress:** `RegionDownloadCard` in MapScreen mirrors the voice card - 
  `routingDownloadingId`/`routingDownloadPct` then `poiPackDownloadingId`/`poiPackDownloadPct`, named by
  `regionDownloadName`. Local pack test: build one with the script's osmium+python steps, serve manifest+zip
  on :8099, `adb reverse`, `-PpoiPackManifestUrl=http://127.0.0.1:8099/poi-pack-manifest.json`. **After
  pushing, dispatch Actions → "Build offline place packs"** (group=us etc.) to publish packs + manifest - 
  until then "Get places" reports no pack available.
  **Pack freshness (2026-07-07): rev + monthly cron + row-level deltas.** Manifest rows carry
  `rev`/`updatedAt`/`counts{poi,addr,streetpt,streetname}` and optionally `delta{fromRev,url,sizeMb}`;
  `poi-packs.yml` has a monthly `schedule` cron (3rd, 07:15 UTC) whose prep step selects ALL catalog regions.
  `build-poi-region.sh` reads the LIVE manifest for the old rev, downloads the previous zip BEFORE clobbering
  it, builds the delta (`scripts/poipack_delta.py`, SQL EXCEPT per table into del_/ins_ tables), and publishes
  it only when it is under half the full size. App: installed revs in `poipacks/revs.json`
  (`PoiPackStore.installedRev`); Settings shows "Update available" + an **Update places** button when the
  manifest rev is newer; `MapViewModel.downloadPoiPack(update=true)` applies the delta via
  `PoiPackStore.applyDelta` ONLY when installedRev == deltaFromRev, else full download. applyDelta runs one
  transaction (delete-by-full-row via a rowid JOIN with NULL-safe `IS` matching, then insert), verifies every
  table count against the manifest before committing, and re-registers packs on both success and failure.
  **sids are STABLE content hashes** - SHA-1 of `street_norm` truncated to a positive 63-bit int, collision
  fails the build; NEVER a counter (a counter renumbers millions of rows on one mid-order insertion and the
  delta balloons to pack size). `TABLE_COLUMNS` in PoiPackStore mirrors `poipack_build.py` +
  `poipack_delta.py` - keep all three in sync (`PRAGMA user_version=2`). Gotcha: KDoc in PoiPackStore must
  not contain a literal `del_*/ins_*` (the `*/` ends the comment). `OfflinePoiStore.search` orders
  whole-query name matches first so they survive the internal 400-row cap (thousands of category hits used
  to crowd out an exact name match in a state pack; found live while verifying deltas). v1-format packs
  (published before rev existed) have no rev; their first v2 rebuild yields no usable delta so clients just
  full-download once, then deltas kick in.
- **The AREA SAVE reads the region's PLACE PACK, not Overpass (issue #304, 2026-09-13).**
  `downloadOfflinePois` first looks up the smallest place-pack region covering the area's center in
  the poi-pack manifest: pack installed = nothing to do; graph installed but no pack (a region from
  before packs, or a failed pack download) = `downloadPoiPackFor`; neither = a status line saying
  the region download this save also triggers brings the pack. Only an area NO pack covers still
  runs the three Overpass queries below (POIs, padded addresses, streets), which after the 425-row
  catalog is nowhere Geofabrik publishes. The "Update saved areas" card goes through the same
  function. The Nominatim maintainer filed #304 against the app's Overpass use; the remaining
  callers were the traffic-control layer and the opt-in speed-camera layer, moved the same day:
  **ROAD FEATURES ARE BAKED PER REGION (`app/data/RoadFeatures`, `scripts/build-road-features.sh`,
  `.github/workflows/road-features.yml`, release `road-features`).** Lights, stop signs, level
  crossings, speed humps and fixed speed cameras come out of the Geofabrik extract as one gzipped
  `lat<TAB>lon<TAB>kind` file per catalog region (kind S/T/R/H/C; a US state is a few hundred KB),
  emitted + merged with the poi-packs matrix shape. The app fetches the manifest (6 h TTL), downloads
  the smallest covering region's file ONCE into `filesDir/roadfeatures/<id>.bin` (re-downloaded
  when the manifest's `updatedAt` moves), keeps up to 4 regions in memory with a 0.1 degree grid,
  and answers the viewport box, the nav corridor, the camera corridor and the pass-the-light
  enrichment from memory. `MapViewModel.roadFeaturesCover*` returns LOADED / NONE / FAILED: NONE
  (no region in the manifest) is the only case that still reaches Overpass; FAILED shows nothing
  and retries next viewport (a failure is never cached). **Nothing is baked until the workflow is
  dispatched** (`gh workflow run road-features.yml -f group=us` etc.; all 425 catalog rows fit in
  two dispatches); until then the manifest is empty and every phone keeps the Overpass path. Local
  test: bake one region with the osmium + `scripts/road_features_tsv.py` steps, serve it with a
  manifest on :8099, `adb reverse`, build with `-ProadFeaturesManifestUrl=`.
  **THE CORRIDOR QUERY ANR'D THE APP ON A LONG ROUTE (2026-09-13, three ANR traces from a
  Davis to San Francisco demo drive, caught before the 0.4.1099 successor stable shipped).**
  `controlsAlong`/`camerasAlong`/`signalsAlong` ran on the MAIN thread from
  `refreshNavRouteControls` and tested every feature in the route's bounding box against every
  polyline segment: ~66k features x 15,890 polyline6 points. Three fixes, all measured on the
  4a: the callers wrap the queries in `withContext(Dispatchers.Default)`; `SegmentIndex`
  simplifies the line to 3 m (15,890 -> 1,339 segments) and buckets the segments into 0.01
  degree cells so each feature tests only the segments near it (index 30 ms, scan 10-20 ms);
  and `parse` reads the inflated bytes once and parses the decimals by hand with a primitive
  grid build, because the line/substring/toDouble/boxed-list version took 14.3 s for Northern
  California's 176k features (now 0.7 s, same counts). Logcat `VelaControls` prints all three
  timings. A region parse still happens once per process at the first query; if that ever
  matters, cache the parsed arrays in a binary sidecar.
- **Offline forward geocoder - typed address → coordinate, no signal (`core/data/OfflineAddressStore` +
  `OverpassPois.fetchAddresses`/`fetchStreets`, DONE 2026-07-07, device-verified in the test suburb).** So an arbitrary
  typed street address routes offline (not only addresses that are an indexed POI). Populated when a map area is
  downloaded (`MapViewModel.downloadOfflinePois`) from keyless Overpass over a bbox **padded to a ~15 km min span
  around the viewport center** (`GEOCODE_PAD_DEG=0.09`, so a saved area covers the surrounding metro, not the few
  on-screen tiles - the tile-viewport bbox gave only 8 addresses; the padded box gave **8591 addresses + 1466
  streets**). TWO OSM sources into ONE SQLite db (`vela_offline_addr.db`, v2): **`addr:housenumber` points**
  (`addr` table) for house-precise hits, and **named road centerlines** (`street` table, thinned to ~1 pt/120 m
  by `toStreetPts`) for a street-level fallback where OSM has the road but no house numbers (the US-suburb
  reality - this is the SAME gap the OpenAddresses/Microsoft *render* overlays fill, but those are PMTiles, not
  queryable as a geocoder, so the geocoder uses OSM). `geocode()` is layered: (1) exact house number, (2)
  **interpolate** between the two bracketing mapped numbers, (3) nearest mapped house on the street, (4) nearest
  point on the street centerline. `normalizeStreet` expands abbreviations both ways ("Pl"↔"place", "SE"↔
  "southeast") so all spellings hit the same rows. Wired into the offline search branch (`MapViewModel`, gated by
  `OfflineAddressStore.looksLikeAddress` so "coffee" doesn't hit it) AND the network-error fallback; `haveArea`
  counts `count()`+`streetCount()` so a street-only suburb isn't misreported "no data". Big Overpass bodies → the
  no-call-timeout `offlineDownloadHttp` (same rule as the graph/overlay downloads). The result Place routes
  through the on-device engine. Device-verified wifi-off: a typed nearby street address → *5 min
  · 1.5 mi* through the offline engine. **Reverse-geocode backfill for offline POIs:** most US chains have no OSM
  `addr:*` (a chain came back as bare state initials), so `MapViewModel.backfillOfflineAddress` - on selecting a place
  while offline, when its address has no house number (`.none { isDigit() }`) - calls
  `OfflineAddressStore.reverseGeocode(loc)` (nearest mapped house ≤60 m, else nearest street ≤150 m, bounded
  lat/lng box scan) and fills `selected.address` if still selected. Device-verified: the offline Applebee's
  card backfilled to its full street address. **Quiet offline indicator (no banner):** `MapUiState.offline` (a reactive
  `ConnectivityManager` default-network callback, `observeConnectivity`, fails safe to online) drives a grayed
  globe-slash + "Offline" in `SearchBar` (bare map only) and a globe-slash chip **inline under the category
  chips** in `MapScreen`'s top Column (gated to the same bare-map state the chips show in, so it never trails a
  results list) - the old "Offline results" status line and the old bottom-left chip are gone. **The directions
  ETA subtitle** (`PlaceSheet.DepartTimeChooser`) only says "current traffic" when `route.hasLiveTraffic`; an
  offline (traffic-less) route shows the arrival time with no traffic note. **Upgrade nudge:** the address
  index is built at download time, so areas saved before the geocoder have tiles+POIs but no addresses.
  Settings → Offline shows a "Update saved areas" card when `regions.isNotEmpty() && offlineAddressCount == 0`
  (via `MapViewModel.offlineAddressCount`); tapping it runs `refreshOfflineDataForSavedAreas` - iterates every
  saved `OfflineRegion`, reads its `OfflineMaps.boundsOf` and re-runs `downloadOfflinePois` over each box.
- **Open building-footprint overlay (`app/offline/OverlayTileStore` + `VelaMapView`, DONE 2026-07-04,
  device-verified in the test suburb).** Fills the map's building gaps where OSM is thin (a suburb the
  Microsoft→OSM import never reached) with **Microsoft US Building Footprints (ODbL)**. Off-device, CI bakes
  ONE `.pmtiles` per US state (`scripts/build-overlay-region.sh` → tippecanoe `-l building -Z14 -z16
  --drop-densest-as-needed`; `-Z14` not `-Z12` - starting at z12 ballooned a state to 271 MB, z14 → 197 MB) →
  `building-overlays` GitHub release + `building-overlay-manifest.json`, matrix workflow
  `.github/workflows/building-overlays.yml` (clone of the routing one, `MANIFEST_MODE=emit` +
  `scripts/merge-overlay-manifest.sh`), catalog `tools/overlay-regions.json`. In-app: `OverlayTileStore` is a
  single-file sibling of `RoutingGraphStore` (`filesDir/overlays/<id>.pmtiles` + `index.json`; PMTiles-magic
  guard). **The overlay STREAMS online - no download needed to see houses (2026-07-05).** `refreshBuildingOverlays`
  runs on every camera-idle (`onViewport`) and emits, per view, a list of full `pmtiles://` URIs: a
  **`pmtiles://file://<abs-path>`** for any DOWNLOADED region (offline), and **`pmtiles://https://…<region>.pmtiles`**
  for the covering regions in view that AREN'T downloaded - the **UNION of up to the 3 smallest covering
  boxes, NOT just the single smallest (2026-07-06)**: a neighbor's rectangular bbox can spill across an
  irregular border AND be smaller - Kansas's box crosses the Missouri River, covers all of NW Missouri
  (St Joseph) and beats Missouri's box, but kansas.pmtiles is EMPTY east of the river, so the old
  single-pick rendered NO footprints there (probed: the doll-museum z15 tile has 413 features in
  missouri.pmtiles vs 36 river-bank scraps in kansas's; the data was never the problem). Streaming the
  union lets whichever archive has the data paint; an empty region's range requests cost ~nothing - MapLibre 11.7+ reads that hosted archive by
  **HTTP range requests** (verified: GitHub release assets 302→release-assets host with `accept-ranges: bytes`,
  MapLibre follows the redirect), fetching only the visible tiles, so footprints appear as you pan. The manual
  **`MapViewModel.downloadOverlayForArea`** (still smallest-covering-box, pulled alongside the area's tiles) is now
  ONLY for going fully offline. Render: `VelaMapView`'s `LaunchedEffect(buildingOverlays, styleRef, darkTheme)`
  adds each URI as a `VectorSource` (used verbatim - the URI already carries `pmtiles://file://` or
  `pmtiles://https://`) + a `FillLayer` `setSourceLayer("building")` **`addLayerBelow` the OSM `building` layer**,
  themed to the exact OSM building fill/outline (`#323f54`/`#3f4e66` dark, `#dde1e7`/`#c4c9d1` light) so overlay
  footprints are indistinguishable from real OSM ones and OSM still wins wherever it has data. `buildingOverlays`
  is de-duped so panning within one region doesn't churn the map sources. **The load-bearing DOWNLOAD bug was NOT
  the render** - it was the `callTimeout(0)` rule above: the 197 MB body aborted at the shared client's 12 s cap,
  silently (that only ever mattered for the offline download; streaming reads a few KB/tile). Device-verified:
  the downloaded test suburb from the local file + downtown/suburban Reno (Nevada, not downloaded) streamed from the hosted
  `nevada.pmtiles` (131 range requests, no PMTiles errors). NB GitHub release hosting works but isn't a CDN - a
  real deployment should host the PMTiles behind a CDN for snappier range reads. `OVERLAY_MANIFEST_URL`
  BuildConfig overridable `-PoverlayManifestUrl=` like routing. BREAKING-ish: an overlay is DATA (ODbL), orthogonal
  to the app's GPLv3, obligation met by tippecanoe `--attribution` + the release publishing derived tiles under ODbL.
  **World catalog (`tools/overlay-regions.json`, 361 rows - ~250 base regions plus chunk pieces):** TWO Microsoft sources picked by each row's
  `source`, both handled by the ONE build script (`SOURCE` env): **`us-legacy`** = a US state's single
  `.geojson.zip` (Microsoft US Building Footprints, 51 states+DC); **`ms-global`** = a world country's
  quadkey-partitioned GeoJSONL from Microsoft's **Global ML Building Footprints** (`global-buildings/dataset-links.csv`
  → `awk` the country's `Location` rows → curl+gunzip each `.csv.gz` into one ndjson → tippecanoe `-P`; ~199
  countries). Country **bboxes are the union of the dataset's own z9 quadkey tiles** (self-consistent with where
  footprints exist); US-state bboxes are Geofabrik extract bounds. **Big countries are CHUNKED** (>1500 MB
  compressed source → India, Brazil, Russia, Germany, Japan, …18 of them): the catalog splits each into
  sub-national pieces by **quadkey PREFIX** (`qkprefix`; adaptive recursive split until each chunk ≤ ~1500 MB - 
  India → 24 pieces), the build script's awk filters the country's rows to that prefix, and each chunk gets its
  own union bbox so the **app's smallest-covering-box rule picks the piece covering the user** (no app change,
  and it fits CI disk + hosts under GitHub's 2 GB/asset limit). Only the whole-US aggregate + continental
  aggregates + duplicate Locations (CzechRepublic→Czechia, DemocraticRepublicoftheCongo→CongoDRC) are dropped.
  The catalog is 361 regions - **over GitHub's 256-job matrix cap** - so each row carries a `group` (`us` / `world`
  / `chunk`) and dispatch is **one group at a time** (`-f group=world`); run-level concurrency is OFF so groups
  build concurrently, only the merge job serializes. The app/manifest are source-AGNOSTIC - the emitted manifest
  row is always `{id,name,url(asset),sizeMb,bbox}`, so no app change was needed for countries OR chunks.
- **Open house-number overlay (`VelaMapView` + `scripts/build-address-region.sh`, DONE 2026-07-05,
  device-verified in the test suburb).** Microsoft footprints have geometry but **no addresses**, so house numbers
  come from a SECOND overlay: **OpenAddresses** address POINTS → per-state `.pmtiles` (`-l address`, keep the
  `number` prop) → `address-overlays` GitHub release + `address-overlay-manifest.json` (`ADDRESS_MANIFEST_URL`,
  `-PaddressManifestUrl=`). **The bake DEDUPES per-unit/parcel repeats (2026-07-10, `scripts/dedup-addresses.py`):**
  OpenAddresses carries one row per unit/parcel, so a complex repeated its number all over its
  footprint on the map; the build keeps one point per (number, street, ~150 m cell). Takes
  effect per region on the next `address-overlays` workflow run (streamed tiles pick it up
  automatically; a LOCALLY DOWNLOADED overlay keeps the old points until re-downloaded).
  Data source = OpenAddresses batch API: `/api/data?source=us/<st>/statewide&layer=addresses`
  → its current `job` → `https://v2.openaddresses.io/batch-prod/job/<job>/source.geojson.gz` (GeoJSONL of Points
  with `number`/`street`; **42 US states have a `statewide` source**, the rest are county-only). Render:
  `VelaMapView`'s `LaunchedEffect(addressOverlays, …)` adds a `VectorSource` (the URI) + a **`SymbolLayer`**
  `setSourceLayer("address")`, `textField(get("number"))`, `textFont(["Noto Sans Regular"])`, size 10, gray +
  white halo, **minZoom 17 + stepped textOpacity (0 below z19, 1 at 19+)** - the visible behavior is
  still numbers-only-at-the-~50-ft-view (17.5 carpeted whole blocks, user 2026-07-13), but the zoom gate
  CANNOT live in the layer's minZoom: the address archives carry tiles **only at z16-17**, and **MapLibre's
  pmtiles path never cold-fetches a tile clamped 2+ levels below the camera on a cold source** - minZoom 19
  meant a fresh launch that zoomed straight in fetched nothing, silently (`querySourceFeatures` = 0 forever),
  and it *looked* intermittent because tiles resident from a lower-zoom browse overzoom fine. The layer
  arms at 17 (being in zoom range is what drives tile fetching, even with the text invisible) and the 50 ft
  gate is the opacity step (found + fixed by alltechdev in the vela-dpad fork, ported 2026-07-13; issue #131).
  Residual edge: a session whose camera STARTS past ~z19 without ever dipping lower still fetches nothing - rare,
  the camera restores to browse zoom. NB the opacity-0 = invisible-to-queryRenderedFeatures gotcha (PR #125)
  doesn't bite here: below z19 the numbers were never tappable anyway - 
  inserted below `vela-controls` (see the LAYER ORDER warning below). **Streams online exactly like buildings**
  (`MapViewModel.refreshAddressOverlays(center)` on every camera-idle → the union of up to the 3
  smallest covering regions' `pmtiles://https://…` URIs - same spilled-bbox shadowing fix as the building
  overlay, see above; reuses `overlayStore.manifest()` which is manifest-URL-agnostic).
  **⚠️ LAYER ORDER (2026-07-06, device-verified fix):** the addr layers are inserted **BELOW `vela-controls`**
  (→ below the ambient POI icons), NOT `addLayer`/top - MapLibre places symbols TOPMOST-FIRST, so numbers
  stacked above the ambient layer grabbed collision boxes before the business icons placed and **EVICTED them
  at z16+** (the "Applebee's icon disappears on zoom-in" bug: reproduced on a big storefront building -
  prominence-scaled icons collide the most; small neighbors survived). Below the icons, numbers place last
  and yield - Google's behavior. Also: while the overlay is active the basemap `vela-housenumber` layer is
  hidden (visibility NONE in the same LaunchedEffect) - both drew the SAME address at a slight offset
  (device-seen: the same number doubled at a slight offset). **NOT** the
  building overlay (different data + a Symbol not Fill layer + its own release/manifest). CI:
  `.github/workflows/address-overlays.yml` (clone of building-overlays), catalog `tools/address-regions.json`.
  **The house numbers fill the exact gap the basemap `vela-housenumber` (OSM `addr:housenumber`) leaves in new
  suburbs** - verified real house numbers in the test suburb rendered over the MS footprints.
- **Traffic lights + stop signs drawn on the map (`OverpassTrafficSignals.fetchControlsInBox` + `VelaMapView`,
  2026-07-05). + STATIC ROAD AIDS (2026-08-08, device-verified crossbuck at a Davis spur crossing):**
  `railway=level_crossing` (dark disc + white crossbuck X, RAILX_IMG) and `traffic_calming`
  bump/hump/table/cushion (amber disc + bump glyph, HUMP_IMG - only the shapes a driver FEELS;
  island/chicane/choker are lane geometry, excluded) ride the SAME pipeline: `TrafficControl.kind`
  (enum SIGNAL/STOP/RAIL_CROSSING/SPEED_HUMP, replaced the old stop boolean), one shared
  `controlSelectors()` in both the viewport-box and route-corridor queries, per-kind 30 m
  clustering, same layers/zoom gates/caps. Built as the buildable subset of "aids on the road"
  after every LIVE incident source proved dead (see ROADMAP: Google=binary vt tiles,
  Waze=reCAPTCHA-gated). OSM `highway=traffic_signals` (a stoplight icon) and `highway=stop` (a red STOP octagon) as a
  non-interactive `SymbolLayer` (`vela-controls`, icons `vela-signal`/`vela-stop`) drawn **beneath** the POI dots
  + pins, `minZoom 16`. **CLUSTERED PER INTERSECTION (2026-07-25):** OSM maps one control node per APPROACH (a
  four-way stop = four `highway=stop` nodes), so `refreshTrafficControls` merges same-type nodes
  within 30 m (`MapDeclutter.cluster`, the same radius the spoken pass-the-light counting uses)
  to their centroid before the cap - one drawn glyph per junction, like Google, and fewer
  allowOverlap symbols to render. **Icon sizing/visibility (2026-07-06, device-verified in downtown Davis):** `iconSize`
  is a zoom-interpolated expression (~0.75 at z15.5 → 1.05 at z17 → 1.5 at z19) - the flat 0.55 was too small to
  spot, especially tilted in nav; and `iconAllowOverlap(true)`+`iconIgnorePlacement(true)` so they ALWAYS draw
  (controls are sparse - one per junction - and the earlier collision-off-below-POIs was culling them away on the
  browse map, so the user couldn't see them; Google shows all of them at street zoom).
  **Z-ORDER (2026-07-09, device-verified downtown Davis):** the VISIBLE controls layer inserts at the very
  BOTTOM of the symbol stack (below the first basemap SymbolLayer), so a stop sign can never cover a street
  name, city label, or POI icon/text - they were stomping labels when the layer sat above the basemap. An
  INVISIBLE claim twin (`vela-controls-claim`, iconOpacity 0, allowOverlap true + ignorePlacement FALSE)
  stays at the old spot above the basemap labels: it places first and claims a collision box, so street
  names shift away from sign positions instead of printing on/next to one. Vela's own layers sit above the
  claim and place before it, so it can never evict a POI. Don't collapse the two layers back into one -
  draw order and placement order are the same thing in MapLibre, so "draws under labels" and "labels avoid
  it" genuinely need two layers. Data is keyless Overpass (sibling of the
  `fetchAlong` nav-landmark fetch + `OverpassPois`), fetched by `MapViewModel.refreshTrafficControls` from
  `onViewport` **only at z ≥ `CONTROLS_MIN_ZOOM` (16)**. Controls are STATIC, so it fetches a box padded 50%
  beyond the viewport and **reuses it while the center stays in the inner half** (`controlsBox`) - panning/driving
  through an area triggers no refetch, sparing the fair-use Overpass server; only nearing the box edge refetches
  (single-flight + 350 ms settle). The layer/updater are identity-gated like markers/ambient (`lastAppliedControls`)
  so a nav speedo tick doesn't re-tessellate them. No app setting (zoom-gated); no PMTiles/CI (live Overpass, unlike
  the building/address overlays). NB the `TRAFFIC_*` constants in `VelaMapView` are a DIFFERENT thing - Google's
  live-traffic raster overlay; the controls use `CONTROLS_*`. **DURING NAV the layer is fed by ONE
  ROUTE-CORRIDOR fetch per driven route instead (issue #248, 2026-08-08):** the moving camera crossed the
  cached box edge constantly, so the viewport path refetched over and over against sometimes-dead mirrors
  and the icons showed rarely / vanished quickly mid-drive. `refreshNavRouteControls` (hooked into the
  navSession observer at nav start + every reroute/faster-route swap, keyed on endpoints+length so a
  same-course stepsUpgrade/trafficUpgrade heal never refetches; skipped during hermetic replays) calls
  `OverpassTrafficSignals.fetchControlsAlongCorridor` - Overpass `around:` LINESTRING form, polyline
  sampled to ≤250 points to keep the GET URL mirror-safe, ~120 m corridor - then the same
  cluster-per-intersection pass, cap `CONTROLS_ROUTE_CAP` (800, nearest-to-start). While the corridor set
  is loaded, `refreshTrafficControls` returns early (it must neither refetch NOR run its z<16 clear
  branch, which would blank the layer at the 15.5 nav zoom floor); a FAILED corridor fetch leaves the
  key unset so the viewport path stays the fallback, and nav-end (`clearNavRouteControls`) nulls
  `controlsBox` so the next browse settle repaints. Needs a real-drive glance to confirm density/size feel.
- **Speed cameras + SPOKEN approach warning (issue #229).** The LAYER (`OverpassSpeedCameras`,
  `SpeedCams` holder, Settings > Navigation > Cameras "Speed cameras", OFF by default) already shipped: OSM
  `highway=speed_camera`, keyless, viewport-box + area-cached, `out body` (never `out tags` - the
  ALPR empty-layer trap). The 2026-08-28 addition is the WARNING the reporter actually asked for
  ("informed about an incoming radar control" - a dot does nothing while driving):
  `OverpassSpeedCameras.fetchAlongCorridor` runs ONCE per driven route (sibling of the controls
  corridor fetch, keyed identically so a same-course heal never refetches or re-arms warnings
  already heard), each hit is projected onto the route by `:core` `nav/RouteProjection` and
  anything not genuinely on it is dropped (a corridor returns the parallel street too), and
  `:core` `nav/CameraAlerts.due` decides when to speak. Timing is SPEED-SCALED (12 s of lead,
  floored 150 m / capped 600 m) because a fixed distance is ample in town and ~2 s on a motorway;
  one announcement per camera per route; never for a camera behind you; silent below 2 m/s so
  sitting beside one is not narrated. A new route key EMPTIES `routeCamMeters` before the fetch
  (review 2026-09-12): a reroute resets traveledM to 0, so the old route's distances against it
  announced a camera kilometers behind you until the corridor fetch landed. Flipping "Warn me out loud" on MID-DRIVE fetches the current route's cameras at once (a `snapshotFlow` on the two toggles in the VM); it used to wait for the next reroute. Unit-tested (`CameraAlertsTest`, `RouteProjectionTest`).
  **The spoken half is its own nested opt-in** (`SpeedCamWarn`, "Warn me out loud", shown only
  while the layer is on): being spoken to is a different ask from seeing a marker, and warning
  about cameras while driving is legally restricted in some countries. Respects the global
  spoken-directions mute like every other prompt. STILL fixed installations only - mobile speed
  traps need a live crowd feed the keyless model has no source for.
  **Speeding alert (issue #404, 2026-09-14):** Settings > Navigation > "Speeding alert"
  (`app.vela.ui.SpeedingAlert` holder, pref `speeding_alert`, OFF by default) says "You're over
  the speed limit" once you have been over the posted limit for 4 s; re-arms after 8 s back
  under it, never more than once per 45 s. The limit is the badge's own (`speedLimitKmh` from
  the offline graph, else `speedLimitOverlayKmh`) and the 5 km/h tolerance matches the badge's
  red state, so the voice never contradicts it. Timing is pure in `:core nav/SpeedingAlerts`
  (`SpeedingAlertsTest`); `MapViewModel.maybeWarnSpeeding` runs beside `maybeWarnCamera` on the
  nav tick, logs a `K` trip note, and `speeding.reset()` on nav end.
  NB `nav/RouteProjection` duplicates the projection in `nav/RouteBar` (issue #228, open in
  parallel); whichever merges second should delegate rather than keep two copies.
- **Plate (Flock / ALPR) camera alerts + DIRECTION-AWARE "on route" (2026-09-16).** Two opt-ins
  in Settings > Navigation > Cameras next to "Avoid surveillance cameras" (`app.vela.ui.FlockNavAlert`, prefs
  `flock_nav_alert_card` / `flock_nav_alert_voice`, both OFF, independent of the `Flock` layer
  toggle because the bundled set is loaded either way): a heads-up card (`host.flashStatus`, the
  same card the closing-soon warning uses) and a spoken "License plate camera ahead" through
  `voice.speak` (so the global mute applies). `NavController.refreshRouteFlock` runs once per
  driven route, keyed EXACTLY like `refreshRouteSpeedCams` (a same-course heal never re-arms),
  called from the same `!replaying || demoDriving` block; it waits up to 60 s for
  `FlockCameras.isLoaded`, takes `FlockCameras.along(poly)`, projects with
  `RouteProjection.alongMeters(.., 45.0)`, and merges cameras within 40 m along the route with
  `:core nav/CameraAlerts.group` (one alert, plural wording when count > 1).
  `maybeWarnFlock` reuses `CameraAlerts.due` (12 s lead, 150-600 m, 2 m/s floor, never behind).
  Nav end calls `clearRouteFlock`; a `snapshotFlow` on the two toggles projects mid-drive.
  No trip `K` note, matching the speed-camera warning (only the speeding alert writes one).
  **Direction rule (`:core nav/CameraFacing`, `CameraFacingTest`):** for a camera within the
  distance gate, find the NEAREST non-degenerate route segment, take its bearing, and compare with
  the camera's facing as lines: `d = |facing - bearing| mod 180`, `min(d, 180 - d) <= 50` counts.
  No facing (empty 4th TSV column / unparseable OSM `direction`) counts. Applied in
  `FlockCameras.along` (route counts, avoid re-rank, alerts), `OverpassAlprCameras.fetchAlong`
  (the pre-load fallback) and the route bar's CAMERA marks. The map layer and cones still draw
  every camera.
- **Surveillance-camera (Flock / ALPR) layer (`OverpassAlprCameras` + `refreshFlock` + `FLOCK_LAYER`, device-verified
  2026-07-12).** Settings > Navigation > Cameras > "Surveillance cameras" (`app.vela.ui.Flock` holder, **ON by default since 2026-07-13** -
  it's a headline feature and the bundled dataset makes it free to draw; `FlockRouteAlert` route-avoid stays
  OFF by default since it changes route choice) draws the
  community DeFlock project's `node["surveillance:type"="ALPR"]` OSM nodes as a purple camera badge, keyless via
  Overpass, sibling of the traffic-controls layer (per-viewport, area-cached `flockBox`, 350 ms settle, `FLOCK_MIN_ZOOM`
  **11** fetch AND layer minZoom **11** - route-overview visibility, re-landed 2026-07-13 now that the bundled
  dataset + stream-parse killed the giant-box OOM that reverted the first z11 try; keep the two gates IN LOCKSTEP,
  the 13 fetch / 13.5 layer era proved a fetch-without-draw dead band, vela-dpad issue #131). **TWO bugs found in device verification (both fixed):** (1) the Overpass `out`
  statement was `out tags`, which for a NODE returns id + tags but **omits lat/lon** - so `OverpassAlprCameras` parsed
  every element to null (no coords) and the layer was ALWAYS empty (this is why it "never drew"); fixed to `out body`
  (verified: Atlanta Ponce City Market went 0 -> 5 cameras, purple badges visible). (2) `Flock.init` was NOT called in
  `VelaApp.onCreate` (unlike `Traffic`/`TransitLayer`), so the persisted toggle read `false` on EVERY launch - the
  layer silently turned itself off after a restart; fixed by initializing it there. Real DeFlock nodes tag the vendor
  as `manufacturer` ("Flock Safety"), not `operator`, so the parser falls back to it. Coverage is OSM's - dense in US
  metros (Atlanta metro ~1571 nodes, a mid-size suburban metro ~200), sparse in a given ~1 km high-zoom box, so cameras show
  best around arterials at a neighborhood zoom, not a quiet residential block. NB you can't browse to a far city and
  see them if free-drive-follow keeps recentering on your GPS - it fetches YOUR viewport (fine for the real use case:
  you driving through a covered area). **THIRD bug found 2026-07-13 (device): the fetch used a SINGLE hardcoded
  endpoint `overpass-api.de`, which regularly answers HTTP 504 "dispatcher" under load - so a fetch over a box that
  genuinely HAS cameras failed and the layer silently stayed empty, on BOTH the map AND the route-count path (both
  call `fetchInBox`).** Fixed with **`OverpassEndpoints`** (`core/data`): a shared endpoint list (primary +
  `kumi.systems` / `maps.mail.ru` / `private.coffee` mirrors) and a `run(http, query){ onBody }` failover runner
  that tries each endpoint in turn, uses the FIRST 2xx, and returns null only when EVERY endpoint fails. **All three
  keyless Overpass callers route through it** (`OverpassAlprCameras`, `OverpassTrafficSignals`, `OverpassPois`), so
  one overloaded instance no longer blanks flock cameras, stop signs/lights, or the offline OSM POI/address index.
  Proven: `overpass-api.de` was 504-ing while `maps.mail.ru` returned 16 Flock nodes over the same box.
  **Any NEW keyless Overpass fetch MUST go through `OverpassEndpoints.run`, never a bare hardcoded endpoint.**
  **BUNDLED + HOSTED on-device dataset (2026-07-13, supersedes the live Overpass path for cameras):** the
  whole global DeFlock set is tiny (~124k points), so it's baked into a gzipped TSV `lat<TAB>lon<TAB>operator<TAB>direction` (4th col = facing degrees since 2026-07-21, cardinals normalized at bake, empty untagged; the app decodes 3-col files too, and a facing CONE layer `vela-flock-dir` draws under the badge for tagged nodes)
  (~1.3 MB) by `scripts/build-flock-cameras.py` and queried on-device by **`app/data/FlockCameras`** (flat
  lat/lng arrays + a 0.1 deg grid index, parsed once off the main thread in `VelaApp`). Map layer draws
  INSTANTLY (no per-viewport network - the "why an API not a tile" report); route "passes N cameras" count
  is instant + RELIABLE (the live Overpass fan-out per tile was slow and often returned 0, so the avoid
  re-rank had no data). `refreshFlock`/`refreshFlockOnRoute` use `FlockCameras.inBox`/`.along` when
  `isLoaded`, falling back to `OverpassAlprCameras` only in the ~seconds before load (or if unreadable).
  **TWO tiers, newest wins BY VERSION, enforced in the loader (2026-07-23):** `ensureLoaded` compares the downloaded copy's version against the bundled floor's and loads the higher, deleting a download the bundled floor has passed - preferring any existing download served a pre-direction 3-column file over the newer 4-column bundled data, and the facing cones drew at launch (Overpass fallback) then vanished on the first viewport refresh. The tiers:  a **bundled floor** (`assets/flock_cameras.bin` + `assets/flock_cameras_version.txt`)
  so a fresh install has cameras instantly + offline; and a **hosted copy** on the `flock-cameras` INFRA
  release that `FlockCameras.refresh` downloads to `filesDir/flock/cameras.bin` when the manifest version
  beats what's on disk (`FLOCK_MANIFEST_URL`, `-PflockManifestUrl=` override) - so **camera data updates
  WITHOUT an app release** (the user's ask). CI **`.github/workflows/flock-cameras.yml`** (weekly Monday
  cron + dispatch) re-bakes + re-hosts the `.bin` + `flock-manifest.json`; version is a `YYYYMMDD` int
  (bundled floor = 20260713). **`.bin` NOT `.gz` on purpose:** aapt special-cases a `.gz` asset and silently
  un-gzips + renames it at build time (broke `open("...tsv.gz")`); a neutral extension is left intact and we
  gunzip it ourselves. Device-verified 2026-07-13: 124,406 loaded, purple badge drew with no network wait,
  route counts `[10,10,11]`, AND the hosted refresh downloaded a newer version (20260714) + hot-swapped +
  is idempotent on relaunch. **DRAWN badges cluster below street zoom (2026-07-25):** a Flock corner mounts several
  single-direction heads, so `vela-flock-cluster` (own source, 40 m `MapDeclutter` merge computed
  at upload time in VelaMapView) draws ONE badge per install from z11/13 up to z16, where the raw
  per-camera layer + facing cones take over; the browse-13/route-11 minZoom gate now lives on the
  cluster layer. Route camera COUNTS stay per-head on purpose. NB "avoid" still only RE-RANKS the alternates Google/OSRM offer (fewest-camera
  within a small detour); it does NOT graph-route around cameras. **To publish the first hosted copy, dispatch
  Actions -> "Flock cameras" once** (until then every install just uses the bundled floor).
- **Transitous is the PRIMARY departure-board source (2026-07-13, phase 1 of the GTFS adoption).**
  `core/data/transit/Transitous` talks to the community MOTIS instance at `api.transitous.org` - the
  open-GTFS + GTFS-Realtime aggregator (transit's FOSSGIS-OSRM: keyless, fair-use, identifying UA sent).
  `fetchStopDepartures` now calls `Transitous.board(lat,lng)` FIRST for any transit-category or
  Intersection place: `map/stops` finds the stop by PROXIMITY (no Google/OSM name correlation at all),
  and `stoptimes` on the nearest stop's PARENT station id returns EVERY route with realtime flags and
  the agency's own route colors - a hub's bays merge for free (device-verified: a corner that gave 1
  route via the Google blob shows 6 lines with official pill colors + live countdowns). The result maps
  into the SAME StopDepartures model, so the whole board UI renders unchanged. **Transitous boards
  REFRESH every 30 s while the sheet is open (2026-07-13):** `startBoardRefresh` re-queries the open
  feed on the countdown clock's cadence and swaps the board in place, self-canceling the moment the
  selection changes; Google-fallback boards stay one-shot on purpose (a refresh there is a whole
  WebView load). The Google blob paths
  (fetchBoardFrom / resolveIntersectionStopBoard) remain the FALLBACK where Transitous lacks coverage.
  `buildBoard` is pure + unit-tested (TransitousTest). Remaining phase-2 candidate: transit
  directions via `/api/v1/plan` as a FALLBACK only - Google stays the primary transit router on
  purpose (its ETAs are traffic/history-aware; GTFS-RT only knows current lateness).
- **Canonical GTFS stops drawn on the map (2026-07-13, phase 2 of the Transitous adoption,
  device-verified).** At z >= 15 (`TRANSIT_STOPS_MIN_ZOOM`) the viewport's transit stops come from
  `Transitous.stopsInBox` (`map/stops`) and draw as a blue bus badge + stop-name label
  (`TRANSIT_STOPS_LAYER` in VelaMapView, sibling of the flock layer: area-cached box in the VM,
  350 ms settle, identity-gated source upload). One icon per STATION - bays dedupe onto their
  `parentId` in the VM, matching how the board queries the parent. **Tapping an icon opens the
  board DIRECTLY by stop id** (`onTransitStopTap` -> a lightweight `gtfs:<stopId>` place +
  `Transitous.boardFor` - zero Google resolution, zero name correlation; device-verified: tap ->
  named stop sheet + live board in one hop). **Wherever this layer has coverage the basemap's OSM
  bus icons hide** (applyData flips `poi_transit`'s filter to exclude class "bus", restoring the
  captured original filter when coverage goes - rail/airport stay basemap) so a stop can't draw
  twice at slightly different corners. **Offline floor = `app/data/TransitStopCache`**: every
  successful viewport fetch overwrites its area in a 24-area LRU JSON on disk, so the places a
  user actually visits keep fresh canonical stops with no extra machinery (the flock-dataset
  freshness property; global GTFS is too big to bundle, the visited-area cache is the
  equivalent). Offline/fetch-failure reads the covering cached area; a never-visited area falls
  back to the OSM basemap icons (filter restored). A fetch blip never blanks drawn stops.
  Regional GTFS stop packs (whole-state stops baked into the poi-pack pipeline) are the future
  hard-offline version - see task/ROADMAP.
- **Directional curb pairs merge into ONE icon (2026-07-13, device-verified).** US GTFS names both
  curbs of an intersection identically and carries NO direction field (verified against the raw
  `map/stops` JSON), so the map drew two overlapping same-named badges and each tap showed only
  half the departures. `Transitous.mergeDirectionalPairs` (same NAME within `PAIR_MERGE_M` = 160 m,
  proximity-clustered) collapses a pair to one representative at the pair's midpoint carrying the
  other ids in `MapStop.siblingIds`; the VM applies it after the parentId dedupe, and
  `TransitStopCache` persists `sib` so offline redraws keep the merge. `boardFor` (badge tap) and
  `board(lat,lng)` (proximity/Google-place path) merge stoptimes across representative + siblings,
  and `buildBoard`'s (route, headsign) grouping naturally shows both directions as separate rows.
  Direction-suffixed names (BRT-style "NB Station"/"SB Station") differ as strings so they never
  merge; geometry-based direction labels were rejected because the feed has no bearing data and
  street diagonals make guessing unreliable. Transit directions are untouched - they walk to the
  itinerary's exact boarding coordinate. Unit-tested (pair -> midpoint + sibling; same name across
  town stays separate; NB/SB stays separate).
- **Constrained / satellite networks (issue #235).** The manifest carries
  `PROPERTY_SATELLITE_DATA_OPTIMIZED` (value = the PACKAGE NAME, not a boolean - Android's docs
  are explicit; added 2901e0af 2026-08-04), which is what lets carriers who gate satellite service
  (Rogers/AT&T/KDDI) pass Vela's traffic at all. **The declaration is only half the contract** -
  Android requires a self-declared satellite-optimized app to ADAPT on such a link, which is the
  half added 2026-08-10: `app/ui/ConstrainedNetwork` reads `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED`
  (API 36) and `TRANSPORT_SATELLITE` (API 35) BY NAME through reflection - compileSdk is 35 and
  minSdk 26, so hardcoding either framework integer would be a guess that could silently mean
  something else on another release; absent constants yield null and the answer is false, because
  absence of the signal is not evidence of a constrained link. Detection rides the EXISTING
  `observeConnectivity` default-network callback (it already fires on capability changes, exactly
  when a handset falls back to satellite) -> `MapUiState.lowData` + the `:core` `LowDataMode.enabled`
  flag (same app-writes-a-core-flag seam as `LowRamMode`, which `:core` cannot read a holder for).
  What actually changes on a constrained link: the per-place PHOTO walk is skipped (the heaviest
  single transfer) and the ambient fan-out takes the LEAN path (8 terms at `!7i30` instead of
  15 at `!7i60`) - the same trade LowRamMode makes, for bytes rather than heap. NOT verifiable
  without a real satellite link; the plumbing is what was tested.
- **Route bar (issue #228, 2026-08-28, Settings > Navigation "Road ahead bar", OFF by default).**
  A strip down the LEFT edge during nav (opposite the FAB stack) showing the road AHEAD:
  congestion bands from `Route.trafficSpans` plus the static furniture already fetched along the
  corridor (lights, stops, level crossings, speed humps, ALPR cameras). Model is pure + tested in
  `:core` `nav/RouteBar` (`RouteBarTest`); the strip is `app/ui/nav/RouteBarStrip`.
  **It shows a 5 km WINDOW, not the whole route (`RouteBar.WINDOW_M`) - the first cut scaled to
  the entire remaining trip and was device-proven useless:** on a 769 mi demo drive every nearby
  mark collapsed into the bottom pixel and the bar read as a plain gray stick. Near the end the
  window shrinks to the destination (`reachesDestination`). TomTom's original also carries live
  HAZARDS; ours deliberately cannot (every keyless incident source is a proven dead end), so it
  draws only what the map already knows. Two clocks: marks are projected onto the polyline ONCE
  per route (`RouteBar.alongMeters`, 40 m corridor so a parallel street is not claimed as yours),
  the model is rebuilt per nav tick from that cache. Portrait only + never in PiP, deliberately -
  issue #297 is already about landscape being crowded (the PiP gate was missing until the
  2026-09-12 review; the strip sat outside the `!pipUi` block). A merged cluster keeps the member
  that says the MOST (`Mark.priority`: camera > crossing > hump > stop > light), because ALPR
  cameras hang on signal masts and first-by-distance hid every one behind the light's dot. The bar's total is the POLYLINE's own length (`totalM`, cached beside the marks): traveledM and the marks are measured along it while `Route.distanceMeters` is the router's figure. The projection cache keys on the mark lists' IDENTITY (a replaced set with the same count kept stale marks), and `RouteProjection.alongMeters` takes one cosine per point, not per segment.
  ⚠️ **INIT-ORDER TRAP (cost a launch crash, device-caught):** `refreshRouteBar()` was first called
  from the main `init` block, but `settingsPrefs` is declared ~3400 lines further down the class,
  and Kotlin runs property initializers + init blocks in DECLARATION order - so it read a null
  SharedPreferences and every launch died with an NPE before the map drew. It now lives in its own
  `init { }` placed immediately AFTER the `settingsPrefs` property. Any future pref read that must
  happen at construction goes there too, not in the main init block.
- **Road name inside the nav bar (issue #553, 2026-09-16).** A fourth `RoadLabel` placement,
  `IN_BAR` ("Inside the bottom bar"): the floating pill is not drawn and `NavBarTop` shows the road in
  its handle row (small chevron + name, ellipsized) on both the bar and the open step sheet's header;
  `barRoadName` in MapScreen computes it with the pill's rules (leg road or passed rename, ref first,
  romanized for Latin UIs). The chevron row stays the tap / D-pad button. Not the default.
- **"Searching for GPS" is pinned ABOVE the puck (user 2026-09-18),** measured through the same
  `puckScreen` offset the road pill uses (the pill goes below, this goes above), with the old
  bottom-center placement as the fallback while no puck position exists. The dot is what has gone
  gray, and the bottom band already holds the speed widget and whatever the right-edge stack slides
  out.
- **Current-road pill under the puck (issue #288, 2026-09-03).** Google's treatment: a rounded
  label directly beneath the nav puck naming the road you are ON. The road is the one entered by
  the LAST MANEUVER PASSED (`maneuvers[stepIndex - 1]`) - the same source the banner's shield
  already uses - preferring its `ref` ("US-23 S", what the reporter's mockup showed) and falling
  back to the street name; romanized through `SpokenScript.forDisplay` like the banner.
  **It follows SILENT RENAMES along the leg (2026-09-13, user: the name changes a mile down the
  same road and the pill never did).** `foldRenames` folds a rename CONTINUE into the previous
  maneuver so it is neither a card nor a prompt, and until now the folded name was simply lost.
  It is kept on the leg as `Maneuver.renames` (`RoadRename(atMeters, road, ref)`, ascending), and
  `Maneuver.roadAt(traveledOnLeg)` answers the road you are on; the pill and the banner's shield
  both call it with `distanceMeters - nav.distanceToNextManeuver`. Unit-tested in VelaLogicTest.
  NB `TripLog` does not record renames (its M lines carry no such field), so a REPLAY of a saved
  trip shows the old behavior; adding a line kind means a `TripScrub` decision first. Hidden
  while PREVIEWING a step (previewing must not change where you "are"), in PiP, and until a puck
  position exists. Positioned from `VelaMapView`'s new `onPuckScreen` callback, which projects the
  drawn puck to screen px - **reported only when it moves >2 px**, because the follow camera parks
  the puck at essentially one spot and pushing it per frame would recompose the label 60x a second.
  The pill is width-capped and CLAMPED into the viewport so a long name near a screen edge cannot
  run off it.
- **Nav smoothness trace (`app/diag/NavTrace`, Settings > Diagnostics, OFF by default, issue #251
  2026-08-10).** One row per nav frame - t, along-route progress, speed, bearing WINDOW, chordBrg,
  displayBearing, live camera bearing, frame dt - into a bounded 72k ring (oldest dropped), written
  to a CSV only on export. Recorded from the VelaMapView motion ticker, gated on the toggle so it
  costs nothing off. **It deliberately carries NO position data** (no lat/lng, no street, no
  wall-clock): every column is a bearing, an along-route distance, a speed or a timing, which is
  what separates the three jitter causes (frame drops vs route geometry vs fix cadence) while
  staying safe to attach to a public issue - unlike a recorded TRIP, which is raw GPS and must
  never be posted. Built because demo-drive measurements proved a bad proxy for real drives (the
  demo path had its own 3x clock bug, see the nav-camera notes).
- **Share diagnostics is functional now (2026-07-13):** `DiagLog` (opt-in breadcrumb ring, :core)
  PERSISTS to a bounded `filesDir/diag_log.jsonl` (appended per event, reloaded at init, deleted on
  opt-out) - it was in-memory only, and since the bug being reported usually killed or preceded a
  process restart, the export was empty essentially every time. `DiagExporter` SCRUBS the export:
  coordinate-looking decimals (3+ places) round to 2 (~1 km) so the JSON is safe to post publicly,
  with a header note saying so. Still no backend, still user-routed via the share sheet.
  **Redact places in exports (2026-09-15, #507):** `DiagScrub` holds both levels; the toggle (pref
  `diag_redact`, Settings > Diagnostics, always shown since 2026-09-16 because it also starts trip
  shares on the widest trim) makes the diagnostics export round to
  ONE decimal (~10 km), replace quoted search terms and intents with `[redacted]`, drop a nav
  start's destination label, keep only the host of any URL, blank `cid=` values and drop the
  reviews probes' detail (page text). Counts, zoom levels and error text stay. `DiagScrubTest`
  pins each rule; add a case when a new breadcrumb carries a name or an address.
- **Public transit uses the same hidden WebView** (`app/web/WebDirectionsFetcher`).
  A plain `/maps/preview/directions` GET with the transit flag (`!3e3`) is silently
  downgraded to a *driving* reply (same TLS-fingerprint bot-detection as photos), so
  the WebView instead navigates the `/maps/dir/<olat>,<olng>/<dlat>,<dlng>/data=!4m2!4m1!3e3`
  page and reads the itinerary set out of `APP_INITIALIZATION_STATE`. **Depart/arrive time:** the
  board is time-dependent, so a scheduled request replaces the plain `!4m2!4m1!3e3` with Google's
  time block - `!4m6!4m5!2m3!6e{0=depart,1=arrive,2=last}!7e2!8j<LOCAL-clock seconds>!3e3` (the `!4m` numbers
  are DESCENDANT counts, so the inner group grows `4m1`→`4m5` and the outer `4m2`→`4m6`; verified
  against a real Google transit-with-time URL - an earlier `!4m8!4m7` guess had the wrong counts and
  Google silently fell back to "now"). **`!8j` is a LOCAL clock, not a unix timestamp (issue #433,
  2026-09-14):** Google reads the seconds as wall-clock-as-UTC, so the fetcher adds the phone's
  zone offset to the picker's epoch; sending the true epoch shifted every schedule east of
  Greenwich by the offset (BST an hour early, UTC+3 three hours). **Preferred vehicles (issue
  #431, 2026-09-14):** `!5e{k}` entries (0 bus, 1 subway, 2 train, 3 tram) sit in the same `!2m`
  options group ahead of the time block; the fetcher builds the group from an entries list and
  sizes the `!4m` wrappers from it (`entries + 2` inner, `+ 3` outer). `MapUiState.transitPrefer`,
  `setTransitPrefer` refetches, chips under the time chooser on the transit tab. **Gotchas:**
  the directions payload is the **longest** `)]}'`-guarded string under slot `[3]`
  (a ~1.7 KB stub sits alongside the ~165 KB real one - take the longest, and poll
  for it: the SPA fills it a beat after page-finish). `TransitParser` (`:core`,
  takes the raw string so `:app` stays out of kotlinx.serialization, like
  `PhotosParser`) reads `root[0][1]` = trips, each trip's **summary at `trip[0]`**;
  `trip[1][0][1]` is the per-stop leg tree. Calibrated + device-verified Davis→Sacramento
  2026-06-18. **Full stop detail (2026-07-07, Miami→Aventura capture, unit-tested):** a RIDE
  leg carries its stop block at **`leg[5]`** - board `[5][0]`, alight `[5][1]`, **stop count
  `[5][2]`**, intermediate list `[5][7]` (each stop node: name `[0]`, agency code `[1]`, and
  time tuples - real-time arr/dep at `[2]`/`[3]`, timetable at `[7]`/`[8]`, so RT-vs-timetable
  epochs give "N min late"); **headsign `leg[0][14][2][1][0]`**, agency phone `leg[0][6][4][0][4]`,
  service alerts `leg[0][9][k][2]`. Fare is scanned defensively from the trip summary (usually
  absent - most US agencies send none). NB `parseLines` allows a **1-char** line name (single-digit
  bus routes like "9" are real; the old ≥2 guard dropped their pill). Each stop node's **coordinates
  are `[4][2]` (lat) / `[4][3]` (lng)** - `parseStopTime` reads them into `TransitStopTime.location`,
  and `assignWalkEndpoints` wires each WALK leg's `walkFrom`/`walkTo` from the adjacent ride's
  alight/board stop (falling back to the trip origin/dest, which `parse(raw, origin, dest)` threads
  through). The UI then fetches that walk leg's turn-by-turn steps **on demand** via the normal walk
  router (`MapViewModel.walkDirections` → OSRM foot) - no extra transit RPC. **The expanded
  itinerary DRAWS ON THE MAP (issue #233, 2026-08-08, device-verified):** expanding a chooser row
  sets `MapUiState.transitPreview` (`onTransitRowExpanded`; cleared on refetch, and collapsing only
  clears it if that row still owns it) -> `ensureTransitPreview` in VelaMapView draws ride legs as
  agency-colored lines THROUGH the stops (board + intermediates + alight coordinates from the same
  payload; stop-to-stop chords - the keyless data carries no track geometry) with white stop dots on
  top (board/alight large, in-between small) and walk legs as dotted gray links, all inserted below
  the route line layer (empty in transit mode) so the drawing sits above roads + the satellite
  raster and below labels; a camera-fit branch (sibling of the route fit, keyed on coords + insets)
  frames the trip between the endpoints card and the chooser. MapScreen gates the param to the
  open, non-navigating TRANSIT chooser - OR to step-by-step transit nav (issue #232, 2026-08-08,
  device-verified): there the WHOLE guided itinerary draws and `transitNavLeg` (the guided leg's
  index) narrows the camera fit to THAT leg's coords, re-framing on every Next/auto-advance;
  `TransitNavSheet` became a BOTTOM PANE (48% height, rounded top, Street-View-pane grammar) so
  the map is actually visible during guidance - it was a full-screen Surface and the guidance
  read as a bare text list (the reporter's complaint); `cameraBottomInsetPx` has a transitNav
  case (0.48 screen) so the leg frames in the visible strip, `fabChromeOk` gained a transitNav
  gate (the P/locate FABs, scale bar and satellite attribution drew OVER the pane), and the
  top search-bar chrome hides during guidance too. **Step-by-step transit
  guidance** (Moovit-style, `TransitNavState` + `startTransitNav`/`advance`/`back`/`endTransitNav` in
  `MapViewModel`, `TransitNavSheet` in `PlaceSheet`) walks the itinerary leg by leg, speaking each
  cue (`transitStepSpoken` → the `transit_nav_*` strings; the walk cue's Google-abbreviated
  duration expands via `SpeechText.spokenEnUnits` — "10 min" was READ as the literal "min",
  user 2026-08-08; digit-anchored regexes + unit-tested so names like "M St" never rewrite;
  English guidance only, other locales keep their own hl= abbreviations) and auto-advancing when GPS reaches the leg
  end. The auto-advance is **latched** (`maybeAdvanceTransitNav`, `TRANSIT_ARM_M=90`/`TRANSIT_ARRIVE_M=40`):
  a leg only advances once it's been ARMED by being >ARM_M from its end, so a transfer hub can't cascade
  through legs and a short final walk can't fire a premature arrival.
- **SUBWAY LEGS RENDERED AS EMPTY WALKS (issue #284, fixed 2026-08-31, live NYC capture).** Two
  independent defects in `TransitParser`, both proven against a real Harlem->Wall St payload:
  (1) **A line drawn as a BULLET carries no text pill.** `[14]` is a list of tagged entries and
  tag 5 is the line identity, expressed EITHER as a text badge at `[1]`
  (`["M101",1,"#1d59b3","#ffffff"]`, buses) OR - with `[1]` NULL - as an agency icon at `[2]`
  (`[3,"us-ny-mta/2.png",null,"2 Line",...]`, NYC subway). `parseLines` only matched the pill, so
  a subway leg yielded no line, and `mode = line?.mode ?: WALK` turned every one into an empty
  walking step. `iconLineName` now reads the name off the icon FILENAME. **The agency prefix is
  the load-bearing rule**: a line icon is operator-scoped and contains a "/" (`us-ny-mta/2.png`)
  while the generic vehicle icon is bare (`subway2.png`, `bus2.png`) - without that test a bus
  would invent a line called "bus2". Filename not the label beside it ("2 Line"), because the
  label is localized.
  (2) **`guessMode` matched mode words inside PLACE NAMES.** It scanned every short string in the
  subtree with "bus" tested first, so a "2" train to **Flat-BUS-h Av** reported as a BUS
  (device-proven: the live capture guessed BUS for a subway leg). It now reads ICON FILENAMES
  ONLY, which are language-neutral and per-mode (bus2/subway2/rail/tram/ferry/walk); Columbus,
  Bushwick and Brisbane were the same trap. The whole LEG is passed in, not just `[14]`, because
  the generic vehicle icon sits outside the badge node. Subway is now tested before rail.
  Pinned by `TransitSubwayTest` using the real captured nodes; re-verified by replaying the new
  rules over the whole live payload (6 trips -> lines 2/3/5 SUBWAY, M101 BUS, walks still WALK). The trip SUMMARY (`parseLines` with `leg == null`) keeps EVERY bullet line beside the pills (review 2026-09-12): a two-subway trip showed one line on the card and a bus-plus-subway trip showed the bus alone. A per-leg node still takes one line, pill first.
- **Live stop departure board (`WebStopDeparturesFetcher` + `core/.../StopDeparturesParser`,
  2026-07-12, keyless + device-verified).** Tapping a transit STATION shows Google's "See departure
  board" in the place sheet. The board is embedded in the station's OWN place page's
  `APP_INITIALIZATION_STATE` (opening the button fires NO data RPC - only a gen_204 beacon) and
  SURVIVES a logged-out session (proven anonymous in Chrome + on-device; NOT login-gated like popular
  times), so it rides the SAME hidden-WebView `?cid=` channel as photos/reviews (desktop UA, anonymous)
  and reuses the longest-`)]}'`-string extract. **Schema (calibrated NYC subway hub 2026-07-12):**
  place `root[6]`, transit node `place[62]` = `["<station>", [ <groups> ]]`; group `[null,"<Subway
  services>", [ <lines> ], … "<mode>"]`; line `[null, [ <directions> ], … ftid]`; direction
  `["<headsign>", null,null, [ <departures> ]]`; a departure time tuple `[rtEpoch,"<tz>","4:35
  AM",offset,schedEpoch]` (realtime when rt≠sched); frequency `[<sec>,"20 min"]`. **TWO layouts
  (2026-07-12):** a station/subway groups entries by line -> direction -> departures (above), but a busy
  BUS stop lists every upcoming departure FLAT, each tagged with its own route pill at `entry[5][1]`
  shaped `["<label>", <int>, "#fill", "#text"]` (the same badge the itinerary line pills use). So the
  parser doesn't assume one shape: it reads the badge (route number + colors) + headsign + times off
  each entry and GROUPS by (route, direction) - the 25 separate "route 14" departures collapse into one
  "14" row with its next few times, in its line color, and lines sort soonest-first. The container path
  is positional; the LEAF details (time tuples, frequency, the route badge) are matched by SHAPE, and
  `place[62]` is validated with a shape-search fallback - a moved leaf/field index degrades one line, not
  the board. `parse` returns **null** for a non-station (routine - most
  places have no transit node) and throws `CalibrationNeededException` only when a transit node yields
  0 lines. **Coverage is AGENCY-DEPENDENT** (only agencies that feed Google real-time embed it): NYC
  MTA + SF BART carry it, SacRT (small light rail) does NOT - `MapViewModel.fetchStopDepartures` is
  gated to transit-category places (`TRANSIT_CAT` regex) so it never fires on a business, and an empty
  result just shows no board. **INTERSECTION-named stops (2026-07-13):** a bus stop named by its corner
  ("Main St & 1st Ave" style) often resolves to Google's "Intersection" entity, whose OWN page has NO
  board (device-confirmed: the "some stops on a state-route corridor show no buses" report). `fetchStopDepartures` now, for an
  "Intersection" category, RE-RESOLVES to the co-located stop (`resolveIntersectionStopBoard`: search
  "<name> bus stop", take the nearest LIVE `TRANSIT_CAT` listing within **250 m**: a REAL co-located stop
  measured **89 m** from its junction point (device 2026-07-13) - just past the OLD 80 m cut, which is exactly
  why boards never showed at these corners; another junction's stops sit ~575 m out, so 250 m catches the
  right one only) and pulls ITS board onto the intersection sheet. No co-located Google
  listing (a rare OSM-only stop) -> no board, correctly. **The transit gate needs the EXCLUSION list too (2026-07-13):** "Gas station" /
  "Charging station" / "Fire station" all contain "station", and boards fetch by PROXIMITY now, so
  the fuel stop beside a bus stop showed that stop's departures (device report). `isTransitCategory`
  = gate word matches AND no NON_TRANSIT_CAT word does (fuel/EV/emergency/broadcast, localized);
  both regexes remote-overridable (`transitCategoryWords` / `transitExcludeWords`, calibration v17).
  v17 ALSO guards the shipped word list itself (lookbehind/lookahead on station/stazione/estaci/
  станц/תחנ) so pre-exclusion installs get the fix remotely - a unit test reads the REAL
  calibration.json and asserts the fuel/EV/emergency categories are rejected while every real
  transit category still matches (a broken edit fails CI, not the fleet). **The transit gates are MULTILINGUAL (issue #71, 2026-07-13):** categories arrive in the
  device language (hl=), so TRANSIT_CAT carries keyword stems for all 15 app languages - Hebrew was
  missing entirely, which made every stop tap in a Hebrew-locale install dead-end as a name-only
  sheet (the reporter's Jerusalem screenshot: no category match -> no live-stop pick -> no board,
  and a bare placeholder hugs its content so there's nothing to swipe to). And a HINTED tap (the
  basemap class says transit, language-independent) that resolves to NO Google stop listing now
  falls back to `Transitous.board` at the tapped coordinate directly - proximity only, no category,
  no feature id (the Google-page fallback is impossible without one anyway). Verified against live
  Transitous data at the reporter's exact stop (Israel MOT GTFS is in Transitous). **BOTH paths are name-first with a bare
  PROXIMITY fallback (2026-07-13):** OSM and Google often NAME the same stop differently ("A & B" vs
  "B & A", Hwy vs road name), so when the "<name> bus stop" search yields no live transit hit within
  250 m, a second location-biased query for just the mode word ("bus stop") runs and the nearest live
  listing wins (`nearestLiveStop` is the one shared predicate). **After-midnight departures carry a
  localized short-weekday marker** ("5:48 AM · Mon") via `departureDayLabel` in PlaceSheet - epoch vs
  now compared on the LOCAL calendar day, SimpleDateFormat("EEE") localizes free, no strings.xml.
  **TRANSIT HUBS are a keyless DATA LIMIT, not a parser bug (proven 2026-07-13 with a saved blob):**
  a major transit center's anonymous place page embedded exactly ONE route's departures (25 times,
  one headsign) - none of the other routes serving the hub appear ANYWHERE in the 156 KB payload
  (Google's app board comes from its first-party transit backend). The parser + grouping are correct.
  The follow-up design (task): a hub's BAYS each have their own Google listing ("<Hub> Bay A1"...)
  with their own boards - fetch the nearest few bay boards and MERGE them into the hub sheet. Fetch pinned `hl=en&gl=us` like `WebDirectionsFetcher` (12-hour clock
  the TIME regex reads). UI: `PlaceSheet.StopDepartureBoard` (one shared 30 s countdown clock, reuses
  `departsInLabel` + the `place_transit_*` strings + `place_departures`/`place_every`).
  **Departs-in countdown (2026-07-12):** `TransitBoard` runs ONE shared `produceState` clock (30 s
  tick) and each `TransitRow` shows a leading "Departing"/"in N min" from `departureEpochSec`
  (`departsInLabel`, hidden when >90 min out or already gone); the countdown reads GREEN with a
  "Live" dot when any leg carries real-time (`delayText` or a `boardStop.scheduledText` differing
  from the timetable), and the boarding leg's "N min late/early" is surfaced in the header. Pure
  render off already-parsed fields, no extra fetch. `delayText` is English-computed in `:core` (as
  in the drill-down); the countdown wrapper strings ARE localized (`place_transit_now`/`_in_min`/
  `_live`, invariable "min" abbreviation per locale like `place_delta_min`).
- **Tap-through route stop timeline (2026-07-12, keyless + device-verified).** Every `DepartureLineRow`
  on the board is `clickable` (a trailing `>` chevron hints it) -> `MapViewModel.openRouteDetail(line)`.
  There is NO new endpoint: the route's stop SEQUENCE is a lazy fetch NOT in the place blob, so this
  REUSES the proven transit-itinerary parser. `openRouteDetail` geocodes the line's headsign (biased to
  transit terminals - it prefers a candidate whose `category` matches station/airport/terminal/bart/…
  nearest the stop, because a bare "Richmond" resolves to a city district not the BART terminal), runs
  `webDirections.transit(stop, terminal)`, and among the ride legs picks the one on the tapped line
  (label match) else the leg whose `boardStop` is nearest the stop (the direction tapped) else the
  first - that `TransitStep` already carries `boardStop`/`intermediateStops`/`alightStop` with per-stop
  times. Rendered by `PlaceSheet.RouteDetailSheet` (full-screen `Surface`, `MapScreen` draws it over the
  place sheet when `state.routeDetail != null || routeDetailLoading`): a vertical rail in the line color,
  board + alight bold, each `TransitStopTime` row `clickable` -> `openRouteStop(stop)` which
  `closeRouteDetail()` + `onPoiTap(stop.name, stop.location, "transit stop")` - so tapping a stop opens
  ITS board and the tap-through continues. **The transit KIND is load-bearing (fixed 2026-07-13):**
  without it `onPoiTap` searched the bare name, which Google resolves to the road JUNCTION, so
  tap-through threw you to a corner. `onPoiTap`'s pick is now TRANSIT-AWARE when a transit hint is set
  (map tap on a stop icon OR this tap-through): it takes the nearest LIVE `TRANSIT_CAT` listing within
  **250 m** (widened from 80 m 2026-07-13: the OSM icon and Google's stop listing routinely sit on different
  corners of the junction - a real pair measured 89 m apart; nearest-wins keeps the wide radius safe),
  EXCLUDES `permanentlyClosed`, and SKIPS the most-reviewed-canonical override (a defunct-but-
  reviewed old shelter was beating the live stop - the "tapped stop shows Permanently
  closed" device report). No live stop listing at the coordinate -> the lightweight name+location
  placeholder stays (a stop name beats an Intersection card; no board without a real stop listing, which
  is correct). Best-effort: an ungeocodable headsign / no ride leg flashes
  `route_detail_unavailable` (localized in all supported languages) and the overlay closes. State on `MapUiState`:
  `routeDetail: TransitStep?`, `routeDetailTitle`, `routeDetailLoading`, guarded by `routeDetailJob`.
  The board cap was raised 8 -> 24 lines (`StopDepartureBoard` + parser `MAX_LINES`) so busy stops show
  more routes. **Timeline rows are Google's treatment (2026-07-13):** taller rows with a hairline
  between stops (drawn INSIDE the row's bottom edge, inset 40dp past the rail - an item-level divider
  opens a visible gap in the connector line), call times in normal ink with the BOARDING stop's time
  a step bigger (titleMedium SemiBold), and a small status word under every time: dim "Scheduled"
  (`place_transit_scheduled`, all 15 locales) or green "Live" when the stop's realtime differs from
  its timetable (`scheduledText != timeText`). **The timeline's PRIMARY source is the GTFS trip itself
  (2026-07-13, device-verified):** Transitous boards stamp every departure with its `tripId`
  (`StopDeparture.tripId`), and `Transitous.tripStops` (`/api/v1/trip`) returns that run's REAL stop
  sequence - per-stop realtime vs timetable times AND per-stop/-run CANCELED flags straight from the
  agency feed (`TransitStopTime.canceled` renders a red "Canceled" + struck-through time,
  `place_transit_canceled` in all 15 locales). `buildTripStep` (pure, unit-tested) BOARDS at the tapped stop
  (nearest to the tapped coordinate; a terminus tap boards at the origin) and puts the stops the
  run already called at into `TransitStep.priorStops` - the sheet renders them GRAYED above with a
  gray rail (the colored rail starts at your stop, Google's treatment) and opens scrolled to the
  boarding stop (`rememberLazyListState(initialFirstVisibleItemIndex = priors)`). A moved time
  renders the timetable time struck through beside the live one - red when late, green when early
  or on time (`TransitStopTime.delayMin`, signed; the feed carries EARLY runs too, verified live). The headsign-geocode + itinerary-reuse path (`itineraryStep`) remains the FALLBACK for
  Google-fallback boards (their departures carry no tripId) and trip-fetch failures. NB transit
  DIRECTIONS still ride Google on purpose (traffic-aware ETAs) - this moved only the stops list.
  Boards still DROP fully-canceled runs (`canceled`/`tripCanceled`/`place.canceled`); showing
  them struck-through on the board is an open option. **Device-verified: Powell St -> Yellow-S -> 11 stops (Powell…SFO, 12:23-12:54 PM), then
  tapping 16th St Mission opened that bus stop's own board.** **Per-line arrival depth raised 4 -> 8
  (2026-07-13, user report "only shows the next 4 or so arrivals"):** parser `MAX_TIMES` was 4, AND
  `DepartureLineRow` only rendered `upcoming.first()` + `drop(1).take(3)` = 4 total; both were the cap.
  Now `MAX_TIMES` = 8 and the trailing times render in a **`FlowRow`** so a busy stop's extra departures
  WRAP to more rows instead of overflowing the single Row (which is why they were capped at 3). **Superseded 2026-07-13: per-line depth is now a VERTICAL LIST of every embedded time** (parser `MAX_TIMES` = 30 ceiling; `DepartureLineRow` stacks the trailing departures one-per-row, each with its own "in N min" countdown, instead of the wrapping FlowRow). The board blob only carries the next several, so the list length is data-driven, not the cap. **Refined same day:** an agency can embed 25+ times and the
  full wall scrolled the route pill + headsign out of view (read as "the bus number is missing") - the
  list shows 5 + an "N more" expander (`place_transit_more_times`, all locales). Countdown past the hour
  reads hours+minutes via `formatDuration` ("in 1 h 6 min", `place_transit_in_duration`); after-midnight
  rows carry a localized short-weekday marker. **The TIME regex matches Unicode spaces explicitly**
  (`[\s\u00A0\u202F]`): some agencies put a NARROW NO-BREAK SPACE before AM/PM, which Android's ICU
  regex counts as `\s` but the JVM does NOT - unit tests silently diverged from device behavior until
  a dumped blob exposed it. **Debug builds keep the last raw board payload** at `filesDir/depdump.txt`
  (WebStopDeparturesFetcher, BuildConfig.DEBUG only) - the schema is agency-shaped, so wrong-parse
  reports are only diagnosable from the actual blob. **The board renders FIRST in the sheet body**
  (above the address; renders nothing for non-transit places). **Badge matcher admits NAMED lines** (8-24
  chars when BOTH colors are hex - branded BRT lines carry a name, not a number; verified against
  a device blob). **Each row carries an explicit "Stops ›" action** (`place_transit_view_stops`, all
  locales) - the bare ripple wasn't discoverable as "tap to see the route's stops" (user 2026-07-13,
  overruling the earlier chevron removal in #168).

## Name

Vela Maps (`app.vela`). "Vela" was clearance-checked and is free of maps-app and
trademark collisions.
