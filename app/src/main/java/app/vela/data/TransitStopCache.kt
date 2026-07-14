package app.vela.data

import android.content.Context
import app.vela.core.data.transit.Transitous
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk cache of Transitous stop AREAS - the offline floor for the transit-stop map layer.
 *
 * Every ONLINE viewport fetch overwrites its area here, so the places a user actually visits keep
 * fresh, canonical GTFS stops with no extra machinery: the cache is continuously updated as a side
 * effect of normal use (the same property the flock dataset gets from its weekly re-bake - stops are
 * far too numerous globally to bundle, so the visited-area cache is the equivalent). Offline, the map
 * layer reads whatever area covers the view; a never-visited area falls back to the OSM basemap icons.
 *
 * Bounded LRU of [MAX_AREAS] areas in one JSON file (~a few hundred KB at worst); loaded lazily,
 * written on each store. Thread-safe via a plain lock - writes are rare (one per NEW area).
 */
class TransitStopCache(private val context: Context) {
    private val lock = Any()
    private val file = File(context.filesDir, "transit_stops_cache.json")
    private var areas: MutableList<Area>? = null // lazy; oldest first

    data class Area(val south: Double, val west: Double, val north: Double, val east: Double, val stops: List<Transitous.MapStop>, val at: Long)

    /** Cache the stops fetched for a box (replacing any area it substantially overlaps). */
    fun store(south: Double, west: Double, north: Double, east: Double, stops: List<Transitous.MapStop>) = synchronized(lock) {
        val list = loadLocked()
        list.removeAll { a -> overlaps(a, south, west, north, east) }
        list.add(Area(south, west, north, east, stops, System.currentTimeMillis()))
        while (list.size > MAX_AREAS) list.removeAt(0)
        persistLocked(list)
    }

    /** Stops for a cached area covering the CENTER of the requested box, or null when uncovered. */
    fun lookup(south: Double, west: Double, north: Double, east: Double): List<Transitous.MapStop>? = synchronized(lock) {
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        return loadLocked().lastOrNull { a -> cLat in a.south..a.north && cLng in a.west..a.east }?.stops
    }

    private fun overlaps(a: Area, s: Double, w: Double, n: Double, e: Double): Boolean {
        val cLat = (s + n) / 2; val cLng = (w + e) / 2
        return cLat in a.south..a.north && cLng in a.west..a.east
    }

    private fun loadLocked(): MutableList<Area> {
        areas?.let { return it }
        val out = mutableListOf<Area>()
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val stopsArr = o.getJSONArray("stops")
                    val stops = ArrayList<Transitous.MapStop>(stopsArr.length())
                    for (j in 0 until stopsArr.length()) {
                        val st = stopsArr.getJSONObject(j)
                        val sib = st.optJSONArray("sib")
                        stops.add(
                            Transitous.MapStop(
                                name = st.optString("n"),
                                stopId = st.optString("i"),
                                parentId = st.optString("p").takeIf { it.isNotEmpty() },
                                lat = st.optDouble("la"),
                                lon = st.optDouble("lo"),
                                siblingIds = sib?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
                            ),
                        )
                    }
                    out.add(Area(o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e"), stops, o.optLong("t")))
                }
            }
        }
        areas = out
        return out
    }

    private fun persistLocked(list: MutableList<Area>) {
        runCatching {
            val arr = JSONArray()
            for (a in list) {
                val stops = JSONArray()
                for (st in a.stops) {
                    stops.put(
                        JSONObject()
                            .put("n", st.name).put("i", st.stopId).put("p", st.parentId ?: "")
                            .put("la", st.lat).put("lo", st.lon)
                            .put("sib", JSONArray(st.siblingIds)),
                    )
                }
                arr.put(
                    JSONObject()
                        .put("s", a.south).put("w", a.west).put("n", a.north).put("e", a.east)
                        .put("t", a.at).put("stops", stops),
                )
            }
            file.writeText(arr.toString())
        }
    }

    private companion object { const val MAX_AREAS = 24 }
}
