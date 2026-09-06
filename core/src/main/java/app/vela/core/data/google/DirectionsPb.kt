package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode

/**
 * Builds the `pb` parameter for `/maps/preview/directions`.
 *
 * Captured from a live request and verified on 2026-06-15 for driving, walking
 * and cycling. Two calibration findings:
 *  - travel mode is the `!1e{N}` field inside the `!20m5` block —
 *    **0 = driving, 1 = cycling, 2 = walking, 3 = transit** (everything else is
 *    identical between modes), and
 *  - the optional `!15m3!1s<token>!7e81` session-token block was removed after
 *    confirming routes still come back without it.
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

    fun build(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        template: String = DEFAULT_TEMPLATE,
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
    ): String {
        val modeCode = when (mode) {
            TravelMode.DRIVE -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALK -> 2
            TravelMode.TRANSIT -> 3
        }
        return withAvoid(template, avoidTolls && mode == TravelMode.DRIVE, avoidHighways && mode == TravelMode.DRIVE)
            .replace("{OLAT}", origin.lat.toString())
            .replace("{OLNG}", origin.lng.toString())
            .replace("{DLAT}", destination.lat.toString())
            .replace("{DLNG}", destination.lng.toString())
            .replace("{MODE}", modeCode.toString())
    }

    /** The avoid flags Google's own web client sends (captured from its `/maps/preview/directions`
     *  request with "Avoid highways" ticked, 2026-09-06, and verified from a plain HTTP client:
     *  Davis to Sacramento flips from I-80 15.3 mi / 21 min to Old River Rd 27.1 mi / 46 min;
     *  Naperville to O'Hare with tolls avoided leaves the I-88/I-294 tollway for I-55/I-90). They
     *  are NOT in the `!20m` route-options group (every scalar there was probed with no effect)
     *  but in the `!6m` feature block's `!2m` submessage: `!1b1` = avoid highways, `!2b1` = avoid
     *  tolls, the same field numbers the web `dir/` URL's `!2m1!1b1` carries. Counts on the
     *  enclosing `!6m` and `!2m` groups grow by the number of flags added. Done by pattern so a
     *  recalibrated template (CalibrationStore) keeps working as long as that block survives. */
    internal fun withAvoid(template: String, avoidTolls: Boolean, avoidHighways: Boolean): String {
        if (!avoidTolls && !avoidHighways) return template
        val flags = buildString {
            if (avoidHighways) append("!1b1")
            if (avoidTolls) append("!2b1")
        }
        val added = flags.count { it == '!' }
        val re = Regex("""!6m(\d+)(!1m5!18b1!30b1!31m1!1b1!34e1!2m)(\d+)""")
        val m = re.find(template) ?: return template
        val outer = m.groupValues[1].toInt() + added
        val inner = m.groupValues[3].toInt() + added
        return template.replaceRange(m.range, "!6m$outer${m.groupValues[2]}$inner$flags")
    }
}
