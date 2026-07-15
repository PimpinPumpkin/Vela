package app.vela.ui.place

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vela.R
import app.vela.core.model.StreetViewPano
import app.vela.streetview.PanoramaView
import androidx.compose.ui.res.stringResource

/**
 * Full-screen in-app Street View. Renders the panorama on a GL sphere ([PanoramaView]) - drag to
 * look, pinch to zoom. Shown while the tiles load (spinner), then the imagery. Closes by the X or
 * system back. Attribution (place name + Google copyright) sits bottom-left, as Google shows it.
 *
 * A borderless full-screen [Dialog] so it covers the map + sheets; the GL view fills it.
 */
@Composable
fun StreetViewScreen(
    pano: StreetViewPano?,
    bitmap: Bitmap?,
    loading: Boolean,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (pano != null) {
                // One PanoramaView for the pano's lifetime; feed the bitmap in ONCE when it arrives
                // (a LaunchedEffect, not the update lambda - re-uploading every recompose is wasteful
                // and would race the VM recycling the bitmap on close).
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val view = remember(pano.panoId) {
                    PanoramaView(ctx).apply { setInitialYaw(pano.headingDeg.toFloat()) }
                }
                DisposableEffect(view) { onDispose { view.onPause() } }
                androidx.compose.runtime.LaunchedEffect(view, bitmap) {
                    bitmap?.let { view.setPanorama(it) }
                }
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }

            if (loading || bitmap == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Attribution: place label + "© Google" (Street View imagery is Google's).
            val label = pano?.addressLabel
            val copy = pano?.copyright ?: "© Google"
            Text(
                text = if (label.isNullOrBlank()) copy else "$label  ·  $copy",
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 20.dp),
            )

            // Close.
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.steps_close_cd),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
