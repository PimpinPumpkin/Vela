# 2. Data and rebakes

## What you see

Nothing, when it works. The map, the places, the speed limits, the stop signs and the camera
dataset all come from files this repository builds and hosts on GitHub releases. They are
rebuilt on a schedule, and a phone picks up the new build either by streaming it or by offering
you an update to a region you downloaded.

## Where the data comes from

One release per dataset, each with its own manifest the app reads, each independently rebuildable
without shipping an app update.

| Dataset | Release tag | Built from | Used for |
| --- | --- | --- | --- |
| Open places | `places-overlays` | Overture Places, AllThePlaces, OpenStreetMap | The businesses on the map, offline and online |
| Offline basemap | `basemap-tiles` | OpenStreetMap via planetiler | The map itself with no signal |
| Offline routing | `obf-regions` | OpenStreetMap | Turn-by-turn with no signal, and posted speed limits |
| Offline place search | `poi-packs` | OpenStreetMap | Searching places and addresses with no signal |
| Road features | `road-features` | OpenStreetMap | Traffic lights, stop signs, crossings, speed bumps, speed cameras |
| Surveillance cameras | `flock-cameras` | DeFlock, in OpenStreetMap | The camera layer and the avoid-cameras feature |
| Buildings | `building-overlays` | Microsoft building footprints (ODbL) | Filling OSM's suburban building gaps |
| Addresses | `address-overlays` | OpenStreetMap | House numbers and the offline geocoder |
| Speed limits | `maxspeed-overlays` | OpenStreetMap `maxspeed` | The posted limit where the routing graph has none |
| Voices and speech | `tts-runtime`, `asr-models` | Piper, Kokoro, sherpa-onnx | On-device speaking and listening |
| Map fonts | `map-fonts` | Roboto | Label glyphs for the offline style |

## How it is decided

### When each rebake runs

All times UTC. Every one of these can also be dispatched by hand from the Actions tab.

| What | When |
| --- | --- |
| Surveillance cameras | **Weekly**, Mondays 08:17 |
| Offline place search (`poi-packs`) | **Monthly**, the 3rd at 07:15 |
| Road features | **Monthly**, the 4th at 07:45 |
| Open places | **Monthly**, the 6th and the 7th at 05:00 (the catalog is split in two halves; the job matrix caps at 256) |
| Offline basemap | **Monthly**, the 9th and the 10th at 05:00 (same split) |
| Routing, buildings, addresses, speed limits | **Quarterly**, January / April / July / October, the 2nd at 04:00, dispatched together by the `quarterly-data-refresh` workflow |

The monthly places bake is timed to Overture, which publishes monthly; it always bakes against
the newest release in the bucket. The quarterly group is the slow-moving data: roads and address
points drift, but not week to week, and those bakes are the expensive ones.

A rebake **overwrites the current generation in place**: same asset names, same manifest. New
generations only fork when a file format changes, which is a deliberate cutover, never a cron.

Each bake job publishes its own archive and one job at the end publishes the manifest that lists
them, which makes that last job a single point of failure: it is serialized against other runs by
a concurrency group, and a job left waiting in such a group is cancelled outright when a newer one
joins it. So the merge does not fold the run's results into whatever the manifest said before. It
rebuilds the list from the archives actually sitting on the release and then applies the run's own
results on top. The manifest is a statement about what is published rather than a tally of which
jobs survived, so a merge that never ran costs nothing and the next one puts everything back.

### How your phone picks up a new build

**Streamed data** (the places layer while online) is read by HTTP range requests, so a rebuilt
archive is picked up as soon as the cache lets go of the old bytes. Forcing it is a matter of
clearing the map cache from Settings > Offline maps.

**Downloaded data** stays exactly as downloaded, which is the point of downloading it. Each
manifest carries a revision; the app records the revision an installed file came from, and
Settings > Offline maps marks a region whose manifest has moved on, so an update is one tap and
never a surprise download on a metered connection.

So a fix that lands in the open data reaches people in this order: the bake runs on its
schedule, streaming users see it within a day, and people who downloaded that region see the
update offered next time they open the offline screen.

### Retired data

`routing-graphs` (GraphHopper CH graphs, ~270 assets) is the previous generation of offline
routing, replaced by `obf-regions` on 2026-09-15. Nothing in the app reads it and the workflow
that built it is gone. The first launch after that update deletes the old graphs from the phone
and tells the user to download their regions again (`LegacyGraphs.purge`). The release itself is
still hosted, unreferenced.

## Limits

- **A bad row in a source dataset lives until the next bake.** For places that is up to a month;
  for routing, up to a quarter. Fixing it in OpenStreetMap is the durable route, and it is why
  OSM wins the coordinate in the places bake.
- **Overture publishes monthly**, so "rebake sooner" does not mean "fresher" for the fields that
  come from Overture. It does for the AllThePlaces and OSM halves.
- **The quarterly cadence is a cost decision.** A world routing bake is the single most expensive
  job in the repo. If a region's roads have changed materially, dispatching that one region is
  better than waiting for the quarter.
- **Nothing is versioned per user.** Everyone on a given day gets whatever the release currently
  holds, which is why rebakes overwrite in place rather than accumulating generations.
