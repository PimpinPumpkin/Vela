package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStartTest {
    // "In 500 feet," then "turn left onto Oak Street": 12 letters, then 25 more.
    @Test fun `a fast voice starts on its first phrase`() {
        assertTrue(StreamStart.ready(madeAudioMs = 900.0, charsLeft = 25, nextChars = 25, renderMsPerChar = 5.0, audioMsPerChar = 75.0))
    }

    @Test fun `a voice that renders about as fast as it speaks waits until the next phrase cannot run it dry`() {
        // 90 ms a letter to render, 83 to play: the next 25 letters take 2.25 s to make.
        assertFalse(StreamStart.ready(madeAudioMs = 1000.0, charsLeft = 25, nextChars = 25, renderMsPerChar = 90.0, audioMsPerChar = 83.0))
        assertTrue(StreamStart.ready(madeAudioMs = 2900.0, charsLeft = 25, nextChars = 25, renderMsPerChar = 90.0, audioMsPerChar = 83.0))
    }

    @Test fun `a voice slower than it speaks also covers the gap it falls behind by`() {
        // 150 ms a letter to render, 80 to play: over 60 letters it falls 4.2 s behind.
        assertFalse(StreamStart.ready(madeAudioMs = 4000.0, charsLeft = 60, nextChars = 10, renderMsPerChar = 150.0, audioMsPerChar = 80.0))
        assertTrue(StreamStart.ready(madeAudioMs = 5300.0, charsLeft = 60, nextChars = 10, renderMsPerChar = 150.0, audioMsPerChar = 80.0))
        assertTrue("nothing left to make", StreamStart.ready(1.0, 0, 0, 150.0, 80.0))
    }

    @Test fun `a per letter figure is a running average`() {
        assertEquals(10.0, StreamStart.perChar(null, 100.0, 10), 1e-9)
        assertEquals(10.0 + (20.0 - 10.0) * 0.3, StreamStart.perChar(10.0, 200.0, 10), 1e-9)
    }
}
