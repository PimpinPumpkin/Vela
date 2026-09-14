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

    /**
     * COMPILED FALLBACK ONLY — the live value is `Calibration.userAgent`, pushed through the
     * signed bundle (Chrome ships every ~4 weeks, so a constant is stale again next month by
     * construction; this one sat at Chrome 124 ≈ April 2024 well into 2026). Read it through
     * `CalibrationStore.current().userAgent`, never directly, on any Google-facing request.
     * Kept in sync with [SEC_CH_UA] — the hint's major version must match the UA's.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    /** Client-hint brand list matching [USER_AGENT]'s major version. Pushed alongside it. */
    const val SEC_CH_UA =
        "\"Chromium\";v=\"154\", \"Google Chrome\";v=\"154\", \"Not/A)Brand\";v=\"24\""

    /**
     * HONEST identifier for COMMUNITY services (FOSSGIS OSRM, Nominatim, Photon, Overpass) — never
     * the Chrome string. Their usage policies ask for a contactable UA so they can reach an abusive
     * client instead of blanket-blocking, and they are free infrastructure Vela depends on. Sending
     * them a spoofed browser UA is both impolite and self-defeating: FOSSGIS already "transiently
     * 5xx/429/resets on mobile" (see RouteGeometry), and being an anonymous Chrome in their logs is
     * the opposite of what earns headroom there. Google-facing requests are the ONLY place the
     * browser UA belongs.
     */
    const val VELA_UA = "VelaMaps/0.4 (+https://github.com/PimpinPumpkin/Vela)"
}
