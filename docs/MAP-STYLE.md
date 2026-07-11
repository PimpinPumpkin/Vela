# Map style

Split out of the README. The basemap, fonts, custom layers and theming details.

The active basemap is the keyless **OpenFreeMap Liberty** style (full street
detail; we inject house-number labels at z17), loaded **by URL** - the only setup
that reliably renders vector tiles on-device. Over it we apply a Google-like look
at **runtime**, by system theme:

- **POI markers** - small category-coloured dots with white Material Icons
  glyphs (food orange, shops blue, parks green, …) in front of a muted-grey
  teardrop pin with a soft shadow (Google-style, no white ring), with the label
  to the **left** of the icon; in **light** mode the POI label text is coloured
  by category too, like Google.
- **Roads, Google-style** - white road fills on a light-grey land, with the
  casings **faded out down the hierarchy** until the minor-road casing equals the
  land, so streets are clean white lines with **no outline** (the outlines were
  what made it look un-Google); soft-yellow motorways; bridges mirror their tier.
- **Building footprints + house numbers** - flat grey footprints with a crisp
  outline from neighbourhood zoom (3D extrusions at street zoom), and OSM house
  numbers from ~z16, both keyless from the OpenFreeMap tiles. Density follows
  OpenStreetMap coverage (dense in metros, patchy in some suburbs).
- **Neutralised landuse** - the tan/yellow residential/commercial/school fills are
  flattened into the land (Google keeps these untinted), so no coloured blobs.
- **Light / dark** - a light-grey-land light palette and Google's canonical night
  palette for dark; casings blend into the land in **both** so roads stay clean.
  (Palette tuned live in a MapLibre GL JS harness against Google, then verified
  on-device in light + dark.)
- **Terrain relief (hillshade)** - shaded relief from the keyless open **terrarium**
  DEM (AWS Open Data; native fetch, no key, no CORS), added under the road layers
  and capped at z16, tuned per theme (a soft warm-grey shadow in light, deeper
  shadows + a cool highlight in dark). Verified in a MapLibre GL JS harness
  against the real DEM tiles before shipping.

A `MAPTILER_KEY` (CI secret) path stays wired but **off** (`USE_MAPTILER` in
`MapScreen`): with a key it switches to **MapTiler Streets / Streets Dark**
(proper fonts) instead of the keyless recolour. The keyless route's remaining
ceiling is the font (OpenFreeMap fixes it to Noto Sans; a bundled-Roboto attempt
broke on-device vector rendering and is parked); self-hosted PMTiles is the
no-key, no-quota path for later. Styles are plain URLs, updatable over-the-air
without an app release.

