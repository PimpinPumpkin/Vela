package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TollNote
import app.vela.core.model.TravelMode
import app.vela.core.nav.CameraDetour
import app.vela.core.nav.TollCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRoutePlanTest {
    private val first = route(-121.735)
    private val second = route(-121.736)
    private val state = MapUiState(directionsOpen = true, travelMode = TravelMode.DRIVE, routes = listOf(first, second))

    @Test fun `a saved route matches by identity after an asynchronous reorder`() {
        val plan = CameraRoutePlan()
        assertEquals(0, plan.indexOfRoute(second, listOf(second.copy(savedName = "Davis fixture"), first)))
        assertEquals(-1, plan.indexOfRoute(second, listOf(first)))
    }

    @Test fun `an older camera pass cannot publish after same size route replacement`() {
        val plan = CameraRoutePlan()
        val old = plan.epoch
        assertTrue(plan.isCurrent(old, TravelMode.DRIVE, state))
        plan.invalidate()
        val reordered = state.copy(routes = listOf(second, first))
        assertFalse(plan.isCurrent(old, TravelMode.DRIVE, reordered))
        assertTrue(plan.isCurrent(plan.epoch, TravelMode.DRIVE, reordered))
    }

    @Test fun `a completed side street pass cannot publish outside planning`() {
        val plan = CameraRoutePlan()
        val epoch = plan.epoch
        assertFalse(plan.isCurrent(epoch, TravelMode.DRIVE, state.copy(routes = emptyList())))
        assertFalse(plan.isCurrent(epoch, TravelMode.DRIVE, state.copy(directionsOpen = false)))
        assertFalse(plan.isCurrent(epoch, TravelMode.DRIVE, state.copy(navigating = true)))
        assertFalse(plan.isCurrent(epoch, TravelMode.DRIVE, state.copy(travelMode = TravelMode.WALK)))
    }

    @Test fun `a named route invalidates counts only if its geometry changed`() {
        val plan = CameraRoutePlan()
        val epoch = plan.epoch
        assertFalse(plan.invalidateForResnap(first, first.copy(savedName = "Davis fixture")))
        assertTrue(plan.isCurrent(epoch, TravelMode.DRIVE, state))
        assertTrue(plan.invalidateForResnap(first, second))
        assertFalse(plan.isCurrent(epoch, TravelMode.DRIVE, state))
    }

    @Test fun `a provisional route still matches its row after toll labels and an appended route`() {
        val plan = CameraRoutePlan()
        val routes = TollCompare.apply(listOf(first), TollCompare.Plan(tolledIdx = 0, avoidingIdx = null), second)
        assertEquals(TollNote.MAY_HAVE_TOLLS, routes.first().tolls)
        assertEquals(0, plan.indexOfRoute(first, routes))
        assertEquals(-1, plan.indexOfRoute(first, listOf(route(-121.735), second)))
    }

    @Test fun `a recount keeps the remaining side street request budget`() {
        val plan = CameraRoutePlan()
        assertTrue(plan.takeDetourRequest())
        assertTrue(plan.invalidateForResnap(first, second))
        repeat(CameraDetour.MAX_REQUESTS - 1) { assertTrue(plan.takeDetourRequest()) }
        assertFalse(plan.takeDetourRequest())
        plan.resetDetourBudget()
        assertEquals(CameraDetour.MAX_REQUESTS, plan.detourRequestsLeft)
    }

    private fun route(longitude: Double) = Route(
        polyline = listOf(LatLng(38.54, longitude), LatLng(38.55, longitude)),
        legs = emptyList(), distanceMeters = 1_100.0, durationSeconds = 100.0, durationInTrafficSeconds = null,
    )
}
