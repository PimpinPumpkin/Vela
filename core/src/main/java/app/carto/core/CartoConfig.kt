package app.carto.core

/**
 * Compile-time switches for the engine.
 *
 * Carto follows the NewPipe model: the device scrapes Google's public web
 * endpoints directly, per-user, with no Carto backend in the middle. The exact
 * request (`pb`) and response (positional-array) shapes are NOT hard knowledge
 * — they must be calibrated against a live capture of maps.google.com (see the
 * `CALIBRATE:` markers in `data/google/`). Until that calibration is done the
 * app runs on [app.carto.core.data.MockMapDataSource] so the entire UI —
 * search, place sheet, routing, turn-by-turn, voice — is exercisable offline.
 */
object CartoConfig {
    /** Flip to true once the Google request/response shapes are calibrated. */
    const val USE_GOOGLE_SOURCE = false

    /** Carto identifies as a normal desktop Chrome to the web endpoints. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
