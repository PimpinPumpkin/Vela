# Vela Maps - Roadmap

> Where Vela is going. [`FEATURES.md`](FEATURES.md) is what's **shipped**;
> [`SPEC.md`](SPEC.md) is **how it's built**; this file is **what is still open** and the
> bigger bets. What shipped, and every dead end that was investigated, moved to
> [`docs/ROADMAP-HISTORY.md`](docs/ROADMAP-HISTORY.md) on 2026-09-21 with its reasoning intact;
> this file is pruned to what somebody could pick up today. Add ideas here the moment they come
> up; when one ships, move its entry to the history file in the same commit.

Last updated: 2026-09-21.

## North star

A degoogled, keyless Google-Maps replacement that reaches **parity** with Google
Maps and, over time, **leans less on Google** by growing Vela's own data layer
(starting with traffic). Privacy-first, F-Droid, GPLv3 - every new data flow is
opt-in and documented in [`PRIVACY.md`](PRIVACY.md).

## Next up

Roughly in the order they are worth doing. Each one is small enough for a single PR.

- **Camera detours over every candidate route (issue #600 follow-up, 2026-09-21).** The shipped
  "Try side streets around cameras" pass only detours the route that LEADS after the camera
  re-rank, and the two stages can disagree: a route with three cameras on one arterial with a
  parallel street beside it detours better than the one-camera route whose camera sits on a
  bridge, but the one-camera route wins the re-rank and the pass never looks at the other. The
  holistic version runs the cluster/offset pass on every drivable candidate, scores each result
  by cameras left plus time added, and leads with the best. The cost is the request budget (six
  per route instead of six in total), so it wants a shared cap or a cheap pre-screen that skips a
  route whose cameras sit where the geometry offers no parallel road. Whether the two toggles then
  become one switch is the same decision: today "avoid" costs no requests and "side streets"
  costs a handful, which is why the second is nested and off.
- **Reroute on the phone first (deferred 2026-09-16).** When a downloaded region covers the drive,
  compute the reroute with the on-device engine at once, then swap in the traffic-aware online
  route when it arrives through the existing heal path. Evidence: a shared diagnostics export
  (issue #557) shows two urgent reroutes timing out at 20 s while the open router hung, and issue
  #258 reports the same pattern in cities. Since 2026-09-17 the on-device engine is a bounded
  FALLBACK inside a reroute; the "phone first, heal later" order is still open, held back because
  every latch back onto the online route is new bug surface.
- **Turn delta updates on by default.** The append-in-place PMTiles patch shipped 2026-09-18 and
  was proven on a Pixel 9 the next day (a 94 KB patch against a 3.3 MB archive, fingerprint
  checked, dead space reclaimed locally). `RegionUpdates` still defaults to OFF; flip it to WIFI
  once a second device has taken a patch on a real state-sized archive.
- **A name index for the downloaded places archive (2026-09-21).** Offline search reads the OSM
  place pack, and OSM is missing whole chains in places (the parts store that started this was
  on the map from the Overture archive and absent from search). The places PMTiles is spatial
  only, so finding it by name means scanning tiles. Bake a small sidecar per region (name,
  normalized key, category, lat, lng; a few MB for a state) beside the archive, download it with
  the archive, and have the offline search branch query it after the pack, deduped by name and
  distance. Same shape as the road-features file.
- **Offline timetables per region (open question, 2026-09-21).** The cached boards cover stops
  the user has tapped online; a stop never tapped shows nothing offline. A real answer is a
  per-region bake of GTFS stop times: for every stop, each route and headsign with its departure
  minutes per service day, compacted (a run is the same pattern most days, so store patterns once
  and reference them). Rough size: raw `stop_times.txt` for a big state's agencies is gigabytes,
  the compacted per-stop form is a few percent of that, so tens of MB for a mid-size state and a
  few hundred for California, on top of the routing and places downloads. The pipeline is the real
  cost: feed discovery (the Mobility Database lists them, but per agency), calendars and
  exceptions, weekly refresh, and no realtime at all, which is what the online board is for. Worth
  it only if people navigate by transit offline; the cached boards are the cheap version.
- **Google-off, per feature.** The master switch shipped 2026-09-21 (Settings > Privacy > "Use
  Vela without Google"). Still wanted: individual toggles under it, in particular "no Google
  routing or live traffic" for people who want Google places but not Google directions; a
  Transitous plan route so transit directions exist with Google off; and free-flow ETAs that say
  they are free-flow.
- **The neural voice's phonemizer is the weak link (2026-09-18, from a drive).** espeak's G2P
  sits in front of the Piper model and reads text that is not prose: "5:49 PM" came out as "five
  foot nine". The workarounds are stacking up (street ordinals spelled out, "I-80" and "CA-99"
  expanded, "take exit 186" rewritten, a comma before " toward ", terminal punctuation on every
  fragment, clock times spelled out in `SpeechText.spokenClock`), and together they say we are
  patching the TEXT because we cannot fix the phonemizer. Options by cost: a better-behaved model
  in the same runtime (Kokoro was too slow in 2026, worth re-measuring on current phones), a model
  whose front end normalizes its own input, or training one. Whatever is chosen keeps the
  constraints: in-process, no network, arm64, fast enough on a Pixel 4a. Until then any new spoken
  string with numbers, units or punctuation gets a `SpeechTextTest` case.
- **Dense-city frame rate (2026-09-16).** Cool, the biggest single cost in Midtown was the Google
  places layer (fixed with a higher GeoJSON maxzoom). What remains one zoom step in is label
  placement (43 fps; 60 with every symbol layer hidden). Next levers: fewer Google labels at
  street zoom, fewer basemap labels. Benchmark with the map's own frame callback
  (`scripts/map-fps.sh`), with cool-downs; the 4a throttles after minutes of scrubbing.
- **More places sources for the open bake.** AllThePlaces and OSM business nodes are in the bake
  (2026-09-15 and 2026-09-18). The long tail with no web presence at all is next:
  chamber-of-commerce member lists and municipal business-license registers where a city
  publishes them as open data, one scraper per source in the AllThePlaces spider shape. The goal
  is the small independent places Overture misses, not another copy of what it has.
- **Region downloads pull the building overlay.** A saved viewport area already pulls the
  Microsoft footprints; a whole-region download does not, so a downloaded state has no houses
  where OSM is thin. Identical bytes, no bake change. (Merging the footprints INTO the basemap
  archive was measured 2026-09-18 and does not pay: only 12% of Microsoft's Delaware footprints
  are in OSM, so a merged archive is double the size and saves nothing; parked behind self-hosted
  tiles, where deleting the render-time coverage gate becomes possible. Numbers in the history.)
- **Docs audit and cleanup (queued 2026-09-15; the roadmap half done 2026-09-21).** README,
  FEATURES, PRIVACY, CLAUDE and docs/ grew by accretion: features described three times in three
  tenses, a FEATURES file that reads as a changelog, contributor notes in CLAUDE.md findable by
  date rather than topic. One pass to state what the app does today in one place, move the
  history into a changelog, and group CLAUDE.md by subsystem.
- **Place-page parity, what is left.** "Mentioned in reviews" topic chips render logged-out on
  the place page, so they are feasible from the reviews scrape. A menu LINK button is parked:
  the menu URL appears in Google's response inconsistently and its path will not pin; the photo
  gallery's Menu tab covers the need. Similar-places only rides focused searches; showing it on
  address-snap and list-tap opens means a focused name lookup on open.
- **Nav polish, small items.** Highlight the CONTINUING lanes for a compound maneuver (OSRM gives
  no cross-step lane linkage, so it needs a careful heuristic rather than a guess); per-state
  and per-province shield SHAPES from the OpenStreetMap Americana set (today a spade for
  interstates, a plain badge for state routes); a "download this region to use avoid offline"
  nudge when a toggle is on with no covering region; parking follow-ups (offer to save the spot
  automatically when a drive ends, distance and age on the chip, a note or photo); a share-TO-Vela
  intent so a Google Maps list link never needs copying.
- **On-street bike lanes.** Dedicated cycleways render in Google's teal; painted lanes
  (`cycleway=lane` on a road way) are not in the keyless OMT tile schema and would need a baked
  layer beside the road features (never per-viewport Overpass, see issue #304).
- **Street View polish.** Walking can step to a different-year neighbor (the neighbor graph
  carries no per-pano date, Google stays in-epoch); higher-zoom tiles on pinch-in; coverage-gate
  the pill.
- **Map label font trickle-down.** Map text renders from the self-hosted Roboto glyph pack, which
  matches the app font today; true inheritance means regenerating that pack from the font file
  the app ships (`scripts/build-map-fonts.sh`) and republishing `map-fonts`. Runtime inheritance
  is not possible in MapLibre.
- **Restaurant menu reliability.** Classify tab-less gallery walks, stop caching a tab-less result
  forever, and separate device render timing from Google-side variance.
- **D-pad hardware pass.** A real keypad-phone session to tune the pan step, the OK-hold
  threshold, focus-ring visibility and traversal order; pixel-verify the full-screen reviews
  page's page-scroll on an unfiltered network; consider an on-screen key-hint pill while the map
  target is focused.
- **Explore (nearby things to do).** A Google-Explore-style surface: nearby restaurants, things to
  do, events as cards on a bottom sheet from the bare map. The category search already returns
  what "Nearby" needs; events have no keyless source. Plan, not now.
- **Voice library next bets.** Host the catalog (`PiperCatalog`) on the signed `calibration.json`
  so new voices ship without an APK (which also needs the download host pinned in the allowlist
  so a compromised bundle cannot redirect a voice download); a preview-before-switching play
  button; a shared `espeak-ng-data` dir across voices (~10 MB per voice). Bigger dictation model
  tiers in the same catalog.
- **Japanese offline voice.** Piper has no Japanese phonemizer, so Japanese guidance rides the
  phone's system TTS. A fully offline voice means Kokoro int8 multi-lang (~126 MB, also Chinese),
  which needs the multi-file sherpa plumbing restored and an on-device speed re-check first.
- **Stability leftover.** The Start-then-launcher quirk: nav keeps running in the foreground
  service but the activity backgrounds.
- **Performance pass.** Frame profiling of dense-marker pans and the place sheet in/out churn.

## On the radar

- **Android Auto on factory head units (pinned #179).** Android Auto lists only navigation apps
  installed from Google Play; a sideload appears only with the "Unknown sources" switch or an
  installer spoof, and any in-app update undoes both. The decision (2026-09-13) is the Play split
  below. Aftermarket units with their own receiver already list sideloaded Vela after King
  Installer plus an ADB install.
- **Both-mode twins across scripts (2026-09-22).** The tap resolve now searches a label in its
  own script language when the English answer does not name it, but the Both-mode twin pass
  still compares Google's English-localized ambient names with the archive's local ones, so over
  Tokyo on an English phone about half the open icons draw beside their Google twin (38% link
  under `hl=en`, 65% under `hl=ja` through the same rule). Options: run the ambient fan-out in
  the region's language when the phone's differs and keep the English copy for the sheet, or
  bake a romanized name into the archive where Overture carries none (it has no alternate names
  in Japan). Needs a device in the region; not chased blind.
- **Cronet at Chrome's version (2026-09-23).** Google traffic rides Cronet 143 from Maven; Chrome is at
  154 and the handshake differs by three signature algorithms. `cronet-build.yml` builds Cronet from
  source at the Android stable tag (untested on a runner; runs once the file is on main). Also open:
  turn `webProxy` on by default once it has run on real sessions for a while.
- **One APK per chip type: flip the switch (2026-09-23).** Built and off: the updater picks the
  APK for the phone's chip type (`update/ApkChoice`), the versionCode is `(2000+run)*10 + chip
  digit`, CI and the F-Droid workflow handle per-chip releases (SPEC 15). What is left: once a
  build with ApkChoice has been the stable for about three weeks, set the repository variable
  `ABI_SPLITS` to `true`; then point README's install button at
  `releases/latest/download/vela-maps-arm64.apk` and open each release's notes with "Most phones:
  vela-maps-arm64.apk. Old 32-bit and keypad phones: armv7." An ARM phone then downloads 74 MB
  instead of 108, and x86 gets Cronet.
- **Review feed paging and the Menu tab without a page (2026-09-23).** Confirm the review feed's
  next-page token (assumed at payload[1]) from a reply on a phone that is not in the limited view;
  find whether `hspqX` can filter by gallery category, so the Menu tab needs no page walk either.
- **iOS (2026-09-13, not started).** `:core` is plain Kotlin and would move to Kotlin
  Multiplatform with the Android-only bits (SQLite stores, WebView bridges, LocationManager)
  behind expect/actual seams; MapLibre has an iOS SDK, sherpa-onnx ships iOS builds, the hidden
  page scrapes map onto WKWebView. The Compose UI would be rewritten and CarPlay is its own
  approval. A second app's worth of work; listed so nobody thinks it is off the table.

## Big bets

### Serving our own map tiles  *(only if the project gets bigger and is ready to run infrastructure)*

**Not now, and not a code problem.** Vela already bakes the whole world's basemap: 414 PMTiles
archives, about 88 GB, on the `basemap-tiles` release, and the app already renders from them
whenever a downloaded region covers the view, online or off. What it has never done is STREAM them.
Online, with nothing downloaded, the basemap is OpenFreeMap's.

Streaming ours is mechanically almost free, since the app already reads two other datasets from
that same release by HTTP range request. Two things stop it being a good idea today:

- **Seams.** Our archives are per region and OpenFreeMap is one planet, so panning across a
  boundary would swap sources mid-gesture, which is the failure class of issue #552. The fix is one
  planet-sized archive, and a GitHub release asset caps at 2 GB against a planet of roughly 90.
- **Release hosting is not a CDN.** An occasional overlay range-read is one thing; a map session
  pulls hundreds of tiles. That is a different order of traffic on hosting never meant for it.

So the missing piece is hosting (PMTiles behind a CDN; object storage plus a small worker is the
standard path, and the format was designed for it), which means a bill, uptime and somebody
carrying it. That is the trigger: **do this when the project is big enough to want its own
infrastructure and ready to run it**, not before.

What it would unlock, and why it is worth writing down now:

- The **Microsoft building merge** stops being an offline-only win. Today it cannot help streaming
  users because online the basemap belongs to someone else, and `runOvlGate` can only be skipped,
  not deleted. With our own tiles everywhere, the merge reaches everyone and the gate dies.
- One schema everywhere: the map a downloaded region draws and the map a streaming user draws stop
  being two slightly different things.
- Independence from OpenFreeMap's donated bandwidth, which is the same courtesy already extended to
  FOSSGIS, Nominatim and Overpass.

Until then the honest position is that OpenFreeMap serves the online map, downloading a region is
how you get ours, and the buildings question stays parked behind this one.

### Contributing back to OpenStreetMap  *(wanted; the hard part is a firewall, not an API)*

Vela takes a great deal from OSM - the basemap, the routing graph, the addresses, the road
features, half the places bake - and gives nothing back. Fixing that is worth doing, and the order
it has to be done in is the opposite of what it looks like.

**The blocker is not the API.** Notes are a plain POST and need no account; editing is OAuth 2.0
with PKCE (a public client, no secret to hide) against the 0.6 API, and the whole write path is a
changeset open, a small diff, a close. That is a week of work. What takes longer is earning the
right to send it.

**The firewall comes first, and it is the thing to bring to the community.** OSM forbids data
derived from Google, and Vela is an app that shows Google's places beside OSM's. So an editor here
cannot work the way an editor in any other app works: **no OSM edit may ever be pre-filled,
suggested or autocompleted from anything that came from Google.** That means the code, not the
wording of a warning. Concretely: the edit path can only read fields whose provenance is the OSM
tile, the Overture/AllThePlaces bake, or what the user typed; a Place that carries a Google feature
id can open a NOTE ("there is a shop here that OSM is missing") but never a tag edit; and the two
paths cannot share a model object, because the moment they do somebody will pass the wrong one.
Getting that wrong once would be a data incident for OSM and the end of Vela's standing with them.

**Build it the way StreetComplete did.** Bounded questions with unambiguous answers, asked about
something the user is standing in front of, never a free-form tag editor. That is what got
StreetComplete community trust, and it is the difference between useful contributions and a flood
of drive-by edits from people who have never seen a changeset. A first set, in order of how safe
they are: a NOTE anywhere (no account, no tags, a human triages it); "is this still here" on a
place the bake shows and the user is standing at; opening hours, phone and website on a place with
NO Google listing open; a missing house number. Nothing that moves geometry.

**The etiquette, all of which the community will ask about:** a real `created_by=Vela <version>`
on every changeset, `source=survey` only when it genuinely was, the app's own OAuth client rather
than a shared one, testing against the dev API (`master.apis.dev.openstreetmap.org`) and never the
live one, a visible changeset comment the user can edit, and an obvious way to see and undo what
you sent.

**Who to ask.** The OpenStreetMap Foundation is the legal and infrastructure body; it does not
approve features, so there is no permission to collect from it. The conversations that matter are
on the community forum (an editor announcing itself before it ships is normal and welcome), and
with the Data Working Group specifically about the Google question - they are the ones who would
act if it went wrong, so they are the ones worth telling first, in writing, before a line of the
editor exists. Expect the first question to be exactly the firewall above.

**Write to the DWG before any of it is built.** That is the gate, not a courtesy: the firewall
above is a design constraint that has to be agreed before there is code to argue about, and
turning up with a finished editor and a question about Google derivation is the wrong order.
Nothing in this entry starts until that conversation has happened.

**Not scheduled.** Notes alone would be a real contribution and could ship on their own.

**Same conversation, second shape (queued 2026-09-15): one-tap fixes.** Vela already links an open
place to the same business elsewhere and can tell when a listing is closed, moved, renamed or
missing. A Vela-side service could take a user's tap ("this place is gone", "wrong hours"), verify
the claim against the business's own website, never against Google, and file it as a note or a
reviewed edit under the OSM import and automated-edit guidelines. Same firewall, same gate: the
DWG conversation first. Until then in-app fixes stay local (the closed-listing hide list).

### A Google Play listing  *(prep work - the split has to be real, not a disguise)*

The reason to want one is **Android Auto**. What the gate actually checks was read off a car log
on 2026-09-22 (GrapheneOS Pixel 9, sandboxed Play, "Unknown sources" on, install fields spoofed to
Play by KingInstaller): on connect the Android Auto app asks the Play Store who owns each app,
Play answers `app owners empty` for anything it did not install, and the validator denies the
package "failed all other checks". (The Desktop Head Unit is no test of this: on 2026-09-22 it
listed and ran a plain sideloaded Vela on an account-less 4a, and the log shows Play was never
asked about the package at all. The head unit skips the ownership gate; the experiment below
has to happen in the real car.) So it is Play's own install record, not the installer fields;
no spoof, no stub package, no copied installer and no patched Gearhead (re-signing it breaks its
signature-gated bindings to Play services, which is the instant crash) can pass it, and the
developer toggle does not cover a navigation app. A Play listing ends that. It would also reach
people who will never install an APK by hand.

Two ideas that came up the same day and do not work: a Vela BACKEND that does the Google
fetching so the app on Play is "clean" (it centralizes every user's Google traffic on one address,
which is the one thing the per-user design exists to avoid, it is trivially blocked, and it puts
the publishing account under Google's enforcement for server-side scraping instead of on-device
scraping, which is not better); and installing the Play edition and then sideloading the full
build over it to inherit Play's record (Play App Signing re-signs, so the two cannot replace each
other, as the signing note below already says).

**The other route worth a test is a dongle.** Not the wireless Android Auto adapters, which still
run the phone's Android Auto app and hit the same gate, but the Android "AI box" class
(Carlinkit, Ottocast and the like): a small Android device that plugs into the car's USB, presents
itself to the head unit over the CarPlay/Android Auto channel, and shows its own screen there.
Vela installs on the box like on a phone, with no Google gate in the loop; the box gets location
from the car or its own receiver and data from a SIM or the phone's hotspot. It is the
"head unit that runs Android" answer in a form that fits an existing car, and it is untested here:
one box, one drive, and it is either a documented path or a dead one.

**The shape that works is a compile-time flavor, not a switch.** A `play` flavor where the Google
extractor is NOT IN THE APK: no scrape, no hidden path, nothing to turn on. What is left is a
complete OpenStreetMap maps app, because most of Vela already is one:

- routing and turn by turn from the on-device obf graph (OSRM online as it is today)
- places from the Overture/OSM bake, offline packs, the address geocoder, speed limits from obf
- the basemap from OpenFreeMap or a downloaded region
- transit from Transitous, road features, cameras

What it loses is the Google half: place pages (reviews, photos, hours), the traffic layer, traffic
ETAs, and Google as the directions fallback. That is a real product difference and the listing has
to describe the app it ships, not the other one.

**What will NOT work, and is worth being blunt about:** shipping a boring app and restoring the
Google half afterwards. Downloading executable code outside Play breaks the Device and Network
Abuse policy, and an app that behaves differently from what review saw breaks Deceptive Behavior.
Both are enforced at the ACCOUNT level, not the app level, and a suspended developer account is not
appealable in any way worth planning around. A remote flag that quietly enables scraping is the
textbook example. The other half of that risk is specific to us: the Google half is built on
Google's own service, and a Play listing puts the account that publishes it directly under Google's
enforcement, which is a different exposure than GitHub or F-Droid.

So the honest split is two distributions: the full app stays on GitHub, Obtainium and F-Droid; the
Play build is the OSM app, honestly described, with a link to the project site for people who want
the other one. A link is fine; an in-app downloader of an APK is not.

**Work it implies, roughly in order:**

- a flavor dimension, with the Google extractor, the WebView scrape and the place-page surfaces
  compiled out, and the search/place paths falling back to what the offline stack already does
- `REQUEST_INSTALL_PACKAGES` and the in-app updater gone from that flavor (Play forbids an app that
  updates itself), which also means the What's new dialog and the update checker are flavor-aware
- the Data Safety form, a privacy policy URL, content rating, and the background location
  declaration with the demo video Play asks for
- package id and signing: Play App Signing re-signs, so a Play install and a GitHub install cannot
  replace each other. Either accept that moving between them needs an uninstall, or publish the Play
  build under its own id and accept two apps on one phone. Decide before the first upload, because
  the id cannot change afterwards.
- listing copy and screenshots that never imply a Google affiliation

**The one door nobody has opened: a phone-side Android Auto sender.** Every open implementation of
the protocol (aasdk, openauto, the Rust `android-auto` crate) is the HEAD UNIT side - they pretend
to be a car so a phone will project to them. The other direction, an app that speaks the protocol
straight to a real head unit in place of Google's, barely exists, and it is the only approach where
Google's allowlist is not in the loop at all: with no gearhead in the conversation there is nothing
to consult a list. Seb3thehacker reported getting text, buttons and then a WebView onto a car screen
this way (issue #179).

What decides whether that is big or a curiosity is ONE question: what happened in the TLS handshake.
If the unit accepted a certificate we can generate, this is clean-room protocol work and shippable.
If it took a certificate extracted from Google's app, it is the `aauto.aar` problem again - the
thing that rules Fermata out - and no amount of good engineering fixes it. Everything else (per-unit
compatibility, wired before wireless, H.264 encode, claiming USB accessory mode) is ordinary work
that only matters after that answer.

It would not live here either way. A sender is its own project, the size of openauto, and Vela's
job would be to feed it frames - which is nearly free, because `CarMapRenderer` already renders the
map to a bitmap for a car surface. One nice alignment: it wants gearhead out of the way to claim
the accessory, and Vela's users are the people who do not have gearhead.

**That project has a name now: Gearslip** (Seb3thehacker, repo pending). Moving it out of this repo
does not move the certificate problem: a sender that needs key material extracted from Google's app
or from head unit firmware cannot be distributed from anywhere - F-Droid would refuse it, and
redistributing somebody's private key is a different order of risk from a license violation. Where
the key was FOUND changes nothing about whose it is. So the cert question decides whether Gearslip
can exist at all, not merely whether Vela can talk to it.

**What is worth building here regardless is the SEAM, not the integration.** A projection client of
any kind needs three things from Vela: frames at a size it names, input events going back, and the
nav state for the cluster. Vela already has all three internally (`CarMapRenderer` renders to a
bitmap, the car screens take input, `NavSession` publishes the state `ManeuverMapper` reads). A
small bound service exposing them would let a companion render Vela on a car screen without forking
it - and the same surface serves Google's Desktop Head Unit, an OpenAuto-style receiver on an
aftermarket unit, an AAOS companion, and anyone else's experiment. It is useful before Gearslip
works and it stays useful if Gearslip never does, which is the test for building it now.

**Which SEAM depends on what Gearslip can consume, and that is not known yet.** Android Auto is a
VIDEO protocol, not a web one: the phone renders frames, H.264 encodes them and ships them to the
unit, which decodes. There is no browser on the other end, so "serve the UI over HTTP" adds a
renderer rather than reusing one. Two shapes, and the tradeoff is real either way:

- **Frames.** Vela hands out the bitmaps `CarMapRenderer` already produces. Reuses the tuned native
  renderer, no second map engine, no compositing step. Needs Gearslip to accept an external frame
  source.
- **A URL.** Vela serves a car page and the client renders it in a WebView. Consumable by anything
  that can show a URL, and Seb's own demo got a WebView onto a car screen, so Gearslip may simply
  BE this shape. The cost is MapLibre GL JS instead of the native renderer plus a compositing pass,
  which makes the map-render term worse. It is not the dominant term (on wireless the radio and the
  encoder are) but it is the one term we would be choosing to inflate.

**The half that does NOT depend on the answer is most of the work:** nav state out (what
`ManeuverMapper` already reads from `NavSession`) and input back. Both transports need those, in
the same shape. So the decision can wait for Gearslip to have a transport, and nothing is blocked
by waiting. Frame pacing is worth saying once: a car screen has no use for 60 fps of map, and 30
halves the encode.

**Not scheduled.** The prep is the flavor split, which is useful on its own: it proves how much of
Vela stands up with no Google at all, which is the direction the project has been walking anyway.

### Opt-in telemetry  *(planned - deliberate, careful)*

The local halves shipped in June 2026: developer diagnostics (a local breadcrumb log the user
exports by hand) and trip recording with replay and the offline nav auditor, both opt-in, both
with no backend and no upload. What is left is the long game:

**Vela's own traffic data.** Crowd-source anonymized speed and route traces from opted-in users
to build a Vela traffic layer, blended with Google's and eventually replacing it where coverage
is good, the first real step off Google. The trip recorder is the on-device half of the capture
this would need; an optional one-tap upload sink for diagnostics would ride the same backend.

**This is a departure from today's "no telemetry, no backend" stance**, so it must be done so it
*earns* trust rather than spends it:
- **Opt-in only**, clear consent screen, easy off plus "delete my data", never on by default.
- **Minimize and anonymize**: no account, a pseudonymous device token at most; trim the precise
  start and end (snap to road, drop the first and last ~100 m like other traffic apps); send
  speed and heading along road segments, never "user X went from home to work".
- Needs **the first Vela backend** (or a privacy-preserving collector); pick something
  self-hostable. This becomes a thing to run, secure and subpoena-proof, the opposite of the
  current no-server design, so weigh it.
- **Update [`PRIVACY.md`](PRIVACY.md) in the same change**: it currently, truthfully, says "no
  telemetry"; that line changes the day this ships.
- Could ride the existing **signed channel** for config (endpoint, sample rate, kill switch).

### Vela traffic layer

Depends on the telemetry above. Aggregate opted-in traces → per-segment speed vs.
free-flow → a traffic overlay + traffic-aware ETAs that don't need Google. Start as a
*supplement* to Google's `/maps/vt` tiles, grow as coverage allows.

## Investigated, parked or dead

One line each, so nobody re-chases them; the full probes are in the history file.

- **Owner posts ("closed for renovation until...")**: a keyless endpoint exists
  (`/maps/preview/localposts`) but its `pb` grammar needs a live capture from a business that
  actually has posts; none found. Capture one when met, then it is the standard calibration
  pipeline. Formal temporary-closure status already shows.
- **Predictive per-departure ETA**: six attempts, dead keyless; the web client sends no time
  field and its "Leave now" control cannot be driven. The only unblock is one mitmproxy capture
  of the Android Google Maps app with Depart-at set. The typical best-to-worst window shipped
  instead.
- **Live traffic incidents**: Google renders them from proprietary binary `vt` tiles (a
  reverse-engineering project that breaks on every reshape), Waze's feed is reCAPTCHA-gated
  (probed four ways 2026-08-08, all 403). Only open DOT/511 feeds remain: fragmented, token-shaped,
  one region at a time. Congestion coloring covers "where is it slow".
- **EV charger detail (price, kW, availability)**: stripped from every keyless response; only the
  type marker arrives. OpenChargeMap would be the open source for it.
- **Q&A, photo dates, photo contributor names, per-review photos via the RPC, menu photo
  dates**: each proven login-gated or bot-gated; the reviews and photos the app shows come from
  the WebView DOM walk. Do not re-probe.
- **Gallery videos**: rare in the data and would need a gated source plus a player dependency.
- **Clean always-snap (Google picks the road, an on-device engine names the turns)**: the
  serverless dense-via version loses ~1 in 10 named turns, the public matchers cap at 10 points.
  It was going to be GraphHopper's map-matcher; GraphHopper was retired 2026-09-15 and the obf
  engine has no matcher, so this is open again on that side. Option 3 (snap only on real traffic
  divergence) stays the online path.
- **Merge Microsoft footprints into the basemap bake**: measured 2026-09-18, doubles the archive
  and saves no bytes; parked behind self-hosted tiles.
- **Parcels**: per-county scraping with a backend and mixed licensing; out of scope.

## Resilience (built - extend as needed)

The signed `calibration.json` channel can already hot-push **config, field paths,
user notices, and sandboxed JS parse-logic** with no app update (see SPEC section 11). Future
breakages should be fixed there first.

## Not going to happen (accounts and backends)

- **A shared Google-to-open POI correlation log (asked 2026-09-18, declined).** Clients would
  contribute the links they resolve (this Overture or OSM id is that Google listing) to a shared
  store, so a tap on a place nobody on this phone has tapped could skip the lookup. What it buys is
  ONE saved request on a first tap: the link alone carries no rating, hours, reviews or photos, so
  the place still has to be fetched, and repeat taps are already free from the on-disk link cache.
  What it costs is a backend to receive, store, moderate and serve it, forever, plus a contribution
  channel that reveals which places a user tapped and when. The rows themselves are impersonal
  (place to place), and batching, delay and dropping rare pairs would blunt the rest, but adding
  behavioral telemetry to save one request is the wrong side of the trade the project exists to
  make. Revisit if first-tap linking ever fails at a rate a cache cannot fix; the thing that looked
  like that (2026-09-18, POIs "not linking") was a 120 m bug in our own ranking.

These stay off the table because they require a Google login or a Vela server, and the
project's core promise is that neither exists:

- Contributing reviews, photos, or map edits (needs a Google account)
- Live location sharing / share-ETA (needs a rendezvous backend)
- Location history / timeline (an anti-goal outright)
- Live "busier than usual" popular times (Google strips the live histogram from every
  anonymous request; the typical-week bars we show are the keyless maximum - probed and
  documented, do not re-chase)

---

## Architecture work

Carried over from the architecture review; the finished items (route provenance as one field,
the shared hidden-WebView base, `NavController`) are in FEATURES.

- **Finish carving the three large files.** `NavCamera` in `VelaMapView` (the follow ticker, the
  puck overlay, the padding and zoom eases as one class with one `frame()` entry point) and
  `SearchController` (query, suggestions, results, the three pickers and their gates as one
  tested state machine). The camera piece needs a real drive to judge.
- **Rules in prose become rules in code.** A SPEC paragraph describing a trap should come with a
  unit test, a lint rule, or a type that makes the wrong state unrepresentable. Convert
  opportunistically when touching one; the spec keeps the why, the test keeps the what.
- **Infrastructure with an owner.** Turn-by-turn depends on the FOSSGIS community servers with no
  agreement and no fallback except the on-device engine. One small self-hosted OSRM instance for
  the main regions, used first with FOSSGIS as the fallback, removes the single failure that
  takes routing from every user at once. The nav diagnostics record which router answered, so the
  decision can be made from real drives.
