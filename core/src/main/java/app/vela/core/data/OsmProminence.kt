package app.vela.core.data

import app.vela.core.model.Place

/**
 * Map-display prominence for an OSM/Overture POI — the open-source stand-in for
 * [app.vela.core.data.google.ambientProminence].
 *
 * WHY THIS EXISTS: the Google ambient layer ranks by review count
 * (`ln(reviewCount+1) * (0.6 + rating/10)`), which is the one signal neither OSM nor
 * Overture carries. Without a replacement, an OSM-sourced dot layer is a UNIFORM blanket —
 * every POI equally important, so the collision winner is arbitrary and the take-N cap drops
 * landmarks as readily as benches. That uniformity, not coverage, is what has kept the OSM
 * layer a fallback (`PoiIcons` shows it only "whenever ambient ISN'T"). Score the open data
 * and the fallback can become the default — which deletes the 15-term Google fan-out, its
 * ~180 MB/12 s parse churn, and the app's single most machine-shaped request pattern.
 *
 * SCALE: deliberately the same ~0–9.5 range as `ambientProminence` (which reaches ≈9.4 at
 * 10k reviews), so the two can sort in ONE pool during a mixed rollout and
 * `rankAmbientPlaces`'s "first = wins the label slot" contract keeps holding. Do not rescale
 * one without the other.
 *
 * ⚠️ THE WEIGHTS BELOW ARE UNTUNED STARTING VALUES, not measured ones. They encode the
 * ordering the Google layer's own comments call for (recognizable anchors lead; "a 0-review
 * mobile mechanic, an adult-family-home, a road intersection" sink) but the constants have not
 * been validated against a real viewport. Tune on a DENSE downtown tile before flipping any
 * default — density is also the render cost, since symbol collision scales badly per tile.
 */
object OsmProminence {

    // ---- category priors -------------------------------------------------------------------
    // The dominant term, because category is the only signal present on essentially EVERY OSM
    // POI. It also has to SUPPRESS, not merely rank: OSM maps street furniture as first-class
    // nodes (benches, post boxes, individual parking spaces), and those must never win a label
    // slot however thoroughly they're tagged.

    /** Anchors a whole viewport — what a map shows first at low zoom. */
    private const val PRIOR_ANCHOR = 4.5

    /** Recognizable and sought-out, but not a landmark. */
    private const val PRIOR_STRONG = 3.2

    /** The ordinary high-street business — the bulk of a useful dot layer. */
    private const val PRIOR_NORMAL = 2.2

    /** Real, mappable, rarely the reason someone looks at the map. */
    private const val PRIOR_MINOR = 1.0

    /** Mapped but never a map dot. Forces the whole score to zero — see [score]. */
    private const val PRIOR_FURNITURE = 0.0

    /** An OSM value we have no prior for. Below [PRIOR_NORMAL] on purpose: an unrecognized
     *  category should not outrank a known-good one, but it must not be suppressed either —
     *  OSM's tag vocabulary is open-ended and long-tail values are usually legitimate. */
    private const val PRIOR_UNKNOWN = 1.6

    private val ANCHOR = setOf(
        "hospital", "university", "airport", "aerodrome", "stadium", "museum", "zoo",
        "theme_park", "national_park", "mall", "supermarket", "department_store",
        "train_station", "station", "bus_station", "ferry_terminal", "cathedral",
        "castle", "townhall", "courthouse", "prison", "convention_centre",
    )

    private val STRONG = setOf(
        "college", "school", "library", "theatre", "cinema", "hotel", "hostel", "motel",
        "marketplace", "park", "sports_centre", "swimming_pool", "golf_course", "attraction",
        "viewpoint", "place_of_worship", "church", "mosque", "synagogue", "temple",
        "police", "fire_station", "post_office", "bank", "fuel", "pharmacy", "clinic",
        "doctors", "dentist", "veterinary", "nursing_home", "bus_stop", "tram_stop",
    )

    private val NORMAL = setOf(
        "restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream",
        "bakery", "butcher", "greengrocer", "convenience", "alcohol", "beverages",
        "hairdresser", "beauty", "barber", "gym", "fitness_centre", "spa",
        "car_repair", "car", "car_parts", "motorcycle", "bicycle", "hardware", "doityourself",
        "clothes", "shoes", "jewelry", "books", "florist", "optician", "laundry", "dry_cleaning",
        "electronics", "mobile_phone", "computer", "furniture", "garden_centre", "pet",
        "toys", "sports", "stationery", "gift", "deli", "seafood", "confectionery",
        "copyshop", "travel_agency", "estate_agent", "car_rental", "bureau_de_change",
    )

    private val MINOR = setOf(
        "kindergarten", "childcare", "community_centre", "social_facility", "playground",
        "picnic_site", "pitch", "dog_park", "garden", "allotments", "parking",
        "charging_station", "car_wash", "recycling", "hunting_stand", "shelter",
    )

    private val FURNITURE = setOf(
        "bench", "waste_basket", "waste_disposal", "vending_machine", "post_box", "atm",
        "telephone", "toilets", "drinking_water", "bicycle_parking", "motorcycle_parking",
        "bicycle_repair_station", "parking_space", "parking_entrance", "surveillance",
        "street_lamp", "fire_hydrant", "tree", "bollard", "gate", "crossing",
        "traffic_signals", "stop", "give_way", "turning_circle", "passing_place",
        "street_cabinet", "pole", "bicycle_rental_station",
    )

    // ---- notability ------------------------------------------------------------------------
    // The closest thing open data has to "how many people know this". A `wikidata` tag is the
    // strongest: OSM mappers attach it to things notable enough to have their own entity, which
    // is roughly the population the Google layer's high review counts select for. `brand` is
    // weaker but genuinely matters for a map — a Safeway dot is recognizable precisely BECAUSE
    // it's a chain, and chains are what the Google ranking surfaces first.
    private const val NOTABLE_WIKIDATA = 2.8
    private const val NOTABLE_WIKIPEDIA = 2.2
    private const val NOTABLE_BRAND = 1.6

