package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemVoiceBoostTest {
    @Test fun `normal and softer use the engine volume alone, with no boost`() {
        assertEquals(0, SystemVoiceBoost.gainMb(1.0f))
        assertEquals(0, SystemVoiceBoost.gainMb(0.6f))
        assertEquals(0, SystemVoiceBoost.gainMb(0.2f))
        assertEquals(0, SystemVoiceBoost.gainMb(Float.NaN))
        assertEquals(1.0f, SystemVoiceBoost.engineVolume(1.0f), 0f)
        assertEquals(0.6f, SystemVoiceBoost.engineVolume(0.6f), 0f)
    }

    @Test fun `louder and loudest raise the voice by their factor over normal`() {
        // 2000 log10(1.6) = 408.2, 2000 log10(2.2) = 684.8: +4.1 dB and +6.8 dB.
        assertEquals(408, SystemVoiceBoost.gainMb(1.6f))
        assertEquals(685, SystemVoiceBoost.gainMb(2.2f))
        assertEquals(1.0f, SystemVoiceBoost.engineVolume(1.6f), 0f)
        assertEquals(1.0f, SystemVoiceBoost.engineVolume(2.2f), 0f)
    }

    @Test fun `the boost stops at loudest`() {
        assertEquals(685, SystemVoiceBoost.gainMb(3.0f))
        assertEquals(685, SystemVoiceBoost.gainMb(Float.POSITIVE_INFINITY))
    }
}
