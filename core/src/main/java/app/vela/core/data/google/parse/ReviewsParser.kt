package app.vela.core.data.google.parse

import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.int
import app.vela.core.data.google.str
import app.vela.core.model.Review
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses Google's `/maps/preview/review/listentitiesreviews` response.
 *
 * Calibrated live (2026-06-16): reviews live at `root[2]`, each review rooted at
 * the entry — author `[0][1]`, author photo `[0][2]`, relative time `[1]`,
 * text `[3]`, rating (1..5) `[4]`. Rating-only reviews have a null text.
 *
 * User-attached photos hang under `[12]` (calibrated 2026-06-20). Rather than pin a
 * brittle leaf index (it nests differently for 1 vs N photos), we collect every
 * `googleusercontent`/`ggpht` image URL under that subtree — the author photo lives
 * at `[0][2]`, *outside* `[12]`, so it isn't swept in.
 */
object ReviewsParser {
    fun parse(root: JsonElement): List<Review> {
        val arr = root.at(2).arr() ?: return emptyList()
        return arr.mapNotNull { rv ->
            val author = rv.at(0, 1).str() ?: return@mapNotNull null
            Review(
                author = author,
                authorPhoto = rv.at(0, 2).str(),
                rating = rv.at(4).int() ?: 0,
                relativeTime = rv.at(1).str(),
                text = rv.at(3).str()?.ifBlank { null },
                photos = reviewPhotos(rv.at(12)),
            )
        }
    }

    /** All image URLs in the `[12]` photo subtree, thumbnail-resized + de-duped. */
    private fun reviewPhotos(node: JsonElement?): List<String> {
        node ?: return emptyList()
        val urls = LinkedHashSet<String>()
        fun walk(x: JsonElement?) {
            when (x) {
                is JsonArray -> x.forEach(::walk)
                is JsonPrimitive -> x.str()?.let { s ->
                    if (s.startsWith("http") && (s.contains("googleusercontent.com/") || s.contains("ggpht.com/"))) {
                        urls.add(resize(s))
                    }
                }
                else -> {}
            }
        }
        walk(node)
        return urls.toList().take(10)
    }

    /** Force a sane thumbnail size onto a FIFE URL: strip any trailing size directive
     *  (`=s…`, `=w…-h…-k-no`, …) and pin our own — strip-then-append so a URL that
     *  already carries a size can never end up double-sized. */
    private fun resize(u: String): String =
        u.replace(Regex("=[swh]\\d[\\w-]*$"), "") + "=w400-h400"
}
