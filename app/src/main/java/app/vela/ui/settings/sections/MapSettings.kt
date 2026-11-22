package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.StartView
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.settingsAnchor
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.ToggleRow

/** Map sub-screen: how the map looks and moves (traffic, transit, topography, layers button,
 * tilt, north lock, 3D, missing-building fill, house numbers). Cameras live under Navigation, places under Places
 * (settings reshuffle, 2026-09-17). */
@Composable
internal fun MapSettingsScreen(onBack: () -> Unit, homeSet: Boolean = false) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_map), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
        LiveTrafficRow(switchModifier = topRow)
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_transit_layer),
            checked = app.vela.ui.TransitLayer.on.value,
            onCheckedChange = { app.vela.ui.TransitLayer.set(context, it) },
            hint = stringResource(R.string.settings_transit_layer_hint),
        )
        // Which kinds of line (discussion #648). Shown only while the lines are on.
        if (app.vela.ui.TransitLayer.on.value) {
            ToggleRow(
                label = stringResource(R.string.settings_transit_lines_metro),
                checked = app.vela.ui.TransitLayer.metro.value,
                onCheckedChange = { app.vela.ui.TransitLayer.setMetro(context, it) },
            )
            ToggleRow(
                label = stringResource(R.string.settings_transit_lines_trains),
                checked = app.vela.ui.TransitLayer.trains.value,
                onCheckedChange = { app.vela.ui.TransitLayer.setTrains(context, it) },
            )
        }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_topography),
            checked = app.vela.ui.Topography.on.value,
            onCheckedChange = { app.vela.ui.Topography.set(context, it) },
            hint = stringResource(R.string.settings_topography_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_layers_button),
            checked = app.vela.ui.LayersButton.on.value,
            onCheckedChange = { app.vela.ui.LayersButton.set(context, it) },
            hint = stringResource(R.string.settings_layers_button_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_parking_button),
            checked = app.vela.ui.ParkingButton.on.value,
            onCheckedChange = { app.vela.ui.ParkingButton.set(context, it) },
            hint = stringResource(R.string.settings_parking_button_hint),
        )
        GroupDivider()
        AutoParkingRows()
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_two_finger_tilt),
            checked = app.vela.ui.MapTilt.on.value,
            onCheckedChange = { app.vela.ui.MapTilt.set(context, it) },
            hint = stringResource(R.string.settings_two_finger_tilt_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_keep_north_up),
            checked = app.vela.ui.NorthLock.on.value,
            onCheckedChange = { app.vela.ui.NorthLock.set(context, it) },
            hint = stringResource(R.string.settings_keep_north_up_hint),
        )
        GroupDivider()
        // Moot under "Keep north up", which holds every view north-up.
        if (!app.vela.ui.NorthLock.on.value) {
            ToggleRow(
                label = stringResource(R.string.settings_turn_with_travel),
                checked = app.vela.ui.TurnWithTravel.on.value,
                onCheckedChange = { app.vela.ui.TurnWithTravel.set(context, it) },
                hint = stringResource(R.string.settings_turn_with_travel_hint),
            )
            GroupDivider()
        }
        ToggleRow(
            label = stringResource(R.string.settings_buildings_3d),
            checked = app.vela.ui.Buildings3d.on.value,
            onCheckedChange = { app.vela.ui.Buildings3d.set(context, it) },
            hint = stringResource(R.string.settings_buildings_3d_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_building_overlay),
            checked = app.vela.ui.BuildingOverlay.on.value,
            onCheckedChange = { app.vela.ui.BuildingOverlay.set(context, it) },
            hint = stringResource(R.string.settings_building_overlay_hint),
        )
        GroupDivider()
        StartViewRows(homeSet)
        // Category shortcuts under the search bar (issue #654): the row, one button, or none.
        GroupDivider()
        Text(
            stringResource(R.string.settings_category_chips),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.CategoryChipsPref.Mode.ROW to stringResource(R.string.settings_category_chips_row),
            app.vela.ui.CategoryChipsPref.Mode.BUTTON to stringResource(R.string.settings_category_chips_button),
            app.vela.ui.CategoryChipsPref.Mode.HIDDEN to stringResource(R.string.settings_category_chips_hidden),
        ).forEach { (mode, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.CategoryChipsPref.mode.value == mode,
                onClick = { app.vela.ui.CategoryChipsPref.set(context, mode) },
            )
        }
        Hint(stringResource(R.string.settings_category_chips_hint))
        // House numbers: how far out they appear (issue #329). Numbers come from OpenStreetMap
        // and, in the US, OpenAddresses, so a missing number is usually missing data.
        GroupDivider()
        Text(
            stringResource(R.string.settings_house_numbers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.settingsAnchor(stringResource(R.string.settings_house_numbers)).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.HouseNumbers.NEAR to stringResource(R.string.settings_house_numbers_near),
            app.vela.ui.HouseNumbers.NORMAL to stringResource(R.string.settings_house_numbers_normal),
            app.vela.ui.HouseNumbers.FAR to stringResource(R.string.settings_house_numbers_far),
            app.vela.ui.HouseNumbers.FURTHEST to stringResource(R.string.settings_house_numbers_furthest),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.HouseNumbers.level.value == id,
                onClick = { app.vela.ui.HouseNumbers.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_house_numbers_hint))
        // Which name a road or place label shows (issue #738): English where OpenStreetMap has
        // one, then the basemap's machine romanization or the local script, or the local name alone.
        GroupDivider()
        Text(
            stringResource(R.string.settings_map_names),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.settingsAnchor(stringResource(R.string.settings_map_names)).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.MapNames.ENGLISH_LATIN to stringResource(R.string.settings_map_names_english_latin),
            app.vela.ui.MapNames.ENGLISH_LOCAL to stringResource(R.string.settings_map_names_english_local),
            app.vela.ui.MapNames.LOCAL to stringResource(R.string.settings_map_names_local),
            app.vela.ui.MapNames.LOCAL_ENGLISH to stringResource(R.string.settings_map_names_local_english),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.MapNames.mode.value == id,
                onClick = { app.vela.ui.MapNames.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_map_names_hint))
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** "Where the map opens": the phone's position, the view the map was left on, Home (offered only
 *  while one is saved), or a view picked from the map as it is now. A choice whose target is gone
 *  shows as "Where I am", which is how the map then opens. */
