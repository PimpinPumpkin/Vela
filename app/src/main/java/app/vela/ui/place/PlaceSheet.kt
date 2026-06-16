package app.vela.ui.place

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.ui.RatingStars
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.placeStatusColor
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    onSteps: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 110.dp.toPx() }
    Card(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .pointerInput(Unit) {
                // Swipe the sheet down past a threshold to dismiss; otherwise snap back.
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        if (offsetY.value > dismissPx) onClose()
                        else scope.launch { offsetY.animateTo(0f) }
                    },
                )
            },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 10.dp)) {
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                ShareButton(place)
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isSaved) "Saved" else "Save",
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                place.rating?.let { r ->
                    Text(
                        String.format(Locale.US, "%.1f", r),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    RatingStars(r, modifier = Modifier.padding(horizontal = 4.dp))
                    place.reviewCount?.let {
                        Text(
                            "($it)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val rest = listOfNotNull(place.priceText, place.category)
                if (rest.isNotEmpty()) {
                    Text(
                        (if (place.rating != null) "   ·   " else "") + rest.joinToString("   ·   "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            place.statusText?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = placeStatusColor(status),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            place.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }

            if (place.phone != null || place.website != null) {
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    place.phone?.let { ph ->
                        FilledTonalButton(onClick = {
                            val dialable = "tel:" + ph.filter { it.isDigit() || it == '+' }
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(dialable))) }
                        }) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Call")
                        }
                    }
                    place.website?.let { site ->
                        FilledTonalButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                        }) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Website")
                        }
                    }
                }
            }

            if (place.hours.isNotEmpty()) {
                HoursSection(place.hours)
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
                    onSteps?.let {
                        OutlinedButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Steps")
                        }
                    }
                }
            }
        }
    }
}

/** Share icon that opens a small menu: a Google Maps link, raw coordinates, or
 *  just the address. */
@Composable
private fun ShareButton(place: Place) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val lat = place.location.lat
    val lng = place.location.lng

    fun share(text: String) {
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
        open = false
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Share, contentDescription = "Share") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Google Maps link") },
                onClick = { share("${place.name}\nhttps://www.google.com/maps/search/?api=1&query=$lat%2C$lng") },
            )
            DropdownMenuItem(
                text = { Text("Coordinates") },
                onClick = { share("$lat, $lng") },
            )
            place.address?.let { addr ->
                DropdownMenuItem(
                    text = { Text("Address") },
                    onClick = { share("${place.name}\n$addr") },
                )
            }
        }
    }
}

/** Collapsible weekly hours. Collapsed shows today's range; expanded lists the
 *  week with today in bold. [hours] entries are "Day: range" starting today. */
@Composable
private fun HoursSection(hours: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val days = remember(hours) {
        hours.map {
            val i = it.indexOf(": ")
            if (i < 0) listOf(it, "") else listOf(it.substring(0, i), it.substring(i + 2))
        }
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Hours",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (!expanded) {
                days.firstOrNull()?.let {
                    Text(it[1], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse hours" else "Expand hours",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 26.dp, top = 2.dp, bottom = 2.dp)) {
                days.forEachIndexed { i, dt ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            dt[0],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            dt[1],
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
