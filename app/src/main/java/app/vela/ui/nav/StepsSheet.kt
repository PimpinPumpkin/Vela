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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.vela.ui.place.FLING_COMMIT_DPS
import app.vela.ui.place.sheetDragGestures
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
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
import app.vela.ui.theme.isAppInAmoled
import androidx.compose.foundation.BorderStroke
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
    // Where each leg after the first begins in [maneuvers], with the stop's name (issue #519): a
    // divider row is drawn before that step so a stop stands out in a long list instead of
    // vanishing between two ordinary turns. Empty for a single-leg trip.
    legStarts: List<Pair<Int, String>> = emptyList(),
    // Real romanized road names (local -> basemap Latin) + the UI language, so foreign street names
    // in the step list show in Latin where we have a real romanization (issue #184). Empty = unchanged.
    roadLatin: Map<String, String> = emptyMap(),
    uiLang: String = "",
    // With [header] (nav): how much of the list WELL is already open when the sheet takes over,
    // i.e. the lift the finger left the bar at; the well grows from there to the list's full
    // height and shrinks back to 0 on close, the card's bottom anchored throughout, so the
    // sheet is the bar with its list well open. Without a header: the offset the card slides
    // up from (0 = the screen bottom).
    enterFromPx: Float = 0f,
    // Bumped by the host (BACK) to close WITH the exit animation; the X and the swipe use the
    // same path internally.
    closeTick: Int = 0,
    // During nav the sheet IS the ETA bar, grown: [header] draws the bar's own top (chevron +
    // End + figures) in place of the "Steps" title row, and the card keeps the bar's floating
    // pill geometry (28dp corners; the host supplies the same margins), so the handover from the
    // bar and back is invisible. The lambda's argument closes the sheet with the exit animation.
    header: (@Composable (close: () -> Unit) -> Unit)? = null,
    // Tallest the LIST may get. The nav form's host sets it so the sheet stops just under the
    // turn banner (Google's expanded sheet fills the screen; keeping the next turn in view
    // while reading the list is worth the strip); null = half the screen, the preview default.
    maxListHeight: androidx.compose.ui.unit.Dp? = null,
    // Nav only (issue #402): a row above the steps listing the stops still ahead, with the way
    // into the stops editor. Null = no row (no stops on the trip, or the pre-nav preview).
    stopsRow: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    fun romanize(s: String): String =
        if (s.isEmpty() || roadLatin.isEmpty()) s
        else app.vela.core.voice.SpokenScript.forDisplay(s, uiLang, roadLatin)
    val dark = isAppInDarkTheme()
    val amoled = isAppInAmoled()
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
    // Slides in from the bottom edge (the list button was tapped from the directions panel):
    // the enter offset starts at the sheet's own height and eases to 0. In the nav form the
    // card never slides; `enterPx` is instead how much of the list well is still CLOSED, from
    // (list height - the bar's lift) to 0, once the list's natural height is known.
    val enter = remember { Animatable(1f) }
    // Starts "everything closed" (a huge value clamps the well to 0) so the first frame, before
    // the list has been measured, IS the bar; the one-shot effect below then waits for the
    // measurement and opens the well from the handed-over lift. One effect keyed on nothing:
    // a re-measure mid-animation (rows settle, lanes load) must not restart or cancel it.
    val enterPx = remember { Animatable(1e9f) }
    var listNaturalPx by remember { mutableIntStateOf(0) }
    val navForm = header != null
    LaunchedEffect(Unit) {
        if (!navForm) {
            enter.animateTo(0f, animationSpec = tween(260))
        } else {
            val natural = snapshotFlow { listNaturalPx }.first { it > 0 }
            enterPx.snapTo((natural - enterFromPx).coerceAtLeast(0f))
            enterPx.animateTo(0f, animationSpec = tween(240))
        }
    }
    val density = LocalDensity.current
    // Close = the nav form shrinks its well to nothing (the card's top edge comes back down to
    // the bar's), the plain form slides down; THEN the state flips and nothing pops.
    var closing by remember { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(onClose)
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                val target = if (navForm) listNaturalPx.toFloat() else sheetHeightPx.toFloat()
                drag.animateTo(target, animationSpec = tween(220))
                latestClose()
            }
        }
    }
    LaunchedEffect(closeTick) { if (closeTick > 0) dismiss() }
    val settleDrag: (Float) -> Unit = { velocityPxS ->
        val flick = with(density) { FLING_COMMIT_DPS.dp.toPx() }
        val span = if (navForm) listNaturalPx else sheetHeightPx
        val committed = velocityPxS > flick ||
            (drag.value > span / 3f && velocityPxS > -flick)
        if (committed) dismiss() else scope.launch { drag.animateTo(0f) }
    }
    // Nav form lands on the step the driver is heading to (the banner's step): the steps already
    // passed sit above it, greyed, one scroll up. The preview (no currentStep) starts at the top.
    val cur = currentStep?.coerceIn(0, (maneuvers.size - 1).coerceAtLeast(0))
    val landIndex = cur ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = landIndex)
    // A trailing spacer (nav form only) sized so the landing row can sit at the top even when the
    // rows still ahead are shorter than the list cap; without it the list clamps its scroll and
    // opens showing passed steps. The well hides the spacer (it is sized out of the natural height),
    // so the card still hugs the rows ahead. Starts at the full cap (no clamp on the first measure),
    // then is set to exactly viewport - rowsAhead from that measure, so the list cannot scroll down
    // into blank space.
    val capPx = with(LocalDensity.current) { (maxListHeight ?: (LocalConfiguration.current.screenHeightDp * 0.5f).dp).toPx() }.roundToInt()
    var tailPx by remember { mutableIntStateOf(-1) }
    // Last known blank below the rows ahead (px), kept while the landing row is scrolled out of view.
    val hiddenPx = remember { intArrayOf(0) }
    // The driver passes a turn with the sheet open: if the list was resting on the old current step,
    // follow to the new one (one frame later, once the tail spacer has grown to allow it). A list the
    // user scrolled elsewhere is left alone. With a stops row this is a no-op: the row keeps its key
    // and stays the first visible item as the passed step slides in above it.
    val prevLand = remember { intArrayOf(landIndex) }
    LaunchedEffect(landIndex) {
        val was = prevLand[0]
        prevLand[0] = landIndex
        if (navForm && was != landIndex &&
            listState.firstVisibleItemIndex == was && listState.firstVisibleItemScrollOffset == 0
        ) {
            androidx.compose.runtime.withFrameNanos { }
            listState.animateScrollToItem(landIndex)
        }
    }
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
            // Invisible until measured: the enter offset is a fraction of the sheet's own height,
            // which is 0 on the first frame, so that frame would flash the sheet fully open. The
            // nav form never slides (its first frame IS the bar: a closed well), so it skips both.
            .graphicsLayer { alpha = if (!navForm && sheetHeightPx == 0) 0f else 1f }
            .offset { IntOffset(0, if (navForm) 0 else (drag.value + sheetHeightPx * enter.value).roundToInt().coerceAtLeast(0)) }
            .pointerInput(Unit) {
                sheetDragGestures(
                    dragBy = { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                    settle = settleDrag,
                )
            },
        shape = if (header != null) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = if (amoled) BorderStroke(1.dp, SheetPalette.BorderAmoled) else null,
        elevation = if (header != null) CardDefaults.cardElevation(defaultElevation = 6.dp) else CardDefaults.cardElevation(),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark, amoled), contentColor = ink),
    ) {
        // Fill the card to the screen bottom; pad content off the nav bar (the floating nav form
        // gets its margins from the host, so only the list padding applies there).
        Column(
            if (header != null) Modifier
            else Modifier.navigationBarsPadding().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        ) {
            if (header != null) {
                header(dismiss)
            } else {
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
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.steps_close_cd), tint = dim) }
                }
            }
            // D-pad-first (docs/dpad.md): land focus on the landing step row when the sheet opens
            // (the current step while navigating, the first step in the preview), so it's the
            // active surface (OK previews that step). No-op under touch.
            val stepsAutoFocus = rememberDpadAutoFocus()
            // The nav form's list WELL: the list at its natural height minus whatever is still
            // closed (entering) or being pulled shut (drag / exit), clipped; read in the layout
            // phase so the animation never recomposes the rows.
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (navForm) Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                val p = measurable.measure(constraints)
                                // How much of the list is the hidden tail spacer: the viewport past
                                // the last real row, measured with the landing row at the top.
                                val info = listState.layoutInfo
                                val vis = info.visibleItemsInfo
                                val lastReal = info.totalItemsCount - 2
                                val landItem = vis.firstOrNull { it.index == landIndex }
                                val lastItem = vis.firstOrNull { it.index == lastReal }
                                val viewport = info.viewportEndOffset - info.viewportStartOffset
                                val rowsAhead: Int? = when {
                                    landItem != null && lastItem != null -> lastItem.offset + lastItem.size - landItem.offset
                                    landItem != null && landItem.offset <= 0 -> Int.MAX_VALUE
                                    else -> null
                                }
                                if (rowsAhead != null) {
                                    val t = (viewport - rowsAhead).coerceAtLeast(0)
                                    if (t != tailPx) tailPx = t
                                    hiddenPx[0] = t
                                }
                                val natural = p.height - hiddenPx[0].coerceIn(0, viewport)
                                if (natural != listNaturalPx) listNaturalPx = natural
                                val h = (natural - drag.value - enterPx.value).roundToInt().coerceIn(0, natural)
                                layout(p.width, h) { p.place(0, 0) }
                            }
                            .padding(start = 20.dp, end = 8.dp, bottom = 8.dp)
                        else Modifier,
                    )
                    .heightIn(max = maxListHeight ?: (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    .nestedScroll(dismissConn),
                state = listState,
            ) {
                // Preview: stops row (if any), then every step from the top. Nav: the passed steps,
                // then the stops row (it lists the stops still AHEAD, so it belongs at the boundary),
                // then the current step and the rest; the landing index is the first item after the
                // passed steps, so the list opens on the stops row / current step.
                val firstAhead = cur ?: 0
                fun LazyListScope.steps(range: IntRange) = items((range.last - range.first + 1).coerceAtLeast(0), key = { "s" + (range.first + it) }) { k ->
                    val i = range.first + k
                    val m = maneuvers[i]
                    val passed = cur != null && i < cur
                    legStarts.firstOrNull { it.first == i }?.let { (_, name) -> StopDividerRow(name, passed = passed) }
                    StepRow(
                        m = m,
                        active = i == currentStep,
                        highlighted = i == previewIndex,
                        passed = passed,
                        romanize = ::romanize,
                        destName = destName,
                        destAddress = destAddress,
                        onClick = { onStep(i) },
                        modifier = if (i == firstAhead) Modifier.focusRequester(stepsAutoFocus) else Modifier,
                    )
                }
                if (cur != null) steps(0 until cur)
                if (stopsRow != null) item(key = "stops") { stopsRow() }
                steps((cur ?: 0) until maneuvers.size)
                if (navForm) item(key = "tail") {
                    Spacer(Modifier.height(with(LocalDensity.current) { (if (tailPx < 0) capPx else tailPx).toDp() }))
                }
            }
        }
    }
}

