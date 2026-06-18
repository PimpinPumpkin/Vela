package app.vela.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = VelaTeal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = VelaTealDark,
    tertiary = VelaAmber,
)

private val DarkColors = darkColorScheme(
    primary = VelaTealLight,
    secondary = VelaTeal,
    tertiary = VelaAmber,
)

/**
 * App theme. Uses Vela's explicit teal light/dark schemes rather than Material You
 * dynamic colour: the in-app Light/Dark switch is the contract, and on some ROMs
 * (observed on GrapheneOS) `dynamicDarkColorScheme` hands back a *light* background,
 * which broke "Dark" for every MaterialTheme surface (Settings etc.). `dynamicColor`
 * stays as an opt-in param but defaults off so the switch is always honoured.
 */
@Composable
fun VelaTheme(
    darkTheme: Boolean = isAppInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = VelaTypography, content = content)
}
