package app.vela.core.data.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `rentals` reply: a Lyon fixture captured from the service on 2026-11-04 (three Vélo'v
 *  stations, a car-share station and a scooter station), plus small replies for the dock rules. */
class TransitousBikeShareTest {
    private val lyon = javaClass.getResourceAsStream("/transitous/rentals-lyon.json")!!.bufferedReader().readText()

    private fun station(docks: String, extra: String = "") = """
        {"providerGroups":[],"providers":[],"vehicles":[],"zones":[],"stations":[
          {"id":"7","providerId":"test-bikes","providerGroupId":"test","name":" Covell Park ",
           "lat":38.5617,"lon":-121.7604,"isRenting":true,"isReturning":true,"numVehiclesAvailable":5,
           "formFactors":["BICYCLE"],"vehicleTypesAvailable":{"A":5,"B":0,"C":0},
           "vehicleDocksAvailable":$docks $extra,"bbox":[]}
        ]}
    """.trimIndent()

    @Test fun `only bike stations are kept, with their counts and flags`() {
        val all = Transitous.parseBikeStations(lyon)
        assertNotNull(all)
        assertEquals(listOf("10036", "1001", "2039"), all!!.map { it.key.substringAfter('/') })
        val full = all[0]
        assertEquals("fr-stations-velov-de-la-metropole-de-lyon-disponibilites-temps-reel/10036", full.key)
        assertEquals("STALINGRAD", full.name)
        assertEquals("Boulevard Stalingrad/angle rue Charlie Chaplin", full.address)
        assertEquals(45.77435, full.lat, 1e-9)
        assertEquals(4.859157, full.lon, 1e-9)
        assertEquals(18, full.bikes)
        assertTrue(full.renting && full.returning)
        assertEquals("an empty station is still listed", 0, all[1].bikes)
        assertFalse("a station out of service rents nothing", all[2].renting)
        assertFalse(all[2].returning)
    }

    @Test fun `a feed with no dock counts leaves the docks unknown`() {
        // Vélo'v sends no per-type dock counts and the public instance no numDocksAvailable.
        assertTrue(Transitous.parseBikeStations(lyon)!!.all { it.docks == null })
        assertNull(Transitous.parseBikeStations(station("{}"))!!.single().docks)
    }

    @Test fun `one dock count repeated under each vehicle type is the largest, not the sum`() {
        val s = Transitous.parseBikeStations(station("""{"A":4,"B":4,"C":4}"""))!!.single()
        assertEquals(4, s.docks)
        assertEquals("Covell Park", s.name)
        assertNull("no address in the reply", s.address)
    }

    @Test fun `the station's own free-dock total wins when the reply has it`() {
        val s = Transitous.parseBikeStations(station("""{"A":4,"B":4,"C":4}""", ""","numDocksAvailable":9"""))!!.single()
        assertEquals(9, s.docks)
    }

    private fun one(formFactors: String, bikes: Int, docks: String) = """
        {"stations":[{"id":"9","providerId":"test-op","name":"Spot","lat":38.5449,"lon":-121.7405,
          "isRenting":true,"isReturning":true,"numVehiclesAvailable":$bikes,"formFactors":$formFactors,
          "vehicleTypesAvailable":{},"vehicleDocksAvailable":$docks,"bbox":[]}]}
    """.trimIndent()

    @Test fun `a free-floating parking zone or a whole service area is not a station`() {
        // Bikes and scooters at one spot with no dock count: an operator's parking zone.
        assertTrue(Transitous.parseBikeStations(one("""["BICYCLE","SCOOTER_STANDING"]""", 2, "{}"))!!.isEmpty())
        // The same mix at a station that counts its docks is a docked station.
        assertEquals(1, Transitous.parseBikeStations(one("""["BICYCLE","SCOOTER_STANDING"]""", 2, """{"A":5}"""))!!.size)
        // Thousands of bikes at one point: a service area.
        assertTrue(Transitous.parseBikeStations(one("""["BICYCLE"]""", 5887, "{}"))!!.isEmpty())
        assertEquals(1, Transitous.parseBikeStations(one("""["BICYCLE"]""", 60, "{}"))!!.size)
    }

    private fun grouped(group: String) = """
        {"stations":[{"id":"9","providerId":"test-op","providerGroupId":"$group","name":"id_59032","lat":38.5449,
          "lon":-121.7405,"isRenting":true,"isReturning":true,"numVehiclesAvailable":3,"formFactors":["BICYCLE"],
          "vehicleTypesAvailable":{},"vehicleDocksAvailable":{},"bbox":[]}]}
    """.trimIndent()

    @Test fun `a free-floating operator's parking spots are not stations`() {
        // Group names as the service sends them (central Paris, Bordeaux, Oslo, 2026-11-04).
        for (g in listOf("Dott paris", "Voi Technology AB", "VOI", "Ryde Oslo", "Lime Paris Proper", "pony", "Bolt")) {
            assertTrue(g, Transitous.parseBikeStations(grouped(g))!!.isEmpty())
        }
        // Public systems are named after the system; a longer word that starts with a brand is not it.
        for (g in listOf("Paris", "nextbike Berlin", "WienMobil Rad", "Villo!", "Blue-bike", "Oslo Bysykkel", "Limerick Bikes")) {
            assertEquals(g, 1, Transitous.parseBikeStations(grouped(g))!!.size)
        }
    }

    @Test fun `a changed reply is a failure, not an empty map`() {
        assertNull(Transitous.parseBikeStations("""{"error":"moved"}"""))
        assertNull(Transitous.parseBikeStations("not json"))
        assertEquals(emptyList<Transitous.BikeStation>(), Transitous.parseBikeStations("""{"stations":[]}"""))
    }
}
