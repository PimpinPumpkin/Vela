package app.vela.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SheetBackTest {
    @Test fun `gesture changes visuals before committing exactly once`() = runBlocking {
        var commits = 0
        val seen = mutableListOf<Float>()
        consumeSheetBack(
            flow { emit(0.2f); assertEquals(0, commits); emit(0.7f); assertEquals(0, commits) },
            update = { seen += it }, commit = { commits++ }, cancel = { fail("Not canceled") },
        )
        assertEquals(listOf(0.2f, 0.7f), seen)
        assertEquals(1, commits)
    }

    @Test fun `canceled gesture restores visuals without dismissing the sheet`() = runBlocking {
        var commits = 0
        var restored = false
        try {
            consumeSheetBack(
                flow { emit(0.6f); throw CancellationException("Gesture reversed") },
                update = {}, commit = { commits++ }, cancel = { restored = true },
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, commits)
        assertTrue(restored)
    }

    @Test fun `button back has no progress events but still dismisses once`() = runBlocking {
        var commits = 0
        consumeSheetBack(emptyFlow(), update = { fail("No progress") }, commit = { commits++ }, cancel = { fail("Not canceled") })
        assertEquals(1, commits)
    }

    @Test fun `gesture progress cannot move beyond the sheet bounds`() = runBlocking {
        val seen = mutableListOf<Float>()
        consumeSheetBack(flowOf(-0.1f, 1.1f), update = { seen += it }, commit = {}, cancel = {})
        assertEquals(listOf(0f, 1f), seen)
    }
}
