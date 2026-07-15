package app.vela.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/**
 * The Material 3 type scale, bumped ~12% across the board - every Vela UI element
 * (place sheet, search rows, directions panel, nav banners, settings) reads a
 * notch bigger / more legible than stock M3, which felt cramped on the map.
 * Map labels are unaffected (those come from the basemap style, not here).
 */
private const val SCALE = 1.12f

private fun TextStyle.scaled(): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * SCALE else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * SCALE else lineHeight,
)

val VelaTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled(),
    )
}
