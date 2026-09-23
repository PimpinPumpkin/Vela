# 1. Places on the map

## What you see

Pan the map and businesses appear: an icon with a name for the ones worth naming, a colored
dot for the rest, more of them the further you zoom in. Tap one and a sheet opens with its
hours, reviews, photos and phone.

Those are two different systems. The pins are open data that ships from this repository. The
sheet is Google, asked only when you tap. **Settings > Places > "Places come from"** decides
which system draws the map:

| Mode | Who draws the pins | Reaches Google while browsing | Works offline |
| --- | --- | --- | --- |
| **Vela data** (default) | The baked open-places layer, plus OpenStreetMap's own shops | Never | Yes, in a downloaded region |
| **Both** | The open layer, topped up by one Google fan-out per settled view | Once per settled view | The open half does |
| **Google** | Google's answer for the current view, refetched on every pan | Every pan | No |

The default is Vela data, in the shipped config and in the compiled fallback
(`MapPoiPrefs.placesSource = SOURCE_OPEN`, `Calibration.defaultPlacesSource = "open"`). A phone
only ends up on another mode because someone picked it.

The tap is the part that changed most recently, so here is what it looks like now. The sheet
opens on the frame you tap, already showing whatever the map itself knows about the place: its
name, its kind, and for an open-data pin the street address with city, state and ZIP, the
phone, the website and often the hours. Under the name a small gray line says where that row
came from and what is happening to it:

- "From Overture · checking Google" while the lookup runs,
- "From AllThePlaces (&lt;chain&gt;) · not matched on Google" when nothing matched,
- "From OpenStreetMap" when Google was never asked (offline, the lookup switched off, or
  **Settings > Privacy > "Use Vela without Google"** on).

An OpenStreetMap row's line is a link to the node, so a place that is wrong on the map can be
fixed where it came from. While Google is asked, the parts the map has nothing for (the rating,
the reviews tabs, the photos) pulse as gray bars. When the listing lands it replaces the sheet
in place, the pulsing parts fade in, and the source line disappears, because a Google listing
is no longer one of the three datasets.

## Where the data comes from

**Overture Maps Places** is the base: an open business dataset maintained by Meta, Microsoft and
others, read straight from its public S3 bucket at bake time. Overture's places come largely
from Meta and Bing, so a business with no Facebook page and no Bing entry is simply absent,
chains included.

**AllThePlaces** (CC0) fills that hole. It scrapes each chain's own store locator weekly and
publishes the world as one PMTiles file; the bake reads the newest run
(`data.alltheplaces.xyz/runs/latest.json`, unless `ATP_RUN` pins one) and pulls the region's z15
tiles out of it with a few range requests. Locator rows carry OSM-syntax `opening_hours`, which
Overture never has.

**OpenStreetMap** does three jobs. It is a **source**: named business nodes from the region's
Geofabrik extract go in beside the other two, because OSM is the one dataset in the stack that
anyone can correct and see corrected in the next bake. It is the **first choice for a place's
coordinate**: OSM maps the shop where the shop is, not at a parcel centroid. And on the phone,
**Settings > Places > "OpenStreetMap shops too"** (on by default) also draws the businesses
already present in the basemap tiles, for what the baked layer lacks; doubles are dropped by
name.

The result is one PMTiles archive per region on the `places-overlays` release, streamed by HTTP
range requests as you pan, or downloaded whole with a region for offline use.

For a tap, two more sources join. **Downloaded place packs** (the per-region OSM SQLite files
that offline search uses) fill in a tapped label's missing fields. And **Google**, through the
same keyless search the search bar uses, supplies the listing with reviews, photos and busy
times, unless the tap lookup or Google as a whole is switched off.

## How it is decided

### Which places exist in a tile, and at what zoom

`tools/build-places-region.sh` loads the three sources into one table, collapses duplicates,
scores every place, and assigns it a minimum zoom from its rank inside a grid cell.

**What gets in.** Business rows only: parks, campus buildings, housing, schools and transit are
dropped, because OSM already draws those. A row goes too when Overture marks it permanently
closed, when its confidence is under 0.4, or when it has neither a category nor a website.

**How the sources join.** Each later source is deduped against everything already in the
table, by the same brand or the same two leading significant name words (`nkey`) within a box
of about 150 m:

- AllThePlaces rows join at confidence 0.85. A locator point that duplicates an Overture row is
  dropped, but **what it knows is kept** (`atpfill`): the nearest same-name locator point's
  hours, phone and website go onto the Overture row wherever that row has none. Matched by name,
  never by brand alone, so one branch's hours cannot land on another branch a block away. On the
  Davis test box this carried hours onto 60 rows that had none.
