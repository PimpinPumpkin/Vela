package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class OsmMaxspeedTest {
    @Test fun bareNumberIsKmh() {
        assertEquals(50.0, OsmMaxspeed.parseKmh("50")!!, 1e-6)
        assertEquals(30.0, OsmMaxspeed.parseKmh("30")!!, 1e-6)
    }

    @Test fun mphConverts() {
        assertEquals(30 * 1.609344, OsmMaxspeed.parseKmh("30 mph")!!, 1e-6)
        assertEquals(65 * 1.609344, OsmMaxspeed.parseKmh("65 mph")!!, 1e-6)
    }

    @Test fun explicitKmhUnit() {
        assertEquals(50.0, OsmMaxspeed.parseKmh("50 km/h")!!, 1e-6)
        assertEquals(50.0, OsmMaxspeed.parseKmh("50 kmh")!!, 1e-6)
        assertEquals(50.0, OsmMaxspeed.parseKmh("50 kph")!!, 1e-6)
    }

    @Test fun unknownFormsAreNull() {
        assertNull(OsmMaxspeed.parseKmh("none"))       // derestricted
        assertNull(OsmMaxspeed.parseKmh("DE:urban"))   // implicit country code
        assertNull(OsmMaxspeed.parseKmh("GB:nsl_single"))
        assertNull(OsmMaxspeed.parseKmh("signals"))
        assertNull(OsmMaxspeed.parseKmh("variable"))
        assertNull(OsmMaxspeed.parseKmh(null))
        assertNull(OsmMaxspeed.parseKmh(""))
        assertNull(OsmMaxspeed.parseKmh("   "))
    }

    @Test fun walkIsSlow() {
        assertEquals(7.0, OsmMaxspeed.parseKmh("walk")!!, 1e-6)
    }

    @Test fun listTakesFirst() {
        assertEquals(50.0, OsmMaxspeed.parseKmh("50; 30")!!, 1e-6)
    }

    @Test fun garbageIsRangeChecked() {
        assertNull(OsmMaxspeed.parseKmh("0"))     // below floor
        assertNull(OsmMaxspeed.parseKmh("9999"))  // above ceiling
    }

    @Test fun fromTagsPrefersPlainThenDirectional() {
        assertEquals(50.0, OsmMaxspeed.fromTags("50", "30 mph", null)!!, 1e-6)
        assertEquals(30 * 1.609344, OsmMaxspeed.fromTags(null, "30 mph", null)!!, 1e-6)
        assertEquals(40.0, OsmMaxspeed.fromTags("none", null, "40")!!, 1e-6) // plain unknown → falls through
        assertNull(OsmMaxspeed.fromTags("none", "signals", null))
    }

    // 2026-10-05 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val MON = 5
    private val SAT = 10
    private val SUN = 11

    // The common Dutch motorway tagging: maxspeed=100 by day, 130 in the evening and at night.
    @Test fun eveningLimitPastMidnight() {
        val c = "130 @ (19:00-06:00)"
        assertEquals(130.0, OsmMaxspeed.conditionalKmh(c, at(MON, 19))!!, 1e-6)
        assertEquals(130.0, OsmMaxspeed.conditionalKmh(c, at(MON, 23, 59))!!, 1e-6)
        assertEquals(130.0, OsmMaxspeed.conditionalKmh(c, at(MON, 5, 59))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 6)))
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 12)))
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 18, 59)))
    }

    @Test fun fromTagsAppliesConditional() {
        assertEquals(100.0, OsmMaxspeed.fromTags("100", null, null, "130 @ (19:00-06:00)", at(MON, 14))!!, 1e-6)
        assertEquals(130.0, OsmMaxspeed.fromTags("100", null, null, "130 @ (19:00-06:00)", at(MON, 22))!!, 1e-6)
        assertEquals(100.0, OsmMaxspeed.fromTags("100", null, null, null, at(MON, 22))!!, 1e-6)
        // Only a conditional limit, outside its time: no known limit.
        assertNull(OsmMaxspeed.fromTags(null, null, null, "30 @ (07:00-09:00)", at(MON, 12)))
    }

    @Test fun weatherAndCommentsAreNeverInForce() {
        assertNull(OsmMaxspeed.conditionalKmh("70 @ wet", at(MON, 12)))
        // "At busy times" is set by the overhead signs, not the clock.
        assertNull(OsmMaxspeed.conditionalKmh("100 @ Mo-Fr 06:00-10:00,15:00-19:00 \"bij grote verkeersdrukte\"", at(MON, 8)))
        assertNull(OsmMaxspeed.conditionalKmh("100 @ (Mo-Fr 06:00-10:00,15:00-19:00 \"bij grote verkeersdrukte\")", at(MON, 8)))
        assertNull(OsmMaxspeed.conditionalKmh("40 @ (2026 Jul 17-2027 Apr 21)", at(MON, 12)))
        assertNull(OsmMaxspeed.conditionalKmh("80 @ (weight>7.5)", at(MON, 12)))
    }

    @Test fun mixedRulesKeepTheTimeOne() {
        val c = "130 @ (19:00-06:00); 70 @ wet"
        assertEquals(130.0, OsmMaxspeed.conditionalKmh(c, at(MON, 21))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 12)))
        assertEquals(130.0, OsmMaxspeed.conditionalKmh("130 @ (19:00-06:00);90 @ wet", at(MON, 21))!!, 1e-6)
    }

    @Test fun lastMatchingRuleWins() {
        val c = "120 @ (06:00-22:00); 80 @ (07:00-09:00)"
        assertEquals(80.0, OsmMaxspeed.conditionalKmh(c, at(MON, 8))!!, 1e-6)
        assertEquals(120.0, OsmMaxspeed.conditionalKmh(c, at(MON, 12))!!, 1e-6)
    }

    @Test fun weekdays() {
        val c = "15 @ (Sa 9:00 - 17:00)"
        assertEquals(15.0, OsmMaxspeed.conditionalKmh(c, at(SAT, 10))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh(c, at(SUN, 10)))
        assertNull(OsmMaxspeed.conditionalKmh(c, at(SAT, 18)))
        val workdays = "30 @ (Mo-Fr 07:00-17:00)"
        assertEquals(30.0, OsmMaxspeed.conditionalKmh(workdays, at(MON, 8))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh(workdays, at(SAT, 8)))
        assertEquals(30.0, OsmMaxspeed.conditionalKmh("30 @ (Sa,Su 07:00-17:00)", at(SUN, 8))!!, 1e-6)
    }

    // A night range belongs to the day it starts on: Friday night runs into Saturday morning.
    @Test fun nightRangeKeepsItsStartDay() {
        val c = "60 @ (Fr 22:00-06:00)"
        assertEquals(60.0, OsmMaxspeed.conditionalKmh(c, at(SAT, 3))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh(c, at(SUN, 3)))
    }

    @Test fun mphInConditional() {
        assertEquals(45 * 1.609344, OsmMaxspeed.conditionalKmh("45 mph @ (20:00-06:00)", at(MON, 23))!!, 1e-6)
    }

    // The common Australian school zone: the holiday exceptions cannot be read, so the rule is
    // never in force, in the morning range as much as in the afternoon one.
    @Test fun unreadableTailSkipsTheWholeRule() {
        val c = "40 @ (Mo-Fr 08:00-09:30,14:30-16:00; PH off; SH off)"
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 8, 30)))
        assertNull(OsmMaxspeed.conditionalKmh(c, at(MON, 15)))
        assertEquals(60.0, OsmMaxspeed.fromTags("60", null, null, c, at(MON, 8, 30))!!, 1e-6)
        assertNull(OsmMaxspeed.conditionalKmh("40 @ (Mo-Fr 00:00-07:00,18:00-24:00;Sa-Su 00:00-24:00)", at(MON, 6)))
        assertNull(OsmMaxspeed.conditionalKmh("30 @ (07:75-09:00)", at(MON, 8, 30)))
    }

    // A timed rule without a number: the plain limit does not apply either, so nothing shows.
    @Test fun noLimitByTheClockShowsNothing() {
        assertNull(OsmMaxspeed.fromTags("120", null, null, "none @ (19:00-06:00)", at(MON, 22)))
        assertEquals(120.0, OsmMaxspeed.fromTags("120", null, null, "none @ (19:00-06:00)", at(MON, 12))!!, 1e-6)
    }

    @Test fun blankOrBrokenIsNull() {
        assertNull(OsmMaxspeed.conditionalKmh(null, at(MON, 12)))
        assertNull(OsmMaxspeed.conditionalKmh("", at(MON, 12)))
        assertNull(OsmMaxspeed.conditionalKmh("130", at(MON, 12)))
        assertNull(OsmMaxspeed.conditionalKmh("none @ (19:00-06:00)", at(MON, 22)))
        assertNull(OsmMaxspeed.conditionalKmh("130 @ (25:00-26:00)", at(MON, 22)))
    }
}
