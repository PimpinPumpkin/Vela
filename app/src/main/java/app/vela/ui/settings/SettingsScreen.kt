package app.vela.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.map.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
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
            SectionTitle("Map style")
            MapStyle.values().forEach { style ->
                SelectableRow(
                    label = style.label,
                    selected = state.styleName == style.label,
                    onClick = { vm.setStyle(style) },
                )
            }
            Hint("Protomaps styles need an API key or a self-hosted PMTiles archive — see the README. The default demo style is keyless.")

            Spacer(Modifier.height(20.dp))
            SectionTitle("Voice")
            val engines = vm.voiceEngines()
            if (engines.isEmpty()) {
                Hint("No TTS engine detected yet. On a degoogled ROM, install RHVoice or eSpeak NG from F-Droid for natural voices — Vela will list them here.")
            }
            engines.forEach { e ->
                SelectableRow(
                    label = e.label,
                    selected = state.selectedEngine?.packageName == e.packageName,
                    onClick = { vm.setVoiceEngine(e) },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Data source")
            Hint(
                "Vela currently runs on built-in mock data so the whole app works offline. " +
                    "The Google extractor switches on once its request/response shapes are " +
                    "calibrated (VelaConfig.USE_GOOGLE_SOURCE). No Google account or API key " +
                    "is used — each device behaves like a single browser.",
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("About")
            Hint(
                "Vela is a degoogled maps client: open tiles for the basemap, scraped Google " +
                    "for POIs, routing and traffic-aware ETAs, AOSP TextToSpeech for voice. GPLv3. " +
                    "No Play Services required.",
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
