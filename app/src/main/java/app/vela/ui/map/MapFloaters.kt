package app.vela.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.dpadHighlight
import app.vela.ui.icons.Sym

/** Small things that float over the map and decide for themselves when to show. One call from
 *  MapScreen, which has no room for more (see the size notes there). */
@Composable
fun BoxScope.MapFloaters(state: MapUiState, vm: MapViewModel, sheetTop: State<Int>) {
    if (state.replaying && !state.demoDriving) ReplayControls(state, vm)
    StreetViewThumb(state, vm, sheetTop)
}

/** Google's own Street View preview of the open place, above the place card's left corner (beside
 *  the panel in landscape); a tap opens the viewer. The search reply names the view (pano id and
 *  heading), so this is the image request and nothing else, and it follows the photo settings. */
@Composable
private fun BoxScope.StreetViewThumb(state: MapUiState, vm: MapViewModel, sheetTop: State<Int>) {
    val place = state.selected ?: return
    val pano = place.svPanoId ?: return
    if (state.navigating || state.directionsOpen || state.transitNav != null || state.streetView != null || state.streetViewLoading) return
    if (!app.vela.ui.LoadPhotos.on.value || app.vela.ui.PhotosOnTap.on.value || app.vela.ui.GoogleFree.on.value || state.offline) return
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val windowH = LocalView.current.height
    // Portrait: only while the card leaves map above it to float on.
    val room by remember(windowH) { derivedStateOf { sheetTop.value > windowH * 0.35f } }
    if (!landscape && !room) return
    val url = remember(pano, place.svYawDeg) {
        "https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=$pano&cb_client=maps_sv.tactile.gps" +
            "&w=408&h=240&yaw=${place.svYawDeg ?: 0.0}&pitch=0&thumbfov=100"
    }
    // No picture (no signal, or Google refused it): no box either. The Street View button on the
    // card is still the way in.
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    val shape = RoundedCornerShape(12.dp)
    val panel = sidePanelWidth()
    Surface(
        shape = shape,
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 4.dp,
        modifier = (
            if (landscape) Modifier.align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                .navigationBarsPadding().padding(start = panel + 12.dp, bottom = 44.dp) // clear of the map credit
            else Modifier.align(Alignment.TopStart).padding(start = 12.dp)
                .offset { IntOffset(0, sheetTop.value - 64.dp.roundToPx() - 12.dp.roundToPx()) }
            )
            .size(width = 96.dp, height = 64.dp)
            .dpadHighlight(shape)
            .clickable { vm.openStreetView(place) },
    ) {
        Box {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.place_street_view),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.clip(shape).background(Color(0xFF3C4043)),
            )
            Icon(
                Sym.Streetview, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp).background(Color(0x99000000), CircleShape).padding(2.dp),
            )
        }
    }
}

/** Drive chrome that comes back after the step list closes pops in (a short scale and fade)
 *  where it used to appear all at once. */
internal fun Modifier.popIn(): Modifier = composed {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        t.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 380f))
    }
    graphicsLayer { alpha = t.value.coerceIn(0f, 1f); scaleX = 0.8f + 0.2f * t.value; scaleY = 0.8f + 0.2f * t.value }
}
