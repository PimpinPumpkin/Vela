# 7. Talking to Google

## What you see

Ratings, opening hours, photos, reviews, the live arrival time, transit directions, Street View
and the search box's suggestions. None of it needs a Google account, a Google Play Services
install or an API key, and none of it passes through a Vela server, because there is no Vela
server. Your phone asks google.com the same questions the Maps website asks from a logged-out
desktop browser, and reads the answers itself. This is the NewPipe model, applied to maps.

Most of the time you see nothing of the machinery. You notice it in four situations:

- **A notice card on the map** (or, rarely, a dialog) saying something like "search is down, a
  fix is on the way". That came through the signed calibration channel described below, not an
  app update.
- **A map that looks flat for a second** on a cold start, every place drawn the same size, then
  settles into big and small pins. That is Google's early-session answer being replaced by the
  full one.
- **A dim line on a place sheet**, "Google is showing a limited view right now...", where the
  popular-times chart would be. Google gives some sessions fewer photos and reviews, and Vela says
  so rather than look broken; the same note is under Settings > Privacy > Google session.
- **Settings > Privacy > "Use Vela without Google"**, which turns all of this off at once.

## Where the data comes from

- **Google's public web endpoints**, the ones `www.google.com/maps` itself calls: map search,
  the autocomplete behind the search box, directions, the photo and Street View services, and
  the place pages. This is not open data and it has no license Vela can point to; it is what
  Google serves an anonymous browser, read on your phone for you. The endpoint list with the
  exact paths is in [SPEC 3.1](../../SPEC.md#31-endpoints).
- **`calibration.json`** at the root of the Vela repository, with its detached signature
  `calibration.json.sig` beside it. The app fetches both from GitHub's raw file host at launch.
  This file holds everything about the scrape that Google can break: request templates,
  response field positions, the browser identity, word tables, tuning numbers, notices and,
  when needed, replacement parsing code.
- **Community services** (the FOSSGIS OSRM and Valhalla routers, Nominatim, Photon, Overpass,
  Transitous) are not Google and are not covered here except for one rule: they get a
  different, honest user agent. Routing is [chapter 5](05-routing.md), search is
  [chapter 6](06-search.md), transit is [chapter 9](09-transit.md).

## How it is decided

### Per user, no key

Each phone is its own anonymous browser session. There is no API key in any build variant
(a hard rule in [SPEC 12](../../SPEC.md#12-degoogled-constraints)), and no token is extracted
from a page: search and directions were calibrated on 2026-06-15 and turned out to need only
ordinary cookies.

The session is warmed once per process. `GoogleSession.ensure()` makes a single GET of
`sessionWarmUrl` (from the bundle, `https://www.google.com/maps?hl=en&gl=us` today), dressed as
a first navigation, and the cookies it collects ride on every request after it. The cookie jar
lives in memory only, so a process restart is a fresh session. (The per-place requests are the
exception: they ride the WebView's saved session instead, see
[Which Google session](#which-google-session-and-how-long-it-lives).)

```
callTimeout         = 12 s    // one hung scrape cannot stall a fan-out
connectTimeout      = 15 s
readTimeout         = 20 s
maxRequestsPerHost  = 24      // the ambient fan-out goes in one round, not OkHttp's default 5
```

**The wire is Chrome's own.** Since 2026-09-23 every request to a google.com host is handed to
Cronet, Chromium's network stack (`core/net/GoogleTransport` passes google.com hosts to
`app/net/CronetTransport`, dial `useCronet`, default 1). OkHttp still builds the request, keeps
the cookies and sets the deadline above, which the Cronet wait honors; Cronet carries it, so the
TLS handshake and HTTP/2 settings are Chrome's rather than OkHttp's. Everything that is not
Google (the routers, geocoders, Transitous, tiles) stays on OkHttp, and so does a Google request
whenever Cronet fails to load or fails before answering.

Because every phone asks from its own IP with its own cookies, there is nothing central for
Google to block. That diffusion is the main defense. Vela ships no TLS stack of its own; the
handshake is only as Chrome-like as the Cronet build (see Limits).

### EU consent cookies

A fresh, cookieless session in the EU or EEA is bounced to Google's `consent.google.com`
interstitial before search can run. The in-memory cookie jar pre-seeds the two cookies that
interstitial checks for, on `www.google.com`, `google.com` and `consent.google.com`:

```
SOCS    = CAESHAgBEhIaAB   // "consent recorded"
CONSENT = YES+
```

A later `Set-Cookie` that tries to downgrade `CONSENT` to a value not starting with `YES` (a
`PENDING` value, for instance) is dropped. US sessions are unaffected. The WebView's jar
(`WebViewCookieJar`, the one the per-place requests borrow) seeds the same two cookies and
refuses the same downgrade. It seeds whenever `SOCS` is missing, so a store that a session
rotation just emptied gets them back on its next request.

### The browser identity it claims

There are two user agents in the app, and mixing them up is a bug.

**Google-facing requests** claim to be current desktop Chrome on Windows. The compiled
fallback, used only when the bundle does not carry one:

```
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
SEC_CH_UA  = "\"Google Chrome\";v=\"155\", \"Chromium\";v=\"155\", \"Not(A:Brand\";v=\"24\""
```

Code reads the live pair from `CalibrationStore.current()`, never from the constants. The hint is
not typed by hand anymore: Chrome computes the whole `Sec-CH-UA` value from its major version (a
made-up "GREASE" brand whose punctuation, version and position rotate per release), and
`BrowserHeaders.secChUaFor` does the same. The hand-typed 153 hint had kept an older release's
GREASE brand under the new number, a combination no real Chrome sends.

A user agent alone is not enough. Real Chrome sends a cluster of headers alongside it, and a
Chrome UA with that cluster missing is a sharper inconsistency than an old version number. So
`BrowserHeaders` sends the whole set:

| Header | Document fetch (session warm-up) | Data request (search, directions, autocomplete) |
| --- | --- | --- |
| `User-Agent` | calibrated UA | calibrated UA |
| `Accept` | `text/html,...` | `*/*` |
| `Accept-Language` | `en-US,en;q=0.9` | `en-US,en;q=0.9` |
| `Sec-CH-UA` | calibrated brand list | calibrated brand list |
| `Sec-CH-UA-Mobile` | `?0` | `?0` |
| `Sec-CH-UA-Platform` | `"Windows"` | `"Windows"` |
| `Sec-Fetch-Dest` / `-Mode` / `-Site` | `document` / `navigate` / `none` | `empty` / `cors` / `same-origin` |
| `Referer` | none, as on a real first visit | `https://www.google.com/maps/` |
| `Downlink` / `RTT` | not sent | `10` / `50` |

The last row exists because google.com's `Accept-CH` asks for exactly those two network hints
(checked 2026-09-22), and Chrome sends them, rounded, on every request after the first
document. A request that carries them looks like one that saw the page. The photo RPC's POST
adds `X-Same-Domain: 1`, which the `batchexecute` endpoint expects from a same-origin caller,
and Street View tile fetches present themselves as a cross-site image load.

**Why desktop.** A mobile string would match the carrier IP better. It would not match the TLS
handshake any better: measured on 2026-09-23, desktop Chromium and the Android WebView send the
identical ClientHello and HTTP/2 settings, so Chrome's handshake does not say which platform it
runs on. (OkHttp's handshake matches neither and looks like OkHttp, which is why Google
requests now go over Cronet.) But
mobile web Maps serves different markup and different endpoints, and every parser in the app
was calibrated against the desktop responses. Switching to a mobile UA is a recalibration of
every parser, not a header edit, and the `?0` and `"Windows"` hints would have to move with it.
The hidden WebViews need desktop for a blunter reason: with a mobile UA, Google deep-links the
page to `intent://` and there is nothing to read.

**Why it has to stay current.** Chrome ships a stable release about every four weeks, so a
compiled UA is stale next month by construction; the one Vela shipped sat at Chrome 124 (April
2024) well into 2026. Staleness is a correctness risk before it is a fingerprinting one: Google
serves different response shapes to different browser generations, so a two-year-old Chrome
can be reading a legacy code path that gets retired without warning, which from the app's side
looks exactly like ordinary calibration drift. The compiled value tracks Chrome's current
Windows stable major, and not the next one: for a week in September 2026 it named a Chrome that
had not shipped yet.

**Checked every day.** `.github/workflows/google-health.yml` runs the app's own request builders
and parsers against Google from the Davis fixture with the repo's `calibration.json`: search,
directions, directions with avoid-highways (which proves the avoid flag still bites),
autocomplete, and the two place RPCs, the review feed and the photo gallery (which prove the
`rpcContext` header below still works). The run is at 14:20 UTC. A reply the parsers cannot read
fails the run and mails the maintainer; Google refusing a datacenter IP outright is only a
warning, because it says nothing about the calibration. A second job compares the Chrome major
Vela claims with Chrome's Windows stable and says so on the run summary when stable has been a
major ahead for a week (`GRACE_DAYS = 7`, since a new major reaches people in stages), or when
Vela claims a Chrome that has not shipped. The first run flagged exactly that: Chrome 154 went
stable on 2026-09-09 while Vela still said 153 (the probe passed with a 154 UA). Chrome 155 went
stable on 2026-09-23 and the bundle moved to it two days later (calibration v23), before the
grace week ran out.

**No clockwork.** Every fixed wait before a Google request (the two-minute live-traffic recheck,
retry backoffs, the stagger between a place's page loads) is drawn with a random spread through
`Jitter`, so no two installs, and no two rechecks, keep the same beat.

**Community services get the honest one.**

```
VELA_UA = "VelaMaps/0.4 (+https://github.com/PimpinPumpkin/Vela)"
```

OSRM, Valhalla, Nominatim, Photon, Overpass and Transitous are free infrastructure Vela depends
on, and their usage policies ask for a contactable identifier so they can reach an abusive
client instead of blanket-blocking. Before 2026-09-15 the router requests were sending them the
spoofed Chrome string. The calibration fetch from GitHub sends `VELA_UA` too.

### Keeping the user agent current without a release

The UA and its brand list are fields in `calibration.json` (`userAgent`, `secChUa`). Refreshing
them is the same procedure as any calibration fix: edit, bump `version`, re-sign, commit. The
next launch of every installed copy picks it up.

Two guards stop a bad push from breaking the scrape:

- **The two must agree, and the phone makes sure.** A hint advertising a different Chrome than
  the UA string is worse than no hint. Since 2026-09-23 `CalibrationStore.parseBundle` does not
  take the pushed `secChUa` on trust: it computes the hint from the effective UA's major with
  `BrowserHeaders.secChUaFor`, and reads the bundle's `secChUa` only when the UA carries no major
  it can parse. The bundle still has to carry the right string for builds older than that, which
  send it as-is, so `scripts/check-chrome-ua.py` (part of the daily health run) flags a pushed
  hint that differs from what Chrome of that major sends. `BrowserHeadersTest` locks the
  compiled pair, so bumping one alone fails the build.
- **Both are sanitized on parse.** OkHttp throws on a control character in a header value, at
  request-build time, inside `runCatching` blocks that swallow the throw. One stray newline in
  a pushed UA would silently kill every scrape with no crash and no log. So
  `BrowserHeaders.sanitize` trims surrounding whitespace first (a trailing newline in
  hand-edited JSON is recovered, not rejected), then rejects anything blank, anything outside
  printable ASCII `0x20..0x7E`, and anything longer than `MAX_UA_LENGTH = 400`. A rejected value
  falls back to the compiled default, never to an empty header.

The same pushed value reaches the hidden WebViews (below), so the OkHttp client and the browser
engine present one identity.

### The signed calibration bundle

`CalibrationStore` starts from a cached bundle if one is on disk and still verifies, otherwise
from the compiled `Calibration.DEFAULT`, so the app always has a working configuration with no
network. Then, once per launch and without blocking anything, it fetches:

```
REMOTE_URL = https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json
SIG_URL    = https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json.sig
```

and adopts the remote bundle only if all three hold:

1. **The signature verifies.** ECDSA over P-256 with SHA-256 (`SHA256withECDSA`), against the
   public key pinned in the app (`PINNED_PUBLIC_KEY`, an SPKI key in base64). The private half
   never enters the repository; `scripts/sign-calibration.sh` signs with it and self-verifies
   before anyone commits. A bundle that does not verify is ignored and the last good one stands.
   The cache is re-verified on every start, so a file tampered on disk falls back to the
   compiled default for that launch.
2. **Every endpoint host is on the allowlist**, `ALLOWED_HOSTS = { www.google.com, google.com }`.
   Even a correctly signed bundle cannot point search, directions, reviews, photos or the
   session warm-up anywhere else.
3. **The version is newer** than the active one. `DEFAULT.version = 1` on purpose, so any real
   bundle wins; the live file is at `version = 22` as of this writing.

Parsing is lenient field by field: a missing or malformed field falls back to the compiled
value, and a bundle can override just the one thing that drifted.

What the bundle can carry, from least to most powerful:

- **Request and response shape.** Endpoint URLs, the search and directions `pb` templates, the
  photo proto, and the positional field paths the search parser (`paths`) and the directions
  parser (`directionsPaths`) read. Paths merge key by key over the compiled ones, so a moved
  field is a one-line edit.
- **The browser identity**, `userAgent` and `secChUa`, above.
- **Word tables.** The only part of the scrape that reads localized *text* to make a decision:
  open and closed status words per language (`statusOpenWords`, `statusClosedWords`), the
  transit-category gate and its exclusions (`transitCategoryWords`, `transitExcludeWords`), and
  the review scrape's words and CSS selectors (`reviewWords`, `reviewSelectors`). A word missing
  in some language, or a CSS class Google rotated, is a config edit rather than an app release.
  These tables were in the data class from day one but not read by the parser until 2026-07-19,
  which is why adding a bundle field is two steps: the class, and `CalibrationStore.parseBundle()`.
- **Fleet defaults**: the default voice, speaker and speed, the map palette, the places source,
  and the classic route picker switch. A user's own setting always wins over these.
- **Tuning dials**, a flat name-to-number map read through `Calibration.tune(key, default)`. A
  missing key means the compiled default, so adding a dial is an edit, never a schema change. The
  code reads 30 dials (the place-data switches below among them); the bundle carries eight today:

  ```
  browseZoom           = 15.5
  browseZoomWide       = 14.5
  browseZoomFocus      = 16.5
  overlayCoverFrac     = 0.18
  ambientFanoutPermits = 4          // applied at the next process start
  ambientCapMin        = 45
  ambientCapMax        = 140
  placesOneSetRev      = 20260923   // the places archive that carries the landmarks (chapter 1)
  ```

  The dials read through `ui/AppTune` (the place-data switches, the proxy dials, `useCronet` and
  `placesOneSetRev`) can also be set on one test phone without a push:
  `adb shell setprop debug.vela.tune.<key> <n>`, which beats the bundle.

- **Notices**: `id`, `level`, `title`, `body` and an optional `url`. Level `urgent` is a modal
  dialog; `info`, `warn` and `error` are dismissable cards on the bare map. Dismissal is
  remembered per `id` on the phone.
- **Parsing code**, `transformsJs`, next.

### Remote parse logic, and its kill switch

A moved field is a path edit. A response whose *shape* changed needs new logic, and new logic
normally means an app release. The bundle can instead carry a small JavaScript file defining
either or both of two functions:

- `parseSearch(rawResponse)` returns the places as flat JSON, replacing the compiled search
  parser entirely.
- `transformPlaces(placesJson)` post-processes whatever the parser produced.

It runs in Rhino, locked down by `JsSandbox`:

```
initSafeStandardObjects     // no Packages, no reflection, no IO: the script sees one string
optimizationLevel = -1      // interpreted; ART cannot run Rhino's bytecode generator
MAX_RUN_MS        = 2_000   // wall-clock kill switch
instructionObserverThreshold = 10_000
```

The kill switch is Rhino's instruction observer, checked every 10,000 instructions against a
deadline. Past two seconds it throws an `Error` rather than an `Exception`, so the script cannot
catch its way past it. Without it, an accidental `while (true)` in a pushed script would hang
the search forever and, because the sandbox is serialized, every search after it.

**Compiled Kotlin is always the fallback.** No script, a missing function, a parse error, a
wrong return type, an empty result or the timeout all leave the compiled result in place. The
path was verified on a device on 2026-06-18 and then cleared; the live bundle carries no script
today.

### The hidden WebViews

Some answers exist only inside a page Google has rendered, or come back degraded to a bare
request. For these, Vela loads Google's own page in a hidden Chromium WebView, anonymously, lets
Google's JavaScript render it, and reads the result back out over a JavaScript bridge. Since
2026-09-23 two of the five are fallbacks rather than the first try: the photos and the details
have one-request methods, and the reviews have one that is built and switched off (see
[Place data](#place-data-the-methods-and-how-to-roll-each-one-back)). What read as bot detection
turned out, for the gallery RPC at least, to be a missing header.

| Fetcher | What a plain request gets | What the page gives | Timeout |
| --- | --- | --- | --- |
| Photos | the gallery RPC answers since 2026-09-23 (dated, no categories); the page is the fallback and "More photos" when paging fails | the full collage, by tab (Menu, Food and drink...) | `55_000` ms |
| Reviews | the old review endpoint is gone; the feed RPC answers but is off (see Place data) | review cards, text, dates, reviewer photos | `45_000` ms |
| Popular times | the details search is stripped on a place's first request and usually complete on a retry; the page is the last resort | the same search, histogram intact | `22_000` ms |
| Transit directions | silently downgraded to a driving reply (measured from OkHttp and curl) | real itineraries | `20_000` ms |
| Stop departure board | a degraded place payload | the board, embedded in the place page | `20_000` ms |

A sixth, visible WebView is the full reviews page, which shows Google's own reviews pane
carved down in place. What each page is and how it is read is in
[SPEC 3.7](../../SPEC.md#37-hidden-webview-scrapes); the transit pair is in
[chapter 9](09-transit.md).

All five hidden ones share `HiddenWebView`, and the rules that matter here are:

- **They sleep between fetches.** A loaded Google page keeps its compositor and timers running
  forever, which measured as roughly 27 percent of the app's CPU during a plain map pan. Each
  fetch runs inside `session { }`, which resumes the view before and pauses it after.
- **They are reaped.** Idle for `reapIdleMs = 120_000` and the view is destroyed; under severe
  memory pressure it is destroyed at once. The next fetch builds a new one.
- **Only the engine is warmed.** A few seconds after the map first settles, at a quiet moment
  (no drive, no sheet, no results), one throwaway WebView is built and destroyed so Chromium's
  own start (half a second of main thread and a sandbox process) does not land under the first
  place tap. Not on a low-RAM phone, and not with Google off. No Google page loads until a place
  needs one: until 2026-09-22 the launch warm loaded google.com and Maps in two hidden views, and
  until 2026-09-23 every search did the same, two whole web apps on the chance of a tap.
- **They cannot wander.** Only http and https load, and a scrape that must stay on one page
  refuses other navigations.
- **They are sized.** A headless WebView is 0 by 0, and Google's virtualized lists render
  nothing into it. Photos use an offscreen `1200 x 3200` pixel viewport; reviews use
  `1200 x 1000` CSS pixels multiplied by the screen density, because 1200 physical pixels on a
  2.75x phone is about 450 CSS pixels, and Google served that its narrow layout.

**The same identity, as far as an app can.** Every WebView calls `WebViewIdentity.apply`, which
sets the calibrated UA and, through androidx.webkit, user-agent metadata built from the same
`secChUa`: Chrome brands, mobile `?0`, platform Windows, x86, 64-bit, a matching full version.
This was measured on a Pixel 4a with a header echo on 2026-09-22. Before it, a WebView whose UA
string said Windows Chrome still sent its own hints, `"Android WebView";v="153"`,
`sec-ch-ua-mobile: ?1` and `sec-ch-ua-platform: "Android"`, contradicting the UA three ways.
After it, the hints matched on Vanadium 153.

**The header that cannot be removed.** Every request from an Android WebView carries
`X-Requested-With` set to the app's package name. Chromium started removing it in M112 under a
deprecation trial, then abandoned the removal; the androidx allow-list API meant to control it
is marked disabled in Chromium's own feature list, and WebView's tests assert the header is the
package name on every main-frame and sub-resource request, on Google's WebView and on Vanadium
alike. So the WebView-backed features (photos, reviews, popular times, transit directions, the
stop board and the reviews page) tell google.com `app.vela` by name. Search, directions,
autocomplete, the map's place fan-out and the one-request place data go through the app's own
client (Cronet, or OkHttp as the fallback) and do not. `WebViewIdentity` still
makes the allow-list call, gated, in case a WebView build ever honors it, and logs one
`VelaWeb identity:` line per view saying which switches took. Overriding the header on the
document load alone was considered and not done: the page's own script requests would still
carry it. The one way around it is not to let the WebView send the request at all, which is what
the proxy below does, off by default.

**Bridge names are random.** A scrape reports back through a JavaScript interface, and
`addJavascriptInterface` puts that object on the page's `window`, where Google's own scripts can
enumerate it. A fixed `VelaBridge` or `VelaPanel` sitting there named the app to anyone who
looked. Since 2026-09-23 `web/JsNames` draws both names once per process (an underscore and ten
random letters). The scripts are still written with the readable names, and `JsNames.of` swaps the
real ones in right before every `evaluateJavascript`, so a new script call has to go through
`JsNames.of` or its bridge calls go nowhere. The proxy's own bridge and the parameter it tags
requests with are drawn the same way.

### The WebView proxy

Behind the calibration dial `webProxy` (default 0, off), `web/WebProxy.kt` takes a Google
WebView's requests away from the WebView and sends them from the app over Cronet, with the
WebView's own cookies (`WebViewCookieJar`) so the page keeps its session. A request the app sends
carries no `X-Requested-With`. Any failure returns null and the WebView loads that request itself,
exactly as before.

- **GETs** are intercepted directly and streamed.
- **POSTs** cannot be: `shouldInterceptRequest` never sees a request body. So a document-start
  script (`WebProxy.SHIM`, dial `webProxyPosts`, default 1 when the proxy is on) wraps XHR, `fetch`
  and `sendBeacon` on google.com pages. A POST to a Google host gets a one-time id appended to its
  URL and its body handed to the bridge first; when the tagged request reaches the interceptor, the
  body is waiting for it, the tag is stripped and the app sends it. Only plain-text bodies (a
  string or URL parameters) are carried; FormData, a Blob or a Request object goes out from the
  WebView as before. Measured on a Pixel 9 before the shim, the POSTs left were the review page's
  `batchexecute`, `play.google.com/log` and the account bar's `ogads-pa` calls.
- **Telemetry can be answered locally**, and since 2026-09-25 that is the user's choice: Settings >
  Privacy "Block Google's page telemetry" (`web/GoogleTelemetry`, default OFF; the dial
  `webProxyBlockLogs` still overrides when set). Blocked, `play.google.com/log`, any `gen_204` ping
  and the account bar's `ogads-pa` get an empty 200 from the app and never leave the phone, with the
  proxy on or off. Nothing Vela reads depends on them; they are the page reporting on itself, and ad
  blockers drop them too. They flow by default because a browser that never sends them looks less
  like a person to Google's traffic scoring, which is what hands a session the limited view. The
  answer carries CORS headers that echo the page's origin, and it is a 200 on purpose: an
  intercepted 204 reached the page without those headers on a Pixel 4a, so every blocked call
  turned into a console error.

Logcat `VelaWebProxy` prints each path once, as `carries:`, `answers locally:` or
`passes through:` (still sent by the WebView, with the header). The dial is read on every request,
but the POST shim is installed only when a view is built, so turning the proxy on reaches POSTs
from the next view.

### Which Google session, and how long it lives

**Google limits new anonymous sessions** (the measurement is under
[Place data](#place-data-the-methods-and-how-to-roll-each-one-back)): a session that has only just
started gets about five reviews, no paging, and on a busy place no popular times. The app's own
cookie jar lives in memory, so its session is new every launch; the WebView's cookies are on disk
and age. So the per-place requests (details, the photo pages, the review feed) are tagged
`AgedSession` in `GoogleMapsDataSource`, and the Cronet transport sends a tagged request with the
WebView's cookies instead of the app's (dial `agedSession`, default 1). It needs no page load and
sends no `X-Requested-With`. Without Cronet the tag does nothing and the request keeps the app's
session. On a fresh install the WebView store is empty too, so the first requests are a new
session either way.

**A saved cookie is a history.** It carries no name or account, but everything an install asks
Google for while one cookie lasts can be linked together. So the session is thrown away on a
schedule (`web/SessionRotation`, Settings > Privacy > "Google session", pref
`google_session_rotate`):

| Setting | A new session | What it costs |
| --- | --- | --- |
| Every week (default) | when the last one is 7 days old | at most a week of linkable history; a stretch of the limited view after each reset |
| Every day | when the last one is 24 hours old | at most a day |
| Every time Vela opens | at every process start | the limited view is the normal state, first answers come back trimmed and are asked again, and Android restarting the app in the background can mean several new sessions a day |

The check runs once per process start, in `VelaApp` before the Cronet engine opens. The first run
only records when the session began. **A rotation clears** the WebView's cookies (on a background
thread, because the cookie manager loads the WebView library), its site storage (on the next idle
moment of the main thread), Cronet's disk cache (only at process start, since it is not safe to
delete once the engine has it open) and, the first time a Google WebView is built afterwards, that
WebView's HTTP cache (`consumeCacheClear`, which every WebView-built fetcher calls). "Start a new
session now" does the same by hand and also empties the app's in-memory jar. The WebView store
holds only Google: no other site is loaded in a WebView. Logcat `VelaSession` says when a rotation
happened.

This is the trade-off, not a fix for it. Keeping one session for good gives the full view and one
long pseudonymous history; a new one every launch gives the least history and mostly the limited
view. Session standing, not age alone, decides which view Google gives: on 2026-09-23 a Pixel 4a
whose WebView session was weeks old was already in the limited view while a Pixel 9's was not.

**It is the session, not the connection.** On 2026-09-25 three phones shared one public IPv4
address (no IPv6): one Pixel 9 got 50 photos per page and a full reviews page, while another
Pixel 9 and the 4a got 10, and that second Pixel 9's reviews page was Google's paged Overview
layout, ending in its "Sign in" footer. So
the limited view follows the cookies, and a freshly installed build on the 4a was limited from
its first request: wiping or rotating the session does not lift it, which is why the Settings
text says starting a new one rarely helps. Google still sees the IP address, which links sessions
from one connection over a short time anyway.

**Telling the user.** In the limited view the place sheet gets quietly thinner, which reads as a
broken app. So Vela watches for it (`web/GoogleStanding`). The first photo request asks for 50
photos: a full session gets 50, a limited one gets 10 with more pages waiting. On 2026-09-25 two
phones on one connection, running the same query in the same minute, split exactly that way, and
only the one that got 10 was missing popular times. So a first page of 20 or fewer with a next page
marks the session limited, and so does "More reviews" loading nothing on the full reviews page; a
first page of 40 or more clears it. A missing popular-times chart on its own proves nothing (many
places have none), so it never marks anything. While marked, a Google place with no chart shows one
dim line where the chart would be ("Google is showing a limited view right now..."), and Settings >
Privacy > Google session says the same. The mark belongs to the session: any rotation clears it.

### The slim early-session answer

For roughly the first three seconds of a fresh session, Google's search answers with a stripped
place block: the rating is there, the review count is not. The same query a few seconds later
comes back complete. This was bisected live on 2026-07-14.

It matters because the map ranks and sizes Google's places by review count (see
[chapter 1](01-places.md#googles-own-ranking-when-google-is-drawing)). The fan-out that fills
the map on a cold start lands entirely inside that window, so the whole pool arrived with no
counts, every place scored zero, and dot sizes and label tiers went flat, then got cached that
way.

`nearbyPlaces` detects the slim flavor and asks again once:

```
rated >= 3                                     // enough rated places to judge
count(rated and no reviewCount) > rated / 2    // a majority, not all: the session can warm mid-burst
delay(1200)                                    // then refetch the whole fan-out once
```

If the refetch carries counts, its places go first in the merged pool, so the de-duplication
keeps the rich copy of each place. The heal doubles the request burst, but only on a cold
start. The map's stickiness rule never freezes a pool whose prominences are all zero, for the
same reason.

The fan-out it refetches is 15 category searches (8 on a low-memory phone or a constrained
link), at most `ambientFanoutPermits = 4` parsing at once. Each response is parsed into a
full JSON tree of up to tens of megabytes, and firing them all at once filled a Pixel 9's
512 MB heap in one burst. The permit count is clamped to 1..13 and is a tuning dial, so a dense area that
still spikes can be answered from the bundle.

### Language and region: the hl and gl rewrites

Every Google endpoint is written with `hl=en&gl=us`. Just before a request goes out, two
rewrites run:

- **`gl` (region)** becomes the country the phone is actually in: the cell network's country
  code, then the SIM's, then the locale's region, refreshed each launch. Any two-letter code is
  accepted; region tunes ranking, not the response shape. A US phone's request is byte for byte
  unchanged.
- **`hl` (language)** becomes the app's language, so categories, hours and the open or closed
  line come back in it. Only for languages the open/closed parser has a word table for; for any
  other, `hl` stays `en`, because an English status the parser can read beats a localized one
  it cannot, which would leave every place with no open or closed color. Chinese carries its
  script: `zh-TW` for Traditional (Hant script, or Taiwan, Hong Kong, Macau), `zh-CN`
  otherwise. A caller can force a language outright (the tap lookup does, for a label in
  another script).

The hidden WebViews pin `hl=en&gl=us`, with one exception: the review scrape follows the app
language, and so does the visible full reviews page, because the page language decides *which*
reviews Google serves, and reviews are content, never translated for the reader.

### The autocomplete request

The search box's suggestions come from Google's own autocomplete, not the search endpoint. The
search endpoint ranks a partial address by prominence over the whole window and would answer a
house number with a ZIP code in another state; the autocomplete honors the location bias the
way the Maps website does. Once typing pauses for 320 ms, the app sends:

```
GET https://www.google.com/s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=..&gl=..&pb=<window>&q=<text>&tch=1&ech=1
```

with the XHR header set above. The `pb` carries the viewport center and its height in meters:

```
SUGGEST_SPAN_M = 20_000     // when the caller has no viewport: about a town
span clamp     = 2_000 .. 500_000 m
```

When it answers, its rows are the suggestions (an exact hit from a downloaded address pack still
leads). When it fails, returns nothing, or Google is switched off, the older pipeline runs:
Photon, the on-device address index and, with Google on, the search endpoint. [Chapter 6](06-search.md) has the rest of search.

### What is dead, and not to be re-chased

Each of these was probed and proven closed. Re-probing them is a known waste of time.

- **The reviews RPC.** `listentitiesreviews` returns 404 for everyone, verified on 2026-07-19
  from a raw client and from a real logged-out Chromium. The endpoint and `reviewsPb` are still in
  the bundle, and `reviews()` still exists, but nothing calls it. Reviews come from the WebView
  scrape, or from the `qv9Egd` review feed, a different RPC that is built and switched off (see
  Place data). If reviews break, debug the scrape, not this RPC.
- **Live busyness.** The "busier than usual" bar is stripped from every anonymous request and is
  login-gated. The chart and its "right now" line on the sheet are the typical week read at the
  current hour, not a live figure.
- **Live traffic incidents.** Google draws them from proprietary binary vector tiles; Waze's feed
  sits behind reCAPTCHA (probed four ways on 2026-08-08, all 403). Only per-state DOT and 511
  feeds remain. Congestion coloring on the route covers "where is it slow".

Two entries came off this list on 2026-09-23. **Photo dates** were listed as dead because the
gallery RPC (`hspqX`) answered zero photos to anything automated, even a byte-identical replay of
the page's own request, and that read as bot-gating. It was a missing header: with
`x-maps-diversion-context-bin` the RPC answers a plain request, dates included. **Popular times
over a plain request** were listed as stripped from every keyless search; Google strips a place's
first request and answers the same request complete seconds later, which is what the details
retries below rely on. Both are a warning about this list itself: probe with the page's own
headers before calling something closed.

### "Use Vela without Google"

One switch in Settings > Privacy (pref `google_free`, off by default), mirrored into the core
module's `NoGoogle` flag and checked at each seam where a Google request would start. With it
on:

- **Search** answers from Photon, OpenStreetMap's geocoder, with its own ranking softly biased
  to you, plus the downloaded place packs. Names and addresses, not categories, except where a
  region is downloaded. **Autocomplete** from Google returns nothing, so the Photon and
  on-device path runs.
- **Places on the map** come from Vela's own data. The Google fan-out, "More results", and the
  tap lookup that matches a tapped pin to its Google listing are all off.
- **Directions** are the open router's alone: no Google traffic, no Google alternates, no
  Google fallback route, so no live arrival time.
- **Every hidden WebView** returns nothing before it loads a page: no photos, reviews, popular
  times, transit directions or Google stop-board fallback. Their warm-ups do not run. The full
  reviews page is hidden.
- **Street View** answers "no coverage" and its button is hidden.
- **The traffic overlay** (a Google tile server) is off, and **satellite** stops falling back to
  Google's imagery for the close-up zooms.

What it does not touch: the calibration fetch (that is GitHub, not Google), the open basemap,
routing, transit boards from Transitous, cameras, road features and everything offline.

One Google request survives it, and only by choice: **a short Google Maps link**
(`maps.app.goo.gl/...`) says nothing about where it points until Google's link shortener is asked.
With "Open shared Google Maps links" on (the default, shown under the switch), `core/data/ShortLinks`
asks it once per hop with no cookies, reads the redirect's `Location` and stops before loading any
Google page; the target's place name and its own pin (`!3d`/`!4d`, preferred over the sharer's
`@` map center) are read on the phone by `MapLinkParser` and searched like any deep link. Turned
off, a short link is refused with a toast. A full `google.com/maps` link never needs the request.
A shared LIST cannot open under the switch at all, because its places exist only on Google's
servers (toast `map_import_needs_google`); with Google on it imports as before. Logcat `VelaLink`
prints where a short link pointed, cut before the `@` coordinates and the query. The FAQ's full cost list is in [docs/FAQ.md](../FAQ.md#can-i-use-vela-without-google-at-all);
what still works with no network at all is [chapter 8](08-offline.md).

## Place data: the methods, and how to roll each one back

Since 2026-09-23 a place tap asks Google for each piece of its sheet with ONE plain request,
where it used to load Google's whole web app in up to three hidden WebViews (several hundred
requests per tap, which is the kind of volume that puts a session into Google's limited view).
Every new method keeps the old one behind it as the fallback, and each can be switched back
without a release. This table is the record to revert from.

| Piece | Now | Falls back to | Remote switch (calibration `tuning`) | Before 2026-09-23 |
|---|---|---|---|---|
| First photos | `hspqX` RPC, one request asking for 50 (`placePhotoPage`, `PHOTO_COUNT`), dated; a full session answers 50, a limited one 10 | two more tries; then the sheet keeps the search's hero photo and "More photos" walks the page | `nativePlacePhotos` 0 | the full page walk (every gallery tab) on every tap |
| More photos | the next `hspqX` page, one request per page (cursor at `[4][2][2]` of the request, payload[5] of the reply; payload[1] is not the photo total and is not read) | one retry, then the full page walk | `nativePlacePhotos` 0 | the same walk |
| Menu tab | only from the page walk: "Load all photos and reviews" on, or "More photos" after native paging fails. The RPC carries no category per photo | none | none | the walk on every tap |
| First reviews | the page scrape, stopped at `FIRST_REVIEWS = 10` (the default). The one-request `qv9Egd` feed (`reviewFeed`) is built but off: on the app's own session, new every launch, it got the limited 5 reviews, and on the WebView's aged session (where it is sent now, with the other per-place requests) it got 0 on a Pixel 9. It stays off until a reply from a healthy session has been captured | the page scrape | `nativeReviewFeed` 1 turns the feed on (compiled default 0) | the page scrape to 50 on every tap |
| More reviews (inline) | the feed's next page, when a reply carries a token (UNVERIFIED: no captured reply has one yet) | the All reviews page | follows `nativeReviewFeed` | the scrape already held up to 50 |
| All reviews | Google's own page, full screen, on tap | none | none | the same |
| Details (popular times, blurb, count, hours) | the search reply when it has them; else ONE plain request of the details page's own search (`placeDetails`, same parser), up to three tries while popular times are missing | the details page, only when every try came back stripped | `nativeDetails` 0 | the details page on nearly every tap |
| Page warm-ups after a search | none | none | none | google.com + Maps loaded in two hidden views per search |
| Transport for every Google request | Cronet (Chrome's network stack, HTTP/2 or HTTP/3) | OkHttp on any Cronet failure | `useCronet` 0 | OkHttp |
| Session for per-place requests (details, photo pages, the review feed) | the WebView's aged Google session, sent over Cronet (`AgedSession` tag, `WebViewCookieJar`): the app's own session is new every launch and Google gives it a limited view, which dropped popular times on busy places | the app's session when Cronet is off | `agedSession` 0 | the app's session |
| WebView page loads | the WebView itself; the Cronet proxy (no `X-Requested-With`, the WebView's own cookies) when on: GETs directly, Google POSTs through a document-start shim that hands their bodies to a randomly named bridge (`webProxyPosts`), and, when the user blocks it, the page's telemetry (`play.google.com/log`, `gen_204`, the account bar's `ogads-pa`) answered locally with an empty 200 | the WebView itself | `webProxy` 1 turns it ON (default 0); `webProxyPosts` / `webProxyBlockLogs` 0 turn the parts off | the WebView itself |
| Neighbor prefetch (ambient) | Google-only mode | none | none | every mode, ~60 requests per map settle |

**Google limits new anonymous sessions** (measured 2026-09-23 on a healthy Pixel 9): the same phone's
weeks-old WebView session loads the full review feed, while a brand-new WebView session there, and
the app's own native session (its cookies live in memory, so it is new every launch), get the
limited view: five reviews, no more pages, no Reviews tab. Clearing cookies therefore never
escapes the limited view; it throws away the aged session that works. Photos and details
answered on fresh sessions too, so they stay on the one-request path (a limited session answers
10 photos per page rather than 50, which is how `GoogleStanding` spots it). The one persistent
session this paragraph once called open now exists for the per-place requests: they borrow the
WebView's aged session (the `AgedSession` row), rotated weekly by default, and that is the privacy
trade-off made explicit in [Which Google session](#which-google-session-and-how-long-it-lives).

What the one-request methods depend on:

- **The `x-maps-diversion-context-bin` header** (`Calibration.rpcContext`, `CAE=` today) on every
  batchexecute POST. Without it the feed answers empty and the gallery answers zero photos,
  which is what "bot-gated since July" really was. If Google changes the value, push the new
  one in `rpcContext`; blank stops sending it. The daily health check (`google-health.yml`)
  requests both RPCs, so a change shows as DRIFT the next morning.
- **The feed's proto** (`Calibration.reviewFeedProto`, `{FID}` and `{TOKEN}`) and the photo
  proto (`photosProto`), both remote.
- **Google answers a place's FIRST request stripped** and the same request seconds later in full
  (the details search on the 4a: 45 KB without popular times, then 93 KB with them, through OkHttp
  and Cronet alike, so it is not the TLS handshake). Every piece tries up to three times (about
  2.5 s, then 3.5 to 4 s apart) before any page load. Some replies still come back without
  popular times after three tries; the sheet then shows none and the next open after the 15-minute
  details cache tries again.
- **Retry timing is remote too:** `placeRetryMs` (2500, the wait before the second try),
  `placeRetryStepMs` (1000 more per later try) and `placeTries` (3, then the page fallback).
- **Per-place cache:** photos and the feed are kept 6 hours, details 15 minutes (popular times
  carry a "right now" reading that should follow the clock), 80 places each, for the life of the
  process.

Order to reach for when something breaks:

1. **A calibration push** (no release): set `nativePlacePhotos` or `nativeReviewFeed` to 0 to put
   everyone back on the page paths, or fix `rpcContext` / the protos. An installed build picks it
   up at its next launch once GitHub's raw file cache (about five minutes) has the commit to main;
   see the one-launch-behind limit below.
2. **Per user:** Settings > Performance > "Load all photos and reviews" restores the full walk
   and 50 reviews on that phone.
3. **Code:** the commits on main are titled "Opening a place asks Google for its photos and
   reviews in one request each...", "Review and photo requests retry once...", "Opening a place
   loads its first photos and reviews instead of everything...", and the cache and More reviews
   commit after them. Reverting them restores the page loads.

`VelaPlaceLoad` logcat lines and the `reviews` diagnostics events say which path each piece took
on a given tap ("photos: rpc 50", "photos: rpc 10" in a limited session, "photos: cache 50",
"reviews: feed 5 (limited view)", "reviews: feed 0, scraping the page", "details: missing
[popularTimes]; details page").

Cost, like for like. A tap used to load up to three Google web apps (several hundred requests)
and walk the whole gallery in 13 to 15 s. Now it is the resolve search (Vela-data and basemap
taps only) plus one to three plain requests each for photos, reviews and details, and no page in
the usual case; each piece lands 0.3 to 4 s after the tap (up to about 9 s when details use all
three tries). The whole gallery is still there: one request per page ("More photos"), which for
a 200-photo place is 4 requests in a full session and 20 in a limited one, against one walk of
several hundred, and it streams while the walk made you wait for everything.

## Limits

- **The handshake is Chrome's only while Cronet carries the request.** The Cronet build is
  Chromium 143 and offers three fewer signature algorithms than current Chrome, so its `ja4`
  differs until the build catches up (SPEC 3.6). The APK ships Cronet's native library for ARM
  only, so on an x86 emulator or Chromebook, and after any Cronet failure, Google requests go
  over OkHttp, whose handshake says OkHttp. A TLS stack of Vela's own would need native
  dependencies and permanent maintenance and would break reproducible F-Droid builds, so there
  is none.
- **Smaller residual tells, left alone:** Chrome sends `X-Client-Data` to Google origins and
  neither client here does; search, directions and autocomplete use the app's own in-memory
  jar while the per-place requests use the WebView's, so one phone is two sessions from one IP;
  `Accept-Language` stays `en-US,en;q=0.9` even when `hl` asks for another language.
- **`X-Requested-With: app.vela`** names the app on every request the WebView sends itself, and
  nothing in the WebView can stop it. It is confined to the WebView-backed features. The proxy
  keeps it off whatever the app sends instead, but the proxy is off by default and a POST body it
  cannot read still goes out from the WebView; turning those features off (or the whole switch)
  is the only certain way.
- **Rotation is checked only at process start.** A process that lives past the week keeps its
  session until the next start or the button.
- **The map's own Google tiles** (the traffic overlay and the satellite fallback) go through
  the map engine's HTTP stack, not `BrowserHeaders`, so they do not carry the Chrome identity.
- **The UA only moves when someone pushes it.** The live bundle carries Chrome 155 (v23), the same as
  the compiled default, and nothing updates it on its own: the daily check says when it is due,
  and a person edits, re-signs and commits. A build older than 2026-09-23 also sends the pushed
  `secChUa` as-is, so a bundle that moves `userAgent` without the matching hint is wrong on those
  phones even though newer builds derive the hint themselves.
- **The Chrome check does not turn the run red.** The step pipes the script into `tee` for the
  run summary without naming a shell, and GitHub's default shell for that is `bash -e` with no
  `pipefail`, so the step takes `tee`'s exit status and passes. The verdict is on the run summary
  page, and no mail goes out for it; the endpoint job is the one that fails loudly.
- **A pushed UA reaches a WebView only when the view is built.** A view created before the
  launch's refresh keeps the old identity until it is reaped.
- **Adoption is one launch behind.** The bundle is fetched once per process, after start, so a
  fix reaches a phone on its next launch or the one after. A dial read on every request (the
  place-data switches, `webProxy`) takes effect as soon as the new bundle is adopted.
  `ambientFanoutPermits` in particular is read when the data source is built, so it needs a
  process restart.
- **There is no rollback, only roll-forward.** A bundle only replaces one with a lower version.
  Undoing a bad push means publishing the old content under a higher version number.
- **New parsing logic is limited to search.** `transformsJs` hooks the search parser only; a
  reshaped directions, autocomplete or WebView payload still needs a path edit or an app release.
- **The consent pre-seed is the lightest-touch fix.** If Google ever insists on its own consent
  handshake, the full form post is the follow-up, and nothing has asked for it yet.
