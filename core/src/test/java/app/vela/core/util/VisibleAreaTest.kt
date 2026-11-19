package app.vela.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleAreaTest {
    private val map = floatArrayOf(0f, 0f, 1000f, 2000f) // a portrait phone

    @Test fun nothingCoveringIsTheWholeMap() {
        assertArrayEquals(map, VisibleArea.of(map, emptyList()), 0f)
        assertEquals(500f to 1000f, VisibleArea.center(map, emptyList()))
    }

    @Test fun aBottomSheetKeepsTheStripAboveIt() {
        val sheet = floatArrayOf(0f, 1100f, 1000f, 2000f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1000f, 1100f), VisibleArea.of(map, listOf(sheet)), 0f)
        assertEquals(500f to 550f, VisibleArea.center(map, listOf(sheet)))
    }

    @Test fun aTopCardAndABottomBarKeepTheMiddle() {
        val card = floatArrayOf(20f, 0f, 980f, 400f)
        val bar = floatArrayOf(0f, 1800f, 1000f, 2000f)
        assertArrayEquals(floatArrayOf(0f, 400f, 1000f, 1800f), VisibleArea.of(map, listOf(card, bar)), 0f)
    }

    @Test fun aLandscapeSidePanelKeepsThePartBesideIt() {
        val land = floatArrayOf(0f, 0f, 2000f, 1000f)
        val panel = floatArrayOf(0f, 0f, 1120f, 1000f)
        assertArrayEquals(floatArrayOf(1120f, 0f, 2000f, 1000f), VisibleArea.of(land, listOf(panel)), 0f)
    }

    @Test fun aLandscapeCardAndPanelKeepTheFullHeightColumnBesideThem() {
        // The route card on top of the route panel, both in the left column. Cutting the panel
        // first keeps the wider strip above it, which the card then cuts to a corner.
        val land = floatArrayOf(0f, 0f, 2340f, 1080f)
        val card = floatArrayOf(33f, 99f, 1344f, 403f)
        val panel = floatArrayOf(136f, 516f, 1311f, 1080f)
        assertArrayEquals(floatArrayOf(1344f, 0f, 2340f, 1080f), VisibleArea.of(land, listOf(card, panel)), 0f)
    }

    @Test fun aFullScreenPageLeavesNothing() {
        assertNull(VisibleArea.of(map, listOf(floatArrayOf(0f, 0f, 1000f, 2000f))))
        assertNull(VisibleArea.center(map, listOf(floatArrayOf(-5f, -5f, 1005f, 2005f))))
    }

    @Test fun aSheetDraggedToTheTopLeavesNothing() {
        assertNull(VisibleArea.of(map, listOf(floatArrayOf(0f, 0f, 1000f, 2000f), floatArrayOf(0f, 0f, 1000f, 150f))))
    }

    @Test fun aPanelOffTheMapIsIgnored() {
        val gone = floatArrayOf(0f, 2000f, 1000f, 2600f) // slid out below the screen
        assertArrayEquals(map, VisibleArea.of(map, listOf(gone)), 0f)
    }

    @Test fun theOrderReportedDoesNotMatter() {
        val sheet = floatArrayOf(0f, 1100f, 1000f, 2000f)
        val card = floatArrayOf(20f, 0f, 980f, 400f)
        assertArrayEquals(VisibleArea.of(map, listOf(sheet, card)), VisibleArea.of(map, listOf(card, sheet)), 0f)
    }
}
