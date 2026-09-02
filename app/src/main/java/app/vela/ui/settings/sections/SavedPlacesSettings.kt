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
            if (uri != null) toastImport(context, vm.importSavedFromUri(uri), places = true)
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
                onClick = { launchImport(context) { importLauncher.launch(IMPORT_MIME) } },
            ) { Text(stringResource(R.string.settings_import)) }
        }
        }

        // Lists export / import (issue #1) - same JSON-file flow as saved places.
        Spacer(Modifier.height(8.dp))
        val listImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) toastImport(context, vm.importListsFromUri(uri), places = false)
        }
        SettingsGroup(title = stringResource(R.string.mapscreen_section_lists)) {
        app.vela.ui.settings.Hint(stringResource(R.string.settings_lists_export_hint))
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
                onClick = { launchImport(context) { listImportLauncher.launch(IMPORT_MIME) } },
            ) { Text(stringResource(R.string.settings_import)) }
        }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// Mime filter for a picked export. The wildcard type is kept alongside the JSON one because a lot
// of file providers hand back application/octet-stream for a .json, and filtering that out makes
// the file look absent - the picker opens onto an empty folder and the feature reads as broken.
// (NB a literal wildcard mime in a KDoc block closes the comment early - see PoiPackStore.)
private val IMPORT_MIME = arrayOf("application/json", "*/*")

/**
 * Open the file picker, and SAY SO when there isn't one (issue #287).
 *
 * The launch used to be wrapped in a bare `runCatching`, so on a device with no documents provider
 * the exception was swallowed and the button genuinely did nothing at all - no picker, no message,
 * which is exactly what was reported. A stripped-down or degoogled ROM without DocumentsUI is a
 * realistic case for this app's users.
 */
private fun launchImport(context: android.content.Context, launch: () -> Unit) {
    runCatching { launch() }.onFailure {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.settings_import_no_picker),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

/** One message per real outcome, instead of "nothing to import" for all of them. */
private fun toastImport(
    context: android.content.Context,
    result: app.vela.core.data.ImportResult,
    places: Boolean,
) {
    val msg = when (result) {
        is app.vela.core.data.ImportResult.Added ->
            if (places) context.getString(R.string.settings_places_imported, result.count)
            else context.getString(R.string.settings_lists_imported, result.count)
        app.vela.core.data.ImportResult.NothingNew -> context.getString(R.string.settings_import_nothing_new)
        app.vela.core.data.ImportResult.Unreadable -> context.getString(R.string.settings_import_unreadable)
        is app.vela.core.data.ImportResult.WrongFormat ->
            result.format?.let { context.getString(R.string.settings_import_wrong_format_named, it) }
                ?: context.getString(R.string.settings_import_wrong_format)
    }
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
}
