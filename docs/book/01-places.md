# 1. Places on the map

## What you see

Pan the map and businesses appear: an icon with a name for the ones worth naming, a colored
dot for the rest, more of them as you zoom in. Tap one and a sheet opens with its hours,
reviews, photos and phone.

The pins are open data baked in this repository. The sheet is Google, asked when you tap.
Settings > Places > "Place icons on the map" picks what draws the pins:

- Vela data (the default): the baked places archive. Browsing does not reach Google where an
  archive covers the view, and a downloaded region works offline.
- Both: the archive, plus one Google fan-out per settled view.
- Google: Google's answer for the view, refetched on every pan. Nothing draws offline.

Where no archive covers the view, every mode draws Google's places. The default is
`Calibration.defaultPlacesSource = "open"`, and a remote change to it reaches only people who
never used the picker. "Show places" off hides every pin. "Parks, schools and civic places" off
hides the park, education and civic groups.

### Tapping a place

The sheet opens at once with what the map knows: name, kind, and for an archive pin the
address, phone, website and often hours. A gray line under the name gives the row's source:
"From Overture · checking Google" while the lookup runs, "From AllThePlaces (&lt;spider&gt;) ·
not matched on Google" when nothing matched, "From OpenStreetMap" when Google was not asked.
The spider is AllThePlaces' scraper for that chain. An OSM row's line links to its node, way or
relation, so a wrong place can be fixed at its source. "Hide website & external links"
removes the link.

While Google is asked, the rating, review tabs and photos pulse as gray bars. The listing then
replaces the sheet in place.

### Route preview

While the route chooser is open the map shows no places: the archive's icons and dots, the
basemap's points, transit icons, stop badges and saved pins all hide. The destination keeps its
pin. During a drive the archive shows fuel only ([chapter 11](11-drive-chrome.md)).

## Where the data comes from

**Overture Maps Places** is the base: an open business dataset from Meta, Microsoft and others,
read from its public S3 bucket at bake time, newest release unless one is pinned. Its rows come
mostly from Meta and Bing, so a business with no Facebook page and no Bing entry is absent,
chains included.

**AllThePlaces** (CC0) fills that gap. It scrapes each chain's store locator weekly and
publishes the world as one PMTiles file. The bake takes the region's z15 tiles from the newest
run (`data.alltheplaces.xyz/runs/latest.json`, unless `ATP_RUN` pins one). Locator rows carry
OSM-syntax `opening_hours`, which Overture lacks.

**OpenStreetMap**, from the region's Geofabrik extract, does three jobs. Named business nodes
are a third source, because OSM is the one dataset anyone can correct. An OSM node is the first
choice for a place's coordinate. And OSM supplies the landmarks: parks, schools, museums,
places of worship, civic and famous buildings.

Foursquare OS Places, OSM's lifecycle tags and Wikidata say what has closed.

The result is one PMTiles archive per region on the `places-overlays` release, streamed by HTTP
range request or downloaded with a region. A seventh of the catalog rebakes every night, so an
OSM edit reaches the map within a week ([chapter 2](02-data-and-rebakes.md)).

A tap also reads downloaded place packs (the OSM SQLite files offline search uses) and, unless
switched off, Google's keyless search.

## How it is decided

### Which places exist in a tile, and at what zoom

`tools/build-places-region.sh` loads the sources into one table, collapses duplicates, deletes
closed places, scores each row, and sets its minimum zoom from its rank inside grid cells.
`.github/workflows/places-overlays.yml` runs it per region.
[SPEC section 5.2](../../SPEC.md) lists every category and threshold.

#### What gets in

Overture's park, campus-building, housing, real-estate, school and transit rows are dropped;
OSM supplies those. A row also goes when it has no name, when Overture marks it permanently
closed, when its confidence is under 0.4, or when it has neither a category nor a website.

AllThePlaces rows join at confidence 0.85 and OSM nodes at 0.8. Each is deduped against the
table: a row with the same brand, or the same first two significant name words (`nkey`), within
about 150 m is dropped. Its hours, phone, website and locality fill the kept row where that
has none (`atpfill`, `osmfill`), matched by name and never by brand alone, so one branch's hours
cannot land on the next branch.

