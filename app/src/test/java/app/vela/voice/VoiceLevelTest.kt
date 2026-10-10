package app.vela.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance volume for the Vela voice ([VoiceLevel]). Kokoro's default voice measured an RMS of
 *  0.052 to 0.058 and peaks of 0.23 to 0.42 on a Pixel 4a. */
class VoiceLevelTest {
    private fun speech(level: Double, n: Int = 2400, phase: Double = 0.0) = FloatArray(n) { (level * Math.sin(it * 0.05 + phase)).toFloat() }

    @Test fun `normal brings a quiet voice up to the target level`() {
        val g = VoiceLevel.gain(0.055f, 1f)
        assertEquals(VoiceLevel.TARGET_RMS, 0.055f * g, 1e-4f)
    }

    @Test fun `a voice that is already loud is not lowered, and an unmeasured one is left alone`() {
        assertEquals(1f, VoiceLevel.gain(0.3f, 1f), 1e-6f)
        assertEquals(1f, VoiceLevel.gain(0f, 1f), 1e-6f)
        assertEquals(VoiceLevel.MAX_LIFT, VoiceLevel.gain(0.005f, 1f), 1e-6f)
    }

    @Test fun `the setting scales from the leveled voice`() {
        val normal = VoiceLevel.gain(0.055f, 1f)
        assertEquals(normal * 0.6f, VoiceLevel.gain(0.055f, 0.6f), 1e-5f)
        assertEquals(normal * 2.2f, VoiceLevel.gain(0.055f, 2.2f), 1e-5f)
    }

    @Test fun `the level holds steady while the loudest sample keeps rising`() {
        // A minute of speech at one level, with a louder syllable now and then: the peak climbs,
        // the gain does not follow it down.
        val m = VoiceLevel.Meter()
        m.add(speech(0.08))
        val first = VoiceLevel.gain(m.rms, 1f)
        repeat(40) { k ->
            val line = speech(0.08, phase = k.toDouble())
            if (k % 5 == 0) for (i in 0 until 12) line[100 + i] = 0.42f
            m.add(line)
        }
        val later = VoiceLevel.gain(m.rms, 1f)
        assertEquals(first, later, first * 0.06f)
    }

    @Test fun `pauses are not measured, and the level survives being stored`() {
        val m = VoiceLevel.Meter()
        m.add(speech(0.08))
        val rms = m.rms
        m.add(FloatArray(48_000)) // a long pause
        assertEquals(rms, m.rms, 0f)
        val back = VoiceLevel.Meter.decode(m.encode())
        assertEquals(m.rms, back.rms, 1e-6f)
        assertEquals(0f, VoiceLevel.Meter.decode("junk").rms, 0f)
        assertEquals(0f, VoiceLevel.Meter.decode(null).rms, 0f)
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
        fun rms(setting: Float): Double {
            val chunk = speech(0.08)
            val m = VoiceLevel.Meter().also { it.add(chunk) }
            VoiceLevel.apply(chunk, VoiceLevel.gain(m.rms, setting))
            return Math.sqrt(chunk.sumOf { (it * it).toDouble() } / chunk.size)
        }
        val softer = rms(0.6f); val normal = rms(1f); val louder = rms(1.6f); val loudest = rms(2.2f)
        assertTrue(softer < normal && normal < louder && louder < loudest)
        assertEquals("normal lands on the target", VoiceLevel.TARGET_RMS.toDouble(), normal, 0.005)
    }
}
