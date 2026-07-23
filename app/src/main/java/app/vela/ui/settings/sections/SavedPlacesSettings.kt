package app.vela.ui.settings.sections

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.dpadRowSibling // D-pad-only operation (docs/dpad.md)

/** Saved places sub-screen: export/import the saved places and the local lists. */
@Composable
internal fun SavedPlacesSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_saved_places), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_saved_places_hint))
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val n = vm.importSavedFromUri(uri)
                android.widget.Toast.makeText(
                    context,
                    if (n > 0) context.getString(R.string.settings_places_imported, n) else context.getString(R.string.settings_import_nothing),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // D-pad: the root Column swallows bare LEFT/RIGHT, so this button pair drives its OWN
            // L/R (issue #24 - Import was unreachable). Same pattern as the vibrate chips.
            val savedFocus = remember { List(2) { FocusRequester() } }
            FilledTonalButton(
                // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
                modifier = topRow.dpadRowSibling(savedFocus, 0),
                onClick = {
                    val intent = vm.exportSavedIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(context, context.getString(R.string.settings_no_saved_places), android.widget.Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.settings_export)) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                modifier = Modifier.dpadRowSibling(savedFocus, 1),
                onClick = {
                    runCatching { importLauncher.launch(arrayOf("application/json", "*/*")) }
                },
            ) { Text(stringResource(R.string.settings_import)) }
        }
        }

        // Lists export / import (issue #1) - same JSON-file flow as saved places.
        Spacer(Modifier.height(8.dp))
        val listImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val n = vm.importListsFromUri(uri)
                android.widget.Toast.makeText(
                    context,
                    if (n > 0) context.getString(R.string.settings_lists_imported, n) else context.getString(R.string.settings_import_nothing),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        SettingsGroup(title = stringResource(R.string.mapscreen_section_lists)) {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
            androidx.compose.foundation.layout.Column {
                app.vela.ui.settings.Hint(stringResource(R.string.settings_lists_export_hint))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Same L/R sibling wiring as the saved-places pair above (issue #24).
            val listsFocus = remember { List(2) { FocusRequester() } }
            FilledTonalButton(
                modifier = Modifier.dpadRowSibling(listsFocus, 0),
                onClick = {
                    val intent = vm.exportListsIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                    else android.widget.Toast.makeText(context, context.getString(R.string.settings_no_lists), android.widget.Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.settings_export)) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                modifier = Modifier.dpadRowSibling(listsFocus, 1),
                onClick = {
                    runCatching { listImportLauncher.launch(arrayOf("application/json", "*/*")) }
                },
            ) { Text(stringResource(R.string.settings_import)) }
        }
        }
        Spacer(Modifier.height(24.dp))
    }
}
