package app.vela.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Google-style POI markers: a category-coloured circle with a white Material
 * Icons glyph in the middle, generated at runtime and registered on the style as
 * `vela-poi-<group>` images. The bundled OpenFreeMap style (liberty-roboto.json)
 * references them from its POI layers via an `icon-image` match on `class`.
 * Keyless - the Material Icons font is bundled in assets.
 *
 * The group keys + colours here MUST stay in sync with the match expression baked
 * into the style asset (see the python transform that generates it).
 */
object PoiIcons {

    // group -> (Material Icons codepoint, circle colour)
    private val GROUPS = listOf(
        Triple("food", 0xe56c, "#E8710A"),
        Triple("shop", 0xe8cc, "#4285F4"),
        Triple("lodging", 0xe53a, "#C2185B"),
        Triple("fuel", 0xe546, "#1967D2"),
        Triple("parking", 0xe54f, "#1A73E8"),
        Triple("park", 0xea63, "#188038"),
        Triple("health", 0xe548, "#D93025"),
        Triple("edu", 0xe80c, "#00897B"),
        Triple("civic", 0xe84f, "#5F6368"),
        Triple("culture", 0xea36, "#9334E6"),
        Triple("sport", 0xeb43, "#E37400"),
        Triple("transit", 0xe530, "#1A73E8"),
        Triple("default", 0xe55f, "#5F6368"),
    )

    /** Set by VelaMapView at style load: over satellite imagery the teardrop backings render
     *  WHITE (Google hybrid's treatment) instead of the muted grey - grey sank into rooftops.
     *  A satellite toggle reloads the style (it's in the styleKey), so every bitmap regenerates
     *  through this flag. */
    @Volatile var satellite: Boolean = false

    private fun backing(): Int = Color.parseColor(if (satellite) "#FFFFFF" else "#9AA0A6")

