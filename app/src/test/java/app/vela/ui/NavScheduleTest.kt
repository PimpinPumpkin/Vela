package app.vela.ui

import app.vela.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavScheduleTest {
    @Before
    fun setUp() {
        NavSchedule.resetToggle()
        NavSchedule.resetPhoneToggle()
        NavSchedule.enabled.value = false
        NavSchedule.useSun.value = true
        AppTheme.night.value = false
    }

    @After
    fun tearDown() {
        NavSchedule.resetToggle()
        NavSchedule.resetPhoneToggle()
        NavSchedule.enabled.value = false
        NavSchedule.useSun.value = true
        AppTheme.night.value = false
    }

    @Test
    fun `car override follows AppTheme night until car signal toggles`() {
        NavSchedule.enabled.value = true
        NavSchedule.useSun.value = true
        AppTheme.night.value = true

        // Initial car signal is day, but schedule overrides it to dark after sunset.
        assertTrue(NavSchedule.carDarkOverride(false))
        // Repeated identical car signal does not count as a car-initiated toggle.
        assertTrue(NavSchedule.carDarkOverride(false))

        // Car toggles its own signal -> schedule yields to the car.
        assertTrue(NavSchedule.carDarkOverride(true))
        assertFalse(NavSchedule.carDarkOverride(false))
    }

    @Test
    fun `resetToggle restores car schedule override for the next session`() {
        NavSchedule.enabled.value = true
        NavSchedule.useSun.value = true
        AppTheme.night.value = true

        assertTrue(NavSchedule.carDarkOverride(false))
        assertFalse(NavSchedule.carDarkOverride(true).let { NavSchedule.carDarkOverride(false) })

        NavSchedule.resetToggle()
        assertTrue(NavSchedule.carDarkOverride(false))
    }

    @Test
    fun `phone override holds until system dark mode toggles and resets cleanly`() {
        NavSchedule.enabled.value = true
        NavSchedule.useSun.value = true
        AppTheme.night.value = true

        NavSchedule.onPhoneDarkSignal(false)
        assertTrue(NavSchedule.phoneDarkOverride(false))

        // Phone OS switches to dark -> schedule yields to phone OS afterward.
        NavSchedule.onPhoneDarkSignal(true)
        assertFalse(NavSchedule.phoneDarkOverride(false))

        NavSchedule.resetPhoneToggle()
        NavSchedule.onPhoneDarkSignal(false)
        assertTrue(NavSchedule.phoneDarkOverride(false))
    }

    @Test
    fun `custom time schedule handles overnight and same-day windows`() {
        // Overnight 18:00 to 06:00
        assertTrue(NavSchedule.isNightAtMinutes(22 * 60, 18, 0, 6, 0))
        assertTrue(NavSchedule.isNightAtMinutes(5 * 60 + 30, 18, 0, 6, 0))
        assertFalse(NavSchedule.isNightAtMinutes(12 * 60, 18, 0, 6, 0))

        // Same-day 01:00 to 05:00
        assertTrue(NavSchedule.isNightAtMinutes(3 * 60, 1, 0, 5, 0))
        assertFalse(NavSchedule.isNightAtMinutes(10 * 60, 1, 0, 5, 0))
    }
}
