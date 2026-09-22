# 6. Search

## What you see

You tap the bar and start typing. Before you have finished the word, rows appear: first the
things that are already yours (a search you ran last week, a place you opened, a saved place, a
contact's address if you allowed it), then Google's own suggestions, then a few plain query
rows such as "Starbucks" that run a search when tapped. Every row except a contact carries a
small north-west arrow on the right that puts the row's text into the box without searching, so
you can finish a long name or address by hand.

Press Enter and you get a results list with pins on the map. The places next to you lead it, the
list can grow with a "More results" row at the bottom, and a house address you typed resolves to
that house even when the search itself only knew businesses.

Some things you type are not searches at all. "Take me home", "Davis to San Francisco", "nearest
pharmacy" and "what's my ETA" are read as actions first. With no signal, the same box searches
the region you downloaded. With Google turned off, it searches OpenStreetMap. While a route is
up, the chips and the free-text field search along it instead of around the map.

## Where the data comes from

- **Typed suggestions** come from Google's own search-as-you-type request, the keyless
  `/s?tbm=map&gs_ri=maps&suggest=p` call that the Google Maps web page fires on every keystroke.
  It is not the search endpoint, and it answers a partial address far better.
- **Results after Enter** come from Google's calibrated map search (`/search?tbm=map`), the same
  keyless scrape that opens a place. How that request is dressed and kept current is the subject
  of the "Talking to Google" chapter (planned).
- **Address help** comes from two more places. Photon (photon.komoot.io, komoot's community
  OpenStreetMap geocoder, keyless under a fair-use policy) is asked about any query that starts
  with a house number, and answers every search when Google is turned off. The on-device
  address index, built from OpenStreetMap `addr:housenumber` points and street centerlines in
  the region packs, answers without any network at all.
- **Offline results** come from the region packs: the whole-region place index and the address
  index described in [chapter 8](08-offline.md). Nothing is fetched.
- **Your own rows** (recent searches, recent places, saved places and list members, contacts)
  never leave the phone to be matched. A contact's **address string** is the one thing that goes
  out, and only when you pick the row; the name stays on the phone.

## How it is decided

### What a typed query is sent to

`MapViewModel.onQueryChange` runs on every keystroke, in this order:

1. Under two characters, everything clears and nothing else happens.
2. **Local matches, synchronously.** `localMatches()` substring-matches the text against your own
   data before any network call, so these rows are instant and are the only thing that shows
   offline.
3. **Wait for a pause.** A literal 320 ms delay; a newer keystroke cancels the pending one.
4. **Start the address side-runs**, in parallel, only when the text looks like an address:
   Photon when it starts with a house number followed by a word
   (`PhotonGeocoder.looksLikeAddress`), and the on-device geocoder when it starts with a digit
   or names a street type (`OfflineAddressStore.looksLikeAddress`: street, ave, blvd, dr, rd
   and so on).
5. **Ask Google's autocomplete** (`MapDataSource.suggest`). If it answers with anything, its
   rows are the suggestions and step 6 is skipped. The Photon call already in flight is canceled
   and its answer ignored, although by then the request has usually already gone out.
6. **Fallback race**, only when the autocomplete failed or answered nothing (offline, Google
   off, a block): the calibrated search endpoint, Photon and the local geocoder, merged.

A reply that lands after the text has changed is dropped, so a slow answer to "sta" can never
overwrite the rows for "starb".

So the honest answer to "where does my typing go" is: to Google's autocomplete on every pause;
also to Photon when it starts with a house number; and to the search endpoint only when the
autocomplete did not answer. Recent searches, saved places and contacts are matched on the
phone.

### Google's autocomplete

The request carries the text, the viewport center and the viewport's height as the bias window
(`!1d<span>!2d<lng>!3d<lat>` in the `pb`), and `hl`/`gl` rewritten to the app language and the
phone's region like every other Google request.

```
suggest window = viewport height, clamped to 2_000 .. 500_000 m
SUGGEST_SPAN_M = 20_000     // the window when there is no viewport yet: a town
```

The bias is the reason it exists. The calibrated search endpoint ranks by prominence over the
whole window, so a bare house number typed at home came back as a ZIP code in another state,
and "459 Ralston" typed from another state found businesses named Ralston and never the San
Francisco street. The autocomplete lists the nearby houses with that number first and still
finds the far street, ranked after them.

**The envelope has two quirks.** The body is `{"c":0,"d":")]}'\n<json>"}` followed by a
comment-style tail, and the app (unlike a desktop browser) gets a **second** object after that
tail, `{"c":0,"d":"","e":"<token>"}`. The first object also echoes the request URL, which can
contain braces. So `SuggestParser.unwrap` reads only the first object, finding its closing brace
by walking the text and skipping braces inside strings; "the last closing brace in the body" is
never the right one. This was found on a device the day the feature shipped, and
`SuggestParserTest` pins it.

Inside, each suggestion is a row of nulls with its content in one column, which the parser
**searches for** rather than assuming (the first array element whose first child is an array
starting with a string), so a shifted column still parses. From that block:

- the primary text (a name, or an address's street line) and the secondary line;
- a location at `[11][2..3]`, or at `[13][0][3][2..3]`;
- a feature id at `[13][0][0]`, kept only when it has Google's `0x..:0x..` shape.

**A row with a location is a place. A row without one is a query row** ("Starbucks" with "See
locations", "cvs pharmacy hours"): it shows with a search icon and no menu, and tapping it runs
that text as a search.

When the autocomplete answers, the phone shows at most eight place rows and three query rows.
The on-device geocoder's hits still lead them, with two filters: they must be within
`SUGGEST_NEAR_M` of the bias point, and a hit is dropped when a Google row within 120 m already
carries the same house number. Anything your own rows already showed is removed too (see below).

### The fallback race, when Google's autocomplete does not answer

This is the pipeline that ran before the autocomplete existed, unchanged:

```
SUGGEST_NEAR_M = 80_000     // about a metro radius
```

- **The search endpoint** answers with the viewport window and your position as the ranking
  point. Its rows are bucketed by distance with a stable sort: inside `SUGGEST_NEAR_M` first,
  Google's own order kept within each bucket. Two rows are never demoted: Google's **top** row
  and an exact name match, because for "fresno" the top row is the city itself and bucketing it
  under every nearby business with Fresno in its name pushed it off the list.
- **Photon** is asked for four rows inside a hard box around the bias point, about ±0.55 degrees
  of latitude and the matching width in longitude (roughly 60 km each way). A soft lat/lon bias
  was tried first and still let a famous far "123 Main Street" outrank every nearby one.
- **The on-device geocoder** is asked for three rows.

Address rows lead (local geocoder first, then Photon), filtered to `SUGGEST_NEAR_M` and
de-duplicated to about 5 m. An address row is only hidden by a Google row that carries the **same
house number** within 120 m. The older rule, "any Google row within 120 m covers it", ate the
address every time a business sat on the same block. At most eight rows show, and no query rows
exist on this path.

### Local suggestions and contacts

`localMatches()` builds up to six rows, in this order:

1. **Recent searches** containing the text, up to three, skipping an exact echo of what is being
   typed.
2. **Contacts** whose name contains the text, up to three (`ContactAddresses.matches`, limit 3),
   only when Settings > Search > contacts is on. It is off by default; the contacts permission
   is asked when you turn it on. The address-bearing contacts are loaded into memory once, because
   a provider query on every keystroke stutters.
3. **Recently viewed places**, then **list places**, then **saved places**, matched on name or
   address and de-duplicated by feature id, or by name plus location when there is none.

Network rows that repeat a local row are dropped, matched on **both** feature id and a name plus
location key rounded to about 5 m. The second key matters because saved and recent places carry
no feature id, so an id-only compare showed them twice.

**Picking a contact** (`openContactAddress`) geocodes the address and opens the result **under
the person's name**, with the address beneath, the way Home and Work open. Online it asks Google
search and prefers, among the top three hits, one with no rating and no category (the house
rather than the shop standing at it); offline, or when that misses, it asks the on-device
geocoder. The opened place is deliberately bare: whatever else was known about that spot is not
the contact's. If nothing geocodes, it falls back to a plain search of the address so the usual
no-results and offline messages show. Contact rows carry the person's photo and a "Contact" chip
with the address book's own label, and no fill-in arrow.

Saved places and recents enter **suggestions**, not results. A search run with Enter shows what
the providers returned; your saved places appear in it only if a provider returned them.

### The fill-in arrow

Every suggestion row except a contact, and every recent row on the empty search page, carries
Google's north-west arrow. `SuggestionRow.onFill` calls `MapViewModel.fillQuery`, which feeds the
text through `onQueryChange` (so the suggestions refresh for it) without searching, and bumps
`queryEdits` so the bar can tell the text was set from outside the keyboard.

It fills the row's **primary text only**: a place's name, an address's street line, a recent
query. Google fills the whole "name, city, state" line; that turned out to leave nothing to
refine, so Vela does not.

The design is that the cursor lands at the **end** of the filled text, through a
`TextFieldValue` owned by `SearchBar`. See Limits: the shipped field does not do that yet.

### Search on Enter

`search()` biases to what you are looking at, not where you are: the viewport center, falling
back to your position before the map has settled. It then offers the text to the query-intent
parser (next section), and only if that declines does `runSearch` run. Before the network,
`runSearch` handles two special shapes: pasted coordinates drop a pin (strict whole-string
match, so an address with numbers still searches), and a pasted Google Maps share link imports
the shared list as results with a Save offer.

Online, one search is several requests:

```
page size      = the template's !7iN token (20 today)
pages 2 and 3  = fetched together, only when page 1 came back with at least 18
NEARBY_SPAN_M  = 2_500      // the nearby pass's window, a walkable radius
SearchPb.MIN_SPAN_M = 1_000 // the window floor; the ceiling is 500_000
```

- **The window** is the real viewport height (`spanMeters`), stretched from the template's baked
  25 km so a zoomed-out search covers what you can see, floored and capped as above.
- **Three pages.** Google's keyless ranking is prominence-heavy across the whole window, so the
  modest place right next to you can rank 21st to 60th for a category. Specific names return
  short pages and never paginate, so they cost nothing extra.
- **The nearby pass.** When your position is inside the window (within half the window's height
  of its center) and the window is more than 1.5 times `NEARBY_SPAN_M` tall, a fourth request
  runs, one page over a 2.5 km window centered on **you**, and its results **lead** the list. The
  Google app weights distance the same way. Search over another neighborhood or city and no
  nearby pass runs, so Google's order for where you are looking stands.
- **The ambient merge.** Places already on the map from the ambient fan-out (chapter 1) whose
  category or name matches the query, and which the search missed, are **appended**, nearest
  first, up to 20. Appended, never reshuffled: an earlier experiment taught the project not to
  reorder Google's list. This costs no network. Address-shaped queries skip it.
- **More results.** When the three pages together returned at least 40, the list ends in a "More
  results" row. It pulls the next three pages of the same request (same query, window and ranking
  point) and appends whatever is new; the row disappears once a pull adds fewer than five, or the
  query changes.

**A typed house address the results cannot place.** When the text starts with a number followed
by a word and not one result carries that number in its name or address, the search asks the
autocomplete as a geocoder and puts up to three of its rows that do carry the number at the top.
The on-device geocoder's exact hits lead even above those, unless a Google or geocoded row within
120 m already carries the same number (Google's entry is richer, so it wins the tie).

The final list is:

```
on-device address hits + autocomplete-geocoded rows + Google's pages (nearby pass first) + ambient extras
```

Two filters then apply to what Google returned: "Hide adult categories" when it is on, and any
refinement shipped in the signed remote transforms. The detail WebViews are warmed only
**after** the results land, because building them before the fetch held a cold search at 13
seconds against 4.

If Google answers but finds nothing, the on-device index is tried before showing "No results",
because it may hold a small local place Google missed. If the request throws, the same on-device
search runs; only when that is also empty does the message say offline or failed.

### plausibleBias and rankBias

Two small rules decide which point a search is about.

- **`plausibleBias`** throws away any point within half a degree of 0,0 in both latitude and
  longitude. That is MapLibre's untouched camera on a device that never had a fix (a Wi-Fi
  tablet), or a bogus provider fix. Biasing to it ranked everything toward open ocean; no bias at
  all lets the regional ranking win. Every "near" in search goes through it.
- **`rankBias`** decides the point the **order and the shown distances** are computed from, which
  is separate from the search window. It is your position when that is within 50 km of the
  window's center, otherwise nothing (Google ranks from the window center). Searching where you
  are sorts from you; browsing a city elsewhere does not reshuffle around wherever the screen
  happens to be centered.

The suggest fetch, `runSearch`, "More results" and "A to B" use both. Search along a route uses
neither (below). "Search this area", offered when you pan while results show, re-runs the query
over the new viewport.

### Query intents

Every submitted query, typed or dictated, first goes through
`core/search/QueryIntents.parse(text, lang)`: rule-based, on the phone, with no server and no
model. It returns one of:

| Intent | Example | What happens |
| --- | --- | --- |
| Home / Work | "take me home", "go to work" | directions to the saved shortcut, opened as a bare place under the shortcut's name; a "set it first" message when unset |
| NavigateTo | "navigate to the library" | search the rest, then the top hit goes straight into the route chooser |
| Route | "Davis to San Francisco" | directions from one to the other |
| Search | "where is the nearest pharmacy near me" | the filler is stripped, "pharmacy" is searched |
| Eta | "what's my ETA" | while navigating, the remaining time is spoken and shown |

Null means plain search, so the parser can never make a query worse. There are word tables for
every app language, and English is tried as a fallback in each, because "navigate to" on a
French phone is common.

The guards are what keep it from being clever at the wrong moment:

- A **bare verb** ("go", "take me") counts only before home or work or an explicit "from A to B".
  "Go karts near me" is a search for go karts.
- A bare "X to Y" is a route only when X is not a question word or a verb. "Where to eat" stays a
  search.
- **"A to B" checks the whole phrase first.** `routeBetween` searches "A to B" as written, and if
  a listing's name contains it ("Road to Hana"), it shows those results instead of routing
  between the halves. Then it resolves the origin first and searches the destination around it,
  preferring an exact name match (the nearest one to the other end when several share a name, so
  a city and its province are not confused), then a result with no rating (a town or an address),
  then Google's first row. A miss on either end says so rather than routing from the wrong
  place.

**The fuzzy pass** runs only after the exact passes miss, and only for languages written with
spaces, because dictation mishears ("navigat to", "nearst", "ofice"). The tolerance is per word
and only over the **vocabulary**, never the place you named:

- accents fold, and an accent-only difference matches at any length;
- one edit (Levenshtein plus adjacent swaps) is allowed from four letters in a leading or
  trailing phrase and from five in a whole-phrase match, and two edits from eight letters;
- a single-word phrase never fuzzes ("fine dining" is not "find dining", "hone" is not "home");
- a multi-word phrase must match word for word, so "home depot" cannot collapse to "home".

Home and Work take the fuzzy compare in both passes, because the exact pass already owns "take me
to my ofice" once its verb matched.

### Offline

`runSearch` polls connectivity on each search. When offline, it skips Google entirely rather than
waiting for a socket timeout, and searches the downloaded region:

```
OFFLINE_ADDR_FILL  = 20     // offline rows whose blank address is filled from the address index
OFFLINE_AT_ADDR_M  = 40.0   // a place this close to a typed address is "at" it
```

- **Places** come from `OfflinePoiStore.search`: the small area-save index plus every installed
  region pack, same SQL. It matches the whole phrase and, for several words, each word of three
  letters or more, against name, category and address, and expands category words to the
  OpenStreetMap values actually stored ("gas" is `Fuel`, "coffee" is `Cafe`). Whole-phrase name
  matches are ordered first **before** the 400-row cap, so a state pack's thousands of cafes
  cannot push out the one exact name. Ranking: transit stops last unless the query asks for
  transit (a US stop is named after its corner, so any street word matched hundreds of them),
  then the number of query words hit, then distance. At most 30 rows.
- **Addresses**, when the text looks like one, come from `OfflineAddressStore.geocode` in four
  layers, stopping at the first that answers:
  1. the exact house number on the street;
  2. the position interpolated between the two nearest mapped numbers on either side;
  3. the nearest mapped house on the street (the right block, at least);
  4. the nearest point on the street's centerline, which works with no mapped houses at all.

  Street names are normalized on both sides ("W Covell Blvd", "West Covell Boulevard" and "w
  covell blvd" are the same row), so the fixture address 1451 W Covell Blvd, Davis, CA 95616
  resolves however it is typed.
- **Businesses at the address.** For the first three address hits, the places within 40 m are
  listed **ahead** of the bare house point, with the typed address filled in when they had none.
  A typed address is usually a way of naming the shop on it.

The merged order is businesses at the address, then addresses, then places when the text looked
like an address; otherwise places, then addresses. If nothing matches, the message distinguishes
"you have a downloaded area and this is not in it" from "download an area first".

While typing offline, you get your own rows and the on-device geocoder's address rows; the place
index is not consulted until Enter. What the region download holds, and how big it is, is
[chapter 8](08-offline.md).

### Google off

With Settings > Privacy > "Use Vela without Google" on, the data source never calls Google:

- `suggest` answers empty, so typing always runs the fallback race, with the search endpoint's
  part answered by Photon too.
- `search` is two Photon calls merged: 20 rows ranked by Photon's own importance with a soft bias
  toward you, then 10 from the hard metro box for the partial-address case. The box used to lead,
  and on a device it showed fuzzy address rows two states away and never the city itself.
- "More results", the nearby pass and the ambient merge have nothing to work with: the page
  search and the ambient fan-out answer empty.

Photon knows names and addresses, not categories. "Coffee" finds places with coffee in the name;
the region packs understand categories, but online they are consulted only when Photon returns
nothing. Photon speaks English, German and French; other languages get its default names.

### Search along a route

With a route on screen (the chooser's chips, the place sheet's along-route chips, or the in-nav
search field), `searchAlongRoute` behaves differently from the map search in four ways:

```
maxMeters = 3_000.0   // RouteCorridor.alongRoute: a result must be this close to the route line
```

- **One window, centered on the route's midpoint**, with the template's own size (about 25 km)
  and no ranking point. The three pages still run, but with no ranking point there is no nearby
  pass, and there is no ambient merge, no house-number geocoding and no intent parsing.
- **A corridor filter.** `RouteCorridor.alongRoute` keeps results within 3 km of the polyline.
- **Travel order.** The list is sorted by how far along the route each result sits, and the
  distance shown is that along-route distance, not the crow-flies distance from the midpoint
  (which read as two stations at opposite ends of the trip both "5.9 mi" away).
- **A pick becomes a stop.** Planning a trip, the destination is stashed and the picked place is
  added as a stop, returning you to the chooser; closing the search returns to the trip too.
  Navigating, a pick from the list becomes the next stop on the live drive. A stray tap on the map
  during a drive does not.

It shares the single-flight job with the map search, so a plain search and an along-route search
cancel each other rather than landing out of order.

### The category chips

One list, `ui/QuickCategories`, feeds the map's chip row, the route chooser's along-route row, the
in-nav search and the Android Auto along-route list, in this order: Restaurants, Coffee, Gas,
Groceries, Things to do, Hotels, Bars, EV charging, Parking, Pharmacy, ATMs, Parks, Hospitals,
Banks, Post office, Campgrounds.

Each chip sends a **stable English query**, which Google understands in any locale and the
offline store expands; only the label is translated. Two exceptions: the fuel chip sends "Petrol
station" in regions whose English says petrol, and the Bars chip is removed while "Hide adult
categories" is on, since that filter would empty it. Every chip's query must be one the offline
store can expand, or the chip is dead offline.

On the map, a chip is a typed search (`quickSearch` sets the text and calls `search()`); on a
route it is `searchAlongRoute`.

### Android Auto

The car's search screen (`SearchCarScreen`) follows the phone's split between typing and
submitting:

- **While typing**, after a 300 ms pause, it asks the autocomplete over a 20 km window around the
  car's last known position (`SUGGEST_SPAN_M = 20_000` on the car side too). When the autocomplete
  answers nothing, it runs the full search instead.
- **On submit**, or when a query row is tapped, it runs the full search.
- Up to two matching contacts lead the list when contacts search is on; six rows show in total.
  Tapping a result previews a route to it.

Before 2026-09-22 it ran the full search on every keystroke. That is three pages plus a nearby
pass per letter, and canceling a coroutine does not abort an OkHttp call already running, so a
typed word queued a dozen requests behind the per-host connection limit and the head unit's
spinner waited for the whole backlog. Only a submit shows the spinner now; while typing, the
previous rows stay until the next answer replaces them, and a superseded keystroke's rows are
never published.

Mid-drive, the car does not offer typing. Its along-route list is the quick categories; a pick
searches **around the car**, sorts by distance, and adds the result as the next stop. It is not
corridor-filtered like the phone's. The car chapter (planned) covers the rest.

## Limits

- **The fill-in arrow does not move the cursor to the end yet.** SPEC, CLAUDE and FEATURES
  describe a `TextFieldValue` in `SearchBar` that puts the cursor at the end of filled text. The
  shipped `BasicTextField` still takes a plain `String`, and the `fillTick` parameter that carries
  `queryEdits` is accepted but unused. A plain String field keeps the old cursor position across
  an outside change, which is exactly the "cursor stayed mid-text" behavior the design was meant
  to fix.
- **Photon hears address-shaped typing even when Google answers.** Its request starts in parallel
  with the autocomplete and is only canceled afterwards, so the query has already been sent.
- **Offline address fill is inverted.** The code's intent is to fill the blank addresses of the
  first 20 offline rows. The condition skips rows 0 to 19 and fills rows 20 onward instead, so the
  rows you see first read as bare names until you open one (opening a place backfills its address
  from the index).
- **The offline fallback after a failed online search is thinner** than the straight offline path:
  it has no address fill and no "businesses at this address" step.
- **Search along a route is one window at the route's midpoint.** On a trip much longer than the
  roughly 25 km window, stops near either end are simply not in the answer; there is no per-leg
  sampling. It also has no offline path: with no signal it reports that the search failed.
- **The car has no offline search.** `SearchCarScreen` calls the data source directly, and the
  region packs live in the phone's view model, not the data source, so a car search with no
  signal returns nothing (despite a code comment saying the packs answer).
- **Google off loses categories online.** Photon has no category search, and the packs are
  consulted only when Photon returns nothing.
- **Intents need a table.** A language without a word table gets English and plain search; Chinese
  and Japanese get no fuzzy pass.
- **Your saved places do not enter the results list**, only the suggestions. A search for the name
  of a saved place relies on the provider finding it again.
