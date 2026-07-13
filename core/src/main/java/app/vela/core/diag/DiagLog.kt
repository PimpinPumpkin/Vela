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
    // The bug being reported usually KILLED or preceded a restart of the process, so an in-memory-only
    // ring exported as "(nothing recorded)" almost every time - the reason the share feature read as
    // non-functional (user 2026-07-13). Persist the ring to a bounded JSONL file: appended per event,
    // reloaded at init, deleted the moment the user opts out. Still local-only, still opt-in.
    private val file = java.io.File(context.filesDir, "diag_log.jsonl")
    private var appendedSinceTrim = 0

    @Volatile
    private var enabled: Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    init {
        if (enabled) runCatching {
            if (file.exists()) file.readLines().takeLast(CAP).forEach { line ->
                decode(line)?.let { ring.addLast(it) }
            }
        }
    }

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
            runCatching {
                file.appendText(encode(ev) + "\n")
                // Bound the file: once it holds twice the ring, rewrite it as just the current ring.
                if (++appendedSinceTrim >= CAP) {
                    appendedSinceTrim = 0
                    file.writeText(ring.joinToString("\n", postfix = "\n") { encode(it) })
                }
            }
        }
    }

    /** A copy of the current breadcrumbs, oldest first. */
    fun snapshot(): List<DiagEvent> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) {
        ring.clear()
        runCatching { file.delete() }
    }

    // One event per line, tab-separated with escaped newlines/tabs - trivially greppable and no
    // serialization dependency (the :app module reads these back through snapshot(), never the file).
    private fun encode(e: DiagEvent): String =
        listOf(e.epochMs.toString(), e.kind, esc(e.summary), e.detail?.let { esc(it) } ?: "").joinToString("\t")

    private fun decode(line: String): DiagEvent? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val t = parts[0].toLongOrNull() ?: return null
        return DiagEvent(t, parts[1], unesc(parts[2]), parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { unesc(it) })
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    private companion object {
        const val PREFS = "vela_settings"
        const val KEY = "diag_enabled"
        const val CAP = 300
        const val DETAIL_CAP = 2000
    }
}
