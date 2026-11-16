package app.vela.ui.map

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One request for what the map shows, asked again as the view moves, that a moving map cannot
 * starve. [ask] waits for the view to hold still and starts that wait again on the next view, but
 * a request already out is left to land: an [ask] meanwhile is dropped, and the caller's `landed`
 * runs once a reply is in, with the wire free, to ask for a view that reply no longer covers.
 * While the map follows a moving fix the view comes once a second (`IDLE_WORK_GAP_MS`), and a
 * request canceled for each new view never landed when its reply took longer than that.
 *
 * A request out longer than [WIRE_DEADLINE_MS] no longer holds the next view back: a socket read
 * that never returns outlives a cancel, and waiting on it would hold the layer for good. The next
 * view cancels it, and its late reply is dropped (`withContext` discards a canceled caller's result).
 */
internal class ViewFetch(
    private val scope: CoroutineScope,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var job: Job? = null
    /** The request that is out, and since when. */
    private var wire: Job? = null
    private var wireSince = 0L

    /** Drops a waiting or outstanding request: the layer went off or the zoom went out. */
    fun cancel() {
        job?.cancel()
    }

    /** Runs [send] once the view has held still for [settleMs], unless a request is out. [send]
     *  says whether its reply landed; only then does [landed] run. */
    fun ask(settleMs: Long, send: suspend () -> Boolean, landed: () -> Unit) {
        val out = wire
        if (out != null && out.isActive && now() - wireSince < WIRE_DEADLINE_MS) return
        job?.cancel()
        // Held until [job] is set: on an immediate dispatcher the body would otherwise run first,
        // and an ask from `landed` would be overwritten by this finished job.
        val next = scope.launch(start = CoroutineStart.LAZY) {
            if (settleMs > 0) delay(settleMs)
            val me = coroutineContext[Job]
            wire = me
            wireSince = now()
            val ok = try {
                send()
            } finally {
                if (wire === me) wire = null
            }
            if (ok) landed()
        }
        job = next
        next.start()
    }

    companion object {
        /** Past the shared client's 12 s call timeout, so only a request that ignores it waits this long. */
        const val WIRE_DEADLINE_MS = 15_000L
    }
}
