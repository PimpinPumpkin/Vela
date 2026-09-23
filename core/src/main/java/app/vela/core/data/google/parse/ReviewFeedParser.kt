package app.vela.core.data.google.parse

import app.vela.core.model.Review
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** One page of Google's review feed. [limited] = Google's limited view for this session or network:
 *  a short list (5 in every capture) and no further pages. */
data class ReviewFeed(val reviews: List<Review>, val limited: Boolean)

/**
 * The review feed RPC (`batchexecute?rpcids=qv9Egd`), the request Google's own place page makes
 * for its Reviews tab. Envelope `)]}'` + chunked `[["wrb.fr","qv9Egd","<payload json>",...]]`.
 * Payload (captured 2026-09-23): [2] the reviews, [5] true + [6] [true] in the limited view.
 * Each review: [0][0] id, [0][1][4][5][0] author, [0][1][4][5][1] avatar, [0][1][6] "7 months ago",
 * [0][2][0][0] stars, [0][2][15][0][0] text, [0][2][2][k][1][6][0] the review's photos.
 * An empty payload (`[null,null,null,null,null,true]`) is what a request WITHOUT the
 * `x-maps-diversion-context-bin` header gets.
 */
object ReviewFeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawBody: String): ReviewFeed? {
        val line = rawBody.lineSequence().firstOrNull { it.startsWith("[[\"wrb.fr\"") } ?: return null
        val row = runCatching { json.parseToJsonElement(line) as JsonArray }.getOrNull()?.getOrNull(0) as? JsonArray ?: return null
        val payloadStr = (row.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val payload = runCatching { json.parseToJsonElement(payloadStr) as JsonArray }.getOrNull() ?: return null
        val limited = (payload.getOrNull(5) as? JsonPrimitive)?.booleanOrNull == true
        val list = payload.getOrNull(2) as? JsonArray ?: return ReviewFeed(emptyList(), limited)
        val reviews = list.mapNotNull { entry ->
            val r = (entry as? JsonArray)?.getOrNull(0) as? JsonArray ?: return@mapNotNull null
            val who = r.at(1, 4, 5)
            val author = who?.at(0).str() ?: return@mapNotNull null
            val stars = (r.at(2, 0, 0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val photos = (r.at(2, 2) as? JsonArray)?.mapNotNull { p ->
                (p as? JsonArray)?.at(1, 6, 0).str()?.takeIf { it.startsWith("http") }
            }.orEmpty()
            Review(
                author = author,
                authorPhoto = who.at(1).str(),
                rating = stars,
                relativeTime = r.at(1, 6).str(),
                text = r.at(2, 15, 0, 0).str()?.takeIf { it.isNotBlank() },
                photos = photos,
            )
        }
        return ReviewFeed(reviews, limited)
    }

    private fun JsonElement?.at(vararg path: Int): JsonElement? {
        var x: JsonElement? = this
        for (i in path) x = (x as? JsonArray)?.getOrNull(i) ?: return null
        return x
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
