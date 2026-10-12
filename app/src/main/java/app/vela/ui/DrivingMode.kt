package app.vela.ui

import androidx.compose.runtime.mutableStateOf

/**
 * Driving mode (issue #727): the map in the drive's layout with no route, for a mounted phone,
 * a tablet or a head unit. The free-drive follow does the camera (SPEC 4.7, Free-drive follow);
 * this holder only says whether the mode is on. `DrivingChrome` draws its bar and buttons,
 * `SearchGates` hides the browse chrome while it draws, `NavCorner` shows the speed box, and
 * Settings > Map > "Where the map opens" > "Driving mode" (`StartView.DRIVE`) turns it on at
 * launch. Memory only: the start setting is what persists.
 */
object DrivingMode {
    val on = mutableStateOf(false)
}

/**
 * Whether the browse map follows the fix: on by default, dropped by a pan or a jump to somewhere
 * else, re-armed by the locate button. A holder rather than a `MapScreen` local so the driving
 * mode's locate button can re-arm it without a new parameter on `MapScreen`.
 */
object FollowMe {
    val on = mutableStateOf(true)
}
