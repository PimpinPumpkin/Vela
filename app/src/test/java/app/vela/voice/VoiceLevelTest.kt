package app.vela.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance volume for the Vela voice ([VoiceLevel]). Kokoro's default voice measured a peak of
 *  0.29 and an RMS of 0.058 on a Pixel 4a. */
class VoiceLevelTest {
    @Test fun `normal brings a quiet voice up to the target peak`() {
        val g = VoiceLevel.gain(0.29f, 1f)
        assertEquals(VoiceLevel.TARGET_PEAK, 0.29f * g, 1e-4f)
    }

    @Test fun `a voice that is already loud is not lowered, and a silent one is not amplified`() {
        assertEquals(1f, VoiceLevel.gain(0.95f, 1f), 1e-6f)
        assertEquals(1f, VoiceLevel.gain(0.001f, 1f), 1e-6f)
        assertEquals(VoiceLevel.MAX_LIFT, VoiceLevel.gain(0.05f, 1f), 1e-6f)
    }

    @Test fun `the setting scales from the leveled voice`() {
        val normal = VoiceLevel.gain(0.29f, 1f)
        assertEquals(normal * 0.6f, VoiceLevel.gain(0.29f, 0.6f), 1e-5f)
        assertEquals(normal * 2.2f, VoiceLevel.gain(0.29f, 2.2f), 1e-5f)
    }

    @Test fun `the limiter leaves quiet samples alone and never passes full scale`() {
        assertEquals(0.5f, VoiceLevel.limit(0.5f), 0f)
        assertEquals(-0.8f, VoiceLevel.limit(-0.8f), 0f)
        var last = 0.8f
        for (x in listOf(0.9f, 1.0f, 1.2f)) {
            val y = VoiceLevel.limit(x)
            assertTrue("$x -> $y", y > last && y < 1f)
            assertEquals(-y, VoiceLevel.limit(-x), 1e-6f)
            last = y
        }
        assertTrue(VoiceLevel.limit(5f) <= 1f && VoiceLevel.limit(-5f) >= -1f)
    }

    @Test fun `loudest is louder than louder, which is louder than normal`() {
        // A sine at the voice's level stands in for speech.
        fun rms(setting: Float): Double {
            val chunk = FloatArray(2400) { (0.29 * Math.sin(it * 0.05)).toFloat() }
            VoiceLevel.apply(chunk, VoiceLevel.gain(VoiceLevel.peak(chunk), setting))
            return Math.sqrt(chunk.sumOf { (it * it).toDouble() } / chunk.size)
        }
        val softer = rms(0.6f); val normal = rms(1f); val louder = rms(1.6f); val loudest = rms(2.2f)
        assertTrue(softer < normal && normal < louder && louder < loudest)
        assertTrue("normal is about three times the raw level", normal > 0.29 / Math.sqrt(2.0) * 2.5)
    }
}