/** The rows the nav bar's drag well shows under the figures: exactly what [StepsSheet] opens on in
 *  the nav form (stops row, then the current step and those after it, dividers included), so the
 *  handover from the bar to the sheet moves nothing. Passed steps are left out: the well does not
 *  scroll, and the sheet opens with them scrolled out of view above. */
@Composable
fun NavStepsPreview(
    maneuvers: List<Maneuver>,
    currentStep: Int,
    romanize: (String) -> String,
    destName: String?,
    destAddress: String?,
    legStarts: List<Pair<Int, String>> = emptyList(),
    stopsRow: (@Composable () -> Unit)? = null,
    maxRows: Int = 14,
) {
    stopsRow?.invoke()
    val from = currentStep.coerceIn(0, (maneuvers.size - 1).coerceAtLeast(0))
    for (i in from until minOf(maneuvers.size, from + maxRows)) {
        legStarts.firstOrNull { it.first == i }?.let { (_, name) -> StopDividerRow(name) }
        StepRow(
            m = maneuvers[i],
            active = i == currentStep,
            highlighted = false,
            romanize = romanize,
            destName = destName,
            destAddress = destAddress,
            onClick = null,
        )
    }
}

/** The stops still ahead on the drive, above the step list (issue #402): a pin glyph, the names
 *  in order, and Edit stops, which opens the same stops editor the chooser uses (reorder, remove,
 *  add; one replan on Done). Same padding grammar as [StepRow] so it reads as part of the list;
 *  drawn by the sheet AND the bar's drag preview. */
