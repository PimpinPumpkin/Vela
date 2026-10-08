package app.vela.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.VelaDialog
import app.vela.ui.VelaMenu
import app.vela.ui.dpadFieldEscape
import app.vela.ui.dpadHighlight
import app.vela.ui.icons.Sym
import app.vela.ui.item

/**
 * What a selection of places can do: be removed, or moved to another list. One shape for an open
 * list's places and for the Saved places, so both get the same bar.
 */
class ListBulk(
    /** The lists a selection can move to, as id and name. */
    val targets: List<Pair<String, String>>,
    val onRemove: (Set<String>) -> Unit,
    val onMove: (ids: Set<String>, toListId: String) -> Unit,
    /** Makes a list by this name and returns its id, for "New list…". */
    val onCreateList: (String) -> String,
)

/**
 * The bar a selection wears in place of its sheet's title: the count, All, Move to list, Remove
 * and Done. [picked] is read when an action runs, [onChange] takes the next selection, and a
 * null one ends the selecting.
 */
@Composable
internal fun BulkBar(
    picked: Set<String>,
    all: Set<String>,
    bulk: ListBulk,
    ink: Color,
    onChange: (Set<String>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var moving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.bulk_selected, picked.size, picked.size),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onChange(if (picked.size == all.size) emptySet() else all) },
            modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp)),
        ) { Text(stringResource(R.string.bulk_all)) }
        Box {
            IconButton(onClick = { moving = true }, enabled = picked.isNotEmpty(), modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
                Icon(Sym.Bookmarks, contentDescription = stringResource(R.string.bulk_move), tint = if (picked.isNotEmpty()) ink else ink.copy(alpha = 0.35f))
            }
            VelaMenu(expanded = moving, onDismissRequest = { moving = false }) {
                bulk.targets.forEach { (id, name) ->
                    item(name, Sym.Bookmark) { moving = false; bulk.onMove(picked, id); onChange(null) }
                }
                item(stringResource(R.string.bulk_new_list), Sym.Add) { moving = false; naming = true }
            }
        }
        IconButton(onClick = { removing = true }, enabled = picked.isNotEmpty(), modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
            Icon(Sym.Delete, contentDescription = stringResource(R.string.bulk_remove), tint = if (picked.isNotEmpty()) MaterialTheme.colorScheme.error else ink.copy(alpha = 0.35f))
        }
        IconButton(onClick = { onChange(null) }, modifier = Modifier.size(40.dp).dpadHighlight(CircleShape)) {
            Icon(Sym.Close, contentDescription = stringResource(R.string.bulk_done), tint = ink)
        }
    }
    if (removing) {
        VelaDialog(
            onDismissRequest = { removing = false },
            title = pluralStringResource(R.plurals.bulk_remove_title, picked.size, picked.size),
            confirmText = stringResource(R.string.bulk_remove),
            onConfirm = { removing = false; bulk.onRemove(picked); onChange(null) },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { removing = false },
        ) {}
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        VelaDialog(
            onDismissRequest = { naming = false },
            title = stringResource(R.string.list_new_title),
            confirmText = stringResource(R.string.bulk_move),
            onConfirm = {
                val n = name.trim()
                if (n.isNotEmpty()) { bulk.onMove(picked, bulk.onCreateList(n)); onChange(null) }
                naming = false
            },
            dismissText = stringResource(R.string.list_cancel),
            onDismiss = { naming = false },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                label = { Text(stringResource(R.string.list_name_label)) },
                modifier = Modifier.dpadFieldEscape(),
            )
        }
    }
}
