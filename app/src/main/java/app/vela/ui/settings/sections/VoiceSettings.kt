package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.core.voice.VoiceEngine
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.CollapsibleSectionTitle
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadFieldEscape // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import app.vela.ui.dpadRowSibling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Voice sub-screen: TTS engine list, the Piper voice library, and the advanced voice options. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSettingsScreen(vm: MapViewModel, onBack: () -> Unit, openLibrary: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_voice), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        // The master switch: persisted, and the in-nav speaker button toggles the SAME state.
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_spoken_directions),
            checked = !state.voiceMuted,
            onCheckedChange = { vm.setSpokenDirections(it) },
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        }
        Spacer(Modifier.height(4.dp))
        // Vela's own on-device neural voices - offer a one-tap download for whichever isn't
        // present yet; once downloaded each shows in the engine list below (selectable). No
        // standalone TTS app needed.
        // A download in flight shows a compact progress line here too, so it's visible even when the
        // Voice library (below) is collapsed. The per-voice controls live in the library.
        state.voiceDownloadingId?.let { id ->
            val nm = vm.voiceCatalog().firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_fallback_name)
            val pct = state.voiceDownloadPct ?: 0f
            if (state.voiceInstalling) {
                // Download hit 100%; now unpacking the ~67 MB archive (~15 s). A distinct
                // "Installing…" with an indeterminate bar so it doesn't read as a stuck 100%
                // download (upstream 75c9104d). The map card already does this; these Settings
                // sites did not, so they read as a hang.
                Text(stringResource(R.string.settings_voice_search_installing), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.settings_voice_downloading, nm, (pct * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        } ?: Spacer(Modifier.height(4.dp))
        // One-tap install of the Vela voice right here when it's missing (user 2026-07-20:
        // don't make me dig into the Voice library for the main voice). Same download the
        // library offers; hidden while any voice download is in flight (the line above shows it).
        run {
            val velaId = vm.defaultVoiceId()
            val vela = vm.voiceCatalog().firstOrNull { it.id == velaId }
            if (vela != null && velaId !in state.installedVoiceIds && state.voiceDownloadingId == null) {
                androidx.compose.material3.Button(
                    onClick = { vm.downloadVoice(velaId) },
                    modifier = Modifier.fillMaxWidth().dpadHighlight(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                ) { Text(stringResource(R.string.settings_voice_install_vela, vela.sizeMb)) }
                Hint(stringResource(R.string.settings_voice_install_vela_hint))
                Spacer(Modifier.height(10.dp))
            }
        }
        // Enumerate TTS engines OFF the main thread. PackageManager.queryIntentServices + the
        // per-engine loadLabel is a binder IPC that took >5 s on the flip phone and ANR'd the UI
        // when run in composition (input-dispatch timeout). Load async (cached in VoiceGuide);
        // render nothing until ready so there's no flash of the "no engines" hint.
        val engines by produceState<List<VoiceEngine>?>(null, state.voiceDownloadingId) {
            value = withContext(Dispatchers.IO) { vm.voiceEngines() }
        }
        val engineList = engines
        if (engineList == null) {
            // still loading - render nothing
        } else if (engineList.isEmpty()) {
            Hint(stringResource(R.string.settings_voice_none_hint))
        } else {
            SettingsGroup {
            engineList.forEachIndexed { i, e ->
                if (i > 0) GroupDivider()
                SelectableRow(
                    label = e.label,
                    selected = state.selectedEngine?.packageName == e.packageName,
                    onClick = { vm.setVoiceEngine(e) },
                )
            }
            GroupDivider()
            Spacer(Modifier.height(6.dp))
            // FlowRow, not Row: a long translated label ("System voice settings") squeezed into a
            // Row wraps INSIDE the button into a tall two-line pill (user screenshot). In a FlowRow
            // the whole pill moves to the next line instead - the VelaDialog button pattern.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // D-pad L/R across the pair (issue #24 - Test voice unreachable). See dpadRowSibling.
                val voiceTestFocus = remember { List(2) { FocusRequester() } }
                FilledTonalButton(modifier = Modifier.dpadRowSibling(voiceTestFocus, 0), onClick = { vm.testVoice() }) { Text(stringResource(R.string.settings_voice_test), maxLines = 1) }
                FilledTonalButton(
                    modifier = Modifier.dpadRowSibling(voiceTestFocus, 1),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.settings_voice_system_settings), maxLines = 1) }
            }
            androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) { Hint(stringResource(R.string.settings_voice_test_hint)) }
            }
        }

        // Voice library - browse, download, switch between and remove Vela's neural voices (Piper).
        // Auto-expanded when nothing is installed so the download path is obvious.
        var voiceLibExpanded by remember { mutableStateOf(openLibrary || state.installedVoiceIds.isEmpty()) }
        CollapsibleSectionTitle(
            stringResource(R.string.settings_voice_library),
            voiceLibExpanded,
        ) { voiceLibExpanded = !voiceLibExpanded }
        if (voiceLibExpanded) VoiceLibrary(vm, state)

        if (engineList?.isNotEmpty() == true) {
            // Speed + the niche bits (playground, the multi-speaker variant picker) - most people never
            // touch these, so tuck them behind a collapsible header (collapsed by default).
            var voiceAdvExpanded by remember { mutableStateOf(false) }
            CollapsibleSectionTitle(stringResource(R.string.settings_voice_advanced), voiceAdvExpanded) { voiceAdvExpanded = !voiceAdvExpanded }
            if (voiceAdvExpanded) {
                SettingsGroup {
                androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
                // Playground: hear the selected voice on any text (or a nav-style sample).
                Spacer(Modifier.height(8.dp))
                var tryText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    modifier = Modifier.fillMaxWidth().dpadFieldEscape(),
                    label = { Text(stringResource(R.string.settings_voice_try_label)) },
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                val navSampleText = stringResource(R.string.settings_voice_nav_sample_text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // D-pad L/R across the pair (issue #24). See dpadRowSibling.
                    val playFocus = remember { List(2) { FocusRequester() } }
                    FilledTonalButton(modifier = Modifier.dpadRowSibling(playFocus, 0), onClick = { vm.speakText(tryText) }, enabled = tryText.isNotBlank()) { Text(stringResource(R.string.settings_voice_speak)) }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(modifier = Modifier.dpadRowSibling(playFocus, 1), onClick = {
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
                    val speedFocus = remember { List(2) { FocusRequester() } }  // D-pad L/R across -/+ (issue #24)
                    FilledTonalButton(modifier = Modifier.dpadRowSibling(speedFocus, 0), onClick = { vm.setVoiceSpeed(-0.1f) }) { Text("−") }
                    Spacer(Modifier.width(6.dp))
                    FilledTonalButton(modifier = Modifier.dpadRowSibling(speedFocus, 1), onClick = { vm.setVoiceSpeed(0.1f) }) { Text("+") }
                }
                Hint(stringResource(R.string.settings_voice_speed_hint))
                // Multi-speaker Vela voices (libritts_r=904, VCTK=109, Arctic=18) - let the user audition +
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
                        val speakerFocus = remember { List(2) { FocusRequester() } }  // D-pad L/R across left/right steppers (issue #24)
                        FilledTonalButton(modifier = Modifier.dpadRowSibling(speakerFocus, 0), onClick = { vm.stepSpeaker(-1) }) { Text("◀") }
                        Spacer(Modifier.width(6.dp))
                        FilledTonalButton(modifier = Modifier.dpadRowSibling(speakerFocus, 1), onClick = { vm.stepSpeaker(1) }) { Text("▶") }
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
                            modifier = Modifier.width(150.dp).dpadFieldEscape(),
                        )
                        Spacer(Modifier.width(8.dp))
                        // The old page passed the ring audit here only because an unrelated ringed
                        // control sat inside the scan window; the button itself had none.
                        FilledTonalButton(
                            onClick = goToVariant,
                            enabled = jump.isNotBlank(),
                            modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
                        ) { Text(stringResource(R.string.settings_voice_variant_go)) }
                    }
                    Hint(stringResource(R.string.settings_voice_variant_hint))
                }
                }
                }
            } // end "Advanced voice options"
        }
        Spacer(Modifier.height(24.dp))
    }
}
