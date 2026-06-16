package app.vela.core

/**
 * Compile-time switches for the engine.
 *
 * Vela follows the NewPipe model: the device scrapes Google's public web
 * endpoints directly, per-user, with no Vela backend in the middle. The exact
 * request (`pb`) and response (positional-array) shapes are NOT hard knowledge
 * — they must be calibrated against a live capture of maps.google.com (see the
 * `CALIBRATE:` markers in `data/google/`). Search and directions were calibrated
 * on 2026-06-15 and now return real data; [app.vela.core.data.MockMapDataSource]
 * remains as an offline fallback so the whole UI still runs with no network.
 */
object VelaConfig {
    /**
     * Search + directions are calibrated and live, so the real source is on by
     * default. Set to false to fall back to the mock for offline demos.
     */
    const val USE_GOOGLE_SOURCE = true

    /** Vela identifies as a normal desktop Chrome to the web endpoints. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
