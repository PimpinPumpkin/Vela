package app.vela.core.util

import java.text.Normalizer

/**
 * Place-name comparison for "is this the same business", shared by the tap resolve (which Google
 * listing did the user tap), the Both-mode twin hiding (which open feature is Google's copy) and,
 * as a SQL mirror in `tools/build-places-region.sh`, the bake's own dedupe. One rule set, because
 * three private copies drifted apart and every drift was a duplicate icon or a wrong tap.
 *
 * Derived from a side by side of Google's answers and the open archive over the Davis fixture
 * (2026-09-21, 495 Google places): 290 matched by exact name after normalization, and the rest of
 * the real twins fell into three families that the rules below cover:
 *
 *  - DESCRIPTOR TAILS. Google says "Circle K | Gas Station", "U.S. Bank Branch", "Wells Fargo
 *    Bank", "Golden 1 Credit Union - Davis", "Hilton Garden Inn Davis Downtown", "Bank of
 *    America (with Drive-thru ATM)"; the archive says "CVS Pharmacy" where Google says "CVS".
 *    The extra words are generic (a category, a city, a branch word), so a name that is the
 *    other plus generic words is the same business ([Match.VARIANT]).
 *  - SPELLING. "&" against "and", "St" against "Street", "NY" against "New York", accents,
 *    possessives, legal suffixes ("James W. Childress, DDS Inc."), chain tails ("by Wyndham",
 *    "an Ascend Collection Hotel"), parentheticals ("(formerly Yakitori Yuchan)", "(aka Aggie
 *    Dental Care)"), a leading "The" or "Dr.". All folded by [normalized].
 *  - DISTINCTIVE OVERLAP. "Davis Dental Creations -Dr. Harsimran Bains" against "Davis Dental
 *    Creations -dr. Simran Bains", "The Local by Dunloe Brewing" against "Dunloe Brewing - The
 *    Local": the words that actually identify the business agree ([Match.OVERLAP]).
 *
 * And the false positives the old two-shared-words rule produced, which the generic list exists
 * to refuse: "Russell Park Apartments" is not "Orchard Park Apartments", "Havana Mini Mart" is not
 * "Kobe Mini Mart", "Davis Senior High School" is not "Davis Adult & Community Education School",
 * "Ergash Dental" is not "Davis Dental", and a brand's fuel station, pharmacy or counter is not
 * the store ("Shop Fuel Station" against "Shop" is a VARIANT of it, which callers that need the
 * store itself keep out with [same]).
 */
object PlaceNames {
    enum class Match { EXACT, VARIANT, OVERLAP, NONE }

    private val PAREN = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val POSSESSIVE = Regex("[’']s\\b")
    private val PUNCT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val TRAILING_NUMBER = Regex("( (no|num|store|unit|#) ?\\p{N}{1,6}| \\p{N}{2,6})$")
    private val COMBINING = Regex("\\p{M}+")

    private val LEGAL = setOf("llc", "inc", "corp", "co", "ltd", "company", "incorporated", "corporation", "pc", "apc", "llp", "pllc", "gmbh", "ag", "sa", "srl", "bv", "pty", "plc")
    private val CHAIN_TAILS = listOf(
        "by wyndham", "by marriott", "by hilton", "by ihg", "by choice hotels", "by best western", "by radisson",
        "an ascend collection hotel", "a tribute portfolio hotel", "a marriott hotel", "a hilton hotel",
    )
    private val ABBR = mapOf(
        "st" to "street", "ave" to "avenue", "blvd" to "boulevard", "rd" to "road", "ctr" to "center", "centre" to "center",
        "ny" to "new york", "nyc" to "new york", "univ" to "university", "mt" to "mount", "ft" to "fort", "hwy" to "highway",
        "pkwy" to "parkway", "sq" to "square", "jr" to "junior", "intl" to "international", "natl" to "national",
    )

