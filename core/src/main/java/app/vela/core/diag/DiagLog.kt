package app.vela.core.diag

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in, **local-only** diagnostics log — the no-backend half of the telemetry
 * plan (see ROADMAP "Opt-in telemetry"). It records a bounded ring of
 * [DiagEvent] breadcrumbs (searches, route computations, parser drift, nav
 * sessions, errors) **only while the user has switched it on**, so that when
 * something misbehaves — a wrong route, a bad ETA, a "needs recalibration"
 * notice — the user can **export the session and hand it to a dev** (see
 * `app/diag/DiagExporter`) instead of trying to describe the bug.
 *
 * Privacy by construction:
 * - **Off by default**; nothing is recorded until [setEnabled]`(true)`.
 * - **Nothing is ever uploaded** here — the user explicitly exports + shares the
 *   bundle themselves (full control over where it goes). Crowd-sourced upload
 *   (the Vela traffic layer) is a separate, later, backend-gated phase.
 * - Turning it **off clears the buffer** immediately.
 * - In-memory only (capped), so it dies with the process — no silent on-disk trail.
 */
@Singleton
class DiagLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val ring = ArrayDeque<DiagEvent>(CAP)

    @Volatile
    private var enabled: Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        if (!value) clear() // opt-out drops anything already collected
    }

    /** Append a breadcrumb (no-op unless the user opted in). Cheap + thread-safe;
     *  safe to call from any data path. [detail] should stay small + shareable. */
    fun record(kind: String, summary: String, detail: String? = null) {
        if (!enabled) return
        val ev = DiagEvent(System.currentTimeMillis(), kind, summary, detail?.take(DETAIL_CAP))
        synchronized(lock) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(ev)
        }
    }

    /** A copy of the current breadcrumbs, oldest first. */
    fun snapshot(): List<DiagEvent> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) { ring.clear() }

    private companion object {
        const val PREFS = "vela_settings"
        const val KEY = "diag_enabled"
        const val CAP = 300
        const val DETAIL_CAP = 2000
    }
}
