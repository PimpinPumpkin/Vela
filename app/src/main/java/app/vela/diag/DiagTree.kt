package app.vela.diag

import android.util.Log
import app.vela.core.diag.DiagLog
import timber.log.Timber

/**
 * A [Timber.Tree] that forwards WARN/ERROR logs into the opt-in [DiagLog] breadcrumb ring, so a
 * warning or error logged anywhere through Timber becomes a diagnostic breadcrumb — it then flows
 * into the crash report (CrashCatcher reads `DiagLog.snapshot()`) and the shareable JSON export
 * (DiagExporter). [DiagLog.record] is a no-op unless the user opted in, so this inherits the
 * existing privacy gate for free. Planted in ALL builds (cheap); the Logcat-facing `DebugTree` is
 * planted only in debug.
 */
class DiagTree(private val diag: DiagLog) : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val kind = if (priority >= Log.ERROR) "error" else "warn"
        val summary = if (tag != null) "$tag: $message" else message
        val detail = t?.let { "$message\n${it.stackTraceToString()}" }
        diag.record(kind, summary, detail)
    }
}