#### One row per business

Overture carries some businesses twice, such as a fuel station under its brand and under
`<brand> Station <town>`. Two passes collapse rows within about 60 m onto one leader: a
landmark's row, then not a kiosk, then higher confidence, then more contact fields.

1. Rows with an equal snap key: the whole name with accents and parentheticals removed, "&"
   read as "and", English legal suffixes and a trailing store number dropped. Fuel rows also
   key by house number (`fuel@<number>`), since a forecourt is one per lot.
2. Rows with an equal core key, when every word of one name is in the other. The core key is
   the snap key minus the generic words in `tools/place-generic-words.txt` (generated from
   `PlaceNames.GENERIC`; a unit test keeps them equal), so `<Brand> Gas Station` folds onto
   `<Brand>`. A core key that is only a street number or one short word is skipped.

#### Closed places

Overture rarely removes anything. Three steps delete closed rows:

- Foursquare. Overture imports Foursquare places without their closing date. The bake joins
  those rows to Foursquare OS Places by id and drops the ones with a `date_closed`. It reads
  the newest release with the `FSQ_HF_TOKEN` secret, else a mirror of the 2025-02-06 release.
  A row that a same-name OSM business within about 150 m or the chain's locator still lists is
  kept, in case it reopened.
- OpenStreetMap. Mappers retag a closed shop with a lifecycle prefix (`disused:`, `was:`,
  `abandoned:`, `closed:`) and leave its name. Another source's row with that name within about
  80 m is dropped, unless OSM has a live business of that name there.
- Wikidata. An OSM object whose Wikidata item has a dissolved or demolished date (P576) is not
  used, and its name joins the closed list.

In a District of Columbia test bake on the mirror these removed 1,499, 104 and 1 of 82,217
rows. Closing a chain row when its locator shows no branch nearby was tried and rejected:
locator data is incomplete per brand, so it hid open stores.

#### Prominence

Prominence is a category prior plus signals. The prior is 4.5 for anchors (hospital,
university, airport, museum, mall, supermarket), 3.2 for hotels, banks and big-box retail, 2.6
for food, 2.2 for everyday shops and services, 1.6 with no category, 1.0 for everything else
and 0.5 for offices, so a crowded block's budget goes to places people walk into.

Added to it: +1.6 for a brand, +0.5 for a website, +0.4 for a phone, +0.2 for an address and
`(confidence - 0.5) * 1.6`. Agreement between sources adds more (`srcbonus`): +0.6 for a paired
OSM node, +0.6 for a locator match, +0.8 for a Wikidata link on the OSM node or its brand.

Three adjustments follow.

- A row with confidence under 0.75 that no second source lists is capped at 3.0 and is never a
  landmark, so a stale row cannot rank on its category alone. In the District of Columbia test
  bake that was 13,396 of 82,286 rows.
- A **tenant** loses 2.0, so a supermarket's pharmacy cannot take the supermarket's label. A
  tenant is a department of an anchor store, hospital or university within about 200 m: the
  same street line without the unit, the same brand, or a name that is the anchor's first word
  plus more. The anchor brand's fuel station, charging bay or convenience shop within about
  275 m counts too. A kiosk (a coin machine, an ATM) is flagged as a tenant but keeps its
  prominence.
- A fuel, charging or convenience row named exactly like its anchor gets " Fuel", " Charging"
  or " Market" appended, so store and forecourt do not share a label.

#### Landmarks

Parks, schools and museums come from OSM, as points and outlines, with any named building or
tower that has a Wikidata link. One archive then holds every map point.

A row is a **landmark** when it is in an anchor category. An OSM park, attraction, historic or
civic building or place of worship is one when it has a Wikidata link, an outline of a hectare
or more, or names in five or more languages. Landmarks are ordered by notability: outline size
(log10 of the area in square meters minus 2, capped at 3), +1.5 for a Wikidata link, and fame,
`0.6 * log2(1 + languages)` capped at 3. Fame counts `name:<lang>` tags; size alone ranks a
famous tower below every large park. Size and the Wikidata link also add to prominence.

