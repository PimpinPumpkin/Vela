# Reach, as far as GitHub will say

Written weekly by `scripts/download-stats.sh` (workflow: `.github/workflows/download-stats.yml`).
Vela has no telemetry and this is not telemetry: every figure here is a byproduct of hosting files
on GitHub, counted whether anyone looks or not, and none of it is attributable to a person.

It exists because the numbers EXPIRE. The nightly prune deletes old releases and their counters
with them, the traffic API is a rolling fourteen days, and a rebuilt asset starts counting from
zero again. Anything not written down on the day is gone.

| File | What it is |
| --- | --- |
| `downloads.csv` | One row per app release asset, appended each run. `v0.*` only. |
| `data-releases.csv` | One row per data release, appended. `kind` says how to read it. |
| `regions.csv` | Current per-region counters, overwritten each run. Which regions people download. |
| `traffic.csv` | Views, clones, stars, forks, open issues, appended. |
| `referrers.csv` | Where visitors came from, appended. |

## How to read it without fooling yourself

**There is no unique-downloader figure anywhere in GitHub's API.** A release asset has a counter,
full stop. The closest honest proxy for active installs is the NEWEST STABLE's counter: the in-app
updater and Obtainium each pull the APK once per device per release, so a few days after a stable
is cut, its counter is roughly the number of updating devices. It counts devices, not people.

**`kind` in `data-releases.csv`:**

- `downloaded` - a phone pulls the whole file once. These mean installs.
- `streamed` - MapLibre range-reads the archive tile by tile, and each read counts. These measure
  map panning, not people.
- `ci` - our own runners fetching a build dependency. Not reach at all.

**A counter resets when its asset is replaced**, so a data release's figure is "since that file was
last rebuilt". The nightly places bake resets a seventh of its catalog every night.

**The F-Droid channel is invisible.** Those APKs are served from GitHub Pages, which publishes no
counters. Every number here is a floor, never a total.

**The canary APK is deliberately absent.** Its asset is replaced on every push, so its counter is
never older than the last commit.

## The one counter that measures every channel

The two `flock-cameras` assets are an accident worth protecting, because they see installs that
release counters never can - F-Droid, canary, and anyone who simply has not updated:

- `FlockCameras.refresh` runs once per process start, from `VelaApp.onCreate`, and ALWAYS fetches
  `flock-manifest.json`. So that counter is **cold launches**.
- It downloads `flock_cameras.bin` only when the hosted version beats the copy on disk. So that
  counter is **distinct installs that have opened the app** since the dataset was last re-baked.
- `flock-cameras.yml` re-bakes on Mondays at 08:17 UTC, and replacing an asset resets its counter.
  Both figures therefore mean "since Monday morning", which makes them a WEEKLY ACTIVE INSTALL
  reading rather than a cumulative total.

**The snapshot cron is deliberately Monday 06:20 UTC, two hours BEFORE that bake**, so it records a
full week just before the reset. Moving either job breaks the reading; if the bake time changes,
move this one with it.
