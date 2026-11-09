package app.vela.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterTargetsTest {
    // What a plain app lists on a DiLink 5.0 head unit (the composite output is hidden from it)
    // and on DiLink 5.1 (listed), plus displays that must never be taken for the cluster.
    private val dilink50 = listOf("Built-in Screen", "shared_fission_bg_XDJAScreenProjection_0", "shared_fission_bg_XDJAScreenProjection_1")
    private val dilink51 = listOf("ivi", "fission_bg_XDJAScreenProjection") + dilink50.drop(1)
    private val phone = listOf("Built-in screen", "HDMI Screen", "Overlay #1")

    @Test fun `only known cluster inputs are offered, the idle card first`() {
        val expect = listOf(ClusterTargets.DILINK5_SIDE, ClusterTargets.DILINK5_FULL)
        assertEquals(expect, ClusterTargets.candidates(dilink50))
        // The composite output is not an input: a Presentation there is cropped.
        assertEquals(expect, ClusterTargets.candidates(dilink51))
        assertEquals(listOf(ClusterTargets.DILINK4), ClusterTargets.candidates(listOf("Built-in Screen", "fission_bg_xdjaVirtualSurface")))
    }

    @Test fun `a phone's external screens are never offered`() {
        assertEquals(emptyList<String>(), ClusterTargets.candidates(phone))
    }

    @Test fun `the test rig switch offers any presentation display after the known ones`() {
        assertEquals(phone, ClusterTargets.candidates(phone, anyPresentation = true))
        assertEquals(ClusterTargets.DILINK5_SIDE, ClusterTargets.candidates(dilink50, anyPresentation = true).first())
    }

    @Test fun `the pick is the stored name, and only while it exists`() {
        assertEquals(ClusterTargets.DILINK5_FULL, ClusterTargets.pick(ClusterTargets.DILINK5_FULL, dilink50))
        // Gone for a moment: nothing, not the next display on the list.
        assertNull(ClusterTargets.pick(ClusterTargets.DILINK5_FULL, listOf("Built-in Screen", ClusterTargets.DILINK5_SIDE)))
        assertNull(ClusterTargets.pick(null, dilink50))
        assertNull(ClusterTargets.pick("HDMI Screen", phone))
    }

    @Test fun `the public display wins and the adb route is only a fallback`() {
        assertEquals(ClusterRoute.PRESENTATION, clusterRoute(publicDisplay = true, adbRoute = true))
        assertEquals(ClusterRoute.PRESENTATION, clusterRoute(publicDisplay = true, adbRoute = false))
        assertEquals(ClusterRoute.ADB, clusterRoute(publicDisplay = false, adbRoute = true))
        assertEquals(ClusterRoute.NONE, clusterRoute(publicDisplay = false, adbRoute = false))
    }
}
