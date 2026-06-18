package app.vela.core.config

/**
 * A user-facing notice pushed through the signed calibration channel — so we can
 * tell users "search is temporarily down, a fix is on the way" without shipping an
 * APK. Dismissal is tracked per [id] on the device.
 */
data class Notice(
    val id: String,
    val level: String = LEVEL_INFO, // info | warn | error  → UI tone
    val title: String,
    val body: String,
    val url: String? = null, // optional "Learn more" link
) {
    companion object {
        const val LEVEL_INFO = "info"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"
    }
}
