package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The traps the gates exist for, each pinned: the wording of the comments in docs/dpad.md and
 *  MapScreen's results-sheet notes, as tests. */
class SearchGatesTest {
    private val place = Place(id = "g:1", name = "Somewhere", location = LatLng(38.5449, -121.7405))
    private val results = listOf(place, place.copy(id = "g:2", name = "Elsewhere"))

    @Test fun `a focused field always shows the overlay, armed or not`() {
        assertTrue(SearchGates.of(MapUiState(), searchExpanded = false, searchFocused = true).searchOpen)
        assertTrue(SearchGates.of(MapUiState(), searchExpanded = true, searchFocused = false).searchOpen)
        assertFalse(SearchGates.of(MapUiState(), searchExpanded = false, searchFocused = false).searchOpen)
    }

    @Test fun `results show on the bare map and hide behind an open search or a selected place`() {
        val bare = SearchGates.of(MapUiState(results = results), false, false)
        assertTrue(bare.resultsShown); assertFalse(bare.resultsMinimized); assertTrue(bare.mapTargetHidden)
        assertFalse(SearchGates.of(MapUiState(results = results), true, false).resultsShown)
        assertFalse(SearchGates.of(MapUiState(results = results, selected = place), false, false).resultsShown)
    }

    @Test fun `a submitted search while picking a stop shows its results (issue 405)`() {
        val picking = MapUiState(results = results, query = "Coffee", pickingStop = true, selected = place)
        val blurred = SearchGates.of(picking, searchExpanded = true, searchFocused = false)
        assertTrue(blurred.pickingResults); assertTrue(blurred.resultsShown); assertTrue(blurred.searchOpen)
        // Still typing: the suggestions own the overlay, not the results.
        val typing = SearchGates.of(picking, searchExpanded = true, searchFocused = true)
        assertFalse(typing.pickingResults); assertFalse(typing.resultsShown)
    }

    @Test fun `street view keeps the results list off the mini map`() {
        val sv = SearchGates.of(MapUiState(results = results, streetViewLoading = true), false, false)
        assertFalse(sv.resultsShown); assertFalse(sv.fabChromeOk)
    }

    @Test fun `a collapsed list is the minimized bar, not the sheet`() {
        val g = SearchGates.of(MapUiState(results = results, resultsCollapsed = true), false, false)
        assertFalse(g.resultsShown); assertTrue(g.resultsMinimized); assertFalse(g.mapTargetHidden)
    }

    @Test fun `picking on the map keeps the crosshair even under a panel`() {
        val g = SearchGates.of(MapUiState(directionsOpen = true, pickOnMap = MapPick.DEST), false, false)
        assertFalse(g.mapTargetHidden)
    }

    @Test fun `bare map and fab chrome`() {
        val bare = SearchGates.of(MapUiState(), false, false, driving = false)
        assertTrue(bare.bareMap); assertTrue(bare.fabChromeOk); assertFalse(bare.drivingChrome)
        assertFalse(SearchGates.of(MapUiState(navigating = true), false, false, driving = false).fabChromeOk)
        assertFalse(SearchGates.of(MapUiState(selected = place), false, false, driving = false).bareMap)
    }

    @Test fun `driving mode takes the bottom edge from the browse buttons on the bare map only`() {
        val bare = SearchGates.of(MapUiState(), false, false, driving = true)
        assertTrue(bare.drivingChrome); assertFalse(bare.fabChromeOk)
        assertTrue(SearchGates.drivingChromeLive.value)
        // A drive, the search page, a place, a list, a pick and a replay put the mode's chrome away,
        // and the browse buttons come back where they would on the plain map.
        assertFalse(SearchGates.of(MapUiState(navigating = true), false, false, driving = true).drivingChrome)
        assertFalse(SearchGates.of(MapUiState(), true, false, driving = true).drivingChrome)
        assertFalse(SearchGates.drivingChromeLive.value)
        val withPlace = SearchGates.of(MapUiState(selected = place), false, false, driving = true)
        assertFalse(withPlace.drivingChrome); assertTrue(withPlace.fabChromeOk)
        assertFalse(SearchGates.of(MapUiState(results = results), false, false, driving = true).drivingChrome)
        assertFalse(SearchGates.of(MapUiState(pickOnMap = MapPick.DEST), false, false, driving = true).drivingChrome)
        assertFalse(SearchGates.of(MapUiState(replaying = true), false, false, driving = true).drivingChrome)
        assertFalse(SearchGates.of(MapUiState(arrived = true), false, false, driving = true).drivingChrome)
    }
}