- OSM nodes join at confidence 0.8, under AllThePlaces, so where another source already has the
  place that row keeps its slot. The same rule applies to a dropped OSM duplicate (`osmfill`):
  a convenience store whose OSM node carried its hours no longer loses them to the dedupe. Nodes
  only; a building mapped as an area is the same centroid guess as the parcel point.

**One row per business.** The source dedupes only ever compared a new source against what was
there, and Overture itself carries businesses twice (a fuel station under its brand and under
`<brand> Station <town>`, a store and the counter inside it named after the store). After the
last source is in, two passes collapse what is left, each onto a leader chosen by: not a kiosk
first, then the higher confidence, then the row that knows more (address, phone, website,
hours):

1. Rows whose **snap key** (the whole name, normalized the way the app normalizes it, trailing
   store number dropped) is equal within about 60 m. Fuel rows also key by their **house
   number** (`fuel@<number>`), because a forecourt is one per lot and its rows spell the road
   three different ways.
2. Rows whose **core key** is equal within the same box. The core key is the snap key minus
   every generic word in `tools/place-generic-words.txt` (1,856 words, the app's own
   `PlaceNames.GENERIC`, kept equal by a unit test), so `<Brand> Gas Station` folds onto
   `<Brand>` and `<Name> Coffee Company` onto `<Name>`. A core key that is only a street number,
   or a single word under five letters, is not a name and stays out, so "38th Street Deli" and
   "38th St Grocery" stay two rows.

**Prominence** is a category prior plus signals:

| Kind of place | Prior |
| --- | --- |
| Hospital, university, college, airport, stadium, museum, zoo, amusement park, shopping center, supermarket, department store, grocery store, convention center, casino, aquarium | 4.5 |
| Hotel, pharmacy, bank, cinema, gym, library, place of worship, bowling alley, hardware store, car dealer, furniture, electronics, sporting goods, home improvement, wholesale club, discount store | 3.2 |
| Restaurants and cafes, bars, bakeries, breweries, wineries, gas stations, EV charging, auto repair, car wash, pet store, bookstore, clothing, shoes, jewelry, florist, liquor, tobacco, toys, bicycles, dentist, vet, optometrist, urgent care, post office, ATM, laundromat, dry cleaner, barber, salons, spa, tattoo | 2.2 |
| No category at all | 1.6 |
| Everything else | 1.0 |

plus `+1.6` for a known brand, `+0.5` for a website, `+0.4` for a phone, `+0.2` for an address,
and `(confidence - 0.5) * 1.6`. So a supermarket with a brand and contact details lands near 7,
and a nameless one-off near 1.

Three demotions and one rename run before the ranking:

