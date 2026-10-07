package app.vela.ui.nav

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import app.vela.ui.icons.Sym
import app.vela.ui.icons.SymOutlined

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
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
import app.vela.ui.theme.isAppInAmoled
import androidx.compose.foundation.BorderStroke
// D-pad-only operation (docs/dpad.md) — one import block so upstream merges stay clean.
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.vela.ui.dpadFieldEscape
import app.vela.ui.dpadAutoFocus
import app.vela.ui.dpadHighlight
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor

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
    // Roundabout shape for [type], when the router measured one - lets the glyph be drawn at the
    // real exit angle and circulation instead of a fixed picture (issue #259). Null draws neutral.
    roundabout: app.vela.core.model.RoundaboutGeometry? = null,
    ref: String? = null,
    laneHint: String? = null,
    lanes: List<Lane> = emptyList(),
    nextText: String? = null,
    nextType: ManeuverType? = null,
    nextRoundabout: app.vela.core.model.RoundaboutGeometry? = null,
    nextRef: String? = null,
    currentRef: String? = null, // highway ref of the road being driven -> persistent shield chip
    nextDistanceMeters: Double? = null,
    // Destination lines for the ARRIVE step (name + address, either may be blank — offline
    // routing can have only a street, an address, or nothing but the tapped coordinates).
    destName: String? = null,
    destAddress: String? = null,
    // Approach gate for lane arrows AND the compound "then" row — speed-scaled by the caller
    // (max(800 m, v×30 s)): a 75 mph exit needs the lanes ~1 km out, a city turn at 800 m.
    laneShowM: Double = LANE_SHOW_M,
    previewing: Boolean = false,
    // Off-route latch: the banner headline becomes "Rerouting..." (Google-style) instead of a stale
    // old-route instruction. Step preview still shows the previewed step normally.
    offRoute: Boolean = false,
    onPreviewNext: () -> Unit = {},
    onPreviewPrev: () -> Unit = {},
    onExitPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Swiping the banner left/right walks the upcoming steps (Google-style): the
    // card grays out, shows that step, and the map's preview marker + camera move
    // there (driven by previewStepIndex). Tapping it resumes live guidance.
    // Google's banner is a DARK FREEWAY GREEN (#00654F, pixel-sampled off the
    // app): white ink, Roboto (the app font - no brand font on the banner).
    // Preview keeps the gray surfaceVariant so a swiped-ahead step never reads
    // as live guidance.
    val container = if (previewing) MaterialTheme.colorScheme.surfaceVariant
    else NavBannerGreen
    val content = if (previewing) MaterialTheme.colorScheme.onSurfaceVariant
    else androidx.compose.ui.graphics.Color.White
    // The detached "then" strip under the card is darker than the banner (Google's reads
    // as a dimmer tab); preview follows the main card's own treatment.
    val thenContainer = if (previewing) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val thenContent = if (previewing) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurface
    // The card tracks your finger as you drag (translationX = offsetX); on release
    // past a threshold it slides the rest of the way out, swaps to the next/prev
    // step, then the new card slides in from the opposite edge — like flicking a
    // pager. Below threshold it springs back.
    // The "Then" tab hangs off the card's lower left: the card's corner is square where they meet.
    val thenShown = nextText != null && nextType != null && isCompoundNext(nextDistanceMeters) &&
        (previewing || distanceMeters <= laneShowM)
    // A long next step can make the tab as wide as the card: then the right corner squares off too.
    var cardW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var tabW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tabSpans = thenShown && tabW > 0 && tabW >= cardW - 2
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // `pointerInput(Unit)` builds the gesture detector ONCE, capturing these lambdas
    // as they were at first composition — which closed over the *first* step index.
    // Without this, every swipe re-ran previewStep(liveStep+1) → the same card forever.
    // rememberUpdatedState keeps the captured refs pointing at the latest lambdas.
    val latestNext by rememberUpdatedState(onPreviewNext)
    val latestPrev by rememberUpdatedState(onPreviewPrev)
    // COMPACT mode on very short screens (ported from alltechdev/vela-dpad, credit ars18):
    // the 54dp glyph + full paddings buried the map on sub-500dp-tall displays, so the banner
    // shrinks its chrome there. Ordinary phones and tall head units never trip the gate.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    // Main card + detached "then" strip share the caller's slot, so measured
    // offsets below (compass, chips) stay right.
    Column(modifier) {
    Card(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { cardW = it.width }
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = if (tabSpans) 0.dp else 24.dp, bottomStart = if (thenShown) 0.dp else 24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rerouting = offRoute && !previewing
                // The refresh glyph SPINS while rerouting (user 2026-07-16: nothing moved, so a
                // slow fetch read as frozen). The angle is read in graphicsLayer - a draw-phase
                // read, so the infinite transition never recomposes the banner; it only exists
                // while rerouting is showing at all.
                if (rerouting) {
                    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "reroute")
                    val angle by spin.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing),
                        ),
                        label = "reroute-angle",
                    )
                    Icon(
                        Sym.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (compact) 36.dp else 54.dp)
                            .graphicsLayer { rotationZ = angle },
                    )
                } else Icon(
                    if (isRoundabout(type)) rememberRoundaboutGlyph(roundabout) else maneuverIcon(type),
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 36.dp else 54.dp),
                )
                Spacer(Modifier.width(if (compact) 10.dp else 18.dp))
                Column(Modifier.weight(1f)) {
                    val signs = if (rerouting) emptyList() else roadSigns(text, ref)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rerouting) stringResource(R.string.nav_rerouting) else formatDistance(distanceMeters),
                            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // The road you're ON, as its stylized shield - persistent for the whole
                        // stretch, right-aligned on the distance row (user 2026-07-16: on the TOP
                        // card, and always, not only when a maneuver mentions the route). Skipped
                        // when the upcoming maneuver's own chips already show the same route, and
                        // while rerouting (the headline owns the row).
                        val cur = if (rerouting) null else currentRef?.trim()?.replace(WS_RUN, " ")
                            ?.uppercase()?.takeIf { c -> c.isNotBlank() && signs.none { it.label == c } }
                        if (cur != null) {
                            Spacer(Modifier.weight(1f))
                            SignChip(Sign(isExit = false, label = cur), onBanner = true)
                        }
                    }
                    if (signs.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
                        ) { signs.forEach { SignChip(it, onBanner = true) } }
                    }
                    // Headline = the SPOKEN form of the instruction (primary sign destination
                    // only), so the card and the voice can never disagree; the chips row above
                    // already carries the stylized route + exit. The sign's secondary cities
                    // drop to a dim one-liner below - present to confirm against the physical
                    // sign, subordinate, and harmless if a monster sign ellipsizes it
                    // (user-agreed design 2026-07-16).
                    if (!rerouting) {
                        val full = text.ifEmpty { stringResource(R.string.nav_maneuver_continue) }
                        val headline = app.vela.core.i18n.NavStringsRegistry.current().spokenSign(full)
                        Text(
                            headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (headline.length < full.length) {
                            val rest = full.substring(headline.length).trim(':', ' ')
                            if (rest.isNotBlank()) Text(
                                rest,
                                style = MaterialTheme.typography.bodyMedium,
                                color = content.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // The arrive step names WHERE you're arriving (Google-style): the business or
                    // label, and its address when that adds anything. Skip a line that would just
                    // repeat the instruction text.
                    if (type == ManeuverType.ARRIVE) {
                        val name = destName?.trim().orEmpty()
                        val addr = destAddress?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
                        if (name.isNotEmpty() && !text.contains(name, ignoreCase = true)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        addr?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.85f))
                        }
                    }
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
                    LaneDiagram(lanes, type, on = content)
                } else laneHint?.let {
                    Spacer(Modifier.height(10.dp))
                    LaneGuide(it, type, on = content)
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
        // Detached Google-style "then" strip: its own smaller, darker tab under the main
        // card - just "then <arrow> <full instruction>", no shield chip, no distance.
        // Same compound + approach gates as the old inline row.
        if (thenShown) {
            // Google's "Then" tab: hangs off the card's lower left in the card's own color,
            // the word and the arrow only.
            Card(
                // As wide as its text needs, up to the whole card.
                Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start).onSizeChanged { tabW = it.width },
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
            ) {
                Row(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.nav_compound_then).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (isRoundabout(nextType)) rememberRoundaboutGlyph(nextRoundabout) else maneuverIcon(nextType),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Vela says what the next step is; Google's tab shows the arrow alone.
                    Text(nextText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// Show the lane diagram only within this distance of the maneuver (~0.5 mi) — beyond it the arrows are
// just noise telling you to pick a lane for an exit miles ahead.
private const val LANE_SHOW_M = 800.0

/** Google's nav-banner freeway green, pixel-sampled off the Maps app (#00654F). */
internal val NavBannerGreen = androidx.compose.ui.graphics.Color(0xFF00654F)
// The nav bottom bar as a drag handle (see NavControls): how far it must be lifted to commit to
// the step sheet, how far it may lift at all, and the upward fling speed that commits regardless.
private const val NAV_BAR_LIFT_COMMIT_DP = 56
private const val NAV_BAR_LIFT_MAX_FRACTION = 0.5f // of the screen height: the sheet's list cap
private const val NAV_BAR_FLING_PX_S = 900f

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
// The bare two-letter state alternative ("NV 28", "NV-28") is CASE-SENSITIVE inside the otherwise
// case-insensitive pattern ((?-i:...)) - a case-blind "[a-z]{2} \d+" would turn "on 5" and "to 96"
// into shields. It previously required the hyphen form, so OSRM's spaced "NV 28" never chipped
// (user replay report 2026-07-16).
private val ROUTE_RE = Regex("""\b(?:(?:I|US|CA|SR|US-?Hwy|Hwy)[-\s]?\d+|(?-i:[A-Z]{2}[-\s]\d+))(?:\s?[NSEW]\b)?""", RegexOption.IGNORE_CASE)

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
    explicitRef?.trim()?.replace(WS_RUN, " ")?.uppercase()?.takeIf { it.isNotBlank() }?.let {
        if (seen.add(routeKey(it))) out.add(Sign(isExit = false, label = it))
    }
    ROUTE_RE.findAll(text).forEach { m ->
        val label = m.value.trim().replace(WS_RUN, " ").uppercase()
        if (seen.add(routeKey(label))) out.add(Sign(isExit = false, label = label))
    }
    return out.take(3)
}

/** One route however it is written: the maneuver's ref "I 5" and the sign text's "I-5 N" are the
 *  same shield (two I-5 badges on an on-ramp card, real drive 2026-10-03). Letters and number
 *  only, spaces, dashes and a trailing direction dropped. */
internal fun routeKey(label: String): String {
    val compact = label.uppercase().replace(Regex("[\\s-]"), "")
    return Regex("^([A-Z]*?)(\\d+)").find(compact)?.let { it.groupValues[1] + it.groupValues[2] } ?: compact
}

@Composable
internal fun SignChip(sign: Sign, onBanner: Boolean = false) {
    // White on the green banner + in step rows; the theme ink only when SignChip is
    // used OFF a sheet surface that already supplies it (default keeps callers unchanged).
    val bannerInk = androidx.compose.ui.graphics.Color.White
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
        // ref; falls back to the plain bordered chip for anything unrecognized. `onBanner`
        // carries the banner content color (white); elsewhere it defaults to the theme ink.
        RouteShield(
            sign.label,
            ink = if (onBanner) bannerInk else MaterialTheme.colorScheme.onPrimaryContainer,
            dim = (if (onBanner) bannerInk else MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f),
        )
    }
}

/** Lane-guidance strip from a hint like "Use the left 2 lanes": a row of
 *  turn-direction arrows for the lanes you want, plus the hint text. We don't
 *  get a per-lane diagram from Google's response, so this shows the count and
 *  direction rather than faking the full lane layout. */
/** Single-line text that SHRINKS to fit its width (never wraps, never ellipsizes) — for the
 *  nav card's trip time against the big driving buttons and Interface-size scaling. Steps down
 *  8% per layout pass while overflowing, floored at 55% of the base size. */
@Composable
private fun FitText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    val scaleState = remember(text) { androidx.compose.runtime.mutableStateOf(1f) }
    val scale = scaleState.value
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        fontSize = style.fontSize * scale,
        onTextLayout = { if (it.hasVisualOverflow && scaleState.value > 0.55f) scaleState.value *= 0.92f },
        modifier = modifier,
    )
}

