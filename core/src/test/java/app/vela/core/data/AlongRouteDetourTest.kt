package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlongRouteDetourTest {
    private fun place(id: String, rating: Double? = null, distance: Double? = null) =
        Place(id = id, name = id, location = LatLng(38.55, -121.7), rating = rating, distanceMeters = distance)

    /**
     * A table for [n] places in the shape the router returns: a row for the car, each first anchor
     * and each place, a column for each place and each second anchor. Only the cells [price] reads
     * are set. [out] is first anchor to place, [back] place to second anchor, [between] first
     * anchor to second anchor, all in seconds.
     */
    private fun table(out: List<Double?>, back: List<Double?>, between: List<Double?>, dist: List<Double> = out.map { 1000.0 }): AlongRouteDetour.Table {
        val n = out.size
        val d = MutableList(2 * n + 1) { MutableList<Double?>(2 * n) { 0.0 } }
        val m = MutableList(2 * n + 1) { MutableList<Double?>(2 * n) { 0.0 } }
        for (i in 0 until n) {
            d[1 + i][i] = out[i]
            d[1 + n + i][n + i] = back[i]
            d[1 + i][n + i] = between[i]
            m[0][i] = dist[i]
        }
        return AlongRouteDetour.Table(d, m)
    }

    // A real answer from the community server for the request shape tableUrl builds, two places
    // near Davis: points 0 car, 1-2 first anchors, 3-4 places, 5-6 second anchors; sources 0-4,
    // destinations 3-6. Trimmed to the two matrices the parser reads.
    private val davisTable = """{"code":"Ok","durations":[[325.1,570.4,436.6,1418.2],[207.5,508.7,375.2,1363.7],[363.3,275,0,1173.6],[0,483.8,350.3,1338.8],[484.2,0,265.6,1209.6]],""" +
        """"distances":[[3220.7,6980.3,5898.3,10538.7],[2183.7,6027.3,4889.3,9562],[3579.5,2025.1,-0.3,6235.2],[0,4672.2,3534.2,8206.9],[4717.4,0,2025.4,5584]]}"""

    @Test
    fun theDetourIsTheExtraTimeThroughThePlaceOverTheTimeBetweenItsAnchors() {
        val t = AlongRouteDetour.parseTable(davisTable)!!
        val out = AlongRouteDetour.price(listOf(place("east"), place("west")), t)!!.associateBy { it.id }
        // first anchor to place + place to second anchor - first anchor to second anchor:
        // 207.5 + 350.3 - 375.2 and 275 + 1209.6 - 1173.6
        assertEquals(182.6, out.getValue("east").detourSeconds!!.toDouble(), 1.0)
        assertEquals(311.0, out.getValue("west").detourSeconds!!.toDouble(), 1.0)
        // The distance is the road distance from the car (the first row), not a straight line.
        assertEquals(3220.7, out.getValue("east").distanceMeters!!, 0.1)
        assertEquals(6980.3, out.getValue("west").distanceMeters!!, 0.1)
    }

    @Test
    fun aPlaceOnTheRouteCostsNothing() {
        val out = AlongRouteDetour.price(listOf(place("on"), place("off")), table(listOf(100.0, 150.0), listOf(100.0, 150.0), listOf(200.0, 200.0)))!!
        assertEquals(listOf("on", "off"), out.map { it.id })
        assertEquals(0, out[0].detourSeconds)
        assertEquals(100, out[1].detourSeconds)
    }

    @Test
    fun roundingNeverMakesTheDetourNegative() {
        val out = AlongRouteDetour.price(listOf(place("a")), table(listOf(100.0), listOf(99.0), listOf(200.0)))!!
        assertEquals(0, out.single().detourSeconds)
    }

    @Test
    fun aPlaceTheRouterCannotReachStaysUnpricedAndGoesLast() {
        val out = AlongRouteDetour.price(
            listOf(place("lost", distance = 1234.0), place("fine", distance = 5000.0)),
            table(listOf(null, 100.0), listOf(100.0, 100.0), listOf(200.0, 300.0)),
        )!!
        assertEquals(listOf("fine", "lost"), out.map { it.id })
        assertNull(out[1].detourSeconds)
        // It keeps the straight-line figure it came with.
        assertEquals(1234.0, out[1].distanceMeters!!, 0.01)
    }

    @Test
    fun noTimeBetweenTheAnchorsLeavesThatPlaceUnpriced() {
        val out = AlongRouteDetour.price(listOf(place("a")), table(listOf(100.0), listOf(100.0), listOf(null)))!!
        assertNull(out.single().detourSeconds)
    }

    @Test
    fun placesWithinTheSameMinuteGoByRatingThenByDistance() {
        val a = place("far-good", rating = 4.6, distance = 9000.0).copy(detourSeconds = 80)
        val b = place("near-poor", rating = 3.1, distance = 2000.0).copy(detourSeconds = 60)
        val c = place("near-good", rating = 4.6, distance = 3000.0).copy(detourSeconds = 70)
        val d = place("slow", rating = 5.0, distance = 1000.0).copy(detourSeconds = 200)
        assertEquals(listOf("near-good", "far-good", "near-poor", "slow"), AlongRouteDetour.rank(listOf(a, b, c, d)).map { it.id })
    }

    @Test
    fun aTableThatDoesNotFitThePlacesIsRefused() {
        val two = table(listOf(100.0, 150.0), listOf(100.0, 150.0), listOf(200.0, 200.0))
        assertNull(AlongRouteDetour.price(listOf(place("only")), two))
    }

    @Test
    fun placesPastTheLimitStayInTheListUnpriced() {
        val n = AlongRouteDetour.MAX_PLACES
        val many = (1..n + 2).map { place("p$it") }
        val out = AlongRouteDetour.price(many, table(List(n) { 100.0 }, List(n) { 100.0 }, List(n) { 150.0 }))!!
        assertEquals(n + 2, out.size)
        assertTrue(out.take(n).all { it.detourSeconds == 50 })
        assertEquals(listOf("p${n + 1}", "p${n + 2}"), out.takeLast(2).map { it.id })
        assertTrue(out.takeLast(2).all { it.detourSeconds == null })
    }

    @Test
    fun theReplyIsReadIncludingUnreachablePairs() {
        val t = AlongRouteDetour.parseTable("""{"code":"Ok","durations":[[0,null],[5.5,0]],"distances":[[0,null],[9,0]]}""")!!
        assertNull(t.durations[0][1])
        assertEquals(5.5, t.durations[1][0]!!, 0.0)
        assertEquals(9.0, t.distances[1][0]!!, 0.0)
    }

    @Test
    fun aReplyThatIsNotOkOrNotJsonIsNull() {
        assertNull(AlongRouteDetour.parseTable("""{"code":"NoTable","message":"x"}"""))
        assertNull(AlongRouteDetour.parseTable("""{"code":"Ok"}"""))
        assertNull(AlongRouteDetour.parseTable("<html>502</html>"))
        assertNotNull(AlongRouteDetour.parseTable(davisTable))
    }

    // North along one meridian, 0.9 degree of latitude: about 100 km.
    private val route = listOf(LatLng(38.0, -121.0), LatLng(38.45, -121.0), LatLng(38.9, -121.0))

    @Test
    fun theAnchorsSitThreeKilometersBeforeAndAfterThePlaceOnTheRoute() {
        // A place 50 km along and a little off the line.
        val mid = place("mid").copy(location = LatLng(38.4, -120.995))
        val (before, after) = AlongRouteDetour.anchors(route, listOf(mid))
        val start = route.first()
        val along = start.distanceTo(LatLng(38.4, -121.0))
        assertEquals(along - AlongRouteDetour.ANCHOR_M, start.distanceTo(before.single()), 150.0)
        assertEquals(along + AlongRouteDetour.ANCHOR_M, start.distanceTo(after.single()), 150.0)
    }

    @Test
    fun theAnchorsAreHeldToTheEndsOfTheRoute() {
        val nearStart = place("a").copy(location = LatLng(38.01, -121.0))
        val nearEnd = place("b").copy(location = LatLng(38.89, -121.0))
        val (before, after) = AlongRouteDetour.anchors(route, listOf(nearStart, nearEnd))
        assertEquals(route.first().lat, before[0].lat, 1e-9)
        assertEquals(route.last().lat, after[1].lat, 1e-9)
        assertEquals(route.last().lng, after[1].lng, 1e-9)
    }

    @Test
    fun theRequestListsTheCarThenTheAnchorsThePlacesAndTheAnchors_longitudeFirst() {
        val car = LatLng(38.5449, -121.7405)
        val one = place("a").copy(location = LatLng(38.553, -121.708))
        val url = AlongRouteDetour.tableUrl(car, listOf(LatLng(38.5455, -121.73)), listOf(one), listOf(LatLng(38.547, -121.7)))
        assertTrue(url, url.startsWith("https://routing.openstreetmap.de/routed-car/table/v1/driving/"))
        assertTrue(url, url.contains("-121.7405,38.5449;-121.73,38.5455;-121.708,38.553;-121.7,38.547?"))
        // One place: sources are points 0-2 (car, first anchor, place), destinations 2-3 (place, second anchor).
        assertTrue(url, url.endsWith("?sources=0;1;2&destinations=2;3&annotations=duration,distance"))
    }

    @Test
    fun theRequestForTwoPlacesHasFivePointsAsSourcesAndFourAsDestinations() {
        val p = place("a")
        val a = LatLng(38.5, -121.7)
        val url = AlongRouteDetour.tableUrl(a, listOf(a, a), listOf(p, p), listOf(a, a))
        assertTrue(url, url.endsWith("?sources=0;1;2;3;4&destinations=3;4;5;6&annotations=duration,distance"))
    }
}
