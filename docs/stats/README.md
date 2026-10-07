# Reach, as far as GitHub will say

Written weekly by `scripts/download-stats.sh` (workflow `.github/workflows/download-stats.yml`).
Vela has no telemetry. Every figure here is a byproduct of hosting files on GitHub, counted
whether anyone looks or not, and none of it can be tied to a person.

The numbers are written down because they expire. The nightly prune deletes old releases and
their counters, the traffic API covers a rolling fourteen days, and a rebuilt file starts
counting from zero.

| File | What it is |
| --- | --- |
| `downloads.csv` | One row per app release file, appended each run. `v0.*` only. |
| `data-releases.csv` | One row per data release, appended. `kind` says how to read it. |
| `regions.csv` | Current per-region counters, overwritten each run. |
| `traffic.csv` | Views, clones, stars, forks, open issues, appended. |
| `referrers.csv` | Where visitors came from, appended. |

## Reading it

GitHub's API has no unique-downloader figure. A release file has a counter and nothing else.
The closest thing to active installs is the newest stable's counter: the in-app updater and
Obtainium each pull the APK once per device per release, so a few days after a stable is cut
its counter is roughly the number of updating devices. It counts devices, not people.

`kind` in `data-releases.csv`:

- `downloaded`: a phone pulls the whole file once. These mean installs.
- `streamed`: MapLibre reads the archive tile by tile, and each read counts. These measure
  map panning.
- `ci`: our own runners fetching a build dependency. Not reach.

A counter resets when its file is replaced, so a data release's figure is "since that file was
last rebuilt". The nightly places bake resets a seventh of its catalog every night.

The F-Droid channel is invisible: those APKs are served from GitHub Pages, which publishes no
counters. Every number here is a floor.

The canary APK is left out. Its file is replaced on every push, so its counter is never older
than the last commit.

## The counter that sees every channel

The two `flock-cameras` files see installs that release counters cannot: F-Droid, canary, and
anyone who has not updated.

- `FlockCameras.refresh` runs once per process start and always fetches `flock-manifest.json`.
  That counter is cold launches.
- It downloads `flock_cameras.bin` only when the hosted version is newer than the copy on the
  phone. That counter is distinct installs that have opened the app since the dataset was last
  rebuilt.
- `flock-cameras.yml` rebuilds on Mondays at 08:17 UTC, and replacing a file resets its
  counter. Both figures therefore mean "since Monday morning": a weekly active install reading.

The snapshot job runs Mondays at 06:20 UTC, two hours before that rebuild, so it records a full
week just before the reset. If the rebuild time changes, move the snapshot with it.
