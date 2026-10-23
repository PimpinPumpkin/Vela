package app.vela.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vela.ui.MapKeys
import kotlin.math.roundToInt

/**
 * The select key's crosshair (docs/dpad.md, Map keys): drawn while a select key is set, at the
 * point the key taps, the middle of the visible map ([MapCover.center]). It also reports the
 * map's own bounds to [MapCover], so the key has nothing to tap while this is not composed.
 * Hidden in Choose on map, which draws its own pin there, and while nothing of the map is visible.
 */
@Composable
internal fun BoxScope.MapKeysCrosshair(state: MapUiState) {
    if (MapKeys.code(MapKeys.Action.SELECT) == 0) return
    DisposableEffect(Unit) { onDispose { MapCover.map.value = null } }
    Box(
        Modifier.matchParentSize().onGloballyPositioned {
            val b = MapCover.bounds(it)
            if (!b.contentEquals(MapCover.map.value)) MapCover.map.value = b
        },
    )
    if (state.pickOnMap != null) return
    // Read here, not in the placement below: the bounds arrive after the first placement, and a
    // placement read alone was not run again when they did, which left the mark in the corner.
    val m = MapCover.map.value
    val crossColor = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            // Placed in the layout phase: a sheet drag moves the point without recomposing.
            .offset {
                val c = MapCover.centerIn(m)
                if (m == null || c == null) IntOffset.Zero
                else IntOffset((c.first - m[0]).roundToInt(), (c.second - m[1]).roundToInt()) -
                    IntOffset(18.dp.roundToPx(), 18.dp.roundToPx())
            }
            .size(36.dp),
    ) {
        if (MapCover.centerIn(m) == null) return@Canvas
        // The same mark as the engaged map target's crosshair in MapScreen.
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(Color.White, radius = size.minDimension * 0.30f, style = Stroke(7f))
        drawCircle(crossColor, radius = size.minDimension * 0.30f, style = Stroke(3.5f))
        drawLine(crossColor, Offset(cx, 0f), Offset(cx, cy - 8f), 3.5f)
        drawLine(crossColor, Offset(cx, cy + 8f), Offset(cx, size.height), 3.5f)
        drawLine(crossColor, Offset(0f, cy), Offset(cx - 8f, cy), 3.5f)
        drawLine(crossColor, Offset(cx + 8f, cy), Offset(size.width, cy), 3.5f)
    }
}
