package app.vela.replay

import android.content.ContextWrapper
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TripStoreResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A fresh store over the same folder, as a new process sees it. */
    private fun store() = TripStore(object : ContextWrapper(null) { override fun getFilesDir(): File = tmp.root })

    @Test fun `a drive resumed after the process died records into the trip it left`() {
        val before = store()
        val id = before.startTrip("Davis", LatLng(38.5449, -121.7405), 1_000L)!!
        before.note("K", "before the restart")
        val after = store()
        assertFalse(after.isRecording)
        assertTrue(after.resumeTrip(id))
        assertEquals(id, after.activeId)
        after.note("K", "after the restart")
        val text = File(tmp.root, "trips/$id.csv").readText()
        assertTrue(text, text.startsWith("META,Davis,1000,"))
        assertTrue(text, text.indexOf("before the restart") in 0 until text.indexOf("after the restart"))
        assertEquals(listOf(id), after.list().map { it.id })
    }

    @Test fun `nothing is resumed over a recording trip or from a file that is gone`() {
        val s = store()
        assertFalse(s.resumeTrip("trip_5"))
        val id = s.startTrip("Davis", null, 2_000L)!!
        assertFalse(s.resumeTrip(id))
        assertEquals(id, s.activeId)
    }

    @Test fun `a drive's nav trace is kept with its trip and goes when the trip does`() {
        val nav = app.vela.diag.NavTrace
        nav.enabled.value = true
        try {
            val s = store()
            val id = s.startTrip("Davis", null, 3_000L)!!
            repeat(5) { s.note("K", "fix $it") }
            for (i in 0 until 400) nav.record(i * 16L, i * 0.2, 12.0, 40.0, 90f, 90f, 90.0, 0.016f, Float.NaN, cameraTarget = 91f)
            s.finishTrip(minFixes = 0)
            nav.awaitWrites()
            val trace = nav.tripTrace(ContextWrapperFor(tmp.root), id)!!
            val lines = trace.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertEquals(app.vela.core.replay.TripNavTrace.COLUMNS, lines.first())
            assertEquals("every row reaches the file once the trip ends", 400, lines.size - 1)
            s.delete(id)
            nav.awaitWrites()
            assertFalse(File(tmp.root, "navtrace/$id.csv").exists())
        } finally {
            nav.enabled.value = false; nav.clear()
        }
    }

    @Test fun `a trip too short to keep takes its nav trace with it`() {
        val nav = app.vela.diag.NavTrace
        nav.enabled.value = true
        try {
            val s = store()
            val id = s.startTrip("Davis", null, 4_000L)!!
            for (i in 0 until 400) nav.record(i * 16L, i * 0.2, 12.0, 40.0, 90f, 90f, 90.0, 0.016f, Float.NaN)
            s.finishTrip()
            nav.awaitWrites()
            assertFalse(File(tmp.root, "navtrace/$id.csv").exists())
        } finally {
            nav.enabled.value = false; nav.clear()
        }
    }

    private class ContextWrapperFor(val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
    }
}
