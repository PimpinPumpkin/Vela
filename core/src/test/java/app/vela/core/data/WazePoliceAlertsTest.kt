package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazePoliceAlertsTest {

    @Test
    fun `envFor maps regions accurately`() {
        // Davis/Sacramento, CA (North America)
        assertEquals("na", WazePoliceAlerts.envFor(38.5449, -121.7405))
        // New York (North America)
        assertEquals("na", WazePoliceAlerts.envFor(40.7128, -74.0060))
        // Tel Aviv (Israel)
        assertEquals("il", WazePoliceAlerts.envFor(32.0853, 34.7818))
        // London (Rest of World)
        assertEquals("row", WazePoliceAlerts.envFor(51.5074, -0.1278))
        // Tokyo (Rest of World)
        assertEquals("row", WazePoliceAlerts.envFor(35.6762, 139.6503))
    }

    @Test
    fun `parseAlerts filters for police and extracts location correctly`() {
        val now = System.currentTimeMillis()
        val recentPub = now - 5 * 60 * 1000L
        val sampleJson = """
        {
          "alerts": [
            {
              "id": "alert-101",
              "type": "POLICE",
              "subtype": "POLICE_VISIBLE",
              "street": "I-80 W",
              "city": "Davis, CA",
              "location": { "x": -121.7405, "y": 38.5449 },
              "reliability": 7,
              "confidence": 1,
              "nThumbsUp": 5,
              "pubMillis": $recentPub
            },
            {
              "id": "alert-102",
              "type": "ACCIDENT",
              "subtype": "ACCIDENT_MINOR",
              "street": "5th St",
              "location": { "x": -121.75, "y": 38.55 }
            },
            {
              "uuid": "uuid-103",
              "type": "POLICE",
              "subtype": "POLICE_HIDING",
              "street": "Russell Blvd",
              "latitude": 38.5420,
              "longitude": -121.7620,
              "nThumbsUp": 2
            },
            {
              "id": "alert-104",
              "type": "HAZARD",
              "subtype": "HAZARD_ON_ROAD"
            }
          ]
        }
        """.trimIndent()

        val policeOnly = WazePoliceAlerts.parseAlerts(sampleJson, allowedCategories = setOf(WazeAlertCategory.POLICE))

        assertEquals(2, policeOnly.size)

        val first = policeOnly[0]
        assertEquals("alert-101", first.id)
        assertEquals("POLICE", first.type)
        assertEquals(WazeAlertCategory.POLICE, first.category())
        assertEquals("POLICE_VISIBLE", first.subtype)
        assertEquals("I-80 W", first.street)
        assertEquals("Davis, CA", first.city)
        assertEquals(38.5449, first.loc.lat, 1e-4)
        assertEquals(-121.7405, first.loc.lng, 1e-4)
        assertEquals(7, first.reliability)
        assertEquals(5, first.nThumbsUp)
        assertEquals(recentPub, first.pubMillis)

        val second = policeOnly[1]
        assertEquals("uuid-103", second.id)
        assertEquals("POLICE_HIDING", second.subtype)
        assertEquals("Russell Blvd", second.street)
        assertEquals(38.5420, second.loc.lat, 1e-4)
        assertEquals(-121.7620, second.loc.lng, 1e-4)
        assertEquals(2, second.nThumbsUp)

        // All categories: includes accident as well
        val allAlerts = WazePoliceAlerts.parseAlerts(sampleJson)
        assertEquals(3, allAlerts.size)
        assertTrue(allAlerts.any { it.category() == WazeAlertCategory.ACCIDENT && it.id == "alert-102" })
    }

    @Test
    fun `parseAlerts handles empty or missing alerts gracefully`() {
        val empty = WazePoliceAlerts.parseAlerts("""{"alerts": []}""")
        assertTrue(empty.isEmpty())

        val noAlerts = WazePoliceAlerts.parseAlerts("{}")
        assertTrue(noAlerts.isEmpty())

        // 403 Forbidden HTML error response must not throw
        val htmlError = "<!doctype html><title>403</title>403 Forbidden"
        val fromHtml = WazePoliceAlerts.parseAlerts(htmlError)
        assertTrue(fromHtml.isEmpty())
    }

    @Test
    fun `parseAlerts prunes unconfirmed reports older than 30 minutes`() {
        val now = System.currentTimeMillis()
        val oldPub = now - 35 * 60 * 1000L // 35 min ago (e.g. Argyroupolis report)
        val freshPub = now - 10 * 60 * 1000L // 10 min ago

        val sampleJson = """
        {
          "alerts": [
            {
              "id": "stale-unconfirmed",
              "type": "POLICE",
              "subtype": "POLICE_WITH_MOBILE_CAMERA",
              "location": { "x": 23.75, "y": 37.90 },
              "nThumbsUp": 0,
              "pubMillis": $oldPub
            },
            {
              "id": "stale-confirmed",
              "type": "POLICE",
              "subtype": "POLICE_VISIBLE",
              "location": { "x": 23.76, "y": 37.91 },
              "nThumbsUp": 1,
              "pubMillis": $oldPub
            },
            {
              "id": "fresh-unconfirmed",
              "type": "POLICE",
              "subtype": "POLICE_HIDING",
              "location": { "x": 23.77, "y": 37.92 },
              "nThumbsUp": 0,
              "pubMillis": $freshPub
            }
          ]
        }
        """.trimIndent()

        val alerts = WazePoliceAlerts.parseAlerts(sampleJson)
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it.id == "stale-confirmed" })
        assertTrue(alerts.any { it.id == "fresh-unconfirmed" })
        assertTrue(alerts.none { it.id == "stale-unconfirmed" })
    }

    @Test
    fun `isExpired prunes unconfirmed reports past 25 min and confirmed past 40 min`() {
        val now = System.currentTimeMillis()
        val unconfirmedFresh = WazePoliceAlert(id = "1", loc = LatLng(0.0, 0.0), nThumbsUp = 0, pubMillis = now - 20 * 60 * 1000L)
        val unconfirmedStale = WazePoliceAlert(id = "2", loc = LatLng(0.0, 0.0), nThumbsUp = 0, pubMillis = now - 26 * 60 * 1000L)
        val confirmedFresh = WazePoliceAlert(id = "3", loc = LatLng(0.0, 0.0), nThumbsUp = 2, pubMillis = now - 35 * 60 * 1000L)
        val confirmedStale = WazePoliceAlert(id = "4", loc = LatLng(0.0, 0.0), nThumbsUp = 2, pubMillis = now - 45 * 60 * 1000L)

        org.junit.Assert.assertFalse(unconfirmedFresh.isExpired(now))
        assertTrue(unconfirmedStale.isExpired(now))
        org.junit.Assert.assertFalse(confirmedFresh.isExpired(now))
        assertTrue(confirmedStale.isExpired(now))
    }

    @Test
    fun `parseAlerts extracts jams array correctly`() {
        val now = System.currentTimeMillis()
        val json = """
        {
          "alerts": [],
          "jams": [
            {
              "uuid": "jam-1",
              "street": "Siggrou",
              "city": "Athens",
              "level": 3,
              "line": [
                { "x": 23.71, "y": 37.95 },
                { "x": 23.72, "y": 37.96 }
              ],
              "pubMillis": ${now - 60_000L}
            }
          ]
        }
        """.trimIndent()

        val parsed = WazePoliceAlerts.parseAlerts(json)
        assertEquals(1, parsed.size)
        val jam = parsed[0]
        assertEquals("jam-1", jam.id)
        assertEquals("JAM", jam.type)
        assertEquals(WazeAlertCategory.JAM, jam.category())
        assertEquals("JAM_LEVEL_3", jam.subtype)
        assertEquals("Siggrou", jam.street)
        assertEquals(37.96, jam.loc.lat, 1e-4)
        assertEquals(23.72, jam.loc.lng, 1e-4)
    }

    @Test
    fun `isCamera identifies camera and speed cam alerts`() {
        val camAlert = WazePoliceAlert(id = "c1", loc = LatLng(0.0, 0.0), type = "CAMERA", subtype = "CAMERA_SPEED")
        val redLightAlert = WazePoliceAlert(id = "c2", loc = LatLng(0.0, 0.0), type = "CAMERA", subtype = "CAMERA_RED_LIGHT")
        val speedCamAlert = WazePoliceAlert(id = "c3", loc = LatLng(0.0, 0.0), type = "SPEED_CAM")
        val policeAlert = WazePoliceAlert(id = "p1", loc = LatLng(0.0, 0.0), type = "POLICE", subtype = "POLICE_VISIBLE")

        assertTrue(camAlert.isCamera())
        assertTrue(redLightAlert.isCamera())
        assertTrue(speedCamAlert.isCamera())
        org.junit.Assert.assertFalse(policeAlert.isCamera())
    }

    @Test
    fun `isPothole identifies POT_HOLE and POTHOLE subtypes`() {
        val wazeStandard = WazePoliceAlert(id = "1", loc = LatLng(0.0, 0.0), type = "HAZARD", subtype = "HAZARD_ON_ROAD_POT_HOLE")
        val altPothole = WazePoliceAlert(id = "2", loc = LatLng(0.0, 0.0), type = "HAZARD", subtype = "HAZARD_ON_ROAD_POTHOLE")
        val genericHazard = WazePoliceAlert(id = "3", loc = LatLng(0.0, 0.0), type = "HAZARD", subtype = "HAZARD_ON_ROAD_CAR_STOPPED")

        assertTrue(wazeStandard.isPothole())
        assertTrue(altPothole.isPothole())
        org.junit.Assert.assertFalse(genericHazard.isPothole())
    }
}