@Composable
fun NavStopsRow(stops: List<String>, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isAppInDarkTheme()
    val ink = SheetPalette.ink(dark)
    val dim = SheetPalette.dim(dark)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .dpadHighlight(RoundedCornerShape(12.dp))
                .clickable(onClick = onEdit)
                .padding(top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.stops_editor_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                )
                Text(
                    stops.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.stops_edit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(color = dim.copy(alpha = 0.25f))
    }
}

/** The stop that begins a leg (issue #519): a primary-tinted pin and "Stop: <name>" on its own
 *  band between the ARRIVE of the previous leg and the first turn of the next, so a long list
 *  reads leg by leg. Same left gutter as [StepRow]. [passed] greys it with the passed step below it. */
@Composable
fun StopDividerRow(name: String, modifier: Modifier = Modifier, passed: Boolean = false) {
    val dark = isAppInDarkTheme()
    val dim = SheetPalette.dim(dark)
    val ink = if (passed) dim else SheetPalette.ink(dark)
    val accent = if (passed) dim else MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(top = 10.dp, bottom = 10.dp, start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                if (name.isBlank()) stringResource(R.string.steps_stop) else stringResource(R.string.steps_stop_at, name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One step of the list: glyph, instruction, signs, road, lanes, distance, then a divider. Shared
 *  by the sheet's lazy list and the nav bar's drag PREVIEW (the rows that show under the ETA row
 *  while the bar is being pulled up), so both render pixel-identical. [passed] (nav only) greys a
 *  step the driver has already driven: glyph and text in the dim ink, signs and lanes faded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepRow(
    m: Maneuver,
    active: Boolean,
    highlighted: Boolean,
    romanize: (String) -> String,
    destName: String?,
    destAddress: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    passed: Boolean = false,
) {
    val dark = isAppInDarkTheme()
    val dim = SheetPalette.dim(dark)
    // A passed step reads in the secondary ink everywhere the row would use the primary one.
    val ink = if (passed) dim else SheetPalette.ink(dark)
    // Signs, lanes and the lane hint carry their own colours; fade them instead.
    val fade = if (passed) Modifier.alpha(PASSED_ALPHA) else Modifier
    Column(modifier) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (highlighted || active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                            )
                            .then(if (onClick != null) Modifier.dpadHighlight(RoundedCornerShape(6.dp)).clickable(onClick = onClick) else Modifier)
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            maneuverIconFor(m),
                            contentDescription = null,
                            tint = if (active && !passed) MaterialTheme.colorScheme.primary else ink,
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
                                    modifier = Modifier.padding(top = 3.dp).then(fade),
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
                                LaneDiagram(m.lanes, m.type, on = ink, modifier = Modifier.padding(top = 3.dp).then(fade))
                            } else m.laneHint?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = fade,
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

/** How much a passed step's own-coloured parts (sign chips, lane diagram, lane hint) are faded. */
private const val PASSED_ALPHA = 0.5f

/**
 * A turn-arrow glyph for each maneuver type (Material "turn_*" symbols).
 *
 * Roundabouts are the exception: they are DRAWN from the maneuver's own geometry, because no fixed
 * picture can be right about both the exit and the direction of travel (issue #259). This
 * type-only entry point can only produce the neutral form - callers holding the whole [Maneuver]
 * should use [maneuverIconFor] so the real exit angle is shown.
 */
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
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> NEUTRAL_ROUNDABOUT
    ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> Icons.Filled.Straight
    ManeuverType.UNKNOWN -> Icons.AutoMirrored.Filled.ArrowForward
}

/** The neutral roundabout glyph (ring + entry, no exit claimed), built once - it is the fallback
 *  for every call site that has only a [ManeuverType] and no measured geometry. */
private val NEUTRAL_ROUNDABOUT: ImageVector by lazy { roundaboutGlyph(null) }

/** The glyph for [m], drawing a roundabout at its real exit angle and circulation where the router
 *  gave us the bearings to derive them (issue #259). Prefer this wherever the Maneuver is in hand. */
@androidx.compose.runtime.Composable
fun maneuverIconFor(m: app.vela.core.model.Maneuver): ImageVector =
    if (isRoundabout(m.type)) rememberRoundaboutGlyph(m.roundabout) else maneuverIcon(m.type)
