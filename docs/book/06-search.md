# 6. Search

## What you see

Tap the bar and type. From the second character rows appear: first your own (recent searches,
places you opened, saved places, a contact's address if you allowed it), then place suggestions,
then a few plain query rows such as "Starbucks" that run a search when tapped. Every row except
a contact has an arrow that puts its text in the box without searching, so you can finish a long
name or address by hand.

Press Enter and you get a results list with pins on the map. Places next to you lead, a "More
results" row can extend the list, and a typed house address resolves to that house even when the
search itself only knew businesses. Pan the map while results show and "Search this area"
appears.

Each result is a card: the name, one line of facts (rating, count, distance, price, kind), the
open or closed line, up to three amenity ticks, up to three photos, and buttons that act without
opening the place (Directions, Call, Website, Share, and Menu when there are menu photos). All
of it comes from the one search reply. Nothing is fetched per result, so most cards have one
photo and none has a review line. The address shows when the place is unrated, when its name
repeats in the list, or when the card has no photos.

Some things you type are commands. "Take me home", "Davis to San Francisco", "nearest pharmacy"
and "what's my ETA" are read as actions first. With no signal the box searches the regions you
downloaded. With Google turned off it searches open data. While a route is up, the chips and the
search field search along it.

With nothing typed, the search page shows your recent searches and places, and the saved places
and routes you pinned. Pins are off by default. The bookmark button's sheet lists everything you
saved, each row with a pin toggle.

## Where the data comes from

| What | Source |
| --- | --- |
| Suggestions while typing | Google's autocomplete, the keyless `/s?tbm=map&gs_ri=maps&suggest=p` request the Google Maps web page sends on each keystroke |
| Results after Enter | Google's map search (`/search?tbm=map`), the same keyless request that opens a place. [Chapter 7](07-talking-to-google.md) covers how it is built and kept current |
| Address help | Photon (photon.komoot.io, komoot's keyless OpenStreetMap geocoder), and the on-device address index built from OpenStreetMap house numbers and street centerlines in the region packs |
| Offline results | The downloaded place packs, places archives and address index ([chapter 8](08-offline.md)) |
| Results without Google | Photon, plus Vela's own places data, downloaded or streamed |
| Your own rows | Recent searches and places, saved places, list members and contacts, matched on the phone |

While you type, the text goes to Google's autocomplete on each pause, to Photon when it starts
with a house number, and to Google's search only when the autocomplete does not answer. A
contact's address string is sent to the geocoder when you pick the row. The name stays on the
phone. A long press on the map is a reverse geocode and goes to OpenStreetMap's Nominatim.

## How it is decided

Thresholds not listed here are in [SPEC section 5.6](../../SPEC.md).

### While you type

`MapViewModel.onQueryChange` runs on every keystroke:

1. Under two characters everything clears.
2. `localMatches()` matches the text against your own data with no network, so those rows are
   instant.
3. It waits 320 ms. A newer keystroke cancels the pending one.
4. If the text looks like an address, two lookups start in parallel: Photon when it starts with
   a house number and a word (`PhotonGeocoder.looksLikeAddress`), and the on-device geocoder
   when it starts with a digit or names a street type (`OfflineAddressStore.looksLikeAddress`).
5. It asks Google's autocomplete (`MapDataSource.suggest`). If that returns any row, those are
   the suggestions and Photon's call is canceled.
6. If the autocomplete fails or returns nothing (offline, Google off, a block), the fallback
   runs: the search endpoint, Photon and the on-device geocoder, merged.

A reply that lands after the text changed is dropped.

#### The autocomplete

The request carries the text, the viewport center and the viewport height as the bias window,
clamped to 2 to 500 km. `SUGGEST_SPAN_M` (20 km) is the window before the map has a viewport,
and a typed address gets at least `ADDRESS_SEARCH_SPAN_M` (40 km). The search endpoint ranks by
prominence over the whole window, which answers a bare house number with a ZIP code in another
state. The autocomplete lists the nearby houses with that number first.

A suggestion with a location is a place row. One without is a query row ("Starbucks", "cvs
pharmacy hours"), and tapping it runs that text as a search. At most eight place rows and three
query rows show. On-device address hits lead them when they are within `SUGGEST_NEAR_M` (80 km)
of the bias point and no Google row within 120 m carries the same house number.

When the view is 50 km or more from you and no suggestion's name starts with what you typed,
`homeSuggestions` sends one more autocomplete request around you and puts up to three matching
rows first. It needs four characters and skips addresses.

Two parsing rules in `SuggestParser`:

- The response is a `{"c":0,"d":"..."}` object, a comment tail, and for the app a second object.
  The first object echoes the request URL, which can contain braces. `SuggestParser.unwrap`
  finds the end of the first object by walking the text and skipping braces inside strings. The
  last closing brace in the body is the wrong one.
- A suggestion is a row of nulls with its content in one column. The parser searches for that
  column (the first array element whose first child is an array starting with a string), so a
  shifted column still parses. The field paths inside it come from calibration.

#### The fallback

- The search endpoint's rows are sorted into two buckets, inside `SUGGEST_NEAR_M` first, with
  Google's order kept within each. Google's top row and an exact name match are never demoted,
  because for a city's name the top row is the city itself.
- Photon is asked for four rows inside a hard box of about 60 km each way around the bias point.
  A soft bias let a famous far "123 Main Street" outrank every nearby one.
- The on-device geocoder is asked for three rows.

Address rows lead, on-device first and then Photon, limited to `SUGGEST_NEAR_M` and
de-duplicated to about 5 m. A Google row hides an address row only when it is within 120 m and
carries the same house number. Hiding on distance alone removed the address whenever a business
shared the block. At most eight rows show, with no query rows.

### Your own rows and contacts

`localMatches()` returns up to six rows: up to three recent searches containing the text, up to
three contacts whose name contains it, then recently viewed places, list places and saved places
matched on name or address. Network rows that repeat one of these are dropped. The match is on
feature id and on name plus location rounded to about 5 m, since saved and recent places carry
no feature id.

Contacts are matched only when Settings > Search > "Search your contacts" is on. It is off by
default and asks for the contacts permission when turned on. Contacts with a postal address are
loaded into memory once (`ContactAddresses`), because a provider query per keystroke stutters. A
contact row shows the person's photo and the address book's label for the address.

Picking a contact (`openContactAddress`) geocodes the address and opens it under the person's
name, the way Home and Work open. Online it asks Google search and takes, among the top three
hits, one with no rating and no category, so the house wins over a shop at the same address.
Offline, or when that misses, it asks the on-device geocoder. The opened place carries only the
name, the point and the address, so a business at that spot does not lend it a rating or hours.
If nothing geocodes, the address runs as a plain search.

A saved pin or address opens as saved, with no lookup. A saved business looks up its listing
for photos and reviews. It accepts a hit within `SAVED_ENRICH_SAME_SPOT_M` (30 m), or within
`SAVED_ENRICH_MAX_M` (250 m) when the names agree, and keeps a name you gave it.

A place starred or put in a list from a map label before its listing had loaded, or with no
connection, is kept with the label's name and point only. The next time it is opened online
with Google on, or its label is tapped, it is looked up by that name. A live listing
within `KEPT_LABEL_MAX_M` (250 m) whose name agrees is kept from then on: the star reopens as a
business and each list entry gets the listing's id and address. The name, note and icon you
set stay. A star you renamed is yours and is not looked up.

Saved places and recents appear in suggestions only. Results after Enter are what the providers
returned.

The fill-in arrow (`MapViewModel.fillQuery`) puts the row's primary text in the box: a place's
name, an address's street line, a recent query. It refreshes the suggestions and leaves the
cursor at the end.

### Search on Enter

`search()` biases to the viewport center, or to your position before the map has one. The text
first goes to the intent parser (below). If that declines, `runSearch` checks two shapes before
searching. Pasted coordinates drop a pin. A pasted Google Maps share link opens the place it
points to, imports a shared list as results with a Save offer, or imports a My Maps custom map.
With Google off, list and custom map imports are refused, and a short link is resolved only
while "Open shared Google Maps links" is on.

Online, one search is up to four requests:

- The window is the viewport height, between `SearchPb.MIN_SPAN_M` (1 km) and 500 km.
- Page one, then pages two and three together when page one came back within two rows of full.
  The page size is the template's `!7i` token, 20 today. Google's keyless ranking favors
  prominence across the whole window, so a small place next to you can rank 21st to 60th for a
  category. A specific name returns a short page and costs nothing extra.
- The nearby pass is one page over a `NEARBY_SPAN_M` (2.5 km) window centered on you, and its
  results lead the list. It runs when you are within half the window's height of its center and
  the window is more than 1.5 times `NEARBY_SPAN_M` tall. A search over another city keeps
  Google's order.

Two steps follow with no request:

- The ambient merge appends places already on the map from the ambient fan-out
  ([chapter 1](01-places.md)) whose category or name matches and which the search missed,
  nearest first, up to 20. Google's order is left alone. Address queries skip it.
- "Hide adult categories" and any refinement in the signed remote transforms filter what Google
  returned.

When the three pages returned at least 40 rows, the list ends in "More results". It fetches the
next three pages of the same request and appends what is new. The row goes away once a pull adds
fewer than five.

If Google answers with nothing, the on-device search runs before "No results" shows. If the
request fails, the same on-device search runs, and only when that is empty too does the message
say offline or failed.

"Search this area" (`searchThisArea`) reruns the query over the new viewport and keeps the
results inside the view plus 10% (`AreaNarrow`). When none are inside, the whole answer shows.

The results sheet shows while there are results, no place is open and the search field is closed
(`SearchGates`). After a search from a view under 2.5 km tall, the camera stays where it is when
enough results fall in the visible part of the map: one for a name, three for a kind of place
(`SearchKind`). Otherwise it frames the first 12 results.

### Other locations of a business

Google answers a business name with one focused place wherever the map is. `SearchParser` adds
the branches named in that place's related block whose names carry the query. With Settings >
Search > "Find other locations automatically" on (the default), the search also asks for
"<name> near me" over a `BRANCH_SPAN_M` (30 km) window around the focused place, which returns a
list with full cards. Same-name rows within 50 m merge, and up to `BRANCH_FILL_MAX` (3) branches
without an address are looked up one by one. That is one to five extra requests. With the
setting off, the list ends in "Show other locations" and the requests run on a tap
(`searchBranches`).

### A typed street address

`AddressQuery.parse` recognizes text that starts with a house number and a street. A result is
that address when the house number and the street's first distinguishing word (not a direction,
not "St" or "Ave") are both whole words of its name or address. A substring test found the
digits in ZIP codes and in neighbors' numbers and took a list of businesses for the address.

When no result within `ADDRESS_SEARCH_SPAN_M` of the view is the address, the search asks the
autocomplete to geocode the text and takes up to three rows that are. The same constant is the
least window an address is searched over, so zooming in to one building does not shrink it. The
on-device geocoder adds up to three hits ahead of those, unless a result within 120 m already is
the address. An on-device estimate (an interpolated position, or a point on the street) is
dropped whenever any other result is the address.

Rows that are the address come first, ordered by how well they agree with the whole typed street
(`AddressQuery.score` counts the direction and the street type), then by distance. The list is:

```
on-device address hits + address rows + Google's pages (nearby pass first) + ambient extras
```

### Which point a search is about

- `plausibleBias` discards a point within half a degree of 0,0. That is the map's untouched
  camera on a device that never had a fix, and biasing to it ranks everything toward open ocean.
- `rankBias` picks the point that the order and the shown distances are computed from. It is
  your position when you are within 50 km of the window's center. Otherwise Google ranks from
  the window center, so browsing another city does not reorder around you.
- A place opened by tapping the map or from Saved and Recent is looked up around its own point,
  so that lookup's distance is to itself. `fromHere` replaces it with the distance from you, or
  with none when there is no fix.

When the window is 50 km or more from you and no result's name matches the query, `homeNameHits`
searches once around you. Results there that match the name exactly or up to generic words
(`PlaceNames`), are not permanently closed and are within `HOME_NAME_MAX_M` (40 km) of you
replace the far results. A chip's query, an address and anything under four characters never
trigger it, so "coffee" over Tokyo means Tokyo's coffee.

### Query intents

Every submitted query, typed or spoken, first goes through `QueryIntents.parse(text, lang)` in
`core/search`. It is rule-based and runs on the phone.

| Intent | Example | What happens |
| --- | --- | --- |
| Home / Work | "take me home", "go to work" | Directions to the saved shortcut, opened under the shortcut's name. A message when it is unset |
| NavigateTo | "navigate to the library" | Searches the rest, then opens the route chooser on the top hit |
| Route | "Davis to San Francisco" | Directions from one to the other |
| Search | "where is the nearest pharmacy near me" | Strips the filler and searches "pharmacy" |
| Eta | "what's my ETA" | While navigating, speaks and shows the remaining time |

Null means plain search. There are word tables for 15 languages, which covers every app language
except Estonian. English is tried as a fallback in each, because "navigate to" on a French phone
is common. Settings > Search lists example phrases (`VoiceCommandExamples`), and a test parses
every one.

The mic turns speech into text with Vela's on-device model when it is installed, or with an
installed voice-input app, and is hidden when neither exists (`VoiceSearch`).

Guards:

- A bare verb ("go", "take me") counts only before home or work, or before an explicit "from A
  to B". "Go karts near me" is a search.
- A bare "X to Y" is a route only when X is not a question word or a verb. "Where to eat" stays
  a search.
- `routeBetween` first searches the whole phrase "A to B". If a listing's name contains it,
  those results show. Otherwise it resolves the origin, then searches the destination around
  it. For each end it prefers an exact name match (the one nearest the other end when several
  share a name), then a result with no rating and no category, then Google's first row. When
  the destination is not found it stops with a message. When the origin is not found the route
  opens from where you are and a message says so.

A fuzzy pass runs after the exact passes miss, for languages written with spaces, because
dictation mishears ("navigat to", "nearst"). It applies per word to the vocabulary only, never
to the place you named:

- an accent-only difference matches at any length;
- one edit is allowed from four letters (five when the whole query must equal the phrase), and
  two from eight;
- a single-word phrase never fuzzes, so "fine dining" is not "find dining";
- a multi-word phrase must match word for word, so "home depot" cannot become "home".

Home and Work phrases take the fuzzy compare in the exact pass too. Without that, "take me to my
ofice" would return as a NavigateTo before the fuzzy pass ran.

### Offline

`runSearch` checks connectivity on each search. Offline it skips Google, which would only wait
for a socket timeout, and searches the downloads.

Places come from two sources, ranked together by `OfflineRank`:

- `OfflinePoiStore.search` reads the area-save index and every installed place pack. It matches
  the whole phrase and, for several words, each word of three letters or more, against name and
  category, and the whole phrase against the address. Category words expand to the
  OpenStreetMap values stored ("gas" is `Fuel`, "coffee" is `Cafe`). A query that is itself a
  category ("Gas station") is not split, because "station" matched every charging station. Names
  compare with accents folded, apostrophes and periods dropped and hyphens as spaces
  (`OfflineRank.fold`), so "mcdonalds" finds "McDonald's" and "cafe" finds "Café". Only Latin
  letters are folded: in other scripts the marks are part of the spelling (the voicing marks on
  kana, Thai and Devanagari vowel signs), and a name is compared as typed. The packs
  store names as OpenStreetMap wrote them and SQLite cannot fold accents, so
  `OfflinePoiStore.nameMatch` does it in the query: a plain name is matched by LIKE, and a name
  with any letter outside ASCII by a GLOB pattern in which each letter is a class of its forms
  (`OfflineRank.glob`: `[eEéÉèÈ...]`, from the Latin-1 and Latin Extended-A and B blocks and the
  Vietnamese vowels). A query holding "ss" is also tried with "ß". The pattern is kept off plain
  names because it costs several times what LIKE does per row. The SQL orders whole-phrase name
  matches first and then the nearest rows before its 400-row cap, so a state pack's thousands of
  cafes cannot push out an exact name or the ones near you.
- `PlacesArchiveSearch` reads the downloaded places archives the map draws (Overture,
  AllThePlaces and OpenStreetMap). It reads the deepest zoom in rings of tiles out from the
  search point until it has 60 matches or reaches `MAX_RINGS` (12 rings of z17 tiles, about
  3 km). Downtown Davis: 144 restaurants in 42 ms.

The ranking puts transit stops last unless the query asks for transit, then rows that hit more
query words, then names in which the query starts a word ("shell" puts Shell ahead of a nearer
Seashell Cafe; Target and Target Optical stay tied), then the nearest. A category query keeps to `OfflineRank.CATEGORY_MAX_M` (100 km)
of the search point, so with no data for the area it answers nothing instead of listing a
downloaded state far away. The same name within 120 m is one place. At most 30 rows return.

Addresses, when the text looks like one, come from `OfflineAddressStore.geocode`, which stops at
the first layer that answers:

1. the exact house number on the street;
2. the position interpolated between the nearest mapped numbers;
3. the mapped houses on the street, nearest first;
4. the nearest point on the street's centerline, which needs no mapped houses.

Street names are normalized on both sides, so "W Covell Blvd" and "West Covell Boulevard" match.

For the first three address hits, places within `OFFLINE_AT_ADDR_M` (40 m) are listed ahead of
the bare house point, since a typed address usually names the business on it. When the text
looked like an address the order is businesses at the address, addresses, places. Otherwise it
is places, then addresses. The first `OFFLINE_ADDR_FILL` (20) place rows that lack an address
get one from the address index, completed with city, state and ZIP from nearby rows.

When nothing matches, the message tells "not in your downloaded area" apart from "download an
area first". While typing offline you get your own rows and on-device address rows. The place
index is read on Enter.

### Without Google

With Settings > Privacy > "Use Vela without Google" on, the data source never calls Google.

- `suggest` returns nothing, so typing always runs the fallback, with Photon answering the
  search part.
- `search` is two Photon calls run together: 20 rows by Photon's own ranking with a soft bias
  toward you, then 10 from the hard box for partial addresses. Photon answers in English, German
  and French. In any other app language the ranked call asks in English, so a landmark is found
  by the name people type.
- `runSearch` also runs `googleFreeLocal`: the place packs plus the places archive. The archive
  is read from the download or, with none there, streamed from the release host by HTTP range
  requests (`PmtilesReader.Archive.http`), up to `MAX_RINGS_STREAMED` (8 rings, about 2 km). In
  a Delaware downtown, "Restaurants" returned 79 matches from 5 requests and 265 KB in under a
  second the first time, and from 3 requests in 84 ms once the directory was cached.
- A category query is answered by Vela's data alone when it finds anything. So is a name it
  finds near the view: within the view's height of its center, at least `GOOGLE_FREE_NEAR_M`
  (3 km) and at most `GOOGLE_FREE_NEAR_CAP_M` (25 km). Asking Photon as well took most of ten
  seconds and returned the same name in other towns, which pulled the map away from the one on
  screen.
- Addresses, and names with no match near the view, also go to Photon. Vela's rows come first,
  and a Photon row with the same name within 120 m is dropped.
- Over a view taller than `GOOGLE_FREE_WIDE_M` (50 km), a name search puts Photon's rows first,
  keeps at most five of Vela's rows that carry every typed word, and opens the top hit when its
  name is exactly what was typed. "Eiffel tower" from a world view goes to Paris.
- "More results", the nearby pass, other locations and the ambient merge do nothing.

Photon knows names and addresses, not categories. Categories come from Vela's data.

### Links from other apps

A link that asks for directions opens the route chooser on its destination. `MapLinkParser`
reads four shapes: `maps?saddr=..&daddr=..` (the last of several places is the destination),
`maps/dir/?api=1&destination=..&origin=..&travelmode=..`, `maps/dir/A/B/`, and the
`google.navigation:` intent. A coordinate becomes a pin with its address looked up. A name runs
a search and takes the top hit. The link's travel mode applies to that trip only. A start within
150 m of you, "Current Location" or a blank start means from here.

A link with more than two places opens as a trip with stops, up to ten. That covers a trip
planned in Google Maps on a desktop: copy the address bar, and either open the link on the
phone (from a calendar event, a note, a message) or paste it into Vela's search box. The
desktop link usually carries each place's coordinate, so those places are not looked up by name
and the trip is the one that was planned, in its order. Points dragged onto the route on the
desktop come along too: the route passes through them and a drive treats them as silent stops,
never announced or listed. Editing the stops lets go of them.

A trip link opens all at once. The route chooser comes up straight away with the start, every
stop and the destination listed by the name or address the link carries, each with a spinner
that turns into a check. The destination is looked up first, near you, and then every other
place together, near the destination. Only when all of them have answered is the trip routed,
once. A lookup that has not answered in 8 seconds counts as not found, so one bad name cannot
hold the trip. A stop that finds nothing is left out of the route, and a dialog says which one
("Could not find Woodland, CA. The route skips it.") with "Search for it" and "Skip it". Search
opens the stop search on the link's own words, and the place you pick goes back into the trip
where the link had it. Several missing places are asked about one at a time, a start that finds
nothing last ("Choose a start" or "Start where I am"). The card keeps one line, "1 place not
found", with "Fix" to open the dialog again. A destination that finds nothing has no trip to show, so
the chooser closes and the status says which name failed.

A plain location link (a `geo:` link, a shared place) shows the place. Settings > Navigation >
"Links from other apps" changes that. "Open directions" sends location links to the route
chooser too. "Start navigation" starts the drive for both kinds once a route exists, after the
same checks the Start button makes (precise location, the notification permission). It suits
delivery apps that hand over one stop after another.

### Search along a route

With a route on screen, the chooser's chips, the place sheet's along-route chips and the in-nav
search field call `searchAlongRoute`:

- It is one search centered on the route's middle point, with the template's own window (about
  25 km) and no ranking point. There is no nearby pass, ambient merge, address geocoding or
  intent parsing.
- `RouteCorridor.alongRoute` keeps results within 3 km of the line and sorts them in travel
  order. The distance shown is the distance along the route to the result plus its distance off
  the line.
- During a drive it searches only the route ahead of the car (`RouteCorridor.ahead`, cut at
  `NavState.traveledM`), so places already passed drop out and distances count from the car.
- A pick becomes a stop. While planning, the destination is kept, the pick is added as a stop
  and you return to the chooser. Closing the search returns there too. While navigating, a pick
  from the list becomes the next stop. A tap on the map during a drive does not add one.

It shares one job with the map search, so the two cancel each other and cannot land out of
order.

### Category chips

`ui/QuickCategories` is the one list behind the map's chip row, the along-route rows, the in-nav
search and the car's lists: Restaurants, Coffee, Gas, Groceries, Things to do, Hotels, Bars, EV
charging, Parking, Pharmacy, ATMs, Parks, Hospitals, Banks, Post offices, Campgrounds. The
in-nav search puts Gas and EV charging first (`forDrive`).

Each chip sends a fixed English query, which Google understands in any locale and the offline
store expands. Only the label is translated. The fuel chip sends "Petrol station" in regions
whose English says petrol. Bars is removed while "Hide adult categories" is on, since that
filter would empty it. A new chip's query must be one the offline store can expand, or the chip
finds nothing offline.

On the map a chip is a typed search (`quickSearch`). On a route it is `searchAlongRoute`.

### Android Auto

The car's search screen (`SearchCarScreen`) splits typing from submitting the way the phone
does:

- While typing, after a 300 ms pause, it asks the autocomplete over a 20 km window around the
  car's last known position. An empty answer runs the full search.
- A submit, or a tap on a query row, runs the full search. Only a submit shows the spinner.
- With no signal, or when both return nothing, it reads the downloaded packs: a typed address
  first, then places. The car service opens the packs itself, because a session started from
  the car never runs the phone's view model.
- Up to two matching contacts lead when contacts search is on. Six rows show in total. Tapping a
  result previews a route to it.

It does not run the full search per keystroke. That is up to four requests per letter, and
canceling a coroutine does not abort an OkHttp call already running, so a typed word queued a
dozen requests behind the per-host connection limit.

Mid-drive the car offers no typing. Its along-route list is the quick categories. A pick
searches around the car, sorts by distance and adds the result as the next stop, with no
corridor filter. [Chapter 10](10-android-auto.md) covers the rest.

## Limits

- Photon receives address-shaped typing even when Google answers. Its request starts in
  parallel with the autocomplete and is canceled only afterwards. Fixing it means waiting for
  the autocomplete first, which delays address rows on the fallback path.
- Offline, only the first 20 place rows get an address filled in (`OFFLINE_ADDR_FILL`). Later
  rows read as bare names until opened.
- The on-device search that runs after a failed online search is thinner than the offline path.
  It has no address fill and no "businesses at this address" step.
- Search along a route is one window at the route's middle. On a trip much longer than about
  25 km, places near either end are not in the answer. It would need a search per stretch of
  the route. It also has no offline path and reports that the search failed.
- With Google off and no download, a category search reads about 2 km of places around the
  search point. Farther places come only from an installed place pack.
- Intents need a word table. Estonian gets the English table. Chinese and Japanese get no fuzzy
  pass over their own words, only over the English fallback.
- Saved places do not enter the results list, only the suggestions. A search for a saved
  place's name relies on the provider finding it again.
- The official Telegram app checks for the Google Maps package before it opens any location, so
  on a phone without Google Maps it never asks Vela.
