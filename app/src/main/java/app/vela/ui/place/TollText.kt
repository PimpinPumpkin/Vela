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
 * transponder; else null. Priced for leaving now, or at the time picked with "Depart at", or the
 * trip's time before the one picked with "Arrive by" ([DepartPick]). Worked out off the main
 * thread once per route, toll file and picked time.
 */
@Composable
fun routeTollText(r: Route): String? {
    val durationS = r.durationInTrafficSeconds ?: r.durationSeconds
    val leaveMs = DepartPick.timed.value?.let { (mode, epochS) -> (if (mode == 2) epochS - durationS.toLong() else epochS) * 1000L }
    val charges by produceState<List<TollCharge>>(emptyList(), System.identityHashCode(r.polyline), TollFile.generation.intValue, leaveMs) {
        value = withContext(Dispatchers.Default) {
            Tolls.charges(r.polyline, maxOf(leaveMs ?: 0L, System.currentTimeMillis()), durationS)
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
