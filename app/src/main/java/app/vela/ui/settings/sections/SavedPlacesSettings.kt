package app.vela.ui.settings.sections

import app.vela.ui.icons.Sym

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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.ui.dpadHighlight
import app.vela.ui.settings.Hint
import app.vela.ui.item

/** Saved places sub-screen: export/import the saved places and the local lists, and the
 *  parking history. */
@Composable
internal fun SavedPlacesSettingsScreen(vm: MapViewModel, onBack: () -> Unit, onCloseSettings: () -> Unit = onBack) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsScaffold(stringResource(R.string.settings_saved_places), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_saved_places_hint))
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            // Off the main thread: a large GPX or KML is read and regex-parsed in full.
            if (uri != null) scope.launch {
                val res = withContext(Dispatchers.IO) { vm.importSavedFromUri(uri) }
                toastImport(context, res, places = true)
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
                onClick = { launchImport(context) { importLauncher.launch(IMPORT_MIME) } },
            ) { Text(stringResource(R.string.settings_import)) }
        }
        }

        // Lists export / import (issue #1) - same JSON-file flow as saved places.
        Spacer(Modifier.height(8.dp))
        val listImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch {
                val res = withContext(Dispatchers.IO) { vm.importListsFromUri(uri) }
                toastImport(context, res, places = false)
            }
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
        Spacer(Modifier.height(8.dp))
        SavedRoutesGroup(vm, onOpen = { vm.openSavedRoute(it); onCloseSettings() })
        Spacer(Modifier.height(8.dp))
        ParkingHistoryGroup(vm)
        Spacer(Modifier.height(24.dp))
    }
}

// Mime filter for a picked export. The wildcard type is kept alongside the JSON one because a lot
// of file providers hand back application/octet-stream for a .json, and filtering that out makes
// the file look absent - the picker opens onto an empty folder and the feature reads as broken.
// (NB a literal wildcard mime in a KDoc block closes the comment early - see PoiPackStore.)
private val IMPORT_MIME = arrayOf(
    "application/json",
    "application/gpx+xml",
    "application/vnd.google-earth.kml+xml",
    "application/xml",
    "text/xml",
    // Kept last and deliberately broad: providers hand back application/octet-stream for a .gpx or
    // .json often enough that filtering strictly makes the file look absent (issue #279).
    "*/*",
)

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

/** Saved routes (issue #622): each one's name and where it goes, with rename and delete. Always
 *  shown, so the settings search entry never leads to nothing. */
@Composable
private fun SavedRoutesGroup(vm: MapViewModel, onOpen: (app.vela.core.model.SavedRoute) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<app.vela.core.model.SavedRoute?>(null) }
    SettingsGroup(title = stringResource(R.string.settings_saved_routes)) {
        if (state.savedRoutes.isEmpty()) Hint(stringResource(R.string.settings_saved_routes_empty))
        state.savedRoutes.forEachIndexed { i, r ->
            if (i > 0) app.vela.ui.settings.GroupDivider()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The row opens the route on the map (a run with its stops loaded).
                Column(Modifier.weight(1f).dpadHighlight().clickable { onOpen(r) }) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    val sub = listOfNotNull(
                        if (r.isRun) androidx.compose.ui.res.pluralStringResource(R.plurals.saved_route_stops, r.stops.size, r.stops.size) else null,
                        r.destLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape)) {
                        Icon(Sym.MoreVert, contentDescription = stringResource(R.string.exp_chooser_more))
                    }
                    app.vela.ui.VelaMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        item(stringResource(R.string.settings_saved_route_edit)) { menu = false; vm.flashStatus(context.getString(R.string.settings_saved_route_edit_hint)); onOpen(r) }
                        item(stringResource(if (r.pinned) R.string.saved_unpin else R.string.saved_pin)) { menu = false; vm.setSavedRoutePinned(r.id, !r.pinned) }
                        item(stringResource(R.string.settings_saved_route_rename)) { menu = false; renaming = r }
                        item(stringResource(R.string.settings_saved_route_delete)) { menu = false; vm.deleteSavedRoute(r.id) }
                    }
                }
            }
        }
    }
    renaming?.let { r ->
        var draft by remember(r.id) { mutableStateOf(r.name) }
        app.vela.ui.VelaDialog(
            onDismissRequest = { renaming = null },
            title = stringResource(R.string.settings_saved_route_rename),
            confirmText = stringResource(R.string.list_save),
            onConfirm = { vm.renameSavedRoute(r.id, draft); renaming = null },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { renaming = null },
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(60) },
                singleLine = true,
                label = { Text(stringResource(R.string.route_save_hint)) },
                modifier = Modifier.fillMaxWidth().dpadHighlight(),
            )
        }
    }
}
