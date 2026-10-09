# 2. Data and rebakes

## What you see

Nothing, when it works. The offline map, the places on it, offline routing and search, speed
limits, stop signs, the camera layer and UK fuel prices come from files this repository builds and
hosts as GitHub release assets. They are rebuilt on a schedule. A phone picks up a new build by streaming it, by
patching a file it downloaded, or by offering Update on a region in Settings > Offline maps.

While a downloaded region updates, the card shows one of three states:

- a percent, while a small update or a whole file downloads;
- "Writing the places update" (or map update) with no percent, while a small update is written
  into the file and checked. The check reads the whole file, which takes minutes on a slow head
  unit;
- "The small update did not fit. Downloading..." with a percent from 0, when the check fails.

## Where the data comes from

Each dataset has its own release and manifest and is rebuilt without an app update. The files
exist nowhere else, so nothing may delete these releases ([chapter 12](12-releases.md)).

| Dataset | Built from | Release, manifest | Rebuilt (schedule job) | How a phone gets a new build |
| --- | --- | --- | --- | --- |
| Places on the map | Overture Places, AllThePlaces, OpenStreetMap | `places-overlays`, `places-overlay-manifest.json` | A seventh of the catalog daily (`places-slice`), so each region weekly | Streamed. A download is patched or replaced |
| Offline basemap | OpenStreetMap, through planetiler | `basemap-tiles`, `basemap-manifest.json` | 30 days (`basemap-a`, `basemap-b`) | Downloaded only, then patched or replaced |
| World floor, zooms 0 to 7 | Natural Earth, through planetiler | `basemap-tiles`, `basemap-world.pmtiles` | By hand (`world-lowzoom.yml` with `publish: true`) | Once, with the first basemap download |
| Offline routing | OpenStreetMap, through OsmAndMapCreator | `obf-regions`, `obf-manifest.json` | 90 days (`obf-us`, `obf-a`, `obf-b`), staged | Whole file, on Update |
| Place packs (offline search) | OpenStreetMap | `poi-packs`, `poi-pack-manifest.json` | 30 days (`poi-a`, `poi-b`) | Row-level delta or the whole pack |
| Road features (traffic lights, stop signs, crossings, speed humps, speed cameras) | OpenStreetMap | `road-features`, `road-features-manifest.json` | 30 days (`roads-a`, `roads-b`) | The file for the region you are in, again when `updatedAt` changes |
| Grid cells, a region in 0.5 degree pieces | OpenStreetMap and the region's places archive | `cells-<region>`, with `cells-manifest.json` on `grid-cells` | 30 days (`cells-us`, `cells-a`, `cells-b`) | The whole cell when `rev` is newer |
| Surveillance cameras | DeFlock's camera nodes in OpenStreetMap | `flock-cameras`, `flock-manifest.json` | Its own cron, Mondays 08:17 UTC | At app start when `version` is newer |
| UK fuel prices | The UK government's Fuel Finder, through matthewgall/fuelfinder-archive | `fuel-gb`, `fuel-gb-manifest.json` | Its own cron, hourly at :23 (`fuel-gb.yml`), published when the source changed | When a UK gas station needs a price, checked again at most every 3 hours |
| Buildings, where OSM has gaps | Microsoft building footprints (ODbL) | `building-overlays`, `building-overlay-manifest.json` | 90 days (`buildings-us`, `buildings-world`, `buildings-chunk`) | Streamed. A saved copy is never refreshed |
| House numbers, where OSM has none | OpenAddresses | `address-overlays`, `address-overlay-manifest.json` | 90 days (`addresses`) | Streamed |
| Speed limits without a routing download | OpenStreetMap `maxspeed` | `maxspeed-overlays`, `maxspeed-overlay-manifest.json` | 90 days (`maxspeed-a`, `maxspeed-b`) | Streamed |
| Map fonts | Google Sans Flex over Roboto over Noto | `map-fonts` (one zip), also unpacked to GitHub Pages | By hand (`scripts/build-map-fonts.sh`) | From Pages online. The zip comes with the first basemap download |

Not in the table:

