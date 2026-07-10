package app.vela.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.vela.core.diag.DiagLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the OS's retained process-exit history ([ApplicationExitInfo], API 30+) on launch and folds
 * ANRs, native crashes, low-memory and SIGKILL exits into the SAME crash-report dir [CrashCatcher]
 * uses - so they auto-surface in Settings → Diagnostics with zero new UI. This closes the gap the
 * uncaught-exception handler can't reach: ANRs (the process is stuck, not throwing) and native
 * SIGSEGV/SIGABRT (MapLibre / sherpa-onnx). For a `REASON_ANR`, the OS trace ([getTraceInputStream])
 * is the real main-thread stack at the stall - the single highest-value artifact on a degoogled
 * device with no Play/Crashlytics.
 *
 * Post-hoc + next-launch by nature (you see it after the process died) and API 30+ only (minSdk is
 * 26). Deduped against a persisted last-seen timestamp so re-harvest is idempotent. [harvest] reads
 * the trace from disk - call it OFF the main thread.
 */
@Singleton
class ExitInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diag: DiagLog,
) {
    fun harvest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { harvestR() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun harvestR() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 0) // most-recent first
        var newest = lastSeen
        for (info in reasons) {
            if (info.timestamp <= lastSeen) break // already folded in on a previous launch
            newest = maxOf(newest, info.timestamp)
            when (info.reason) {
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_LOW_MEMORY -> writeReport(info)
            }
        }
        if (newest > lastSeen) prefs.edit().putLong(KEY_LAST, newest).apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeReport(info: ApplicationExitInfo) {
        val reason = reasonName(info.reason)
        val body = buildString {
            append("=== process exit: ").append(reason).append(" ===\n")
            append("when: ").append(info.timestamp).append('\n')
            append("description: ").append(info.description ?: "-").append('\n')
            append("importance: ").append(info.importance).append('\n')
            append("pss: ").append(info.pss).append(" kB, rss: ").append(info.rss).append(" kB")
            if (info.reason == ApplicationExitInfo.REASON_ANR) {
                append("\n\n=== ANR trace (main-thread stack at the stall) ===\n")
                val trace = runCatching {
                    info.traceInputStream?.bufferedReader()?.use(BufferedReader::readText)
                }.getOrNull()
                append(trace ?: "(no trace available)")
            }
        }
        // filename starts with `crash-` so CrashCatcher.pending() lists it and the existing Settings
        // row exports it; info.timestamp makes the write idempotent even if the pref write is lost.
        CrashCatcher.write(context, "crash-exit-${info.timestamp}.txt", "process-exit report", body, diag.snapshot())
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        else -> "reason $reason"
    }

    private companion object {
        const val PREFS = "vela_settings"
        const val KEY_LAST = "last_exit_ts"
    }
}
