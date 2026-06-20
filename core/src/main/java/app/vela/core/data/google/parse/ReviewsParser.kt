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
 * Photos: collected by **URL shape**, not a fixed index. Google serves a reviewer's
 * **uploaded** photos from FIFE paths `/gps-cs…`, `/geougc…` or `/p/AF1Qip…`, whereas
 * **avatars** (the reviewer's profile picture) come from `/a/…` / `/a-/…` with
 * `ACg8oc`/`ALV-` ids. The earlier parser walked `[12]` and swept the avatar that
 * lives at `[12][1][3]` into the photo strip — so every review showed the reviewer's
 * face as if it were an uploaded photo (the bug fixed here, 2026-06-20). Verified
 * against Tartine + Bottega Louie: **this RPC returns ONLY avatars** (author photo at
 * `[0][2]`, plus copies at `[12][1][3]` and `[60][2]`), never uploaded photos — so the
 * strip now correctly shows nothing rather than a face. Sourcing real per-review
 * uploaded photos needs a different RPC/flag (see ROADMAP); this collector already
 * accepts them the instant they appear in the response.
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
                photos = reviewPhotos(rv),
            )
        }
    }

    /** A review's **user-uploaded** photos: every FIFE image URL in the review subtree
     *  that is a UGC photo and NOT a profile avatar — thumbnail-resized + de-duped.
     *  Walks the whole review (not a pinned index) so it's robust to nesting changes;
     *  the avatar filter is what keeps the reviewer's face out of the strip. */
    private fun reviewPhotos(review: JsonElement?): List<String> {
        review ?: return emptyList()
        val urls = LinkedHashSet<String>()
        fun walk(x: JsonElement?) {
            when (x) {
                is JsonArray -> x.forEach(::walk)
                is JsonPrimitive -> x.str()?.let { s -> if (isUploadedPhoto(s)) urls.add(resize(s)) }
                else -> {}
            }
        }
        walk(review)
        return urls.toList().take(10)
    }

    /** A FIFE image URL that is a reviewer-**uploaded** photo (not their avatar). */
    private fun isUploadedPhoto(u: String): Boolean {
        if (!u.startsWith("http")) return false
        if (!u.contains("googleusercontent.com/") && !u.contains("ggpht.com/")) return false
        if (isAvatar(u)) return false
        return u.contains("/gps-cs") || u.contains("/geougc") ||
            u.contains("/p/AF1Qip") || u.contains("googleusercontent.com/p/")
    }

    /** Reviewer profile pictures (avatars) — `/a/…`, `/a-/…`, or the `ACg8oc`/`ALV-`
     *  FIFE id prefixes. Explicitly NOT review content, so never shown as a photo. */
    private fun isAvatar(u: String): Boolean =
        AVATAR_PATH.containsMatchIn(u) || u.contains("ACg8oc") || u.contains("ALV-")

    /** Force a sane thumbnail size onto a FIFE URL: strip any trailing size directive
     *  (`=s…`, `=w…-h…-k-no`, …) and pin our own — strip-then-append so a URL that
     *  already carries a size can never end up double-sized. */
    private fun resize(u: String): String =
        u.replace(Regex("=[swh]\\d[\\w-]*$"), "") + "=w400-h400"

    private val AVATAR_PATH = Regex("/a-?/")
}
