package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review scraper's language handling ([ReviewWords], issue #278).
 *
 * The page is served in the reader's language now, so every label the scraper keys on has to be
 * recognised in that language. These are the failures that would otherwise be silent: ratings
 * quietly becoming zero, the reviews tab never opening, and - the one that does damage - clicking
 * "Write a review" instead of "More reviews".
 *
 * The Chinese strings are the real ones captured from a live zh-TW place page.
 */
class ReviewWordsTest {

    @Test fun `a rating is read from the front of the label in any language`() {
        assertEquals(5, ReviewWords.ratingOf("5 stars"))
        assertEquals(5, ReviewWords.ratingOf("5 顆星"))   // live capture, zh-TW
        assertEquals(4, ReviewWords.ratingOf("4 顆星"))   // live capture, zh-TW
        assertEquals(5, ReviewWords.ratingOf("5 étoiles"))
        assertEquals(5, ReviewWords.ratingOf("5 звёзд"))
        assertEquals(1, ReviewWords.ratingOf("1 star"))
    }

    // German writes "4,0 Sterne"; a decimal comma must not read as a different number.
    @Test fun `a decimal rating keeps its whole-star value`() {
        assertEquals(4, ReviewWords.ratingOf("4,0 Sterne"))
        assertEquals(4, ReviewWords.ratingOf("4.0 stars"))
    }

    @Test fun `a label with no leading rating yields nothing`() {
        assertNull(ReviewWords.ratingOf("Photo of Jr"))
        assertNull(ReviewWords.ratingOf(""))
        assertNull(ReviewWords.ratingOf("Local Guide · 11 reviews"))
    }

    @Test fun `the reviews tab is recognised in every shipped language`() {
        listOf(
            "Reviews", "評論", "评论", "Rezensionen", "Bewertungen", "Avis", "Reseñas",
            "Recensioni", "Avaliações", "Beoordelingen", "Отзывы", "Opinie", "Omdömen",
            "Відгуки", "ביקורות", "クチコミ", "レビュー",
        ).forEach { assertTrue("tab not recognised: $it", ReviewWords.isReviewsTab(it)) }
    }

    // The one that does real damage: clicking this opens the review composer.
    @Test fun `write-a-review is never mistaken for more-reviews`() {
        listOf(
            "撰寫評論",              // live capture, zh-TW
            "Write a review",
            "Rezension schreiben",
            "Escribir una reseña",
            "Écrire un avis",
        ).forEach { assertFalse("would click the composer: $it", ReviewWords.isMoreReviewsButton(it)) }
    }

    @Test fun `a genuine more-reviews button is still clicked`() {
        listOf(
            "More reviews", "Alle Rezensionen", "Más reseñas", "Plus d'avis",
            "もっとクチコミ", "更多評論", "Все отзывы", "Wszystkie opinie",
        ).forEach { assertTrue("missed: $it", ReviewWords.isMoreReviewsButton(it)) }
    }

    // A tab word alone must not be enough for the button, or the composer test above is luck.
    @Test fun `a bare review word is not a more-reviews button`() {
        assertFalse(ReviewWords.isMoreReviewsButton("Reviews"))
        assertFalse(ReviewWords.isMoreReviewsButton("評論"))
    }
}
