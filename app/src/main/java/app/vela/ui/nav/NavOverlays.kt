package app.vela.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration

/** Top banner during navigation: distance to the next maneuver + its text. */
@Composable
fun ManeuverBanner(
    text: String,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column {
                Text(
                    formatDistance(distanceMeters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text.ifEmpty { "Continue" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Bottom bar during navigation: remaining time/distance + an End button. */
@Composable
fun NavControls(
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    offRoute: Boolean,
    onStop: () -> Unit,
    onSteps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    formatDuration(remainingSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    formatDistance(remainingDistanceMeters) + if (offRoute) " · rerouting…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onSteps) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Steps")
                }
                Button(onClick = onStop) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("End")
                }
            }
        }
    }
}
