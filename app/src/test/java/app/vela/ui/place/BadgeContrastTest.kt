package app.vela.ui.place

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeContrastTest {
    @Test fun aWhiteOrPaleLineOnTheLightSheetIsOutlined() {
        assertTrue(BadgeContrast.needsOutline(Color.White, dark = false))
        assertTrue(BadgeContrast.needsOutline(Color(0xFFFFD600), dark = false)) // pale yellow
        assertTrue(BadgeContrast.needsOutline(Color(0xFFF8BBD0), dark = false)) // light pink
    }

    @Test fun aColorThatReadsOnTheSheetKeepsNoOutline() {
        assertFalse(BadgeContrast.needsOutline(Color(0xFFF7A800), dark = false)) // orange
        assertFalse(BadgeContrast.needsOutline(Color(0xFF1A73E8), dark = false)) // blue
        assertFalse(BadgeContrast.needsOutline(Color(0xFFFFD600), dark = true))
        assertFalse(BadgeContrast.needsOutline(Color.White, dark = true))
    }

    @Test fun aNavyOrBlackLineOnTheDarkSheetIsOutlined() {
        assertTrue(BadgeContrast.needsOutline(Color(0xFF001E62), dark = true))
        assertTrue(BadgeContrast.needsOutline(Color.Black, dark = true))
        assertFalse(BadgeContrast.needsOutline(Color(0xFF001E62), dark = false))
    }
}
