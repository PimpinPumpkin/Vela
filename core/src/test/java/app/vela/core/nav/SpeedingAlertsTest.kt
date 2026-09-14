package app.vela.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedingAlertsTest {
    private fun run(a: SpeedingAlerts, speeds: List<Double?>, limit: Double?, stepMs: Long = 1000L, t0: Long = 0L): List<Int> {
        val fired = mutableListOf<Int>()
        speeds.forEachIndexed { i, s -> if (a.update(s, limit, t0 + i * stepMs)) fired += i }
        return fired
    }

    @Test
    fun `a blip over the limit stays silent`() {
        val a = SpeedingAlerts()
        val speeds = listOf(70.0, 70.0, 78.0, 79.0, 70.0, 70.0, 70.0)
        assertTrue(run(a, speeds, 72.0).isEmpty())
    }

    @Test
    fun `sustained speeding speaks once after the hold and does not nag`() {
        val a = SpeedingAlerts()
        val speeds = List(30) { 85.0 }
        val fired = run(a, speeds, 72.0)
        assertTrue(fired.toString(), fired == listOf(4))
    }

    @Test
    fun `slowing under the limit for a while re-arms the alert`() {
        val a = SpeedingAlerts()
        a.minGapMs = 0L
        val speeds = List(6) { 85.0 } + List(10) { 60.0 } + List(6) { 85.0 }
        val fired = run(a, speeds, 72.0)
        assertTrue(fired.toString(), fired == listOf(4, 20))
    }

    @Test
    fun `a short dip under the limit does not re-arm`() {
        val a = SpeedingAlerts()
        a.minGapMs = 0L
        val speeds = List(6) { 85.0 } + List(3) { 60.0 } + List(6) { 85.0 }
        val fired = run(a, speeds, 72.0)
        assertTrue(fired.toString(), fired == listOf(4))
    }

    @Test
    fun `the minimum gap holds even when re-armed`() {
        val a = SpeedingAlerts()
        val speeds = List(6) { 85.0 } + List(10) { 60.0 } + List(6) { 85.0 }
        val fired = run(a, speeds, 72.0)
        assertTrue(fired.toString(), fired == listOf(4))
    }

    @Test
    fun `within tolerance is not speeding`() {
        val a = SpeedingAlerts()
        assertTrue(run(a, List(10) { 76.9 }, 72.0).isEmpty())
        assertFalse(run(SpeedingAlerts(), List(10) { 77.1 }, 72.0).isEmpty())
    }

    @Test
    fun `no limit or no speed means nothing to say`() {
        val a = SpeedingAlerts()
        assertTrue(run(a, List(10) { 120.0 }, null).isEmpty())
        assertTrue(run(a, List(10) { null }, 50.0).isEmpty())
    }
}
