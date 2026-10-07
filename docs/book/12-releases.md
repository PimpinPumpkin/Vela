# 12. Releases

## What you see

When a newer build is out, a card on the bare map says "Vela 0.4.1830 is available", with Not now
and Update and a fold that shows what changed. Update downloads the file, then Android's install
dialog takes over. The first launch of the new build shows its release notes once, in a "What's
new" dialog.

Settings > About has a Check for updates on launch switch (on by default), the Update channel
choice (Stable, Nightly or Canary), a Check for updates button, a row that reopens What's new, a
switch that turns that dialog off, and a line saying which app Android recorded as the installer.

Obtainium and Vela's own F-Droid repository deliver the same builds. Every channel and source
carries APKs signed with the same key, so moving between them never needs a reinstall.

## Where the data comes from

Every build is a GitHub release on `PimpinPumpkin/Vela`, built by GitHub Actions from this
repository. There is no update server.

| Channel | Release | Tag | Cut by |
| --- | --- | --- | --- |
| Canary | One prerelease, replaced on each push | `canary` | `ci.yml`, on a push to the `canary` branch |
| Nightly | One prerelease a day when `main` has moved | `v0.4.<run>` | `ci.yml`, daily at 10:30 UTC or on dispatch |
| Stable | The newest nightly, made a full release | The nightly's tag | `promote-stable.yml`, Mondays 16:00 UTC or on dispatch |

The F-Droid repository is a copy of the same APKs, served from GitHub Pages at
`https://pimpinpumpkin.github.io/Vela/repo`. The full list of rules and constants is
[SPEC section 15](../../SPEC.md).

## How it is decided

### Three channels

A push to `main` or `canary` builds, tests and uploads the APK as a workflow artifact. A push that
changes only docs paths (`paths-ignore` in `ci.yml`) starts no run. A push to `main` never makes a
release.

`canary` is the working branch. Each push deletes the `canary` release and creates it again on the
pushed commit, which keeps it at the top of the releases page. The download URL and the updater's
lookup do not change, and the few seconds of 404 during the swap read as nothing newer. The tag is
not a `v0.*` tag, so the nightly, stable, prune and F-Droid queries never see it. It never changes
either, so the version is in the notes:

```
Canary branch.

versionName: 0.4.1822-canary
versionCode: 38220

Latest change: <newest user-facing commit subject>
```

The nightly job publishes only if `main` has moved since the newest `v0.[0-9]*` tag. A manual
dispatch of `ci.yml` does the same (`force` recuts the same commit) and is how a fix ships before
the next cron. The title ends in "nightly" and the notes open with "Nightly build.", so that
someone reading them in the app can tell the channel.

The promotion compares the newest prerelease and the newest full release whose tags match
`^v0\.[0-9]+\.[0-9]+$`, and stops unless the nightly has the higher run number. It then edits the
nightly in place: title `Vela <version>`, prerelease off, marked Latest, notes regenerated to
cover everything since the previous stable. Nothing is rebuilt.

Stables are never deleted. They are the changelog and the bisect range, and a deleted release
loses its download count. Nightlies keep the newest 30, and the prune deletes older ones with
their tags.

### Version names and codes

`ci.yml` derives both from `github.run_number`:

```
versionName = 0.4.<run>              (canary: 0.4.<run>-canary)
versionCode = (2000 + <run>) * 10    plus a chip digit, 0 for the all-in-one APK
```

The counter counts every run of `ci.yml`, pushes and pull requests included. Nightly numbers
therefore have gaps, and all three channels share one rising versionCode. Switching channel in
either direction is an upgrade to the installer.

- Releases are cut inside `ci.yml`. Another workflow's counter would start at 1 and send the code
  backward.
- The versionName is plain semver so that Obtainium can compare it.
- Never name a release `v0.4.0`. The updater takes the run number from the tag, reads code 2000
  and never offers it.

The updater compares on the older scale `2000 + run`. `legacyCode` (`update/ApkChoice.kt`) divides
a code of 20000 or more by ten, which drops the chip digit: 38222 and 38220 both read 3822.

### One APK per chip type

