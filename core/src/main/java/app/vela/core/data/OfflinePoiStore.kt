package app.vela.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny on-device place index (SQLite) populated from OpenStreetMap/[OverpassPois]
 * when a map region is downloaded — the keyless, no-backend source behind **offline
 * search**. Used as a fallback when Google search can't be reached (offline).
 */
@Singleton
class OfflinePoiStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val helper = object : SQLiteOpenHelper(context, "vela_offline_pois.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE poi(id TEXT PRIMARY KEY, name TEXT, lat REAL, lng REAL, category TEXT, " +
                    "address TEXT, phone TEXT, website TEXT, hours TEXT)",
            )
            db.execSQL("CREATE INDEX idx_poi_name ON poi(name COLLATE NOCASE)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS poi"); onCreate(db)
        }
    }

    /** Upsert a batch of POIs (deduped by id). Keeps the detail tags (address/phone/
     *  website/hours) the OSM source carries, so an offline place sheet isn't bare. */
    fun add(pois: List<Place>) {
        if (pois.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (p in pois) {
                db.insertWithOnConflict("poi", null, ContentValues().apply {
                    put("id", p.id); put("name", p.name)
                    put("lat", p.location.lat); put("lng", p.location.lng)
                    put("category", p.category)
                    put("address", p.address)
                    put("phone", p.phone)
                    put("website", p.website)
                    put("hours", p.hours.joinToString("\n").ifBlank { null })
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(): Int = helper.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM poi", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Every POI within [radiusM] of [loc], nearest first: the businesses AT a typed address. A
     *  pack POI often has no `addr:*` of its own (most US chains do not in OSM), so an address
     *  search could find the house point but never the shop standing on it (user 2026-09-19). */
    fun near(loc: LatLng, radiusM: Double, limit: Int = 6): List<Place> {
        val dLat = radiusM / 111_000.0
        val dLng = dLat / Math.cos(Math.toRadians(loc.lat)).coerceAtLeast(0.2)
        val args = arrayOf((loc.lat - dLat).toString(), (loc.lat + dLat).toString(), (loc.lng - dLng).toString(), (loc.lng + dLng).toString())
        val sql = "SELECT id,name,lat,lng,category,address,phone,website,hours FROM poi WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?"
        val rows = ArrayList<Place>()
        fun query(db: android.database.sqlite.SQLiteDatabase) {
            runCatching {
                db.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        val at = LatLng(c.getDouble(2), c.getDouble(3))
                        val d = loc.distanceTo(at)
                        if (d > radiusM) continue
                        rows.add(Place(
                            id = c.getString(0), name = c.getString(1), location = at, category = c.getString(4),
                            address = c.getString(5), phone = c.getString(6), website = c.getString(7),
                            hours = app.vela.core.util.OsmHours.lines(c.getString(8)), // packs keep OSM's raw tag
                            distanceMeters = d,
                        ))
                    }
                }
            }
        }
        query(helper.readableDatabase)
        OfflinePacks.dbs.forEach(::query)
        return rows.distinctBy { it.id }.sortedBy { it.distanceMeters }.take(limit)
    }

    /** Name/category match, nearest first. Robust to a few things a plain LIKE misses:
     *  - Category words ("gas", "coffee", "food", the map's chips) are expanded to the OSM tag values
     *    we actually store — a gas station is category "Fuel" (from `amenity=fuel`), not "gas".
     *  - Multi-word queries ("mexican restaurant", "coffee shop") match the whole phrase OR any single
     *    word, so "mexican restaurant" finds "Ixtapa Mexican Restaurant" and the restaurant category,
     *    instead of returning nothing because no name contains the exact phrase.
     */
    fun search(query: String, near: LatLng?, limit: Int = 30): List<Place> {
        val term = query.trim()
        // name/category LIKE targets: the whole query, plus each word ≥3 chars (multi-word only).
        val nameCat = LinkedHashSet<String>().apply { add(term) }
        val words = term.split(Regex("\\s+")).filter { it.length >= 3 }
        if (words.size > 1) nameCat.addAll(words)
        // category-tag targets: keywords for the whole query and for each word.
        val cats = LinkedHashSet<String>().apply { addAll(categoryKeywords(term)); words.forEach { addAll(categoryKeywords(it)) } }

        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        for (t in nameCat) { clauses.add("name LIKE ?"); args.add("%$t%"); clauses.add("category LIKE ?"); args.add("%$t%") }
        for (c in cats) { clauses.add("category LIKE ?"); args.add("%$c%") }
        // Whole-query address match, so typing a downloaded POI's street address finds it offline (the
        // general typed-address geocoder is OfflineAddressStore).
        clauses.add("address LIKE ?"); args.add("%$term%")
        // Whole-query NAME matches must survive the LIMIT, not just win the post-sort: a state pack has
        // thousands of category hits ("cafe"), and taking the first 400 in table order dropped an exact
        // name match that lived past them (found while verifying delta updates). The ORDER BY puts
        // phrase-in-name rows first, THEN the cap applies. Its LIKE arg is the last one bound.
        args.add("%$term%")
        val sql = "SELECT id,name,lat,lng,category,address,phone,website,hours FROM poi " +
            "WHERE ${clauses.joinToString(" OR ")} ORDER BY (name LIKE ?) DESC LIMIT 400"
        val rows = ArrayList<Place>()
        fun query(db: android.database.sqlite.SQLiteDatabase) {
            runCatching {
                db.rawQuery(sql, args.toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        val loc = LatLng(c.getDouble(2), c.getDouble(3))
                        rows.add(
                            Place(
                                id = c.getString(0),
                                name = c.getString(1),
                                location = loc,
                                category = c.getString(4),
                                address = c.getString(5),
                                phone = c.getString(6),
                                website = c.getString(7),
                                hours = app.vela.core.util.OsmHours.lines(c.getString(8)), // packs keep OSM's raw tag
                                distanceMeters = near?.distanceTo(loc),
                            ),
                        )
                    }
                }
            }
        }
        // The viewport-download index, then every installed region pack (a state's whole POI set) —
        // same schema, same SQL. Dedupe by id (a POI can be in both once its area was also saved).
        query(helper.readableDatabase)
        OfflinePacks.dbs.forEach(::query)
        // Rank by how many query words hit the name/category (so "mexican restaurant" leads with the
        // Mexican restaurant, not a random one), then by distance.
        val qWords = (if (words.size > 1) words else listOf(term)).map { it.lowercase() }
        // TRANSIT STOPS GO LAST unless the query asks for transit. US stops are named by their
        // corner ("172nd St NE & Smokey Point Blvd"), so any query carrying a street or a town
        // word matched hundreds of them and a business search offline read as a list of
        // intersections (user 2026-09-21, a parts store the pack did not have). They still show,
        // after everything else.
        val transitQuery = TRANSIT_QUERY_WORDS.any { term.lowercase().contains(it) }
        return rows.distinctBy { it.id }.sortedWith(
            compareBy<Place> { p -> if (!transitQuery && (p.category ?: "").lowercase() in TRANSIT_STOP_CATS) 1 else 0 }
                .thenByDescending { p ->
                    val hay = (p.name + " " + (p.category ?: "") + " " + (p.address ?: "")).lowercase()
                    qWords.count { hay.contains(it) }
                }.thenBy { it.distanceMeters ?: Double.MAX_VALUE },
        ).take(limit)
    }

    companion object {
        // Map a search word (or the map's category chip) to the OSM tag values we store as `category`
        // (amenity/shop/leisure/…, space-separated + capitalized, e.g. "Fuel", "Fast food"). Matched
        // case-insensitively via LIKE. Keep the values in the OSM form, not the display word.
        private val CATEGORY_KEYWORDS: Map<String, List<String>> = mapOf(
            "gas" to listOf("fuel", "charging station"),
            "gas station" to listOf("fuel"),
            "fuel" to listOf("fuel"),
            "petrol" to listOf("fuel"),
            "petrol station" to listOf("fuel"),
            "charging" to listOf("charging station"),
            "ev charging" to listOf("charging station"),
            "coffee" to listOf("cafe", "coffee"),
            "cafe" to listOf("cafe"),
            "food" to listOf("restaurant", "fast food"),
            "restaurant" to listOf("restaurant", "fast food"),
            "restaurants" to listOf("restaurant", "fast food"),
            "fast food" to listOf("fast food"),
            "groceries" to listOf("supermarket", "convenience", "greengrocer"),
            "grocery" to listOf("supermarket", "convenience"),
            "supermarket" to listOf("supermarket"),
            "store" to listOf("supermarket", "convenience", "department store"),
            "pharmacy" to listOf("pharmacy", "chemist"),
            "drug store" to listOf("pharmacy", "chemist"),
            "hotel" to listOf("hotel", "motel", "guest house"),
            "hotels" to listOf("hotel", "motel", "guest house"),
            "motel" to listOf("motel"),
            "lodging" to listOf("hotel", "motel", "guest house", "hostel"),
            "parking" to listOf("parking"),
            "parking lot" to listOf("parking"),
            "atm" to listOf("atm", "bank"),
            "atms" to listOf("atm", "bank"),
            "bank" to listOf("bank", "atm"),
            "banks" to listOf("bank", "atm"),
            "hospital" to listOf("hospital"),
            "hospitals" to listOf("hospital"),
            "clinic" to listOf("clinic", "doctors"),
            "doctor" to listOf("doctors", "clinic"),
            "urgent care" to listOf("clinic", "hospital"),
            "bar" to listOf("bar", "pub", "biergarten"),
            "bars" to listOf("bar", "pub", "biergarten"),
            "pub" to listOf("pub", "bar"),
            "bakery" to listOf("bakery"),
            "park" to listOf("park"),
            "parks" to listOf("park"),
            "school" to listOf("school"),
            "gym" to listOf("fitness center", "fitness centre", "sports center", "sports centre"),
            "car wash" to listOf("car wash"),
            "post office" to listOf("post office"),
            "post offices" to listOf("post office"),
            // tourism=camp_site / caravan_site ("Camp site", "Caravan site" once formatted).
            "campground" to listOf("camp site", "caravan site"),
            "campgrounds" to listOf("camp site", "caravan site"),
            "camping" to listOf("camp site", "caravan site"),
            // The "Things to do" chip. Mostly tourism=* values plus a few amenity/leisure ones that
            // win the category slot first (amenity is read before tourism).
            "things to do" to listOf(
                "attraction", "museum", "viewpoint", "theme park", "zoo", "aquarium", "gallery",
                "theatre", "cinema", "arts center", "arts centre", "water park",
            ),
            "hardware" to listOf("hardware", "doityourself"),
        )

        /** Exact key first, then the word minus a trailing "s", so typed plurals ("cafes", "gyms")
         *  reach the singular entry. Irregular plurals need their own key ("groceries"). */
        /** Pack categories that are transit stops (public_transport=* and amenity=bus_station). */
        internal val TRANSIT_STOP_CATS = setOf("platform", "stop position", "stop area", "station", "bus station", "bus stop")
        internal val TRANSIT_QUERY_WORDS = listOf("bus", "stop", "station", "transit", "train", "tram", "platform", "metro", "light rail", "subway", "ferry")

        internal fun categoryKeywords(query: String): List<String> {
            val key = query.trim().lowercase()
            return CATEGORY_KEYWORDS[key]
                ?: key.takeIf { it.length > 3 && it.endsWith("s") }?.let { CATEGORY_KEYWORDS[it.dropLast(1)] }
                ?: emptyList()
        }
    }
}
