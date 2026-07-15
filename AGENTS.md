# Vela - project guide for Claude

Degoogled Google-Maps replacement for Android (the "NewPipe for Maps"). Open
vector tiles for the basemap; the device scrapes Google's public web endpoints
per-user (no backend, no shared API key) for POIs, routing and traffic-aware
ETAs. Targets GrapheneOS / no-GMS ROMs; F-Droid distribution. GPLv3.

## Docs discipline (read first)

**Every change updates the docs in the same commit.** Hard rule for all
collaborators (human or Claude). When you change behaviour, calibration,
features, or structure, update - in the *same* commit:
- `README.md` - status, architecture, calibrated request/response paths
- `docs/FEATURES.md` - tick/retire the affected items
- `docs/SPEC.md` - the authoritative rebuild spec (architecture / extractor contract /
  resilience / constraints); update when a load-bearing decision or path changes
- `docs/ROADMAP.md` - planned work + big bets (opt-in telemetry, Vela's own traffic layer,
  popular times, …); add new ideas here as they come up
- `AGENTS.md` - this file (build rules, layout, gotchas); `CLAUDE.md` is a gitignored symlink to it
- the `project-vela` memory note if a load-bearing fact changed

Stale docs are treated as a bug. Code-only commits are not OK; if a change
genuinely needs no doc edit, say why in the commit.

**Writing style (hard rule): NO EMOJIS, EVER** - not in code, comments, docs, commit
messages, UI strings, or script output. Plain text only. Also no em-dashes; write in a
plain human voice (commit subjects are the user-facing changelog). Use words like `PASS`/
`FAIL`, not pictographs.

## Visual verification (HARD RULE, NO EXCEPTIONS)

**Anything a user can see MUST be verified VISUALLY, by looking at an actual on-device
screenshot - EVERYTHING, every time. No exceptions.** A passing (or failing) test script is
NEVER the final word on UX. This is not optional and not satisfied by audits alone.

- **Scripts are necessary but NOT sufficient.** The `tests/dpad` / `tests/small_screen`
  auditors race the app's focus-recovery timing: identical fresh runs produce DIFFERENT
  failures (search bar focused, then a FAB focused, then a focus-loss that vanishes next run),
  and they report "could not open X" when the surface opens fine by hand. A red script is a
  lead to investigate, not a verdict; a green script is not proof the UI is right. Never
  conclude "it works" or "it's broken" from a script alone.
- **Look at the pixels.** For every UI change or surface touched: `adb exec-out screencap -p`
  (or `adb shell su -c screencap` on a locked/secure screen), pull it, and OPEN it. Confirm
  with your eyes: the change actually rendered; the focus ring is present and on the right
  element; nothing is clipped, overlapping, or off-screen; text is readable and correctly
  inset; both light AND dark themes look right where the change can show in either.
  **`tests/devices/capture.sh <id>` automates this** - it drives the full first-run flow + core
  surfaces at a target device's real geometry and saves labeled screenshots into
  `tests/devices/<id>/screenshots/auto/`, so regenerating the visual proof is one command. It does not
  replace looking - you still open the frames - but it makes the capture reproducible and PR-reviewable.
- **Drive it like a user.** Send real keypresses (D-pad: `input keyevent 19/20/21/22/23`,
  BACK `4`) through the actual flow and screenshot at each step - fresh launch, first arrow,
  each surface, the specific control you changed. Do not trust `uiautomator` focus dumps
  alone (a single snapshot routinely misses the focused node while auto-focus retries across
  frames) - the screenshot is the source of truth.
- **No UI change lands on the word of a script.** If you have not looked at a screenshot of
  the affected surface behaving correctly, the change is not verified. Ship the frames.

## Feature-phone display size (HARD RULE, NO EXCEPTIONS)

D-pad-first means feature-phone-first, and feature phones are SMALL. "Works with a D-pad" must hold
at a FEATURE-PHONE display size, not only on your dev phone's native panel. Non-negotiable:

- **The app adapts its own density to the screen (`ui/AdaptiveDensity.kt`).** Standard Material
  layouts assume ~360dp of logical width; a 240px feature phone is far below that and controls
  crowd/clip. `AdaptiveDensity.wrap` (chained with `AppLocale.wrap` in both
  MainActivity/VelaApp.`attachBaseContext`) overrides the effective `densityDpi` so a small screen
  reports at least `MIN_WIDTH_DP` (360) of width - fixing clipping across EVERY screen in one place.
  It ONLY shrinks small screens (>= 360dp wide = byte-for-byte no-op), device-verified: at 240x320 all
  three category chips fit where before only one did. `MIN_WIDTH_DP` is tuned VISUALLY (smaller text is
  the cost) - re-verify on-screen if you change it. NB density fixes LAYOUT/clipping, not focus - the
  D-pad focus rules below are separate.
