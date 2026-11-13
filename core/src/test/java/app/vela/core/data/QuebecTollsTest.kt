package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import kotlin.math.cos

class QuebecTollsTest {
    private val zone = Tolls.ZONE

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): ZonedDateTime = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    // 2026-11-03 is a Tuesday, 2026-11-07 a Saturday.
    private val tuesday = 3
    private val saturday = 7

    /** [from] moved [meters] along the bearing of the line from [a] to [b], beyond [b]. */
    private fun beyond(a: LatLng, b: LatLng, meters: Double): LatLng {
        val dy = (b.lat - a.lat) * 111_320.0
        val dx = (b.lng - a.lng) * 111_320.0 * cos(Math.toRadians(b.lat))
        val len = Math.hypot(dx, dy)
        return LatLng(b.lat + dy / len * meters / 111_320.0, b.lng + dx / len * meters / (111_320.0 * cos(Math.toRadians(b.lat))))
    }

    /** A route that drives the bridge piece [piece], with [leadM] of straight road before it and 2 km after. */
    private fun driving(piece: List<LatLng>, leadM: Double = 3_000.0): List<LatLng> =
        listOf(beyond(piece[1], piece[0], leadM)) + piece + beyond(piece[piece.size - 2], piece.last(), 2_000.0)

    private val a25Piece = QuebecTollBridges.A25[1]   // 4 points, about 600 m
    private val a30Piece = QuebecTollBridges.A30[0]   // about 1.6 km

    @Test
    fun theA25PeaksAreSixOhOneToNineAndThreeOhOneToSixOnAWorkingDay() {
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, tuesday, 6, 0)))
        assertEquals(412, QuebecTolls.a25Cents(at(2026, 11, tuesday, 6, 1)))
        assertEquals(412, QuebecTolls.a25Cents(at(2026, 11, tuesday, 9, 0)))
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, tuesday, 9, 1)))
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, tuesday, 15, 0)))
        assertEquals(412, QuebecTolls.a25Cents(at(2026, 11, tuesday, 15, 1)))
        assertEquals(412, QuebecTolls.a25Cents(at(2026, 11, tuesday, 18, 0)))
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, tuesday, 18, 1)))
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, tuesday, 12)))
    }

    @Test
    fun theA25IsAtTheOffPeakPriceAllDayOnAWeekend() {
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, saturday, 7)))
        assertEquals(330, QuebecTolls.a25Cents(at(2026, 11, saturday, 17)))
    }

    @Test
    fun aRouteOverTheA25BridgePaysItsTollWithTheNoAccountFee() {
        val out = QuebecTolls.charges(driving(a25Piece), ms(at(2026, 11, tuesday, 12)), 600.0)
        assertEquals(listOf(TollCharge("A-25", 330, 650)), out)
    }

    @Test
    fun aRouteOverTheA30BridgePaysTheSameAtEveryHourAndNoFee() {
        val rush = QuebecTolls.charges(driving(a30Piece), ms(at(2026, 11, tuesday, 7)), 600.0)
        val night = QuebecTolls.charges(driving(a30Piece), ms(at(2026, 11, saturday, 23)), 600.0)
        assertEquals(listOf(TollCharge("A-30", 440, 0)), rush)
        assertEquals(rush, night)
    }

    @Test
    fun theBridgeIsFoundWhicheverCarriagewayAndWhicheverWayTheRouteDrives() {
        val reversed = driving(a25Piece.reversed())
        assertEquals(1, QuebecTolls.charges(reversed, ms(at(2026, 11, tuesday, 12)), 600.0).size)
        val otherSide = driving(QuebecTollBridges.A25[5])
        assertEquals(1, QuebecTolls.charges(otherSide, ms(at(2026, 11, tuesday, 12)), 600.0).size)
    }

    @Test
    fun theTollIsPricedAtTheTimeOfTheCrossingNotTheDeparture() {
        // 30 km of road, then the bridge, on a one hour drive: the crossing is near the end of it.
        val route = driving(a25Piece, leadM = 30_000.0)
        // Leaving at 07:00 the car is on the bridge about 07:53, in the morning peak.
        assertEquals(412, QuebecTolls.charges(route, ms(at(2026, 11, tuesday, 7)), 3600.0).single().cents)
        // Leaving at 08:30 it is on the bridge about 09:23, after the peak.
        assertEquals(330, QuebecTolls.charges(route, ms(at(2026, 11, tuesday, 8, 30)), 3600.0).single().cents)
    }

    @Test
    fun aRoadThatOnlyCrossesTheBridgeDoesNotPay() {
        // East-west through the middle of the piece, 400 m long.
        val mid = LatLng((a25Piece[1].lat + a25Piece[2].lat) / 2, (a25Piece[1].lng + a25Piece[2].lng) / 2)
        val across = listOf(LatLng(mid.lat, mid.lng - 0.0025), LatLng(mid.lat, mid.lng + 0.0025))
        // The bridge runs north-west to south-east, so a due east-west road is not along it.
        assertTrue(QuebecTolls.charges(across, ms(at(2026, 11, tuesday, 12)), 60.0).isEmpty())
    }

    @Test
    fun aRouteFarFromBothBridgesPaysNothing() {
        val davis = listOf(LatLng(38.54, -121.74), LatLng(38.60, -121.60))
        assertTrue(QuebecTolls.charges(davis, ms(at(2026, 11, tuesday, 12)), 900.0).isEmpty())
        assertTrue(QuebecTolls.charges(emptyList(), 0L, 0.0).isEmpty())
    }

    @Test
    fun aRouteInsideTheTollAreaThatMissesBothBridgesPaysNothing() {
        // Inside the area's box, on neither bridge.
        val between = listOf(LatLng(45.50, -73.60), LatLng(45.55, -73.66), LatLng(45.60, -73.72))
        assertTrue(QuebecTolls.charges(between, ms(at(2026, 11, tuesday, 12)), 1500.0).isEmpty())
    }
}