A landmark is never a tenant. An existing row with the landmark's name stands for it and takes
its credit.

#### Where each place sits

The coordinate is OSM's, then the locator's, then Overture's. Tenants never move.

- The locator snap needs an equal snap key and a disagreement of 30 to 120 m. Under 30 m the
  sources agree anyway (median 7.4 m over Davis chains). Past 120 m it is another branch.
- The OSM snap takes any distance inside the 150 m duplicate box, on the snap key or the core
  key, when node and row are each other's best match. A chain keeps the 120 m ceiling. On a
  Delaware test box this moved 123 of 141 OSM shop nodes' places onto the OSM pin, against 15
  with the 30 to 120 m band.
- Overture stacks a building's tenants on one parcel point. A stacked row whose address names
  a unit moves to Overture's address point for that unit. The rest are spread on a ring 10 to
  20 m out.

#### Addresses and names

The tile's `addr` is the bare street line, because the tenant match, the unit snap and the
fuel key join on it. City, region and postcode come out as `loc`, in the country's own order
(`fmtloc`). A row with no locality, as most OSM rows are, borrows the `loc` of the nearest row
that has one within about 300 m (`LOCFILL`). Offline search does the same for place-pack rows
([chapter 8](08-offline.md)).

The name keys keep letters of every script, so the rules work for Japanese, Cyrillic or Arabic
names. A non-Latin name carries `name_en` when OSM has one, from its own row, its paired OSM
node or a region-wide chain dictionary. The app shows it when the UI language uses Latin script
(`uiWantsLatinLabels`).

#### Ranks and minimum zoom

Each row is ranked by prominence in nested cells: `frank` (about 100 m), `rank` (400 m) and
`crank` (1.6 km). Landmarks are also ranked by notability in their own 1.6 km budget (`lrank`)
and in a 6.5 km cell (`xrank`). The first matching line sets the zoom:

| Condition | Appears from |
| --- | --- |
| a tenant or kiosk that is not fuel | z17 |
| a landmark with `xrank = 1` | z11 |
| a landmark with `xrank <= 3` | z12 |
| a landmark with `lrank <= 4` | z14 |
| a landmark with `lrank <= 10` | z15 |
| `crank = 1` and prominence >= 6 | z13 |
| `crank <= 2`, or prominence >= 5 with `crank <= 6` | z14 |
| `rank <= 3`, or prominence >= 4.5 with `rank <= 8` | z15 |
| `rank <= 12`, or prominence >= 3.5 with `rank <= 24` | z16 |
| everything else | z17 |

Prominence buys a few more places per cell, never an unlimited number. Before that cap a
Shinjuku, Tokyo z16 tile carried 963 places against 86 in Davis, and the view panned at 13 to
23 fps on a Pixel 4a. After it, 22 to 40. Landmarks have their own budget so a downtown's shops
cannot take every slot from its parks. Fuel is exempt from the tenant zoom because a fuel
station is a destination while driving.

Tiles run from z11 to z17. Each feature carries `src` (always `overture`; the tap handler keys
on it), `origin` (`overture`, `atp` or `osm`) and `mark` (1 for an OSM landmark or named
building). An id starts `atp:` or `osm:` for those origins.

Each bake prints `LANDMARKS|count|by z15|%` and the ten most notable landmarks that arrive
after z15 (`LATE|...`). The workflow copies both to the run summary.

### Which of the places in a tile get an icon, a label, or a dot

`VelaMapView` decides per zoom by rank, before collision. The named dials are `AppTune` keys
and can be tuned remotely.

Below z13 everything in the tile gets an icon; those tiles hold only landmarks. After that a
place must be near the top of its cell: the top two per 1.6 km cell from z13, the top one per
400 m cell from z15, `openRankZ16` (3) from z16 and `openRankZ17` (8) from z17. From z17.5 the
budget is per 100 m block: `openIconCapNear` (8), then `openIconCapClose` (16) from z18.5 and
`openIconCapMax` (40) from z19.5. At every step a high enough prominence, between 4.5 and 6,
passes regardless ([SPEC section 5.3](../../SPEC.md)).

