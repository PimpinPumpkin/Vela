package app.vela.ui.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsRoutesTest {
    @Test
    fun `main map cannot match the map settings route ignoring case`() {
        assertNotEquals(MAP_ROUTE.lowercase(Locale.ROOT), SettingsSection.MAP.route.lowercase(Locale.ROOT))
    }

    @Test
    fun `every destination remains distinct under case insensitive navigation matching`() {
        val routes = listOf(MAP_ROUTE) + SettingsSection.entries.map { it.route }
        assertEquals(routes.size, routes.map { it.lowercase(Locale.ROOT) }.toSet().size)
    }

    @Test
    fun `routes do not change with the device language`() {
        val original = Locale.getDefault()
        val routes = SettingsSection.entries.map { it.route }
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(routes, SettingsSection.entries.map { it.route })
        } finally {
            Locale.setDefault(original)
        }
    }
}
