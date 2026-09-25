# 12. Releases

## What you see

A card on the bare map that says "Vela 0.4.1830 is available", with **Not now** and **Update**
buttons and a fold that shows what changed. Tap Update and a progress bar fills, then Android's
own install dialog takes over. The first time you open the new build, a "What's new in 0.4.1830"
dialog shows the release notes once.

Settings > About holds the controls: a **Check for updates on launch** toggle (on by default), an
**Update channel** picker with three choices, a **Check for updates** button, and a **What's new
in this version** row that reopens the notes for the build you are on. The channels read:

- **Stable (weekly)**: one release a week.
- **Nightly (daily)**: a build each day there are changes.
- **Canary (development builds)**: work in progress, replaced every time the working branch moves.

People who do not use the in-app updater get the same builds through Obtainium, which tracks the
GitHub releases, or through Vela's own F-Droid repository. Every channel and every install source
carries the same signed APK, so moving between them never forces a reinstall.

## Where the data comes from

Everything is a GitHub release on `PimpinPumpkin/Vela`, built by GitHub Actions from this
repository. There is no update server.

| Channel | Release | Tag | versionName | Made by |
| --- | --- | --- | --- | --- |
| Canary | one rolling prerelease, deleted and recreated per push | `canary` (fixed) | `0.4.<run>-canary` | `ci.yml`, on a push to the `canary` branch |
| Nightly | one prerelease per day, when `main` moved | `v0.4.<run>` | `0.4.<run>` | `ci.yml`, daily cron or manual dispatch |
| Stable | the newest nightly, flipped to a full release | the nightly's own tag | the nightly's own name | `promote-stable.yml`, weekly |

The F-Droid repository is a second copy of the same APKs, rebuilt by `fdroid-repo.yml` and served
from GitHub Pages at `https://pimpinpumpkin.github.io/Vela/repo`. It is not the f-droid.org
catalog: f-droid.org builds from source, and its build cannot take the prebuilt sherpa-onnx
runtime Vela bundles for on-device voices.