A landmark passes every step through z17. From z17.5 a non-fuel tenant stays a dot until
z18.5, and a place in the default or health group must be in the top `openGenericBlockTop` (3)
of its block or reach `openGenericMinProminence` (4.0), else it is a dot until
`GENERIC_ICON_ZOOM` (20.3). This keeps a tower's office tenants from each taking a pin.

Dots: none below z15, `rank <= 6` at z15, `rank <= 15` at z16, all from z17. From z17 one kind
is not drawn at all until `GENERIC_REVEAL_ZOOM` (19.5): a generic place as above that ranks
past `openGenericHideRank` (120) in its 400 m cell. That clears the office dots from a dense
downtown and leaves a home office on a residential street alone. The rule needs the `mark`
property, so it never hides a named building.

Labels follow the icon steps. From z17.5 a label also needs `rank <= openLabelCap` (20) or
prominence >= 3.0, because each label costs glyph layout and a collision pass. Icons collide
below z18 and may overlap from z18, so a shop under a stack still appears up close.

Over an archive whose `rev` (its bake date) is at least `placesOneSetRev` (20260923) the app
hides the basemap's own point layers (`poi_r1`, `poi_r7`, `poi_r20`). On a Pixel 4a this took
Shinjuku pans from 20 to 45 fps to 35 to 58. An older archive has no landmarks, so over it
those layers keep drawing, less the points the archive already shows by name. The dial must
never go below the oldest archive that carries landmarks.

Vela's zoom number reads about one lower than Google's for the same area, because of 512 px
tiles.

### Google's own ranking, when Google is drawing

In Google or Both mode, and wherever no archive covers the view, the pins come from a fan-out
of 15 category searches (8 on a low-RAM or low-data device). Each is ordered by its own
relevance, so Vela computes one ranking:

```
prominence = ln(reviewCount + 1) * (0.6 + rating / 10)
           + (categoryPrior - NEUTRAL_PRIOR) * PRIOR_WEIGHT
```

A missing rating counts as 3.5. `categoryPrior` uses the bake's 4.5, 3.2, 2.2 and 1.0 tiers
(1.6 with no category), `NEUTRAL_PRIOR` is 2.2 and `PRIOR_WEIGHT` is 0.9, so an ordinary
restaurant's number is unchanged and anchors rise. The keywords are English and Google's
category text arrives in the app's language. A category that matches none falls to the 1.0
tier, so in another language the order is by reviews alone and each place scores about 1.1
lower.

Labels are tiered. Below z15.5 only prominence 6.0 or more is named, from z15.5 it takes 5.0,
from z16.5 it takes 3.0, and from z17.5 everything is named. An unnamed symbol skips label
placement, which is most of the layer's cost. The layer keeps the top of the ranking, up to
`ambientCapMin` (45) places at z14 and `ambientCapMax` (140) at z17.5.

### Why the map does not reshuffle while you look at it

A settled view is painted several times: the fan-out streams its pool, and a cold session
refetches once because Google strips review counts in a session's first seconds. A changed
review count would reorder labels and resize icons under a user who has not moved.

`AmbientStability` fixes each place's prominence at the first paint of a view. Later paints can
add places but cannot reorder or resize what is drawn. Moving the map clears it. A pool with no
positive prominence is never remembered, since that is the stripped cold-start answer.

### One rule for "the same business"

The tap (which Google listing), Both mode (which archive pin is Google's copy) and the bake
(which rows to collapse) all ask whether two names are one business. All three use
`core/util/PlaceNames`. The bake mirrors it in SQL.

`normalized` folds a name: accents and parentheticals out, "&" read as "and", legal forms (LLC,
GmbH, SARL, ООО and others), hotel chain tails, a leading "The" and a trailing store number
dropped, street abbreviations expanded. `match(a, b)` then returns one of four answers:

- EXACT: equal after normalizing (`<Name> #12` and `<Name>`).
- VARIANT: one is the other plus only generic words (`<Brand>` and `<Brand> Pharmacy`).
- OVERLAP: the identifying words agree, with other words around them. The main cases are two
  shared identifying words, and a name inside a longer one that has a strong core (two
  identifying words, or one of five letters or more that is not an ordinal).
- NONE: different businesses (`<Park> Park` and `<Park> Pool`).

Generic words (categories, structure words, street types) describe a business without naming
it. `GENERIC` is the union of thirteen language tables, because the names on a map belong to
the region and not to the phone's language. Names in scripts without word breaks (Han, kana,
Hangul, Thai) are compared as strings (`cjkMatch`). A caller adds the town from the listing's
address (`cityWords`) and `localGeneric`, the words that three or more names in the current
pool share, so a neighborhood's name does not join unrelated businesses.