    /**
     * Words that describe a business rather than name it. A name made only of these matches
     * nothing by overlap, and a name that is another plus some of these is that business. Kept
     * as one list on purpose: it is the IDF of a places corpus written down, and it has to be the
     * same list in every caller.
     */
    val GENERIC: Set<String> = setOf(
        "the", "of", "and", "at", "in", "on", "a", "an", "for", "by", "with", "to",
        "store", "stores", "shop", "shoppe", "shops", "station", "center", "centers", "services", "service", "group", "office", "offices",
        "company", "branch", "bank", "atm", "atms", "pharmacy", "drug", "drugs", "grooming", "fuel", "gas", "market", "markets", "mart", "mini",
        "grocery", "restaurant", "cafe", "coffee", "inn", "hotel", "hotels", "suites", "motel", "apartments", "apartment", "clinic", "medical",
        "dental", "dentistry", "hospital", "church", "school", "university", "college", "salon", "studio", "bar", "grill", "kitchen",
        "bakery", "baking", "deli", "express", "downtown", "plaza", "mall", "building", "hall", "department", "dept", "emergency", "room",
        "outlet", "supply", "supplies", "food", "foods", "drinks", "liquor", "wine", "beer", "auto", "automotive", "repair", "car", "cars",
        "care", "health", "healthcare", "wellness", "fitness", "gym", "realty", "real", "estate", "agency", "agent", "agents", "realtor",
        "insurance", "law", "legal", "attorney", "attorneys", "financial", "tax", "consulting", "home", "homes", "self", "drive", "thru",
        "mobile", "pet", "pets", "animal", "veterinary", "vet", "spa", "nails", "nail", "hair", "beauty", "pizza", "sushi", "taqueria",
        "cuisine", "catering", "team", "associates", "partners", "properties", "management", "rental", "rentals", "storage", "cleaners",
        "laundry", "wash", "tire", "tires", "smog", "oil", "change", "lube", "glass", "body", "collision", "parts", "hardware", "lumber",
        "paint", "garden", "nursery", "florist", "flowers", "gifts", "gift", "books", "bookstore", "toys", "thrift", "resale", "vintage",
        "boutique", "jewelry", "jewelers", "optical", "vision", "eye", "eyecare", "chiropractic", "physical", "therapy", "massage", "yoga",
        "pilates", "martial", "arts", "dance", "music", "lessons", "academy", "learning", "preschool", "daycare", "child", "childcare",
        "kids", "senior", "living", "community", "county", "city", "public", "library", "park", "pool", "recreation", "sports", "club",
        "lounge", "tavern", "pub", "brewing", "brewery", "roasters", "tea", "bagels", "donuts", "ice", "cream", "yogurt", "juice",
        "smoothie", "burgers", "chicken", "bbq", "mexican", "chinese", "japanese", "thai", "indian", "italian", "greek", "mediterranean",
        "vietnamese", "korean", "american", "cantina", "bistro", "eatery", "diner", "house", "place", "spot", "corner", "village",
        "square", "commons", "crossing", "ranch", "farm", "farms", "credit", "union", "federal", "mortgage", "lending", "loan", "loans",
        "wholesale", "retail", "convenience", "general", "family", "practice", "physician", "physicians", "doctor", "doctors", "dds",
        "dmd", "md", "dr", "professional", "professionals", "solutions", "systems", "technologies", "international", "national",
        "north", "south", "east", "west", "inc", "co",
    )

