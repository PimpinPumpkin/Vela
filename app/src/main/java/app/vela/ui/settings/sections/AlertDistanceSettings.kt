package app.vela.ui.settings.sections

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.AccidentBadge
import app.vela.ui.AlertDistancePrefs
import app.vela.ui.ClosureBadge
import app.vela.ui.HazardBadge
import app.vela.ui.JamBadge
import app.vela.ui.PlateCameraBadge
import app.vela.ui.PoliceBadge
import app.vela.ui.SpeedCameraBadge
import app.vela.ui.VelaDialog
import app.vela.ui.dpadClickable
import app.vela.ui.dpadHighlight
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.SubHead
import app.vela.ui.settings.settingsAnchor

/**
 * Navigation alert trigger distance customization (morphe-patches style).
 * Allows users to configure warning lead distances in meters for all camera types,
 * police reports, road hazards, accidents, closures, and traffic jams.
 */
@Composable
internal fun AlertDistanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var editingCategory by remember { mutableStateOf<AlertCategoryItem?>(null) }

    SettingsScaffold(stringResource(R.string.settings_alert_distance_title), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_alert_distance_intro))

        // Default distance group
        SubHead(stringResource(R.string.settings_alert_distance_default_group))
        SettingsGroup {
            val defM = AlertDistancePrefs.defaultMeters.intValue
            listOf(
                AlertDistancePrefs.DISTANCE_DYNAMIC to stringResource(R.string.settings_alert_distance_dynamic),
                200 to stringResource(R.string.settings_alert_distance_meters, 200),
                300 to stringResource(R.string.settings_alert_distance_meters, 300),
                500 to stringResource(R.string.settings_alert_distance_meters, 500),
                750 to stringResource(R.string.settings_alert_distance_meters, 750),
                1000 to stringResource(R.string.settings_alert_distance_meters, 1000),
                1500 to stringResource(R.string.settings_alert_distance_meters, 1500),
            ).forEachIndexed { idx, (meters, label) ->
                if (idx > 0) GroupDivider()
                SelectableRow(
                    label = label,
                    selected = defM == meters,
                    onClick = { AlertDistancePrefs.setDefault(context, meters) },
                    modifier = if (idx == 0) topRow else Modifier,
                )
            }
        }

        // Cameras & radars
        Spacer(Modifier.height(12.dp))
        SubHead(stringResource(R.string.settings_alert_distance_cameras_group))
        SettingsGroup {
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_speed_cam),
                distanceMeters = AlertDistancePrefs.speedCamMeters.intValue,
                leading = { SpeedCameraBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_speed_cam),
                        currentMeters = AlertDistancePrefs.speedCamMeters.intValue,
                        onSave = { AlertDistancePrefs.setSpeedCam(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_flock),
                distanceMeters = AlertDistancePrefs.flockMeters.intValue,
                leading = { PlateCameraBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_flock),
                        currentMeters = AlertDistancePrefs.flockMeters.intValue,
                        onSave = { AlertDistancePrefs.setFlock(context, it) },
                    )
                },
            )
        }

        // Police & speed traps
        Spacer(Modifier.height(12.dp))
        SubHead(stringResource(R.string.settings_alert_distance_police_group))
        SettingsGroup {
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_police),
                distanceMeters = AlertDistancePrefs.policeMeters.intValue,
                leading = { PoliceBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_police),
                        currentMeters = AlertDistancePrefs.policeMeters.intValue,
                        onSave = { AlertDistancePrefs.setPolice(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_police_hidden),
                distanceMeters = AlertDistancePrefs.policeHiddenMeters.intValue,
                leading = { PoliceBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_police_hidden),
                        currentMeters = AlertDistancePrefs.policeHiddenMeters.intValue,
                        onSave = { AlertDistancePrefs.setPoliceHidden(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_police_camera),
                distanceMeters = AlertDistancePrefs.policeCameraMeters.intValue,
                leading = { PoliceBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_police_camera),
                        currentMeters = AlertDistancePrefs.policeCameraMeters.intValue,
                        onSave = { AlertDistancePrefs.setPoliceCamera(context, it) },
                    )
                },
            )
        }

        // Accidents & crashes
        Spacer(Modifier.height(12.dp))
        SubHead(stringResource(R.string.settings_alert_distance_accidents_group))
        SettingsGroup {
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_accidents),
                distanceMeters = AlertDistancePrefs.accidentMeters.intValue,
                leading = { AccidentBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_accidents),
                        currentMeters = AlertDistancePrefs.accidentMeters.intValue,
                        onSave = { AlertDistancePrefs.setAccident(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_accidents_minor),
                distanceMeters = AlertDistancePrefs.accidentMinorMeters.intValue,
                leading = { AccidentBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_accidents_minor),
                        currentMeters = AlertDistancePrefs.accidentMinorMeters.intValue,
                        onSave = { AlertDistancePrefs.setAccidentMinor(context, it) },
                    )
                },
            )
        }

        // Road hazards
        Spacer(Modifier.height(12.dp))
        SubHead(stringResource(R.string.settings_alert_distance_hazards_group))
        SettingsGroup {
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_hazards),
                distanceMeters = AlertDistancePrefs.hazardMeters.intValue,
                leading = { HazardBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_hazards),
                        currentMeters = AlertDistancePrefs.hazardMeters.intValue,
                        onSave = { AlertDistancePrefs.setHazard(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_construction),
                distanceMeters = AlertDistancePrefs.hazardConstructionMeters.intValue,
                leading = { HazardBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_construction),
                        currentMeters = AlertDistancePrefs.hazardConstructionMeters.intValue,
                        onSave = { AlertDistancePrefs.setHazardConstruction(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_pothole),
                distanceMeters = AlertDistancePrefs.hazardPotholeMeters.intValue,
                leading = { HazardBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_pothole),
                        currentMeters = AlertDistancePrefs.hazardPotholeMeters.intValue,
                        onSave = { AlertDistancePrefs.setHazardPothole(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_shoulder),
                distanceMeters = AlertDistancePrefs.hazardShoulderMeters.intValue,
                leading = { HazardBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_shoulder),
                        currentMeters = AlertDistancePrefs.hazardShoulderMeters.intValue,
                        onSave = { AlertDistancePrefs.setHazardShoulder(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_weather),
                distanceMeters = AlertDistancePrefs.hazardWeatherMeters.intValue,
                leading = { HazardBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_weather),
                        currentMeters = AlertDistancePrefs.hazardWeatherMeters.intValue,
                        onSave = { AlertDistancePrefs.setHazardWeather(context, it) },
                    )
                },
            )
        }

        // Traffic & closures
        Spacer(Modifier.height(12.dp))
        SubHead(stringResource(R.string.settings_alert_distance_traffic_group))
        SettingsGroup {
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_closure),
                distanceMeters = AlertDistancePrefs.closureMeters.intValue,
                leading = { ClosureBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_closure),
                        currentMeters = AlertDistancePrefs.closureMeters.intValue,
                        onSave = { AlertDistancePrefs.setClosure(context, it) },
                    )
                },
            )
            GroupDivider()
            AlertCategoryRow(
                title = stringResource(R.string.settings_alert_distance_jam),
                distanceMeters = AlertDistancePrefs.jamMeters.intValue,
                leading = { JamBadge(Modifier.size(24.dp)) },
                onClick = {
                    editingCategory = AlertCategoryItem(
                        title = context.getString(R.string.settings_alert_distance_jam),
                        currentMeters = AlertDistancePrefs.jamMeters.intValue,
                        onSave = { AlertDistancePrefs.setJam(context, it) },
                    )
                },
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    editingCategory?.let { item ->
        AlertDistancePickerDialog(
            title = item.title,
            currentMeters = item.currentMeters,
            onDismiss = { editingCategory = null },
            onSelect = {
                item.onSave(it)
                editingCategory = null
            },
        )
    }
}

