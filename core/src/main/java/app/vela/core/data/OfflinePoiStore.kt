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

    /** Name/category match, nearest first. */
    fun search(query: String, near: LatLng?, limit: Int = 30): List<Place> {
        val q = "%${query.trim()}%"
        val rows = ArrayList<Place>()
        helper.readableDatabase.rawQuery(
            "SELECT id,name,lat,lng,category,address,phone,website,hours FROM poi WHERE name LIKE ? OR category LIKE ? LIMIT 400",
            arrayOf(q, q),
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
}
