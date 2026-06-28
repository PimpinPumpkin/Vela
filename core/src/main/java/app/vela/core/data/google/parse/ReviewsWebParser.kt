package app.vela.core.data.google.parse

import app.vela.core.model.Review
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the reviews JSON that [app.vela.web.WebReviewsFetcher] builds by reading Google Maps'
 * rendered review DOM — Google **deleted** the keyless `listentitiesreviews` endpoint (404) and
 * moved reviews behind a `batchexecute` RPC (`rpcids=T4jwAf`) whose proto resisted capture, so we
 * read the reviews the real browser engine renders instead (same hidden-WebView tactic as photos/
 * transit). Each element is `{r:rating, a:author, d:relativeDate, t:text, av:avatarUrl, p:[photoUrls]}`.
 *
 * Lives in `:core` (kotlinx.serialization) so `:app` stays JSON-free, exactly like [PhotosParser] /
 * [TransitParser] taking a raw response string. Best-effort: a malformed blob → empty list.
 */
object ReviewsWebParser {
    fun parse(json: String): List<Review> = runCatching {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val author = o["a"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (author.isBlank()) return@mapNotNull null
            val photos = o["p"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.startsWith("http") }
                ?.distinct()
                .orEmpty()
            Review(
                author = author,
                authorPhoto = o["av"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") },
                rating = (o["r"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 5),
                relativeTime = o["d"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                text = o["t"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                photos = photos,
            )
        }
    }.getOrDefault(emptyList())
}
