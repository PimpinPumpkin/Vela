package app.vela.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.vela.ui.dpadFieldEscape
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
        // Settings search, rebuilt for hub-and-spoke: the old single page scrolled to the matched
        // row's measured Y, which died with the long page - a match now OPENS ITS SPOKE instead.
        // The index is static (label resource -> section); matching is a simple contains() on the
        // localized label, same as before.
        var searchQuery by remember { mutableStateOf("") }
        // Filled search pill (no outline - the outlined field read dated, user 2026-07-23). Token
        // colors so Material You / light / dark / AMOLED all theme it; the indicator lines are
        // transparent so it reads as one soft container, like the app's own map search bar.
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            placeholder = { Text(stringResource(R.string.settings_search_hint), fontFamily = SettingsFontFamily) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            // The top focusable control: Back routes its DOWN here; UP/DOWN escape the field
            // (dpadFieldEscape) so the rows below stay reachable by key.
            modifier = topRow.fillMaxWidth().padding(vertical = 4.dp).dpadFieldEscape(),
        )
        val sectionTitles = mapOf(
            SettingsSection.APPEARANCE to stringResource(R.string.settings_appearance),
            SettingsSection.MAP to stringResource(R.string.settings_map),
            SettingsSection.PLACE_PAGES to stringResource(R.string.settings_place_pages),
            SettingsSection.NAVIGATION to stringResource(R.string.settings_navigation),
            SettingsSection.VOICE to stringResource(R.string.settings_voice),
            SettingsSection.SEARCH to stringResource(R.string.settings_search),
            SettingsSection.OFFLINE to stringResource(R.string.settings_offline),
            SettingsSection.SAVED_PLACES to stringResource(R.string.settings_saved_places),
            SettingsSection.DATA_PRIVACY to stringResource(R.string.settings_data_privacy),
            SettingsSection.DIAGNOSTICS to stringResource(R.string.settings_diagnostics),
            SettingsSection.ABOUT to stringResource(R.string.settings_about),
        )
        if (searchQuery.isNotBlank()) {
            val matches = SEARCH_INDEX.map { (res, section) -> stringResource(res) to section }
                .filter { it.first.contains(searchQuery.trim(), ignoreCase = true) }
                .take(10)
            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.settings_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            matches.forEach { (label, section) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(DpadShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .dpadHighlight(DpadShape(16.dp))
                        .dpadClickable { searchQuery = ""; onOpen(section) }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontFamily = SettingsFontFamily)
                        Text(
                            sectionTitles[section] ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = SettingsFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            return@SettingsScaffold
        }
        @Composable
        fun rowModifier(section: SettingsSection, first: Boolean): Modifier {
            var m: Modifier = Modifier
            if (section == returnTo) m = m.dpadAutoFocus(returnFocus)
            return m
        }
        HubRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.settings_appearance),
            subtitle = stringResource(R.string.settings_hub_appearance_sub),
            modifier = rowModifier(SettingsSection.APPEARANCE, first = true),
            onClick = { onOpen(SettingsSection.APPEARANCE) },
        )
        HubRow(
            icon = Icons.Outlined.Map,
            title = stringResource(R.string.settings_map),
            subtitle = stringResource(R.string.settings_hub_map_sub),
            modifier = rowModifier(SettingsSection.MAP, first = false),
            onClick = { onOpen(SettingsSection.MAP) },
        )
        HubRow(
            icon = Icons.Outlined.Storefront,
            title = stringResource(R.string.settings_place_pages),
            subtitle = stringResource(R.string.settings_hub_place_pages_sub),
            modifier = rowModifier(SettingsSection.PLACE_PAGES, first = false),
            onClick = { onOpen(SettingsSection.PLACE_PAGES) },
        )
        HubRow(
            icon = Icons.Outlined.Navigation,
            title = stringResource(R.string.settings_navigation),
            subtitle = stringResource(R.string.settings_hub_navigation_sub),
            modifier = rowModifier(SettingsSection.NAVIGATION, first = false),
            onClick = { onOpen(SettingsSection.NAVIGATION) },
        )
        HubRow(
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
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
            icon = Icons.Outlined.Mic,
            title = stringResource(R.string.settings_search),
            subtitle = stringResource(R.string.settings_hub_search_sub),
            modifier = rowModifier(SettingsSection.SEARCH, first = false),
            onClick = { onOpen(SettingsSection.SEARCH) },
        )
        HubRow(
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.settings_offline),
            subtitle = stringResource(R.string.settings_hub_offline_sub),
            modifier = rowModifier(SettingsSection.OFFLINE, first = false),
            onClick = { onOpen(SettingsSection.OFFLINE) },
        )
        HubRow(
            icon = Icons.Outlined.Star,
            title = stringResource(R.string.settings_saved_places),
            subtitle = stringResource(R.string.settings_hub_saved_sub),
            modifier = rowModifier(SettingsSection.SAVED_PLACES, first = false),
            onClick = { onOpen(SettingsSection.SAVED_PLACES) },
        )
        HubRow(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.settings_data_privacy),
            subtitle = stringResource(R.string.settings_hub_privacy_sub),
            modifier = rowModifier(SettingsSection.DATA_PRIVACY, first = false),
            onClick = { onOpen(SettingsSection.DATA_PRIVACY) },
        )
        HubRow(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.settings_diagnostics),
            subtitle = stringResource(R.string.settings_hub_diagnostics_sub),
            modifier = rowModifier(SettingsSection.DIAGNOSTICS, first = false),
            onClick = { onOpen(SettingsSection.DIAGNOSTICS) },
        )
        HubRow(
            icon = Icons.Outlined.Info,
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
            // Filled M3 card per category row, no outline (the bordered boxes read dated - user
            // 2026-07-23). surfaceContainer is a theme token, so Material You tints it and the
            // AMOLED scheme's stepped near-blacks keep the rows visible on true black. The focus
            // ring draws over the same rounded shape when the row is focused.
            .clip(DpadShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .dpadHighlight(DpadShape(26.dp))
            .dpadClickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Outlined stock-style glyph in the quiet ink (single-ink rule - never the teal primary).
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = SettingsFontFamily)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SettingsFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The settings-search index: every user-facing row label, mapped to the spoke that hosts it.
 * A match opens the spoke (the hub-and-spoke replacement for the old measured scroll-to-Y).
 * When a section gains a row, add its label here - the search only knows what's listed.
 */
private val SEARCH_INDEX: List<Pair<Int, SettingsSection>> = listOf(
    // Appearance
    R.string.settings_follow_system to SettingsSection.APPEARANCE,
    R.string.settings_theme_light to SettingsSection.APPEARANCE,
    R.string.settings_theme_dark to SettingsSection.APPEARANCE,
    R.string.settings_theme_amoled to SettingsSection.APPEARANCE,
    R.string.settings_ui_scale to SettingsSection.APPEARANCE,
    R.string.settings_map_colors to SettingsSection.APPEARANCE,
    R.string.settings_dynamic_color to SettingsSection.APPEARANCE,
    R.string.settings_units to SettingsSection.APPEARANCE,
    R.string.settings_units_imperial to SettingsSection.APPEARANCE,
    R.string.settings_units_metric to SettingsSection.APPEARANCE,
    R.string.settings_language to SettingsSection.APPEARANCE,
    R.string.settings_follow_system_language to SettingsSection.APPEARANCE,
    // Map
    R.string.settings_live_traffic to SettingsSection.MAP,
    R.string.settings_transit_layer to SettingsSection.MAP,
    R.string.settings_topography to SettingsSection.MAP,
    R.string.settings_layers_button to SettingsSection.MAP,
    R.string.settings_flock to SettingsSection.MAP,
    R.string.settings_flock_route_alert to SettingsSection.MAP,
    R.string.settings_buildings_3d to SettingsSection.MAP,
    R.string.settings_building_overlay to SettingsSection.MAP,
    R.string.settings_map_places to SettingsSection.MAP,
    R.string.settings_show_pois to SettingsSection.MAP,
    R.string.settings_show_civic to SettingsSection.MAP,
    R.string.settings_show_transit_stops to SettingsSection.MAP,
    R.string.settings_poi_icon_size to SettingsSection.MAP,
    // Place pages
    R.string.settings_show_reviews to SettingsSection.PLACE_PAGES,
    R.string.settings_read_all_reviews to SettingsSection.PLACE_PAGES,
    R.string.settings_load_photos to SettingsSection.PLACE_PAGES,
    R.string.settings_hide_adult to SettingsSection.PLACE_PAGES,
    R.string.settings_hide_external_links to SettingsSection.PLACE_PAGES,
    // Navigation
    R.string.settings_keep_screen_on to SettingsSection.NAVIGATION,
    R.string.settings_traffic_lights to SettingsSection.NAVIGATION,
    R.string.settings_vibrate_on_turns to SettingsSection.NAVIGATION,
    R.string.settings_demo_drive to SettingsSection.NAVIGATION,
    R.string.settings_sim_location to SettingsSection.NAVIGATION,
    R.string.settings_parking_history to SettingsSection.NAVIGATION,
    // Voice
    R.string.settings_spoken_directions to SettingsSection.VOICE,
    R.string.settings_voice_library to SettingsSection.VOICE,
    R.string.settings_voice_advanced to SettingsSection.VOICE,
    R.string.settings_voice_test to SettingsSection.VOICE,
    // Search
    R.string.settings_voice_search_toggle to SettingsSection.SEARCH,
    R.string.settings_asr_engines_title to SettingsSection.SEARCH,
    R.string.settings_voice_search_engine_title to SettingsSection.SEARCH,
    // Offline
    R.string.settings_offline to SettingsSection.OFFLINE,
    // Saved places
    R.string.settings_export to SettingsSection.SAVED_PLACES,
    R.string.settings_import to SettingsSection.SAVED_PLACES,
    R.string.mapscreen_section_lists to SettingsSection.SAVED_PLACES,
    // Data & privacy
    R.string.settings_privacy_button to SettingsSection.DATA_PRIVACY,
    R.string.settings_live_rechecks to SettingsSection.DATA_PRIVACY,
    // Diagnostics
    R.string.settings_share_diagnostics to SettingsSection.DIAGNOSTICS,
    R.string.settings_texture_render to SettingsSection.DIAGNOSTICS,
    R.string.settings_save_trips to SettingsSection.DIAGNOSTICS,
    R.string.settings_building_debug to SettingsSection.DIAGNOSTICS,
    // About
    R.string.settings_support to SettingsSection.ABOUT,
    R.string.settings_version to SettingsSection.ABOUT,
    R.string.settings_update_auto to SettingsSection.ABOUT,
    R.string.settings_update_nightly to SettingsSection.ABOUT,
    R.string.settings_update_check_now to SettingsSection.ABOUT,
)
