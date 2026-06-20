package app.vela.core

import app.vela.core.data.google.parse.ReviewsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review photo parsing. Recalibrated 2026-06-20 against real responses (Tartine,
 *  Bottega Louie): the reviews RPC carries the reviewer's **avatar** at `[0][2]`,
 *  `[12][1][3]` and `[60][2]` (`/a-/…ALV-`, `/a/…ACg8oc`), and the old parser swept
 *  those into the photo strip — showing the reviewer's face as a "photo". Photos are
 *  now collected by URL shape: uploaded UGC (`/gps-cs`, `/geougc`, `/p/AF1Qip`) only,
 *  avatars never. */
class ReviewsPhotoTest {

    private fun arr(size: Int, vararg at: Pair<Int, String>): String {
        val m = at.toMap()
        return (0 until size).joinToString(",", "[", "]") { m[it] ?: "null" }
    }

    private val avatarA = "https://lh3.googleusercontent.com/a-/ALV-UjWauthor=s48"
    private val avatarB = "https://lh3.googleusercontent.com/a/ACg8ocOwner=s64"
    private val uploaded = "https://lh3.googleusercontent.com/gps-cs-s/APNQkPHOTO=w1600-h1200-k-no"

    /** Avatars at [0][2] AND in the [12] subtree must NOT become review photos. */
    @Test fun avatarsAreNotCollectedAsPhotos() {
        val author = arr(3, 1 to "\"Jane D\"", 2 to "\"$avatarA\"")
        val r12 = "[null,[null,null,null,\"$avatarA\"]]" // avatar copy at [12][1][3]
        val review = arr(61, 0 to author, 1 to "\"2 weeks ago\"", 3 to "\"Great burgers\"", 4 to "5",
            12 to r12, 60 to arr(3, 2 to "\"$avatarB\""))
        val reviews = ReviewsParser.parse(Json.parseToJsonElement("[null,null,[$review]]"))
        assertEquals(1, reviews.size)
        assertEquals("Jane D", reviews[0].author)
        assertEquals(0, reviews[0].photos.size) // the bug: was showing the avatar
    }

    /** A genuine uploaded photo IS collected (and thumbnail-resized), the avatar isn't. */
    @Test fun uploadedPhotoIsCollectedAvatarExcluded() {
        val author = arr(3, 1 to "\"Bob\"", 2 to "\"$avatarA\"")
        val r12 = "[null,[null,[[\"$uploaded\"]]]]" // a UGC photo nested under [12]
        val review = arr(13, 0 to author, 3 to "\"tasty\"", 4 to "4", 12 to r12)
        val r = ReviewsParser.parse(Json.parseToJsonElement("[null,null,[$review]]"))[0]
        assertEquals(1, r.photos.size)
        assertTrue("thumbnail-resized", r.photos[0].endsWith("=w400-h400"))
        assertTrue("avatar excluded", r.photos.none { it.contains("ALV-") })
    }

    @Test fun reviewWithoutPhotosHasEmptyList() {
        val review = arr(13, 0 to arr(3, 1 to "\"Sam\""), 3 to "\"ok\"", 4 to "3")
        val r = ReviewsParser.parse(Json.parseToJsonElement("[null,null,[$review]]"))[0]
        assertEquals(0, r.photos.size)
    }
}
