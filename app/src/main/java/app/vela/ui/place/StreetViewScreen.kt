package app.vela.ui.place

import app.vela.ui.icons.Sym

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.vela.R
import app.vela.core.model.StreetViewLink
import app.vela.core.model.StreetViewPano
import app.vela.core.model.StreetViewTime
import app.vela.streetview.PanoramaView
import app.vela.streetview.GroundArrows
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadAutoFocus
import androidx.compose.ui.focus.focusRequester
import app.vela.ui.map.coversMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-app Street View, Google-style HALF-SCREEN by default: the pano pane takes the top of the
 * screen and the live map stays visible underneath, showing a view-direction cone at the pano's
 * position (fed via [onPose]) that rotates as you look around and hops as you walk. A corner
 * button toggles full screen. The panorama renders on a GL sphere ([PanoramaView]); on top we
 * overlay **walk arrows** and a **date chip** that goes back in time when there are older captures.
 */
@Composable
fun StreetViewScreen(
    pano: StreetViewPano?,
    bitmap: Bitmap?,
    loading: Boolean,
    shownYear: Int?,
    shownMonth: Int?,
    historical: Boolean,
    onClose: () -> Unit,
    onMove: (StreetViewLink) -> Unit,
    onTimeTravel: (StreetViewTime) -> Unit,
    onPose: (lat: Double, lng: Double, yawDeg: Float) -> Unit = { _, _, _ -> },
) {
    var full by remember { mutableStateOf(false) }
    // D-pad focus ring (fork-only): pano look-around <-> the overlay controls. Predictable
    // ORDER, not spatial - the controls overlap the fullscreen pano box, so directional focus
    // movement can't separate them (docs/dpad.md).
    val rPano = remember { androidx.compose.ui.focus.FocusRequester() }
    val rClose = remember { androidx.compose.ui.focus.FocusRequester() }
    val rFull = remember { androidx.compose.ui.focus.FocusRequester() }
    val rTime = remember { androidx.compose.ui.focus.FocusRequester() }
    val hasTime = (pano?.history?.size ?: 0) > 1
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Arrow cycling for a control in the ring: forward -> next, back -> prev. OK falls
    // through (the Surface's own onClick activates it); BACK closes the whole viewer.
    fun ringKey(ev: androidx.compose.ui.input.key.KeyEvent, prev: androidx.compose.ui.focus.FocusRequester, next: androidx.compose.ui.focus.FocusRequester): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        return when (ev.key) {
            Key.DirectionDown, Key.DirectionRight -> { runCatching { next.requestFocus() }; true }
            Key.DirectionUp, Key.DirectionLeft -> { runCatching { prev.requestFocus() }; true }
            Key.Back -> { onClose(); true }
            else -> false
        }
    }
    // Back backs out of full screen first, then closes the viewer.
    BackHandler(onBack = { if (full) full = false else onClose() })
    BoxWithConstraints(
        Modifier.fillMaxWidth().fillMaxHeight(if (full) 1f else 0.55f).coversMap().background(Color.Black),
    ) {
            val density = LocalDensity.current
            val paneH = maxHeight // captured: BoxWithConstraints' receiver doesn't reach nested lambdas
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }

            // Live camera yaw / pitch / fov, polled each frame so the arrows track where you look.
            var yaw by remember { mutableFloatStateOf(0f) }
            var pitch by remember { mutableFloatStateOf(0f) }
            var fov by remember { mutableFloatStateOf(75f) }
            // A step toward a neighbor is under way: the view is moving forward, the arrows are
            // down, and the next pano keeps the direction you were looking (as Google's does).
            var walking by remember { mutableStateOf(false) }
            var panoView by remember { mutableStateOf<PanoramaView?>(null) }
            fun walk(link: StreetViewLink) {
                if (walking) return
                walking = true
                panoView?.beginWalk()
                onMove(link)
            }

            if (pano != null) {
                val ctx = LocalContext.current
                // ONE view per PANE SIZE - NOT keyed on panoId (a per-pano instance left the OLD
                // texture on screen after a walk), but RE-CREATED on the fullscreen toggle: a
                // SurfaceView's window hole doesn't follow a pure-Compose resize (device-caught
                // 2026-07-16: the GL render grew to full height but stayed CROPPED in the old
                // half-screen hole, reading as "fullscreen just zoomed"). key(view) makes
                // AndroidView actually swap instances; the effects below are view-keyed so the
                // fresh view gets the texture and the CURRENT look direction re-fed.
                val view = remember(full) { PanoramaView(ctx) }
                DisposableEffect(view) { panoView = view; onDispose { view.onPause() } }
                // Aim and texture land together, when the picture is in: aiming the new pano's
                // heading at the OLD picture swung it at every corner while the next one loaded.
                // A pano we were already showing (fullscreen toggle, time travel swapping in the
                // historical capture's heading) and a walk keep the user's compass yaw; a newly
                // opened pano faces its requested direction (Google's yaw, the look-at target).
                var seenPano by remember { mutableStateOf<String?>(null) }
                val aimPano by androidx.compose.runtime.rememberUpdatedState(pano)
                LaunchedEffect(view, bitmap) {
                    val bmp = bitmap?.takeUnless { it.isRecycled } ?: return@LaunchedEffect
                    val cur = aimPano
                    val face = if (seenPano != null && (cur.panoId == seenPano || walking)) yaw
                    else (cur.initialFacingDeg ?: cur.headingDeg).toFloat()
                    seenPano = cur.panoId
                    view.setPanorama(bmp, cur.headingDeg.toFloat(), face)
                    walking = false
                }
                // Report the pose to the map underneath: immediately on each pano (position hop),
                // then whenever the compass yaw moves ~a degree - not every frame, so the map's
                // cone update stays a trickle instead of a 60 Hz recomposition storm.
                var sentYaw by remember { mutableFloatStateOf(Float.NaN) }
                LaunchedEffect(pano.panoId) {
                    sentYaw = view.currentYawDeg()
                    onPose(pano.lat, pano.lng, sentYaw)
                }
                // The frame loop is keyed on `view` (which survives a pano change), so its closure
                // must read the CURRENT pano's position, not the one captured when it started -
                // otherwise moving the pano (map tap) then rotating reported the OLD coords and the
                // map puck snapped back to the start pano (user 2026-07-16).
                val posPano by androidx.compose.runtime.rememberUpdatedState(pano)
                LaunchedEffect(view) {
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { }
                        yaw = view.currentYawDeg()
                        pitch = view.currentPitchDeg()
                        fov = view.currentFovDeg()
                        if (sentYaw.isNaN() || abs(normDeg(yaw - sentYaw)) > 1f) {
                            sentYaw = yaw
                            onPose(posPano.lat, posPano.lng, yaw)
                        }
                    }
                }
                key(view) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                }
                // D-pad look-around (docs/dpad.md rule 3: the drag gesture needs a key path). The
                // engage model mirrors MapDpadController: focused-but-not-engaged, OK engages and
                // arrows fall through to reach the controls; engaged, arrows pan the sphere and
                // +/- zoom. BACK disengages first (the screen's BackHandler then closes). Opens
                // focused here so the first key looks around, not into the void.
                var engaged by remember(pano.panoId) { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .dpadAutoFocus(rPano)
                        .onKeyEvent { ev ->
                            val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                            if (!engaged) {
                                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent isOk
                                return@onKeyEvent when (ev.key) {
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { engaged = true; true }
                                    // Enter the control ring: forward lands on Close, back on the
                                    // last control (Time if present, else Fullscreen).
                                    Key.DirectionDown, Key.DirectionRight -> {
                                        runCatching { rClose.requestFocus() }; true
                                    }
                                    Key.DirectionUp, Key.DirectionLeft -> {
                                        runCatching { (if (hasTime) rTime else rFull).requestFocus() }; true
                                    }
                                    else -> false
                                }
                            }
                            val step = 0.28f
                            when (ev.type) {
                                KeyEventType.KeyDown -> when (ev.key) {
                                    // Signs mirror the drag-the-world touch convention so a key
                                    // press turns the VIEW in the arrow's direction (RIGHT looks
                                    // right), which is the natural key mapping - the inverse of dx/dy.
                                    Key.DirectionLeft -> { view.panByFraction(step, 0f); true }
                                    Key.DirectionRight -> { view.panByFraction(-step, 0f); true }
                                    Key.DirectionUp -> { view.panByFraction(0f, step); true }
                                    Key.DirectionDown -> { view.panByFraction(0f, -step); true }
                                    Key.ZoomIn, Key.Plus, Key.Equals -> { view.zoomStep(1.25f); true }
                                    Key.ZoomOut, Key.Minus -> { view.zoomStep(0.8f); true }
                                    Key.Back -> { engaged = false; true }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        val fwd = pano.neighbors.minByOrNull { abs(normDeg(it.bearingDeg.toFloat() - yaw)) }
                                        if (fwd != null && abs(normDeg(fwd.bearingDeg.toFloat() - yaw)) < 60f) walk(fwd)
                                        true
                                    }
                                    else -> false
                                }
                                KeyEventType.KeyUp -> ev.key in setOf(
                                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                                    Key.ZoomIn, Key.Plus, Key.Equals, Key.ZoomOut, Key.Minus,
                                )
                                else -> false
                            }
                        }
                        .focusable(),
                )
            }

            // Walk arrows: a chevron on the road per neighbor, projected through the panorama's
            // own camera (GroundArrows), so it turns and foreshortens as you look around. Shown in
            // historical views too (the neighbor graph is the base pano's, so you can still walk
            // from an older capture). Down while loading or mid-step, and they fade back in.
            val arrowsUp = pano != null && bitmap != null && !loading && !walking
            // In after a step lands; gone at once when one starts (a fade-out drew the next pano's
            // arrows over the old picture).
            val fadeIn by androidx.compose.animation.core.animateFloatAsState(if (arrowsUp) 1f else 0f, androidx.compose.animation.core.tween(if (arrowsUp) 220 else 0), label = "svArrows")
            val arrowAlpha = if (arrowsUp) fadeIn else 0f
            if (pano != null && arrowAlpha > 0f) {
                val scale = (wPx / hPx).coerceIn(0.6f, 1.4f).toDouble() * 0.8
                val shapes = pano.neighbors.map { link ->
                    GroundArrows.chevron(link.bearingDeg, yaw.toDouble(), pitch.toDouble(), fov.toDouble(), wPx, hPx, scale)
                        ?.takeIf { pts -> pts.all { it.x > -wPx * 0.5f && it.x < wPx * 1.5f && it.y < hPx * 1.2f } }
                }
                val centers = shapes.map { pts -> pts?.let { Offset(it.sumOf { p -> p.x.toDouble() }.toFloat() / it.size, it.sumOf { p -> p.y.toDouble() }.toFloat() / it.size) } }
                val reach = with(density) { 72.dp.toPx() }
                // A tap that misses a chevron's hit box but lands near it still walks.
                androidx.compose.runtime.SideEffect {
                    panoView?.onTap = { x, y ->
                        if (arrowsUp) GroundArrows.pick(Offset(x, y), centers, reach)?.let { walk(pano.neighbors[it]) }
                    }
                }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    for (pts in shapes) {
                        if (pts == null) continue
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
                            close()
                        }
                        // A soft shadow on the road, then the white chevron with a thin dark edge so it
                        // reads on pale concrete as well as on asphalt.
                        translate(0f, 3.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.28f * arrowAlpha)) }
                        drawPath(path, Color.White.copy(alpha = 0.94f * arrowAlpha))
                        drawPath(path, Color.Black.copy(alpha = 0.35f * arrowAlpha), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()))
                    }
                }
                // Invisible hit boxes on the chevrons, for TalkBack and D-pad focus.
                val moveLabel = stringResource(R.string.street_view_move)
                if (arrowsUp) pano.neighbors.forEachIndexed { i, link ->
                    val c = centers[i] ?: return@forEachIndexed
                    val box = 56.dp
                    val half = with(density) { box.toPx() / 2f }
                    Box(
                        Modifier
                            .offset { IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt()) }
                            .size(box)
                            .dpadHighlight(CircleShape)
                            .clip(CircleShape)
                            .clickable(onClickLabel = null) { walk(link) }
                            .semantics { contentDescription = moveLabel },
                    )
                }
            }

            // The spinner waits a moment on a step: the forward move covers a quick load, and a
            // spinner flashed over it reads as stutter. A first open has nothing to show, so at once.
            val busy = loading || (pano != null && bitmap == null)
            var spin by remember { mutableStateOf(false) }
            LaunchedEffect(busy) {
                spin = false
                if (busy) {
                    if (panoView != null && pano != null) kotlinx.coroutines.delay(700)
                    spin = true
                }
            }
            if (busy && spin) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            // Attribution: place label, the shown capture date, and the Google copyright. The
            // copyright's year is set to the SHOWN capture year (not Google's current-year field),
            // so it matches the imagery and updates when you go back in time - "© 2026 Google" over
            // a May 2024 pano read wrong (user 2026-07-15).
            val label = pano?.addressLabel
            // Non-breaking spaces INSIDE the date and copyright tokens so they never split across a
            // wrap - the end-cap was breaking "© 2024 Google" onto two lines, leaving the "©" stranded
            // above "Google" (user 2026-07-15). Wraps now only happen at the " · " separators.
            val date = monthYear(shownYear, shownMonth)?.replace(' ', '\u00A0')
            val copy = run {
                val base = pano?.copyright ?: "© Google"
                val withYear = if (shownYear != null && Regex("\\d{4}").containsMatchIn(base))
                    base.replaceFirst(Regex("\\d{4}"), shownYear.toString())
                else base
                withYear.replace(' ', '\u00A0')
            }
            Text(
                text = listOfNotNull(label?.takeIf { it.isNotBlank() }, date, copy).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.85f),
                // End-cap so a long attribution wraps on the left rather than running under the
                // bottom-right time-machine chip (user 2026-07-15: they collided).
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp, end = 84.dp),
            )

            // Time machine: a chip bottom-right when the spot has more than one capture. Tapping it
            // reveals the dates (newest first) above it; picking one goes to that capture.
            if (pano != null && pano.history.size > 1) {
                var open by remember(pano.panoId) { mutableStateOf(false) }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    // Raised clear of the attribution row so the chip and its expanded date list
                    // never sit on top of the copyright text (user 2026-07-15).
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 56.dp),
                ) {
                    if (open) {
                        // Cap the date list to the pane's own height minus room for the toggle,
                        // the bottom padding, and the top-right fullscreen button (user 2026-07-16:
                        // a spot with many years spilled off the top and occluded that button).
                        // The reserve INCLUDES the status-bar inset the fullscreen button sits under
                        // (a fixed dp reserve let the list top reach the button on tall status bars);
                        // newest-first, and the list SCROLLS when the captures outnumber the cap.
                        val topReserve = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 200.dp
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .heightIn(max = (paneH - topReserve).coerceAtLeast(96.dp))
                                .verticalScroll(rememberScrollState()),
                        ) {
                        for (t in pano.history) {
                            val isShown = t.year == shownYear && t.month == shownMonth
                            Surface(
                                onClick = { open = false; onTimeTravel(t) },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isShown) Color.White else Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .onKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                                            open = false; runCatching { rTime.requestFocus() }; true
                                        } else false
                                    }
                                    .dpadHighlight(RoundedCornerShape(20.dp)),
                            ) {
                                Text(
                                    monthYear(t.year, t.month) ?: "",
                                    color = if (isShown) Color(0xFF1A1A1A) else Color.White,
                                    fontWeight = if (isShown) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        }
                    }
                    Surface(
                        onClick = { open = !open },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .focusRequester(rTime)
                            .onKeyEvent { ev ->
                                if (open && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true
                                } else ringKey(ev, rFull, rPano)
                            }
                            .dpadHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Icon(
                                Sym.History,
                                contentDescription = stringResource(R.string.street_view_dates),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Close.
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)
                    .focusRequester(rClose)
                    .onKeyEvent { ringKey(it, if (hasTime) rTime else rFull, rFull) }
                    .dpadHighlight(CircleShape).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Sym.Close, contentDescription = stringResource(R.string.steps_close_cd), tint = Color.White)
                }
            }

            // Fullscreen toggle (Google-style: half-screen over the map by default).
            Surface(
                onClick = { full = !full },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                    .focusRequester(rFull)
                    .onKeyEvent { ringKey(it, rClose, if (hasTime) rTime else rPano) }
                    .dpadHighlight(CircleShape).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (full) Sym.FullscreenExit else Sym.Fullscreen,
                        contentDescription = stringResource(R.string.street_view_fullscreen),
                        tint = Color.White,
                    )
                }
            }
    }
}

/** Smallest signed angle a→ into [-180, 180]. */
private fun normDeg(d: Float): Float {
    var x = (d + 540f) % 360f - 180f
    if (x < -180f) x += 360f
    return x
}

/** "May 2025" in the app locale, or null if we don't have a date. */
private fun monthYear(year: Int?, month: Int?): String? {
    if (year == null || month == null || month !in 1..12) return null
    val name = java.text.DateFormatSymbols().months.getOrNull(month - 1) ?: return year.toString()
    return "$name $year"
}
