package app.vela.ui.settings.sections

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.QuickCategories
import app.vela.ui.QuickChipsPref
import app.vela.ui.VelaSwitch
import app.vela.ui.dpadClickable // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import app.vela.ui.icons.Sym
import app.vela.ui.map.ReorderHandle
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.settingsAnchor

/** The row on the Search page that opens [QuickChipsSettingsScreen]. */
@Composable
internal fun QuickChipsRow(onOpen: () -> Unit) {
    val label = stringResource(R.string.settings_quick_chips)
    Row(
        Modifier.fillMaxWidth().settingsAnchor(label).dpadHighlight(DpadShape(10.dp)).dpadClickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_quick_chips_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Sym.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Settings > Search > Quick search buttons (issue #779): one row per chip in the user's order,
 * a switch that takes it off the rows and a handle that moves it. Hidden chips keep their place
 * in the list, so switching one back on puts it where it was. Reset puts the default back.
 */
@Composable
internal fun QuickChipsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val chips = QuickCategories.arranged()
    val hidden = QuickChipsPref.hidden.value
    // One height for every row: the handle moves a row per row height dragged.
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    SettingsScaffold(stringResource(R.string.settings_quick_chips), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_quick_chips_hint))
        SettingsGroup {
            chips.forEachIndexed { index, chip ->
                // Keyed on the chip, so a moved row carries its focus with it.
                key(chip.key) {
                    if (index > 0) GroupDivider()
                    ChipRow(
                        chip = chip,
                        shown = chip.key !in hidden,
                        rowHeightPx = rowHeightPx,
                        switchModifier = if (index == 0) topRow else Modifier,
                        onShown = { QuickChipsPref.setShown(context, chip.key, it) },
                        onMove = { d -> if ((d < 0 && index > 0) || (d > 0 && index < chips.lastIndex)) QuickCategories.move(context, chip.key, d) },
                        onHeight = { rowHeightPx = it },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val customized = QuickChipsPref.customized()
        val reset = stringResource(R.string.settings_quick_chips_reset)
        Row(
            Modifier.fillMaxWidth().settingsAnchor(reset).dpadHighlight(DpadShape(10.dp))
                .dpadClickable(enabled = customized) { QuickChipsPref.reset(context) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                reset,
                style = MaterialTheme.typography.bodyLarge,
                color = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// focusProperties.enter is still experimental in the Compose UI version in use.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChipRow(
    chip: QuickCategories.Chip,
    shown: Boolean,
    rowHeightPx: Float,
    switchModifier: Modifier,
    onShown: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onHeight: (Float) -> Unit,
) {
    // Focus arriving from another row lands on the switch, never the handle, where Up and Down
    // would move the chip instead of walking the rows (the lists sheet's rule).
    val switchFocus = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .focusProperties { enter = { switchFocus } }
            .focusGroup()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            chip.icon,
            contentDescription = null,
            tint = if (shown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(chip.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        VelaSwitch(checked = shown, onCheckedChange = onShown, modifier = switchModifier.focusRequester(switchFocus))
        Spacer(Modifier.width(4.dp))
        // Drag moves the row; focused, Up and Down move it and OK flips the switch.
        ReorderHandle(
            onMove = onMove,
            onActivate = { onShown(!shown) },
            rowHeightPx = rowHeightPx,
            contentDescription = stringResource(R.string.list_reorder),
        )
    }
}
