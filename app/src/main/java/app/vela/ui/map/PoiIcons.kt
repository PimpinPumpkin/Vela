package app.vela.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Google-style POI markers: a category-coloured circle with a white Material
 * Icons glyph in the middle, generated at runtime and registered on the style as
 * `vela-poi-<group>` images. The bundled OpenFreeMap style (liberty-roboto.json)
 * references them from its POI layers via an `icon-image` match on `class`.
 * Keyless — the Material Icons font is bundled in assets.
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
     *  "Coffee shop") so an ambient Google POI gets the SAME coloured dot as the equivalent OSM
     *  POI. Keyword match over the same vocabulary as [CLASS_GROUPS]; order matters (more specific
     *  first). The image to use is `vela-poi-<returned group>`. */
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
            any("park", "garden", "trail", "playground", "campground", "nature") -> "park"
            any("parking", "parking garage", "parking lot") -> "parking"
            any("school", "university", "college", "academy", "education", "library", "kindergarten", "preschool") -> "edu"
            any("museum", "theater", "theatre", "gallery", "cinema", "movie", "art ", "cultural", "historical", "aquarium", "zoo") -> "culture"
            any("gym", "fitness", "stadium", "sport", "golf", "bowling", "yoga", "arena", "athletic", "climbing") -> "sport"
            any("station", "transit", "airport", "bus ", "train", "subway", "metro", "light rail", "ferry") -> "transit"
            any("bank", "atm", "credit union", "post office", "police", "fire station", "city hall",
                "courthouse", "church", "mosque", "temple", "synagogue", "place of worship", "cemetery", "government") -> "civic"
            any("store", "shop", "grocery", "supermarket", "market", "mall", "retail", "boutique", "outlet",
                "dealer", "salon", "barber", "hardware", "florist", "laundr", "jewelr", "furniture", "pharmacy",
                "auto parts", "tire", "nail", "spa") -> "shop"
            else -> "default"
        }
    }

    /** Remap OpenFreeMap Liberty's poi_r1/r7/r20 layers to our coloured markers,
     *  and (in light mode) colour the POI label text by category, like Google. */
    fun applyToLiberty(style: Style, dark: Boolean) {
        runCatching {
            val icon = Expression.raw(match("\"vela-poi-default\"") { "\"vela-poi-$it\"" })
            val colorByGroup = GROUPS.associate { it.first to it.third }
            val textColor = Expression.raw(match("\"#5F6368\"") { "\"${colorByGroup[it] ?: "#5F6368"}\"" })
            listOf("poi_r1", "poi_r7", "poi_r20").forEach { id ->
                val layer = style.getLayer(id) as? SymbolLayer ?: return@forEach
                layer.setProperties(
                    PropertyFactory.iconImage(icon),
                    PropertyFactory.iconSize(0.8f),
                )
                // Category-coloured labels (Google-style) in light mode; the dark
                // theme keeps light-grey labels for contrast.
                if (!dark) layer.setProperties(PropertyFactory.textColor(textColor))
                // Only show POIs that have a NAME — the nameless ones can't be opened
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
                layer.setProperties(PropertyFactory.iconImage(icon), PropertyFactory.iconSize(0.8f))
                if (!dark) layer.setProperties(PropertyFactory.textColor(textColor))
                layer.setMinZoom(16f)
                hideNameless(layer)
            }
        }
    }

    /** Restrict a POI symbol layer to features that have a `name`, preserving the
     *  layer's existing (rank) filter by AND-ing the two. */
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

    private fun marker(tf: Typeface, codepoint: Int, colorHex: String): Bitmap {
        val size = 84
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val r = size / 2f - 5f
        canvas.drawCircle(cx, cx, r + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(colorHex) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = Color.WHITE
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val glyph = String(Character.toChars(codepoint))
        val fm = text.fontMetrics
        canvas.drawText(glyph, cx, cx - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }
}
