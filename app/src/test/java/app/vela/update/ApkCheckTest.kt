package app.vela.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ApkCheckTest {
    private fun file(bytes: ByteArray) = File.createTempFile("vela", ".apk").apply { deleteOnExit(); writeBytes(bytes) }
    private val apk = "PK".toByteArray() + ByteArray(1000) { (it % 7).toByte() }

    @Test fun theRightFilePasses() {
        val f = file(apk)
        assertNull(ApkCheck.problem(f, apk.size.toLong(), ApkCheck.sha256(f)))
        assertNull("no size and no checksum published: the zip check alone", ApkCheck.problem(f, 0, null))
    }

    @Test fun aShortDownloadIsCaughtBySize() {
        assertNotNull(ApkCheck.problem(file(apk.copyOf(600)), apk.size.toLong(), null))
    }

    @Test fun aChangedByteIsCaughtByTheChecksum() {
        val good = ApkCheck.sha256(file(apk))
        val bad = apk.copyOf().also { it[500] = 99 }
        assertNotNull(ApkCheck.problem(file(bad), apk.size.toLong(), good))
    }

    @Test fun anErrorPageIsNotAnApk() {
        assertNotNull(ApkCheck.problem(file("<html>rate limited</html>".toByteArray()), 0, null))
    }

    @Test fun githubsDigestFieldIsRead() {
        val hex = "774e7cb3a4b7f8c899e862e70d8c899b11e1e84d1fb74d90554817dcc06dc00c"
        assertEquals(hex, ApkCheck.digestOf("sha256:$hex"))
        assertNull(ApkCheck.digestOf(""))
        assertNull(ApkCheck.digestOf("md5:abc"))
    }
}
