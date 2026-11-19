package app.vela.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopularTimesHintTest {
    @Test fun aRunOfPlacesWithNoChartTurnsThePlaceholderOffAndAChartTurnsItBackOn() {
        PopularTimesHint.answered(true)
        assertTrue(PopularTimesHint.show)
        repeat(PopularTimesHint.MISSES_OFF - 1) { PopularTimesHint.answered(false) }
        assertTrue(PopularTimesHint.show)
        PopularTimesHint.answered(false)
        assertFalse(PopularTimesHint.show)
        repeat(5) { PopularTimesHint.answered(false) }
        assertFalse(PopularTimesHint.show)
        PopularTimesHint.answered(true)
        assertTrue(PopularTimesHint.show)
    }
}