    fun addTo(context: Context, style: Style) {
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/MaterialIcons-Regular.ttf")
        }.getOrNull() ?: return
        GROUPS.forEach { (key, codepoint, color) ->
            if (style.getImage("vela-poi-$key") == null) {
                style.addImage("vela-poi-$key", marker(tf, codepoint, color))
            }
        }
    }

    /** Register a NUMBERED STOP pin ("vela-stopnum-<n>"): brand-teal circle, white ring and
     *  number, a short tail so the tip marks the spot - the trip's intermediate stops drawn in
     *  visit order (theme-independent; the teal is VelaTeal, bitmaps can't read the theme). */
    fun ensureStopNumberIcon(style: Style, n: Int): String {
        val key = "vela-stopnum-$n"
        if (style.getImage(key) != null) return key
        val w = 84
        val h = 100
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val bodyCy = h * 0.38f
        val bodyR = w * 0.36f
        val tipY = h - 6f
        val d = tipY - bodyCy
        val sin = (bodyR / d).coerceAtMost(0.985f)
        val cos = kotlin.math.sqrt(1f - sin * sin)
        val teardrop = Path().apply {
            addCircle(cx, bodyCy, bodyR, Path.Direction.CW)
            op(
                Path().apply {
                    moveTo(cx - bodyR * sin, bodyCy + bodyR * cos)
                    lineTo(cx, tipY)
                    lineTo(cx + bodyR * sin, bodyCy + bodyR * cos)
                    close()
                },
                Path.Op.UNION,
            )
        }
        canvas.save()
        canvas.translate(0f, w * 0.02f)
        canvas.drawPath(teardrop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter = BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL)
        })
        canvas.restore()
        // White outline so the pin separates from any basemap/imagery, then the teal body.
        canvas.drawPath(teardrop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setStyle(Paint.Style.STROKE) // explicit setter - `style` resolves to the fn param
            strokeWidth = w * 0.06f
        })
        canvas.drawPath(teardrop, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14857A") })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD // plain sans - the class typeface() is the ICON font
            color = Color.WHITE
            textSize = w * 0.40f
            textAlign = Paint.Align.CENTER
        }
        val fm = text.fontMetrics
        canvas.drawText("$n", cx, bodyCy - (fm.ascent + fm.descent) / 2f, text)
        style.addImage(key, bmp)
        return key
    }

    // class -> group, for OpenFreeMap Liberty's rank-tiered poi layers
    // (poi_r1/r7/r20 mix all categories, so they need a class match).
    private val CLASS_GROUPS = linkedMapOf(
        "food" to listOf("restaurant", "fast_food", "cafe", "bar", "pub", "food_court", "ice_cream", "bakery", "food", "beer", "deli", "confectionery"),
        "shop" to listOf("shop", "grocery", "supermarket", "convenience", "clothing_store", "mall", "department_store", "jewelry", "gift", "books", "furniture", "hardware", "florist", "mobile_phone", "optician", "hairdresser", "laundry", "butcher", "greengrocer", "marketplace", "car", "bicycle", "outdoor", "chemist", "shoes", "toys"),
        "lodging" to listOf("lodging"),
        "fuel" to listOf("fuel"),
        "parking" to listOf("parking", "bicycle_parking"),
        "park" to listOf("park", "garden", "nature_reserve", "golf", "pitch", "playground", "dog_park", "picnic_site"),
        "health" to listOf("hospital", "pharmacy", "doctors", "dentist", "veterinary", "clinic"),
        "edu" to listOf("school", "college", "university", "library", "kindergarten"),
        "civic" to listOf("bank", "atm", "post", "police", "fire_station", "town_hall", "courthouse", "place_of_worship", "cemetery", "community_centre"),
        "culture" to listOf("museum", "art_gallery", "cinema", "theatre", "attraction", "gallery", "information", "artwork", "viewpoint", "aquarium", "zoo"),
        "sport" to listOf("stadium", "sports", "sports_centre", "fitness_centre", "swimming_pool"),
        "transit" to listOf("bus", "railway", "aerodrome", "station", "subway", "tram", "ferry_terminal", "airport"),
    )

    /** Best dot group for a Google place's category phrase ("Pizza restaurant", "Gas station",
     * "Coffee shop") so an ambient Google POI gets the SAME coloured dot as the equivalent OSM
     * POI. Keyword match over the same vocabulary as [CLASS_GROUPS]; order matters (more specific
     * first). The image to use is `vela-poi-<returned group>`. */
    fun groupForCategory(category: String?): String {
        val c = category?.lowercase() ?: return "default"
        fun any(vararg k: String) = k.any { it in c }
        return when {
            any("gas station", "gas ", "fuel", "petrol", "charging station", "ev charg") -> "fuel"
            any("coffee", "cafe", "café", "espresso", "tea ", "teahouse") -> "food"
            any("restaurant", "pizza", "burger", "steak", "sushi", "diner", "bakery", "deli", "bistro",
                "eatery", "barbecue", "bbq", "taco", "sandwich", "ice cream", "donut", "brewery", "brewpub",
                "grill", " bar", "pub", "food", "buffet", "ramen", "noodle", "pho", "creamery") -> "food"
            any("hotel", "motel", "inn", "lodging", "resort", "hostel", "bed & breakfast") -> "lodging"
            any("hospital", "clinic", "pharmacy", "drugstore", "dentist", "doctor", "medical", "health",
                "veterinar", "urgent care", "physician", "chiropract", "optometr") -> "health"
            any("parking", "parking garage", "parking lot") -> "parking" // before "park" - "parking".contains("park")
            any("park", "garden", "trail", "playground", "campground", "nature") -> "park"
            any("school", "university", "college", "academy", "education", "library", "kindergarten", "preschool") -> "edu"
            any("museum", "theater", "theatre", "gallery", "cinema", "movie", "art ", "cultural", "historical", "aquarium", "zoo") -> "culture"
            any("gym", "fitness", "stadium", "sport", "golf", "bowling", "yoga", "arena", "athletic",
                "climbing", "recreation cent", "rec cent", "ice rink", "skating") -> "sport"
            any("station", "transit", "airport", "bus ", "train", "subway", "metro", "light rail", "ferry") -> "transit"
            any("bank", "atm", "credit union", "post office", "police", "fire station", "city hall",
                "courthouse", "church", "mosque", "temple", "synagogue", "place of worship", "cemetery",
                "government", "community cent") -> "civic"
            any("store", "shop", "grocery", "supermarket", "market", "mall", "retail", "boutique", "outlet",
                "dealer", "salon", "barber", "hardware", "florist", "laundr", "jewelr", "furniture", "pharmacy",
                "auto parts", "tire", "nail", "spa") -> "shop"
            else -> "default"
        }
    }

    /** Best dot group for a Google place - its category FIRST, then a NAME fallback. Google's keyless
     * data sometimes returns a generic administrative category ("Non-profit organization",
     * "Establishment", "Corporate office") that themes to [default] even though the place is really a
     * gym, church, or school - and the OSM basemap DOES classify it (so the grey ambient dot turns into
     * a themed OSM icon the moment the ambient layer clears on select, the "grey on the map / orange
     * weight when I tap it" YMCA inconsistency). When the category is inconclusive, the NAME usually
     * carries the real signal ("…YMCA", "…Community Church", "…Elementary"), so the ambient dot gets the
     * SAME icon Google and our OSM POIs give it. Category stays authoritative; the name only breaks a
     * [default] tie. */
    fun groupFor(name: String?, category: String?): String {
        val byCat = groupForCategory(category)
        if (byCat != "default") return byCat
        return groupForName(name)
    }

    /** Category group inferred from a place NAME alone - only strong, unambiguous signals, used as the
     * fallback in [groupFor] when the category didn't resolve. Conservative on purpose (a café named
     * "The Gym" is a rarity; a place literally named "…YMCA" is a gym) so it can't mis-theme a place
     * whose category was simply missing. */
    private fun groupForName(name: String?): String {
        val n = name?.lowercase() ?: return "default"
        fun any(vararg k: String) = k.any { it in n }
        return when {
            any("ymca", "ywca", "crossfit", " gym", "fitness", "athletic club", "health club", "rec center", "recreation center") -> "sport"
            any("church", "chapel", "cathedral", "parish", " mosque", "synagogue", "temple", "gurdwara",
                "kingdom hall", "ministries", "worship center") -> "civic"
            any("elementary", "middle school", "high school", " academy", "university", " college",
                "montessori", "preschool", "day school") -> "edu"
            any("hospital", "medical center", "medical centre", " clinic", "pharmacy", "urgent care",
                "dental", "dentist") -> "health"
            any(" museum", "theatre", " theater", "art gallery") -> "culture"
            else -> "default"
        }
    }

    /** Remap OpenFreeMap Liberty's poi_r1/r7/r20 layers to our coloured markers,
     * and colour the POI label text by category like Google - saturated in light, PASTEL TINTS in
     * dark (Google's dark labels are lightened category colours, not the full-saturation ones,
     * which vanish against a dark map - ground-truthed vs the Maps app; see [labelColor]). */
    fun applyToLiberty(style: Style, dark: Boolean) {
        runCatching {
            val icon = Expression.raw(match("\"vela-poi-default\"") { "\"vela-poi-$it\"" })
            val fallback = if (dark) "#C8CDD4" else "#5F6368"
            val textColor = Expression.raw(match("\"$fallback\"") { "\"${labelColor(it, dark)}\"" })
            listOf("poi_r1", "poi_r7", "poi_r20").forEach { id ->
                val layer = style.getLayer(id) as? SymbolLayer ?: return@forEach
                layer.setProperties(
                    PropertyFactory.iconImage(icon),
                    PropertyFactory.iconSize(0.8f),
                    // Rank the collision by the tile's `rank` (lower = more prominent), which matches
                    // symbol-sort-key order (lower is placed first = wins the slot). So a Safeway (low
                    // rank) beats a tiny tenant inside it (high rank) instead of arbitrary tile order.
                    PropertyFactory.symbolSortKey(Expression.get("rank")),
                    // Label placement MATCHES the ambient Google-POI layer exactly (variable anchor:
                    // prefer left-of-icon at a tight 1.4-em gap, fall back to under-icon on collision).
                    // These OSM layers show whenever ambient ISN'T (fresh area pre-fetch, offline, nav,
                    // search) - a fixed -2.6 offset here leaves labels too far from the icon until they
                    // re-render (the re-render = ambient taking over).
                    PropertyFactory.textVariableAnchor(
                        arrayOf(Property.TEXT_ANCHOR_RIGHT, Property.TEXT_ANCHOR_TOP),
                    ),
                    PropertyFactory.textRadialOffset(1.4f),
                    PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
                    // UPRIGHT font, matching the ambient Google-POI layer. Liberty's default POI face is
                    // Noto Sans ITALIC, so when ambient clears (search / a place selected / nav) these OSM
                    // labels slanted - an inconsistent "everything goes italic mid-search" flicker. Google's
                    // labels are upright everywhere; pin the same regular face the ambient layer uses.
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                )
                // Category-coloured labels (Google-style) in light mode; the dark
                // theme keeps light-grey labels for contrast.
                layer.setProperties(PropertyFactory.textColor(textColor)) // per-category in BOTH modes (dark = pastel tints)
                // Only show POIs that have a NAME - the nameless ones can't be opened
                // (they'd just drop an address pin) and read as junk/duplicate icons.
                // AND with the layer's existing rank filter so the rank gating stays.
                hideNameless(layer)
            }
            // Liberty only shows rank 1-6 POIs at z15 (sparse vs Google). Pull the
            // next tier (poi_r7 = rank 7-19) down to z15 too so more businesses
            // show; MapLibre's label collision keeps it from cluttering.
            style.getLayer("poi_r7")?.setMinZoom(15f)
            // Transit (bus/rail/airport) is its own always-on layer in Liberty, so
            // bus stops clutter every zoom level. Push it to z16+ like Google, and
            // give it our marker + category colour for consistency.
            (style.getLayer("poi_transit") as? SymbolLayer)?.let { layer ->
                layer.setProperties(
                    PropertyFactory.iconImage(icon),
                    PropertyFactory.iconSize(0.8f),
                    PropertyFactory.symbolSortKey(Expression.get("rank")),
                    // Same tight variable-anchor placement as the poi_r* layers above / the ambient layer.
                    PropertyFactory.textVariableAnchor(
                        arrayOf(Property.TEXT_ANCHOR_RIGHT, Property.TEXT_ANCHOR_TOP),
                    ),
                    PropertyFactory.textRadialOffset(1.4f),
                    PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")), // upright, like ambient (Liberty's default is italic)
                )
                layer.setProperties(PropertyFactory.textColor(textColor)) // per-category in BOTH modes (dark = pastel tints)
                layer.setMinZoom(16f)
                hideNameless(layer)
            }
        }
    }

    /** Restrict a POI symbol layer to features that have a `name`, preserving the
     * layer's existing (rank) filter by AND-ing the two. */
    private fun hideNameless(layer: SymbolLayer) {
        val named = Expression.has("name")
        val existing = layer.filter
        layer.setFilter(if (existing != null) Expression.all(existing, named) else named)
    }

    /** Build a MapLibre `match` on the POI `class` → a value per group. */
    private fun match(default: String, value: (String) -> String): String {
        val sb = StringBuilder("""["match",["get","class"]""")
        CLASS_GROUPS.forEach { (group, classes) ->
            sb.append(',').append(classes.joinToString(",", "[", "]") { "\"$it\"" })
            sb.append(',').append(value(group))
        }
        return sb.append(',').append(default).append(']').toString()
    }

    /** Blend [hex] toward white by [f] - Google's DARK-mode POI labels are pastel TINTS of the
     * category colour (ground-truthed against the Maps app in Davis: restaurants read light
     * peach, shopping light blue, lodging light pink), not the saturated light-mode colour. */
    private fun lighten(hex: String, f: Float): String {
        val c = hex.removePrefix("#").toLong(16)
        fun ch(shift: Int): Int {
            val v = ((c shr shift) and 0xFF).toInt()
            return (v + ((255 - v) * f)).toInt().coerceIn(0, 255)
        }
        return String.format("#%02X%02X%02X", ch(16), ch(8), ch(0))
    }

    /** Public single-group variant of [labelColor] for layers styled outside this file
     * (the canonical GTFS stop labels take the transit category colour per theme). */
    fun labelColorFor(group: String, dark: Boolean): String = labelColor(group, dark)

    /** The label colour for a category [group] per theme: the icon colour in light, its pastel
     * tint in dark (Google's own dark-mode treatment - full saturation vanishes on a dark map). */
    private fun labelColor(group: String, dark: Boolean): String {
        val base = GROUPS.first { it.first == group }.third
        return if (dark) lighten(base, 0.55f) else base
    }

    /** Data-driven text colour for the AMBIENT Google-POI layer: match the feature's `icon`
     * property ("vela-poi-<group>") to the category label colour, Google-style - the label
     * reads as part of the icon. Default = the plain per-theme label grey. */
    fun ambientLabelColor(dark: Boolean): Expression {
        val sb = StringBuilder("""["match",["get","icon"]""")
        GROUPS.forEach { (group, _, _) ->
            sb.append(",\"vela-poi-").append(group).append("\",\"").append(labelColor(group, dark)).append('"')
        }
        sb.append(",\"").append(if (dark) "#C8CDD4" else "#3C4043").append("\"]")
        return Expression.raw(sb.toString())
    }

    private fun marker(tf: Typeface, codepoint: Int, colorHex: String): Bitmap {
        // Google-style POI: a category-coloured dot with a white glyph sitting in front of a
        // muted-grey TEARDROP/pin backing whose point extends below the dot (NO white ring), with a
        // soft drop shadow. The dot is the BITMAP CENTRE - so with the layer's default centre anchor
        // the dot marks the place and the grey teardrop reads as a pin behind it (no placement shift).
        val w = 100
        val h = 92
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val bodyCy = h / 2f          // grey body + coloured dot centred → the dot IS the anchor point
        val bodyR = w * 0.32f        // grey teardrop body radius
        val dotR = w * 0.27f         // coloured dot (grey shows as a thin ring + the point below)
        val tipY = h - 4f            // teardrop point near the bottom
        // Teardrop = grey body circle unioned with a triangle down to the point (tangent sides).
        val d = tipY - bodyCy
        val sin = (bodyR / d).coerceAtMost(0.985f)
        val cos = kotlin.math.sqrt(1f - sin * sin)
        val teardrop = Path().apply {
            addCircle(cx, bodyCy, bodyR, Path.Direction.CW)
            op(
                Path().apply {
                    moveTo(cx - bodyR * sin, bodyCy + bodyR * cos)
                    lineTo(cx, tipY)
                    lineTo(cx + bodyR * sin, bodyCy + bodyR * cos)
                    close()
                },
                Path.Op.UNION,
            )
        }
        // Soft drop shadow (teardrop, nudged down a hair, blurred).
        canvas.save()
        canvas.translate(0f, w * 0.02f)
        canvas.drawPath(teardrop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter = BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL)
        })
        canvas.restore()
        // Grey teardrop backing.
        canvas.drawPath(teardrop, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backing() })
        // Category-coloured dot.
        canvas.drawCircle(cx, bodyCy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(colorHex) })
        // White Material glyph centred on the dot.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = Color.WHITE
            textSize = w * 0.32f
            textAlign = Paint.Align.CENTER
        }
        val glyph = String(Character.toChars(codepoint))
        val fm = text.fontMetrics
        canvas.drawText(glyph, cx, bodyCy - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }
}
