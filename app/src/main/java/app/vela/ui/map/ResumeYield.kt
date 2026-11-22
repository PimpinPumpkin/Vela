package app.vela.ui.map

/**
 * Whether a "Resume your drive" tapped at [atTap] should give way by the time its route is ready
 * ([now]): the wait for a fresh fix and the route take seconds, and a driver who has started
 * something else in them (another drive, directions, a place, a new search) has moved on. The
 * resumed drive used to start under that and leave the route sheet over the live drive.
 */
internal object ResumeYield {
    fun movedOn(atTap: MapUiState, now: MapUiState): Boolean =
        now.navigating ||
            (now.directionsOpen && !atTap.directionsOpen) ||
            (now.selected != null && now.selected?.id != atTap.selected?.id) ||
            (now.query.isNotBlank() && now.query != atTap.query)
}
