package app.vela.core.data.google

import app.vela.core.config.Calibration
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestShapeTest {
    @Test fun `reqid climbs by 100000 from a random start`() {
        val a = RequestShape.nextReqId()
        val b = RequestShape.nextReqId()
        assertEquals(100_000, b - a)
        assertTrue(a % 100_000 in 10_000..99_999)
    }

    @Test fun `ech counts up`() {
        val a = RequestShape.nextEch()
        assertEquals(a + 1, RequestShape.nextEch())
    }

    @Test fun `callback names look like the Maps JavaScript ones and differ`() {
        val a = RequestShape.callbackName()
        assertTrue(Regex("""_xdc_\._[a-z0-9]{6}""").matches(a))
        assertFalse((1..20).all { RequestShape.callbackName() == a })
    }

    @Test fun `batch urls get a live reqid, an encoded source path and a gl`() {
        val u = RequestShape.batchUrl(Calibration.DEFAULT.photosEndpoint)
        assertFalse(u.contains("_reqid=1&"))
        assertTrue(u.contains("source-path=%2Fmaps"))
        assertTrue(u.contains("&gl=us"))
        // A template that already names a region keeps it.
        assertEquals(1, Regex("gl=").findAll(RequestShape.batchUrl("https://x/b?rpcids=a&hl=en&gl=de&_reqid=5&rt=c")).count())
    }

    @Test fun `directions viewport sits on the trip, not on the captured Davis window`() {
        val sf = LatLng(37.7749, -122.4194)
        val oak = LatLng(37.8044, -122.2712)
        val pb = DirectionsPb.build(sf, oak, TravelMode.DRIVE, Calibration.DEFAULT.directionsPb)
        assertFalse(pb.contains("-121.7527808"))
        val m = Regex("""!3m12!1m3!1d([0-9.]+)!2d(-?[0-9.]+)!3d(-?[0-9.]+)""").find(pb)!!
        assertEquals((sf.lng + oak.lng) / 2, m.groupValues[2].toDouble(), 1e-9)
        assertEquals((sf.lat + oak.lat) / 2, m.groupValues[3].toDouble(), 1e-9)
        assertTrue(m.groupValues[1].toDouble() > 13_000) // ~13 km apart, with a margin
    }

    @Test fun `a short trip still gets a sensible window`() {
        val a = LatLng(38.5449, -121.7405)
        val pb = RequestShape.fitDirections("!3m12!1m3!1d24960.7!2d-121.75!3d38.55!2m3", listOf(a, a))
        val d = Regex("""!1d([0-9.]+)!2d""").find(pb)!!.groupValues[1].toDouble()
        assertEquals(1500.0, d, 1500.0 * 0.003)
        assertTrue(pb.contains("!2d-121.7405!3d38.5449"))
    }

    @Test fun `a recalibrated template without the viewport group is left alone`() {
        val pb = "!1m4!3m2!3d{OLAT}!4d{OLNG}"
        assertEquals(pb, RequestShape.fitDirections(pb, listOf(LatLng(1.0, 2.0))))
    }

    @Test fun `spans are long decimals near the asked value and never repeat`() {
        val a = RequestShape.span(3000.0).toDouble()
        assertEquals(3000.0, a, 3000.0 * 0.003)
        assertTrue(RequestShape.span(3000.0).contains('.'))
        assertFalse((1..20).all { RequestShape.span(3000.0).toDouble() == a })
        val pb = SearchPb.build("coffee", LatLng(38.5, -121.7), Calibration.DEFAULT.searchPb)
        assertFalse(pb.contains("25229.167291701906"))
    }
}
