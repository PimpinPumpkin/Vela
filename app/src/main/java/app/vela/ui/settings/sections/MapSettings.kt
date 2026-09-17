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
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.ToggleRow

/** Map sub-screen: how the map looks (traffic, transit, topography, layers button, 3D,
 * missing-building fill, house numbers). Cameras live under Navigation, places under Places
 * (settings reshuffle, 2026-09-17). */
@Composable
internal fun MapSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_map), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_live_traffic),
            checked = app.vela.ui.Traffic.on.value,
            onCheckedChange = { app.vela.ui.Traffic.set(context, it) },
            hint = stringResource(R.string.settings_live_traffic_hint),
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_transit_layer),
            checked = app.vela.ui.TransitLayer.on.value,
            onCheckedChange = { app.vela.ui.TransitLayer.set(context, it) },
            hint = stringResource(R.string.settings_transit_layer_hint),
        )
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
        // House numbers: how far out they appear (issue #329). Numbers come from OpenStreetMap
        // and, in the US, OpenAddresses, so a missing number is usually missing data.
        GroupDivider()
        Text(
            stringResource(R.string.settings_house_numbers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.HouseNumbers.NEAR to stringResource(R.string.settings_house_numbers_near),
            app.vela.ui.HouseNumbers.NORMAL to stringResource(R.string.settings_house_numbers_normal),
            app.vela.ui.HouseNumbers.FAR to stringResource(R.string.settings_house_numbers_far),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.HouseNumbers.level.value == id,
                onClick = { app.vela.ui.HouseNumbers.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_house_numbers_hint))
        }

        Spacer(Modifier.height(24.dp))
    }
}
