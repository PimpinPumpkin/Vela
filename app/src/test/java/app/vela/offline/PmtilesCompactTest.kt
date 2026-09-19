package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compaction, against a real patched archive rather than a mock.
 *
 * `patched.pmtiles` is built by `scripts/pmtiles-test-fixture.py`: a small archive, a second
 * revision of it, a patch between the two produced by the real producer, applied by the real
 * applier. So it carries dead bytes exactly the way a phone's archive does after an update, and
 * the claim under test is the one the whole delta design rests on - that a rewritten archive holds
 * what a fresh download holds.
 */
class PmtilesCompactTest {

    private fun fixture(): File {
        val bytes = javaClass.getResourceAsStream("/patched.pmtiles")!!.use { it.readBytes() }
        return File.createTempFile("vela-compact", ".pmtiles").apply { writeBytes(bytes) }
    }

    @Test fun `compaction drops the dead bytes and keeps every tile`() {
        val file = fixture()
        val before = file.length()
        val entriesBefore = PmtilesReader.entries(file)
        val fingerprintBefore = PmtilesPatch.fingerprint(file)
        assertNotNull(entriesBefore)
        assertNotNull(fingerprintBefore)

        val out = PmtilesCompact.compact(file)
        assertTrue("compaction refused: $out", out is PmtilesCompact.Outcome.Done)
        out as PmtilesCompact.Outcome.Done

        assertEquals(before, out.beforeBytes)
        assertEquals(file.length(), out.afterBytes)
        assertTrue("the file must get smaller: $before -> ${file.length()}", file.length() < before)
        assertEquals(
            "a compacted archive holds exactly what it held before",
            fingerprintBefore, PmtilesPatch.fingerprint(file),
        )
        val after = PmtilesReader.entries(file)!!
        assertEquals(entriesBefore!!.size, after.size)
        assertEquals(entriesBefore.map { it.id }, after.map { it.id })
        assertEquals(entriesBefore.map { it.length }, after.map { it.length })
        assertEquals(entriesBefore.map { it.runLength }, after.map { it.runLength })
    }

    @Test fun `a compacted archive has no dead space left to reclaim`() {
        val file = fixture()
        PmtilesCompact.compact(file)
        val once = file.length()
        val again = PmtilesCompact.compact(file)
        assertTrue(again is PmtilesCompact.Outcome.Done)
        assertEquals("compacting twice must change nothing", once, file.length())
    }

    @Test fun `a file that is not an archive is refused, and left alone`() {
        val junk = File.createTempFile("vela-compact", ".pmtiles").apply { writeBytes(ByteArray(300)) }
        val before = junk.readBytes()
        assertTrue(PmtilesCompact.compact(junk) is PmtilesCompact.Outcome.Refused)
        assertArrayEqualsBytes(before, junk.readBytes())
    }

    private fun assertArrayEqualsBytes(a: ByteArray, b: ByteArray) =
        assertEquals("the file must not be touched by a refusal", a.toList(), b.toList())
}
