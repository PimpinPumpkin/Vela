package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.fillMaxWidth
import app.vela.ui.settings.settingsAnchor
import app.vela.ui.dpadHighlight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)

/** Privacy sub-screen: the how-Vela-handles-data explainer, the privacy policy link and Clear
 *  history. The places source moved to Places and live rechecks to Navigation (2026-09-17). */
@Composable
internal fun PrivacySettingsScreen(vm: app.vela.ui.map.MapViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_privacy), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_data_privacy_hint))
        // The master switch (2026-09-21): one control instead of the four-toggle recipe the FAQ
        // used to give, and it reaches the surfaces those toggles could not (search, Street View,
        // transit, the satellite fallback, the traffic raster).
        SettingsGroup {
            app.vela.ui.settings.ToggleRow(
                label = stringResource(R.string.settings_google_free),
                checked = app.vela.ui.GoogleFree.on.value,
                onCheckedChange = { app.vela.ui.GoogleFree.set(context, it) },
                hint = stringResource(R.string.settings_google_free_hint),
                switchModifier = topRow,
            )
            // Only meaningful while the switch is on: whether a shared short link may still ask
            // Google's shortener where it points.
            if (app.vela.ui.GoogleFree.on.value) {
                app.vela.ui.settings.GroupDivider()
                app.vela.ui.settings.ToggleRow(
                    label = stringResource(R.string.settings_google_free_links),
                    checked = app.vela.ui.GoogleFree.resolveLinks.value,
                    onCheckedChange = { app.vela.ui.GoogleFree.setResolveLinks(context, it) },
                    hint = stringResource(R.string.settings_google_free_links_hint),
                )
            }
        }
        // How long one Google session lives (2026-09-23, web/SessionRotation): a saved cookie is a
        // pseudonymous history, a new one gets Google's limited view. Pointless with Google off.
        if (!app.vela.ui.GoogleFree.on.value) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                app.vela.ui.settings.SubHead(stringResource(R.string.settings_google_session))
                app.vela.ui.settings.Hint(stringResource(R.string.settings_google_session_hint))
                if (app.vela.web.GoogleStanding.limited.value) {
                    app.vela.ui.settings.Hint(stringResource(R.string.settings_google_session_limited))
                }
                val rot = app.vela.web.SessionRotation
                listOf(
                    rot.WEEK to R.string.settings_google_session_week,
                    rot.DAY to R.string.settings_google_session_day,
                    rot.LAUNCH to R.string.settings_google_session_launch,
                ).forEach { (key, label) ->
                    app.vela.ui.settings.SelectableRow(
                        label = stringResource(label),
                        selected = rot.mode.value == key,
                        onClick = { rot.setMode(context, key) },
                    )
                }
                // What "every time" costs, shown once it is picked, so nobody wonders why the most
                // private option is not the default (user 2026-09-23).
                if (rot.mode.value == rot.LAUNCH) {
                    app.vela.ui.settings.Hint(stringResource(R.string.settings_google_session_launch_hint))
                }
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
                        onClick = {
                            rot.startNewNow(context)
                            android.widget.Toast.makeText(context, context.getString(R.string.settings_google_session_done), android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(stringResource(R.string.settings_google_session_now)) }
                }
                app.vela.ui.settings.ToggleRow(
                    label = stringResource(R.string.settings_block_google_telemetry),
                    checked = app.vela.web.GoogleTelemetry.block.value,
                    onCheckedChange = { app.vela.web.GoogleTelemetry.set(context, it) },
                    hint = stringResource(R.string.settings_block_google_telemetry_hint),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GoogleUsageGroup()
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilledTonalButton(
            modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/PimpinPumpkin/Vela/blob/main/PRIVACY.md"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Text(stringResource(R.string.settings_privacy_button)) }
        }
        }
        // Clear history (issue #425): one row for what used to be spread over three screens
        // (Clear recents on the search page, Clear all under Parking history, trips one at a
        // time under Diagnostics). Confirmed, since it cannot be undone.
        Spacer(Modifier.height(8.dp))
        var confirmClear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        SettingsGroup {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().settingsAnchor(stringResource(R.string.settings_clear_history)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.settings_clear_history), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_clear_history_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FilledTonalButton(
                modifier = Modifier.padding(top = 8.dp).dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
                onClick = { confirmClear = true },
            ) { Text(stringResource(R.string.settings_clear_history_action)) }
        }
        }
        if (confirmClear) {
            app.vela.ui.VelaDialog(
                onDismissRequest = { confirmClear = false },
                title = stringResource(R.string.settings_clear_history_confirm_title),
                confirmText = stringResource(R.string.settings_clear_history_action),
                onConfirm = {
                    vm.clearAllHistory()
                    confirmClear = false
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_clear_history_done), android.widget.Toast.LENGTH_SHORT).show()
                },
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { confirmClear = false },
            ) {
                Text(stringResource(R.string.settings_clear_history_confirm_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** How many requests went to Google, today and over the last week, by purpose (2026-09-25,
 *  core/net/GoogleUsage). Read when the page opens; the numbers are a snapshot, not live. */
@Composable
private fun GoogleUsageGroup() {
    val today = androidx.compose.runtime.remember { app.vela.diag.GoogleUsageStore.today() }
    val week = androidx.compose.runtime.remember { app.vela.diag.GoogleUsageStore.week() }
    val avg = androidx.compose.runtime.remember { app.vela.diag.GoogleUsageStore.weekDailyAverage() }
    SettingsGroup {
        app.vela.ui.settings.SubHead(stringResource(R.string.settings_google_usage))
        app.vela.ui.settings.Hint(stringResource(R.string.settings_google_usage_hint))
        if (week.isEmpty()) {
            app.vela.ui.settings.Hint(stringResource(R.string.settings_google_usage_none))
        } else {
            Text(
                stringResource(R.string.settings_google_usage_summary, today.sumOf { it.second }, avg),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val todayMap = today.toMap()
            week.forEach { (kind, n) ->
                Text(
                    stringResource(R.string.settings_google_usage_row, googleUsageLabel(kind), todayMap[kind] ?: 0, n),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun googleUsageLabel(kind: String): String = when {
    kind == "search" -> stringResource(R.string.google_usage_kind_search)
    kind == "nearby places" -> stringResource(R.string.google_usage_kind_nearby)
    kind == "place details" -> stringResource(R.string.google_usage_kind_details)
    kind == "photos" -> stringResource(R.string.google_usage_kind_photos)
    kind == "reviews" -> stringResource(R.string.google_usage_kind_reviews)
    kind == "images" -> stringResource(R.string.google_usage_kind_images)
    kind == "directions" -> stringResource(R.string.google_usage_kind_directions)
    kind == "suggestions" -> stringResource(R.string.google_usage_kind_suggestions)
    kind == "street view" -> stringResource(R.string.google_usage_kind_streetview)
    kind == "session" -> stringResource(R.string.google_usage_kind_session)
    kind == "shared list" -> stringResource(R.string.google_usage_kind_lists)
    kind == "page resources" -> stringResource(R.string.google_usage_kind_page_resources)
    kind.startsWith("page: ") -> stringResource(R.string.google_usage_kind_page, kind.removePrefix("page: "))
    else -> stringResource(R.string.google_usage_kind_other)
}

