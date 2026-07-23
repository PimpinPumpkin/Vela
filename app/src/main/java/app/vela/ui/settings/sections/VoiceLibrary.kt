package app.vela.ui.settings.sections

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.voice.PiperCatalog
import app.vela.core.voice.PiperVoice
import app.vela.ui.map.MapUiState
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.Hint
import app.vela.ui.dpadClickable // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadFieldEscape
import app.vela.ui.dpadHighlight
import app.vela.ui.rememberDpadFocusKeeper // focus handoff for swap-in controls (docs/dpad.md)
import app.vela.ui.DpadFocusHandoff
import app.vela.ui.dpadFocusKept
import app.vela.ui.dpadRowSibling
import androidx.compose.ui.focus.FocusRequester

/**
 * The in-app Piper voice browser: the curated catalog grouped by accent, each row showing the voice's
 * accent/gender/quality/size + a Download / Use / Delete control and inline download progress.
 * Downloaded voices float to the top of their group; the active one is marked "In use"; the star marks
 * the best few for navigation. A plain Column (the catalog is small and this lives inside the Settings
 * verticalScroll - a LazyColumn would fight it for height).
 */
@Composable
internal fun VoiceLibrary(vm: MapViewModel, state: MapUiState) {
    val catalog = remember { vm.voiceCatalog() }
    val installed = state.installedVoiceIds
    val selected = state.selectedVoiceId
    val installedMb = catalog.filter { it.id in installed }.sumOf { it.sizeMb }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // The app's language - its voices are floated to the top of the browser (Google-style), and if none
    // is installed yet we nudge the user to grab the matching voice (so nav text + voice speak the same
    // language, not French words read by an English voice).
    val appLang = app.vela.ui.AppLocale.effective().language
    val hasAppLangVoice = catalog.any { it.langCode == appLang && it.id in installed }

    Spacer(Modifier.height(6.dp))
    Hint(
        if (installed.isEmpty())
            stringResource(R.string.settings_voice_lib_empty_hint)
        else
            stringResource(R.string.settings_voice_lib_installed_hint, installedMb, installed.size),
    )
    if (appLang != "en" && !hasAppLangVoice) {
        val langLabel = PiperCatalog.languageLabel(appLang)
        Hint(stringResource(R.string.settings_voice_lib_lang_hint, langLabel, langLabel, langLabel))
    }
    if (catalog.size > 12) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            placeholder = { Text(stringResource(R.string.settings_voice_search)) },
            singleLine = true,
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = app.vela.ui.settings.settingsFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).dpadFieldEscape(),
        )
    }
    fun matches(v: PiperVoice) = query.isBlank() ||
        listOf(v.displayName, v.region, PiperCatalog.languageLabel(v.langCode), v.note ?: "")
            .any { it.contains(query.trim(), ignoreCase = true) }

    // Grouped by language - the app's language first (Google-style), then English, then by endonym.
    // Each language is its own collapsible sub-group so the ~40-voice list isn't one long scroll: a
    // group opens by default only when it's the app's language or already has a voice installed, so you
    // see your own language + what you've got and the rest stays folded away. A search forces all open.
    val langOrder = PiperCatalog.languageCodes().let { codes ->
        if (appLang in codes) listOf(appLang) + codes.filter { it != appLang } else codes
    }
    val langExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val langShowAll = remember { mutableStateMapOf<String, Boolean>() }
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
            val installedHere = group.count { it.id in installed }
            val defaultOpen = lang == appLang || installedHere > 0
            // A live search reveals every match; otherwise honour the user's toggle, falling back to the default.
            val expanded = if (query.isNotBlank()) true else (langExpanded[lang] ?: defaultOpen)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .dpadHighlight(RoundedCornerShape(8.dp))
                    .dpadClickable(enabled = query.isBlank()) { langExpanded[lang] = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PiperCatalog.languageLabel(lang),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (installedHere > 0) "$installedHere/${group.size}" else "${group.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                // Show just the top few per language (each language still has a lot, English most of
                // all); a "Show more" reveals the rest. Never hide an INSTALLED voice behind it, and a
                // search shows everything that matches.
                val showAll = query.isNotBlank() || (langShowAll[lang] ?: false)
                val limit = maxOf(3, installedHere)
                val visible = if (showAll) group else group.take(limit)
                visible.forEach { v ->
                    VoiceRow(
                        v = v,
                        installed = v.id in installed,
                        active = v.id == selected,
                        downloading = state.voiceDownloadingId == v.id,
                        downloadPct = if (state.voiceDownloadingId == v.id) state.voiceDownloadPct ?: 0f else 0f,
                        installing = state.voiceDownloadingId == v.id && state.voiceInstalling,
                        anyDownloading = state.voiceDownloadingId != null,
                        onDownload = { vm.downloadVoice(v.id) },
                        onUse = { vm.selectVoice(v.id) },
                        onDelete = {
                            // Confirm only when it's the last voice (guidance would lose its neural voice).
                            if (installed.size == 1 && v.id == selected) confirmDeleteId = v.id else vm.deleteVoice(v.id)
                        },
                    )
                }
                if (query.isBlank() && group.size > limit) {
                    TextButton(modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.textShape), onClick = { langShowAll[lang] = !showAll }) {
                        Text(
                            if (showAll) stringResource(R.string.settings_voice_show_less)
                            else stringResource(R.string.settings_voice_show_more, group.size - limit),
                        )
                    }
                }
            }
        }
    }

    confirmDeleteId?.let { id ->
        val nm = catalog.firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_this_voice)
        app.vela.ui.VelaDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = stringResource(R.string.settings_voice_remove_title, nm),
            confirmText = stringResource(R.string.settings_voice_remove),
            onConfirm = { vm.deleteVoice(id); confirmDeleteId = null },
            dismissText = stringResource(R.string.settings_cancel),
            onDismiss = { confirmDeleteId = null },
            text = { Text(stringResource(R.string.settings_voice_remove_body)) },
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
    installing: Boolean = false, // download done, unpacking the archive (~15 s) - not a stuck 100%
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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
            val sub = when {
                installing -> stringResource(R.string.settings_voice_search_installing) // unpacking, not a stuck 100% (upstream 75c9104d)
                downloading -> stringResource(R.string.settings_voice_row_downloading, (downloadPct * 100).toInt())
                active -> stringResource(R.string.settings_voice_row_in_use, v.region, gender, v.sizeMb)
                installed -> stringResource(R.string.settings_voice_row_downloaded, v.region, gender, v.sizeMb)
                else -> "${v.region} · $gender · ${v.quality.name.lowercase()} · ${v.sizeMb} MB" + (v.note?.let { " · $it" } ?: "")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        // D-pad: this trailing control REPLACES itself when used (Download -> spinner ->
        // Use/Delete) and Compose recovery then dumped focus at the TOP of the page (user
        // report). The keeper re-places focus on the new variant; the spinner is a focus
        // stop with a ring so the row keeps the highlight while it works.
        val keeper = rememberDpadFocusKeeper()
        when {
            downloading -> {
                DpadFocusHandoff(keeper)
                CircularProgressIndicator(
                    Modifier
                        .size(22.dp)
                        .dpadFocusKept(keeper)
                        .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                        .focusable(),
                    strokeWidth = 2.dp,
                )
            }
            active -> {
                DpadFocusHandoff(keeper)
                IconButton(onClick = onDelete, modifier = Modifier.dpadFocusKept(keeper)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            installed -> {
                DpadFocusHandoff(keeper)
                // Use + Delete sit side by side, so LEFT/RIGHT must hop between them directly (issue
                // #65: a bare horizontal move otherwise cleared focus - Delete only reachable from
                // above, Use only from below). Wire the pair with dpadRowSibling like the other
                // Settings button rows. The keeper's requester (dpadFocusKept) stays on Use so a
                // Download->Use handoff still lands the row's focus here.
                val useDeleteFocus = remember { List(2) { FocusRequester() } }
                FilledTonalButton(
                    onClick = onUse,
                    modifier = Modifier.dpadFocusKept(keeper).dpadRowSibling(useDeleteFocus, 0),
                ) { Text(stringResource(R.string.settings_voice_row_use)) }
                IconButton(onClick = onDelete, modifier = Modifier.dpadRowSibling(useDeleteFocus, 1)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                DpadFocusHandoff(keeper)
                FilledTonalButton(onClick = onDownload, enabled = !anyDownloading, modifier = Modifier.dpadFocusKept(keeper)) { Text(stringResource(R.string.settings_download)) }
            }
        }
        LaunchedEffect(downloading, active, installed) { keeper.retarget() }
    }
    // The label above already reads "Installing…" during unpack; the bar must go indeterminate too,
    // or it sits frozen at 100% and reads as a hang (upstream 75c9104d, hunk f).
    if (downloading) {
        if (installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { downloadPct }, modifier = Modifier.fillMaxWidth())
    }
}
