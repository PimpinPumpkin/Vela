package app.vela.diag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * A tiny LIVE ANR detector for DEBUG builds only (zero release overhead). It posts a tick to the
 * main looper, sleeps [timeoutMs], and if the tick hasn't run the main thread was blocked that long
 * - it dumps the main-thread stack via [onAnr] (which writes a `crash-anr-*` report), then waits
 * for recovery so it reports the stall once, not every cycle.
 *
 * Complements [ExitInfoReader]: this catches ANRs LIVE and works on API 26–29 (where
 * [android.app.ApplicationExitInfo] isn't available); ExitInfoReader gets the OS's official trace
 * on API 30+ post-hoc. Start once from `VelaApp.onCreate` under `BuildConfig.DEBUG`.
 */
class AnrWatchdog(
    private val timeoutMs: Long = 5_000L,
    private val onAnr: (String) -> Unit,
) : Thread("vela-anr-watchdog") {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ticked = true

    init { isDaemon = true }

    override fun run() {
        while (!isInterrupted) {
            ticked = false
            main.post { ticked = true }
            SystemClock.sleep(timeoutMs)
            // Skip while a crash is in flight: the system crash handler blocks the main thread for
            // seconds, which is not an ANR (CrashCatcher.crashing is set before that block begins).
            if (!ticked && !isInterrupted && !CrashCatcher.crashing) {
                val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "\tat $it" }
                onAnr("Main thread blocked > $timeoutMs ms\n\n$stack")
                while (!ticked && !isInterrupted && !CrashCatcher.crashing) SystemClock.sleep(500) // wait for recovery
            }
        }
    }
}
