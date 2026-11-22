package app.vela.ui.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.SheetPalette
import app.vela.ui.theme.isAppInDarkTheme

/**
 * "Resume navigation to …?" after a drive was cut off by a process kill (GrapheneOS reaping the
 * backgrounded nav process); Resume routes again from the current fix. Capped in width: full width
 * on a small landscape screen it covered the map buttons down the right side, and a tap meant for
 * the locate button resumed the old drive. Out of `MapScreen`, which is at its size limits.
 */
@Composable
internal fun BoxScope.ResumeNavCard(label: String, vm: MapViewModel) {
    val dark = isAppInDarkTheme()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SheetPalette.bg(dark),
        contentColor = SheetPalette.ink(dark),
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp)
            .widthIn(max = RESUME_CARD_MAX_DP.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.mapscreen_resume_nav, label.ifBlank { stringResource(R.string.mapscreen_your_destination) }),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.align(Alignment.End).padding(top = 8.dp)) {
                TextButton(onClick = vm::dismissResume) { Text(stringResource(R.string.mapscreen_dismiss)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::resumeNav) { Text(stringResource(R.string.mapscreen_resume)) }
            }
        }
    }
}

/** Wide enough for the prompt on one or two lines, narrow enough to leave a landscape screen's
 *  map buttons clear (a 752 dp wide 800 by 480 head unit keeps its 64 dp column free). */
private const val RESUME_CARD_MAX_DP = 480
