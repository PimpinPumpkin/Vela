package app.vela.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkChoiceTest {
    private val split = listOf(
        "vela-maps-all.apk", "vela-maps-arm64.apk", "vela-maps-armv7.apk",
        "vela-maps-x86.apk", "vela-maps-x86_64.apk",
    )
    private val arm64Phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test fun `each phone gets its own chip type`() {
        assertEquals("vela-maps-arm64.apk", ApkChoice.pick(split, arm64Phone))
        assertEquals("vela-maps-arm64.apk", ApkChoice.pick(split, listOf("arm64-v8a")))
        assertEquals("vela-maps-armv7.apk", ApkChoice.pick(split, listOf("armeabi-v7a", "armeabi")))
        assertEquals("vela-maps-x86_64.apk", ApkChoice.pick(split, listOf("x86_64", "x86", "arm64-v8a")))
        assertEquals("vela-maps-x86.apk", ApkChoice.pick(split, listOf("x86")))
    }

    @Test fun `an unknown chip falls back to the all-in-one APK`() {
        assertEquals("vela-maps-all.apk", ApkChoice.pick(split, listOf("riscv64")))
    }

    @Test fun `a release with one APK is taken whatever its name`() {
        assertEquals("vela-maps-v0.4.1802.apk", ApkChoice.pick(listOf("vela-maps-v0.4.1802.apk"), arm64Phone))
        assertEquals("vela-maps-canary.apk", ApkChoice.pick(listOf("vela-maps-canary.apk", "notes.txt"), arm64Phone))
        assertNull(ApkChoice.pick(listOf("notes.txt"), arm64Phone))
    }

    @Test fun `the canary names work the same way`() {
        val canary = listOf("vela-maps-canary-all.apk", "vela-maps-canary-arm64.apk", "vela-maps-canary-armv7.apk")
        assertEquals("vela-maps-canary-arm64.apk", ApkChoice.pick(canary, arm64Phone))
        assertEquals("vela-maps-canary-all.apk", ApkChoice.pick(canary, listOf("x86_64")))
    }

    @Test fun `old updaters see the all-in-one APK first`() {
        // GitHub lists assets by name; an updater before ApkChoice takes the first .apk.
        assertEquals("vela-maps-all.apk", split.sorted().first())
        assertEquals("vela-maps-canary-all.apk", listOf("vela-maps-canary-arm64.apk", "vela-maps-canary-all.apk", "vela-maps-canary-armv7.apk").sorted().first())
    }

    @Test fun `version codes fold onto the legacy scale`() {
        assertEquals(3802, legacyCode(3802))
        assertEquals(3810, legacyCode(38100))
        assertEquals(3810, legacyCode(38102))
        assertEquals(3810, legacyCode(38104))
        assertEquals(1, legacyCode(1))
    }
}
