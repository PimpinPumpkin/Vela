package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperCatalogTest {

    @Test fun `ids are unique`() {
        val ids = PiperCatalog.ALL.map { it.id }
        assertEquals("no duplicate voice ids", ids.size, ids.toSet().size)
    }

    @Test fun `byId round-trips every entry`() {
        for (v in PiperCatalog.ALL) assertEquals(v, PiperCatalog.byId(v.id))
        assertEquals(null, PiperCatalog.byId("en_US-does-not-exist"))
    }

    @Test fun `download url matches the sherpa asset scheme`() {
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
            PiperCatalog.downloadUrl("en_US-lessac-medium"),
        )
        // Every id yields a well-formed vits-piper archive URL.
        for (v in PiperCatalog.ALL) {
            val url = PiperCatalog.downloadUrl(v.id)
            assertTrue(url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-"))
            assertTrue(url.endsWith("${v.id}.tar.bz2"))
        }
    }

    @Test fun `the Google-like voices the user named are present`() {
        for (id in listOf("en_US-lessac-medium", "en_US-hfc_female-medium", "en_US-libritts_r-medium")) {
            assertNotNull("$id must be in the catalog", PiperCatalog.byId(id))
        }
        assertTrue("HFC Female (the default) is recommended", PiperCatalog.byId("en_US-hfc_female-medium")!!.recommended)
    }

    @Test fun `every language has exactly one recommended default voice`() {
        val byLang = PiperCatalog.ALL.groupBy { it.langCode }
        for ((lang, voices) in byLang) {
            val recommended = voices.count { it.recommended }
            assertEquals("language $lang must have exactly one recommended default", 1, recommended)
            // defaultFor returns a recommended voice of that language.
            val def = PiperCatalog.defaultFor(lang)
            assertEquals(lang, def.langCode)
            assertTrue(def.recommended)
        }
    }

    @Test fun `langCode and region derive from the id`() {
        val fr = PiperCatalog.byId("fr_FR-siwis-medium")!!
        assertEquals("fr", fr.langCode)
        assertEquals("FR", fr.region)
        assertEquals("Français", PiperCatalog.languageLabel("fr"))
        val mx = PiperCatalog.byId("es_MX-claude-high")!!
        assertEquals("es", mx.langCode)
        assertEquals("MX", mx.region)
    }

    @Test fun `the default voice is in the catalog and recommended`() {
        val def = PiperCatalog.byId(VelaPiper.DEFAULT_VOICE_ID)
        assertNotNull("the fleet default voice must be a catalog entry", def)
        assertTrue("the default voice should be a recommended nav voice", def!!.recommended)
    }

    @Test fun `the speaker-seed voice is the multi-speaker pack`() {
        // Calibration.defaultVoiceSpeaker tunes VelaPiper.LEGACY_ID (libritts_r), the 904-voice pack.
        val seed = PiperCatalog.byId(VelaPiper.LEGACY_ID)
        assertNotNull(seed)
        assertTrue("libritts_r is multi-speaker", seed!!.multiSpeaker)
        assertEquals(904, seed.numSpeakers)
    }

    @Test fun `sizes and speaker counts are sane`() {
        for (v in PiperCatalog.ALL) {
            assertTrue("${v.id} size", v.sizeMb in 20..200)
            assertTrue("${v.id} speakers", v.numSpeakers >= 1)
            // MULTI ⇔ more than one speaker.
            assertEquals("${v.id} gender/speaker agreement", v.gender == VoiceGender.MULTI, v.numSpeakers > 1)
        }
    }
}
