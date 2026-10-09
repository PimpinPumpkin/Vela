package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor

/**
 * UK fuel prices (SPEC 5.8). Google's keyless search carries a price for US gas stations
 * ([Place.fuelPrice]) and none for UK ones. The UK government's Fuel Finder publishes every
 * forecourt's prices; `fuel-gb.yml` trims its CSV with `tools/build-fuel-gb.py` and hosts it on
 * the `fuel-gb` release, and [FuelGbStore] downloads it. This file is the pure part: the parse,
 * a grid index and the rule that gives a gas station its price.
 *
 * File format, gzipped CSV with a header: `brand,lat,lng,e10,e5,b7s,b7p,updated`. Prices are in
 * pence, blank when the station sells none; `updated` is the newest price report, unix seconds.
 * Columns are read by name, so a column added later does not break an older app.
 */
object FuelGb {
    /** A gas station takes the nearest Fuel Finder forecourt within this. */
    const val MATCH_M = 75.0
    /** A forecourt whose brand agrees with the place's name wins over a nearer one by up to this. */
    const val BRAND_SLACK_M = 25.0
    /** A forecourt whose newest price is older than this shows nothing. */
    const val MAX_AGE_S = 45L * 86_400L

    // The box that decides whether a place could be in the Fuel Finder data at all, and so
    // whether the file is worth downloading. It also takes in Ireland, where nothing matches.
    const val UK_SOUTH = 49.8
    const val UK_NORTH = 60.9
    const val UK_WEST = -8.7
    const val UK_EAST = 1.8

    fun inUk(p: LatLng): Boolean = p.lat in UK_SOUTH..UK_NORTH && p.lng in UK_WEST..UK_EAST

    /** A gas station still without a price, inside the UK box. [isFuel] is the app's own test. */
    fun wants(place: Place, isFuel: (Place) -> Boolean): Boolean =
        place.fuelPrice == null && inUk(place.location) && isFuel(place)

    fun parseGzip(input: InputStream): FuelGbStations =
        GZIPInputStream(input, 1 shl 16).bufferedReader().use { parse(it) }

    /** Rows with a bad coordinate are skipped. A missing column reads as blank. */
    fun parse(reader: Reader): FuelGbStations {
        val br = reader as? BufferedReader ?: BufferedReader(reader)
        val header = br.readLine()?.split(',')?.map { it.trim().lowercase(Locale.ROOT) } ?: return FuelGbStations.EMPTY
        fun col(name: String) = header.indexOf(name)
        val cBrand = col("brand"); val cLat = col("lat"); val cLng = col("lng")
        val cE10 = col("e10"); val cE5 = col("e5"); val cB7s = col("b7s"); val cB7p = col("b7p")
        val cUpd = col("updated")
        if (cLat < 0 || cLng < 0) return FuelGbStations.EMPTY
        val lat = ArrayList<Double>(9000); val lng = ArrayList<Double>(9000); val brand = ArrayList<String>(9000)
        val e10 = ArrayList<Int>(9000); val e5 = ArrayList<Int>(9000); val b7s = ArrayList<Int>(9000)
        val b7p = ArrayList<Int>(9000); val upd = ArrayList<Long>(9000)
        val interned = HashMap<String, String>()
        while (true) {
            val line = br.readLine() ?: break
            if (line.isBlank()) continue
            val f = line.split(',')
            fun at(i: Int) = if (i in f.indices) f[i].trim() else ""
            val la = at(cLat).toDoubleOrNull() ?: continue
            val lo = at(cLng).toDoubleOrNull() ?: continue
            if (la !in -90.0..90.0 || lo !in -180.0..180.0) continue
            val b = at(cBrand)
            lat += la; lng += lo; brand += interned.getOrPut(b) { b }
            e10 += pence(at(cE10)); e5 += pence(at(cE5)); b7s += pence(at(cB7s)); b7p += pence(at(cB7p))
            upd += at(cUpd).toLongOrNull() ?: 0L
        }
        return FuelGbStations(
            lat.toDoubleArray(), lng.toDoubleArray(), brand.toTypedArray(),
            e10.toIntArray(), e5.toIntArray(), b7s.toIntArray(), b7p.toIntArray(), upd.toLongArray(),
        )
    }

    /** "172.9" -> 17290 (hundredths of a penny); blank or junk -> -1. */
    private fun pence(s: String): Int {
        if (s.isEmpty()) return -1
        val v = s.toDoubleOrNull() ?: return -1
        return if (v > 0) Math.round(v * 100).toInt() else -1
    }

    /** 17290 -> "172.9", 17295 -> "172.95": one decimal as on the pumps, two only when present. */
    fun formatPence(hundredths: Int): String =
        if (hundredths % 10 == 0) String.format(Locale.US, "%.1f", hundredths / 100.0)
        else String.format(Locale.US, "%.2f", hundredths / 100.0)

    /**
     * The forecourt for a gas station named [name] at [at], or null: the nearest within
     * [MATCH_M], unless one whose brand agrees with the name sits no more than [BRAND_SLACK_M]
     * farther. Staleness is not considered here: the nearest forecourt is the station, and a
     * station with old prices shows nothing rather than its neighbor's.
     */
    fun pick(data: FuelGbStations, name: String?, at: LatLng): Int? {
        val near = data.within(at, MATCH_M)
        if (near.isEmpty()) return null
        val (first, d0) = near[0]
        if (name.isNullOrBlank() || brandAgrees(data.brand[first], name)) return first
        return near.firstOrNull { (i, d) -> d - d0 <= BRAND_SLACK_M && brandAgrees(data.brand[i], name) }?.first ?: first
    }