/** [FitText] for the trip time: the numbers in semibold, the units in the regular weight. */
@Composable
private fun FitDuration(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val scaleState = remember(text) { androidx.compose.runtime.mutableStateOf(1f) }
    val styled = remember(text) {
        androidx.compose.ui.text.buildAnnotatedString {
            text.forEach { ch ->
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = if (ch.isDigit() || ch == '<') FontWeight.SemiBold else FontWeight.Normal))
                append(ch); pop()
            }
        }
    }
    Text(
        styled, style = style, color = color, maxLines = 1, softWrap = false,
        fontSize = style.fontSize * scaleState.value,
        onTextLayout = { if (it.hasVisualOverflow && scaleState.value > 0.55f) scaleState.value *= 0.92f },
    )
}

@Composable
private fun LaneGuide(hint: String, type: ManeuverType, on: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = on.copy(alpha = 0.14f),
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
    // Flat mid-gray for the arrows you're NOT taking — a solid color (not a translucent tint of
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

/** In-nav search-along-route chips: one row above the controls bar while the search button is
 *  armed. Same one-shot categories as the route chooser's row; a pick searches the REMAINING
 *  route and the results list takes the bottom slot. */
@Composable
fun NavSearchChips(
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recents: List<app.vela.core.data.RecentQuery> = emptyList(),
) {
    val dark = isAppInDarkTheme()
    val amoled = isAppInAmoled()
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val autoFocus = app.vela.ui.rememberDpadAutoFocus()
    Box(modifier.fillMaxSize()) {
        // Landscape: the rest of the route stays in view under a tint; a tap on it closes the page.
        // A tap target only, never a focus stop: with keys the close button and Back do this.
        if (landscape) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { onClose() } },
            )
        }
        Surface(
            color = SheetPalette.bg(dark, amoled),
            contentColor = SheetPalette.ink(dark),
            modifier = if (landscape) Modifier.fillMaxHeight().width(app.vela.ui.map.sidePanelWidth()) else Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(SheetPalette.row(dark, amoled), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.focusRequester(autoFocus).dpadHighlight(androidx.compose.foundation.shape.CircleShape)) {
                        Icon(Sym.ArrowBack, contentDescription = stringResource(R.string.place_back), tint = SheetPalette.ink(dark))
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SheetPalette.ink(dark)),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onPick(query.trim()) }),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.place_search_along_route),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = SheetPalette.dim(dark),
                                )
                            }
                            inner()
                        },
                        // dpadFieldEscape: UP/DOWN leave the field instead of being eaten as cursor
                        // moves, so the tiles below stay key-reachable (docs/dpad.md).
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp).dpadFieldEscape(),
                    )
                }
                // The same categories as everywhere else (fuel and charging first), as large tiles: three to a row.
                Column(
                    Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tile = RoundedCornerShape(12.dp)
                    app.vela.ui.QuickCategories.forDrive().chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { chip ->
                                Column(
                                    Modifier.weight(1f)
                                        .border(BorderStroke(1.dp, SheetPalette.dim(dark).copy(alpha = 0.45f)), tile)
                                        .clip(tile)
                                        .dpadHighlight(tile)
                                        .clickable { onPick(chip.query) }
                                        .padding(vertical = 14.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(chip.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(chip.label), style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    // Recent searches under the tiles, as on Google's page: a tap runs it along the route.
                    if (recents.isNotEmpty()) {
                        Text(
                            stringResource(R.string.mapscreen_section_recent), style = MaterialTheme.typography.titleSmall,
                            color = SheetPalette.dim(dark), modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                        )
                        recents.take(6).forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).dpadHighlight(RoundedCornerShape(10.dp))
                                    .clickable { onPick(r.query) }.padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Sym.History, contentDescription = null, tint = SheetPalette.dim(dark), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(r.query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Bottom bar during navigation: remaining time/distance + an End button. */
@Composable
fun NavControls(
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    offRoute: Boolean,
    paused: Boolean = false,
    onStop: () -> Unit,
    onSteps: () -> Unit,
    trafficRatio: Double? = null,
    showListButton: Boolean = true, // false = the chevron handle alone (a focusable button itself)
    // A drag that commits reports how far the bar's top edge had risen (px), so the step sheet
    // can take over from exactly there instead of sliding in from the screen bottom.
    onStepsFromDrag: ((liftPx: Float) -> Unit)? = null,
    // What shows UNDER the figures while the bar is being pulled up: the step rows, rendered by
    // the same composable the sheet uses, so a partial drag already reads the list (Google's one
    // continuous sheet). Null = the bar just grows blank.
    preview: (@Composable () -> Unit)? = null,
    // How far the drag may open the well: the same cap the sheet's list gets (null = half the
    // screen), so the bar never stands taller than the sheet that replaces it.
    maxLift: androidx.compose.ui.unit.Dp? = null,
    // The road you are on, shown in the handle row instead of the floating pill (issue #553).
    roadName: String? = null,
    // Pause in the bar's right slot; null leaves the slot as it was.
    onPause: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val amoled = isAppInAmoled()
    // Google's gesture: the ETA bar is the handle for the step list. Drag it UP and the card GROWS
    // with the finger, its bottom edge anchored and its top rising like a sheet, the step rows
    // showing in the space that opens under the figures; past NAV_BAR_LIFT_COMMIT_DP (or an
    // upward fling) it commits and StepsSheet takes over from the lifted edge with the same
    // header and rows; below that it springs back. The list button stays as the tap and D-pad
    // path (docs/dpad.md), so keypad phones lose nothing.
    val lift = remember { Animatable(0f) }
    val liftScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestSteps by rememberUpdatedState(onSteps)
    val maxLiftPx = with(density) { (maxLift ?: (LocalConfiguration.current.screenHeightDp * NAV_BAR_LIFT_MAX_FRACTION).dp).toPx() }
    // The preview's natural height (set in the well's layout pass): the drag never opens the
    // well past the rows it has, or the card would stand taller than the sheet that replaces it.
    val previewNaturalPx = remember { floatArrayOf(0f) }
    Card(
        modifier
            .fillMaxWidth()
            // dpad-ok: OK on NavBarTop's handle opens the step list, the same as this drag.
            .pointerInput(Unit) {
                val commitPx = with(density) { NAV_BAR_LIFT_COMMIT_DP.dp.toPx() }
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val cap = if (previewNaturalPx[0] > 0f) minOf(maxLiftPx, previewNaturalPx[0]) else maxLiftPx
                        liftScope.launch { lift.snapTo((lift.value + dy).coerceIn(-cap, 0f)) }
                    },
                    onDragEnd = {
                        val vy = tracker.calculateVelocity().y
                        val commit = -lift.value > commitPx || vy < -NAV_BAR_FLING_PX_S
                        liftScope.launch {
                            if (commit) {
                                // Left lifted on purpose: the sheet replaces this bar on the next
                                // frame, starting from the edge the finger left it at.
                                val fromDrag = onStepsFromDrag
                                if (fromDrag != null) fromDrag(-lift.value) else latestSteps()
                            } else {
                                lift.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { liftScope.launch { lift.animateTo(0f) } },
                )
            },
        // Match the banner's treatment: generous radius + shadow, a floating pill not a bar.
        // Google's bar is OLED BLACK in both themes (the bottom bar in the screenshots);
        // white figures sit on it, so it saves power and matches the map chrome.
        shape = navBarShape(),
        border = if (amoled) BorderStroke(1.dp, SheetPalette.BorderAmoled) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = navBarLook().bg,
            contentColor = navBarLook().ink,
        ),
    ) {
        // LAYOUT (issue #273): End on the LEFT as an icon, the trip figures CENTERD, Steps on the
        // right. Previously the figures sat left with two controls crowded right, one an icon and
        // one a labeled button - two different shapes doing the same job at the same size. As
        // icons they read as a matched pair with the numbers between them, which is also the one
        // arrangement where the two 54dp targets cannot be hit by mistake for each other.
        NavBarTop(
            remainingDistanceMeters = remainingDistanceMeters,
            remainingSeconds = remainingSeconds,
            offRoute = offRoute,
            paused = paused,
            onStop = onStop,
            onSteps = onSteps,
            trafficRatio = trafficRatio,
            showListButton = showListButton,
            handleUp = true,
            roadName = roadName,
            onPause = onPause,
        )
        // The well the drag opens under the figures: exactly the lift tall, clipped, holding the
        // step rows at the sheet's own list padding so they do not move at the handover. Read in
        // the layout phase, so the drag never recomposes the bar.
        if (preview != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val h = (-lift.value).roundToInt().coerceAtLeast(0)
                        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        previewNaturalPx[0] = p.height.toFloat()
                        layout(p.width, h) { p.place(0, 0) }
                    }
                    .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            ) { Column { preview() } }
        }
        if (navBarFlush()) Spacer(Modifier.navigationBarsPadding())
    }
}

/** A button on the nav bar, Google's dress and size: a thin ring on the bar's black, a white
 *  glyph. [filled] = the state it controls is on (the drive is paused). */
@Composable
fun NavBarButton(onClick: () -> Unit, filled: Boolean = false, end: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.material3.OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(60.dp).dpadHighlight(androidx.compose.foundation.shape.CircleShape),
        border = BorderStroke(1.5.dp, navBarLook().ink.copy(alpha = if (filled) 0f else 0.5f)),
        colors = androidx.compose.material3.IconButtonDefaults.outlinedIconButtonColors(
            containerColor = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else navBarLook().ink,
        ),
    ) { content() }
}

/** A button floating over the nav map: a black circle with a white glyph, which reads on any
 *  map; with wallpaper colors turned on (Settings > Appearance) it takes the theme's instead. */
@Composable
fun NavFab(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val themed = app.vela.ui.theme.wallpaperColorsInUse()
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick, modifier = modifier, shape = androidx.compose.foundation.shape.CircleShape,
        // Off wallpaper colors the buttons wear what the bar wears: white in the light theme, near black in the dark ones.
        containerColor = if (themed) MaterialTheme.colorScheme.primaryContainer else navBarLook().bg,
        contentColor = if (themed) MaterialTheme.colorScheme.onPrimaryContainer else navBarLook().ink,
    ) { content() }
}

/** Google's Re-center: a labeled pill at the bottom left while the camera is away from the car. */
@Composable
fun NavRecenterPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val themed = app.vela.ui.theme.wallpaperColorsInUse()
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
        shape = androidx.compose.foundation.shape.CircleShape,
        // Off wallpaper colors the buttons wear what the bar wears: white in the light theme, near black in the dark ones.
        containerColor = if (themed) MaterialTheme.colorScheme.primaryContainer else navBarLook().bg,
        contentColor = if (themed) MaterialTheme.colorScheme.onPrimaryContainer else navBarLook().ink,
    ) {
        Icon(SymOutlined.Navigation, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.mapscreen_recenter), style = MaterialTheme.typography.titleMedium)
    }
}

