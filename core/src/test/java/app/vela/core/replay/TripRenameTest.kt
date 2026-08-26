package app.vela.core.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a saved trip ([TripLog.renameHeader]).
 *
 * A recorded drive cannot be captured again, so what is worth pinning is that a rename touches the
 * label and NOTHING else: the header's other fields keep their positions, and every recorded fix,
 * route and maneuver line survives byte for byte.
 */
class TripRenameTest {

    private val trip = listOf(
        "META,Old name,1755000000,38.5449,-121.7405,2955",
        "RD,3000,300,320",
        "M,DEPART,38.54490,-121.74050,1500,Head east",
        "38.54490,-121.74050,1755000000,90.0,13.4",
        "38.54491,-121.73990,1755000001,90.0,13.5",
    )

    @Test fun `renaming changes only the label`() {
        val out = TripLog.renameHeader(trip, "Morning commute")!!
        assertEquals("META,Morning commute,1755000000,38.5449,-121.7405,2955", out[0])
        assertEquals("every line after the header is untouched", trip.drop(1), out.drop(1))
    }

    // A comma would shift every later field along by one - the start time would be read as a
    // coordinate - so it is replaced, not kept.
    @Test fun `a comma in the name cannot shift the header fields`() {
        val fields = TripLog.renameHeader(trip, "Home, then work")!![0].split(',')
        assertEquals("META", fields[0])
        assertEquals("Home  then work", fields[1])
        assertEquals("1755000000", fields[2])
        assertEquals("38.5449", fields[3])
        assertEquals("-121.7405", fields[4])
        assertEquals("2955", fields[5])
    }

    // A newline would split the header into two records, the second of which parses as junk.
    @Test fun `a newline in the name cannot split the header`() {
        val out = TripLog.renameHeader(trip, "Line one\nline two")!!
        assertEquals(trip.size, out.size)
        assertTrue(out[0].startsWith("META,Line one line two,"))
    }

    @Test fun `a blank name falls back rather than leaving the trip nameless`() {
        assertEquals("META,Trip,1755000000,38.5449,-121.7405,2955", TripLog.renameHeader(trip, "   ")!![0])
    }

    @Test fun `an over-long name is trimmed to something a header can hold`() {
        assertEquals(80, TripLog.renameHeader(trip, "x".repeat(500))!![0].split(',')[1].length)
    }

    // Refusing is what protects a file that is not a trip: the caller then writes nothing at all.
    @Test fun `a file with no trip header is refused`() {
        assertNull(TripLog.renameHeader(listOf("38.5449,-121.7405,1,0,0"), "Nope"))
        assertNull(TripLog.renameHeader(emptyList(), "Nope"))
    }

    // Still parsing afterwards is the whole reason this lives beside the format.
    @Test fun `a renamed trip still parses`() {
        assertEquals(2, TripLog.parsePoints(TripLog.renameHeader(trip, "Renamed")!!).size)
    }
}
