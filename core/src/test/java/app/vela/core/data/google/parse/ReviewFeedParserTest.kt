package app.vela.core.data.google.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes from a capture of the qv9Egd feed (2026-09-23), with made-up people and text. */
class ReviewFeedParserTest {
    private fun nulls(n: Int) = List(n) { JsonNull }

    /** One review entry as raw JSON, the captured nesting with made-up content. */
    private fun review(author: String, stars: Int, ago: String, text: String?, photos: List<String>): kotlinx.serialization.json.JsonElement {
        val q = { v: String -> JsonPrimitive(v).toString() }
        val who = "[null,null,null,null,[null,null,null,null,null,[${q(author)},\"https://lh3.googleusercontent.com/a-/avatar\"]],null,${q(ago)}]"
        val pics = photos.joinToString(",") { "[null,[null,null,null,null,null,null,[${q(it)}]]]" }
        val body = "[[${stars}],null,[${pics}]" + ",null".repeat(12) + (if (text != null) ",[[${q(text)}]]" else "") + "]"
        return kotlinx.serialization.json.Json.parseToJsonElement("[[${q("Ci9review-" + author)},$who,$body]]")
    }

    private fun envelope(payload: JsonArray): String {
        val row = buildJsonArray { addJsonArray { add("wrb.fr"); add("qv9Egd"); add(payload.toString()); add(JsonNull); add(JsonNull); add(JsonNull); add("generic") } }
        return ")]}'\n\n${row.toString().length}\n$row\n25\n[[\"e\",4,null,null,1]]\n"
    }

    @Test fun `a full page parses author, stars, time, text and photos`() {
        val payload = JsonArray(listOf(JsonNull, JsonNull, JsonArray(listOf(
            review("Alex Example", 5, "a month ago", "Great produce.", listOf("https://lh3.googleusercontent.com/grass-cs/p1=w300")),
            review("Sam Sample", 2, "2 years ago", null, emptyList()),
        )), JsonNull, JsonNull))
        val feed = ReviewFeedParser.parse(envelope(payload))!!
        assertFalse(feed.limited)
        assertEquals(2, feed.reviews.size)
        val a = feed.reviews[0]
        assertEquals("Alex Example", a.author)
        assertEquals(5, a.rating)
        assertEquals("a month ago", a.relativeTime)
        assertEquals("Great produce.", a.text)
        assertEquals(listOf("https://lh3.googleusercontent.com/grass-cs/p1=w300"), a.photos)
        assertEquals("https://lh3.googleusercontent.com/a-/avatar", a.authorPhoto)
        assertNull(feed.reviews[1].text)
    }

    @Test fun `the limited view is flagged`() {
        val payload = JsonArray(listOf(JsonNull, JsonNull, JsonArray(listOf(review("Alex Example", 4, "a week ago", "Fine.", emptyList()))),
            JsonNull, JsonNull, JsonPrimitive(true), buildJsonArray { add(true) }))
        val feed = ReviewFeedParser.parse(envelope(payload))!!
        assertTrue(feed.limited)
        assertEquals(1, feed.reviews.size)
    }

    @Test fun `the header-less empty answer is an empty page, not a crash`() {
        val payload = JsonArray(nulls(5) + listOf(JsonPrimitive(true)))
        val feed = ReviewFeedParser.parse(envelope(payload))!!
        assertTrue(feed.reviews.isEmpty())
    }

    @Test fun `garbage is null`() {
        assertNull(ReviewFeedParser.parse("<html>sorry</html>"))
    }
}
