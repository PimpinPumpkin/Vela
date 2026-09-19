package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The delta applier. [appliesARealPatch] is the one that matters: it runs a patch built by
 * `scripts/pmtiles-make-patch.py` against the archive it was built from, and checks the archive
 * ends up with the fingerprint of a fresh download. Producer and applier are two implementations
 * of one format, so the only honest test is to make them meet:
 *
 *   python3 scripts/pmtiles-make-patch.py old.pmtiles new.pmtiles /tmp/p.vpatch
 *   ./gradlew :app:testReleaseUnitTest --tests '*PmtilesPatchTest*' --rerun-tasks \
 *       -DvelaArchive=/tmp/old-copy.pmtiles -DvelaPatch=/tmp/p.vpatch -DvelaFingerprint=<new's>
 */
class PmtilesPatchTest {

    @Test fun `a patch for another revision is refused before anything is written`() {
        val archive = File.createTempFile("vela", ".pmtiles").apply { writeBytes(ByteArray(200)) }
        val patch = File.createTempFile("vela", ".vpatch").apply { writeBytes("nonsense".toByteArray()) }
        val before = archive.length()
        val out = PmtilesPatch.apply(archive, patch)
        assertTrue(out is PmtilesPatch.Outcome.Refused)
        assertEquals("the archive must not be touched by a refusal", before, archive.length())
    }

    /** Fingerprint one archive and print it, for checking Kotlin against
     *  `python3 scripts/velapmtiles.py <archive>`. The two must agree or the acceptance check is
     *  worthless. Runs when velaArchive is given without velaPatch. */
    @Test fun fingerprintRealArchive() {
        val path = System.getProperty("velaArchive")
        assumeTrue("set -DvelaArchive (and no -DvelaPatch)", path != null && System.getProperty("velaPatch") == null)
        val f = File(path!!)
        assumeTrue("no such file", f.exists())
        println("entries: " + PmtilesReader.entries(f)?.size)
        println("fingerprint: " + PmtilesPatch.fingerprint(f))
    }

    @Test fun appliesARealPatch() {
        val archivePath = System.getProperty("velaArchive")
        val patchPath = System.getProperty("velaPatch")
        assumeTrue("set -DvelaArchive and -DvelaPatch to run this", archivePath != null && patchPath != null)
        val archive = File(archivePath!!)
        val patch = File(patchPath!!)
        assumeTrue("no such files", archive.exists() && patch.exists())

        val plan = PmtilesPatch.read(patch)
        assertNotNull("the patch header must parse", plan)
        println("patch: rev ${plan!!.fromRev} -> ${plan.toRev}, ${plan.tileLengths.size} tiles, " +
            "${plan.tileLengths.sum() / 1024} KB of tile data, dead ${plan.deadBytes / 1024} KB")

        val before = archive.length()
        val out = PmtilesPatch.apply(archive, patch)
        println("outcome: $out, archive ${before / 1024} KB -> ${archive.length() / 1024} KB")
        assertTrue("apply should succeed: $out", out is PmtilesPatch.Outcome.Applied)

        val want = System.getProperty("velaFingerprint") ?: plan.toFingerprint
        assertEquals("a patched archive must hold what a fresh download holds", want, PmtilesPatch.fingerprint(archive))
    }
}
