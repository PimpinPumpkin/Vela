package app.vela.ui.place

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.vela.R
import app.vela.core.model.Place
import app.vela.ui.SheetPalette
import app.vela.ui.dpadHighlight
import app.vela.ui.rememberDpadMode
import app.vela.ui.theme.isAppInDarkTheme

// One row per entry, fixed height so the drag maths is simple integer swaps.
private val ROW_HEIGHT = 56.dp

/**
 * The dedicated stops editor (Google-style): origin at the top, each intermediate stop as a
 * draggable row (grab the handle, drag past a neighbour's midpoint to swap), the destination
 * pinned at the bottom, and Add stop / Done under the list. Edits are LOCAL until Done — one
 * reroute per visit, not one per micro-change; back / X discards. Under D-pad (no drag) each
 * row shows up/down arrows instead (docs/dpad.md: everything must stay key-operable).
 */
@Composable
fun StopsEditorSheet(
    originName: String,
    originIsMe: Boolean = true,
    destinationName: String,
    stops: List<Place>,
    onApply: (List<Place>) -> Unit,
    onAddStop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val ink = SheetPalette.ink(dark)
    val dim = SheetPalette.dim(dark)
    val dpadMode = rememberDpadMode()
    var order by remember(stops) { mutableStateOf(stops) }
    // Index of the row being dragged (-1 = none) and its finger offset within the gesture.
    var dragIdx by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    fun swap(a: Int, b: Int) {
        val list = order.toMutableList()
        val t = list[a]; list[a] = list[b]; list[b] = t
        order = list
    }

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark), contentColor = ink),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stops_editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.mapscreen_cancel), tint = dim)
                }
            }
            // Origin — fixed (the trip starts where it starts; edit it from the panel's From row).
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                // Same glyph grammar as the panel: PIN = where you start (LOCATION-BLUE when it's
                // your current position — the non-verbal "this is you"), checkered FLAG = finish.
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = if (originIsMe) MaterialTheme.colorScheme.primary else dim,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(originName, style = MaterialTheme.typography.bodyLarge, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            order.forEachIndexed { i, p ->
                key(p.id) {
                    // The row must read its CURRENT index inside the drag callbacks — the gesture
                    // outlives recompositions while swaps shuffle the list underneath it.
                    val idx by rememberUpdatedState(i)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .then(
                                if (i == dragIdx) {
                                    Modifier
                                        .zIndex(1f)
                                        .graphicsLayer { translationY = dragDy }
                                        .background(SheetPalette.row(dark), RoundedCornerShape(8.dp))
                                } else Modifier,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.stops_drag_reorder),
                            tint = dim,
                            modifier = Modifier
                                .size(24.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { dragIdx = idx; dragDy = 0f },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragDy += delta.y
                                            // Past a neighbour's midpoint → swap and carry the
                                            // remainder, so a long drag walks multiple rows.
                                            while (dragDy > rowHeightPx / 2 && dragIdx < order.lastIndex) {
                                                swap(dragIdx, dragIdx + 1); dragIdx += 1; dragDy -= rowHeightPx
                                            }
                                            while (dragDy < -rowHeightPx / 2 && dragIdx > 0) {
                                                swap(dragIdx, dragIdx - 1); dragIdx -= 1; dragDy += rowHeightPx
                                            }
                                        },
                                        onDragEnd = { dragIdx = -1; dragDy = 0f },
                                        onDragCancel = { dragIdx = -1; dragDy = 0f },
                                    )
                                },
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // D-pad fallback: a drag handle has no key equivalent, so key-driven UIs
                        // get explicit up/down (hidden under touch to keep the rows clean).
                        if (dpadMode && order.size > 1) {
                            if (i > 0) IconButton(onClick = { swap(i, i - 1) }, modifier = Modifier.size(36.dp).dpadHighlight(RoundedCornerShape(6.dp))) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.place_move_stop_up), tint = dim, modifier = Modifier.size(20.dp))
                            }
                            if (i < order.lastIndex) IconButton(onClick = { swap(i, i + 1) }, modifier = Modifier.size(36.dp).dpadHighlight(RoundedCornerShape(6.dp))) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.place_move_stop_down), tint = dim, modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(
                            onClick = { order = order.filterIndexed { j, _ -> j != i } },
                            modifier = Modifier.size(36.dp).dpadHighlight(RoundedCornerShape(6.dp)),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.place_remove_stop), tint = dim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            // Destination — pinned last.
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsScore, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Text(
                    destinationName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .dpadHighlight(RoundedCornerShape(8.dp))
                    .clickable { onAddStop() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.place_add_stop), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { onApply(order) }) {
                    Text(stringResource(R.string.nav_done))
                }
            }
        }
    }
}
