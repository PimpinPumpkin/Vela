package app.vela.core

import app.vela.core.data.google.parse.ReviewsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review parsing incl. user-attached photos under `review[12]` (calibrated
 *  2026-06-20). Guards the photo collection + that the author photo ([0][2],
 *  outside [12]) isn't swept into the review's photo list. */
class ReviewsPhotoTest {

    private fun arr(size: Int, vararg at: Pair<Int, String>): String {
        val m = at.toMap()
        return (0 until size).joinToString(",", "[", "]") { m[it] ?: "null" }
    }

    @Test fun collectsReviewPhotosFrom12AndExcludesAuthorPhoto() {
        val author = arr(3, 1 to "\"Jane D\"", 2 to "\"https://lh3.googleusercontent.com/AUTHOR=s48\"")
        val photo = "https://lh3.googleusercontent.com/geougc/AB=w1600-h1200-k-no"
        val r12 = "[null,[null,null,null,\"$photo\"]]" // url at [12][1][3]
        val review = arr(13, 0 to author, 1 to "\"2 weeks ago\"", 3 to "\"Great burgers\"", 4 to "5", 12 to r12)
        val root = "[null,null,[$review]]" // root[2] = [review]

        val reviews = ReviewsParser.parse(Json.parseToJsonElement(root))
        assertEquals(1, reviews.size)
        val r = reviews[0]
        assertEquals("Jane D", r.author)
        assertEquals(5, r.rating)
        assertEquals("Great burgers", r.text)
        assertEquals(1, r.photos.size)
        assertTrue("thumbnail-resized", r.photos[0].endsWith("=w400-h400"))
        assertTrue("author photo not swept in", r.photos.none { it.contains("AUTHOR") })
    }

    @Test fun reviewWithoutPhotosHasEmptyList() {
        val author = arr(3, 1 to "\"Bob\"")
        val review = arr(13, 0 to author, 3 to "\"ok\"", 4 to "4")
        val reviews = ReviewsParser.parse(Json.parseToJsonElement("[null,null,[$review]]"))
        assertEquals(1, reviews.size)
        assertEquals(0, reviews[0].photos.size)
    }
}
