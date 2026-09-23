package app.vela.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class JitterTest {
    @After fun reset() { Jitter.random = Random() }

    @Test fun staysInsideTheSpread() {
        Jitter.random = Random(7)
        repeat(10_000) {
            val v = Jitter.around(120_000L)
            assertTrue("$v", v in 90_000L..150_000L)
        }
    }

    @Test fun actuallySpreads() {
        Jitter.random = Random(7)
        val seen = (1..200).map { Jitter.around(120_000L) }.toSet()
        assertTrue(seen.size > 150)
    }

    @Test fun zeroSpreadIsExact() {
        assertEquals(700L, Jitter.around(700L, 0.0))
    }
}
