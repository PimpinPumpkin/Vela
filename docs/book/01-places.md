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
| **Both** | The open layer, topped up by one Google request per settled view | Once per settled view | The open half does |
| **Google** | Google's answer for the current view, refetched on every pan | Every pan | No |

The default is Vela data, in the shipped config and in the compiled fallback
(`MapPoiPrefs.placesSource = SOURCE_OPEN`, `Calibration.defaultPlacesSource = "open"`). A phone
only ends up on another mode because someone picked it.

## Where the data comes from

**Overture Maps Places** is the base: an open business dataset maintained by Meta, Microsoft and
others, read straight from its public S3 bucket at bake time.

**AllThePlaces** (CC0) fills Overture's biggest hole. Overture's places come largely from Meta
and Bing, so a chain store with no Facebook presence is simply absent. AllThePlaces scrapes each
chain's own store locator weekly and publishes the world as one PMTiles file; the bake pulls the
region's z15 tiles out of it with a few range requests. A locator point that already has an
Overture row of the same brand, or the same leading name words, within ~150 m is dropped as a
duplicate; the rest join with a lower confidence than Overture's own. Chain rows carry
`opening_hours`, which Overture never has, which is why some open-data places show hours offline.

**OpenStreetMap** does two jobs. In the bake it is the *first choice for a place's coordinate*:
named business nodes are extracted with `osmium`, and a place whose name matches one snaps to the
OSM point. OSM maps the shop where the shop is, and when it is wrong anyone can fix it in a
minute and every map benefits, which is not true of a parcel centroid in a bulk dataset. Second,
on the phone, **Settings > Places > "OpenStreetMap shops too"** (on by default) also draws the
businesses already present in the basemap tiles, for what the baked layer lacks. Doubles are
dropped by name.

The result is one PMTiles archive per region on the `places-overlays` release, streamed by HTTP
range requests as you pan, or downloaded whole with a region for offline use.

## How it is decided

### Which places exist in a tile, and at what zoom

The bake scores every place, then assigns it a minimum zoom from its rank inside a grid cell.

**Prominence** is a category prior plus signals:

| Kind of place | Prior |
| --- | --- |
| Hospital, university, college, airport, stadium, museum, zoo, amusement park, shopping center, supermarket, department store, grocery store, convention center, casino, aquarium | 4.5 |
| Hotel, pharmacy, bank, cinema, gym, library, place of worship, bowling alley, hardware store, car dealer, furniture, electronics, sporting goods, home improvement, wholesale club, discount store | 3.2 |
| Restaurants and cafes, bars, bakeries, gas stations, EV charging, auto repair, car wash, pet store, bookstore, clothing, shoes, jewelry, florist, liquor, tobacco, toys, bicycles, dentist, vet, optometrist, urgent care, post office, ATM, laundromat, dry cleaner, barber, salons, spa, tattoo | 2.2 |
| No category at all | 1.6 |
| Everything else | 1.0 |

plus `+1.6` for a known brand, `+0.5` for a website, `+0.4` for a phone, `+0.2` for an address,
and `(confidence - 0.5) * 1.6` from Overture's own confidence. So a supermarket with a brand and
contact details lands near 7, and a nameless one-off near 1.

Each place is then ranked by prominence inside four nested grid cells: `frank` (~100 m),
`rank` (~400 m), `crank` (~1.6 km) and `xrank` (~6.5 km). The minimum zoom follows:

| Condition | Appears from |
| --- | --- |
| `crank = 1` and prominence >= 6 | z13 |
| `crank <= 2` or prominence >= 5 | z14 |
| `rank <= 3` or prominence >= 4.5 | z15 |
| `rank <= 12` or prominence >= 3.5 | z16 |
| everything else | z17 |

So a downtown thins to its landmarks as you zoom out and a village keeps its one cafe at z15.

Two demotions run before that. A **tenant** (a counter or kiosk inside another business) has its
prominence cut by 2.0 and draws as a dot until z18.5, so a supermarket's in-store sushi bar
cannot take the supermarket's label; fuel is exempt, because a fuel kiosk really is the thing you
are looking for.

### Which of the places in a tile get an icon, a label, or a dot

The tile can hold more than the map should draw, so the app decides per zoom. Labels are tiered
by zoom against prominence: below z15.5 only prominence >= 6.0 is named, from z15.5 >= 5.0, from
z16.5 >= 3.0, and from z17.5 everything. An unnamed symbol skips label placement entirely, which
is also most of the rendering cost.

Note that Vela's zoom number reads about one lower than Google's for the same visible area, a
consequence of 512 px tiles. Compare the two by matching the area on screen, never the z number.

### Google's own ranking, when Google is drawing

In Google or Both mode the pins come from a fan-out of about 13 per-category searches, each
ordered by its own relevance. There is no global ranking in that answer, so Vela computes one:

```
prominence = ln(reviewCount + 1) * (0.6 + rating / 10)      // how many people know it
           + (categoryPrior - 2.2) * 0.9                    // what kind of place it is
```

The category prior is the same 1.0 to 4.5 scale the bake uses, applied as a difference from the
everyday-business tier, so an ordinary restaurant's number is unchanged and the label tiers above
keep meaning what they meant. Anchors rise, places with no category at all sink. Google's
category text arrives in the app's language and the keyword table is English, so a non-English
session gets the neutral prior and the old reviews-only ranking.

The layer is capped per zoom (`ambientCapMin` to `ambientCapMax`, both remotely tunable), and the
cap keeps the top of that ranking.

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

### What happens when you tap

A tap resolves to the nearest candidate under your finger, not to whichever layer is "more
important": a search pin, then a saved pin, then the nearest of (transit stop icon, Google
ambient POI, basemap or open-places POI). A stop icon competes by distance like everything else.

For an open-data place the sheet is seeded from the tile itself (name, category, address, phone,
website, and hours when AllThePlaces supplied them), so it reads with no signal. Then, unless
**"Look up tapped places on Google"** is off, it is matched to a Google listing for hours,
reviews, photos and busy times. The match is name-first: the pool is listings whose name shares
words with the tapped label, and only if none agrees does it fall back, to listings within 60 m.
A tap on a business never resolves into a transit stop or an intersection, both of which Google
lists as places and both of which sit meters away on the same corner.

## Limits

- **Coordinates are only as good as the source.** OSM snapping fixes the ones OSM has mapped;
  everything else is Overture's point, which for a big-box store can be the parcel centroid.
- **Chains lead.** A brand is worth +1.6, which is deliberate for recognizability but does mean a
  chain pharmacy outranks a better independent one nearby.
- **The open layer has no ratings.** Ranking cannot know that a place is beloved, only what kind
  of place it is and how completely it is described.
- **Category priors are keyword lists.** A place whose category string is unusual falls to the
  1.0 tier and needs a zoom to appear.
- **A rebake is not instant.** See [chapter 2](02-data-and-rebakes.md) for when the data is
  rebuilt and how a phone picks up a new build.
