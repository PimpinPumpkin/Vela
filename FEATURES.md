# Vela — feature list

Status legend: ✅ done · 🟡 partial / in progress · ⬜ planned

## Map & rendering
- ✅ MapLibre Native vector rendering (Compose-wrapped)
- ✅ Detailed open basemap: bundled OpenFreeMap Liberty + injected house numbers at z17; OpenMapTiles vector source pinned to OpenFreeMap's **versioned** tile path (the un-versioned path serves empty tiles — that was a blank-map bug)
- ✅ Route line, **tappable Google-style search-result pins**, location dot as GeoJSON layers
- ✅ Heading-up, tilted navigation camera; fit-route-to-screen on preview; recenter FAB
- ✅ Compass kept clear of the status bar (inset-aware margins)
- ✅ Tap a labelled POI **or a search-result pin** to open it; camera frames all results after a search. Tapping a POI also reads `name:latin`/`name:en` (not just `name`), and an **unnamed** POI icon (an apartment gym, an unnamed park/playground) **reverse-geocodes to a pin + address** instead of being a dead tap. When several Google listings share the same spot (e.g. a co-branded "SpeeDee Midas" with a sparse **Midas** *and* a rich **SpeeDee** profile), the tap now opens the **most-reviewed = canonical** one rather than whichever happens to be a few feet nearer
- ✅ Bottom sheets (place sheet, steps) **fill to the screen edge** — content is padded off the gesture/nav bar, but the sheet background no longer stops short and lets the map peek through at the very bottom
- ✅ **Long-press the map** → drop a pin, reverse-geocode it to an address (Nominatim/OSM, keyless), then get Directions — works even where no building is drawn
- ✅ Keyless **OpenFreeMap Liberty** basemap (active, loaded by URL — the setup that renders on-device, no key): **Google-style POI markers + category-coloured labels**, a **clean Google-style road treatment** — white road fills on light-grey land with the **casings faded out** (minor-road casing == the land, so streets are crisp white lines with **no outline**), soft-yellow motorways, **neutralised landuse** (no tan residential/commercial blobs), and **flattened fill-patterns** (Liberty's fern-hatch wetlands + dotted pedestrian plazas → flat fills, like Google) — plus light/dark recolour, all at **runtime** (tuned live in a MapLibre GL JS harness against Google, on-device-verified light + dark)
- 🟡 **MapTiler Streets** path stays wired but off (`USE_MAPTILER=true` to enable, needs the key). The bundled-style **Roboto font** is parked — its vector tiles wouldn't load via `fromJson` on-device (loading Liberty by URL is what works)
- ✅ **First-run welcome** — a clean branded intro (no tracking / real places & routes / free & open source) with a single "Get started"; shown once
- ✅ **Tasteful donation** — a permanent "Support Vela" entry in Settings, plus a **one-time** prompt that appears only **after a week** of use ("entirely optional, and this is the only time it'll ask"), trivially dismissed, never blocking. (`Onboarding` holder; set `DONATE_URL` to your own Liberapay/Ko-fi/Sponsors page)
- ✅ **In-app Light / Dark / Follow-system switch** (Settings → Appearance) — sets Vela's theme **independently of the phone**, so you can run the app dark without flipping the whole OS (the whole app + the map recolour live off one preference, `AppTheme`/`isAppInDarkTheme`; persisted). Dark mode recolours **every** landuse/landcover fill (commercial, school, retail, …), not just a hardcoded few, so no light/cream patches break the night palette (verified on-device)
- ✅ **Google-style POI markers** — category-coloured circles with white Material Icons glyphs (food=orange, shop=blue, park=green, health=red, transit=blue, …), generated at runtime over a bundled Material Icons font, sized to read like Google's (`iconSize` 0.8); in light mode the POI **label text is coloured by category too** (like Google). **Density tuned for parity:** Liberty's always-on `poi_transit` layer (bus stops at every zoom = clutter) is pushed to **z16+** like Google, while the next business tier (`poi_r7`) is pulled down to **z15** so more shops/restaurants show; MapLibre's label collision keeps it tidy. **Nameless POIs are filtered out** (`has("name")` AND-ed onto each poi layer's rank filter) — the unnamed icons couldn't be opened anyway (they'd just drop a near-by address pin) and read as duplicate junk, so only labelled, tappable POIs render now
- ✅ **Buildings** (footprints + 3-D massing) — OSM building data already in the Liberty tiles (`building` 2-D fill + `building-3d` extrusion at high zoom), now given enough contrast + a subtle outline to actually read (was coloured almost identical to the land). **Keyless, no API key, no new data/infra.** Microsoft US Building Footprints / Overture (both free + ODbL) are the open fallbacks for coverage gaps, but they're bulk files that'd need self-tiling/hosting — only for gaps
- ✅ **Terrain relief (hillshade)** — Google-style shaded relief from the **keyless
  open terrarium DEM** (AWS Open Data, no key, native fetch so no CORS), added at
  runtime under the road layers (so roads + labels stay crisp) and capped at z16
  (terrain context for the overview, gone at street level). Tuned per theme — a
  soft warm-grey shadow in light, deeper shadows + a cool highlight in dark.
  Verified in a MapLibre GL JS harness (same render engine as MapLibre Native)
  against the real DEM tiles before shipping
