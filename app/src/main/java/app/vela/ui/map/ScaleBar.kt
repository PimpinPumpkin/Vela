package app.vela.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.vela.ui.Units

/**
 * A Google-style map scale bar: an open-topped bracket (⊔) sized to a round
 * distance, with the distance label above it. Honours the [Units] metric/
 * imperial preference and reads the live metres-per-pixel from the map.
 *
 * Drawn twice (a 1px offset shadow under the ink) so it stays legible over
 * both light and dark tiles.
 */
@Composable
fun ScaleBar(
    metersPerPixel: Double,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (metersPerPixel <= 0.0 || metersPerPixel.isNaN() || metersPerPixel.isInfinite()) return
    val density = LocalDensity.current
    val imperial = Units.imperial.value
    val maxBarPx = with(density) { 96.dp.toPx() }
    val (meters, label) = remember(metersPerPixel, imperial) {
        niceScale(metersPerPixel * maxBarPx, imperial)
    }
    val barDp = with(density) { (meters / metersPerPixel).toFloat().toDp() }
    val ink = if (dark) Color(0xFFE8EAED) else Color(0xFF3C4043)
    val shadow = if (dark) Color(0xB3000000) else Color(0x80FFFFFF)

    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            color = ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(barDp).padding(bottom = 1.dp),
        )
        Canvas(Modifier.width(barDp).height(7.dp)) {
            val sw = with(density) { 2.dp.toPx() }
            drawBracket(shadow, sw, 0.8f, 0.8f)
            drawBracket(ink, sw, 0f, 0f)
        }
    }
}

/** Bottom rule with up-ticks at both ends, offset by ([dx],[dy]) for the shadow pass. */
private fun DrawScope.drawBracket(color: Color, sw: Float, dx: Float, dy: Float) {
    val w = size.width
    val yb = size.height - sw / 2f + dy
    val yt = sw / 2f + dy
    val xl = sw / 2f + dx
    val xr = w - sw / 2f + dx
    drawLine(color, Offset(xl, yb), Offset(xr, yb), sw, StrokeCap.Round) // baseline
    drawLine(color, Offset(xl, yb), Offset(xl, yt), sw, StrokeCap.Round) // left tick
    drawLine(color, Offset(xr, yb), Offset(xr, yt), sw, StrokeCap.Round) // right tick
}

private val METRIC = doubleArrayOf(
    1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
    1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0, 50_000.0,
    100_000.0, 200_000.0, 500_000.0, 1_000_000.0, 2_000_000.0, 5_000_000.0,
)
private val FEET = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
private val MILES = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)

/** Largest round distance whose bar is no wider than [maxMeters]; returns (metres, label). */
private fun niceScale(maxMeters: Double, imperial: Boolean): Pair<Double, String> {
    if (imperial) {
        val maxFeet = maxMeters * 3.280839895
        if (maxFeet < 5280.0) {
            var pick = FEET[0]
            for (s in FEET) if (s <= maxFeet) pick = s
            return (pick / 3.280839895) to "${pick.toInt()} ft"
        }
        val maxMiles = maxFeet / 5280.0
        var pick = MILES[0]
        for (s in MILES) if (s <= maxMiles) pick = s
        return (pick * 1609.344) to "${pick.toInt()} mi"
    }
    var pick = METRIC[0]
    for (s in METRIC) if (s <= maxMeters) pick = s
    val label = if (pick < 1000.0) "${pick.toInt()} m" else "${(pick / 1000).toInt()} km"
    return pick to label
}
