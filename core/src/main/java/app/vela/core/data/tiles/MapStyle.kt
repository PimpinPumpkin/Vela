package app.vela.core.data.tiles

/**
 * Base-layer styles. Default is **OpenFreeMap Liberty** — a full detailed OSM
 * vector style (roads, labels, POIs) served free with no API key, so the map
 * looks real out of the box. Positron is the light/minimal variant. The MapLibre
 * demo style (country outlines only) and a Protomaps slot (needs a key, the
 * "Google-Maps-ify" target) are kept as options. Styles are plain URLs, so they
 * can be swapped over-the-air without an app release.
 *
 * NOTE: OpenFreeMap is a free community service — fine for now, but self-host
 * tiles (or Protomaps PMTiles) before any real release.
 */
enum class MapStyle(val label: String, val uri: String) {
    // The active basemap: a bundled copy of OpenFreeMap Liberty re-pointed at
    // Roboto glyphs + Google-style POI icons, with the OpenMapTiles vector source
    // pinned to OpenFreeMap's current *versioned* tile path (the un-versioned path
    // serves empty tiles — that was the blank-map bug). Tiles/sprite stay remote,
    // keyless. Other styles were removed: the demo style is outlines-only, and
    // Positron/Bright/Protomaps weren't wired through the versioned-tile fix.
    LIBERTY("OpenFreeMap Liberty", "asset://styles/liberty-roboto.json");

    companion object {
        val DEFAULT = LIBERTY
    }
}

/**
 * Google's stable raster XYZ endpoint (mt0..mt3). Included for testing/parity
 * only — using it ships a Google-look map AND puts tile load back on Google,
 * both of which Vela deliberately avoids by using open tiles. lyrs: m=roads,
 * s=satellite, y=hybrid, t=terrain, h=transparent roads overlay.
 */
object GoogleRasterTiles {
    fun tiles(layers: String = "m"): List<String> =
        (0..3).map { "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}" }
}
