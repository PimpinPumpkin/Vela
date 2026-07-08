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
 * On-device street-address index (SQLite), populated from OSM `addr:housenumber` points when a map
 * region is downloaded ([OverpassPois.fetchAddresses]). This is the offline **forward geocoder**: it
 * turns a typed address like "1451 W Covell Blvd" into a coordinate with no network, so you can route to
 * an arbitrary address offline. Separate from [OfflinePoiStore] (that's named businesses); this is every
 * addressed building OSM has in the downloaded area.
 *
 * Robustness comes from a normalized street form: both the stored street and the query are lowercased
 * and their abbreviations expanded ("Pl" → "place", "SE" → "southeast", "Ave" → "avenue", …), then
 * matched word-by-word. So "W Covell Blvd", "West Covell Blvd" and "West Covell Boulevard" all hit the same
 * row. If the exact house number isn't mapped, it falls back to the street (nearest point on it).
 */
@Singleton
class OfflineAddressStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Addr(
        val id: String,
        val housenumber: String?,
        val street: String?,
        val city: String?,
        val lat: Double,
        val lng: Double,
    )

    /** One sampled point on a named road centreline (from [OverpassPois.fetchStreets]) — the data behind
     *  the street-level geocoding fallback where OSM has the road but no house numbers on it. */
    data class StreetPt(val street: String, val lat: Double, val lng: Double)

    private val helper = object : SQLiteOpenHelper(context, "vela_offline_addr.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE addr(id TEXT PRIMARY KEY, housenumber TEXT, street TEXT, " +
                    "street_norm TEXT, city TEXT, lat REAL, lng REAL)",
            )
            db.execSQL("CREATE INDEX idx_addr_street ON addr(street_norm)")
            db.execSQL("CREATE INDEX idx_addr_hn ON addr(housenumber)")
            db.execSQL(
                "CREATE TABLE street(id TEXT PRIMARY KEY, street TEXT, street_norm TEXT, lat REAL, lng REAL)",
            )
            db.execSQL("CREATE INDEX idx_street_norm ON street(street_norm)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS addr")
            db.execSQL("DROP TABLE IF EXISTS street")
            onCreate(db)
        }
    }

    fun add(addrs: List<Addr>) {
        if (addrs.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (a in addrs) {
                db.insertWithOnConflict("addr", null, ContentValues().apply {
                    put("id", a.id)
                    put("housenumber", a.housenumber)
                    put("street", a.street)
                    put("street_norm", a.street?.let { normalizeStreet(it) })
                    put("city", a.city)
                    put("lat", a.lat); put("lng", a.lng)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addStreets(streets: List<StreetPt>) {
        if (streets.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (p in streets) {
                db.insertWithOnConflict("street", null, ContentValues().apply {
                    // id keys on street+rounded point so re-downloading an area doesn't pile up duplicates
                    put("id", "${normalizeStreet(p.street)}@${"%.5f".format(p.lat)},${"%.5f".format(p.lng)}")
                    put("street", p.street)
                    put("street_norm", normalizeStreet(p.street))
                    put("lat", p.lat); put("lng", p.lng)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(): Int = helper.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM addr", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun streetCount(): Int = helper.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM street", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Forward-geocode a typed address → matching Places (nearest first). Empty if nothing matches.
     * Layered so an arbitrary address in a downloaded area resolves even where OSM is thin:
     *  1. exact `housenumber` on the street,
     *  2. interpolate the house's position between the two nearest mapped numbers on the street,
     *  3. any mapped house on the street (routes you to the right block),
     *  4. nearest point on the street's centreline geometry (works with zero mapped houses).
     */
    fun geocode(query: String, near: LatLng?, limit: Int = 20): List<Place> {
        val m = HOUSE_STREET.find(query.trim())
        val houseNo = m?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val streetPart = (m?.groupValues?.get(2) ?: query).trim()
        val words = normalizeStreet(streetPart).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()

        // (1) exact house number on the street.
        if (houseNo != null) {
            val exact = run(houseNo, words, near, limit)
            if (exact.isNotEmpty()) return exact
        }
        // Everything mapped on this street (ignoring house number), for interpolation + block fallback.
        val onStreet = query(null, words)
        if (onStreet.isNotEmpty()) {
            // (2) interpolate between bracketing house numbers.
            if (houseNo != null) {
                interpolate(houseNo, onStreet)?.let { loc ->
                    return listOf(placeAt(loc, "$houseNo $streetPart", onStreet.firstOrNull()?.city, near))
                }
            }
            // (3) nearest mapped house on the street.
            return onStreet.map { placeAt(LatLng(it.lat, it.lng), addrLabel(it.hn, it.street), it.city, near) }
                .distinctBy { it.name }
                .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                .take(limit)
        }
        // (4) no mapped houses on the street → nearest point on the road centreline.
        val onGeom = streetGeom(words)
        if (onGeom.isNotEmpty()) {
            val best = onGeom.minByOrNull { near?.distanceTo(LatLng(it.lat, it.lng)) ?: 0.0 } ?: onGeom.first()
            val loc = LatLng(best.lat, best.lng)
            val label = if (houseNo != null) "$houseNo $streetPart" else best.street
            return listOf(placeAt(loc, label, null, near))
        }
        return emptyList()
    }

    private data class AddrRow(val hn: String?, val street: String?, val city: String?, val lat: Double, val lng: Double)

    /** All addr rows matching the street words (no house-number filter). */
    private fun query(houseNo: String?, words: List<String>): List<AddrRow> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (houseNo != null) { clauses.add("housenumber = ?"); args.add(houseNo) }
        for (w in words) { clauses.add("street_norm LIKE ?"); args.add("%$w%") }
        val rows = ArrayList<AddrRow>()
        helper.readableDatabase.rawQuery(
            "SELECT housenumber,street,city,lat,lng FROM addr WHERE ${clauses.joinToString(" AND ")} LIMIT 400",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(AddrRow(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4)))
            }
        }
        return rows
    }

    private fun run(houseNo: String?, words: List<String>, near: LatLng?, limit: Int): List<Place> =
        query(houseNo, words)
            .map { placeAt(LatLng(it.lat, it.lng), addrLabel(it.hn, it.street), it.city, near) }
            .distinctBy { it.name }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .take(limit)

    /** Street centreline points matching the street words. */
    private fun streetGeom(words: List<String>): List<StreetPt> {
        val clauses = words.map { "street_norm LIKE ?" }
        val args = words.map { "%$it%" }
        val rows = ArrayList<StreetPt>()
        helper.readableDatabase.rawQuery(
            "SELECT street,lat,lng FROM street WHERE ${clauses.joinToString(" AND ")} LIMIT 4000",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) rows.add(StreetPt(c.getString(0), c.getDouble(1), c.getDouble(2)))
        }
        return rows
    }

    /** Lerp the target house's position between the two nearest bracketing mapped numbers on the street. */
    private fun interpolate(houseNo: String, onStreet: List<AddrRow>): LatLng? {
        val target = houseNo.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val numbered = onStreet.mapNotNull { r -> r.hn?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { it to r } }
        if (numbered.size < 2) return null
        val below = numbered.filter { it.first <= target }.maxByOrNull { it.first }
        val above = numbered.filter { it.first >= target }.minByOrNull { it.first }
        return when {
            below != null && above != null && above.first != below.first -> {
                val t = (target - below.first).toDouble() / (above.first - below.first)
                LatLng(
                    below.second.lat + t * (above.second.lat - below.second.lat),
                    below.second.lng + t * (above.second.lng - below.second.lng),
                )
            }
            below != null -> LatLng(below.second.lat, below.second.lng)
            above != null -> LatLng(above.second.lat, above.second.lng)
            else -> null
        }
    }

    private fun addrLabel(hn: String?, street: String?): String =
        listOfNotNull(hn, street).joinToString(" ").ifBlank { street ?: "Address" }

    private fun placeAt(loc: LatLng, name: String, city: String?, near: LatLng?): Place =
        Place(
            id = "addr:${loc.lat},${loc.lng}",
            name = name,
            location = loc,
            category = "Address",
            address = listOfNotNull(name, city).joinToString(", ").ifBlank { null },
            distanceMeters = near?.distanceTo(loc),
        )

    companion object {
        // A query is address-like if it starts with a house number or names a street type. Used to decide
        // whether to run the geocoder alongside the POI search (so "coffee" doesn't hit the address table).
        fun looksLikeAddress(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return false
            if (q.first().isDigit()) return true
            return STREET_TYPE_WORDS.any { Regex("(^|\\s)$it(\\s|$)").containsMatchIn(q) }
        }

        private val HOUSE_STREET = Regex("^\\s*(\\d+[a-zA-Z]?)\\s+(.+)$")

        private val ABBREV = mapOf(
            "st" to "street", "str" to "street", "ave" to "avenue", "av" to "avenue",
            "blvd" to "boulevard", "boul" to "boulevard", "dr" to "drive", "rd" to "road",
            "ln" to "lane", "ct" to "court", "pl" to "place", "sq" to "square", "ter" to "terrace",
            "cir" to "circle", "hwy" to "highway", "pkwy" to "parkway", "pky" to "parkway",
            "trl" to "trail", "way" to "way", "loop" to "loop",
            "n" to "north", "s" to "south", "e" to "east", "w" to "west",
            "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
        )

        private val STREET_TYPE_WORDS = setOf(
            "street", "st", "avenue", "ave", "av", "boulevard", "blvd", "drive", "dr", "road", "rd",
            "lane", "ln", "court", "ct", "place", "pl", "square", "sq", "terrace", "ter", "circle", "cir",
            "highway", "hwy", "parkway", "pkwy", "trail", "trl", "way", "loop", "route",
        )

        /** Lowercase, strip punctuation, expand each abbreviation to its full form → space-joined words.
         *  Applied identically to the stored street and the query so they line up regardless of how the
         *  user abbreviated it. */
        fun normalizeStreet(s: String): String =
            s.lowercase()
                .replace(Regex("[.,#]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { ABBREV[it] ?: it }
    }
}
