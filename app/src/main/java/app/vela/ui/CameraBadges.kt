package app.vela.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.map.alprCameraBitmap
import app.vela.ui.map.speedCamBitmap

/** The plate camera badge as the map draws it, so the welcome card and the setting that turn it
 *  on show the thing itself. */
@Composable
fun PlateCameraBadge(modifier: Modifier = Modifier) {
    val bmp = remember { alprCameraBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.camera_badge_plate), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}

/** The speed camera badge, the same way. */
@Composable
fun SpeedCameraBadge(modifier: Modifier = Modifier) {
    val bmp = remember { speedCamBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.camera_badge_speed), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}
