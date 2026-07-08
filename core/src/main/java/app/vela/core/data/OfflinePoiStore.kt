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

    /** Name/category match, nearest first. Common category words ("gas", "coffee", "food", the map's
     *  own category chips) are expanded to the OSM tag values we actually store — a gas station is
     *  category "Fuel" (from `amenity=fuel`), not "gas", so a plain LIKE would miss it. */
    fun search(query: String, near: LatLng?, limit: Int = 30): List<Place> {
        val term = query.trim()
        val like = "%$term%"
        // name/category direct match, plus a category LIKE per expanded OSM keyword.
        val where = StringBuilder("name LIKE ? OR category LIKE ?")
        val args = ArrayList<String>().apply { add(like); add(like) }
        for (c in categoryKeywords(term)) {
            where.append(" OR category LIKE ?")
            args.add("%$c%")
        }
        val rows = ArrayList<Place>()
        helper.readableDatabase.rawQuery(
            "SELECT id,name,lat,lng,category,address,phone,website,hours FROM poi WHERE $where LIMIT 400",
            args.toTypedArray(),
        ).use { c ->
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
                        hours = c.getString(8)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                        distanceMeters = near?.distanceTo(loc),
                    ),
                )
            }
        }
        return rows.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }.take(limit)
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
            "motel" to listOf("motel"),
            "lodging" to listOf("hotel", "motel", "guest house", "hostel"),
            "parking" to listOf("parking"),
            "atm" to listOf("atm", "bank"),
            "bank" to listOf("bank", "atm"),
            "hospital" to listOf("hospital"),
            "clinic" to listOf("clinic", "doctors"),
            "doctor" to listOf("doctors", "clinic"),
            "urgent care" to listOf("clinic", "hospital"),
            "bar" to listOf("bar", "pub", "biergarten"),
            "pub" to listOf("pub", "bar"),
            "bakery" to listOf("bakery"),
            "park" to listOf("park"),
            "school" to listOf("school"),
            "gym" to listOf("fitness centre", "sports centre"),
            "car wash" to listOf("car wash"),
            "post office" to listOf("post office"),
            "hardware" to listOf("hardware", "doityourself"),
        )

        internal fun categoryKeywords(query: String): List<String> =
            CATEGORY_KEYWORDS[query.trim().lowercase()] ?: emptyList()
    }
}
