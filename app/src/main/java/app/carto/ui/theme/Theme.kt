package app.carto.ui.theme

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
    primary = CartoTeal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = CartoTealDark,
    tertiary = CartoAmber,
)

private val DarkColors = darkColorScheme(
    primary = CartoTealLight,
    secondary = CartoTeal,
    tertiary = CartoAmber,
)

/**
 * App theme. Honours Material You dynamic colour on Android 12+ (so Carto picks
 * up the user's wallpaper palette on GrapheneOS too), falling back to the Carto
 * teal scheme otherwise.
 */
@Composable
fun CartoTheme(
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
    MaterialTheme(colorScheme = colorScheme, typography = CartoTypography, content = content)
}
