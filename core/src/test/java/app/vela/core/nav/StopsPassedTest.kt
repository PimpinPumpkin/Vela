package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class StopsPassedTest {
    private fun passed(marks: List<Double?>, from: Int, at: Double) = NavEngine.stopsPassed(marks, marks.size, from, at, 25.0)

    @Test
    fun marksPassInOrder() {
        val marks = listOf(1000.0, 3000.0)
        assertEquals(0, passed(marks, 0, 500.0))
        assertEquals(1, passed(marks, 0, 980.0))
        assertEquals(2, passed(marks, 1, 3100.0))
    }

    @Test
    fun noMarksKeepsEveryStop() {
        // Right after a stops edit every mark is null until the new route lands.
        assertEquals(0, passed(listOf(null, null, null), 0, 5000.0))
    }

    @Test
    fun unmarkedStopPassesWithTheNextMarkedOne() {
        val marks = listOf(null, 2000.0, null)
        assertEquals(0, passed(marks, 0, 1000.0))
        assertEquals(2, passed(marks, 0, 2000.0))
    }
}

class StopSkippedTest {
    private fun skipped(marks: List<Double?>, from: Int, prev: Double, now: Double) =
        NavEngine.stopSkipped(marks, marks.size, from, prev, now, 25.0, 250.0)

    @Test
    fun aJumpOverTheNextStopIsASkip() {
        org.junit.Assert.assertTrue(skipped(listOf(900.0, 1800.0), 0, 100.0, 2500.0))
    }

    @Test
    fun drivingUpToTheStopIsNot() {
        org.junit.Assert.assertFalse(skipped(listOf(900.0), 0, 880.0, 915.0))
    }

    @Test
    fun aJumpShortOfTheStopIsNot() {
        org.junit.Assert.assertFalse(skipped(listOf(3000.0), 0, 100.0, 1500.0))
    }
}

class OnlySilentSkippedTest {
    private fun only(marks: List<Double?>, silent: List<Boolean>, from: Int, prev: Double, now: Double) =
        NavEngine.onlySilentSkipped(marks, silent, from, prev, now, 25.0)

    @Test
    fun aJumpPastASavedRoutesViaOwesNothing() {
        org.junit.Assert.assertTrue(only(listOf(900.0), listOf(true), 0, 100.0, 2500.0))
        org.junit.Assert.assertTrue(only(listOf(900.0, 1500.0), listOf(true, true), 0, 100.0, 2500.0))
    }

    @Test
    fun aJumpThatAlsoPassesARealStopIsStillASkip() {
        org.junit.Assert.assertFalse(only(listOf(900.0, 1500.0), listOf(true, false), 0, 100.0, 2500.0))
        org.junit.Assert.assertFalse(only(listOf(900.0), listOf(false), 0, 100.0, 2500.0))
    }

    @Test
    fun aViaWithNoMarkLeavesTheRealStopBehindItInCharge() {
        org.junit.Assert.assertFalse(only(listOf(null, 1500.0), listOf(true, false), 0, 100.0, 2500.0))
        // And a jump with no stop inside it is not this case at all.
        org.junit.Assert.assertFalse(only(listOf(5000.0), listOf(true), 0, 100.0, 2500.0))
    }
}
