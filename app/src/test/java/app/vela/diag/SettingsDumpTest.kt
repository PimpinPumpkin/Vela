package app.vela.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDumpTest {
    @Test
    fun `isInternalKey keeps user privacy preferences and drops device-local or coordinate keys`() {
        // Must be backed up
        assertFalse(SettingsDump.isInternalKey("google_session_rotate"))
        assertFalse(SettingsDump.isInternalKey("voice_volume"))
        assertFalse(SettingsDump.isInternalKey("nav_schedule_enabled"))
        assertFalse(SettingsDump.isInternalKey("nav_line_multiplier"))

        // Must stay excluded
        assertTrue(SettingsDump.isInternalKey("google_session_started"))
        assertTrue(SettingsDump.isInternalKey("start_view_loc"))
        assertTrue(SettingsDump.isInternalKey("ui_font_uri"))
        assertTrue(SettingsDump.isInternalKey("ui_font_name"))
        assertTrue(SettingsDump.isInternalKey("diag_enabled"))
        assertTrue(SettingsDump.isInternalKey("routing_offer_done"))
        assertTrue(SettingsDump.isInternalKey("last_fix_lat"))
        assertTrue(SettingsDump.isInternalKey("theme_sun_lat"))
        assertTrue(SettingsDump.isInternalKey("sim_location"))
        assertTrue(SettingsDump.isInternalKey("last_seen_version"))
    }
}
