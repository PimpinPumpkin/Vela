package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.ui.DpadRingBox // D-pad-only operation (docs/dpad.md)
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadRowSibling

/**
 * Diagnostics sub-screen: breadcrumb sharing, compatibility rendering, trip recording + the
 * recorded-trip list, crash reports. [onCloseSettings] closes all of Settings back to the map
 * (trip replay plays on the map).
 */
@Composable
internal fun DiagnosticsSettingsScreen(vm: MapViewModel, onBack: () -> Unit, onCloseSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
    SettingsScaffold(stringResource(R.string.settings_diagnostics), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        LaunchedEffect(Unit) { vm.refreshDiagnostics() }
        PageIntro(stringResource(R.string.settings_diagnostics_hint))
        var showDiagConsent by remember { mutableStateOf(false) }
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_share_diagnostics),
            checked = state.diagnosticsEnabled,
            onCheckedChange = { on -> if (on) showDiagConsent = true else vm.setDiagnostics(false) },
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        if (state.diagnosticsEnabled) {
            GroupDivider()
            Spacer(Modifier.height(6.dp))
            DpadRingBox(androidx.compose.material3.ButtonDefaults.filledTonalShape, Modifier.padding(horizontal = 16.dp)) {
                FilledTonalButton(onClick = {
                    val intent = vm.diagShareIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_diag_nothing),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_diag_export)) }
            }
        }
        }

        // Compatibility (TextureView) rendering - a hardware escape hatch (port of upstream
        // PimpinPumpkin/Vela 261156e2 + df2b8570). Writes the "texture_render" pref that
        // VelaMapView reads when it creates the map; needs an app restart to apply. Also flips
        // itself on via the two-crash sentinel when a GPU driver kills the map at init.
        var textureRender by remember { mutableStateOf(prefs.getBoolean("texture_render", app.vela.ui.map.fragileGpuDefault())) }
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_texture_render),
            checked = textureRender,
            onCheckedChange = { on -> textureRender = on; prefs.edit().putBoolean("texture_render", on).apply() },
            hint = stringResource(R.string.settings_texture_render_hint),
        )
        GroupDivider()
        // Building-overlay debug badge + fps readout on the map (the runOvlGate probe tooling).
        ToggleRow(
            label = stringResource(R.string.settings_building_debug),
            checked = app.vela.ui.BuildingDebug.on.value,
            onCheckedChange = { app.vela.ui.BuildingDebug.set(context, it) },
            hint = stringResource(R.string.settings_building_debug_hint),
        )
        }

        // Trip recording - more invasive than diagnostics (it's your exact routes),
        // so it's a separate opt-in. Records nav GPS traces for replay testing.
        LaunchedEffect(Unit) { vm.refreshTripRecording() }
        var showTripConsent by remember { mutableStateOf(false) }
        var trips by remember { mutableStateOf(vm.recordedTrips()) }
        // Re-read on entry so a trip recorded since the app launched shows up without
        // a restart (the list was otherwise only refreshed after a delete).
        LaunchedEffect(Unit) { trips = vm.recordedTrips() }
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_save_trips),
            checked = state.tripRecordingEnabled,
            onCheckedChange = { on -> if (on) showTripConsent = true else vm.setTripRecording(false) },
            hint = stringResource(R.string.settings_save_trips_hint),
        )
        if (trips.isNotEmpty()) {
            GroupDivider()
            Hint(stringResource(R.string.settings_recorded_trips_hint))
            trips.forEachIndexed { ti, t ->
                if (ti > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                        val recordedAt = if (t.startedAt > 0L)
                            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(t.startedAt))
                        else null
                        Hint(listOfNotNull(recordedAt, stringResource(R.string.settings_trip_points, t.fixCount)).joinToString(" · "))
                    }
                    // D-pad: Replay/Share/Delete sit side by side inside the L/R-swallowing Column, so
                    // the trio drives its own LEFT/RIGHT (issue #24 pattern).
                    val tripFocus = remember(t.id) { List(3) { FocusRequester() } }
                    TextButton(modifier = Modifier.dpadRowSibling(tripFocus, 0), onClick = { vm.replayTrip(t); onCloseSettings() }) { Text(stringResource(R.string.settings_trip_replay)) }
                    // Share the raw trace off-device - works on release builds, so a
                    // drive can be handed over for replay/debug without a dev build.
                    TextButton(modifier = Modifier.dpadRowSibling(tripFocus, 1), onClick = {
                        val intent = vm.exportTripIntent(t)
                        if (intent != null) runCatching { context.startActivity(intent) }
                        else android.widget.Toast.makeText(context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.settings_trip_share)) }
                    IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape).dpadRowSibling(tripFocus, 2), onClick = { vm.deleteTrip(t.id); trips = vm.recordedTrips() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_trip_delete))
                    }
                }
            }
        } else if (state.tripRecordingEnabled) {
            Hint(stringResource(R.string.settings_no_trips_hint))
        }
        }
        if (showTripConsent) {
            app.vela.ui.VelaDialog(
                onDismissRequest = { showTripConsent = false },
                title = stringResource(R.string.settings_trip_consent_title),
                confirmText = stringResource(R.string.settings_turn_on),
                onConfirm = { vm.setTripRecording(true); showTripConsent = false },
                dismissText = stringResource(R.string.settings_cancel),
                onDismiss = { showTripConsent = false },
                text = { Text(stringResource(R.string.settings_trip_consent_body)) },
            )
        }
        var crashReports by remember { mutableStateOf(app.vela.diag.CrashCatcher.pending(context)) }
        if (crashReports.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Hint(stringResource(R.string.settings_crash_hint))
            // Settings swallows bare LEFT/RIGHT (a no-target horizontal move would CLEAR focus),
            // so a Row of buttons needs explicit sibling wiring or only the first is ever
            // reachable - the same trap the update buttons had (issue #79, @SILB).
            val crashFocus = remember { List(2) { FocusRequester() } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DpadRingBox(androidx.compose.material3.ButtonDefaults.filledTonalShape) {
                    FilledTonalButton(
                        modifier = Modifier.dpadRowSibling(crashFocus, 0),
                        onClick = {
                            app.vela.diag.CrashCatcher.shareIntent(context)?.let { runCatching { context.startActivity(it) } }
                        },
                    ) { Text(stringResource(R.string.settings_crash_export)) }
                }
                Spacer(Modifier.width(8.dp))
                DpadRingBox(androidx.compose.material3.ButtonDefaults.textShape) {
                    TextButton(
                        modifier = Modifier.dpadRowSibling(crashFocus, 1),
                        onClick = {
                            app.vela.diag.CrashCatcher.clear(context); crashReports = emptyList()
                        },
                    ) { Text(stringResource(R.string.settings_crash_discard)) }
                }
            }
            if (!state.diagnosticsEnabled) {
                // We caught this crash either way, but with diagnostics off there were no
                // breadcrumbs leading up to it. Offer to turn them on here, in context, instead
                // of asking everyone up front during onboarding. Routes through the same consent
                // dialog the toggle uses.
                Spacer(Modifier.height(4.dp))
                Hint(stringResource(R.string.settings_crash_diag_offer))
                DpadRingBox(androidx.compose.material3.ButtonDefaults.textShape) {
                    TextButton(onClick = { showDiagConsent = true }) {
                        Text(stringResource(R.string.settings_crash_enable_diag))
                    }
                }
            }
        }
        if (showDiagConsent) {
            app.vela.ui.VelaDialog(
                onDismissRequest = { showDiagConsent = false },
                title = stringResource(R.string.settings_diag_consent_title),
                confirmText = stringResource(R.string.settings_turn_on),
                onConfirm = { vm.setDiagnostics(true); showDiagConsent = false },
                dismissText = stringResource(R.string.settings_cancel),
                onDismiss = { showDiagConsent = false },
                text = { Text(stringResource(R.string.settings_diag_consent_body)) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
