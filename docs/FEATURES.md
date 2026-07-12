# Vela - feature list

Status legend: [x] done · [~] partial / in progress · [ ] planned

> **At a glance** (jump to a section for the detail). For *how* each of these works, see the
> "How it works" table in [`README.md`](../README.md#how-it-works).
>
> | Area | The short version |
> |---|---|
> | [Map & rendering](#map--rendering) | Keyless OpenFreeMap/Protomaps vector tiles, Google-style POI markers, hillshade, in-app light/dark, scale bar |
> | [Search & POIs](#search--pois-live-google-data) | Live keyless Google search - name/rating/reviews/hours/price/website/photos/popular-times |
> | [Routing & traffic](#routing--traffic) | OSRM turn-by-turn + Google traffic ETA & jam reroute; alternates; offline on-device routing (135-region catalog) |
> | [Navigation](#navigation) | Maneuver banner with lane diagram + highway shields, spoken + haptic guidance, speedometer, arrival summary; Android Auto (first cut) |
> | [Location](#location-degoogled) | AOSP LocationManager + rotation-vector heading, no GMS/Fused |
> | [Offline](#offline) | Downloadable tiles + OSM POI index + address geocoder + whole-state place packs + routing graphs + building/house-number overlays |
> | [Platform](#platform--distribution) | GrapheneOS/no-GMS, CI-signed `v0.0.<run>` releases, Obtainium, in-app updater, local crash/ANR diagnostics |
> | [Resilience](#resilience--maintainability) | Signed remote calibration (pb/paths/JS) + notices - hot-fix drift without an app update |

## Map & rendering
- [x] **Settings reorganized + nav UI refresh.** Sections in usage order (Appearance, Map, Place pages, Navigation, Voice, Offline, ...); vibrate-on-turns is one row of per-mode chips; the in-drive banner and bottom bar are soft-cornered floating cards.
- [x] **Arrive step names the destination.** The banner, step list and arrival card show the business name and address, falling back to address then coordinates.
- [x] **Zoomed-in pan smoothness.** The scale bar recomposes only past 1% change, house numbers skip the collision index, and a Map 3D-buildings toggle disables the z16+ extrusion layer.
- [x] **Highlight transit lines (train + subway).** Draws rail in colour (heavy rail purple, subway/light-rail/tram teal) from existing basemap tiles, no network; on by default (localized).
- [x] **Build a route between two points ("Choose on map").** The origin/stop picker shows a centre crosshair and a Set start/stop button; move the map or long-press to set the point, which is reverse-geocoded and routed (localized).
- [x] **Camera stays put when you close a place sheet or exit Settings.** The map re-frames only when a sheet appears or grows, and Settings draws as an overlay so the map view survives.
- [x] MapLibre Native vector rendering (Compose-wrapped).
- [x] Detailed open basemap: OpenFreeMap Liberty with injected house numbers at z17, pinned to OpenFreeMap's versioned tile path.
- [x] Route line, tappable Google-style search-result pins, and location dot as map layers.
- [x] Heading-up tilted navigation camera; fit-route-on-preview and a recenter FAB. Pinch during nav keeps the follow-camera at your chosen zoom; a pan detaches to look around, Re-center reattaches; speed-adaptive zoom pulls back on freeways and tightens in cities; the camera follows the smoothed puck at 60 fps.
- [x] **Traversed route greys out** behind the vehicle, with live traffic coloured per segment ahead (free-flow blue, amber/red over congestion); the full-map traffic overlay no longer auto-shows during nav. Swipe the banner to step through maneuvers.
- [x] Compass kept clear of the status bar (inset-aware margins).
- [x] Tap a labelled POI or search-result pin to open it; the camera frames all results. Unnamed POI icons reverse-geocode to a pin; co-located listings open the most-reviewed canonical one.
- [x] Bottom sheets fill to the screen edge (content padded off the gesture bar).
- [x] **Tap a house number or building to open its address**, snapped to the exact tapped number; a plain footprint reverse-geocodes, a real business opens as itself.
- [x] **Long-press the map** to drop a pin, reverse-geocode it (keyless OSM/Nominatim) and get Directions; a point with no street address shows copyable lat/lng.
- [x] Keyless **OpenFreeMap Liberty** basemap recoloured at runtime: Google-style POI markers, clean white roads, neutralised landuse, flattened fill-patterns, light/dark.
- [~] **MapTiler Streets** path wired but off (needs a key); the bundled-style Roboto font is parked.
- [x] **First-run welcome** - a branded intro with a single "Get started", shown once.
- [x] **Tasteful donation** - a permanent "Support Vela" entry plus a one-time prompt after a week of use, easily dismissed.
- [x] **In-app Light/Dark/Follow-system switch** setting Vela's theme independent of the phone; dark mode recolours every landuse fill.
- [x] **Google-style POI markers** - category-coloured teardrop pins with Material glyphs and left-aligned upright labels, ranked by prominence; nameless POIs filtered out.
- [x] **Building footprints + house numbers, Google-style** - flat grey footprints from ~z14, 3D extrusions at z16+, house numbers where OSM has them (coverage is OSM-limited).
- [x] **Traffic lights + stop signs on the map** at z16+ (keyless Overpass, area-cached), drawn beneath POI pins.
- [x] **Map POIs are Google-ranked by real prominence, not OSM** - ambient Google places ranked by review count and rating, view-filtered and zoom-scaled; OSM POIs hidden while they show.
- [x] **Per-travel-mode "Vibrate on turns"** - Driving/Walking/Cycling/Transit toggles, default on except driving.
- [x] **Place-sheet header + actions, Google-style** - name with Save/Share/overflow/close, and Directions/Call/Website action pills over the address.
- [x] **Search targets the panned viewport, not GPS**, so panning elsewhere then searching returns results there; autocomplete too.
- [x] **Faster POI loading via HTTP concurrency** - parallel category queries fire in one round, plus a 16-entry LRU cache with a 30-min TTL.
- [x] **POI dots reappear instantly on zoom-back** from the last cached fetch, refined by the network fetch.
- [x] **Voice settings decluttered + default speed 0.8x** - essentials shown, playground/speed/variant behind an Advanced header.
- [x] **Replay uses the selected voice, not the system fallback**; all POI tiers hide during turn-by-turn.
- [x] **Buildings** (footprints + 3D massing) from OSM Liberty tiles, keyless; gaps in new suburbs filled by the Microsoft building overlay for downloaded regions.
- [x] **Terrain relief (hillshade)** from the keyless open terrarium DEM, under the road layers, capped at z16, tuned per theme.
- [x] **Two-finger tilt to 3D** - pitch the map up to 70 degrees; the tilt sticks across browse moves.
- [x] **Ambient Google POIs on the map** - the visible area's prominent Google places drawn as native-style category dots, zoom >= 14 on a bare map, viewport-tracking with a category fan-out.
- [~] Self-hosted PMTiles - the no-key, no-quota Google-look path - remains for later.
- [ ] Protomaps "Google-Maps-ify" bundled style (road hierarchy, hillshade, POI icons done).
- [ ] Satellite / aerial imagery layer.
- [x] Map rotation/tilt + heading-up mode during nav.

## Search & POIs (live Google data)
- [x] **Toggles to hide reviews and skip photo loading** (Settings → Map, both default on) that gate both the scrape and the render, so off means no traffic (localized).
- [x] **"Hide adult categories" toggle** (default off) dropping adult/nightlife/alcohol/gambling/smoking places from search and the map by category only, precisely, across all 15 languages (localized).
- [x] **"Hide website & external links" toggle** (default off) hiding the Website, Street View and Book/Reserve/Order on place pages (localized).
- [x] Place search - name, category, full address, rating, review count, coordinates.
- [x] Searching a specific/far address resolves to that single geocoded location; empty searches show "no results".
- [x] **Address to business snap** - a raw address that is a business lands on the business, not the bare address.
- [x] **Business name stripped from the address line** when the formatted address is name-prefixed.
- [x] Search-result rows show a 5-star rating, colour-coded open/closed status, and the full address, sized for legibility.
- [x] **Google-styled place sheet**: high-contrast name and status, 5-star visual, three-detent swipe (expanded/peek/minimized card), distance, price, category, copyable address, collapsible weekly hours, and an amber holiday-hours callout.
- [x] Full-screen photo viewer with pinch-to-zoom, swipe-down-to-dismiss, and sideways paging.
- [x] **System maps handler** - registers for geo: URIs and Google-Maps web links so other apps can open places in Vela.
- [x] Viewport-biased "near me" search.
- [x] Recent searches and recently-viewed places on the search page (capped, deduped, cleared together).
- [x] **Full-screen search page** with Home/Work shortcuts and saved + recent searches over the map.
- [x] **Home/Work shortcuts** - two pinned rows, settable by picking a place or promoting an open/saved place, with a Change/Remove menu.
- [x] **Autocomplete/suggestions as you type**, reusing the search endpoint; stale responses dropped.
- [x] Clear-search (X) plus a bottom-sheet results list with minimized/peek/expanded detents, a detent chevron, and an X that exits search entirely.
- [x] **Result filters** - "Open now" and "4.0 star" chips that stack, with a live count.
- [x] **Back gesture peels one layer at a time** (steps → nav → preview → place sheet → results); only the bare map exits.
- [x] **Full reviews via a hidden WebView, with per-review photos** - loads the place page anonymously and scrapes each review card (rating, author, date, text, uploaded photos, avatars excluded); retries when the count says there are reviews but none loaded.
- [x] **Tabbed place sheet** (Reviews + About) with photos, info, hours, actions, popular times, then tabs.
- [x] **About tab** - editorial one-liner, the owner's blurb, then attribute sections, fetched lazily and used to backfill fields a summary node drops.
- [x] **Action link - Book/Reserve/Order online** as a tinted button opening the provider link, gated on a real http(s) URL.
- [x] **Attribute highlight chips** - the most-scanned attributes surfaced as a chip row on the overview.
- [x] **"People also search for"** - a row of related-place cards on a focused name search, tap to open.
- [x] **"Also at this location"** - co-located listings (same street line) with rating and category, tap to open, drawn from results in hand.
- [x] **Directions panel** with a From→To header and swap, Drive/Transit/Walk/Bike tabs, Start + Steps, and selectable route options each with a traffic-coloured ETA, distance, via-road, Fastest tag and +N min delta.
- [x] **Route from a different starting point** - the From row is editable via search, in both directions, with a "Your location" reset.
- [x] **Multi-stop directions (waypoints)** - an Add stop row inserts removable intermediate stops and routes straight through them as a single route.
- [x] **Multi-stop follow-ups** - a per-stop arrival cue, reroute through remaining stops, and up/down reorder.
- [x] **Depart/arrive time (+ date, + Last available)** - a Leave now/Depart at/Arrive by chooser that re-fetches transit and shows drive's typical arrival window.
- [x] **Search along route** - Gas/Food/Coffee/Groceries chips search near the planned route, ordered start to destination with along-trip distances; tapping a result adds it as a stop.
- [x] **Consistent sheet styling** - one Google-grey palette across the place sheet, directions, chooser, steps, nav bar, results, search page and banners.
- [x] **Permanently-closed POIs** - flagged, kept in results labelled red, dropped from map pins.
- [x] **Alternate routes** - Google's driving alternates selectable, each drawn along Google's own geometry.
- [x] **Alternates drawn greyed on the map + tappable**, below the active blue line and below label layers.
- [x] Place sheet peeks (~56%) with the map visible; drag the handle to expand for reviews, down to dismiss.
- [x] **Pin stays visible above the sheet** via map bottom padding + zoom.
- [x] **Popular/busy times** histogram, keyless via a specific name+address query fetched in a hidden WebView, with a loading indicator.
- [ ] "hours updated N ago"; Updates/posts tab.
- [x] **Reviews load many (~40, capped 50), not 3** - an offscreen WebView viewport renders the virtualized list, de-duped across scroll windows, opened via the Reviews tab; per-review photos preserved.
- [x] **In-app neural voice (Piper), runs inside Vela, no standalone app** - downloads the fleet-default voice and runs it in-process (sherpa-onnx VITS to AudioTrack); real-time on old phones; replaces needing SherpaTTS. (Kokoro/Matcha were tried and removed.)
  - **Voice library** - a browsable catalog of ~40 curated Piper voices grouped by language, each with Download/Use/Delete and per-voice speaker choice; the installed set is derived from the filesystem, switching is race-free.
  - **Default voice = HFC Female @ 0.8x, remote-settable** - the default voice id, speaker and speed ride the signed calibration bundle; a user's own pick still wins. Settings → Voice has a playground, a speed stepper and a variant picker for multi-speaker voices.
  - **Clearer pauses at periods** - splits each utterance on sentence boundaries and splices silence, name-aware so road abbreviations aren't split.
  - **Street numbers read the human way + no clipped start** - 3-digit street ordinals spoken as people say them, and the DEPART maneuver spoken once at start then skipped.
  - **Spoken navigation speaks 14 languages** (fr de es it pt nl ru pl sv uk zh zh-TW ja he, plus English) via per-language templates auto-following the phone's language; road names never translated. Chinese/Japanese/Hebrew have no Piper voice, so their guidance uses the phone's system TTS.
  - **15 UI languages incl. Chinese (Simplified + Traditional), Japanese and Hebrew (RTL).** Full UI chrome + POI open/closed status parsing (localized `hl=`); RTL is automatic via `supportsRtl`.
  - **Voice-language pairing** - onboarding grabs the voice matching the app language, the library floats it to the top, and a missing match shows a download nudge.
  - **In-app language picker** - Settings → Language overrides the app language ("Follow system" or any of 11).
  - **UI chrome speaks 10 languages** - all ~330 strings translated, switched at runtime; format placeholders validated against English; dual-purpose logic-key literals stay inline.
  - **Google POI content comes back localized** - the language is rewritten at request time, with open/closed parsed from the localized status text against a per-language keyword table.
  - **Voice-language mismatch handled** - a mismatched neural voice routes to Android TTS in the target language, or stays silent with a nudge rather than mangling it. The nudge carries a one-tap fix: a language Vela can voice (fr/ru/zh/...) gets a "Get a voice" pill to the voice library; one it can't (Japanese/Hebrew - no Piper voice, spoken by system TTS) gets a "System voices" pill straight to the phone's text-to-speech settings.
- [x] **Voice search - speak your query, on-device (no account, no Google needed).** A mic in the search bar dictates a search. TWO tiers: **tier-1 on-device** downloads Vela Voice (Whisper tiny + Silero VAD, ~58 MB, run in-process via the bundled sherpa-onnx) and records + transcribes on the phone - nothing uploaded, works on a bare degoogled ROM; **tier-2 provider** hands off to an installed voice-input app (FUTO Voice Input, or Google's recognizer on GMS phones) via the RECOGNIZE_SPEECH intent. **Auto** prefers on-device when the model is present, else a provider; Settings -> Search lets you pin the choice and pick which voice app. The mic only shows when something can service it (never a dead button); with nothing installed, tapping it offers the Vela Voice download. Whisper is pinned to the app language. D-pad-first: the listening sheet's Done button auto-focuses. Model hosted on this repo's `asr-models` release.
- [x] **POI hero photos - LRU cache** so re-tapping a place shows its gallery instantly.
- [x] **Reviews pivot follow-ups** - native review search bolds matched terms, the full-screen panel renders on first paint with tappable photos, a bigger sheet handle, and reliable photo captions (fixed for Android 15/16).
- [x] **Reviews = native inline list + full-screen "Read all" Google panel** - inline reviews are Vela's own native list; a "Read all reviews" button opens Google's live panel full-screen with its own infinite scroll, search, sort and photo/video viewers.
  - The full-screen panel embeds Google's own reviews pane in a CSS-carved WebView: auto-pages with no cap, Google's server-side search/sort, theme-following, trackers and post-load navigation blocked, photos and native search/sort/topic chips wired to Google's hidden controls, a watchdog that fails over to the native scraper if the feed is withheld, and a "Search reviews" box that live-filters loaded reviews.
- [x] **Live review-loading progress** - a determinate "Loading reviews... N of ~M" bar with reviews streaming in under it; cancellable, with stale-flag hygiene on a dropped pin.
- [x] Place actions in a **Google-style quick-action row** - Call, Website, Save, and a Share menu (Maps link / geo: pin / coordinates / address).
- [x] **Place photos - full gallery, keyless via place-page scrape** - a hero strip that opens instantly with the search preview then swaps in ~9-25 gallery photos scraped from the hidden WebView, with shimmer tiles while loading.
- [x] **Photo category tabs (Menu / Food & drink / Vibe / By owner)** - the WebView visits each category tab and returns tagged photos; a chip row filters the strip.
- [x] **Full gallery via a hidden WebView** - the gallery RPC serves user photos only to a real browser engine, so an anonymous WebView same-origin-fetches it; keyless, lazy, best-effort, OkHttp fallback.
- [x] Category quick-chips (Restaurants/Coffee/Gas/...) for one-tap search, each with a leading icon.
- [x] "Search this area" re-searches the panned viewport after a pan.
- [x] Filter open now, rating >= 4.0, and price (cycling down the price levels), stackable chips.
- [x] Saved/favourite places (star from the place sheet), enriched via search on reopen, each with a Set Home/Work or Remove menu.
- [x] **Export/import saved places** as portable JSON via the system sheet; import merges deduped.
- [ ] Overture/OSM POIs as a fallback source.

## Routing & traffic
- [x] Driving directions with real traffic-aware ETA (live in-traffic duration).
- [x] **Live traffic overlay** (browse) - Google's congestion-coloured roads as a keyless raster layer, toggle in Settings → Map, off by default.
- [x] **Per-segment route-line traffic** - the drawn route is coloured along its length from the response's congestion spans (free-flow blue with amber/red bands), rendered as solid colour bands and combined with the driven-grey split during nav; walk/bike fall back to one overall tint.
- [x] Alternative routes returned.
- [x] **Turn-by-turn from an open router (FOSSGIS OSRM), not Google** - every turn with street names for drive/walk/bike, since Google's keyless steps are abbreviated; Google stays for live-traffic ETA and POIs.
- [x] **Traffic-aware routing** - when Google's live route diverges >700 m from OSRM's, Vela re-runs OSRM through points sampled off Google's polyline to follow the jam-avoiding path with full street-named steps; free-flow routes stay pure OSRM.
- [x] **Offline routing - fully on-device + live, world catalog hosted** - when offline or OSRM is down, an on-device GraphHopper engine loads downloaded per-region Contraction-Hierarchies graphs and routes complete street-named turn-by-turn in ~200 ms; graphs built off-device.
  - **Get a region two ways** - pick it under Settings → Offline → Routing regions (regions covering your location sort first), or download offline map tiles for an area, which also grabs its routing graph.
  - **Hosting + world catalog** - graphs and a manifest hosted on a GitHub release; a 135-region catalog (US states, Canadian provinces, ~36 European countries, plus starter regions elsewhere) built by a parallel Actions matrix.
- [x] **Turn instructions keep the road name** ("Turn right onto Russell Blvd"), synthesized from OSRM's type/modifier/name/ref/destinations/exits; highways name by ref, ramps read "Take exit 72B toward..."; offline GraphHopper carries names but not refs yet.
- [x] **Walking/biking routes draw dashed** - a second round-capped dash layer (drive stays solid + traffic-coloured), true circular dots on a zoom-interpolated width.
- [x] **POI/interaction polish** - ambient POIs declutter by prominence, tapping a POI while the chooser is up brings it to the front, and results get a "Hide results" bar.
- [x] **Recenter fix + address shimmer suppressed** - a nonce force-moves to the user once per tap; the photo shimmer is gated on photo-worthiness.
- [x] **Honest remaining-distance/next-turn on routes that pass near themselves** - the nav engine tracks monotonic forward progress and measures along the road, not crow-flies.
- [x] **Lane guidance - real per-lane diagram** from OSRM lane data, drawn in the banner and step list with the active lanes bright, appearing within ~0.5 mi.
- [x] **Spoken lane guidance - lanes-first, Google-style** ("Use the right 2 lanes to..."), from the same OSRM lane data, spoken once at the far prompt (localized).
- [x] **No more "continue on the road you're already on", even when the name changes** - CONTINUE is voice-silenced unless it carries lane guidance, while genuine bends and junctions still speak and the banner still shows every step; plus straight renames stay silent, roundabout exit counts are restored, the fastest route reliably leads the picker, and the compass isn't buried under the nav card.
- [x] **Nav survives a process kill - "Resume navigation?"** - persists the destination and offers a Resume card on next launch if recent, re-routing from your current fix; heartbeated every 5 min.
- [x] **Navigation tracking overhaul** - GPS starvation at a standstill fixed with a 0 m distance filter, the parked-jitter ratchet gated at near-zero speed, mid-drive freezes unblocked, and the out-and-back "turns miles early" bug fixed with a windowed maneuver projection.
- [x] **Full navigation audit + remediation** - a broad audit fixing position integrity (drop NETWORK fixes during nav, monotonic dt, coarse fixes don't steer), rerouting (single-flight, cooldown, latch-clear-on-failure), guidance timing (speed-scaled prompts, silent tunnel catch-up, proximity arrival), ETA from remaining steps times traffic, a symmetric accel-bounded speed filter, a geometry-split route line, refcounted audio focus with a ducking hold, and lifecycle teardown on arrival.
- [x] **Trip replay made trustworthy** - segmented trips (each route swap its own block), hermetic replays (no live fetches), and trace-time puck physics so it doesn't stutter; maneuver positions resolved sequentially forward.
- [x] **Compound maneuver preview ("... then keep right")** when the next maneuver closely follows, carrying its shield.
- [x] **Highway/exit signage with real shield shapes** - route refs and exit numbers rendered as green exit tabs and interstate/US-route/state shield shapes, the network inferred from the ref prefix with no OSM lookup.
- [x] Route geometry via open router - per-mode OSRM backends for drive/walk/bike.
- [x] **Live route re-check while navigating** - every ~2 min re-queries traffic and offers a faster route.
- [x] Walking + cycling modes, each with its own path-following line.
- [x] **Public transit directions** - a Transit chip shows a results board with each option's departure-arrival window, duration, distance, agency and coloured line pills; served via a hidden WebView since a plain request downgrades to driving (keyless parse).
- [x] **Transit leg drill-down with full stop detail** - board/alight stops with IDs and real-time "N min late/early", an intermediate-stops list, service alerts, a dialable agency phone and fare where provided, from the same keyless fetch.
- [x] **Transit walk-leg turn-by-turn** - tap a Walk leg to expand OSRM-foot steps between stop coordinates from the same transit payload, fetched on demand.
- [x] **Transit step-by-step guidance (Moovit-style)** - a Start button opens a full-screen leg-by-leg guide that auto-advances on GPS and speaks each cue (localized), with a latched advance so hubs don't cascade.
- [ ] Per-minute predictive future-traffic ETA (login/app-only); avoid tolls/highways.
- [ ] Self-hosted routing backend (replace the FOSSGIS community server).

## Navigation
- [x] **Android Auto, first cut.** Vela appears in the car launcher as a navigation-category app showing the live map (car day/night), a puck, the route, camera follow with Re-center/zoom, and pan/zoom; while navigating it shows the current maneuver + distance driven by the same nav session the phone runs, speaking through the car speakers. You still start and end nav on the phone.
- [x] Turn-by-turn engine (step advancement, off-route detection, reroute), pure/Android-free, measuring distance along the route and pinning each turn to its step start.
- [x] **Offline nav auditor** - the navigated route is saved into the recorded trip, so a replay drives the exact line the user saw and diffs banner/voice claims against where maneuvers sit, flagging silent/missed turns and lying card distances.
- [x] **Screen stays awake while navigating** (Settings → Navigation, default on), cleared the instant nav ends.
- [x] Spoken guidance via AOSP TextToSpeech, tuned for the car (rate, highest-quality offline voice), speaking "Head east on..."; Settings → Voice lists engines with Test and System settings; spoken distances follow the Units setting; road abbreviations expanded for speech.
- [x] **Speaks the road name** - "turn right onto Larch Way", generating TTS from the written instruction (bare "turn right" only when Google's text has no road name).
- **Fixed: every turn drew a generic forward arrow** - the parser now reads the child turn side/type token, so plain turns and ramps get correct arrows and haptics.
- **Fixed: route-line gradient spamming MapLibre errors** - the gradient always seeds a valid base-colour stop.
- **Fixed: silent navigation** - a queries entry for TTS_SERVICE restores engine visibility on targetSdk 30+.
- [x] **One-tap voice on a ROM with no TTS** - Settings → Voice installs eSpeak NG or RHVoice from F-Droid, with a spinner and a fallback to the F-Droid page.
- [x] **Mute voice during nav** - a speaker toggle in the nav bar, independent of the haptic cues.
- [x] **Speedometer** - a circular badge showing GPS speed in mph/km/h.
- [x] **Scale bar** - a bracket sized to a round distance from live metres-per-pixel (correct for zoom and latitude), following the Units preference.
- [x] **Pan-away + Re-center** - dragging during nav detaches the follow-camera; a Re-center button reattaches it.
- [x] **Haptic turn cues** - a light pre-turn tick then a firm direction-coded buzz (left two pulses, right three, straight one), toggle in Settings → Navigation.
- [x] **Google-style maneuver banner** - a large turn arrow, distance, instruction with inline shields, a lane strip, a "then ..." preview, and remaining time/distance + arrival clock on the bottom bar.
- [x] **Swipe the banner to look ahead** - the card tracks your finger and slides to the next/previous step, moving the marker and camera; Re-center snaps back to the current step.
- [x] **Traffic-coloured nav ETA** - the remaining-time readout tinted by live traffic, normal ink when there's no data.
- [x] **Minimisable route chooser** - swipe the directions panel down to preview the route, up or tap to restore; a compact Start stays reachable.
- [x] **Directions step list/overview** before and during nav; tap a step to preview that turn at its true cumulative distance; pre-nav steps and ETA match nav from the start.
- [~] **Foreground navigation service** - guidance continues backgrounded/screen-off via an ongoing notification with the next turn, ETA, faster-route note and End; best-effort on Android 14/GrapheneOS.
- [x] **Periodic live re-routing** - every ~2 min re-checks traffic and offers a one-tap switch to a faster route.
- [~] **Posted speed-limit badge** (app-side done; needs the graph re-bake to light up) - a US-MUTCD or EU-disc sign by the speedometer that reddens when exceeded, from OSM maxspeed read off the on-device GraphHopper graph, keyless + offline.
- [ ] Speed-camera + hazard alerts.
- [x] **Android Auto first cut shipped** (see above); remaining: car-side search + route start, and a real head-unit drive.
- [x] **Arrival/trip summary** - a "You've arrived" card with total time and distance and the destination name, with the final maneuver pinned to the route end.

## Location (degoogled)
- [x] AOSP `LocationManager` (GPS + NETWORK), no Fused/GMS.
- [x] Last-known seeding for an instant map; PSDS slow-fix tip.
- [x] **Google-style location indicator** - browse: a blue dot with a heading cone driven by the rotation-vector compass (facing shown even standing still), greying when the fix is stale.
  - **Nav: a solid blue arrow puck** that snaps onto the route line and faces down the road, engaging only within ~22 m and heading the road's way, so a wrong-way/off-road fix shows the real position.
  - The puck rides a forward-progress motion model with dead reckoning, eased progress and smoothed heading, its speed from a Kalman filter fusing GPS speed with measured acceleration.
  - The on-route match is forward-only and monotonic within a bounded look-ahead, so a route passing near itself can't pull the puck backward; it holds and dead-reckons when nothing is ahead, re-acquiring globally only at start or after a reroute.
  - The follow-camera tracks the same smoothed point and holds the road-aligned heading off-route; the traversed-grey split rides the puck's drawn position as a hard cut.
  - Heading and speed are synthesised from movement when a fix lacks them, gated on real movement; single-fix speed spikes and position outliers are rejected and position runs through a speed-adaptive low-pass, with re-anchoring on a persistent leap.
- [~] BeaconDB WiFi positioning - NETWORK coarse fixes used for the browse dot when GPS is quiet (never during nav); an explicit opt-in and deeper use are still open.

## Offline
- [x] **Onboarding offers offline maps** - a one-time prompt explaining places/search/directions need signal, opening Settings → Offline (localized).
- [x] **Settings → Offline is collapsible**, collapsed by default.
- [x] **Offline basemap region downloads** - save the on-screen area's tiles/glyphs/sprites (MapLibre's offline store) to render with no network; manage and delete saved areas.
- [x] **Offline search** - a downloaded area also pulls its OSM/Overpass POIs (with address/phone/website/hours) into an on-device index used when Google is unreachable; category words and the map chips map to OSM tags, multi-word queries ranked, addresses matched.
- [x] **True offline address routing - typed address to coordinate to route, no signal** - a downloaded area builds an on-device forward geocoder from OSM house-number points and road centrelines (keyless Overpass over a padded box), layered exact → interpolate → nearest house → nearest street, abbreviation-normalized, routing via GraphHopper.
  - **Offline POIs show an address** - a POI with no OSM street reverse-geocodes against the same index (nearest house <=60 m, else street <=150 m).
  - **No misleading "current traffic" offline** - the ETA subtitle notes traffic only when the route carries a live ETA.
  - **Upgrade nudge for older saved areas** - an "Update saved areas" card re-fetches address/street data for areas saved before this feature (localized).
- [x] **Whole-region offline place packs - Organic-Maps-style state-wide search** - downloading a state pulls a per-region SQLite pack (POIs with detail, address points, street names) baked by CI from Geofabrik; a normalized schema keeps a state small and fast; packs ride the region, with a "Get places" button for regions installed before packs (localized).
- [x] **Place packs stay fresh** - a monthly CI rebuild bumps each region's rev; an "Update places" button applies a small row-level delta verified against manifest counts, falling back to a full download; deltas stay small via stable content-hash street ids.
- [x] **Offline search ranks exact name matches above the category flood** - pack SQL orders whole-query name matches first, ahead of the row cap.
- [x] **State downloads show a heads-up progress card** on the map for the routing-region download and the place pack that follows.
- [x] **Quiet offline indicator, no banner** - a greyed globe-slash "Offline" in the search bar and a chip under the category chips, from a reactive connectivity callback; an offline search with no saved area shows a helpful message instead of a raw host error.
- [x] **Open building-footprint overlay (Microsoft, ODbL)** - fills OSM's building gaps; streams online via PMTiles HTTP range requests (no download) and saves per region for offline, baked by CI into per-region archives; a 361-row world catalog (51 US states + DC, ~185 countries, big countries chunked by quadkey), rendered beneath the OSM building layer so it only fills gaps.
- [x] **Open house-number overlay (OpenAddresses)** - a second streamed overlay of address points rendered as house-number labels over the footprints at z17.5+, filling the gap where OSM lacks addr:housenumber; WA hosted, a CI pipeline fans out the 42 statewide-source US states.
- [x] Offline routing - on-device GraphHopper CH graphs (135-region world catalog), done.
- [ ] Region downloads as portable PMTiles + historical traffic.

## Platform & distribution
- [x] **In-app updater** - checks the newest GitHub release (~daily on launch or on demand), offers a newer build via a map card, downloads the APK with a progress bar and hands it to the system installer; "Not now" silences that version; a Settings toggle (localized).
- [x] No Google Play Services anywhere.
- [x] Material 3 Compose UI; Hilt DI; R8 on all build types (debug installs side by side; release adds resource-shrink; staging for profiling).
- [x] Public GitHub repo + local mirror + offline bundle.
- [x] CI builds, tests, signs and publishes a normal release `v0.0.<run>` with debug and release APKs; no prerelease channel, tracked by Obtainium and the updater with zero config.
- [x] **Opt-in diagnostics/debug export** (Settings → Diagnostics, off by default) - a local-only event log exportable to JSON via the share sheet, never auto-uploaded, wiped when turned off, in-memory only.
- [x] **Crash/ANR/jank capture, all local** - an uncaught-exception handler persists stack traces + breadcrumbs to disk for export, ApplicationExitInfo harvests ANR/native/low-memory kills, a debug ANR watchdog and StrictMode flag stalls and main-thread I/O; captured even with diagnostics off, never auto-sent.
- [x] **Trip recording + replay** (Settings → "Save my trips", off by default, separate opt-in) - records each drive's GPS trace to a local file replayable on the map at 3x through the real nav pipeline; saved on arrival, listed with Replay/Share/Delete, Share exporting the raw CSV; replay auto-routes to the destination.
- [x] **Simulate driving (demo mode)** (Settings → Navigation, off by default) - Start drives any planned route as a synthetic GPS trace through the live-nav loop so nav runs anywhere for demos and screenshots; End stops it; turn off to navigate for real.
- [x] **Simulate my location (demo mode)** (off by default) - Vela pretends you're at the map centre so the dot, directions origin and recenter read from there; turn off for real GPS.
- [x] Settings shows the installed app version (name + build code).
- [x] **Full D-pad / no-touchscreen operation** (touch is a bonus) - every surface reachable and activatable by focus with a visible ring, the map key-drivable (arrows pan, OK taps a crosshair, hold-OK long-presses, +/- zoom), key alternatives for every gesture, fields escaping UP/DOWN, and every screen auto-focusing on open; menus and dialogs rebuilt as VelaMenu/VelaDialog since Compose can't pre-focus a DropdownMenu/AlertDialog; affordances appear only in key-driven mode. See [`docs/dpad.md`](dpad.md).
- [x] **Tiny feature-phone screens (240x320-class)** - `AdaptiveDensity` (`ui/AdaptiveDensity.kt`) checks the screen at launch and scales the app's own density so it always has >= ~360dp of logical width, fixing clipping across every screen at once (verified: all three category chips fit at 240x320 where before only one did); works on logical dp so it generalizes across resolutions (verified at 480x854@320). Only shrinks small screens; normal screens are a no-op. Target device matrix + per-phone visual proof in [`tests/devices/`](../tests/devices/); one-command screenshot capture (`tests/devices/capture.sh`) and a density-sweep auditor (`tests/small_screen/run_matrix.sh`).
- [ ] F-Droid submission + reproducible build.
- [ ] UnifiedPush for delay alerts (no FCM).
- [ ] ACRA / self-hosted crash reporting.

## Resilience / maintainability
- [x] **Remotely-updatable scraper calibration** - a signed calibration.json holds pb templates, endpoint URLs and the parser's field-index paths, adopted at launch when newer, so most Google drift is a one-line edit + version bump, not a release.
- [x] **Signed update channel** - the bundle is ECDSA-P256/SHA-256 signed and verified against a pinned public key before adopting, so a repo/CDN compromise can't push config or code.
- [x] **Pushed notices** - a notices array in the signed bundle surfaces dismissable bare-map alerts with no app update; dismissals persist per-id.
- [x] **Remote parse logic** - a signed transformsJs bundle runs in a Rhino sandbox (no Java access), so a response-shape change can be hot-fixed, with compiled Kotlin always the fallback.
- [x] **Dead-code CI gate** - detekt dead-code rules, a host-side unused-declaration audit, and Android Lint UnusedResources.

## Known calibration debts (the NewPipe lifestyle)
- Google request/response shapes are pinned to a 2026-06-15 capture; expect periodic re-calibration. Pb/endpoint and field-index paths are remote fixes via `calibration.json`, so drift is an edit + version bump.
- EU/EEA consent wall: pre-seeds Google's `SOCS`/`CONSENT` cookies so a cookieless session isn't bounced to `consent.google.com` (best-effort, US-verified; the full form-POST handshake is the follow-up).