The APKs are signed with the release keystore, which lives outside the repository and reaches CI
through the secrets `VELA_KEYSTORE_BASE64`, `VELA_KEYSTORE_PASSWORD` and `VELA_KEY_ALIAS`. Without
the secret (a fork's CI, for example) the release build falls back to the debug key, which still
installs but can never update a real install. Losing the real key would mean no installed copy of
Vela could ever be updated again.

## How it is decided

### Three channels

**A push never mints a release** (since 2026-08-07). A push to `main` or `canary` builds, runs the
unit tests and the translation check, and uploads the APK as a workflow artifact. What happens next
depends on which branch and which trigger.

**Canary.** `canary` is the working branch: merges land there first, batches assemble there, and
pushing `canary` to `main` is the deliberate step that makes a change release-worthy. Every push to
`canary` also publishes, because canary is a real update channel. The `canary` release is **deleted
and recreated** on every push (`gh release delete canary --yes --cleanup-tag`, then
`gh release create canary --prerelease --target <pushed commit>`), titled `Vela 0.4.<run>-canary`.
Until 2026-09-22 it was edited in place, and since GitHub lists releases by creation date, the
August-created canary sat fifteen rows down under every nightly and data release. Recreating it
moves it to the top and moves the tag to the pushed commit. The download URL
(`releases/download/canary/...`) and the updater's `releases/tags/canary` lookup never change; the
swap costs a few seconds of 404, which the updater reads as "nothing newer".

The canary notes are a label and three lines the updater parses:

```
Canary branch.

versionName: 0.4.1822-canary
versionCode: 38220

Latest change: <newest user-facing commit subject>
```

The tag is deliberately not `v0.*`, so the nightly and stable queries, the nightly prune and the
F-Droid build never see it.

**Nightly.** The CI workflow has a daily cron at 10:30 UTC (`'30 10 * * *'`). It builds `main` and
publishes only if `main` moved: it takes the newest tag matching `v0.[0-9]*` by version sort, and if
that tag's commit is `HEAD`, it logs "main has not moved" and exits. A manual dispatch does the same,
unless `force` is true, which recuts the same code. That dispatch is the "fix it now" path: push to
`main`, then `gh workflow run ci.yml`. Merges do not each get a dispatch; the cron is the cadence.

A nightly is titled `Vela 0.4.<run> nightly`, and its notes open with the line "Nightly build."
(2026-09-21). Before that, nightlies and stables had the same title with only the prerelease badge
apart, and the What's new dialog shows the body, so nobody could tell which channel they were
reading.

**Stable.** `promote-stable.yml` runs Mondays at 16:00 UTC (`"0 16 * * 1"`), or by hand. It finds
the newest prerelease whose tag matches `^v0\.[0-9]+\.[0-9]+$` (in the newest 100 releases) and
the newest full release matching the same pattern (in the newest 200), compares their run numbers,
and does nothing if the nightly is not newer. Otherwise it edits the nightly in place:

```
gh release edit <nightly> --title "Vela <version>" --prerelease=false --latest --notes <new notes>
```

Same tag, same signed APK, no rebuild. The notes are regenerated to span everything since the
previous stable, so a weekly user reads the whole week, not the last day's slice.

**What is kept.** Stables are never deleted: they are the changelog, the build someone bisecting a
regression installs, and their download counters are the only measure of reach the project has (a
deleted release takes its counter with it). Nightlies keep a rolling **30**: after each nightly
publish, the prune step lists every release (paginated, `per_page=100`), keeps only tags matching
`^v0\.`, and deletes every prerelease past the 30th newest, tag included.

### Version names and codes

`ci.yml` derives both from `github.run_number`, the workflow's own run counter:

```
versionName = 0.4.<run>              (canary: 0.4.<run>-canary)
versionCode = (2000 + <run>) * 10    (since 2026-09-23; 2000 + <run> before)
```

The run counter counts every run of `ci.yml`: pushes to either branch, pull requests, crons and
dispatches. So nightly numbers have gaps, and a canary and a nightly are ordered by when they were
built, on one line. That is the point: every channel shares one monotonic versionCode, so switching
channels in either direction is always a plain upgrade to the installer, never a downgrade.

**The run number must stay in `ci.yml`.** A release cut by a separate workflow would start its own
counter at 1 and regress the versionCode.

**versionName is plain semver** so Obtainium can compare it. The minor has moved three times while
the code kept rising: `0.1.<run>` / `1000 + run` until 2026-06-18, then `0.2`, `0.3` on 2026-07-08,
`0.4` on 2026-07-11. The 2026-06-18 bump of the code base from 1000 to 2000 was a repair: local dev
builds had been hand-set with `-PappVersionCode` in the 1000s (up to 1117), got installed on a test
phone, and left it ahead of the release line, so Obtainium saw the next release as a downgrade.

**Never name a release `v0.4.0`.** The updater takes the last component of the tag as the run
number, so a hand-named `v0.4.0` reads as run 0, code 2000, and is never offered to anyone.

**The times ten** (2026-09-23) makes room for a chip digit in the last place:

| APK | Digit | versionCode for run 1822 |
| --- | --- | --- |
| all-in-one (and every build without per-chip splits) | 0 | 38220 |
| armv7 (`armeabi-v7a`) | 1 | 38221 |
| arm64 (`arm64-v8a`) | 2 | 38222 |
| x86 | 3 | 38223 |
| x86_64 | 4 | 38224 |

The digit is added in `app/build.gradle.kts` (`androidComponents.onVariants`, the index of the ABI in
`armeabi-v7a, arm64-v8a, x86, x86_64` plus one); the all-in-one output has no ABI filter and keeps
the base. Two reasons for the digit: an F-Droid index needs a distinct versionCode for every APK it
lists, and moving from the all-in-one APK to your chip's APK of the same build has to be an upgrade.
The jump from `2000 + run` to `(2000 + run) * 10` is one-way, and every build after it is still an
upgrade over every build before it.

**`legacyCode` folds it back.** The updater never learned the new scale. It compares everything on
`2000 + run`, and `update/ApkChoice.kt` folds any code of 20000 or more by dividing by ten:

```kotlin
fun legacyCode(versionCode: Int): Int = if (versionCode >= 20000) versionCode / 10 else versionCode
```

Integer division drops the chip digit, so 38222 and 38220 both read as 3822. The installed code is
folded before any comparison, and so is the code read out of the canary notes. The dismissed-update
preference and the tag math never had to change.

### One APK per chip type

Built, and **off until the repository variable `ABI_SPLITS` is `true`**. With it set, CI passes
`-PabiSplits`, Gradle's ABI splits turn on (`include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")`,
`isUniversalApk = true`), and `scripts/stage-apks.sh` names the outputs:

