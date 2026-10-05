package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.TravelMode
import org.junit.Assert.*
import org.junit.Test

class BottomOverlayTest {
    private val place = Place(id = "test", name = "Place", location = LatLng(38.5449, -121.7405))
    private fun overlay(state: MapUiState, search: Boolean = false, picking: Boolean = false, google: Boolean = true) =
        BottomOverlay.of(state, search, picking, google)

    @Test fun `closing a place retains a distinct outgoing frame while the target becomes empty`() {
        val open = MapUiState(selected = place)
        val outgoing = BottomOverlayFrame(open, overlay(open))
        assertEquals(BottomOverlay.NONE, overlay(open.copy(selected = null)))
        assertEquals(place, outgoing.state.selected)
        assertEquals(BottomOverlay.PLACE, outgoing.overlay)
    }

    @Test fun `async place updates and switching places do not replay entrance`() {
        assertEquals(overlay(MapUiState(selected = place)), overlay(MapUiState(selected = place.copy(id = "resolved", name = "Resolved"))))
    }

    @Test fun `directions and trip editing supersede place details`() {
        val state = MapUiState(selected = place, directionsOpen = true)
        assertEquals(BottomOverlay.DIRECTIONS, overlay(state))
        assertEquals(BottomOverlay.CLASSIC_DIRECTIONS, overlay(state, google = false))
        assertEquals(BottomOverlay.CLASSIC_DIRECTIONS, overlay(state.copy(travelMode = TravelMode.TRANSIT)))
        assertEquals(BottomOverlay.TRIP_EDITOR, overlay(state.copy(editingStops = true)))
        assertEquals(BottomOverlay.PLACE, overlay(state.copy(directionsOpen = false)))
    }

    @Test fun `search and street view hide sheets without dropping model data`() {
        val state = MapUiState(selected = place)
        assertEquals(BottomOverlay.NONE, overlay(state, search = true))
        assertEquals(BottomOverlay.NONE, overlay(state.copy(streetViewLoading = true)))
    }

    @Test fun `steps retain their own motion instead of double sliding`() {
        val state = MapUiState(selected = place, directionsOpen = true, showSteps = true)
        assertEquals(BottomOverlay.STEPS, overlay(state))
        assertFalse(overlay(state).slide)
        assertEquals(BottomOverlay.NAV_CONTROLS, overlay(MapUiState(navigating = true)))
        assertTrue(BottomOverlay.NAV_CONTROLS.slide)
        assertFalse(BottomOverlay.animateChange(BottomOverlay.STEPS, BottomOverlay.NAV_CONTROLS))
        assertFalse(BottomOverlay.animateChange(BottomOverlay.NAV_CONTROLS, BottomOverlay.STEPS))
        assertTrue(BottomOverlay.animateChange(BottomOverlay.DIRECTIONS, BottomOverlay.NAV_CONTROLS))
        assertTrue(BottomOverlay.animateChange(BottomOverlay.NAV_CONTROLS, BottomOverlay.NONE))
    }

    @Test fun `results return after place closes and remain available in pick mode`() {
        val state = MapUiState(results = listOf(place), selected = place)
        assertEquals(BottomOverlay.PLACE, overlay(state))
        val results = state.copy(selected = null)
        assertEquals(BottomOverlay.RESULTS, overlay(results))
        assertEquals(BottomOverlay.NONE, overlay(results, search = true))
        assertEquals(BottomOverlay.RESULTS, overlay(results, search = true, picking = true))
    }
}
