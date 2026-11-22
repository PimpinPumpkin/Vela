package app.vela.streetview

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundArrowsTest {
    private val w = 1080f
    private val h = 1290f

    @Test fun theLookDirectionIsTheMiddleAndHalfTheFieldOfViewIsTheEdge() {
        val c = GroundArrows.project(90.0, 0.0, 90.0, 0.0, 75.0, w, h)!!
        assertEquals(w / 2, c.x, 0.5f)
        assertEquals(h / 2, c.y, 0.5f)
        assertEquals(w, GroundArrows.project(127.5, 0.0, 90.0, 0.0, 75.0, w, h)!!.x, 0.5f)
        assertNull(GroundArrows.project(270.0, 0.0, 90.0, 0.0, 75.0, w, h))
    }

    @Test fun theGroundIsBelowTheMiddleAndRisesWhenYouLookDown() {
        val level = GroundArrows.project(0.0, -20.0, 0.0, 0.0, 75.0, w, h)!!
        val lookingDown = GroundArrows.project(0.0, -20.0, 0.0, -20.0, 75.0, w, h)!!
        assertTrue(level.y > h / 2)
        assertEquals(h / 2, lookingDown.y, 0.5f)
    }

    @Test fun aChevronAheadSitsLowInTheMiddleAndOneBehindIsNotDrawn() {
        val ahead = GroundArrows.chevron(0.0, 0.0, 0.0, 75.0, w, h)!!
        val cx = ahead.sumOf { it.x.toDouble() } / ahead.size
        val cy = ahead.sumOf { it.y.toDouble() } / ahead.size
        assertEquals(w / 2.0, cx, 1.0)
        assertTrue(cy > h * 0.6)
        // The tip points up the screen, away from the camera.
        assertTrue(ahead[0].y < cy)
        assertNull(GroundArrows.chevron(180.0, 0.0, 0.0, 75.0, w, h))
    }

    @Test fun aTapTakesTheNearestChevronInReach() {
        val centers = listOf(Offset(300f, 900f), null, Offset(700f, 880f))
        assertEquals(2, GroundArrows.pick(Offset(680f, 860f), centers, 150f))
        assertNull(GroundArrows.pick(Offset(500f, 300f), centers, 150f))
    }
}
