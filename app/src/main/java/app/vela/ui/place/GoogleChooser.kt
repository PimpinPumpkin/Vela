package app.vela.ui.place

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vela.R
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.ui.SheetPalette
import app.vela.ui.dpadHighlight
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.nav.StepRow
import app.vela.ui.nav.StopDividerRow
import app.vela.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The route chooser laid out like Google Maps' own (the 2026 layout, read off the app on the 4a),
 * an EXPERIMENT behind Settings > Diagnostics so it can be compared with [DirectionsPanel] on the
 * same build. What differs from the shipped chooser, all on purpose:
 *
 * - A header with the MODE as the title ("Drive") and three round actions: options, share, close.
 * - Underlined mode TABS (glyph + time) instead of pill chips, with a divider under them.
 * - ONE route summary - the selected route's time in its traffic colour, the distance, and a line
 *   saying why it is the pick - instead of a list of route cards. The other routes are chosen
 *   from their time bubbles on the map ([app.vela.ui.map.RouteBubble]), or by tapping their line.
 * - Expanding the sheet shows the time and avoid chips, "add a stop along the way", and the TURN
 *   LIST inline (Google's expanded sheet), with the stop bands from the steps sheet.
 * - A sticky bottom bar: Start, Add stops, Share.
 *
 * Transit keeps [DirectionsPanel]: Google's transit chooser is a different list entirely.
 */
@Composable
fun GoogleStyleDirectionsPanel(
    currentMode: TravelMode,
    routes: List<Route>,
    activeRoute: Route?,
    flockOnRoute: List<Int> = emptyList(),
    modeEtas: Map<TravelMode, String> = emptyMap(),
    onModeSelected: (TravelMode) -> Unit,
    avoidTolls: Boolean = false,
    avoidHighways: Boolean = false,
    avoidFerries: Boolean = false,
    onAvoidTolls: (Boolean) -> Unit = {},
    onAvoidHighways: (Boolean) -> Unit = {},
    onAvoidFerries: (Boolean) -> Unit = {},
    onStartNav: () -> Unit,
    onSearchAlongRoute: (String) -> Unit,
    onTimeSelected: (Int, Long?) -> Unit = { _, _ -> },
    onEditStops: () -> Unit,
    altsOpen: Boolean = false,
    onAltsOpenChange: (Boolean) -> Unit = {},
    onSelectRoute: (Int) -> Unit = {},
    onShare: () -> Unit,
    onClose: () -> Unit,
    onStep: (Int) -> Unit = {},
    destName: String? = null,
    destAddress: String? = null,
    legStarts: List<Pair<Int, String>> = emptyList(),
    minimizeTick: Int = 0,
    onCollapsedChange: (Boolean) -> Unit = {},
    bodyMaxDp: Float? = null,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val ink = SheetPalette.ink(dark)
    val dim = SheetPalette.dim(dark)
    val collapsed = remember { mutableStateOf(true) } // Google opens at the summary, not the list
    val bodyMax = (LocalConfiguration.current.screenHeightDp * 0.58f).let { cap -> bodyMaxDp?.let { minOf(cap, it) } ?: cap }
    val bodyH = remember { Animatable(0f) }
    val settleSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 350f) }
    val decay = remember { exponentialDecay<Float>(frictionMultiplier = 1.6f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bodyScroll = rememberScrollState()
    LaunchedEffect(collapsed.value) {
        onCollapsedChange(collapsed.value)
        val target = if (collapsed.value) 0f else bodyMax
        if (bodyH.targetValue != target) bodyH.animateTo(target, settleSpec)
    }
    fun dragBy(dyPx: Float) {
        val dyDp = with(density) { dyPx.toDp().value }
        scope.launch { bodyH.snapTo((bodyH.value - dyDp).coerceIn(0f, bodyMax)) }
    }
    fun settle(velocityPxPerSec: Float) {
        val vDp = with(density) { velocityPxPerSec.toDp().value }
        val naturalEnd = decay.calculateTargetValue(bodyH.value, -vDp)
        val target = when {
            vDp < -FLING_COMMIT_DPS -> bodyMax
            vDp > FLING_COMMIT_DPS -> 0f
            else -> if (kotlin.math.abs(naturalEnd) < kotlin.math.abs(bodyMax - naturalEnd)) 0f else bodyMax
        }
        scope.launch {
            bodyH.animateTo(target, settleSpec, initialVelocity = -vDp)
            collapsed.value = target == 0f
        }
    }
    val conn = remember {
        object : NestedScrollConnection {
            private var dragging = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && bodyScroll.value == 0 && bodyH.value > 0f) { dragging = true; dragBy(available.y); return available }
                if (available.y < 0f && bodyH.value < bodyMax) { dragging = true; dragBy(available.y); return available }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragging) { dragging = false; settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }
    var seenTick by remember { mutableStateOf(minimizeTick) }
    LaunchedEffect(minimizeTick) {
        if (minimizeTick == seenTick || collapsed.value) return@LaunchedEffect
        seenTick = minimizeTick
        bodyH.animateTo(0f, settleSpec)
        collapsed.value = true
    }

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark), contentColor = ink),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .pointerInput(Unit) { sheetDragGestures(dragBy = { dragBy(it) }, settle = { settle(it) }) }
                .padding(top = 6.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().dpadHighlight(RoundedCornerShape(8.dp)).clickable { collapsed.value = !collapsed.value }.padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(dim.copy(alpha = 0.4f)))
            }
            // Header: the mode is the title, the actions are round buttons (Google's grammar).
            Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modeTitle(currentMode),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                RoundAction(Icons.Default.Tune, stringResource(R.string.exp_chooser_options), dark) {
                    collapsed.value = false
                    scope.launch { bodyScroll.animateScrollTo(0) }
                }
                Spacer(Modifier.width(8.dp))
                RoundAction(Icons.Default.Share, stringResource(R.string.place_share), dark, onShare)
                Spacer(Modifier.width(8.dp))
                RoundAction(Icons.Default.Close, stringResource(R.string.place_close_directions), dark, onClose)
            }
            Spacer(Modifier.height(10.dp))
            // Mode tabs: glyph + time, the selected one in the accent with an underline.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp)) {
                listOf(
                    Triple(TravelMode.DRIVE, R.string.place_mode_drive, Icons.Default.DirectionsCar),
                    Triple(TravelMode.TRANSIT, R.string.place_mode_transit, Icons.Default.DirectionsBus),
                    Triple(TravelMode.WALK, R.string.place_mode_walk, Icons.AutoMirrored.Filled.DirectionsWalk),
                    Triple(TravelMode.BICYCLE, R.string.place_mode_bike, Icons.AutoMirrored.Filled.DirectionsBike),
                ).forEach { (mode, label, icon) ->
                    val sel = mode == currentMode
                    val tint = if (sel) MaterialTheme.colorScheme.primary else ink
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .dpadHighlight(RoundedCornerShape(8.dp))
                            .clickable { onModeSelected(mode) }
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                modeEtas[mode] ?: stringResource(label),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                color = tint,
                            )
                        }
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
            }
            HorizontalDivider(color = dim.copy(alpha = 0.25f))
            // The selected route, summarized. Always visible (the collapsed sheet is this).
            val route = activeRoute ?: routes.firstOrNull()
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp)) {
                if (route == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.place_finding_route), style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                } else {
                    val eta = route.durationInTrafficSeconds ?: route.durationSeconds
                    val fastest = routes.minOfOrNull { it.durationInTrafficSeconds ?: it.durationSeconds } ?: eta
                    val deltaMin = ((eta - fastest) / 60.0).roundToInt()
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = trafficEtaColor(route) ?: ink, fontWeight = FontWeight.Medium, fontSize = 22.sp)) {
                                append(formatDuration(eta))
                            }
                            withStyle(SpanStyle(color = dim, fontSize = 18.sp)) { append(" (${formatDistance(route.distanceMeters)})") }
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    // "Fastest" belongs to the fastest route only: a near-tie a few seconds slower
                    // rounds to the same minute and used to claim it too.
                    val isFastest = routes.indexOfFirst { (it.durationInTrafficSeconds ?: it.durationSeconds) == fastest } == routes.indexOf(route)
                    val via = route.summary?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.exp_chooser_via, it) }
                    Text(
                        when {
                            isFastest && routes.size > 1 -> listOfNotNull(stringResource(R.string.exp_chooser_fastest), via).joinToString(" · ")
                            deltaMin >= 1 -> listOfNotNull(stringResource(R.string.exp_chooser_slower, formatDuration(eta - fastest)), via).joinToString(" · ")
                            else -> via ?: ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                    )
                    val idx = routes.indexOf(route).coerceAtLeast(0)
                    val cams = flockOnRoute.getOrElse(idx) { 0 }
                    if (cams > 0) {
                        Text(stringResource(R.string.dir_cameras_on_route, cams), style = MaterialTheme.typography.bodyMedium, color = SheetPalette.TrafficAmber)
                    }
                    // The alternates affordance sits with the ETA, not down in the button row: it is
                    // about THIS number ("29 min ... and what else?"). Always there, so its absence
                    // never has to be interpreted; with one route it says so and does nothing.
                    Spacer(Modifier.height(6.dp))
                    if (routes.size > 1) {
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .dpadHighlight(CircleShape)
                                .clickable { onAltsOpenChange(!altsOpen) }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.AltRoute, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                androidx.compose.ui.res.pluralStringResource(R.plurals.exp_chooser_alts, routes.size - 1, routes.size - 1),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.exp_chooser_alts_none), style = MaterialTheme.typography.bodyMedium, color = dim)
                    }
                }
            }
            // The alternates pane, over the body: every route with its time, how much longer it is,
            // its distance, the roads it uses and its camera count. Back returns to the Google view.
            if (altsOpen && routes.size > 1) {
                val fastestEta = routes.minOf { it.durationInTrafficSeconds ?: it.durationSeconds }
                Column(Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onAltsOpenChange(false) }, modifier = Modifier.dpadHighlight(CircleShape)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.exp_chooser_alts_back), tint = ink)
                        }
                        Text(
                            stringResource(R.string.exp_chooser_alts_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val fastestIdx = routes.indexOfFirst { (it.durationInTrafficSeconds ?: it.durationSeconds) == fastestEta }
                    // With "Avoid surveillance cameras" on, the counts are computed and the app may
                    // already have picked a route that is not the fastest. Say which one has the
                    // fewest, so the choice it made explains itself (user 2026-09-17).
                    val fewestCamIdx = flockOnRoute.takeIf { it.size == routes.size && it.distinct().size > 1 }
                        ?.let { counts -> counts.indices.minByOrNull { counts[it] } }
                    routes.forEachIndexed { i, r ->
                        val eta = r.durationInTrafficSeconds ?: r.durationSeconds
                        val delta = (eta - fastestEta).toInt()
                        val chosen = r === activeRoute
                        val cams = flockOnRoute.getOrElse(i) { 0 }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .dpadHighlight(RoundedCornerShape(14.dp))
                                .clickable { onSelectRoute(i); onAltsOpenChange(false) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formatDuration(eta),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = trafficEtaColor(r) ?: ink,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when {
                                            i == fastestIdx -> stringResource(R.string.exp_chooser_fastest)
                                            delta < 60 -> stringResource(R.string.exp_chooser_alts_same)
                                            else -> stringResource(R.string.exp_chooser_slower, formatDuration(delta.toDouble()))
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = dim,
                                    )
                                }
                                Text(
                                    listOfNotNull(
                                        formatDistance(r.distanceMeters),
                                        r.summary?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.exp_chooser_via, it) },
                                        if (cams > 0) stringResource(R.string.dir_cameras_on_route, cams) else null,
                                        if (i == fewestCamIdx) stringResource(R.string.exp_chooser_fewest_cams) else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (cams > 0) SheetPalette.TrafficAmber else dim,
                                )
                            }
                            if (chosen) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            // Expandable body: options, add-along-the-way, and the inline turn list.
            val bodyComposed by remember { derivedStateOf { !collapsed.value || bodyH.value > 1f } }
            if (bodyComposed && route != null) {
                Column(
                    Modifier
                        .graphicsLayer {
                            alpha = (bodyH.value / 120f).coerceIn(0f, 1f)
                            clip = true
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        }
                        .layout { measurable, constraints ->
                            val cap = bodyH.value.dp.roundToPx().coerceAtLeast(0)
                            val pl = measurable.measure(constraints.copy(maxHeight = minOf(constraints.maxHeight, cap)))
                            layout(pl.width, pl.height) { pl.place(0, 0) }
                        },
                ) {
                    Column(Modifier.nestedScroll(conn).verticalScroll(bodyScroll).padding(top = 12.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            DepartTimeChooser(route, dim, isTransit = false, onTimeSelected = onTimeSelected)
                        }
                        if (currentMode == TravelMode.DRIVE) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = avoidTolls, onClick = { onAvoidTolls(!avoidTolls) },
                                    label = { Text(stringResource(R.string.place_avoid_tolls)) },
                                    shape = CircleShape,
                                    modifier = Modifier.dpadHighlight(CircleShape),
                                )
                                FilterChip(
                                    selected = avoidHighways, onClick = { onAvoidHighways(!avoidHighways) },
                                    label = { Text(stringResource(R.string.place_avoid_highways)) },
                                    shape = CircleShape,
                                    modifier = Modifier.dpadHighlight(CircleShape),
                                )
                                FilterChip(
                                    selected = avoidFerries, onClick = { onAvoidFerries(!avoidFerries) },
                                    label = { Text(stringResource(R.string.place_avoid_ferries)) },
                                    shape = CircleShape,
                                    modifier = Modifier.dpadHighlight(CircleShape),
                                )
                            }
                            if ((avoidTolls || avoidHighways || avoidFerries) && routes.isNotEmpty() && routes.all { it.avoidNotHonored }) {
                                Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.place_avoid_not_honored), style = MaterialTheme.typography.bodyMedium, color = ink)
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.exp_chooser_along),
                            style = MaterialTheme.typography.titleSmall,
                            color = ink,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            app.vela.ui.QuickCategories.all().map { Triple(it.label, it.query, it.icon) }.forEach { (labelRes, query, icon) ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onSearchAlongRoute(query) },
                                    label = { Text(stringResource(labelRes)) },
                                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = dim) },
                                    shape = CircleShape,
                                )
                            }
                        }
                        if (route.maneuvers.isNotEmpty()) {
                            Text(
                                stringResource(R.string.place_steps),
                                style = MaterialTheme.typography.titleSmall,
                                color = ink,
                                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
                            )
                            Column(Modifier.padding(start = 20.dp, end = 8.dp)) {
                                route.maneuvers.forEachIndexed { i, m ->
                                    legStarts.firstOrNull { it.first == i }?.let { (_, name) -> StopDividerRow(name) }
                                    StepRow(
                                        m = m, active = false, highlighted = false, romanize = { it },
                                        destName = destName, destAddress = destAddress,
                                        onClick = { onStep(i) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Sticky action bar.
            if (route != null) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onStartNav) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.place_start))
                    }
                    FilledTonalButton(onClick = onEditStops) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.exp_chooser_add_stops))
                    }
                    FilledTonalButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.place_share))
                    }
                }
            }
        }
    }
}

@Composable
private fun modeTitle(mode: TravelMode): String = stringResource(
    when (mode) {
        TravelMode.DRIVE -> R.string.place_mode_drive
        TravelMode.TRANSIT -> R.string.place_mode_transit
        TravelMode.WALK -> R.string.place_mode_walk
        TravelMode.BICYCLE -> R.string.place_mode_bike
    },
)

/** Google's round header action: a tonal circle with a glyph. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, dark: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SheetPalette.row(dark))
            .dpadHighlight(CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = SheetPalette.ink(dark), modifier = Modifier.size(20.dp))
    }
}
