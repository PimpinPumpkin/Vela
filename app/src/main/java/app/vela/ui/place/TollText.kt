package app.vela.ui.place

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.vela.R
import app.vela.core.data.Tolls
import app.vela.core.model.Route
import app.vela.ui.TollTransponder
import app.vela.ui.formatCad

/**
 * "Toll about $4.40" for a route over one of Quebec's two toll bridges, "Toll about $18.20 to $26.40"
 * for a trip on Ontario's Highway 407, with the fee a vehicle without a transponder is billed unless
 * the setting says there is one, else null. The tolls are matched to the route's line once per
 * route, not on every redraw.
 */
@Composable
fun routeTollText(r: Route): String? {
    val charges = remember(System.identityHashCode(r.polyline)) {
        Tolls.charges(r.polyline, System.currentTimeMillis(), r.durationInTrafficSeconds ?: r.durationSeconds)
    }
    if (charges.isEmpty()) return null
    val base = charges.sumOf { it.cents }
    val top = charges.sumOf { it.maxCents }
    val fee = if (TollTransponder.on.value) 0 else charges.sumOf { it.feeCents }
    return when {
        top > base && fee > 0 -> stringResource(R.string.route_toll_range_fee, formatCad(base), formatCad(top), formatCad(fee))
        top > base -> stringResource(R.string.route_toll_range, formatCad(base), formatCad(top))
        fee > 0 -> stringResource(R.string.route_toll_about_fee, formatCad(base), formatCad(fee))
        else -> stringResource(R.string.route_toll_about, formatCad(base))
    }
}
