package app.vela.ui.place

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.model.BikeShareStatus
import app.vela.ui.icons.Sym
import kotlinx.coroutines.delay

/**
 * A bike-share station's counts on its sheet (SPEC 8, Bike-share stations): "7 bikes · 16 free
 * docks", bold, then how old the counts are. Below the action pills like the fuel price, so a
 * refresh moves nothing above them. The age ticks on its own, so a sheet left open while the
 * refresh cannot run still tells how old its numbers are.
 */
@Composable
internal fun BikeShareLine(s: BikeShareStatus, ink: Color, dim: Color) {
    val now by produceState(System.currentTimeMillis(), s.atMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val counts = when {
        !s.renting && !s.returning -> stringResource(R.string.place_bike_share_closed)
        else -> listOfNotNull(
            if (s.renting) pluralStringResource(R.plurals.place_bike_share_bikes, s.bikes, s.bikes)
            else stringResource(R.string.place_bike_share_no_rentals),
            if (!s.returning) stringResource(R.string.place_bike_share_no_returns)
            else s.docks?.let { d -> pluralStringResource(R.plurals.place_bike_share_docks, d, d) },
        ).joinToString("  ·  ")
    }
    val minutes = ((now - s.atMs) / 60_000L).toInt().coerceAtLeast(0)
    val age = if (minutes == 0) stringResource(R.string.place_bike_share_updated_now)
        else pluralStringResource(R.plurals.place_bike_share_updated_min, minutes, minutes)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.DirectionsBike, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink)) { append(counts) }
                withStyle(SpanStyle(color = dim)) { append("  ·  $age") }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
