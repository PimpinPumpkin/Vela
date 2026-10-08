package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo

/**
 * Matching and ranking for offline search, shared by the place packs ([OfflinePoiStore], OSM) and
 * the downloaded places archives the map draws (Overture, AllThePlaces, OSM), so a result from
 * either source is judged the same way.
 */
object OfflineRank {
    /** A category query (the map's chips, "restaurants", "gas") keeps to results this close to the
     *  search point: with no place pack for the area, "Restaurants" used to list a downloaded state
     *  on the far side of the country. A name search is never cut. */
    const val CATEGORY_MAX_M = 100_000.0

    /** Two rows this close with the same name are one place seen by two sources. */
    private const val SAME_PLACE_M = 120.0

    fun isCategoryQuery(query: String): Boolean = OfflinePoiStore.categoryKeywords(query.trim()).isNotEmpty()

    // The accented Latin letters fold spells plainly: Latin-1, Latin Extended-A and B (European
    // languages, Turkish) and the Vietnamese vowels. glob builds its classes from the same ranges.
    private val ACCENTED_LATIN = Regex("[\u00C0-\u024F\u1EA0-\u1EF9]")
    private val MARKS_ON_LATIN = Regex("([A-Za-z\u00C0-\u024F])\\p{Mn}+")

    /** A name or query with what people do not type folded away: accents on Latin letters ("cafe"
     *  is "Café", "zurich" is "Zürich"), apostrophes and periods dropped, hyphens as spaces,
     *  lowercase. Only Latin is folded: the pack query can match a folded letter only through
     *  [glob]'s classes, which are Latin, and decomposing other scripts takes the voicing marks
     *  off kana, splits Hangul and drops Thai, Devanagari and Arabic vowel signs, which no name
     *  in a pack would then match. */
    fun fold(s: String): String =
        MARKS_ON_LATIN.replace(
            ACCENTED_LATIN.replace(s) { java.text.Normalizer.normalize(it.value, java.text.Normalizer.Form.NFD) },
            "$1",
        ).lowercase()
            .replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("ł", "l").replace("đ", "d").replace("ı", "i")
            .replace("'", "").replace("\u2019", "").replace(".", "").replace('-', ' ')

    /** For each plain letter, every accented letter that [fold] turns into it, both cases: the
     *  Latin-1 and Latin Extended-A/B blocks (European languages, Turkish) and the Vietnamese
     *  vowels. The rest of Latin Extended Additional is left out: every letter in a class is
     *  compared at every position of every name, and those forms are not in place names. */
    private val ACCENTED: Map<Char, String> by lazy {
        val out = HashMap<Char, StringBuilder>()
        for (cp in (0x00C0..0x024F) + (0x1EA0..0x1EF9)) {
            val c = cp.toChar()
            val f = fold(c.toString())
            if (f.length == 1 && f[0] in 'a'..'z') out.getOrPut(f[0]) { StringBuilder() }.append(c)
        }
        out.mapValues { it.value.toString() }
    }

    /** The longest folded text that gets accent classes in [glob]. Past it the letters match by
     *  case only, which keeps the pattern far under SQLite's 50,000-byte limit. */
    private const val GLOB_ACCENT_CHARS = 48

    /**
     * A SQLite GLOB pattern that finds [folded] (text already through [fold]) anywhere in a name,
     * whatever its case or accents: each letter becomes a class of its forms, so "cafe" is
     * `*[cCçÇ...][aAáÁ...][fF][eEéÉ...]*`. The packs store names as OpenStreetMap wrote them and
     * SQLite cannot fold accents itself (its LIKE only ignores case for ASCII), so the folding
     * is done in the pattern. GLOB reads UTF-8 one character at a time, classes included.
     */
    fun glob(folded: String): String = glob(folded, eszett = false)

    /** [glob] with each "ss" read as one "ß", so "strasse" finds "Hauptstraße"; null when the
     *  text has no "ss". A pattern cannot say "ss or ß", and folding ß in the pack's names in
     *  SQL costs every search about a fifth more, so this second pattern is only run when it
     *  can matter. The other letters [fold] spells as two (æ, œ) are typed as themselves by the
     *  people who search for them, and are not given one. */
    fun globEszett(folded: String): String? = if ("ss" in folded) glob(folded, eszett = true) else null