With the repository variable `ABI_SPLITS` set to `true`, a release carries one APK per chip type
(`vela-maps-arm64.apk`, `-armv7.apk`, `-x86.apk`, `-x86_64.apk`) and the all-in-one
`vela-maps-all.apk`. The switch is still off ([ROADMAP](../../ROADMAP.md)), so a release carries
one APK.

- `ApkChoice.pick` walks `Build.SUPPORTED_ABIS` in the phone's order and takes the first asset
  ending in `-<chip>.apk`, else the first APK with no chip in its name.
- Each chip APK adds a digit to the versionCode: armv7 1, arm64 2, x86 3, x86_64 4. Moving from
  the all-in-one APK to a chip APK of the same build is then an upgrade, and an F-Droid index gets
  the distinct code it needs for every APK.
- GitHub lists assets by name, and updaters older than `ApkChoice` take the first `.apk`.
  `vela-maps-all.apk` sorts first, so an old build keeps getting a file that runs on every chip.
  The switch can go on once a build with `ApkChoice` has been the stable for a few weeks.
- An arm64 phone then downloads 74.3 MB in place of 108.4.

### Release notes

Notes are the commit subjects since the previous release, one line each, from
`scripts/changelog.sh`. They appear on the release page, in Obtainium, in the update card and in
the What's new dialog, so a subject is written as a plain sentence about what changed for the user.

A nightly lists the commits since the previous `v0.*` tag, a stable those since the previous
stable, and canary only the newest subject. A commit is left out when it touches only docs paths,
when it changes only comments or blank lines, or when its subject starts with `Docs:`. A docs sweep
would otherwise read as a feature.

The app shows notes through `plainReleaseNotes`, which strips the Markdown marks and keeps the
first 24 lines. A stable's generated list covers a week, can run past 24 lines, and reads like a
git log. So on the day a stable is cut, a person edits its notes
(`gh release edit v0.4.<run> --notes-file`) to put a short list of the main user-facing changes
above the generated one. The promotion workflow cannot write that list.

### The in-app updater

`SelfUpdater.check` runs at launch and from the Check for updates button. The launch check needs
its switch (`self_update_check`) and runs at most once in 20 hours.

- Stable reads `releases/latest`. The promotion marks each stable Latest and the data releases are
  prereleases, so Latest is the newest stable.
- Nightly lists the app tags through `git/matching-refs/tags/v0.`, once per check, and takes the
  highest of the newest three runs that has a published release, nightly or stable. A release
  is fetched by the tag name the list gave (`appReleaseTags`), so a new version line such as
  `v0.5.<run>` needs no change in the app.
- Canary reads `releases/tags/canary` and the version lines in its notes, and also runs the nightly
  check. The higher code wins, so a canary that has fallen behind the nightlies strands nobody.

The updater never fetches the releases list. The data releases carry about 450 assets each, and a
check that listed them downloaded 4 to 9 MB. Every check logs one line under `VelaUpdate`.

An update is offered when its code is above the installed one. A failed check returns nothing and
the launch does not wait for it. Not now stores the offered code in `update_dismissed_code`, and
the launch check stays quiet until a higher code appears. The button ignores the stored code.

For stable and nightly the card also shows the notes of skipped releases: up to
`HISTORY_MAX_RELEASES` (8) between the installed build and the offered one, each under its
version.

Download and install:

- The APK that `ApkChoice` picked is saved in `filesDir/updates/`, and anything else there is
  deleted.
- The download client has no call timeout, because the shared client's 12 s limit would cut the
  body off. The download runs under a foreground service (`MapViewModel.downloadLaunch`) and can
  be canceled.
- `ApkCheck` accepts the file only if it starts with the zip bytes `PK`, has the size the release
  states, and matches the SHA-256 GitHub publishes for the asset when there is one. A file that
  fails is deleted.
- A file that passed is kept until a newer update replaces it. Android's install prompt goes away
  when the screen locks, and the next tap on Update reuses the file after checking it again.
- The APK goes to the system installer through the FileProvider. Android requires the same package
  and the same signing certificate, and the user confirms.