| Built as | Nightly or stable | Canary |
| --- | --- | --- |
| `app-universal-release.apk` | `vela-maps-all.apk` | `vela-maps-canary-all.apk` |
| `app-arm64-v8a-release.apk` | `vela-maps-arm64.apk` | `vela-maps-canary-arm64.apk` |
| `app-armeabi-v7a-release.apk` | `vela-maps-armv7.apk` | `vela-maps-canary-armv7.apk` |
| `app-x86-release.apk` | `vela-maps-x86.apk` | `vela-maps-canary-x86.apk` |
| `app-x86_64-release.apk` | `vela-maps-x86_64.apk` | `vela-maps-canary-x86_64.apk` |

Without the variable, the build is one APK under its old name: `vela-maps-v0.4.<run>.apk` on a
nightly, `vela-maps-canary.apk` on canary. The canary release on 2026-09-25 still carried the single
`vela-maps-canary.apk`.

Measured sizes: the all-in-one APK of a split build is 121.9 MB; the chip APKs are arm64 74.3 MB,
armv7 35.4, x86 41.2, x86_64 41.5. The unsplit build leaves Cronet's x86 libraries out (108.4 MB):
x86 emulators and Chromebooks still install it and their Google requests fall back to OkHttp. A
split build keeps them, since there the x86 APKs carry their own Cronet.

**The phone picks its file.** `ApkChoice.pick` walks `Build.SUPPORTED_ABIS` in the phone's own
order and takes the first asset ending in `-<tag>.apk` for one of them (an arm64 phone lists
`arm64-v8a` first, so it gets `-arm64.apk`); failing that, the first `.apk` whose name carries no
chip tag, which is the all-in-one on a split release and the only APK on an unsplit one.

**Why `-all.apk` sorts first.** Every updater before `ApkChoice` took the first asset ending in
`.apk`, and GitHub lists assets alphabetically. `vela-maps-all.apk` sorts ahead of `-arm64`,
`-armv7`, `-x86` and `-x86_64` (`al` before `ar`), so a phone on an old build, including a 32-bit
phone, keeps downloading a file that runs on every chip. The name was chosen for that.

**When it is safe to flip.** Once a build with `ApkChoice` has been the stable for a few weeks. The
sort order is the safety net for old updaters; waiting means few phones still depend on it. The
F-Droid repository is already ready for it (see below). The device check, done on a test phone on
2026-09-23: install a separate package at a high code, `-PappId=app.vela.dev
-PappVersionCode=37000`, press Settings > About > Check for updates, and look for
`installed=3700` in logcat under `VelaUpdate`.

### How the in-app updater picks a build

`SelfUpdater.check(installedVersionCode, channel)` runs on launch and on the Check for updates
button. The channel is the `update_channel` preference (`stable`, `nightly` or `canary`), migrated
in place from the older `update_nightly` boolean, so anyone who had nightlies on stays there.

- **Stable** reads `releases/latest`, the one release GitHub marks Latest. The promotion passes
  `--latest`, and every infrastructure release is a prerelease, so Latest is always the newest
  stable. The tag must match `^v0\.\d+\.(\d+)$`; the run it captures gives the code `2000 + run`.
- **Nightly** reads the app-release tags from the refs endpoint, newest run first, and takes the
  first of the newest three that has a published, non-draft release. A stable was a nightly, so the
  highest run is the newest either way.
- **Canary** reads `releases/tags/canary`, pulls `versionName:` and `versionCode:` out of the notes
  with a regex (the tag never changes, so the notes are the only place the version lives), folds
  the code, and also runs the nightly check. It takes whichever is higher, so a canary that has
  fallen behind the nightly line never strands anyone on it.

An update is offered when the candidate's code is above the folded installed code. Nothing
newer, and any error, returns null: the check is best-effort, and a launch never blocks or
complains about it.

**Minor-agnostic.** The tag parse takes only the run. A release is fetched by trying
`releases/tags/v0.4.<run>` first and then `v0.3` and `v0.2`, since the refs list gives runs and not
the minor that went with them.

**The releases list is never fetched** (2026-09-22). The data releases (`obf-regions`,
`places-overlays`, `basemap-tiles`, `road-features`) carry about 450 assets each, about 780 KB of
release JSON apiece, and they sit near the top of the list because they are republished
constantly. A check that listed releases pulled 4 to 9 MB over cellular and parsed it with
`org.json` on the phone, which is what "checking for updates is slow" was. The tags come from
`git/matching-refs/tags/v0.` instead (about 200 KB for 550 tags, no bodies, no assets), and each
release is fetched by its tag (about 15 KB).

