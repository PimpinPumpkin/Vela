# 2. Data and rebakes

## What you see

Nothing, when it works. The map, the places, the speed limits, the stop signs and the camera
dataset all come from files this repository builds and hosts on GitHub releases. They are
rebuilt on a schedule, and a phone picks up the new build either by streaming it or by offering
you an Update on a region you downloaded, in Settings > Offline maps.

When it does not work, what you see is a region that looks wrong for no visible reason: a state
whose downloaded map has no businesses, a country whose house numbers vanished, or an Update
button that never appears. Most of this chapter is about why those happened and what now stops
them.

## Where the data comes from

One release per dataset, each with its own manifest the app reads, each independently rebuildable
without shipping an app update. Every one is a fixed-tag prerelease ("infrastructure release")
whose assets exist nowhere else, so any cleanup that deletes releases selects by the tag pattern
`v0.*` and never by "prerelease" or "old".

| Dataset | Release tag | Manifest | Built from | Used for |
| --- | --- | --- | --- | --- |
| Open places | `places-overlays` | `places-overlay-manifest.json` | Overture Places, AllThePlaces, OpenStreetMap | The businesses on the map, offline and online |
| Offline basemap | `basemap-tiles` | `basemap-manifest.json` | OpenStreetMap via planetiler 0.10.2 | The map itself with no signal |
| World floor | `basemap-tiles` | `basemap-world.pmtiles` (no manifest of its own) | Natural Earth via planetiler | Coastlines, water, borders and place names anywhere, offline |
| Offline routing | `obf-regions` | `obf-manifest.json` | OpenStreetMap via OsmAndMapCreator | Turn-by-turn with no signal, and posted speed limits |
| Offline place search | `poi-packs` | `poi-pack-manifest.json` | OpenStreetMap | Searching places and addresses with no signal |
| Road features | `road-features` | `road-features-manifest.json` | OpenStreetMap | Traffic lights, stop signs, crossings, speed bumps, speed cameras |
| Surveillance cameras | `flock-cameras` | `flock-manifest.json` | DeFlock, in OpenStreetMap | The camera layer and the avoid-cameras feature |
| Buildings | `building-overlays` | `building-overlay-manifest.json` | Microsoft building footprints (ODbL) | Filling OSM's suburban building gaps |
| House numbers | `address-overlays` | `address-overlay-manifest.json` | OpenAddresses | House numbers where OSM has no `addr:housenumber` |
| Speed limits | `maxspeed-overlays` | `maxspeed-overlay-manifest.json` | OpenStreetMap `maxspeed` | The posted limit without a routing download |
| Voices and speech | `tts-runtime`, `asr-models` | catalog in `:core` | Piper, Kokoro, sherpa-onnx | On-device speaking and listening |
| Map fonts | `map-fonts` | none (one zip) | Roboto over Noto | Label glyphs for the offline style; online they come from GitHub Pages |

Every manifest URL is a build constant with a Gradle override for local testing, so a dev build
can point at a manifest served from the laptop through `adb reverse`:
`-PplacesManifestUrl`, `-PbasemapManifestUrl`, `-PworldBasemapUrl`, `-PobfManifestUrl`,
`-PpoiPackManifestUrl`, `-ProadFeaturesManifestUrl`, `-PflockManifestUrl`,
`-PoverlayManifestUrl` (buildings), `-PaddressManifestUrl`, `-PmaxspeedManifestUrl` and
`-PmapFontsUrl`. Without one, the app reads
`https://github.com/PimpinPumpkin/Vela/releases/download/<tag>/<manifest>`.

**The catalogs.** Rows come from four files in `tools/`, and the ids are shared wherever the
extract is shared:

| Catalog | Rows | Used by |
| --- | --- | --- |
| `routing-regions.json` | 458 | obf, basemap, place packs, road features, speed limits; carries each row's Geofabrik `pbf_url` |
| `places-regions.json` | 448 | the places bake (its own boxes, same ids, the OSM extract looked up by id in the routing catalog) |
| `overlay-regions.json` | 361 (groups `us` 51, `world` 185, `chunk` 125) | building footprints |
| `address-regions.json` | 52 | house numbers (US states with an OpenAddresses source) |

