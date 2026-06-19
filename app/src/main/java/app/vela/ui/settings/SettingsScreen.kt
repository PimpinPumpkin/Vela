package app.vela.ui.settings
import android.content.Intent
import android.net.Uri

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import app.vela.offline.OfflineMaps
import org.maplibre.android.offline.OfflineRegion
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
            val engines = vm.voiceEngines()
            if (engines.isEmpty()) {
                Hint("No text-to-speech engine on this phone, so spoken directions are silent. Install an open-source one in one tap below, then pick it here.")
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
                Hint("Tap Test voice to hear it. Silent? The engine has no voice downloaded — open System voice settings, or add a more natural one below.")
            }
            // One-tap install of an open-source engine — the fix for a ROM that ships none.
            val installable = vm.installableEngines()
            if (installable.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Hint(if (engines.isEmpty()) "Open-source voices (from F-Droid):" else "Add a more natural voice (from F-Droid):")
                installable.forEach { eng ->
                    OutlinedButton(
                        onClick = { vm.installVoiceEngine(eng) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Install ${eng.label}") }
                    Hint(eng.note)
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Navigation")
            val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }
            var haptics by remember { mutableStateOf(prefs.getBoolean("haptics_on", true)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vibrate on turns", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = haptics,
                    onCheckedChange = {
                        haptics = it
                        prefs.edit().putBoolean("haptics_on", it).apply()
                    },
                )
            }
            Hint("Direction-coded buzzes at each turn — distinct for left vs right — so you can follow a route by feel while biking or walking, without looking at the screen.")

            Spacer(Modifier.height(20.dp))
            SectionTitle("Offline maps")
            var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
            LaunchedEffect(Unit) { OfflineMaps.list(context) { regions = it } }
            OutlinedButton(
                onClick = {
                    vm.downloadViewport()
                    onBack() // back to the map so the user sees the download progress
                },
                enabled = vm.hasViewport(),
            ) { Text("Download the area you're viewing") }
            Hint("Saves the open tiles for the map area you last had on screen, so it renders later with no network. (Routing and search still need a connection.)")
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

            Spacer(Modifier.height(20.dp))
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

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
