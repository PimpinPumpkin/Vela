package app.vela.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

// Containers are set to teal tints too — otherwise Material's defaults leave
// primaryContainer/secondaryContainer a stock purple, which made the map FABs and
// selected chips read "weirdly purple" against the teal brand.
private val LightColors = lightColorScheme(
    primary = VelaTeal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFB6E7DF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF00201D),
    secondary = VelaTealDark,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFCDE9E3),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF06201C),
    tertiary = VelaAmber,
)

private val DarkColors = darkColorScheme(
    primary = VelaTealLight,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003730),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF13534B),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFB6E7DF),
    secondary = VelaTeal,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1F4A44),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFCDE9E3),
    tertiary = VelaAmber,
)

/**
 * App theme. Vela's explicit teal light/dark schemes by default; Material You dynamic
 * colour (issue #15) when the user opts in via Settings -> Appearance ([DynamicColor]).
 *
 * The dynamic scheme is sanity-checked before use: on some ROMs (observed on GrapheneOS)
 * `dynamicDarkColorScheme` handed back a *light* background, which broke "Dark" for every
 * MaterialTheme surface (Settings etc.). If the scheme's background luminance contradicts
 * the requested theme, Vela falls back to its own colours - the Light/Dark switch is the
 * contract and always wins. Accent legibility comes from using the scheme's PAIRED slots
 * everywhere (primary with onPrimary, container with onContainer), which the system
 * generates at accessible contrast in both themes.
 */
@Composable
fun VelaTheme(
    darkTheme: Boolean = isAppInDarkTheme(),
    dynamicColor: Boolean = DynamicColor.on.value,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val saneBackground = if (darkTheme) dyn.background.luminance() < 0.4f else dyn.background.luminance() > 0.6f
            if (saneBackground) dyn else if (darkTheme) DarkColors else LightColors
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = VelaTypography, content = content)
}