    // ---- attribute completeness ------------------------------------------------------------
    // Weak individually, useful together: somebody bothered to fill these in, which correlates
    // with the POI being real, current and worth finding. Capped low on purpose — a
    // meticulously tagged corner shop must not reach a landmark's score.
    private const val HAS_WEBSITE = 0.5
    private const val HAS_HOURS = 0.5
    private const val HAS_PHONE = 0.4
    private const val HAS_ADDRESS = 0.2

    /** Overture's existence confidence (0..1) scaled into the score. Not prominence — it says
     *  "this place is probably real", which at the low end is exactly the junk the Organic Maps
     *  reviewers hit in Poland (37% of Warsaw records clear 0.8). Absent → treated as neutral. */
    private const val CONFIDENCE_WEIGHT = 0.8
    private const val CONFIDENCE_NEUTRAL = 0.5

    /**
     * Prominence for an OSM/Overture POI, on `ambientProminence`'s scale.
     *
     * [category] is the raw OSM tag VALUE (`fast_food`, `place_of_worship`) or the humanized
     * form Vela stores on [Place] (`Fast food`) — [normalize] accepts either, because
     * `OverpassPois.toPlace` humanizes before the score ever sees it.
     *
     * Street furniture returns exactly `0.0` regardless of every other signal: suppression has
     * to be absolute, or a vending machine with an `opening_hours` tag outranks a cafe without one.
     */
    fun score(
        category: String?,
        hasWebsite: Boolean = false,
        hasPhone: Boolean = false,
        hasHours: Boolean = false,
        hasAddress: Boolean = false,
        wikidata: Boolean = false,
        wikipedia: Boolean = false,
        brand: Boolean = false,
        overtureConfidence: Double? = null,
    ): Double {
        val prior = prior(category)
        if (prior == PRIOR_FURNITURE) return 0.0

        // Take the strongest notability signal rather than summing: wikidata and wikipedia
        // overwhelmingly co-occur on the same objects, so adding them double-counts one fact.
        val notability = maxOf(
            if (wikidata) NOTABLE_WIKIDATA else 0.0,
            if (wikipedia) NOTABLE_WIKIPEDIA else 0.0,
            if (brand) NOTABLE_BRAND else 0.0,
        )

        val completeness =
            (if (hasWebsite) HAS_WEBSITE else 0.0) +
                (if (hasPhone) HAS_PHONE else 0.0) +
                (if (hasHours) HAS_HOURS else 0.0) +
                (if (hasAddress) HAS_ADDRESS else 0.0)

        val confidence =
            (overtureConfidence?.coerceIn(0.0, 1.0) ?: CONFIDENCE_NEUTRAL) * CONFIDENCE_WEIGHT

        return prior + notability + completeness + confidence
    }

    /**
     * Prominence from a [Place] — the full signal set, now that `OverpassPois.toPlace` retains
     * `brand`, `wikidata` and `wikipedia` (falling back to the `brand:*` forms, which chains carry
     * far more often than the plain tags).
     *
     * Overture's `confidence` is the one input a [Place] cannot express — there is no field for it
     * — so an Overture-sourced caller should use the signal overload directly rather than
     * round-tripping through [Place], where confidence silently reads as neutral.
     */
    fun score(p: Place): Double = score(
        category = p.category,
        hasWebsite = !p.website.isNullOrBlank(),
        hasPhone = !p.phone.isNullOrBlank(),
        hasHours = p.hours.isNotEmpty(),
        hasAddress = !p.address.isNullOrBlank(),
        wikidata = !p.wikidata.isNullOrBlank(),
        wikipedia = !p.wikipedia.isNullOrBlank(),
        brand = !p.brand.isNullOrBlank(),
    )

    /** Lowercase, and accept the humanized form Vela stores on [Place] ("Fast food" →
     *  "fast_food"). Tag values never contain spaces, so this is unambiguous. */
    private fun normalize(category: String?): String? =
        category?.trim()?.lowercase()?.replace(' ', '_')?.takeIf { it.isNotEmpty() }

    private fun prior(category: String?): Double = when (val c = normalize(category)) {
        null -> PRIOR_UNKNOWN
        in FURNITURE -> PRIOR_FURNITURE
        in ANCHOR -> PRIOR_ANCHOR
        in STRONG -> PRIOR_STRONG
        in NORMAL -> PRIOR_NORMAL
        in MINOR -> PRIOR_MINOR
        else -> PRIOR_UNKNOWN.also { _ -> unmatched(c) }
    }

    /** Hook for the tuning pass: the long tail of OSM values we have no prior for is the list
     *  worth reading before hand-classifying more of them. Deliberately a no-op — this is
     *  `:core`, which must not depend on a logger, and the tile-build pipeline is where the
     *  real census belongs. */
    @Suppress("UNUSED_PARAMETER")
    private fun unmatched(category: String) { /* no-op; see kdoc */ }
}

/**
 * Order OSM/Overture POIs for the browse map — the open-data twin of
 * `rankAmbientPlaces`, with the same contract: first = wins the label slot, and the tail is
 * what a take-N cap drops. Prominence first, distance only as a tiebreak, because
 * distance-bucketing was tried on the Google layer and REVERTED (it floated near-centre junk
 * above the landmarks).
 */
fun rankOsmPlaces(places: List<Place>): List<Place> =
    places.sortedWith(
        compareByDescending<Place> { OsmProminence.score(it) }
            .thenBy { it.distanceMeters ?: Double.MAX_VALUE },
    )