/** The nav bar's surface, shared with the step sheet it opens into: near black in every theme. */
val NavBarColor = androidx.compose.ui.graphics.Color(0xFF101214)

/** What the drive's bottom bar wears: the theme's own surface and inks under wallpaper colors,
 *  white with dark inks in the light theme, near-black with light inks in the dark ones. It was
 *  near-black in every theme for a while (Google's bar), which sat badly under a light app
 *  (issue #679). [onDark] says which set of fixed inks sits on it. */
class NavBarLook(val bg: Color, val ink: Color, val dim: Color, val onDark: Boolean)

@Composable
fun navBarLook(): NavBarLook =
    if (app.vela.ui.theme.wallpaperColorsInUse()) NavBarLook(
        MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurfaceVariant, isAppInDarkTheme(),
    ) else if (!isAppInDarkTheme()) NavBarLook(SheetPalette.bg(false, false), SheetPalette.InkLight, SheetPalette.DimLight, false)
    else NavBarLook(NavBarColor, SheetPalette.InkDark, SheetPalette.DimDark, true)

/** Portrait: the bar sits flush on the screen's bottom edge, like Google's. Landscape: a card. */
@Composable
fun navBarFlush(): Boolean = true

@Composable
fun navBarShape(): androidx.compose.ui.graphics.Shape =
    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** The bar's top: the chevron handle and the End | figures | list row. Drawn by [NavControls] and,
 *  with [handleUp] false, as the header of the expanded [StepsSheet] during nav, so the two are
 *  pixel-identical where they meet. */
