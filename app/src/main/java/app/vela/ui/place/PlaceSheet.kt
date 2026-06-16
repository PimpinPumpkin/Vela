package app.vela.ui.place

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import java.util.Locale

@Composable
fun PlaceSheet(
    place: Place,
    route: Route?,
    isSaved: Boolean,
    currentMode: TravelMode,
    onClose: () -> Unit,
    onToggleSave: () -> Unit,
    onModeSelected: (TravelMode) -> Unit,
    onDirections: () -> Unit,
    onStartNav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val text = buildString {
                        append(place.name)
                        place.address?.let { append('\n').append(it) }
                        append("\ngeo:${place.location.lat},${place.location.lng}?q=")
                        append("${place.location.lat},${place.location.lng}(")
                        append(Uri.encode(place.name)).append(')')
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share place",
                            ),
                        )
                    }
                }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isSaved) "Saved" else "Save",
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            val meta = buildList {
                place.rating?.let { r ->
                    add("★ " + String.format(Locale.US, "%.1f", r) + (place.reviewCount?.let { " ($it)" } ?: ""))
                }
                place.priceText?.let { add(it) }
                place.category?.let { add(it) }
                place.openNow?.let { add(if (it) "Open" else "Closed") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString("   ·   "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            place.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            place.website?.let { site ->
                Text(
                    site.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                        },
                )
            }
            if (place.hours.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    place.hours.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (place.address != null) {
                Text(
                    "Hours not listed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TravelMode.DRIVE to "Drive",
                    TravelMode.WALK to "Walk",
                    TravelMode.BICYCLE to "Bike",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { onModeSelected(mode) },
                        label = { Text(label) },
                    )
                }
            }

            route?.let { r ->
                Spacer(Modifier.height(12.dp))
                val eta = formatDuration(r.durationInTrafficSeconds ?: r.durationSeconds)
                val label = "$eta  ·  ${formatDistance(r.distanceMeters)}" +
                    if (r.hasLiveTraffic) "  ·  live traffic" else ""
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (r.hasLiveTraffic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (route == null) {
                    Button(onClick = onDirections, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Directions")
                    }
                } else {
                    Button(onClick = onStartNav, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Start")
                    }
                }
            }
        }
    }
}
