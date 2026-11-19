package app.vela.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R


/**
 * The D-pad map target (docs/dpad.md), drawn while the map is the primary surface. It has two
 * stages so the chrome stays reachable:
 * - Focused (the "OK: move the map" pill): a normal focus stop. Arrows move focus to the search
 *   bar, chips, zoom buttons and other controls; OK engages.
 * - Engaged (crosshair and edge ring): arrows pan, OK taps at the crosshair or confirms a Choose
 *   on map pick, holding OK long-presses, plus, minus and the zoom keys zoom, Back disengages and
 *   focus stays on the target.
 * With a list, sheet or panel open the panel owns focus, so MapScreen does not draw this. Choose
 * on map engages it at once. Its own composable because MapScreen is at ART's verifier limit.
 */
@Composable
internal fun BoxScope.MapDpadTarget(
    state: MapUiState,
    vm: MapViewModel,
    mapDpad: MapDpadController,
    focusRequester: FocusRequester,
    engagedState: MutableState<Boolean>,
) {
    var mapEngaged by engagedState
    var mapFocused by remember { mutableStateOf(false) }
    if (mapEngaged) {
        // Screen-edge ring: unmistakable "the MAP has the keys now" signal.
        Box(
            Modifier
                .matchParentSize()
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
    }
    val centerHeld = remember { booleanArrayOf(false) } // long-press fired for the held OK
    Box(
        Modifier
            .align(Alignment.Center)
            .size(140.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                mapFocused = it.isFocused
                if (!it.isFocused) mapEngaged = false
            }
            .onKeyEvent { ev ->
                if (!mapFocused) return@onKeyEvent false
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (!mapEngaged) {
                    // Plain focus stop: only OK does anything (engage); arrows fall
                    // through to normal focus traversal so the chrome is reachable.
                    return@onKeyEvent when {
                        isOk && ev.type == KeyEventType.KeyDown -> true
                        isOk && ev.type == KeyEventType.KeyUp -> { mapEngaged = true; true }
                        else -> false
                    }
                }
                val pan = 0.22f // fraction of the view per press; holds auto-repeat
                when (ev.type) {
                    KeyEventType.KeyDown -> when (ev.key) {
                        Key.DirectionUp -> { mapDpad.panBy(0f, -pan); true }
                        Key.DirectionDown -> { mapDpad.panBy(0f, pan); true }
                        Key.DirectionLeft -> { mapDpad.panBy(-pan, 0f); true }
                        Key.DirectionRight -> { mapDpad.panBy(pan, 0f); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val n = ev.nativeKeyEvent
                            if (n.repeatCount == 0) {
                                centerHeld[0] = false
                            } else if (!centerHeld[0] && n.eventTime - n.downTime >= 500) {
                                centerHeld[0] = true
                                mapDpad.longPressAtCenter()
                            }
                            true
                        }
                        Key.ZoomIn, Key.Plus, Key.Equals -> { mapDpad.zoomBy(1.0); true }
                        Key.ZoomOut, Key.Minus -> { mapDpad.zoomBy(-1.0); true }
                        else -> false
                    }
                    KeyEventType.KeyUp -> when (ev.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                        Key.ZoomIn, Key.Plus, Key.Equals, Key.ZoomOut, Key.Minus,
                        -> true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (!centerHeld[0]) {
                                // In Choose-on-map mode OK = the crosshair confirm;
                                // otherwise it's a tap at the crosshair.
                                if (state.pickOnMap != null) vm.confirmMapPick() else mapDpad.selectAtCenter()
                            }
                            centerHeld[0] = false
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Crosshair only while the map is key-driven; Choose-on-map draws its own.
            mapEngaged && state.pickOnMap == null -> {
                val crossColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(36.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(Color.White, radius = size.minDimension * 0.30f, style = Stroke(7f))
                    drawCircle(crossColor, radius = size.minDimension * 0.30f, style = Stroke(3.5f))
                    drawLine(crossColor, Offset(cx, 0f), Offset(cx, cy - 8f), 3.5f)
                    drawLine(crossColor, Offset(cx, cy + 8f), Offset(cx, size.height), 3.5f)
                    drawLine(crossColor, Offset(0f, cy), Offset(cx - 8f, cy), 3.5f)
                    drawLine(crossColor, Offset(cx + 8f, cy), Offset(size.width, cy), 3.5f)
                }
            }
            // Focused but not engaged: a visible stop + how to enter map control.
            // In Choose-on-map mode draw NOTHING here: the ChooseOnMapOverlay
            // supplies the pin + "Move the map to set…" banner, and the target is
            // auto-engaged so arrows already pan; the "OK: move the map" pill would
            // be wrong there (OK confirms the pick, it doesn't enter map control).
            mapFocused && state.pickOnMap == null -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 4.dp,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    stringResource(R.string.mapscreen_dpad_engage),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
