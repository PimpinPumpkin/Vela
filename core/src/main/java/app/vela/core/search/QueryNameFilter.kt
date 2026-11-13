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
 * the name.
 */
object QueryNameFilter {
    /**
     * [places] whose name holds every naming word of [query], or all of [places] when the query
     * reads as a kind of place or no name matches. A word may be the start of a name word, so
     * "mcdo" finds McDonald's. A describing word in a name query ("shell gas station") may be in
     * the name or in the category. The filter never empties a list.
     */
    fun apply(query: String, places: List<Place>): List<Place> {
        val words = PlaceNames.tokens(query)
        val naming = words.filterNot(::describes)
        if (naming.isEmpty() || places.isEmpty()) return places
        fun holds(have: List<String>, w: String) = have.any { it.startsWith(w) }
        val named = places.filter { p ->
            val name = PlaceNames.tokens(p.name)
            val category = PlaceNames.tokens(p.category)
            naming.all { holds(name, it) } && words.all { holds(name, it) || holds(category, it) }
        }
        if (named.isEmpty()) return places
        // A kind of place is in the category of at least as many results as it is in the name of.
        val inCategory = places.count { p -> PlaceNames.tokens(p.category).let { c -> naming.all { holds(c, it) } } }
        return if (inCategory >= named.size) places else named
    }

    /** True for a word that describes a business, in the singular or the plural: "burger" is as
     *  much a kind of place as the list's "burgers". */
    private fun describes(w: String): Boolean =
        w in PlaceNames.GENERIC || (w + "s") in PlaceNames.GENERIC || (w.length > 3 && w.endsWith("s") && w.dropLast(1) in PlaceNames.GENERIC)
}
