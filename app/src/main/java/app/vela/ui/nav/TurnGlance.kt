package app.vela.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.ui.LockScreenNav
import app.vela.ui.formatDistance
import app.vela.ui.map.MapViewModel

/**
 * The glance screen of a turn wake ([LockScreenNav.glance]): black, with the next turn in large
 * type, its lanes, and the turn after it when that comes right after. Black costs an OLED screen
 * nothing and does not glare at night. A tap, a key or the power button brings the whole drive
 * back ([LockScreenNav.userActive]).
 */
@Composable
fun TurnGlance(vm: MapViewModel) {
    if (!LockScreenNav.glance.value) return
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.navigating) return
    val mans = state.activeRoute?.maneuvers
    val step = state.nav.stepIndex
    val m = mans?.getOrNull(step)
    val next = mans?.getOrNull(step + 1)
    val white = Color.White
    val soft = Color(0xFFBDC1C6)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // dpad-ok: a key press ends the glance in MainActivity.dispatchKeyEvent.
            .pointerInput(Unit) { detectTapGestures { LockScreenNav.endGlance() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (m != null) {
                Icon(
                    if (isRoundabout(m.type)) rememberRoundaboutGlyph(m.roundabout) else maneuverIcon(m.type),
                    contentDescription = null,
                    tint = white,
                    modifier = Modifier.size(140.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.nav.offRoute) stringResource(R.string.nav_rerouting) else formatDistance(state.nav.distanceToNextManeuver),
                color = white,
                fontSize = 64.sp,
                lineHeight = 68.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                app.vela.core.util.RoadNameShort.inText(state.maneuverText, m?.road),
                color = soft,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (m?.mayBeBanned == true) {
                Spacer(Modifier.height(12.dp))
                TurnBanWarning(Color(0xFFFDD663))
            }
            if (m != null && m.lanes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                LaneDiagram(m.lanes, m.type, on = white)
            }
            if (m != null && next != null && isCompoundNext(m.distanceMeters)) {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nav_compound_then), color = soft, fontSize = 22.sp)
                    Spacer(Modifier.size(10.dp))
                    Icon(
                        if (isRoundabout(next.type)) rememberRoundaboutGlyph(next.roundabout) else maneuverIcon(next.type),
                        contentDescription = null,
                        tint = soft,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}
