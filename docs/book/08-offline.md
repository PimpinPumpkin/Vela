# 8. Offline

## What you see

Settings > Offline maps has two ways in. **Download an area** is Google's picker (issue #609,
2026-09-25): it drops you on the map with a frame over it (`MapUiState.areaPicking`, the insets
`MapViewModel.AREA_FRAME_*`, shared by the overlay and the bounds math), and panning and pinching
choose what the frame covers, from a neighborhood to half a state. Whatever the framing zoom, the
area is saved at full street detail: every zoom from two above the framing one down to the vector
tiles' last (14). The card under the frame prices it live: tiles counted per zoom, times the
region's own density where Vela has its map archive (the archive's size over its box's tiles; the
Woodland to Dixon frame, mostly farmland, estimated 5 MB and measured 5.9 MB from 509 sampled
tiles at about 12 KB), else `AREA_TILE_KB` (110 KB). Over `AREA_MAX_TILES` (60,000, about half of a
large US state) it says to zoom in or take the whole region. The region around the frame (routing,
place pack, places file, the region's map and building outlines) only comes whole and is a
checkbox on the same card. Before 2026-09-25 the button lived in Settings, saved whatever the map
last showed at only a few zoom levels around the current one (so a zoomed-out view was saved with no
street detail) and pulled the whole region silently. **Entire states &
countries** is the catalog: one tap on a state, a province or a country downloads everything
Vela needs to work there with no signal. One card on the map follows the whole download piece by
piece, and one message at the end says whether the region is ready or only partly there.

With a region on the phone and no connection, the map still draws streets, names and buildings,
the businesses still show as pins, search still finds places and typed addresses, a route can
still be planned and driven with spoken turns and the posted speed limit. The only sign that
you are offline is a small globe with a slash through it and a grayed "Offline" in the search
bar. There is no banner, because nothing is broken.

What you do not get is anything that only Google or a live feed can answer: traffic, reviews,
photos, fresh opening status, transit directions, live departures. Those do not show an error
either. They simply are not there, and this chapter lists which ones.

## Where the data comes from

Everything a download pulls is baked by this repository and hosted on GitHub releases, one
release per dataset. How and when each one is rebuilt is [chapter 2](02-data-and-rebakes.md);
this chapter is what lands on the phone and what the phone does with it.

| Piece | On the phone | Format | Example size |
| --- | --- | --- | --- |
| Routing | `files/obf/<id>.obf` | OsmAnd `.obf`, served raw, so the download is the install | Delaware 20 MB, Luxembourg 39 MB, Saarland 8 MB |
| Place pack | `files/poipacks/<id>.db` | SQLite (POIs, addresses, street names), zipped for the download | installed is about 2.35 times the zip when the manifest does not say |
| Places archive | `files/places/<id>.pmtiles` | PMTiles, the open places layer | Kentucky 183 MB; about half a gigabyte for a big US state |
| Basemap | `files/basemap/<id>.pmtiles` | PMTiles, OpenMapTiles schema from planetiler | Saarland 33 MB at full detail (z14) |
| World floor | `files/basemap/world.pmtiles` | PMTiles, z0 to z7, no roads | about 11 MB, once |
| Glyph pack | `files/glyphs/` | the `map-fonts` zip, unpacked | about 200 MB on disk, once |
| Sprite | `files/sprites/` | copied out of the APK | about 230 KB, once |

Put together, a Northern California download installs about 800 MB. Before the size shown on
the row included the places and map archives, the same row read 126 MB, which was routing and
search only.

Two more things arrive by other paths:

- **Road features** (traffic lights, stop signs, crossings, speed humps, speed cameras) are one
  small file per region, a few hundred KB for a US state. The app pulls the file for the region
  it is in the first time it needs it, while online. The download button does not fetch it.
- **A saved area** (the "area you're viewing" button) is MapLibre's own tile download, stored in
  MapLibre's database, not in the folders above. It also pulls the building overlay for the area.

## How it is decided

### What one tap pulls

A catalog row is **one download under one progress card** (`MapViewModel.downloadRoutingGraph`),
each piece starting when the one before it finishes:

1. the routing `.obf` ("Downloading <region> routing");
2. then the place pack with the same region id ("Saving <region> places for offline search");
3. then the places archives, if **Include places with downloads** is on (it is by default):
   step 1 of `fetchRegionArchives`, "Saving <region> places for the map";
4. then the basemap archives, step 2, "Downloading the <region> map", and once those are in, the
   glyph pack and the world floor.

The card names the piece it is on (`regionFileStep` is 1 for the places file, 2 for the map) and
shows that piece's own percent, which starts again at zero for each file. The place pack runs
chained (`downloadPoiPack(chained = true)`), so it neither clears the card nor announces itself.
Nothing says "ready" until the last piece is done, and then exactly one line does:

- `mapvm_region_ready`: "<region> is downloaded: map, routes and places work offline", when every
  piece arrived;
- `mapvm_region_incomplete`: "<region> is only partly downloaded. Connect and tap Update in
  Settings, Offline maps to finish it", when any piece failed.

A piece the catalog has nothing published for counts as complete (there is nothing to finish), and
so do the places archives when the include setting is off. A cancel stops at the next file and
says nothing. If the routing file fails, the chain stops there with the routing-failed message. A
parent row ("Germany", "United States") queues its pieces and downloads them one after another;
cancel clears the queue.

**Why one card.** Until 2026-09-23 only the routing file and the pack ran under the card. The pack
ended with its own "places are searchable" line and the card went away, while the places file and
the map, the biggest piece, carried on as separate silent jobs. A user read that line as done,
turned Wi-Fi off, and had searchable places drawn on a gray map. Now the card stays up until the
map is in, and the one message at the end is the only thing that says the region is ready.

The places and basemap catalogs are cut differently from the routing catalog, so the chain has to
decide which archives "belong" to a region (`archivesFor`): the archive with the region's own id
if there is one, else every archive whose box center lies inside the region, else the smallest
archive covering the region's center. The same-id rule comes first because the center rule alone
once turned an 800 MB Northern California download into 1.5 GB, pulling the whole-state places
file, a city test bake and a neighboring state's places and map.

The size on the row and in the confirm dialog is the **installed** size of all of it: the routing
file (`installedMb`, else the download size, which for an `.obf` is the same number), plus the
pack (`installedMb`, else the zip times 2.35), plus the places and basemap archives the chain will
pull. Anything over a gigabyte asks first:

```
CONFIRM_MB = 1024
```

**Saving the area you're viewing** stores MapLibre tiles from one zoom level above the current
one to three below it, capped at z16, up to a tile budget:

```
TILE_LIMIT = 50_000   // tiles; past it the save stops and says the area is too large
```

and then treats the region that contains the area as if you had tapped its row: routing, pack,
places, basemap, plus the building overlay. Only where no place pack covers the area does it fall
back to fetching places and addresses from OpenStreetMap live, padded around the center:

```
GEOCODE_PAD_DEG = 0.09   // about 10 km of latitude either side, so addresses across the metro resolve
```

### Which region a point is in

"The smallest box that covers the point" is wrong at borders, because a region's box is a
rectangle and the region is not. Vela ships the real boundary of every catalog region (the
polygon Geofabrik cut the extract with, simplified to a few kilometers, about 340 KB for all
458 catalog regions) and asks it first; the box is the fallback only for a region with no
polygon. A box that spans the whole globe in longitude is an extract crossing the antimeridian,
and it never covers anything by itself:

```
WORLD_SPAN = 350.0   // degrees of longitude: this wide is the antimeridian, not a region
WORLD_BAND = 120.0   // unless it is also this tall, which is the world archive on purpose
```

### Offline, or just Google off

Two switches decide what the app asks for, and they are not the same switch.

```
offlineNow = the latched offline flag || the system reports no internet
googleOff  = offlineNow || Settings > Privacy > "Use Vela without Google"
```

The offline flag latches only if the connection is **still** gone 3 seconds later, so a Wi-Fi to
cellular handoff does not flash the indicator. Coming back online clears it at once, and so does
any live search that succeeds.

| | Offline | Google off, online |
| --- | --- | --- |
| Map tiles | downloaded basemap, else the world floor | streamed as usual |
| Search | on-phone packs only | the OpenStreetMap geocoder plus the packs |
| Routing | the on-phone `.obf` | the open router only, no traffic, no Google fallback |
| Reviews, photos, details, tap lookup | skipped | skipped |
| Departure boards | last board seen at that stop | the open feeds, not Google's page |

So Google off still uses the network for everything that is not Google. Offline uses nothing.

### Routing with no signal

The on-phone router is the **fallback**, not the default: online routing comes from the open
router, and the `.obf` answers when that comes back empty, which with no connection is always.
[Chapter 5](05-routing.md) has the handoff and the router itself; the parts that decide what
works offline are these.

- **A trip may cross files.** Every installed region that intersects the trip's padded box is
  handed to the router together, so a drive from one downloaded state into the next works. Both
  endpoints must fall inside the installed files, or there is no offline route at all.
- **Avoids work.** Avoid tolls, highways and ferries are applied from the road attributes at
  calculation time, and walking and cycling come from the same file.
- **Long routes fail.** Past `MEMORY_MB = 256` the router throws rather than slowing down, and on a
  dense network that happens somewhere between 60 and 150 km (the measurements are in chapter 5).
  The budget cannot be raised on a phone.
- **One route, no alternates, no traffic.** The arrival time is free-flow.

The same file answers the **posted speed limit** under the puck while driving, so the badge keeps
working with no signal:

```
LIMIT_SNAP_M    = 25.0   // a fix farther than this from any road is off the network
LIMIT_MEMORY_MB = 32     // the lookup only holds the tiles around the puck
```

A derestricted road reads as blank, never as a number.

### Searching with no signal

With no connection a typed query never goes to Google. It goes to the installed place packs (and
the small index an area save filled where no pack existed): place names and categories, with
category words expanded to the OpenStreetMap tags actually stored, plus the four-layer address
geocoder when the text looks like an address. The rules and constants are in
[chapter 6](06-search.md#offline). What this chapter adds is what the packs hold.

A pack is one SQLite file per region with four tables, and the schema is normalized because a
naive one did not fit: street names live once in `streetname`, and the millions of `addr` and
`streetpt` rows point at them by number.

| Table | Columns | Used for |
| --- | --- | --- |
| `poi` | id, name, lat, lng, category, address, phone, website, hours | place search, the offline sheet |
| `streetname` | sid, street, street_norm | matching a typed street once per query |
| `addr` | hn, sid, city, lat, lng | house numbers: exact, interpolated, nearest on the street |
| `streetpt` | sid, lat, lng | the centerline fallback where a street has no numbers |

A query matches street names first (a scan of tens of thousands of rows) and then reaches the big
tables through their indexes, so a whole-state pack answers as fast as a small one. OpenStreetMap
tags few businesses with an address, so offline rows borrow one from the nearest mapped house:

```
REV_ADDR_M   = 60.0    // a mapped house this close is the place's address
REV_STREET_M = 150.0   // else "on <street>" for a street this close
```

**The city, state and ZIP come from the neighbors.** OpenStreetMap often tags a place with only its
number and street, so an offline row used to read "123 Main St" and nothing else. The places
around it usually do carry the rest, so `OfflineAddressStore.localityNear` asks them: it reads the
pack places in a box around the point whose address has a locality after the street line, keeps
the ones with a postcode if there are any, takes the nearest seven, and returns the most common
answer, the nearest one breaking a tie.

```
LOCALITY_BOX_DEG  = 0.006   // about 650 m either way: the box the vote reads
LOCALITY_VOTERS   = 7       // nearest addresses that vote
LOCALITY_CELL_DEG = 0.005   // answers cached per cell of about 550 m, so a list costs a scan or two
OFFLINE_ADDR_FILL = 20      // offline search rows filled; the ones on screen
```

`completeAddress` only touches an address that needs it (`needsLocality`): a bare street line, or
a street line plus a place name with no postcode. `withLocality` then appends the answer to a bare
street line, or replaces a bare trailing place name **only when the answer starts with that same
name** ("Davis" becomes "Davis, CA 95616"). It never swaps one town for another: a row that says
it is in one town keeps that town even if every neighbor votes for the next one. Nothing nearby with
a locality means the address stays as it was. It runs on the first `OFFLINE_ADDR_FILL` rows of an
offline search and on the sheet of a place selected offline, after the street line itself has been
filled from the nearest house. It needs no rebake; the places layer's own tiles get the same fill at
bake time ([chapter 1](01-places.md#which-places-exist-in-a-tile-and-at-what-zoom)).

### Tapping a place with no signal

A pin from the open places layer carries its own data in the tile, so offline the sheet shows its
category, address, phone, website and opening hours straight from the file. Online the tap is
matched to a Google listing, and that link is remembered on the phone:

```
openPlaceCache = 500 entries, persisted to open_place_links.json
```

Offline, a pin tapped before shows the listing it resolved to last time. The remembered links are
dropped when the app updates to a new build, and when a region's places archive is updated or
downloaded again, because a link made by an older rule, or keyed on a row the rebake has moved,
would keep opening the wrong listing. The list of places found permanently closed is not dropped;
that is a correction, not a cache.

A basemap label keeps its name and nothing more. A transit stop shows the last board seen there
(the newest 48 stops are kept), with the time it was fetched so nobody reads yesterday's 8:05 as
today's, and the stop icons come from the last 24 areas you looked at online; the details are in
[chapter 9](09-transit.md#offline-copies).

Google's business dots, where that source is in use, come back from a disk cache of the areas
you browsed (32 areas of 200 places, kept 14 days). The fetch that fills it does not run at all
offline.

### The map at a region's edge

The basemap pick runs on every camera idle and chooses at most one archive to draw from. A box
covering the view is not enough, for the same reason as above, so the pick **asks the file**:
does the tile under the view carry the road layer?

```
COVERAGE_PROBE_Z = 12   // tiles about ten kilometers across
FULL_MAP_ZOOM    = 14   // an archive baked shallower than this is used only offline
```

It asks about roads and not about tiles because planetiler's water, land cover and boundaries are
global, so every archive has tiles across its whole box and out to sea. Answers are memoized per
archive and tile, and "cannot tell" is never stored as "no".

Switching archives reloads the whole map style, which is a visible freeze, so the rules differ by
direction and by connection:

- **Online**, an archive is in use only while the tile at the center, the eight around it and the
  four corners of the screen all carry its roads. The moment a border comes on screen, the view
  streams instead, and it keeps streaming until the border has left the screen. One reload each
  way, none while you pan along the border.
- **Offline** there is nothing to stream in its place, so the archive in use is **kept** while its
  roads reach the center, the ring or any corner (`keepMounted`). Before this rule, panning from
  Pennsylvania across the New York line with only Pennsylvania installed blanked the whole screen,
  the Pennsylvania half included, for twelve seconds (issue #552).
  The same loose rule picks an archive to mount in the first place: offline, any installed
  archive whose roads reach the view beats the world floor. Until 2026-09-26 a fresh offline mount
  still had to pass the online test, so a screen with one corner over a lake mounted the world
  floor instead and never recovered (reproduced on a lakeshore downtown with the whole state
  installed: places on an empty map; after the fix, the state's streets).
- Either way, swaps are at least `BASEMAP_SWAP_COOLDOWN_MS = 2_000` apart, and a newer camera idle
  cancels a pending one.
- Where nothing installed holds the map, the world floor draws: coastlines, water, borders and
  place names at low zoom, so losing the signal away from a download is a coarse map, not a blank
  one.

Labels need the glyph pack. An archive installed without it (an interrupted first download)
installs the pack the next time there is a connection.

The places layer picks the same way but more simply: exactly one source, the smallest installed
archive covering the center, else the smallest streamed one. One, because nested archives drew
every business in the overlap twice.

### Storage, and giving the space back

The Offline maps page runs top to bottom: **This area** (save the view, include places, update
policy), **Storage**, **Downloaded** (every saved area and every installed region, each with its
own controls), then **Entire states & countries** as one alphabetical tree. The tree reads the
hierarchy out of the names' parentheticals: "Bayern (Germany)" sits under Germany, and "(state)",
"(US)" and "(California)" all fold under the United States. The region you are in is marked and
its parent starts open. The catalog is a lazy list with its own scroll, the height of the screen
less 160 dp, because composing every row at once cost a 430 ms frame on a Pixel 4a.

The Storage rows are measured from the folders:

| Row | Counts |
| --- | --- |
| Saved areas & map cache | MapLibre's database, the building and address overlays, the basemap archives (the world floor included), the label glyph pack, the road features |
| Offline routing | `files/obf` |
| Offline places | the place packs and the places archives |
| Voices & speech models | managed on the Voice page |

MapLibre keeps saved areas and the browsing cache in **one SQLite file**, and deleting rows does
not shrink it. So every saved-area delete and every **Clear map cache** ends by packing the
database (a VACUUM). Without it a phone that had saved and deleted a few large areas reported 5 GB
of map data with nothing listed (issue #601).

Deleting a region removes its routing file, its pack, and every places and basemap archive with
its id or its center inside it. **Delete all offline data** removes every saved area and every
installed file the stores know about, then sweeps their folders (`obf`, `poipacks`, `places`,
`basemap`, `overlays`, `roadfeatures`, and the retired `graphs`) for anything left, such as an
archive whose id vanished when a country was re-split. It also deletes the label glyph pack the
offline basemap needs (about 200 MB), which comes back with the next basemap download. It clears the browsing cache and packs the
database. Voices and speech models stay.

### Updates: patches and compaction

Every manifest row carries a revision (`YYYYMMDD`), and the phone records the revision each file
came from. Opening Offline maps compares the two (`refreshRegionUpdates`) and puts **Update** on a
region whose routing, places or map has moved on. One tap (`updateRegion`) refreshes, in order:
the place pack, the places archives, the basemap archives, then the routing file.

**A piece that never arrived counts as an update too.** For an installed region, a places archive
the region should have and does not (checked only while Include places with downloads is on) marks
it "places", and a missing basemap archive marks it "map", exactly as a newer revision would.
Update then fetches the missing archives (`fetchRegionArchives`, the same steps as the first
download) after refreshing any that are installed. Without this, a download cut short before its
map (Wi-Fi off, the app killed) left a gray map and no button anywhere that would fetch it; the
incomplete message tells the user to tap exactly this.

- **The place pack** takes a row-level delta when the manifest has one for exactly the installed
  revision: a small SQLite of deleted and inserted rows, applied in one transaction and checked
  against the manifest's row count for every table before it commits. Otherwise the pack is
  downloaded whole.
- **The routing file** is always downloaded whole, next to the old one, which is replaced only
  when the new one is complete.
- **The places and basemap archives** can take a patch, when the setting allows it (below).

A rebaked archive changes very little: Kentucky over seven days moved 1.3% of its tiles, and the
patch was 4.4 MB against a 183 MB archive. The patch is applied **in place**: the changed tiles
are appended, then the rebuilt directory, then the 127-byte header last. Until that last write
the old header still describes the old archive, so an interrupted apply leaves it intact, and the
cost in free space is the patch, not a second copy. The result is then proven: a fingerprint over
every tile has to equal the fingerprint of a fresh download of that revision, or the region is
downloaded whole.

A patch leaves the tiles it replaced behind, unreferenced. Those bytes are counted per archive,
and the phone rewrites the archive without them when they grow, which costs a pass over the file
and nothing on the network:

```
DEAD_LIMIT_DIVISOR = 5   // compact once dead bytes pass a fifth of the file
                         // past half, and compaction still not keeping up, take the region whole
```

Compaction needs room for a second copy while it runs; without it, it is refused and the archive
stays correct, just larger.

The policy is the user's: **Update downloaded regions** is Never on its own, On Wi-Fi (the
default since 2026-09-25), or On Wi-Fi and mobile data, where "Wi-Fi" means the system says the network is not
metered. It was off by default until then because a feature that rewrites an installed archive was not
to switch itself on before someone had watched it work. That happened on 2026-09-19 a
Pixel 9 took a published patch end to end, and the same run found and fixed two bugs (the catalog
cached for the life of the process, so no update was ever offered, and dead bytes never bounded).
The default became On Wi-Fi on 2026-09-25; anyone who had picked Never keeps it. On either
Wi-Fi setting the app checks a minute after start, at
most once in 20 hours, and applies every published patch that fits an installed places or
basemap archive or place pack, on its own and quietly; it never downloads a region whole by
itself, and it skips a drive in progress.

The catalogs are cached so a pan does not refetch them, but the cache expires, so a process that
lives for days still sees a new revision:

```
MANIFEST_TTL_MS = 3_600_000   // an hour
MISS_MEMO_MS    =   600_000   // an unreachable manifest is not retried for ten minutes
```

**The routing catalog is kept on disk** (`app/offline/RegionCatalog.kt`). It is the list the
Offline maps page is built from, installed regions included, and every successful fetch writes it
to `files/catalog-<hash>.json` (the hash is of the manifest URL). When the fetch fails, the page
reads that copy instead. Before 2026-09-23 a failed fetch meant an empty list, so a user who had
just downloaded a state and opened the page with no signal read it as "nothing is downloaded". A
phone that has never fetched the catalog still gets an empty page offline.

## Limits

- **Offline routing is a city and metro feature.** Long routes fail on memory, not just time,
  and the fix is OsmAnd's precomputed hierarchy, which the bake does not generate. Until it does,
  an intercity drive with no signal is not offered.
- **No traffic, reviews, photos, fresh hours or transit directions offline.** The arrival time of
  an offline route is free-flow. Transit directions stay with Google on purpose, so with no signal
  there are none. A departure board is only the last one you saw, with its time on it.
- **Road features need one online visit.** The file is kept once fetched, but a region you
  downloaded and never looked at or drove in while online has no traffic lights or stop signs on
  the drive.
- **The address interpolation does not know which town it is in.** A state pack can hold a street
  of the same name in several towns, and the interpolation layer brackets the typed number with
  the nearest numbers from any of them. The exact-number layer, which runs first, is unaffected.
- **A borrowed locality is a vote, not a lookup.** A place with no city or ZIP of its own takes the
  most common one within about 650 m, so a place just across a town or ZIP line from most of its
  neighbors can be given theirs. A place that names its own town is never changed.
- **The Storage rows do not count everything.** The small place and address indexes an area save
  fills where no pack exists live in the app's databases, not the folders above, so they are in
  none of the rows, and Delete all offline data leaves them in place. The sprite copy (about
  230 KB) is uncounted too.
- **Only patches are automatic.** A region whose archive has no patch from its installed
  revision (a bake that changed too much, or a skipped revision) waits for a tap on Update, which
  downloads it whole over the installed copy; the old copy stays until the new one is complete.
- **Places archives change often.** Besides the monthly bake, a seventh of the places catalog is
  rebaked every night, so a downloaded region's places show Update roughly weekly.
- **A region's edge is still a seam.** The rules above remove the flicker and the blank screen,
  but offline, past the last installed archive, the view drops to the world floor's low-zoom map.
