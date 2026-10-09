package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NdwBridgeOpeningsTest {
    // The feed's shape (DATEX II v3, trimmed), one record per kind, at Amsterdam coordinates.
    private fun record(id: String, status: String, start: String, end: String?, lat: String = "52.3731", lng: String = "4.8922", type: String = "bridgeSwingInOperation") = """
        <sit:situation id="$id"><sit:situationRecord xsi:type="sit:GeneralNetworkManagement" id="${id}_01" version="1">
        <sit:probabilityOfOccurrence>riskOf</sit:probabilityOfOccurrence>
        <sit:validity><com:validityStatus>definedByValidityTimeSpec</com:validityStatus><com:validityTimeSpecification>
        <com:overallStartTime>$start</com:overallStartTime>${if (end != null) "<com:overallEndTime>$end</com:overallEndTime>" else ""}
        </com:validityTimeSpecification></sit:validity>
        <sit:locationReference xsi:type="loc:PointLocation"><loc:pointByCoordinates><loc:pointCoordinates>
        <loc:latitude>$lat</loc:latitude><loc:longitude>$lng</loc:longitude>
        </loc:pointCoordinates></loc:pointByCoordinates></sit:locationReference>
        <sit:operatorActionStatus>$status</sit:operatorActionStatus>
        <sit:generalNetworkManagementType>$type</sit:generalNetworkManagementType>
        </sit:situationRecord></sit:situation>
    """.trimIndent()

    private fun feed(vararg records: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <mc:messageContainer xmlns:sit="http://datex2.eu/schema/3/situation" xmlns:mc="http://datex2.eu/schema/3/messageContainer" xmlns:loc="http://datex2.eu/schema/3/locationReferencing" xmlns:com="http://datex2.eu/schema/3/common">
        <mc:payload xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="sit:SituationPublication">
        <com:publicationTime>2026-10-09T08:08:00.001119015Z</com:publicationTime>
        ${records.joinToString("\n")}
        </mc:payload></mc:messageContainer>""".byteInputStream()

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun parsesPlannedAndOpenNow() {
        val out = NdwBridgeOpenings.parse(feed(
            record("A", "approved", "2026-10-09T09:23:00Z", "2026-10-09T09:29:00Z"),
            record("B", "implemented", "2026-10-09T08:05:12.5Z", null, lat = "52.3600", lng = "4.9000"),
        ))
        assertEquals(2, out.size)
        val a = out[0]
        assertEquals(LatLng(52.3731, 4.8922), a.loc)
        assertEquals(ms("2026-10-09T09:23:00Z"), a.startMs)
        assertEquals(ms("2026-10-09T09:29:00Z"), a.endMs)
        assertFalse(a.openNow)
        val b = out[1]
        assertTrue(b.openNow)
        assertNull(b.endMs)
        assertEquals(LatLng(52.36, 4.9), b.loc)
    }

    @Test fun skipsEndingOtherKindsAndBrokenRecords() {
        val out = NdwBridgeOpenings.parse(feed(
            record("A", "beingTerminated", "2026-10-09T09:23:00Z", null),
            record("B", "approved", "2026-10-09T09:23:00Z", "2026-10-09T09:29:00Z", type = "laneClosures"),
            record("C", "approved", "not a time", "2026-10-09T09:29:00Z"),
            record("D", "approved", "2026-10-09T09:23:00Z", "2026-10-09T09:29:00Z", lat = ""),
        ))
        assertTrue(out.isEmpty())
    }

    @Test fun netherlandsBox() {
        assertTrue(NdwBridgeOpenings.inNetherlands(LatLng(52.3731, 4.8922))) // Amsterdam
        assertFalse(NdwBridgeOpenings.inNetherlands(LatLng(48.8566, 2.3522))) // Paris
        assertFalse(NdwBridgeOpenings.inNetherlands(LatLng(38.5449, -121.7405))) // Davis
    }

    // Alerts. The route has 20 km left, driven at 72 km/h: 1000 s, so 5 km takes 250 s.
    private val now = ms("2026-10-09T09:00:00Z")
    private val remM = 20_000.0
    private val remS = 1_000.0
    private val here = LatLng(52.3731, 4.8922)
    private fun planned(startOffsetS: Long, lenS: Long = 360) =
        BridgeOpening(here, now + startOffsetS * 1000, now + (startOffsetS + lenS) * 1000, openNow = false)
    private val openNow = BridgeOpening(here, now - 60_000, null, openNow = true)

    @Test fun openNowWithinReach() {
        val bridges = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(openNow)))
        assertEquals(BridgeAlerts.Alert.OpenNow(0), BridgeAlerts.due(bridges, 0.0, remM, remS, now, emptySet()))
    }

    // The feed keeps some "open" records that were never closed; an hour-old one is not open.
    @Test fun staleOpenNowIsIgnored() {
        val stale = BridgeOpening(here, now - 9 * 24 * 3_600_000L, null, openNow = true)
        assertNull(BridgeAlerts.due(listOf(BridgeAlerts.OnRoute(4_000.0, listOf(stale))), 0.0, remM, remS, now, emptySet()))
        val hourOld = BridgeOpening(here, now - 61 * 60_000L, null, openNow = true)
        assertNull(BridgeAlerts.due(listOf(BridgeAlerts.OnRoute(4_000.0, listOf(hourOld))), 0.0, remM, remS, now, emptySet()))
        val withFresh = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(stale, openNow)))
        assertEquals(BridgeAlerts.Alert.OpenNow(0), BridgeAlerts.due(withFresh, 0.0, remM, remS, now, emptySet()))
    }

    @Test fun tooFarOrBehindIsQuiet() {
        val far = listOf(BridgeAlerts.OnRoute(6_000.0, listOf(openNow)))
        assertNull(BridgeAlerts.due(far, 0.0, remM, remS, now, emptySet()))
        val behind = listOf(BridgeAlerts.OnRoute(1_000.0, listOf(openNow)))
        assertNull(BridgeAlerts.due(behind, 1_500.0, remM, remS, now, emptySet()))
    }

    @Test fun plannedOpeningAroundArrival() {
        // 4 km ahead: reached in 200 s. An opening starting at 300 s is within the 3 minute margin.
        val soon = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(planned(300))))
        assertEquals(BridgeAlerts.Alert.Planned(0, now + 300_000), BridgeAlerts.due(soon, 0.0, remM, remS, now, emptySet()))
        // One that starts 10 minutes after you pass is not.
        val later = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(planned(800))))
        assertNull(BridgeAlerts.due(later, 0.0, remM, remS, now, emptySet()))
        // Nor one that ended well before you get there.
        val over = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(planned(-600))))
        assertNull(BridgeAlerts.due(over, 0.0, remM, remS, now, emptySet()))
    }

    @Test fun onceEachAndOpenAfterPlanned() {
        val bridges = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(planned(300))))
        val said = setOf(BridgeAlerts.key(0, false))
        assertNull(BridgeAlerts.due(bridges, 0.0, remM, remS, now, said))
        // The planned opening began: it is announced again, as open.
        val opened = listOf(BridgeAlerts.OnRoute(4_000.0, listOf(openNow)))
        assertEquals(BridgeAlerts.Alert.OpenNow(0), BridgeAlerts.due(opened, 0.0, remM, remS, now, said))
        assertNull(BridgeAlerts.due(opened, 0.0, remM, remS, now, said + BridgeAlerts.key(0, true)))
    }

    @Test fun nearestDueBridgeFirst() {
        val bridges = listOf(
            BridgeAlerts.OnRoute(2_000.0, listOf(planned(900))),
            BridgeAlerts.OnRoute(3_000.0, listOf(openNow)),
        )
        assertEquals(BridgeAlerts.Alert.OpenNow(1), BridgeAlerts.due(bridges, 0.0, remM, remS, now, emptySet()))
    }
}
