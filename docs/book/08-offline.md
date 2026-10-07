# 8. Offline

## What you see

Settings > Offline maps has two ways to put data on the phone. **Download an area** puts a
frame over the map. Pan and pinch until it covers what you want, and the card under it shows
the size before anything downloads. **Entire states & countries** is the catalog. One tap on
a state, province or country downloads everything Vela needs there under one progress card,
and one message says whether the region is ready or only partly there.

With that data and no connection, the map draws streets, names and buildings, businesses show
as pins, search finds places and typed addresses, and a route can be planned and driven with
spoken turns and the posted speed limit. A globe with a slash and a gray "Offline" in the
search bar are the only sign. Tapping the globe checks the connection again.

Anything only Google or a live feed can answer is absent, with no error shown: traffic,
reviews, photos, current opening status, transit directions, live departures. A transit stop
shows the last board seen there, with the time it was fetched.

The page runs top to bottom: the area download and its settings, Keep viewed places for
offline, Storage, Downloaded, then the catalog as one alphabetical tree. The tree reads its
hierarchy from the parentheses in the catalog names (`regionTree`), and a parent row
downloads its pieces one after another. Sizes are installed sizes, and a region over 1 GB
(`CONFIRM_MB`) asks first.

## Where the data comes from

Every file is baked by this repository and hosted on GitHub releases
([chapter 2](02-data-and-rebakes.md)). [SPEC section 7](../../SPEC.md) lists every manifest,
rule and constant.

| Piece | On the phone | Format | Delaware |
| --- | --- | --- | --- |
| Routing | `obf/<id>.obf` | OsmAnd `.obf`, served raw | 8 MB |
| Place pack | `poipacks/<id>.db` | SQLite, zipped for the download | 23 MB (10 MB zipped) |
| Places archive | `places/<id>.pmtiles` | PMTiles of the open places layer | 58 MB |
| Basemap | `basemap/<id>.pmtiles` | PMTiles, OpenMapTiles schema | usually the largest piece |
| World floor | `basemap/world.pmtiles` | PMTiles, z0 to z7, no roads | about 11 MB, once |
| Glyph pack | `glyphs/` | the `map-fonts` zip, unpacked | about 200 MB on disk, once |

The folders sit under the storage root, internal or the SD card. The region that holds the
Bay Area installs at about 800 MB.

Road features (traffic lights, stop signs, crossings, speed humps, speed cameras) are one
small file per region in `files/roadfeatures`. The app fetches the file for the region it is
in the first time the map or a route needs it, while online. No download button does.

## How it is decided

### What a region download pulls

`MapViewModel.downloadRoutingGraph` runs the pieces in order under one card:

1. The routing file.
2. The place pack. A piece of a split country or state uses its parent's pack
   (`RegionPacks.packFor`). A shared parent pack over `AUTO_PARENT_MAX_MB` (600 MB zipped) is
   left for the row's Get places button, which names the parent and its size first.
3. The places archives, when Include places with downloads is on (the default).
4. The basemap archives, then the glyph pack and the world floor if they are missing.

One message ends the download: `mapvm_region_ready` when every piece arrived,
`mapvm_region_incomplete` when one failed, which tells the user to tap Update. Nothing says
"ready" before the map is in, so nobody turns Wi-Fi off with places drawn on a gray map. A
piece the catalog has nothing published for counts as complete.

The places and basemap catalogs are cut differently from the routing catalog. `archivesFor`
takes the archive with the region's own id, else every archive whose box center lies inside
the region, else the smallest archive covering the region's center. The same-id rule comes
first because the center rule alone also pulls parent and neighbor archives.

### What a picked area pulls

`downloadPickedArea` saves the framed box with MapLibre's own tile download, into MapLibre's
database, from two zoom levels out from the framing zoom through 14, the vector tiles' last.
The area has street detail whatever zoom it was framed at.

