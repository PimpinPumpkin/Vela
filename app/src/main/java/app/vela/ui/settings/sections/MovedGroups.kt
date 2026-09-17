package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadRowSibling
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.ToggleRow

// Settings groups shared across screens after the 2026-09-17 reshuffle.

/** Surveillance and speed cameras: the map markers and every alert about them, one group
 *  (moved from Map to Navigation, 2026-09-17: they are driving alerts first). */
@Composable
internal fun CameraSettingsGroup() {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.settings_cameras_group)) {
        ToggleRow(
            label = stringResource(R.string.settings_flock),
            checked = app.vela.ui.Flock.on.value,
            onCheckedChange = { app.vela.ui.Flock.set(context, it) },
            hint = stringResource(R.string.settings_flock_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_speed_cams),
            checked = app.vela.ui.SpeedCams.on.value,
            onCheckedChange = { app.vela.ui.SpeedCams.set(context, it) },
            hint = stringResource(R.string.settings_speed_cams_hint),
        )
        // The spoken warning is nested: it only means anything once the cameras are being tracked,
        // and it is its own opt-in because being spoken to is a different ask from seeing a marker
        // (and is restricted in some countries).
        if (app.vela.ui.SpeedCams.on.value) {
            ToggleRow(
                label = stringResource(R.string.settings_speed_cam_warn),
                checked = app.vela.ui.SpeedCamWarn.on.value,
                onCheckedChange = { app.vela.ui.SpeedCamWarn.set(context, it) },
                hint = stringResource(R.string.settings_speed_cam_warn_hint),
            )
        }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock_route_alert),
            checked = app.vela.ui.FlockRouteAlert.on.value,
            onCheckedChange = { app.vela.ui.FlockRouteAlert.set(context, it) },
            hint = stringResource(R.string.settings_flock_route_alert_hint),
        )
        // Plate cameras coming up while navigating: a heads-up card and a spoken line, each its
        // own opt-in. Not nested under the layer toggle: the bundled dataset is loaded either way.
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock_nav_card),
            checked = app.vela.ui.FlockNavAlert.card.value,
            onCheckedChange = { app.vela.ui.FlockNavAlert.setCard(context, it) },
            hint = stringResource(R.string.settings_flock_nav_card_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock_nav_voice),
            checked = app.vela.ui.FlockNavAlert.voice.value,
            onCheckedChange = { app.vela.ui.FlockNavAlert.setVoice(context, it) },
            hint = stringResource(R.string.settings_flock_nav_voice_hint),
        )
    }
}

