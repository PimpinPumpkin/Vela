package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode

/**
 * Builds the `pb` parameter for `/maps/preview/directions`.
 *
 * Captured from a live request and verified on 2026-06-15 for driving, walking
 * and cycling. Two calibration findings:
 * - travel mode is the `!1e{N}` field inside the `!20m5` block -
 * **0 = driving, 1 = cycling, 2 = walking, 3 = transit** (everything else is
 * identical between modes), and
 * - the optional `!15m3!1s<token>!7e81` session-token block was removed after
 * confirming routes still come back without it.
 *
 * Driving returns traffic-aware ETAs + alternatives; walking/cycling return a
 * single route with no traffic; transit (3) uses a different response shape the
 * current parser doesn't read, so the UI offers drive / walk / bike only.
 */
object DirectionsPb {
    // Shipped default; the live template comes from CalibrationStore and is passed
    // into [build].
    const val DEFAULT_TEMPLATE =
        "!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2" +
        "!3m12!1m3!1d24960.741896132306!2d-121.7527808!3d38.554674999999996!2m3!1f0.0!2f0.0!3f0.0" +
        "!3m2!1i1024!2i768!4f13.1!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m27!32i1" +
        "!49b1!63m0!66b1!85b1!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1" +
        "!244b1!246b1!250b1!253b1!260b1!266b1!270b1!273b1!279b1!281b1!291m0!10b1!12b1!13b1!14b1" +
        "!16b1!17m1!3e1!20m5!1e{MODE}!2e3!5e2!6b1!14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2" +
        "!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024" +
        "!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40i783!47m2!8b1!10e2"

    fun build(origin: LatLng, destination: LatLng, mode: TravelMode, template: String = DEFAULT_TEMPLATE): String {
        val modeCode = when (mode) {
            TravelMode.DRIVE -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALK -> 2
            TravelMode.TRANSIT -> 3
        }
        return template
            .replace("{OLAT}", origin.lat.toString())
            .replace("{OLNG}", origin.lng.toString())
            .replace("{DLAT}", destination.lat.toString())
            .replace("{DLNG}", destination.lng.toString())
            .replace("{MODE}", modeCode.toString())
    }
}
