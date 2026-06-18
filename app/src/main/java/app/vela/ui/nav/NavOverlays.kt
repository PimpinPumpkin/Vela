package app.vela.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.core.model.ManeuverType
import app.vela.ui.formatArrivalClock
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration

/**
 * Top banner during navigation, styled like Google's: a large directional turn
 * arrow for [type], the distance to the maneuver, the instruction with any
 * **highway/exit shields** pulled out of the text, a **lane-guidance** strip
 * (from [laneHint]), and a compact "then <icon>" preview of the maneuver after
 * this one ([nextText]/[nextType]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManeuverBanner(
    text: String,
    distanceMeters: Double,
    type: ManeuverType = ManeuverType.STRAIGHT,
    laneHint: String? = null,
    nextText: String? = null,
    nextType: ManeuverType? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(maneuverIcon(type), contentDescription = null, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatDistance(distanceMeters),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val signs = roadSigns(text)
                    if (signs.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
                        ) { signs.forEach { SignChip(it) } }
                    }
                    Text(text.ifEmpty { "Continue" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            laneHint?.let {
                Spacer(Modifier.height(10.dp))
                LaneGuide(it, type)
            }
            if (nextText != null && nextType != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "then",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(maneuverIcon(nextType), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(nextText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val EXIT_RE = Regex("""\bexit\s+(\w[\w-]*)""", RegexOption.IGNORE_CASE)
private val ROUTE_RE = Regex("""\b(?:I|US|CA|SR|US-?Hwy|Hwy)[-\s]?\d+(?:\s?[NSEW]\b)?""", RegexOption.IGNORE_CASE)

/** A highway shield or exit tab extracted from an instruction. */
internal data class Sign(val isExit: Boolean, val label: String)

/** Pull route shields ("I-80 E") and the exit tab ("Exit 71") out of an
 *  instruction so they can be rendered as Google-style badges. */
internal fun roadSigns(text: String): List<Sign> {
    val seen = HashSet<String>()
    val out = ArrayList<Sign>()
    EXIT_RE.find(text)?.let {
        val label = "Exit ${it.groupValues[1]}"
        if (seen.add(label.lowercase())) out.add(Sign(isExit = true, label = label))
    }
    ROUTE_RE.findAll(text).forEach { m ->
        val label = m.value.trim().replace(Regex("\\s+"), " ").uppercase()
        if (seen.add(label.lowercase())) out.add(Sign(isExit = false, label = label))
    }
    return out.take(3)
}

@Composable
internal fun SignChip(sign: Sign) {
    if (sign.isExit) {
        Surface(color = Color(0xFF1E7E34), shape = RoundedCornerShape(4.dp)) {
            Text(
                sign.label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    } else {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onPrimaryContainer),
        ) {
            Text(
                sign.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Lane-guidance strip from a hint like "Use the left 2 lanes": a row of
 *  turn-direction arrows for the lanes you want, plus the hint text. We don't
 *  get a per-lane diagram from Google's response, so this shows the count and
 *  direction rather than faking the full lane layout. */
@Composable
private fun LaneGuide(hint: String, type: ManeuverType) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(laneArrowCount(hint)) {
                    Icon(maneuverIcon(type), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun laneArrowCount(hint: String): Int {
    val n = Regex("\\d+").find(hint)?.value?.toIntOrNull()
    return (n ?: if (hint.contains("any", ignoreCase = true)) 2 else 1).coerceIn(1, 3)
}

/** Bottom bar during navigation: remaining time/distance + an End button. */
@Composable
fun NavControls(
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    offRoute: Boolean,
    onStop: () -> Unit,
    onSteps: () -> Unit,
    voiceMuted: Boolean = false,
    onToggleVoice: () -> Unit = {},
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
                    formatDistance(remainingDistanceMeters) +
                        " · " + formatArrivalClock(remainingSeconds) +
                        if (offRoute) " · rerouting…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = onToggleVoice) {
                    Icon(
                        if (voiceMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (voiceMuted) "Unmute voice guidance" else "Mute voice guidance",
                    )
                }
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

/** Arrival/trip summary shown when nav reaches the destination: a "you've
 *  arrived" card with the trip's total time and distance, and a Done button to
 *  return to the map. */
@Composable
fun ArrivalSummary(
    destinationLabel: String,
    tripSeconds: Double,
    tripDistanceMeters: Double,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text("You've arrived", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (destinationLabel.isNotBlank()) {
                        Text(destinationLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text("Trip time", style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatDuration(tripSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column {
                    Text("Distance", style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatDistance(tripDistanceMeters),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
