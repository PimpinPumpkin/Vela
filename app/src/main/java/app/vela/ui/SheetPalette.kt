package app.vela.ui

import androidx.compose.ui.graphics.Color

/**
 * One Google-style palette shared by every bottom surface — the place sheet, the
 * directions panel, the route chooser, the steps list and the nav bar — so they
 * read as one consistent sheet instead of several differently-coloured cards.
 *
 * Deliberately FIXED (not Material-You tokens) so a wallpaper tint can't wash the
 * text out; choose the variant with the in-app `isAppInDarkTheme()`. Accent colour
 * stays the theme `primary` (teal); traffic uses [TrafficGreen]/[TrafficAmber]/
 * [TrafficRed].
 */
object SheetPalette {
    val Amoled = Color(0xFF000000)   // pure black for OLED power saving
    val Dark = Color(0xFF1F1F1F)     // sheet / card background
    val Light = Color(0xFFFFFFFF)
    val InkDark = Color(0xFFE8EAED)  // primary text
    val InkLight = Color(0xFF202124)
    val DimDark = Color(0xFF9AA0A6)  // secondary text
    val DimLight = Color(0xFF5F6368)
    val RowAmoled = Color(0xFF0D0F11)// inset row / chip background in AMOLED
    val RowDark = Color(0xFF202124)  // inset row / chip background
    val RowLight = Color(0xFFF1F3F4)
    val BorderAmoled = Color(0xFF22252A) // subtle separation line for pure black surfaces

    // Shared traffic-coded colours (route ETAs, the route line, the steps header).
    val TrafficGreen = Color(0xFF1E8E3E)
    val TrafficAmber = Color(0xFFE8923D)
    val TrafficRed = Color(0xFFD93838)

    fun bg(dark: Boolean, amoled: Boolean = false) = when {
        amoled && dark -> Amoled
        dark -> Dark
        else -> Light
    }
    fun ink(dark: Boolean) = if (dark) InkDark else InkLight
    fun dim(dark: Boolean) = if (dark) DimDark else DimLight
    fun row(dark: Boolean, amoled: Boolean = false) = when {
        amoled && dark -> RowAmoled
        dark -> RowDark
        else -> RowLight
    }
}
