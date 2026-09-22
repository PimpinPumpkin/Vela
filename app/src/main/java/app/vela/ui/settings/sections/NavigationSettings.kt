package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.vela.R
import app.vela.core.feedback.Haptics
import app.vela.core.model.TravelMode
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadHighlight
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

/** Navigation sub-screen: guidance toggles, vibrate chips, cameras, live re-checks. Parking
 *  history moved to Saved places and the demo modes to Diagnostics (2026-09-17). */
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

        // Which route chooser Directions opens. The Google-style picker graduated from an
        // experiment to the default on 2026-09-18; the classic panel stays one toggle away.
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_route_picker),
            checked = app.vela.ui.RoutePicker.googleStyle.value,
            onCheckedChange = { app.vela.ui.RoutePicker.set(context, it) },
            hint = stringResource(R.string.settings_route_picker_hint),
        )

        // Route bar (issue #228). Off by default: it is extra chrome on the nav screen, and the
        // congestion color already on the route line covers some of the same ground.
        var routeBar by remember { mutableStateOf(prefs.getBoolean("route_bar", false)) }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_route_bar),
            checked = routeBar,
            onCheckedChange = { routeBar = it; vm.setRouteBar(it) },
            hint = stringResource(R.string.settings_route_bar_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_route_trail),
            checked = app.vela.ui.RouteTrail.on.value,
            onCheckedChange = { app.vela.ui.RouteTrail.set(context, it) },
            hint = stringResource(R.string.settings_route_trail_hint),
        )
        // Bike routing preference (issue #401): safety over speed, on by default.
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_bike_safe),
            checked = app.vela.ui.BikeSafe.on.value,
            onCheckedChange = { app.vela.ui.BikeSafe.set(context, it) },
            hint = stringResource(R.string.settings_bike_safe_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_prefer_buttons),
            checked = app.vela.ui.PreferButtons.on.value,
            onCheckedChange = { app.vela.ui.PreferButtons.set(context, it) },
            hint = stringResource(R.string.settings_prefer_buttons_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_faster_auto),
            checked = app.vela.ui.FasterRouteAuto.accept.value,
            onCheckedChange = { app.vela.ui.FasterRouteAuto.set(context, it) },
            hint = stringResource(R.string.settings_faster_auto_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_pause_in_bar),
            checked = app.vela.ui.PauseInBar.on.value,
            onCheckedChange = { app.vela.ui.PauseInBar.set(context, it) },
            // With Prefer buttons on the bar carries the step-list button as well, so say so.
            hint = stringResource(
                if (app.vela.ui.PreferButtons.on.value) R.string.settings_pause_in_bar_hint_buttons
                else R.string.settings_pause_in_bar_hint
            ),
        )
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
        Text(
            stringResource(R.string.settings_road_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.RoadLabel.BAR to stringResource(R.string.settings_road_label_bar),
            app.vela.ui.RoadLabel.IN_BAR to stringResource(R.string.settings_road_label_inbar),
            app.vela.ui.RoadLabel.PUCK to stringResource(R.string.settings_road_label_puck),
            app.vela.ui.RoadLabel.OFF to stringResource(R.string.settings_road_label_off),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.RoadLabel.mode.value == id,
                onClick = { app.vela.ui.RoadLabel.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_road_label_hint))

        // Arrow size + colors (issue #344): bigger targets for aging eyes, and a white disc so
        // the puck does not blend into the blue route line.
        GroupDivider()
        Text(
            stringResource(R.string.settings_puck_size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.PuckStyle.SIZE_NORMAL to stringResource(R.string.settings_puck_size_normal),
            app.vela.ui.PuckStyle.SIZE_LARGE to stringResource(R.string.settings_puck_size_large),
            app.vela.ui.PuckStyle.SIZE_XL to stringResource(R.string.settings_puck_size_xl),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.PuckStyle.size.value == id,
                onClick = { app.vela.ui.PuckStyle.setSize(context, id) },
            )
        }
        GroupDivider()
        Text(
            stringResource(R.string.settings_puck_style),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.vela.ui.PuckStyle.STYLE_BLUE to stringResource(R.string.settings_puck_style_blue),
            app.vela.ui.PuckStyle.STYLE_WHITE to stringResource(R.string.settings_puck_style_white),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.vela.ui.PuckStyle.style.value == id,
                onClick = { app.vela.ui.PuckStyle.setStyle(context, id) },
            )
        }

        var trafficLights by remember { mutableStateOf(prefs.getBoolean("nav_traffic_lights", false)) }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_nav_tap_places),
            checked = app.vela.ui.MapPoiPrefs.navTapPlaces.value,
            onCheckedChange = { app.vela.ui.MapPoiPrefs.setNavTapPlaces(context, it) },
            hint = stringResource(R.string.settings_nav_tap_places_hint),
        )
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
        // Over-the-limit voice alert (issue #404): its own opt-in, off by default. Sits with the
        // other spoken extras; the timing is in :core SpeedingAlerts.
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_speeding_alert),
            checked = app.vela.ui.SpeedingAlert.on.value,
            onCheckedChange = { app.vela.ui.SpeedingAlert.set(context, it) },
            hint = stringResource(R.string.settings_speeding_alert_hint),
        )
        }

        SettingsGroup {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
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

        Spacer(Modifier.height(8.dp))
        CameraSettingsGroup()
        Spacer(Modifier.height(8.dp))
        LiveRechecksGroup(vm)
        Spacer(Modifier.height(24.dp))
    }
}
