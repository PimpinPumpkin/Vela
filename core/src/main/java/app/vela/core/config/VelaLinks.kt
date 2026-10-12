package app.vela.core.config

/**
 * Every address the app has of its own project: the repository, its API, the download
 * releases, the raw files on main, the site, the donate page. Compiled defaults live here and
 * nowhere else; a signed `calibration.json` can replace any of them through its `links` object,
 * so a renamed account, a moved repository or a new site reaches installed apps without a
 * release. A build can point a data file at a local server (`-PobfManifestUrl=` and the like),
 * which wins over the default and loses to a pushed link.
 *
 * Most keys derive from a base ([DERIVED]): a file on the releases, a page on the repository or
 * the site. Pushing the base alone moves all of them; pushing one key moves that one.
 *
 * Moving house: push a bundle whose `links` name the new places while the old ones still
 * answer. Every app that fetches it keeps the bundle and applies it at the next launch, so it
 * asks the new places from then on, the settings file included. Then change the defaults here
 * for the next release.
 */
object VelaLinks {
    const val REPO = "repo"
    const val API = "api"
    const val RELEASES = "releases"
    const val RAW = "raw"
    const val SITE = "site"
    const val DONATE = "donate"
    const val PRIVACY = "privacy"
    const val CALIBRATION = "calibration"
    const val OBF_MANIFEST = "obfManifest"
    const val OVERLAY_MANIFEST = "overlayManifest"
    const val MAXSPEED_MANIFEST = "maxspeedManifest"
    const val ADDRESS_MANIFEST = "addressManifest"
    const val PLACES_MANIFEST = "placesManifest"
    const val CELLS_MANIFEST = "cellsManifest"
    const val BASEMAP_MANIFEST = "basemapManifest"
    const val WORLD_BASEMAP = "worldBasemap"
    const val POI_PACK_MANIFEST = "poiPackManifest"
    const val FLOCK_MANIFEST = "flockManifest"
    const val ROAD_FEATURES_MANIFEST = "roadFeaturesManifest"
    const val MAP_FONTS = "mapFonts"
    const val MAP_FONTS_PACK = "mapFontsPack"
    const val ASR_MODELS = "asrModels"
    const val FUEL_GB = "fuelGb"
    const val PAINTED_ROADS = "paintedRoads"

    private val DEFAULTS: Map<String, String> = mapOf(
        REPO to "https://github.com/PimpinPumpkin/Vela",
        API to "https://api.github.com/repos/PimpinPumpkin/Vela",
        RELEASES to "https://github.com/PimpinPumpkin/Vela/releases/download",
        RAW to "https://raw.githubusercontent.com/PimpinPumpkin/Vela/main",
        SITE to "https://pimpinpumpkin.github.io/Vela",
        DONATE to "https://buymeacoffee.com/PimpinPumpkin",
    )

    /** Key to (base key, path appended to the base). */
    private val DERIVED: Map<String, Pair<String, String>> = mapOf(
        PRIVACY to (REPO to "/blob/main/PRIVACY.md"),
        CALIBRATION to (RAW to "/calibration.json"),
        OBF_MANIFEST to (RELEASES to "/obf-regions/obf-manifest.json"),
        OVERLAY_MANIFEST to (RELEASES to "/building-overlays/building-overlay-manifest.json"),
        MAXSPEED_MANIFEST to (RELEASES to "/maxspeed-overlays/maxspeed-overlay-manifest.json"),
        ADDRESS_MANIFEST to (RELEASES to "/address-overlays/address-overlay-manifest.json"),
        PLACES_MANIFEST to (RELEASES to "/places-overlays/places-overlay-manifest.json"),
        CELLS_MANIFEST to (RELEASES to "/grid-cells/cells-manifest.json"),
        BASEMAP_MANIFEST to (RELEASES to "/basemap-tiles/basemap-manifest.json"),
        WORLD_BASEMAP to (RELEASES to "/basemap-tiles/basemap-world.pmtiles"),
        POI_PACK_MANIFEST to (RELEASES to "/poi-packs/poi-pack-manifest.json"),
        FLOCK_MANIFEST to (RELEASES to "/flock-cameras/flock-manifest.json"),
        ROAD_FEATURES_MANIFEST to (RELEASES to "/road-features/road-features-manifest.json"),
        MAP_FONTS to (SITE to "/fonts"),
        MAP_FONTS_PACK to (RELEASES to "/map-fonts/map-fonts.zip"),
        ASR_MODELS to (RELEASES to "/asr-models"),
        FUEL_GB to (RELEASES to "/fuel-gb/"),
        PAINTED_ROADS to (RELEASES to "/painted-roads/painted-california.pmtiles?v=3"),
    )

    /** The signed bundle's `links`, applied by `CalibrationStore` at launch and on every accepted bundle. */
    @Volatile var overrides: Map<String, String> = emptyMap()

    /** A build's own links (a local test server for a data file), set by the app at start. */
    @Volatile var compiled: Map<String, String> = emptyMap()

    val keys: Set<String> get() = DEFAULTS.keys + DERIVED.keys

    /** The link for [key]: pushed, else the build's own, else derived from its base, else the default. */
    fun get(key: String): String =
        overrides[key]?.takeIf { it.isNotBlank() }
            ?: compiled[key]?.takeIf { it.isNotBlank() }
            ?: DERIVED[key]?.let { (base, path) -> get(base) + path }
            ?: DEFAULTS[key]
            ?: throw IllegalArgumentException("no link named $key")

    /** The compiled default for [key], what the app ships with. */
    fun default(key: String): String =
        DERIVED[key]?.let { (base, path) -> default(base) + path } ?: DEFAULTS[key] ?: throw IllegalArgumentException("no link named $key")

    /** A bundle may set only https links, and only names this build knows. */
    fun acceptable(links: Map<String, String>): Boolean =
        links.all { (k, v) -> k in keys && v.startsWith("https://") && v.none { it.isWhitespace() } }

    fun repo(): String = get(REPO)
    fun api(path: String): String = get(API) + path
    fun release(tag: String, file: String): String = "${get(RELEASES)}/$tag/$file"
    fun raw(path: String): String = get(RAW) + path
    fun site(): String = get(SITE)
    fun donate(): String = get(DONATE)
    fun privacy(): String = get(PRIVACY)
    fun calibration(): String = get(CALIBRATION)
    fun calibrationSig(): String = calibration() + ".sig"
    fun obfManifest(): String = get(OBF_MANIFEST)
    fun overlayManifest(): String = get(OVERLAY_MANIFEST)
    fun maxspeedManifest(): String = get(MAXSPEED_MANIFEST)
    fun addressManifest(): String = get(ADDRESS_MANIFEST)
    fun placesManifest(): String = get(PLACES_MANIFEST)
    fun cellsManifest(): String = get(CELLS_MANIFEST)
    fun basemapManifest(): String = get(BASEMAP_MANIFEST)
    fun worldBasemap(): String = get(WORLD_BASEMAP)
    fun poiPackManifest(): String = get(POI_PACK_MANIFEST)
    fun flockManifest(): String = get(FLOCK_MANIFEST)
    fun roadFeaturesManifest(): String = get(ROAD_FEATURES_MANIFEST)
    fun mapFonts(): String = get(MAP_FONTS)
    fun mapFontsPack(): String = get(MAP_FONTS_PACK)
    fun asrModels(): String = get(ASR_MODELS)
    fun fuelGb(): String = get(FUEL_GB)
    fun paintedRoads(): String = get(PAINTED_ROADS)
}
