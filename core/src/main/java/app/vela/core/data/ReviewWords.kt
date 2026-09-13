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
            "omdöme|vélemény|értékelés|ביקור|评论|評論|评价|" +
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
            "ещё|все|więcej|wszystkie|fler|alla|több|összes|" +
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

    // ---- The full-screen review page (ReviewsPanel) keys on these as well (2026-09-13). ----
    // Each is a case-insensitive alternation the page script tests against a control's label.
    // Guessed for the languages nobody on the project speaks; a miss there leaves a Google
    // control visible or a summary row un-carved, never a wrong click. All remotely
    // overridable through calibration `reviewWords` by key (review, more, sort, star, ago,
    // write, like, share, actions, all, processed).

    /** The Sort button / menu ("Sort reviews", "排序評論"). */
    const val SORT_PATTERN = "sort|排序|trier|sortier|ordenar|ordina|classificar|sorteer|сортир|сортув|sortuj|sortera|rendez|מיין|並べ替え|並び替え"

    /** The star widgets' aria-labels ("4.5 stars", "4.5 顆星", "5 星級、908 則評論"). */
    const val STAR_PATTERN = "star|étoile|stern|estrella|stella|estrela|ster|звезд|звёзд|зірк|зіроч|gwiazd|stjärn|csillag|כוכב|顆星|星"

    /** A relative date under a review ("2 months ago", "4 個月前", "il y a 3 mois"): the one
     *  content signal that review cards have rendered when Google's class names have rotated. */
    const val AGO_PATTERN = "\\bago$|前$|^il y a\\b|^vor\\b|^hace\\b|\\bfa$|^há\\b|atrás$|geleden$|назад$|тому$|temu$|sedan$|^לפני|(napja|hete|hónapja|éve|órája|perce)$"

    /** "Write a review" (blocked: it leads to sign-in). */
    const val WRITE_PATTERN = "write a review|撰寫評論|写评价|撰写评价|我要评价|rezension schreiben|bewertung schreiben|escribir una reseña|rédiger un avis|scrivi una recensione|escrever uma avaliação|^avaliar$|review schrijven|написать отзыв|оставить отзыв|написати відгук|залишити відгук|napisz opinię|skriv en recension|értékelés írása|vélemény írása|כתיבת ביקורת|クチコミを投稿|クチコミを書く"

    /** The per-review Like button, whole label. */
    const val LIKE_PATTERN = "^(like|喜歡|赞|点赞|j.aime|gefällt mir|me gusta|mi piace|gostei|vind ik leuk|liken|нравится|лайк|подобається|polubienie|lubię to|gilla|tetszik|lájk|אהבתי|לייק|いいね)$|^позначка"

    /** The per-review Share button: a prefix in most languages ("Share Jane's review.",
     *  "分享…的評論"), a SUFFIX in German and Dutch ("Rezension von X teilen."). */
    const val SHARE_PATTERN = "^(share|分享|partag|compartir|condividi|compartilhar|поделиться|поділитися|udostępnij|powoduje udostępnien|dela|megosztás|שיתוף|共有)|(teilen|delen|megosztása|共有)\\.?$"

    /** The per-review overflow ("Actions for Jane's review", "對…的評論採取動作"). */
    const val ACTIONS_PATTERN = "^actions for|採取動作|采取操作|执行的操作|^actions pour|^aktionen für|^acciones|^azioni per|^ações para|^acties voor|^действия|^дії|^działania|^åtgärder|^műveletek|^פעולות|に対する操作|の操作|アクション$"

    /** The "All" topic chip that anchors the chips row: the word alone ("All"), or the word plus
     *  one review word ("Alle Rezensionen", "所有評論"). Anchored so "Allergens 4" is not it. */
    const val ALL_PATTERN = "^(all|alle|todas|todos|tous|toutes|tutte|tutti|mais|meer|все|всі|wszystkie|alla|összes|כל|すべて|全部|所有評論|全部評論|所有评价|全部评价|すべてのクチコミ)$|^(all|alle|todas|todos|tous|toutes|tutte|tutti|все|всі|wszystkie|alla|összes)\\s+\\S+(\\s+\\S+)?$"

    /** The "reviews are automatically processed" disclaimer row the panel strips. */
    const val PROCESSED_PATTERN = "automatically processed|自動處理|自动处理|automatisch verarbeitet|traités automatiquement|procesan automáticamente|elaborate automaticamente|processadas automaticamente|automatisch verwerkt|автоматически обрабатыва|автоматично обробля|automatycznie przetwarzane|behandlas automatiskt|automatikusan|מעובדות באופן אוטומטי|自動的に処理"

    /** A histogram row's star and count, language-neutral: a single leading digit (not part of
     *  a decimal or a thousands group), then anything up to the first number ("5 stars, 1,189
     *  reviews", "5 星級、908 則評論", "5 Sterne,1.329 Rezensionen", "5-звездочные,1 324 отзывов"
     *  - the count's thousands separator is a comma, a dot or a space by language; Ukrainian's rows
     *  are "5, 908 відгуків", so a separator right after the digit is fine as long as no digit
     *  follows it). */
    val HISTOGRAM_ROW = Regex("""^\s*([1-5])(?!\d|[.,]\d)\D+?(\d[\d.,\s\u00a0\u202f]*)""")

    /** The word tables the page script needs, keyed as the calibration override map is. */
    val DEFAULT_WORDS: Map<String, String> = mapOf(
        "review" to REVIEW_PATTERN, "more" to MORE_PATTERN, "sort" to SORT_PATTERN, "star" to STAR_PATTERN,
        "ago" to AGO_PATTERN, "write" to WRITE_PATTERN, "like" to LIKE_PATTERN, "share" to SHARE_PATTERN,
        "actions" to ACTIONS_PATTERN, "all" to ALL_PATTERN, "processed" to PROCESSED_PATTERN,
    )

    /** [DEFAULT_WORDS] with any remote overrides laid over by key. */
    fun words(overrides: Map<String, String>?): Map<String, String> =
        if (overrides.isNullOrEmpty()) DEFAULT_WORDS else DEFAULT_WORDS + overrides.filterValues { it.isNotBlank() }

    /** A histogram row's (stars, count), or null when the label is not one. */
    fun histogramRow(ariaLabel: String): Pair<Int, Int>? {
        // No star-word requirement: Ukrainian's rows are "5, 908 відгуків", no star word at all
        // (captured 2026-09-13). The leading-digit rule alone keeps "1,329 reviews" and "4,5
        // Sterne" out, and the page script only tests table rows.
        val m = HISTOGRAM_ROW.find(ariaLabel) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].replace(Regex("\\D"), "").toInt()
    }

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
