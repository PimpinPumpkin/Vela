package app.vela.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.map.MapUiState
import app.vela.ui.dpadAutoFocus // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadClickable
import app.vela.ui.dpadHighlight
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

/**
 * The Settings hub: one short list of category rows, each opening its own sub-screen. Replaces the
 * old single extremely-long page - on a 240x320 keypad phone reaching Diagnostics is now ~10 DOWN
 * presses instead of ~70. Row order is the fork's deliberate section order (AGENTS.md).
 *
 * D-pad: each row is ONE focus stop (dpadClickable + a ring hugging the row). On first open the
 * scaffold auto-focuses Back (the documented Settings behaviour); returning from a sub-screen
 * restores focus to the row you came from ([returnTo]) - Compose's own recovery is
 * nondeterministic when the focused tree unmounts, so the row is re-focused explicitly.
 */
@Composable
internal fun SettingsHub(
    state: MapUiState,
    returnTo: SettingsSection?,
    onOpen: (SettingsSection) -> Unit,
    onBack: () -> Unit,
) {
    val returnFocus = remember { FocusRequester() }
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        autoFocusBack = returnTo == null,
    ) { topRow ->
        Spacer(Modifier.height(4.dp))
        @Composable
        fun rowModifier(section: SettingsSection, first: Boolean): Modifier {
            var m: Modifier = Modifier
            if (first) m = m.then(topRow)
            if (section == returnTo) m = m.dpadAutoFocus(returnFocus)
            return m
        }
        HubRow(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.settings_appearance),
            subtitle = stringResource(R.string.settings_hub_appearance_sub),
            modifier = rowModifier(SettingsSection.APPEARANCE, first = true),
            onClick = { onOpen(SettingsSection.APPEARANCE) },
        )
        HubRow(
            icon = Icons.Default.Map,
            title = stringResource(R.string.settings_map),
            subtitle = stringResource(R.string.settings_hub_map_sub),
            modifier = rowModifier(SettingsSection.MAP, first = false),
            onClick = { onOpen(SettingsSection.MAP) },
        )
        HubRow(
            icon = Icons.Default.Storefront,
            title = stringResource(R.string.settings_place_pages),
            subtitle = stringResource(R.string.settings_hub_place_pages_sub),
            modifier = rowModifier(SettingsSection.PLACE_PAGES, first = false),
            onClick = { onOpen(SettingsSection.PLACE_PAGES) },
        )
        HubRow(
            icon = Icons.Default.Navigation,
            title = stringResource(R.string.settings_navigation),
            subtitle = stringResource(R.string.settings_hub_navigation_sub),
            modifier = rowModifier(SettingsSection.NAVIGATION, first = false),
            onClick = { onOpen(SettingsSection.NAVIGATION) },
        )
        HubRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.settings_voice),
            // A voice download keeps its progress visible from the hub (it used to sit at the top
            // of the Voice section precisely so a collapsed library couldn't hide it).
            subtitle = if (state.voiceDownloadingId != null) {
                if (state.voiceInstalling) stringResource(R.string.settings_voice_search_installing)
                else stringResource(R.string.settings_voice_search_downloading, ((state.voiceDownloadPct ?: 0f) * 100).toInt())
            } else stringResource(R.string.settings_hub_voice_sub),
            modifier = rowModifier(SettingsSection.VOICE, first = false),
            onClick = { onOpen(SettingsSection.VOICE) },
        )
        HubRow(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.settings_search),
            subtitle = stringResource(R.string.settings_hub_search_sub),
            modifier = rowModifier(SettingsSection.SEARCH, first = false),
            onClick = { onOpen(SettingsSection.SEARCH) },
        )
        HubRow(
            icon = Icons.Default.CloudDownload,
            title = stringResource(R.string.settings_offline),
            subtitle = stringResource(R.string.settings_hub_offline_sub),
            modifier = rowModifier(SettingsSection.OFFLINE, first = false),
            onClick = { onOpen(SettingsSection.OFFLINE) },
        )
        HubRow(
            icon = Icons.Default.Star,
            title = stringResource(R.string.settings_saved_places),
            subtitle = stringResource(R.string.settings_hub_saved_sub),
            modifier = rowModifier(SettingsSection.SAVED_PLACES, first = false),
            onClick = { onOpen(SettingsSection.SAVED_PLACES) },
        )
        HubRow(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.settings_data_privacy),
            subtitle = stringResource(R.string.settings_hub_privacy_sub),
            modifier = rowModifier(SettingsSection.DATA_PRIVACY, first = false),
            onClick = { onOpen(SettingsSection.DATA_PRIVACY) },
        )
        HubRow(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.settings_diagnostics),
            subtitle = stringResource(R.string.settings_hub_diagnostics_sub),
            modifier = rowModifier(SettingsSection.DIAGNOSTICS, first = false),
            onClick = { onOpen(SettingsSection.DIAGNOSTICS) },
        )
        HubRow(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_hub_about_sub),
            modifier = rowModifier(SettingsSection.ABOUT, first = false),
            onClick = { onOpen(SettingsSection.ABOUT) },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // A thin outline per category row - visible structure in light AND on AMOLED black,
            // where borderless rows melt into the background. The orange focus ring draws over
            // the same rounded shape when the row is focused.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), DpadShape(14.dp))
            .dpadHighlight(DpadShape(14.dp))
            .dpadClickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monochrome leading icon (single-ink rule - never the teal primary).
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

