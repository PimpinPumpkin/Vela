# The Google extractor & calibration

Split out of the README (it is reference material, not the pitch). How the per-user
extractor works, what `calibration.json` remotely repairs, and how to recalibrate when
Google moves a field.

Calibrated live on 2026-06-15. The shapes Google can change are pinned here so
re-calibration is a lookup, not a rediscovery:

**Search** - `GET /search?tbm=map&q=<q>&pb=<SearchPb>`. A bare `q=` returns an
empty envelope; the `pb` (viewport-driven, captured in [`SearchPb`](core/src/main/java/app/vela/core/data/google/SearchPb.kt),
no session token needed) is what populates results. Results at `root[64][i]`,
each rooted at `[1]`: name `[1][11]`, **full address `[1][39]`** (street, city,
state, ZIP - fall back to joining the components at `[1][2]`), rating `[1][4][7]`,
reviews `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`,
feature id `[1][10]` (`0x..:0x..`, → reviews endpoint), place id `[1][78]`,
**photos `[1][72][0][i][6][0]`** (FIFE URLs; re-size with a `=w500-h350`
suffix), **featured review snippet `[1][142][1][0][1][0][0]`**, and the **About**
attributes at `[1][100][1]` (see below). Full reviews come from a separate
keyless endpoint (below). A **specific/far address** doesn't come back as a `[64]`
list - it's a single geocoded result whose place node sits at `[0][1][0][14]`
(same internal schema), so the parser falls back to that, then to the largest
name+coord array; a structurally-valid response with no matches returns empty
(not a calibration error).

**Directions** - `GET /maps/preview/directions?pb=<DirectionsPb>` (no token).
Routes at `root[0][1][r]`, summary at `[0]`: distance m `[2][0]`, typical
duration s `[3][0]`, and **live `duration_in_traffic` s `[10][0][0]`**. Steps
arrive as `<step maneuver='TURN_LEFT' meters='120'>…</step>` markup - type and
distance parse straight out of the attributes. But Google's keyless steps come
back **abbreviated** on longer routes (a 6-mi route returned 2 of ~10 turns), so
**turn-by-turn + geometry are now PRIMARY from an open router**
([`RouteGeometry`](core/src/main/java/app/vela/core/data/RouteGeometry.kt), FOSSGIS
OSRM with a per-mode `routed-car`/`routed-bike`/`routed-foot` backend) - every turn,
with street names, for drive/walk/bike. Google's directions response is kept for
what it actually wins: the **live `duration_in_traffic`** (scaled onto the OSRM
route) and **per-segment congestion** at `route[3][5][0]` - `[level, startMeters,
lengthMeters]` spans (non-free-flow stretches only), mapped onto the OSRM geometry
as `Route.trafficSpans` → the route line's Google-style colour bands (blue → amber
→ red → dark-red) - plus it's the **fallback router** when OSRM is unreachable. Its
own complete geometry (delta-encoded E7 at `[0][7][i]`, decoded in
[`DirectionsParser`](core/src/main/java/app/vela/core/data/google/parse/DirectionsParser.kt))
still drives the **traffic-aware snap**: when Google's live route diverges from
OSRM's free-flow one (a jam reroute), Vela re-runs OSRM *through points sampled off
Google's polyline* so you follow Google's smart path **with** full OSRM steps. Live
**public transit** is a separate path - Google silently downgrades a keyless
transit request to driving, so it goes through a hidden WebView (see below).

**Place details** ride along in the search response - no separate RPC for the
common fields: website `[1][7][0]`, price text `[1][4][2]`, open-status
`[1][203][1][8][0]`, rich status with closing time `[1][203][1][4][0]`
("Open · Closes 9 PM"), and **weekly hours `[1][203][0]`** for most places -
falling back to `[1][118][0][3][0]`. Both are 7-entry arrays starting with
today: day name `[0]` + hours text `[3][0][0]`. (Re-calibrated 2026-06-16;
reading only `[118]` had missed hours for the majority of businesses.) Google's
**editorial one-liner** ("Welcoming coffeehouse…") sits at `[1][32][1][1]` and the
business's own **"From the owner"** blurb at `[1][154][0][0]` - both shown in the
sheet (the keyless/list response trims them, so they ride the same lazy WebView detail
fetch as popular times). The
**About** panel rides along too at `[1][100][1]` - a list of sections, each with
a title `[s][1]` and items `[s][2][j][1]` (Service options, Highlights,
Accessibility, …). **Popular times** (`[1][84]`) ride a hidden WebView with a
*specific* query - see below.

