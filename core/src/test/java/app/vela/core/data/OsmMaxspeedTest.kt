package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
