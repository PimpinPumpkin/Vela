package app.vela.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTagsTest {
    @Test fun `app tags come back newest run first with their real names`() {
        val refs = listOf(
            "refs/tags/v0.4.2360", "refs/tags/v0.4.2374", "refs/tags/v0.3.812", "refs/tags/canary",
            "refs/tags/v0.4.2372", "refs/tags/obf-regions", "refs/tags/v0.4.2374-rc",
        )
        assertEquals(
            listOf(2374 to "v0.4.2374", 2372 to "v0.4.2372", 2360 to "v0.4.2360", 812 to "v0.3.812"),
            appReleaseTags(refs),
        )
    }

    @Test fun `a new version line is found without a code change`() {
        val refs = listOf("refs/tags/v0.4.2374", "refs/tags/v0.5.2380", "refs/tags/v0.5.2391")
        assertEquals(listOf(2391 to "v0.5.2391", 2380 to "v0.5.2380", 2374 to "v0.4.2374"), appReleaseTags(refs))
    }

    @Test fun `a run tagged under two lines keeps the higher line`() {
        assertEquals(listOf(2380 to "v0.5.2380"), appReleaseTags(listOf("refs/tags/v0.4.2380", "refs/tags/v0.5.2380")))
    }
}