**Reviews** - ⚠️ **the `listentitiesreviews` RPC below is DEAD** (Google 404'd it; it only ever
served avatars). Reviews now come from the place's `?cid=` page rendered in a hidden WebView
([`WebReviewsFetcher`](app/src/main/java/app/vela/web/WebReviewsFetcher.kt)): `.jJc9Ad` cards de-duped by
`data-review-id`, accumulated across scroll, **with per-review uploaded photos** - up to ~50, not the old
~3 (the headless WebView needs an explicit offscreen viewport for Google's virtualized list to render - see
SPEC §Reviews). The pb below is kept only as calibration history.
`GET /maps/preview/review/listentitiesreviews?pb=…` was a keyless
endpoint (no token: the `!5m2!1s<session>` block accepts any string). The pb is
`!1m2!1y<HIGH>!2y<LOW>!2m2!2i<offset>!3i<count>!3e1!5m2!1svela!7e81`, where
`<HIGH>`/`<LOW>` are the two halves of the place's feature id `[1][10]`
(`0xHIGH:0xLOW`) as unsigned-64 decimals. Reviews come back at `root[2]`, each:
author `[0][1]`, author photo `[0][2]`, relative time `[1]`, text `[3]`, rating
`[4]`. It needs the same consent cookies as search (the shared cookie jar carries
them); a cookieless request returns an empty envelope. It serves a **fixed top
~20** (the `2i` offset is ignored and `3i` count is capped); deeper pagination is
behind an obfuscated continuation token, deliberately not chased.

**Photos** - the search response carries a **photo preview** at
`[1][72][0][i][6][0]` (the immediate hero; Google **moved this block `[105]`→`[72]`
on 2026-06-27**, which briefly blanked every hero strip - hot-fixed via calibration
`v7`, no app update) - **de-duped** (Google now serves the single hero **twice**) plus
a small `[1][204][0][i][1][2][0][0]` block that **only landmark places carry** (a famous
landmark → ~4; an ordinary business → **1**). The **full gallery (~9-25 photos) is scraped from the place's own page**, and that
**replaced** the bare `hspqX` photo RPC. That RPC
(`POST …/batchexecute?rpcids=hspqX`, `/MapsPhotoService.ListEntityPhotos`) is **bot-degraded
per-session** to a Street-View-only reply - on-device logging showed byte-identical degraded
replies across retries, so retrying never recovers it. But Google **renders the real photo
collage to a logged-out browser on the place PAGE itself.** So
[`WebPhotoFetcher`](app/src/main/java/app/vela/web/WebPhotoFetcher.kt) (a **hidden WebView**,
real Chromium, **desktop UA**, anonymous/no-login) loads the place's `?cid=` Maps page, lets
Google's own JS draw it, then a self-polling injected script scrapes every `googleusercontent`
photo URL out of the DOM (avatars + Street View filtered, de-duped by image id; clicks the
"Photos" affordance + scrolls to surface more) and bridges them back - **the same tactic as
[`WebReviewsFetcher`](app/src/main/java/app/vela/web/WebReviewsFetcher.kt)**. A rendered page
is far harder for Google to bot-degrade than a naked RPC POST. **Keyless** (no key, no
account); lazy + best-effort (failure → keep the preview). While it's in flight the sheet
shows a row of **pulsing shimmer tiles** (`MapState.photosLoading`) so it reads as "more
loading". Gotchas: **desktop UA** (a mobile UA makes Google deep-link to `intent://`),
**block non-http(s) redirects**, and a `Handler` not `View.postDelayed` (a headless WebView
never attaches). *(History - the gallery was first wrongly called sign-in-gated, then wrongly
"retry-fixable"; the truth: the bare RPC is per-session-degraded and the page scrape sidesteps
it. 2026-06-28.)*