- Speech models (`asr-models`) and Piper voices (sherpa-onnx's own `tts-models` release) are
  fixed files.
- `tts-runtime`, `obf-tools` and `cronet-runtime` hold build inputs. `cronet-build.yml` adds the
  current Chrome for Android stable to `cronet-runtime` every Monday at 06:20 UTC.
- `routing-graphs` holds the retired GraphHopper graphs. Nothing rebuilds it and no current
  build reads it; it stays hosted for old app versions. A current build deletes old graphs at
  first launch (`LegacyGraphs.purge`).

### Catalogs

Regions come from four files in `tools/` that share ids.

| Catalog | Rows | Used by |
| --- | --- | --- |
| `routing-regions.json` | 458 | Everything baked from a Geofabrik extract (each row carries its `pbf_url`) |
| `places-regions.json` | 448 | Places, with its own boxes |
| `overlay-regions.json` | 361, in groups `us`, `world`, `chunk` | Buildings |
| `address-regions.json` | 52 | House numbers |

The routing catalog has every country-level extract Geofabrik publishes, plus sub-areas for the
countries Geofabrik divides (groups such as `germany-sub`). Eleven whole-country and whole-state
rows carry `skip_obf: true`. Their sub-area rows cover them, so the routing, basemap and
grid-cell bakes skip them and the places catalog leaves them out. A workflow matrix holds at most
256 jobs, so the catalog bakes in halves or sets.

Each manifest URL has a Gradle override for local testing (`-PplacesManifestUrl` and the rest,
in `app/build.gradle.kts`).

## How it is decided

### One bake at a time

The bakes have no schedules of their own. They share the repository's 1,000 GitHub API requests
an hour with CI, and overlapping bakes failed the app releases and each other with HTTP 403.
`bake-conductor.yml` runs hourly at :05 (`scripts/bake-conductor.py`) over
`tools/bake-schedule.json`. Each hour it:

1. Settles the run it started last. Failed regions are retried alone, at most `maxRetries` (3)
   times a cycle. A run where only the manifest merge failed is rerun.
2. Publishes the staged routing manifest if it is ready.
3. Starts nothing while a workflow in the schedule is running or queued, or with fewer than
   `reserve` (400) API requests left this hour.
4. Otherwise starts one bake: a pending retry, else the most overdue job. A job is due
   when `everyHours` have passed since its last good run.

The conductor's run never fails. Its record is `state.json` on the `bake-conductor` release.
A bake started by hand is fine; the conductor sees it running and waits. A new bake or cadence
is an edit to the schedule file.

Two small jobs keep crons of their own: the camera dataset weekly, and the UK fuel prices
hourly, because that source changes twice a day. The fuel job reads the published manifest as a
plain download and uploads nothing while the source is unchanged, so most hours spend no API
request.

### The daily seventh of places

The places catalog is sorted by id, and a row bakes on the day where `index % 7` equals the UTC
weekday (Monday is 0): 64 regions a day. OpenStreetMap is the one source anybody can correct, so
a fix there reaches the map within a week. The whole catalog is not baked daily because every
rebake offers the archive again to everyone who downloaded the region. Each bake reads the
newest Overture release and AllThePlaces run.

### Routing goes through staging

The routing bake writes `obf-manifest-staging.json`, which no phone reads. Only the manifest is
staged: each region's file is overwritten in place as it bakes, and the live `obf-manifest.json`
is what offers Update and lists new regions. The conductor copies staging over live once all
three routing jobs finish a clean cycle (every region baked, retries included) and staging passes
four checks: no live region is missing, no `rev` goes backwards, every row's file is on the
release, and at least one region is newer. The replaced manifest is kept as
`obf-manifest-previous.json`; copying it over the live name is the rollback.

A cycle that runs out of retries with regions still failing is not clean, and nothing is
published. It becomes clean when each of those regions has been baked since, for example by a
dispatch of that one region after a fix: the conductor sees a file newer than the cycle's last
attempt. Before that rule a single failed region held the whole catalog's update back for the
90 days until the next cycle.

The bake indexes roads only, from an extract that `osmium tags-filter` has cut to about a third
of its bytes to fit a 16 GB runner. An extract with more than 250 MB of roads (`OBF_SPLIT_MB`)
is cut into strips, indexed strip by strip and joined. The highway-hierarchy shortcuts
([chapter 5](05-routing.md)) go in last; a region where that step fails ships without them.

### How a bake runs

A plan job builds the matrix from a catalog, one job per region bakes and uploads its own file,
and a last job publishes the manifest. The rules that keep a bake from failing:

- A rebake overwrites the same asset names. A new generation is forked only when a file format
  changes.
- Every OSM extract is downloaded through `scripts/fetch-pbf.sh`, which survives a mirror that
  redirects in a circle.
- Tools are pinned: planetiler 0.10.2 for the basemap; tippecanoe 2.79.0, go-pmtiles 1.31.2 and
  DuckDB 1.5.4 for places.
- The places read of Overture is pruned on its `bbox` column. A filter on the geometry reads
  every place on earth: on Kentucky the scan took 504 s that way and 3.7 s on `bbox`.
- A release asset must stay under 2 GiB. A basemap archive that reaches it is rebaked one zoom
  shallower, and the phone uses an archive shallower than `FULL_MAP_ZOOM` (14) only offline.

What a places archive carries is [chapter 1](01-places.md). The other bake rules are in
[SPEC sections 5.2 and 7](../../SPEC.md).

### How a manifest is published

For places, basemap and grid cells the manifest is derived from the files on the release
(`scripts/repair-places-manifest.sh`, `repair-basemap-manifest.sh`, `merge-cells-manifest.sh`).
GitHub cancels a job that is pending in a concurrency group when a newer run joins, so a merge
job that folded its own run's entries into the old manifest could be canceled after its archives
were uploaded. The repair keeps the old row for an unchanged archive (the same size, and not
uploaded after the row's `rev`), builds a new row for anything else, and lets the run's own
entries win. Running it twice changes nothing, and the next run repairs a merge that never ran.

The other workflows still replace rows by id in the old manifest, serialized by a concurrency
group.

### UK fuel prices

Google's keyless search gives US gas stations a price and UK ones none. The UK government's
Fuel Finder publishes every forecourt's prices, but its site refuses connections from outside the
UK, GitHub's runners among them, and its API needs a personal key. `fuel-gb.yml` therefore tries
the site, logs the refusal, and takes the copy matthewgall/fuelfinder-archive republishes on
GitHub. `tools/build-fuel-gb.py` trims it to brand, position, four prices (E10, E5, standard and
premium diesel) and the time of the newest report, about 150 KB gzipped for some 8,100
forecourts, and drops placeholder prices outside 100 to 250 p. A source whose newest price is
more than 3 days old, or that cannot be fetched, ends the run green with a warning and the
published file stays.

The phone downloads the file the first time a UK gas station without a price shows up in
results, on a place sheet or in the car's results, and checks the small manifest again at most
every 3 hours. A Google place counts as a UK gas station by the type and country Google's reply
carries, which read the same in every app language; an open-data place, which has neither, by its
category and position. That position test leaves out the Republic of Ireland, which Fuel Finder
does not cover, and keeps Northern Ireland, which it does, so a phone in Dublin never downloads
the file.

A gas station takes the nearest forecourt within 75 m. A forecourt of the place's own brand wins
when it is at most 25 m farther, so a Tesco forecourt beside an Esso goes to the Tesco listing,
while a supermarket shop on another brand's forecourt takes that forecourt. The text reads
"172.9p/E10 · 199.9p/B7"; the map bubble shows the petrol price.

Stations report a price only when it changes, so an old report is often still the price, and the
place sheet says how old it is ("Updated 3 days ago"). A station whose newest report is more
than 21 days old shows nothing, and a file whose newest report is more than 2 days old shows no
prices at all, because then the archive has stopped. The rules and constants are in
[SPEC section 5.8](../../SPEC.md).

### Which region a point is in

A region is picked by the polygon its extract was cut with, because a bounding box also covers a
neighbor's land. `scripts/region-polys.py` simplifies the `.poly` file Geofabrik publishes beside
each extract to about 5 km and writes `app/src/main/assets/region_polys.json`.
`RegionPolys.covers` answers from it. For an id with no polygon (the building catalog, or a row
added since the script last ran) the stores fall back to `RegionPolys.boxCovers`. Among covering
regions the smallest box wins. `RegionPolysTest` fails when a catalog id has no polygon, so
rerun the script after adding a row.

An extract that crosses the antimeridian reports a box from longitude -180 to 180, which read
literally covers every point in its latitude band. A house-number overlay with such a box would
hide the basemap's own house numbers across the band. `boxCovers` therefore rejects a box 350
degrees wide or more (`WORLD_SPAN`) unless it is also at least 120 degrees tall (`WORLD_BAND`),
which is the world floor. For the `alaska` row `scripts/clamp-bbox.py` also cuts the east edge
to -129.9.

### How your phone picks up a new build

Streamed data (places, buildings, house numbers, speed limits) is read by HTTP range requests
against a fixed URL, so a rebuilt archive shows up once the map's tile cache drops the old bytes.
Settings > Offline maps > Clear map cache forces it. The places and basemap stores cache a
manifest for 60 minutes (`MANIFEST_TTL_MS`), so a long-running app still sees a new bake.

Downloaded data stays as downloaded until it is updated. That includes the map you see online: a
downloaded basemap archive answers tile requests where it covers them (`LocalBasemapTiles`). Each
store records the installed revision (`revs.json`), and `MapViewModel.refreshRegionUpdates`
compares it with the manifests when Offline maps opens and after any download. A region's row
shows Update when its place pack, its routing file, or a places or basemap archive whose box
center lies inside the region is newer, or when one of those archives never finished
downloading. One tap refreshes the place pack, then places, then the map, then routing.

What counts as newer:

- Places, basemap, routing and grid cells: `rev` is the UTC bake date, `YYYYMMDD`. Two bakes on
  the same day share a `rev`, so the second is never offered to a phone that has the first and
  no patch is built between them.
- Place packs: `rev` counts up by one per bake.
- Road features: `updatedAt`, compared with the stamp stored beside the file. The manifest is
  read again every 6 hours.
- Cameras: `version`. `FlockCameras.refresh` runs at app start and downloads the dataset when
  it beats both the downloaded copy and the one bundled in the APK ([chapter 3](03-cameras.md)).

### Patches

A week of OpenStreetMap edits changes little of a region: on Kentucky, 1.3% of the tiles and 3.2%
of the bytes, a 4.4 MB patch against a 183 MB archive. So the places and basemap bakes publish a
patch against the old archive.

- The bake applies the patch to a copy of the published archive and publishes it only if the
  copy has the new archive's fingerprint and the patch is under a third of the archive.
- The phone applies it in place (`PmtilesPatch`). It appends the changed tiles and a rebuilt
  directory, checks the fingerprint, and only then rewrites the header, so an interrupted apply
  leaves the old archive readable.
- A patch applies only when the installed `rev` equals its `fromRev`. Otherwise, or on a
  fingerprint mismatch, the whole file is downloaded over the installed copy, which stays until
  the new one is complete.
- Replaced tiles stay in the file as dead bytes. Past a fifth of the file
  (`DEAD_LIMIT_DIVISOR`) `PmtilesCompact` rewrites the archive without them.
- Place packs have their own delta, a small SQLite file of rows to delete and insert, published
  only when it is under half the pack. `PoiPackStore.applyDelta` checks every table's row count
  against the manifest before committing.

### Automatic updates

Settings > Offline maps > "Update downloaded regions" sets `RegionUpdates.Mode`: "Never on its
own", "On Wi-Fi" (the default, meaning an unmetered network as the system reports it) or "On
Wi-Fi and mobile data". When the mode allows the current connection, the app checks a minute
after start, every 3 hours, and 15 s after a validated network appears. A check that read the
manifests is not repeated for 20 hours (`AUTO_PATCH_EVERY_MS`). Nothing runs during navigation.

The pass applies every published patch that starts from an installed revision, for places
archives, basemap archives and place packs, and downloads again any grid cell with a newer bake
(a cell is a few MB). It never downloads a whole places, basemap or routing file; those wait for
Update. A place pack whose delta fails to apply is downloaded whole. A tap on Update tries the
patch first in every mode. Each attempt is logged under `VelaDelta`.

The patch format and thresholds are in [SPEC section 7](../../SPEC.md). Downloading and deleting
regions is [chapter 8](08-offline.md).

## Limits

- A wrong row in a source dataset lives until the next bake, from a week for places to 90 days
  for routing, buildings, house numbers and speed limits. Fixing it in OpenStreetMap is the
  durable route.
- Overture publishes about monthly. In between, the weekly places rebake refreshes only the
  OpenStreetMap and AllThePlaces parts.
- A routing region that fails all its retries holds back the Update offer for every routing
  region, because the staged manifest is published only after a clean cycle and the next cycle
  is 90 days later. Until then the fix is by hand: dispatch the missing regions with
  `staging: true` and copy the staging manifest over the live one.
- Routing files have no patch, so a routing update is always the whole file and never automatic.
  A places or basemap patch exists only from the previous revision, so a phone two rebakes behind
  takes the whole file.
- A building overlay saved with an offline area and the world floor are never refreshed. The
  font zip is replaced only when an app update raises `GlyphPackStore.PACK_VERSION`.
- UK fuel prices depend on one person's archive repository while the government's site refuses
  GitHub's runners. If the archive stops, the runs warn, and two days later the app shows no UK
  prices until it starts again.
