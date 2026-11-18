package app.vela.core.location

import app.vela.core.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuidanceFixTest {
    private val davis = LatLng(38.5449, -121.7405)

    @Before fun clean() = GuidanceFix.reset()

    private fun take(t: Double) = GuidanceFix.take(davis, t, 5.0, 0.0, null, null, null, still = true)

    @Test
    fun aFixBothFeedsReceiveIsHandedToTheEngineOnce() {
        val first = take(10.0)
        val second = take(10.0)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertSame(first.out, second.out)
        assertTrue(take(11.0).fresh)
    }

    @Test
    fun withFreeFusedOffGuidanceGetsTheFixAsItCame() {
        val near = LatLng(38.5449, -121.7405)
        GuidanceFix.take(near, 1.0, 5.0, 0.0, null, null, null, still = true)
        GuidanceFix.take(near, 2.0, 5.0, 0.0, null, null, null, still = true)
        // A 70 m leap at rest: the filter refuses it, the raw feed hands it on.
        val leap = LatLng(38.5449, -121.7397)
        assertFalse(GuidanceFix.take(leap, 3.0, 5.0, 0.0, null, null, null, still = true).out.accepted)
        GuidanceFix.rawWhen = { true }
        val t = GuidanceFix.take(leap, 4.0, 5.0, 0.0, null, null, null, still = true)
        assertTrue(t.raw)
        assertTrue(t.out.accepted)
        assertSame(leap, t.out.position)
        GuidanceFix.rawWhen = { false }
        assertFalse(GuidanceFix.take(leap, 5.0, 5.0, 0.0, null, null, null, still = true).raw)
    }

    @Test
    fun theCarFeedStandsDownOnlyWhileThePhoneFeeds() {
        assertFalse(GuidanceFix.phoneFeeding(1_000L))
        GuidanceFix.phoneFed(1_000L)
        assertTrue(GuidanceFix.phoneFeeding(1_000L + GuidanceFix.PHONE_FRESH_MS - 1))
        assertFalse(GuidanceFix.phoneFeeding(1_000L + GuidanceFix.PHONE_FRESH_MS))
    }
}
