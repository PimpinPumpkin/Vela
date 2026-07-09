package app.vela.core.data.google.parse

import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.str
import app.vela.core.model.ImportedList
import app.vela.core.model.LatLng
import app.vela.core.model.Place

/**
 * Parses a Google Maps SHARED LIST ("Import Me to Vela!", issue #1) from the keyless
 * `/maps/preview/entitylist/getlist` RPC. The response (after the `)]}'` guard) is:
 *
 *   root[0][4] = list title · [5] = description · [3] = [author name, avatar, id] ·
 *   [8] = items, each:
 *     [1] = place node: [4] = full address · [5] = [_,_,lat,lng] ·
 *           [6] = feature id as a DECIMAL signed-int64 PAIR (two's-complement hex of
 *                 [hi, lo] is the familiar "0x..:0x.." id the reviews RPC takes)
 *     [2] = display name · [3] = the list owner's personal NOTE on the place
 *
 * Calibrated live 2026-07-08 against a public shared list. Best-effort throughout:
 * a malformed item is skipped, a malformed list returns null, never a throw.
 */
object EntityListParser {

    fun parse(raw: String): ImportedList? = runCatching {
        val root = GoogleResponse.parse(raw).at(0) ?: return null
        val title = root.at(4).str()?.trim()?.ifBlank { null } ?: return null
        val description = root.at(5).str()?.trim()?.ifBlank { null }
        val author = root.at(3, 0).str()?.trim()?.ifBlank { null }
        val places = root.at(8).arr().orEmpty().mapNotNull { item ->
            val node = item.at(1) ?: return@mapNotNull null
            val lat = node.at(5, 2).dbl() ?: return@mapNotNull null
            val lng = node.at(5, 3).dbl() ?: return@mapNotNull null
            val name = item.at(2).str()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val fid = featureId(node.at(6, 0).str(), node.at(6, 1).str())
            Place(
                id = "import:" + (fid ?: "$lat,$lng"),
                name = name,
                location = LatLng(lat, lng),
                address = node.at(4).str()?.trim()?.ifBlank { null },
                featureId = fid,
                savedNote = item.at(3).str()?.trim()?.ifBlank { null },
            )
        }
        ImportedList(title = title, description = description, author = author, places = places)
    }.getOrNull()

    /** ["-8654527782550677995","-8127173087487969012"] → "0x781f4e...:0x8f..." — the decimal
     *  signed int64 pair rendered as two's-complement hex is the standard feature id. */
    private fun featureId(hi: String?, lo: String?): String? {
        val h = hi?.toLongOrNull() ?: return null
        val l = lo?.toLongOrNull() ?: return null
        return "0x${java.lang.Long.toHexString(h)}:0x${java.lang.Long.toHexString(l)}"
    }
}
