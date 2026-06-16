package app.vela.core.data.google

import app.vela.core.model.LatLng

/**
 * Google's Encoded Polyline Algorithm Format (1e5 precision). A stable, fully
 * specified format — the geometry inside a directions response is encoded
 * exactly this way, so [decode] is one of the few pieces here that needs no
 * calibration.
 */
object PolylineCodec {

    fun decode(encoded: String): List<LatLng> {
        val path = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            path.add(LatLng(lat / 1e5, lng / 1e5))
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