| Channel | Nothing newer | Update found |
| --- | --- | --- |
| Stable | 1 request (`releases/latest`) | plus the refs list and up to 8 release reads for the notes |
| Nightly | 2 (the refs list, the newest tag's release) | plus the refs list again and up to 8 release reads |
| Canary | 3 (the canary release, then the nightly check) | the same 3, no history |

Those are the usual counts. A nightly check can take more when the newest tags have no release, or
when a run's tag is not on the current minor (each miss is one more request). Every check logs one
line under `VelaUpdate` with the channel, the installed code, the result, and the request and byte
counts.

**Notes across skipped releases** (issue #330). For stable and nightly, the updater fetches the
releases between the installed build and the offered one, newest first, at most
`HISTORY_MAX_RELEASES = 8`, keeping only the channel's own kind (full releases for stable,
prereleases for nightly). If at least two remain, the card shows each release's notes under its
version; otherwise the offered release's own notes. Canary has no history: its notes carry only
the latest change.

**When it checks.** The launch check runs only when **Check for updates on launch**
(`self_update_check`, default on) is set, and at most once in 20 hours (`last_update_check_ms`,
`20 * 60 * 60_000L`). The Check for updates button is unthrottled.

**Dismissed codes.** **Not now** stores the offered code (on the folded scale) as
`update_dismissed_code`. The launch check then stays quiet for that release and anything older;
only a newer release brings the card back. The Check for updates button ignores the dismissed code,
since pressing it is asking.

**What it downloads.** One APK, the one `ApkChoice` picked, into `filesDir/updates/` as
`vela-<code>.apk`, after clearing anything already in that folder (one update on disk at a time).
The client has no call timeout and a 60 s read timeout, because the shared client's 12 s cap would
cut an 80 to 120 MB body off mid-read without saying so. The download runs in the app-lifetime
download scope under a foreground service, so leaving the app does not kill it, and it can be
canceled. A file that does not start with the zip magic bytes `PK` is deleted rather than handed on.
The APK then goes to the **system** installer through the FileProvider (`ACTION_VIEW`,
`application/vnd.android.package-archive`), and Android enforces the update contract: same package,
same signing key, and the user confirms. Nothing is installed silently.

One exception to installing straight away: when Android records Play as the installer, which on a
build not distributed there means someone set it up for Android Auto, the updater holds the APK
back and offers it as a file, because a self-install would overwrite that record and the car would
drop Vela. [Chapter 10](10-android-auto.md#the-install-gate) has the whole story.

### What's new

`ui/WhatsNew.kt` (2026-09-13). On launch, it compares the build's versionName with
`last_seen_version` in the `vela_onboarding` preferences:

- **No stored version** (a fresh install, or the first run of a build that knows about the prompt):
  store the current one silently. There is no "before" to compare with.
- **Same version:** nothing.
- **A new version, but the welcome screen is not finished:** store it silently.
- **A new version:** fetch that version's own release, `releases/tags/v<versionName>`, or
  `releases/tags/canary` for a build whose name ends in `-canary`, and show the notes once. It is
  the last of the one-time prompts, so it never stacks on a setup step.

A failed fetch leaves the version unseen, so the next launch tries again; the prompt is never shown
without notes in hand. **Got it** marks the version seen, **Full notes** opens the release page.
Nothing is bundled in the APK: the notes are whatever the release body says on the day you open
the app.

Both the dialog and the update card run the body through `plainReleaseNotes`: headings, bullet
marks, links and bold stripped, the `versionName` and `versionCode` lines and anything starting with
`<` dropped, and **only the first 24 lines kept**.

**Why stable notes lead with a hand-written list.** A stable's generated notes are a commit list
spanning a week, which reads like a git log to the people it is for, and a busy week runs past 24
lines, so the dialog would show the first 24 commits and nothing else. So the same day a stable is
cut (the Monday promotion or an early one), a person edits its notes (`gh release edit v0.4.<run>
--notes-file`) to put a short list of the major user-facing changes above the generated list: one
line per feature, in plain words, with the reason for an early cut first if there is one.
`v0.4.1217` is the model. The promotion workflow cannot write that part, so it is a step in the
release, not an afterthought. Nightlies keep the commit list alone.

### The changelog is the commit subjects

Release notes are built from the commit subjects since the previous release, by
`scripts/changelog.sh`:

- **Nightly:** the range from the newest `v0.[0-9]*` tag to `HEAD` (or the last 20 user-facing
  commits when there is no previous tag), then a **Full changelog** compare link.
- **Stable:** regenerated at promotion, from the previous stable's tag to the promoted one (or the
  last 30), then a compare link.
- **Canary:** `--latest`, the single newest user-facing subject, as "Latest change:" (falling back
  to the plain newest subject when nothing qualifies).
- An empty list becomes "- Maintenance and internal changes".

**A commit is skipped** (2026-09-22) when every file it touches is documentation (`*.md`, `docs/`,
`site/`, `fdroid/metadata/`, `LICENSE`, `.github/ISSUE_TEMPLATE/`), when every line it adds or
removes in any other file is a comment or blank (after dropping comment lines and trailing `//`
comments, the removed and added code lines are the same multiset), or when its subject starts with
`Docs:`. Before that rule, a docs sweep showed up in the What's new dialog as if it were a feature.

So **commit subjects are the user-facing changelog**: they are written as plain-language changelog
lines, in the same human voice as everything else, because they go on the release page, into
Obtainium, into the update card and into the What's new dialog verbatim.

**Docs-only pushes do not build at all** (since 2026-07-09). `ci.yml` has a `paths-ignore` list
(`**.md`, `docs/**`, `LICENSE`, `.gitignore`, `fdroid/metadata/**`, `.github/ISSUE_TEMPLATE/**`,
`site/**`), and a push is skipped only when every changed file matches; a mixed push builds.
Workflow files are not ignored on purpose, since they change the build. `[skip ci]` in a subject
suppresses a run by hand. The trigger: a dozen documentation commits on 2026-07-09 had become a
dozen identical nightlies.

### Obtainium

Obtainium tracks the GitHub releases directly. By default it follows the latest full release, the
weekly stable; with "include prereleases" it follows nightlies. It compares versionNames, which is
why they are plain semver. It cannot cleanly track canary, since its version detection keys on the
tag and the canary tag never changes. That is deliberate containment: canary rides the in-app
updater or a manual download, so an Obtainium user with prereleases on never gets a canary by
surprise.

### The F-Droid repository

`fdroid-repo.yml` (2026-07-09) rebuilds a signed F-Droid repository from the releases and deploys it
to GitHub Pages.

**When it runs.** On `workflow_run` of "CI" or "Promote weekly stable", only when that run
succeeded on `main`; on the release events `published` and `released` for a tag starting with `v`;
on a push to `site/**`; and by hand. The `workflow_run` trigger is the one that matters: a release
created by CI's own token does not fire release events for other workflows (GitHub's
anti-recursion rule), so a release-event-only trigger left the index stale until someone dispatched
it (found on 2026-07-09). `edited` is left out on purpose: marking a release Latest fired a second
run that raced the first for the Pages environment (2026-07-16). A run that starts supersedes the
one in progress (`cancel-in-progress: true`), since each run rebuilds everything from scratch.

