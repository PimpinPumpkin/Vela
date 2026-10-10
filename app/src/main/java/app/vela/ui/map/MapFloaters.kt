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
 *  MapScreen, which has no room for more (see the size notes there). The Street View preview
 *  that floated above the place card until 2026-10-10 is the first tile of the place sheet's
 *  photo strip now (`StreetViewTile`, issue #724). */
@Composable
internal fun BoxScope.MapFloaters(state: MapUiState, vm: MapViewModel) {
    if (state.replaying && !state.demoDriving) ReplayControls(state, vm)
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
