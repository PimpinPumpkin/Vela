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
        // Wherever it lands: a cell position can be a kilometer or more out.
        assertTrue(FixRules.isUpgrade(shownAccM = 600f, newAccM = 8f, shownIsGps = false, newIsGps = true, movedM = 2_500.0))
        // Already precise: normal smoothing.
        assertFalse(FixRules.isUpgrade(shownAccM = 20f, newAccM = 5f, shownIsGps = false, newIsGps = true, movedM = 10.0))
        assertFalse(FixRules.isUpgrade(shownAccM = null, newAccM = 5f, shownIsGps = false, newIsGps = true, movedM = 0.0))
    }

    @Test
    fun aGpsFixThatSharpensTheOneShowingIsAnUpgrade() {
        // A first lock with no network location: 80 m, then 10 m, 60 m from where the dot is.
        assertTrue(FixRules.isUpgrade(shownAccM = 80f, newAccM = 10f, shownIsGps = true, newIsGps = true, movedM = 60.0))
        assertTrue(FixRules.isUpgrade(shownAccM = 80f, newAccM = 10f, shownIsGps = true, newIsGps = true, movedM = 160.0))
    }

    @Test
    fun aGpsLeapAfterAPoorGpsFixIsNotAnUpgrade() {
        // A street canyon: 64 m, then a fix 300 m off that claims 30 m. The outlier hold judges it.
        assertFalse(FixRules.isUpgrade(shownAccM = 64f, newAccM = 30f, shownIsGps = true, newIsGps = true, movedM = 300.0))
        // The same leap from any other pairing of providers is still taken.
        assertTrue(FixRules.isUpgrade(shownAccM = 64f, newAccM = 30f, shownIsGps = false, newIsGps = true, movedM = 300.0))
        assertTrue(FixRules.isUpgrade(shownAccM = 64f, newAccM = 30f, shownIsGps = false, newIsGps = false, movedM = 300.0)) // Wi-Fi after cell
        assertTrue(FixRules.isUpgrade(shownAccM = 64f, newAccM = 30f, shownIsGps = true, newIsGps = false, movedM = 300.0))
    }
}
