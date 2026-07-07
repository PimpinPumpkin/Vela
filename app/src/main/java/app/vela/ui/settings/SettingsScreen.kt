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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vela.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.ui.Onboarding
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.Units
import androidx.compose.foundation.layout.Arrangement
import app.vela.core.voice.PiperCatalog
import app.vela.core.voice.PiperVoice
import app.vela.ui.map.MapUiState
import app.vela.ui.map.MapViewModel
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MapViewModel, onBack: () -> Unit, openOffline: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // System back should return to the map, not fall through and exit the app.
    BackHandler(onBack = onBack)
    // When arriving from the onboarding "set up offline" prompt we open expanded and
    // scroll straight to the Offline section (it sits well below the fold).
    val scrollState = rememberScrollState()
    var viewportTopY by remember { mutableStateOf<Float?>(null) }
    var offlineSectionY by remember { mutableStateOf<Float?>(null) }
    var didAutoScroll by remember { mutableStateOf(false) }
    LaunchedEffect(openOffline, viewportTopY, offlineSectionY) {
        if (openOffline && !didAutoScroll) {
            val top = viewportTopY
            val sec = offlineSectionY
            if (top != null && sec != null) {
                didAutoScroll = true
                scrollState.animateScrollTo((scrollState.value + (sec - top)).toInt().coerceAtLeast(0))
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .onGloballyPositioned { viewportTopY = it.positionInRoot().y }
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            SelectableRow(
                label = stringResource(R.string.settings_follow_system),
                selected = AppTheme.mode.value == ThemeMode.SYSTEM,
                onClick = { AppTheme.set(context, ThemeMode.SYSTEM) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_theme_light),
                selected = AppTheme.mode.value == ThemeMode.LIGHT,
                onClick = { AppTheme.set(context, ThemeMode.LIGHT) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_theme_dark),
                selected = AppTheme.mode.value == ThemeMode.DARK,
                onClick = { AppTheme.set(context, ThemeMode.DARK) },
            )
            Hint(stringResource(R.string.settings_appearance_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_map_style))
            MapStyle.values().forEach { style ->
                SelectableRow(
                    label = style.label,
                    selected = state.styleName == style.label,
                    onClick = { vm.setStyle(style) },
                )
            }
            Hint(stringResource(R.string.settings_map_style_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_units))
            SelectableRow(
                label = stringResource(R.string.settings_units_imperial),
                selected = Units.imperial.value,
                onClick = { Units.set(context, true) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_units_metric),
                selected = !Units.imperial.value,
                onClick = { Units.set(context, false) },
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_language))
            SelectableRow(
                label = stringResource(R.string.settings_follow_system),
                selected = app.vela.ui.AppLocale.language.value.isBlank(),
                onClick = { app.vela.ui.AppLocale.set(context, "") },
            )
            app.vela.ui.AppLocale.SUPPORTED.forEach { code ->
                SelectableRow(
                    label = app.vela.ui.AppLocale.endonym(code),
                    selected = app.vela.ui.AppLocale.language.value == code,
                    onClick = { app.vela.ui.AppLocale.set(context, code) },
                )
            }
            Hint(stringResource(R.string.settings_language_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_voice))
            // Vela's own on-device neural voices — offer a one-tap download for whichever isn't
            // present yet; once downloaded each shows in the engine list below (selectable). No
            // standalone TTS app needed. Kokoro = premium/slower, Piper = fast.
            // A download in flight shows a compact progress line here too, so it's visible even when the
            // Voice library (below) is collapsed. The per-voice controls live in the library.
            state.voiceDownloadingId?.let { id ->
                val nm = vm.voiceCatalog().firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_fallback_name)
                val pct = state.kokoroDownloadPct ?: 0f
                Text(stringResource(R.string.settings_voice_downloading, nm, (pct * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            } ?: Spacer(Modifier.height(4.dp))
            val engines = vm.voiceEngines()
            if (engines.isEmpty()) {
                Hint(stringResource(R.string.settings_voice_none_hint))
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
                    OutlinedButton(onClick = { vm.testVoice() }) { Text(stringResource(R.string.settings_voice_test)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(stringResource(R.string.settings_voice_system_settings)) }
                }
                Hint(stringResource(R.string.settings_voice_test_hint))
            }

            // Voice library — browse, download, switch between and remove Vela's neural voices (Piper).
            // Auto-expanded when nothing is installed so the download path is obvious.
            var voiceLibExpanded by remember { mutableStateOf(state.installedVoiceIds.isEmpty()) }
            CollapsibleSectionTitle(stringResource(R.string.settings_voice_library), voiceLibExpanded) { voiceLibExpanded = !voiceLibExpanded }
            if (voiceLibExpanded) VoiceLibrary(vm, state)

            if (engines.isNotEmpty()) {
                // Speed + the niche bits (playground, the multi-speaker variant picker) — most people never
                // touch these, so tuck them behind a collapsible header (collapsed by default).
                var voiceAdvExpanded by remember { mutableStateOf(false) }
                CollapsibleSectionTitle(stringResource(R.string.settings_voice_advanced), voiceAdvExpanded) { voiceAdvExpanded = !voiceAdvExpanded }
                if (voiceAdvExpanded) {
                // Playground: hear the selected voice on any text (or a nav-style sample).
                Spacer(Modifier.height(12.dp))
                var tryText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_voice_try_label)) },
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                val navSampleText = stringResource(R.string.settings_voice_nav_sample_text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.speakText(tryText) }, enabled = tryText.isNotBlank()) { Text(stringResource(R.string.settings_voice_speak)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.speakText(navSampleText)
                    }) { Text(stringResource(R.string.settings_voice_nav_sample)) }
                }
                // Speed applies to whichever voice is selected (neural + system TTS).
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_voice_speed, "%.2fx".format(state.voiceSpeed)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.setVoiceSpeed(-0.1f) }) { Text("−") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.setVoiceSpeed(0.1f) }) { Text("+") }
                }
                Hint(stringResource(R.string.settings_voice_speed_hint))
                // Multi-speaker Vela voices (libritts_r=904, VCTK=109, Arctic=18) — let the user audition +
                // pick a variant. Hidden for single-speaker voices (lessac/hfc/…), where it's meaningless.
                if (state.selectedEngine?.packageName?.startsWith("vela.") == true && vm.voiceSpeakerCount() > 1) {
                    Spacer(Modifier.height(10.dp))
                    val cnt = vm.voiceSpeakerCount()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cnt > 0) stringResource(R.string.settings_voice_variant_of, state.voiceSpeaker, cnt)
                            else stringResource(R.string.settings_voice_variant, state.voiceSpeaker),
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
                            label = { Text(stringResource(R.string.settings_voice_variant_field)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { goToVariant() }),
                            modifier = Modifier.width(150.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = goToVariant, enabled = jump.isNotBlank()) { Text(stringResource(R.string.settings_voice_variant_go)) }
                    }
                    Hint(stringResource(R.string.settings_voice_variant_hint))
                }
                } // end "Advanced voice options"
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_navigation))
            val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
            Text(stringResource(R.string.settings_vibrate_on_turns), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            listOf(
                TravelMode.DRIVE to stringResource(R.string.settings_mode_driving),
                TravelMode.WALK to stringResource(R.string.settings_mode_walking),
                TravelMode.BICYCLE to stringResource(R.string.settings_mode_cycling),
                TravelMode.TRANSIT to stringResource(R.string.settings_mode_transit),
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
            Hint(stringResource(R.string.settings_vibrate_hint))

            var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_nav", true)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_keep_screen_on), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = keepAwake,
                    onCheckedChange = {
                        keepAwake = it
                        prefs.edit().putBoolean("keep_screen_on_nav", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_keep_screen_on_hint))

            var trafficLights by remember { mutableStateOf(prefs.getBoolean("nav_traffic_lights", false)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_traffic_lights), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = trafficLights,
                    onCheckedChange = {
                        trafficLights = it
                        prefs.edit().putBoolean("nav_traffic_lights", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_traffic_lights_hint))

            var demoDrive by remember { mutableStateOf(prefs.getBoolean("demo_drive", false)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_demo_drive), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = demoDrive,
                    onCheckedChange = {
                        demoDrive = it
                        prefs.edit().putBoolean("demo_drive", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_demo_drive_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_map))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_live_traffic), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = app.vela.ui.Traffic.on.value,
                    onCheckedChange = { app.vela.ui.Traffic.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_live_traffic_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_read_all_reviews), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = app.vela.ui.LiveReviews.on.value,
                    onCheckedChange = { app.vela.ui.LiveReviews.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_read_all_reviews_hint))

            Spacer(Modifier.height(20.dp).onGloballyPositioned { offlineSectionY = it.positionInRoot().y })
            // Collapsed by default — the routing-region list can be long, so don't make the user
            // scroll past all of it to reach the sections below. Opens expanded when the onboarding
            // offline prompt sent us here.
            var offlineExpanded by remember { mutableStateOf(openOffline) }
            CollapsibleSectionTitle(stringResource(R.string.settings_offline), offlineExpanded) { offlineExpanded = !offlineExpanded }
            if (offlineExpanded) {
            Hint(stringResource(R.string.settings_offline_hint))
            SubHead(stringResource(R.string.settings_offline_map_area))
            var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
            LaunchedEffect(Unit) { OfflineMaps.list(context) { regions = it } }
            OutlinedButton(
                onClick = {
                    vm.downloadViewport()
                    onBack() // back to the map so the user sees the download progress
                },
                enabled = vm.hasViewport(),
            ) { Text(stringResource(R.string.settings_offline_download_viewport)) }
            Hint(stringResource(R.string.settings_offline_download_viewport_hint))
            if (regions.isEmpty()) {
                Hint(stringResource(R.string.settings_offline_no_areas))
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
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_offline_delete_area))
                        }
                    }
                }
            }

            SubHead(stringResource(R.string.settings_routing_regions))
            LaunchedEffect(Unit) { vm.refreshRoutingRegions() }
            Hint(stringResource(R.string.settings_routing_regions_hint))
            if (state.routingRegions.isEmpty()) {
                Hint(stringResource(R.string.settings_routing_no_regions))
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
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.settings_clear_filter))
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.settings_routing_filter_placeholder, state.routingRegions.size)) },
                    )
                }
                val shown = if (routeFilter.isBlank()) ordered
                    else ordered.filter { it.name.contains(routeFilter.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    Hint(stringResource(R.string.settings_routing_no_match, routeFilter.trim()))
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
                                    downloading -> stringResource(R.string.settings_routing_downloading, state.routingDownloadPct)
                                    installed -> stringResource(R.string.settings_routing_installed)
                                    here -> stringResource(R.string.settings_routing_size_here, region.sizeMb)
                                    else -> stringResource(R.string.settings_routing_size, region.sizeMb)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (here && !installed && !downloading) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            installed -> IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                            }
                            else -> OutlinedButton(
                                onClick = { vm.downloadRoutingGraph(region) },
                                enabled = state.routingDownloadingId == null,
                            ) { Text(stringResource(R.string.settings_download)) }
                        }
                    }
                }
            }
            } // end if (offlineExpanded)

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_saved_places))
            Hint(stringResource(R.string.settings_saved_places_hint))
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val n = vm.importSavedFromUri(uri)
                    android.widget.Toast.makeText(
                        context,
                        if (n > 0) "Imported $n place${if (n == 1) "" else "s"}" else context.getString(R.string.settings_import_nothing),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                OutlinedButton(onClick = {
                    val intent = vm.exportSavedIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(context, context.getString(R.string.settings_no_saved_places), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.settings_export)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    runCatching { importLauncher.launch(arrayOf("application/json", "*/*")) }
                }) { Text(stringResource(R.string.settings_import)) }
            }
            Spacer(Modifier.height(8.dp))

            SectionTitle(stringResource(R.string.settings_data_privacy))
            Hint(stringResource(R.string.settings_data_privacy_hint))
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
            }) { Text(stringResource(R.string.settings_privacy_button)) }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_diagnostics))
            LaunchedEffect(Unit) { vm.refreshDiagnostics() }
            Hint(stringResource(R.string.settings_diagnostics_hint))
            var showDiagConsent by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_share_diagnostics), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
                        context.getString(R.string.settings_diag_nothing),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_diag_export)) }
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
                Text(stringResource(R.string.settings_save_trips), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.tripRecordingEnabled,
                    onCheckedChange = { on -> if (on) showTripConsent = true else vm.setTripRecording(false) },
                )
            }
            Hint(stringResource(R.string.settings_save_trips_hint))
            if (trips.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Hint(stringResource(R.string.settings_recorded_trips_hint))
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
                            Hint(listOfNotNull(recordedAt, stringResource(R.string.settings_trip_points, t.fixCount)).joinToString(" · "))
                        }
                        TextButton(onClick = { vm.replayTrip(t); onBack() }) { Text(stringResource(R.string.settings_trip_replay)) }
                        // Share the raw trace off-device — works on release builds, so a
                        // drive can be handed over for replay/debug without a dev build.
                        TextButton(onClick = {
                            val intent = vm.exportTripIntent(t)
                            if (intent != null) runCatching { context.startActivity(intent) }
                            else android.widget.Toast.makeText(context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.settings_trip_share)) }
                        IconButton(onClick = { vm.deleteTrip(t.id); trips = vm.recordedTrips() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_trip_delete))
                        }
                    }
                }
            } else if (state.tripRecordingEnabled) {
                Spacer(Modifier.height(4.dp))
                Hint(stringResource(R.string.settings_no_trips_hint))
            }
            if (showTripConsent) {
                AlertDialog(
                    onDismissRequest = { showTripConsent = false },
                    title = { Text(stringResource(R.string.settings_trip_consent_title)) },
                    text = {
                        Text(stringResource(R.string.settings_trip_consent_body))
                    },
                    confirmButton = { TextButton(onClick = { vm.setTripRecording(true); showTripConsent = false }) { Text(stringResource(R.string.settings_turn_on)) } },
                    dismissButton = { TextButton(onClick = { showTripConsent = false }) { Text(stringResource(R.string.settings_cancel)) } },
                )
            }
            var crashReports by remember { mutableStateOf(app.vela.diag.CrashCatcher.pending(context)) }
            if (crashReports.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Hint(stringResource(R.string.settings_crash_hint))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        app.vela.diag.CrashCatcher.shareIntent(context)?.let { runCatching { context.startActivity(it) } }
                    }) { Text(stringResource(R.string.settings_crash_export)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        app.vela.diag.CrashCatcher.clear(context); crashReports = emptyList()
                    }) { Text(stringResource(R.string.settings_crash_discard)) }
                }
            }
            if (showDiagConsent) {
                AlertDialog(
                    onDismissRequest = { showDiagConsent = false },
                    title = { Text(stringResource(R.string.settings_diag_consent_title)) },
                    text = {
                        Text(stringResource(R.string.settings_diag_consent_body))
                    },
                    confirmButton = { TextButton(onClick = { vm.setDiagnostics(true); showDiagConsent = false }) { Text(stringResource(R.string.settings_turn_on)) } },
                    dismissButton = { TextButton(onClick = { showDiagConsent = false }) { Text(stringResource(R.string.settings_cancel)) } },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_about))
            Hint(stringResource(R.string.settings_about_hint))
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_support))
            Hint(stringResource(R.string.settings_support_hint))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Onboarding.DONATE_URL)))
                    }
                },
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.settings_support_button))
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_version))
            Text(
                stringResource(R.string.settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
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
            contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
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

/**
 * The in-app Piper voice browser: the curated catalog grouped by accent, each row showing the voice's
 * accent/gender/quality/size + a Download / Use / Delete control and inline download progress.
 * Downloaded voices float to the top of their group; the active one is marked "In use"; ★ marks the
 * best few for navigation. A plain Column (the catalog is small and this lives inside the Settings
 * verticalScroll — a LazyColumn would fight it for height).
 */
@Composable
private fun VoiceLibrary(vm: MapViewModel, state: MapUiState) {
    val catalog = remember { vm.voiceCatalog() }
    val installed = state.installedVoiceIds
    val selected = state.selectedVoiceId
    val installedMb = catalog.filter { it.id in installed }.sumOf { it.sizeMb }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // The app's language — its voices are floated to the top of the browser (Google-style), and if none
    // is installed yet we nudge the user to grab the matching voice (so nav text + voice speak the same
    // language, not French words read by an English voice).
    val appLang = app.vela.ui.AppLocale.effective().language
    val hasAppLangVoice = catalog.any { it.langCode == appLang && it.id in installed }

    Spacer(Modifier.height(6.dp))
    Hint(
        if (installed.isEmpty())
            stringResource(R.string.settings_voice_lib_empty_hint)
        else
            "Installed: $installedMb MB · ${installed.size} voice${if (installed.size == 1) "" else "s"}. Tap Use to switch (plays a sample); the trash icon frees the space.",
    )
    if (appLang != "en" && !hasAppLangVoice) {
        val langLabel = PiperCatalog.languageLabel(appLang)
        Hint(stringResource(R.string.settings_voice_lib_lang_hint, langLabel, langLabel, langLabel))
    }
    if (catalog.size > 12) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.settings_voice_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    fun matches(v: PiperVoice) = query.isBlank() ||
        listOf(v.displayName, v.region, PiperCatalog.languageLabel(v.langCode), v.note ?: "")
            .any { it.contains(query.trim(), ignoreCase = true) }

    // Grouped by language — the app's language first (Google-style), then English, then by endonym.
    val langOrder = PiperCatalog.languageCodes().let { codes ->
        if (appLang in codes) listOf(appLang) + codes.filter { it != appLang } else codes
    }
    langOrder.forEach { lang ->
        val group = catalog.filter { it.langCode == lang && matches(it) }.sortedWith(
            compareByDescending<PiperVoice> { it.id in installed }
                .thenByDescending { it.id == selected }
                .thenByDescending { it.recommended }
                .thenBy { it.novelty }
                .thenByDescending { it.quality.ordinal }
                .thenBy { it.displayName },
        )
        if (group.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(PiperCatalog.languageLabel(lang), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            group.forEach { v ->
                VoiceRow(
                    v = v,
                    installed = v.id in installed,
                    active = v.id == selected,
                    downloading = state.voiceDownloadingId == v.id,
                    downloadPct = if (state.voiceDownloadingId == v.id) state.kokoroDownloadPct ?: 0f else 0f,
                    anyDownloading = state.voiceDownloadingId != null,
                    onDownload = { vm.downloadVoice(v.id) },
                    onUse = { vm.selectVoice(v.id) },
                    onDelete = {
                        // Confirm only when it's the last voice (guidance would lose its neural voice).
                        if (installed.size == 1 && v.id == selected) confirmDeleteId = v.id else vm.deleteVoice(v.id)
                    },
                )
            }
        }
    }

    confirmDeleteId?.let { id ->
        val nm = catalog.firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_this_voice)
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.settings_voice_remove_title, nm)) },
            text = { Text(stringResource(R.string.settings_voice_remove_body)) },
            confirmButton = { TextButton(onClick = { vm.deleteVoice(id); confirmDeleteId = null }) { Text(stringResource(R.string.settings_voice_remove)) } },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun VoiceRow(
    v: PiperVoice,
    installed: Boolean,
    active: Boolean,
    downloading: Boolean,
    downloadPct: Float,
    anyDownloading: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val gender = when (v.gender) {
        app.vela.core.voice.VoiceGender.FEMALE -> stringResource(R.string.settings_voice_gender_female)
        app.vela.core.voice.VoiceGender.MALE -> stringResource(R.string.settings_voice_gender_male)
        app.vela.core.voice.VoiceGender.MULTI -> stringResource(R.string.settings_voice_gender_multi, v.numSpeakers)
        app.vela.core.voice.VoiceGender.NEUTRAL -> stringResource(R.string.settings_voice_gender_neutral)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (v.recommended) "★ " else "") + v.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
            val sub = when {
                downloading -> stringResource(R.string.settings_voice_row_downloading, (downloadPct * 100).toInt())
                active -> stringResource(R.string.settings_voice_row_in_use, v.region, gender, v.sizeMb)
                installed -> stringResource(R.string.settings_voice_row_downloaded, v.region, gender, v.sizeMb)
                else -> "${v.region} · $gender · ${v.quality.name.lowercase()} · ${v.sizeMb} MB" + (v.note?.let { " · $it" } ?: "")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        when {
            downloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            active -> IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            installed -> {
                OutlinedButton(onClick = onUse) { Text(stringResource(R.string.settings_voice_row_use)) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> OutlinedButton(onClick = onDownload, enabled = !anyDownloading) { Text(stringResource(R.string.settings_download)) }
        }
    }
    if (downloading) LinearProgressIndicator(progress = { downloadPct }, modifier = Modifier.fillMaxWidth())
}