Two rules also take the places' kinds (their icon groups):

- `sameBusiness` refuses an OVERLAP between two known, different kinds, such as a fuel station
  and the pizza place on its lot. EXACT and VARIANT cross kinds, because a store and
  `<store> Pharmacy` are one business. A VARIANT between a park, transit or culture place and a
  business is refused, since a pharmacy named after a plaza is not the plaza.
- `sameFuelLot` calls two fuel stations within `FUEL_LOT_M` (30 m) one station whatever their
  names, unless both have house numbers and they differ.

`PlaceNamesMatchTest` holds the fixture pairs and `PlaceNamesI18nTest` one pair per language.

### Both mode: which copy is drawn

Google's copy wins a twin: its coordinate is the storefront and its rank comes from reviews.
The fan-out waits until the view has settled for 1.5 s. The twin pass (`hideOpenTwins`) runs
400 ms and 2 s after each upload, once the map has been still for `TWIN_PASS_STILL_MS`
(700 ms). It compares the archive icons rendered on screen with the Google places drawn, and
hides an archive pin when:

- its normalized name equals a Google place's within `DEDUPE_SAME_NAME_M` (150 m), or
- `sameFuelLot` says they are one forecourt, or
- `sameBusiness` agrees within `DEDUPE_NAME_M` (80 m).

The pin's `name_en` is tested too, because Google answers in English. A hidden pin is released
when its Google partner is no longer drawn. An archive pin that matches a permanently closed
Google listing within 80 m, with no live listing of that name within 150 m, joins the persisted
closed set.

### What happens when you tap

#### Which feature you tapped

A parking pin, a search pin or a saved pin wins outright. Otherwise the nearest of a transit
stop icon, a Google place and a basemap or archive place wins. The map first asks which icon is
rendered at the tapped pixel. When a named place is there, only those compete, so a dot a few
meters away cannot beat the icon under the finger. With nothing at the pixel, a 48 dp box
decides, so a lone dot stays tappable.

#### The sheet

An archive pin seeds the sheet from the tile. `offlineTwin` then fills missing fields from a
downloaded place-pack row within 80 m whose name agrees. A basemap label brings only its name,
so offline the pack row is all it has. A watchdog (`TAP_RESOLVE_WATCHDOG_MS`, 6 s) ends the
loading skeletons if the lookup hangs.

#### The lookup

Unless "Look up tapped places on Google" is off, the name is searched near the tap with
`searchOnce`: one page, no nearby pass. On a Pixel 4a the three-page search spent 4.3 s of a
4.7 s tap on pages two and three. One page takes about 1.3 s.

A transit stop is recognized from the feature's kind, after the exclusion list
`NON_TRANSIT_CAT` (fuel, charging, fire, police), because "Gas station" contains "station". It
resolves to the nearest live transit listing within 250 m, else to the Transitous board at the
tapped point.

For a business, candidates come from the first pool that is not empty:

1. Listings that are the same business under `PlaceNames.sameBusiness`, within
   `BUSINESS_TAP_CAP_M` (1.5 km). The cap stops a brand's distant branches from filling the
   pool and blocking the fallbacks.
2. The same search repeated in the label's language, when its script is not the app
   language's (`crossScriptCandidates`). Two extra requests at most.
3. A listing of the tapped icon group within `NO_NAME_MATCH_M` (60 m), already in the results
   (`sameKindNear`).
