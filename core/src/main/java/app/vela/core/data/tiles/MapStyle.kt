package app.vela.core.data.tiles

/**
 * Base-layer styles. Default is the MapLibre demo style — free, keyless, always
 * resolves — so the map renders out of the box. [PROTOMAPS_LIGHT]/[_DARK] are
 * the real target from planning: point them at a hosted Protomaps style (needs
 * a key) or a self-hosted PMTiles archive + style JSON, then make one default
 * and apply the "Google-Maps-ify" diff. Keeping styles as plain URLs means they
 * can be updated over-the-air without an app release.
 */
enum class MapStyle(val label: String, val uri: String) {
    DEMO("MapLibre Demo", "https://demotiles.maplibre.org/style.json"),
    PROTOMAPS_LIGHT("Protomaps Light", "https://api.protomaps.com/styles/v4/light/en.json?key=YOUR_PROTOMAPS_KEY"),
    PROTOMAPS_DARK("Protomaps Dark", "https://api.protomaps.com/styles/v4/dark/en.json?key=YOUR_PROTOMAPS_KEY");

    companion object {
        val DEFAULT = DEMO
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
