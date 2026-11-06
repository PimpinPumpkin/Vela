package app.vela.ui.settings.sections

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.AccidentBadge
import app.vela.ui.ClosureBadge
import app.vela.ui.HazardBadge
import app.vela.ui.JamBadge
import app.vela.ui.PoliceBadge
import app.vela.ui.WazeAlertsPrefs
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow

/**
 * Dedicated Waze crowd alerts settings hub:
 * - Master switch for live crowdsourced alerts
 * - Individual category filters (police, accidents, hazards, closures, jams)
 * - Navigation audio announcements
 * - Action to launch official Waze app for reporting
 */
@Composable
internal fun WazeAlertsSettingsScreen(
    onBack: () -> Unit,
    onOpenAlertDistances: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_waze_alerts_title), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_waze_alerts_master_hint))

        // Master switch
        SettingsGroup {
            ToggleRow(
                label = stringResource(R.string.settings_waze_alerts_master),
                checked = WazeAlertsPrefs.masterOn.value,
                onCheckedChange = { WazeAlertsPrefs.setMaster(context, it) },
                hint = stringResource(R.string.settings_waze_alerts_subtitle),
                switchModifier = topRow,
            )
        }

        if (WazeAlertsPrefs.masterOn.value) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup(title = stringResource(R.string.settings_waze_categories_group)) {
                ToggleRow(
                    label = stringResource(R.string.settings_waze_police),
                    checked = WazeAlertsPrefs.policeOn.value,
                    onCheckedChange = { WazeAlertsPrefs.setPolice(context, it) },
                    hint = stringResource(R.string.settings_waze_police_hint),
                    leading = { PoliceBadge() },
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_waze_accidents),
                    checked = WazeAlertsPrefs.accidentsOn.value,
                    onCheckedChange = { WazeAlertsPrefs.setAccidents(context, it) },
                    hint = stringResource(R.string.settings_waze_accidents_hint),
                    leading = { AccidentBadge() },
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_waze_hazards),
                    checked = WazeAlertsPrefs.hazardsOn.value,
                    onCheckedChange = { WazeAlertsPrefs.setHazards(context, it) },
                    hint = stringResource(R.string.settings_waze_hazards_hint),
                    leading = { HazardBadge() },
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_waze_closures),
                    checked = WazeAlertsPrefs.closuresOn.value,
                    onCheckedChange = { WazeAlertsPrefs.setClosures(context, it) },
                    hint = stringResource(R.string.settings_waze_closures_hint),
                    leading = { ClosureBadge() },
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_waze_jams),
                    checked = WazeAlertsPrefs.jamsOn.value,
                    onCheckedChange = { WazeAlertsPrefs.setJams(context, it) },
                    hint = stringResource(R.string.settings_waze_jams_hint),
                    leading = { JamBadge() },
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingsGroup(title = stringResource(R.string.settings_waze_voice_group)) {
                if (WazeAlertsPrefs.policeOn.value) {
                    ToggleRow(
                        label = stringResource(R.string.settings_waze_police_warn),
                        checked = WazeAlertsPrefs.policeWarnOn.value,
                        onCheckedChange = { WazeAlertsPrefs.setPoliceWarn(context, it) },
                        hint = stringResource(R.string.settings_waze_police_warn_hint),
                    )
                }
                if (WazeAlertsPrefs.accidentsOn.value) {
                    if (WazeAlertsPrefs.policeOn.value) GroupDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_waze_warn_accidents),
                        checked = WazeAlertsPrefs.accidentsWarnOn.value,
                        onCheckedChange = { WazeAlertsPrefs.setAccidentsWarn(context, it) },
                        hint = stringResource(R.string.settings_waze_warn_accidents_hint),
                    )
                }
                if (WazeAlertsPrefs.hazardsOn.value) {
                    if (WazeAlertsPrefs.policeOn.value || WazeAlertsPrefs.accidentsOn.value) GroupDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_waze_warn_hazards),
                        checked = WazeAlertsPrefs.hazardsWarnOn.value,
                        onCheckedChange = { WazeAlertsPrefs.setHazardsWarn(context, it) },
                        hint = stringResource(R.string.settings_waze_warn_hazards_hint),
                    )
                }
                if (WazeAlertsPrefs.closuresOn.value) {
                    if (WazeAlertsPrefs.policeOn.value || WazeAlertsPrefs.accidentsOn.value || WazeAlertsPrefs.hazardsOn.value) GroupDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_waze_warn_closures),
                        checked = WazeAlertsPrefs.closuresWarnOn.value,
                        onCheckedChange = { WazeAlertsPrefs.setClosuresWarn(context, it) },
                        hint = stringResource(R.string.settings_waze_warn_closures_hint),
                    )
                }
                if (WazeAlertsPrefs.jamsOn.value) {
                    if (WazeAlertsPrefs.policeOn.value || WazeAlertsPrefs.accidentsOn.value || WazeAlertsPrefs.hazardsOn.value || WazeAlertsPrefs.closuresOn.value) GroupDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_waze_warn_jams),
                        checked = WazeAlertsPrefs.jamsWarnOn.value,
                        onCheckedChange = { WazeAlertsPrefs.setJamsWarn(context, it) },
                        hint = stringResource(R.string.settings_waze_warn_jams_hint),
                    )
                }
            }

            if (onOpenAlertDistances != null) {
                Spacer(Modifier.height(8.dp))
                SettingsGroup(title = stringResource(R.string.settings_alert_distance_title)) {
                    AlertDistanceNavigationRow(onOpen = onOpenAlertDistances)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
