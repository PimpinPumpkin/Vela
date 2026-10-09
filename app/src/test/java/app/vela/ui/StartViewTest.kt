package app.vela.ui

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the map opens: given the setting, whether fixes can arrive, the last known fix, the view
 *  the map was left on, Home, the picked view and whether a launch intent or a drive has the
 *  camera. Fixtures are Davis, Sacramento and San Francisco. */
class StartViewTest {
    private val davis = LatLng(38.5449, -121.7405)
    private val sacramento = LatLng(38.5816, -121.4944)
    private val left = StartCamera(LatLng(37.7749, -122.4194), 12.3)
    private val picked = StartCamera(LatLng(38.5539, -121.7381), 13.0)

    private fun open(
        mode: String = StartView.HERE,
        taken: Boolean = false,
        locationOn: Boolean = true,
        fix: LatLng? = davis,
        last: StartCamera? = left,
        home: LatLng? = sacramento,
        place: StartCamera? = picked,
    ) = startFor(mode, taken, locationOn, fix, last, home, place)

    @Test fun `where I am with location on opens on the fix and follows it`() {
        assertEquals(Start(davis), open())
        assertEquals(Start(davis), open(last = null, home = null, place = null))
    }

    @Test fun `where I am with location off opens where the map was left and waits for a fix`() {
        assertEquals(Start(left.center, left.zoom, hold = true, untilFix = true), open(locationOn = false))
        assertEquals(Start(left.center, left.zoom, hold = true, untilFix = true), open(locationOn = false, fix = null))
    }

    @Test fun `where I am with location off and no view saved opens on the old fix`() {
        assertEquals(Start(davis), open(locationOn = false, last = null))
    }

    @Test fun `where I am before the first fix stands in with the view the map was left on`() {
        assertEquals(Start(left.center, left.zoom, hold = true, untilFix = true), open(fix = null))
    }

    @Test fun `nothing known is the world view`() {
        assertEquals(Start(null), open(fix = null, last = null))
        assertEquals(Start(null), open(locationOn = false, fix = null, last = null))
    }

    @Test fun `where I left the map holds that view whether or not location is on`() {
        val want = Start(left.center, left.zoom, hold = true)
        assertEquals(want, open(mode = StartView.LAST))
        assertEquals(want, open(mode = StartView.LAST, locationOn = false, fix = null))
    }

    @Test fun `home opens on Home at street zoom and holds`() {
        val want = Start(sacramento, StartView.HOME_ZOOM, hold = true)
        assertEquals(want, open(mode = StartView.HOME))
        assertEquals(want, open(mode = StartView.HOME, locationOn = false, fix = null, last = null))
    }

    @Test fun `a place I choose opens on the picked view and holds`() {
        val want = Start(picked.center, picked.zoom, hold = true)
        assertEquals(want, open(mode = StartView.PLACE))
        assertEquals(want, open(mode = StartView.PLACE, locationOn = false))
    }

    @Test fun `a choice whose target is gone opens like where I am`() {
        // Home removed after it was picked.
        assertEquals(open(), open(mode = StartView.HOME, home = null))
        assertEquals(open(locationOn = false), open(mode = StartView.HOME, home = null, locationOn = false))
        // No view saved yet (the first launch after the setting was picked).
        assertEquals(open(last = null), open(mode = StartView.LAST, last = null))
        assertEquals(open(), open(mode = StartView.PLACE, place = null))
        // A value this build does not know.
        assertEquals(open(), open(mode = "elsewhere"))
    }

    @Test fun `a launch intent or a running drive keeps the camera in every mode`() {
        for (mode in listOf(StartView.HERE, StartView.LAST, StartView.HOME, StartView.PLACE)) {
            assertNull(open(mode = mode, taken = true))
            assertNull(open(mode = mode, taken = true, locationOn = false, fix = null))
        }
    }

    @Test fun `settings show a choice with no target as where I am`() {
        assertEquals(StartView.HOME, shownStartMode(StartView.HOME, homeSet = true, placeSet = false))
        assertEquals(StartView.HERE, shownStartMode(StartView.HOME, homeSet = false, placeSet = true))
        assertEquals(StartView.PLACE, shownStartMode(StartView.PLACE, homeSet = false, placeSet = true))
        assertEquals(StartView.HERE, shownStartMode(StartView.PLACE, homeSet = true, placeSet = false))
        assertEquals(StartView.LAST, shownStartMode(StartView.LAST, homeSet = false, placeSet = false))
        assertEquals(StartView.HERE, shownStartMode("elsewhere", homeSet = true, placeSet = true))
    }

    @Test fun `a view survives being stored`() {
        assertEquals(left, StartCamera.parse(left.encode()))
        assertEquals(picked, StartCamera.parse(picked.encode()))
    }

    @Test fun `text that is not a view reads as none`() {
        assertNull(StartCamera.parse(null))
        assertNull(StartCamera.parse(""))
        assertNull(StartCamera.parse("38.5449,-121.7405"))
        assertNull(StartCamera.parse("north,west,close"))
        assertNull(StartCamera.parse("91.0,-121.7405,12.0"))
        assertNull(StartCamera.of(Double.NaN, -121.7405, 12.0))
    }

    @Test fun `a longitude past the date line wraps and the zoom is bounded`() {
        assertEquals(-170.0, StartCamera.of(20.0, 190.0, 2.0)!!.center.lng, 1e-9)
        assertEquals(170.0, StartCamera.of(20.0, -190.0, 2.0)!!.center.lng, 1e-9)
        assertEquals(22.0, StartCamera.of(38.5449, -121.7405, 40.0)!!.zoom, 0.0)
    }
}
