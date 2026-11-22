package app.vela.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map's own light or dark, and when "Day and night while navigating" can change anything. */
class MapThemeTest {
    private fun own(mode: MapThemeMode, drive: Boolean = false, night: Boolean) = mapDarkOwn(mode, drive) { night }

    @Test fun `follow leaves the answer to the app`() {
        assertNull(own(MapThemeMode.FOLLOW, night = true))
        assertNull(own(MapThemeMode.FOLLOW, night = false))
    }

    @Test fun `light and dark hold day and night`() {
        assertEquals(false, own(MapThemeMode.LIGHT, night = true))
        assertEquals(true, own(MapThemeMode.DARK, night = false))
    }

    @Test fun `auto goes by the sun`() {
        assertEquals(true, own(MapThemeMode.AUTO, night = true))
        assertEquals(false, own(MapThemeMode.AUTO, night = false))
    }

    @Test fun `day and night while driving goes by the sun in every mode`() {
        for (m in MapThemeMode.entries) {
            assertEquals(m.name, true, own(m, drive = true, night = true))
            assertEquals(m.name, false, own(m, drive = true, night = false))
        }
    }

    @Test fun `the sun is not read for a fixed map`() {
        for (m in listOf(MapThemeMode.LIGHT, MapThemeMode.DARK, MapThemeMode.FOLLOW)) {
            mapDarkOwn(m, false) { throw AssertionError("night read for ${m.name}") }
        }
    }

    @Test fun `the stored mode reads back, and an unknown name is follow`() {
        assertEquals(MapThemeMode.FOLLOW, AppTheme.mapModeFrom(null))
        for (m in MapThemeMode.entries) assertEquals(m, AppTheme.mapModeFrom(m.name))
        // A build from before AUTO reads it the same way, as a name its enum lacks.
        assertEquals(MapThemeMode.FOLLOW, AppTheme.mapModeFrom("SOMETHING_NEWER"))
    }

    @Test fun `the driving toggle shows unless app and map already go by the sun`() {
        assertFalse(navDayNightMatters(ThemeMode.AUTO, MapThemeMode.FOLLOW))
        assertFalse(navDayNightMatters(ThemeMode.AUTO, MapThemeMode.AUTO))
        assertTrue(navDayNightMatters(ThemeMode.AUTO, MapThemeMode.LIGHT))
        assertTrue(navDayNightMatters(ThemeMode.AUTO, MapThemeMode.DARK))
        for (app in ThemeMode.entries.filter { it != ThemeMode.AUTO }) {
            for (map in MapThemeMode.entries) assertTrue("$app $map", navDayNightMatters(app, map))
        }
    }
}
