package app.vela.ui.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** A request for what the map shows is never canceled once it is out (the map's view comes once
 *  a second while it follows a moving fix, sooner than a slow reply), but one stuck past the
 *  deadline no longer holds the next view back. */
class ViewFetchTest {
    private var now = 0L
    private val fetch = ViewFetch(CoroutineScope(Dispatchers.Unconfined)) { now }

    @Test fun `a request that is out lands, and a newer view waits for it`() {
        val reply = CompletableDeferred<Boolean>()
        var sent = 0
        var landed = 0
        fetch.ask(0, send = { sent++; reply.await() }, landed = { landed++ })
        fetch.ask(0, send = { sent++; true }, landed = { landed++ })
        assertEquals("the second view does not cancel or join the first", 1, sent)
        reply.complete(true)
        assertEquals(1, landed)
        fetch.ask(0, send = { sent++; true }, landed = { landed++ })
        assertEquals("once it is in, the next view is asked", 2, sent)
    }

    @Test fun `the caller can ask again from landed, with the wire free`() {
        var sent = 0
        val second = CompletableDeferred<Boolean>()
        fetch.ask(0, send = { sent++; true }, landed = {
            if (sent == 1) fetch.ask(0, send = { sent++; second.await() }, landed = {})
        })
        assertEquals(2, sent)
        fetch.ask(0, send = { sent++; true }, landed = {})
        assertEquals("the request asked from landed is the one out", 2, sent)
        fetch.cancel()
        fetch.ask(0, send = { sent++; true }, landed = {})
        assertEquals(3, sent)
    }

    @Test fun `a failed request does not run landed, and cancel drops one that is out`() {
        var landed = 0
        fetch.ask(0, send = { false }, landed = { landed++ })
        assertEquals(0, landed)
        val reply = CompletableDeferred<Boolean>()
        fetch.ask(0, send = { reply.await() }, landed = { landed++ })
        fetch.cancel()
        reply.complete(true)
        assertEquals(0, landed)
        var sent = 0
        fetch.ask(0, send = { sent++; true }, landed = {})
        assertEquals("a canceled request frees the wire", 1, sent)
    }

    @Test fun `a request stuck past the deadline no longer holds the next view back`() {
        val stuck = CompletableDeferred<Boolean>()
        var sent = 0
        var landed = 0
        fetch.ask(0, send = { sent++; stuck.await() }, landed = { landed++ })
        now = ViewFetch.WIRE_DEADLINE_MS - 1
        fetch.ask(0, send = { sent++; true }, landed = { landed++ })
        assertEquals("still inside the deadline, the view waits", 1, sent)
        now = ViewFetch.WIRE_DEADLINE_MS
        fetch.ask(0, send = { sent++; true }, landed = { landed++ })
        assertEquals("past it, the next view is asked", 2, sent)
        assertEquals(1, landed)
        stuck.complete(true)
        assertEquals("the stuck request's late reply is dropped", 1, landed)
    }
}
