package app.vela.core.data.google.parse

import app.vela.core.model.ExitSign
import app.vela.core.model.LatLng
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's exits read off its directions reply, in the shape calibrated 2026-11-14. */
class DirectionsExitSignsTest {
    private fun arr(vararg e: JsonElement?) = JsonArray(e.map { it ?: JsonNull })
    private fun n(v: Number) = JsonPrimitive(v)
    private fun s(v: String) = JsonPrimitive(v)

    /** Steps as the reply carries them; in [text] a name is between `[` `]`, as the reply flags it. */
    private fun steps(vararg st: Triple<String, LatLng, String?>): List<ExitSign> =
        DirectionsParser.exitSigns(arr(arr(*st.map { (t, at, icon) -> stepOf(t, at, icon) }.toTypedArray())))

    /** `[7][2]` the point, `[14]` the parts; the icon in the array the step heads. */
    private fun stepOf(text: String, at: LatLng, icon: String?): JsonArray {
        val parts = ArrayList<JsonArray>()
        Regex("\\[([^\\]]*)\\]|([^\\[]+)").findAll(text).forEach { m ->
            val name = m.groups[1]?.value
            parts += if (name != null) arr(n(2), arr(s(name), n(1))) else arr(n(2), arr(s(m.groups[2]!!.value)))
        }
        val st = arr(
            n(0), s(""), arr(n(500), s("0.3 mi"), n(1)), arr(n(30), s("30 sec")), null, null, null,
            arr(arr(arr(n(2612.0), n(at.lng), n(at.lat))), arr(), arr(null, null, n(at.lat), n(at.lng))),
            null, null, null, null, null, null, JsonArray(parts),
        )
        return arr(st, null, arr(s("//maps.gstatic.com/tactile/directions/text_mode/maneuvers-2x.png"), icon?.let { s("dir-tt $it") }))
    }

    private val a = LatLng(38.5700, -121.5146)
    private val b = LatLng(38.5804, -121.5048)

    @Test fun anExitGivesItsNumberItsSignsAndItsSide() {
        val signs = steps(
            Triple("Take exit [4A] to merge onto [I-5 N] toward [Redding]", a, "dir-tt-ramp-right"),
            Triple("Use the right lane to take exit [519B] for [J St] toward [Downtown]", b, "dir-tt-ramp-right"),
        )
        assertEquals(listOf(ExitSign(a, "4A", "I-5 N: Redding", false), ExitSign(b, "519B", "J St, Downtown", false)), signs)
    }

    @Test fun aSignSplitIntoPartsIsOneSignAndOneThatRepeatsTheNumberIsDropped() {
        val sign = steps(Triple("Take exit [147] toward [147]/[Bush Intercont]['][l Arpt]/[Will Clayton Pkwy]", a, "dir-tt-ramp-right")).single()
        assertEquals("147", sign.number)
        assertEquals("Bush Intercont'l Arpt, Will Clayton Pkwy", sign.dest)
    }

    @Test fun anExitByItsSignAMotorwayJunctionAndAHighwayToHighwayRampAreExits() {
        val named = steps(Triple("Take the [Jackson Gap St] exit toward [Rental Car Return]", a, "dir-tt-ramp-right")).single()
        assertEquals(ExitSign(a, null, "Jackson Gap St, Rental Car Return", false), named)
        val junction = steps(Triple("At junction [4], Use the left lane to exit toward [Heathrow Airport]", a, "dir-tt-ramp-left")).single()
        assertEquals(ExitSign(a, "4", "Heathrow Airport", true), junction)
        // No exit word, but the icon is a ramp off the road.
        val ramp = steps(Triple("Use the right 3 lanes to merge onto [I-105 W] toward [Lax Arpt]", a, "dir-tt-ramp-right")).single()
        assertEquals(ExitSign(a, null, "I-105 W: Lax Arpt", false), ramp)
        // An exit word and no icon: the side is not known.
        assertEquals(ExitSign(a, null, "Airport", null), steps(Triple("Use the middle lane to take the exit toward [Airport]", a, null)).single())
    }

    @Test fun turnsRampsOntoAHighwayAndRoundaboutsAreNoExits() {
        val none = steps(
            Triple("Turn [left] onto [Richards Blvd]", a, "dir-tt-turn-left"),
            Triple("Use the left 2 lanes to turn [left] to merge onto [I-80 E] toward [Sacramento]", a, "dir-tt-merge"),
            Triple("At [Hogarth Roundabout], take the [2nd] exit onto [Hogarth Ln]/[A4]", a, "dir-tt-roundabout-left"),
            Triple("Keep [left] to continue on [US-50 E], follow signs for [Sacramento]", a, "dir-tt-turn-left"),
        )
        assertTrue(none.toString(), none.isEmpty())
    }

    private fun names(vararg st: Triple<String, LatLng, String?>): List<app.vela.core.model.StepName> =
        DirectionsParser.stepNames(arr(arr(*st.map { (t, at, icon) -> stepOf(t, at, icon) }.toTypedArray())))

    @Test fun everyStepGivesTheStreetItGoesOntoAndItsSide() {
        val steps = names(
            Triple("Head toward [I St]", a, null),
            Triple("Turn [right] onto [E 8th St]", a, "dir-tt-turn-right"),
            Triple("[L St] turns [left] and becomes [2nd St]", b, null),
            Triple("Turn [left] to stay on [Country Club Dr]", a, null),
            Triple("Continue onto [King St]", b, null),
            Triple("Keep [right] at the fork", a, null),
        )
        assertEquals(
            listOf(
                app.vela.core.model.StepName(a, "E 8th St", false),
                app.vela.core.model.StepName(b, "2nd St", true),
                app.vela.core.model.StepName(a, "Country Club Dr", true),
                app.vela.core.model.StepName(b, "King St", null),
                app.vela.core.model.StepName(a, null, false),
            ),
            steps,
        )
    }
}
