package app.vela.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixRulesTest {
    @Test
    fun aCellFixDoesNotReplaceAFreshWifiFix() {
        assertFalse(FixRules.betterThanLast(newAccM = 1500f, lastAccM = 40f, ageS = 2.0, speedMps = 0.0))
    }

    @Test
    fun aWorseFixWinsOnceTheLastIsOldEnough() {
        // 40 m + 5 m/s x 300 s = 1540 m > 1500 m.
        assertTrue(FixRules.betterThanLast(newAccM = 1500f, lastAccM = 40f, ageS = 300.0, speedMps = 0.0))
    }

    @Test
    fun aBetterFixAlwaysWins() {
        assertTrue(FixRules.betterThanLast(newAccM = 20f, lastAccM = 40f, ageS = 0.5, speedMps = 0.0))
    }

    @Test
    fun gpsLockAfterANetworkPositionIsAnUpgrade() {
        assertTrue(FixRules.isUpgrade(shownAccM = 600f, newAccM = 8f))
        assertFalse(FixRules.isUpgrade(shownAccM = 20f, newAccM = 5f)) // already precise: normal smoothing
        assertFalse(FixRules.isUpgrade(shownAccM = null, newAccM = 5f))
    }
}
