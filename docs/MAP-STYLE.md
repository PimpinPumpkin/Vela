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
(proper fonts) instead of the keyless recolour. The old keyless font ceiling is
gone: map labels render in Roboto from a self-hosted glyph set (Roboto
composited over OpenFreeMap's Noto per glyph, so every non-Latin script keeps
full coverage; `ui/map/MapFonts` patches the live style's glyphs URL at launch
and falls back to plain Noto if the host is unreachable). Self-hosted PMTiles is
the no-key, no-quota path for later. Styles are plain URLs, updatable over-the-air
without an app release.


## Archived palette: the pre-pixel-sample "greens fixed" look (commit 071c6c3)

The palette that shipped between the flat-vegetation work and the final
pixel-sampled match of the Google app (2026-07-11). Kept as a rollback
reference; to restore it wholesale, take `applyLight`/`applyDark` from commit
`071c6c3` ("Map greens match Google now and are actually visible in dark
mode"). The current palette lives in CLAUDE.md and the code.

Light: land `#f2f1ee`, water `#90daee`, park `#cfeccd`, grass `#d3f8e2`,
wood `#c9f2da`, wetland `#cdeff0`, plaza `#ededed`, buildings `#dde1e7`
(outline `#c4c9d1`, extrusion same fill), minor/secondary roads white with
casings `#e4e6ea`, trunk/primary casing `#dadde2`, arterial fills yellow
`#f9d27a` / motorway `#f0b85a` (the last Google-web-style yellows; the current
palette fills roads solid blue-grey like the app).

Dark: land `#242f3e`, water `#17263c`, park/grass `#2c4a34`, wood `#274330`,
wetland `#26403c`, plaza and other-landuse `#2a3546` (landuse at opacity 0.5),
buildings `#323f54` (outline `#3f4e66`, extrusion same fill), roads
`#49536a` minor / `#5e6a85` secondary / `#6f7a96` trunk+motorway, casings
`#242f3e` (= land). Greens here are true greens; the current sampled palette
uses Google's dark teal vegetation instead.
