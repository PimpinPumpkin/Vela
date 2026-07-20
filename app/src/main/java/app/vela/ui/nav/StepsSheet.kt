package app.vela.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.vela.ui.place.FLING_COMMIT_DPS
import app.vela.ui.place.sheetDragGestures
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import app.vela.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.ui.SheetPalette
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.theme.isAppInDarkTheme
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.rememberDpadAutoFocus
import androidx.compose.ui.focus.focusRequester

/**
 * The full turn-by-turn step list — shown both while previewing a route and
 * during navigation. Tapping a step asks the map to pan to that maneuver so you
 * can see where you'd turn ([onStep]); [currentStep] is highlighted while
 * navigating, [previewIndex] while previewing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepsSheet(
    maneuvers: List<Maneuver>,
    etaSeconds: Double,
    distanceMeters: Double,
    hasLiveTraffic: Boolean,
    previewIndex: Int?,
    currentStep: Int?,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    // Destination lines for the ARRIVE row (name + address; either may be blank — offline
    // routing can have only a street, an address, or nothing but the tapped coordinates).
    destName: String? = null,
    destAddress: String? = null,
    // Real romanized road names (local -> basemap Latin) + the UI language, so foreign street names
    // in the step list show in Latin where we have a real romanization (issue #184). Empty = unchanged.
    roadLatin: Map<String, String> = emptyMap(),
    uiLang: String = "",
    modifier: Modifier = Modifier,
) {
    fun romanize(s: String): String =
        if (s.isEmpty() || roadLatin.isEmpty()) s
        else app.vela.core.voice.SpokenScript.forDisplay(s, uiLang, roadLatin)
    val dark = isAppInDarkTheme()
    val ink = SheetPalette.ink(dark)
    val dim = SheetPalette.dim(dark)
    // Swipe-down to dismiss (user 2026-07-15): the card rides the finger (down only) and a
    // release commits close on a flick or past a third of the sheet, else springs back - the
    // shared sheetDragGestures grammar, so it feels like every other sheet. The header/edges
    // drag via the card's own detector; the step LIST joins in through a nested-scroll
    // connection (dismissConn) so a downward drag on the body with the list AT ITS TOP pulls
    // the sheet too, the place-sheet grammar (user 2026-07-15: "swipe down anywhere on the
    // body, not just the chevron").
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val settleDrag: (Float) -> Unit = { velocityPxS ->
        val flick = with(density) { FLING_COMMIT_DPS.dp.toPx() }
        val committed = velocityPxS > flick ||
            (drag.value > sheetHeightPx / 3f && velocityPxS > -flick)
        if (committed) onClose() else scope.launch { drag.animateTo(0f) }
    }
    val listState = rememberLazyListState()
    val dismissConn = remember(listState) {
        object : NestedScrollConnection {
            // True once this gesture moved the sheet - its release then settles the sheet and
            // eats the fling instead of letting the list scroll run away with it.
            private var draggingSheet = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                // Downward drag with the list at its top pulls the sheet; an upward drag
                // retracts a pulled sheet before the list scrolls again.
                if (available.y > 0f && atTop) {
                    draggingSheet = true
                    scope.launch { drag.snapTo(drag.value + available.y) }
                    return available
                }
                if (available.y < 0f && drag.value > 0f) {
                    draggingSheet = true
                    scope.launch { drag.snapTo((drag.value + available.y).coerceAtLeast(0f)) }
                    return available
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (draggingSheet) {
                    draggingSheet = false
                    settleDrag(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    Card(
        modifier
            .fillMaxWidth()
            .onSizeChanged { sheetHeightPx = it.height }
            .offset { IntOffset(0, drag.value.roundToInt().coerceAtLeast(0)) }
            .pointerInput(Unit) {
                sheetDragGestures(
                    dragBy = { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                    settle = settleDrag,
                )
            },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark), contentColor = ink),
    ) {
        // Fill the card to the screen bottom; pad content off the nav bar.
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            // Grab handle - signals the sheet drags like the others.
            Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(dim.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.steps_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                    Text(
                        formatDuration(etaSeconds) + "  ·  " + formatDistance(distanceMeters) +
                            if (hasLiveTraffic) "  ·  " + stringResource(R.string.steps_live_traffic) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasLiveTraffic) SheetPalette.TrafficGreen else dim,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.steps_close_cd), tint = dim) }
            }
            // D-pad-first (docs/dpad.md): land focus on the first step row when the sheet
            // opens, so it's the active surface (OK previews that step). No-op under touch.
            val stepsAutoFocus = rememberDpadAutoFocus()
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    .nestedScroll(dismissConn),
                state = listState,
            ) {
                itemsIndexed(maneuvers) { i, m ->
                    val highlighted = i == previewIndex
                    val active = i == currentStep
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(stepsAutoFocus) else Modifier)
                            .background(
                                if (highlighted || active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                            )
                            .dpadHighlight(RoundedCornerShape(6.dp))
                            .clickable { onStep(i) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            maneuverIcon(m.type),
                            contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.primary else ink,
                            // size + gap must be SEPARATE modifiers: `.size(24).padding(end=16)`
                            // insets the icon INSIDE the 24 dp box, shrinking the actual glyph to
                            // ~8 dp (why the step icons looked tiny). Spacer carries the gap.
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            val continueLabel = stringResource(R.string.steps_continue)
                            Text(
                                m.instruction.ifEmpty { continueLabel }.let { romanize(it) },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = ink,
                            )
                            val signs = roadSigns(m.instruction, m.ref)
                            if (signs.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 3.dp),
                                ) { signs.forEach { SignChip(it) } }
                            }
                            // The arrive row names WHERE the trip ends (business + address), same
                            // dedupe rules as the banner: no line that just repeats another.
                            if (m.type == ManeuverType.ARRIVE) {
                                val name = destName?.trim().orEmpty()
                                val addr = destAddress?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
                                if (name.isNotEmpty() && !m.instruction.contains(name, ignoreCase = true)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ink)
                                }
                                addr?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = dim)
                                }
                            }
                            // On ARRIVE the destination lines above already say where the trip ends —
                            // the raw street name under a full address is noise. Keep it only when
                            // there's no name/address at all (it's then the only locator we have).
                            if (m.type != ManeuverType.ARRIVE || (destName.isNullOrBlank() && destAddress.isNullOrBlank())) {
                                m.road?.let {
                                    Text(romanize(it), style = MaterialTheme.typography.bodySmall, color = dim)
                                }
                            }
                            if (m.lanes.isNotEmpty()) {
                                LaneDiagram(m.lanes, m.type, on = ink, modifier = Modifier.padding(top = 3.dp))
                            } else m.laneHint?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (m.distanceMeters > 0) {
                            Text(
                                formatDistance(m.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = dim,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A turn-arrow glyph for each maneuver type (Material "turn_*" symbols). */
fun maneuverIcon(type: ManeuverType): ImageVector = when (type) {
    ManeuverType.DEPART -> Icons.Filled.TripOrigin
    ManeuverType.ARRIVE -> Icons.Filled.Flag
    ManeuverType.TURN_LEFT -> Icons.Filled.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.UTURN -> Icons.Filled.UTurnLeft
    ManeuverType.MERGE -> Icons.Filled.Merge
    ManeuverType.FORK_LEFT -> Icons.Filled.ForkLeft
    ManeuverType.FORK_RIGHT -> Icons.Filled.ForkRight
    ManeuverType.RAMP_LEFT -> Icons.Filled.RampLeft
    ManeuverType.RAMP_RIGHT -> Icons.Filled.RampRight
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> Icons.Filled.RoundaboutLeft
    ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> Icons.Filled.Straight
    ManeuverType.UNKNOWN -> Icons.AutoMirrored.Filled.ArrowForward
}