The size on the card is the tile count times the region's tile density, measured from its
basemap archive, else `AREA_TILE_KB` (110). Over `AREA_MAX_TILES` (60,000, about half of a
large US state) the card says to zoom in or take the whole region.

Tiles alone give a map with no routing or search. The card offers one of two additions:

- The region's grid cells the frame touches. This is the default where cells are baked.
- The whole region: the download above plus the region's building overlay
  (`overlays/<id>.pmtiles`).

Where no place pack covers the area, the whole-region choice fetches places and addresses
from OpenStreetMap live, padded by `GEOCODE_PAD_DEG` (0.09 degrees, about 10 km each way).

### Grid cells

Cells are 0.5 degree tiles of one global grid, clipped to the region. Each is a zip of its
routing file, its place pack and its slice of the region's places tiles
([SPEC 7.6](../../SPEC.md)). `CellStore` installs the parts under the cell's id into the
stores a region download fills, so routing, search and the places layer read them with no
code of their own.

Cells download one after another under the region card, and a cancel keeps the ones already
down. Downloaded lists them as one row per region. Downloading the whole region afterwards
removes its cells, or search would list each place twice. An update pulls the zip again.

Delaware is 10 cells and 37.7 MB against 75.6 MB for the whole region. Four trips across cell
edges route exactly as over the region file (`ObfCellsProbeTest`). In the region that holds
the Bay Area the largest cell is 93 MB.

### Which region a point is in

