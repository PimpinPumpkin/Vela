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

    private fun ts(name: String, id: String, lat: Double, lng: Double, dep: String, sched: String = dep, cancelled: Boolean = false) =
        Transitous.TripStop(
            name = name, stopId = id, lat = lat, lon = lng,
            departure = dep, scheduledDeparture = sched, tz = "UTC", cancelled = cancelled,
        )

    @Test
    fun `trip step trims to the tapped stop and maps realtime plus cancelled`() {
        val leg = Transitous.TripLeg(
            from = ts("Origin Terminal", "s1", 37.00, -122.00, "2026-01-01T10:00:00Z"),
            intermediateStops = listOf(
                ts("Main St", "s2", 37.01, -122.00, "2026-01-01T10:10:00Z"),
                // realtime moved this call 3 min late
                ts("Oak Ave", "s3", 37.02, -122.00, "2026-01-01T10:23:00Z", sched = "2026-01-01T10:20:00Z"),
                ts("Pine Rd", "s4", 37.03, -122.00, "2026-01-01T10:30:00Z", cancelled = true),
            ),
            to = ts("End Terminal", "s5", 37.04, -122.00, "2026-01-01T10:40:00Z"),
            mode = "BUS", headsign = "End Terminal", routeShortName = "42", routeColor = "00aa00",
        )
        // Tapped at Main St -> the timeline BOARDS there; the origin terminal it already
        // passed goes into priorStops (shown greyed above, Google-style).
        val step = Transitous.buildTripStep(leg, atLat = 37.01, atLng = -122.00)!!
        assertEquals("Main St", step.boardStop?.name)
        assertEquals(listOf("Origin Terminal"), step.priorStops.map { it.name })
        assertEquals("End Terminal", step.alightStop?.name)
        assertEquals(listOf("Oak Ave", "Pine Rd"), step.intermediateStops.map { it.name })
        assertEquals(3, step.numStops)
        assertEquals("42", step.line?.name)
        assertEquals("#00aa00", step.line?.colorHex)
        // Realtime stop keeps the differing timetable time + a signed delay (3 min late here);
        // on-time stops carry neither.
        val oak = step.intermediateStops[0]
        assertEquals("10:23 AM", oak.timeText)
        assertEquals("10:20 AM", oak.scheduledText)
        assertEquals(3, oak.delayMin)
        assertNull(step.boardStop?.scheduledText)
        assertNull(step.boardStop?.delayMin)
        assertTrue(step.intermediateStops[1].cancelled)
        // An EARLY call carries a negative delay.
        val earlyLeg = leg.copy(
            intermediateStops = listOf(
                ts("Main St", "s2", 37.01, -122.00, "2026-01-01T10:08:00Z", sched = "2026-01-01T10:10:00Z"),
            ),
        )
        val early = Transitous.buildTripStep(earlyLeg, atLat = 37.00, atLng = -122.00)!!
        assertEquals(-2, early.intermediateStops[0].delayMin)
        // Tapping the terminus keeps the whole run boarding at the origin, nothing prior.
        val full = Transitous.buildTripStep(leg, atLat = 37.04, atLng = -122.00)!!
        assertEquals("Origin Terminal", full.boardStop?.name)
        assertTrue(full.priorStops.isEmpty())
    }

    @Test
    fun `board departures carry the trip id and drop cancelled runs`() {
        val live = st("7", "Uptown", "2026-01-01T10:00:00Z").copy(tripId = "t-1")
        val gone = st("7", "Uptown", "2026-01-01T10:30:00Z").copy(tripId = "t-2", tripCancelled = true)
        val board = Transitous.buildBoard(listOf(live, gone), null)!!
        assertEquals(1, board.lines[0].upcoming.size)
        assertEquals("t-1", board.lines[0].upcoming[0].tripId)
    }

    @Test
    fun `iso parse and clock text`() {
        val epoch = Transitous.parseIso("2026-01-01T20:26:00Z")!!
        assertEquals(1767299160L, epoch)
        assertEquals("8:26 PM", Transitous.clockText(epoch, "UTC"))
        assertNull(Transitous.parseIso("not-a-time"))
    }
}
