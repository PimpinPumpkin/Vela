package app.vela.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The predicate gates every memory adaptation in the app, so its boundaries are pinned here. */
class LowRamModeTest {

    @Test
    fun `heap class 128 is low-RAM`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 128, totalRamMb = 3000))
    }

    @Test
    fun `heap class below the ceiling is low-RAM`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 127, totalRamMb = 3000))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 96, totalRamMb = 3000))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 1, totalRamMb = 3000))
    }

    @Test
    fun `heap class above the ceiling is not low-RAM on its own`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 129, totalRamMb = 3000))
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 192, totalRamMb = 3000))
    }

    @Test
    fun `both probes unreadable is constrained, not roomy`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 0, totalRamMb = 0))
    }

    @Test
    fun `one unreadable probe does not veto a good reading from the other`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 0, totalRamMb = 3000))
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 0))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 96, totalRamMb = 0))
    }

    @Test
    fun `a nominal 2 GB phone is low-RAM even with a generous heap class`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 192, totalRamMb = 1900))
    }

    @Test
    fun `total RAM boundary is inclusive`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 2048))
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 2049))
    }

    @Test
    fun `isLowRamDevice alone is enough`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = true, heapClassMb = 512, totalRamMb = 8000))
    }

    @Test
    fun `a 4a stays on the normal path`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 5700))
    }

    @Test
    fun `a 32-bit process is constrained however roomy the phone is`() {
        // The QM215 handset as it reports: 3566 MB, heap class 256, isLowRamDevice false.
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 3566, is32Bit = true))
    }

    @Test
    fun `the same phone on a 64-bit process is not constrained`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 3566, is32Bit = false))
    }

    @Test
    fun `bitness defaults to 64-bit, so an older caller reads as before`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 3566))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 128, totalRamMb = 3566))
    }

    @Test
    fun `64-bit does not turn an unreadable probe into a roomy reading`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 0, totalRamMb = 0, is32Bit = false))
    }
}
