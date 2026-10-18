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

/** The Waze police report badge, the same way. */
@Composable
fun PoliceBadge(modifier: Modifier = Modifier) {
    val bmp = remember { app.vela.ui.map.policeBadgeBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.camera_badge_police), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}

/** The Waze accident alert badge. */
@Composable
fun AccidentBadge(modifier: Modifier = Modifier) {
    val bmp = remember { app.vela.ui.map.accidentBadgeBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.waze_badge_accident), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}

/** The Waze road hazard & weather alert badge. */
@Composable
fun HazardBadge(modifier: Modifier = Modifier) {
    val bmp = remember { app.vela.ui.map.hazardBadgeBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.waze_badge_hazard), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}

/** The Waze road closure badge. */
@Composable
fun ClosureBadge(modifier: Modifier = Modifier) {
    val bmp = remember { app.vela.ui.map.closureBadgeBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.waze_badge_closure), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}

/** The Waze traffic jam badge. */
@Composable
fun JamBadge(modifier: Modifier = Modifier) {
    val bmp = remember { app.vela.ui.map.jamBadgeBitmap().asImageBitmap() }
    Image(bmp, contentDescription = stringResource(R.string.waze_badge_jam), modifier = modifier.size(28.dp), filterQuality = FilterQuality.High)
}


