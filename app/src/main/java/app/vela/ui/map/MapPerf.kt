package app.vela.ui.map

/**
 * What the map was doing, second by second, for a recorded trip (2026-10-04, after a "one frame a
 * second while I looked around the map mid-drive" report that could not be reproduced at a desk).
 * The map view writes plain counters here from its frame callback and nav ticker; the view model
 * calls [sample] once a second during a drive and writes what comes back into the trip as a `K`
 * note. A bad second is described in full; a good one says nothing. No coordinates, ever: K notes
 * pass the trip scrubber untouched.
 *
 * Plain volatile fields, written on the main and render-callback threads and read from a ticker:
 * a torn read costs one slightly wrong line in a diagnostic.
 */
object MapPerf {
    @Volatile private var mapFrames = 0
    @Volatile private var loadingFrames = 0
    @Volatile private var uiWorstMs = 0
    @Volatile private var gestures = 0
    @Volatile var zoom = 0.0
    @Volatile var tilt = 0.0
    @Volatile var following = true
    @Volatile var symbolsHidden = false
    private val slow = ArrayList<String>()
    private var lastFollowing = true
    private var lastZoomNoted = -1.0
    private var lastNoteMs = 0L

    /** One map frame drawn; [fully] false = tiles or symbols still arriving. */
    fun mapFrame(fully: Boolean) { mapFrames++; if (!fully) loadingFrames++ }

    /** The gap before this UI frame of the nav ticker. The ticker's own parked sleep is not a stall. */
    fun uiFrame(dtMs: Int, parked: Boolean) { if (!parked && dtMs > uiWorstMs) uiWorstMs = dtMs }

    fun gesture() { gestures++ }

    /** A pass that held the main thread for [ms]: named in the next bad second's note. */
    fun slowPass(what: String, ms: Long) = synchronized(slow) { if (slow.size < 6) slow += "$what ${ms} ms" }

    /**
     * The last second, as a trip note, or null when nothing is worth writing. Written when the UI
     * thread stalled ([STALL_MS]), when the map drew under [LOW_FPS] frames while a finger was
     * moving it, and when the camera left or rejoined the car.
     */
    fun sample(nowMs: Long): String? {
        val frames = mapFrames; mapFrames = 0
        val loading = loadingFrames; loadingFrames = 0
        val worst = uiWorstMs; uiWorstMs = 0
        val g = gestures; gestures = 0
        val passes = synchronized(slow) { slow.toList().also { slow.clear() } }
        val state = "zoom %.1f tilt %.0f, %s%s".format(zoom, tilt, if (following) "following" else "free camera", if (symbolsHidden) ", symbols hidden" else "")
        val bad = worst >= STALL_MS || (g > 0 && frames < LOW_FPS) || passes.isNotEmpty()
        if (bad && nowMs - lastNoteMs >= MIN_GAP_MS) {
            lastNoteMs = nowMs; lastFollowing = following; lastZoomNoted = zoom
            return "perf: map $frames fps" + (if (loading > 0) " ($loading still loading)" else "") + ", longest stall $worst ms, " +
                (if (g > 0) "finger on the map, " else "") + state + if (passes.isEmpty()) "" else "; slow: " + passes.joinToString(", ")
        }
        // The camera leaving the car, coming back, or a free camera changing zoom by a level: the
        // context a later bad second is read against.
        if (following != lastFollowing || (!following && kotlin.math.abs(zoom - lastZoomNoted) >= 1.0)) {
            lastFollowing = following; lastZoomNoted = zoom
            return "camera: $state"
        }
        return null
    }

    fun reset() { sample(0L); lastFollowing = true; lastZoomNoted = -1.0; lastNoteMs = 0L }

    const val STALL_MS = 250
    const val LOW_FPS = 20
    const val MIN_GAP_MS = 2_000L
}
