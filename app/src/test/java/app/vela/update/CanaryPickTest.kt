package app.vela.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanaryPickTest {
    private fun info(code: Int, commit: String?) = SelfUpdater.UpdateInfo("0.5.$code", 2000 + code, "", 0, "", null, commit)

    private val canarySha = "072f9b1870a274429af22fdec74dd67a5a7aadc5"
    private val nightlySha = "3e4bef74266d60d336381fcb8626b417392baedb"
    private val canary = info(2768, canarySha)

    @Test fun `a newer nightly that lacks canary's commit leaves the phone on canary`() {
        val asked = mutableListOf<Pair<String, String>>()
        val picked = canaryPick(canary, info(2782, nightlySha)) { n, c -> asked += n to c; false }
        assertEquals(canary, picked)
        assertEquals(listOf(nightlySha to canarySha), asked)
    }

    @Test fun `a newer nightly that holds canary's commit is offered`() {
        val nightly = info(2790, nightlySha)
        assertEquals(nightly, canaryPick(canary, nightly) { _, _ -> true })
    }

    @Test fun `an older nightly is not compared`() {
        assertEquals(canary, canaryPick(canary, info(2741, nightlySha)) { _, _ -> error("not asked") })
        assertEquals(canary, canaryPick(canary, null) { _, _ -> error("not asked") })
    }

    @Test fun `anything unknown keeps canary`() {
        assertEquals(canary, canaryPick(canary, info(2782, nightlySha)) { _, _ -> null })
        assertEquals(canary, canaryPick(canary, info(2782, null)) { _, _ -> true })
        val noCommit = info(2768, null)
        assertEquals(noCommit, canaryPick(noCommit, info(2782, nightlySha)) { _, _ -> true })
    }

    @Test fun `no readable canary release offers nothing`() {
        assertNull(canaryPick(null, info(2782, nightlySha)) { _, _ -> true })
    }

    @Test fun `compare statuses read with the nightly as base`() {
        assertEquals(true, compareHolds("behind"))
        assertEquals(true, compareHolds("identical"))
        assertEquals(false, compareHolds("ahead"))
        assertEquals(false, compareHolds("diverged"))
        assertNull(compareHolds(""))
        assertNull(compareHolds(null))
    }
}
