package app.vela.ui.map

/**
 * The search, results and picker presentation gates, in one place (issue #417 refactor 3, step 3a).
 *
 * MapScreen used to compute these inline, nine booleans read from five places, and every one of
 * the traps in docs/dpad.md and the results-sheet notes lived in a different expression: the
 * focused field that must always show its overlay, the results that hide while a place is
 * selected unless the pick is for a stop, Street View clearing `selected` and letting the list
 * reappear over the mini map, the pick-on-map crosshair that must stay while everything else is
 * hidden. They are derived here from [MapUiState] plus the two focus facts only the screen knows,
 * so they can be unit-tested and read as one thing.
 */
internal data class SearchGates(
    /** The search overlay owns the screen: an armed or focused field, or an endpoint/stop pick. */
    val searchOpen: Boolean,
    /** A search SUBMITTED while picking an origin, destination or stop: its results may show even
     *  though the overlay counts as open and a place stays selected (issue #405). */
    val pickingResults: Boolean,
    /** The results sheet is up at peek or expanded. */
    val resultsShown: Boolean,
    /** The results sheet is collapsed to its bottom bar (the chrome lifts above it). */
    val resultsMinimized: Boolean,
    /** A panel owns the map, so the D-pad crosshair target unmounts (never while picking on the map). */
    val mapTargetHidden: Boolean,
    /** Nothing is covering the bottom corner: the parking and locate buttons may show. */
    val fabChromeOk: Boolean,
    /** No place selected and no search open: the plain map with its chips. */
    val bareMap: Boolean,
) {
    companion object {
        fun of(s: MapUiState, searchExpanded: Boolean, searchFocused: Boolean): SearchGates {
            val picking = s.pickingOrigin || s.pickingDest || s.pickingStop
            // Tied to LIVE focus as well as the armed flag: a focused field always shows its overlay
            // (re-tapping a focused field fires no focus change, so the flag alone could strand it).
            val searchOpen = searchExpanded || searchFocused || picking
            val pickingResults = picking && s.results.isNotEmpty() && !searchFocused && s.query.isNotBlank()
            val resultsOwn = s.results.isNotEmpty() && (s.selected == null || pickingResults) && (!searchOpen || pickingResults)
            val streetViewUp = s.streetView != null || s.streetViewLoading
            return SearchGates(
                searchOpen = searchOpen,
                pickingResults = pickingResults,
                resultsShown = resultsOwn && !s.resultsCollapsed && !streetViewUp,
                resultsMinimized = resultsOwn && s.resultsCollapsed,
                mapTargetHidden = s.pickOnMap == null && (
                    searchOpen || s.selected != null || s.directionsOpen || s.showSteps || s.arrived ||
                        (s.results.isNotEmpty() && !s.resultsCollapsed && s.selected == null)
                    ),
                fabChromeOk = !s.navigating && !searchOpen && s.resumeNavLabel == null && !s.areaPicking &&
                    !s.directionsOpen && !s.showSteps && s.transitNav == null && !streetViewUp,
                bareMap = s.selected == null && !searchOpen,
            )
        }
    }
}
