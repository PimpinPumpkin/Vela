package app.vela.core.data.tiles

/**
 * The basemap looks: OpenFreeMap's style files, all on the same keyless OpenMapTiles tiles, the
 * same glyph server and the same sprite. [LIBERTY] is the Vela look: the app paints its own
 * palette, road widths, icons and extra fills over Liberty at runtime (`VelaMapView.applyMapTheme`,
 * `ensureLayers`). The other looks load as OpenFreeMap serves them; only the passes that do not
 * depend on a look (label language, house numbers, Vela's own layers) run on them.
 *
 * Styles are plain URLs, so a look can change over the air without an app release.
 */
enum class MapStyle(
    val key: String,
    val label: String,
    val uri: String,
    /** Vela paints this look (palettes, road widths, icons, twin fills); the others keep their own. */
    val painted: Boolean,
    /** A dark look as served: Vela's layers over it take the dark colors whatever the app theme. */
    val darkLook: Boolean,
) {
    LIBERTY("liberty", "OpenFreeMap Liberty", "https://tiles.openfreemap.org/styles/liberty", painted = true, darkLook = false),
    BRIGHT("bright", "OpenFreeMap Bright", "https://tiles.openfreemap.org/styles/bright", painted = false, darkLook = false),
    POSITRON("positron", "OpenFreeMap Positron", "https://tiles.openfreemap.org/styles/positron", painted = false, darkLook = false),
    FIORD("fiord", "OpenFreeMap Fiord", "https://tiles.openfreemap.org/styles/fiord", painted = false, darkLook = true),
    DARK("dark", "OpenFreeMap Dark", "https://tiles.openfreemap.org/styles/dark", painted = false, darkLook = true);

    companion object {
        val DEFAULT = LIBERTY

        fun fromKey(key: String?): MapStyle? = entries.firstOrNull { it.key == key }

        /** The look whose live style URL this is, or null for any other URI. */
        fun byUri(uri: String): MapStyle? = entries.firstOrNull { it.uri == uri }
    }
}

/**
 * Google's stable raster XYZ endpoint (mt0..mt3). Included for testing/parity
 * only: using it ships a Google-look map AND puts tile load back on Google,
 * both of which Vela deliberately avoids by using open tiles. lyrs: m=roads,
 * s=satellite, y=hybrid, t=terrain, h=transparent roads overlay.
 */
object GoogleRasterTiles {
    fun tiles(layers: String = "m"): List<String> =
        (0..3).map { "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}" }
}
