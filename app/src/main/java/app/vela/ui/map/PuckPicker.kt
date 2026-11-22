package app.vela.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.PuckStyle
import app.vela.ui.VelaDialog
import app.vela.ui.dpadHighlight

/**
 * The navigation icon picker that a tap on the arrow opens while navigating (SPEC 3.7, Settings): the icons
 * and car colors of Settings > Navigation > Navigation icon. A pick shows on the map at once;
 * Cancel, Back or a tap outside puts back the icon the dialog opened with.
 */
internal object PuckPicker {
    val open = mutableStateOf(false)

    val SHAPES = listOf(
        PuckStyle.SHAPE_ARROW to R.string.settings_puck_shape_arrow,
        PuckStyle.SHAPE_CAR to R.string.settings_puck_shape_car,
        PuckStyle.SHAPE_UFO to R.string.settings_puck_shape_ufo,
        PuckStyle.SHAPE_SHIP to R.string.settings_puck_shape_ship,
        PuckStyle.SHAPE_DUCK to R.string.settings_puck_shape_duck,
    )

    val CAR_COLORS = listOf(
        "red" to R.string.settings_car_red, "blue" to R.string.settings_car_blue,
        "white" to R.string.settings_car_white, "green" to R.string.settings_car_green,
        "yellow" to R.string.settings_car_yellow,
    )
}

/** An icon as the drive shows it: the 3D model for the four alternatives, the flat arrow. */
internal fun puckPreviewBitmap(shape: String, carColor: String, sizePx: Int): Bitmap {
    val mesh = PuckModels.forShape(shape, carColor)
    return if (mesh != null) puck3DPreviewBitmap(mesh, sizePx)
    else navPuckBitmap(scale = sizePx / 202f, shape = PuckStyle.SHAPE_ARROW)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PuckPickerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val before = remember { PuckStyle.shape.value to PuckStyle.carColor.value }
    val cancel = {
        if (PuckStyle.shape.value != before.first) PuckStyle.setShape(context, before.first)
        if (PuckStyle.carColor.value != before.second) PuckStyle.setCarColor(context, before.second)
        onClose()
    }
    VelaDialog(
        onDismissRequest = cancel,
        title = stringResource(R.string.settings_puck_shape),
        confirmText = stringResource(R.string.puck_pick_done),
        onConfirm = onClose,
        dismissText = stringResource(R.string.puck_pick_cancel),
        onDismiss = cancel,
    ) {
        Column {
            // Tiles 84 dp wide: three to a row on a phone, so the five sit three over two.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PuckPicker.SHAPES.forEach { (id, label) ->
                    PuckTile(id, stringResource(label), PuckStyle.shape.value == id) { PuckStyle.setShape(context, id) }
                }
            }
            if (PuckStyle.isCar()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PuckPicker.CAR_COLORS.forEach { (id, label) ->
                        FilterChip(
                            selected = PuckStyle.carColor.value == id,
                            onClick = { PuckStyle.setCarColor(context, id) },
                            label = { Text(stringResource(label)) },
                            shape = CircleShape,
                            modifier = Modifier.dpadHighlight(CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PuckTile(shape: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val carColor = PuckStyle.carColor.value
    val img = remember(shape, carColor) { puckPreviewBitmap(shape, carColor, 160).asImageBitmap() }
    val tileShape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(84.dp)
            .clip(tileShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tileShape)
            .dpadHighlight(tileShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(img, contentDescription = null, modifier = Modifier.size(56.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
