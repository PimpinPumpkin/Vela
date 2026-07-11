package app.vela.core.config

/**
 * A user-facing notice pushed through the signed calibration channel — so we can
 * tell users "search is temporarily down, a fix is on the way" without shipping an
 * APK. Dismissal is tracked per [id] on the device.
 */
data class Notice(
    val id: String,
    val level: String = LEVEL_INFO, // info | warn | error | urgent  → UI tone (urgent = modal dialog)
    val title: String,
    val body: String,
    val url: String? = null, // optional "Learn more" link
) {
    companion object {
        const val LEVEL_INFO = "info"
        /** Renders as a MODAL DIALOG instead of a map card — for pushed announcements that must
         *  be seen (a "servers are overloaded" or "Google broke X, fix incoming" note). */
        const val LEVEL_URGENT = "urgent"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"
    }
}