**What it serves.** The newest full `v0.*` release, plus the newest `v0.*` release of any kind when
that is not the same one, which is a nightly ahead of stable. Each release's APKs are downloaded
into their own folder and renamed `<tag>-<file>`, because per-chip file names are the same on every
release and the stable's `vela-maps-arm64.apk` would collide with the nightly's. Where a release has
per-chip APKs, the all-in-one is dropped: F-Droid clients choose the APK for their chip themselves,
and GitHub Pages has a 1 GB budget. `archive_older: 0` turns F-Droid's archive off, so the index
lists exactly the files fetched.

**How the suggested version is pinned to stable.** The build reads the versionCode of every stable
APK with `aapt2 dump badging`, takes the highest, and appends it to the app metadata at build time:

```
CurrentVersion: <stable versionName>
CurrentVersionCode: <the stable's highest versionCode>
```

F-Droid clients suggest versions up to `CurrentVersionCode` and treat anything above as unstable.
So a default F-Droid user updates weekly on stables, and the newer nightly in the same index is
offered only to someone who turns on unstable updates for Vela in their client. Before the pin,
clients offered the highest version, and everyone on F-Droid was silently on nightlies. Reading the
code off the files is right on both sides of the 2026-09-23 scale change, and taking the highest
means every chip APK of the stable (up to digit 4) sits at or under the pin, while any nightly,
from a later run, sits above it.

