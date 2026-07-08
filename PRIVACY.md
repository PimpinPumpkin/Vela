# Vela Maps — Privacy

> What leaves your phone, where it goes, and what it doesn't. Written to be honest
> rather than reassuring: Vela scrapes Google's public web endpoints, so Google does
> see some of your requests — but as a logged-out browser would, not tied to an account.

## TL;DR

- **There is no Vela server.** Vela has no backend, no account, no analytics, no crash
  reporting, no ad SDK. Nothing you do is sent to *us* — there is no "us" to send it to.
- **Vela talks to Google directly from your phone**, the same way `maps.google.com`
  in a browser does, for search / places / routing / traffic. Google therefore sees
  your **IP address**, your **search text**, and the **map area** of each request —
  but **not a Google account** (you're never signed in) and **no app/API key** that
  labels the traffic as "Vela."
- A few **non-Google open services** get small, specific requests (map tiles, reverse
  geocoding, terrain, fallback routing) — see the table.
- **Your places, history, and settings stay on the device.** Saved/Home/Work/recent
  places, preferences, and downloaded offline areas are local only; they're never
  uploaded anywhere.

## What each service receives

| Service | When | What it gets | What it does **not** get |
|---|---|---|---|
| **google.com** (search/place) | every search, place open | your IP, the query text, the map viewport (lat/lng), a logged-out session cookie | your Google account, name, device ID, contacts |
| **google.com** (directions) | planning a route | your IP, origin + destination coordinates, viewport | account; your live position isn't sent unless you navigate |
| **google.com** (reviews/photos) | opening reviews / the photo gallery | your IP, the place's feature id | account (photos load via an **anonymous** hidden WebView — see below) |
| **google.com/maps/vt** (traffic) | traffic overlay on | your IP, the tile coordinates you're viewing | anything tied to you beyond IP |
| **OpenFreeMap** | viewing the map | your IP, which map tiles you pan over | no search/place text — just tile coordinates |
| **OSM Nominatim** | long-pressing to drop a pin | your IP, that one lat/lng | nothing else |
| **AWS (terrarium DEM)** | hillshade relief | your IP, tile coordinates | nothing else |
| **FOSSGIS OSRM** | every route you plan | your IP, origin/destination (and waypoint) coordinates | the primary turn-by-turn router; Google is queried in parallel only for the traffic ETA |
| **Overpass (OSM)** | "download this area", offline address indexing, and traffic-light/stop-sign icons at close zoom | your IP, the bounding box | nothing else |
| **raw.githubusercontent.com** | once at launch (config refresh) | your IP, a plain file fetch | no data *about you* is sent — it's a download |
| **GitHub release assets** | downloading offline regions/voices, checking for app updates, and streaming the building/house-number overlays as you browse (small ranged tile reads) | your IP, which file or tile byte-range is fetched (implies your rough map area for the overlays) | no query text, no account |

## Google, specifically

Because Vela scrapes Google rather than running its own maps stack, Google is the
service that sees the most. Concretely, per request Google receives **your IP
address, the search/route text or coordinates, the map area, a browser-like
User-Agent, and short-lived consent cookies** (`SOCS`/`CONSENT`, seeded in memory so
the EU consent wall doesn't block you — they carry no identity).

What Google does **not** get from Vela:
- **No Google account / sign-in.** Vela never logs in. There is no Gmail, no profile,
  no "your timeline."
- **No shared API key.** Requests aren't stamped as coming from an app called "Vela";
  they look like an ordinary logged-out browser hitting `maps.google.com`.
- **No persistent identity from Vela.** The session cookies are in-memory and
  per-session; Vela doesn't attach a device id or a stable user id.

**Versus the official Google Maps app:** there, you're normally signed in, so Google
ties every search, route, and stop to your account and builds your Maps history and
location profile. With Vela it's closer to using `google.com/maps` in a **private /
incognito browser window**: Google still sees the IP and the individual requests, but
can't link them to a Google account or your real-world identity. The honest limit:
**your IP is still visible to Google** (and to every service in the table) — that's
inherent to fetching from them. If you want to hide that too, run Vela over a **VPN or
Tor**; it works over any network.

## The hidden WebViews (photos, reviews, transit, popular times)

Four features — the **full photo gallery**, **reviews**, **public-transit directions**
and **popular times** — are only served to a real browser engine, so Vela loads `maps.google.com` in a **hidden,
logged-out WebView** and reads the result. This is the one place Google's own
JavaScript runs on your device. It runs **anonymously** (no login), but, like any
browser visit to Google, that JS *could* set cookies or fingerprint the browser. It's
an explicit, scoped tradeoff for data a plain request can't get; if you never open the
photo gallery or transit directions, that WebView never loads.

## What stays on your device

Stored locally only (SharedPreferences / SQLite / MapLibre's offline store), never
transmitted:
- Saved places, Home/Work shortcuts, recently-viewed places, recent searches
- Settings (theme, units, voice engine, traffic toggle, keep-screen-on-while-navigating)
- Downloaded offline map areas + their offline POIs
- Dismissed-notice ids

There is no cloud sync. Uninstalling the app removes all of it.

## No tracking

Vela contains **no analytics, no advertising, no crash reporting, and no
Firebase/Play Services**, and **no telemetry that runs without you turning it on**. The
app makes network requests only to the services in the table above, only when a feature
needs them. It's GPLv3 — you can read every request the code makes in
[`core/data/google`](core/src/main/java/app/vela/core/data/google) and [`SPEC.md`](SPEC.md).

## Diagnostics (opt-in, off by default)

Settings → **Diagnostics** has one switch, **off by default**. When you turn it on, Vela
keeps a short **local** log of what it did — your searches, the routes it computed, and
any "needs recalibration" hiccups — so that if something misbehaves you can **export it
and hand it to a developer** to debug. Specifics:

- **Nothing is uploaded by Vela.** The log lives only in memory on your phone. The only
  way it leaves is if *you* tap **Export debug session** and then choose where to send it
  (email, a chat app, Files…). You see it's a file; you pick the destination.
- **It contains** the breadcrumbs above — which can include your search terms, the
  start/end coordinates of routes you asked for, and **navigation breadcrumbs** (a
  start/arrival line with the destination name and the drive's distance + time, plus
  "GPS gap" markers noting where the signal dropped and for how long — for debugging a
  bad route or *tuning the turn-by-turn*). No account, no contacts, no continuous
  location trail.
- **Turning it off wipes the log**, and it clears when the app closes anyway.

## Trip recording (separate opt-in, off by default)

Settings → **"Save my trips"** is a **second, distinct switch**, also **off by default**
and **more revealing** than diagnostics — so it's deliberately separate, and the first-run
prompt asks for it on its own line.

- When on, Vela records the **GPS trace of each navigation** (the points along your drive
  + the destination) to a **file on your phone**, so a trip can be **replayed** later to
  test turn-by-turn without driving it again.
- This is your **exact routes and movement** — the most sensitive thing the app stores.
  **Vela never uploads it** — there is no auto-upload code path. The only way a trace
  leaves the phone is if *you* tap **Share** on a trip and choose where to send it (the
  same user-initiated FileProvider export as the diagnostics log) — useful for handing a
  drive to a developer to debug a bad route.
- Manage it in Settings → recorded trips have **Replay**, **Share**, and **Delete**;
  turning the switch off stops new recording. Off by default; you choose to enable it.

A future, **separately-announced** opt-in may aggregate anonymized speed traces to build
Vela's own traffic layer — that one needs a server and a fresh consent screen, and this
file will change the day it ships. It does not exist today.

*Questions or something inaccurate here? Open an issue.*
