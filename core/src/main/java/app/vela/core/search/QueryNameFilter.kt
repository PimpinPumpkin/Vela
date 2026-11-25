package app.vela.core.search

import app.vela.core.model.Place
import app.vela.core.util.PlaceNames

/**
 * Keeps a name search to places with that name.
 *
 * Google answers "Chevron" with the brand's stations first and then whatever else it finds
 * near the point. A search along a route wants the brand only. But a typed word is often a kind
 * of place ("pizza", "coffee", "restaurant"), and only some of those places carry it in their
 * name, so a name filter on every query would drop the good ones. Two tests tell the cases apart.
 * A query made only of the words that describe a business ([PlaceNames.GENERIC]) is a kind of
 * place. So is one whose naming words turn up in the category of as many results as carry them in
 * the name. And a brand is a name its places carry whole: at least half of the matches are named
 * exactly the query, describing words and marked store numbers aside ("CVS Pharmacy", "Chevron
 * #4521"), and once it is one, a place stays when it holds the name as whole words.
 * Names that only contain the word ("Pho Tasty", "Lenchitos Tacos", "Bob's Groceries") are a kind
 * of place's, and the list is kept whole (SPEC 5.6 has the numbers).
 */
object QueryNameFilter {
    /**
     * [places] whose name holds every naming word of [query], or all of [places] when the query
     * reads as a kind of place or no name matches. A word may be the start of a name word, so
     * "mcdo" finds McDonald's. A describing word in a name query ("shell gas station") may be in
     * the name or in the category. The filter never empties a list.
     */
    fun apply(query: String, places: List<Place>): List<Place> = apply(query, places, foodWords())

    /** [apply] with the dishes and drinks given: the remote settings' list, else [FOOD]. */
    internal fun apply(query: String, places: List<Place>, food: Set<String>): List<Place> {
        fun describes(w: String): Boolean = forms(w).any { it in PlaceNames.GENERIC || it in food }
        val words = PlaceNames.tokens(query)
        val naming = words.filterNot(::describes)
        if (naming.isEmpty() || places.isEmpty()) return places
        fun prefix(have: List<String>, w: String) = have.any { it.startsWith(w) }
        fun whole(have: List<String>, w: String) = w in have
        fun matches(p: Place, holds: (List<String>, String) -> Boolean): Boolean {
            val name = PlaceNames.tokens(p.name)
            val category = PlaceNames.tokens(p.category)
            return naming.all { holds(name, it) } && words.all { holds(name, it) || prefix(category, it) }
        }
        // Whole words when any name has them ("arco" is not "Arcos Tacos"); the start of a word
        // only when none does, for a name typed in part ("mcdo").
        val wholeNamed = places.filter { matches(it, ::whole) }
        val holds: (List<String>, String) -> Boolean = if (wholeNamed.isNotEmpty()) ::whole else ::prefix
        val named = wholeNamed.ifEmpty { places.filter { matches(it, ::prefix) } }
        if (named.isEmpty()) return places
        // A kind of place is in the category of at least as many results as it is in the name of.
        val inCategory = places.count { p -> PlaceNames.tokens(p.category).let { c -> naming.all { prefix(c, it) } } }
        if (inCategory >= named.size) return places
        // A brand: at least half of the matches carry exactly the typed name.
        val exact = named.count { p ->
            val own = PlaceNames.tokens(p.name).filterNot(::describes) + listOfNotNull(numberedName(p.name))
            own.size == naming.size && naming.all { holds(own, it) }
        }
        return if (exact > 0 && exact * 2 >= named.size) named else places
    }

    // A describing word is on the shared list or among the dishes and drinks that name places
    // without being Google's category for them, in the singular or the plural ("groceries" is
    // "grocery", "burger" as much as "burgers").

    /** The dishes and drinks in force: the remote settings' `nameFilterFoodWords` when present,
     *  normalized as names are, else [FOOD]. */
    private fun foodWords(): Set<String> {
        val remote = app.vela.core.config.CalibrationStore.latest.nameFilterFoodWords ?: return FOOD
        if (remote !== remoteSeen) {
            remoteSeen = remote
            remoteFood = remote.flatMap { PlaceNames.tokens(it) }.toSet()
        }
        return remoteFood
    }

    @Volatile private var remoteSeen: List<String>? = null
    @Volatile private var remoteFood: Set<String> = emptySet()

    private fun forms(w: String): List<String> = listOfNotNull(
        w, w + "s",
        if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else null,
        if (w.length > 4 && w.endsWith("es")) w.dropLast(2) else null,
        if (w.length > 4 && w.endsWith("ies")) w.dropLast(3) + "y" else null,
    )

    /** The number a name ends in when it is part of the name ("Pho 99", "Pho 2000"), which the
     *  shared word split drops: such a name is not the bare word. A store number marked as one
     *  ("#4521", "Store 12") is not part of it. */
    private fun numberedName(name: String?): String? {
        val n = name?.trim() ?: return null
        if (STORE_NUMBER.containsMatchIn(n)) return null
        return BARE_NUMBER.find(n)?.groupValues?.get(1)
    }

    private val STORE_NUMBER = Regex("(#|\\b(no|num|store|unit)\\.?)\\s*\\p{N}{1,6}$", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER = Regex("\\s(\\p{N}{2,6})$")

    /** Dishes and drinks that are in many places' names but seldom in Google's category for them
     *  ("Bubble tea store", "Vietnamese restaurant", "Mexican restaurant"), singular forms. */
    internal val FOOD = setOf(
        "acai", "bagel", "banh", "bao", "birria", "boba", "burrito", "churro", "crepe", "cupcake",
        "curry", "doughnut", "dumpling", "empanada", "falafel", "froyo", "gelato", "gyro", "matcha",
        "noodle", "pho", "poke", "pretzel", "pupusa", "ramen", "shawarma", "taco", "tamale",
        "teriyaki", "udon", "waffle",
    )
}
