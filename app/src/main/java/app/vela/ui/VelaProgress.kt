package app.vela.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The one progress bar (user 2026-09-18).
 *
 * Every download in the app grew its own `LinearProgressIndicator(Modifier.fillMaxWidth())`, which
 * left two things wrong. In Settings the bar ran the full width of the group while every row beside
 * it keeps a 16 dp margin, so the update bar visibly overhung the card. And a square-ended bar sits
 * badly against surfaces that are rounded everywhere else in this app (see the stadium-pill chip
 * rule): the ends are clipped round here so it reads like the rest of the chrome.
 *
 * [progress] null means indeterminate, which is what an unpack step that cannot report a percentage
 * gets instead of a frozen-looking bar.
 */
@Composable
fun VelaProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) = VelaProgressBarOf(progress?.let { p -> { p } }, modifier)

/**
 * The same, taking the value as a lambda so an animating bar is read in the draw phase instead of
 * recomposing whatever draws it. A download reports a new percent a few times a second and either
 * form is fine; a countdown runs at frame rate and needs this one.
 */
@Composable
fun VelaProgressBarOf(
    progress: (() -> Float)?,
    modifier: Modifier = Modifier,
) {
    val shaped = modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    if (progress == null) {
        LinearProgressIndicator(modifier = shaped)
    } else {
        LinearProgressIndicator(
            progress = { progress().coerceIn(0f, 1f) },
            modifier = shaped,
            // The track's own ends are drawn by the component; clipping the whole thing keeps both
            // ends round without fighting the M3 version's stop-indicator behavior.
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
