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
        GroupDivider()
        // Nav smoothness trace (issue #251): records the numbers behind the nav camera during a
        // REAL drive so a "map swims / puck jitters" report is diagnosable. Carries no position
        // data by design, so the export is safe to attach to a public issue.
        ToggleRow(
            label = stringResource(R.string.settings_nav_trace),
            checked = app.vela.diag.NavTrace.enabled.value,
            onCheckedChange = { app.vela.diag.NavTrace.set(context, it) },
            hint = stringResource(R.string.settings_nav_trace_hint),
        )
        if (app.vela.diag.NavTrace.enabled.value) {
            Spacer(Modifier.height(6.dp))
            DpadRingBox(androidx.compose.material3.ButtonDefaults.filledTonalShape, Modifier.padding(horizontal = 16.dp)) {
                FilledTonalButton(onClick = {
                    val intent = app.vela.diag.NavTrace.shareIntent(context)
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_nav_trace_empty),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_nav_trace_export)) }
            }
            Spacer(Modifier.height(4.dp))
        }
        }

        // Trip recording - more invasive than diagnostics (it's your exact routes),
        // so it's a separate opt-in. Records nav GPS traces for replay testing.
        LaunchedEffect(Unit) { vm.refreshTripRecording() }
        var showTripConsent by remember { mutableStateOf(false) }
        var shareTrip by remember { mutableStateOf<app.vela.replay.TripMeta?>(null) }
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
                    // Share the trace off-device - works on release builds, so a drive can be
                    // handed over for replay/debug without a dev build. Opens the trim dialog
                    // rather than sharing outright: a raw trip starts and ends at its owner's
                    // front door, and that decision should be made deliberately every time.
                    TextButton(modifier = Modifier.dpadRowSibling(tripFocus, 1), onClick = {
                        shareTrip = t
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
        shareTrip?.let { meta -> TripShareDialog(meta, vm, context) { shareTrip = null } }
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

/**
 * The trim-before-you-share dialog for one recorded trip.
 *
 * A trip CSV is raw GPS whose first and last fixes are, almost always, exactly the places its
 * owner would least like published. This is the deliberate moment to decide that: the trimmed
 * share is the prominent button, the full trace stays available but has to be reached for, and
 * the summary says what is actually being removed *before* anything leaves the device — including
 * the first surviving coordinate, which is the one thing worth eyeballing yourself.
 */
@Composable
private fun TripShareDialog(
    meta: app.vela.replay.TripMeta,
    vm: MapViewModel,
    context: android.content.Context,
    onClose: () -> Unit,
) {
    var radius by remember { mutableStateOf(app.vela.core.replay.TripScrub.DEFAULT_RADIUS_M) }
    val report = remember(meta.id, radius) { vm.scrubTripForSharing(meta, radius) }
    app.vela.ui.VelaDialog(
        onDismissRequest = onClose,
        title = stringResource(R.string.settings_trip_share_title),
        confirmText = stringResource(R.string.settings_trip_share_trimmed),
        onConfirm = {
            val r = report
            val intent = r?.let { vm.shareScrubbedTripIntent(it) }
            if (intent != null) runCatching { context.startActivity(intent) }
            else android.widget.Toast.makeText(
                context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT,
            ).show()
            onClose()
        },
        dismissText = stringResource(R.string.settings_cancel),
        onDismiss = onClose,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.settings_trip_share_explain),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_trip_share_radius),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (m in listOf(200.0, 400.0, 800.0)) {
                    if (m == radius) {
                        FilledTonalButton(onClick = { radius = m }) {
                            Text(stringResource(R.string.settings_trip_share_radius_m, m.toInt()))
                        }
                    } else {
                        TextButton(onClick = { radius = m }) {
                            Text(stringResource(R.string.settings_trip_share_radius_m, m.toInt()))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (report == null) {
                Text(
                    stringResource(R.string.settings_trip_scrub_short),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(
                        R.string.settings_trip_share_summary,
                        report.fixesRemoved, report.fixesAfter,
                        report.trimmedStartM.toInt(), report.trimmedEndM.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Hint(stringResource(R.string.settings_trip_share_also))
                report.firstRemaining?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    Hint(
                        stringResource(
                            R.string.settings_trip_share_first,
                            String.format(java.util.Locale.US, "%.5f", p.lat),
                            String.format(java.util.Locale.US, "%.5f", p.lng),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Still reachable, but it is the reach: this is the file that names your front door.
            TextButton(onClick = {
                val intent = vm.exportTripIntent(meta)
                if (intent != null) runCatching { context.startActivity(intent) }
                else android.widget.Toast.makeText(
                    context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT,
                ).show()
                onClose()
            }) { Text(stringResource(R.string.settings_trip_share_full)) }
        }
    }
}
