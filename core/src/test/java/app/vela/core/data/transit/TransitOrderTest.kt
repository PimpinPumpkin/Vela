package app.vela.core.data.transit

import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitStep
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitOrderTest {
    private fun walk(min: String) = TransitStep(mode = TransitMode.WALK, durationText = min)
    private fun ride(name: String) = TransitStep(mode = TransitMode.BUS, durationText = "10 min", line = TransitLine(name = name, mode = TransitMode.BUS))

    private val twoRidesShortWalk = TransitItinerary(durationText = "A", steps = listOf(walk("2 min"), ride("1"), ride("2"), walk("3 min")))
    private val oneRideLongWalk = TransitItinerary(durationText = "B", steps = listOf(walk("1 hr 5 min"), ride("3")))
    private val oneRideMidWalk = TransitItinerary(durationText = "C", steps = listOf(walk("12 min"), ride("4"), walk("4 min")))

    @Test
    fun `fewer transfers puts the fewest rides first, keeping order on ties`() {
        val out = TransitOrder.byPreference(listOf(twoRidesShortWalk, oneRideLongWalk, oneRideMidWalk), 2)
        assertEquals(listOf("B", "C", "A"), out.map { it.durationText })
    }

    @Test
    fun `less walking puts the least walking first`() {
        val out = TransitOrder.byPreference(listOf(oneRideLongWalk, oneRideMidWalk, twoRidesShortWalk), 3)
        assertEquals(listOf("A", "C", "B"), out.map { it.durationText })
    }

    @Test
    fun `best keeps the planner's order and minutes read hours`() {
        val trips = listOf(oneRideLongWalk, twoRidesShortWalk)
        assertEquals(trips, TransitOrder.byPreference(trips, 0))
        assertEquals(65, TransitOrder.minutesOf("1 hr 5 min"))
        assertEquals(120, TransitOrder.minutesOf("2 h"))
        assertEquals(0, TransitOrder.minutesOf(null))
    }

    private val googleWalk = TransitItinerary(durationText = "W", steps = listOf(walk("50 min")))

    @Test
    fun `a walk-only answer gives way to the planner's rides, kept last`() {
        assertEquals(true, TransitOrder.walkOnly(listOf(googleWalk)))
        assertEquals(true, TransitOrder.walkOnly(listOf(TransitItinerary(durationText = "E"))))
        assertEquals(false, TransitOrder.walkOnly(listOf(googleWalk, oneRideMidWalk)))
        assertEquals(false, TransitOrder.walkOnly(emptyList()))
        val out = TransitOrder.ridesBeforeWalk(listOf(googleWalk), listOf(twoRidesShortWalk, oneRideLongWalk, googleWalk), 2)
        assertEquals(listOf("B", "A", "W"), out.map { it.durationText })
    }

    @Test
    fun `a planner with no ride leaves Google's walk alone`() {
        assertEquals(listOf("W"), TransitOrder.ridesBeforeWalk(listOf(googleWalk), listOf(googleWalk), 0).map { it.durationText })
        assertEquals(listOf("W"), TransitOrder.ridesBeforeWalk(listOf(googleWalk), emptyList(), 0).map { it.durationText })
    }
}