- A **tenant** (a department of a nearby anchor store, matched by the same street line with the
  unit dropped, the same brand, or a name that is the anchor's first word plus more; or the
  anchor brand's own fuel station, charging bay or convenience shop within about 275 m) loses
  2.0 prominence, so a supermarket's in-store pharmacy cannot take the supermarket's label.
- A **kiosk** (a Redbox, a Coinstar, an ecoATM, a money-transfer window, a key machine, an ATM)
  is flagged the same way. Both are baked at minzoom 17 and drawn as dots until z18.5.
- **Fuel is exempt** from the tenant minzoom, because a fuel kiosk really is the thing you are
  looking for while driving.
- **A forecourt says which one it is.** The pumps are often published under the bare brand name,
  so the store and its forecourt drew as two icons carrying the same label a few tens of meters
  apart, and a tap on "the store" was a coin toss. A fuel, charging or convenience row whose
  name is exactly its anchor's gets " Fuel", " Charging" or " Market" appended. A row that
  already names itself is left alone, and this is the only place in the bake where a name is
  rewritten.

**Where each place sits.** The coordinate prefers OSM, then the AllThePlaces locator, then
Overture's own point; tenants never move. The AllThePlaces snap needs the whole snap key to match
and a disagreement of 30 to 120 m: under 30 m the sources agree anyway (a median 7.4 m over the
Davis chains), and past 120 m it is a different branch. The OSM snap is wider on purpose, because
OSM pins are placed more carefully and are the ones a person can fix: any distance inside the
duplicate box (about 150 m), on the whole name or on the name with generic words removed ("Joe's
Pizza" and "Joe's Pizza & Pasta"), with each node and each row in at most one pair and only when
they are each other's best match. A chain (a brand, or a name the region has twice) keeps the
120 m ceiling. On a Dover, Delaware test box it moved 123 of 141 OSM shop nodes' places onto the
OSM pin, against 15 under the old band; every looser-name pair was the same business. Overture also puts every tenant of a
building on one parcel point; a stacked tenant whose address names a unit is moved to Overture's
own address point for that number and unit within about 200 m, and whatever still shares a
point is spread on a golden-angle ring 10 to 20 m out, with the best row left in place.

**The rest of the address.** The tile's `addr` is the bare street line, and it has to stay that
way because the tenant match, the unit snap and the fuel house-number key all join on it. The
city, region and postcode travel in a side table (`locs`) and come out as a separate tile
property, `loc`, which the app appends. `fmtloc` writes it the way the country writes an
address: "Davis, CA 95616" in the US, Canada and Australia (a ZIP+4 is cut to the ZIP), city
then postcode in Britain and Ireland, postcode then city everywhere else. OSM and AllThePlaces
rows rarely say their country, so they take the region's most common Overture country
(`regioncc`). Every row in the Davis test box got one.

**The ranks.** Each place is then ranked by prominence inside four nested grid cells: `frank`
(about 100 m), `rank` (about 400 m), `crank` (about 1.6 km) and `xrank` (about 6.5 km, and only
for landmark categories). The minimum zoom follows:

| Condition | Appears from |
| --- | --- |
| a tenant or kiosk that is not fuel | z17 |
| a landmark (airport, hospital, university, stadium, mall, zoo, museum...) with `xrank = 1` | z11 |
| a landmark with `xrank <= 3` | z12 |
| `crank = 1` and prominence >= 6 | z13 |
| `crank <= 2` or prominence >= 5 | z14 |
| `rank <= 3` or prominence >= 4.5 | z15 |
| `rank <= 12` or prominence >= 3.5 | z16 |
| everything else | z17 |

So a downtown thins to its landmarks as you zoom out and a village keeps its one cafe at z15.

Every tile feature carries `id`, `name`, `class`, `group`, `icon`, `prominence`, `confidence`,
the four ranks, `landmark`, `tenant`, `brand`, `addr`, `loc`, `website`, `phone`, `hours`,
`src` (always `overture`, because the tap gate keys on it) and `origin` (`overture`, `atp` or
`osm`, which is what to read when telling the datasets apart). The row id keeps its origin too:
`atp:<spider>:<ref>` for a locator row, `osm:n<id>` for an OSM node, Overture's hex id
otherwise.

A seventh of the catalog rebakes every night, so an OSM edit reaches the map within a week on
its own, and a single region can be rebaked on demand in about two minutes. See
[chapter 2](02-data-and-rebakes.md) for the schedule and how a phone picks up the new archive.

**The cell budget is a cap** (2026-09-22). A place used to skip its cell's budget outright once
its prominence was high enough, and in a dense city nearly every shop is: a Shinjuku zoom-16 tile
carried 963 places against Davis's 86, and a pan over it ran 10 to 14 fps on a 4a. Importance now
buys a few more places per cell (zoom 14: 2, or 6 at prominence 5; zoom 15: 3, or 8 at 4.5; zoom
16: 12, or 24 at 3.5), and zoom 17 still carries everything, so a place past the budget comes back
as a dot when you zoom in. Measured with the Shinjuku test box:

| | z14 | z15 | z16 | z17 | Pan fps on a 4a |
|---|---|---|---|---|---|
| Shinjuku before | 418 | 480 | 885 | 474 | 13 to 23 |
| Shinjuku after | 5 | 53 | 55 | 474 | 22 to 40 |
| Davis downtown before | 87 | 63 | 83 | 117 | |
| Davis downtown after | 12 | 47 | 56 | 117 | |

What decides the order inside a cell, with no reviews to go on: the category prior (food 2.6
above the other everyday services at 2.2, offices, agencies and consultants lowest at 0.5), brand,
contact details, Overture's confidence, and since the same day AGREEMENT: +0.6 when OSM's node pairs
with the place, +0.6 when a chain's own locator matched it, +0.8 when OSM links it to Wikidata
(`srcbonus`, added to prominence before the cells are ranked). The rest of the Tokyo cost is the
basemap's own OSM point layers (`poi_r*`): hiding them on top of the cap measured 46 to 60 fps.

**One set of map points** (2026-09-22, branch `places-one-set`, behind the `placesOneSetRev`
calibration dial). The basemap's own point layers (Liberty's `poi_r1`/`poi_r7`/`poi_r20`, built by
OpenFreeMap from OSM) drew parks, temples, schools and museums as a second set that the phone had
to reconcile with Vela's places and that cost half the frame rate in Tokyo. The bake now takes
those from the region's OSM extract (points and outlines; an outline sits at the average of its
outer ring), so one archive holds every map point, ranked and budgeted together, and the app hides
the basemap's copy over any archive baked on or after the dial's date. Landmarks get their own
budget per ~1.6 km cell (4 at z14, 10 at z15), ordered by notability: outline size (log10 of the
area, a hectare = +2) and a Wikidata link (+1.5 there, +2.0 on prominence). A landmark is never a
tenant, never folds into a business of the same name key, and gives its English name and Wikidata
credit to the Overture row it merges into. Measured on test boxes (4a, pan fps; archive size):

