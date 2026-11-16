package app.vela.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.BuildConfig
import app.vela.R
import app.vela.core.data.SettingsBackup
import app.vela.ui.dpadRowSibling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Diagnostics > Settings backup (issue #951): Vela's settings to one file and back, so
 * a reinstall or a new phone starts with the same setup. What the file holds is [SettingsBackup].
 * Export asks whether to include places (on by default). Import asks first, writes the values,
 * then restarts Vela: settings are read into process-wide holders at launch (`VelaApp`), and a
 * restart is the one way every one of them reads the new value.
 */
@Composable
internal fun SettingsBackupGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askExport by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<SettingsBackup.Decoded.Ok?>(null) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(SettingsBackup.MIME),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { SettingsFile.write(context, uri, places) }
            toast(context, if (ok) R.string.settings_backup_exported else R.string.settings_backup_write_error)
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            when (val got = withContext(Dispatchers.IO) { SettingsFile.read(context, uri) }) {
                is SettingsBackup.Decoded.Ok ->
                    if (got.count == 0) toast(context, R.string.settings_backup_empty) else pending = got
                SettingsBackup.Decoded.WrongFormat -> toast(context, R.string.settings_backup_wrong_file)
                SettingsBackup.Decoded.TooNew -> toast(context, R.string.settings_backup_too_new)
                SettingsBackup.Decoded.Unreadable -> toast(context, R.string.settings_import_unreadable)
            }
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_backup_title)) {
        Hint(stringResource(R.string.settings_backup_hint))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Same L/R sibling wiring as the saved-places pair (issue #24, docs/dpad.md).
            val focus = remember { List(2) { FocusRequester() } }
            FilledTonalButton(
                modifier = Modifier.dpadRowSibling(focus, 0),
                onClick = { askExport = true },
            ) { Text(stringResource(R.string.settings_export)) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                modifier = Modifier.dpadRowSibling(focus, 1),
                // The wildcard type too: providers often hand back application/octet-stream for a
                // .json, and a strict filter makes the file look absent (issue #279).
                onClick = {
                    runCatching { picker.launch(arrayOf(SettingsBackup.MIME, "*" + "/" + "*")) }
                        .onFailure { toast(context, R.string.settings_import_no_picker) }
                },
            ) { Text(stringResource(R.string.settings_import)) }
        }
    }
    if (askExport) {
        app.vela.ui.VelaDialog(
            onDismissRequest = { askExport = false },
            title = stringResource(R.string.settings_backup_export_title),
            confirmText = stringResource(R.string.settings_export),
            onConfirm = {
                askExport = false
                runCatching { saver.launch(SettingsFile.fileName()) }
                    .onFailure { toast(context, R.string.settings_import_no_picker) }
            },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { askExport = false },
        ) {
            Column {
                ToggleRow(
                    label = stringResource(R.string.settings_backup_places),
                    checked = places,
                    onCheckedChange = { places = it },
                    hint = stringResource(
                        if (places) R.string.settings_backup_places_on_hint else R.string.settings_backup_places_off_hint,
                    ),
                )
            }
        }
    }
    pending?.let { got ->
        app.vela.ui.VelaDialog(
            onDismissRequest = { pending = null },
            title = stringResource(R.string.settings_backup_confirm_title),
            confirmText = stringResource(R.string.settings_backup_confirm_ok),
            onConfirm = {
                pending = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { SettingsFile.apply(context, got) }
                    if (ok) SettingsFile.restart(context) else toast(context, R.string.settings_backup_write_error)
                }
            },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { pending = null },
        ) {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.settings_backup_confirm_text, got.count, got.count))
        }
    }
}

private fun toast(context: android.content.Context, res: Int) =
    android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_LONG).show()

/** The Android half of [SettingsBackup]: the preference files, the font file, the picked file,
 *  the restart. */
internal object SettingsFile {
    // The settings are kilobytes; a custom font is at most 12 MB, a third more as base64.
    private const val MAX_BYTES = 17 * 1024 * 1024

    // Where AppFont keeps the custom font and its name (AppFont.fontFile, KEY_NAME).
    private const val FONT_PATH = "fonts/ui.ttf"
    private const val FONT_NAME_KEY = "ui_font_name"

    fun fileName(): String =
        "vela-settings-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"

    fun write(context: android.content.Context, uri: android.net.Uri, places: Boolean): Boolean = runCatching {
        val prefs = (SettingsBackup.SETTINGS_FILES + SettingsBackup.PLACE_FILES).associateWith { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all
        }
        val fontName = prefs["vela_settings"]?.get(FONT_NAME_KEY) as? String
        val fontFile = java.io.File(context.filesDir, FONT_PATH)
        val font = if (fontName != null && fontFile.isFile && fontFile.length() in 1..SettingsBackup.MAX_FONT_BYTES.toLong()) {
            SettingsBackup.Font(fontName, fontFile.readBytes())
        } else null
        val text = SettingsBackup.encode(prefs, font, places, BuildConfig.VERSION_NAME)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    fun read(context: android.content.Context, uri: android.net.Uri): SettingsBackup.Decoded = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BYTES) return SettingsBackup.Decoded.WrongFormat
            }
            buf.toByteArray()
        }
        SettingsBackup.decode(String(bytes, Charsets.UTF_8))
    }.getOrDefault(SettingsBackup.Decoded.Unreadable)

    /**
     * Makes the phone match [got] with `commit`, so it is on disk before the restart: the keys
     * [SettingsBackup.resets] lists are removed (they read their default again) and the file's
     * keys are written. The font goes beside its target and is renamed over it; `AppFont.init`
     * loads it at the next launch and drops the choice if the platform cannot build it. A file
     * with no font resets the font choice, so the old font file goes too.
     */
    fun apply(context: android.content.Context, got: SettingsBackup.Decoded.Ok): Boolean = runCatching {
        fun prefs(name: String) = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val current = (SettingsBackup.SETTINGS_FILES + SettingsBackup.PLACE_FILES).associateWith { prefs(it).all.keys.toSet() }
        val resets = SettingsBackup.resets(current, got)
        val dest = java.io.File(context.filesDir, FONT_PATH)
        val f = got.font
        if (f != null) {
            dest.parentFile?.mkdirs()
            val tmp = java.io.File(dest.parentFile, "restore.tmp")
            tmp.writeBytes(f.bytes)
            dest.delete()
            check(tmp.renameTo(dest))
        } else if (FONT_NAME_KEY in resets["vela_settings"].orEmpty()) {
            dest.delete()
        }
        (resets.keys + got.prefs.keys + if (f != null) setOf("vela_settings") else emptySet()).all { name ->
            val e = prefs(name).edit()
            for (k in resets[name].orEmpty()) e.remove(k)
            for ((k, v) in got.prefs[name].orEmpty()) {
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v as Set<String>)
                }
            }
            if (name == "vela_settings") f?.let { e.putString(FONT_NAME_KEY, it.name) }
            e.commit()
        }
    }.getOrDefault(false)

    /** Starts Vela afresh in a new task and ends this process. */
    fun restart(context: android.content.Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
