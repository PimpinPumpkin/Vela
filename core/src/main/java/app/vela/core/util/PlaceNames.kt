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

    private val CONNECTORS = setOf("and", "of", "at", "by", "for", "with")
    private val LEGAL = setOf("llc", "inc", "corp", "co", "ltd", "company", "incorporated", "corporation", "pc", "apc", "llp", "pllc", "gmbh", "ag", "sa", "srl", "bv", "pty", "plc")
    private val CHAIN_TAILS = listOf(
        "by wyndham", "by marriott", "by hilton", "by ihg", "by choice hotels", "by best western", "by radisson",
        "an ascend collection hotel", "a tribute portfolio hotel", "a marriott hotel", "a hilton hotel",
    )
    private val ABBR = mapOf(
        "st" to "street", "ave" to "avenue", "blvd" to "boulevard", "rd" to "road", "ctr" to "center", "centre" to "center",
        "ny" to "new york", "nyc" to "new york", "univ" to "university", "mt" to "mount", "ft" to "fort", "hwy" to "highway",
        "pkwy" to "parkway", "sq" to "square", "jr" to "junior", "intl" to "international", "natl" to "national",
        "dr" to "doctor", "drs" to "doctors", "ln" to "lane", "ct" to "court", "ter" to "terrace", "pl" to "place",
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
        "paint", "garden", "nursery", "florist", "flowers", "gifts", "gift", "books", "bookstore", "toys", "thrift", "resale",
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
        // Street types: "38th st grocery deli" and "Rsvp 38th Street Venture" share "38th street"
        // and are not one business; the ordinal alone must not carry it.
        "street", "avenue", "boulevard", "road", "lane", "court", "way", "highway", "parkway", "terrace", "alley", "route",
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
        if (joined.isNotEmpty() && joined[0] == "the") joined.removeAt(0)
        // A connector left dangling by a dropped suffix ("Avid & Co." is "avid and" without this).
        while (joined.isNotEmpty() && joined.last() in CONNECTORS) joined.removeAt(joined.lastIndex)
        while (joined.isNotEmpty() && joined.first() in CONNECTORS) joined.removeAt(0)
        return joined.joinToString(" ")
    }

    fun tokens(name: String?): List<String> = normalized(name).split(' ').filter { it.isNotEmpty() }

    /** The words that identify a business: not generic, not a number, at least two letters
     *  ("US Bank" is named by "us"; a single letter never names anything on its own). */
    fun distinctive(name: String?, extraGeneric: Set<String> = emptySet()): Set<String> =
        tokens(name).filter { isDistinctive(it, extraGeneric) }.toSet()

    // A number that survived the trailing-store-number strip IS the name ("Thai 5", "Pho 175",
    // "Studio 54"): without it those names were all generic words and matched nothing.
    /** [words] with each plural replaced by its singular WHEN the other name has that singular. */
    private fun foldPlurals(words: List<String>, other: Set<String>): List<String> = words.map { w ->
        if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && w.dropLast(1) in other) w.dropLast(1) else w
    }

    /** Two identifying words, or one of at least five letters: "speedee", "nordstrom", "laurenzos"
     *  carry a name on their own; "finn", "bayou", "main" do not (Midtown and Houston, 2026-09-22:
     *  "Bayou Place" against "Bunnies On The Bayou", "Bryant Health Clinic" against an osteria
     *  in Bryant Park). */
    private fun strongCore(words: List<String>): Boolean =
        words.size >= 2 || (words.size == 1 && words[0].length >= 5 && !isOrdinal(words[0]))

    private val ORDINAL = Regex("\\d+(st|nd|rd|th)?")
    private fun isOrdinal(w: String) = ORDINAL.matches(w)

    /**
     * Words that are generic IN THIS POOL: a token carried by [minNames] or more of [names] names
     * a neighborhood or a mall rather than a business ("Memorial Heights", "NoMad", "Flatiron"),
     * and a caller passes the result as `extraGeneric` so two businesses that merely share the
     * neighborhood are not one. The pool is whatever the caller is comparing against (the places
     * on screen, a search's results), so it costs nothing to compute.
     */
    fun localGeneric(names: Collection<String?>, minNames: Int = 3): Set<String> {
        val counts = HashMap<String, Int>()
        for (n in names) for (t in tokens(n).toSet()) counts[t] = (counts[t] ?: 0) + 1
        return counts.filterValues { it >= minNames }.keys
    }

    private fun isDistinctive(w: String, extraGeneric: Set<String>): Boolean =
        (w !in GENERIC && w !in extraGeneric && !pluralGeneric(w) && w.length >= 2) || (w.length == 1 && w[0].isDigit())

    /** "studios", "salons", "cleaners" are as generic as their singulars. */
    private fun pluralGeneric(w: String): Boolean = w.length > 3 && w.endsWith("s") && w.dropLast(1) in GENERIC

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
        // Plurals fold PAIRWISE ("Sola Salons" against "Sola Salon Studios"): a word loses its "s"
        // only when the other name carries the singular, so "Davis" and "Wells" stay themselves.
        val ra = na.split(' '); val rb = nb.split(' ')
        val la = foldPlurals(ra, rb.toSet()); val lb = foldPlurals(rb, ra.toSet())
        if (la == lb) return Match.EXACT
        val ta = la.toSet(); val tb = lb.toSet()
        val da = la.filter { isDistinctive(it, extraGeneric) }.toSet(); val db = lb.filter { isDistinctive(it, extraGeneric) }.toSet()
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
            // shorter name identifies is all there. A short lone word is not enough of an
            // identity to claim a longer name ("The Finn" is not "Dish Society at Finn Hall").
            return if (strongCore(coreDistinct)) Match.OVERLAP else Match.NONE
        }
        // Not nested: two identifying words in common ("Davis Dental Creations" plus a dentist's
        // surname, "Dunloe" and "Local"). ONE shared word is not enough: "Arroyo Park" is not
        // "Arroyo Pool", "Avid & Co." is not "The Avid Reader Bookstore".
        if ((da intersect db).size >= 2) return Match.OVERLAP
        // Three more families from the Midtown and Houston side by sides (2026-09-22):
        // - a BRAND PREFIX of two or more words with an identifying one among them: "Bank of
        //   America Financial Center" and "Bank of America ATM";
        val prefix = la.zip(lb).takeWhile { (x, y) -> x == y }.size
        if (prefix >= 2 && la.take(prefix).any { isDistinctive(it, extraGeneric) }) return Match.OVERLAP
        // - the shorter name, less generic words at its ends, is a PHRASE inside the longer ("23rd
        //   Street Dental" of "23rd Street Dental Associates" inside "My NYC Dentist - 23rd Street
        //   Dental"). Three words carry it even when the only identifying one is a street number
        //   (that is how New York names things); two words need an identifying word that is not.
        val (short, long) = if (la.size <= lb.size) la to lb else lb to la
        val lead = short.dropWhile { !isDistinctive(it, extraGeneric) }
        for (k in lead.size downTo 2) {
            val phrase = lead.take(k)
            val named = phrase.any { isDistinctive(it, extraGeneric) && !isOrdinal(it) } || (k >= 3 && phrase.any { isDistinctive(it, extraGeneric) })
            if (named && long.windowed(k).any { it == phrase }) return Match.OVERLAP
        }
        // - the shorter name's identifying words all sit in the longer's, which has more of them
        //   ("Laurenzo's Restaurant" against "Laurenzo's Prime Rib"), when the shorter is more than
        //   one word and its identity is not just a street number. A one-word name inside a longer
        //   one stays out ("Avid & Co." is still not "The Avid Reader"), and so does "Arroyo Park"
        //   against "Arroyo Pool" (the same one identifying word on both sides).
        // With ONE identifying word in the shorter name, the longer has to LEAD with it (a brand
        // in front: "Laurenzo's Prime Rib", "Walgreens Photo", "Chase Home Lending"); a word that
        // merely appears inside the longer name is a neighborhood or a landmark far more often
        // than a business ("Bayou Place" against "Bunnies On The Bayou").
        val (dShort, dLong) = if (short === la) da to db else db to da
        if (short.size >= 2 && dShort.isNotEmpty() && dLong.size > dShort.size && dLong.containsAll(dShort) &&
            dShort.any { !isOrdinal(it) } && (dShort.size >= 2 || long.first() == dShort.single())
        ) return Match.OVERLAP
        return Match.NONE
    }

    /** The loose test: the same business by [match], any kind but NONE. */
    fun agree(a: String?, b: String?, extraGeneric: Set<String> = emptySet()): Boolean =
        match(a, b, extraGeneric) != Match.NONE

    /**
     * [agree] with the two places' KINDS (the icon group, "fuel", "food", "shop"...; null or
     * "default" = unknown) in hand: an OVERLAP between two known, different kinds is not a match.
     * "Cathcart Station Alfy's" (a fuel station) and "Cathcart Station LLC" (a pizza place) share
     * their identifying words and are two businesses on one lot; a VARIANT or EXACT still counts
     * across kinds, because "Safeway Pharmacy" and "Safeway" ARE one business in two listings.
     */
    fun sameBusiness(a: String?, kindA: String?, b: String?, kindB: String?, extraGeneric: Set<String> = emptySet()): Boolean {
        val m = match(a, b, extraGeneric)
        if (m == Match.NONE) return false
        if (m == Match.OVERLAP && knownKind(kindA) && knownKind(kindB) && kindA != kindB) return false
        return true
    }

    /** Two fuel stations within [FUEL_LOT_M] are one station: a forecourt is one per lot, and the
     *  sources name it after different things (the brand, the operator, the shop inside). Two
     *  stations facing each other across a road are the case to refuse: when both sides carry a
     *  house number and the numbers differ they are two lots whatever the distance, and the
     *  distance itself is short of a road's width plus two setbacks (user 2026-09-22). */
    fun sameFuelLot(kindA: String?, kindB: String?, distanceM: Double, numberA: String? = null, numberB: String? = null): Boolean {
        if (kindA != FUEL_KIND || kindB != FUEL_KIND) return false
        if (!numberA.isNullOrBlank() && !numberB.isNullOrBlank() && numberA != numberB) return false
        return distanceM < FUEL_LOT_M
    }

    /** The house number a street address starts with ("16315 State Route 9 SE" -> "16315"). */
    fun houseNumber(address: String?): String? =
        address?.trimStart()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }

    const val FUEL_KIND = "fuel"
    const val FUEL_LOT_M = 30.0
    private fun knownKind(k: String?) = !k.isNullOrBlank() && k != "default"

    /** The words of a town out of a listing's address ("239 G St, Davis, CA 95616" -> davis, ca),
     *  to pass as [extraGeneric]: a name that ends in its own town is the name. */
    fun cityWords(address: String?): Set<String> {
        if (address.isNullOrBlank()) return emptySet()
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptySet()
        return parts.drop(1).flatMap { tokens(it) }.filter { it.length >= 2 && !it.all { c -> c.isDigit() } }.toSet()
    }
}
