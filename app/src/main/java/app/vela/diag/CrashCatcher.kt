package app.vela.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.vela.BuildConfig
import app.vela.core.diag.DiagEvent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches an otherwise-fatal uncaught exception and **persists** a crash report —
 * stack trace, app/device versions, and whatever diagnostic breadcrumbs were in
 * memory — to a file under `filesDir/diag/crash/`, then chains to the system's
 * default handler so the normal crash flow is unchanged.
 *
 * Why this exists: when nav crashed on a phone that wasn't tethered, there was no
 * way to get the stack trace. This keeps it on disk so the user can **export the
 * crash report on the next launch** (Settings → Diagnostics) and hand it to a dev.
 *
 * The stack trace + device info are benign (no personal data), so a report is
 * written even if the opt-in diagnostics log is off — the breadcrumb section is
 * simply empty in that case (breadcrumbs are only recorded when the user opted in).
 * The report never leaves the phone unless the user exports + shares it.
 */
object CrashCatcher {

    /** True once an uncaught exception is being handled. The [AnrWatchdog] reads this to stand down:
     *  the system crash handler ([prev]) blocks the main thread for seconds while it reports the
     *  crash, which would otherwise look like an ANR and spawn a bogus `crash-anr` report. */
    @Volatile
    var crashing = false
        private set

    fun install(context: Context, breadcrumbs: () -> List<DiagEvent>) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            crashing = true // tell the ANR watchdog to stand down BEFORE the system handler blocks main
            runCatching { writeReport(app, ex, breadcrumbs()) }
            prev?.uncaughtException(thread, ex)
        }
    }

    /** The shared crash/ANR/exit-info report directory. */
    fun reportDir(context: Context) = File(context.filesDir, "diag/crash").apply { mkdirs() }

    private fun writeReport(context: Context, ex: Throwable, crumbs: List<DiagEvent>) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        write(context, "crash-${System.currentTimeMillis()}.txt", "crash report",
            "=== stack trace ===\n" + sw.toString(), crumbs)
    }

    /**
     * Write a report into the shared crash dir in the standard header format so it auto-surfaces in
     * Settings → Diagnostics ([pending] lists any `crash-*.txt`). Reused by [ExitInfoReader] (ANR /
     * native-crash / low-memory kills) and [AnrWatchdog], not just the uncaught-exception path.
     * [name] MUST start with `crash-`.
     */
    fun write(context: Context, name: String, title: String, body: String, crumbs: List<DiagEvent>) {
        val text = buildString {
            append("Vela ").append(title).append('\n')
            append("when: ").append(System.currentTimeMillis()).append('\n')
            append("version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("android: API ").append(Build.VERSION.SDK_INT)
                .append(" — ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
            append(body).append("\n\n")
            append("=== breadcrumbs (").append(crumbs.size).append(") ===\n")
            crumbs.forEach { e ->
                append(e.epochMs).append(" [").append(e.kind).append("] ").append(e.summary)
                e.detail?.let { append(" :: ").append(it) }
                append('\n')
            }
        }
        File(reportDir(context), name).writeText(text)
        prune(context)
    }

    /** Keep only the few most recent reports so this can't grow unbounded. ExitInfo/ANR reports
     *  share this budget with exception reports, so keep enough that a burst can't evict a crash. */
    private fun prune(context: Context, keep: Int = 10) {
        val files = pending(context)
        if (files.size > keep) files.dropLast(keep).forEach { runCatching { it.delete() } }
    }

    /** Crash reports on disk, oldest first. */
    fun pending(context: Context): List<File> =
        reportDir(context).listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.sortedBy { it.name } ?: emptyList()

    fun clear(context: Context) {
        pending(context).forEach { runCatching { it.delete() } }
    }

    /** Share the most recent crash report via the system sheet, or null if none. */
    fun shareIntent(context: Context): Intent? {
        val newest = pending(context).lastOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", newest)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vela crash report")
            putExtra(Intent.EXTRA_TEXT, "Attached: a Vela crash report (stack trace + diagnostics).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share crash report").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
