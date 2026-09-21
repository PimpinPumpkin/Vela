package app.vela.data

import android.content.Context
import app.vela.core.model.StopDeparture
import app.vela.core.model.StopDepartureLine
import app.vela.core.model.StopDepartures
import app.vela.core.model.TransitMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last departure board seen at each stop, on disk, so a stop tapped with no connection still
 * shows which routes serve it, their headsigns and colors, and the last times that were seen
 * (user 2026-09-19: "when I tap a bus stop offline nothing really happens"). The sibling of
 * [TransitStopCache], which keeps the stop ICONS for visited areas; this keeps their boards.
 *
 * Keyed by the stop's coordinate rounded to ~10 m (the tap offline carries the same cached stop
 * point the online tap did), newest 48 kept. The caller shows the board with the time it was
 * fetched so nobody reads yesterday's 8:05 as today's; a live fetch always replaces it.
 */
class TransitBoardCache(private val context: Context) {
    private val lock = Any()
    private val file = File(context.filesDir, "transit_boards_cache.json")
    private var entries: MutableMap<String, Entry>? = null

    class Entry(val lat: Double, val lng: Double, val at: Long, val board: StopDepartures)

    private fun key(lat: Double, lng: Double) = "%.4f,%.4f".format(java.util.Locale.ROOT, lat, lng)

    fun put(lat: Double, lng: Double, board: StopDepartures) {
        if (board.lines.isEmpty()) return
        synchronized(lock) {
            val map = load()
            map[key(lat, lng)] = Entry(lat, lng, System.currentTimeMillis(), board)
            while (map.size > MAX_ENTRIES) {
                val oldest = map.entries.minByOrNull { it.value.at } ?: break
                map.remove(oldest.key)
            }
            save(map)
        }
    }

    /** The cached board at (or within ~40 m of) the point, newest first, else null. */
    fun get(lat: Double, lng: Double): Entry? = synchronized(lock) {
        val map = load()
        map[key(lat, lng)] ?: map.values
            .filter { distanceM(it.lat, it.lng, lat, lng) <= NEAR_M }
            .maxByOrNull { it.at }
    }

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            if (!file.exists()) return@runCatching
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.getDouble("lat"); val lng = o.getDouble("lng")
                map[key(lat, lng)] = Entry(lat, lng, o.getLong("at"), boardFrom(o.getJSONObject("board")))
            }
        }
        entries = map
        return map
    }

    private fun save(map: Map<String, Entry>) {
        runCatching {
            val arr = JSONArray()
            map.values.forEach { e ->
                arr.put(JSONObject().put("lat", e.lat).put("lng", e.lng).put("at", e.at).put("board", boardTo(e.board)))
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    private fun boardTo(b: StopDepartures): JSONObject = JSONObject()
        .putOpt("station", b.stationName)
        .put("lines", JSONArray().apply {
            b.lines.forEach { l ->
                put(JSONObject()
                    .putOpt("label", l.label)
                    .put("mode", l.mode.name)
                    .putOpt("headsign", l.headsign)
                    .putOpt("color", l.colorHex)
                    .putOpt("headway", l.headwayText)
                    .put("upcoming", JSONArray().apply {
                        l.upcoming.forEach { d ->
                            put(JSONObject().putOpt("clock", d.clockText).putOpt("epoch", d.epochSec).put("rt", d.realtime).putOpt("trip", d.tripId))
                        }
                    }))
            }
        })

    private fun boardFrom(o: JSONObject): StopDepartures {
        val lines = ArrayList<StopDepartureLine>()
        val la = o.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until la.length()) {
            val l = la.getJSONObject(i)
            val ups = ArrayList<StopDeparture>()
            val ua = l.optJSONArray("upcoming") ?: JSONArray()
            for (j in 0 until ua.length()) {
                val d = ua.getJSONObject(j)
                ups.add(StopDeparture(
                    clockText = d.optString("clock").takeIf { d.has("clock") },
                    epochSec = if (d.has("epoch")) d.getLong("epoch") else null,
                    realtime = d.optBoolean("rt"),
                    tripId = d.optString("trip").takeIf { d.has("trip") },
                ))
            }
            lines.add(StopDepartureLine(
                label = l.optString("label").takeIf { l.has("label") },
                mode = runCatching { TransitMode.valueOf(l.optString("mode")) }.getOrDefault(TransitMode.GENERIC),
                headsign = l.optString("headsign").takeIf { l.has("headsign") },
                colorHex = l.optString("color").takeIf { l.has("color") },
                headwayText = l.optString("headway").takeIf { l.has("headway") },
                upcoming = ups,
            ))
        }
        return StopDepartures(stationName = o.optString("station").takeIf { o.has("station") }, lines = lines)
    }

    private fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng) * Math.cos(Math.toRadians((aLat + bLat) / 2))
        return Math.sqrt(dLat * dLat + dLng * dLng) * 6_371_000.0
    }

    companion object {
        const val MAX_ENTRIES = 48
        const val NEAR_M = 40.0
    }
}
