package app.vela.core.search

import app.vela.core.data.OfflineRank

/**
 * Was a search for a NAME ("Safeway", "Panera") or for a KIND of place ("food", "pharmacy")?
 * The map keeps a close view on one hit for a name and on three for a kind (issue #647).
 *
 * A name is a query that is not a known category word and that most of the results are called:
 * a search for a chain comes back as a list of that chain, a search for a kind as a list of
 * differently named places.
 */
object SearchKind {
    private const val NAMED_SHARE = 0.6

    fun isName(query: String, resultNames: List<String>): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 2 || resultNames.isEmpty()) return false
        if (OfflineRank.isCategoryQuery(q)) return false
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return false
        val named = resultNames.count { n -> val l = n.lowercase(); words.all { l.contains(it) } }
        return named >= resultNames.size * NAMED_SHARE
    }
}
