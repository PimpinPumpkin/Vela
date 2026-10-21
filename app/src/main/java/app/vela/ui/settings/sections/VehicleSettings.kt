package app.vela.ui.settings.sections

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import app.vela.R
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.ToggleRow
import app.vela.vehicle.FceCarPropertyTransport
import app.vela.vehicle.SomeIpHudTransport
import app.vela.vehicle.SteeringWheelService
import app.vela.vehicle.VehicleCapabilities

/**
 * The instrument-cluster and steering-wheel settings (SPEC 10.9). One group under
 * Navigation, every row off by default. The rows describe what the driver gets, never the
 * mechanism, and each is independent: a car may offer one and not another.
 */
@Composable
internal fun VehicleSettingsGroup() {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.settings_vehicle_group)) {
        // The cluster map (a Presentation on the cluster display).
        ToggleRow(
            label = stringResource(R.string.settings_cluster_map),
            checked = app.vela.ui.ClusterMapPref.on.value,
            onCheckedChange = {
                app.vela.ui.ClusterMapPref.set(context, it)
                VehicleCapabilities.clusterController?.onSettingChanged()
            },
            hint = stringResource(R.string.settings_cluster_map_hint),
        )
        if (app.vela.ui.ClusterMapPref.on.value) {
            val targets = VehicleCapabilities.clusterController?.targets().orEmpty()
            if (targets.isEmpty()) {
                Hint(stringResource(R.string.settings_cluster_map_none))
            } else {
                val chosen = app.vela.ui.ClusterMapPref.display.value
                SelectableRow(
                    label = stringResource(R.string.settings_cluster_map_display_auto),
                    selected = chosen == null,
                    onClick = {
                        app.vela.ui.ClusterMapPref.setDisplay(context, null)
                        VehicleCapabilities.clusterController?.onSettingChanged()
                    },
                )
                for (name in targets) {
                    SelectableRow(
                        label = name,
                        selected = chosen == name,
                        onClick = {
                            app.vela.ui.ClusterMapPref.setDisplay(context, name)
                            VehicleCapabilities.clusterController?.onSettingChanged()
                        },
                    )
                }
            }
        }
        GroupDivider()
        // The cluster's built-in turn view (FCE vehicle properties).
        ToggleRow(
            label = stringResource(R.string.settings_cluster_hud),
            checked = app.vela.ui.FceClusterHudPref.on.value,
            onCheckedChange = { app.vela.ui.FceClusterHudPref.set(context, it) },
            hint = stringResource(R.string.settings_cluster_hud_hint),
        )
        if (app.vela.ui.FceClusterHudPref.on.value && !FceCarPropertyTransport.available()) {
            Hint(stringResource(R.string.settings_cluster_hud_absent))
        }
        GroupDivider()
        // The windshield HUD (road-info frames to the glass).
        ToggleRow(
            label = stringResource(R.string.settings_windshield_hud),
            checked = app.vela.ui.SomeIpHudPref.on.value,
            onCheckedChange = {
                app.vela.ui.SomeIpHudPref.set(context, it)
                VehicleCapabilities.someIpHud?.onSettingChanged()
            },
            hint = stringResource(R.string.settings_windshield_hud_hint),
        )
        if (app.vela.ui.SomeIpHudPref.on.value && !SomeIpHudTransport.available(context)) {
            Hint(stringResource(R.string.settings_windshield_hud_absent))
        }
        GroupDivider()
        // The steering-wheel voice key. An app cannot turn its own accessibility service on, so the
        // switch reflects the system state and tapping it opens the system screen that does.
        var enabled by remember { mutableStateOf(SteeringWheelService.isEnabled(context)) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) enabled = SteeringWheelService.isEnabled(context)
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        ToggleRow(
            label = stringResource(R.string.settings_wheel_voice),
            checked = enabled,
            onCheckedChange = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            hint = stringResource(if (enabled) R.string.settings_wheel_voice_enabled else R.string.settings_wheel_voice_hint),
        )
    }
    Spacer(Modifier.height(8.dp))
}
