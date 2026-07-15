package app.vela.core.data.google

import app.vela.core.model.StreetViewLink
import app.vela.core.model.StreetViewPano
import app.vela.core.model.StreetViewTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parses the keyless `GeoPhotoService.SingleImageSearch` response into a [StreetViewPano].
 *
 * Everything hangs off the pano node (root[1]); the shape (SF capture 2026-07-15):
 *   [1][1][1]           panoId
 *   [1][2][3][1]        tile size, [1][2][3][0] the zoom pyramid
 *   [1][3][2][0][0]     address label
 *   [1][4][0][0][0]     copyright
 *   [1][5][0][1]        position: [ [_,_,lat,lng], _, [heading,tilt,roll] ]
 *   [1][5][0][3][0]     the local pano graph (~100 nearby panos, each [[2,id],_,[[_,_,lat,lng]…]])
 *   [1][5][0][8]        history stack: [ [neighbourIndex,[year,month],…], … ]
 *   [1][6][7]           THIS pano's capture [year, month]
 *
 * Only the pano id is required; everything else falls back. A "no imagery" response has no
 * pano node → null.
 */
object StreetViewParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PANO_ID = Regex("^[A-Za-z0-9_-]{20,25}$")

    // Neighbour de-clutter: drop the same-spot historical panos, cap how far a "walk" arrow
    // reaches, and keep only the nearest pano per direction bucket so arrows don't pile up.
    private const val SAME_SPOT_M = 4.0
    private const val MAX_WALK_M = 45.0
    private const val BUCKET_DEG = 30.0

    fun parse(raw: String, lat: Double, lng: Double): StreetViewPano? {
        val root = runCatching { json.parseToJsonElement(unwrap(raw)) }.getOrNull() ?: return null
        // Two endpoints, two nestings: SingleImageSearch (lat/lng lookup, cb() wrapper) puts the pano
        // node at root[1]; photometa/v1 (by-panoid, )]}' guard) wraps it one deeper at root[1][0].
        // Pick whichever actually carries the [1][1] pano id.
        val panoNode = root.at(1).takeIf { it.at(1, 1).str()?.let(PANO_ID::matches) == true }
            ?: root.at(1, 0)
        val panoId = panoNode.at(1, 1).str()?.takeIf { PANO_ID.matches(it) }
            ?: panoNode.findString { PANO_ID.matches(it) }
            ?: return null

        val tileSize = panoNode.at(2, 3, 1, 0).int() ?: panoNode.at(2, 3, 1, 1).int() ?: 512
        val levels = (panoNode.at(2, 3, 0) as? JsonArray)?.size ?: 6

        val posNode = panoNode.at(5, 0, 1)
        val pLat = posNode.at(0, 2).dbl() ?: lat
        val pLng = posNode.at(0, 3).dbl() ?: lng
        val heading = posNode.at(2, 0).dbl() ?: 0.0

        val address = panoNode.at(3, 2, 0, 0).str()
        val copyright = panoNode.at(4, 0, 0, 0, 0).str()
        val year = panoNode.at(6, 7, 0).int()
        val month = panoNode.at(6, 7, 1).int()

        // Raw local graph: [ [2,id], _, [ [_,_,lat,lng], … ] ] each. Index 0 is this pano itself.
        val graph = (panoNode.at(5, 0, 3, 0) as? JsonArray).orEmpty()
        data class Raw(val id: String, val la: Double, val ln: Double)
        val raws = graph.mapNotNull { g ->
            val id = g.at(0, 1).str()?.takeIf { PANO_ID.matches(it) } ?: return@mapNotNull null
            val gLa = g.at(2, 0, 2).dbl() ?: return@mapNotNull null
            val gLn = g.at(2, 0, 3).dbl() ?: return@mapNotNull null
            Raw(id, gLa, gLn)
        }

        // History: [ [neighbourIndex, [year,month], …], … ] indexes into the raw graph. Prepend
        // this pano as the newest so the list is the full "other dates" set.
        val history = buildList {
            if (year != null && month != null) add(StreetViewTime(panoId, year, month))
            (panoNode.at(5, 0, 8) as? JsonArray)?.forEach { h ->
                val idx = h.at(0).int() ?: return@forEach
                val hy = h.at(1, 0).int() ?: return@forEach
                val hm = h.at(1, 1).int() ?: return@forEach
                graph.getOrNull(idx)?.at(0, 1)?.str()?.let { hid ->
                    if (PANO_ID.matches(hid)) add(StreetViewTime(hid, hy, hm))
                }
            }
        }.distinctBy { it.panoId }.sortedByDescending { it.year * 100 + it.month }

        // Walkable neighbours: distance + bearing from this pano, drop same-spot (historical) and
        // far panos, keep the nearest per BUCKET_DEG sector.
        val walk = raws.asSequence()
            .filter { it.id != panoId }
            .map { r ->
                val dm = haversine(pLat, pLng, r.la, r.ln)
                val bd = bearing(pLat, pLng, r.la, r.ln)
                StreetViewLink(r.id, r.la, r.ln, bd, dm)
            }
            .filter { it.distanceM in SAME_SPOT_M..MAX_WALK_M }
            .sortedBy { it.distanceM }
            .fold(mutableListOf<StreetViewLink>()) { keep, link ->
                if (keep.none { abs(angleDelta(it.bearingDeg, link.bearingDeg)) < BUCKET_DEG }) keep.add(link)
                keep
            }
            .sortedBy { it.bearingDeg }

        return StreetViewPano(
            panoId = panoId, lat = pLat, lng = pLng, headingDeg = heading,
            tileSize = tileSize, maxZoom = levels, addressLabel = address, copyright = copyright,
            captureYear = year, captureMonth = month, neighbors = walk, history = history,
        )
    }

    private fun unwrap(raw: String): String {
        val t = raw.trim()
        // photometa/v1 uses the XSSI guard )]}' then the JSON array.
        if (t.startsWith(")]}'")) return t.substring(4).trimStart('\n', '\r', ' ')
        // SingleImageSearch uses a /**/cb && cb( … ) callback wrapper.
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        return if (open in 0 until close) t.substring(open + 1, close).trim() else t
    }

    private fun haversine(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLng = Math.toRadians(bLng - aLng)
        val y = sin(dLng) * cos(Math.toRadians(bLat))
        val x = cos(Math.toRadians(aLat)) * sin(Math.toRadians(bLat)) -
            sin(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Signed smallest difference a→b in degrees, in [-180, 180]. */
    private fun angleDelta(a: Double, b: Double): Double {
        var d = (b - a + 540) % 360 - 180
        if (d < -180) d += 360
        return d
    }
}
