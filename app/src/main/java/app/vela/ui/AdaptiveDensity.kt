package app.vela.ui

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * Adaptive UI density for tiny (feature-phone) screens - the app CHECKS the screen and scales its own
 * density so the layout fits.
 *
 * Standard Material layouts assume roughly [MIN_WIDTH_DP] of logical width (48dp touch targets, list
 * paddings, dialog button rows). On a 240px-wide feature phone that only exists at higher density, the
 * logical width is far below that, so controls crowd and clip. Rather than hand-fix every screen for
 * every device, this overrides the app's effective `densityDpi` ONCE (at [wrap], from
 * `attachBaseContext`) so a small screen reports at least [MIN_WIDTH_DP] dp of width - then every screen
 * lays out with room, in one place.
 *
 * It ONLY ever SHRINKS a small screen; a normal/large screen (>= [MIN_WIDTH_DP] wide) is a byte-for-byte
 * no-op, so ordinary phones are untouched. The cost is physically smaller text on a tiny panel, so
 * [MIN_WIDTH_DP] is tuned VISUALLY on the real target sizes (240x320, 320x240). Mirrors [AppLocale.wrap]
 * and is chained with it in MainActivity/VelaApp.attachBaseContext.
 */
object AdaptiveDensity {
    /** Ensure the app has at least this much logical width (dp). Tunable; verify visually per device. */
    const val MIN_WIDTH_DP = 360

    /** Wrap a base [Context] with a shrunk density iff the screen is narrower than [MIN_WIDTH_DP].
     * Reads only the incoming [Configuration] (runs from `attachBaseContext`, before any init). */
    fun wrap(base: Context): Context {
        val cfg = base.resources.configuration
        val widthDp = cfg.screenWidthDp
        // Unknown (0) or already wide enough -> leave the default path byte-for-byte untouched.
        if (widthDp <= 0 || widthDp >= MIN_WIDTH_DP) return base
        val scale = widthDp.toFloat() / MIN_WIDTH_DP            // < 1 : shrink dp -> more fits
        val out = Configuration(cfg).apply {
            densityDpi = (cfg.densityDpi * scale).roundToInt().coerceAtLeast(1)
            // Report the larger logical size that the shrunk density buys, so layouts see the room.
            screenWidthDp = (screenWidthDp / scale).roundToInt()
            screenHeightDp = (screenHeightDp / scale).roundToInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / scale).roundToInt()
        }
        return base.createConfigurationContext(out)
    }
}
