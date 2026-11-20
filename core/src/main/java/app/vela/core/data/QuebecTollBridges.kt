package app.vela.core.data

import app.vela.core.model.LatLng

/**
 * The tolled bridges of Quebec's two toll crossings, one line per carriageway piece, from
 * OpenStreetMap ways tagged `toll=yes` and `bridge=yes` on the A-25 and the A-30 (ODbL, queried
 * 2026-10-31). The bridge only, not the approaches the tag also covers, so a route that joins the
 * motorway past the river does not pay. Each line is "lat,lng lat,lng ...".
 */
internal object QuebecTollBridges {
    /** OSM ways: 958824640, 958824641, 1175031631, 1175031632, 1175031633, 1175031634, 1359910778, 1360701772. */
    val A25: List<List<LatLng>> = listOf(
        line("45.63638,-73.61896 45.63414,-73.61657"),
        line("45.63273,-73.61487 45.63444,-73.61668 45.63645,-73.61882 45.63705,-73.61946"),
        line("45.63414,-73.61657 45.63338,-73.61577 45.63204,-73.61434"),
        line("45.63705,-73.61946 45.63916,-73.62170"),
        line("45.63916,-73.62170 45.64033,-73.62295 45.64073,-73.62337"),
        line("45.63975,-73.62254 45.63695,-73.61957 45.63638,-73.61896"),
        line("45.64066,-73.62351 45.63975,-73.62254"),
        line("45.63211,-73.61421 45.63273,-73.61487"),
    )

    /** OSM ways: 171814124, 194473615, 299944347. */
    val A30: List<List<LatLng>> = listOf(
        line("45.29970,-74.00941 45.31280,-74.01913"),
        line("45.31447,-74.02056 45.29965,-74.00957"),
        line("45.31280,-74.01913 45.31453,-74.02041"),
    )

    private fun line(s: String): List<LatLng> = s.split(' ').map { p -> p.split(',').let { LatLng(it[0].toDouble(), it[1].toDouble()) } }
}