**Public transit** rides the same WebView trick for the same reason: a `directions`
GET with the transit flag is silently downgraded to a *driving* reply, so
[`WebDirectionsFetcher`](app/src/main/java/app/vela/web/WebDirectionsFetcher.kt)
navigates the `/maps/dir/…/data=!4m2!4m1!3e3` page and reads the itinerary set out
of `APP_INITIALIZATION_STATE` (the **longest** `)]}'` payload at slot `[3]` - a tiny
stub sits beside the real ~165 KB one), which
[`TransitParser`](core/src/main/java/app/vela/core/data/google/parse/TransitParser.kt)
turns into the results board (trips at `root[0][1]`, each trip's summary at
`trip[0]`: departure/arrival times, total duration, agency, and the coloured line
pills you ride). Keyless, best-effort, device-verified Davis→Sacramento.

**Popular / busy times** ride the same WebView for the same TLS-fingerprint reason -
but with one extra catch: the histogram (`[1][84]`) is stripped not just from the
keyless OkHttp reply but also from a **bare-name** WebView search, which comes back
as a 20-result `[64]` list trimmed of `[84]`. The fix is the *query*:
[`WebPopularTimesFetcher`](app/src/main/java/app/vela/web/WebPopularTimesFetcher.kt)
searches **name + address** (e.g. `In-N-Out Burger 1020 Olive Dr Davis CA`), which
resolves to a *single focused result* whose place node at `[0][1][0][14]` keeps
`[84]`. [`PopularTimesParser`](core/src/main/java/app/vela/core/data/google/parse/PopularTimesParser.kt)
reads it (via `SearchParser`'s single-result snap, or straight off the focused node)
into the day-chip + "busy right now" histogram in the place sheet. Keyless,
best-effort, lazy on place-select like photos. *(Earlier called sign-in-gated - that
too was bot-degradation, not a login wall. Corrected 2026-06-19.)*

**Remote calibration.** The brittle bits that drift - the `pb`/proto templates and
the endpoint URLs above (search, directions, reviews, **photos**) - are not
hard-compiled; they live in [`calibration.json`](calibration.json) at the repo root.
[`CalibrationStore`](core/src/main/java/app/vela/core/config/CalibrationStore.kt)
ships a bundled `Calibration.DEFAULT`, then fetches that file from the repo's raw
URL at launch and **adopts it when its `version` is newer** - gated by a host
allowlist (every endpoint must be `google.com`, so a tampered file can't redirect
requests). So when Google reshuffles a `pb`, moves an endpoint, **or relocates a
field index**, the fix is a one-line edit + `version` bump committed to `main` -
**every user gets it on their next launch, no app update**. Phase 2 added the
`paths` object - the search parser's positional field-index paths (`name`,
`address`, `rating`, `photos`, `featureId`, … as `[i,j,…]` arrays; relative to a
result entry whose place node is `[1]`, except `results`/`single` which are
root-relative). Only a change that needs genuinely new parsing *logic* still ships
as a release.

**Reverse-geocode** (long-press the map → drop a pin → address) uses
OpenStreetMap's **Nominatim** (`/reverse`, keyless and documented) rather than
Google, since Google's map search doesn't reverse-geocode a `lat,lng` query.

To re-calibrate when a shape drifts: capture the request in DevTools, mask the
query/coords, and replace the `pb` template in `SearchPb`/`DirectionsPb`; re-pin
the response indices in `SearchParser`/`DirectionsParser`. `VelaConfig.USE_GOOGLE_SOURCE`
is already `true`.

When a response no longer matches, the parsers throw `CalibrationNeededException`
and the UI shows a non-fatal "needs recalibration" notice - that's the *expected*
periodic failure mode, not a crash. `PolylineCodec` needs no calibration; it
decodes Google's geometry exactly and is covered by a reference-vector test.

> **Do not embed a static Google API key.** That converts "a user scraped from
> their own IP" (defensible, NewPipe's footing) into "the app shipped Google's
> credential" (not). The per-user `GoogleSession` bootstrap is the whole point.