The updater holds the update back when Android records Play as the app that installed Vela or
started the install (`InstallSource.setForCar`). A tool sets that record so that Android Auto will
list Vela, and a self-install would replace it. A dialog offers Save the file, for that tool, or
Update anyway. [Chapter 10](10-android-auto.md#the-install-gate) explains the gate.

### What's new

`ui/WhatsNew.kt` compares the build's versionName with `last_seen_version` at launch. With no
stored version, an unfinished welcome screen or the dialog switched off, it stores the current
version and shows nothing. With a new version it fetches the build's own release,
`releases/tags/v<versionName>` or `releases/tags/canary`, and shows the notes once, after any
other one-time prompt.

Nothing is bundled in the APK, so the dialog shows what the release body says that day. A failed
fetch leaves the version unseen, and the next launch tries again.

### Obtainium

Obtainium follows the GitHub releases: the latest full release by default, which is the weekly
stable, and nightlies with "include prereleases" on. It reads the version from the tag, and the
canary tag never changes, so canary users take the in-app updater or download the APK.

Obtainium reads only the first hundred releases. The per-region data releases are therefore
created where GitHub sorts them last ([SPEC section 7.6](../../SPEC.md)).

### The F-Droid repository

`fdroid-repo.yml` builds a signed F-Droid repository from the releases. It is Vela's own
repository, because f-droid.org builds every app from source and Vela bundles prebuilt libraries.
[FDROID.md](../../FDROID.md) is the user guide.

- It serves the newest stable, plus the newest nightly when that is ahead.
- The build reads the versionCode off each stable APK and writes the highest into the app metadata
  as `CurrentVersionCode`. Clients suggest versions up to that code and treat higher ones as
  unstable, so a default user updates weekly and the nightly reaches only someone who turns on
  unstable updates for Vela.
- It runs after a nightly cut or a promotion through `workflow_run`, because a release created by
  CI's own token fires no release event for other workflows.

### The signing certificate

APKs are signed with the release keystore, which is outside the repository and reaches CI as
repository secrets. Without them, as in a fork's CI, the release build is signed with the debug
key and cannot update a real install. If the keystore were lost, no installed copy could be
updated again.

The certificate's SHA-256 is in the README and in FDROID.md. Check an APK against it with
`apksigner verify --print-certs`. The F-Droid index is signed with a separate key, and the
fingerprint in the repository address belongs to that key.

### The other releases are data hosting

Every release whose tag does not start with `v0.` holds files the app or the build downloads: the
datasets in [chapter 2](02-data-and-rebakes.md) and the build runtimes (`tts-runtime`,
`obf-runtime`, `cronet-runtime`). The files exist nowhere else. These releases are prereleases
too, so a cleanup of old prereleases matches them.

Anything that deletes, edits or picks releases selects by the tag pattern `v0.*`, never by
"prerelease" or by age. A prune that selected old prereleases deleted four data releases, and
offline downloads failed until the data was rebuilt. A promotion that took the newest prerelease
of any tag promoted a data release, and `releases/latest` then had no APK for the updater or the
F-Droid build.

The queries in `ci.yml`, `promote-stable.yml` and `fdroid-repo.yml` filter on `^v0\.` and page
through the whole list. Among hundreds of releases, a fixed `--limit` window can hold no app
release at all.

### Local builds

Without the CI properties a build is versionName `0.3.0`, versionCode 1. Keep a hand-set code below
1000, so that a phone that ran a local build is never ahead of the release line.

To test the updater without touching the installed app, build a second package with a high code
(`-PappId=app.vela.dev -PappVersionCode=37000`), check for updates, and look for `installed=3700`
under `VelaUpdate`.

## Limits

- A stable can be hours old. The promotion takes the newest nightly at Monday 16:00 UTC, the
  nightly job runs at 10:30, and nothing checks the build's health. Keeping a bad nightly out of
  stable means getting a fixed one out before the promotion.
- Moving from canary or nightly to stable offers nothing until stable passes the installed build.
  The updater never offers a lower code.
- A canary build's What's new can describe a newer canary, because the release it reads is replaced
  on every push. A nightly pruned before its first launch has no release, so its dialog never
  shows and the app asks again at each launch.
- The notes of skipped releases cover the newest 8 tags in the gap, counted before the other
  channel's releases are filtered out, so on stable the nightlies in between take most of them. A
  nightly user's list leaves out a release that has since been promoted.
- What Obtainium does with five APKs on one release is untested. A user may need to set a filter
  once the per-chip switch is on.
- F-Droid installs are counted nowhere. Pages serves the files with no counter.
