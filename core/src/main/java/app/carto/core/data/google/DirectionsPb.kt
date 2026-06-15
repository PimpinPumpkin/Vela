package app.carto.core.data.google

import app.carto.core.model.LatLng

/**
 * Builds the `pb` parameter for `/maps/preview/directions`.
 *
 * This is a real template captured from a live request (driving mode), verified
 * against maps.google.com on 2026-06-15. Two findings from that capture shape it:
 *  - the optional `!15m3!1s<token>!7e81` session-token block was REMOVED after
 *    confirming directions still returns full routes without it (so no per-user
 *    token bootstrap is needed here), and
 *  - origin/destination are the plain `!1m4!3m2!3d<lat>!4d<lng>!6e2` blocks; a
 *    minimal pb without the surrounding flag/viewport blocks is rejected, so the
 *    captured skeleton is kept intact and only the coordinates are substituted.
 *
 * CALIBRATE: if directions break, recapture this skeleton (mask coords) and
 * replace TEMPLATE. The trailing `!3e` controls travel mode in Google's schema;
 * this template returns the driving routes + traffic that Carto needs today.
 */
object DirectionsPb {
    private const val TEMPLATE =
        "!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2" +
        "!3m12!1m3!1d{SPAN}!2d{CLNG}!3d{CLAT}!2m3!1f0.0!2f0.0!3f0.0!3m2!1i1024!2i768!4f13.1" +
        "!6m54!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m25!32i1!49b1!63m0!66b1!85b1" +
        "!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1!250b1" +
        "!253b1!260b1!266b1!273b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m5!1e6!2e1" +
        "!5e2!6b1!14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6" +
        "!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748" +
        "!2m2!1i1024!2i768!27b1!40i782!47m2!8b1!10e2"

    fun build(origin: LatLng, destination: LatLng): String =
        TEMPLATE
            .replace("{OLAT}", origin.lat.toString())
            .replace("{OLNG}", origin.lng.toString())
            .replace("{DLAT}", destination.lat.toString())
            .replace("{DLNG}", destination.lng.toString())
            .replace("{CLAT}", ((origin.lat + destination.lat) / 2.0).toString())
            .replace("{CLNG}", ((origin.lng + destination.lng) / 2.0).toString())
            .replace("{SPAN}", "42819")
}
