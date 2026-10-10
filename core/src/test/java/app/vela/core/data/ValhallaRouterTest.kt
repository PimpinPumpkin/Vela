package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ValhallaRouter] against a captured FOSSGIS Valhalla reply (Davis fixture, 2026-09-14): a
 *  two-leg bicycle trip through one stop, with a roundabout in each leg. */
class ValhallaRouterTest {
    private val routes = ValhallaRouter.parse(CAPTURE)

    @Test
    fun `parses one continuous route from two legs`() {
        assertEquals(1, routes.size)
        val r = routes[0]
        assertEquals(7588.0, r.distanceMeters, 10.0)
        assertEquals(1607.1, r.durationSeconds, 5.0)
        assertTrue(r.polyline.size > 500)
        assertEquals(ManeuverType.DEPART, r.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, r.maneuvers.last().type)
        // The stop's arrive + depart are folded away: exactly one of each.
        assertEquals(1, r.maneuvers.count { it.type == ManeuverType.DEPART })
        assertEquals(1, r.maneuvers.count { it.type == ManeuverType.ARRIVE })
        assertEquals("right", r.maneuvers.last().side)
    }

    @Test
    fun `step lengths tile the whole trip`() {
        val r = routes[0]
        assertEquals(r.distanceMeters, r.maneuvers.sumOf { it.distanceMeters }, 0.01)
    }

