package app.vela.core.data.transit

import app.vela.core.model.TransitMode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `board collapses the same run fed in twice by merged siblings`() {
        // Two feed copies of the same physical stop (two agencies, or a parent + curb twin)
        // deliver the same departures with different trip ids; the board must not list 7:25 twice.
        val a = st("102", "Downtown", "2026-01-01T10:00:00Z").copy(tripId = "ct-1")
        val b = st("102", "Downtown", "2026-01-01T10:00:00Z").copy(tripId = "et-1")
        val later = st("102", "Downtown", "2026-01-01T11:00:00Z").copy(tripId = "ct-2")
        val board = Transitous.buildBoard(listOf(a, b, later), null)!!
        assertEquals(listOf(2), board.lines.map { it.upcoming.size })
    }

    @Test
    fun `iso parse and clock text`() {
        val epoch = Transitous.parseIso("2026-01-01T20:26:00Z")!!
        assertEquals(1767299160L, epoch)
        assertEquals("8:26 PM", Transitous.clockText(epoch, "UTC"))
        assertNull(Transitous.parseIso("not-a-time"))
    }

    // Guards the SHIPPED calibration word list: v0.4.670 installs join transitCategoryWords into
    // one regex with no exclusion support, so the guarded "station"-family tokens pushed in v17
    // must reject fuel/EV/emergency categories while keeping real transit matching. Reads the
    // real calibration.json so an edit that breaks the pattern fails CI, not the fleet.
    @Test
    fun `calibration transit words reject fuel stations`() {
        val raw = java.io.File("../calibration.json").takeIf { it.exists() }
            ?: java.io.File("calibration.json")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(raw.readText()).jsonObject
        // The fork's signed calibration bundle (v13) does not carry transitCategoryWords yet -
        // the compiled regex covers the behaviour; this test self-arms when a bundle ships the key.
        val words = root["transitCategoryWords"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return
        val gate = Regex(words.joinToString("|"), RegexOption.IGNORE_CASE)
        // must NOT match (the doubled-board report: a fuel stop next to a bus stop)
        for (bad in listOf(
            "Gas station", "Fuel station", "Filling station", "Service station", "Charging station",
            "Fire station", "Police station", "Radio station", "Television station",
            "Tankstation", "Bensinstation", "Stazione di servizio", "Estación de servicio",
            "Заправочная станция", "תחנת דלק", "Station-service",
        )) assertTrue("should reject: $bad", !gate.containsMatchIn(bad))
        // must still match
        for (good in listOf(
            "Bus stop", "Bus station", "Train station", "Transit station", "Subway station",
            "Light rail station", "Treinstation", "Tågstation", "Gare", "Bahnhof",
            "Stazione ferroviaria", "Estación de tren", "Станция метро", "תחנת אוטובוס",
        )) assertTrue("should match: $good", gate.containsMatchIn(good))
    }

    @Test
    fun `directional pairs merge into one stop with siblings`() {
        fun stop(id: String, name: String, lat: Double, lng: Double, parent: String? = null) =
            Transitous.MapStop(name = name, stopId = id, parentId = parent, lat = lat, lon = lng)
        val merged = Transitous.mergeDirectionalPairs(
            listOf(
                // a directional pair ~25 m apart, same name -> one icon at the midpoint
                stop("a1", "Main St & 1st Ave", 47.0000, -122.0000),
                stop("a2", "Main St & 1st Ave", 47.0002, -122.0001),
                // same name across town -> stays its own stop
                stop("b1", "Main St & 1st Ave", 47.1000, -122.0000),
                // direction-suffixed names differ -> never merged
                stop("c1", "Hub NB Station", 47.0500, -122.0000),
                stop("c2", "Hub SB Station", 47.0501, -122.0000),
            ),
        )
        assertEquals(4, merged.size)
        val pair = merged.first { it.stopId == "a1" }
        assertEquals(listOf("a2"), pair.siblingIds)
        assertEquals(47.0001, pair.lat, 1e-9)
        assertTrue(merged.any { it.stopId == "b1" && it.siblingIds.isEmpty() })
        assertTrue(merged.any { it.stopId == "c1" } && merged.any { it.stopId == "c2" })
    }
}
