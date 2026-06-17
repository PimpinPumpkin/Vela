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
) {
    companion object {
        val DEFAULT = Calibration(
            version = 1,
            searchEndpoint = "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us",
            searchPb = SearchPb.DEFAULT_TEMPLATE,
            directionsEndpoint = "https://www.google.com/maps/preview/directions?authuser=0&hl=en&gl=us",
            directionsPb = DirectionsPb.DEFAULT_TEMPLATE,
            reviewsEndpoint = "https://www.google.com/maps/preview/review/listentitiesreviews?authuser=0&hl=en&gl=us",
            reviewsPb = "!1m2!1y{HIGH}!2y{LOW}!2m2!2i0!3i20!3e1!5m2!1svela!7e81",
            sessionWarmUrl = "https://www.google.com/maps?hl=en&gl=us",
        )
    }
}
