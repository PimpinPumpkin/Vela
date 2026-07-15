package app.vela.core.data.google

import app.vela.core.model.StreetViewPano
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Parses the keyless `GeoPhotoService.SingleImageSearch` response into a [StreetViewPano].
 *
 * The response is a JSONP-ish callback (`/**/cb && cb( <array> )`) wrapping the same kind of
 * deeply-nested positional array the rest of the scrape deals with, so the same defensiveness
 * applies: only the pano id is required, every other field falls back. Shape for reference
 * (SF capture 2026-07-15):
 *
 *   [ [0],
 *     [ [1], [2,"<panoId>"], [2,2,[H,W],[ [pyramid…], [tileH,tileW] ], …, "<panoId>"],
 *       … ],
 *     [null,null,[["San Francisco, California","en"]]],   // [3] address label
 *     [[[["© 2026 Google"]]]],                             // [4] copyright
 *     [[[1],[[null,null,LAT,LNG],…,[HEADING,TILT,ROLL],…], …neighbour links… ]] ]  // [5]
 *
 * A "no imagery here" response carries no pano node, so [parse] returns null - the caller
 * hides the Street View entry rather than showing a dead one.
 */
object StreetViewParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PANO_ID = Regex("^[A-Za-z0-9_-]{20,25}$")

    /** [raw] is the full `/**/cb && cb( … )` body. Returns null when there's no pano. */
    fun parse(raw: String, lat: Double, lng: Double): StreetViewPano? {
        val root = runCatching { json.parseToJsonElement(unwrap(raw)) }.getOrNull() ?: return null

        // Pano id: the [2,"<id>"] pair under the pano node, else a shape search for the 22-ish char
        // base64url id anywhere in that node (survives an index shift).
        val panoNode = root.at(1)
        val panoId = panoNode.at(1, 1).str()?.takeIf { PANO_ID.matches(it) }
            ?: panoNode.findString { PANO_ID.matches(it) }
            ?: return null

        // Tile geometry: [1][2][3][1] = [tileH, tileW]; the pyramid at [1][2][3][0] gives the zoom
        // levels. Both default to the standard SV values if the shape drifted.
        val tileSize = panoNode.at(2, 3, 1, 0).int()
            ?: panoNode.at(2, 3, 1, 1).int() ?: 512
        val levels = (panoNode.at(2, 3, 0) as? JsonArray)?.size ?: 6

        // Address, copyright and position all live INSIDE the pano node (root[1]), not at root
        // level - root itself is just [status, panoNode, responseMeta]. Position [1][5][0][1] is
        // [ [_,_,lat,lng], _, [heading,tilt,roll] ].
        val posNode = panoNode.at(5, 0, 1)
        val pLat = posNode.at(0, 2).dbl() ?: lat
        val pLng = posNode.at(0, 3).dbl() ?: lng
        val heading = posNode.at(2, 0).dbl() ?: 0.0

        val address = panoNode.at(3, 2, 0, 0).str()
        val copyright = panoNode.at(4, 0, 0, 0, 0).str()

        return StreetViewPano(
            panoId = panoId,
            lat = pLat,
            lng = pLng,
            headingDeg = heading,
            tileSize = tileSize,
            maxZoom = levels,
            addressLabel = address,
            copyright = copyright,
        )
    }

    /** Strip the `/**/<fn> && <fn>(` prefix and the trailing `)` around the JSON array. */
    private fun unwrap(raw: String): String {
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        return if (open in 0 until close) raw.substring(open + 1, close).trim() else raw.trim()
    }
}
