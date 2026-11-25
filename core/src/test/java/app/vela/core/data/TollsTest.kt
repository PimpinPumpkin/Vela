package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Highway 407 ETR from a copy of the toll file built from the rate chart in force from 1 January
 *  2026 (`tolls-2026.json`). */
class TollsTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(d: Int, h: Int, mi: Int = 0): ZonedDateTime = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    // 2026-11-03 is a Tuesday, 2026-11-07 a Saturday.
    private val tuesday = 3
    private val saturday = 7

    private val fixture: String = javaClass.getResource("/tolls-2026.json")!!.readText()

    @Before fun load() { assertTrue(Tolls.load(fixture)) }

    private val road get() = Tolls.road("407etr")!!
    private fun rateRange(eastbound: Boolean, t: ZonedDateTime) = road.period(t.toLocalDate())!!.rateRange(eastbound, t)!!

    private fun lengthM(l: List<LatLng>) = RouteProjection.cumulative(l).last()

    private val ways: List<List<LatLng>> by lazy {
        Json.parseToJsonElement(fixture).jsonObject.getValue("roads").jsonArray[0].jsonObject.getValue("lines").jsonArray.map { l ->
            l.jsonPrimitive.content.split(' ').map { p -> p.split(',').let { LatLng(it[0].toDouble(), it[1].toDouble()) } }
        }
    }

    /**
     * The longest run of consecutive carriageway ways in the data that goes [eastbound] (each way
     * ends where the next begins). The two directions are separate carriageways, tens of meters
     * apart, so each needs its own line to drive.
     */
    private fun longestChain(eastbound: Boolean): List<LatLng> {
        val key = { p: LatLng -> "%.5f,%.5f".format(p.lat, p.lng) }
        val byStart = ways.associateBy { key(it.first()) }
        var best: List<LatLng> = emptyList()
        var bestLen = 0.0
        for (w in ways) {
            val pts = ArrayList(w)
            var guard = 0
            while (guard++ < 400) {
                val next = byStart[key(pts.last())] ?: break
                pts.addAll(next.drop(1))
            }
            if ((pts.last().lng > pts.first().lng) != eastbound) continue
            val len = lengthM(pts)
            if (len > bestLen) { best = pts; bestLen = len }
        }
        return best
    }

    private val eastChain: List<LatLng> by lazy { longestChain(true) }
    private val westChain: List<LatLng> by lazy { longestChain(false) }

    // --- the rate chart, pinned against the official page ---

    @Test
    fun westboundWeekdayAtSevenAmRunsFromTheCheapestZoneToTheDearest() {
        val (lo, hi) = rateRange(false, at(tuesday, 7))
        assertEquals(62.30, lo, 0.001)
        assertEquals(108.79, hi, 0.001)
    }

    @Test
    fun eastboundWeekdayAtThreeThirtyPmIsTheDearestBandOfTheWeek() {
        val (lo, hi) = rateRange(true, at(tuesday, 15, 30))
        assertEquals(62.53, lo, 0.001)
        assertEquals(119.21, hi, 0.001)
    }

    @Test
    fun theBandChangesAtItsStartMinute() {
        // Weekday westbound: 9:29 is the 7 a.m. band, 9:30 the next.
        assertEquals(108.79, rateRange(false, at(tuesday, 9, 29)).second, 0.001)
        assertEquals(79.08, rateRange(false, at(tuesday, 9, 30)).second, 0.001)
        // 4:59 a.m. is still the night band that began at 9 p.m., 5:00 the first of the day.
        assertEquals(50.66, rateRange(false, at(tuesday, 4, 59)).second, 0.001)
        assertEquals(79.13, rateRange(false, at(tuesday, 5, 0)).second, 0.001)
        assertEquals(50.66, rateRange(false, at(tuesday, 21, 0)).second, 0.001)
    }

    @Test
    fun aWeekendHasItsOwnBands() {
        assertEquals(87.27, rateRange(false, at(saturday, 10)).second, 0.001)
        assertEquals(50.50, rateRange(false, at(saturday, 8, 30)).first, 0.001)
        assertEquals(71.91, rateRange(false, at(saturday, 8, 30)).second, 0.001)
        assertEquals(50.66, rateRange(false, at(saturday, 23)).second, 0.001)
    }

    // --- the road ---

    @Test
    fun bothCarriagewaysHaveALongLineToTestOn() {
        assertTrue("east ${lengthM(eastChain)} m", lengthM(eastChain) > 8_000.0)
        assertTrue("west ${lengthM(westChain)} m", lengthM(westChain) > 8_000.0)
    }

    @Test
    fun anEastboundTripPaysAboutItsKilometersAtThePerKilometerRatePlusTheTripToll() {
        val km = lengthM(eastChain) / 1000.0
        val t = at(tuesday, 12)
        val (lo, hi) = rateRange(true, t)
        val out = Tolls.charges(eastChain, ms(t), 3600.0).single()
        assertEquals("407 ETR", out.name)
        assertEquals(km * lo + 100, out.cents.toDouble(), (km * lo + 100) * 0.025)
        assertEquals(km * hi + 100, out.maxCents.toDouble(), (km * hi + 100) * 0.025)
        assertTrue(out.maxCents > out.cents)
        assertEquals(530, out.feeCents)
    }

    @Test
    fun aWestboundTripIsPricedAtTheWestboundRates() {
        val km = lengthM(westChain) / 1000.0
        val t = at(tuesday, 12)
        val (lo, hi) = rateRange(false, t)
        val out = Tolls.charges(westChain, ms(t), 3600.0).single()
        assertEquals(km * lo + 100, out.cents.toDouble(), (km * lo + 100) * 0.025)
        assertEquals(km * hi + 100, out.maxCents.toDouble(), (km * hi + 100) * 0.025)
    }

    @Test
    fun aRouteOnTheOtherCarriagewayWithTheWrongHeadingIsNotCounted() {
        // The eastbound line driven backwards is a westbound trip on the wrong side of the median:
        // within 25 m of the road only where the carriageways are close, so it is not priced as the whole road.
        val km = lengthM(eastChain) / 1000.0
        val t = at(tuesday, 12)
        val (lo, _) = rateRange(false, t)
        val out = Tolls.charges(eastChain.reversed(), ms(t), 3600.0)
        assertTrue(out.isEmpty() || out.single().cents < (km * lo + 100) * 0.9)
    }

    @Test
    fun theTripIsPricedAtItsEntryTimeNotTheDeparture() {
        // 33 km of road to the south, then the 407, on a four hour drive. The trip enters the road after
        // the lead-in's share of the drive, so the departure is set to make that 15:45 or 12:00. Leaving
        // an hour earlier would put it in other bands.
        val km = lengthM(eastChain) / 1000.0
        val first = eastChain.first()
        val route = listOf(LatLng(first.lat - 0.3, first.lng), first) + eastChain.drop(1)
        val share = lengthM(route.take(2)) / lengthM(route)
        val entryOffsetMs = (share * 4 * 3_600_000).toLong()
        val peak = Tolls.charges(route, ms(at(tuesday, 15, 45)) - entryOffsetMs, 4 * 3600.0).single()
        val midday = Tolls.charges(route, ms(at(tuesday, 12)) - entryOffsetMs, 4 * 3600.0).single()
        val peakTop = km * rateRange(true, at(tuesday, 15, 45)).second + 100
        val middayTop = km * rateRange(true, at(tuesday, 12)).second + 100
        val detail = "km $km, lead share $share, peak ${peak.cents}-${peak.maxCents}, midday ${midday.cents}-${midday.maxCents}, rates peak ${rateRange(true, at(tuesday, 15, 45))} midday ${rateRange(true, at(tuesday, 12))}"
        assertEquals(detail, peakTop, peak.maxCents.toDouble(), peakTop * 0.03)
        assertEquals(detail, middayTop, midday.maxCents.toDouble(), middayTop * 0.03)
    }

    @Test
    fun aFiveKilometerTripPaysItsKilometersPlusExactlyTheTripToll() {
        val cum = RouteProjection.cumulative(eastChain)
        val five = eastChain.filterIndexed { i, _ -> cum[i] < 5_000.0 } + RouteProjection.pointAt(eastChain, cum, 5_000.0)
        val t = at(tuesday, 12)
        val (lo, hi) = rateRange(true, t)
        val out = Tolls.charges(five, ms(t), 300.0).single()
        // The run is counted in 50 m steps, so it can differ from 5 km by one step.
        assertEquals(5.0 * lo + 100, out.cents.toDouble(), 5.0 * lo * 0.03 + 8)
        assertEquals(5.0 * hi + 100, out.maxCents.toDouble(), 5.0 * hi * 0.03 + 8)
        assertEquals(530, out.feeCents)
    }

    @Test
    fun aStretchUnderAKilometerIsNotATrip() {
        // The first 600 m of the line, cut where it reaches 600 m: a road running beside the 407 for a
        // moment, or a ramp's end. (The data's vertices can be kilometers apart, so cut by distance.)
        val cum = RouteProjection.cumulative(eastChain)
        val short = eastChain.filterIndexed { i, _ -> cum[i] < 600.0 } + RouteProjection.pointAt(eastChain, cum, 600.0)
        assertEquals(600.0, lengthM(short), 5.0)
        assertTrue(Tolls.charges(short, ms(at(tuesday, 12)), 120.0).isEmpty())
    }

    @Test
    fun aRoadThatOnlyCrossesTheTollRoadPaysNothing() {
        val mid = eastChain[eastChain.size / 2]
        val across = listOf(LatLng(mid.lat - 0.002, mid.lng), LatLng(mid.lat + 0.002, mid.lng))
        assertTrue(Tolls.charges(across, ms(at(tuesday, 12)), 60.0).isEmpty())
    }

    @Test
    fun aRouteFarFromTheRoadPaysNothing() {
        val davis = listOf(LatLng(38.54, -121.74), LatLng(38.60, -121.60))
        assertTrue(Tolls.charges(davis, ms(at(tuesday, 12)), 900.0).isEmpty())
        assertTrue(Tolls.charges(emptyList(), 0L, 0.0).isEmpty())
    }

    // --- the file ---

    @Test
    fun aTripAfterTheLastDayOfItsRatesShowsNoPrice() {
        val until = road.periods.last().validUntil
        val after = until.plusDays(1).atTime(12, 0).atZone(zone)
        assertTrue(Tolls.charges(eastChain, ms(after), 3600.0).isEmpty())
        assertTrue(Tolls.charges(eastChain, ms(until.atTime(12, 0).atZone(zone)), 3600.0).isNotEmpty())
    }

    @Test
    fun aNewChartTakesOverOnTheDayItTakesEffect() {
        // The same file with a second period from 1 December at twice the rates, the first ending
        // the day before.
        val doubled = fixture.replace(Regex(""""periods":\[(\{.*?"valid_until":")[0-9-]+(".*\})\]\}\]""")) { m ->
            val first = m.groupValues[1] + "2026-11-30" + m.groupValues[2]
            val second = (m.groupValues[1] + "2027-01-31" + m.groupValues[2])
                .replace("\"effective\":\"2026-01-01\"", "\"effective\":\"2026-12-01\"")
                .replace(Regex("""\[(\d+),([\d.]+),([\d.]+)\]""")) { b ->
                    "[${b.groupValues[1]},${b.groupValues[2].toDouble() * 2},${b.groupValues[3].toDouble() * 2}]"
                }
            """"periods":[$first,$second]}]"""
        }
        assertTrue(Tolls.load(doubled))
        assertEquals(2, road.periods.size)
        val nov = Tolls.charges(eastChain, ms(at(24, 12)), 3600.0).single()
        val dec = Tolls.charges(eastChain, ms(ZonedDateTime.of(2026, 12, 1, 12, 0, 0, 0, zone)), 3600.0).single()
        assertEquals((nov.maxCents - 100) * 2.0, (dec.maxCents - 100).toDouble(), 4.0)
    }

    @Test
    fun aFileThatCannotBeReadKeepsTheRoadsLoaded() {
        assertFalse(Tolls.load("{\"roads\": 5"))
        assertEquals(1, Tolls.roadCount)
    }

    @Test
    fun theBundledFileLoadsWithRatesInForceTheDayItWasMade() {
        val bundled = File("../app/src/main/assets/tolls.json").readText()
        assertTrue(Tolls.load(bundled))
        val generated = java.time.LocalDate.parse(Json.parseToJsonElement(bundled).jsonObject.getValue("generated").jsonPrimitive.content)
        assertTrue(road.period(generated) != null)
        assertEquals(File("../app/src/main/assets/tolls_version.txt").readText().trim(),
            Json.parseToJsonElement(bundled).jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun aRouteNearTorontoOnTheFreeHighwaysPaysNothing() {
        // The 401 through Mississauga and Toronto, south of the 407.
        val h401 = listOf(LatLng(43.62, -79.70), LatLng(43.72, -79.45), LatLng(43.77, -79.25))
        assertTrue(Tolls.charges(h401, ms(at(tuesday, 12)), 2400.0).isEmpty())
    }
}