The index is signed with a separate repository key (`FDROID_KEYSTORE_BASE64`,
`FDROID_KEYSTORE_PASS`); the APKs keep the Vela app signature. The same Pages artifact carries the
project website and the map font glyphs, because `actions/deploy-pages` replaces the whole site: a
second Pages workflow would take down the F-Droid repository and the fonts. Never re-run only a
failed deploy job of this workflow; the Pages artifact belongs to the original attempt, so dispatch
a fresh run.

### Infrastructure releases are never app releases

The same repository hosts the datasets from [chapter 2](02-data-and-rebakes.md) and the build
runtimes as fixed-tag prereleases: `obf-regions`, `places-overlays`, `basemap-tiles`,
`road-features`, `poi-packs`, `flock-cameras`, `building-overlays`, `address-overlays`,
`maxspeed-overlays`, `map-fonts`, `asr-models`, `tts-runtime`, `obf-runtime`, and the retired
`routing-graphs`. Their assets exist nowhere else; the release is the download backend the app's
manifests point at. They are prereleases on purpose, to stay off `releases/latest`.

**The rule: anything that deletes, edits or picks releases selects by the tag pattern `v0.*`, never
by "prerelease" or "old".** Both halves were learned the hard way:

- The first nightly prune (2026-07-09) selected old prereleases and deleted four of the five
  infrastructure releases that existed then, which broke every offline download until the data was
  rebuilt. The fifth, `routing-graphs`, survived only because it sat past the query's
  `--limit 200` window.
- An unbounded "newest prerelease" in the promotion picked `obf-tools` the week it was created and
  promoted it to stable. That knocked the real stable off `releases/latest`, so the in-app updater
  and the F-Droid build found no APK.

So every query in `ci.yml`, `promote-stable.yml` and `fdroid-repo.yml` filters on
`^v0\.` or `^v0\.[0-9]+\.[0-9]+$`, the F-Droid release trigger requires a tag starting with `v`, and
any list query assumes 400 or more releases and either paginates or bounds by tag. The canary
release is the one app release outside the pattern, which is exactly why none of these see it.

### Local builds stay below the release line

Without the CI properties, `app/build.gradle.kts` builds versionName `0.3.0`, versionCode `1`. A
local build given a code by hand is kept **below 1000** (for example `-PappVersionCode=1`), so the
release line always wins and Obtainium and the in-app updater never see the next release as a
downgrade. That is the 2026-06-18 lesson above. `legacyCode` leaves anything under 20000 alone, so
a dev build at 1 is offered the current release like any old install.

When a dev build has to replace a release install on a test phone (to keep its saved trips and
permission grants), it is built at the installed versionCode and installed with `adb install -r`.
Never `adb uninstall`: it destroys saved trips, with no recovery. To test the updater against the
real release line without touching the installed app, build a separate package id with a high code,
as in the per-chip check above.

## Limits

- **A stable is not always a week old.** The promotion takes the newest nightly at Monday 16:00
  UTC, and the nightly cron runs at 10:30. If `main` moved over the weekend, the stable is a nightly
  that has been out for five and a half hours. There is no health check in the promotion; "healthy"
  in the workflow's comments means only "the newest one". Holding a bad nightly back means pushing a
  fix or promoting by hand.
- **Moving from nightly or canary back to stable offers nothing until stable passes you.** Codes
  only go up, and nothing is ever offered as a downgrade. That is the price of one line for every
  channel.
- **Canary's What's new can describe a newer canary.** The canary tag is recreated on every push, so
  a canary build that opens after the next push fetches the next build's "Latest change". And a
  nightly pruned before its first launch (30 kept) has no release to fetch, so its What's new never
  shows.
- **The cumulative notes see at most the newest 8 releases in the gap**, and a nightly user's
  history drops a promoted release from the list, since it is no longer a prerelease. A phone far
  behind gets the newest part of the story.
- **The hand-written stable list is a person's job.** If nobody edits the notes on promotion day,
  stable users see the first 24 lines of the commit list.
- **Per-chip APKs are not live yet.** Until `ABI_SPLITS` is flipped, every phone downloads the
  108.4 MB all-in-one APK. What Obtainium does with five APKs on one release has not been tried; an
  Obtainium user may need to pick one or set a filter, and the flip should come with a note saying
  so.
- **The F-Droid channel is invisible to the project.** Pages serves the files with no counter, so
  F-Droid installs show up nowhere in the weekly reach snapshot.
