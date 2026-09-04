package app.vela.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.core.nav.RouteBar
import app.vela.ui.formatDistance

/**
 * The road ahead as a bar (issue #228, TomTom's RouteBar as the reference the reporter attached):
 * a track from the arrow at the bottom to the top of the look-ahead window, congestion painted on
 * it as red/amber segments, and what is coming up as ROUND ICON BADGES (speed and surveillance
 * cameras, level crossings, speed humps) or small dots (lights, stop signs, which are too frequent
 * in a town to badge). The arrow marker carries the remaining trip distance; the top cap says how
 * much road the bar spans, so it reads as a scale rather than as decoration. The first cut of this
 * (2026-09-02) was a 10 dp strip with 3 dp coloured ticks: technically the same information, and
 * unreadable in practice (user 2026-09-04).
 */
@Composable
fun RouteBarStrip(model: RouteBar.Model, remainingMeters: Double, modifier: Modifier = Modifier) {
    if (model.isEmpty) return
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val chip = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.width(STRIP_W.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (model.reachesDestination) "" else formatDistance(model.spanM),
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.8f),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.requiredWidth(LABEL_W.dp).padding(bottom = 2.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Layout(content = {
                // The track: a rounded channel the badges sit on.
                Box(
                    Modifier
                        .width(TRACK_W.dp)
                        .clip(RoundedCornerShape((TRACK_W / 2).dp))
                        .background(track)
                        .span(0.0, 1.0, kind = ROLE_TRACK),
                )
                for (b in model.bands) {
                    Box(
                        Modifier
                            .width(TRACK_W.dp)
                            .clip(RoundedCornerShape((TRACK_W / 2).dp))
                            .background(congestionColor(b.level))
                            .span(b.from, b.to, kind = ROLE_BAND),
                    )
                }
                for (p in model.pins) {
                    when (p.kind) {
                        RouteBar.Mark.SIGNAL, RouteBar.Mark.STOP -> Box(
                            Modifier
                                .size(DOT.dp)
                                .clip(CircleShape)
                                .background(pinColor(p.kind))
                                .span(p.at, p.at, kind = ROLE_DOT),
                        )
                        else -> Box(
                            Modifier
                                .size(BADGE.dp)
                                .shadow(2.dp, CircleShape)
                                .clip(CircleShape)
                                .background(pinColor(p.kind))
                                .span(p.at, p.at, kind = ROLE_BADGE),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (p.kind) {
                                    RouteBar.Mark.CAMERA -> Icons.Filled.Videocam
                                    RouteBar.Mark.RAIL_CROSSING -> Icons.Filled.Train
                                    else -> Icons.Filled.Warning
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size((BADGE - 10).dp),
                            )
                        }
                    }
                }
                // The arrow, at the bottom of the track, where you are.
                Box(
                    Modifier
                        .size(BADGE.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(chip)
                        .span(0.0, 0.0, kind = ROLE_PUCK),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                }
            }) { measurables, constraints ->
                val h = constraints.maxHeight
                val w = constraints.maxWidth
                // The track runs from the arrow's centre (bottom) to the top cap; badges centre on it.
                val inset = (BADGE.dp.roundToPx() / 2)
                val trackH = (h - 2 * inset).coerceAtLeast(1)
                fun yAt(f: Double) = h - inset - (f * trackH).toInt()
                val placed = measurables.map { m ->
                    val d = m.parentData as RouteBarSpan
                    val p = when (d.kind) {
                        ROLE_TRACK -> m.measure(constraints.copy(minHeight = trackH, maxHeight = trackH, minWidth = 0))
                        ROLE_BAND -> {
                            val bh = ((d.to - d.from) * trackH).toInt().coerceIn(TRACK_W.dp.roundToPx(), trackH)
                            m.measure(constraints.copy(minHeight = bh, maxHeight = bh, minWidth = 0))
                        }
                        else -> m.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    }
                    Triple(p, d, d.kind)
                }
                layout(w, h) {
                    for ((p, d, kind) in placed) {
                        val x = (w - p.width) / 2
                        val y = when (kind) {
                            ROLE_TRACK -> inset
                            ROLE_BAND -> yAt(d.to)
                            else -> yAt(d.from) - p.height / 2
                        }
                        p.place(x, y.coerceIn(0, (h - p.height).coerceAtLeast(0)))
                    }
                }
            }
        }
        Text(
            formatDistance(remainingMeters),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.requiredWidth(LABEL_W.dp).padding(top = 2.dp),
        )
    }
}

private const val STRIP_W = 34
private const val LABEL_W = 64 // the distance labels may be wider than the strip ("768.8 mi")
private const val TRACK_W = 8
private const val BADGE = 26
private const val DOT = 9
private const val ROLE_TRACK = 0
private const val ROLE_BAND = 1
private const val ROLE_DOT = 2
private const val ROLE_BADGE = 3
private const val ROLE_PUCK = 4

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

private fun Modifier.span(from: Double, to: Double, kind: Int) = this.then(RouteBarSpan(from, to, kind))

private data class RouteBarSpan(val from: Double, val to: Double, val kind: Int) :
    androidx.compose.ui.layout.ParentDataModifier, Modifier.Element {
    override fun androidx.compose.ui.unit.Density.modifyParentData(parentData: Any?) = this@RouteBarSpan
}
