package app.vela.ui.settings.sections

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.SubHead
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadClickable // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import app.vela.ui.rememberDpadFocusKeeper // focus handoff for swap-in controls (docs/dpad.md)
import app.vela.ui.DpadFocusHandoff
import app.vela.ui.dpadFocusKept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

/** Search sub-screen: the voice-search toggle, the on-device ASR engine picker, the provider picker. */
@Composable
internal fun SearchSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_search), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_voice_search_toggle),
            checked = app.vela.ui.VoiceSearch.enabled.value,
            onCheckedChange = { app.vela.ui.VoiceSearch.set(context, it) },
            hint = stringResource(R.string.settings_voice_search_hint),
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        }

        // On-device voice search (tier-1): a PER-ENGINE picker (upstream 5d2a6636 + 137beea9).
        // Whisper (multilingual, smallest, the default) plus opt-in SenseVoice / Moonshine.
        // Download one or more, pick which the mic uses ("Use"), remove to free space. D-pad
        // safety: every control is a FULL-WIDTH single focus stop - an installed engine renders a
        // "Use/Active" row AND a SEPARATE "Remove" row (never two nested targets in one row), a
        // not-installed engine a "Download" row. One download at a time (the collision guard).
        LaunchedEffect(Unit) { vm.refreshAsr() }
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_asr_engines_hint))
        val anyAsrBusy = state.asrDownloadingId != null
        SettingsGroup {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
        app.vela.voice.AsrEngine.entries.forEachIndexed { engineIdx, engine ->
            if (engineIdx > 0) GroupDivider()
            val installed = engine.id in state.asrInstalledIds
            val active = engine.id == state.asrActiveId && installed
            val thisDownloading = state.asrDownloadingId == engine.id
            val langs = stringResource(
                when (engine) {
                    app.vela.voice.AsrEngine.WHISPER_TINY -> R.string.settings_asr_langs_whisper
                    app.vela.voice.AsrEngine.SENSE_VOICE -> R.string.settings_asr_langs_sensevoice
                    app.vela.voice.AsrEngine.MOONSHINE -> R.string.settings_asr_langs_moonshine
                },
            )
            val meta = stringResource(R.string.settings_asr_engine_meta, langs, engine.sizeMb)
            // D-pad: a keeper PER ENGINE ROW (same idiom as the routing-region rows) so when this
            // engine's control swaps Download -> spinner -> Use/Remove, the highlight stays on it
            // instead of teleporting to the top of the page. DpadFocusHandoff inside each branch,
            // dpadFocusKept on every variant, one retarget per row (docs/dpad.md; AGENTS.md).
            val keeper = rememberDpadFocusKeeper()
            when {
                thisDownloading -> {
                    val pct = state.asrDownloadPct ?: 0f
                    val queued = state.asrDownloadPct == null
                    DpadFocusHandoff(keeper)
                    Column(Modifier.fillMaxWidth().dpadFocusKept(keeper).dpadHighlight(DpadShape(6.dp)).focusable().padding(vertical = 8.dp)) {
                        Text(engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(
                            when {
                                queued -> stringResource(R.string.map_asr_waiting)
                                state.asrInstalling -> stringResource(R.string.settings_voice_search_installing)
                                else -> stringResource(R.string.settings_voice_search_downloading, (pct * 100).toInt())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Indeterminate while queued/installing (nothing to count): a frozen bar reads as stuck.
                        if (queued || state.asrInstalling) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                    }
                }
                installed -> {
                    // ONE focus stop = the whole row (dpadClickable); the RadioButton is read-only
                    // (onClick = null) so it isn't a second stop, matching the SelectableRow idiom.
                    // OK selects this engine; the ring is the only focus signal (dpadClickable drops
                    // Material's grey layer under key input).
                    DpadFocusHandoff(keeper)
                    Row(
                        Modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable(enabled = !active) { vm.selectAsrEngine(engine) }.dpadFocusKept(keeper).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = active, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(if (active) R.string.settings_asr_active else R.string.settings_asr_use),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Remove is a SEPARATE full-width row (its own single focus stop), never a nested
                    // target inside the row above - the anti-pattern the audits reject.
                    Row(
                        Modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable { vm.deleteAsrEngine(engine) }.padding(start = 40.dp, top = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_voice_search_remove), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> {
                    DpadFocusHandoff(keeper)
                    Row(
                        Modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable(enabled = !anyAsrBusy) { vm.downloadAsrEngine(engine) }.dpadFocusKept(keeper).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.settings_voice_search_download, engine.sizeMb), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            LaunchedEffect(thisDownloading, installed, active) { keeper.retarget() }
            Spacer(Modifier.height(4.dp))
        }
        }
        }
        // The engine picker only matters when there's actually a choice (the model AND a voice app,
        // or two voice apps): "Vela Voice" = AUTO (the model wins, provider as graceful fallback),
        // "Android default" = the implicit intent Android routes, or each installed app pinned by name
        // so Android never interjects its own chooser. Enumerated off the main thread (a binder query
        // + per-app label load, the same class of call as the TTS engine list).
        val voiceProviders by produceState(initialValue = emptyList<app.vela.ui.VoiceSearch.Provider>()) {
            value = withContext(Dispatchers.IO) { app.vela.ui.VoiceSearch.providers(context) }
        }
        if ((state.asrInstalledIds.isNotEmpty() && voiceProviders.isNotEmpty()) || voiceProviders.size > 1) {
            Spacer(Modifier.height(8.dp))
            SubHead(stringResource(R.string.settings_voice_search_engine_title))
            SettingsGroup {
            val eng = app.vela.ui.VoiceSearch.engine.value
            val savedPick = app.vela.ui.VoiceSearch.provider.value
            val savedValid = voiceProviders.any { it.component.flattenToString() == savedPick }
            if (state.asrInstalledIds.isNotEmpty()) {
                SelectableRow(stringResource(R.string.settings_voice_search_engine_auto), eng != app.vela.ui.VoiceSearch.Engine.SYSTEM, onClick = { app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.AUTO) })
            }
            if (voiceProviders.isNotEmpty()) {
                if (state.asrInstalledIds.isNotEmpty()) GroupDivider()
                SelectableRow(
                    stringResource(R.string.settings_voice_search_engine_default),
                    eng == app.vela.ui.VoiceSearch.Engine.SYSTEM && !savedValid,
                    onClick = {
                        app.vela.ui.VoiceSearch.clearProvider(context)
                        app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.SYSTEM)
                    },
                )
            }
            voiceProviders.forEach { p ->
                GroupDivider()
                SelectableRow(
                    p.label,
                    eng == app.vela.ui.VoiceSearch.Engine.SYSTEM && savedValid && p.component.flattenToString() == savedPick,
                    onClick = {
                        app.vela.ui.VoiceSearch.setProvider(context, p.component)
                        app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.SYSTEM)
                    },
                )
            }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
