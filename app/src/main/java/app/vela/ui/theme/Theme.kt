package app.vela.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
 * App theme. Honours Material You dynamic colour on Android 12+ (so Vela picks
 * up the user's wallpaper palette on GrapheneOS too), falling back to the Vela
 * teal scheme otherwise.
 */
@Composable
fun VelaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
