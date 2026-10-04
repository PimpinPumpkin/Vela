package app.vela.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.util.ShapeMeasure
import app.vela.ui.dpadHighlight
import app.vela.ui.formatArea
import app.vela.ui.formatDistance
import app.vela.ui.icons.Sym

/** A shape being drawn on the map: taps add its points. [pts] is lat, lng, lat, lng... */
data class DrawState(val pts: List<Double> = emptyList(), val closed: Boolean = false, val color: Long = DRAW_COLORS[0])

/** The colors offered while drawing (ARGB): red, orange, yellow, green, blue, purple. */
val DRAW_COLORS = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA)

/**
 * The drawing bar (issue #669, the owner's "draw over the map and save it"): while it is up every
 * map tap adds a point. Line or area, a color, what the shape measures so far, an optional name,
 * then Undo / Cancel / Save. Saved shapes go to the "My drawings" list and are drawn like an
 * imported custom map's.
 */
@Composable
fun BoxScope.DrawBar(draw: DrawState, vm: MapViewModel) {
    var name by remember { mutableStateOf("") }
    val points = draw.pts.size / 2
    val enough = points >= if (draw.closed) 3 else 2
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !draw.closed, onClick = { vm.drawSetClosed(false) }, label = { Text(stringResource(R.string.shape_line)) }, shape = CircleShape, modifier = Modifier.dpadHighlight(CircleShape))
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = draw.closed, onClick = { vm.drawSetClosed(true) }, label = { Text(stringResource(R.string.shape_area)) }, shape = CircleShape, modifier = Modifier.dpadHighlight(CircleShape))
                Spacer(Modifier.weight(1f))
                DRAW_COLORS.forEach { c ->
                    Box(
                        Modifier.padding(start = 6.dp).size(26.dp).background(Color(c), CircleShape)
                            .border(if (c == draw.color) 3.dp else 1.dp, if (c == draw.color) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .dpadHighlight(CircleShape).clickable { vm.drawSetColor(c) },
                    )
                }
            }
            Text(
                when {
                    points == 0 -> stringResource(R.string.draw_hint)
                    draw.closed && points >= 3 -> stringResource(R.string.shape_area_measure, formatArea(ShapeMeasure.areaM2(draw.pts)), formatDistance(ShapeMeasure.lengthM(draw.pts, closed = true)))
                    points >= 2 -> stringResource(R.string.shape_line_measure, formatDistance(ShapeMeasure.lengthM(draw.pts)))
                    else -> stringResource(R.string.draw_hint_more)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.draw_name_hint)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::drawUndo, enabled = points > 0, modifier = Modifier.dpadHighlight(CircleShape)) {
                    Icon(Sym.Undo, contentDescription = stringResource(R.string.draw_undo))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::cancelDrawing, modifier = Modifier.dpadHighlight(CircleShape)) { Text(stringResource(R.string.draw_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.saveDrawing(name) }, enabled = enough, shape = CircleShape, modifier = Modifier.dpadHighlight(CircleShape)) {
                    Text(stringResource(R.string.draw_save))
                }
            }
        }
    }
}