    /** Accents folded, case and punctuation gone, "&" read as "and", legal suffixes, chain tails,
     *  parentheticals, a leading "the"/"dr" and a trailing store number dropped, common street
     *  abbreviations expanded, and runs of single letters joined ("u s bank" is "us bank"). */
    fun normalized(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase()
        s = s.replace(PAREN, " ")
        s = s.replace("&", " and ").replace("+", " and ")
        s = s.replace(POSSESSIVE, "s")
        s = s.replace(PUNCT, " ").replace(SPACES, " ").trim()
        for (tail in CHAIN_TAILS) if (s.endsWith(" $tail")) s = s.removeSuffix(" $tail").trim()
        s = s.replace(TRAILING_NUMBER, "").trim()
        val words = ArrayList<String>()
        for (w in s.split(' ')) {
            if (w.isEmpty() || w in LEGAL) continue
            val exp = ABBR[w]
            if (exp != null) words.addAll(exp.split(' ')) else words.add(w)
        }
        // "u s bank" -> "us bank": abbreviations written with dots come through as single letters.
        val joined = ArrayList<String>(words.size)
        var run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { joined.add(run.toString()); run = StringBuilder() } }
        for (w in words) {
            if (w.length == 1 && w[0].isLetter()) run.append(w) else { flush(); joined.add(w) }
        }
        flush()
        // A lone "s" after a word is a possessive the punctuation pass split off ("JOE S DINER").
        val glued = ArrayList<String>(joined.size)
        for (w in joined) {
            if (w == "s" && glued.isNotEmpty()) glued[glued.lastIndex] = glued.last() + "s" else glued.add(w)
        }
        joined.clear(); joined.addAll(glued)
        if (joined.isNotEmpty() && (joined[0] == "the" || joined[0] == "dr")) joined.removeAt(0)
        return joined.joinToString(" ")
    }

    fun tokens(name: String?): List<String> = normalized(name).split(' ').filter { it.isNotEmpty() }

    /** The words that identify a business: not generic, not a number, at least two letters
     *  ("US Bank" is named by "us"; a single letter never names anything on its own). */
    fun distinctive(name: String?, extraGeneric: Set<String> = emptySet()): Set<String> =
        tokens(name).filter { isDistinctive(it, extraGeneric) }.toSet()

    private fun isDistinctive(w: String, extraGeneric: Set<String>): Boolean =
        w !in GENERIC && w !in extraGeneric && w.length >= 2 && !w.all { c -> c.isDigit() }

    /** True when two names identify the same business under [normalized] (the strict test). */
    fun same(a: String?, b: String?): Boolean {
        val na = normalized(a)
        return na.isNotEmpty() && na == normalized(b)
    }

    /**
     * How [a] and [b] relate. [extraGeneric] adds words that are generic in THIS comparison, such as
     * the town's name out of a listing's address ("FIT House Davis" is "FIT House" in Davis, and a
     * different gym elsewhere). Distance is the caller's business: a VARIANT is the same business
     * on the same lot, an OVERLAP wants the two within tens of meters.
     */
    fun match(a: String?, b: String?, extraGeneric: Set<String> = emptySet()): Match {
        val na = normalized(a); val nb = normalized(b)
        if (na.isEmpty() || nb.isEmpty()) return Match.NONE
        if (na == nb) return Match.EXACT
        val ta = na.split(' ').toSet(); val tb = nb.split(' ').toSet()
        val da = distinctive(a, extraGeneric); val db = distinctive(b, extraGeneric)
        val nested = ta.containsAll(tb) || tb.containsAll(ta)
        if (nested) {
            val extra = if (ta.size >= tb.size) ta - tb else tb - ta
            val core = if (ta.size >= tb.size) tb else ta
            val coreDistinct = core.filter { isDistinctive(it, extraGeneric) }
            // The shorter name must still NAME something: "Hair" inside "Hair Studio" is two
            // descriptions, not a business.
            if (coreDistinct.isEmpty()) return Match.NONE
            if (extra.none { isDistinctive(it, extraGeneric) }) return Match.VARIANT
            // Extra words that are not generic ("SpeeDee-Midas" over "SpeeDee", "Sam's
            // Mediterranean Cuisine" over "Sam's Cuisine"): the same business when what the
            // shorter name identifies is all there.
            return Match.OVERLAP
        }
        // Not nested: two identifying words in common ("Davis Dental Creations" plus a dentist's
        // surname, "Dunloe" and "Local"). ONE shared word is not enough: "Arroyo Park" is not
        // "Arroyo Pool", "Avid & Co." is not "The Avid Reader Bookstore".
        return if ((da intersect db).size >= 2) Match.OVERLAP else Match.NONE
    }

    /** The loose test: the same business by [match], any kind but NONE. */
    fun agree(a: String?, b: String?, extraGeneric: Set<String> = emptySet()): Boolean =
        match(a, b, extraGeneric) != Match.NONE

    /** The words of a town out of a listing's address ("239 G St, Davis, CA 95616" -> davis, ca),
     *  to pass as [extraGeneric]: a name that ends in its own town is the name. */
    fun cityWords(address: String?): Set<String> {
        if (address.isNullOrBlank()) return emptySet()
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptySet()
        return parts.drop(1).flatMap { tokens(it) }.filter { it.length >= 2 && !it.all { c -> c.isDigit() } }.toSet()
    }
}