    private fun glob(folded: String, eszett: Boolean): String {
        val sb = StringBuilder("*")
        var skip = false
        folded.forEachIndexed { i, c ->
            if (skip) { skip = false; return@forEachIndexed }
            if (eszett && c == 's' && folded.getOrNull(i + 1) == 's') { sb.append("[ßẞ]"); skip = true; return@forEachIndexed }
            when {
                c == '*' || c == '?' || c == '[' -> sb.append('[').append(c).append(']')
                c == ']' -> sb.append("[]]")
                c in 'a'..'z' -> {
                    sb.append('[').append(c).append(c.uppercaseChar())
                    if (i < GLOB_ACCENT_CHARS) sb.append(ACCENTED[c].orEmpty())
                    sb.append(']')
                }
                c.uppercaseChar() != c -> sb.append('[').append(c).append(c.uppercaseChar()).append(']')
                else -> sb.append(c)
            }
        }
        return sb.append('*').toString()
    }

    /** The single words a multi-word query is also matched by (3+ letters). None for a query that
     *  is a known category: "gas station" must not match on "station". */
    fun queryWords(term: String): List<String> {
        if (isCategoryQuery(term)) return emptyList()
        return term.trim().split(Regex("\\s+")).filter { it.length >= 3 }.takeIf { it.size > 1 }.orEmpty()
    }

    /** The same test the pack SQL runs: the query (or, multi-word, any word of 3+ letters) in the
     *  name or category, a category keyword in the category, or the whole query in the address. */
    fun matches(query: String, name: String, category: String?, address: String?, brand: String? = null): Boolean {
        val term = query.trim().lowercase()
        if (term.isEmpty()) return false
        val n = fold(name)
        val c = category?.lowercase().orEmpty()
        val words = queryWords(term)
        val targets = listOf(term) + words
        val b = brand?.let(::fold)
        if (targets.any { n.contains(fold(it)) || c.contains(it) || b?.contains(fold(it)) == true }) return true
        val cats = OfflinePoiStore.categoryKeywords(term) + words.flatMap { OfflinePoiStore.categoryKeywords(it) }
        if (cats.any { c.contains(it) }) return true
        return address?.lowercase()?.contains(term) == true
    }

    /** Transit stops last (unless the query asks for transit), then the rows that answer more of
     *  the query, then names where the query starts a word, then the nearest. A category row
     *  answers its word too: "restaurants" is not a substring of the category "Restaurant", so
     *  before this every chip result tied and far name matches ("... Restaurants") could lead.
     *  The word-start step is deliberately coarse: "shell" puts Shell ahead of a nearer Seashell
     *  Cafe, but Walmart and Walmart Supercenter stay tied so the nearer one still leads. */
    fun rank(query: String, near: LatLng?, rows: List<Place>, limit: Int): List<Place> {
        val term = query.trim()
        val qWords = queryWords(term).ifEmpty { listOf(term) }.map { it.lowercase() }
        val transitQuery = OfflinePoiStore.TRANSIT_QUERY_WORDS.any { term.lowercase().contains(it) }
        val categoryQuery = isCategoryQuery(term)
        val foldedTerm = fold(term)
        val withDist = rows.map { p -> if (near != null) p.copy(distanceMeters = near.distanceTo(p.location)) else p }
            .filter { p -> !categoryQuery || near == null || (p.distanceMeters ?: 0.0) <= CATEGORY_MAX_M }
        val kept = ArrayList<Place>()
        val seenIds = HashSet<String>()
        for (p in withDist) {
            if (!seenIds.add(p.id)) continue
            val key = p.name.trim().lowercase()
            if (kept.any { it.name.trim().lowercase() == key && it.location.distanceTo(p.location) < SAME_PLACE_M }) continue
            kept.add(p)
        }
        return kept.sortedWith(
            compareBy<Place> { p -> if (!transitQuery && (p.category ?: "").lowercase() in OfflinePoiStore.TRANSIT_STOP_CATS) 1 else 0 }
                .thenByDescending { p ->
                    val hay = (p.name + " " + (p.category ?: "") + " " + (p.address ?: "")).lowercase()
                    val name = fold(p.name)
                    val cat = (p.category ?: "").lowercase()
                    qWords.count { w -> hay.contains(w) || name.contains(fold(w)) || OfflinePoiStore.categoryKeywords(w).any { cat.contains(it) } }
                }.thenBy { p -> if (categoryQuery || startsAWord(fold(p.name), foldedTerm)) 0 else 1 }
                .thenBy { it.distanceMeters ?: Double.MAX_VALUE },
        ).take(limit)
    }

    private fun startsAWord(name: String, q: String): Boolean = q.isNotEmpty() && (name.startsWith(q) || name.contains(" $q"))
}
