package app.vela.ui.place

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import app.vela.R
import app.vela.core.data.TollCharge
import app.vela.core.data.Tolls
import app.vela.core.model.Route
import app.vela.data.TollFile
import app.vela.ui.TollTransponder
import app.vela.ui.formatCad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Toll about $18.20 to $26.40 + $5.30 fee" for a route on a road in the toll file ([Tolls]), or
 * one price when the range is a few cents, the fee left out when the setting says there is a
 * transponder; else null. Worked out off the main
 * thread once per route and toll file.
 */
@Composable
fun routeTollText(r: Route): String? {
    val charges by produceState<List<TollCharge>>(emptyList(), System.identityHashCode(r.polyline), TollFile.generation.intValue) {
        value = withContext(Dispatchers.Default) {
            Tolls.charges(r.polyline, System.currentTimeMillis(), r.durationInTrafficSeconds ?: r.durationSeconds)
        }
    }
    if (charges.isEmpty()) return null
    val base = charges.sumOf { it.cents }
    val top = charges.sumOf { it.maxCents }
    val fee = if (TollTransponder.on.value) 0 else charges.sumOf { it.feeCents }
    // A range of a few cents, as at night, reads as one price.
    val range = top - base > maxOf(25, base / 20)
    return when {
        range && fee > 0 -> stringResource(R.string.route_toll_range_fee, formatCad(base), formatCad(top), formatCad(fee))
        range -> stringResource(R.string.route_toll_range, formatCad(base), formatCad(top))
        fee > 0 -> stringResource(R.string.route_toll_about_fee, formatCad(top), formatCad(fee))
        else -> stringResource(R.string.route_toll_about, formatCad(top))
    }
}