| | Current bake | One set | Size change |
|---|---|---|---|
| Shinjuku | 20 to 45 | 35 to 58 | +0.5% |
| Midtown | 20 to 37 | 36 to 58 | +0.3% |
| Davis downtown | 43 to 59 | 52 to 59 | +4.6% |

Bryant Park, Grand Central, the Empire State Building and Davis's Central Park arrive at z14-15.
At the widest street zooms in Midtown the dense bus-stop layer can still win the space.

**Names in every script, and English names** (2026-09-22). The name keys (`snapkey`, `nkey`) keep
letters of every script, as the app's `PlaceNames` does. They used to keep only `a-z0-9`, so a
Japanese, Chinese, Korean, Cyrillic, Greek, Hebrew, Arabic or Thai name keyed to nothing. In
those regions no name rule ran at all: OSM's copy of a shop Overture already had went in as a
second pin, and no OSM or chain-locator position was ever used. Measured on a small Shinjuku box
with the old and new keys:

| Shinjuku test box | Old keys | New keys |
|---|---|---|
| OSM shops added as extra pins | 1,501 | 1,176 |
| Places moved onto an OSM pin | 453 | 1,566 |
| Places with an English name | none | 1,021 |

A place whose own name is not Latin carries `name_en` in the tile. It comes from OSM
(`name:en`, then a romanized `name:*-Latn`, `name:latin` or `brand:en`): the place's own OSM row,
the OSM node it pairs with by name, or a region-wide chain dictionary. The dictionary maps a
chain name to its English form when OSM agrees on the spelling, and applies to a name that
starts with a dictionary key of at least four characters that is itself a chain ("ローソン西新宿1丁目店"
reads "Lawson"). Overture itself carries no English name there (0 of 14,718 places in a central
Tokyo box). The app shows `name_en` on the places layer and the sheet for a Latin-script UI
(`uiWantsLatinLabels`), and the Both-mode twin test compares it too, because Google answers in
English. The basemap's own shop and transit labels (`poi_r*`, `poi_transit`) follow the same
one-line rule as place names now, instead of stacking the romanized name over the local one.

### Which of the places in a tile get an icon, a label, or a dot

The tile can hold more than the map should draw, so the app decides per zoom, by rank rather
than by collision. The steps, with the remotely tunable dials named:

| Zoom | Gets an icon |
| --- | --- |
| below z13 | everything in the tile (only landmarks are in the z11 and z12 tiles) |
| z13 | `crank <= 2` or prominence >= 6 |
| z15 | `rank <= 1` or prominence >= 5 |
| z16 | `rank <= openRankZ16` (3) or prominence >= `openPromZ16` (5.5) |
| z17 | `rank <= openRankZ17` (8) or prominence >= `openPromZ17` (5.0) |
| z17.5 | `frank <= openIconCapNear` (8) or prominence >= 6, tenants still dots |
| z18.5 | `frank <= openIconCapClose` (16) or prominence >= 5 |
| z19.5 | `frank <= openIconCapMax` (40) or prominence >= 4.5 |

From z17.5 two more rules apply: a place in the default (plain-pin) or health group needs to be
in the top `openGenericBlockTop` (3) of its block or reach `openGenericMinProminence` (4.0),
else it stays a dot, and an archive baked before `frank` existed falls back to `rank` with a
quarter of the cut. Everything below the cut still draws as a category-colored dot: none below
z15, `rank <= 6` at z15, `rank <= 15` at z16, all from z17.

