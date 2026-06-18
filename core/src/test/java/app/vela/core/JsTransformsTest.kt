package app.vela.core

import app.vela.core.config.JsSandbox
import app.vela.core.config.PlaceJson
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsTransformsTest {

    @Test fun runsAFunctionAndReturnsResult() {
        assertEquals("HELLO", JsSandbox.run("function shout(s){ return s.toUpperCase(); }", "shout", "hello"))
    }

    @Test fun missingFunctionFallsBackToNull() {
        assertNull(JsSandbox.run("function a(x){ return x; }", "nope", "x"))
    }

    @Test fun parseErrorFallsBackToNull() {
        assertNull(JsSandbox.run("function broken( { not js", "broken", "x"))
    }

    @Test fun sandboxExposesNoJava() {
        // The whole safety argument: initSafeStandardObjects must NOT expose `java`/
        // `Packages`, so fetched code can't reach the host. The reference throws → null.
        val src = "function hack(x){ return '' + java.lang.System.getProperty('user.home'); }"
        assertNull(JsSandbox.run(src, "hack", "x"))
    }

    @Test fun placeJsonRoundTrips() {
        val places = listOf(
            Place(
                id = "0x1:0x2", name = "Temple Coffee", location = LatLng(38.5, -121.7),
                rating = 4.6, reviewCount = 930, address = "239 G St", category = "Coffee shop",
            ),
        )
        val decoded = PlaceJson.decode(PlaceJson.encode(places))!!
        assertEquals(1, decoded.size)
        assertEquals("Temple Coffee", decoded[0].name)
        assertEquals(38.5, decoded[0].location.lat, 1e-9)
        assertEquals(4.6, decoded[0].rating!!, 1e-9)
        assertEquals(930, decoded[0].reviewCount)
        assertEquals("239 G St", decoded[0].address)
    }

    /** The exact transformPlaces contract a hot-fix would use: flat-places JSON in,
     *  flat-places JSON out — proving the round trip the app relies on. */
    @Test fun transformPlacesHookRewritesResults() {
        val src = """
            function transformPlaces(json){
              var ps = JSON.parse(json);
              ps.forEach(function(p){ p.name = '★ ' + p.name; });
              return JSON.stringify(ps);
            }
        """.trimIndent()
        val placesJson = PlaceJson.encode(listOf(Place(id = "a", name = "Midas", location = LatLng(1.0, 2.0))))
        val out = PlaceJson.decode(JsSandbox.run(src, "transformPlaces", placesJson)!!)!!
        assertEquals("★ Midas", out[0].name)
    }
}
