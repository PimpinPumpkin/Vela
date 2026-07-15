package app.vela.ui.place

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.dpadHighlight

// The map result pins' red (PoiIcons.RESULT_RED) - the destination pin on this card is the same
// species as the pin the route ends at on the map, so the two must stay the same ink.
private val DestinationRed = Color(0xFFDB4437)

// One endpoint row's height; the connector dots between rows key off it too.
private val ENDPOINT_ROW = 44.dp
private val GLYPH_RAIL = 26.dp

/**
 * Google's directions header: while the route chooser is open the search bar swaps for this card,
 * with the origin row, stops, and destination row down a glyph rail (origin ring, connector dots,
 * red pin), a back arrow on the left and swap on the right. The rows moved OUT of the bottom
 * chooser (which keeps mode chips / leave-now / routes / Start), so the endpoints stay visible and
 * editable even while the chooser is collapsed to its Start bar, and the whole thing reads like
 * gmaps on a small screen. Every control is a D-pad focus stop with a ring (docs/dpad.md).
 */
@Composable
fun RouteTopCard(
    originName: String,
    originIsMe: Boolean,
    destinationName: String,
    stops: List<String> = emptyList(),
    showStopControls: Boolean = true, // false on transit: no waypoints there
    onEditOrigin: (() -> Unit)? = null,
    onEditDestination: (() -> Unit)? = null,
    onEditStops: () -> Unit = {},
    onAddStop: (() -> Unit)? = null,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // Same tone as the search bar it replaces, so the top chrome reads as one family.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.place_close_directions),
                    tint = dim,
                )
            }
            Column(Modifier.weight(1f)) {
                EndpointRow(
                    text = originName,
                    textColor = if (onEditOrigin != null) MaterialTheme.colorScheme.primary else ink,
                    editable = onEditOrigin != null,
                    editLabel = stringResource(R.string.place_change_start),
                    onClick = onEditOrigin,
                ) {
                    // Origin = a ring, teal when it's literally you (the app's "this is me" ink;
                    // gmaps uses its location blue the same way).
                    Box(
                        Modifier
                            .size(12.dp)
                            .border(2.dp, if (originIsMe) MaterialTheme.colorScheme.primary else dim, CircleShape),
                    )
                }
                ConnectorRow(dim)
                if (stops.isNotEmpty() && showStopControls) {
                    EndpointRow(
                        text = stops.first(),
                        textColor = ink,
                        editable = true,
                        editLabel = stringResource(R.string.stops_edit),
                        onClick = onEditStops,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dim))
                    }
                    // Extra stops read as their own quiet line under the first (the old inline
                    // "+N" was easy to miss, user 2026-07-14) - tappable with a pencil, a second
                    // door into the stops editor (user 2026-07-14).
                    if (stops.size > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .dpadHighlight(RoundedCornerShape(8.dp))
                                .clickable { onEditStops() },
                        ) {
                            Spacer(Modifier.width(GLYPH_RAIL + 8.dp))
                            Text(
                                pluralStringResource(R.plurals.topcard_more_stops, stops.size - 1, stops.size - 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = dim,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.stops_edit),
                                tint = dim,
                                modifier = Modifier.size(12.dp).padding(end = 0.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    ConnectorRow(dim)
                }
                EndpointRow(
                    text = destinationName,
                    textColor = if (onEditDestination != null) MaterialTheme.colorScheme.primary else ink,
                    bold = true,
                    editable = onEditDestination != null,
                    editLabel = stringResource(R.string.place_change_destination),
                    onClick = onEditDestination,
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = DestinationRed, modifier = Modifier.size(20.dp))
                }
                // Add stop keeps its own quiet row (gmaps buries it in an overflow menu; a
                // visible row is the discoverable version and the card has the room).
                if (showStopControls && onAddStop != null && stops.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .dpadHighlight(RoundedCornerShape(8.dp))
                            .clickable { onAddStop() }
                            .padding(vertical = 4.dp),
                    ) {
                        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = dim, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.place_add_stop), style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onSwap, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = stringResource(R.string.place_swap_start_destination),
                        tint = dim,
                    )
                }
                // With stops in play the labeled Add-stop row is gone (the stops summary row took
                // its slot), so adding ANOTHER stop gets this compact + under the swap.
                if (showStopControls && onAddStop != null && stops.isNotEmpty()) {
                    IconButton(onClick = onAddStop, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.place_add_stop),
                            tint = dim,
                        )
                    }
                }
            }
        }
    }
}

/** One endpoint line: fixed glyph rail + the name + a pencil when tappable, gmaps' row grammar. */
@Composable
private fun EndpointRow(
    text: String,
    textColor: Color,
    editable: Boolean,
    editLabel: String,
    onClick: (() -> Unit)?,
    bold: Boolean = false,
    glyph: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ENDPOINT_ROW)
            .then(
                if (onClick != null) {
                    Modifier.clip(RoundedCornerShape(10.dp)).dpadHighlight(RoundedCornerShape(10.dp)).clickable { onClick() }
                } else Modifier,
            ),
    ) {
        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (editable) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Edit, contentDescription = editLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** The dots between glyphs plus a hairline under the text side - gmaps' rail connector. */
@Composable
private fun ConnectorRow(dim: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.width(GLYPH_RAIL), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(2) { Box(Modifier.size(2.5.dp).clip(CircleShape).background(dim.copy(alpha = 0.7f))) }
            }
        }
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = dim.copy(alpha = 0.18f))
    }
}
