package app.vela.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.R
import app.vela.ui.DpadRingBox // D-pad-only operation (docs/dpad.md)
import app.vela.ui.Onboarding
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadRowSibling

/** About sub-screen: the project blurb, support/donate, version + the in-app updater. */
@Composable
internal fun AboutSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
    SettingsScaffold(stringResource(R.string.settings_about), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_about_hint))
        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_support)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
        Hint(stringResource(R.string.settings_support_hint))
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            modifier = topRow.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
            onClick = {
Onboarding.openDonate(context)
            },
        ) {
            Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.settings_support_button))
        }
        }
        }

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_version)) {
        // Tap to copy - bug reports need the exact version, and typing it from the screen
        // is error-prone (issue #58's one keeper suggestion).
        val versionLine = stringResource(R.string.settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val versionCopied = stringResource(R.string.settings_version_copied)
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Text(
            versionLine,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .dpadHighlight()
                .clickable {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(versionLine))
                    android.widget.Toast.makeText(context, versionCopied, android.widget.Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        GroupDivider()
        // Self-updater: a launch check (throttled to ~daily) plus a manual check here.
        // The system installer does the install either way.
        var selfUpdate by remember { mutableStateOf(prefs.getBoolean("self_update_check", true)) }
        ToggleRow(
            label = stringResource(R.string.settings_update_auto),
            checked = selfUpdate,
            onCheckedChange = { selfUpdate = it; prefs.edit().putBoolean("self_update_check", it).apply() },
            hint = stringResource(R.string.settings_update_auto_hint),
        )
        GroupDivider()
        // Nightly channel: check against prereleases (the newest CI build) instead of stable.
        var nightly by remember { mutableStateOf(prefs.getBoolean("update_nightly", false)) }
        ToggleRow(
            label = stringResource(R.string.settings_update_nightly),
            checked = nightly,
            onCheckedChange = { nightly = it; prefs.edit().putBoolean("update_nightly", it).apply() },
            hint = stringResource(R.string.settings_update_nightly_hint),
        )
        GroupDivider()
        // A clear gap between the hint paragraph and the button (they read as one clump otherwise).
        Spacer(Modifier.height(10.dp))
        var updateStatus by remember { mutableStateOf<String?>(null) }
        val checkingText = stringResource(R.string.settings_update_checking)
        val noneText = stringResource(R.string.settings_update_none)
        // Found update: a full inline offer, right here. The map card said "go back to the
        // map to install" from Settings, which was a pointless round trip - same state, same
        // download call as the card, so the two surfaces stay in sync (start it here, the
        // card shows the same progress, and vice versa).
        state.updateInfo?.let { u ->
            Text(
                stringResource(R.string.update_available_title, u.versionName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            state.updateDownloadPct?.let { pct ->
                Text(stringResource(R.string.update_downloading, pct), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            } ?: run {
                val updFocus = remember { List(2) { FocusRequester() } }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    // D-pad (issue #65): these were plain Material buttons with no visible focus ring,
                    // so a keypad user couldn't SEE the update button highlighted. dpadHighlight gives
                    // them the fork's focus ring, like the map UpdateCard already has. The ring shape
                    // MUST match each button's OWN shape - CircleShape drew a circle over a pill (tester
                    // report), so pass the matching ButtonDefaults shape per button type.
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.shape) {
                        Button(modifier = Modifier.dpadRowSibling(updFocus, 0), onClick = { vm.downloadUpdate() }) { Text(stringResource(R.string.update_install)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.textShape) {
                        TextButton(modifier = Modifier.dpadRowSibling(updFocus, 1), onClick = { vm.dismissUpdate(); updateStatus = null }) { Text(stringResource(R.string.update_later)) }
                    }
                }
            }
        } ?: DpadRingBox(androidx.compose.material3.ButtonDefaults.outlinedShape, Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = {
                    updateStatus = checkingText
                    vm.checkForUpdateNow { found -> updateStatus = if (found) null else noneText }
                },
            ) { Text(stringResource(R.string.settings_update_check_now)) }
        }
        updateStatus?.let { androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) { Hint(it) } }
        }
        // Breathing room under the last control so the button doesn't sit right on the
        // gesture bar at the end of the scroll.
        Spacer(Modifier.height(56.dp))
    }
}
