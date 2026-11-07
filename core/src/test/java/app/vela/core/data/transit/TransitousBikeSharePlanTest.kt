package app.vela.core.data.transit

import app.vela.core.model.LatLng
import app.vela.core.model.TransitMode
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bike-share trips from the open planner, on replies captured from the service on 2026-11-04:
 * Lyon from Part-Dieu to Vieux Lyon (two Vélov rides alone, Vélov after a metro or a bus), Lyon
 * from Villeurbanne to Confluence (Vélov after metro A), central Paris asked with no system named
 * (Lime, Voi and Dott bikes beside one Vélib' ride), and the systems around central Paris.
 */
class TransitousBikeSharePlanTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/transitous/$name")!!.bufferedReader().readText()

    private val lyonDirect = fixture("plan-lyon-bikeshare-direct.json")
    private val lyonTransit = fixture("plan-lyon-bikeshare-transit.json")
    private val paris = fixture("plan-paris-bikeshare-unfiltered.json")
    private val velov = setOf("Vélov")

    private val partDieu = LatLng(45.7606, 4.8599)
    private val vieuxLyon = LatLng(45.7622, 4.8270)
    private val villeurbanne = LatLng(45.7665, 4.8795)
    private val confluence = LatLng(45.7432, 4.8155)

    @Test fun `a bike-share trip alone reads walk, ride and walk`() {
        val trips = Transitous.parseBikeSharePlan(lyonDirect, partDieu, vieuxLyon, velov)
        val t = trips.first { it.steps.size == 3 }
        assertEquals(listOf(TransitMode.WALK, TransitMode.BIKE_SHARE, TransitMode.WALK), t.steps.map { it.mode })
        val ride = t.steps[1]
        assertEquals("Vélov", ride.line!!.name)
        assertEquals(TransitMode.BIKE_SHARE, ride.line!!.mode)
        assertEquals("3019 - PART-DIEU / DERUELLE", ride.boardStop!!.name)
        assertEquals("QUAI ROMAIN ROLLAND", ride.alightStop!!.name)
        assertNotNull(ride.boardStop!!.timeText)
        assertNotNull(ride.alightStop!!.timeText)
        assertEquals("11 min", ride.durationText)
        val rental = ride.rental!!
        assertEquals("fr-stations-velov-de-la-metropole-de-lyon-disponibilites-temps-reel", rental.providerId)
        assertEquals("Vélov", rental.groupId)
        assertEquals("Vélov", rental.system)
        assertTrue("an ELECTRIC_ASSIST bike", rental.electric)
        assertTrue(ride.path!!.size > 2)
        // The walks lead to the station and away from the other one.
        assertTrue(t.steps[0].walkFrom!!.distanceTo(partDieu) < 1.0)
        assertTrue(t.steps[0].walkTo!!.distanceTo(ride.boardStop!!.location!!) < 1.0)
        assertTrue(t.steps[2].walkFrom!!.distanceTo(ride.alightStop!!.location!!) < 1.0)
        assertTrue(t.steps[2].walkTo!!.distanceTo(vieuxLyon) < 1.0)
        assertEquals(listOf("Vélov"), t.lines.map { it.name })
        assertTrue(t.departureEpochSec!! < t.arrivalEpochSec!!)
    }

    @Test fun `a bike leg after a transit ride keeps both`() {
        val trips = Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov)
        val t = trips.first()
        assertEquals(
            listOf(TransitMode.WALK, TransitMode.SUBWAY, TransitMode.WALK, TransitMode.BIKE_SHARE, TransitMode.WALK),
            t.steps.map { it.mode },
        )
        assertEquals(listOf("A", "Vélov"), t.lines.map { it.name })
        assertEquals("REMPARTS D'AINAY", t.steps[3].boardStop!!.name)
        assertEquals("CONFLUENCE / DARSE", t.steps[3].alightStop!!.name)
        assertEquals("the transit leg's agency is the trip's", "TCL", t.agency)
    }

    @Test fun `later runs of the same trip are one choice, earliest arrival first`() {
        // Five metro runs to two stations come back; one trip per station stays.
        val transit = Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov)
        assertEquals(listOf("REMPARTS D'AINAY", "PERRACHE / CARNOT"), transit.map { it.steps[3].boardStop!!.name })
        assertTrue(transit[0].arrivalEpochSec!! <= transit[1].arrivalEpochSec!!)
        // Seven answers, four distinct trips, the three that arrive first kept.
        val direct = Transitous.parseBikeSharePlan(lyonDirect, partDieu, vieuxLyon, velov)
        assertEquals(3, direct.size)
        assertEquals(direct.map { it.arrivalEpochSec }, direct.map { it.arrivalEpochSec }.sortedBy { it })
        val pickups = direct.map { t -> t.steps.first { it.mode == TransitMode.BIKE_SHARE }.boardStop!!.name }
        assertEquals(pickups.distinct(), pickups)
    }

    @Test fun `free-floating bikes and systems not asked for are left out`() {
        val trips = Transitous.parseBikeSharePlan(paris, LatLng(48.8530, 2.3499), LatLng(48.8606, 2.3376), setOf("Paris"))
        assertEquals(1, trips.size)
        val ride = trips.single().steps.single { it.mode == TransitMode.BIKE_SHARE }
        assertEquals("Arcole - Notre-Dame", ride.boardStop!!.name)
        assertEquals("Jean-Jacques Rousseau - Deux Ecus", ride.alightStop!!.name)
        assertFalse(ride.rental!!.electric)
        // Dott's parking spots read as stations at both ends: only the system list keeps them out.
        val withDott = Transitous.parseBikeSharePlan(paris, LatLng(48.8530, 2.3499), LatLng(48.8606, 2.3376), setOf("Paris", "Dott paris"))
        assertTrue(withDott.any { t -> t.steps.any { it.rental?.groupId == "Dott paris" } })
        // Lime and Voi bikes are left anywhere: never a bike-share trip, whatever the list says.
        val withLime = Transitous.parseBikeSharePlan(paris, LatLng(48.8530, 2.3499), LatLng(48.8606, 2.3376), setOf("Lime Paris Proper", "Voi Technology AB"))
        assertEquals(emptyList<Any>(), withLime)
    }

    @Test fun `the plain planner still reads a rental leg as a walk and skips direct answers`() {
        val trips = Transitous.parsePlan(lyonDirect, partDieu, vieuxLyon)
        assertTrue(trips.isNotEmpty())
        assertTrue(trips.all { t -> t.steps.none { it.mode == TransitMode.BIKE_SHARE } })
        assertTrue(trips.all { t -> t.steps.count { it.line != null } == 1 })
    }

    @Test fun `the docked bike systems around a trip`() {
        assertEquals(listOf("Paris"), Transitous.parseBikeShareGroups(fixture("rentals-providers-paris.json")))
        val reply = """{"providerGroups":[
            {"id":"nextbike Berlin","name":"nextbike Berlin","providers":["a"],"formFactors":["BICYCLE","CARGO_BICYCLE"]},
            {"id":"Zeus Scooters","name":"Zeus Scooters","providers":["b"],"formFactors":["SCOOTER_STANDING"]},
            {"id":"Tier Berlin","name":"Tier Berlin","providers":["c"],"formFactors":["BICYCLE"]},
            {"id":"Bikes, Inc","name":"Bikes, Inc","providers":["d"],"formFactors":["BICYCLE"]},
            {"id":"Car share","name":"Car share","providers":["e"],"formFactors":["CAR"]}
        ],"providers":[],"stations":[],"vehicles":[],"zones":[]}"""
        assertEquals(listOf("nextbike Berlin"), Transitous.parseBikeShareGroups(reply))
        assertEquals("an error reply is a failure, not no systems", null, Transitous.parseBikeShareGroups("""{"error":"x"}"""))
    }

    @Test fun `the request asks for docked bikes of the named systems, leaving now`() {
        val url = Transitous.bikeSharePlanUrl(partDieu, vieuxLyon, listOf("Vélov", "nextbike Berlin"))
        assertTrue(url.startsWith("https://api.transitous.org/api/v1/plan?fromPlace=45.760600,4.859900&toPlace=45.762200,4.827000"))
        assertTrue(url.contains("&directModes=RENTAL&preTransitModes=WALK,RENTAL&postTransitModes=WALK,RENTAL"))
        assertTrue(url.contains("&directRentalFormFactors=BICYCLE&preTransitRentalFormFactors=BICYCLE&postTransitRentalFormFactors=BICYCLE"))
        val groups = "V%C3%A9lov,nextbike%20Berlin"
        assertTrue(url.contains("&directRentalProviderGroups=$groups"))
        assertTrue(url.contains("&preTransitRentalProviderGroups=$groups"))
        assertTrue(url.contains("&postTransitRentalProviderGroups=$groups"))
        assertFalse(url.contains("&time="))
        assertFalse(url.contains("arriveBy"))
        // A bike alone may take an hour with its walks, a bike around transit half an hour.
        assertTrue(url.contains("&maxDirectTime=3600&maxPreTransitTime=1800&maxPostTransitTime=1800"))
    }

    @Test fun `a trip that leaves long after the search is dropped`() {
        fun at(iso: String) = java.time.Instant.parse(iso).epochSecond
        // The metro runs leave from 18:17 on: at 17:52 the 18:23 one is past the half hour.
        val soon = Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov, at("2026-11-04T17:52:00Z"))
        assertEquals(listOf("REMPARTS D'AINAY"), soon.map { it.steps[3].boardStop!!.name })
        // An hour before, none is near enough.
        assertEquals(emptyList<Any>(), Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov, at("2026-11-04T17:15:00Z")))
        // Without a time nothing is dropped for it.
        assertEquals(2, Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov).size)
    }

    // The Vélov stations near those Lyon trips' bike legs, captured beside them.
    private val velovStations by lazy { Transitous.parseBikeStations(fixture("rentals-lyon-velov.json"))!! }

    @Test fun `each bike leg's stations are found among the system's stations`() {
        val legs = (
            Transitous.parseBikeSharePlan(lyonDirect, partDieu, vieuxLyon, velov) +
                Transitous.parseBikeSharePlan(lyonTransit, villeurbanne, confluence, velov)
            ).flatMap { t -> t.steps.filter { it.mode == TransitMode.BIKE_SHARE } }
        assertEquals(5, legs.size)
        for (s in legs) for (end in listOf(s.boardStop!!, s.alightStop!!)) {
            val st = Transitous.stationFor(velovStations, s.rental!!.providerId, end.name, end.location)
            assertNotNull(end.name, st)
            assertEquals(end.name, st!!.name)
        }
        val pickup = legs.first { it.boardStop!!.name == "3019 - PART-DIEU / DERUELLE" }
        assertEquals(1, Transitous.stationFor(velovStations, pickup.rental!!.providerId, pickup.boardStop!!.name, null)!!.bikes)
    }

    @Test fun `a station named another way is found by its position, a farther one is not`() {
        val st = velovStations.first { it.name == "QUAI ROMAIN ROLLAND" }
        val provider = st.key.substringBefore('/')
        // 11 m off, and 56 m from the nearest station.
        assertEquals(st, Transitous.stationFor(velovStations, provider, "Quai Romain-Rolland", LatLng(st.lat + 0.0001, st.lon)))
        assertEquals(null, Transitous.stationFor(velovStations, provider, "Quai Romain-Rolland", LatLng(st.lat + 0.0005, st.lon)))
        assertEquals("another provider's station is never this one", null, Transitous.stationFor(velovStations, "other-bikes", st.name, LatLng(st.lat, st.lon)))
    }

    @Test fun `the systems are looked for a kilometer past both ends`() {
        val b = Transitous.paddedBox(partDieu, vieuxLyon, 1000.0)
        assertEquals(45.7606 - 1000.0 / 111_320.0, b[0], 1e-9)
        assertEquals(45.7622 + 1000.0 / 111_320.0, b[2], 1e-9)
        assertTrue(b[1] < 4.8270 - 0.012 && b[3] > 4.8599 + 0.012)
    }
}
