package app.vela.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private fun ok(text: String) = SettingsBackup.decode(text) as SettingsBackup.Decoded.Ok

    @Test
    fun choicesTipsAndVoiceLevelsAreKept() {
        for ((file, k) in listOf(
            "vela_settings" to "theme_mode", "vela_settings" to "voice_volume", "vela_settings" to "ui_font_name",
            "vela_settings" to "offline_storage", "vela_settings" to "saved_pin_tip_done", "vela_units" to "imperial",
            "vela_onboarding" to "welcome_done", "vela_onboarding" to "donate_prompt_done", "vela_onboarding" to "voice_prompt_done",
            "vela_notices" to "dismissed", "vela_voice_level" to "en_US-amy:0",
        )) assertTrue("$file/$k", SettingsBackup.keep(file, k, places = false))
    }

    @Test
    fun machineStateIsNeverKept() {
        for (k in listOf("first_ms", "last_update_check_ms", "texture_render_auto_ms", "region_autopatch_at",
                "update_dismissed_code", "last_seen_version", "whats_new_after_update", "open_links_build",
                "map_init_crashes", "map_init_inflight", "sim_location", "demo_drive", "google_session_started",
                "browser_viewport", "theme_sun_lat", "theme_sun_lng", "theme_sun_at", "location_prompt_done",
                "notif_prompt_done", "asr_load_strikes_en", "asr_model_bad_en", "")) {
            assertFalse(k, SettingsBackup.keep("vela_settings", k, places = true))
            assertFalse(k, SettingsBackup.keep("vela_onboarding", k, places = true))
        }
    }

    @Test
    fun placesFollowTheSwitch() {
        for ((file, k) in listOf("vela_shortcuts" to "home", "vela_saved_routes" to "routes", "vela_recents" to "q",
                "vela_recent_places" to "p", "vela_settings" to "parking_lat", "vela_settings" to "start_view_loc",
                "vela_settings" to "trip_names")) {
            assertTrue("$file/$k on", SettingsBackup.keep(file, k, places = true))
            assertFalse("$file/$k off", SettingsBackup.keep(file, k, places = false))
        }
    }

    @Test
    fun otherFilesAreNotKept() {
        assertFalse(SettingsBackup.keep("vela_location", "last_lat", places = true))
        assertFalse(SettingsBackup.keep("vela_nav_resume", "label", places = true))
        assertFalse(SettingsBackup.keep("vela_saved", "places", places = true))
    }

    @Test
    fun everyTypeAndTheFontSurviveTheRoundTrip() {
        val settings = mapOf<String, Any?>(
            "theme_mode" to "dark",
            "keep_screen_on_nav" to true,
            "puck_size" to 3,
            "region_update_mode" to 7L,
            "voice_volume" to 1.6f,
            "quick_chips_hidden" to setOf("gas", "food"),
            "parking_lat" to 38.5449f,
        )
        val font = SettingsBackup.Font("Inter", byteArrayOf(0, 1, 2, 3, -1))
        val text = SettingsBackup.encode(
            mapOf("vela_settings" to settings, "vela_units" to mapOf("imperial" to true), "vela_shortcuts" to mapOf("home" to "{}")),
            font, places = true, appVersion = "0.5.1",
        )
        val got = ok(text)
        assertTrue(got.places)
        assertEquals(settings, got.prefs["vela_settings"])
        assertEquals(mapOf("imperial" to true), got.prefs["vela_units"])
        assertEquals(mapOf("home" to "{}"), got.prefs["vela_shortcuts"])
        assertEquals("Inter", got.font?.name)
        assertArrayEquals(font.bytes, got.font?.bytes)
        assertEquals(10, got.count)
    }

    @Test
    fun placesOffLeavesThemOutOfTheFile() {
        val text = SettingsBackup.encode(
            mapOf(
                "vela_settings" to mapOf("theme_mode" to "dark", "parking_lat" to 38.5449f, "trip_names" to "Work|Gym"),
                "vela_shortcuts" to mapOf("home" to "Davis"),
                "vela_recents" to mapOf("q" to "coffee"),
            ),
            font = null, places = false, appVersion = "0.5.1",
        )
        for (s in listOf("38.5449", "Work|Gym", "Davis", "coffee", "vela_shortcuts", "vela_recents")) assertFalse(s, text.contains(s))
        assertTrue(text.contains("theme_mode"))
        assertFalse(ok(text).places)
    }

    @Test
    fun aFileWithoutPlacesCannotSetThemByHand() {
        val text = """{"format":"vela-settings","version":1,"places":false,"files":{
            "vela_settings":{"theme_mode":{"t":"s","v":"dark"},"parking_lat":{"t":"f","v":38.5},"sim_location":{"t":"b","v":true}},
            "vela_shortcuts":{"home":{"t":"s","v":"x"}}}}"""
        val got = ok(text)
        assertEquals(mapOf("theme_mode" to "dark"), got.prefs["vela_settings"])
        assertNull(got.prefs["vela_shortcuts"])
    }

    @Test
    fun aValueOfTheWrongShapeIsSkipped() {
        val text = """{"format":"vela-settings","version":1,"files":{"vela_settings":{
            "puck_size":{"t":"i","v":"big"},
            "voice_volume":{"t":"zz","v":1},
            "theme_mode":{"t":"s","v":"dark"}}},
            "font":{"name":"","data":"AAEC"}}"""
        val got = ok(text)
        assertEquals(mapOf("theme_mode" to "dark"), got.prefs["vela_settings"])
        assertNull(got.font)
    }

    @Test
    fun aSettingLeftAtItsDefaultGoesBackToItOnImport() {
        // Exported with "keep screen on" never touched (so not stored), then turned off.
        val file = ok(SettingsBackup.encode(mapOf("vela_settings" to mapOf("theme_mode" to "dark")), null, places = false, appVersion = "0.5.1"))
        val phone = mapOf("vela_settings" to setOf("theme_mode", "keep_screen_on_nav", "sim_location", "parking_lat"))
        // keep_screen_on_nav is removed, so it reads its default (on) again; the simulator switch
        // is machine state and the parking spot is a place the file left out: both stay.
        assertEquals(mapOf("vela_settings" to setOf("keep_screen_on_nav")), SettingsBackup.resets(phone, file))
    }

    @Test
    fun placesAreResetOnlyWhenTheFileHasThem() {
        val phone = mapOf("vela_shortcuts" to setOf("home", "work"), "vela_settings" to setOf("parking_lat"))
        val without = ok(SettingsBackup.encode(emptyMap(), null, places = false, appVersion = "0.5.1"))
        assertEquals(emptyMap<String, Set<String>>(), SettingsBackup.resets(phone, without))
        val with = ok(SettingsBackup.encode(mapOf("vela_shortcuts" to mapOf("home" to "{}")), null, places = true, appVersion = "0.5.1"))
        assertEquals(
            mapOf("vela_settings" to setOf("parking_lat"), "vela_shortcuts" to setOf("work")),
            SettingsBackup.resets(phone, with),
        )
    }

    @Test
    fun otherFilesAreRecognized() {
        assertEquals(SettingsBackup.Decoded.Unreadable, SettingsBackup.decode("not json"))
        assertEquals(SettingsBackup.Decoded.WrongFormat, SettingsBackup.decode("""{"places":[]}"""))
        assertEquals(SettingsBackup.Decoded.TooNew, SettingsBackup.decode("""{"format":"vela-settings","version":99,"files":{}}"""))
        assertEquals(0, ok("""{"format":"vela-settings","version":1,"files":{"vela_other":{"x":{"t":"b","v":true}}}}""").count)
    }
}