    @Test
    fun `roundabouts keep their exit number and phrase`() {
        val r = routes[0]
        val rbs = r.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }
        assertEquals(2, rbs.size)
        assertEquals(listOf(1, 2), rbs.map { it.roundaboutExit })
        assertTrue(rbs[1].instruction, rbs[1].instruction.contains("2nd"))
        assertEquals(2, r.maneuvers.count { it.type == ManeuverType.EXIT_ROUNDABOUT })
    }

    @Test
    fun `turns are phrased with the street name`() {
        val r = routes[0]
        val right = r.maneuvers.first { it.type == ManeuverType.TURN_RIGHT }
        assertTrue(right.instruction, right.instruction.startsWith("Turn right onto "))
        assertEquals("3rd Street", right.road)
    }

    @Test
    fun `a bend that keeps the road name is a silent rename`() {
        assertEquals("new name" to "straight", ValhallaRouter.osrmGrammar(16, "3rd Street", sameRoad = true))
        assertEquals("turn" to "slight left", ValhallaRouter.osrmGrammar(16, "Elm Street", sameRoad = false))
        assertEquals("roundabout" to null, ValhallaRouter.osrmGrammar(26, null, false))
        assertEquals("arrive" to "left", ValhallaRouter.osrmGrammar(6, null, false))
    }

    // East 200 m on A Road, right (south) 90 m still on A Road, right (west) onto B Road.
    private fun cornerTrip(secondType: Int): String {
        val pts = listOf(LatLng(38.5450, -121.7430), LatLng(38.5450, -121.7407), LatLng(38.5442, -121.7407), LatLng(38.5442, -121.7430))
        val shape = app.vela.core.data.google.PolylineCodec.encode(pts, 6).replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"trip":{"summary":{"length":0.49,"time":60.0},"legs":[{"shape":"$shape","maneuvers":[
            {"type":1,"instruction":"Drive east on A Road.","street_names":["A Road"],"length":0.2,"time":20.0,"begin_shape_index":0,"end_shape_index":1},
            {"type":$secondType,"instruction":"Turn right to stay on A Road.","street_names":["A Road"],"length":0.09,"time":12.0,"begin_shape_index":1,"end_shape_index":2},
            {"type":10,"instruction":"Turn right onto B Road.","street_names":["B Road"],"length":0.2,"time":28.0,"begin_shape_index":2,"end_shape_index":3},
            {"type":4,"instruction":"You have arrived.","length":0.0,"time":0.0,"begin_shape_index":3,"end_shape_index":3}]}]}}"""
    }

    @Test
    fun `a turn that keeps the road drops the name only when the driver was told it`() {
        // The whole trip: "Head out on A Road" was said, so the corner on A Road is a bare turn.
        val whole = ValhallaRouter.parse(cornerTrip(10), null, departSaid = true).single().maneuvers
        assertEquals("Turn right", whole[1].instruction)
        assertEquals("A Road", whole[1].road)
        assertEquals("Turn right onto B Road", whole[2].instruction)
        // A piece cut from mid-trip: its own first step is never said, so nobody has named A Road.
        val piece = ValhallaRouter.parse(cornerTrip(10), null, departSaid = false).single().maneuvers
        assertEquals("Turn right onto A Road", piece[1].instruction)
        assertEquals("Turn right onto B Road", piece[2].instruction)
    }

    // East on A Road, then B Road joined by a merge whose line says only its sign, then a right
    // that stays on B Road.
    private fun mergeTrip(): String {
        val pts = listOf(LatLng(38.5450, -121.7430), LatLng(38.5450, -121.7407), LatLng(38.5442, -121.7407), LatLng(38.5442, -121.7430))
        val shape = app.vela.core.data.google.PolylineCodec.encode(pts, 6).replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"trip":{"summary":{"length":0.49,"time":60.0},"legs":[{"shape":"$shape","maneuvers":[
            {"type":1,"instruction":"Drive east on A Road.","street_names":["A Road"],"length":0.2,"time":20.0,"begin_shape_index":0,"end_shape_index":1},
            {"type":25,"instruction":"Merge onto B Road.","street_names":["B Road"],"sign":{"exit_toward_elements":[{"text":"Sacramento"}]},"length":0.09,"time":12.0,"begin_shape_index":1,"end_shape_index":2},
            {"type":10,"instruction":"Turn right to stay on B Road.","street_names":["B Road"],"length":0.2,"time":28.0,"begin_shape_index":2,"end_shape_index":3},
            {"type":4,"instruction":"You have arrived.","length":0.0,"time":0.0,"begin_shape_index":3,"end_shape_index":3}]}]}}"""
    }

    @Test
    fun `a merge that says only its sign has not told the road, so the turn after it names it`() {
        val ms = ValhallaRouter.parse(mergeTrip(), null, departSaid = true).single().maneuvers
        assertEquals("Merge toward Sacramento", ms[1].instruction)
        assertEquals("Turn right onto B Road", ms[2].instruction)
    }

    @Test
    fun `a slight bend that keeps the road stays a silent rename in a piece cut from mid-trip`() {
        // What the road is called on the map decides this, told or not: it is the road curving.
        val piece = ValhallaRouter.parse(cornerTrip(9), null, departSaid = false).single().maneuvers
        assertTrue(piece.none { it.type == ManeuverType.SLIGHT_RIGHT })
    }

    @Test
    fun `garbage is an empty list`() {
        assertTrue(ValhallaRouter.parse("{}").isEmpty())
        assertTrue(ValhallaRouter.parse("not json").isEmpty())
    }

    companion object {
        private const val CAPTURE = """{"trip":{"summary":{"length":7.588,"time":1607.089},"legs":[{"shape":"c`rohA`gmegFhCe@b@IdEw@j@dFFn@lGbp@JfAl@xFh@nFJbAtGrp@Ht@h@xFl@zFDh@`H`r@Ff@f@fFh@jF@L`Hls@h@zEt@lG~Dx`@dEhb@j@d@RnACh@Yj@~Cp[pCvXFp@XtCZhDHt@wCh@qARSMiA[mAIcDj@a@RsAdAeAz@aBfAiBv@{A^yAJ}ABiG]s@@{ALeqAjVgIxBiAf@kF|C_AnAW`CaAzVAjEZdS@xAAfDAhG[|kASnNYbHBzFMrFL|Bn@lEJbCErCYtFAt@U~qA?VArLGbt@HnA`@vAb@z@~@hAVlCZfDb@~EA|@Sp@iCxDc@tAOfB]zfC[xC?|CJfBl@vCd@v@El@@j@g@x@_@lBWlCEzDRdD?hUCfNFhCPbC`DbVDxAI~@_AxAGFOX[b@k@j@q@b@aA^K|@AhCB`I@tCCrBrE`Ax@p@\\x@TlA|@`HvDuArBS`BJxO~EbBp@pRvKfCfCnC`EbBbEz@vCnCpRl@rAx@p@lAKlD_@zO{CpDQjC?nDj@zOzIdAh@t@\\pBjArBhArAx@nKlFzFdCv@b@fFnElC~BtAr@lFvA~@Pf@DtFF~N?nPJpREhA@P@LLtAfC~@~@n@Xd@HlKFt@?p@Of@e@xAiDX]b@OvFDjFDtP@dOFh@CrUDn{@Px@Wj@o@jCJjDLxDNWhCD~T`BbMdCfHd@vAzEbERRpExEfDrE","maneuvers":[{"type":2,"instruction":"Bike south on F Street.","street_names":["F Street"],"length":0.02,"time":4.179,"begin_shape_index":0,"end_shape_index":3,"bearing_after":168},{"type":10,"instruction":"Turn right onto 3rd Street.","street_names":["3rd Street"],"length":0.498,"time":109.499,"begin_shape_index":3,"end_shape_index":25,"bearing_before":168,"bearing_after":256},{"type":16,"instruction":"Bear left to stay on 3rd Street.","street_names":["3rd Street"],"length":0.003,"time":0.599,"begin_shape_index":25,"end_shape_index":26,"bearing_before":257,"bearing_after":214},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.008,"time":2.5,"begin_shape_index":26,"end_shape_index":29,"bearing_before":214,"bearing_after":273},{"type":16,"instruction":"Bear left onto 3rd Street.","street_names":["3rd Street"],"length":0.086,"time":19.049,"begin_shape_index":29,"end_shape_index":33,"bearing_before":276,"bearing_after":257},{"type":8,"instruction":"Continue on the cycleway.","length":0.01,"time":4.25,"begin_shape_index":33,"end_shape_index":35,"bearing_before":258,"bearing_after":258},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.532,"time":106.399,"begin_shape_index":35,"end_shape_index":67,"bearing_before":257,"bearing_after":348},{"type":8,"instruction":"Continue.","length":0.022,"time":6.649,"begin_shape_index":67,"end_shape_index":69,"bearing_before":276,"bearing_after":269},{"type":8,"instruction":"Continue on the cycleway.","length":0.532,"time":108.849,"begin_shape_index":69,"end_shape_index":97,"bearing_before":274,"bearing_after":260},{"type":26,"instruction":"Enter the roundabout and take the 1st exit.","length":0.004,"time":0.799,"begin_shape_index":97,"end_shape_index":99,"bearing_before":240,"bearing_after":273,"roundabout_exit_count":1},{"type":27,"instruction":"Exit the roundabout onto the cycleway.","length":0.153,"time":34.702,"begin_shape_index":99,"end_shape_index":118,"bearing_before":273,"bearing_after":300},{"type":15,"instruction":"Turn left onto the cycleway.","length":0.035,"time":14.5,"begin_shape_index":118,"end_shape_index":123,"bearing_before":335,"bearing_after":284},{"type":15,"instruction":"Turn left onto the cycleway.","length":0.036,"time":10.95,"begin_shape_index":123,"end_shape_index":128,"bearing_before":273,"bearing_after":194},{"type":15,"instruction":"Turn left onto the cycleway.","length":0.303,"time":62.85,"begin_shape_index":128,"end_shape_index":152,"bearing_before":255,"bearing_after":160},{"type":8,"instruction":"Continue on La Rue.","street_names":["La Rue"],"length":0.516,"time":103.649,"begin_shape_index":152,"end_shape_index":189,"bearing_before":207,"bearing_after":208},{"type":8,"instruction":"Continue.","length":0.028,"time":13.099,"begin_shape_index":189,"end_shape_index":192,"bearing_before":155,"bearing_after":184},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.121,"time":24.287,"begin_shape_index":192,"end_shape_index":201,"bearing_before":184,"bearing_after":280},{"type":6,"instruction":"Your destination is on the left.","length":0.0,"time":0.0,"begin_shape_index":201,"end_shape_index":201,"bearing_before":225}]},{"shape":"kreohAluwfgFgDsEqEyESS{EcEe@wAeCgHaBcME_UViCyDOkDMkCKk@n@y@Vo{@QsUEi@BeOGuPAkFEwFEc@NY\\yAhDg@d@q@Nu@?mKGe@Io@Y_A_AuAgCMMQAiAAqRDoPK_O?uFGg@E_AQmFwAuAs@mC_CgFoEw@c@{FeCoKmFsAy@sBiAqBkAu@]eAi@{O{IoDk@kC?qDP{OzCmD^mAJyDtA{VtFcEz@_Ez@oPjDkGrAi@GW@sB\\sAN}@F]Oa@Ec@H_@VW`@Mh@Aj@Fl@W^K`@MfBS`CGxFDVN^CrJGrYAhD?zABjGOnh@Gn\\C|\\AbIBj@VnA`AjDZfBH~B?`@?lB?xB?h@?LA~DQhH[vCsA|DQl@QpAI~AI|][vkAIdAEp@q@bEEn@C|@@jDFzHN~V?p@An@WfDO~Ba@`FIbBExAAzDIzn@@vCJlDLdCr@lLNpDFvDD`KDnANfBPtB@p@Q`PVrDh@fBbAbA|DpAsAjIa@rFmAhHk@pBq@~AOLOJU\\Qj@Ip@@r@T~@j@v@\\zBRpCBjBNzA?bCGpCE~BMzCk@pGwAfLa@`IIhKs@xdEuFAiC?{D?AxDc@?qv@UoFA}FLsBNaD`@gDl@oD|@{FjBaFtB_FrCkAt@gChBiCtBwBpBsShSqNpNoDhCqB|@yBv@uBd@iBP}ADuGJcu@e@m@t~@o@zeAFpFZjF`AxJRpAfAvHoDj@cAPyUbEcc@dIgGrAsLtDoJtDqFrCwKnHaPpNwIdJyJrO_GlL}DbIcEtJwBbGWt@qCxJm@`Du@pEyAnJmApI{C_@qBa@cCo@{DeD{BoBw@m@m@[cBQs@BsHXwXa@aIEiIg@kCFwFf@oI\\gFb@_AIoGcAWCg@GcB?cBJaFZmS?qA?}G?cPg@qT@cCJ_CEaKrAaBBs@Fs@Rc@f@mA|AuAhAeFHqJC{@OiACiALk@f@wAxAw@p@kEvAuDf@oBXi@Nq@\\}CpBsArBYd@aEdDaEvBeALkAL{JeBgDPq@`BwAvA_Bn@iBFcCi@qCeBeBeAeC_@gC^sB|B_BpDs@v@OPcA`@cBZoBVgAXmCnBgAdBqBhDw@bBoAhAiAXmL?wa@e@gFAaBbvBElc@a@pf@AdAGjFcEAyh@O","maneuvers":[{"type":2,"instruction":"Bike northeast on the cycleway.","length":0.121,"time":24.287,"begin_shape_index":0,"end_shape_index":9,"bearing_after":45},{"type":15,"instruction":"Turn left.","length":0.028,"time":13.099,"begin_shape_index":9,"end_shape_index":12,"bearing_before":100,"bearing_after":4},{"type":16,"instruction":"Bear left onto La Rue.","street_names":["La Rue"],"length":0.516,"time":103.649,"begin_shape_index":12,"end_shape_index":49,"bearing_before":4,"bearing_after":335},{"type":8,"instruction":"Continue on the cycleway.","length":0.27,"time":56.249,"begin_shape_index":49,"end_shape_index":71,"bearing_before":28,"bearing_after":27},{"type":26,"instruction":"Enter the roundabout and take the 2nd exit.","length":0.016,"time":3.2,"begin_shape_index":71,"end_shape_index":79,"bearing_before":353,"bearing_after":15,"roundabout_exit_count":2},{"type":27,"instruction":"Exit the roundabout onto the cycleway.","length":0.82,"time":173.75,"begin_shape_index":79,"end_shape_index":147,"bearing_before":265,"bearing_after":292},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.45,"time":90.2,"begin_shape_index":147,"end_shape_index":173,"bearing_before":199,"bearing_after":288},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.041,"time":11.949,"begin_shape_index":173,"end_shape_index":177,"bearing_before":271,"bearing_after":0},{"type":10,"instruction":"Turn right onto Eisenhower Street.","street_names":["Eisenhower Street"],"length":0.474,"time":100.2,"begin_shape_index":177,"end_shape_index":202,"bearing_before":271,"bearing_after":0},{"type":15,"instruction":"Turn left onto Calaveras Avenue.","street_names":["Calaveras Avenue"],"length":0.243,"time":52.5,"begin_shape_index":202,"end_shape_index":209,"bearing_before":1,"bearing_after":272},{"type":10,"instruction":"Turn right onto Anderson Road.","street_names":["Anderson Road"],"length":0.463,"time":96.25,"begin_shape_index":209,"end_shape_index":231,"bearing_before":254,"bearing_after":349},{"type":10,"instruction":"Turn right onto the cycleway.","length":0.619,"time":124.7,"begin_shape_index":231,"end_shape_index":291,"bearing_before":287,"bearing_after":9},{"type":15,"instruction":"Turn left onto the cycleway.","length":0.244,"time":48.799,"begin_shape_index":291,"end_shape_index":317,"bearing_before":355,"bearing_after":309},{"type":15,"instruction":"Turn left onto West Covell Boulevard/CR E6.","street_names":["West Covell Boulevard","CR E6"],"length":0.285,"time":63.45,"begin_shape_index":317,"end_shape_index":322,"bearing_before":0,"bearing_after":272},{"type":10,"instruction":"Turn right onto County Road 99.","street_names":["County Road 99"],"length":0.085,"time":17.981,"begin_shape_index":322,"end_shape_index":324,"bearing_before":272,"bearing_after":1},{"type":5,"instruction":"Your destination is on the right.","length":0.0,"time":0.0,"begin_shape_index":324,"end_shape_index":324,"bearing_before":1}]}]}}"""
    }
}
