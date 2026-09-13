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
            "Відгуки", "ביקורות", "クチコミ", "レビュー", "Vélemények",
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

    // The full-screen page's hooks, against the zh-TW labels captured live on 2026-09-13.
    @Test fun `histogram rows parse in any language and a decimal rating is not a row`() {
        assertEquals(5 to 908, ReviewWords.histogramRow("5 星級、908 則評論"))
        assertEquals(1 to 18, ReviewWords.histogramRow("1 星級、18 則評論"))
        assertEquals(5 to 1189, ReviewWords.histogramRow("5 stars, 1,189 reviews"))
        assertEquals(5 to 1329, ReviewWords.histogramRow("5 Sterne,1.329 Rezensionen"))   // live, de
        assertEquals(5 to 1324, ReviewWords.histogramRow("5-звездочные,1 324 отзывов"))   // live, ru
        assertNull(ReviewWords.histogramRow("4,5 Sterne"))
        assertNull(ReviewWords.histogramRow("4,5-звездочные"))
        assertNull(ReviewWords.histogramRow("1.329 Rezensionen"))
        assertNull(ReviewWords.histogramRow("4.5 顆星"))
        assertNull(ReviewWords.histogramRow("1,329 則評論"))
        assertNull(ReviewWords.histogramRow("4.5 stars"))
    }

    @Test fun `the page controls are recognised in Chinese and English`() {
        val w = ReviewWords.words(null)
        fun m(key: String, s: String) = Regex(w.getValue(key), RegexOption.IGNORE_CASE).containsMatchIn(s)
        assertTrue(m("sort", "排序評論")); assertTrue(m("sort", "Sort reviews"))
        assertTrue(m("write", "撰寫評論")); assertTrue(m("write", "Write a review"))
        assertTrue(m("like", "喜歡")); assertTrue(m("like", "Like")); assertFalse(m("like", "Likely"))
        assertTrue(m("share", "分享chang nikko的評論。")); assertTrue(m("share", "Share Jane's review."))
        assertTrue(m("actions", "對chang nikko的評論採取動作")); assertTrue(m("actions", "Actions for Jane's review"))
        assertTrue(m("all", "所有評論")); assertTrue(m("all", "All")); assertFalse(m("all", "Allergens 4"))
        assertTrue(m("ago", "4 個月前")); assertTrue(m("ago", "2 years ago")); assertTrue(m("ago", "il y a 3 mois"))
        assertFalse(m("ago", "Mikuni")); assertFalse(m("ago", "500 1st St"))
        assertTrue(m("star", "4.5 顆星")); assertTrue(m("star", "5 stars"))
        assertTrue(m("review", "對「Mikuni」的評論")); assertFalse(m("review", "「Mikuni」總覽"))
        // German and Russian, captured live the same day.
        assertTrue(m("review", "Rezensionen zu „Mikuni“")); assertTrue(m("review", "Отзывы о месте \"Mikuni\""))
        assertTrue(m("sort", "Rezensionen sortieren"))
        assertTrue(m("write", "Rezension schreiben")); assertTrue(m("write", "Оставить отзыв"))
        assertTrue(m("like", "Gefällt mir")); assertTrue(m("like", "Лайк"))
        assertTrue(m("share", "Rezension von Raul Guzman teilen.")); assertTrue(m("share", "Поделиться отзывом пользователя C Varty"))
        assertFalse(m("share", "Teilen Sie uns mit"))
        assertTrue(m("actions", "Aktionen für die Rezension von Raul Guzman")); assertTrue(m("actions", "Действия с отзывом пользователя C Varty"))
        assertTrue(m("all", "Alle Rezensionen")); assertFalse(m("all", "Alle 36 Rezensionen ansehen"))
        assertTrue(m("ago", "vor 7 Jahren")); assertTrue(m("ago", "4 месяца назад"))
        assertTrue(m("star", "4,5 Sterne")); assertTrue(m("star", "5-звездочные,908 отзывов"))
        // Every other app language, captured live the same day.
        assertTrue(m("all", "Tous les avis")); assertTrue(m("all", "Todas las reseñas")); assertTrue(m("all", "Tutte le recensioni")); assertTrue(m("all", "すべてのクチコミ"))
        assertTrue(m("actions", "Acciones en la reseña de X")); assertTrue(m("actions", "Ações para a avaliação de X")); assertTrue(m("actions", "刑部耕平 さんのクチコミへのアクション")); assertTrue(m("actions", "可对张丰铖的评价执行的操作"))
        assertTrue(m("write", "Avaliar")); assertTrue(m("write", "Vélemény írása")); assertTrue(m("write", "我要评价")); assertFalse(m("write", "Avaliar o restaurante"))
        assertTrue(m("sort", "Classificar avaliações")); assertTrue(m("sort", "Trier les avis")); assertTrue(m("sort", "クチコミの並べ替え")); assertTrue(m("sort", "对评价排序"))
        assertTrue(m("like", "Liken")); assertTrue(m("like", "Polubienie")); assertTrue(m("like", "Lájk")); assertTrue(m("like", "לייק")); assertTrue(m("like", "点赞")); assertTrue(m("like", "Позначка \"подобається\"")); assertTrue(m("like", "J'aime"))
        assertTrue(m("share", "Powoduje udostępnienie opinii użytkownika X.")); assertTrue(m("share", "C Varty véleményének megosztása.")); assertTrue(m("share", "刑部耕平 さんのクチコミを共有")); assertTrue(m("share", "Review van C Varty delen."))
        assertTrue(m("star", "5 зірочок")); assertTrue(m("star", "5 つ星、クチコミ 908 件")); assertTrue(m("star", "5 csillag, 908 vélemény"))
        assertTrue(m("ago", "4 meses atrás")); assertTrue(m("ago", "4 hónapja")); assertTrue(m("ago", "לפני 4 חודשים")); assertTrue(m("ago", "för 4 månader sedan")); assertTrue(m("ago", "4 か月前"))
        assertEquals(5 to 908, ReviewWords.histogramRow("5, 908 відгуків"))        // uk, no star word
        assertEquals(5 to 908, ReviewWords.histogramRow("5 つ星、クチコミ 908 件"))
        assertEquals(5 to 908, ReviewWords.histogramRow("5星级，908 条评价"))
        assertEquals(5 to 908, ReviewWords.histogramRow("5-gwiazdkowy,908 opinii"))
        assertNull(ReviewWords.histogramRow("4.5 星级"))
    }

    @Test fun `remote overrides lay over the defaults by key`() {
        val w = ReviewWords.words(mapOf("sort" to "ordenar|sort", "bogus" to ""))
        assertEquals("ordenar|sort", w["sort"])
        assertEquals(ReviewWords.STAR_PATTERN, w["star"])
        assertFalse(w.containsKey("bogus"))
    }
}
