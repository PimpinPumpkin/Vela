package app.vela.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vela.core.nav.ShieldType
import app.vela.core.nav.parseRouteRef

private val InterstateBlue = Color(0xFF003F87)
private val InterstateRed = Color(0xFFB01E2E)
private val ShieldInk = Color(0xFF111111)

/** Google-style route shield for a sign label ("I-80 E", "US 50", "CA-99"). Real interstate and
 * US-route silhouettes; a neutral white marker for state/provincial routes; the plain bordered
 * chip as the fallback. Network is inferred from the ref ([parseRouteRef]) - no OSM lookup. The
 * shields use fixed real-signage colours (same in light/dark); [ink]/[dim] colour the generic
 * chip + the trailing cardinal so those blend with the banner. */
@Composable
internal fun RouteShield(label: String, ink: Color, dim: Color) {
    val ref = parseRouteRef(label)
    when (ref.type) {
        ShieldType.INTERSTATE -> ShieldBadge(
            ref.number, ref.direction, dim,
            fill = InterstateBlue, redTop = true, numberColor = Color.White, stroke = null,
        )
        ShieldType.US_ROUTE -> ShieldBadge(
            ref.number, ref.direction, dim,
            fill = Color.White, redTop = false, numberColor = ShieldInk, stroke = ShieldInk,
        )
        ShieldType.STATE -> StateMarker(ref.number, ref.direction, dim)
        ShieldType.GENERIC -> GenericChip(ref.raw, ink)
    }
}

/** A US-shield/badge silhouette: rounded flat top, straight shoulders, curving to a soft point. */
private fun shieldPath(w: Float, h: Float): Path = Path().apply {
    val cr = w * 0.16f
    moveTo(cr, 0f)
    lineTo(w - cr, 0f)
    quadraticBezierTo(w, 0f, w, cr)
    lineTo(w, h * 0.42f)
    cubicTo(w, h * 0.66f, w * 0.72f, h * 0.86f, w * 0.5f, h)
    cubicTo(w * 0.28f, h * 0.86f, 0f, h * 0.66f, 0f, h * 0.42f)
    lineTo(0f, cr)
    quadraticBezierTo(0f, 0f, cr, 0f)
    close()
}

@Composable
private fun ShieldBadge(
    number: String,
    direction: String?,
    dim: Color,
    fill: Color,
    redTop: Boolean,
    numberColor: Color,
    stroke: Color?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 36.dp, height = 30.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val p = shieldPath(size.width, size.height)
                drawPath(p, fill)
                if (redTop) {
                    clipPath(p) { drawRect(InterstateRed, size = Size(size.width, size.height * 0.30f)) }
                }
                if (stroke != null) drawPath(p, stroke, style = Stroke(width = size.width * 0.06f))
            }
            Text(
                number,
                color = numberColor,
                fontWeight = FontWeight.Bold,
                fontSize = if (number.length >= 3) 12.sp else 14.sp,
                modifier = Modifier.padding(top = if (redTop) 4.dp else 0.dp),
            )
        }
        direction?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, color = dim, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Neutral state/provincial marker: a white rounded square with a black number (one shape for
 * all states/provinces in v1; per-state shapes are the follow-up). */
@Composable
private fun StateMarker(number: String, direction: String?, dim: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 32.dp, height = 30.dp)
                .background(Color.White, RoundedCornerShape(6.dp))
                .border(1.5.dp, ShieldInk, RoundedCornerShape(6.dp)),
        ) {
            Text(
                number,
                color = ShieldInk,
                fontWeight = FontWeight.Bold,
                fontSize = if (number.length >= 3) 12.sp else 14.sp,
            )
        }
        direction?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, color = dim, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The original plain bordered chip - kept as the fallback for refs we don't have a shape for. */
@Composable
private fun GenericChip(label: String, ink: Color) {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.5.dp, ink)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
