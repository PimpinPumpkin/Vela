package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * UK fuel prices (SPEC 5.8). `fuel/fuelfinder-sample.csv` is a made-up Fuel Finder file of eight
 * forecourts around central London; `fuel/fuel-gb-sample.csv` is what `tools/build-fuel-gb.py`
 * wrote from it (`--now 1791460000`, then gunzipped). Regenerate both together.
 */
class FuelGbTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_791_460_000L
    private val sampleText = javaClass.getResourceAsStream("/fuel/fuel-gb-sample.csv")!!.bufferedReader().readText()
    private val data = FuelGb.parse(StringReader(sampleText))

    private val esso = LatLng(51.5074, -0.1278)
    private val tesco = LatLng(51.5076, -0.1278) // 22 m north of the Esso
    private fun station(name: String, at: LatLng, category: String? = "Gas station", price: String? = null) =
        Place(id = "p:$name", name = name, location = at, category = category, fuelPrice = price)
    private val isFuel: (Place) -> Boolean = { it.category?.lowercase()?.contains("gas station") == true }

    // ── The trim (tools/build-fuel-gb.py) and the parse ──────────────────────────────────────

    @Test fun trimKeepsOpenPricedForecourtsOnly() {
        // Eight in: permanently closed, no coordinate, and only a 299.9 placeholder are dropped.
        assertEquals(5, data.size)
        assertTrue(sampleText.startsWith("brand,lat,lng,e10,e5,b7s,b7p,updated\n"))
    }

    @Test fun trimBoundsPricesAndCleansBrands() {
        val shell = data.brand.indexOf("Shell Ltd") // "Shell, Ltd" lost its comma
        assertTrue(shell >= 0)
        assertEquals(19590, data.b7s[shell])
        assertEquals(-1, data.e10[shell])
        val none = data.brand.indexOf("none")
        assertEquals(17500, data.e10[none])
        assertEquals(-1, data.b7s[none]) // 80.0 is below the 100 p floor
    }

    @Test fun trimKeepsTheNewestReportAcrossFuels() {
        // The Tesco's E10 was reported on Oct 7 (in BST), its E5 on Oct 8 10:07:46 UTC.
        val i = data.brand.indexOf("TESCO")
        assertEquals(1_791_454_066L, data.updated[i])
    }

    @Test fun parseReadsColumnsByNameAndSkipsBadRows() {
        val csv = """
            updated,lng,lat,brand,extra,e10
            1791454066,-0.1278,51.5074,ESSO,x,172.9
            1791454066,not-a-number,51.5,BP,x,170.9
            1791454066,-0.1278,51.5076,TESCO,x,
        """.trimIndent()
        val d = FuelGb.parse(StringReader(csv))
        assertEquals(2, d.size)
        assertEquals(17290, d.e10[0])
        assertEquals(-1, d.e10[1])
        assertEquals(-1, d.b7s[0]) // a column the file does not have reads as absent
    }

    @Test fun parseGzipMatchesPlain() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(sampleText.toByteArray()) } }.toByteArray()
        val d = FuelGb.parseGzip(gz.inputStream())
        assertEquals(data.size, d.size)
        assertEquals(data.lat.toList(), d.lat.toList())
    }

    // ── Labels ───────────────────────────────────────────────────────────────────────────────

    @Test fun labelPetrolThenDiesel() {
        assertEquals("172.9p/E10 · 199.9p/B7", FuelGb.label(data, data.brand.indexOf("ESSO"), now))
        assertEquals("175.0p/E10", FuelGb.label(data, data.brand.indexOf("none"), now))
        assertEquals("195.9p/B7", FuelGb.label(data, data.brand.indexOf("Shell Ltd"), now))
    }

    @Test fun labelFallsBackToE5WithoutE10() {
        // The BP has E5 only (and premium diesel, which is not shown), but its prices are from
        // August: fine at the start of September, gone in October.
        val bp = data.brand.indexOf("BP")
        assertEquals("181.9p/E5", FuelGb.label(data, bp, 1_786_348_800L + 86_400L * 20))
        assertNull(FuelGb.label(data, bp, now))
    }

    @Test fun formatPenceKeepsOneDecimalLikeThePumps() {
        assertEquals("172.9", FuelGb.formatPence(17290))
        assertEquals("175.0", FuelGb.formatPence(17500))
        assertEquals("172.95", FuelGb.formatPence(17295))
    }

    // ── Matching ─────────────────────────────────────────────────────────────────────────────

    @Test fun nearestWithinRange() {
        assertEquals(data.brand.indexOf("ESSO"), FuelGb.pick(data, "Fuel stop", LatLng(51.50735, -0.1278)))
        assertNull(FuelGb.pick(data, "Fuel stop", LatLng(51.5050, -0.1278))) // ~270 m from both
    }

    @Test fun aCloseBrandMatchBeatsTheNearest() {
        // On the Esso's own point, a place named for Tesco takes the Tesco 22 m away...
        assertEquals(data.brand.indexOf("TESCO"), FuelGb.pick(data, "Tesco Express", esso))
        // ...and an Esso standing on the Tesco's point takes the Esso.
        assertEquals(data.brand.indexOf("ESSO"), FuelGb.pick(data, "Esso", tesco))
    }

    @Test fun aFarBrandMatchLosesToTheNearest() {
        // An Esso and a Tesco 60 m apart, and a Tesco-named place 10 m from the Esso: the Tesco
        // is 50 m farther, past the slack. A shop of one brand on another's forecourt takes the
        // forecourt it stands on.
        val d = FuelGb.parse(StringReader("brand,lat,lng,e10,updated\nESSO,51.50740,-0.12780,172.9,1791454066\nTESCO,51.50794,-0.12780,169.9,1791454066\n"))
        assertEquals(0, FuelGb.pick(d, "Tesco Express", LatLng(51.50731, -0.1278)))
        assertEquals(1, FuelGb.pick(d, "Tesco Express", LatLng(51.50785, -0.1278)))
    }

    @Test fun brandWords() {
        assertTrue(FuelGb.brandAgrees("SAINSBURYS", "Sainsbury's Petrol Station"))
        assertTrue(FuelGb.brandAgrees("EG ON THE MOVE", "EG On The Move Shell"))
        assertTrue(FuelGb.brandAgrees("Phillips 66 - JET", "JET"))
        assertFalse(FuelGb.brandAgrees("ESSO", "Tesco Express"))
        assertFalse(FuelGb.brandAgrees("none", "None Such Garage"))
        assertFalse(FuelGb.brandAgrees("Highland Fuels Ltd", "Shell Fuels"))
    }

    @Test fun annotateFillsOnlyUkGasStationsWithoutAPrice() {
        val p = station("Esso", esso)
        assertEquals("172.9p/E10 · 199.9p/B7", FuelGb.annotate(p, data, now, isFuel).fuelPrice)
        val google = station("Esso", esso, price = "$5.34/Regular")
        assertSame(google, FuelGb.annotate(google, data, now, isFuel))
        val cafe = station("Esso", esso, category = "Cafe")
        assertSame(cafe, FuelGb.annotate(cafe, data, now, isFuel))
        val us = station("Esso", LatLng(38.5449, -121.7405))
        assertSame(us, FuelGb.annotate(us, data, now, isFuel))
    }

    @Test fun annotateAllKeepsTheListWhenNothingChanges() {
        val list = listOf(station("Cafe", esso, category = "Cafe"), station("Far", LatLng(51.40, -0.30)))
        assertSame(list, FuelGb.annotateAll(list, data, now, isFuel))
        val mixed = list + station("Tesco", tesco)
        val out = FuelGb.annotateAll(mixed, data, now, isFuel)
        assertSame(mixed[0], out[0])
        assertEquals("169.9p/E10 · 189.9p/B7", out[2].fuelPrice)
    }

    @Test fun ukBox() {
        assertTrue(FuelGb.inUk(esso))
        assertTrue(FuelGb.inUk(LatLng(60.15, -1.15))) // Shetland
        assertFalse(FuelGb.inUk(LatLng(48.8566, 2.3522)))
        assertFalse(FuelGb.inUk(LatLng(38.5449, -121.7405)))
    }

    // ── Ireland: Fuel Finder covers Northern Ireland, not the Republic ───────────────────────

    @Test fun northernIrelandIsInTheRepublicIsNot() {
        // Belfast, Derry, Strabane (on the border river), Newry, Belleek, Enniskillen.
        listOf(54.597 to -5.930, 55.000 to -7.320, 54.8306 to -7.4768, 54.176 to -6.338,
            54.477 to -8.096, 54.344 to -7.639).forEach { (la, lo) -> assertTrue("$la,$lo", FuelGb.inUk(LatLng(la, lo))) }
        // Dublin, Dundalk, Monaghan, Letterkenny, Wexford, and the border towns facing Strabane,
        // Derry and Warrenpoint (Lifford, Muff, Omeath).
        listOf(53.350 to -6.260, 54.000 to -6.400, 54.249 to -6.968, 54.950 to -7.730, 52.336 to -6.463,
            54.8346 to -7.4824, 55.0689 to -7.2703, 54.0897 to -6.2607).forEach { (la, lo) -> assertFalse("$la,$lo", FuelGb.inUk(LatLng(la, lo))) }
        // Points on Great Britain's coast facing Ireland stay in: Anglesey, Pembrokeshire, Galloway, Kintyre.
        listOf(53.310 to -4.630, 51.880 to -5.270, 54.840 to -5.120, 55.310 to -5.800).forEach { (la, lo) -> assertTrue("$la,$lo", FuelGb.inUk(LatLng(la, lo))) }
    }

    // ── Google's type and country decide; the category is only the fallback ──────────────────

    @Test fun googleTypeAndCountryWinOverCategoryAndPosition() {
        // A German-language category on a Google gas station: the type decides.
        val de = station("Esso", esso, category = "Tankstelle").copy(placeType = FuelGb.GAS_STATION_TYPE, countryCode = "GB")
        assertEquals("172.9p/E10 · 199.9p/B7", FuelGb.annotate(de, data, now, isFuel).fuelPrice)
        // A shop on a forecourt that Google types as a convenience store: no price.
        val shop = station("Esso", esso).copy(placeType = "SearchResult.TYPE_CONVENIENCE_STORE", countryCode = "GB")
        assertSame(shop, FuelGb.annotate(shop, data, now, isFuel))
        // A country outside the feed is never matched, wherever the point sits.
        val ie = station("Esso", esso).copy(placeType = FuelGb.GAS_STATION_TYPE, countryCode = "IE")
        assertSame(ie, FuelGb.annotate(ie, data, now, isFuel))
        // No type (an open-data place): the category rule, and the position, decide.
        assertEquals("172.9p/E10 · 199.9p/B7", FuelGb.annotate(station("Esso", esso), data, now, isFuel).fuelPrice)
    }

    // ── Outdated prices ──────────────────────────────────────────────────────────────────────

    @Test fun aStationOlderThanThreeWeeksShowsNothing() {
        val t = 1_791_454_066L
        val d = FuelGb.parse(StringReader("brand,lat,lng,e10,updated\nESSO,51.50740,-0.12780,172.9,${t - 20 * 86_400}\nBP,51.51000,-0.12000,169.9,$t\n"))
        assertEquals("172.9p/E10", FuelGb.label(d, 0, t))
        assertNull(FuelGb.label(d, 0, t + 2 * 86_400)) // 22 days
    }

    @Test fun aStaleFileShowsNoPricesAtAll() {
        val p = station("Esso", esso)
        // The file's newest report is 2026-10-08 10:07 UTC: a day later prices show...
        assertNotNull(FuelGb.annotate(p, data, data.newestReport + 86_400, isFuel).fuelPrice)
        // ...three days later the feed has stopped, and nothing is filled, the fresh Esso included.
        assertFalse(FuelGb.fresh(data, data.newestReport + 3 * 86_400))
        assertSame(p, FuelGb.annotate(p, data, data.newestReport + 3 * 86_400, isFuel))
        val list = listOf(p)
        assertSame(list, FuelGb.annotateAll(list, data, data.newestReport + 3 * 86_400, isFuel))
    }

    @Test fun annotationCarriesTheReportTime() {
        val filled = FuelGb.annotate(station("Esso", esso), data, now, isFuel)
        assertEquals(data.updated[data.brand.indexOf("ESSO")], filled.fuelPriceAt)
        // Google's US price keeps no time.
        assertNull(station("ARCO", LatLng(38.5449, -121.7405), price = "$5.34/Regular").fuelPriceAt)
    }

    @Test fun daysAgoCountsLocalCalendarDays() {
        val utc = java.time.ZoneId.of("UTC")
        val noon = 1_791_460_800L // 2026-10-08 12:00 UTC
        assertEquals(0, FuelGb.daysAgo(noon - 3_600, noon, utc))
        assertEquals(1, FuelGb.daysAgo(noon - 13 * 3_600, noon, utc)) // 23:00 the day before
        assertEquals(5, FuelGb.daysAgo(noon - 5 * 86_400, noon, utc))
        assertEquals(0, FuelGb.daysAgo(noon + 60, noon, utc)) // a clock a little behind
    }

    // ── The helper the car's screens use ─────────────────────────────────────────────────────

    @Test fun fillLoadsOnlyWhenAUkGasStationNeedsAPrice() = runBlocking {
        var loads = 0
        val load: suspend () -> FuelGbStations? = { loads++; data }
        val us = listOf(station("ARCO", LatLng(38.5449, -121.7405), price = "$5.34/Regular"), station("Cafe", esso, category = "Cafe"))
        assertSame(us, FuelGb.fill(us, now, isFuel, load))
        assertEquals(0, loads)
        val uk = listOf(station("Tesco", tesco))
        assertEquals("169.9p/E10 · 189.9p/B7", FuelGb.fill(uk, now, isFuel, load)[0].fuelPrice)
        assertEquals(1, loads)
        // Nothing loaded (offline, no file yet): the list comes back as it was.
        assertSame(uk, FuelGb.fill(uk, now, isFuel) { null })
    }

    // ── The store ────────────────────────────────────────────────────────────────────────────

    private class FakeRelease(var data: ByteArray) {
        val asked = mutableListOf<String>()
        fun manifest(): String {
            val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            return """{"version":1,"file":"fuel-gb.csv.gz","fileSha256":"$sha","stations":5}"""
        }
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            asked += url.substringAfterLast('/')
            val body = if (url.endsWith(".json")) manifest().toByteArray() else data
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
    }

    private fun gz(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun storeChecksEveryThreeHoursAndDownloadsOnlyAChangedFile() = runBlocking {
        var clock = 1_000_000_000L
        val release = FakeRelease(gz(sampleText))
        val dir = tmp.newFolder("fuelgb")
        val store = FuelGbStore({ dir }, release.client, baseUrl = "https://example.org/fuel-gb/", nowMs = { clock })

        assertTrue(store.ensure())
        assertEquals(5, store.current!!.size)
        assertEquals(listOf("fuel-gb-manifest.json", "fuel-gb.csv.gz"), release.asked)

        clock += FuelGbStore.CHECK_EVERY_MS - 1
        assertFalse(store.ensure())
        assertEquals(2, release.asked.size) // nothing asked inside the window

        clock += 2
        assertFalse(store.ensure())
        assertEquals("fuel-gb-manifest.json", release.asked.last()) // same file: the manifest only
        assertEquals(3, release.asked.size)

        release.data = gz(sampleText.lines().take(3).joinToString("\n"))
        clock += FuelGbStore.CHECK_EVERY_MS
        assertTrue(store.ensure())
        assertEquals(2, store.current!!.size)

        // A new process reads the file from disk without asking.
        val again = FuelGbStore({ dir }, release.client, baseUrl = "https://example.org/fuel-gb/", nowMs = { clock })
        val before = release.asked.size
        assertTrue(again.ensure())
        assertEquals(2, again.current!!.size)
        assertEquals(before, release.asked.size)
        again.release()
        assertNull(again.current)
    }

    @Test fun storeKeepsTheOldFileWhenTheDownloadIsBad() = runBlocking {
        var clock = 1_000_000_000L
        val release = FakeRelease(gz(sampleText))
        val dir = tmp.newFolder("fuelgb2")
        val store = FuelGbStore({ dir }, release.client, baseUrl = "https://example.org/fuel-gb/", nowMs = { clock })
        assertTrue(store.ensure())
        release.data = "not gzip".toByteArray()
        clock += FuelGbStore.CHECK_EVERY_MS
        assertFalse(store.ensure())
        assertNotNull(store.current)
        assertEquals(5, FuelGb.parseGzip(java.io.File(dir, FuelGbStore.DATA_FILE).inputStream()).size)
    }
}
