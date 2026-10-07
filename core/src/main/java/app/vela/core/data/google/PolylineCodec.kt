package app.vela.core.data.google

import app.vela.core.model.LatLng

/**
 * Google's Encoded Polyline Algorithm Format. A stable, fully specified format — the geometry
 * inside a directions response is encoded exactly this way, so [decode] is one of the few pieces
 * here that needs no calibration.
 *
 * [precision] is the number of decimal places the coordinates were scaled by. Google always uses
 * 5, which is the default and what every Google call site wants. OSRM can emit either, and Vela
 * asks it for **6** — at 5, a latitude step is 1.11 m, so every route vertex is snapped to a
 * meter-ish grid and a physically straight road arrives visibly kinked. That quantization is
 * scatter the nav puck then has to smooth back out; asking for the extra digit removes it at the
 * source and costs nothing (same vertex count, ~15% more characters). The open transit planner
 * sends **7**.
 */
object PolylineCodec {

    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val scale = Math.pow(10.0, precision.toDouble())
        val path = ArrayList<LatLng>()
        var index = 0
        // Long, not Int: at 7 decimals a longitude past about 107 degrees doubles to more than an
        // Int holds in the zigzag step, and the whole line came out on another continent (found
        // 2026-10-07 on a San Francisco ride; New York decoded fine).
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0L
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            path.add(LatLng(lat / scale, lng / scale))
        }
        return path
    }

    fun encode(path: List<LatLng>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (p in path) {
            val lat = Math.round(p.lat * 1e5)
            val lng = Math.round(p.lng * 1e5)
            encodeSigned(lat - lastLat, sb)
            encodeSigned(lng - lastLng, sb)
            lastLat = lat
            lastLng = lng
        }
        return sb.toString()
    }

    private fun encodeSigned(vIn: Long, sb: StringBuilder) {
        var v = if (vIn < 0) (vIn shl 1).inv() else vIn shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }
}
