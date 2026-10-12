package app.vela.ui

import app.vela.ui.icons.Sym

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import app.vela.R

/**
 * The one list of quick-category chips. The map's chip row, the route chooser's "search along
 * route", the in-nav search, the place sheet and the car's lists all show these, in the same
 * order, so a chip means the same thing wherever it appears (2026-09-16: the three rows had
 * drifted to three different sets). Each chip sends a STABLE English query (Google understands
 * it in any locale, and OfflinePoiStore expands it offline); the label is what localizes.
 *
 * Which chips show and in what order is the user's (Settings > Search > Quick search buttons,
 * [QuickChipsPref]). [catalog] is every chip, [arranged] the catalog in the user's order and
 * [all] what the rows show.
 */
object QuickCategories {
    /** [key] names the chip in the saved order and hidden set: the query, except for the fuel
     *  chip, whose query says "Petrol station" in some regions and would not match its own
     *  saved key after a language change. */
    data class Chip(val label: Int, val query: String, val icon: ImageVector, val key: String = query)

    const val FUEL_KEY = "Gas station"

    /** Every chip, most-used first, long tail at the end. Bars is left out while "Hide adult
     *  categories" is on: that filter drops every bar result, so the chip could only come back
     *  empty. Reading [HideAdult.on] here keeps the rows in step when the setting flips. */
    fun catalog(): List<Chip> = listOfNotNull(
        Chip(R.string.cat_restaurants, "Restaurants", Sym.Restaurant),
        Chip(R.string.cat_coffee, "Coffee", Sym.LocalCafe),
        Chip(R.string.cat_gas, CategoryQuery.fuel(), Sym.LocalGasStation, key = FUEL_KEY),
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

    /** The catalog in the user's order, hidden chips included: the settings page and the map
     *  row's More menu. */
    fun arranged(): List<Chip> {
        val chips = catalog()
        val byKey = chips.associateBy { it.key }
        return QuickChipOrder.arrange(chips.map { it.key }, QuickChipsPref.order.value).mapNotNull { byKey[it] }
    }

    /** What the rows show: [arranged] minus the chips the user switched off. */
    fun all(): List<Chip> {
        val hidden = QuickChipsPref.hidden.value
        return arranged().filter { it.key !in hidden }
    }

    /** The same list for a drive in progress: fuel and charging lead, since those are what a
     *  driver stops for; the rest keep their order. */
    fun forDrive(): List<Chip> = all().let { list ->
        val first = setOf(R.string.cat_gas, R.string.cat_ev)
        list.filter { it.label in first } + list.filter { it.label !in first }
    }

    /** Moves the chip [key] by [delta] rows in the user's order (the settings page's handle). */
    fun move(context: Context, key: String, delta: Int) {
        val keys = arranged().map { it.key }
        val next = QuickChipOrder.moved(keys, key, delta)
        if (next != keys) QuickChipsPref.setOrder(context, next)
    }
}