    /**
     * The price text for forecourt [i], or null when its newest report is older than [MAX_AGE_S]
     * or it has no price: "172.9p/E10 · 199.9p/B7". Petrol first (E10, else E5) so the map bubble,
     * which shows the text before the first '/', shows the petrol price. Diesel is standard B7.
     * E10, E5 and B7 are the labels on UK pumps, so the text is the same in every language.
     */
    fun label(data: FuelGbStations, i: Int, nowSec: Long): String? {
        val upd = data.updated[i]
        if (upd <= 0 || nowSec - upd > MAX_AGE_S) return null
        val petrol = when {
            data.e10[i] > 0 -> "${formatPence(data.e10[i])}p/E10"
            data.e5[i] > 0 -> "${formatPence(data.e5[i])}p/E5"
            else -> null
        }
        val diesel = if (data.b7s[i] > 0) "${formatPence(data.b7s[i])}p/B7" else null
        return listOfNotNull(petrol, diesel).joinToString(" · ").ifEmpty { null }
    }

    /** [place] with its UK price filled in, or the same instance when there is nothing to fill. */
    fun annotate(place: Place, data: FuelGbStations, nowSec: Long, isFuel: (Place) -> Boolean): Place {
        if (!wants(place, isFuel)) return place
        val i = pick(data, place.name, place.location) ?: return place
        val text = label(data, i, nowSec) ?: return place
        return place.copy(fuelPrice = text)
    }

    /** [places] with every UK gas station's price filled in; the same list when none changed. */
    fun annotateAll(places: List<Place>, data: FuelGbStations, nowSec: Long, isFuel: (Place) -> Boolean): List<Place> {
        var out: MutableList<Place>? = null
        for (k in places.indices) {
            val p = places[k]
            val q = annotate(p, data, nowSec, isFuel)
            if (q !== p) {
                if (out == null) out = places.toMutableList()
                out[k] = q
            }
        }
        return out ?: places
    }

    // Words in a brand that do not identify it ("Highland Fuels Ltd", "The Co-operative",
    // "Murco T/A ...") and the file's placeholders for no brand.
    private val BRAND_FILLER = setOf(
        "the", "ltd", "limited", "plc", "uk", "co", "op", "and", "of", "on", "t", "a", "ta",
        "petrol", "petroleum", "fuel", "fuels", "oil", "oils", "station", "stations", "service",
        "services", "filling", "forecourt", "garage", "energy", "group", "independent", "independant",
        "unbranded", "none",
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun words(s: String): List<String> =
        s.lowercase(Locale.ROOT).replace("'", "").replace("’", "")
            .split(NON_WORD).filter { it.isNotEmpty() }

    /** Whether the forecourt's brand names the place: a word of the brand that identifies it
     *  ("sainsburys", "esso", "jet") is a word of the place's name. */
    fun brandAgrees(brand: String, name: String): Boolean {
        val b = words(brand).filter { it !in BRAND_FILLER }
        if (b.isEmpty()) return false
        val n = words(name).toSet()
        return b.any { it in n }
    }
}

/** The forecourts of one Fuel Finder file, in flat arrays with a grid index. Prices are
 *  hundredths of a penny, -1 when absent. */
class FuelGbStations(
    val lat: DoubleArray,
    val lng: DoubleArray,
    val brand: Array<String>,
    val e10: IntArray,
    val e5: IntArray,
    val b7s: IntArray,
    val b7p: IntArray,
    val updated: LongArray,
) {
    val size: Int get() = lat.size

    private val grid: HashMap<Long, IntArray> = HashMap<Long, MutableList<Int>>().let { g ->
        for (i in lat.indices) g.getOrPut(key(cell(lat[i]), cell(lng[i]))) { ArrayList(4) }.add(i)
        HashMap<Long, IntArray>(g.size * 2).also { out -> for ((k, v) in g) out[k] = v.toIntArray() }
    }

    /** Forecourts within [meters] of [at], nearest first, with their distances. */
    fun within(at: LatLng, meters: Double): List<Pair<Int, Double>> {
        val dLat = meters / 111_320.0
        val dLng = meters / (111_320.0 * cos(Math.toRadians(at.lat)).coerceAtLeast(1e-6))
        val out = ArrayList<Pair<Int, Double>>()
        for (r in cell(at.lat - dLat)..cell(at.lat + dLat)) {
            for (c in cell(at.lng - dLng)..cell(at.lng + dLng)) {
                val bucket = grid[key(r, c)] ?: continue
                for (i in bucket) {
                    val d = at.distanceTo(LatLng(lat[i], lng[i]))
                    if (d <= meters) out += i to d
                }
            }
        }
        out.sortBy { it.second }
        return out
    }

    companion object {
        private const val CELL = 0.01
        private fun cell(v: Double): Long = floor(v / CELL).toLong()
        private fun key(r: Long, c: Long): Long = (r shl 32) xor (c and 0xffffffffL)

        val EMPTY = FuelGbStations(
            DoubleArray(0), DoubleArray(0), emptyArray(), IntArray(0), IntArray(0), IntArray(0), IntArray(0), LongArray(0),
        )
    }
}