Labels follow the icon steps exactly, since a name floating without its icon reads as broken.
From z17.5 only the top `openLabelCap` (20) per 400 m cell, or prominence >= 3.0, also get a
name; each label is glyph layout plus a collision pass over four anchor slots, and a mall puts
dozens in one cell. Icons collide below z18 and may overlap from z18, so a shop under a stack
still appears when you are close.

Note that Vela's zoom number reads about one lower than Google's for the same visible area, a
consequence of 512 px tiles. Compare the two by matching the area on screen, never the z number.

### Google's own ranking, when Google is drawing

In Google or Both mode the pins come from a fan-out of 15 per-category searches (8 on a low-RAM or low-data device), each
ordered by its own relevance. There is no global ranking in that answer, so Vela computes one:

```
prominence = ln(reviewCount + 1) * (0.6 + rating / 10)      // how many people know it
           + (categoryPrior - 2.2) * 0.9                    // what kind of place it is
```

A missing rating counts as 3.5. The category prior is the same 1.0 to 4.5 scale the bake uses,
applied as a difference from the everyday-business tier (`NEUTRAL_PRIOR` 2.2, `PRIOR_WEIGHT`
0.9), so an ordinary restaurant's number is unchanged. Anchors rise, places with no category at
all sink. Google's category text arrives in the app's language and the keyword table is
English, so a non-English session gets the neutral prior and the old reviews-only ranking.

Ambient labels are tiered by zoom against that prominence: below z15.5 only prominence >= 6.0
is named, from z15.5 >= 5.0, from z16.5 >= 3.0, and from z17.5 everything. An unnamed symbol
skips label placement entirely, which is also most of the rendering cost. The layer is capped
per zoom (`ambientCapMin` 45 to `ambientCapMax` 140, both remotely tunable), and the cap keeps
the top of the ranking.

### Why the map does not reshuffle while you look at it

A settled view is painted several times: the fan-out streams its pool as terms land, the
duplicate pass re-runs a couple of seconds later, and a cold session refetches the whole fan-out
once, because Google strips review counts from the first few seconds of a session. Different
review counts for the same place means a different ranking, which used to reorder labels and
resize icons under a user who had not moved.

So the first *rich* paint of a view fixes each place's prominence and later paints of the same
view reuse it (`AmbientStability`). Later answers still add places; they cannot reorder or resize
what is already on screen. Moving the map forgets it and ranks fresh. A pool whose prominences
are all zero is never remembered, because that is the stripped cold-start flavor and freezing it
would pin the flatness the refetch exists to fix.

### One rule for "the same business"

Three different places need to decide whether two names are one business: the tap (which Google
listing did you mean), Both mode (which open pin is Google's copy) and the bake (which rows to
collapse). They used to have three private rules that drifted apart, and every drift was a
duplicate icon or a wrong tap. Now all three read `core/util/PlaceNames`, and the bake mirrors it
in SQL.

`normalized` folds a name before any comparison: accents out (plus the letters decomposition
leaves alone, such as ß, æ, ø, ł, ё), parentheticals out, "&" read as "and", a possessive kept on
its word, legal forms dropped in every app language (LLC, Inc, GmbH, SARL, S.r.l., B.V., ООО,
Kft and the rest), hotel chain tails ("by Wyndham") dropped, a leading "The" dropped, a trailing
store number dropped, and street abbreviations expanded. `match(a, b)` then answers one of four:

| Answer | Meaning | Example |
| --- | --- | --- |
| EXACT | equal after normalizing | `<Name> #12` and `<Name>` |
| VARIANT | one is the other plus only generic words | `<Brand>` and `<Brand> Pharmacy` |
| OVERLAP | the identifying words agree, with other words around them | `<Name>` and `<Name>-<Partner>` |
| NONE | different businesses | `<Park> Park` and `<Park> Pool` |

**Generic words** are words that describe a business rather than name it: categories,
structure words, street types. The list is one table per app language, all fifteen UNIONED,
because the names on a map belong to the region and not to the phone: a bakery's "Boulangerie"
is generic whatever language the reader uses. On top of that list a caller adds two things it
learns from the comparison at hand: the town out of the listing's address (`cityWords`, so
`<Gym> Davis` is `<Gym>`) and `localGeneric`, the words that three or more names in the current
pool share. That second one matters in a city, where a neighborhood or a landmark sits in a dozen
names on one screen and used to glue unrelated businesses together.

