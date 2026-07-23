package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)

/** Data source and privacy sub-screen: the how-Vela-handles-data explainer + privacy policy link. */
@Composable
internal fun DataPrivacySettingsScreen(vm: app.vela.ui.map.MapViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_data_privacy), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_data_privacy_hint))
        SettingsGroup {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        OutlinedButton(
            // The top (and only) focusable control; on the old page this button sat beside a
            // VelaSwitch whose ring token satisfied the audit window - here it carries its own ring.
            modifier = topRow.dpadHighlight(androidx.compose.material3.ButtonDefaults.outlinedShape),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/PimpinPumpkin/Vela/blob/main/PRIVACY.md"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Text(stringResource(R.string.settings_privacy_button)) }
        }
        }
        // Live rechecks: the ~2-min traffic/route recheck during nav (a Google request each time).
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(24.dp))
    }
}
