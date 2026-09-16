package app.vela.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
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

    fun all(): List<Chip> = listOf(
        Chip(R.string.cat_restaurants, "Restaurants", Icons.Default.Restaurant),
        Chip(R.string.cat_coffee, "Coffee", Icons.Default.LocalCafe),
        Chip(R.string.cat_gas, CategoryQuery.fuel(), Icons.Default.LocalGasStation),
        Chip(R.string.cat_ev, "EV charging station", Icons.Default.EvStation),
        Chip(R.string.cat_groceries, "Groceries", Icons.Default.LocalGroceryStore),
        Chip(R.string.cat_hotels, "Hotels", Icons.Default.Hotel),
        Chip(R.string.cat_pharmacy, "Pharmacy", Icons.Default.LocalPharmacy),
        Chip(R.string.cat_atms, "ATMs", Icons.Default.LocalAtm),
        Chip(R.string.cat_parks, "Parks", Icons.Default.Park),
    )
}
