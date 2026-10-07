# 7. Talking to Google

## What you see

Ratings, opening hours, photos, reviews, popular times, traffic on a route, transit directions,
Street View and search suggestions come from Google. None of it needs a Google account, Google
Play services or an API key, and no Vela server sits in between. The phone asks google.com what
the Maps website asks from a logged-out desktop browser and reads the answers itself, the way
NewPipe reads YouTube.

Most of this is invisible. It shows in four places:

- A notice card on the map, or rarely a dialog, such as "search is down, a fix is on the way".
  It comes from the signed settings file described below, with no app update.
- With "Place icons on the map" set to Google, a cold start can draw every place the same size
  for a second. A new session's first answers carry no review counts, and the app asks again.
- A dim line on a place sheet: "Google is showing a limited view right now, so popular times
  and more reviews may be missing here."
- Settings > Privacy: "Use Vela without Google", how long a Google session lives, "Block
  Google's page telemetry", and "Requests to Google", a count of what this phone has sent.

## Where the data comes from

- Google's public web endpoints, the ones `www.google.com/maps` calls itself: map search,
  autocomplete, directions, the photo gallery, Street View and the place pages. Google serves
  them to any anonymous browser. The data is not open and has no license Vela can point to.
- `calibration.json` at the root of the Vela repository, with its detached signature
  `calibration.json.sig`, fetched from GitHub's raw file host at launch. It holds everything
  about these requests that Google can break.
- The community services (FOSSGIS OSRM and Valhalla, Nominatim, Photon, Overpass, Transitous)
  are covered in [chapter 5](05-routing.md), [chapter 6](06-search.md) and
  [chapter 9](09-transit.md). They and the calibration fetch are sent `VelaConfig.VELA_UA`
  (`VelaMaps/0.4 (+https://github.com/PimpinPumpkin/Vela)`), never the browser identity,
  because their usage policies ask for a client they can contact.

Request shapes, response paths, headers and constants are in
[SPEC section 3](../../SPEC.md). This chapter explains how the parts fit.

## How it is decided

### Keyless requests