@Composable
fun NavBarTop(
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    offRoute: Boolean,
    paused: Boolean = false,
    onStop: () -> Unit,
    onSteps: () -> Unit,
    trafficRatio: Double?,
    showListButton: Boolean,
    handleUp: Boolean,
    roadName: String? = null,
    // Pause in the bar's right slot (user 2026-09-18). Null keeps the slot empty, which is what a
    // touch phone had there: a 54 dp spacer holding the figures centered against End. The list
    // button still wins the slot when it is asked for.
    onPause: (() -> Unit)? = null,
) {
    val dark = isAppInDarkTheme()
    // The bar surface is OLED black (see NavControls), so the handle chevron and
    // the in-bar road name always use the dark inks - never the light sheet ink.
    val look = navBarLook()
    val barInk = look.ink
    val barDim = look.dim
    // No-traffic-signal ETA: Google's bar reads white-on-black here, not the
    // sheet ink - the bar is a black pill in both themes (see NavControls).
    val etaColor = when {
        trafficRatio == null -> look.ink
        trafficRatio > 1.4 -> SheetPalette.TrafficRed
        trafficRatio > 1.15 -> SheetPalette.TrafficAmber
        // Good-traffic green on the black pill: Google's vivid green, not the deep
        // light-theme value. A themed light bar takes the deep one.
        else -> if (look.onDark) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFF188038)
    }
    Column {
        // The handle: a grab bar that says "this lifts" (or "this closes", pointing down on the
        // expanded sheet), and a real button (tap, focus ring, OK) so the gesture is never the
        // only way in. Sits in the card's top padding.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                // A fixed 20 dp cut the road name's descenders (issue #617): the row grows to the
                // text when a name is shown.
                .then(if (roadName.isNullOrBlank()) Modifier.height(20.dp) else Modifier.heightIn(min = 20.dp))
                .dpadHighlight(RoundedCornerShape(10.dp))
                .clickable(onClick = onSteps),
            contentAlignment = Alignment.Center,
        ) {
            if (roadName.isNullOrBlank()) {
                // A flat grab bar, as on Google's: plainer to see on the black than the thin chevron.
                val handleCd = stringResource(if (handleUp) R.string.nav_steps_handle_cd else R.string.steps_close_cd)
                Box(
                    Modifier.size(width = 40.dp, height = 5.dp)
                        .background(barInk.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                        .semantics { contentDescription = handleCd },
                )
            } else {
                // The road you are on takes the handle row (issue #553); a small chevron stays
                // beside it so the row still reads as "this lifts".
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 56.dp)) {
                    Icon(
                        if (handleUp) Sym.KeyboardArrowUp else Sym.KeyboardArrowDown,
                        contentDescription = stringResource(if (handleUp) R.string.nav_steps_handle_cd else R.string.steps_close_cd),
                        tint = barDim,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        roadName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = barInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // End keeps a DESTRUCTIVE color rather than the tonal fill Steps uses: it is the one
            // control here that throws the drive away, and an unlabeled X must not look like just
            // another button. The label survives as its accessibility name.
            NavBarButton(onClick = onStop, end = true) {
                Icon(Sym.Close, contentDescription = stringResource(R.string.nav_end), modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Both lines SHRINK to fit rather than wrap or ellipsize: the 54dp buttons (and
                // any Interface-size scale) squeezed the column and "1 hr 25 min" wrapped rough,
                // while ellipsis on the second line cut off the arrival TIME (user 2026-07-11).
                FitDuration(formatDuration(remainingSeconds), style = MaterialTheme.typography.headlineSmall, color = etaColor)
                // While PAUSED nothing updates the nav state, so nothing would recompose this and
                // the arrival clock would sit frozen at whatever minute the stop began - the one
                // figure that should keep moving while you stand still, because it is what the stop
                // is costing you. A half-minute tick keeps it honest.
                var pausedTick by remember { mutableStateOf(0) }
                LaunchedEffect(paused) { while (paused) { kotlinx.coroutines.delay(30_000); pausedTick++ } }
                FitText(
                    formatDistance(remainingDistanceMeters) +
                        " · " + formatArrivalClock(remainingSeconds).also { pausedTick } +
                        when {
                            paused -> " · " + stringResource(R.string.nav_paused)
                            offRoute -> " · " + stringResource(R.string.nav_rerouting)
                            else -> ""
                        },
                    // A size up and nearly white: this line is read at a glance too.
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (paused) MaterialTheme.colorScheme.primary else if (look.onDark) androidx.compose.ui.graphics.Color(0xFFDADCE0) else barDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            // Bigger driving targets (user 2026-07-11, car-screen use): 54dp buttons, 26dp glyphs.
            // Both can want this slot: the step-list button is asked for by "Prefer buttons over
            // swipes", and pause is the default. Someone who asked for buttons gets both rather
            // than a silent choice between them; the figures column shrinks to fit (FitText).
            if (showListButton) {
                NavBarButton(onClick = onSteps) {
                    Icon(Sym.List, contentDescription = stringResource(R.string.nav_steps), modifier = Modifier.size(26.dp))
                }
            }
            if (showListButton && onPause != null) Spacer(Modifier.width(6.dp))
            if (onPause != null) {
                // Paused, it fills like End does: the bar already says "Paused" beside the figures,
                // and the control that put the drive on hold should look held.
                NavBarButton(onClick = onPause, filled = paused) {
                    Icon(
                        if (paused) Sym.PlayArrow else Sym.Pause,
                        contentDescription = stringResource(if (paused) R.string.nav_resume else R.string.nav_pause),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            if (!showListButton && onPause == null) {
                Spacer(Modifier.size(54.dp)) // keeps the figures centered against the End button
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
    destinationAddress: String = "",
    tripSeconds: Double,
    tripDistanceMeters: Double,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    // "Save the way you drove" (issue #622): shown when the drive left the planned route.
    onSaveDriven: ((String) -> Unit)? = null,
    drivenDefaultName: String = "",
) {
    var naming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (naming && onSaveDriven != null) {
        var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(drivenDefaultName) }
        app.vela.ui.VelaDialog(
            onDismissRequest = { naming = false },
            title = stringResource(R.string.route_save_title),
            confirmText = stringResource(R.string.list_save),
            onConfirm = { naming = false; onSaveDriven(draft) },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { naming = false },
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(60) },
                singleLine = true,
                label = { Text(stringResource(R.string.route_save_hint)) },
                modifier = Modifier.fillMaxWidth().dpadFieldEscape(),
            )
        }
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.CheckCircle, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text(stringResource(R.string.nav_arrived), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (destinationLabel.isNotBlank()) {
                        Text(destinationLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (destinationAddress.isNotBlank() && !destinationAddress.equals(destinationLabel, ignoreCase = true)) {
                        Text(destinationAddress, style = MaterialTheme.typography.bodyMedium)
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
            // Where the drive ended is where the car is: one tap saves it, the same as the map's
            // parking button. Not offered after a walk or a ride, or with that button hidden.
            if (app.vela.ui.ParkingButton.on.value && app.vela.ui.ParkingActions.arrivedByCar?.invoke() == true) {
                var parked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                val noFix = stringResource(R.string.map_parking_no_fix)
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        if (app.vela.ui.ParkingActions.saveHere?.invoke() == true) parked = true
                        else android.widget.Toast.makeText(context, noFix, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    enabled = !parked,
                    modifier = Modifier.fillMaxWidth().dpadHighlight(androidx.compose.material3.ButtonDefaults.outlinedShape),
                ) {
                    Icon(Sym.LocalParking, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(if (parked) R.string.map_parking_saved else R.string.map_parking_save))
                }
            }
            if (onSaveDriven != null) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.driven_route_title), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { naming = true },
                    modifier = Modifier.fillMaxWidth().dpadHighlight(androidx.compose.material3.ButtonDefaults.outlinedShape),
                ) {
                    Text(stringResource(R.string.driven_route_save))
                }
            }
            Spacer(Modifier.height(16.dp))
            // Key-first devices: the card opens on Done, so OK ends the trip.
            val doneFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().dpadAutoFocus(doneFocus).dpadHighlight(androidx.compose.material3.ButtonDefaults.shape),
            ) {
                Text(stringResource(R.string.nav_done))
            }
        }
    }
}

/**
 * The in-drive "add this as a stop?" card (Settings > Navigation, off by default). A tap on a place
 * while driving only OFFERS it; this card's button is the second tap that changes the drive, which
 * is the whole point: one stray touch at speed must not re-route you.
 *
 * The card takes itself away. [autoDismissMs] counts down on the ring around the close button and
 * calls [onDismiss] at zero, so an offer the driver ignores cannot sit over the map for the rest of
 * the drive; the countdown restarts whenever [name] changes, which is the next tap. Key paths get a
 * longer window from the caller, since reaching the button takes more presses than a thumb does.
 */
@Composable
fun NavStopOffer(
    name: String,
    meta: String,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Issue #604: the tapped place is already a stop, so the card also offers to take it out.
    onRemove: (() -> Unit)? = null,
    autoDismissMs: Long = 10_000L,
    // Changes on every offer, so a second tap on the same place restarts the clock.
    offerKey: Any = name,
) {
    // Restarts on a new place, and only on a new place: a recomposition while the same offer is up
    // (a speedo tick, the detour figure landing) must not give the driver back their ten seconds.
    val left = remember(offerKey, autoDismissMs) { androidx.compose.animation.core.Animatable(1f) }
    val dismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(offerKey, autoDismissMs) {
        left.snapTo(1f)
        left.animateTo(
            0f,
            androidx.compose.animation.core.tween(
                autoDismissMs.toInt(),
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        )
        dismiss.value()
    }
    val ringColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (onRemove != null) {
                androidx.compose.material3.TextButton(
                    onClick = onRemove,
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp)),
                ) { Text(stringResource(R.string.nav_stop_offer_remove)) }
            }
            androidx.compose.material3.Button(
                onClick = onAdd,
                modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp)),
            ) { Text(stringResource(R.string.nav_stop_offer_add)) }
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
            ) {
                // The ring is the clock: it says the offer is about to go without adding a number
                // to read at speed. Drawn behind the X, read in the draw phase so the countdown
                // never recomposes the card.
                androidx.compose.foundation.Canvas(Modifier.size(36.dp)) {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = -360f * left.value,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
                Icon(
                    Sym.Close,
                    contentDescription = stringResource(R.string.place_close_directions),
                )
            }
        }
    }
}

/**
 * The drive's two "hold something" controls behind ONE button (user 2026-09-18).
 *
 * Pause and mute used to sit as a two-target pill in the nav stack, which is 112 dp of a small
 * phone's right edge for two things you touch rarely. Now there is one 56 dp button: on a running
 * drive the FIRST tap slides mute out beside it for [OPEN_MS] and a second tap on the same button
 * pauses (pausing on the first tap made holding the drive the only way to reach mute); while
 * paused, one tap resumes. A LONG PRESS mutes outright, for people who know where it is. The button carries both states - the glyph is
 * pause or resume, the accent fill says the drive is held, and a muted drive wears a small crossed
 * speaker - because one control standing for two states has to show both.
 *
 * Long press is touch-only by nature; the slide-out is the key path to mute, which is what keeps
 * this D-pad legal.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NavHoldControls(
    paused: Boolean,
    muted: Boolean,
    onPause: () -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var openedAt by remember { mutableStateOf(0L) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(open, openedAt) {
        if (open) {
            kotlinx.coroutines.delay(OPEN_MS)
            open = false
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.End) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.End) +
                androidx.compose.animation.fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                HoldChoice(
                    icon = if (muted) Sym.VolumeOff else Sym.VolumeUp,
                    label = stringResource(if (muted) R.string.nav_unmute_voice else R.string.nav_mute_voice),
                    filled = false,
                ) { onMute(); open = false }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (paused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .dpadHighlight(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        // The FIRST tap only opens the pop-out (user 2026-09-18). Pausing on that
                        // tap meant the one way to reach mute was to hold the drive first, which
                        // is not what the reach was for; the second tap on the same target, which
                        // has not moved, pauses. Anyone who knows the long press never sees this.
                        onClick = {
                            // Paused, the glyph already says what a tap does, so resuming is never
                            // the two-tap case.
                            if (open || paused) onPause() else open = true
                            openedAt = System.currentTimeMillis()
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onMute()
                            open = false
                        },
                        onClickLabel = stringResource(
                            when {
                                paused -> R.string.nav_resume
                                open -> R.string.nav_pause
                                else -> R.string.nav_hold_controls
                            }
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Sym.PlayArrow else Sym.Pause,
                    contentDescription = stringResource(R.string.nav_hold_controls),
                )
                if (muted) {
                    // The second state, as a badge rather than a second button: a held drive and a
                    // silent one are different things and the button has to say which it is.
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(18.dp),
                    ) {
                        Icon(
                            Sym.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.padding(3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(56.dp)
            .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
            .dpadHighlight(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (filled) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current,
        )
    }
}

/** How long the pop-out waits before closing itself. Long enough to open it, look, and reach the
 *  second target from the wheel; short enough that it is never still there at the next junction. */
private const val OPEN_MS = 6_000L

/** A run of whitespace, compiled once (the banner used to compile it on every fix). */
private val WS_RUN = Regex("\\s+")
