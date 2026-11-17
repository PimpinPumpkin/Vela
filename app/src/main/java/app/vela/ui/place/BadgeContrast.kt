package app.vela.ui.place

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.vela.ui.SheetPalette
import kotlin.math.max
import kotlin.math.min

/**
 * A transit line's own color kept as the agency paints it, with a thin outline when it would
 * melt into the sheet behind it (issue #888: a white or pale yellow line on the light sheet, a
 * navy or black one on the dark sheet). Measured against the sheet and the inset row color of
 * the theme, since a badge sits on either.
 */
object BadgeContrast {
    /** Under this contrast ratio (WCAG's formula) with the sheet or an inset row, the badge gets
     *  an outline. Pale yellow on white is about 1.4, light pink 1.6; orange (2.0) and light
     *  green (2.1) read fine without one. */
    const val MIN_RATIO = 1.7f

    fun ratio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    fun needsOutline(line: Color, dark: Boolean): Boolean {
        val behind = if (dark) listOf(SheetPalette.Dark, SheetPalette.RowDark, SheetPalette.Amoled)
        else listOf(SheetPalette.Light, SheetPalette.RowLight)
        return behind.any { ratio(line, it) < MIN_RATIO }
    }
}
