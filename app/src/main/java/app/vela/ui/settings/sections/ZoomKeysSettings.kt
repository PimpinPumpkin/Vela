package app.vela.ui.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.ZoomKeys
import app.vela.ui.dpadClickable
import app.vela.ui.dpadHighlight
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.settingsAnchor

/** The two zoom-key rows (issue #694): each shows its key and opens a "press a key" dialog. */
@Composable
internal fun ZoomKeyRows() {
    val context = LocalContext.current
    var settingIn by remember { mutableStateOf<Boolean?>(null) } // true: zoom in, false: zoom out
    KeyRow(stringResource(R.string.settings_zoom_key_in), ZoomKeys.zoomIn.intValue) { settingIn = true }
    GroupDivider()
    KeyRow(stringResource(R.string.settings_zoom_key_out), ZoomKeys.zoomOut.intValue) { settingIn = false }
    Hint(stringResource(R.string.settings_zoom_keys_hint))
    settingIn?.let { forIn ->
        KeyCaptureDialog(
            title = stringResource(if (forIn) R.string.settings_zoom_key_in else R.string.settings_zoom_key_out),
            onKey = { ZoomKeys.set(context, forIn, it); settingIn = null },
            onClear = { ZoomKeys.set(context, forIn, 0); settingIn = null },
            onDismiss = { settingIn = null },
        )
    }
}

@Composable
private fun KeyRow(label: String, keyCode: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().settingsAnchor(label).dpadHighlight(RoundedCornerShape(10.dp)).dpadClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (keyCode == 0) stringResource(R.string.settings_zoom_key_none) else ZoomKeys.label(keyCode),
            style = MaterialTheme.typography.bodyLarge,
            color = if (keyCode == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Takes the next key pressed. A raw Dialog whose prompt holds focus from the start, on a touch
 * phone too, or a key press would go nowhere. The arrows, OK and Back are not taken: they pass
 * through, so the arrows reach Clear and Back closes the dialog.
 */
@Composable
private fun KeyCaptureDialog(title: String, onKey: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp).widthIn(max = 360.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_zoom_key_press),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .dpadHighlight(RoundedCornerShape(12.dp))
                        .onPreviewKeyEvent { ev ->
                            val code = ev.nativeKeyEvent.keyCode
                            if (!ZoomKeys.assignable(code)) return@onPreviewKeyEvent false
                            if (ev.type == KeyEventType.KeyDown) onKey(code)
                            true
                        }
                        .focusable()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClear, modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp))) {
                        Text(stringResource(R.string.settings_zoom_key_clear))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp))) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}
