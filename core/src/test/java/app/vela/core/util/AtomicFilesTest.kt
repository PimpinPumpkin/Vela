package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class AtomicFilesTest {
    @Test
    fun `replaces the file and leaves no temp behind`() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val f = java.io.File(dir, "index.json")
        AtomicFiles.writeText(f, "[1]")
        AtomicFiles.writeText(f, "[1,2]")
        assertEquals("[1,2]", f.readText())
        assertFalse(java.io.File(dir, "index.json.tmp").exists())
        dir.deleteRecursively()
    }
}
