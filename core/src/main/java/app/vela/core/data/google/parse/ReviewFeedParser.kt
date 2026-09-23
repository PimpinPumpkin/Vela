package app.vela.core.data.google.parse

import app.vela.core.model.Review
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** One page of Google's review feed. [end] = Google says there is nothing after this page
 *  (payload[5] = true). The empty answer carries it too, and so does the LIMITED view: a place with
 *  hundreds of reviews answering 5 and "end" is Google limiting the session, which only the caller
 *  can judge against the place's own review count. */
data class ReviewFeed(val reviews: List<Review>, val end: Boolean, val nextToken: String? = null)

/**
 * The review feed RPC (`batchexecute?rpcids=qv9Egd`), the request Google's own place page makes
 * for its Reviews tab. Envelope `)]}'` + chunked `[["wrb.fr","qv9Egd","<payload json>",...]]`.
 * Payload (captured 2026-09-23): [1] the next page token (assumed, see below), [2] the reviews,
 * [5] true when no page follows.
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
        val end = (payload.getOrNull(5) as? JsonPrimitive)?.booleanOrNull == true
        // The page token. UNVERIFIED (2026-09-23): every capture so far was an end-of-list reply
        // with payload[1] null; a continuing reply is assumed to carry the next token there, the
        // slot before the list, as Google's other paged RPCs do. A non-string leaves it null, so a
        // wrong guess only means no "More reviews" button.
        val token = (payload.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && !end }
        val list = payload.getOrNull(2) as? JsonArray ?: return ReviewFeed(emptyList(), end)
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
        return ReviewFeed(reviews, end, token)
    }

    private fun JsonElement?.at(vararg path: Int): JsonElement? {
        var x: JsonElement? = this
        for (i in path) x = (x as? JsonArray)?.getOrNull(i) ?: return null
        return x
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