No build has an API key and no token is taken from a page. Search and directions need only
ordinary cookies. `GoogleSession.ensure()` makes one GET of `sessionWarmUrl`
(`https://www.google.com/maps?hl=en&gl=us`) per process, sent as a first navigation, and the
cookies it collects go on later requests. That jar is in memory, so every launch is a new Google
session. The per-place requests use a second, saved session
(see [Google sessions](#google-sessions-and-the-limited-view)).

A cookieless session in the EU is redirected to `consent.google.com`. Both cookie jars pre-seed
`SOCS=CAESHAgBEhIaAB` and `CONSENT=YES+` and drop a later `Set-Cookie` that would change
`CONSENT` to a value not starting with `YES`.

Every request goes through one shared OkHttp client (`CoreModule`) with a 12 s call timeout, so
one hung request cannot stall a batch. OkHttp builds the request, holds the cookies and sets the
deadline. When the host is Google's, `GoogleTransport.hook` hands the request to
`CronetTransport`, which sends it through Cronet, Chromium's network stack, so the TLS handshake
and HTTP/2 settings are Chrome's. If Cronet cannot load or fails before answering, OkHttp sends
that request. Requests to other services stay on OkHttp.

Endpoints are stored with `hl=en&gl=us`. On search, autocomplete and the per-place requests,
`gl` is rewritten to the country the phone is in (cell network, then SIM, then locale) and `hl`
to the app's language. `hl` changes only for a language the open/closed parser has a word table
for (`SearchParser.STATUS_LANGS`), because a status line the parser cannot read would leave
every place with no open or closed state. Chinese is sent as `zh-TW` or `zh-CN` by script and
region. Directions requests are not rewritten.

The search box's suggestions come from Google's autocomplete endpoint (`suggestEndpoint`),
because the search endpoint ranks a partial address by prominence over the whole window. The
request goes out 320 ms after typing pauses ([chapter 6](06-search.md)).

### The first answers of a new session

For the first seconds of a new session Google's search reply is stripped: ratings without
review counts, a focused place without its related list, a place without popular times. The
same request a few seconds later is complete. Three parts of the app handle this.

`nearbyPlaces` is the category fan-out that fills the map when Google draws the places
([chapter 1](01-places.md)): 15 searches, 8 in low-memory or low-data mode, with at most
`ambientFanoutPermits` (4) parsed at once, because each reply becomes a JSON tree of tens of
megabytes and parsing all of them together filled a 512 MB heap. The map sizes Google's places
by review count, so a pool without counts draws flat. When at least 3 places are rated and more
than half of those have no count, the fan-out runs once more about 1.2 s later, and its places
go first in the merged pool so de-duplication keeps the complete copies.

A name search that lands on one branch of a chain looks up the other branches with 1 to 5
extra requests, one of them the plain search again when the first reply was stripped. With
Settings > Search > "Find other locations automatically" off (it is on by default), the lookup
waits for a tap on "Show other locations".

The per-place requests retry, as described under
[A place tap](#a-place-tap-photos-reviews-and-details).

### The browser identity

Requests to Google claim to be current desktop Chrome on Windows. The user agent and its
`Sec-CH-UA` brand list are calibration fields (`userAgent`, `secChUa`).
`VelaConfig.USER_AGENT` and `SEC_CH_UA` are only the compiled fallback. Code reads the pair
from `CalibrationStore.current()`, never from the constants.

Chrome computes its `Sec-CH-UA` value from its major version, including a made-up "GREASE"
brand that changes with each release. `BrowserHeaders.secChUaFor` does the same computation and
`CalibrationStore.parseBundle` derives the hint from the user agent's major, so the two cannot
disagree.

`BrowserHeaders` adds the headers Chrome sends beside its user agent: the client hints, the
`Sec-Fetch-*` triple, `Accept`, an `Accept-Language` built from the phone's language list, a
Maps `Referer`, and the `Downlink` and `RTT` hints google.com asks for, taken from Cronet's
network estimate. The session warm-up is sent as a document navigation, data requests as
same-origin fetches, place photos and Street View tiles as cross-site image loads. The header
table is in [SPEC 3.6](../../SPEC.md#36-browser-identity).

A value that every install sent identically would pick out Vela's traffic. `BrowserViewport`
gives each install one common desktop window size, picked once, for its search, directions,
autocomplete and photo requests. `RequestShape` fills in the request counters, map spans and
the directions viewport per session. `Jitter` spreads every fixed wait before a Google request,
by 25% by default.

The identity is desktop for two reasons. Every parser was calibrated against the desktop
responses, and mobile web Maps serves different markup and endpoints. And with a mobile user
agent Google redirects a hidden page to an `intent://` link, which leaves nothing to read.

Google serves different response shapes to different browser generations, and Chrome ships a
stable release about every four weeks, so the claimed version follows Chrome's current Windows
stable major. Moving it is a calibration push.

A pushed `userAgent` passes through `BrowserHeaders.sanitize`. OkHttp throws on a control
character in a header value, inside `runCatching` blocks that swallow the error, so one stray
newline would stop every request without a crash or a log line. `sanitize` trims whitespace,
then rejects a blank value, anything outside printable ASCII and anything longer than
`MAX_UA_LENGTH = 400`. The compiled value is used in its place.

### The signed calibration bundle

`CalibrationStore` starts from the cached bundle if one is on disk and its signature still
verifies, and otherwise from the compiled `Calibration.DEFAULT`, so the app works with no
network. Once per process, without blocking anything, it fetches `calibration.json` and its
signature from the repository's `main` branch and adopts the remote bundle only if all three
hold:

1. The signature verifies: ECDSA over P-256 with SHA-256, against `PINNED_PUBLIC_KEY`. The
   private key is never in the repository.
2. Every endpoint host is in `ALLOWED_HOSTS` (`www.google.com`, `google.com`), so a correctly
   signed bundle still cannot point search, directions, reviews, photos, autocomplete or the
   session warm-up anywhere else.
3. Its `version` is higher than the active one. `DEFAULT.version` is 1 so that any published
   bundle wins.

Parsing is field by field. A missing or malformed field keeps the compiled value, so a bundle
can override only the thing that moved. The bundle carries:

- Request and response shape: endpoint URLs, request templates, `rpcContext`, and the
  positional paths the search, directions and autocomplete parsers read. Paths merge key by key
  over the compiled ones, so a moved field is a one-line edit.
- The browser identity.
- Word tables, for the places where the app reads localized text to decide something: open and
  closed words per language, transit-category words, and the review page's words and CSS
  selectors.
- Fleet defaults such as the voice, the map palette and the places source. A user's own
  setting always wins.
- Notices. Level `urgent` is a dialog; `info`, `warn` and `error` are dismissable cards on the
  bare map. Dismissal is remembered per notice `id`.
- Tuning dials and parsing code, both below.

To publish a change, edit the file, raise `version`, run `scripts/sign-calibration.sh` (it
signs and verifies its own output) and commit both files to main. A new field needs a line in
`CalibrationStore.parseBundle` as well as in the `Calibration` class, or it is silently dropped.

#### Tuning dials

`tuning` is a flat name-to-number map read through `Calibration.tune(key, default)`. A missing
key means the compiled default, so adding a dial needs no schema change. `ui/AppTune` reads
`adb shell setprop debug.vela.tune.<key> <n>` first, then the bundle, then the default, so a
dial can be tried on one phone without a push. Dials that open the app up for inspection read
the adb property alone, so a bundle cannot turn them on.

| Dial | Compiled default | Effect |
| --- | --- | --- |
| `useCronet` | 1 | 0 sends Google requests over OkHttp |
| `agedSession` | 1 | 0 sends per-place requests on the app's own session |
| `webProxy` | 0 | 1 sends a Google page's requests from the app |
| `nativePlacePhotos` | 1 | 0 reads a place's first photos from the page |
| `nativeDetails` | 1 | 0 reads a place's details from the page |
| `nativeReviewFeed` | 0 | 1 asks the review feed for the first reviews |
| `placeTries`, `placeRetryMs`, `placeRetryStepMs` | 3, 2500, 1000 | the per-place retry schedule |
| `ambientFanoutPermits` | 4 | parallel parses in the map fan-out, read at process start |

The live bundle sets `webProxy` to 1 for every install. Its other dials are
`ambientFanoutPermits`, three browse zooms, `overlayCoverFrac`, `ambientCapMin`,
`ambientCapMax` and `placesOneSetRev`.

#### Remote parsing code

A moved field is a path edit. A reply whose shape changed needs new logic, and the bundle can
carry it as JavaScript in `transformsJs`. `parseSearch(rawResponse)` replaces the compiled
search parser and `transformPlaces(placesJson)` post-processes its result.

The script runs in Rhino inside `JsSandbox`, with no Java, reflection or IO, and is stopped
after `MAX_RUN_MS = 2_000`. Without the deadline a `while (true)` in a pushed script would hang
the search and, because the sandbox is serialized, every search after it. No script, a missing
function, an error, an empty result or the timeout all leave the compiled Kotlin result in
place. The live bundle carries no script.

### The hidden pages

Some answers exist only inside a page Google has rendered. For those the app loads Google's own
page in a hidden WebView, anonymously, lets the page's JavaScript run, and reads the result
back over a JavaScript bridge.

| Page | What it is for | When it loads |
| --- | --- | --- |
| Reviews | a place's first reviews, read off its place page | when the Reviews tab scrolls into view, outside a drive |
| Photos | the gallery walked tab by tab, the only source of photo categories | on the Menu chip, and on "More photos" when the photo request will not page |
| Details | the details search run from inside a Google page | only when every plain try came back stripped |
| Transit directions | itineraries. A plain request with the transit flag was answered with a driving reply | on every transit directions request ([chapter 9](09-transit.md)) |
| Stop board | a stop's departures, embedded in its place page | when the open transit data has no board for the stop ([chapter 9](09-transit.md)) |

With "Load all photos and reviews" on, the reviews and photos pages load on every tap.

A sixth WebView is visible: "All reviews" opens Google's own reviews page full screen, cut down
to the review list. The fetcher classes, URLs and timeouts are in
[SPEC 3.7](../../SPEC.md#37-hidden-webview-scrapes).

The five hidden ones share `HiddenWebView`:

- A view sleeps between fetches. A loaded Google page keeps its compositor and timers running,
  which measured as about 27% of the app's CPU during a map pan.
- A view idle for 120 s (`reapIdleMs`) is destroyed, and under severe memory pressure it is
  destroyed at once. The next fetch builds a new one.
- No Google page is loaded ahead of need. A few seconds after the map first settles, one empty
  WebView is built and destroyed so that Chromium's own start does not land under the first
  place tap. Not on a low-RAM phone, and not with Google off.
- A headless WebView is 0 by 0 and Google's lists render nothing into it, so a view gets an
  offscreen viewport wide enough for the desktop layout.
- Pages load in English with `gl=us`. The reviews pages and the transit directions page follow
  the app's language, which decides which reviews Google serves and how transit names are
  written.

Every WebView calls `WebViewIdentity.apply`, which sets the calibrated user agent and, through
androidx.webkit, client-hint metadata that matches it. Without the metadata a WebView with a
desktop user agent still sends `"Android WebView"`, `?1` and `"Android"` hints.

A script reports back through a JavaScript interface, which sits on the page's `window` where
the page's own scripts can list it. `web/JsNames` draws the interface names at random once per
process, and `JsNames.of` swaps them into a script in place of the readable `VelaBridge` and
`VelaPanel`.

### The WebView proxy

An Android WebView adds `X-Requested-With: <package name>` to every request it sends, and no
app setting removes it. `web/WebProxy` avoids the header by sending the page's requests from
the app over Cronet, with the WebView's own cookies so the page keeps its session. The dial
`webProxy` is off in the compiled defaults and on for every install through the live bundle.

- GETs and CORS preflights are intercepted in `shouldInterceptRequest` and sent by the app.
- `shouldInterceptRequest` never sees a POST body. A document-start script (`WebProxy.SHIM`)
  wraps XHR, `fetch` and `sendBeacon` on google.com pages. A POST to a Google host gets a
  one-time id added to its URL and its body handed to a bridge first, so the body is waiting
  when the tagged request reaches the interceptor. The bridge name and the tag are random per
  process.
- The WebView passes the interceptor only some of its headers. The proxy adds the missing
  `Sec-Fetch-*` and `Sec-CH-UA` headers the way Chrome derives them.
- A failed proxy request returns null and the WebView loads it itself.

Google's pages also report on themselves: `play.google.com/log`, `gen_204` pings and the
account bar's `ogads-pa` calls. Nothing Vela reads depends on them. Settings > Privacy > "Block
Google's page telemetry" (off by default) answers them on the phone with an empty 200, with
the proxy on or off. They are sent by default because a session whose pages never send them
looks less like a browser's.

Logcat `VelaWebProxy` prints each path once as `carries:`, `answers locally:` or
`passes through:` (sent by the WebView itself, with the header).

### Google sessions and the limited view

One phone holds two Google sessions. The app's own cookie jar is in memory and starts over at
every launch; search, autocomplete and directions use it. The WebView's cookies are on disk and
last. The per-place requests (details, photo pages, the review feed) carry the `AgedSession`
tag, and `CronetTransport` sends a tagged request with the WebView's cookies, with no page load
and no `X-Requested-With`. Without Cronet the request uses the app's session.

Google gives some anonymous sessions a limited view: about five reviews and no further pages,
10 photos per page where 50 were asked for, and no popular times on busy places. A new session
starts out limited, which is why the per-place requests use the saved one.

A saved cookie carries no name or account, but everything an install asks Google while it lasts
can be linked together. So the saved session is thrown away on a schedule
(`web/SessionRotation`, Settings > Privacy > Google session):

| Setting | A new session | Cost |
| --- | --- | --- |
| Every week (default) | when the last one is 7 days old | up to a week of linkable history |
| Every day | when the last one is 24 hours old | up to a day |
| Every time Vela opens | at every process start | the limited view is the normal state, and Android restarting the app in the background can mean several new sessions a day |

The check runs once per process start, before the Cronet engine opens. A rotation clears the
WebView's cookies and site storage, Cronet's disk cache, and the WebView's HTTP cache when the
next Google WebView is built. "Start a new session now" clears the same things except Cronet's
cache, which is only safe to delete before the engine opens it, and also empties the app's
in-memory jar. No other site is loaded in a WebView, so nothing else is lost.

In the limited view a place sheet gets thinner, which reads as a broken app, so
`web/GoogleStanding` watches for it. A first photo page of 20 or fewer with another page waiting
marks the session limited, and so does "More reviews" loading nothing on the All reviews page.
A first page of 40 or more clears the mark. A missing popular-times chart alone marks nothing,
because many places have none. While the session is marked, a Google place with no chart shows
the dim line where the chart would be, and the All reviews page and Settings > Privacy say the
same. The mark belongs to the session and a rotation clears it.

### A place tap: photos, reviews and details

A tap asks Google for each part of the sheet with one plain request where it can, and loads a
page only as a fallback. A hidden reviews page costs about 137 requests to Google per load,
counted on a Pixel 9, and most taps never scroll to the reviews.

For photos, `placePhotoPage` sends one request to the gallery RPC (`hspqX`) for 50 photos, each
with its date. "More photos" asks for the next page, one request per page, and walks the photo
page when a page comes back empty twice. If the first request stays empty after every try, the
sheet keeps the search reply's photo and leaves the page walk to a tap on "More photos".

The first reviews are read from the place page, up to `FIRST_REVIEWS = 10`. The one-request
review feed (`qv9Egd`, `reviewFeed`) is built and off. It answers a plain request, but a new
session gets only about five reviews with no paging, and for a session with the full view
Google expects a single-use token that only its own page creates, so a request without it gets
an empty list. The rest of the reviews are on the All reviews page.

Details are popular times, the editorial blurb, the review count, hours and the address.
Nothing is asked when the search reply already has popular times, a review count, an address
and weekly hours. Otherwise `placeDetails` sends the search the details page runs, the place's
name plus its address, as a plain request, and each reply is merged into the sheet as it lands.
The details page loads only when no try returned popular times or a review count, and not
during a drive or with the route chooser open.

Both RPCs need the header `x-maps-diversion-context-bin` (`Calibration.rpcContext`, `CAE=`).
Without it the gallery answers zero photos and the feed answers empty.

Google often answers a place's first request stripped, so each one-request piece gets
`placeTries` tries (3). The wait before a retry is `placeRetryMs` (2500 ms) plus
`placeRetryStepMs` (1000 ms) for each later try, jittered. Details retry while popular times
are missing. Answers are cached per place for the life of the process: photos and the feed for
6 hours, details for 15 minutes because popular times are read against the current hour.

The switches that put a piece back on its page:

- By calibration push: `nativePlacePhotos` 0 reads the first photos from the page walk,
  stopped at `FIRST_PHOTOS = 6`, and `nativeDetails` 0 goes straight to the details page.
  `nativeReviewFeed` works the other way: 1 turns the feed on.
- Per phone: Settings > Performance > "Load all photos and reviews" walks the whole gallery and
  reads up to 50 reviews on every tap.

Settings > Places has the switches that send less: "Load reviews only when I tap", "Load photos
only when I tap", "Show reviews" and "Load photos", and "Wait for popular times", which when
off gives every piece a single try. The method table is in
[SPEC 3.7](../../SPEC.md#37-hidden-webview-scrapes). `VelaPlaceLoad` logcat lines say which
path each piece took.

### The request counter

`core/net/GoogleUsage` counts every request the phone sends to a Google host, by purpose and by
day. The count stays on the phone. `GoogleTransport.hook` records each request through the
shared client, whether Cronet or OkHttp carries it. A hidden page load is counted where it
starts, and each request a Google page makes after it loads is counted as page resources.
Settings > Privacy > "Requests to Google" shows today and the last week by purpose, the
diagnostics export carries the counts without URLs, and 14 days are kept (`KEEP_DAYS`).

A request to Google sent through any other client would be missing from the count and would
not carry the browser identity.

### "Use Vela without Google"

One switch in Settings > Privacy (`ui/GoogleFree`, pref `google_free`, off by default),
mirrored into the core module's `NoGoogle` flag and checked wherever a Google request would
start. With it on:

- Search answers from Photon and the downloaded place packs: names and addresses, and
  categories only where a region is downloaded. Google's autocomplete returns nothing, so the
  Photon and on-phone path runs.
- Places on the map come from Vela's own data. The Google fan-out, "More results" and the tap
  lookup that matches a pin to its Google listing are off.
- Directions come from the open routers alone, with no traffic and no live arrival time.
- No hidden page loads: no photos, reviews, popular times, Google transit directions or
  stop-board fallback. The All reviews page is not offered.
- Street View answers "no coverage" and its button is hidden.
- The traffic overlay is off, and satellite stops falling back to Google's imagery for the
  close zooms.
- A shared Google Maps list cannot open, because its places exist only on Google's servers.

The calibration fetch (GitHub), the basemap, the open routers, Transitous, cameras, road
features and everything offline are unaffected.

One Google request can still happen under the switch. A short link (`maps.app.goo.gl/...`) says
nothing about where it points until Google's link shortener is asked. With "Open shared Google
Maps links" on (the default, shown under the switch), `core/data/ShortLinks` asks the shortener
once per hop with no cookies, reads the redirect's `Location` and stops before loading any
Google page. With it off, a short link is refused with a toast. A full `google.com/maps` link
never needs the request.

The cost of the switch in user terms is in
[the FAQ](../FAQ.md#can-i-use-vela-without-google-at-all). What works with no network at all is
[chapter 8](08-offline.md).

### The daily health check

`.github/workflows/google-health.yml` runs two jobs at 14:20 UTC against the repository's
`calibration.json`.

`GoogleHealthProbeTest` runs the app's own request builders and parsers against Google from the
Davis fixture: search, directions to Sacramento, the same trip with avoid-highways (which must
come back at least 3 km longer), autocomplete, the review feed and the photo gallery (which
prove `rpcContext` still works). A reply the parsers cannot read fails the run and mails the
maintainer. Google refusing a datacenter IP outright is only a warning, because it says nothing
about the calibration.

`scripts/check-chrome-ua.py` compares the Chrome major Vela claims with Chrome's Windows stable.
It fails when stable has been a major ahead for 7 days (`GRACE_DAYS`, since a new major reaches
people in stages), when Vela claims a Chrome that has not shipped, or when the pushed `secChUa`
is not what Chrome of that major sends. Builds that predate the derived hint send the pushed
`secChUa` as it is, so the bundle has to carry the right string.

## Limits

- The handshake is Chrome's only while Cronet carries the request. The all-in-one APK ships
  Cronet for ARM only, so on an x86 emulator or Chromebook, and after any Cronet failure,
  Google requests go over OkHttp with OkHttp's handshake.
- Chrome sends an `X-Client-Data` header to Google and Vela does not.
- One phone is two sessions from one IP address: search, autocomplete and directions on the
  app's session, the per-place requests on the WebView's.
- `X-Requested-With: app.vela` still goes out on whatever a WebView sends itself: a POST the
  shim does not carry (a FormData body, or a host outside google.com), everything when Cronet
  is unavailable, and any request whose proxy attempt failed.
- The limited view follows the session, and a new session starts limited, so starting a new
  one rarely lifts it.
- Popular times can still be missing after three tries. The sheet shows none, and the next open
  after the 15-minute details cache asks again.
- Rotation is checked only at process start. A process that outlives the period keeps its
  session until the next start or the button.
- The map's own Google tiles (the traffic overlay and the satellite fallback) go through the
  map engine's HTTP client. They do not carry the browser identity and are not counted.
- Nothing moves the claimed Chrome version on its own. A person edits, signs and commits.
- A wrong `chromeFullVersion` is printed on the health run's summary but does not fail the
  run: `check-chrome-ua.py` resets its exit code after that test.
- The bundle is fetched once per process, after start. Requests sent before it lands use the
  previous bundle, and a process that keeps running does not see a later push. Some values are
  read once: `ambientFanoutPermits` at process start, and the user agent and the POST shim when
  a WebView is built.
- There is no rollback. A bundle only replaces one with a lower version, so undoing a push
  means publishing the old content under a higher number.
- `transformsJs` hooks the search parser only. A reshaped directions, autocomplete, map
  fan-out or page reply needs a path edit or an app release.
- EU consent rests on the two pre-seeded cookies. The app never completes Google's consent
  form.

### What is dead, and not to be re-chased

Each of these was probed and found closed.

- The old reviews RPC, `listentitiesreviews`, returns 404. Its endpoint and template are still
  in the bundle and `reviews()` still exists, but nothing calls it.
- Live busyness. The "busier than usual" reading is absent from every anonymous reply. The
  chart's "right now" line is the typical week read at the current hour.
- Live traffic incidents. Google draws them from binary vector tiles, and Waze's feed is behind
  reCAPTCHA. Congestion coloring on the route is what covers slow traffic.

The photo gallery RPC and popular times over a plain request were once on this list. The
gallery was missing the `x-maps-diversion-context-bin` header, and popular times come back on a
repeat of the same request. Before calling an endpoint closed, send the page's own headers and
ask twice.