/** What the map draws for places: the master switch, tapped-place lookup, civic places,
 *  transit stops and icon size (moved from Map to Places, 2026-09-17). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PlacesOnMapGroup() {
    val context = LocalContext.current
        SettingsGroup(title = stringResource(R.string.settings_map_places)) {
        ToggleRow(
            label = stringResource(R.string.settings_show_pois),
            checked = app.vela.ui.MapPoiPrefs.showPois.value,
            onCheckedChange = { app.vela.ui.MapPoiPrefs.setShowPois(context, it) },
            hint = stringResource(R.string.settings_show_pois_hint),
        )
        if (app.vela.ui.MapPoiPrefs.showPois.value) {
            if (app.vela.ui.MapPoiPrefs.openPlaces) {
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_places_lookup),
                    checked = app.vela.ui.MapPoiPrefs.lookupTappedPlaces.value,
                    onCheckedChange = { app.vela.ui.MapPoiPrefs.setLookupTappedPlaces(context, it) },
                    hint = stringResource(R.string.settings_places_lookup_hint),
                )
            }
            GroupDivider()
            ToggleRow(
                label = stringResource(R.string.settings_show_civic),
                checked = app.vela.ui.MapPoiPrefs.showCivic.value,
                onCheckedChange = { app.vela.ui.MapPoiPrefs.setShowCivic(context, it) },
                hint = stringResource(R.string.settings_show_civic_hint),
            )
        }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_show_transit_stops),
            checked = app.vela.ui.MapPoiPrefs.showTransit.value,
            onCheckedChange = { app.vela.ui.MapPoiPrefs.setShowTransit(context, it) },
            hint = stringResource(R.string.settings_show_transit_stops_hint),
        )
        GroupDivider()
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.settings_poi_icon_size),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val sizeFocus = remember { List(3) { FocusRequester() } }
                listOf(
                    R.string.settings_poi_size_small to 0.7f,
                    R.string.settings_poi_size_default to 1.0f,
                    R.string.settings_poi_size_large to 1.25f,
                ).forEachIndexed { i, (label, value) ->
                    FilterChip(
                        selected = kotlin.math.abs(app.vela.ui.MapPoiPrefs.iconScale.floatValue - value) < 0.01f,
                        onClick = { app.vela.ui.MapPoiPrefs.setIconScale(context, value) },
                        label = { Text(stringResource(label)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier
                            .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                            .dpadRowSibling(sizeFocus, i),
                    )
                }
            }
            Hint(stringResource(R.string.settings_poi_icon_size_hint))
        }
        }
}

/** Where the map's places come from: Vela data, Google, or both, with what each costs. */
@Composable
internal fun PlacesSourceGroup(topRow: Modifier = Modifier) {
    val context = LocalContext.current
        SettingsGroup {
            // Where the map's businesses come from (Map, then Privacy on 2026-09-16, then Places on
            // 2026-09-17 so everything about places sits together; the hints still spell out what
            // leaves the phone as you pan). Each option states its own cost so the choice
            // is the user's: open data is offline and quiet, Google is complete and chatty, both
            // is the open layer plus one Google fetch per settled view.
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.settings_places_source),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            listOf(
                app.vela.ui.MapPoiPrefs.SOURCE_OPEN to R.string.settings_places_source_open,
                app.vela.ui.MapPoiPrefs.SOURCE_GOOGLE to R.string.settings_places_source_google,
                app.vela.ui.MapPoiPrefs.SOURCE_BOTH to R.string.settings_places_source_both,
            ).forEachIndexed { i, (id, label) ->
                SelectableRow(
                    modifier = if (i == 0) topRow else Modifier,
                    label = stringResource(label),
                    selected = app.vela.ui.MapPoiPrefs.placesSource.value == id,
                    onClick = { app.vela.ui.MapPoiPrefs.setPlacesSource(context, id) },
                )
            }
            Hint(
                stringResource(
                    when (app.vela.ui.MapPoiPrefs.placesSource.value) {
                        app.vela.ui.MapPoiPrefs.SOURCE_GOOGLE -> R.string.settings_places_source_google_hint
                        app.vela.ui.MapPoiPrefs.SOURCE_BOTH -> R.string.settings_places_source_both_hint
                        else -> R.string.settings_places_source_open_hint
                    },
                ),
            )
            // The short hints carry what matters; the rest (who maintains the data, where Vela
            // serves it from, what still touches Google) lives behind Learn more.
            var placesInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.TextButton(
                onClick = { placesInfo = true },
                modifier = Modifier.padding(start = 8.dp).dpadHighlight(androidx.compose.foundation.shape.CircleShape),
            ) { Text(stringResource(R.string.settings_places_source_more)) }
            if (placesInfo) {
                app.vela.ui.VelaDialog(
                    onDismissRequest = { placesInfo = false },
                    title = stringResource(R.string.settings_places_source_more_title),
                    text = { Text(stringResource(R.string.settings_places_source_more_body)) },
                    confirmText = stringResource(android.R.string.ok),
                    onConfirm = { placesInfo = false },
                    dismissText = stringResource(R.string.settings_places_source_more_credit),
                    onDismiss = {
                        placesInfo = false
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://overturemaps.org/")),
                            )
                        }
                    },
                    dismissLowEmphasis = true,
                )
            }
        }
}

/** The ~2 min traffic and route re-check during navigation (a Google request each time). */
@Composable
internal fun LiveRechecksGroup(vm: app.vela.ui.map.MapViewModel) {
        var liveRechecks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.liveRechecksOn()) }
        SettingsGroup {
        app.vela.ui.settings.ToggleRow(
            label = stringResource(R.string.settings_live_rechecks),
            checked = liveRechecks,
            onCheckedChange = { on ->
                liveRechecks = on
                vm.setLiveRechecks(on)
            },
            hint = stringResource(R.string.settings_live_rechecks_hint),
        )
        }
}

/** Simulated drive and simulated location, for demos and screenshots (moved from Navigation to
 *  Diagnostics, 2026-09-17: they are test tools, and leaving one on breaks real navigation). */
@Composable
internal fun DemoModesGroup(vm: app.vela.ui.map.MapViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
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
}

/** Recent "parked here" saves, so an accidental overwrite is recoverable (moved from Navigation
 *  to Saved places, 2026-09-17). */
@Composable
internal fun ParkingHistoryGroup(vm: app.vela.ui.map.MapViewModel) {
        // Parking history - recent "parked here" saves, so an accidental overwrite is
        // recoverable (also reachable by long-pressing the P button on the map).
        // Always present (issue #426): the settings search lists "Parking history", and a group
        // that only existed once you had parked led the match to nothing on a fresh install.
        val state by vm.state.collectAsStateWithLifecycle()
        run {
            Spacer(Modifier.height(8.dp))
            SettingsGroup(title = stringResource(R.string.settings_parking_history)) {
            if (state.parkingHistory.isEmpty()) {
                Hint(stringResource(R.string.settings_parking_history_empty))
            } else {
            Hint(stringResource(R.string.settings_parking_history_hint))
            Box(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.clearParkingHistory() }) { Text(stringResource(R.string.parking_history_clear_all)) }
            }
            }
            state.parkingHistory.forEachIndexed { pi, entry ->
                if (pi > 0) GroupDivider()
                val isCurrent = entry.savedAtMillis == state.parkedAtMillis
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
                        app.vela.ui.formatDateTime(androidx.compose.ui.platform.LocalContext.current, entry.savedAtMillis),
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
}
