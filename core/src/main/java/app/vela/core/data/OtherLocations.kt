package app.vela.core.data

/**
 * Settings > Search > "Find other locations automatically". Off (the default): a name search
 * that Google answers with one focused place shows a "Show other locations" row, and the extra
 * requests run only on a tap. On: they run with the search. Same seam as [NoGoogle]: the
 * preference lives in `:app` (`app.vela.ui.OtherLocationsAuto`) and is pushed down here.
 */
object OtherLocations {
    @Volatile var auto: Boolean = false
}
