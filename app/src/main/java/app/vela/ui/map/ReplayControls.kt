package app.vela.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.dpadHighlight
import app.vela.ui.icons.Sym

/** The speeds a trip replay offers, times real time. */
val REPLAY_SPEEDS = listOf(1f, 3f, 10f)

/**
 * A trip replay's controls, in place of the old Stop pill: a slider to move through the drive
 * (released = the replay restarts and runs silently up to that moment, so the turn card and the
 * route are right when it gets there), pause, the speed, and stop.
 */
@Composable
fun BoxScope.ReplayControls(state: MapUiState, vm: MapViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(bottom = 150.dp, start = 12.dp, end = 88.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(replayClock(((dragging ?: state.replayProgress) * state.replayTotalS).toInt()), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = dragging ?: state.replayProgress,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { vm.seekReplay(it) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(replayClock(state.replayTotalS), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { vm.setReplayPaused(!state.replayPaused) }, modifier = Modifier.dpadHighlight(CircleShape)) {
                    Icon(if (state.replayPaused) Sym.PlayArrow else Sym.Pause, contentDescription = stringResource(if (state.replayPaused) R.string.replay_play else R.string.replay_pause))
                }
                REPLAY_SPEEDS.forEach { sp ->
                    FilterChip(
                        selected = state.replaySpeed == sp, onClick = { vm.setReplaySpeed(sp) },
                        label = { Text("${sp.toInt()}×") }, shape = CircleShape, modifier = Modifier.dpadHighlight(CircleShape),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::stopReplay, modifier = Modifier.dpadHighlight(CircleShape)) {
                    Icon(Sym.Close, contentDescription = stringResource(R.string.mapscreen_stop_replay))
                }
            }
        }
    }
}

private fun replayClock(s: Int): String = "%d:%02d".format(s / 60, s % 60)
