package app.vela.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration

/**
 * The full turn-by-turn step list — shown both while previewing a route and
 * during navigation. Tapping a step asks the map to pan to that maneuver so you
 * can see where you'd turn ([onStep]); [currentStep] is highlighted while
 * navigating, [previewIndex] while previewing.
 */
@Composable
fun StepsSheet(
    maneuvers: List<Maneuver>,
    etaSeconds: Double,
    distanceMeters: Double,
    hasLiveTraffic: Boolean,
    previewIndex: Int?,
    currentStep: Int?,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Steps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        formatDuration(etaSeconds) + "  ·  " + formatDistance(distanceMeters) +
                            if (hasLiveTraffic) "  ·  live traffic" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasLiveTraffic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close steps") }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(maneuvers) { i, m ->
                    val highlighted = i == previewIndex
                    val active = i == currentStep
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (highlighted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { onStep(i) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            maneuverIcon(m.type),
                            contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp).padding(end = 16.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.instruction.ifEmpty { "Continue" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                            m.road?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (m.distanceMeters > 0) {
                            Text(
                                formatDistance(m.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A turn-arrow glyph for each maneuver type (Material "turn_*" symbols). */
fun maneuverIcon(type: ManeuverType): ImageVector = when (type) {
    ManeuverType.DEPART -> Icons.Filled.TripOrigin
    ManeuverType.ARRIVE -> Icons.Filled.Flag
    ManeuverType.TURN_LEFT -> Icons.Filled.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.UTURN -> Icons.Filled.UTurnLeft
    ManeuverType.MERGE -> Icons.Filled.Merge
    ManeuverType.FORK_LEFT -> Icons.Filled.ForkLeft
    ManeuverType.FORK_RIGHT -> Icons.Filled.ForkRight
    ManeuverType.RAMP_LEFT -> Icons.Filled.RampLeft
    ManeuverType.RAMP_RIGHT -> Icons.Filled.RampRight
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> Icons.Filled.RoundaboutLeft
    ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> Icons.Filled.Straight
    ManeuverType.UNKNOWN -> Icons.AutoMirrored.Filled.ArrowForward
}
