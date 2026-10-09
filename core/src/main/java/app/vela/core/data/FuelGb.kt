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
    /** A forecourt whose newest price report is older than this shows nothing. Stations report
     *  only when a price changes, so an old report is often still the price; in the 2026-10-08
     *  file 57% of forecourts had reported within 7 days of its newest report, 87% within 14,
     *  96% within 21. */
    const val MAX_AGE_S = 21L * 86_400L
    /** A file whose newest report is older than this shows no prices at all: the feed stopped. */
    const val FEED_MAX_AGE_S = 2L * 86_400L

    /** Google's language-independent type for a gas station in the search reply ([Place.placeType]). */
    const val GAS_STATION_TYPE = "SearchResult.TYPE_GAS_STATION"
    /** The country Fuel Finder covers ([Place.countryCode]); Northern Ireland is part of it. */
    const val FUEL_FINDER_COUNTRY = "GB"

    // Where a place without a country could be in the Fuel Finder data, which decides whether the
    // file is worth downloading: this box, less the island of Ireland, except Northern Ireland.
    const val UK_SOUTH = 49.8
    const val UK_NORTH = 60.9
    const val UK_WEST = -8.7
    const val UK_EAST = 1.8

    // (lat, lng) pairs. The island polygon runs through the sea between Ireland and Great Britain,
    // clear of Wales, Galloway, Kintyre and Islay; west of the box it does not matter.
    private val IRELAND = doubleArrayOf(
        51.2, -11.0, 51.2, -6.0, 53.6, -5.85, 54.0, -5.4, 55.45, -6.2, 55.6, -7.0, 55.6, -11.0,
    )
    // Coarse Northern Ireland, kept a little inside the border where the two sides are close: every
    // Northern Ireland forecourt in the 2026-10-08 file is inside it, and the Republic's border towns
    // (Lifford, Muff, Omeath, Pettigo, Clones, Dundalk) are outside.
    private val NORTHERN_IRELAND = doubleArrayOf(
        55.35, -6.05, 55.10, -5.70, 54.75, -5.35, 54.00, -5.40, 54.02, -6.05,
        54.096, -6.25, 54.12, -6.31, 54.05, -6.38, 54.04, -6.45, 54.045, -6.67,
        54.17, -6.70, 54.19, -6.75, 54.33, -6.88, 54.43, -7.05, 54.30, -7.12,
        54.235, -7.14, 54.21, -7.24, 54.17, -7.30, 54.13, -7.40, 54.16, -7.52,
        54.17, -7.60, 54.22, -7.68, 54.292, -7.80, 54.294, -7.90, 54.43, -8.16,
        54.485, -8.13, 54.50, -8.04, 54.53, -7.85, 54.66, -7.70, 54.79, -7.556,
        54.832, -7.479, 54.98, -7.42, 55.03, -7.375, 55.062, -7.275, 55.20, -6.97,
    )

    /** Inside the area a Fuel Finder forecourt can be, for a place with no country. */
    fun inUk(p: LatLng): Boolean {
        if (p.lat !in UK_SOUTH..UK_NORTH || p.lng !in UK_WEST..UK_EAST) return false
        return !inPolygon(IRELAND, p) || inPolygon(NORTHERN_IRELAND, p)
    }

    private fun inPolygon(poly: DoubleArray, p: LatLng): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = poly[2 * i]; val xi = poly[2 * i + 1]
            val yj = poly[2 * j]; val xj = poly[2 * j + 1]
            if ((yi > p.lat) != (yj > p.lat) && p.lng < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** A gas station: Google's own type when the place has one (the same in every app language),
     *  else [byCategory], the app's icon rule, for open-data places whose categories are English. */
    fun isGasStation(place: Place, byCategory: (Place) -> Boolean): Boolean =
        place.placeType?.let { it == GAS_STATION_TYPE } ?: byCategory(place)

    /** In the Fuel Finder area: Google's country when the place has one, else [inUk]. */
    fun inFuelFinderArea(place: Place): Boolean =
        place.countryCode?.equals(FUEL_FINDER_COUNTRY, ignoreCase = true) ?: inUk(place.location)

    /** A gas station still without a price, in the Fuel Finder area. */
    fun wants(place: Place, byCategory: (Place) -> Boolean): Boolean =
        place.fuelPrice == null && inFuelFinderArea(place) && isGasStation(place, byCategory)

    /** Whether the file is recent enough to show any price: its newest report within [FEED_MAX_AGE_S]. */
    fun fresh(data: FuelGbStations, nowSec: Long): Boolean =
        data.newestReport > 0 && nowSec - data.newestReport <= FEED_MAX_AGE_S

    /** Whole local days from a report at [atSec] to [nowSec]: 0 today, 1 yesterday. */
    fun daysAgo(atSec: Long, nowSec: Long, zone: java.time.ZoneId): Int {
        val then = java.time.Instant.ofEpochSecond(atSec).atZone(zone).toLocalDate()
        val now = java.time.Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(then, now).toInt().coerceAtLeast(0)
    }

    /**
     * [places] with UK prices filled from the file [load] returns, for a screen with no live state
     * to update (the car). [load] runs (and may download) only when a place needs a price, so a
     * list with no UK gas station never touches the network. The same list when nothing changed.
     */
    suspend fun fill(
        places: List<Place>,
        nowSec: Long,
        byCategory: (Place) -> Boolean,
        load: suspend () -> FuelGbStations?,
    ): List<Place> {
        if (places.none { wants(it, byCategory) }) return places
        val data = load() ?: return places
        return annotateAll(places, data, nowSec, byCategory)
    }

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

    /** [place] with its UK price and report time filled in, or the same instance when there is
     *  nothing to fill (not a UK gas station, no forecourt close enough, its prices too old, or
     *  the whole file stale). [byCategory] is the fallback gas-station test ([isGasStation]). */
    fun annotate(place: Place, data: FuelGbStations, nowSec: Long, byCategory: (Place) -> Boolean): Place {
        if (!fresh(data, nowSec) || !wants(place, byCategory)) return place
        val i = pick(data, place.name, place.location) ?: return place
        val text = label(data, i, nowSec) ?: return place
        return place.copy(fuelPrice = text, fuelPriceAt = data.updated[i])
    }

    /** [places] with every UK gas station's price filled in; the same list when none changed. */
    fun annotateAll(places: List<Place>, data: FuelGbStations, nowSec: Long, byCategory: (Place) -> Boolean): List<Place> {
        if (!fresh(data, nowSec)) return places
        var out: MutableList<Place>? = null
        for (k in places.indices) {
            val p = places[k]
            val q = annotate(p, data, nowSec, byCategory)
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

    /** The newest price report in the file, unix seconds (0 for an empty file). */
    val newestReport: Long = updated.maxOrNull() ?: 0L

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
