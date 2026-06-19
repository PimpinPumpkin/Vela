package app.vela.core.config

import app.vela.core.data.google.DirectionsPb
import app.vela.core.data.google.SearchPb

/**
 * Remotely-updatable scraper calibration — the brittle bits that drift when
 * Google reshapes things: the `pb` templates and the endpoint URLs. Ships as
 * [DEFAULT]; [CalibrationStore] fetches a newer version from the public repo at
 * runtime so a fix lands without an app update ("push out the scraping").
 *
 * Phase 1 covers pb templates + endpoints. The positional field-index paths the
 * parsers read (`[1][39]`, `[1][10]`, …) are still compiled in — externalising
 * those is a later phase. A change that needs genuinely new parsing *logic*
 * still needs an app release; this only fixes path/pb/endpoint drift.
 */
data class Calibration(
    val version: Int,
    val searchEndpoint: String,
    val searchPb: String,
    val directionsEndpoint: String,
    val directionsPb: String,
    val reviewsEndpoint: String,
    val reviewsPb: String,
    val sessionWarmUrl: String,
    // Full place gallery: the batchexecute `hspqX` (/MapsPhotoService.ListEntityPhotos)
    // POST — keyless (no `at` token, just the warmed session cookies). `{FID}` is the
    // place feature id, `{COUNT}` the page size. Returns ~40+ photos vs the search
    // preview's ~10. (Calibrated live 2026-06-17.)
    val photosEndpoint: String = DEFAULT_PHOTOS_ENDPOINT,
    val photosProto: String = DEFAULT_PHOTOS_PROTO,
    // Phase 2: the positional field-index paths the search parser reads. A missing
    // key falls back to [DEFAULT_PATHS], so a remote file can override just the one
    // that drifted. Paths are relative to a result entry (whose place node is [1]),
    // except `results`/`single` which are relative to the response root.
    val paths: Map<String, List<Int>> = DEFAULT_PATHS,
    // Phase 3: user-facing notices pushed through the same signed channel (alerts
    // like "search is down, fix coming"), and an optional JavaScript bundle of
    // parse-transform overrides ([transformsJs]) run in a sandbox when a response
    // reshape needs new *logic*, not just a moved field — compiled Kotlin is the
    // fallback. Both arrive only on a signature-verified bundle.
    val notices: List<Notice> = emptyList(),
    val transformsJs: String? = null,
) {
    companion object {
        const val DEFAULT_PHOTOS_ENDPOINT =
            "https://www.google.com/maps/_/MapsWizUi/data/batchexecute?rpcids=hspqX&source-path=/maps&hl=en&_reqid=1&rt=c"

        // hspqX request proto: feature id at [2][0], page size at [4][2][1].
        const val DEFAULT_PHOTOS_PROTO =
            "[2,null,[\"{FID}\",null,null,null,null,null,null,null,0],null," +
                "[null,[1200,1000],[null,{COUNT},null,null,1],null,null,null," +
                "[[[1,0,3],[2,1,2],[2,0,3],[8,0,3],[10,0,3],[10,1,2],[10,0,4],[9,1,2]],1],null,0]," +
                "null,null,null,null,null,null,null,null,null,null,[null,1,null,1]]"

        val DEFAULT_PATHS: Map<String, List<Int>> = mapOf(
            "results" to listOf(64),
            "single" to listOf(0, 1, 0, 14),
            // "At this place": the business(es) Google lists at a searched ADDRESS
            // (a sub-field of the single geocoded node). Lets an address snap to the
            // business there. Each entry's place node is at [i][0].
            "atThisPlace" to listOf(0, 1, 0, 14, 68),
            "name" to listOf(1, 11),
            "lat" to listOf(1, 9, 2),
            "lng" to listOf(1, 9, 3),
            "address" to listOf(1, 39),
            "addressComponents" to listOf(1, 2),
            "category" to listOf(1, 13, 0),
            "rating" to listOf(1, 4, 7),
            "reviewCount" to listOf(1, 4, 8),
            "priceText" to listOf(1, 4, 2),
            "website" to listOf(1, 7, 0),
            "phone" to listOf(1, 178, 0, 0),
            "featureId" to listOf(1, 10),
            "placeId" to listOf(1, 78),
            "photos" to listOf(1, 105, 0, 1, 0),
            "featuredReview" to listOf(1, 142, 1, 0, 1, 0, 0),
            "about" to listOf(1, 100, 1),
            "openStatus" to listOf(1, 203, 1, 8, 0),
            "statusRich" to listOf(1, 203, 1, 4, 0),
            "status118" to listOf(1, 118, 0, 3, 1, 4, 0),
            "hours203" to listOf(1, 203, 0),
            "hours118" to listOf(1, 118, 0, 3, 0),
            // Popular-times histogram: [84][0] = 7 days, each [d][0]=day-of-week,
            // [d][1]=hourly [hour, occupancy%, …]. Relative to the place node [1].
            "popularTimes" to listOf(1, 84),
        )

        val DEFAULT = Calibration(
            version = 1,
            searchEndpoint = "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us",
            searchPb = SearchPb.DEFAULT_TEMPLATE,
            directionsEndpoint = "https://www.google.com/maps/preview/directions?authuser=0&hl=en&gl=us",
            directionsPb = DirectionsPb.DEFAULT_TEMPLATE,
            reviewsEndpoint = "https://www.google.com/maps/preview/review/listentitiesreviews?authuser=0&hl=en&gl=us",
            reviewsPb = "!1m2!1y{HIGH}!2y{LOW}!2m2!2i0!3i20!3e1!5m2!1svela!7e81",
            sessionWarmUrl = "https://www.google.com/maps?hl=en&gl=us",
            photosEndpoint = DEFAULT_PHOTOS_ENDPOINT,
            photosProto = DEFAULT_PHOTOS_PROTO,
            paths = DEFAULT_PATHS,
        )
    }
}
