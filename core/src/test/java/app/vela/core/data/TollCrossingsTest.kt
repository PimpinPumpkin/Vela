package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The A-25 and A-30 toll bridges, from a copy of the toll file with their written rates
 *  (`tolls-quebec-2026.json`): the A-25 grid in force from 1 June 2026 and the A-30 one from
 *  28 February 2026 to 31 January 2027. A crossing costs the same however much of the bridge the
 *  route drives. */
class TollCrossingsTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(d: Int, h: Int, mi: Int = 0): ZonedDateTime = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    // 2026-11-03 is a Tuesday, 2026-11-07 a Saturday.
    private val tuesday = 3
    private val saturday = 7

    private val fixture: String = javaClass.getResource("/tolls-quebec-2026.json")!!.readText()

    @Before fun load() { assertTrue(Tolls.load(fixture)) }

    /** A straight drive from [km] before [a] to [km] past [b]. Its only vertices are the two ends:
     *  a route line can have none on a straight bridge. */
    private fun through(a: LatLng, b: LatLng, km: Double = 2.0): List<LatLng> {
        val f = km * 1000.0 / RouteProjection.cumulative(listOf(a, b)).last()
        return listOf(
            LatLng(a.lat - (b.lat - a.lat) * f, a.lng - (b.lng - a.lng) * f),
            LatLng(b.lat + (b.lat - a.lat) * f, b.lng + (b.lng - a.lng) * f),
        )
    }

    // Each carriageway's ends, in the direction of traffic, from the file's lines.
    private val a25NorthStart = LatLng(45.63211, -73.61421)
    private val a25NorthEnd = LatLng(45.64073, -73.62337)
    private val a25North = through(a25NorthStart, a25NorthEnd)
    private val a25South = through(LatLng(45.64066, -73.62351), LatLng(45.63204, -73.61434))
    private val a30North = through(LatLng(45.29970, -74.00941), LatLng(45.31453, -74.02041))
    private val a30South = through(LatLng(45.31447, -74.02056), LatLng(45.29965, -74.00957))

    private fun one(route: List<LatLng>, t: ZonedDateTime): TollCharge = Tolls.charges(route, ms(t), 300.0).single()

    @Test
    fun anA25CrossingInTheMorningPeakCostsTheCarPeakToll() {
        val out = one(a25North, at(tuesday, 7, 30))
        assertEquals("A-25", out.name)
        assertEquals(420, out.cents)
        assertEquals(420, out.maxCents)
        assertEquals(650, out.feeCents)
    }

    @Test
    fun anA25CrossingOutsideThePeaksOrAtTheWeekendCostsTheOffPeakToll() {
        assertEquals(336, one(a25North, at(tuesday, 12)).cents)
        assertEquals(336, one(a25North, at(tuesday, 22)).cents)
        assertEquals(336, one(a25North, at(tuesday, 3)).cents)
        assertEquals(336, one(a25North, at(saturday, 7, 30)).cents)
        assertEquals(420, one(a25North, at(tuesday, 16, 30)).cents)
    }

    @Test
    fun theA25PeaksRunFromAMinutePastSixToNineAndFromAMinutePastThreeToSix() {
        val p = Tolls.road("a25")!!.period(at(tuesday, 12).toLocalDate())!!
        fun rate(h: Int, mi: Int) = p.rateRange(true, at(tuesday, h, mi))!!.first
        assertEquals(336.0, rate(6, 0), 0.0)
        assertEquals(420.0, rate(6, 1), 0.0)
        assertEquals(420.0, rate(9, 0), 0.0)
        assertEquals(336.0, rate(9, 1), 0.0)
        assertEquals(336.0, rate(15, 0), 0.0)
        assertEquals(420.0, rate(15, 1), 0.0)
        assertEquals(420.0, rate(18, 0), 0.0)
        assertEquals(336.0, rate(18, 1), 0.0)
    }

    @Test
    fun anA30CrossingCostsTheSameAtEveryHourWithNoFee() {
        for (t in listOf(at(tuesday, 7, 30), at(tuesday, 12), at(tuesday, 23), at(saturday, 16))) {
            val out = one(a30North, t)
            assertEquals("A-30", out.name)
            assertEquals(440, out.cents)
            assertEquals(440, out.maxCents)
            assertEquals(0, out.feeCents)
        }
    }

    @Test
    fun bothWaysOverEachBridgePay() {
        val t = at(tuesday, 7, 30)
        assertEquals(420, one(a25South, t).cents)
        assertEquals(440, one(a30North, t).cents)
        assertEquals(440, one(a30South, t).cents)
    }

    @Test
    fun theTollDoesNotDependOnTheLengthDriven() {
        // The A-25 bridge is about 1.2 km: priced by the kilometer it would cost about $4.03 at noon.
        assertEquals(336, one(through(a25NorthStart, a25NorthEnd, km = 0.2), at(tuesday, 12)).cents)
        assertEquals(336, one(through(a25NorthStart, a25NorthEnd, km = 3.0), at(tuesday, 12)).cents)
    }

    @Test
    fun aRouteOverTheBridgeAndBackPaysTwice() {
        val out = one(a25North + a25South, at(tuesday, 12))
        assertEquals(672, out.cents)
        assertEquals(1300, out.feeCents)
    }

    @Test
    fun aRouteOverBothBridgesPaysEach() {
        val out = Tolls.charges(a30North + a25North, ms(at(tuesday, 12)), 3600.0)
        assertEquals(setOf("A-25", "A-30"), out.map { it.name }.toSet())
        assertEquals(336 + 440, out.sumOf { it.cents })
        assertEquals(650, out.sumOf { it.feeCents })
    }

    @Test
    fun aRouteBesideOrUnderTheBridgePaysNothing() {
        val t = ms(at(tuesday, 12))
        // A road beside the bridge going the same way, about 190 m from it.
        val beside = through(LatLng(45.63211, -73.61721), LatLng(45.64073, -73.62637))
        assertTrue(Tolls.charges(beside, t, 300.0).isEmpty())
        // A road crossing under the bridge's middle.
        val under = listOf(LatLng(45.63420, -73.63000), LatLng(45.63860, -73.60800))
        assertTrue(Tolls.charges(under, t, 300.0).isEmpty())
    }

    @Test
    fun aCrossingOutsideItsRatesDatesShowsNoPrice() {
        fun day(d: LocalDate) = ms(d.atTime(12, 0).atZone(zone))
        assertEquals(440, Tolls.charges(a30North, day(LocalDate.of(2027, 1, 31)), 300.0).single().cents)
        assertTrue(Tolls.charges(a30North, day(LocalDate.of(2027, 2, 1)), 300.0).isEmpty())
        assertTrue(Tolls.charges(a25North, day(LocalDate.of(2026, 5, 31)), 300.0).isEmpty())
    }

    @Test
    fun aRoadOfAKindThisBuildDoesNotPriceIsLeftOut() {
        assertEquals(2, Tolls.roadCount)
        assertTrue(Tolls.load(fixture.replace("\"kind\":\"crossing\"", "\"kind\":\"zone\"")))
        assertEquals(0, Tolls.roadCount)
    }

    @Test
    fun theBundledFileHasRatesForBothBridgesTheDayItWasMade() {
        val bundled = File("../app/src/main/assets/tolls.json").readText()
        assertTrue(Tolls.load(bundled))
        val generated = LocalDate.parse(Json.parseToJsonElement(bundled).jsonObject.getValue("generated").jsonPrimitive.content)
        assertTrue(Tolls.road("a25")!!.period(generated) != null)
        assertTrue(Tolls.road("a30")!!.period(generated) != null)
    }
}
