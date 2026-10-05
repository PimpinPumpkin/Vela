package app.vela.ui

import app.vela.ui.icons.Sym

import androidx.compose.ui.graphics.vector.ImageVector
import app.vela.R

/**
 * The one list of quick-category chips. The map's chip row, the route chooser's "search along
 * route" and the in-nav search all show these, in this order, so a chip means the same thing
 * wherever it appears (2026-09-16: the three rows had drifted to three different sets). Each
 * chip sends a STABLE English query (Google understands it in any locale, and OfflinePoiStore
 * expands it offline); the label is what localizes.
 */
object QuickCategories {
    data class Chip(val label: Int, val query: String, val icon: ImageVector)

    /** Most-used first, long tail at the end (the rows scroll). Bars is left out while "Hide adult
     *  categories" is on: that filter drops every bar result, so the chip could only come back
     *  empty. Reading [HideAdult.on] here keeps the rows in step when the setting flips. */
    fun all(): List<Chip> = listOfNotNull(
        Chip(R.string.cat_restaurants, "Restaurants", Sym.Restaurant),
        Chip(R.string.cat_coffee, "Coffee", Sym.LocalCafe),
        Chip(R.string.cat_gas, CategoryQuery.fuel(), Sym.LocalGasStation),
        Chip(R.string.cat_groceries, "Groceries", Sym.LocalGroceryStore),
        Chip(R.string.cat_things_to_do, "Things to do", Sym.Attractions),
        Chip(R.string.cat_hotels, "Hotels", Sym.Hotel),
        if (HideAdult.on.value) null else Chip(R.string.cat_bars, "Bars", Sym.LocalBar),
        Chip(R.string.cat_ev, "EV charging station", Sym.EvStation),
        Chip(R.string.cat_parking, "Parking", Sym.LocalParking),
        Chip(R.string.cat_pharmacy, "Pharmacy", Sym.LocalPharmacy),
        Chip(R.string.cat_atms, "ATMs", Sym.LocalAtm),
        Chip(R.string.cat_parks, "Parks", Sym.Park),
        Chip(R.string.cat_hospitals, "Hospitals", Sym.LocalHospital),
        Chip(R.string.cat_banks, "Banks", Sym.AccountBalance),
        Chip(R.string.cat_post_offices, "Post office", Sym.LocalPostOffice),
        Chip(R.string.cat_campgrounds, "Campgrounds", Sym.Cabin),
    )

    /** The same list for a drive in progress: fuel and charging lead, since those are what a
     *  driver stops for; the rest keep their order. */
    fun forDrive(): List<Chip> = all().let { list ->
        val first = setOf(R.string.cat_gas, R.string.cat_ev)
        list.filter { it.label in first } + list.filter { it.label !in first }
    }
}
