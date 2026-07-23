package app.vela.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.VelaSwitch
import app.vela.ui.dpadClickable // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadHighlight
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

// The shared row/heading vocabulary for every Settings page (the hub and each sub-screen).
// Moved verbatim out of the old single-page SettingsScreen so all section files share one copy.

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** A [SectionTitle] that toggles a collapsible body - tap the whole row; a chevron shows the state. */
@Composable
internal fun CollapsibleSectionTitle(text: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().dpadHighlight(DpadShape(10.dp)).dpadClickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the RadioButton is display-only so the ROW is the single focus stop. A
        // separately-focusable RadioButton made the row TWO focus stops, and a horizontal (LEFT/
        // RIGHT) D-pad move into that nested target cleared focus with no way back (dpad audit
        // 2026-07-08) - the Material "clickable row + indicator" pattern.
        RadioButton(selected = selected, onClick = null)
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * A settings section group: an optional accent [SubHead] over a FILLED rounded container of rows -
 * the modern M3 grouped-card treatment (user 2026-07-23: the fork's outlined boxes and hairlines
 * read dated). Structure comes from the container fill, not borders or dividers. The fill is the
 * surfaceContainer TOKEN so it themes itself: Material You tints it from the wallpaper, light/dark
 * pick their own step, and the AMOLED scheme's near-black container roles keep the layering on
 * true black. Purely structural: no focus behaviour of its own.
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (title != null) SubHead(title)
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = DpadShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** A lighter heading for sub-parts within a section (e.g. "Map area" / "Routing regions" under
 * "Offline") and for [SettingsGroup] titles - brand-colored so it reads as the same accent system
 * as the hub and the collapsible headers. */
@Composable
internal fun SubHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

/** The page-level "what this page is" caption. Plain small text (standard layout, no container). */
@Composable
internal fun PageIntro(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * The gap between rows inside a [SettingsGroup]. Deliberately NOT a drawn line anymore (user
 * 2026-07-23: the hairlines read dated) - the filled container plus row spacing carries the
 * structure, like modern M3 settings. Kept as a composable so every call site stays valid.
 */
@Composable
internal fun GroupDivider() {
    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 2.dp))
}

/**
 * A "label + switch" settings row, hub-styled: the hint renders as a supporting line UNDER the
 * label inside the row (the old page drew it as a separate full-width paragraph below). One focus
 * stop: the ring lives on the [VelaSwitch] track (docs/dpad.md - a menu toggle row rings the
 * switch, not the row). [switchModifier] exists so a page can attach its top-row focus bridge to
 * its first switch.
 */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
    switchModifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        VelaSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = switchModifier,
        )
    }
}
