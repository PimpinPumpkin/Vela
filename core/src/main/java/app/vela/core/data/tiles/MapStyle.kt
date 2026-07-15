package app.vela.core.data.tiles

/**
 * Base-layer styles. Default is **OpenFreeMap Liberty** - a full detailed OSM
 * vector style (roads, labels, POIs) served free with no API key, so the map
 * looks real out of the box. Positron is the light/minimal variant. The MapLibre
 * demo style (country outlines only) and a Protomaps slot (needs a key, the
 * "Google-Maps-ify" target) are kept as options. Styles are plain URLs, so they
 * can be swapped over-the-air without an app release.
 *
 * NOTE: OpenFreeMap is a free community service - fine for now, but self-host
 * tiles (or Protomaps PMTiles) before any real release.
 */
enum class MapStyle(val label: String, val uri: String) {
    // The keyless basemap: OpenFreeMap Liberty loaded from its remote URL - the
    // setup that always rendered on-device. POI markers + colours are applied at
    // runtime (see VelaMapView.applyMapTheme / PoiIcons.applyToLiberty). A bundled
    // copy was tried for a Roboto font, but its vector tiles wouldn't load via
    // fromJson on-device, so that approach was dropped.
    LIBERTY("OpenFreeMap Liberty", "https://tiles.openfreemap.org/styles/liberty");

    companion object {
        val DEFAULT = LIBERTY
    }
}
