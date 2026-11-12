package app.vela.core.diag

/**
 * Lines for the recorded drive from code that has no handle on it, the routers above all: what
 * each one answered when the drive asked for a route (`RC` lines, a `K` summary). The app points
 * [sink] at the trip file while a real drive records; with no sink, or no drive, nothing is kept.
 */
object TripLines {
    @Volatile var sink: ((tag: String, payload: String) -> Unit)? = null

    fun add(tag: String, payload: String) {
        runCatching { sink?.invoke(tag, payload) }
    }
}