The rules that decide OVERLAP are strict on purpose. One shared identifying word is not enough;
two are. A nested name needs a "strong core": two identifying words, or one of at least five
letters that is not an ordinal ("The Finn" does not claim a longer name that mentions Finn).
Europe's four-letter brands get an allowance when the word leads both names. A shared brand
prefix of two words counts, as does the shorter name as a phrase inside the longer. Plurals fold
pairwise, a name glued into one word is read as its words when the other name spells it, and
names in scripts written without spaces (Han, kana, Hangul, Thai) are compared as strings after
their descriptor suffixes are stripped (`cjkMatch`).

Two rules also take the places' **kinds** (their icon group):

- `sameBusiness` refuses an OVERLAP between two known, different kinds: a fuel station and the
  pizza place on its lot can share their identifying words. EXACT and VARIANT still cross kinds,
  because a store and `<store> Pharmacy` are one business in two listings.
- `sameFuelLot` calls two fuel stations within `FUEL_LOT_M` (30 m) one station whatever their
  names, since the sources name a forecourt after different things (the brand, the operator, the
  shop inside). Two known house numbers that differ refuse it at any distance, which is the
  two-stations-facing-each-other-across-a-road case.

`PlaceNamesMatchTest` pins the fixture pairs and `PlaceNamesI18nTest` pins one pair per language.
Measured over the Davis fixture, 72% of Google's places link to an archive row through the rule;
in two dense downtowns 81% and 83%. The remainder is mostly places the archive does not have.

### Both mode: which copy is drawn

In Both mode Google wins a twin outright. Its coordinate is the storefront, where Overture stacks
a building's tenants on one parcel point, and its ranking comes from review counts rather than a
category prior. The fan-out waits for a 1.5 s settle, and the twin pass (`hideOpenTwins`) runs
400 ms and 2 s after each upload, only once the map has been still for `TWIN_PASS_STILL_MS`
(700 ms). It compares the open icons actually rendered on screen against the Google places
actually drawn, and hides an open pin when:

- its normalized name equals a Google place's within `DEDUPE_SAME_NAME_M` (150 m), or
- `sameFuelLot` says they are one forecourt (the Google place's house number rides on the map
  feature for this), or
- `sameBusiness` agrees within `DEDUPE_NAME_M` (80 m), with the screen's own `localGeneric`
  words passed in.

A pin already hidden is re-checked directly from the source, and released when its Google partner
is no longer drawn, so an open place is never left hidden with nothing in its place. The same
pass purges closures: an open pin that matches a permanently closed Google listing within 80 m,
with no live listing of that name within 150 m, goes into the persisted closed set. A closure is
a correction; a business that moved down the block is not. Offline nothing is hidden, because
there is nothing to compare against.

### What happens when you tap

**Which feature you tapped.** A tap resolves to what is drawn under your finger, not to whichever
layer is "more important": a search pin, then a saved pin, then the nearest of (transit stop icon,
Google ambient POI, basemap or open-places POI). A stop icon competes by distance like everything
else.

"Under your finger" is literal. The map first asks what is rendered at that exact pixel on the
icon layers, and when something is, only those compete. An icon hangs above its point like a pin,
so measuring from each candidate's own point put the shop 40 px "away" while a coin machine's dot
a few meters off sat right under the thumb. With nothing rendered at that pixel the wider touch
area decides, so a dot on its own is still tappable.

**The sheet, at once.** An open-data pin seeds the sheet from the tile: name, category, street
line plus `loc`, phone, website, and hours converted from OSM syntax (see below). In the same
moment, `offlineTwin` looks for a downloaded place-pack row within 80 m whose name agrees, and
fills any field the tap did not bring without overwriting one it did. A basemap label brings only
its name, so for those the pack is the difference between a bare title and a full sheet, and
offline it is what stays.

The sheet shows its loading state (`tapResolvingFor`) only when a lookup will actually run: not
for a transit stop, whose departure board has its own loading state, and not when Google is off.
Only a section with nothing to show yet is a skeleton: the details block skeletons when there is
no category and no hours, the body when there is no address, phone, website or hours, and the
rating and the reviews tabs always wait for the listing. "Hours not listed" is held back too,
because the map lacking hours says nothing about Google's. A skeleton section fades in over
280 ms when the listing lands (`rememberReveal`, with `ModulateAlpha` so the fade does not render
offscreen); text that was already on screen just updates. The Google listing usually has a
different id than the tapped pin, and every `remember` in the sheet is keyed on the id, so the
swap used to remount the whole sheet and flash. `sheetAlias` keeps the sheet's key at the
placeholder's id across that swap, so it recomposes once, in place. If the lookup hangs, a
watchdog (`TAP_RESOLVE_WATCHDOG_MS`, 6 s) ends the skeletons and the map's own data shows; a late
listing still fades in.

