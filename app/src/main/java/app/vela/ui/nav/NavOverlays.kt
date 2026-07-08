package app.vela.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.model.Lane
import app.vela.core.model.ManeuverType
import app.vela.ui.SheetPalette
import app.vela.ui.formatArrivalClock
import kotlinx.coroutines.launch
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.theme.isAppInDarkTheme
// D-pad-only operation (docs/dpad.md) — one import block so upstream merges stay clean.
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.vela.ui.dpadHighlight

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
    ref: String? = null,
    laneHint: String? = null,
    lanes: List<Lane> = emptyList(),
    nextText: String? = null,
    nextType: ManeuverType? = null,
    nextRef: String? = null,
    nextDistanceMeters: Double? = null,
    // Approach gate for lane arrows AND the compound "then" row — speed-scaled by the caller
    // (max(800 m, v×30 s)): a 75 mph exit needs the lanes ~1 km out, a city turn at 800 m.
    laneShowM: Double = LANE_SHOW_M,
    previewing: Boolean = false,
    onPreviewNext: () -> Unit = {},
    onPreviewPrev: () -> Unit = {},
    onExitPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Swiping the banner left/right walks the upcoming steps (Google-style): the
    // card greys out, shows that step, and the map's preview marker + camera move
    // there (driven by previewStepIndex). Tapping it resumes live guidance.
    val container = if (previewing) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primaryContainer
    val content = if (previewing) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onPrimaryContainer
    // The card tracks your finger as you drag (translationX = offsetX); on release
    // past a threshold it slides the rest of the way out, swaps to the next/prev
    // step, then the new card slides in from the opposite edge — like flicking a
    // pager. Below threshold it springs back.
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // `pointerInput(Unit)` builds the gesture detector ONCE, capturing these lambdas
    // as they were at first composition — which closed over the *first* step index.
    // Without this, every swipe re-ran previewStep(liveStep+1) → the same card forever.
    // rememberUpdatedState keeps the captured refs pointing at the latest lambdas.
    val latestNext by rememberUpdatedState(onPreviewNext)
    val latestPrev by rememberUpdatedState(onPreviewPrev)
    Card(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offsetX.value }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dx) }
                    },
                    onDragEnd = {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        scope.launch {
                            when {
                                offsetX.value <= -110f -> {
                                    offsetX.animateTo(-w); latestNext(); offsetX.snapTo(w); offsetX.animateTo(0f)
                                }
                                offsetX.value >= 110f -> {
                                    offsetX.animateTo(w); latestPrev(); offsetX.snapTo(-w); offsetX.animateTo(0f)
                                }
                                else -> offsetX.animateTo(0f)
                            }
                        }
                    },
                )
            }
            // D-pad step preview (docs/dpad.md): focus the banner, then LEFT/RIGHT walk the
            // upcoming steps (the key mirror of the swipe above); OK resumes live guidance
            // (via the clickable below while previewing). Placed BEFORE the clickable so key
            // events bubbling up from its focus target reach this handler; the extra
            // focusable() only exists when the clickable isn't there (one focus stop always).
            .dpadHighlight(RoundedCornerShape(12.dp))
            .onKeyEvent { ev ->
                val previewKey = ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
                when {
                    !previewKey -> false
                    ev.type != KeyEventType.KeyUp -> true // consume the DOWN so focus doesn't move
                    ev.key == Key.DirectionRight -> { latestNext(); true }
                    else -> { latestPrev(); true }
                }
            }
            .then(
                if (previewing) Modifier.clickable(onClick = onExitPreview) else Modifier.focusable(),
            ),
        // Softer, more current shape than the stock card: big radius + a real shadow so the
        // banner floats over the map instead of sitting on it like a toolbar.
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(maneuverIcon(type), contentDescription = null, modifier = Modifier.size(54.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatDistance(distanceMeters),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val signs = roadSigns(text, ref)
                    if (signs.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
                        ) { signs.forEach { SignChip(it) } }
                    }
                    Text(
                        text.ifEmpty { stringResource(R.string.nav_maneuver_continue) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // Real per-lane diagram from OSRM (a cell per lane, arrows for what it allows, the ones for
            // THIS turn highlighted) when we have it; else the old count-based hint from Google markup.
            // Only show lane guidance when you're actually APPROACHING the maneuver (Google-style) —
            // otherwise the arrows sit there for miles telling you to "be in the right lane" for an exit
            // way ahead, which is just noise. The distance gate covers BOTH paths (the count-based hint
            // was just as noisy). In step-preview (swiping ahead) always show, since you're inspecting a step.
            if (previewing || distanceMeters <= laneShowM) {
                if (lanes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LaneDiagram(lanes, type)
                } else laneHint?.let {
                    Spacer(Modifier.height(10.dp))
                    LaneGuide(it, type)
                }
            }
            // Compound "then <next>" preview — only when the next maneuver CLOSELY follows this one
            // (Google shows it only for back-to-back turns like "exit, then keep right") AND we're
            // actually APPROACHING this one: gated on the gap alone, an exit 12 km ahead with a merge
            // 300 m after it kept "then ⤵ Merge onto I-80 E" on the banner for the whole 12 km — the
            // same noise the lane gate was added to kill. Preview always shows (inspecting a step).
            if (nextText != null && nextType != null && isCompoundNext(nextDistanceMeters) &&
                (previewing || distanceMeters <= laneShowM)
            ) {
                Spacer(Modifier.height(8.dp))
                val nextSigns = roadSigns(nextText, nextRef)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.nav_compound_then),
                        style = MaterialTheme.typography.labelLarge,
                        color = content.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(maneuverIcon(nextType), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    nextSigns.firstOrNull()?.let { SignChip(it); Spacer(Modifier.width(6.dp)) }
                    Text(nextText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (previewing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.nav_preview_tap_resume),
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// Show the lane diagram only within this distance of the maneuver (~0.5 mi) — beyond it the arrows are
// just noise telling you to pick a lane for an exit miles ahead.
private const val LANE_SHOW_M = 800.0

// A "then <next>" compound preview only makes sense when the next maneuver closely follows this one
// (~0.3 mi) — an exit-then-merge, not a turn 5 miles later. Matches Google's compound-maneuver treatment.
private const val COMPOUND_M = 500.0

/** True when the next maneuver follows closely enough to show the compound "then …" preview. */
internal fun isCompoundNext(nextDistanceMeters: Double?): Boolean =
    nextDistanceMeters != null && nextDistanceMeters <= COMPOUND_M

private val EXIT_RE = Regex("""\bexit\s+(\w[\w-]*)""", RegexOption.IGNORE_CASE)
// I / US / SR / Hwy (space or dash), plus any 2-letter-DASH-number for state/provincial routes
// (TX-35, ON-401, CA-99) — the dash keeps it route-like so it doesn't grab random "to 5" text;
// parseRouteRef's state set then filters an unknown 2-letter prefix back to a plain chip.
private val ROUTE_RE = Regex("""\b(?:(?:I|US|CA|SR|US-?Hwy|Hwy)[-\s]?\d+|[A-Za-z]{2}-\d+)(?:\s?[NSEW]\b)?""", RegexOption.IGNORE_CASE)

/** A highway shield or exit tab extracted from an instruction. */
internal data class Sign(val isExit: Boolean, val label: String)

/** Pull route shields ("I-80 E") and the exit tab ("Exit 71") out of an instruction so they can be
 *  rendered as Google-style badges. [explicitRef] is the maneuver's own ref field (OSRM's `ref`): a
 *  highway can have a NAME in the text and a ref that never appears there ("Continue onto Yolo Causeway",
 *  ref "I 80"), so pass it to still get the shield. */
internal fun roadSigns(text: String, explicitRef: String? = null): List<Sign> {
    val seen = HashSet<String>()
    val out = ArrayList<Sign>()
    EXIT_RE.find(text)?.let {
        val label = "Exit ${it.groupValues[1]}"
        if (seen.add(label.lowercase())) out.add(Sign(isExit = true, label = label))
    }
    explicitRef?.trim()?.replace(Regex("\\s+"), " ")?.uppercase()?.takeIf { it.isNotBlank() }?.let {
        if (seen.add(it.lowercase())) out.add(Sign(isExit = false, label = it))
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
        // Real highway-shield shapes (interstate / US-route / state marker), inferred from the
        // ref; falls back to the plain bordered chip for anything unrecognised.
        RouteShield(
            sign.label,
            ink = MaterialTheme.colorScheme.onPrimaryContainer,
            dim = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
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

/** Google-style lane diagram: one cell per approach lane, drawn in road order, each showing the
 *  arrow(s) that lane permits. Lanes that serve THIS maneuver ([Lane.valid]) are bright; the rest are
 *  dimmed — so you can see which lane to be in. Data is OSRM's per-lane `indications`/`valid`. */
@Composable
internal fun LaneDiagram(
    lanes: List<Lane>,
    maneuver: ManeuverType = ManeuverType.STRAIGHT,
    on: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier: Modifier = Modifier,
) {
    val bright = on
    // Flat mid-grey for the arrows you're NOT taking — a solid colour (not a translucent tint of
    // `on`) so overlapping strokes don't build up into a muddy skeuomorphic blob.
    val dim = Color(0xFF80868B)
    // A signed direction level for the maneuver, or null when the type doesn't pin a side (MERGE /
    // ROUNDABOUT / arrive / unknown) — in that case we can't say WHICH allowed direction we're
    // taking, so a valid lane lights ALL its arrows rather than guessing (and lighting the wrong one).
    val target = maneuverBucket(maneuver)
    Surface(color = on.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.take(8).forEach { lane ->
                val inds = lane.indications.ifEmpty { listOf("straight") }.distinct()
                // In a valid lane, ONLY the arrow for the turn we're taking lights up — the other
                // directions the lane also allows stay dim (so a "go straight" turn doesn't light
                // the right-turn head on a straight-or-right lane). The active one = the lane's
                // indication closest to the maneuver's direction. When the maneuver side is unknown
                // (target null), light all of a valid lane's arrows. Invalid lanes: all dim.
                val active = if (lane.valid && target != null) inds.minByOrNull { kotlin.math.abs(laneBucket(it) - target) } else null
                fun lit(ind: String) = lane.valid && (target == null || ind == active)
                // One SHARED vertical shaft + a head per indication (not a whole arrow each — that
                // double-drew the shaft). Wider cell + smaller heads so two forked heads don't
                // overlap; dim heads drawn first so the bright active head sits on top of any touch.
                Canvas(Modifier.size(width = 30.dp, height = 26.dp)) {
                    val cw = size.width; val ch = size.height
                    val baseX = cw / 2f; val bendY = ch * 0.44f; val stroke = cw * 0.11f
                    drawLine(if (lane.valid) bright else dim, Offset(baseX, ch * 0.92f), Offset(baseX, bendY), stroke, cap = StrokeCap.Round)
                    inds.sortedBy { if (lit(it)) 1 else 0 }.forEach { ind ->
                        laneHead(ind, if (lit(ind)) bright else dim, baseX, bendY, cw, ch, stroke)
                    }
                }
            }
        }
    }
}

/** Coarse signed direction level for a lane indication (straight 0, right +, left −); used to
 *  match a lane's allowed directions against the maneuver we're actually taking. A u-turn is a
 *  hard LEFT (−4), so it matches a "uturn" indication AND, absent one, the left-most arrow. */
private fun laneBucket(indication: String): Int = when (indication.trim().lowercase().replace('_', ' ')) {
    "uturn" -> -4
    "sharp left" -> -3
    "left" -> -2
    "slight left", "merge to left" -> -1
    "slight right", "merge to right" -> 1
    "right" -> 2
    "sharp right" -> 3
    else -> 0 // straight / none / ""
}

/** The maneuver's signed direction, or null when the type doesn't pin a side (a valid lane then
 *  lights all its arrows rather than guessing wrong). */
private fun maneuverBucket(type: ManeuverType): Int? = when (type) {
    ManeuverType.UTURN -> -4
    ManeuverType.SHARP_LEFT -> -3
    ManeuverType.TURN_LEFT -> -2
    ManeuverType.SLIGHT_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.RAMP_LEFT -> -1
    ManeuverType.SLIGHT_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.RAMP_RIGHT -> 1
    ManeuverType.TURN_RIGHT -> 2
    ManeuverType.SHARP_RIGHT -> 3
    ManeuverType.STRAIGHT, ManeuverType.CONTINUE, ManeuverType.DEPART -> 0
    // MERGE / ROUNDABOUT / EXIT_ROUNDABOUT / ARRIVE / UNKNOWN — side not encoded in the type.
    else -> null
}

/** Draw one lane HEAD: the angled stem rising from the shared bend point [bendY] to a tip, plus the
 *  two barbs. The vertical shaft (base→bend) is drawn once per lane by the caller, so several
 *  indications on one lane share it instead of each redrawing (and muddying) it. */
private fun DrawScope.laneHead(indication: String, color: Color, baseX: Float, bendY: Float, w: Float, h: Float, stroke: Float) {
    val deg = when (indication.trim().lowercase().replace('_', ' ')) {
        "straight", "none", "" -> 0f
        "slight right" -> 32f
        "slight left" -> -32f
        "right" -> 66f
        "left" -> -66f
        "sharp right" -> 108f
        "sharp left" -> -108f
        "uturn" -> 155f
        "merge to left" -> -32f
        "merge to right" -> 32f
        else -> 0f
    }
    val a = Math.toRadians(deg.toDouble())
    val headLen = h * 0.40f
    val tip = Offset(
        baseX + (kotlin.math.sin(a) * headLen).toFloat(),
        bendY - (kotlin.math.cos(a) * headLen).toFloat(),
    )
    // the stem from the shared bend up to the tip (vertical when straight)
    drawLine(color, Offset(baseX, bendY), tip, stroke, cap = StrokeCap.Round)
    // arrowhead: two short barbs pointing back along the head direction (smaller than before so two
    // forked heads in one cell don't collide)
    val barb = w * 0.22f
    listOf(150.0, -150.0).forEach { d ->
        val ba = a + Math.toRadians(d)
        drawLine(
            color, tip,
            Offset(
                tip.x + (kotlin.math.sin(ba) * barb).toFloat(),
                tip.y - (kotlin.math.cos(ba) * barb).toFloat(),
            ),
            stroke, cap = StrokeCap.Round,
        )
    }
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
    trafficRatio: Double? = null,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    // Colour the ETA by live traffic (Google-style): green free-flowing → amber →
    // red. Default ink when there's no live data (offline / traffic-less route).
    val etaColor = when {
        trafficRatio == null -> SheetPalette.ink(dark)
        trafficRatio > 1.4 -> SheetPalette.TrafficRed
        trafficRatio > 1.15 -> SheetPalette.TrafficAmber
        else -> SheetPalette.TrafficGreen
    }
    Card(
        modifier.fillMaxWidth(),
        // Match the banner's treatment: generous radius + shadow, a floating pill not a bar.
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = SheetPalette.bg(dark),
            contentColor = SheetPalette.ink(dark),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDuration(remainingSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = etaColor,
                )
                Text(
                    formatDistance(remainingDistanceMeters) +
                        " · " + formatArrivalClock(remainingSeconds) +
                        if (offRoute) " · " + stringResource(R.string.nav_rerouting) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheetPalette.dim(dark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // Steps is icon-only so the row stays compact (the left ETA column can
            // grow with a longer "X mi · 7:42 PM"); End keeps its label.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onToggleVoice) {
                    Icon(
                        if (voiceMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (voiceMuted) stringResource(R.string.nav_unmute_voice) else stringResource(R.string.nav_mute_voice),
                    )
                }
                FilledTonalIconButton(onClick = onSteps) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.nav_steps))
                }
                Button(onClick = onStop) {
                    Text(stringResource(R.string.nav_end), maxLines = 1, softWrap = false)
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
                    Text(stringResource(R.string.nav_arrived), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (destinationLabel.isNotBlank()) {
                        Text(destinationLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text(stringResource(R.string.nav_trip_time), style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatDuration(tripSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column {
                    Text(stringResource(R.string.nav_distance), style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatDistance(tripDistanceMeters),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nav_done))
            }
        }
    }
}
