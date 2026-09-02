package app.vela.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import app.vela.core.nav.RouteBar

/**
 * The route bar (issue #228): a thin vertical strip showing the road AHEAD - you at the bottom,
 * the destination at the top, congestion painted along it and the road furniture ahead marked on
 * it.
 *
 * TomTom's original also carries live hazards; Vela's deliberately does not, because every keyless
 * live-incident source is a proven dead end. What it draws is exactly what the map already knows,
 * so the bar can never imply knowledge the app does not have.
 *
 * Sized and positioned by the caller. Everything here is a pure function of [model] - no state, no
 * animation - because it sits on the nav screen where a per-frame recomposition is expensive.
 */
@Composable
fun RouteBarStrip(model: RouteBar.Model, modifier: Modifier = Modifier) {
    if (model.isEmpty) return
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .width(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(track),
    ) {
        // Bottom-anchored fractions: 0 is you, 1 is the destination, which is how the strip reads
        // when it stands beside a map with the puck low on the screen.
        Layout(content = {
            for (b in model.bands) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(congestionColor(b.level))
                        .layoutId(b.from, b.to),
                )
            }
            for (p in model.pins) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(pinColor(p.kind))
                        .layoutId(p.at, p.at),
                )
            }
        }) { measurables, constraints ->
            val h = constraints.maxHeight
            val placeables = measurables.map { m ->
                val (from, to) = m.parentData as? Pair<*, *> ?: (0.0 to 0.0)
                val f = (from as? Double) ?: 0.0
                val t = (to as? Double) ?: 0.0
                val bandH = ((t - f) * h).toInt()
                m.measure(
                    constraints.copy(
                        minWidth = constraints.maxWidth,
                        maxWidth = constraints.maxWidth,
                        minHeight = 0,
                        maxHeight = if (bandH > 0) bandH else constraints.maxHeight,
                    ),
                ) to f
            }
            layout(constraints.maxWidth, h) {
                placeables.forEach { (p, f) ->
                    // y grows downward, so a fraction near 1 (the destination) sits at the TOP.
                    val y = (h - (f * h).toInt() - p.height).coerceIn(0, (h - p.height).coerceAtLeast(0))
                    p.place(0, y)
                }
            }
        }
    }
}

/** Same grading the route line and the ETA colour use, so the bar agrees with the map. */
private fun congestionColor(level: Int): Color = when {
    level >= 3 -> Color(0xFF9C1C1C) // severe
    level == 2 -> Color(0xFFD93025) // heavy
    else -> Color(0xFFF9AB00)       // moderate
}

private fun pinColor(kind: RouteBar.Mark): Color = when (kind) {
    RouteBar.Mark.CAMERA -> Color(0xFF8E24AA)        // the purple the camera badge uses
    RouteBar.Mark.RAIL_CROSSING -> Color(0xFF37474F)
    RouteBar.Mark.SPEED_HUMP -> Color(0xFFFFA000)
    RouteBar.Mark.STOP -> Color(0xFFD32F2F)
    RouteBar.Mark.SIGNAL -> Color(0xFF388E3C)
}

/** Carries a child's (from, to) fractions through measurement. */
private fun Modifier.layoutId(from: Double, to: Double) =
    this.then(RouteBarParentData(from to to))

private data class RouteBarParentData(val span: Pair<Double, Double>) :
    androidx.compose.ui.layout.ParentDataModifier, Modifier.Element {
    override fun androidx.compose.ui.unit.Density.modifyParentData(parentData: Any?) = span
}
