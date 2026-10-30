package app.vela.core.data.google

import app.vela.core.data.DestinationPlaces
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A destination that is a Google place is asked for as the place. */
class DirectionsPlaceTest {
    private val davis = LatLng(38.5449, -121.7405)
    private val store = LatLng(38.5542886, -121.6997428)
    private val woodland = LatLng(38.6785, -121.7733)

    @After fun reset() = DestinationPlaces.clear()

    @Test fun `a place destination carries its text and id, as the web client sends it`() {
        val ref = DestinationPlaces.Ref("Target, 4601 2nd St, Davis, CA 95618", "0x80852bc9ab269187:0x4df403f6b25b049c")
        val pb = DirectionsPb.build(davis, store, TravelMode.DRIVE, place = ref)
        assertTrue(pb, pb.startsWith("!1m4!3m2!3d38.5449!4d-121.7405!6e2!1m6!1sTarget, 4601 2nd St, Davis, CA 95618!2s0x80852bc9ab269187:0x4df403f6b25b049c!3m2!3d38.5542886!4d-121.6997428!6e0!3m12"))
        // A stop stays a bare point before it.
        val via = DirectionsPb.build(davis, store, TravelMode.DRIVE, waypoints = listOf(woodland), place = ref)
        assertTrue(via, "!6e2!1m4!3m2!3d38.6785!4d-121.7733!6e2!1m6!1sTarget" in via)
        // No place: the bare point, as before.
        assertTrue(DirectionsPb.build(davis, store, TravelMode.DRIVE).startsWith("!1m4!3m2!3d38.5449!4d-121.7405!6e2!1m4!3m2!3d38.5542886!4d-121.6997428!6e2!3m12"))
    }

    @Test fun `a separator in the text is escaped`() {
        assertEquals("Yum*21 Cafe *2A2", DirectionsPb.pbText("Yum! Cafe *2"))
    }

    @Test fun `the place is found by its point, and only there`() {
        DestinationPlaces.remember(store, "Target", "0x1:0x2")
        assertEquals("0x1:0x2", DestinationPlaces.at(LatLng(store.lat + 0.00001, store.lng))?.featureId)
        assertNull(DestinationPlaces.at(LatLng(store.lat + 0.001, store.lng)))
        DestinationPlaces.remember(store, "", "0x3:0x4")
        assertEquals("a blank text is not kept", "0x1:0x2", DestinationPlaces.at(store)?.featureId)
        assertFalse(DirectionsPb.withPlace("!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2", DestinationPlaces.at(store), DirectionsPb.DEFAULT_PLACE_GROUP).contains("0x1"))
    }
}