4. The tapped kind beside the building (`kindBesideAnchor`), for a row the archive names after
   the site while Google lists it under a brand: a category search around the nearest
   name-agreeing listing on the lot. One extra request.
5. Anything within 60 m.

When the tapped row and a candidate both have a house number and they differ (`houseClash`),
the candidate is out of every pool without a name match, and out of name matches beyond
`SAME_LOT_M` (120 m). Distance alone cannot tell a fuel station from the one across the
junction. On the lot a mismatch is tolerated, because open-data numbers are sometimes wrong,
and a matching number is preferred.

Within the pool a live listing beats a permanently closed one. Within 120 m, a listing whose
normalized name equals the tapped name beats one with extra words, and among those one of the
tapped kind wins, so a store tap does not open the brand's fuel station. Then the nearest wins,
unless one within 35 m has `reviews >= 2 * nearest + 5`, which promotes the fuller profile of a
duplicate.

A business tap never resolves to a transit stop or a junction (`JUNCTION_CATEGORIES`). The pick
must be within 1.5 km for a business and 30 km for a settlement label. With no pick the label
keeps its own data and the source line says "not matched on Google".

#### After the pick

If Google's name is not in the app language's script and the map's label is, the map's label
stays as the title (`NameScript.prefer`).

The link is remembered per archive pin (`openPlaceCache`, 500 entries,
`open_place_links.json`), so the next tap is instant and an offline tap opens the last listing
seen. A listing with no review count and no hours is not remembered. The cache is dropped when
the app's version changes and when a region's archive is updated, so a link made by an older
rule does not outlive its fix.

A closed pick hides the pin only when no live listing of that name is within 150 m. The hidden
ids persist in `open_place_closed.json`, capped at `CLOSED_OPEN_PLACES_CAP` (2,000).

Each tap that searched logs one `VelaTap` line with no coordinates: the pool sizes, the three
nearest answers, the pick and the timings.

Offline, or with "Use Vela without Google" on, an archive pin shows its tile data plus the pack
row, or the remembered listing. With only "Look up tapped places on Google" off, an archive pin
stays on its tile data, but a basemap tap has nothing else to show and still resolves.

### Open-data hours

AllThePlaces and OSM carry hours in OSM's `opening_hours` syntax. `core/util/OsmHours.lines`
converts it into the per-day lines Google gives (`Monday: 8 AM–5 PM`), which the sheet and the
open-or-closed badge already read. Holiday and date rules are skipped, because the lines
describe an ordinary week. Syntax it does not understand is shown as its own text. It converts
97.7% of the distinct hours strings in one US state's place pack.

## Limits

- A place that neither OSM nor a locator has sits on Overture's point, which for a big store
  can be the parcel centroid. Mapping the shop in OSM fixes it within a week.
- Chains lead. A brand is +1.6, and with a locator match (+0.6) and a brand Wikidata link
  (+0.8) a branch starts 3.0 ahead of an independent with the same details. The archive has no
  ratings, so rank reflects the kind of place and how fully it is described.
- Category priors are keyword lists. An unusual category falls to the 1.0 tier, and the
  Google-side keywords are English only.
- Closures lag. Without `FSQ_HF_TOKEN` the Foursquare copy ends in February 2025, and a closed
  place that none of the three sources records stays until someone tags it in OSM. A closed
  Google listing hides the pin on that phone only.
- The bake folds EXACT and VARIANT duplicates only. An OVERLAP needs the kinds and the pool's
  shared words, which only the app has. No rule covers a shop inside a fuel station under a
  different name, or Google's own two profiles for one business.
- A non-Latin place without `name_en` cannot match Google's English name in Both mode, so both
  copies can draw.
- Legal forms written with dots survive. Each word is checked against the legal-form list
  before single letters are joined, so "S.r.l." becomes "srl" too late.
- The locality borrow can miss an east or west neighbor above about 47.5 degrees latitude,
  because `LOCFILL`'s 0.004 degree search cells are not widened with latitude.
- A downloaded archive stays at its bake until the region is updated
  ([chapter 2](02-data-and-rebakes.md)). Hours that exist only as holiday or seasonal rules are
  not shown.