@Composable
private fun StartViewRows(homeSet: Boolean) {
    val context = LocalContext.current
    val title = stringResource(R.string.settings_start_view)
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.settingsAnchor(title).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
    val shown = app.vela.ui.shownStartMode(StartView.mode.value, homeSet, StartView.place.value != null)
    // Takes the view the map is on now, picks the option and says so.
    val useCurrent = {
        if (StartView.useLiveView(context)) {
            android.widget.Toast.makeText(context, context.getString(R.string.settings_start_view_saved), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    SelectableRow(
        label = stringResource(R.string.settings_start_view_here),
        selected = shown == StartView.HERE,
        onClick = { StartView.set(context, StartView.HERE) },
    )
    SelectableRow(
        label = stringResource(R.string.settings_start_view_last),
        selected = shown == StartView.LAST,
        onClick = { StartView.set(context, StartView.LAST) },
    )
    if (homeSet) {
        SelectableRow(
            label = stringResource(R.string.shortcut_home),
            selected = shown == StartView.HOME,
            onClick = { StartView.set(context, StartView.HOME) },
        )
    }
    SelectableRow(
        label = stringResource(R.string.settings_start_view_place),
        selected = shown == StartView.PLACE,
        // The first pick takes the current view. After that the row keeps the saved one and the
        // button below replaces it.
        onClick = { if (StartView.place.value != null) StartView.set(context, StartView.PLACE) else useCurrent() },
    )
    androidx.compose.foundation.layout.Box(Modifier.padding(start = 54.dp, end = 16.dp, bottom = 4.dp)) {
        androidx.compose.material3.FilledTonalButton(
            modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
            onClick = useCurrent,
        ) { Text(stringResource(R.string.settings_start_view_use_current)) }
    }
    // Driving mode (issue #727): "Where I am" in the drive's layout, for a mounted phone or tablet.
    SelectableRow(
        label = stringResource(R.string.settings_start_view_drive),
        selected = shown == StartView.DRIVE,
        onClick = { StartView.set(context, StartView.DRIVE) },
    )
    Hint(stringResource(R.string.settings_start_view_hint))
}

/** "Save where I park" and its Bluetooth option (AutoParking). The Bluetooth option asks for
 *  Nearby devices (Android 12 and up), then for location all the time (Android 10 and up; from
 *  11 the system shows its settings page for that). Nearby devices refused leaves it off;
 *  location all the time refused leaves it on, saving only when Vela saw the drive. */
@Composable
private fun AutoParkingRows() {
    val context = LocalContext.current
    val ap = app.vela.ui.AutoParking
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val background = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ap.setBluetooth(context, true) }
    fun askBackground() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            background.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            ap.setBluetooth(context, true)
        }
    }
    val nearby = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) askBackground()
        else android.widget.Toast.makeText(context, R.string.settings_auto_parking_bt_denied, android.widget.Toast.LENGTH_LONG).show()
    }
    ToggleRow(
        label = stringResource(R.string.settings_auto_parking),
        checked = ap.on.value,
        onCheckedChange = { ap.set(context, it) },
        hint = stringResource(R.string.settings_auto_parking_hint),
    )
    if (ap.on.value) {
        ToggleRow(
            label = stringResource(R.string.settings_auto_parking_bt),
            checked = ap.bluetooth.value,
            onCheckedChange = { on ->
                when {
                    !on -> ap.setBluetooth(context, false)
                    android.os.Build.VERSION.SDK_INT >= 31 && !granted(android.Manifest.permission.BLUETOOTH_CONNECT) ->
                        nearby.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    else -> askBackground()
                }
            },
            hint = stringResource(R.string.settings_auto_parking_bt_hint),
        )
    }
}
