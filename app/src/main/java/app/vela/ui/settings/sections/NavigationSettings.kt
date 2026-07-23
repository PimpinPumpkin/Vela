package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.core.feedback.Haptics
import app.vela.core.model.TravelMode
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadRowSibling // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

/** Navigation sub-screen: guidance toggles, vibrate chips, parking history, demo modes. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NavigationSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
    SettingsScaffold(stringResource(R.string.settings_navigation), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_nav", true)) }
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_keep_screen_on),
            checked = keepAwake,
            onCheckedChange = {
                keepAwake = it
                prefs.edit().putBoolean("keep_screen_on_nav", it).apply()
            },
            hint = stringResource(R.string.settings_keep_screen_on_hint),
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )

        var trafficLights by remember { mutableStateOf(prefs.getBoolean("nav_traffic_lights", false)) }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_traffic_lights),
            checked = trafficLights,
            onCheckedChange = {
                trafficLights = it
                prefs.edit().putBoolean("nav_traffic_lights", it).apply()
            },
            hint = stringResource(R.string.settings_traffic_lights_hint),
        )
        }

        SettingsGroup {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_vibrate_on_turns), style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        // One chip per travel mode (was four stacked switch rows - a lot of vertical space
        // for a setting most people touch once). Selected = that mode vibrates at turns.
        // FlowRow, not a scrollable Row: on a narrow screen / low density the fourth chip
        // rendered partially cut with no hint that the row scrolls (user report, 2026-07-16) -
        // wrapping onto a second line keeps every chip fully visible instead.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The ROOT swallows bare LEFT/RIGHT (SettingsScaffold), so this horizontal row drives
            // its OWN LEFT/RIGHT via FocusRequesters - requestFocus (not moveFocus) never clears at
            // the ends, and consuming the key stops it reaching the root swallow.
            val chipFocus = remember { List(4) { FocusRequester() } }
            listOf(
                TravelMode.DRIVE to stringResource(R.string.settings_mode_driving),
                TravelMode.WALK to stringResource(R.string.settings_mode_walking),
                TravelMode.BICYCLE to stringResource(R.string.settings_mode_cycling),
                TravelMode.TRANSIT to stringResource(R.string.settings_mode_transit),
            ).forEachIndexed { i, (mode, label) ->
                var on by remember(mode) {
                    val default = if (!prefs.getBoolean(Haptics.KEY, true)) false else Haptics.defaultFor(mode)
                    mutableStateOf(prefs.getBoolean(Haptics.keyFor(mode), default))
                }
                FilterChip(
                    selected = on,
                    onClick = {
                        on = !on
                        prefs.edit().putBoolean(Haptics.keyFor(mode), on).apply()
                    },
                    label = { Text(label) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier
                        .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                        .focusRequester(chipFocus[i])
                        .onKeyEvent { ev ->
                            if (ev.key == Key.DirectionRight || ev.key == Key.DirectionLeft) {
                                if (ev.type == KeyEventType.KeyDown) {
                                    if (ev.key == Key.DirectionRight && i < chipFocus.lastIndex) chipFocus[i + 1].requestFocus()
                                    if (ev.key == Key.DirectionLeft && i > 0) chipFocus[i - 1].requestFocus()
                                }
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
        Hint(stringResource(R.string.settings_vibrate_hint))
        }
        }

        var demoDrive by remember { mutableStateOf(prefs.getBoolean("demo_drive", false)) }
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_demo_drive),
            checked = demoDrive,
            onCheckedChange = {
                demoDrive = it
                prefs.edit().putBoolean("demo_drive", it).apply()
            },
            hint = stringResource(R.string.settings_demo_drive_hint),
        )

        // Simulated location - pretend to be at the current map centre (for demos / screenshots
        // without leaking where you actually are). Reactive holder so the switch reflects state.
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_sim_location),
            checked = app.vela.ui.SimLocation.on,
            onCheckedChange = { on -> if (on) vm.simulateLocationHere() else vm.stopSimulateLocation() },
            hint = stringResource(R.string.settings_sim_location_hint),
        )
        }

        // Parking history - recent "parked here" saves, so an accidental overwrite is
        // recoverable (also reachable by long-pressing the P button on the map).
        val state by vm.state.collectAsStateWithLifecycle()
        if (state.parkingHistory.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup(title = stringResource(R.string.settings_parking_history)) {
            Box(Modifier.padding(horizontal = 4.dp)) { Hint(stringResource(R.string.settings_parking_history_hint)) }
            Box(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = { vm.clearParkingHistory() }) { Text(stringResource(R.string.parking_history_clear_all)) }
            }
            state.parkingHistory.forEachIndexed { pi, entry ->
                if (pi > 0) GroupDivider()
                val isCurrent = entry.savedAtMillis == state.parkedAtMillis
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.LocalParking,
                        contentDescription = null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(entry.savedAtMillis)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Text(stringResource(R.string.parking_history_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        // D-pad: Restore/Delete sit side by side inside the L/R-swallowing Column,
                        // so the pair drives its own LEFT/RIGHT (issue #24 pattern).
                        val rowFocus = remember(entry.savedAtMillis) { List(2) { FocusRequester() } }
                        TextButton(modifier = Modifier.dpadRowSibling(rowFocus, 0), onClick = { vm.restoreParkingFromHistory(entry) }) { Text(stringResource(R.string.parking_history_restore)) }
                        IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape).dpadRowSibling(rowFocus, 1), onClick = { vm.deleteParkingHistoryEntry(entry) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.parking_history_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
