package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.ui.Units
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadRowSibling
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.ThemeMode

/** Appearance sub-screen: theme, interface size, map colors, Material You, units, language. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_appearance), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        SelectableRow(
            label = stringResource(R.string.settings_follow_system),
            selected = AppTheme.mode.value == ThemeMode.SYSTEM,
            onClick = { AppTheme.set(context, ThemeMode.SYSTEM) },
            // The top focusable row: Back routes its DOWN here, UP from here goes back to Back.
            modifier = topRow,
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_light),
            selected = AppTheme.mode.value == ThemeMode.LIGHT,
            onClick = { AppTheme.set(context, ThemeMode.LIGHT) },
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_dark),
            selected = AppTheme.mode.value == ThemeMode.DARK,
            onClick = { AppTheme.set(context, ThemeMode.DARK) },
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_amoled),
            selected = AppTheme.mode.value == ThemeMode.AMOLED,
            onClick = { AppTheme.set(context, ThemeMode.AMOLED) },
        )
        GroupDivider()
        // Day/night by the actual sun, not by the clock and not by the OS (issue #262). It sits in
        // the mode list rather than as a toggle because it IS a mode - picking it replaces whatever
        // light/dark choice was there.
        SelectableRow(
            label = stringResource(R.string.settings_theme_auto),
            selected = AppTheme.mode.value == ThemeMode.AUTO,
            onClick = { AppTheme.set(context, ThemeMode.AUTO) },
        )
        }
        Hint(stringResource(R.string.settings_theme_auto_hint))
        Hint(stringResource(R.string.settings_appearance_hint))

        // Orthogonal to the mode above, and only meaningful alongside a FIXED choice: keep the app
        // dark by preference, but drive by a light map in daylight. Hidden under AUTO, where it
        // would claim to do something already happening.
        if (AppTheme.mode.value != ThemeMode.AUTO) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                ToggleRow(
                    label = stringResource(R.string.settings_nav_day_night),
                    checked = AppTheme.navDayNight.value,
                    onCheckedChange = { AppTheme.setNavDayNight(context, it) },
                    hint = stringResource(R.string.settings_nav_day_night_hint),
                )
            }
        }
        // FONT (issue #252). Only two states, and deliberately so: the platform font, or a file the
        // user supplies. We ship no faces of our own - the one people ask for is proprietary and
        // cannot be redistributed - so the honest offer is "use what you already have a licence to".
        Spacer(Modifier.height(8.dp))
        val fontBad = stringResource(R.string.settings_font_bad)
        val fontPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val name = queryDisplayName(context, uri) ?: "Custom"
                if (!app.vela.ui.AppFont.setCustom(context, uri, name)) {
                    android.widget.Toast.makeText(context, fontBad, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_font)) {
            SelectableRow(
                label = stringResource(R.string.settings_font_system),
                selected = app.vela.ui.AppFont.customName.value == null,
                onClick = { app.vela.ui.AppFont.clear(context) },
            )
            GroupDivider()
            SelectableRow(
                label = app.vela.ui.AppFont.customName.value ?: stringResource(R.string.settings_font_choose),
                selected = app.vela.ui.AppFont.customName.value != null,
                // Picking the row re-opens the chooser, so swapping fonts is one tap either way -
                // whether a custom font is already set or not.
                onClick = {
                    // Fonts arrive under a pile of MIME types (font/ttf, application/x-font-ttf,
                    // application/octet-stream from some file managers), so accept anything and let
                    // AppFont reject what will not load. A filter that hides the user's font file is
                    // worse than a chooser that shows too much.
                    fontPicker.launch(arrayOf("*/*"))
                },
            )
        }
        Hint(stringResource(R.string.settings_font_hint))

        Spacer(Modifier.height(8.dp))
        // Interface size: scales every control and sheet (not the map) - for car/tablet
        // screens where buttons run small (user 2026-07-11). Numeric labels, no i18n needed.
        SettingsGroup(title = stringResource(R.string.settings_ui_scale)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val scaleFocus = remember { List(4) { FocusRequester() } }
                    listOf(0.9f, 1.0f, 1.15f, 1.3f).forEachIndexed { i, f ->
                        FilterChip(
                            selected = kotlin.math.abs(app.vela.ui.UiScale.factor.value - f) < 0.01f,
                            onClick = { app.vela.ui.UiScale.set(context, f) },
                            label = { Text("${(f * 100).toInt()}%") },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            modifier = Modifier
                                .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                                .dpadRowSibling(scaleFocus, i),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Map colour sets: Modern = the Google-app-sampled palette (default), Classic = the
        // archived pre-sample look (white roads, yellow motorways, true greens). The fleet
        // default is remote-pushable via calibration.json; an explicit pick here wins.
        SettingsGroup(title = stringResource(R.string.settings_map_colors)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val palFocus = remember { List(2) { FocusRequester() } }
                    listOf(
                        app.vela.ui.MapColors.MODERN to stringResource(R.string.settings_map_colors_modern),
                        app.vela.ui.MapColors.CLASSIC to stringResource(R.string.settings_map_colors_classic),
                    ).forEachIndexed { i, (id, label) ->
                        FilterChip(
                            selected = app.vela.ui.MapColors.current() == id,
                            onClick = { app.vela.ui.MapColors.set(context, id) },
                            label = { Text(label) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            modifier = Modifier
                                .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                                .dpadRowSibling(palFocus, i),
                        )
                    }
                }
                Hint(stringResource(R.string.settings_map_colors_hint))
            }
        }

        // Material You (issue #15): tint the app's buttons, chips and accents from the
        // wallpaper palette. Android 12+ only - below S there is no dynamic palette, so
        // the row is not shown at all rather than shown dead.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                ToggleRow(
                    label = stringResource(R.string.settings_dynamic_color),
                    checked = app.vela.ui.theme.DynamicColor.on.value,
                    onCheckedChange = { app.vela.ui.theme.DynamicColor.set(context, it) },
                    hint = stringResource(R.string.settings_dynamic_color_hint),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_app_name_title)) {
            // Component state IS the persistence (survives reboots/updates); mirror it into
            // compose state so the switch animates without a restart.
            val generic = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(app.vela.ui.AppNameAlias.isGeneric(context))
            }
            ToggleRow(
                label = stringResource(R.string.settings_app_name_generic),
                checked = generic.value,
                onCheckedChange = {
                    generic.value = it
                    app.vela.ui.AppNameAlias.setGeneric(context, it)
                },
                hint = stringResource(R.string.settings_app_name_generic_hint),
            )
        }

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_units)) {
            SelectableRow(
                label = stringResource(R.string.settings_units_imperial),
                selected = Units.imperial.value,
                onClick = { Units.set(context, true) },
            )
            GroupDivider()
            SelectableRow(
                label = stringResource(R.string.settings_units_metric),
                selected = !Units.imperial.value,
                onClick = { Units.set(context, false) },
            )
        }

        Spacer(Modifier.height(8.dp))
        // Most people want the system language, so lead with a single toggle and only reveal the
        // full picker when they turn it off (keeps the list out of the common case).
        SettingsGroup(title = stringResource(R.string.settings_language)) {
            val followSystemLang = app.vela.ui.AppLocale.language.value.isBlank()
            ToggleRow(
                label = stringResource(R.string.settings_follow_system_language),
                checked = followSystemLang,
                onCheckedChange = { on ->
                    // Off -> seed with the language closest to the device locale so the picker opens
                    // on a real current choice, not blank; on -> clear the override back to system.
                    app.vela.ui.AppLocale.set(context, if (on) "" else app.vela.ui.AppLocale.deviceDefaultSupported())
                },
            )
            if (!followSystemLang) {
                app.vela.ui.AppLocale.SUPPORTED.forEach { code ->
                    GroupDivider()
                    SelectableRow(
                        label = app.vela.ui.AppLocale.endonym(code),
                        selected = app.vela.ui.AppLocale.language.value == code,
                        onClick = { app.vela.ui.AppLocale.set(context, code) },
                    )
                }
            }
        }
        Hint(stringResource(R.string.settings_language_hint))
        Spacer(Modifier.height(24.dp))
    }
}


/** The human file name behind a document [uri], for the settings row. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()