The routing catalog covers every country-level extract Geofabrik publishes, plus first-level
sub-areas for the countries Geofabrik divides (`germany-sub`, `france-sub` and so on, dispatched
together as `all-sub`). `china-sub` joined on 2026-09-21: Geofabrik cuts China into 33
sub-extracts (every province plus Beijing, Shanghai, Tianjin, Chongqing, Hong Kong and Macau, the
largest 164 MB), so they bake like `germany-sub` while the whole-country row keeps
`skip_obf: true`. Eleven rows carry that flag (California, France, Germany, Great Britain, Italy,
Spain, Japan, India, Indonesia, Brazil, China): they run out of memory in the routing bake even
filtered, and their sub-area rows cover them. 63 rows are `big: true` (over 450 MB of extract).

## How it is decided

### When each rebake runs

All times UTC. Every one of these can also be dispatched by hand from the Actions tab.

| What | When |
| --- | --- |
| Surveillance cameras | **Weekly**, Mondays 08:17 |
| Offline place search (`poi-packs`) | **Monthly**, the 3rd and the 5th at 07:15: half the catalog each by sorted id (the 256-job cap) |
| Road features | **Monthly**, the 4th and the 6th at 07:45, half the catalog each |
| Open places, full | **Monthly**, the 6th and the 7th at 05:00: half the catalog each by sorted id, because a job matrix caps at 256 |
| Open places, rolling | **Nightly** at 04:40: one seventh of the catalog |
| Offline basemap | **Monthly**, the 9th and the 10th at 05:00, split in halves the same way (the 447 rows without `skip_obf`) |
| Buildings, house numbers, speed limits | **Quarterly**, January / April / July / October, the 2nd at 04:00, dispatched by `quarterly-data-refresh` (speed limits as two halves, `shard=a` then `shard=b`) |
| Offline routing (`obf-regions`) | **Manual only** |
| World floor | **Manual only** (`world-lowzoom.yml` with `publish: true`) |

The monthly places bake always bakes against the newest Overture release in the public bucket
(a dispatch can pin one; the fallback is `2026-08-19.0`), and since 2026-09-22 against the newest
AllThePlaces run too (`runs/latest.json`; the fixed id `2026-09-05-13-32-25` is only the fallback
when that fetch fails). Before that, every bake since 2026-09-15 carried the same week-old chain
locator data while AllThePlaces publishes weekly.

**The nightly seventh.** OpenStreetMap is a real source of places, not just a donor of positions,
and it is the one source anybody can fix. So a seventh of the places catalog rebakes every night:
the catalog is sorted by id, and a row bakes on the night where `index % 7` equals the weekday
(Monday = 0). Every region comes round once a week, always on the same night, about 64 regions a
night. A seventh rather than everything because every rebake republishes the archive, and anyone
who downloaded the region is offered it again; streaming users pick it up with no prompt at all.

**The quarterly group.** `quarterly-data-refresh` fires three building dispatches (groups `us`,
`world`, `chunk`, five seconds apart), one house-number dispatch with `all=true` and one
speed-limit dispatch with `all=true`, and exits; each workflow then runs on its own clock. It no
longer touches routing: the obf bake stays manual on purpose, because runner memory limits and the
staging-to-live manifest copy are human steps.

A rebake **overwrites the current generation in place**: same asset names, same manifest. New
generations only fork when a file format changes, which is a deliberate cutover, never a cron.

### How a bake job runs

Every workflow has the same shape: a `plan` job builds the matrix from a catalog, one job per region
bakes and uploads only its own archive and emits a manifest entry as a build artifact, and a final
job publishes the manifest. Parallelism per workflow: places `max-parallel: 8` per shard, basemap 4,
obf 12, place packs 12, road features 16, buildings, house numbers and speed limits 8.

**Places.**

- **The Overture read is pruned on Overture's `bbox` column, not on the geometry** (2026-09-18).
  The region filter used to be `ST_X`/`ST_Y`, which DuckDB decodes per row, so every place on earth
  was read for every region: 504 s of a 570 s Kentucky bake, once per region, 414 times a wave. The
  same filter against the plain `bbox` struct lets row-group statistics skip everything outside the
  region, and the identical 400,608 rows came back in 3.7 s. The geometry test stays as the exact
  filter. With the scan down to seconds, `max-parallel` went from 4 to 8.
