package app.vela.ui.nav

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.vela.R
import app.vela.ui.Clock24
import app.vela.ui.FollowMe
import app.vela.ui.SheetPalette
import app.vela.ui.dpadHighlight
import app.vela.ui.formatClock
import app.vela.ui.icons.Sym
import app.vela.ui.map.MapUiState
import app.vela.ui.map.MapViewModel
import app.vela.ui.map.NAV_FAB_EDGE_DP
import app.vela.ui.map.SearchGates
import app.vela.ui.map.landscapeColumn
import app.vela.ui.map.popIn
import app.vela.ui.map.sidePanelWidth
import app.vela.ui.theme.isAppInAmoled
import app.vela.ui.theme.isAppInDarkTheme
import kotlin.math.roundToInt

/**
 * Driving mode's chrome (issue #727): the drive's bar with the way out and the time, the locate
 * and voice buttons in the drive's column, and the map credit, over the free-drive map. Composed
 * from `MapFloaters` and drawn only while [SearchGates.drivingChromeLive] says nothing else owns
 * the screen. The search bar stays at the top (the category chips hide), the speed box is
 * `NavCorner`'s, and the camera is the free-drive follow's (SPEC 4.7).
 */
internal object DrivingBar {
    /** The bar's height above the system bar and its right edge, in window px, for `NavCorner`.
     *  Zero while the bar is not drawn. */
    val heightPx = mutableIntStateOf(0)
    val rightPx = mutableIntStateOf(0)
}

@Composable
internal fun BoxScope.DrivingChrome(state: MapUiState, vm: MapViewModel) {
    if (!app.vela.ui.DrivingMode.on.value) return
    val context = LocalContext.current
    // The screen stays on for the whole mode, a sheet or a search up or not, under the same
    // setting as a drive (Settings > Navigation, "Keep screen on while navigating"). The setting
    // is read on every pass, since Settings draws over the map and this never leaves
    // composition; the clock below recomposes it once a minute. Keyed on the drive too: a drive
    // adds the same flag and clears it when it ends, and this puts it back.
    val keepAwake = context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE).getBoolean("keep_screen_on_nav", true)
    val window = remember(context) {
        var c: android.content.Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window
    }
    DisposableEffect(keepAwake, window, state.navigating) {
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // The time of day, so a mounted screen with no status bar still has a clock. Ticks at the
    // top of each minute.
    var clockTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000 + 20); clockTick++ } }

    if (!SearchGates.drivingChromeLive.value) return
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val look = navBarLook()
    val amoled = isAppInAmoled()
    val dark = isAppInDarkTheme()
    val density = LocalDensity.current
    val rootView = LocalView.current
    DisposableEffect(Unit) {
        onDispose { DrivingBar.heightPx.intValue = 0; DrivingBar.rightPx.intValue = 0 }
    }

    // The locate button: the browse map's, in the drive's dress. With no permission the tap is
    // the ask, as on the browse map.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.startLocation()
    }
    val locate = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            FollowMe.on.value = true // the tap means "track me again", as on the browse map
            vm.recenter()
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val clock = remember(clockTick, Clock24.on.value) { formatClock(System.currentTimeMillis()) }

    // The bar: the drive's card, flush on the bottom edge in portrait, in the left column in
    // landscape, with the way out where End sits during a drive.
    Card(
        shape = navBarShape(),
        border = if (amoled) BorderStroke(1.dp, SheetPalette.BorderAmoled) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = look.bg, contentColor = look.ink),
        modifier = Modifier
            .align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
            .landscapeColumn(landscape, sidePanelWidth())
            .then(if (landscape) Modifier.padding(start = DRIVING_LAND_EDGE_DP, end = 16.dp) else Modifier)
            .fillMaxWidth()
            .onGloballyPositioned {
                // The card holds the system bar's space itself; what sits above the bar wants the
                // height above that.
                val inset = ViewCompat.getRootWindowInsets(rootView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                DrivingBar.heightPx.intValue = (it.size.height - inset).coerceAtLeast(1)
                DrivingBar.rightPx.intValue = it.boundsInWindow().right.roundToInt()
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavBarButton(onClick = vm::leaveDrivingMode, end = true) {
                Icon(Sym.Close, contentDescription = stringResource(R.string.driving_mode_leave), modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.driving_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = look.ink,
                    maxLines = 1,
                )
                Text(clock, style = MaterialTheme.typography.labelLarge, color = look.dim, maxLines = 1)
            }
            // The width of the button on the left, so the title sits centered on the bar.
            Spacer(Modifier.size(60.dp))
        }
        if (navBarFlush()) Spacer(Modifier.navigationBarsPadding())
    }

    // The right-edge column, where the drive keeps its buttons: locate, then the voice.
    val barClearance = with(density) { DrivingBar.heightPx.intValue.toDp() } + 16.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = NAV_FAB_EDGE_DP, bottom = if (landscape) 16.dp else barClearance)
            .popIn(),
    ) {
        NavFab(onClick = locate, modifier = Modifier.dpadHighlight(CircleShape)) {
            Icon(Sym.MyLocation, contentDescription = stringResource(R.string.mapscreen_center_on_my_location))
        }
        NavVoiceButton(muted = state.voiceMuted, onVoice = vm::setVoiceMode)
    }

    // The map credit, which the browse map's button block draws and which yields to this chrome:
    // the ODbL wants it in every map state. Above the bar's middle: the speed box has the left
    // corner, and the two sat on each other there. It fades while the voice choices are slid out
    // across it (issue #840), as the drive's own credit does.
    val osmUri = "https://www.openstreetmap.org/copyright"
    val creditShown by androidx.compose.animation.core.animateFloatAsState(
        if (VoiceSlideOutOpen.value) 0f else 1f,
        label = "drivingCredit",
    )
    Text(
        stringResource(R.string.map_osm_attribution),
        style = MaterialTheme.typography.labelSmall,
        color = if (dark) Color(0xFFB8C2CC) else Color(0xFF4A4A4A),
        modifier = Modifier
            .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(end = if (landscape) NAV_FAB_EDGE_DP + 56.dp + 12.dp else 0.dp, bottom = if (landscape) 2.dp else 2.dp + with(density) { DrivingBar.heightPx.intValue.toDp() } + 6.dp)
            .alpha(creditShown)
            .dpadHighlight(RoundedCornerShape(6.dp))
            .clickable {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(osmUri)))
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Landscape: the bar sits this far from the safe left edge, as the drive's cards do. */
private val DRIVING_LAND_EDGE_DP = 8.dp
