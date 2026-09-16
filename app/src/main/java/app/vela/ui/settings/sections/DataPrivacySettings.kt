package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.fillMaxWidth
import app.vela.ui.settings.settingsAnchor
import app.vela.ui.dpadHighlight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.Hint
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SelectableRow
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
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilledTonalButton(
            // The top (and only) focusable control; on the old page this button sat beside a
            // VelaSwitch whose ring token satisfied the audit window - here it carries its own ring.
            modifier = topRow.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
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
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            // Where the map's businesses come from (moved here from Map, 2026-09-16: it is a
            // privacy choice, what leaves the phone as you pan, not a map look). Each option states its own cost so the choice
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
            ).forEach { (id, label) ->
                SelectableRow(
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
        // Clear history (issue #425): one row for what used to be spread over three screens
        // (Clear recents on the search page, Clear all under Parking history, trips one at a
        // time under Diagnostics). Confirmed, since it cannot be undone.
        Spacer(Modifier.height(8.dp))
        var confirmClear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        SettingsGroup {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().settingsAnchor(stringResource(R.string.settings_clear_history)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.settings_clear_history), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_clear_history_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FilledTonalButton(
                modifier = Modifier.padding(top = 8.dp).dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
                onClick = { confirmClear = true },
            ) { Text(stringResource(R.string.settings_clear_history_action)) }
        }
        }
        if (confirmClear) {
            app.vela.ui.VelaDialog(
                onDismissRequest = { confirmClear = false },
                title = stringResource(R.string.settings_clear_history_confirm_title),
                confirmText = stringResource(R.string.settings_clear_history_action),
                onConfirm = {
                    vm.clearAllHistory()
                    confirmClear = false
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_clear_history_done), android.widget.Toast.LENGTH_SHORT).show()
                },
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { confirmClear = false },
            ) {
                Text(stringResource(R.string.settings_clear_history_confirm_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