- **The toolchain is cached and pinned.** tippecanoe 2.79.0, go-pmtiles 1.31.2 and DuckDB sit in
  `~/vela-bin` under the cache key `bake-tools-<os>-tippecanoe-2.79.0-pmtiles-1.31.2-duckdb-latest`.
  Every job used to build tippecanoe from source: 69 s of a roughly two-minute job, 414 times a wave.
  osmium and jq still come from apt.
- **Scratch goes on the big disk.** A runner's root has about 14 GB free and `/mnt` about 65 GB; a
  continent-sized region (Australia: a 619 MB AllThePlaces extract, a 1.3 GB OSM extract, DuckDB's
  spill and tippecanoe's temp files) fills root, and a runner whose disk fills reports only "the
  runner has received a shutdown signal". `TMPDIR=/mnt/vela-work` moves all of it. DuckDB runs
  with `memory_limit = 11GB` and a spill directory under the 16 GB runner, so a big box is a slower
  bake rather than a dead one.
- **The AllThePlaces decode streams.** The region's z15 tiles come out of the world archive by range
  request (`pmtiles extract`), and `tippecanoe-decode` is filtered with `grep` before `jq` so it is
  read one feature at a time; parsing a dense country as one JSON document took the runner down.
  A cheap business-tag test runs first because AllThePlaces also carries national address registers
  (Belgium's decode was 7.1 million rows, nearly all addresses).
- **What the bake carries for a place** is chapter 1's subject. The parts that decide rebake
  behavior: a dropped duplicate still donates its hours, phone and website to the row that won
  (`atpfill` for a chain locator, `osmfill` for an OSM node, nearest same-name match only, never by
  brand), and every row carries `loc`, its city, state and ZIP formatted the way the country writes
  them. Both arrived on 2026-09-22, so a region baked before that has neither until its next rebake.
- **An empty region publishes nothing.** Uninhabited rows (Ashmore and Cartier) have no businesses,
  tippecanoe refuses an empty input, and the upload step skips them.
- **The upload retries.** GitHub's asset upload answers 500 on big archives often enough to fail a
  run by itself (a 300 MB archive, twice on 2026-09-17), so it tries four times, waiting
  `30 s x try` between attempts.

**Basemap.** planetiler is pinned to v0.10.2, downloaded with five retries and checked with
`unzip -t`, because the moving `latest` asset came back as something other than a jar on busy
runners and killed five jobs of the world bake. `PLANETILER_XMX = 10g`. A GitHub release asset
must stay under 2 GiB; Nunavut's z14 bake did not, so an archive at or over 2,147,483,648 bytes is
rebaked one zoom shallower, and fails loudly if it still does not fit. On the phone, an archive
shallower than `FULL_MAP_ZOOM = 14` (read from byte 101 of its header) is used only offline, since
streaming draws that ground better.

**World floor.** `world-lowzoom.yml` bakes z0-7 (default `maxzoom` 7) against Monaco, the smallest
extract Geofabrik publishes: at those zooms the water, boundary and place layers come from
planetiler's Natural Earth base data, not from OSM, so a tiny input measures the global cost. The
result is about 11 MB (10.96 MB published). The app pulls it once alongside the first offline
download and keeps it out of the per-region candidate list (`WORLD_ID = "world"`), so losing
signal away from a downloaded region is a coarse map rather than a blank screen.

**Routing.** Routing-only obf, indexed lean (`VelaObfShim`), from an extract pre-filtered by
`osmium tags-filter` to highway ways, ferry and shuttle-train routes and restriction and route
relations: that cuts a US state to about a third of its bytes and a quarter of its nodes, which is
what fits the big rows under a 16 GB runner. `JAVA_HEAP = 12g` (above that the runner kills the
JVM), `-XX:+UseParallelGC` so a doomed region fails fast. Measured before the filter: Bavaria needed
`-Xmx22g` and 3 h 7 min on a 32 GB machine for a 694 MB obf. A dispatch writes to
`obf-manifest-staging.json` by default (`staging: true`), which the app never reads; copying staging
over `obf-manifest.json` flips the fleet's whole catalog at once.

**House numbers** come from OpenAddresses, not OSM: the bake resolves each source's current job id
through the OpenAddresses batch API (ids rotate per refresh), and a source ending in `/*` folds every
county and city source under that prefix into one archive for states with no statewide source.

### How the manifest is published

**Places and basemap: derived from the release.** The last job of a bake used to fold the run's own
entries into whatever the manifest already said, and it sat in a concurrency group so parallel runs
could not clobber each other. GitHub cancels a job that is *pending* in a concurrency group as soon
as a newer one joins, so in a wave of runs the middle merges were killed after their archives had
already been uploaded: on 2026-09-18, 10 of 25 basemap runs lost their merge and the manifest listed
99 of 414 regions; on 2026-09-22, 34 of 54 runs of a state-wide places wave lost theirs, published
new archives under the previous bake's revisions, and mailed a failure each.

So the merge is now a repair. `scripts/merge-places-manifest.sh` and
`scripts/merge-basemap-manifest.sh` do nothing but `exec` `repair-places-manifest.sh` and
`repair-basemap-manifest.sh` with today's date and the run's entry directory. Each one:

1. Lists every `places-*.pmtiles` or `basemap-*.pmtiles` asset on the release, with its size.
2. Keeps the old manifest's row for an archive that has not changed. For places, "unchanged" means
   the same size in MB **and** an upload date no later than the row's `rev`, because two bakes of
   a small state can round to the same size (Nebraska, 2026-09-22). For basemap it is the size
   alone.
3. Builds a fresh row for anything else. Places take name and bounds from `places-regions.json`
   and look for a `places-<id>.<oldrev>.vpatch` asset to carry as the delta. Basemap takes the name
   from the routing catalog and the bounds from the archive itself: one range request for the first
   127 bytes, read by `scripts/pmtiles-bbox.py` (int32 E7 at bytes 102 to 117, min lon, min lat,
   max lon, max lat), then passed through `scripts/clamp-bbox.py`.
4. Lets the run's own entry files win for the regions it baked, since they carry the true bake
   `rev` and any delta.
5. Uploads with `upload_manifest`: up to five tries, sleeping `RANDOM % 15 + 5` seconds between
   them, because several merges finishing together race on the one asset (`--clobber` deletes and
   re-uploads, so the loser sees a 422 "already exists" or a 404; two of nine parallel runs on
   2026-09-22).
6. Lists the release again, and if an archive landed meanwhile, rebuilds (basemap once more; places
   up to three attempts).

The concurrency groups are gone from both merge jobs, and the places merge runs `if: always()`. The
manifest is now a function of what is published: running it twice changes nothing, a merge that
never ran costs nothing, and the next one puts everything back. A run with no entries of its own
still runs the repair and heals whatever an earlier run left out.

**Everything else still folds.** The obf, place pack and road features workflows serialize whole
runs with a run-level concurrency group, and the building, house-number and speed-limit merges sit
in their own merge-job groups; their merge scripts replace rows by id and keep the rest. They are
dispatched far less often, in fewer and larger runs, which is what keeps the cancellation from
biting them.

### The Alaska box

Alaska's extract crosses the antimeridian (the Aleutians reach past 180), so every source that
derives a box from it (the PMTiles header, osmium's header box, Geofabrik's index) reports
longitude -180 to 180. Read literally, `[49.8, -180, 73, 180]` covers every point between 49.8 N
and 73 N on Earth. The address-overlay rule hides the basemap's own house-number layer wherever a
house-number overlay covers the view, so the Netherlands, Britain, Canada and Germany north of
Munich lost their house numbers to an empty Alaska overlay, while France and Spain, below the band,
kept theirs (issue #257, fixed 2026-09-22). There was never a data gap to fill.

Three layers of fix:

- **The live manifests** (house numbers, buildings, speed limits, basemap) were patched by hand to
  `E = -129.9`, which fixed every installed build with no update. The places catalog already had a
  hand-set Alaska box.
- **The catalogs and bake scripts** clamp it: `overlay-regions.json` and `address-regions.json`
  carry `-129.9`, and `scripts/clamp-bbox.py` rewrites an `alaska` box whose west edge is at or
  below -179 and east edge at or above 179, in the basemap bake, the basemap repair and the
  speed-limit bake.
- **The app refuses a globe-wide box.** `RegionPolys.boxCovers` only covers when
  `e - w < WORLD_SPAN` (350.0) or `n - s >= WORLD_BAND` (120.0). The second clause keeps a real
  whole-world row, like the world floor, working.

### Which region a point is in

Since 2026-09-21 (issue #599) a region is picked by the polygon its extract was cut with, not its
box. `scripts/region-polys.py` fetches the `.poly` Geofabrik publishes beside every extract in the
routing catalog, simplifies each to `TOL_DEG = 0.05` (about 5 km), and writes
`app/src/main/assets/region_polys.json` (458 regions, about 340 KB). `RegionPolys.covers(id, lat,
lng)` answers from it, or null for an id it has no polygon for (the building catalog, or a row added
since the last run of the script), and every caller then falls back to `boxCovers`. The tie-break
among covering regions is still the smallest box. The trigger: Vietnam's extract carries the island
claims, so its box reaches 114.6 E and swallows Hong Kong, and "download the area you're viewing"
from Hong Kong announced Vietnam. `RegionPolysTest` fails if a catalog id is missing from the asset,
so the script has to be rerun whenever a row is added.

### How your phone picks up a new build

**Streamed data** (places, basemap, buildings, house numbers and speed limits while online) is read
by HTTP range requests against the same URL, so a rebuilt archive is picked up as soon as the cache
lets go of the old bytes. Forcing it is a matter of clearing the map cache from Settings > Offline
maps. The places and basemap stores cache each manifest for `MANIFEST_TTL_MS = 60 min` and remember
a failed fetch for `MISS_MEMO_MS = 10 min`, since the lookup runs on every camera idle. The cache
expires on purpose: a bake publishes while the app is running, and a process that lives for days
would otherwise never offer the Update (found on a device, a rebake four minutes old and no Update).

**Downloaded data** stays exactly as downloaded, which is the point of downloading it. Each
installed file's revision is recorded beside it (`revs.json` per store), and
`MapViewModel.refreshRegionUpdates` compares each downloaded region's pieces against the
manifests when Offline maps opens: the obf by the routing row's `rev` (only when an installed rev
is known), and every places and basemap archive whose box center falls inside the region's polygon.
A region with anything newer gets an **Update** button. One tap refreshes, in order, the place pack,
the places archives, the basemap archives and the routing file.

- **Revisions.** Places, basemap and obf rows carry `rev` as the bake date, `YYYYMMDD`. Place packs
  count instead: `rev` is the live row's plus one. Road features carry an `updatedAt` stamp, and the
  app re-downloads a region's file on its own whenever the stamp differs (manifest cached
  `MANIFEST_TTL_MS = 6 h`, `MAX_LOADED = 4` regions in memory). The camera dataset carries a
  `version`; `FlockCameras.refresh` runs at app start and downloads it only when it beats both the
  downloaded copy and the floor bundled in the APK.
- **Same-day caveat.** Two bakes of a region on the same UTC day share a `rev`. The second one
  overwrites the archive but the manifest's rev does not move, so a phone that downloaded the first
  bake that morning is never offered the second, and no patch is published between them. The places
  workflow's `rev` input overrides the stamp for testing.

**Delta updates** (bake publishing since 2026-09-18). A week of OpenStreetMap edits moves about one
tile in a hundred, so a places rebake publishes a patch against the archive it replaces instead of
making every downloader take a few hundred MB again. On 2026-09-22 the live places manifest listed
416 regions, 36 of them with a patch.

- **Built and proven in the bake, or not published.** The job fetches the published archive and
  its `rev` before baking, runs `scripts/pmtiles-make-patch.py`, applies the result to a copy with
  `pmtiles-apply-patch.py --verify`, and publishes only if the result carries the new archive's
  fingerprint and the patch is under a third of the archive. It is uploaded as
  `places-<id>.<fromRev>.vpatch` and the row gains `delta: {fromRev, url, sizeMb}`. A rebake that
  also carries a change to the bake script is not a delta candidate (Guernsey and Jersey the day
  after the OSM source landed: 2506 of 4586 tiles, 2.17 MB against 3.3 MB, correctly refused).
  `places-churn.yml` measures real churn by baking one region twice against OSM extracts N days
  apart (Andorra, six days: 11% of tiles, 25% of bytes, a delta at 22% of a full download).
- **Applied in place.** `PmtilesPatch` (format `VELAPTCH`, `VERSION = 2`) appends the changed tile
  blobs and the rebuilt directory past the end of the file, `fsync`s, checks the fingerprint (SHA-256
  over sorted tile ids, run lengths and tile hashes) against the directory it just wrote, and only
  then rewrites the 127-byte header. An interrupted apply leaves the old archive intact and longer.
  The patch names no offsets, only whether each tile rides in the patch or is already in the
  archive under that id, so it lands on an archive an earlier patch or a compaction already moved.
- **Only from the exact revision.** The installed `rev` must equal the patch's `fromRev`; a gap,
  a refusal or a fingerprint mismatch falls back to downloading the region whole.
- **Dead space is reclaimed locally.** A patch leaves the replaced tiles behind, counted per archive
  in `dead.json`. Past a fifth of the file (`DEAD_LIMIT_DIVISOR = 5`), `PmtilesCompact` rewrites it
  in tile order into a temporary file, needing the archive's size plus `MARGIN_BYTES = 32 MB` free,
  checks the fingerprint and swaps it in. Past half the file in dead bytes, the delta is refused
  and the region comes down whole. `adb shell setprop debug.vela.compact true` compacts after every
  patch.
- **Policy is the user's, and off by default.** Settings > Offline maps > "Update downloaded
  regions": "Never on its own" (`RegionUpdates.Mode.OFF`, the default), "On Wi-Fi" (an unmetered
  network, as the system judges it) or "On Wi-Fi and mobile data". It stays off until somebody has
  watched a patch download and apply on a real phone. On Wi-Fi or mobile, a minute after the app
  starts and at most once in 20 hours (`AUTO_PATCH_DELAY_MS = 60_000`, `AUTO_PATCH_EVERY_MS` = 20 h),
  every installed places or basemap archive and place pack whose manifest publishes a patch from the
  installed revision takes it quietly; routing files publish no patches and a full re-download is
  never automatic. The mode also decides whether a tap on Update may patch; otherwise the tap
  downloads the archive whole, over the installed copy, which stays until the new one is complete.
  Every attempt is recorded in the diagnostics ring (kind `delta`, `auto:` for the daily pass) and
  in logcat under `VelaDelta`.
- **Place packs have their own deltas.** `poipack_delta.py` publishes a row-level SQLite delta
  (`<id>.delta.zip`) only when it is under half the full pack, and the app applies it whenever the
  installed pack's rev equals `fromRev`, independent of the setting above. Basemap, obf and the
  overlays have no delta: an update is a full download.

So a fix that lands in OpenStreetMap reaches people in this order: the region's night comes round
(within a week), streaming users see it once their cache lets go, and people who downloaded the
region see an Update the next time they open Offline maps, within the hour of the bake.

### Retired data

`routing-graphs` (GraphHopper CH graphs, about 270 assets) is the previous generation of offline
routing, replaced by `obf-regions` on 2026-09-15. Nothing in the app reads it and the workflow that
built it is gone. The first launch after that update deletes the old graphs from the phone and
tells the user to download their regions again (`LegacyGraphs.purge`). The release itself is still
hosted, unreferenced.

## Limits

- **A bad row in a source dataset lives until the next bake.** For places that is up to a week for
  the OSM and AllThePlaces sides; for buildings, house numbers and speed limits, up to a quarter;
  for routing, until someone dispatches it. Fixing it in OpenStreetMap is the durable route, and it
  is why OSM wins the coordinate in the places bake.
- **Overture publishes monthly**, so "rebake sooner" does not mean "fresher" for the fields that
  come from Overture. It does for the AllThePlaces and OSM halves.
- **The basemap repair judges "unchanged" by size alone.** A same-size rebake keeps the old `rev`
  when no run entry overrides it, the way the places repair did before the upload-date check.
- **The routing bake is manual and memory-bound.** A region whose filtered extract still does not
  fit 12 GB of heap has no routing file, and the obf catalog changes only when someone dispatches it
  and copies staging over live. If a region's roads have changed materially, dispatching that one
  region is the fix.
- **Nothing is versioned per user.** Everyone on a given day gets whatever the release currently
  holds, which is why rebakes overwrite in place rather than accumulating generations. A patch
  exists only from the immediately previous revision, so a phone two rebakes behind takes the whole
  file.
- **The data releases are huge to list.** Each of `obf-regions`, `places-overlays`, `basemap-tiles`
  and `road-features` holds about 450 assets, about 780 KB of release JSON apiece, and they sort to
  the top of the release list because they are republished constantly. The app updater therefore
  reads app tags from the refs endpoint and never lists releases (a check is three requests,
  208 KB); anything else that lists releases has to paginate or bound by tag.