**The lookup.** Unless **"Look up tapped places on Google"** is off, the tapped name is searched
near the tap with `searchOnce`, one page of results and no nearby pass. The full three-page
search used to spend most of a tap fetching pages two and three for a chain whose first page was
already full: about 4.3 s of a 4.7 s tap on a test phone, now about 1.3 s.

First the tap decides whether it is a transit stop, from the tapped feature's kind. The open
places layer seeds taps with Overture's category, and "Gas station", "Fire station" and
"Electric vehicle charging station" all contain the word "station", which once sent every fuel
tap down the transit path: it searched for `<name> transit stop`, kept only stop listings, found
none, and linked nothing. The kind now passes the same exclusion list the results use
(`NON_TRANSIT_CAT`: fuel, charging, fire, police, broadcast, in the app's languages) before any
transit word is looked for. A real stop resolves to the nearest live transit listing within
250 m, and falls back to the Transitous board at the tapped point.

For a business, the pick is built from a series of pools, each tried only when the one before is
empty:

1. **Name matches within reach.** Listings that are the same business under
   `PlaceNames.sameBusiness`, with the town and the pool's shared words as generic, and only
   within `BUSINESS_TAP_CAP_M` (1.5 km) of the tap. A pin named for a brand agreed with seventeen
   of that brand's stations miles away, and that full pool kept every nearby fallback from
   running while the right listing, under the seller's own name, sat 11 m from the pin.
2. **The same name in another script.** When the tapped label is written in a script Google
   answers differently (kana or Han in Japan, Han, Hangul, Cyrillic, Hebrew, Thai, Arabic, Greek)
   and that is not the app's language, `crossScriptCandidates` runs the search again in the
   label's own language, keeps what agrees, and fetches the nearest one's copy in the app's
   language so the sheet's category and hours read in your language. Two requests at most, only
   on a cross-script miss.
3. **The tapped kind, already in the results** (`sameKindNear`): a listing of the same icon group
   within `NO_NAME_MATCH_M` (60 m). Free, since it is already there.
4. **The tapped kind beside the building** (`kindBesideAnchor`). An open-data row named for the
   site (`<Station> <Pizza counter>`) while Google lists the pumps under a brand finds only the
   other business in the building, which the kind rule refuses. The nearest name-agreeing listing
   on the lot is taken as the anchor, a search for the tile's category runs around it, and the
   nearest same-group listing within 60 m of the anchor is kept. One extra request, only on a tap
   that would otherwise not link.
5. **Anything on the lot**: a listing within 60 m, whatever its name.

**The house number gates all of it.** Distance alone cannot tell a fuel station from the one
across the junction, 40 to 80 m apart. When the tapped row has an address starting with a house
number (`tappedHouse`, from the tile or the pack twin) and a candidate does too, a different
number (`houseClash`) rules the candidate out of every pool without a name match, and out of name
matches beyond `SAME_LOT_M` (120 m). On the lot a mismatch is tolerated, because open-data numbers
are sometimes wrong, and a candidate whose number agrees (`houseAgrees`) is preferred. A missing
number on either side decides nothing.

Within the chosen pool:

- **A closed listing never beats a live one.** Google keeps a moved business's old, permanently
  closed profile beside the live one for months. Closed listings drop out whenever a live
  candidate exists.
- **The exact name beats a nearer one, on the lot.** Among candidates within 120 m, a listing
  whose normalized name equals the tapped name (`PlaceNames.same`) wins over one with extra
  words. That is what keeps a tap on a store from opening the brand's fuel station or pharmacy.
  It is confined to the lot because a brand's listing elsewhere can carry the exact name while
  the one under the finger says `<Name> Coffee Company`.
- **The same kind beats the same name.** Some brands name the forecourt exactly what they name
  the store, and the tapped point is the parcel, which can sit nearer the pumps than the doors.
  Among exact-name matches, one of the tapped kind wins.
- The nearest remaining listing is the pick, unless one within 35 m has clearly more reviews
  (`reviews >= 2 * nearest + 5`), which promotes the rich profile of a true duplicate.

A tap on a business never resolves into a transit stop or an intersection
(`JUNCTION_CATEGORIES`: intersection, junction, crossroads, road, highway), both of which Google
lists as places and both of which sit meters away on the same corner. The pick must also be near
the tap: 1.5 km for a business, 30 km for a settlement label, unbounded for a stop. Nothing left
means the tapped label keeps its own name, point and tile data, and the source line says "not
matched on Google".

**After the pick.** When Google's name is not in the app language's script and the map's label
is, the map's label stays as the title (`NameScript.prefer`). A resolved link is remembered per
open pin (`openPlaceCache`, 500 entries, persisted to `open_place_links.json`), so the next tap
is instant and an offline tap opens the last listing seen; a slim, count-less listing is never
remembered. The cache is dropped when the app's version changes and when a region's archive is
updated or re-downloaded, because a link made by an older, worse rule would otherwise outlive the
fix for it. A resolved listing that is permanently closed hides the open pin for good
(`open_place_closed.json`) only when no live listing of that name sits within 150 m; that list is
a correction, not a cache, and is never dropped.

**Why a tap did not link** is logged, one `VelaTap` line per tap with no coordinates: the tapped
label and kind, how many results came back and how many survived the transit filter, the size of
each pool (`agree`, `near60`, `cross`, `kindNear`, `kind`, `pool`, `exact`, `local`, `sameKind`),
the tapped house number and how many candidates clashed with it, the three nearest answers, the
pick before and after the distance cap, and `ms=search/total`. A transit-branch tap shows
`cap=2147483647m`. Together with the sheet's source line, this says which dataset needs fixing
for a place that never links.

**When Google is off.** Offline, or with "Use Vela without Google" on, nothing about the tap
reaches Google: an open pin shows its tile data (plus the pack twin), or the listing remembered
from an earlier online tap, and a basemap tap keeps its name and whatever the pack knows. No
spinner waits on a host that cannot answer. With only "Look up tapped places on Google" off, an
open pin stays on its tile data, while a basemap tap, which has nothing else to show, still
resolves.

### Open-data hours

AllThePlaces and OSM carry hours in OSM's `opening_hours` syntax. `core/util/OsmHours.lines` is
the one converter for every open source (the tile seed, the place packs, and the Overpass path
of a saved area). It turns the syntax into the same per-day lines Google gives
(`Monday: 8 AM–5 PM`), which the sheet's hours section and the open-or-closed badge already read. It covers day
ranges and lists (with or without spaces after the commas), ranges that wrap past Sunday,
several time ranges per day, rule groups separated by commas as well as semicolons, `off` and
`closed`, `24/7` and its spellings, open-ended times ("17:00+" means until midnight), overnight
ranges, later rules overriding earlier days, and sun events (sunrise, sunset, dawn, dusk) shown
as words. Public and school holidays are dropped from day lists, and a rule only about holidays
or dates is skipped, because the lines describe an ordinary week. Anything else is shown as the
text itself, cleaned of quotes, so nothing is invented.

Measured against the distinct hours strings of every tagged business in one state's place pack,
the converter went from 86% to 97.7% coverage. Before that, the pack path returned the raw tag,
which is the "weird raw string" people saw with Google off.

## Limits

- **Coordinates are only as good as the source.** OSM snapping fixes the ones OSM has mapped,
  locator snapping the chains; everything else is Overture's point, which for a big-box store can
  be the parcel centroid, and a stacked tenant with no unit in its address gets an invented ring
  slot.
- **Chains lead.** A brand is worth +1.6, which is deliberate for recognizability but does mean a
  chain pharmacy outranks a better independent one nearby.
- **The open layer has no ratings.** Ranking cannot know that a place is beloved, only what kind
  of place it is and how completely it is described.
- **Category priors are keyword lists.** A place whose category string is unusual falls to the
  1.0 tier and needs a zoom to appear. The Google-side priors are English only.
- **The bake folds EXACT and VARIANT duplicates, not OVERLAP ones.** An overlap needs the kinds
  and the pool's shared words, which only the app has, so those twins are merged on screen in
  Both mode and in the tap, never in the archive. Two more cases are in no rule yet: a non-fuel
  department listing at the same point under a different name (the shop inside a fuel station
  beside the station's brand), and Google's own two profiles for one business.
- **Cross-script linking is half built.** The tap bridges a Japanese label to an English-language
  listing; the Both-mode twin pass still compares English ambient names against the local-script
  archive, so in such a city both copies can draw. The cross-script tap has not been checked on a
  device in Japan.
- **Hours and `loc` arrive with a rebake.** A region baked before the fills and the `loc`
  property shows street-line addresses and fewer hours until its next bake. Hours that exist only
  as holiday or seasonal rules are not shown as a week.
- **A rebake is not instant.** See [chapter 2](02-data-and-rebakes.md) for when the data is
  rebuilt and how a phone picks up a new build.
