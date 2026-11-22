package app.vela.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenNavTest {
    // A city route: 5 km left in 10 minutes, about 8.3 m/s, so 25 s is about 210 m.
    @Test fun aCityTurnLightsAboutTwoHundredMetersOut() {
        assertTrue(LockScreenNav.turnNear(200.0, 5_000.0, 600.0))
        assertFalse(LockScreenNav.turnNear(260.0, 5_000.0, 600.0))
    }

    // A freeway route: 30 km in 16 min 40 s, 30 m/s, so 25 s is 750 m.
    @Test fun aFreewayExitLightsAboutSevenHundredFiftyMetersOut() {
        assertTrue(LockScreenNav.turnNear(740.0, 30_000.0, 1_000.0))
        assertFalse(LockScreenNav.turnNear(800.0, 30_000.0, 1_000.0))
    }

    @Test fun theLeadStaysInsideItsBounds() {
        // Crawling in traffic: never later than 200 m out.
        assertTrue(LockScreenNav.turnNear(190.0, 1_000.0, 1_000.0))
        // A very fast pace: never earlier than 1 km out.
        assertFalse(LockScreenNav.turnNear(1_100.0, 100_000.0, 1_000.0))
        // No time left to read a pace from: the floor.
        assertTrue(LockScreenNav.turnNear(150.0, 0.0, 0.0))
        assertFalse(LockScreenNav.turnNear(250.0, 0.0, 0.0))
    }
}
