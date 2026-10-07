package app.vela.core.data.google

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylinePrecisionTest {
    /** A light rail leg under Market Street in San Francisco, as the open transit planner sends it
     *  (7 decimals). Decoded with 32-bit numbers its longitude overflowed and the line left the
     *  continent; past about 107 degrees east or west, every 7-decimal line did. */
    @Test fun sevenDecimalsFarFromGreenwich() {
        val pts = PolylineCodec.decode("""co|toUfnjv}gAk\fw@_nSw`YohRomWkxPgpU{sGofIcmKoiOk~Ho{KfJwj@""", 7)
        assertEquals(9, pts.size)
        assertEquals(37.784653, pts.first().lat, 1e-6)
        assertEquals(-122.40709, pts.first().lng, 1e-6)
        assertEquals(37.78922, pts.last().lat, 1e-6)
        assertEquals(-122.40135, pts.last().lng, 1e-6)
    }

    @Test fun fiveDecimalsStillRoundTrip() {
        val line = listOf(app.vela.core.model.LatLng(38.5449, -121.7405), app.vela.core.model.LatLng(35.6812, 139.7671))
        val back = PolylineCodec.decode(PolylineCodec.encode(line))
        assertEquals(139.7671, back[1].lng, 1e-5)
        assertEquals(38.5449, back[0].lat, 1e-5)
    }
}