/** Row on parent pages (Cameras or Waze Alerts) that links to [AlertDistanceSettingsScreen]. */
@Composable
internal fun AlertDistanceNavigationRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.settings_alert_distance_title)
    Row(
        modifier
            .fillMaxWidth()
            .settingsAnchor(title)
            .dpadHighlight(RoundedCornerShape(10.dp))
            .dpadClickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_alert_distance_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val defM = AlertDistancePrefs.defaultMeters.intValue
        val summary = if (defM <= 0) {
            stringResource(R.string.settings_alert_distance_dynamic_short)
        } else {
            stringResource(R.string.settings_alert_distance_meters, defM)
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AlertCategoryRow(
    title: String,
    distanceMeters: Int,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .settingsAnchor(title)
            .dpadHighlight(RoundedCornerShape(10.dp))
            .dpadClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 12.dp)) { leading() }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val sub = when (distanceMeters) {
                AlertDistancePrefs.DISTANCE_USE_DEFAULT -> {
                    val defM = AlertDistancePrefs.defaultMeters.intValue
                    if (defM <= 0) {
                        stringResource(R.string.settings_alert_distance_use_default) + " (" + stringResource(R.string.settings_alert_distance_dynamic_short) + ")"
                    } else {
                        stringResource(R.string.settings_alert_distance_use_default) + " (" + stringResource(R.string.settings_alert_distance_meters, defM) + ")"
                    }
                }
                AlertDistancePrefs.DISTANCE_DYNAMIC -> stringResource(R.string.settings_alert_distance_dynamic_short)
                else -> stringResource(R.string.settings_alert_distance_meters, distanceMeters)
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val valueBadge = when (distanceMeters) {
            AlertDistancePrefs.DISTANCE_USE_DEFAULT -> stringResource(R.string.settings_alert_distance_use_default)
            AlertDistancePrefs.DISTANCE_DYNAMIC -> stringResource(R.string.settings_alert_distance_dynamic_short)
            else -> stringResource(R.string.settings_alert_distance_meters, distanceMeters)
        }
        Text(
            valueBadge,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private data class AlertCategoryItem(
    val title: String,
    val currentMeters: Int,
    val onSave: (Int) -> Unit,
)

@Composable
private fun AlertDistancePickerDialog(
    title: String,
    currentMeters: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var selectedMeters by remember { mutableStateOf(currentMeters) }
    var customText by remember {
        mutableStateOf(if (currentMeters > 0 && currentMeters !in listOf(200, 300, 500, 750, 1000, 1500)) currentMeters.toString() else "")
    }
    var showCustomInput by remember {
        mutableStateOf(currentMeters > 0 && currentMeters !in listOf(200, 300, 500, 750, 1000, 1500))
    }

    VelaDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = stringResource(android.R.string.ok),
        onConfirm = {
            if (showCustomInput) {
                val parsed = customText.trim().toIntOrNull()?.coerceIn(50, 3000)
                if (parsed != null) onSelect(parsed) else onSelect(selectedMeters)
            } else {
                onSelect(selectedMeters)
            }
        },
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = onDismiss,
        text = {
            // VelaDialog already has verticalScroll in its content column; do not nest verticalScroll here!
            Column(Modifier.fillMaxWidth()) {
                val options = listOf(
                    AlertDistancePrefs.DISTANCE_USE_DEFAULT to stringResource(R.string.settings_alert_distance_use_default),
                    AlertDistancePrefs.DISTANCE_DYNAMIC to stringResource(R.string.settings_alert_distance_dynamic),
                    200 to stringResource(R.string.settings_alert_distance_meters, 200),
                    300 to stringResource(R.string.settings_alert_distance_meters, 300),
                    500 to stringResource(R.string.settings_alert_distance_meters, 500),
                    750 to stringResource(R.string.settings_alert_distance_meters, 750),
                    1000 to stringResource(R.string.settings_alert_distance_meters, 1000),
                    1500 to stringResource(R.string.settings_alert_distance_meters, 1500),
                )

                options.forEach { (m, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .dpadClickable {
                                selectedMeters = m
                                showCustomInput = false
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = !showCustomInput && selectedMeters == m,
                            onClick = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // Custom meters row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .dpadClickable { showCustomInput = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = showCustomInput,
                        onClick = null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_alert_distance_custom), style = MaterialTheme.typography.bodyLarge)
                }

                if (showCustomInput) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.settings_alert_distance_custom_prompt)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        },
    )
}
