package app.vela.ui.place

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
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
    // Back backs out of full screen first, then closes the viewer.
    BackHandler(onBack = { if (full) full = false else onClose() })
    BoxWithConstraints(
        Modifier.fillMaxWidth().fillMaxHeight(if (full) 1f else 0.55f).background(Color.Black),
    ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }

            // Live camera yaw / fov, polled each frame so the arrows track where you look.
            var yaw by remember { mutableFloatStateOf(0f) }
            var fov by remember { mutableFloatStateOf(75f) }

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
                DisposableEffect(view) { onDispose { view.onPause() } }
                // Re-aim on each new pano OR new view. A pano we were already showing (fullscreen
                // toggle) keeps the user's current yaw; a genuinely new pano faces its requested
                // direction (Google's yaw / look-at target / down the street).
                var seenPano by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(view, pano.panoId) {
                    val face = if (pano.panoId == seenPano) yaw
                    else (pano.initialFacingDeg ?: pano.headingDeg).toFloat()
                    seenPano = pano.panoId
                    view.setCompass(pano.headingDeg.toFloat(), face)
                }
                LaunchedEffect(view, bitmap) { bitmap?.let { view.setPanorama(it) } }
                // Report the pose to the map underneath: immediately on each pano (position hop),
                // then whenever the compass yaw moves ~a degree - not every frame, so the map's
                // cone update stays a trickle instead of a 60 Hz recomposition storm.
                var sentYaw by remember { mutableFloatStateOf(Float.NaN) }
                LaunchedEffect(pano.panoId) {
                    sentYaw = view.currentYawDeg()
                    onPose(pano.lat, pano.lng, sentYaw)
                }
                LaunchedEffect(view) {
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { }
                        yaw = view.currentYawDeg()
                        fov = view.currentFovDeg()
                        if (sentYaw.isNaN() || abs(normDeg(yaw - sentYaw)) > 1f) {
                            sentYaw = yaw
                            onPose(pano.lat, pano.lng, yaw)
                        }
                    }
                }
                key(view) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                }
            }

            // Walk arrows: one tappable chevron per neighbour that's within the visible arc. Shown in
            // historical views too (the neighbour graph is the base pano's, so you can still walk from
            // an older capture - it lands on that neighbour's imagery). Hidden only while loading.
            if (pano != null && bitmap != null && !loading) {
                val halfFov = fov * 0.5f
                for (link in pano.neighbors) {
                    val delta = normDeg(link.bearingDeg.toFloat() - yaw) // -180..180
                    if (abs(delta) > halfFov * 0.92f) continue
                    val fracX = delta / halfFov                          // -1..1 across the view
                    val xPx = wPx / 2f + fracX * (wPx * 0.42f)
                    val yPx = hPx * 0.66f
                    val arrowSize = 52.dp
                    val halfArrow = with(density) { arrowSize.toPx() / 2f }
                    Surface(
                        onClick = { onMove(link) },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier
                            .offset { IntOffset((xPx - halfArrow).roundToInt(), (yPx - halfArrow).roundToInt()) }
                            .size(arrowSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = stringResource(R.string.street_view_move),
                                tint = Color(0xFF1A1A1A),
                                modifier = Modifier.rotate(delta), // lean toward the street's direction
                            )
                        }
                    }
                }
            }

            if (loading || (pano != null && bitmap == null)) {
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
                        for (t in pano.history) {
                            val isShown = t.year == shownYear && t.month == shownMonth
                            Surface(
                                onClick = { open = false; onTimeTravel(t) },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isShown) Color.White else Color.Black.copy(alpha = 0.6f),
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
                    Surface(
                        onClick = { open = !open },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Icon(
                                Icons.Default.History,
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
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.steps_close_cd), tint = Color.White)
                }
            }

            // Fullscreen toggle (Google-style: half-screen over the map by default).
            Surface(
                onClick = { full = !full },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
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
