package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.Hint
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadRowSibling

/** Map sub-screen: map-layer toggles (traffic, transit, topography, layers button, flock, 3D,
 * missing-building fill) plus the Places-on-the-map visibility + sizing group. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            label = stringResource(R.string.settings_flock),
            checked = app.vela.ui.Flock.on.value,
            onCheckedChange = { app.vela.ui.Flock.set(context, it) },
            hint = stringResource(R.string.settings_flock_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock_route_alert),
            checked = app.vela.ui.FlockRouteAlert.on.value,
            onCheckedChange = { app.vela.ui.FlockRouteAlert.set(context, it) },
            hint = stringResource(R.string.settings_flock_route_alert_hint),
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
        }

        // Places on the map: POI visibility + sizing (user 2026-07-15).
        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_map_places)) {
        ToggleRow(
            label = stringResource(R.string.settings_show_pois),
            checked = app.vela.ui.MapPoiPrefs.showPois.value,
            onCheckedChange = { app.vela.ui.MapPoiPrefs.setShowPois(context, it) },
            hint = stringResource(R.string.settings_show_pois_hint),
        )
        if (app.vela.ui.MapPoiPrefs.showPois.value) {
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
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 4.dp)) {
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
        Spacer(Modifier.height(24.dp))
    }
}
