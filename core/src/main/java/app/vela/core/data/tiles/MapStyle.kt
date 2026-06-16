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
    LIBERTY("OpenFreeMap Liberty", "https://tiles.openfreemap.org/styles/liberty"),
    POSITRON("OpenFreeMap Positron", "https://tiles.openfreemap.org/styles/positron"),
    BRIGHT("OpenFreeMap Bright", "https://tiles.openfreemap.org/styles/bright"),
    DEMO("MapLibre Demo (outline only)", "https://demotiles.maplibre.org/style.json"),
    PROTOMAPS_LIGHT("Protomaps (needs key)", "https://api.protomaps.com/styles/v4/light/en.json?key=YOUR_PROTOMAPS_KEY");

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
