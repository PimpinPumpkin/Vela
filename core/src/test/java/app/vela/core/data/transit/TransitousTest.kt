package app.vela.core.data.transit

import app.vela.core.model.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitousTest {

    private fun st(
        route: String?, headsign: String?, iso: String,
        realtime: Boolean = false, color: String? = null, mode: String = "BUS", cancelled: Boolean = false,
    ) = Transitous.StopTime(
        place = Transitous.StPlace(departure = iso, scheduledDeparture = iso, tz = "UTC", cancelled = cancelled),
        mode = mode, realTime = realtime, headsign = headsign, routeShortName = route, routeColor = color,
    )

    @Test
    fun `groups by route and headsign, sorts lines soonest first`() {
        val board = Transitous.buildBoard(
            listOf(
                st("42", "Downtown", "2026-01-01T10:20:00Z"),
                st("Rapid Blue", "Airport", "2026-01-01T10:05:00Z", realtime = true, color = "00aa00"),
                st("42", "Downtown", "2026-01-01T10:40:00Z"),
                st("42", "Uptown", "2026-01-01T10:30:00Z"),
            ),
            stationName = "Main St Station",
        )!!
        assertEquals("Main St Station", board.stationName)
        assertEquals(3, board.lines.size)
        // soonest line leads: the named line at 10:05
        assertEquals("Rapid Blue", board.lines[0].label)
        assertEquals("#00aa00", board.lines[0].colorHex)   // hex prefix added
        assertTrue(board.lines[0].upcoming[0].realtime)
        // route 42 Downtown keeps BOTH its times, sorted
        val downtown = board.lines.first { it.label == "42" && it.headsign == "Downtown" }
        assertEquals(2, downtown.upcoming.size)
        assertTrue(downtown.upcoming[0].epochSec!! < downtown.upcoming[1].epochSec!!)
        assertEquals(TransitMode.BUS, downtown.mode)
    }

    @Test
    fun `cancelled runs are dropped, empty board is null`() {
        assertNull(Transitous.buildBoard(listOf(st("1", "A", "2026-01-01T10:00:00Z", cancelled = true)), null))
        assertNull(Transitous.buildBoard(emptyList(), null))
    }

    @Test
    fun `iso parse and clock text`() {
        val epoch = Transitous.parseIso("2026-01-01T20:26:00Z")!!
        assertEquals(1767299160L, epoch)
        assertEquals("8:26 PM", Transitous.clockText(epoch, "UTC"))
        assertNull(Transitous.parseIso("not-a-time"))
    }
}
