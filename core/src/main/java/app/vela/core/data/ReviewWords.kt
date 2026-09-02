package app.vela.core.data

/**
 * The words that identify Google's review controls in each language Vela ships (issue #278).
 *
 * The review scraper used to run the place page in English and match English labels. That forced
 * Google to serve English reviews to everyone, because the page language decides which reviews you
 * get - a Chinese reader looking at a Chinese restaurant was shown the English ones. Reviews are
 * content and must never be translated for the reader, so the page now follows the app's language,
 * which means every label the scraper keys on has to be recognised in that language too.
 *
 * Kept here rather than inline in the scraper's JavaScript so the patterns can be unit-tested: a
 * word list that silently stops matching is invisible until someone reports missing reviews.
 */
object ReviewWords {

    /**
     * "Reviews", for finding the reviews TAB.
     *
     * Matched as a plain substring, which is safe because it is only ever tested against
     * `role="tab"` elements, where the choices are Overview / Reviews / About.
     */
    const val REVIEW_PATTERN =
        "review|rezension|bewertung|reseña|opini|avis|commentaire|recension|recensioni|" +
            "avalia|beoordel|отзыв|відгук|" +
            "omdöme|ביקור|评论|評論|评价|" +
            "クチコミ|口コミ|レビュー"

    /**
     * "More" / "all", required IN ADDITION to [REVIEW_PATTERN] when clicking the "more reviews"
     * BUTTON.
     *
     * Without it a bare review-word match hits "Write a review" (zh-TW "撰寫評論"), and clicking that
     * opens the review composer instead of the list - a wrong click, not merely a missed one.
     */
    const val MORE_PATTERN =
        "more|all|mehr|alle|más|todas|plus|tous|più|tutte|mais|meer|" +
            "ещё|все|więcej|wszystkie|fler|alla|" +
            "більше|всі|עוד|כל|" +
            "更多|全部|もっと|すべて"

    /**
     * A star widget's rating, read from the FRONT of its aria-label.
     *
     * Every language leads with the number ("5 stars", "5 顆星", "5 étoiles", "4,0 Sterne"), so the
     * leading digit is the one language-neutral signal. The old parser looked for the English word
     * "star" and returned 0 for every review as soon as the page was not English.
     */
    val LEADING_RATING = Regex("""^\s*([1-5])(?:[.,]0)?\b""")

    private val review = Regex(REVIEW_PATTERN, RegexOption.IGNORE_CASE)
    private val more = Regex(MORE_PATTERN, RegexOption.IGNORE_CASE)

    /** Whether a `role="tab"` label names the reviews tab. */
    fun isReviewsTab(label: String): Boolean = review.containsMatchIn(label)

    /** Whether a button opens MORE reviews (as opposed to composing one). */
    fun isMoreReviewsButton(label: String): Boolean =
        review.containsMatchIn(label) && more.containsMatchIn(label)

    /** The rating in a star widget's aria-label, or null when it carries none. */
    fun ratingOf(ariaLabel: String): Int? =
        LEADING_RATING.find(ariaLabel)?.groupValues?.get(1)?.toIntOrNull()
}
