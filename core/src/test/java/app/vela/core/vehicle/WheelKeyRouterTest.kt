package app.vela.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelKeyRouterTest {
    private val pass = WheelKeyRouter.Decision(false)
    private val consume = WheelKeyRouter.Decision(true)
    private val repeat = WheelKeyRouter.Decision(true, WheelAction.REPEAT_INSTRUCTION)

    @Test fun `a voice press during a drive repeats the instruction once and owns both edges`() {
        val r = WheelKeyRouter()
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 1_000, guiding = true))
        assertEquals(consume, r.onKey(304, down = false, nowMs = 1_120, guiding = true))
    }

    @Test fun `every voice code acts the same`() {
        for ((i, code) in listOf(304, 312, 327, 328).withIndex()) {
            val r = WheelKeyRouter()
            val t = 10_000L * (i + 1)
            assertEquals("$code", repeat, r.onKey(code, down = true, nowMs = t, guiding = true))
            assertEquals("$code", consume, r.onKey(code, down = false, nowMs = t + 100, guiding = true))
        }
    }

    @Test fun `with no drive the voice key stays the car's`() {
        val r = WheelKeyRouter()
        assertEquals(pass, r.onKey(304, down = true, nowMs = 1_000, guiding = false))
        assertEquals(pass, r.onKey(304, down = false, nowMs = 1_100, guiding = false))
    }

    @Test fun `call and media keys always pass, during a drive too`() {
        val r = WheelKeyRouter()
        for (code in listOf(313, 314, 309, 353, 331)) {
            assertEquals("$code", pass, r.onKey(code, down = true, nowMs = 1_000, guiding = true))
            assertEquals("$code", pass, r.onKey(code, down = false, nowMs = 1_100, guiding = true))
        }
    }

    @Test fun `unknown keys pass`() {
        val r = WheelKeyRouter()
        for (code in listOf(4, 24, 25, 87, 88, 320, 321, 0, -1)) {
            assertEquals("$code", pass, r.onKey(code, down = true, nowMs = 1_000, guiding = true))
        }
    }

    @Test fun `repeated downs while held fire nothing more`() {
        val r = WheelKeyRouter()
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 1_000, guiding = true))
        for (t in 1_050L..1_600L step 50) assertEquals(consume, r.onKey(304, down = true, nowMs = t, guiding = true))
        assertEquals(consume, r.onKey(304, down = false, nowMs = 1_650, guiding = true))
    }

    @Test fun `a long code while the base is held is folded into the press`() {
        val r = WheelKeyRouter()
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 1_000, guiding = true))
        assertEquals(consume, r.onKey(312, down = true, nowMs = 1_600, guiding = true))
        assertEquals(consume, r.onKey(312, down = false, nowMs = 1_700, guiding = true))
        assertEquals(consume, r.onKey(304, down = false, nowMs = 1_710, guiding = true))
    }

    @Test fun `a late long code or companion just after the release is swallowed`() {
        val r = WheelKeyRouter()
        r.onKey(304, down = true, nowMs = 1_000, guiding = true)
        r.onKey(304, down = false, nowMs = 1_500, guiding = true)
        assertEquals(consume, r.onKey(327, down = true, nowMs = 1_540, guiding = true))
        assertEquals(consume, r.onKey(327, down = false, nowMs = 1_560, guiding = true))
        // Past the window, a new press.
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 3_000, guiding = true))
    }

    @Test fun `a companion of a press the car kept goes to the car too`() {
        val r = WheelKeyRouter()
        r.onKey(304, down = true, nowMs = 1_000, guiding = false)
        r.onKey(304, down = false, nowMs = 1_100, guiding = false)
        // The drive started between the two: the companion still belongs to the car's press.
        assertEquals(pass, r.onKey(327, down = true, nowMs = 1_130, guiding = true))
        assertEquals(pass, r.onKey(327, down = false, nowMs = 1_150, guiding = true))
    }

    @Test fun `a press that started before the drive stays the car's until released`() {
        val r = WheelKeyRouter()
        assertEquals(pass, r.onKey(304, down = true, nowMs = 1_000, guiding = false))
        assertEquals(pass, r.onKey(304, down = true, nowMs = 1_050, guiding = true))
        assertEquals(pass, r.onKey(312, down = true, nowMs = 1_600, guiding = true))
        assertEquals(pass, r.onKey(304, down = false, nowMs = 1_700, guiding = true))
    }

    @Test fun `a release Vela never saw the press of passes`() {
        val r = WheelKeyRouter()
        assertEquals(pass, r.onKey(304, down = false, nowMs = 1_000, guiding = true))
    }

    @Test fun `a press that lost its release does not block the next one`() {
        val r = WheelKeyRouter()
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 1_000, guiding = true))
        // No release, then a fresh press long after.
        assertEquals(repeat, r.onKey(304, down = true, nowMs = 1_000 + WheelKeys.STALE_PRESS_MS + 1, guiding = true))
    }
}