A region's box is a rectangle and the region is not, so a neighbor's box often covers a point
its data does not reach. Vela ships the real boundary of every catalog region
(`assets/region_polys.json`, Geofabrik's polygons simplified to a few kilometers, 340 KB).
`RoutingRegion.covers` asks `RegionPolys` first and uses the box only for a region with no
polygon. Among covering regions the smallest box wins.

Files already on the phone are indexed by box. That is why the router and the basemap pick
below run a second test against the data itself.

### Offline, or only Google off

```
offlineNow = the latched offline flag || no network by either test
googleOff  = offlineNow || Settings > Privacy > "Use Vela without Google"
```

Two tests say whether there is a network. Android's is a default network with internet
capability. Vela's own is `NetHealth`: an HTTP response in the last `FRESH_MS` (15 s) with no
unreachable host since. When Android reports no network, the flag latches only if, 3 seconds
later and once Vela's own traffic has gone stale, both tests still say no, so a Wi-Fi to
cellular handoff does not flash the indicator. The flag clears at once when the network
returns or any request gets a response.

The basemap pick is stricter: a network that never validated, such as a car Wi-Fi with no
data, also counts as offline.

Google off still uses the network for everything that is not Google: streamed tiles, the
open routers and geocoder, Transitous boards. Offline uses nothing.

### The map with no signal

Online, the style always streams. `LocalBasemapTiles`, an interceptor in MapLibre's HTTP
client, answers a basemap tile request from a downloaded region file when the tile's corners,
padded by `EDGE_PAD_DEG` (0.05 degrees), lie inside that region's boundary and the file is a
full-depth bake (`FULL_MAP_ZOOM`, 14). The style never changes, so nothing reloads at the edge
of the data and no data is spent inside it.

When streaming cannot work, `BasemapTileStore.installedFor` picks one archive to mount as the
map's source:

- Candidates are the installed archives whose box holds the view's center or a corner,
  smallest first. The pick asks each file whether its tile carries the road layer
  (`PmtilesReader.hasRoads` at `COVERAGE_PROBE_Z`, 12), because every archive has water and
  land cover tiles across its whole box.
- An archive is mounted, and the mounted one kept, while its roads reach the center tile, one
  of the eight around it, or a corner of the screen. Requiring every corner dropped a whole
  downloaded state to the world floor when one corner sat over a lake.
- Where nothing qualifies the world floor draws coastlines, borders and place names at low
  zoom.
- A swap reloads the whole style, so swaps are at least `BASEMAP_SWAP_COOLDOWN_MS` (2 s)
  apart.

Labels need the glyph pack on the phone, because a labeled tile never completes while a
remote font host fails to answer.

The places layer (`PmtilesRegionStore.sourcesFor`) mounts every installed archive whose box
touches the view, nearest first, at most `MAX_MOUNTED` (8), minus any nested inside another,
which would draw the overlap twice.

### Routing with no signal

Online the on-phone router is a fallback, and [chapter 5](05-routing.md) gives the order.
With no connection it is the only router. `ObfRouteEngine` runs OsmAnd's router over the
installed `.obf` files.

- A trip may cross files. Every installed file whose box meets the trip's padded box goes to
  the router together. Both ends must fall inside an installed box and within
  `ENDPOINT_SNAP_M` (2 km) of a road in the data, or there is no offline route.
- Avoid tolls, highways and ferries are applied from road attributes at calculation time.
  Walking and cycling use the same file.
- Region files carry OsmAnd's highway hierarchy for car and bicycle. With it, 148 km across
  Delaware takes 0.7 s and 71 MB on a Pixel 4a. Without it the plain search runs inside
  `MEMORY_MB` (256) and fails somewhere between 60 and 150 km on a dense network.
- One route, no alternates, no traffic. The arrival time is free-flow.

The same files answer the posted speed limit under the puck (`currentRoadLimit`), from the
nearest road within `LIMIT_SNAP_M` (25 m). A derestricted or untagged road reads as blank.

### Searching with no signal

With no connection a typed query never goes to Google. It reads three things on the phone,
and [chapter 6](06-search.md) has the matching and ranking rules:

- The installed place packs, plus the small index an area save filled where no pack existed:
  place names and categories (`OfflinePoiStore`).
- The downloaded places archives, the ones the map draws, read in rings of tiles around the
  search point out to `MAX_RINGS` (12, about 3 km) (`PlacesArchiveSearch`).
- For text that looks like an address, `OfflineAddressStore.geocode`, in four layers: the
  exact house number on the street, a position interpolated between the nearest mapped
  numbers, any mapped house on the street, then the nearest point on the street's centerline.

A pack is one SQLite file per region. Street names are stored once and the millions of
address and centerline rows point at them by number, so a whole-state pack answers as fast as
a small one.

OpenStreetMap tags few businesses with an address, so an offline row borrows one: the nearest
mapped house within `REV_ADDR_M` (60 m), else the nearest street within `REV_STREET_M`
(150 m). The city, state and ZIP come from the neighbors: `localityNear` takes the most common
answer among the nearest `LOCALITY_VOTERS` (7) pack places within about 650 m. It only
completes a bare street line, or extends a bare place name the answer starts with ("Davis"
becomes "Davis, CA 95616"), so it never swaps one town for another.

### Tapping a place with no signal

A pin from the open places layer carries its category, address, phone, website and hours in
the tile, and the sheet shows them. A pin tapped before, online, also opens the Google
listing it resolved to then (500 links in `open_place_links.json`). The links are dropped on
a new app build and when a region's archive is updated, because a rebake can move the rows.

With Keep viewed places for offline on (the default), a place opened online is saved with what
was loaded for it (`PlaceCache`, 400 places for 30 days), and offline the sheet fills from
that copy. Nothing is saved while Use Vela without Google is on. Viewed Street View panoramas
are kept the same way, up to 150 MB.

A transit stop shows the last board fetched there (`TransitBoardCache`, the newest 48 stops)
with its fetch time. Stop icons come from the last 24 areas viewed online
(`TransitStopCache`). [Chapter 9](09-transit.md) has the details.

### Storage, the SD card and deleting

The Storage row "Saved areas & map cache" counts MapLibre's database, the building overlays,
the basemap archives, the glyph pack and the road features. "Offline places" counts the place
packs and the places archives.

MapLibre keeps saved areas and the browsing cache in one SQLite file, and deleting rows does
not shrink it. Every saved-area delete and every Clear map cache ends by packing the database
(`OfflineMaps.packDatabase`).

With an SD card mounted, the page offers to store downloads on it (`StorageLocation`). The
move carries the folders in `FOLDERS` and MapLibre's database, checks each file's length, and
deletes the source only when every file has copied. Voices, speech models, road features and
caches stay internal. With the card chosen and missing, the app uses internal storage and the
page says so.

Deleting a region removes its routing file, its cells, its places and basemap archives, and
its place pack unless another installed piece shares it. Delete all offline data removes
every saved area and installed file, the saved places and Street View, and the glyph pack. It
then sweeps the store folders for anything left, such as an archive whose id left the
catalog, clears the browsing cache and packs the database. Voices and speech models stay.

### Updates and patches

Every manifest row carries a revision, and the phone records the revision each file came
from. Opening Offline maps compares them (`refreshRegionUpdates`) and puts Update on a region
whose routing, places or map is behind. A piece that never arrived counts too, so Update
finishes a download that was cut short. One tap (`updateRegion`) refreshes the place pack,
the places archives, the basemap archives, then the routing file.

The place pack takes a row-level delta when the manifest has one from exactly the installed
revision, applied in one transaction and checked against the manifest's row counts before it
commits. A places or basemap archive takes a patch on the same condition (`PmtilesPatch`).
Anything else, and the routing file always, is downloaded whole beside the installed file
and replaces it only when complete.

A rebaked archive changes little. Kentucky over seven days moved 1.3% of its tiles, and the
patch was 4.4 MB against a 183 MB archive. The patch is applied in place, with the header
written last, so an interrupted apply leaves the old archive intact. A fingerprint over every
tile must then equal that of a fresh download, or the region is downloaded whole. The tiles a
patch replaced stay behind as dead bytes. Past a fifth of the file `PmtilesCompact` rewrites
the archive without them, with no network. [SPEC 7.3](../../SPEC.md) has the format.

Update downloaded regions sets what happens unasked: Never on its own, On Wi-Fi (the
default), or On Wi-Fi and mobile data, where "Wi-Fi" means a network the system calls
unmetered. On an automatic setting `maybeAutoPatch` runs a minute after start, every 3 hours
and when a working network appears, at most once in 20 hours. It applies every published
patch that fits an installed archive or place pack and pulls newer grid cells. It never
downloads a region whole and it skips a drive in progress.

The places and basemap catalogs are cached for an hour (`MANIFEST_TTL_MS`), so a process that
lives for days still sees a new revision. The routing catalog is also written to disk on
every fetch (`RegionCatalog`), so installed regions still list with no signal.

## Limits

- Transit directions need Google's page or the Transitous planner, so there are none
  offline.
- The highway hierarchy is not used for a trip across two region files, a trip over grid
  cells, a walk, or a file downloaded before it was baked. Those use the plain search, which
  fails on long routes. Updating the region fixes the last case.
- Offline steps have no lane diagram. Lane guidance comes from the online router.
- Road features need an online visit, so a region never looked at or driven in while online
  has no traffic lights or stop signs. The list that maps a point to its file is held in
  memory only, so an app started with no connection draws none even with the file on the
  phone. Bundling the file with the region download would fix both.
- Address interpolation takes the nearest house numbers on any street of that name in the
  pack, so where several towns share a street name the estimate can land in the wrong town.
- A borrowed city and ZIP is a vote. A place just across a town or ZIP line from most of its
  neighbors can be given theirs.
- The Storage rows do not count the small place and address indexes an area save fills where
  no pack exists, and Delete all offline data leaves them.
- Deleting a region leaves its building overlay and road features. Only Delete all offline
  data removes those.
- House numbers from the address overlay are streamed only, so they are missing offline.
- Only patches are automatic. A region's places are rebaked about once a week, and an archive
  with no patch from its installed revision waits for a tap on Update.
- A phone that has never fetched the catalog shows an empty catalog offline.
