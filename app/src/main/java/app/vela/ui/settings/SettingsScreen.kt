package app.vela.ui.settings
import android.content.Intent
import android.net.Uri

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import app.vela.core.feedback.Haptics
import app.vela.core.model.TravelMode
import app.vela.offline.OfflineMaps
import org.maplibre.android.offline.OfflineRegion
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.ui.Onboarding
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.Units
import app.vela.ui.map.MapViewModel
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // System back should return to the map, not fall through and exit the app.
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle("Appearance")
            SelectableRow(
                label = "Follow system",
                selected = AppTheme.mode.value == ThemeMode.SYSTEM,
                onClick = { AppTheme.set(context, ThemeMode.SYSTEM) },
            )
            SelectableRow(
                label = "Light",
                selected = AppTheme.mode.value == ThemeMode.LIGHT,
                onClick = { AppTheme.set(context, ThemeMode.LIGHT) },
            )
            SelectableRow(
                label = "Dark",
                selected = AppTheme.mode.value == ThemeMode.DARK,
                onClick = { AppTheme.set(context, ThemeMode.DARK) },
            )
            Hint("Light/Dark applies to Vela only — it won't touch your phone's system theme.")

            Spacer(Modifier.height(20.dp))
            SectionTitle("Map style")
            MapStyle.values().forEach { style ->
                SelectableRow(
                    label = style.label,
                    selected = state.styleName == style.label,
                    onClick = { vm.setStyle(style) },
                )
            }
            Hint("OpenFreeMap (the default) is keyless and detailed. Protomaps needs an API key; the demo style is country outlines only.")

            Spacer(Modifier.height(20.dp))
            SectionTitle("Units")
            SelectableRow(
                label = "Imperial (miles, feet)",
                selected = Units.imperial.value,
                onClick = { Units.set(context, true) },
            )
            SelectableRow(
                label = "Metric (kilometers, meters)",
                selected = !Units.imperial.value,
                onClick = { Units.set(context, false) },
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Voice")
            // Vela's own on-device neural voices — offer a one-tap download for whichever isn't
            // present yet; once downloaded each shows in the engine list below (selectable). No
            // standalone TTS app needed. Kokoro = premium/slower, Piper = fast.
            val dlPct = state.kokoroDownloadPct
            if (dlPct != null) {
                Text("Downloading neural voice… ${(dlPct * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { dlPct }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            } else {
                if (!vm.piperInstalled()) {
                    Button(onClick = { vm.downloadPiper() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download the Vela voice · ~67 MB")
                    }
                    Hint("A natural on-device voice for spoken directions — no account, no standalone app, and it speaks in real time even on old phones. One-time download; wifi recommended.")
                    Spacer(Modifier.height(8.dp))
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
            val engines = vm.voiceEngines()
            if (engines.isEmpty()) {
                Hint("No voice available yet — download the neural voice above. (You can also install a system TTS engine and it'll appear here to pick.)")
            } else {
                engines.forEach { e ->
                    SelectableRow(
                        label = e.label,
                        selected = state.selectedEngine?.packageName == e.packageName,
                        onClick = { vm.setVoiceEngine(e) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.testVoice() }) { Text("Test voice") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("System voice settings") }
                }
                Hint("Tap Test voice to hear it. The neural voice is recommended; you can override to any installed text-to-speech engine.")

                // Speed + the niche bits (playground, the 900-voice variant picker) — most people never
                // touch these, so tuck them behind a collapsible header (collapsed by default).
                var voiceAdvExpanded by remember { mutableStateOf(false) }
                CollapsibleSectionTitle("Advanced voice options", voiceAdvExpanded) { voiceAdvExpanded = !voiceAdvExpanded }
                if (voiceAdvExpanded) {
                // Playground: hear the selected voice on any text (or a nav-style sample).
                Spacer(Modifier.height(12.dp))
                var tryText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Try the voice on any text") },
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.speakText(tryText) }, enabled = tryText.isNotBlank()) { Text("Speak") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.speakText("In a quarter mile, turn right onto Main Street, then your destination is on the left.")
                    }) { Text("Nav sample") }
                }
                // Speed applies to whichever voice is selected (neural + system TTS).
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Voice speed · ${"%.2fx".format(state.voiceSpeed)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.setVoiceSpeed(-0.1f) }) { Text("−") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.setVoiceSpeed(0.1f) }) { Text("+") }
                }
                Hint("Slower or faster spoken directions — tap − / + (it speaks a sample). 1.00× is normal.")
                // The Vela voice (libritts_r) is multi-speaker — let the user audition + pick one.
                if (state.selectedEngine?.packageName?.startsWith("vela.") == true) {
                    Spacer(Modifier.height(10.dp))
                    val cnt = vm.voiceSpeakerCount()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Voice variant #${state.voiceSpeaker}" + (if (cnt > 0) " of $cnt" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.stepSpeaker(-1) }) { Text("◀") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { vm.stepSpeaker(1) }) { Text("▶") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Jump straight to a variant number (904 is a lot to step through).
                    var jump by remember { mutableStateOf("") }
                    val goToVariant = {
                        jump.trim().toIntOrNull()?.let { vm.setSpeaker(it) }
                        jump = ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = jump,
                            onValueChange = { s -> jump = s.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            label = { Text("Variant #") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { goToVariant() }),
                            modifier = Modifier.width(150.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = goToVariant, enabled = jump.isNotBlank()) { Text("Go") }
                    }
                    Hint("This voice has hundreds of speakers — tap ◀ ▶ to audition one at a time, or type a variant number and Go to jump straight to it (it speaks a sample). Keep the one you like.")
                }
                } // end "Advanced voice options"
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Navigation")
            val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
            Text("Vibrate on turns", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            listOf(
                TravelMode.DRIVE to "Driving",
                TravelMode.WALK to "Walking",
                TravelMode.BICYCLE to "Cycling",
                TravelMode.TRANSIT to "Transit",
            ).forEach { (mode, label) ->
                var on by remember(mode) {
                    val default = if (!prefs.getBoolean(Haptics.KEY, true)) false else Haptics.defaultFor(mode)
                    mutableStateOf(prefs.getBoolean(Haptics.keyFor(mode), default))
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = on,
                        onCheckedChange = {
                            on = it
                            prefs.edit().putBoolean(Haptics.keyFor(mode), it).apply()
                        },
                    )
                }
            }
            Hint("Direction-coded buzzes at each turn — distinct for left vs right — so you can follow a route by feel. Set it per travel mode (e.g. on for cycling and walking, off while driving).")

            var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_nav", true)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Keep screen on while navigating", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = keepAwake,
                    onCheckedChange = {
                        keepAwake = it
                        prefs.edit().putBoolean("keep_screen_on_nav", it).apply()
                    },
                )
            }
            Hint("Stops the display from dimming or sleeping while turn-by-turn is running, so the next turn is always visible without tapping to wake it. On by default; the screen sleeps normally again the moment you arrive or leave navigation.")

            Spacer(Modifier.height(20.dp))
            SectionTitle("Map")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Live traffic overlay", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = app.vela.ui.Traffic.on.value,
                    onCheckedChange = { app.vela.ui.Traffic.set(context, it) },
                )
            }
            Hint("Shades roads by congestion while browsing the map (Google's keyless traffic tiles). Off by default — navigation already colours your route by traffic, so this is just for scanning the wider area. It's a raster overlay, so it's a touch grainy.")

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("“Read all reviews” button", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = app.vela.ui.LiveReviews.on.value,
                    onCheckedChange = { app.vela.ui.LiveReviews.set(context, it) },
                )
            }
            Hint("Reviews in the place sheet are always Vela's own smooth native list. This adds a “Read all reviews” button that opens Google's full reviews page FULL-SCREEN — every review (not just the first ~50), Google's server-side search, and video reviews play. Trackers/beacons are blocked, but it does run Google's page inside the app. Off = native list only, no Google page for reviews.")

            Spacer(Modifier.height(20.dp))
            // Collapsed by default — the routing-region list can be long, so don't make the user
            // scroll past all of it to reach the sections below.
            var offlineExpanded by remember { mutableStateOf(false) }
            CollapsibleSectionTitle("Offline", offlineExpanded) { offlineExpanded = !offlineExpanded }
            if (offlineExpanded) {
            Hint("Using the map without signal takes two things — the map TILES you see, and the ROAD NETWORK that routes you. Saving a map area grabs both for that spot; the region list below adds the road network for anywhere you're travelling.")
            SubHead("Map area")
            var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
            LaunchedEffect(Unit) { OfflineMaps.list(context) { regions = it } }
            OutlinedButton(
                onClick = {
                    vm.downloadViewport()
                    onBack() // back to the map so the user sees the download progress
                },
                enabled = vm.hasViewport(),
            ) { Text("Download the area you're viewing") }
            Hint("Saves the open tiles for the map area you last had on screen — and the routing region around it — so it renders and navigates later with no network. Search still needs a connection.")
            if (regions.isEmpty()) {
                Hint("No areas saved yet.")
            } else {
                regions.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            OfflineMaps.nameOf(r),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { OfflineMaps.delete(r) { OfflineMaps.list(context) { regions = it } } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete this offline area")
                        }
                    }
                }
            }

            SubHead("Routing regions")
            LaunchedEffect(Unit) { vm.refreshRoutingRegions() }
            Hint("The road network for a whole region (state, country…), so turn-by-turn works offline anywhere inside it — grab where you're travelling. Online still uses live traffic; this is the no-signal fallback.")
            if (state.routingRegions.isEmpty()) {
                Hint("No regions available yet.")
            } else {
                val loc = state.myLocation
                val covers = { r: app.vela.offline.RoutingRegion ->
                    loc != null && loc.lat in r.s..r.n && loc.lng in r.w..r.e
                }
                // The region you're IN = the SMALLEST bbox that contains you. Region boxes carry a Geofabrik
                // buffer that spills across borders (British Columbia's box dips into Seattle), so "any box that
                // covers you" mislabels big neighbours — the smallest covering box is the specific one. Sort:
                // installed first (manage what you have), then that primary region, then everything by name.
                val primary = state.routingRegions.filter(covers)
                    .minByOrNull { (it.n - it.s) * (it.e - it.w) }
                val ordered = state.routingRegions.sortedWith(
                    compareByDescending<app.vela.offline.RoutingRegion> { it.id in state.routingInstalledIds }
                        .thenByDescending { it.id == primary?.id }
                        .thenBy { it.name },
                )
                // With a world-sized catalog, a name filter makes a region you're TRAVELLING to findable
                // without scrolling past a hundred others (the sort above handles where you are now).
                var routeFilter by remember { mutableStateOf("") }
                if (state.routingRegions.size > 8) {
                    OutlinedTextField(
                        value = routeFilter,
                        onValueChange = { routeFilter = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (routeFilter.isNotEmpty()) {
                                IconButton(onClick = { routeFilter = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear filter")
                                }
                            }
                        },
                        placeholder = { Text("Filter ${state.routingRegions.size} regions… (e.g. Japan, Texas)") },
                    )
                }
                val shown = if (routeFilter.isBlank()) ordered
                    else ordered.filter { it.name.contains(routeFilter.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    Hint("No regions match “${routeFilter.trim()}”.")
                }
                shown.forEach { region ->
                    val installed = region.id in state.routingInstalledIds
                    val downloading = state.routingDownloadingId == region.id
                    val here = region.id == primary?.id
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(region.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    downloading -> "Downloading… ${state.routingDownloadPct}%"
                                    installed -> "Installed · routes offline here"
                                    here -> "${region.sizeMb} MB · covers your location"
                                    else -> "${region.sizeMb} MB"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (here && !installed && !downloading) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            installed -> IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove offline routing")
                            }
                            else -> OutlinedButton(
                                onClick = { vm.downloadRoutingGraph(region) },
                                enabled = state.routingDownloadingId == null,
                            ) { Text("Download") }
                        }
                    }
                }
            }
            } // end if (offlineExpanded)

            Spacer(Modifier.height(20.dp))
            SectionTitle("Saved places")
            Hint("Back up your starred places to a file, or restore them on another device. Import merges into what you already have — it never overwrites or removes anything.")
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val n = vm.importSavedFromUri(uri)
                    android.widget.Toast.makeText(
                        context,
                        if (n > 0) "Imported $n place${if (n == 1) "" else "s"}" else "Nothing new to import",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                OutlinedButton(onClick = {
                    val intent = vm.exportSavedIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(context, "No saved places yet", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Export") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    runCatching { importLauncher.launch(arrayOf("application/json", "*/*")) }
                }) { Text("Import") }
            }
            Spacer(Modifier.height(8.dp))

            SectionTitle("Data source & privacy")
            Hint(
                "Vela talks to Google's public web endpoints directly from this device — no " +
                    "Vela backend, no Google account, no API key, and no telemetry unless you turn " +
                    "on Diagnostics below (off by default, local-only). Each device behaves " +
                    "like a logged-out browser; Google sees your IP, query and map area but not an " +
                    "account. Saved places and history never leave the phone.",
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/PimpinPumpkin/Vela/blob/main/PRIVACY.md"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("What data Google receives") }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Diagnostics")
            LaunchedEffect(Unit) { vm.refreshDiagnostics() }
            Hint(
                "Off by default. When on, Vela keeps a short local log of what it did — your " +
                    "searches, routes, and any “needs recalibration” hiccups — so if something " +
                    "misbehaves you can export it and hand it to a developer. It stays on this phone and " +
                    "is never uploaded; you pick where the export goes. Turning it off wipes the log.",
            )
            var showDiagConsent by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Share diagnostics", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.diagnosticsEnabled,
                    onCheckedChange = { on -> if (on) showDiagConsent = true else vm.setDiagnostics(false) },
                )
            }
            if (state.diagnosticsEnabled) {
                OutlinedButton(onClick = {
                    val intent = vm.diagShareIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(
                        context,
                        "Nothing recorded yet — use the app, then export.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Export debug session") }
            }

            // Trip recording — more invasive than diagnostics (it's your exact routes),
            // so it's a separate opt-in. Records nav GPS traces for replay testing.
            LaunchedEffect(Unit) { vm.refreshTripRecording() }
            var showTripConsent by remember { mutableStateOf(false) }
            var trips by remember { mutableStateOf(vm.recordedTrips()) }
            // Re-read on entry so a trip recorded since the app launched shows up without
            // a restart (the list was otherwise only refreshed after a delete).
            LaunchedEffect(Unit) { trips = vm.recordedTrips() }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Save my trips (for replay)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.tripRecordingEnabled,
                    onCheckedChange = { on -> if (on) showTripConsent = true else vm.setTripRecording(false) },
                )
            }
            Hint("Records each navigation's GPS trace on this phone so a drive can be replayed later to test turn-by-turn without driving it again. More revealing than diagnostics — it's your exact routes — and never leaves the phone.")
            if (trips.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Hint("Recorded trips — tap Replay to play one back on the map (3×):")
                trips.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            val recordedAt = if (t.startedAt > 0L)
                                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                                    .format(java.util.Date(t.startedAt))
                            else null
                            Hint(listOfNotNull(recordedAt, "${t.fixCount} points").joinToString(" · "))
                        }
                        TextButton(onClick = { vm.replayTrip(t); onBack() }) { Text("Replay") }
                        // Share the raw trace off-device — works on release builds, so a
                        // drive can be handed over for replay/debug without a dev build.
                        TextButton(onClick = {
                            val intent = vm.exportTripIntent(t)
                            if (intent != null) runCatching { context.startActivity(intent) }
                            else android.widget.Toast.makeText(context, "Couldn't read that trip", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("Share") }
                        IconButton(onClick = { vm.deleteTrip(t.id); trips = vm.recordedTrips() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete trip")
                        }
                    }
                }
            } else if (state.tripRecordingEnabled) {
                Spacer(Modifier.height(4.dp))
                Hint("No trips recorded yet — navigate somewhere with this on and it'll show up here to replay.")
            }
            if (showTripConsent) {
                AlertDialog(
                    onDismissRequest = { showTripConsent = false },
                    title = { Text("Save your trips?") },
                    text = {
                        Text(
                            "Vela will record the GPS trace of each navigation on this phone, so a drive " +
                                "can be replayed for testing without driving it again. This is more revealing " +
                                "than diagnostics — it captures your exact routes — but it stays on the phone " +
                                "and is never uploaded. Turn it off any time.",
                        )
                    },
                    confirmButton = { TextButton(onClick = { vm.setTripRecording(true); showTripConsent = false }) { Text("Turn on") } },
                    dismissButton = { TextButton(onClick = { showTripConsent = false }) { Text("Cancel") } },
                )
            }
            var crashReports by remember { mutableStateOf(app.vela.diag.CrashCatcher.pending(context)) }
            if (crashReports.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Hint("Vela closed unexpectedly recently. Sending the crash report (stack trace) helps fix it — it's a plain text file, no personal data.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        app.vela.diag.CrashCatcher.shareIntent(context)?.let { runCatching { context.startActivity(it) } }
                    }) { Text("Export crash report") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        app.vela.diag.CrashCatcher.clear(context); crashReports = emptyList()
                    }) { Text("Discard") }
                }
            }
            if (showDiagConsent) {
                AlertDialog(
                    onDismissRequest = { showDiagConsent = false },
                    title = { Text("Turn on diagnostics?") },
                    text = {
                        Text(
                            "Vela will keep a short local log of your searches, routes and any errors. " +
                                "It never leaves the phone unless you tap Export and choose where to send " +
                                "it. Turn it off any time to wipe the log.",
                        )
                    },
                    confirmButton = { TextButton(onClick = { vm.setDiagnostics(true); showDiagConsent = false }) { Text("Turn on") } },
                    dismissButton = { TextButton(onClick = { showDiagConsent = false }) { Text("Cancel") } },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("About")
            Hint(
                "Vela is a degoogled maps client: open tiles for the basemap, scraped Google " +
                    "for POIs, routing and traffic-aware ETAs, AOSP TextToSpeech for voice. GPLv3. " +
                    "No Play Services required.",
            )
            Spacer(Modifier.height(20.dp))
            SectionTitle("Support")
            Hint("Vela is free and ad-free. If it's become useful, a donation helps keep development going — entirely optional.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Onboarding.DONATE_URL)))
                    }
                },
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Support Vela")
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Version")
            Text(
                "Vela ${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** A [SectionTitle] that toggles a collapsible body — tap the whole row; a chevron shows the state. */
@Composable
private fun CollapsibleSectionTitle(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A lighter heading for sub-parts within a section (e.g. "Map area" / "Routing regions" under "Offline"). */
@Composable
private fun SubHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
