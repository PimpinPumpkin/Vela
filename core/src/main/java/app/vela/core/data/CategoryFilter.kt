package app.vela.core.data

import app.vela.core.model.Place

/**
 * Optional content filter: drop places whose Google CATEGORY marks them as adult / nightlife /
 * alcohol / gambling / smoking. Off by default; enabled by the "Hide adult categories" setting.
 * Category is free text from Google (e.g. "Bar", "Night club", "Liquor store"). Matching is on the
 * CATEGORY only — never the name — so a place whose category is "Restaurant" is always kept.
 *
 * Matching is PRECISE, not naive substring (a bare `" bar" in category` both misses a standalone
 * "Bar" and false-matches "Sushi bar"/"Public library"/"Discount store"/"Alcoholism treatment"):
 *  - [EXACT]  — the whole category equals one of these (handles standalone "Bar"/"Pub").
 *  - [PHRASE] — the category CONTAINS one of these specific, unambiguous strings.
 * Food "…bar" categories (sushi/juice/coffee/salad/oyster/snack bar) are deliberately KEPT — only
 * the alcohol-bar phrases below are blocked. "Bar & grill" is left as-is (food-primary).
 *
 * **Localised categories:** Google returns the category in the app's language (`hl=<lang>`), so the
 * PHRASE list also carries the equivalent terms for Vela's other UI languages (fr de es it pt nl ru
 * pl sv uk). Only high-confidence, unambiguous terms are included per language — deliberately
 * conservative to avoid dropping a benign place for a general user (a stricter, name-aware variant
 * is a downstream concern, not this shared filter).
 *
 * Applied at the data-source boundary ([app.vela.core.data.google.GoogleMapsDataSource.search] and
 * [nearbyPlaces]) so search results AND ambient map POIs are filtered from one seam.
 */
object CategoryFilter {

    /** Whether the filter is active. Off by default; the app flips it from the "Hide adult categories"
     *  setting ([app.vela.ui.HideAdult]) so the data-source seam can gate on a :core-visible flag
     *  without :core depending on the app's reactive holder. */
    @Volatile
    var enabled: Boolean = false

    /** The entire category equals one of these (after lowercase+trim). */
    private val EXACT = setOf(
        // English
        "bar", "pub", "gastropub", "night club", "nightclub", "lounge bar", "wine bar", "cocktail bar",
        "brewery", "brewpub", "distillery", "winery", "casino", "liquor store", "adult entertainment",
        // Other languages — standalone bar/pub words
        "bár", "барь", "бар", // ru/uk transliterations of "bar"
        "kneipe", "diskothek", "kasino", // de
        "discoteca", // es/it/pt
        "kasyno", "dyskoteka", // pl
        "nattklubb", // sv
        "казино", "нічний клуб", "ночной клуб", // ru/uk
    )

    /** The category CONTAINS one of these — each specific enough not to collide with a benign
     *  category (no bare "bar"/"pub"/"disco"/"alcohol"/"lounge" here — those over-match). */
    private val PHRASE = listOf(
        // ---- English ----
        "wine bar", "cocktail bar", "sports bar", "beer bar", "dive bar", "gay bar", "hookah bar",
        "piano bar", "karaoke bar", "tiki bar", "whisky bar", "whiskey bar", "cigar bar",
        "night club", "nightclub", "cabaret", "gentlemen's club", "gentlemens club", "strip club",
        "go-go bar", "topless bar", "peep show", "adult entertainment", "adult video", "adult dvd",
        "sex shop", "adult store", "adult book", "escort service", "escort agency", "massage parlor",
        "brewpub", "brewery", "brewing company", "distillery", "winery", "vineyard", "wine cellar",
        "liquor store", "wine shop", "wine store", "bottle shop", "off-licence", "off licence",
        "beer store", "beer garden", "beer hall",
        "casino", "gambling", "betting", "bookmaker", "sportsbook", "off-track betting", "lottery retailer",
        "hookah lounge", "shisha", "cigar lounge", "smoke shop", "head shop", "tobacco shop", "tobacco store",
        "vape shop", "e-cigarette", "vaporizer store", "cannabis", "marijuana dispensary", "cannabis store",
        "dispensary",
        // ---- French ----
        "boîte de nuit", "discothèque", "bar à vin", "bar à cocktails", "débit de boissons", "cave à vin",
        "caviste", "brasserie artisanale", "club de striptease", "sex-shop", "boutique érotique",
        "maison close", "salle de jeux", "casa de apostas",
        // ---- Spanish ----
        "club nocturno", "bar de copas", "coctelería", "licorería", "vinatería", "cervecería artesanal",
        "club de estriptis", "sala de fiestas", "casa de apuestas", "tienda erótica", "sex-shop",
        // ---- German ----
        "nachtclub", "nachtbar", "weinbar", "cocktailbar", "spirituosen", "weinhandlung", "brauerei",
        "spielhalle", "spielbank", "wettbüro", "erotik", "bordell", "shisha-bar",
        // ---- Italian ----
        "discoteca", "enoteca", "vineria", "birreria", "birrificio", "distilleria", "sala giochi",
        "sala scommesse", "sexy shop", "night club",
        // ---- Portuguese ----
        "casa noturna", "boate", "adega", "loja de bebidas", "cervejaria artesanal", "casa de apostas",
        "sex shop", "casa de shows adulto",
        // ---- Dutch ----
        "nachtclub", "wijnbar", "slijterij", "brouwerij", "gokhal", "speelhal", "wedkantoor", "seksshop",
        "coffeeshop",
        // ---- Polish ----
        "klub nocny", "winiarnia", "sklep monopolowy", "browar", "gorzelnia", "kasyno", "salon gier",
        "zakłady bukmacherskie", "sklep erotyczny", "nocny klub",
        // ---- Russian ----
        "ночной клуб", "винный бар", "кальян-бар", "магазин алкоголя", "пивоварня", "казино",
        "букмекер", "тотализатор", "секс-шоп", "стриптиз",
        // ---- Ukrainian ----
        "нічний клуб", "винний бар", "кальян-бар", "магазин алкоголю", "пивоварня", "казино",
        "букмекер", "секс-шоп", "стриптиз",
        // ---- Swedish ----
        "nattklubb", "vinbar", "systembolaget", "spritbutik", "bryggeri", "spelhall", "vadslagning",
        "sexbutik",
    )

    /** True if [category] is an adult/nightlife/alcohol/gambling/smoking category that should be hidden. */
    fun isAdult(category: String?): Boolean {
        val c = category?.lowercase()?.trim() ?: return false
        if (c.isEmpty()) return false
        if (c in EXACT) return true
        return PHRASE.any { it in c }
    }

    /** Keep only places whose category isn't adult/nightlife. */
    fun filter(places: List<Place>): List<Place> = places.filterNot { isAdult(it.category) }

    /** Filter only when [enabled] (the setting is on); otherwise pass through unchanged. */
    fun applyIfEnabled(places: List<Place>): List<Place> = if (enabled) filter(places) else places
}