- **"Fully supported" is a HARD REQUIREMENT, not a vibe.** You may call a device (or its screen size)
  FULLY SUPPORTED only when **EVERY screen** is reachable, opens focused, is D-pad-navigable, and is
  clip-free at that device's screen size - **verified VISUALLY by screenshot**, not by a passing script
  alone. "Every screen" means the WHOLE app, not a core subset: first-run (Welcome + every onboarding
  dialog), bare map, search overlay + results, place sheet + expanded + reviews + overflow menu,
  directions + route alternates + steps, turn-by-turn navigation cards, transit, AND every Settings
  section + sub-screen (Voice library, Offline, Saved places, Diagnostics, ...), plus every menu/dialog.
  The reproducible gate is **`tests/devices/full_coverage.sh <id>`** - it drives every surface at the
  device's simulated size, captures a labeled screenshot of each, and prints a COVERED/MISSED checklist;
  a device is fully supported only when it reports **FULLY COVERED (0 MISSED)** and you have LOOKED at
  the frames. Content surfaces (search/place/nav/transit) need live search+routing, so run it with the
  network up - a MISSED row (including network-blocked) means NOT fully supported yet. Until every row
  is covered, the status is "core surfaces at the simulated size", NOT "fully supported" - say so.
  **Everything must be reproducible in scripts** so a NEW phone is added by: a matrix row + its geometry
  in `capture.sh`/`full_coverage.sh`/`run_matrix.sh`, then running them - no bespoke manual steps.
  **The gate is PHASED - and every new feature MUST add its surfaces as its own phase** (in
  `full_coverage.sh`'s `ALLPHASES` + a `phase <name>` block with its own frame numbers, same PR as the
  feature): `PHASES="voice parking" bash tests/devices/full_coverage.sh <id>` re-captures just those
  frames, so verifying one feature on a device never needs the full ~13-min tour. A partial run
  reports PARTIAL, never the FULLY COVERED verdict. Current phases:
  `firstrun map search place directions settings voice parking`.
  **HARD RULE - one surface fails, re-run ONLY that phase, NEVER the whole leg.** A full leg is
  ~13-14 min (~35 min for both geometries); iterating a fix by re-running the whole tour is
  unacceptable waste. `PHASES=<phase> bash tests/devices/full_coverage.sh <id>` runs just that group -
  a light phase (map/place) in ~1-2 min; the `settings` phase is the slow one (~9 min measured at
  240x320) because its three deep sub-sections each dump-per-swipe scroll, so that swipe overhead is
  the real bottleneck to optimize next. Every phase re-establishes its own state (no `pm clear` outside `firstrun`, each does
  `goto_map`; `place`/`directions` re-run search if needed), so any single phase runs standalone as
  long as the app is past first-run. Iterate the fix with `PHASES=<failing-phase>` at the ONE affected
  geometry/flavor; only once it passes, run the full leg (no `PHASES`) ONCE for the verdict. Do NOT
  re-run legs that already passed when the change cannot regress them - reason about blast radius
  first (e.g. a swipe-helper tweak can't regress a leg that already reached the section).
  **The IN-PROCESS self-coverage suite (`app/src/androidTest` SelfTourTest + `tests/devices/
  self_coverage.sh <id>`)** is the fast tour: ~10x quicker than full_coverage.sh for the surfaces
  it covers (36s vs ~6min at 240x320) and STRICTER - real focus-state assertions per D-pad step
  (By.focused(true) - actual focus, not pixel inference), exact pixel-bounds clip checks, and
  direct flavor assertions (restricted rows asserted ABSENT). Accuracy contract, non-negotiable:
  same sources of truth as the external harness (accessibility tree, REAL framebuffer stills incl.
  the GL map via androidx.test Screenshot, REAL system-dispatcher key input via
  UiDevice.pressKeyCode), run against the R8-minified debug build; scrcpy records every run; the
  eyeball pass on the stills stays mandatory; tree-vs-pixels disagreement = failure. Do NOT use the
  Compose test runtime (createAndroidComposeRule) here - it needs Compose-internal classes the
  minified app strips; UiAutomator in-process reads the same accessibility tree without that
  fight. The kotlin/kotlinx-coroutines wholesale keeps in proguard-rules-debug.pro exist FOR this
  suite (the test APK resolves those from the app dex).
  **Migration rule: the existing gates (audit_static, audit_dynamic, small-screen matrix,
  full_coverage) remain MANDATORY until a surface is covered here strictly better - the suite
  AUGMENTS, it does not replace yet. Wrapper gotcha: stills are pulled after EACH geometry (a
  later run's pm clear wipes the app's external files).**

    **HARD RULE - EVERY TARGET GEOMETRY, EVERY TIME, NOTHING LEFT TO CHANCE:** a feature (or phase)
  counts as verified ONLY when its phase has run green AND its frames have been eyeballed at EVERY
  target geometry in the device matrix - today that is BOTH `240x320@160` AND `480x854@320`. One
  size is NOT a proxy for the other. Run
  `for id in kyocera-e4810 sonim-x320; do PHASES="<phase>" bash tests/devices/full_coverage.sh $id; done`
  and look at both sets of frames before calling it done. A new geometry added to the matrix joins
  this loop the same day.
    **HARD RULE - THE FULL VERIFICATION MATRIX, BEFORE EVERY FEATURE PR MERGES (added 2026-07-13
  after it was repeatedly forgotten - do not merge without ALL FOUR cells):**
  1. **Both geometries** - 240x320@160 AND 480x854@320 (the rule above). The attached device's
     native size is NOT a substitute.
  2. **D-pad walk-and-activate** - arrow TO each new interactive element and activate it with OK,
     confirming a visible focus ring and the state change. `audit_static.sh` passing is necessary
     but NOT sufficient - it proves the ring exists, not that the element is reachable/activatable.
     (Check the UI/accessibility state for the effect, not the prefs file - SharedPreferences
     `apply()` writes disk lazily and races a file check into a false failure.)
  3. **BOTH release variants** - standard AND restricted (`assembleRestrictedDebug`): the feature is
     present and working there, or correctly gated, AND the flavor's locks are still intact
     (no Place-pages section etc.).
  4. **Eyeballed screenshots** - a human-readable frame per surface; script assertions alone don't
     count (the verify-visually rule).
  State the matrix results in the PR body. A cell that genuinely cannot run yet (e.g. a HUD that
  only renders while driving) is DECLARED as deferred in the PR, never silently skipped.
- **Every screen opens focused AND stays D-pad-navigable at a SMALL display, not just native.**
  Verify at a real target size - `adb shell wm size 240x320; adb shell wm density 160` (Kyocera e4810;
  see `tests/devices/`) - by SCREENSHOT: the screen lands a visible focus ring on open AND arrows move
  it row-to-row. Two distinct small-screen focus bugs were device-found and fixed on Settings: (1) the
  weak `rememberDpadAutoFocus` left the Back button UNfocused on open (fixed with robust
  `dpadAutoFocus(requester)`); (2) DOWN from the focused Back button CLEARED focus instead of entering
  the scrolling content (Compose can't cross container boundaries) - fixed with an explicit
  `topRowFocus.requestFocus()` bridge (mirror of the UP-from-top -> Back routing). Both verified by
  screenshot at 240x320. Restore with `wm size reset; wm density reset`. **Sweep EVERY target density
  in one shot** with `tests/small_screen/run_matrix.sh` (runs the small-screen auditor at each geometry
  in the `tests/devices/` matrix); a change must be green at all of them, not just one.
- **The auditors are only trustworthy because of hard-won robustness - keep it.** `PKG` auto-detects
  the installed build (a hardcoded `app.vela` vs the `app.vela.debug` sideload made `launch_fresh`
  launch a NON-existent package and silently drive whatever app was foreground - the root of most
  "flakiness"); `ui_dump` retries when uiautomator returns only the root node mid-animation;
  `launch_fresh` verifies the app reached the foreground; `warm_up` clears cold-start. If an auditor
  result looks wrong, suspect these before the app - but VERIFY against a screenshot either way.
- **Auditors must FAIL, never silently SKIP/PASS, when a core surface can't be reached or focused.**
  A SKIP that doesn't count as a failure is a FALSE PASS: `audit_smallscreen.sh` SKIPped Settings (a
  no-network surface that must always open) and still printed PASS, hiding that Settings was
  unfocusable. Rules the auditors now enforce, and that you must preserve: a core no-network surface
  (bare map, search, Settings) that can't be reached is a FAIL; a full D-pad walk that never focuses
  ANYTHING (`seen==0`) is a FAIL. Only deep/NETWORK-bound surfaces may legitimately skip. **Narrow
  carve-out:** when a surface is genuinely VERIFIED VISUALLY (screenshots committed under
  `tests/devices/`) but the harness can't reliably SCRIPT-navigate to it, emit a NOTE pointing at that
  proof - not a vacuous pass, not a misleading FAIL (the screenshot is the proof; don't burn effort
  perfecting harness navigation for something already verified by eye). Settings on the bare map is the
  standing example.
- **Prefer the robust `dpadAutoFocus()` / `dpadAutoFocus(requester)` over the weak
  `rememberDpadAutoFocus()`** for any screen whose focus target sits in a scroll container or attaches
  a frame or two late. The weak helper bails the instant `requestFocus()` doesn't throw, even when
  focus never landed; the robust one re-requests until `onFocusEvent` confirms it truly landed, then
  stops (so it never fights the user). `audit_static.sh` surfaces every weak use for triage - each must
  be screenshot-verified to actually focus on a small screen, or converted.

## The restricted build flavor + the feat/restrictions branch (hard rules)

**WHAT THIS IS (read before touching anything restriction-related):** Vela ships TWO release APKs
from one codebase via the `policy` flavor dimension (`app/build.gradle.kts` `productFlavors`):
- **`standard`** - the app exactly as before. Its behavior must stay BYTE-IDENTICAL: every
  restriction below remains a user-flippable Settings toggle with the same defaults as always.
- **`restricted`** - for users who impose the restrictions on themselves and want them
  NON-OPTIONAL. The five self-restriction toggles are HARD-LOCKED at their restrictive values and
  their Settings rows are REMOVED (the whole Place pages section disappears): reviews OFF,
  "Read all reviews" page OFF, photos OFF, adult categories HIDDEN, website/external links HIDDEN.
  **Voice search stays FULLY AVAILABLE in the restricted flavor** (mic, Vela Voice download, the
  Search section in Settings) - it is not a content restriction, it is how a keypad user types.

How the lock works (keep this shape for any new restriction):
- `BuildConfig.RESTRICTED` (false in defaultConfig, true only in the `restricted` flavor) surfaces
  as `ui/Restricted.kt`'s `RESTRICTED_BUILD` compile-time constant.
- **The lock lives in each HOLDER, not in the Settings UI**: `init()` forces the locked value and
  never reads the pref; `set()` is a no-op. So every caller is bound, R8 strips the dead branch
  per flavor, and no pref surgery can unlock it.
- Settings hides the now-inert rows behind `if (!RESTRICTED_BUILD)`.
- The restricted flavor has its OWN applicationId (`app.vela.restricted`, debug
  `app.vela.restricted.debug`) - installs side by side, and the OS installer can never cross-grade
  a restricted install onto a standard APK. `SelfUpdater` picks the release asset matching its own
  flavor by the `restricted` name marker - keep "restricted" in the release asset filename.
- CI (`ci.yml`): debug builds are STANDARD-ONLY (`assembleStandardDebug`); release builds BOTH
  flavors and publishes `vela-maps-v<ver>.apk` + `vela-maps-v<ver>-restricted.apk` on every release.
- **A new user-facing restriction = a holder locked by `RESTRICTED_BUILD` + a hidden Settings row +
  (if it must act inside `:core`) the `CategoryFilter.enabled`-style flag pattern.** Never a fork of
  app logic per flavor and never a branch-only patch.
- **Testing: the restricted flavor obeys every D-pad + small-screen hard rule** - run the coverage
  phases against `app.vela.restricted.debug` (`VELA_PKG=app.vela.restricted.debug`) at EVERY target
  geometry. EVERY phase must pass, the `voice` phase included (the mic works in restricted too).

Branch rules:
- **Keep `feat/restrictions` synced with `main` at all times.** After anything merges to main,
  merge main into `feat/restrictions` (or rebase it) promptly - it must never drift stale.
- **On conflict, `feat/restrictions` WINS.** If a restrictions-based feature in that branch
  conflicts with something from main, resolve the conflict in favor of the restrictions branch's
  behavior - main's version yields inside that branch. Main itself is never changed by this rule;
  it only governs how conflicts are resolved WITHIN `feat/restrictions`.

## Porting from upstream (hard rules)

This is a fork of PimpinPumpkin/Vela and periodically ports fixes from it. Two
non-negotiable rules for any ported commit:

- **CREDIT THE ORIGINAL AUTHOR, CLEARLY, IN THE COMMIT.** Name the upstream commit SHA in
  the commit subject or body (e.g. `Port upstream 84ab10f: ...`). Never present upstream
  work as original. The upstream project is the source; say so plainly.
- **Re-verify D-pad AND small-screen compatibility after EVERY port, thoroughly.** D-pad /
  feature-phone operability is this fork's reason to exist and upstream does not test for
  it, so a port can silently regress it. After each ported change: compile, run
  `tests/dpad/audit_static.sh` (host-side) and the `tests/dead_code` gate, and - for anything
  touching UI - the on-device `tests/dpad/run_all.sh` + `tests/dpad/audit_dynamic.sh` and
  `tests/small_screen/run_all.sh`. Those scripts are the START, not the proof: then VERIFY
  VISUALLY with screenshots per the "Visual verification" rule above - drive the ported
  surface by hand and look at it. A port that regresses D-pad or small-screen operability
  does not land.

## MapLibre gotchas (hard-won 2026-07-13, do not relearn)

- **An 8-digit hex string is NOT transparent.** `PropertyFactory.lineColor("#00000000")` renders
  OPAQUE BLACK (MapLibre's colour parser rejects the string and falls back) - this painted black
  bars over every road once the maxspeed tiles carried features. Use the `@ColorInt` overload
  (`android.graphics.Color.*`), never an 8-digit hex string.
- **queryRenderedFeatures EXCLUDES invisible features.** `lineOpacity(0f)` (or a fully transparent
  colour) makes a layer unqueryable - the speed-limit sign silently died this way. The
  invisible-but-queryable pattern is `lineOpacity(0.004f)` (1/255 alpha: imperceptible on screen,
  still rasterized, still queryable). Applies to ANY query-only layer.
- **MapLibre's pmtiles path will NOT cold-fetch tiles clamped 2+ levels below the camera.** A layer
  whose minZoom sits far above its archive's max tile zoom (address PMTiles: tiles at z16-17, layer
  at 19) never triggers a fetch on a cold source - querySourceFeatures stays 0 forever, no log, no
  error. Resident tiles overzoom fine, which makes it look intermittent. Keep the LAYER's minZoom
  inside (or within ~1 of) the archive's native range and gate visuals with stepped `textOpacity`
  instead. First probe when a pmtiles layer draws nothing: `querySourceFeatures` (0 = tiles never
  loaded), and read the archive's real zoom range from its header (bytes 100-101).
- **A fetch gate and its render layer's minZoom must move in lockstep.** Upstream lowered the Flock
  camera FETCH to z11 for route overview but left the SymbolLayer at `setMinZoom(13.5f)` - fetched
  cameras never drew. Grep for the layer's minZoom whenever a zoom gate changes.

## Porting upstream commits (hard rule, 2026-07-13)

**Read every upstream diff critically before adapting - upstream ships bugs.** Three found in one
day: the opaque-"transparent" query layer (black roads), the unqueryable opacity-0 fix (dead speed
sign), and the half-done route-overview zoom (fetch gate lowered, render layer clamped). Porting
means: read the full diff, reason about edge cases (races, gates, parsers), verify the claim
on-device, and keep fork-specific behaviour (45 s WebView ceiling, file-sink diagnostics, fork
User-Agent) that upstream's version would regress. Never `git cherry-pick` blind.

Diff-only verdicts from the 2026-07-14 smalls batch (both were OUR fixes offered upstream in
issue #131 and then iterated there):

- **House-number cold-start gate (upstream 425ca71b + a162c3a7 vs the fork's fix):** identical
  thresholds on both sides (layer arms at z17 inside the archive's z16-17 tile range; numbers show
  from z19). Upstream's second commit moved the 50 ft gate from a stepped `textOpacity` into the
  TEXT FIELD itself (`Expression.step` -> empty string below z19, `get("number")` at 19+).
  ADOPTED upstream's mechanism: empty text means NO symbols exist in the z17-18.9 band at all,
  so the densest symbol band on the map stops doing invisible placement work per frame, and
  `onAddressLabelTap` can never resolve a label the user was never shown. Visible behaviour and
  the fork's thresholds are unchanged.
- **Flock route-overview zoom (upstream f1009cee vs the fork's fix):** same fetch gate
  (`FLOCK_MIN_ZOOM = 11.0`) and same layer `setMinZoom(11f)` on both sides - kept ours. Adopted
  the one real delta: a z11 `iconSize` stop (0.55) so a metro's worth of badges reads as dots at
  overview zoom. Upstream's follow-ups 0a8bc537 + f1ddb341 (ported) then moved the flock layer
  BELOW the ambient POIs and flipped `iconIgnorePlacement` to false, so cameras always draw but
  claim their collision box - street names dodge the badge, POIs (placed earlier) still win on top.

Satellite batch (2026-07-14 port of upstream 68570ba6..bb271d94, applied as the net final
state - upstream's own 13ac02e8 already made the layers panel a VelaMenu):

- **`VelaMenu.toggleItem` touch row carries `dpadHighlight` (fork-only line).** Upstream's touch
  branch was a bare `.clickable` Row; a hybrid touch+keypad phone can key-walk the anchored
  DropdownMenu, so the fork adds the focus ring (the static auditor flags it otherwise). D-pad
  branch is upstream's verbatim.
- **Address-overlay satellite styling merged INTO the fork's layer, not over it.** Upstream's
  47897018 makes overlay house numbers white-on-black-halo over imagery; the fork's `vela-addr-*`
  layer keeps its minZoom 17 + empty-text-below-z19 textField gate and Timber logs, with only the
  colour/halo/width picking up the `satelliteOn` branch and `satelliteOn` joining the
  LaunchedEffect keys.
- **styleKey has no `pal=` here.** Upstream's key also carries a MapColors palette the fork never
  ported; the fork's key is `"$styleUri|dark=$darkTheme|sat=$satelliteOn"`.

## Transit + speed-display rules (2026-07-14)

- **Transit category checks go through `isTransitCategory()` - NEVER bare `TRANSIT_CAT`.** The gate
  regex matches "station", which also matches "Gas station" / "Charging station" / "Fire station" -
  a fuel stop next to a bus stop fetched and showed that stop's departures until the exclusion
  regex (`NON_TRANSIT_CAT`) landed. Both keyword tables are remote-overridable via the calibration
  bundle (`transitCategoryWords` / `transitExcludeWords`); compiled fallbacks carry the behaviour
  when the bundle lacks them. Any NEW transit-category test must call the one predicate.
- **Open departure boards auto-refresh every ~30 s**, on TWO loops matched to their cost:
  `startTransitousBoardRefresh` re-queries the open Transitous feed (one small JSON call, place-ID
  gated - Transitous boards can hang off id-only placeholders with no feature id), and
  `startBoardRefresh` re-loads the Google fallback board - the hidden WebView with a 45 s ceiling,
  so that loop stays SEQUENTIAL delay-then-fetch, deliberately (a slow load stretches the cadence
  instead of piling requests up; feature-id gated). Both cancel when the selection changes. Keep
  any new board source on the loop shape matching its fetch cost.
- **Speed display = TWO separate stacked cards (user design, 2026-07-14):** the regulatory SPEED
  LIMIT sign is a free-standing card ABOVE its own speedometer card; no limit -> the speedo stands
  alone. Not one shared box, not upstream's side-by-side row (squat on narrow screens - a
  deliberate fork divergence from b5f54ad0's layout). Keep it this way through rebases.

## Transitous (open GTFS transit data - primary board/timeline source, 2026-07-13 wave)

- **What it is:** [Transitous](https://transitous.org) is the community-run, keyless public-transit
  API over the world's open GTFS + GTFS-Realtime feeds (a MOTIS server). It is to transit what
  FOSSGIS OSRM is to road routing and Overpass is to POI landmarks: canonical agency data, no
  account, no key, FAIR-USE community hosting. Vela's usage stays light by design - one small JSON
  call per opened stop plus the 30 s refresh while a sheet is open, and one area-cached `map/stops`
  box per browsed viewport; the client sends an identifying User-Agent per the Transitous policy.
  The endpoint is the `Transitous.BASE` const in `core/data/transit/Transitous.kt` (NOT calibration
  - a self-hosted MOTIS is a drop-in swap if the fork ever outgrows fair use).
- **Primary-then-fallback contract (hard rule):** departure boards and stop timelines try Transitous
  FIRST; the hidden-WebView Google scrape is the FALLBACK where the open feeds lack the agency.
  Concretely: `fetchStopDepartures` does one proximity `board()` lookup at the place's own
  coordinate (no name correlation at all) and only on an empty result falls to `fetchBoardFrom` /
  `resolveIntersectionStopBoard`; `openRouteDetail` reads the tapped departure's GTFS `tripId`
  (Transitous boards stamp one on every departure; Google boards never do) and falls to the
  headsign-geocode `itineraryStep` reuse. Transit DIRECTIONS (origin-to-destination itineraries)
  still come from Google on purpose - only stop boards + timelines moved.
- **Why boards got better, not just freer:** unlike Google's anonymous place page, `stoptimes`
  returns EVERY route serving the stop with realtime flags and agency route colours, and querying a
  stop's PARENT station id aggregates all its child bays (verified live) - a multi-bay transit
  center gets one complete merged board for free. `/trip` gives the timeline per-stop realtime vs
  timetable AND per-stop/per-run CANCELLED flags, which the Google itinerary reuse could never
  provide (that is what lights the red "Cancelled" strike-through rows).
- **Canonical GTFS stops on the map (`refreshTransitStops` + `TRANSIT_STOPS_LAYER`):** at z >= 15
  the stop icons come from Transitous `map/stops` (agencies' own positions, one icon per station -
  bays collapse onto their parent), same area-cached 350 ms-debounced contract as the
  traffic-controls layer. Tapping one opens its board DIRECTLY by stop id (`onTransitStopTap`) -
  no Google resolution, no language dependence. While the layer has coverage the basemap's OSM
  `poi_transit` class="bus" icons are filter-hidden so a stop can't draw twice on different
  corners (rail/airport stay basemap); the original filter is captured once per style load and
  restored when coverage goes. MapScreen filter-hides the SELECTED stop's badge (the red pin drops
  at the same coordinate and two stacked glyphs read as a glitch).
- **Offline floor (`app/data/TransitStopCache`):** every ONLINE `map/stops` fetch overwrites its
  area in a 24-area LRU JSON on disk, so previously browsed areas keep their stops with no signal;
  a FAILED fetch never blanks stops already drawn (null from `stopsInBox` = failure and is never
  area-cached - empty list only on a genuinely stopless box). Never-visited areas fall back to the
  OSM basemap icons.
- **Load-bearing gotchas (from upstream's comments - keep them true):** `StopDeparture.realtime`
  is "the feed live-tracks this run" (the green-dot signal), NOT "the time moved"; the timeline's
  live signal is `scheduledText != null` (kept ONLY when realtime moved the shown time - same
  contract as the itinerary parser) with signed `delayMin` colouring late (red) vs early/on-time
  (green). `buildBoard` DROPS cancelled runs from boards; the timeline SHOWS cancelled calls
  (struck through). A terminus tap boards at the trip ORIGIN (an arrivals-only view has no ride
  left). Clock text is rendered in the STOP's own timezone in the Google-board "h:mm a" format the
  countdown logic parses.
- **Stop taps in EVERY app language (issue #71 upstream):** the transit-category gate carries
  keyword stems for all fifteen app languages (Hebrew included), and when a tapped stop resolves to
  NO usable Google listing at all, the tap's basemap class (language-independent) is trusted and
  the board fetches from Transitous directly at the coordinate - Transitous-or-nothing there, since
  the Google path needs a feature id that doesn't exist. The status-word tables are also
  remote-overridable now (`statusClosedWords`/`statusOpenWords` in the calibration bundle ->
  `SearchParser.remoteClosedWords`/`remoteOpenWords`, pushed by `adoptKeywordTables`); the fork's
  shipped bundle (v13) doesn't carry any keyword lists yet, so the compiled tables rule until one
  is pushed (the TransitousTest bundle guard arms itself when they appear).

## Known issues (live)

- **Cold camera at deep zoom can miss overlay house numbers** (residual edge of the 2026-07-14
  fix). ROOT CAUSE of the earlier "numbers at no zoom" scare: the address PMTiles carry tiles ONLY
  at z16-17, and MapLibre's pmtiles path will not cold-fetch a tile clamped 2+ levels below the
  camera - so a layer minZoom of 19 meant the source never fetched on any cold path (the earlier
  "pre-existing on main" reading was wrong: main's 17.5 test frame sat just UNDER its threshold, so
  the layer was correctly invisible, not broken). The fix arms the layer at z17 (in-range = tiles
  fetch, even at opacity 0) with a stepped-opacity 50 ft gate at z19; every zoom-through path
  works. Residual: a process whose camera STARTS past ~z19 without ever dipping lower fetches
  nothing (rare - the camera restores to browse zoom). Upstream has the unfixed version (layer
  minZoom 19 -> their overlay numbers never render on any cold path).

## Build

- **Build variants (reworked).**
  - `debug` is now R8-minified AND debuggable, so it runs smooth on-device (no more map-scroll/nav
    lag) while breakpoints, Timber, StrictMode and the ANR watchdog all work; it installs side by
    side with a release build via `applicationIdSuffix ".debug"` (applicationId `app.vela.debug`,
    `versionNameSuffix "-debug"`).
  - `release` = R8 + resource-shrink.
  - `staging` = release-optimized but non-debuggable, for true frame profiling.
  - R8 running on `debug` too means slower builds but the old "always ship release" caveat is gone.
- `./gradlew :core:test` runs the pure-logic unit tests (polyline, nav engine).
- **Local diagnostics (crash/ANR/jank, all on-device, no Firebase/Crashlytics).**
  - `Timber` is a thin logging facade: a `DiagTree` forwards WARN/ERROR into an opt-in breadcrumb
    ring (`DiagLog`, 300 entries, gated on the `diag_enabled` pref, default off); a `DebugTree` logs
    to Logcat in debug builds only.
  - `CrashCatcher` is the uncaught-exception handler that writes `crash-*.txt` (header + stacktrace
    + breadcrumbs) to `filesDir/diag/crash/`, surfaced in Settings → Diagnostics with export/share.
  - `ExitInfoReader` (API 30+) harvests `ApplicationExitInfo` for ANR/native-crash/SIGNALED/
    low-memory kills into `crash-exit-*.txt` on the next launch, deduped by a `last_exit_ts` pref.
  - `AnrWatchdog` (debug-only live main-thread stall detector) writes `crash-anr-*.txt` and stands
    down during crash teardown via `CrashCatcher.crashing` so a crash does not also log a bogus ANR.
  - `StrictMode` (debug-only) catches main-thread disk/network I/O, `penaltyLog` to Logcat plus
    deduped breadcrumbs into `DiagLog` keyed by violation-type + call-site so startup pref reads
    can't flood the ring.
- **D-pad regression suite (`tests/dpad/`).** On-device, reproducible. Run after any change
  that touches focus (see `docs/dpad.md`):
  - `run_all.sh` - per-surface focus assertions (bare map → search bar, Settings/Welcome/dialog/menu
    auto-focus, Choose-on-map engages, Directions pill reachable).
  - `audit_static.sh` - EXHAUSTIVE source scan (no device): every clickable/toggleable/selectable
    has a `dpadHighlight` ring, every gesture has a key path, no bare `DropdownMenu`/`AlertDialog`,
    no `isSystemInDarkTheme`; fails on any real violation. Wire it into CI.
  - `audit_dynamic.sh` - EXHAUSTIVE on-device tour: every surface opens focused, focus is never lost
    across a full traversal, BACK exits. "Nothing escapes the auditor."
- **Dead-code gate (three engines, all in CI, `main`).** ACCURACY IS THE CONTRACT: none may flag
  anything actually needed (a false positive is a bug; a false negative is tolerated).
  - `./gradlew :core:detekt :app:detekt` - parser-level dead code detekt finds and grep CANNOT:
    unused imports (delegate-aware, so Compose `by remember` `getValue`/`setValue` imports are not
    false-flagged), unused private members, unreachable code. Scoped to dead-code rules ONLY
    (`config/detekt/detekt.yml`, `buildUponDefaultConfig = false`) on the two shipped modules; NOT a
    style/complexity linter. detekt runs without type resolution, so it needs no compile classpath.
  - `tests/dead_code/audit_deadcode.sh` - the whole-tree half (host-side python, no JDK): fails
    on any public/internal top-level declaration the ENTIRE tree (every module, .kt + .xml + .kts)
    never references, counting a name used ANYWHERE (even only in its own file) as live and skipping
    every reflection/DI/framework ENTRY POINT (`@Composable`/`@Inject`/`@Provides`/`@HiltViewModel`/
    `@AndroidEntryPoint`/`@Module`/`@Serializable`/`@Preview`/`@Test`/`@Keep`/`@JvmStatic`, the
    R8-kept `core/.../model` package, the four manifest entry classes). Also surfaces whole DEAD
    MODULES as an advisory CHECK, not a hard fail. Mirrors `audit_static.sh` in shape.
  - Android Lint `UnusedResources` (scoped via the `lint{}` block) for dead drawables/strings/layouts.
- **Auditing a real drive.**
  - A saved trip stores the navigated route too (`core/replay/TripLog` format, shared by `:app`'s
    `TripStore` writer and the `:core` reader).
  - To diff what the nav cards/voice said against the plotted route from a shared trip CSV, call
    `TripLog.audit(csv)` (→ `NavReplay.Report.summary()`) or run the on-demand harness:
    `./gradlew :core:testDebugUnitTest --tests '*auditSharedTripLog' -DvelaTrip=<abs.csv> --rerun-tasks`
    then read the report from the test-results XML `system-out`
    (`core/build/test-results/testDebugUnitTest/*.xml`).
  - The property passthrough lives in `core/build.gradle.kts` (`tasks.withType<Test>` forwards
    `velaTrip`) - without it the harness silently skips. It flags silent/missed turns, too-early
    announcements, and lying card distances.
  - **Trips are SEGMENTED**: every route the drive used (start + each reroute/faster-route swap) is
    its own `RP/RD/M` block, activated at the fix where it appears; `TripLog.parse().segments`
    carries them, audit + in-app replay are segment-aware, and replays are HERMETIC
    (`NavSession.replayMode` - no live reroute/recheck fetches, recorded swaps play back via
    `replaySetRoute`; the map view scales the puck's clocks by `replaySpeedup`).
  - Never audit/replay a multi-block trip against a single mashed route. NB replays of OLD trips play
    back the dirty fixes the old pipeline recorded (BeaconDB teleports) - judge the engine on fresh
    recordings.
- **Demo / simulate-driving mode** (Settings → Navigation, off by default, pref `demo_drive` in
  `vela_settings`).
  - Drives a planned route as a SYNTHETIC GPS trace so nav can be shown/tested **anywhere** with no
    real fix. `DemoTrace.fromRoute(polyline)` (pure `:core`) → one clean `ReplayFix`/sec, fed through
    the SAME hermetic `LocationProvider.replay` path a recorded trip uses
    (`MapViewModel.startDemoDrive`, `startNav` branches on the pref).
  - It's presented as real nav, not a replay: `MapUiState.demoDriving` hides the "Stop replay" pill
    and the normal **End** (`stopNav`) cancels the demo job (its `finally` resumes live GPS + resets
    the dot/route).
  - **Turn it OFF to navigate for real** - while on, every "Start" simulates instead of using GPS.
- **Simulate-my-location (demo)** (`ui/SimLocation.kt`, Settings → Navigation, off by default,
  pref `sim_location` in `vela_settings`).
  - A sibling of demo-drive for demos/screenshots: when on, Vela pretends to be at the map centre
    (captured when you flip the toggle), so the location dot, the directions ORIGIN ("Your
    location"), and recenter all read from there instead of your real GPS.
  - Process-wide reactive holder like `TransitLayer` (`init` in `VelaApp`); `MapViewModel` applies
    it - `startLocation()` pins `myLocation` to the sim point (guard sits AFTER the replaying guard
    so demo-drive still wins), `simulateLocationHere()` captures `mapCenter`, `stopSimulateLocation()`
    resumes live GPS.
  - NB search/place-sheet DISTANCES are `near`-relative (off `mapCenter`) regardless of this toggle;
    sim-location is specifically about the dot + route origin.
  - **Turn it OFF for real navigation.**
- CI: **simple stable channel.**
  - `.github/workflows/ci.yml`: every push to `main` builds + tests, then publishes a NORMAL
    (non-prerelease) release `v0.0.<run>` (versionName `0.0.<run>`, versionCode = the GitHub run
    number, `<run>`). It builds BOTH the debug and release APKs and attaches both to the release.
  - There is no nightly/prerelease channel and no promote-to-stable workflow (both retired);
    Obtainium and the in-app updater track `releases/latest` directly.
  - **Release notes are a real changelog** built from the commit subjects since the previous
    `v0.[0-9]*` tag (the glob spans minor bumps; checkout is `fetch-depth: 0` so the tag history is
    present; the publish step formats them + a compare link into `--notes`). So **commit subjects ARE
    the user-facing changelog** - write them as plain-language changelog lines (no em-dashes, human
    voice), not terse hashes.
  - **Keep local dev builds below the current run number** (e.g. `-PappVersionCode=1`), so the
    release line always wins.
  - Release signing uses repo secrets `VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD`,
    `VELA_KEY_ALIAS` (set; keystore at `~/.vela-signing/`, outside the repo - back it up). Without
    them the APK is debug-signed. Version override: `-PappVersionName`/`-PappVersionCode`.
  - An optional `MAPTILER_KEY` secret → `BuildConfig.MAPTILER_KEY` (`-PmaptilerKey`) switches the
    basemap to MapTiler Streets (Google-like, with a dark variant by system theme); empty locally →
    keyless OpenFreeMap. **Never commit the MapTiler key** - CI-secret + BuildConfig only.
- Toolchain: AGP 8.7.3, Kotlin 2.1.0, Gradle
  8.11.1, compileSdk 35, minSdk 26, Java 17, Compose + Hilt + version catalog.
- Release signing from env: `VELA_KEYSTORE_PATH` / `VELA_KEYSTORE_PASSWORD` /
  `VELA_KEY_ALIAS` (default alias `vela`); falls back to debug keystore locally.
- **No blocking IPC/IO from a composable body.**
  - A `PackageManager.queryIntentServices` binder IPC + per-engine `loadLabel` directly in
    composition re-runs on every recompose - jank on a Pixel, a >5 s ANR on a slow keypad phone.
  - Load such data with `produceState { withContext(Dispatchers.IO) { … } }`;
    `VoiceGuide.availableEngines()` also caches the system-engine enumeration per process.
- **Memory: the browse map runs near the heap ceiling, so keep allocation LOW (2026-07-13).**
  Panning already churns ~180 MB/12 s at baseline (ambient POI scrape + parse per pan) - pre-existing,
  close enough to the default ~256 MB heap that any EXTRA churn triggers a blocking GC per frame
  (staccato pan/zoom) and OOM-crashes on a burst. Two rules: (1) `android:largeHeap="true"` is set
  (raises the ceiling ~2x); don't remove it. (2) **Any Overpass / large-HTTP-body reader MUST
  stream-parse** - `Json.decodeFromStream(body.byteStream())` into a tiny `@Serializable` DTO, NEVER
  `resp.body.string()` + `parseToJsonElement` (that held ~5-10x the wire size in transient heap and
  OOM'd mid-read - the Flock `out body` fetch per pan did this; fixed in `OverpassAlprCameras`,
  `OverpassTrafficSignals`/`OverpassPois` are the same pattern + a pending follow-up). And NEVER lower a
  per-viewport Overpass fetch's min-zoom without shrinking the box.

## Layout

- `:core` is the UI-agnostic "extractor" (NewPipeExtractor pattern). `:app` is
  the Compose UI. Don't let MapLibre or Android UI types leak into `:core`
  (convert `LatLng` at the view boundary).
- The one seam is `core/data/MapDataSource`. `MockMapDataSource` is the default
  and keeps the entire app usable offline; `google/GoogleMapsDataSource` is the
  real scraper.
- **Android Auto (`app/car/`).**
  - `VelaCarAppService` is a NAVIGATION-category templated `CarAppService` (manifest service +
    `xml/automotive_app_desc.xml` + the two `androidx.car.app.*` permissions + application-level
    `minCarApiLevel=1`); a sideload appears in the car launcher only with AA developer "Unknown
    sources" on, hence `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`.
  - `CarMapScreen` is the whole car UI: NavigationTemplate (Re-center / + / − action strip;
    RoutingInfo card with `NavSession.state.maneuverText` + distance while navigating) over a map
    surface.
  - **The MapLibre-on-car trick: SurfaceCallback surface → `DisplayManager.createVirtualDisplay` →
    `Presentation` → plain `MapView`** (MapLibre can't draw to a raw surface). It reuses
    `applyDark`/`applyLight` from VelaMapView (made `internal` for this) keyed to
    `carContext.isDarkMode`, has its OWN AOSP LocationManager listener for the puck (works with the
    phone UI closed), and draws the route from `NavSession.state.route.polyline`.
  - Pan/zoom arrive as `onScroll`/`onScale` and move the camera by hand (projection math -
    `MapLibreMap.scrollBy` isn't a thing in 11.x).
  - The PHONE runs nav (MapViewModel feeds NavSession) and speaks; the car is a display. Car-side
    search/route-start is a follow-up; untested on a real head unit yet.
- **Settings ORDER is deliberate:** Appearance → Map (traffic/transit/3D) →
  **Place pages** (ShowReviews / read-all-reviews / LoadPhotos) → Navigation (keep-screen-on,
  traffic lights, vibrate-on-turns as FilterChips one per mode, demo LAST) → Voice → Offline →
  Saved places → Data & privacy → Diagnostics → About/Support/Version(+updater). Put a new setting
  in the section it serves, not at the end; place-content settings go under Place pages, not Map.
- **Nav UI style:** ManeuverBanner + NavControls are RoundedCornerShape(24/28dp)
  Cards with elevation 6dp, 54dp turn glyph, headlineMedium-bold distance, titleMedium-medium road
  name, FilledTonalIconButton for mute/steps. On screens under 500dp tall the banner runs COMPACT (36dp glyph, titleLarge distance, tighter padding - issue #41; every text line is maxLines-capped so a long arrive card can never bury the map). Keep new nav chrome on this treatment (no flat
  default-radius cards, no OutlinedIconButton circles - that was the "dated" look).
- **In-nav search along route (ported upstream cead2dc5):** the NavControls magnifier arms
  `NavSearchChips` (Gas/Food/Coffee/Groceries, `NavOverlays.kt`) above the bar; a pick runs the
  normal `searchAlongRoute` (which skips stashing `alongRouteDest` while navigating), the nav
  branch of MapScreen's bottom `when` steps aside while `state.results` is non-empty so the
  results sheet shows, and `selectPlace` gates on `navigating` -> `addStopDuringNav` ->
  `NavSession.addStop` (user-ordered replan: the pick becomes the NEXT stop, marks null until the
  new route lands so a failed fetch keeps the stop for the next reroute/recheck; no back-on-course
  discard, no cooldown). BACK order: results list, then the chip row, then end-nav - browsing gas
  stations must never end the drive. Fork adaptations: the magnifier button matches the fork bar's
  default-size FilledTonalIconButtons (upstream's 54dp driving-target sizing is not in this fork),
  the `selectPlace` gate is merged with the a17eded6 inert-map-taps guard (a tap with no results
  list open stays inert), and under D-pad the chip row auto-focuses its first chip on arm
  (`rememberDpadAutoFocus`, beyond upstream).
  is open the search bar swaps for a Google-style endpoints card (origin ring / stops / red
  destination pin down a glyph rail, back arrow left, swap right, an Add stop row when no stops
  exist); `DirectionsPanel` LOST its header params (originName/onEdit*/stops/onAddStop/onEditStops/
  onSwap/onClose) and keeps mode chips / time chooser / routes / Start, plus the fork-only
  `flockOnRoute`. The card hides while the search overlay, pick-on-map, steps preview or stops
  editor own the screen; it uses colorScheme tokens (it replaces the search bar), NOT SheetPalette.
  MapScreen measures its bottom edge (`topCardBottomPx`) and passes `cameraTopInsetPx` to
  VelaMapView so the route fit frames below the card. The card has NO auto-focus on purpose - the
  panel's Drive tab keeps `rememberDpadAutoFocus()`. Heads-up InfoCards sit UNDER the measured card
  in directions mode (the 96dp constant was tuned for the search bar); with stops present a compact
  + button under the swap adds another stop (upstream cc691c0f). DIFF-ONLY verdict on cc691c0f's
  third change: upstream deleted the "Rerouted to avoid cameras" flash, the fork KEEPS it (kept
  once already in the 2026-07-14 smalls batch; the under-card offset removes upstream's overlap
  complaint, and the flash is real information when the list auto-picks a different route).
- **Chip style = stadium pills:** EVERY chip (map CategoryChips, results-panel filter
  chips Open-now/top-rated/price/sort + the collapsed "N results" pill, PlaceSheet travel-mode chips
  now with a leading `Icons.Default.Directions*` glyph, Settings vibrate-on-turns FilterChips) sets
  `shape = androidx.compose.foundation.shape.CircleShape` - full-radius pills, Google-style. Keep any
  new chip on CircleShape; monochrome leading icons (tint `onSurface`, not the teal primary) so it
  reads single-ink like Google's.
- **Search-results sheet - BOTTOM sheet with drag detents (`MapScreen.SearchResults`).**
  - Results rise from the BOTTOM, Google-style. It renders with the other bottom surfaces in
    MapScreen's bottom `when` (nav / directions / place sheet win the slot first) and shares the
    place sheet's detent grammar: **MINIMIZED** (a short "N results" bar; = the VM's
    `resultsCollapsed`, so the back gesture and the sheet agree) ↔ **PEEK** (~0.42 list cap) ↔
    **EXPANDED** (~0.82, fills the screen).
  - Handle TAP steps up; drag UP grows a detent, DOWN shrinks one; the nested-scroll connection steps
    ONE detent per gesture (re-armed in `onPreFling`), a hard fling can cross two (matches Google).
  - **BACK also steps one detent** - `resultsExpanded` is HOISTED to MapScreen so the BackHandler
    does expanded → peek → minimized → CLEARED (a back on the minimized bar runs `clearSearch()` to
    the bare map, not exit the app); and the sheet modifier carries `statusBarsPadding()` so the
    expanded handle pill stops below the clock / camera cutout.
  - **Camera frames the result CLUSTER:** the marker-fit branch in VelaMapView median-centers the
    pins and drops outliers past 4x the median spread (min 40 km) so one stray far hit can't zoom to
    a continental view; it fits with the results-sheet bottom inset (0.50 screen) so pins sit above
    the sheet; `lastFittedMarkersKey` re-arms while the sheet is minimized so expanding re-frames;
    and the fit CONSUMES `lastCameraTarget` (else the recenter branch re-fires on the STALE VM center
    and yanks the camera back to before the search).
  - There is **NO "hide results" button**.
  - **Filter chips are `ElevatedFilterChip` with an explicit filled `chipColors`** (subtle alpha
    tint off, solid `primary` teal + check on, `border = null`).
  - **Chrome:** `resultsShown` (peek/expanded) hides the scale bar / locate FAB / "Search this
    area"; `resultsMinimized` shows them again but LIFTED by `chromeLift` (76dp). The compass is
    MapLibre's built-in (`setCompassMargins`), which fades facing north (Google's behaviour) and
    reappears when rotated/tilted or during heading-up nav. Its browse-mode top margin is
    statusBar + 122dp so it sits BELOW the floating search bar and the category chips (8dp under the
    status bar put it exactly behind the bar, a half-hidden circle - ported from upstream `292f8d6`).
- **Map tap resolution order (`VelaMapView` click listener).** A single tap (24dp hit box) resolves,
  in priority:
  - (1) our search-result pin → `onMarkerTap`;
  - (2) an ambient Google POI dot → `onAmbientTap`;
  - (3) a greyed alternate route line → `onSelectAlternate`;
  - (4) a NAMED basemap POI (a business) → `onPoiTap`;
  - (5) a **HOUSE-NUMBER label** (basemap `vela-housenumber` `housenumber` or the address overlay
    `vela-addr-*` `number`, queried by layer id) → `onAddressLabelTap(number, labelPoint)`;
  - (6) an unnamed POI icon (has `class`, no name) → reverse-geocode at the tap;
  - (7) a **BUILDING footprint** (`building`/`building-3d` basemap fill or the `vela-ovl-*` overlay
    fill, queried by layer id) → reverse-geocode at the tap;
  - else nothing (only a long-press drops a raw coordinate pin on empty land).
  - **The house-number case must SNAP to the tapped number:** `MapViewModel.onAddressLabelTap` LEADS
    the pin with the label's own number and uses the reverse-geocode only for the street/city,
    replacing whatever house number the geocode led with (a regex strips `^\s*\d+\S*\s+` then
    prepends the tapped number) - Google's reverse-geocode snaps to the nearest ADDRESSABLE point,
    which for a tapped OSM label routinely returns a NEIGHBOUR. A real business sitting on the point
    still wins (if the geocode has a rating/category it's shown as-is).
- **Place-content toggles:** `ShowReviews` / `LoadPhotos` reactive holders
  (`ui/PlaceContent.kt`, same shape as `LiveReviews`, init in VelaApp, rows in Settings → Map).
  They gate BOTH fetch (`fetchReviews`/`fetchPhotos` first line) and render (PlaceSheet `hasReviews`
  + the photo-hero `if`), so off = zero scrape traffic. Keep any new review/photo surface behind them.
- **"Hide adult categories" toggle:** `HideAdult` holder (`ui/PlaceContent.kt`, default **off**, init
  in VelaApp, row in Settings → Map).
  - It flips `CategoryFilter.enabled` (a `:core` flag) - `:core`'s `data/CategoryFilter` filters
    adult/nightlife/alcohol/gambling/smoking places at the `GoogleMapsDataSource.search`/
    `nearbyPlaces` seam.
  - Match is CATEGORY-only (never name) and PRECISE (`EXACT`/`PHRASE`, food "…bar" kept); the keyword
    lists are **multilingual** (categories arrive localized via `hl=<lang>`, so the filter must too).
    Unit-tested (`CategoryFilterTest`).
  - NB the `:core` flag pattern (not reading the app holder from `:core`) is deliberate - mirror it
    for any future content gate that must act inside `:core`.
- **"Hide website & external links" toggle:** `HideExternalLinks` holder
  (`ui/PlaceContent.kt`, default **off**, init in VelaApp, row in Settings → Map). Gates the Website
  pill/row, the Street View pano and the Book/Reserve/Order action in `PlaceSheet`. No restricted build
  flavor / LockableToggle machinery; keep holders in the plain `ShowReviews` shape. Gate any new
  external-link surface on a place page behind this holder.
- **In-app updater (`app/update/SelfUpdater.kt`).**
  - Reads `releases/latest` from `alltechdev/vela-dpad` → tag `v0.0.<run>` → versionCode = `<run>`
    compared to BuildConfig; newer → `MapUiState.updateInfo` card on the bare map.
  - Download = no-call-timeout client (~80 MB APK) + zip-magic check → `filesDir/updates/`
    (FileProvider `updates` path) → ACTION_VIEW package-archive; the OS verifies same package +
    signature.
  - Launch check ~daily behind `self_update_check` (Settings → Version, default on); manual
    Check-for-updates button there too. "Not now" stores `update_dismissed_code` (only a NEWER
    release re-offers).
  - The tag parse takes the run number for the versionCode; update `SelfUpdater.check` if the
    versionCode scheme ever changes.
- **Zoomed-in pan perf:**
  - (1) `reportScale` (fires per camera-move FRAME) only pushes to compose when mpp moved >1% - an
    unconditional write recomposed the scale bar every pan frame; keep the gate.
  - (2) Both house-number layers (`vela-housenumber` basemap + `vela-addr-N` overlay) carry
    `textIgnorePlacement(true)`: they still YIELD to icons (allow-overlap stays false) but never
    enter the collision index - cheaper placement at street zoom and numbers can't evict icons
    whatever the layer order.
  - (3) `ui/Buildings3d` holder + Settings → Map "3D buildings" toggle sets visibility on the basemap
    `building-3d` fill-extrusion layer (a LaunchedEffect in VelaMapView owns visibility; applyLight/
    applyDark only colour it) - extrusion is the fragment-heavy layer, the documented 5a-class
    stutter source at z16+.
- **Light/dark is `AppTheme` (`ui/theme/AppTheme.kt`), not the OS.** Read the
  in-app theme with the composable **`isAppInDarkTheme()`** - never call
  `isSystemInDarkTheme()` directly in app UI (it ignores the user's Light/Dark/
  System choice in Settings → Appearance). `AppTheme.mode` is a process-wide
  reactive `mutableStateOf` (same shape as `ui/Units`), persisted to
  `vela_settings`, `init()`-ed in `VelaApp`; flipping it recomposes the theme and
  reloads the map style (`VelaMapView`'s styleKey carries `dark=`).
- **Basemap layer gotchas (`VelaMapView.ensureLayers`/`applyLight`/`applyDark`, OpenFreeMap Liberty).**
  - (1) **`maxzoom` is EXCLUSIVE** - the bundled `building` FILL layer is `minzoom 13 / maxzoom 14`,
    so `setMinZoom(14f)` alone collapses its range to empty and the flat footprints never paint
    (you'd see only the faint `building-3d` extrusion). The fill needs a matching **`setMaxZoom(24f)`**
    to re-open the top; keep it. `building-3d` (fill-extrusion) is gated to **z16+** on purpose (the
    flat fill carries the browse-zoom footprint look; extrusion is the per-pixel-expensive part on a
    Pixel 5a).
  - (2) **House numbers** render via the runtime `vela-housenumber` SymbolLayer (OMT `housenumber`
    source-layer, `minZoom 19` - house numbers only at the ~50 ft scale; 17.5 still carpeted whole blocks, 2026-07-13) - OpenFreeMap
    **does** serve that source-layer (verified vs the live TileJSON + z14 tiles), so it works;
    coverage is OSM `addr:housenumber` (partial), not a render bug.
  - (3) The runtime loads the style from the **LIVE** URL `MapStyle.LIBERTY.uri =
    https://tiles.openfreemap.org/styles/liberty` (`fromUri`), and offline downloads use the same URL
    - both **auto-follow OpenFreeMap's current tile snapshot**, so there is NO dated-path/
    blank-basemap risk. The bundled `liberty-roboto.json` asset (which pins a dated `planet/<snapshot>`
    path) is **parked + unused** - the `asset://`/`fromJson` path in `VelaMapView` is dead code kept
    only as reference; don't be misled by its stale path.
  - Verify basemap edits on-device in **both** themes.
  - **A style dies the instant `map.setStyle` is called; guard every deferred access.** During a
    light/dark flip the OLD `Style` object becomes invalid immediately, and ANY access to it (even
    reading `.layers`) throws "Calling getLayers when a newer style is loading" - not just mutation.
    The overlay `LaunchedEffect`s are keyed on `darkTheme`, so they re-run in the load window against
    the stale reference and crashed the app. So: null `styleRef` FIRST (before `setStyle`, re-set in
    the callback) and wrap each effect's style enumeration/getLayer/setProperties in `runCatching`.
    (Ported from upstream `328a5ab`.)
- **D-PAD-FIRST IS THE FORK'S REASON TO EXIST - NON-NEGOTIABLE (read before touching ANY UI).**
  Vela must be **100% operable with a 5-key D-pad (↑ ↓ ← → + OK) and hardware BACK, on a device with
  NO touchscreen** (a feature phone). Touch is a *bonus*, never a requirement. A change that
  regresses it is a **release blocker**. Every rule below is MANDATORY for any new or edited UI and is
  **enforced by `tests/dpad/` - run the auditors before every UI commit, wire `audit_static.sh`
  into CI**:
  1. **Opens already focused.** Every screen / overlay / sheet / dialog / menu lands focus on a
     primary element the instant it appears. Attach `rememberDpadAutoFocus()`. The bare map is the ONE
     exception (it opens ambient; the first arrow reaches the search bar). Menus/dialogs: use
     `VelaMenu`/`VelaDialog` (a stock Compose `DropdownMenu`/`AlertDialog` **cannot** be pre-focused -
     proven; never use them for new UI).
  2. **Every interactive element is focusable WITH A VISIBLE RING.** Any `.clickable` /
     `.combinedClickable` / `.toggleable` / `.selectable` needs `Modifier.dpadHighlight(shape)` in its
     chain (Material's default indication is too faint on Vela's grey sheets). Material
     buttons/chips/switches keep their built-in indication (OK). A bare `.focusable()` is allowed ONLY
     if it draws its own affordance (the map target's crosshair/pill, a full-screen viewer).
  3. **Every gesture has a key path.** Any `detect*Gestures` / `draggable` / `swipeable` /
     `awaitPointerEventScope` / `.scrollable` must have an `onKeyEvent` or a focusable control that
     does the same thing, in the same composable. The map's pan/zoom/tap live on `MapDpadController`.
  4. **No focus traps.** BACK always exits a surface. A text field in a vertical list gets
     `Modifier.dpadFieldEscape()` (UP/DOWN escape it). A **vertical-list screen swallows bare
     LEFT/RIGHT** with `Modifier.dpadSwallowHorizontal()` (on the scroll container AND any lone control
     outside it, e.g. a top-bar back button) - a no-target horizontal move otherwise CLEARS focus
     irrecoverably in a `verticalScroll` Column.
  5. **Dialogs & menus MUST fit a small screen.** Wrap dialog/menu content in a `verticalScroll` (or
     `LazyColumn`) capped to a fraction of the screen height so a tall body scrolls and the buttons
     stay on-screen. (`VelaDialog`/`VelaMenu` already do this; keep it.)
  6. **Touch stays byte-identical.** Every D-pad affordance is gated on `rememberDpadMode()` /
     `rememberDpadFirstDevice()`; the touch path must be unchanged. Key device behaviour off the LIVE
     input mode where it matters (e.g. the search bar shows the soft keyboard on a touch tap, hides it
     on a D-pad OK - a hybrid touch+keypad phone reports `dpadMode` true but still wants touch typing).
  7. **Theme via `isAppInDarkTheme()`**, never `isSystemInDarkTheme()` in app UI.
  8. **Merge-friendly** (this fork rebases on upstream): new behaviour in new files
     (`DpadFocus.kt`/`VelaMenu.kt`/`VelaDialog.kt`/`MapDpadController.kt`), shared-file edits as small
     anchored insertions, one commented D-pad import block per file. **Reuse the helpers - do not
     reinvent** `dpadHighlight`/`dpadAutoFocus`/`dpadSwallowHorizontal`/`dpadFieldEscape`/
     `dpadRowSibling` (the last wires LEFT/RIGHT across a button/chip row inside a vertical list that
     swallows bare L/R - or the row's siblings are unreachable; see issue #24).
  9. **Enforcement (two suites):** `tests/dpad/` - `audit_static.sh` (no device; every rule above
     as a source scan; **must be 0 violations**, CI-ready), `audit_dynamic.sh` (every surface opens
     focused, multi-axis traversal never loses focus + reaches all distinct elements, BACK exits),
     `run_all.sh` (per-surface). `tests/small_screen/` - the feature-phone twin:
     `audit_smallscreen.sh` (shrinks the display; nothing clipped off-screen) + `audit_dialogs.sh`
     (dialogs/menus scroll and keep buttons on-screen). A UI PR that fails ANY of these does not merge.
- **D-pad-only operation implementation (`docs/dpad.md`).**
  - Helpers in `app/ui/DpadFocus.kt` (`rememberDpadMode`/`rememberNoTouchDevice`/
    `Modifier.dpadHighlight`/`Modifier.dpadFieldEscape` - makes a text field's UP/DOWN escape it
    instead of being swallowed as a cursor move, so controls below the field stay reachable - and
    `rememberDpadAutoFocus()` - attach its `FocusRequester` to a screen's primary element so focus is
    PLACED on appearance, no wake-up keypress; retries because the node isn't attached on frame 1).
  - The map is key-driven via `app/ui/map/MapDpadController.kt` (wired in `VelaMapView`, key handling
    + crosshair + zoom buttons in `MapScreen`).
  - **Detection is CONSERVATIVE - do not loosen it.** `rememberDpadFirstDevice` (`detectDpadFirst`)
    returns true ONLY for a genuinely touchless device (`!FEATURE_TOUCHSCREEN`) or a PHYSICAL
    (non-virtual) `InputDevice` with `SOURCE_DPAD`. It must NOT count the framework's Virtual
    aggregate device (id −1): it reports `KEYBOARD | DPAD` on essentially every phone, so counting it
    makes `dpadMode` always-true on ordinary phones and BREAKS the search bar (a tap no longer opens
    the field / raises the keyboard; the `+`/`−` zoom buttons show under touch).
  - A fake-touchscreen keypad phone is NOT D-pad-first then; it gets full D-pad operation reactively
    on the first key via `rememberDpadMode` (`dpadFirst || inputMode == Keyboard`). The soft keyboard
    in `SearchBar` is likewise keyed off the LIVE `inputMode`, not the static device type, so a touch
    tap raises it even on a hybrid phone.
  - Rules when touching UI: (1) every new interactive element must be focusable with a visible ring
    (`dpadHighlight`) and every new gesture needs a key alternative; (2) D-pad code CALLS THE TOUCH
    PATHS (the named `handleTap` lambda, `gestureMove`, `navUserZoom`) - never fork them; (3) all
    D-pad affordances gate on `dpadMode`/`noTouch` so touch UX stays byte-identical; (4) keep the
    diff merge-friendly - new behaviour in new files, shared-file edits as small anchored insertions
    (the one commented import block per file).
  - Search-overlay focus is subtle (armed field + explicit `searchExpanded` flag - THREE traps in
    docs/dpad.md: opens-on-focus, can't-BACK-out, and DOWN-must-escape-into-the-suggestions); don't
    "simplify" it back to bare field-focus.
  - Choose-on-map keeps the map pannable to place the pin (a `pickOnMap` exception in
    `mapTargetHidden`) and the directions panel is scroll-capped so **Start** is reachable with 4
    alternates.
  - The one raw WebView in the app - the full-screen "Read all reviews" panel (`ReviewsPanel`,
    `fullScreen`) - maps ↑/↓ to `pageUp`/`pageDown` + `requestFocus()`es so it scrolls by D-pad (a
    WebView's default is to hop focus between links, not scroll); exit is always hardware BACK via the
    `Dialog`'s `BackHandler`.
  - **NO screen/view may open with nothing focused** - Compose focus recovery is nondeterministic, so
    every screen attaches `rememberDpadAutoFocus()` to a primary element (Settings→back, Welcome→Get
    started, place sheet→handle, directions→Drive tab, steps→first row, reviews→back arrow); the map +
    photo gallery already self-focus. When adding a screen, give it an auto-focus target.
  - **Menus & dialogs (the hard one): a Compose `DropdownMenu` Popup / `AlertDialog` can NOT be
    pre-focused (~10 approaches proven to fail - requestFocus/moveFocus/synthetic KeyEvent); only a
    hand-built RAW `Dialog` with an explicit `.focusable()` element auto-focuses.** So use
    **`VelaMenu`** (`ui/VelaMenu.kt`, drop-in DropdownMenu: anchored DropdownMenu under touch,
    auto-focusing raw-Dialog chooser under D-pad) and **`VelaDialog`** (`ui/VelaDialog.kt`, drop-in
    two-button AlertDialog that auto-focuses its dismiss button) - NEVER a bare `DropdownMenu`/
    `AlertDialog` for new D-pad UI. Their buttons/items focus via `.focusable()`+`.onKeyEvent` (OK) +
    `pointerInput` (touch), NOT `.clickable` (whose nested focusable won't take requestFocus in a
    Dialog window).
- **Localization (i18n) is three layers, one control (`AppLocale`, `ui/`, same process-wide reactive
  holder shape as `AppTheme`).** `AppLocale.language` = "" (follow system) or a code; Settings →
  Language picks it.
  - (1) **Spoken nav** - the GENERATED turn-by-turn text is a per-language `NavStrings` table in
    `:core` (`core/i18n`), switched by `NavStringsRegistry`; `AppLocale.apply()` drives it. **BOTH
    routers feed it:** `RouteGeometry.osrmPhrase` (online OSRM) AND `GraphHopperRouteEngine.ghPhrase`
    (offline) map their maneuvers to the OSRM `(type, mod)` token pair and call
    `NavStringsRegistry.current().phrase(...)`, so offline routes localize through the same 11 tables.
    - **The chosen neural voice must actually speak that language** - `VoiceGuide` guards on
      `NeuralSynth.voiceLanguage` and, on a mismatch, falls back to a system TTS in the target
      language (or stays silent + fires a "get a matching voice" hint) rather than reading Russian nav
      text through the English Piper model.
  - (2) **UI chrome** - all ~330 user-facing `:app` strings live in `res/values/strings.xml`
    (English) + `res/values-<lang>/` for the 14 translated languages (fr de es it pt nl ru pl sv uk zh zh-rTW ja iw),
    referenced via `stringResource`/`getString`. The runtime switch is `AppLocale.wrap(context)`
    (overrides the Configuration locale, **no-op when following the system** so the default path is
    untouched) applied in **both** `MainActivity.attachBaseContext` (Compose UI) and
    `VelaApp.attachBaseContext` (ViewModel/notification `getString`); changing the language calls
    `recreate()`.
  - (3) **Google POI content** - the scrape's `hl=en` is rewritten to the app/system language at
    request time (`GoogleMapsDataSource.localized()`, no-op for English) so categories/hours/status/
    price come back localized. **The rewrite is GATED to `SearchParser.STATUS_LANGS` (= the 11
    keyword-table languages, keyed off `CLOSED_WORDS`)** - for any OTHER locale the scrape stays
    `hl=en`, because a status string in a language `parseOpenNow` can't read leaves openNow null
    forever and the UI can't colour open/closed; English text the English table handles is the safer
    fallback.
  - The **open/closed BOOLEAN is parsed from the localized status TEXT against a per-language keyword
    table** (`SearchParser.parseOpenNow(status, lang)`, `lang` = the same `Locale.getDefault()` that
    set `hl=`; CLOSED words are matched FIRST - "Opens 5 AM" / "Ouvre à 07:00" / "Fechado" / "Opent om
    9:00" are prefix-cousins of the open words, so open-first matching paints a closed Starbucks
    green).
  - **Do NOT resurrect the numeric status-code path** (`openFromCode`, paths `statusCodeRich`/
    `statusCodeSimple`): those ints are span/style markers, not open/closed codes (closed pharmacies
    carried "open" 6, an Open-24-hours place carried 13/4 and rendered red). `placeStatusColor(status,
    openNow)` colours from the boolean and refuses to green English text that literally reads closed
    even if fed `openNow=true`.
  - `gl` (region) still `us` - GPS-region `gl` is a follow-up.
  - **Dual-purpose literals stay inline on purpose** - strings that double as a logic key (place
    "Open"/"Closed" → status-colour parser, the map category chips / search-along-route chips are also
    the query, review sort/tab labels branch a `when`) are NOT in strings.xml; they localize only once
    display text is split from the logic key.
  - **Names/addresses/reviews are DATA - never translated.**
  - Adding a user-facing string means: add it to `values/strings.xml` AND all `values-<lang>/`, and
    match the `%1$s`/`%2$d` placeholder TYPE to the arg (Int → `%d`, else `%s`; a `%d` fed a String
    crashes).

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
- **Remote calibration (`calibration.json` at the repo root).**
  - The `pb`/proto templates and endpoint URLs (search, directions, reviews, **photos** -
    `photosEndpoint`/`photosProto` for the `hspqX` gallery RPC) are remotely updatable: `CalibrationStore`
    (in `:core`, `config/`) fetches `calibration.json` from the repo's raw URL at launch and adopts it
    when its `version` is higher than the bundled `Calibration.DEFAULT`, provided every endpoint host is
    on the allowlist (`www.google.com`/`google.com`).
  - The bundle also carries **`defaultVoiceId`** (String - the Piper voice a fresh install downloads +
    activates), **`defaultVoiceSpeaker`** (int - only tunes libritts_r's 904 variants) and
    **`defaultVoiceSpeed`** (float - spoken-directions speed), so a favourite voice/speaker/pace can be
    pushed as everyone's default with a version bump + re-sign, no app release (a user's own
    `voice_model`/`voice_speaker`/`voice_speed` pick still wins). Shipped defaults (calibration **v13**):
    voice **HFC Female** (`en_US-hfc_female-medium`), speaker 14 (libritts only), speed **0.8×** -
    matched in the compiled `Calibration.DEFAULT` + `VelaPiper.DEFAULT_VOICE_ID`.
  - NB the neural voice lengthens pauses at periods by **splitting the utterance on sentence boundaries
    and splicing silence in-app** (`SpeechText.splitSentences` in `:core` + `PiperSynth.concat`) - sherpa-onnx's
    `silenceScale` config is a measured no-op on the Piper/VITS path, don't reach for it.
  - **Every fragment gets terminal punctuation before synthesis (`PiperSynth`):** a bare-ending fragment
    ("turn left") gives the model no final prosody contour, so it trails off and swallows the last
    consonant ("lef" instead of "left"). A `;` is appended to any fragment ending in a letter/digit;
    punctuation is language-neutral, so it's safe for every Piper voice.
  - Spoken text also runs through `SpeechText.spokenNumbers` in `EnNavStrings.expandForSpeech` - 3-digit
    **street ordinals** ("128th" → "one twenty eighth", **space not hyphen** - the hyphenated compound
    gets a reduced/flapped "-ty" from the neural voice) are pre-expanded so the neural G2P doesn't mangle
    them into "one, hundred and 28th" (only 100–999; 1–2 digit + 4-digit+ are left for espeak).
  - And `NavEngine` **does not announce the DEPART maneuver** - `NavSession.start` speaks it once
    ("Starting navigation. Head east on F St"); the engine skips it (distance ≈ 0), else the opener gets
    clipped by a re-announced "head out".
  - **Multiple downloadable voices (voice browser).** `VelaPiper` is one engine (`ENGINE_ID =
    "vela.piper"`) that holds ANY of many Piper voices, each in its own `filesDir/piper/<id>/` dir
    (`<id>.onnx` + `tokens.txt` + `espeak-ng-data/`, the sherpa `vits-piper-<id>` archive layout). The
    **installed set is derived from the filesystem** (`installedVoiceIds`, keeps only complete dirs → a
    partial download self-heals), the pick persists in **`voice_model`**, and **speaker choice is
    per-voice** (`voice_speaker_<id>`; the legacy global `voice_speaker` is migrated onto libritts_r).
  - The browsable catalog is `PiperCatalog` in `:core` (pure data, unit-tested, ~40 curated voices across
    11 languages; URL = `…/tts-models/vits-piper-<id>.tar.bz2`). `PiperSynth.ensureLoaded` reloads when
    the selected voice changes; `PiperSynth.reloadVoice()` is the SINGLE switch trigger - it bumps the
    generation counter (aborting any in-flight utterance) then tears down + rebuilds on the same serial
    worker, so `tts` is never freed mid-`generate()`. `MapViewModel.migrateFlatLayoutIfNeeded` (first
    thing in `init`) relocates the old flat single-voice install in place (rename, copy-fallback,
    verify-gated, re-runnable) - never re-downloads.
  - **Voice search (speak a query into the search bar), two tiers.** `ui/VoiceSearch` (process-wide
    reactive holder, `init` in `VelaApp`) resolves the mic mode. **tier-1 on-device** =
    `voice/WhisperRecognizer` (Whisper tiny int8 + Silero VAD via the SAME bundled sherpa-onnx AAR as
    Piper - `OfflineRecognizer`/`Vad`; the wholesale `com.k2fsa.sherpa.onnx.**` R8 keep already covers
    it) recording through `AudioRecord`; the model is `voice/AsrModel` (~58 MB, files in
    `filesDir/asr/whisper-tiny/`). **tier-2** = a `RECOGNIZE_SPEECH` intent hand-off to an installed
    voice-input app. The mic lives in `ui/search/SearchBar` (`onMic`, shown only when the mode isn't
    NONE); the listening sheet is `ui/VoiceCaptureDialog` (raw D-pad-focusable `Dialog`, Done
    auto-focuses); wiring + the RECORD_AUDIO launcher + the download-offer are in `MapScreen`; the
    Settings -> Search section (toggle, model download/remove, engine picker) is in `SettingsScreen`.
    Needs `RECORD_AUDIO` (manifest; asked at the mic tap).
    - **Model hosting: the `asr-models` GitHub release on THIS repo** (fixed-tag prerelease, like
      `routing-graphs`/`building-overlays`; `AsrModel.URL`). **The archive MUST be a `.tar.bz2` whose
      single top-level folder holds the 4 files** (`tiny-encoder.int8.onnx`, `tiny-decoder.int8.onnx`,
      `tiny-tokens.txt`, `silero_vad.onnx`) - `KokoroInstaller.download` (reused for the ASR download)
      unpacks bzip2 and RENAMES that inner folder to `AsrModel.dir`, so a `.tar.gz` or a flat/no-folder
      archive would fail to install. (The mirror was re-packed from upstream's `.tar.gz`; drop the macOS
      `._*` resource forks when re-packing.)
  - **Any large download (voice model, routing graph, building overlay) MUST NOT use the shared OkHttp
    client** - its `callTimeout(12s)` (scrape-bounding) aborts the body read mid-stream, `runCatching`
    eats it, and the asset SILENTLY never installs (no crash, no log). `KokoroInstaller`,
    `RoutingGraphStore`, `OverlayTileStore` **and `VoiceInstaller`** (the TTS-engine APK download; a
    >12 s APK fetch otherwise silently falls back to the F-Droid web page) each derive a `downloadHttp`
    with `callTimeout(0)` + `readTimeout(60s)` for the body; only the tiny manifest/version fetch stays
    on the shared short-timeout client. `OverlayTileStore.download` is also serialized behind a `Mutex`
    (+ a first-line "already installed" re-check) so two callers for the same region can't interleave
    writes into the one `.tmp` (whose 7-byte magic check could then pass on a corrupt archive).
  - Settings → Voice → **Voice library** is the browser; the multi-speaker variant picker (Advanced)
    only shows when the SELECTED catalog voice has >1 speaker.
  - **To ship a pb/endpoint fix WITHOUT an app release:** edit the drifted field in `calibration.json`,
    **bump `version`**, **re-sign** (`./scripts/sign-calibration.sh`), commit `calibration.json` +
    `calibration.json.sig` to `main` - users pick it up on their next launch (raw.githubusercontent
    caches ~5 min). Keep the compiled `Calibration.DEFAULT`'s field VALUES (paths, endpoints, voice
    defaults) in sync with `calibration.json` when you cut a release - but `DEFAULT.version`
    intentionally STAYS `1` (the remote bundle's higher `version` must always win the adopt-if-newer
    check; the shipped `calibration.json` is at v13, `DEFAULT.version` at 1 - that gap is by design).
  - **Phase 2: the search parser's positional field-index paths are remote too** - the `paths` object in
    `calibration.json` (`name`, `address`, `rating`, `photos`, `featureId`, … as `[i,j,…]` arrays,
    relative to a result entry whose place node is `[1]`; `results`/`single` are root-relative). So a
    "Google moved field X to a new index" fix is also just an edit + version bump.
  - **All three result-shape gates follow `paths.name`** - `singleResultEntry`, `atThisPlaceEntries` and
    `findResultsArray` wrap the candidate as `[null, node]` and validate through `pathOf(paths,"name")`
    instead of a hard-coded `at(11)`, so a `paths.name` recalibration reaches the single-result /
    address-snap / fallback paths too. And the WebView details/popular-times path
    (`PopularTimesParser.parse`) threads the LIVE `cal.paths` through `SearchParser.parse`/
    `parsePopularTimes` rather than pinning `DEFAULT_PATHS`.
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
  `body`/`url`) shown as dismissable cards on the bare map (`MapViewModel.refreshNotices`,
  dismissed ids in `vela_notices` prefs) - push "search is down, fix coming" with no
  app update. Rides the same signed channel.
- **Phase 3: remote parse *logic*** via `transformsJs`.
  - A signed JS bundle run in a **Rhino sandbox** (`JsSandbox`, interpreted/`optimizationLevel=-1` for
    ART, `initSafeStandardObjects` so it can't reach Java/IO; a private `ContextFactory` arms Rhino's
    instruction observer as a **2 s wall-clock kill switch** - a runaway `while(true)` in a pushed
    `transforms.js` throws an `Error` (which JS can't `catch`) → the `runCatching` becomes the
    compiled-Kotlin fallback, so it can't hang search or, via `synchronized(this)`, wedge every later
    transform (unit-tested); `org.mozilla:rhino-runtime`, R8-keep in `core/consumer-rules.pro`).
  - `JsTransforms` exposes two search hooks - `parseSearch(rawResponse)` (full re-parse of a reshaped
    response) and `transformPlaces(placesJson)` (post-process) - over the flat `PlaceJson` contract;
    **compiled Kotlin is always the fallback** (no script / missing fn / any error → unchanged).
  - So a *response-shape* change can be hot-fixed too, not just a moved field. Wired in
    `GoogleMapsDataSource.search`.

## Degoogled constraints (hard rules)

- Location: AOSP `LocationManager` only - never `FusedLocationProviderClient`. **Fix discipline
  (don't regress):**
  - NETWORK (BeaconDB) fixes are DROPPED during nav and used in browse only when GPS has been quiet
    ≥12 s (`NETWORK_FIX_QUIET_MS`, OsmAnd's `useOnlyGPS` pattern) - they're 100-1000 m off and
    teleported the dot/reroutes.
  - Inter-fix `dt` comes from `loc.elapsedRealtimeNanos` (monotonic - `loc.time` mixes GNSS UTC with
    the network system clock and a negative dt bypassed the outlier gate).
  - Fixes with accuracy >50 m never feed `NavSession`.
  - The `minDistanceM=0f` registration MUST stay 0 (a distance filter starves fixes at a standstill -
    the frozen-speedo/creeping-puck bug).
  - Measured speeds pass a SYMMETRIC accel-bounded gate against the last ACCEPTED value
    (`gateMeasuredSpeed`, 2-fix persistence escape, shared with replay) - one-sided spike filters
    self-latch (a down-glitch to 0 then rejects every real speed as an up-spike forever).
  - The registered `LocationListener` MUST be an explicit `object` overriding all four callbacks,
    NOT the SAM lambda. The lambda implements only `onLocationChanged`; the provider-state callbacks
    have default bodies only from Android 11, so on Android 10 and below the framework calls
    `onProviderDisabled` (a present-but-disabled NETWORK provider, common on degoogled devices) and
    the lambda dies with `AbstractMethodError` on every launch. (Ported from upstream `84ab10f`.)
- Nav guidance discipline:
  - prompt/turn-now distances SCALE WITH SPEED in `NavEngine` (max(fixed, v×T); `spoken` stores band
    SLOTS not metres);
  - one prompt per update speaking the TRUE distance, silent catch-up past maneuvers >75 m behind;
  - proximity arrival (crow ≤40 m) + no rerouting within 150 m of the destination or while stationary
    (EXCEPT a FAR deviation: `FAR_OFF_M` 90 m counts at ANY speed, upstream a8f46047 - parking-lot
    creep sits under the 2 m/s moving floor forever, so the reroute/redrawn line never came);
  - off-route measured on the windowed/anchored projection (never whole-polyline min);
  - TUNNEL DEAD RECKONING (`MapViewModel.tunnelDeadReckonLoop`, upstream a8f46047): the engine only
    advances on fixes, so a GPS outage froze the whole stack; when the guidance feed goes quiet
    >3.5 s while navigating, on-route, not replaying and not from a standstill, the VM synthesizes
    1 Hz fixes ALONG the route at the last speed (decay tau 60 s, floor 1.5 m/s, cap 3 km) through
    the NORMAL `navSession.onLocation` path - puck/banner/voice keep working, `navStarved` keeps the
    "Searching for GPS" chip up for honesty, the first real fix re-anchors. Never feeds trip
    recording (no fake points in trips). Nav zoom range is 18.0->15.5 (was 17.3->15.0); GTFS stop
    icons hide during nav (declutter effect nulls `lastAppliedAmbient` so applyData re-asserts the
    OSM POI visibility, and the VM skips the stop fetch); the nav-end camera reset is one INSTANT
    move zeroing bearing/tilt/padding in a single snap (an animated level-out got cancelled
    mid-flight by the next camera write and left the browse map partially rotated);
  - reroutes are single-flight + cooldown + latch-clear-on-failure (a failed fetch must NOT kill
    rerouting - the event is edge-triggered);
  - ETA sums the remaining STEP durations × traffic ratio (never remaining/avg-speed).
  - The route line's driven/ahead cut is a GEOMETRY split (`ROUTE_AHEAD_LAYER` suffix over a
    traversed-grey full line) - MapLibre bakes line-gradients into a 256-texel texture (no crisp cut)
    and has no `line-trim-offset`; don't "simplify" it back to a gradient.
- Nav drive-report fixes:
  - (1) **Route line z-order** - the route line inserts BELOW the first symbol layer, but Liberty's
    first symbol is `road_one_way_arrow` (~idx 61) which sits UNDER the `bridge_*` layers (~63-82) →
    bridges paint over the route on bridges. `VelaMapView.ensureLayers` anchors instead to the first
    symbol AFTER the last `bridge_*` layer (a real label), so the route draws above all road+bridge
    geometry, still below text.
  - (2) **Exit consolidation** - OSRM splits one exit into ramp + fork/merge steps, each spoken
    separately ("Take exit 15"…"Keep right"…"Merge"). `RouteGeometry.consolidateExits` folds a ramp's
    immediately-following, <500 m-gapped FORK/MERGE run into the ramp maneuver (sums distances so they
    still tile the polyline; stops at any real turn / far gap) → one prompt. Unit-tested.
    - **Sibling `RouteGeometry.foldRenames`** folds a pure-rename CONTINUE (OSRM `continue`/`new name`
      going straight, no genuine fork - "5th St becomes Anderson Rd") into the PRECEDING maneuver
      so it's not its own banner card / step at all (NavEngine already silences its voice). Applied on
      BOTH routers (OSRM `parseOsrmRoute` + GraphHopper `toRoute`); a genuine-fork CONTINUE
      (`continueHasGenuineFork`, spoken) and STRAIGHT (a junction straight-through) are left alone.
      Unit-tested.
  - (3) **Feet steps** - `formatDistance` (banner) + all 11 `NavStrings.spokenDistance` (voice) round
    feet Google-style: 50 ft at/above 100 ft, 10 ft below.
  - (4) **Voice K/C** - `EnNavStrings.expandForSpeech` rewrites `<XX>-<n>` (CA-99, SR-99) → "State
    Route n" so espeak's G2P doesn't mangle the bare 2-letter code's onset.
  - (6) **Continue/straight lane silence** - a CONTINUE/STRAIGHT speaks its lane preface ONLY for a
    GENUINE fork (an "off" lane whose OWN indication is an explicit `straight`/`slight*` arrow, e.g.
    "use the left 2 lanes to stay on I-80"); a plain turn bay at an intersection (off lane marked only
    `left`/`right`, OR **`none`** = OSRM's "no painted arrow" sentinel, NOT "goes straight") while you
    sail straight through is silenced (`Route.continueHasGenuineFork` gates `NavEngine`'s escape hatch;
    it matches only `straight`/`slight*` on an off lane - `none`/`through` are excluded).
  - (5) **Traffic-light landmarks ("pass the light, then turn") - BUILT (Settings → Navigation →
    "Traffic-light guidance", OFF by default, English-only):** `RouteGeometry.enrichWithLights` folds a
    "pass the light, then …" clause into a surface-street TURN when 1–2 signals fall on the approach
    (`NavStrings.passLights`); signals from `OverpassTrafficSignals.fetchAlong` (keyless Overpass).
    - **Two refinements (unit-tested):** it EXCLUDES a signal AT the turn vertex itself (that's the
      light you turn at, not one you pass first - `distanceTo(turnPt) >= LIGHT_SNAP_M`), and it CLUSTERS
      matched signals within `LIGHT_CLUSTER_M` (30 m) before counting, because OSM maps one
      `traffic_signals` node per approach/carriageway at a junction - raw-node counting said "pass 2
      lights" for one intersection. Still needs a real-drive calibration of the thresholds.
- Nav fixes, round 2:
  - (1) **Replay arrow** - the arrow's visibility keys on the `displayBearing` passed to `applyData`
    (`VelaMapView` ~730), which prefers snap/compass/`myBearing`; recorded traces often carry no
    per-fix bearing, so with no route snap it went null and hid the arrow. Now falls back to the
    engaged puck's OWN route-derived heading (`navPuck.displayBearing`, seeded from the road segment by
    the motion ticker) while navigating.
  - (2) **Replay GPS snap-back** - `replayTrip` cancels+nulls `locationJob`, but `startLocation()` is
    guarded only by `locationJob != null`, so a permission callback / MapScreen effect re-started the
    live collector mid-replay and its real fixes overwrote `myLocation`+`center`. Two guards:
    `startLocation()` no-ops while `replaying`, and the live collector drops every fix while
    `replaying`. Replay's `finally` resumes live GPS once `replaying=false`.
  - (2b) **Replay teardown** (stop or natural end) - the `navSession→state` observer keeps
    `activeRoute` once nav stops (`else it.activeRoute`), so the `finally` must explicitly null
    `activeRoute`/`routes`/`directionsOpen`/step preview; and it snaps `myLocation`/`center` back to
    the user's real PRE-replay location (`resumeLoc`, captured in `replayTrip`). Gated on `ownedNav` (a
    replay riding an already-active nav leaves that route/location alone).
  - (3) **U-turn / back-on-course** - a U-turn strays >45 m → `RerouteNeeded` → async directions fetch
    (~1-3 s); but the U-turn outlasts the fetch, and by the time it lands the driver has rejoined the
    ORIGINAL line. `reroute()` captures `fromRoute` and, before adopting, discards the result if the
    driver is SOLIDLY back on it - `route === fromRoute && nav.onRouteStreak >= BACK_ON_COURSE_HITS(2)`.
    - **NOT bare `!offRoute`**: the offRoute latch clears on a SINGLE grazing fix (and `offDist` can
      match a parallel/overlapping leg), so one spurious graze would kill a legit missed-turn reroute.
      So `NavState.onRouteStreak` (consecutive on-corridor+moving fixes, computed in `NavEngine` beside
      `offRouteHits`, reset the instant off) gates it - a graze can't reach 2, a real rejoin does.
      Self-healing (a re-deviation re-fires the edge; no cooldown charged). Threshold tunable from a
      real-drive U-turn capture.
  - (4) **Traffic incidents** - DEFERRED: no keyless real-time source (Google keyless response carries
    none; incident tiles are proprietary binary; OSM has only stale roadworks; DOT/511 needs a token +
    is per-state). Congestion colouring already shows where it's slow. See ROADMAP.
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
- Voice: AOSP `TextToSpeech`, engine-selectable - never hard-depend on Google TTS.
  - **Plus an in-process neural option (Piper):** Vela bundles the **sherpa-onnx** runtime (arm64
    `.so`, from the `tts-runtime` release AAR - gitignored, fetched in CI, NOT committed) and downloads
    a **Piper VITS** voice into `filesDir/piper/<id>/`, run in-process by `app/voice/PiperSynth` (sherpa
    `OfflineTts` + `AudioTrack`) behind the `:core` `voice/NeuralSynth` seam (the AAR can't live in the
    `:core` library module). The default is **HFC Female** (`en_US-hfc_female-medium`, ~67 MB), the
    default once present.
  - **Non-obvious, all device-only (compiler-clean):** R8 MUST `-keep class com.k2fsa.sherpa.onnx.**`
    (JNI resolves classes by original name); and you must generate the WHOLE utterance before
    `AudioTrack.play()` (streaming underruns → AudioFlinger drops the track → SIGABRT).
  - The whole utterance is generated, but it's **written to the track in ~200 ms chunks with a
    `generation` check between them** (`PiperSynth`) so an interrupt (turn-now/rerouting/stop) takes
    effect within ~200 ms instead of blocking for the full utterance - safe against the SIGABRT rule
    because back-to-back chunk writes keep the buffer full (no underrun).
  - **Audio-focus is refcounted via the utterance callbacks; two leaks closed:** a system-TTS `speak()`
    returning `ERROR` enqueues no utterance so no callback ever fires - `VoiceGuide.speakViaSystem`
    rolls back the focus acquire on `ERROR`; and a failed system-TTS `onInit` used to queue every prompt
    into `pending` forever - it now clears `pending`, latches `systemInitFailed`, and fires
    `langUnavailable` instead.
  - **A Piper voice is a SINGLE-language model** - reading another language's nav text through it is
    gibberish. `NeuralSynth.voiceLanguage` exposes the loaded voice's lang (id prefix, `en_US-hfc_female`
    → "en"); `VoiceGuide.speakNow` compares it to the language the nav text is GENERATED in
    (`NavStringsRegistry.current().locale`) and, on a mismatch, routes to **Android `TextToSpeech` in the
    target language instead** (`speakViaSystem`, lazily creating a default engine as the fallback - the
    system `tts` is NOT shut down when the neural voice is active). If the system TTS has no voice for
    that language either, guidance stays **silent** (never mangles it through the wrong voice) and fires
    `langUnavailable(lang)` → `MapViewModel` flashes a "get a &lt;language&gt; voice in Settings → Voice"
    hint.
  - **(History: earlier iterations bundled Kokoro (`KokoroSynth`) and Matcha, both removed after
    on-device A/B. `MapViewModel` reclaims their old model dirs and sanitizes stale `vela.kokoro`/
    `vela.matcha` prefs to Piper. `project_vela_kokoro_tts` memory is that historical record, not the
    current design.)**
- Nav feedback: spoken guidance (`VoiceGuide`) + **direction-coded haptic turn cues**
  (`core/feedback/Haptics`, `NavEvent.Haptic`); toggle in Settings → Navigation.
- EU consent: `InMemoryCookieJar` (CoreModule) pre-seeds Google's `SOCS`/`CONSENT`
  cookies so a cookieless EU session isn't bounced to `consent.google.com` - don't
  strip those, and don't let a `Set-Cookie` downgrade `CONSENT` to `PENDING`.
- No GMS: no FCM/Firebase/Play Integrity/Fused. If push is needed later, use
  UnifiedPush; crash reporting via ACRA/self-hosted Sentry.
- **Photos use a hidden WebView** (`app/web/WebPhotoFetcher`).
  - The full gallery RPC (`hspqX`) serves real photos only to a real browser engine - OkHttp gets a
    bot-degraded Street-View-only reply (TLS-fingerprint detection, not headers).
  - The WebView loads `maps.google.com` **anonymously (no login)** and same-origin-fetches the RPC.
    This is the one place we run Google's JS - an accepted tradeoff for richer photos (lazy,
    best-effort, OkHttp fallback).
  - Gotchas: **desktop UA** (mobile UA → Google deep-links to `intent://`), block non-http(s)
    redirects, and use a `Handler` not `View.postDelayed` (a headless WebView never attaches).
- **Routing is OPEN, not Google.**
  - Turn-by-turn comes from **FOSSGIS OSRM** (`RouteGeometry.route`, `steps=true`, per-mode
    `routed-car`/`-bike`/`-foot`) - complete, street-named maneuvers + real geometry.
  - **Highways identify by `ref` not `name`** - `parseOsrmRoute` captures `ref`/`destinations`/`exits`
    (not just `name`) and `osrmPhrase` uses them ("Take exit 72B toward …"); `Maneuver.ref` feeds the
    banner shield even when the text shows a name.
  - **`routeOsrm` retries 3× w/ backoff** - a transient community-server blip otherwise drops nav to
    Google's abbreviated (nameless) steps.
  - Google's keyless `/maps/preview/directions` returns **abbreviated** steps for longer routes (a
    6-mi route came back with 2 of ~10 turns), so it's demoted to (a) the **live-traffic source** -
    `GoogleMapsDataSource.applyTraffic` scales OSRM's free-flow duration by Google's in-traffic/typical
    ratio and maps its congestion spans onto the OSRM geometry - and (b) the **fallback router** when
    OSRM is unreachable. The two are fetched in parallel.
  - **`OSRM_BASE` is the FOSSGIS community server (fair-use) - point at a self-hosted OSRM/Valhalla
    before any real release.**
- **Traffic-AWARE routing (option 3).**
  - OSRM's free-flow route ignores live traffic, so when Google *rerouted around a jam* its path differs
    from OSRM's. `directions()` detects this (`RouteGeometry.divergent` - sample Google's polyline, true
    if any point strays >700 m from OSRM's line) and, only then, re-runs OSRM **through ~12 points
    sampled off Google's polyline** (`sampleVias` → `routeVia`) so we follow Google's jam-avoiding path
    *with* full OSRM street-named steps.
  - Multi-waypoint OSRM returns one leg per via with spurious `arrive`+`depart` at each boundary -
    `parseOsrmRoute` filters all but the true first-depart/last-arrive. Free-flow routes (the common
    case) stay pure OSRM, untouched.
  - The traffic-snapped route leads **only when it earns it** - its live ETA must be ≤ OSRM free-flow
    best × `SNAP_ETA_MARGIN` (1.2), else a divergent-but-not-faster snap steps aside for OSRM's clean
    route. The `directions` diag logs `snapKept`/`gEta`/`osrmFF` to tune the margin from real
    side-by-side data.
  - **Per-alternate re-rank:** each Google route in `root[0][1]` carries its OWN `duration_in_traffic`
    (`parseRoute` reads `summary[10][0][0]` per route), so the returned list is **sorted by live
    in-traffic ETA - fastest leads, Google-style.**
  - **Sort key = the EXACT value the picker shows:** `compareBy({ durationInTrafficSeconds ?:
    durationSeconds }, { provisional })`. `RouteOption` displays `durationInTrafficSeconds ?:
    durationSeconds` and tags the min-SHOWN route "Fastest", so the sort MUST use the same expression -
    else the top/selected route and the "Fastest"-tagged route diverge and the fastest-shown route
    isn't at the top. The axis is already fair without the fudge factor: PRIMARY routes go through
    `applyTraffic` (their `durationInTrafficSeconds` = free-flow × the top Google route's ratio) and
    Google's alternates carry their own per-route `duration_in_traffic`, so a route only falls back to
    raw `durationSeconds` when there's genuinely no traffic signal for it. Do NOT bake an estimate onto
    `durationInTrafficSeconds` to "fix the axis" - `Route.hasLiveTraffic` keys off its nullness.
    Provisional routes are the stable tie-break.
  - **Alternates = GOOGLE's own alternate routes, NAME-ON-PICK:** `directions()` returns the named
    primary + each distinct Google route as a **provisional** `Route` (`Route.provisional` - polyline +
    live ETA now, turn-by-turn deferred), `dedupeRoutes`, prefers them over OSRM's free-flow alts, caps
    at `MAX_ROUTES`=4. Picking a provisional alternate (`MapViewModel.selectRoute` →
    `MapDataSource.nameRoute`, also on `startNav` as a safety) NAMES it - by snapping its polyline
    through OSRM (`routeVia`, guarded to reach dest) + re-applying Google's traffic. So only the route
    you drive gets snapped, and the picker loads fast.
  - **Next = swap `nameRoute`'s snap for on-device GraphHopper MAP-MATCH where the region's downloaded**
    (wobble-free); the snap stays the fallback.
- **Why not "always snap to Google's path"?**
  - Google's keyless **polyline is complete** (decoded from `root[0][7][i]`) even though its *step text*
    is abbreviated - so we *can* always trace it.
  - But doing it cleanly needs **map-matching**, and the public infra won't reliably give it: FOSSGIS
    **`/match` caps at 10 trace coords** (11+ → `TooBig`; confidence ~0.01 at that sparsity) and public
    **Valhalla `/trace_route` times out**.
  - The serverless fallback - dense-waypoint `/route` (40–100 vias, no cap) - *does* reproduce Google's
    path exactly, **but a via landing on a turn gets swallowed into a via arrive/depart → ~1-in-10 named
    turns lost**, so we do **not** always-snap.
  - Clean always-snap (and offline routing) is gated on an **on-device engine** - see the next bullet.
    Option 3 is the public-server stopgap and stays as the online/fallback path. **No backend needed
    for any of this** (the serverless constraint holds).
- **On-device routing engine = GraphHopper (`core/data/RouteEngine` + `GraphHopperRouteEngine`).**
  - Pure-JVM, runs on ART - validated end-to-end on device long ago via the throwaway `:ghprobe`
    instrumented module (now retired; the routing itself shipped). Chosen over Valhalla (no maintained
    Android map-matching binding) / BRouter
    (no street names) / Mapbox (token-gated). It's wired as a `:core` dep
    (`libs.graphhopper.mapmatching`, **OSM-import deps excluded** - osmosis/protobuf/woodstox/
    xmlgraphics are Android-hostile + only needed to *build* graphs, which we do off-device).
  - **Three ART workarounds, all in `GraphHopperRouteEngine` - don't remove:** (1)
    **`graph.dataaccess=MMAP`** (default RAMDataAccess static-inits a JDK-16 `VarHandle` method ART
    lacks); (2) **override `createWeightingFactory()`** to a hand-rolled `SpeedWeighting`+access-block
    (v11 compiles custom models via **Janino** → JVM bytecode ART can't load); (3) **swallow `close()`**
    (MMAP unmap uses `Unsafe.invokeCleaner`, absent on Android - keep one engine for the process
    lifetime).
  - **R8:** `consumer-rules.pro` keeps `com.graphhopper.**` + hppc/jts/jackson wholesale (GraphHopper
    resolves a lot reflectively) and `-dontwarn`s the excluded/absent refs - release build is clean
    (**but +~10 MB APK; tighter keeps / on-demand delivery is a later optimisation**).
  - Graphs are built off-device, one per region, and (Phase 1b) downloaded alongside the offline tiles;
    `RouteEngine` is selected by connectivity + graph-presence.
  - **Speed needs Contraction Hierarchies:** plain flexible A* with the interpreted `SpeedWeighting` was
    **7.6 s** for a 24-mi trip on a Pixel 5a; **CH prepared on the SAME `SpeedWeighting`** (the engine
    declares `setCHProfiles`, `tools/graphbuilder` builds it) → **188 ms**. Graphs MUST be built with CH
    on that weighting (CH bakes the build-time weighting), to **internal** storage (FUSE external was
    I/O-bound).
  - **`SpeedWeighting` ETA gotcha:** it reports time as `distance_m/speed` as if `car_average_speed`
    (km/h) were m/s - 3.6× too fast - so the engine AND `graphbuilder` override `calcEdgeMillis` to
    `distance_m·3600/kmh`; keep them identical.
  - **Encoded values = `car_access, car_average_speed, road_access, max_speed`** - the string is
    byte-identical in `GraphBuilder.java` and `GraphHopperRouteEngine.kt` (a mismatch fails graph load);
    keep it so. `max_speed` is the OSM `maxspeed` posted limit (km/h), a **passive stored column**
    (`OSMMaxSpeedParser` auto-registers; NOT in the weighting/CH, so it doesn't change routes) read by
    the speed-limit badge via `GraphHopperRouteEngine.currentRoadLimit(lat,lng)` - a `LocationIndex`
    snap + `EdgeIteratorState.get` off the **base graph** (CH-safe).
  - **Adding/removing an encoded value is a BREAKING graph-format change**: old graphs lack the EV and
    `getDecimalEncodedValue` THROWS - `currentRoadLimit` swallows it (badge hidden, no crash), but to
    actually light the badge up you must **re-bake + re-host every region graph** via
    `routing-graphs.yml`. Existing installs keep their old graphs until re-downloaded (no
    version-discriminator yet - a manifest `schema` bump so they auto-update is a follow-up).
  - **Status: DONE, graphs HOSTED + multi-region.** `RoutingGraphStore` (`:app`) downloads region CH
    graphs from a manifest (`BuildConfig.ROUTING_MANIFEST_URL`, override `-ProutingManifestUrl=` for
    local testing) into `filesDir/graphs/<id>/`, merging each into `filesDir/graphs/index.json`
    (`[{id,bbox:[S,W,N,E]}]`); `GraphHopperRouteEngine` lazy-loads a `GraphHopper` per region and routes
    a trip on the **smallest region whose bbox covers BOTH endpoints**, falling through to the
    next-smallest if that graph can't make the trip (`inBox`, unit-tested). Smallest-first because
    Geofabrik extract boxes carry a buffer that spills across borders; the same rule drives the picker's
    "covers your location" label + the tiles→routing combine, so all three agree.
  - Settings → **Offline** (one section: a **Map area** subhead = viewport tile download, and a
    **Routing regions** subhead = the picker) is a location-aware picker (regions covering the GPS fix
    sort first + flag "covers your location"; a name filter appears once the catalog is large);
    downloading offline map *tiles* for an area ALSO pulls that area's routing region
    (`MapViewModel.downloadRoutingForArea`). `directions()` uses the engine when OSRM is empty. A trip
    must fit ONE region's monolithic graph (cross-region → online).
  - **Hosting + world catalog:** graphs + `routing-manifest.json` are assets on the **`routing-graphs`
    GitHub release** (fixed-tag prerelease, never the "Latest" the APK tracks). The catalog is
    **`tools/routing-regions.json`** (135 regions, grouped by continent; `big:true` = country-sized). CI
    **`.github/workflows/routing-graphs.yml`** is a **race-safe matrix**: `prep` (group/ids → matrix) →
    parallel `build` (each region: `graphbuilder` CH graph → upload its own `<id>.zip` + emit a manifest
    *entry* artifact, via `scripts/build-routing-region.sh MANIFEST_MODE=emit`) → one `merge`
    (`scripts/merge-routing-manifest.sh` folds all entries into the manifest in a single replace-by-id
    upload; a `concurrency: routing-graphs-manifest` guard also serializes whole runs so back-to-back
    dispatches queue instead of racing two merge jobs).
  - **bbox MUST come from `osmium -g header.boxes`** (declared extract region) - `data.bbox` (node
    extent) is polluted by outlier nodes and made Oregon falsely cover WA.
  - Build one region locally: `scripts/build-routing-region.sh <id> "<name>" <pbf-url>` (all-in-one), or
    the graph alone: `./gradlew :tools:graphbuilder:run --args="region.osm.pbf out-dir"`. Local manifest
    test: serve a manifest+graph, `adb reverse tcp:8099 tcp:8099`, build with
    `-ProutingManifestUrl=http://127.0.0.1:8099/manifest.json` (localhost cleartext allowed by
    `res/xml/network_security_config.xml`; all other traffic stays HTTPS).
- **Offline PLACE packs - whole-region POI/address search, Organic-Maps-style (`app/offline/PoiPackStore` +
  `core/data/OfflinePacks`).**
  - Downloading a state (routing region) also pulls its place pack - a per-region SQLite db baked by CI
    from the SAME Geofabrik PBF (`scripts/build-poi-region.sh`: osmium tags-filter → export geojsonseq →
    `poipack_build.py` → SQLite → zip; workflow `poi-packs.yml`, a matrix clone of routing-graphs.yml
    with `merge-poi-manifest.sh`; release tag `poi-packs`, manifest `poi-pack-manifest.json`,
    `POI_PACK_MANIFEST_URL` / `-PpoiPackManifestUrl=`).
  - **Pack schema is NORMALIZED, not the app stores' own schema** (that naive shape was 761 MB for WA):
    `poi(id,name,lat,lng,category,address,phone,website,hours)` + `streetname(sid,street,street_norm)` +
    `addr(hn,sid,city,lat,lng)` + `streetpt(sid,lat,lng)` → WA = 335 MB raw / **143 MB zipped** (163k
    POIs, 2.8M addrs, 1.2M street pts, 92k street names).
  - The normalization is also the QUERY strategy: match street names first (~90k-row scan), hit the big
    tables only through sid/hn/lat indexes - never a LIKE scan of millions of rows.
  - `OfflinePacks` (:core singleton) holds the opened read-only dbs; `OfflinePoiStore.search` runs its
    same SQL on each pack (identical poi columns), `OfflineAddressStore` has dedicated pack paths
    (`packSids`/`packQuery`/`packStreetGeom` + reverse-geocode JOINs) merged into query()/streetGeom()/
    reverseGeocode(); counts include packs. `poipack_build.py` PORTS `normalizeStreet`'s ABBREV and
    OverpassPois' category formatting - keep them in sync.
  - Lifecycle: pack downloads after its region's graph (`downloadPoiPack`), deletes with it
    (`deleteRoutingGraph`), `registerPacks()` at VM init; graphs installed before packs get a **"Get
    places"** button on the Settings row (`downloadPoiPackFor`, with a "no pack published yet" status
    when the manifest lacks the region).
  - **Heads-up progress:** `RegionDownloadCard` in MapScreen mirrors the voice card
    (`routingDownloadingId`/`routingDownloadPct` then `poiPackDownloadingId`/`poiPackDownloadPct`, named
    by `regionDownloadName`).
  - Local pack test: build one with the script's osmium+python steps, serve manifest+zip on :8099,
    `adb reverse`, `-PpoiPackManifestUrl=http://127.0.0.1:8099/poi-pack-manifest.json`. **After pushing,
    dispatch Actions → "Build offline place packs"** (group=us etc.) to publish packs + manifest.
  - **Pack freshness: rev + monthly cron + row-level deltas.** Manifest rows carry
    `rev`/`updatedAt`/`counts{poi,addr,streetpt,streetname}` and optionally `delta{fromRev,url,sizeMb}`;
    `poi-packs.yml` has a monthly `schedule` cron (3rd, 07:15 UTC) whose prep step selects ALL catalog
    regions. `build-poi-region.sh` reads the LIVE manifest for the old rev, downloads the previous zip
    BEFORE clobbering it, builds the delta (`scripts/poipack_delta.py`, SQL EXCEPT per table into del_/
    ins_ tables), and publishes it only when it is under half the full size.
  - App: installed revs in `poipacks/revs.json` (`PoiPackStore.installedRev`); Settings shows "Update
    available" + an **Update places** button when the manifest rev is newer;
    `MapViewModel.downloadPoiPack(update=true)` applies the delta via `PoiPackStore.applyDelta` ONLY when
    installedRev == deltaFromRev, else full download. applyDelta runs one transaction (delete-by-full-row
    via a rowid JOIN with NULL-safe `IS` matching, then insert), verifies every table count against the
    manifest before committing, and re-registers packs on both success and failure.
  - **sids are STABLE content hashes** - SHA-1 of `street_norm` truncated to a positive 63-bit int,
    collision fails the build; NEVER a counter (a counter renumbers millions of rows on one mid-order
    insertion and the delta balloons to pack size).
  - `TABLE_COLUMNS` in PoiPackStore mirrors `poipack_build.py` + `poipack_delta.py` - keep all three in
    sync (`PRAGMA user_version=2`). Gotcha: KDoc in PoiPackStore must not contain a literal `del_*/ins_*`
    (the `*/` ends the comment).
  - `OfflinePoiStore.search` orders whole-query name matches first so they survive the internal 400-row
    cap. v1-format packs (published before rev existed) have no rev; their first v2 rebuild yields no
    usable delta so clients just full-download once, then deltas kick in.
- **Offline forward geocoder - typed address → coordinate, no signal (`core/data/OfflineAddressStore` +
  `OverpassPois.fetchAddresses`/`fetchStreets`).**
  - So an arbitrary typed street address routes offline (not only addresses that are an indexed POI).
    Populated when a map area is downloaded (`MapViewModel.downloadOfflinePois`) from keyless Overpass
    over a bbox **padded to a ~15 km min span around the viewport centre** (`GEOCODE_PAD_DEG=0.09`, so a
    saved area covers the surrounding metro, not just the on-screen tiles).
  - TWO OSM sources into ONE SQLite db (`vela_offline_addr.db`, v2): **`addr:housenumber` points**
    (`addr` table) for house-precise hits, and **named road centrelines** (`street` table, thinned to
    ~1 pt/120 m by `toStreetPts`) for a street-level fallback where OSM has the road but no house numbers
    (the US-suburb reality; the OpenAddresses/Microsoft *render* overlays are PMTiles, not queryable as a
    geocoder, so the geocoder uses OSM).
  - `geocode()` is layered: (1) exact house number, (2) **interpolate** between the two bracketing mapped
    numbers, (3) nearest mapped house on the street, (4) nearest point on the street centreline.
    `normalizeStreet` expands abbreviations both ways ("Pl"↔"place", "SE"↔"southeast") so all spellings
    hit the same rows.
  - Wired into the offline search branch (`MapViewModel`, gated by `OfflineAddressStore.looksLikeAddress`
    so "coffee" doesn't hit it) AND the network-error fallback; `haveArea` counts `count()`+
    `streetCount()` so a street-only suburb isn't misreported "no data". Big Overpass bodies → the
    no-call-timeout `offlineDownloadHttp` (same rule as the graph/overlay downloads). The result Place
    routes through the normal GraphHopper offline engine.
  - **Reverse-geocode backfill for offline POIs:** most US chains have no OSM `addr:*` (Applebee's comes
    back as bare "WA"), so `MapViewModel.backfillOfflineAddress` - on selecting a place while offline,
    when its address has no house number (`.none { isDigit() }`) - calls
    `OfflineAddressStore.reverseGeocode(loc)` (nearest mapped house ≤60 m, else nearest street ≤150 m,
    bounded lat/lng box scan) and fills `selected.address` if still selected.
  - **Quiet offline indicator (no banner):** `MapUiState.offline` (a reactive `ConnectivityManager`
    default-network callback, `observeConnectivity`, fails safe to online) drives a greyed globe-slash +
    "Offline" in `SearchBar` (bare map only) and a globe-slash chip **inline under the category chips**
    in `MapScreen`'s top Column (gated to the same bare-map state the chips show in, so it never trails a
    results list).
  - **The directions ETA subtitle** (`PlaceSheet.DepartTimeChooser`) only says "current traffic" when
    `route.hasLiveTraffic`; an offline (traffic-less) route shows the arrival time with no traffic note.
  - **Upgrade nudge:** the address index is built at download time, so areas saved before the geocoder
    have tiles+POIs but no addresses. Settings → Offline shows a "Update saved areas" card when
    `regions.isNotEmpty() && offlineAddressCount == 0` (via `MapViewModel.offlineAddressCount`); tapping
    it runs `refreshOfflineDataForSavedAreas` - iterates every saved `OfflineRegion`, reads its
    `OfflineMaps.boundsOf` and re-runs `downloadOfflinePois` over each box.
- **Open building-footprint overlay (`app/offline/OverlayTileStore` + `VelaMapView`).**
  - Fills the map's building gaps where OSM is thin (a suburb the Microsoft→OSM import never reached)
    with **Microsoft US Building Footprints (ODbL)**.
  - Off-device, CI bakes ONE `.pmtiles` per US state (`scripts/build-overlay-region.sh` → tippecanoe
    `-l building -Z14 -z16 --drop-densest-as-needed`; `-Z14` not `-Z12` - starting at z12 ballooned WA
    to 271 MB, z14 → 197 MB) → `building-overlays` GitHub release + `building-overlay-manifest.json`,
    matrix workflow `.github/workflows/building-overlays.yml` (clone of the routing one,
    `MANIFEST_MODE=emit` + `scripts/merge-overlay-manifest.sh`), catalog `tools/overlay-regions.json`.
  - In-app: `OverlayTileStore` is a single-file sibling of `RoutingGraphStore` (`filesDir/overlays/
    <id>.pmtiles` + `index.json`; PMTiles-magic guard).
  - **The overlay STREAMS online - no download needed to see houses.** `refreshBuildingOverlays` runs on
    every camera-idle (`onViewport`) and emits, per view, a list of full `pmtiles://` URIs: a
    **`pmtiles://file://<abs-path>`** for any DOWNLOADED region (offline), and
    **`pmtiles://https://…<region>.pmtiles`** for the covering regions in view that AREN'T downloaded -
    the **UNION of up to the 3 smallest covering boxes, NOT just the single smallest**: a neighbour's
    rectangular bbox can spill across an irregular border AND be smaller (Kansas's box crosses the
    Missouri River and beats Missouri's box, but kansas.pmtiles is EMPTY east of the river, so a
    single-pick rendered NO footprints there). Streaming the union lets whichever archive has the data
    paint; an empty region's range requests cost ~nothing.
  - MapLibre 11.7+ reads that hosted archive by **HTTP range requests** (GitHub release assets
    302→release-assets host with `accept-ranges: bytes`, MapLibre follows the redirect), fetching only
    the visible tiles, so footprints appear as you pan. The manual **`MapViewModel.downloadOverlayForArea`**
    (still smallest-covering-box, pulled alongside the area's tiles) is now ONLY for going fully offline.
  - Render: `VelaMapView`'s `LaunchedEffect(buildingOverlays, styleRef, darkTheme)` adds each URI as a
    `VectorSource` (used verbatim - the URI already carries `pmtiles://file://` or `pmtiles://https://`)
    + a `FillLayer` `setSourceLayer("building")` **`addLayerBelow` the OSM `building` layer**, themed to
    the exact OSM building fill/outline (`#323f54`/`#3f4e66` dark, `#dde1e7`/`#c4c9d1` light) so overlay
    footprints are indistinguishable from real OSM ones and OSM still wins wherever it has data.
    `buildingOverlays` is de-duped so panning within one region doesn't churn the map sources.
  - **The load-bearing DOWNLOAD bug was NOT the render** - it was the `callTimeout(0)` rule above: the
    197 MB body aborted at the shared client's 12 s cap, silently (that only ever mattered for the
    offline download; streaming reads a few KB/tile). NB GitHub release hosting works but isn't a CDN - a
    real deployment should host the PMTiles behind a CDN for snappier range reads.
  - `OVERLAY_MANIFEST_URL` BuildConfig overridable `-PoverlayManifestUrl=` like routing. BREAKING-ish: an
    overlay is DATA (ODbL), orthogonal to the app's GPLv3, obligation met by tippecanoe `--attribution` +
    the release publishing derived tiles under ODbL.
  - **World catalog (`tools/overlay-regions.json`, 361 rows - ~250 base regions plus chunk pieces):** TWO
    Microsoft sources picked by each row's `source`, both handled by the ONE build script (`SOURCE` env):
    **`us-legacy`** = a US state's single `.geojson.zip` (Microsoft US Building Footprints, 51 states+DC);
    **`ms-global`** = a world country's quadkey-partitioned GeoJSONL from Microsoft's **Global ML Building
    Footprints** (`global-buildings/dataset-links.csv` → `awk` the country's `Location` rows → curl+gunzip
    each `.csv.gz` into one ndjson → tippecanoe `-P`; ~199 countries). Country **bboxes are the union of
    the dataset's own z9 quadkey tiles**; US-state bboxes are Geofabrik extract bounds.
  - **Big countries are CHUNKED** (>1500 MB compressed source, 18 of them): the catalog splits each into
    sub-national pieces by **quadkey PREFIX** (`qkprefix`; adaptive recursive split until each chunk ≤
    ~1500 MB - India → 24 pieces), the build script's awk filters the country's rows to that prefix, and
    each chunk gets its own union bbox so the **app's smallest-covering-box rule picks the piece covering
    the user** (fits CI disk + hosts under GitHub's 2 GB/asset limit). Only the whole-US aggregate +
    continental aggregates + duplicate Locations (CzechRepublic→Czechia, DemocraticRepublicoftheCongo→
    CongoDRC) are dropped.
  - The catalog is 361 regions - **over GitHub's 256-job matrix cap** - so each row carries a `group`
    (`us` / `world` / `chunk`) and dispatch is **one group at a time** (`-f group=world`); run-level
    concurrency is OFF so groups build concurrently, only the merge job serialises. The app/manifest are
    source-AGNOSTIC - the emitted manifest row is always `{id,name,url(asset),sizeMb,bbox}`.
- **Open house-number overlay (`VelaMapView` + `scripts/build-address-region.sh`).**
  - Microsoft footprints have geometry but **no addresses**, so house numbers come from a SECOND overlay:
    **OpenAddresses** address POINTS → per-state `.pmtiles` (`-l address`, keep the `number` prop) →
    `address-overlays` GitHub release + `address-overlay-manifest.json` (`ADDRESS_MANIFEST_URL`,
    `-PaddressManifestUrl=`).
  - Data source = OpenAddresses batch API: `/api/data?source=us/<st>/statewide&layer=addresses` → its
    current `job` → `https://v2.openaddresses.io/batch-prod/job/<job>/source.geojson.gz` (GeoJSONL of
    Points with `number`/`street`; **42 US states have a `statewide` source**).
  - Render: `VelaMapView`'s `LaunchedEffect(addressOverlays, …)` adds a `VectorSource` (the URI) + a
    **`SymbolLayer`** `setSourceLayer("address")`, `textField(get("number"))`, `textFont(["Noto Sans
    Regular"])`, size 10, grey + white halo, **minZoom 19** (in lockstep with the basemap
    `vela-housenumber` layer - numbers only at the ~50 ft scale, raised from 17.5 on 2026-07-13) - inserted
    below `vela-controls` (see the LAYER ORDER warning below).
  - **Streams online exactly like buildings** (`MapViewModel.refreshAddressOverlays(center)` on every
    camera-idle → the union of up to the 3 smallest covering regions' `pmtiles://https://…` URIs - same
    spilled-bbox shadowing fix as the building overlay, see above; reuses `overlayStore.manifest()` which
    is manifest-URL-agnostic).
  - **LAYER ORDER:** the addr layers are inserted **BELOW `vela-controls`** (→ below the ambient POI
    icons), NOT `addLayer`/top - MapLibre places symbols TOPMOST-FIRST, so numbers stacked above the
    ambient layer grab collision boxes before the business icons place and **EVICT them at z16+** (the
    "Applebee's icon disappears on zoom-in" bug: big prominence-scaled icons collide the most). Below the
    icons, numbers place last and yield - Google's behaviour. Also: while the overlay is active the
    basemap `vela-housenumber` layer is hidden (visibility NONE in the same LaunchedEffect) - both drew
    the SAME address at a slight offset.
  - **NOT** the building overlay (different data + a Symbol not Fill layer + its own release/manifest).
    CI: `.github/workflows/address-overlays.yml` (clone of building-overlays), catalog
    `tools/address-regions.json`.
  - **The house numbers fill the exact gap the basemap `vela-housenumber` (OSM `addr:housenumber`) leaves
    in new suburbs.**
- **Ambient POI label density + collision priority (upstream c35eea33 + bd165ba0).** The ambient icon
  layer's `sort` (collision priority) is PROMINENCE-based, never the list index - an index key changes
  every place's priority whenever the pool re-ranks and the whole layer's placement reshuffles.
  `(10 - prominence) * 1000 + i`. Labels tier by zoom x prominence through the `textField` step
  expression (below z15.5 only prominence>=6 ~ 400+ reviews; z15.5+ >=5 ~ 120+; z16.5+ >=3 ~ 20+;
  z17.5+ everything) - an EMPTY textField skips label placement entirely (a textOpacity of 0 would
  still place and collide it, paying the cost invisibly). Guarding both: `nearbyPlaces`'s SLIM-FLAVOR
  HEAL - a fresh session's first ~3 s serves rating-but-no-review-count places, which zeroes
  `ambientProminence` and flattens ranking/sizing/tiers; a majority-slim pool refetches once.
- **Traffic lights + stop signs drawn on the map (`OverpassTrafficSignals.fetchControlsInBox` + `VelaMapView`).**
  - OSM `highway=traffic_signals` (a stoplight icon) and `highway=stop` (a red STOP octagon) as a
    non-interactive `SymbolLayer` (`vela-controls`, icons `vela-signal`/`vela-stop`) drawn **beneath**
    the POI dots + pins, `minZoom 16`.
  - **Icon sizing/visibility:** `iconSize` is a zoom-interpolated expression (~0.75 at z15.5 → 1.05 at
    z17 → 1.5 at z19) - the flat 0.55 was too small to spot, especially tilted in nav; and
    `iconAllowOverlap(true)`+`iconIgnorePlacement(true)` so they ALWAYS draw (controls are sparse - one
    per junction - and the earlier collision-off-below-POIs was culling them away on the browse map, so
    the user couldn't see them; Google shows all of them at street zoom).
  - Data is keyless Overpass (sibling of the `fetchAlong` nav-landmark fetch + `OverpassPois`), fetched
    by `MapViewModel.refreshTrafficControls` from `onViewport` **only at z ≥ `CONTROLS_MIN_ZOOM` (16)**.
    Controls are STATIC, so it fetches a box padded 50% beyond the viewport and **reuses it while the
    center stays in the inner half** (`controlsBox`) - panning/driving through an area triggers no
    refetch; only nearing the box edge refetches (single-flight + 350 ms settle).
  - The layer/updater are identity-gated like markers/ambient (`lastAppliedControls`) so a nav speedo
    tick doesn't re-tessellate them. No app setting (zoom-gated); no PMTiles/CI (live Overpass). NB the
    `TRAFFIC_*` constants in `VelaMapView` are a DIFFERENT thing - Google's live-traffic raster overlay;
    the controls use `CONTROLS_*`.
- **Public transit uses the same hidden WebView** (`app/web/WebDirectionsFetcher`).
  - A plain `/maps/preview/directions` GET with the transit flag (`!3e3`) is silently downgraded to a
    *driving* reply (same TLS-fingerprint bot-detection as photos), so the WebView instead navigates the
    `/maps/dir/<olat>,<olng>/<dlat>,<dlng>/data=!4m2!4m1!3e3` page and reads the itinerary set out of
    `APP_INITIALIZATION_STATE`.
  - **Depart/arrive time:** the board is time-dependent, so a scheduled request replaces the plain
    `!4m2!4m1!3e3` with Google's time block - `!4m6!4m5!2m3!6e{0=depart,1=arrive,2=last}!7e2!8j<unix-seconds>!3e3`
    (the `!4m` numbers are DESCENDANT counts, so the inner group grows `4m1`→`4m5` and the outer
    `4m2`→`4m6`; verified against a real Google transit-with-time URL - an earlier `!4m8!4m7` guess had
    the wrong counts and Google silently fell back to "now").
  - **Gotchas:** the directions payload is the **longest** `)]}'`-guarded string under slot `[3]` (a
    ~1.7 KB stub sits alongside the ~165 KB real one - take the longest, and poll for it: the SPA fills
    it a beat after page-finish).
  - `TransitParser` (`:core`, takes the raw string so `:app` stays out of kotlinx.serialization, like
    `PhotosParser`) reads `root[0][1]` = trips, each trip's **summary at `trip[0]`**; `trip[1][0][1]` is
    the per-stop leg tree. Calibrated + device-verified Davis→Sacramento.
  - **Full stop detail (unit-tested):** a RIDE leg carries its stop block at **`leg[5]`** - board
    `[5][0]`, alight `[5][1]`, **stop count `[5][2]`**, intermediate list `[5][7]` (each stop node: name
    `[0]`, agency code `[1]`, and time tuples - real-time arr/dep at `[2]`/`[3]`, timetable at `[7]`/
    `[8]`, so RT-vs-timetable epochs give "N min late"); **headsign `leg[0][14][2][1][0]`**, agency phone
    `leg[0][6][4][0][4]`, service alerts `leg[0][9][k][2]`. Fare is scanned defensively from the trip
    summary (usually absent). NB `parseLines` allows a **1-char** line name (single-digit bus routes like
    "9" are real).
  - Each stop node's **coordinates are `[4][2]` (lat) / `[4][3]` (lng)** - `parseStopTime` reads them
    into `TransitStopTime.location`, and `assignWalkEndpoints` wires each WALK leg's `walkFrom`/`walkTo`
    from the adjacent ride's alight/board stop (falling back to the trip origin/dest, which `parse(raw,
    origin, dest)` threads through). The UI then fetches that walk leg's turn-by-turn steps **on demand**
    via the normal walk router (`MapViewModel.walkDirections` → OSRM foot) - no extra transit RPC.
  - **Step-by-step transit guidance** (Moovit-style, `TransitNavState` + `startTransitNav`/`advance`/
    `back`/`endTransitNav` in `MapViewModel`, `TransitNavSheet` in `PlaceSheet`) walks the itinerary leg
    by leg, speaking each cue (`transitStepSpoken` → the `transit_nav_*` strings) and auto-advancing when
    GPS reaches the leg end. The auto-advance is **latched** (`maybeAdvanceTransitNav`,
    `TRANSIT_ARM_M=90`/`TRANSIT_ARRIVE_M=40`): a leg only advances once it's been ARMED by being >ARM_M
    from its end, so a transfer hub can't cascade through legs and a short final walk can't fire a
    premature arrival.

## Name

Vela Maps (`app.vela`). "Vela" was clearance-checked and is free of maps-app and
trademark collisions.