- 🟡 Self-hosted PMTiles — the no-key, no-quota Google-look path — remains for later
- ⬜ Protomaps "Google-Maps-ify" style (road hierarchy ✅, hillshade ✅, POI icons ✅ done; this is the bundled-style variant)
- ⬜ Satellite layer (terrain relief ✅ done; aerial imagery still planned)
- ⬜ Map rotation/tilt + heading-up mode during nav

## Search & POIs (live Google data)
- ✅ Place search — name, category, **full address (street, city, state, ZIP)**, rating, review count, coordinates
- ✅ Searching a **specific/far address** resolves to that single geocoded location (handles the response's single-result shape, not just the POI list — fixes the old "calibration error" on far addresses); genuinely-empty searches now show "no results" instead of an error
- ✅ **Address → business snap** — searching a raw address that *is* a business (e.g. "1020 Olive Dr, Davis") now lands on the **business** (In-N-Out Burger, rating/hours/category and all), not the bare address — Google lists the "at this place" business under the geocoded node (`[0][1][0][14][68]`) and Vela now reads it. *(verified on-device; unit-tested; the path is in calibration so it's remotely fixable)*
- ✅ Search-result rows show **5-star rating**, colour-coded open/closed status, and the **full address (city/state/ZIP)** to disambiguate similar names / lookalike residential addresses — **sized for legibility** (name at titleMedium, the rating/category/address lines bumped up from the cramped small text)
- ✅ Place sheet (**Google-styled**): high-contrast white-on-dark / black-on-white name + status time (fixed palette, not washed-out by Material You), **5-star rating visual**, **swipe-down to dismiss from anywhere on the sheet** (a nested-scroll handler: at the top of the body a downward drag collapses then dismisses; mid-list it scrolls), status with the **word colour-coded** (Open green / Closed red) and the time in plain ink, **distance from your location** (when opened from a located search) + price + category, **full address with a copy button**, **collapsible weekly hours** (today first, expand for the week)
- ✅ Full-screen photo viewer: **pinch-to-zoom** (+ pan when zoomed) and **swipe-down-to-dismiss**, swipe sideways between photos
- ✅ **System maps handler** — Vela registers for `geo:` URIs and Google-Maps web links (`/maps/place`, `/search`, `?q=`, `@lat,lng`, `maps.app.goo.gl`), so tapping an address or "open in maps" in any other app offers Vela. A query runs a search (biased to any coords in the link), a bare point drops a reverse-geocoded pin — the degoogled-replacement piece. (`MapLinkParser` in `:core`, unit-tested; parsed in `MainActivity`.)
- ✅ Viewport-biased "near me" search
- ✅ Recent searches (persisted) + **recently-viewed places** — opening a place records it; the search page shows a **Recent** section (pin icon, one tap to reopen — enriched via search) above **Recent searches** (clock icon). Capped at 8, deduped, cleared together *(verified on-device)*
- ✅ **Full-screen search page** (Google-style) — focusing the search box opens an opaque page with **Home/Work shortcuts**, saved + recent searches over the map (back arrow / back gesture closes it); running a search drops back to the map with the results list + red pins
- ✅ **Home / Work shortcuts** (Google's signature) — two pinned rows at the top of the search page. Unset shows "Set home/work address"; tapping arms an assign mode (a "Search for your home address" banner + Cancel) and the **next place you pick** — a suggestion, a saved place, or a tapped POI — gets pinned. A set shortcut shows the place name and a **⋮ menu (Change / Remove)**; tapping the row opens the place. You can also set the **currently-open place** as Home/Work straight from its **place-sheet ⋮ overflow**, or promote a Saved place via its ⋮. Persisted in `PlaceShortcutStore` (`vela_shortcuts` prefs), so they survive restarts *(verified on-device: set Home → "Sacramento Valley Station", ⋮ → Change/Remove)*
- ✅ **Autocomplete / suggestions as you type** — after a short debounce, the search page shows live **place matches** (name + address) to tap, like Google; tapping one opens its sheet directly. Reuses the calibrated search endpoint (no separate suggest RPC); a stale response is dropped if the query moved on. *(verified on-device: "starb" → Starbucks locations)*
- ✅ Clear-search (X) button; the **results list is a top-sheet** under the search bar (it hangs from the top, so the gestures follow that): **swipe DOWN to grow** it (~half screen → ~94% full), **swipe UP to retract** it — first shrinking an expanded list, then hiding it to the "N results" pill so you can browse the map (pins stay, tapping the pill re-opens it); the chevron + back gesture also collapse it. A nested-scroll handler lets a down-overscroll at the top of the list expand it ("pull to see more")
- ✅ **Result filters** — chips in the results header: **"Open now"** (places open right now) and **"4.0★"** (rating ≥ 4.0); they stack and the count updates live
- ✅ **Back gesture peels one layer at a time** (steps → navigation → route preview → place sheet → results list) instead of closing the app — only the bare map exits
- ✅ **Full reviews** — the place sheet's **Reviews tab** lists real reviews (author + photo, star rating, relative date, text) pulled from Google's keyless `listentitiesreviews` endpoint by feature id
- ✅ **Tabbed place sheet** (Google-style): **Reviews** (rating summary + featured highlight + full list) and **About** (Service options, Highlights, Accessibility, … from Google's attributes). Layout order: **photos (hero, at the top) → info → hours → action row → tabs** (photos lead so they're visible at the peek height / in landscape)
- ✅ **"Also at this location"** — when other Google listings sit at the same spot (a co-branded shop's duplicate profile, a different unit at the address), the place sheet lists them with rating + category, tap to open — like Google's co-located-businesses section. Drawn for free from search results already in hand (no extra request). Matches on the **same street line** (e.g. "239 G St", suite-insensitive), not raw proximity, so a shop across the street isn't wrongly listed
- ✅ **Directions panel** (Google-style popup, not buried in the place sheet): tapping **Directions** opens a dedicated bottom panel with a **From → To** header (origin dot + destination pin) and a **swap (⇄)** button to **reverse the route** (you ⇄ the place), **Drive / Transit / Walk / Bike** tabs, and a prominent **Start** + **Steps**. For drive/walk/bike it lists the **route options** — each with a **traffic-coloured ETA** (green free-flowing → amber → red), distance, **via-road name** and a **"Fastest"** tag; **tap an alternate to select it** (the map line switches to it). Transit shows the results board instead.
- ✅ **Depart / arrive time** — the directions panel has **Leave now / Depart at / Arrive by** chips with a time picker; it shows the resulting **arrival** (or leave-by) time computed from the route's traffic-aware duration, labelled "current traffic" (future-traffic *prediction* would need a separate calibrated request — this is the current-conditions planning aid)
- ✅ **Search along route** — with a trip planned, the directions panel shows **Gas / Food / Coffee / Groceries** chips; tapping one searches near the route and shows only the results **within ~3 km of the route line, ordered start → destination** (so you find stops actually on the way). The route stays drawn; tap a result to open it
- ✅ **Consistent sheet styling** — the place sheet, directions panel, route chooser, **steps list**, nav bar, **the search-results list, the full-screen search page, and the "set as Home/Work" banner** now all share one Google-grey palette (`ui/SheetPalette`: `#1F1F1F`/`#FFF` surface, fixed ink/dim text, teal accent, green/amber/red traffic) instead of differently-shaded Material-You-tinted cards
- ✅ **Permanently-closed (dead) POIs** — parsed from Google's status (`permanentlyClosed`); they **stay in search results** (labelled "Permanently closed" in red, in both the row and the place sheet — even when Google sends no hours) but are **dropped from the map pins** so they don't clutter the map (a place you explicitly open still gets its pin)
- ✅ **Alternate routes** — Google's 2-3 driving alternates are surfaced + selectable in the directions panel (e.g. "20 min · via I-80 E" / "21 min · via Co Hwy E6"); each draws along **Google's OWN route geometry** (delta-encoded in the response at `[0][7][i]`, decoded directly) so the line matches the via-label exactly, alternates included — **no more lines that double back on themselves or cut straight across** (the old scattered-point guess is gone; an open router is now only a fallback for routes Google omits geometry for)
- ✅ **Alternates drawn greyed on the map + tappable** (Google-style) — the non-selected routes render as **grey lines beneath the active blue one** (theme-aware shade so they read on dark/light tiles); **tap a grey alternate to switch to it** (same as picking it in the panel). The directions camera now also **frames the whole route above the panel** (per-edge bottom padding) instead of centring it behind the card *(rendering + framing verified on-device, Davis→South Lake Tahoe: grey I-80 arc alongside the blue US-50 route)*
- ✅ Place sheet **peeks** (~56% screen) so the business info isn't immediately full-screen and the map stays visible above it; **drag the handle up to expand** (~92%, for the reviews), down to shrink, down again to dismiss. The body scrolls, so a tall place (hours + tabs) is fully reachable at either height
- ✅ **Pin stays visible above the sheet** — opening a place pushes the map's optical centre up by the sheet height (MapLibre bottom padding) and zooms in, so the pin sits in the visible strip above the card instead of being hidden behind it (Google-style)
- ⬜ Popular/busy times — **built but dormant; confirmed session-gated 2026-06-18.** Histogram is place node `[84]`, but Google strips it from the keyless search response (only full/logged-in sessions get it) and, unlike photos/transit, it's not served to the anonymous WebView either (place panel never renders there; `/maps/preview/place` is token-gated). The model/parser/UI (`PopularTimesSection`) auto-activate if `[84]` ever shows up keyless; the only real unblock is an opt-in Google login. See ROADMAP "Known-hard" + SPEC Non-goals
- ⬜ "hours updated N ago" (place-RPC-only, absent from the search response); Updates/posts tab
- ℹ️ Reviews are the **top ~20** — the `listentitiesreviews` endpoint serves a fixed page (offset ignored) and deeper paging is behind an obfuscated continuation token; not chased (fragility vs. value)
- ✅ Place actions in a **Google-style quick-action row** (circular icon + label): **Call** (dialer), Website, Save, **Share menu (Google Maps link / Map pin geo: / coordinates / address)** — the actions are **evenly weighted across the full width** so all five fit on one row without the trailing Share icon clipping off the edge. The **geo: pin** is the degoogled-friendly share (`geo:lat,lng?q=lat,lng(Name)` opens in any maps app, incl. Vela — no google.com); round-trips back through Vela's own `MapLinkParser` (unit-tested)
- ✅ **Place photos** — business photo strip **leads the sheet as a hero** (horizontally scrollable, at the top so it's visible at the peek height / in landscape); **tap a photo to open a full-screen, swipeable gallery** with a counter. Opens with the search response's **preview (~10)** at `[1][105][0][1][0][i][6][0]`, then swaps in the **full gallery (~30–40)** — including for places with **no** search preview (e.g. SpeeDee/Midas-style service shops), which used to show nothing.
- ✅ **Full gallery via a hidden WebView** (`WebPhotoFetcher`) — the gallery RPC (`batchexecute` `hspqX` / `/MapsPhotoService.ListEntityPhotos`) serves the user photos **only to a real browser engine**. A plain HTTP client — even with perfect headers + consent cookies — gets a degraded **Street-View-only** reply: bot-detection at the **TLS/fingerprint** level (verified on-device — OkHttp gets a 162 KB token-less "lite" `/maps` page). So Vela runs a **hidden Android WebView** (real Chromium) that loads `maps.google.com` as an **anonymous, no-login** session — exactly like a logged-out browser, which *does* show the photos — and does a same-origin `fetch` to the RPC, handing the raw response back over a JS bridge. **Keyless** (no API key, no account). Created **lazily** (only when a place's photos are wanted), strictly best-effort (failure → keep the preview). **On-device verified 2026-06-17: 31 photos for SpeeDee-Midas, Davis.** Tradeoff: the WebView runs Google's JS (a fingerprinting step for a degoogled app — the opt-in cost of richer photos), OkHttp fallback kept. Gotchas baked in: **desktop UA** (a mobile UA makes Google deep-link to `intent://` the native app), **block non-http(s) redirects**, and **`Handler`, not `View.postDelayed`** (a headless WebView never attaches to a window, so View timers never fire).
- ✅ Category quick-chips (Restaurants/Coffee/Gas/Groceries/Hotels/Pharmacy/ATMs/Parks) → one-tap search, each with a Google-style leading icon
- ✅ "Search this area" — re-search after panning the map
- ✅ Filter: **open now** + **rating ≥ 4.0** (chips in the results header, stackable); ⬜ price still to add
- ✅ Saved / favourite places (star from the place sheet) — reopening a saved place **enriches it via search** so photos, rating and reviews load (saved places carry no feature id of their own); each saved row in the search page has a **⋮ menu** to **Set as Home / Set as Work** (promote it straight to a shortcut) or **Remove** it *(verified on-device)*
- ⬜ **Export / import saved places** (portable, user-savable bookmarks — planned)
- ⬜ Overture/OSM POIs as a fallback source

## Routing & traffic
- ✅ Driving directions with **real traffic-aware ETA** (live `duration_in_traffic`)
- ✅ **Live traffic overlay** — Google's actual congestion-coloured roads + incident markers, as a **keyless raster layer** (the web map's own public `/maps/vt?…!2straffic` PNG tiles on www.google.com — no API key). Toggle with the traffic-light button on the map; off by default. Drawn **above** the route line so the route's blue doesn't hide the traffic colours. So you see true **per-segment** traffic (red on the jam, green where clear) on every road, including your route — what the route-line's whole-route tint can't show on its own
- ✅ **Route-line traffic colour** — the drawn route tints blue → amber → red as the
  live traffic-aware time runs over the typical time (overall congestion; per-segment
  isn't reliably in the response and our line is OSRM geometry, so it's whole-route)
- ✅ Alternative routes returned
- ✅ Turn-by-turn maneuver list (type + distance from Google's step markup)
- ✅ **Lane guidance** — Google's lane hints ("Use the right 2 lanes to turn") are
  pulled out of the step markup into their own field; the nav banner renders them as
  a **strip of turn-direction arrows** (one per indicated lane) + the hint text, and
  the step list shows them highlighted (the main instruction stays clean: "Turn right
  onto …")
- ✅ **Highway/exit signage** — route refs ("I-80 E", "US-50 E") and exit numbers
  ("Exit 4A") are parsed out of each instruction and rendered as Google-style badges:
  a **green exit tab** + a **bordered route shield**, in both the nav banner and the
  step list
- ✅ Route geometry via open router — **per-mode** FOSSGIS OSRM backends
  (`routed-car`/`routed-bike`/`routed-foot`), so drive/walk/bike each follow the
  correct network; Valhalla later
- 🟡 **Live route re-check while navigating** — periodically re-query traffic and
  offer a faster route if one appears (see Navigation below)
- ✅ Walking + cycling modes (drive/walk/bike) — each with its **own** path-following
  line, not a car route reused
- ✅ **Public transit** directions — a **Transit** chip in the directions chooser
  shows a Google-style results board: each option's **departure–arrival window**,
  **total duration**, **distance**, **agency**, and the **coloured line pills** you
  ride (real Google line colours + per-mode glyph: 🚆 train / 🚌 bus / 🚊 tram / …).
  Like photos, transit is served **only to a real browser engine** (a plain GET with
  the `!3e3` flag is silently downgraded to *driving*), so it goes through a hidden
  **WebView** (`app/web/WebDirectionsFetcher`) that loads the `/maps/dir/…/!3e3` page
  and reads the itinerary set out of `APP_INITIALIZATION_STATE` (the longest
  `)]}'` payload at slot [3]); `TransitParser` (keyless) parses it. **Verified
  on-device** Davis→Sacramento (6 options: Amtrak Thruway, Yolobus 42B/43/44, …).
- ✅ Transit **leg drill-down** — tap an itinerary to expand its ordered legs
  ("Walk 7 min → Bus 42B 5:48–6:41 AM (53 min) → Walk 7 min"): each leg shows its
  mode glyph, the ridden line (name + colour) or "Walk", and board/alight times +
  duration/distance. Parsed (unit-tested) from `trip[1]` in the **same** keyless
  fetch — no extra RPC. *(UI built; on-device visual check pending — phone was off
  ADB when it shipped.)*
- ⬜ Transit drill-down **stop names + ridden polyline** — the intermediate stops
  (board/alight stop names) sit in the leg's stop array and the shape in the same
  payload; deferred until the index can be device-verified (pinned from one capture).
- ⬜ Departure/arrival time selection; avoid tolls/highways
- ⬜ Self-hosted routing backend (replace the FOSSGIS community server)

## Navigation
- ✅ Turn-by-turn engine (step advancement, off-route detection, reroute) —
  pure/Android-free, **unit-tested** (arrival-requires-final-maneuver, reroute
  fires once per off-route transition not per fix, off-route clears on return)
- ✅ Spoken guidance via AOSP TextToSpeech (engine-selectable) — **tuned for the
  car**: a measured speech rate (0.97) + neutral pitch, and on init it auto-selects
  the **highest-quality offline voice** for the locale (engines often default to a
  low-quality or download-required one), so guidance sounds natural, not robotic.
  Speaks **"Head east on …"** (the initial cardinal is computed from the route's first
  leg and injected, since Google's markup only says "Head toward …"). **Settings →
  Voice** lists installed engines, a **Test voice** button (hear it on your hardware),
  and a **System voice settings** shortcut to install/download a voice
- 🐞 **Fixed: silent navigation** — on a targetSdk-30+ build, Android package
  visibility hid every TTS engine (`getEngines()` empty, the engine couldn't be
  bound) so guidance was silently dropped. A `<queries>` for `TTS_SERVICE` restores it;
  picking an engine now actually re-inits TTS (it used to be ignored). *(verified
  on-device: audio focus + frames delivered on nav start)*
- ✅ **One-tap voice on a ROM with no TTS** — many degoogled ROMs ship no engine at
  all, so **Settings → Voice → Install eSpeak NG / Install RHVoice** downloads the
  latest open-source engine from **F-Droid** (resolved via its API) and hands it to
  the system installer; once installed it's a normal engine Vela already drives — no
  heavy synth bundled into the app, works on any ROM. A nav-start hint points there if
  you have none. *(verified end-to-end on-device: download → install → eSpeak appears
  as a Vela engine → speaks)*
- ✅ **Mute voice during nav** — a speaker toggle in the nav bottom bar silences /
  restores spoken guidance on the fly (Google-style), independent of the haptic cues
- ✅ **Speedometer** — a Google-style circular badge (bottom-left during nav) shows
  your current GPS speed in mph or km/h (follows the Units setting)
- ✅ **Scale bar** — a Google-style ⊔ bracket (bottom-left, by the attribution) sized
  to a round distance, with the distance label above it; reads the live
  metres-per-pixel from the map (correct for zoom **and** latitude on Mercator) and
  follows the Units metric/imperial preference (m/km ↔ ft/mi). Updates as you zoom/pan
- ✅ **Pan-away + Re-center** — dragging the map during navigation **detaches the
  follow-camera** so you can look around (it stops snapping back on every GPS fix);
  a **Re-center** button appears and reattaches it, then hides once you're following
  again (Google-style)
- ✅ **Haptic turn cues** — a light "get ready" tick at the pre-turn prompt, then a
  firm **direction-coded** buzz at the turn (left = two long pulses, right = three
  short, straight/other = one), so you can navigate by feel while biking/walking.
  Toggle in Settings → Navigation ("Vibrate on turns", default on)
- ✅ **Google-style maneuver banner** — a large **directional turn arrow** (the
  maneuver-type glyph, not a generic icon), the distance, the instruction with
  inline **highway/exit shields**, a **lane-guidance** strip, and a compact
  **"then <icon> …"** preview of the maneuver after this one — + remaining
  time/distance **and the arrival clock time** ("3.4 mi · 7:42 PM") on the
  bottom bar. The Steps control is icon-only + the ETA column flexes so a long
  "X mi · 7:42 PM" never pushes the **End** button off-screen
- ✅ **Swipe the banner to look ahead** — drag the maneuver banner left/right to
  walk the upcoming steps (Google-style): the card **tracks your finger** and, past
  a threshold, **slides off and the next/previous step slides in** (a pager-style
  flick, not an instant swap); it greys out, shows that step, and the map's marker +
  camera move there; tap it to resume live guidance
- ✅ **Directions step list / overview** (before *and* during nav); tap a step to preview that turn on the map — placed at its **true cumulative distance** along the route line (matching the polyline's own length, not the summed step distances), so the previewed spot lands on the actual turn
- 🟡 **Foreground navigation service** — guidance continues with the app
  backgrounded / screen off, persistent notification (this iteration)
- 🟡 **Periodic live re-routing** — every ~2 min while underway, re-check
  traffic; if a meaningfully faster route exists, announce it and offer to
  switch (this iteration)
- ⬜ Speed limits / speed-camera + hazard alerts (lane guidance ✅ done above)
- ⬜ Android Auto (needs GMS — likely out of scope)
- ✅ **Arrival / trip summary** — on reaching the destination, a "You've arrived"
  card replaces the nav controls with the trip's total time and distance (and the
  destination name), and a Done button returns to a clean map. (Real-drive
  hardening of the foreground service + live re-route is still pending an
  on-device test run.)

## Location (degoogled)
- ✅ AOSP `LocationManager` (GPS + NETWORK), no Fused/GMS
- ✅ Last-known seeding for instant map; PSDS slow-fix tip
- ✅ **Google-style location indicator** — a blue dot (Google's `#4285F4`) with a white ring, and a **translucent heading cone/beam** that fans out from under the dot in the facing direction (from GPS bearing; drawn beneath the dot, gradient-faded, hidden when there's no heading) instead of a hard arrow. **Greys out when the fix is stale** (no GPS update for ~12 s, or before the first live fix) and turns blue again on a fresh fix, like Google *(verified on-device)*
- ⬜ Compass heading when stationary
- ⬜ Optional BeaconDB WiFi positioning for faster coarse fix

## Offline
- ✅ **Offline basemap region downloads** — **Settings → Offline maps → "Download the
  area you're viewing"** saves the last on-screen area's tiles/glyphs/sprites (via
  MapLibre's built-in offline store) so it renders later with **no network**; the
  same section manages/deletes saved areas. (Moved off the map FAB stack to declutter;
  the viewport is captured on camera-idle.) Open tiles, no Google, no backend.
- ✅ **Offline search** — downloading a map area also pulls its POIs from
  **OpenStreetMap/Overpass** (keyless) into an on-device SQLite index; when Google
  search can't be reached, search falls back to that index ("Offline results")
- ⬜ Offline routing (embedded Valhalla / routing graph) — the heavy native lift
- ⬜ Region downloads as portable PMTiles + historical traffic

## Platform & distribution
- ✅ No Google Play Services anywhere
- ✅ Material 3 Compose UI; Hilt DI; R8 release builds
- ✅ Public GitHub repo + local mirror + offline bundle
- ✅ CI (GitHub Actions): every push to main builds + tests + signs the APK and publishes a **normal versioned release** (`v0.1.<run>`), kept as a revision history — Obtainium tracks the latest with zero config
- ✅ **Opt-in diagnostics / debug export** (Settings → Diagnostics, **off by default**) — a local-only event log (searches, computed routes, parser "drift", nav start/reroute/arrival) that the user can **Export debug session** to a JSON bundle and hand to a developer via the system share sheet. **Never auto-uploaded** — user-initiated and user-routed; turning it off wipes the log; in-memory only (capped at 300 events). The no-backend half of the telemetry plan; `core/diag/DiagLog` + `app/diag/DiagExporter`, consent dialog on enable, `PRIVACY.md` updated
- ✅ Settings shows the installed app version (name + build code)
- ⬜ F-Droid submission + reproducible build
- ⬜ UnifiedPush for delay alerts (no FCM)
- ⬜ ACRA / self-hosted crash reporting

## Resilience / maintainability
- ✅ **Remotely-updatable scraper calibration** — `calibration.json` at the repo
  root holds the `pb` templates, endpoint URLs **and (phase 2) the search parser's
  positional field-index paths** (`name`, `address`, `rating`, `photos`, … as
  `[i,j,…]` arrays). The app fetches it at launch and adopts a newer `version`
  (host-allowlisted to google.com) **without an app update** — so most Google
  drift (a moved `pb`, endpoint, or field index) is now a one-line edit + version
  bump, not a release.
- ✅ **Signed update channel** — the bundle is **ECDSA-P256/SHA-256 signed**
  (`calibration.json.sig`); the app verifies it against a **pinned public key**
  before adopting, so a repo/CDN compromise can't push config — or code — to
  devices (private key kept out of the repo; `scripts/sign-calibration.sh` re-signs;
  `BundleSignature.verify` is unit-tested). *(verified on-device)*
- ✅ **Pushed notices** — a `notices` array in the signed bundle surfaces dismissable
  alerts on the bare map ("search is down, fix coming") with **no app update**;
  dismissals persist per-id. *(verified on-device end-to-end)*
- ✅ **Remote parse logic** (phase 3) — a signed `transformsJs` bundle runs in a **Rhino
  sandbox** (interpreted, no Java access), so a *response-shape* change can be hot-fixed
  too — not just a moved field. Two search hooks (`parseSearch` full re-parse /
  `transformPlaces` post-process) over a flat place-JSON contract; **compiled Kotlin is
  always the fallback**. *(verified on-device: a pushed transform marked the first
  result, then cleared; engine + sandbox + contract unit-tested)*

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect
  periodic re-calibration (paths documented in the README). Pb/endpoint drift is
  now a remote `calibration.json` fix (above); index-path drift still needs a build.
- EU/EEA consent wall: pre-seeds Google's `SOCS`/`CONSENT` cookies in the shared
  jar so a cookieless session isn't bounced to `consent.google.com` (best-effort,
  US-verified only; the full form-POST handshake is the follow-up if it persists).
