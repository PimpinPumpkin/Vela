package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import app.vela.ui.VelaMenu
import app.vela.ui.item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics sub-screen: breadcrumb sharing, compatibility rendering, demo modes, experiments,
 * trip recording + the recorded-trip list, crash reports. [onCloseSettings] closes all of Settings back to the map
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
        GroupDivider()
        // Issue #507: the export with the searches, destinations, links and place names gone
        // and coordinates at ~10 km, for a report the user means to post publicly. Always shown
        // (it also widens the trim on trip shares, which do not need diagnostics on).
        var redact by remember { mutableStateOf(prefs.getBoolean(app.vela.diag.DiagExporter.REDACT_PREF, false)) }
        ToggleRow(
            label = stringResource(R.string.settings_diag_redact),
            checked = redact,
            onCheckedChange = { on ->
                redact = on
                prefs.edit().putBoolean(app.vela.diag.DiagExporter.REDACT_PREF, on).apply()
            },
            hint = stringResource(R.string.settings_diag_redact_trips_hint),
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
        // When the sentinel flipped it on by itself, SAY SO on the row (with the date): a user who
        // never touched this must be able to see the app did, and that turning it off is safe to try.
        var textureAutoMs by remember { mutableStateOf(prefs.getLong("texture_render_auto_ms", 0L)) }
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_texture_render),
            checked = textureRender,
            onCheckedChange = { on ->
                textureRender = on
                prefs.edit().putBoolean("texture_render", on).remove("texture_render_auto_ms").apply()
                textureAutoMs = 0L
            },
            hint = if (textureRender && textureAutoMs > 0L) {
                stringResource(
                    R.string.settings_texture_render_auto_hint,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(textureAutoMs)),
                )
            } else stringResource(R.string.settings_texture_render_hint),
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

        // Demo modes and experiments (moved here 2026-09-17): test tools, kept apart from the
        // settings people use every day.
        Spacer(Modifier.height(4.dp))
        DemoModesGroup(vm)
        Spacer(Modifier.height(4.dp))
        SettingsGroup(title = stringResource(R.string.settings_experiments)) {
        ToggleRow(
            label = stringResource(R.string.exp_google_chooser),
            checked = app.vela.ui.Experiments.googleChooser.value,
            onCheckedChange = { app.vela.ui.Experiments.setGoogleChooser(context, it) },
            hint = stringResource(R.string.exp_google_chooser_hint),
        )
        }

        // Trip recording - more invasive than diagnostics (it's your exact routes),
        // so it's a separate opt-in. Records nav GPS traces for replay testing.
        LaunchedEffect(Unit) { vm.refreshTripRecording() }
        var showTripConsent by remember { mutableStateOf(false) }
        var shareTrip by remember { mutableStateOf<app.vela.replay.TripMeta?>(null) }
        // Reading the list opens every trip file (for the row's distance and duration), so it
        // never runs in composition or on the main thread.
        val scope = rememberCoroutineScope()
        var trips by remember { mutableStateOf<List<app.vela.replay.TripMeta>>(emptyList()) }
        var tripsLoaded by remember { mutableStateOf(false) }
        val reloadTrips: () -> Unit = {
            scope.launch {
                trips = withContext(Dispatchers.IO) { vm.recordedTrips() }
                tripsLoaded = true
            }
        }
        // Multi-select for export. Off until asked for: the common case is one trip, and a
        // checkbox on every row all the time would be clutter for it.
        var selecting by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var renaming by remember { mutableStateOf<app.vela.replay.TripMeta?>(null) }
        var batchShare by remember { mutableStateOf<List<app.vela.replay.TripMeta>?>(null) }
        var menuFor by remember { mutableStateOf<String?>(null) }
        // Re-read on entry so a trip recorded since the app launched shows up without
        // a restart (the list was otherwise only refreshed after a delete).
        LaunchedEffect(Unit) { reloadTrips() }
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_save_trips),
            checked = state.tripRecordingEnabled,
            onCheckedChange = { on -> if (on) showTripConsent = true else vm.setTripRecording(false) },
            hint = stringResource(R.string.settings_save_trips_hint),
        )
        // Naming on save only makes sense while trips are being recorded, so it hides with the
        // switch above rather than sitting there inert.
        if (state.tripRecordingEnabled) {
            ToggleRow(
                label = stringResource(R.string.settings_name_trips),
                checked = state.nameTripsOnSave,
                onCheckedChange = { vm.setNameTripsOnSave(it) },
                hint = stringResource(R.string.settings_name_trips_hint),
            )
        }
        if (trips.isNotEmpty()) {
            GroupDivider()
            Hint(stringResource(R.string.settings_recorded_trips_hint))
            // Selection bar. Sharing several drives at once is the point of the mode, so the
            // Share action carries the count and does nothing at zero rather than opening an
            // empty chooser.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!selecting) {
                    TextButton(
                        modifier = Modifier.dpadHighlight(),
                        onClick = { selecting = true; selected = emptySet() },
                    ) { Text(stringResource(R.string.settings_trip_select)) }
                } else {
                    TextButton(
                        modifier = Modifier.dpadHighlight(),
                        onClick = {
                            selected = if (selected.size == trips.size) emptySet() else trips.map { it.id }.toSet()
                        },
                    ) { Text(stringResource(R.string.settings_trip_select_all)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        modifier = Modifier.dpadHighlight(),
                        enabled = selected.isNotEmpty(),
                        // One trim distance for the whole set, chosen in a dialog that says what
                        // comes off before the zip is built.
                        onClick = { batchShare = trips.filter { it.id in selected } },
                    ) { Text(stringResource(R.string.settings_trip_share_selected, selected.size)) }
                    TextButton(
                        modifier = Modifier.dpadHighlight(),
                        onClick = { selecting = false; selected = emptySet() },
                    ) { Text(stringResource(R.string.settings_trip_select_done)) }
                }
            }
            trips.forEachIndexed { ti, t ->
                if (ti > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Two single lines: when (the thing people scan by) on top, then the figures
                    // and the name. The name goes last so a long street address is what gets cut.
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            if (t.startedAt > 0L) tripWhen(context, t.startedAt) else t.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tripFigures(t),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // NB an early `return@forEachIndexed` here crashes the Compose compiler
                    // ("No mapping for symbol" during IR lowering) - a composable lambda cannot
                    // return early past later composable calls. Keep this as if/else.
                    if (selecting) {
                        // In selection mode the row IS the checkbox - tapping it toggles, which is
                        // how every list of this shape behaves.
                        Checkbox(
                            modifier = Modifier.dpadHighlight(CircleShape),
                            checked = t.id in selected,
                            onCheckedChange = { on ->
                                selected = if (on) selected + t.id else selected - t.id
                            },
                        )
                    } else {
                    // D-pad: Replay/Share/More sit side by side inside the L/R-swallowing
                    // Column, so the group drives its own LEFT/RIGHT (issue #24 pattern).
                    val tripFocus = remember(t.id) { List(3) { FocusRequester() } }
                    IconButton(
                        modifier = Modifier.dpadHighlight(CircleShape).dpadRowSibling(tripFocus, 0),
                        onClick = { vm.replayTrip(t); onCloseSettings() },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.settings_trip_replay))
                    }
                    // Share the trace off-device - works on release builds, so a drive can be
                    // handed over for replay/debug without a dev build. Opens the trim dialog
                    // rather than sharing outright: a raw trip starts and ends at its owner's
                    // front door, and that decision should be made deliberately every time.
                    IconButton(
                        modifier = Modifier.dpadHighlight(CircleShape).dpadRowSibling(tripFocus, 1),
                        onClick = { shareTrip = t },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.settings_trip_share))
                    }
                    Box {
                        IconButton(
                            modifier = Modifier.dpadHighlight(CircleShape).dpadRowSibling(tripFocus, 2),
                            onClick = { menuFor = t.id },
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.settings_trip_more))
                        }
                        VelaMenu(expanded = menuFor == t.id, onDismissRequest = { menuFor = null }) {
                            item(stringResource(R.string.settings_trip_rename), Icons.Default.Edit) {
                                menuFor = null
                                renaming = t
                            }
                            item(stringResource(R.string.settings_trip_delete), Icons.Default.Delete) {
                                menuFor = null
                                vm.deleteTrip(t.id)
                                reloadTrips()
                            }
                        }
                    }
                    }
                }
            }
        } else if (state.tripRecordingEnabled && tripsLoaded) {
            Hint(stringResource(R.string.settings_no_trips_hint))
        }
        }
        renaming?.let { target ->
            // Seeded with the current name and remembered per trip, so opening the dialog on a
            // different row does not carry the previous row's text across.
            var draft by remember(target.id) { mutableStateOf(target.label) }
            app.vela.ui.VelaDialog(
                onDismissRequest = { renaming = null },
                title = stringResource(R.string.settings_trip_rename_title),
                confirmText = stringResource(R.string.settings_trip_rename),
                onConfirm = {
                    val name = draft.trim()
                    if (name.isNotEmpty()) {
                        val ok = vm.renameTrip(target.id, name)
                        // A rewrite can fail (missing file, unwritable) and the list would just
                        // redraw the old name, which reads as the button doing nothing.
                        if (!ok) android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_trip_rename_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        reloadTrips()
                    }
                    renaming = null
                },
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { renaming = null },
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_trip_rename_hint)) },
                    modifier = Modifier.fillMaxWidth().dpadHighlight(),
                )
            }
        }

        shareTrip?.let { meta -> TripShareDialog(meta, vm, context) { shareTrip = null } }
        batchShare?.let { metas -> TripBatchShareDialog(metas, vm, context, scope) { batchShare = null } }
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
    // "Redact places in exports" starts the dialog on the widest trim.
    var radius by remember { mutableStateOf(app.vela.core.replay.TripScrub.defaultRadius(vm.redactExports())) }
    // The scrub reads and rewrites the whole CSV, so it runs off the main thread and re-runs
    // when the radius changes, instead of inside composition.
    var report by remember(meta.id) { mutableStateOf<app.vela.core.replay.TripScrub.Report?>(null) }
    var scrubbed by remember(meta.id) { mutableStateOf(false) }
    LaunchedEffect(meta.id, radius) {
        report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.scrubTripForSharing(meta, radius) }
        scrubbed = true
    }
    app.vela.ui.VelaDialog(
        onDismissRequest = onClose,
        title = stringResource(R.string.settings_trip_share_title),
        confirmText = stringResource(R.string.settings_trip_share_trimmed),
        onConfirm = {
            val r = report
            val intent = r?.let { vm.shareScrubbedTripIntent(it, meta.startedAt) }
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
            TrimRadiusPicker(radius) { radius = it }
            Spacer(Modifier.height(8.dp))
            val shown = report
            if (!scrubbed) {
                // The summary arrives a moment after the dialog opens.
            } else if (shown == null) {
                Text(
                    stringResource(R.string.settings_trip_scrub_short),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(
                        R.string.settings_trip_share_summary,
                        shown.fixesRemoved, shown.fixesAfter,
                        shown.trimmedStartM.toInt(), shown.trimmedEndM.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Hint(stringResource(R.string.settings_trip_share_also))
                shown.firstRemaining?.let { p ->
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

/** The trim-distance choice both share dialogs offer; the picked one is the filled pill. */
@Composable
private fun TrimRadiusPicker(radius: Double, onPick: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (m in app.vela.core.replay.TripScrub.RADIUS_OPTIONS_M) {
            if (m == radius) {
                FilledTonalButton(modifier = Modifier.dpadHighlight(CircleShape), onClick = { onPick(m) }) {
                    Text(stringResource(R.string.settings_trip_share_radius_m, m.toInt()))
                }
            } else {
                TextButton(modifier = Modifier.dpadHighlight(CircleShape), onClick = { onPick(m) }) {
                    Text(stringResource(R.string.settings_trip_share_radius_m, m.toInt()))
                }
            }
        }
    }
}

/** "Sep 7, 7:42 PM": when a drive started, short enough for one list line. The year shows only
 *  when it is not this year, and the clock follows the device's 12/24-hour setting. */
private fun tripWhen(context: android.content.Context, epochMs: Long): String =
    android.text.format.DateUtils.formatDateTime(
        context, epochMs,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
    )

/** "12.3 mi · 24 min · Trip name": the row's second line. Figures a trip does not have (an old or
 *  very short recording) are left out rather than shown as zero. */
private fun tripFigures(t: app.vela.replay.TripMeta): String = listOfNotNull(
    t.distanceM.takeIf { it >= 1.0 }?.let { app.vela.ui.formatDistance(it) },
    t.durationMs.takeIf { it > 0L }?.let { app.vela.ui.formatDuration(it / 1000.0) },
    t.label.takeIf { t.startedAt > 0L },
).joinToString(" · ")

/**
 * The trim-before-you-share dialog for SEVERAL trips, sent as one zip.
 *
 * One trim distance applies to every trip in the set, and the summary adds up what comes off
 * across all of them before anything is built. A trip too short to keep anything is left out and
 * counted as such; it is never sent raw. [scope] belongs to the Settings page, so the zip is still
 * built and handed to the share sheet after this dialog has closed.
 */
@Composable
private fun TripBatchShareDialog(
    metas: List<app.vela.replay.TripMeta>,
    vm: MapViewModel,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
) {
    var radius by remember { mutableStateOf(app.vela.core.replay.TripScrub.defaultRadius(vm.redactExports())) }
    var reports by remember { mutableStateOf<List<app.vela.core.replay.TripScrub.Report?>?>(null) }
    LaunchedEffect(radius) {
        reports = null
        reports = withContext(Dispatchers.IO) { vm.scrubTripsForSharing(metas, radius) }
    }
    val summary = reports?.let { app.vela.core.replay.TripShareBatch.summarize(it) }
    app.vela.ui.VelaDialog(
        onDismissRequest = onClose,
        title = stringResource(R.string.settings_trip_batch_title),
        confirmText = stringResource(R.string.settings_trip_batch_confirm),
        onConfirm = confirm@{
            val r = reports ?: return@confirm // still trimming; the summary is not up yet
            if (summary == null || summary.kept == 0) {
                android.widget.Toast.makeText(
                    context, context.getString(R.string.settings_trip_batch_none), android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@confirm
            }
            onClose()
            scope.launch {
                val intent = withContext(Dispatchers.IO) { vm.shareTripsZipIntent(metas, r) }
                if (intent != null) runCatching { context.startActivity(intent) }
                else android.widget.Toast.makeText(
                    context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        },
        dismissText = stringResource(R.string.settings_cancel),
        onDismiss = onClose,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.settings_trip_batch_explain),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_trip_share_radius),
                style = MaterialTheme.typography.labelLarge,
            )
            TrimRadiusPicker(radius) { radius = it }
            Spacer(Modifier.height(8.dp))
            if (summary == null) {
                // The summary arrives a moment after the dialog opens.
            } else if (summary.kept == 0) {
                Text(
                    stringResource(R.string.settings_trip_batch_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(
                        R.string.settings_trip_batch_summary,
                        summary.kept, summary.picked, summary.fixesRemoved, summary.fixesKept,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (summary.leftOut > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_trip_batch_left_out, summary.leftOut),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Hint(stringResource(R.string.settings_trip_share_also))
            }
        }
    }
}
