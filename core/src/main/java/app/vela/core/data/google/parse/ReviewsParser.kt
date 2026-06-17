package app.vela.core.data.google.parse

import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.int
import app.vela.core.data.google.str
import app.vela.core.model.Review
import kotlinx.serialization.json.JsonElement

/**
 * Parses Google's `/maps/preview/review/listentitiesreviews` response.
 *
 * Calibrated live (2026-06-16): reviews live at `root[2]`, each review rooted at
 * the entry — author `[0][1]`, author photo `[0][2]`, relative time `[1]`,
 * text `[3]`, rating (1..5) `[4]`. Rating-only reviews have a null text.
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
            )
        }
    }
}
