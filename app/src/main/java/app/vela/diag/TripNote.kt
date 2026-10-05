package app.vela.diag

/**
 * One door into the recorded trip for everything that is not navigation itself (2026-10-04): the
 * voice's timing, the network changing, the phone running hot or short of memory, the app leaving
 * the screen. The view model points [sink] at the trip file's `K` notes; a note added with no
 * drive recording goes nowhere. Rule for a note: what happened and how long it took, never where.
 * No coordinates, no place names, no spoken text.
 */
object TripNote {
    @Volatile var sink: ((String) -> Unit)? = null
    fun add(note: String) { runCatching { sink?.invoke(note) } }
}
