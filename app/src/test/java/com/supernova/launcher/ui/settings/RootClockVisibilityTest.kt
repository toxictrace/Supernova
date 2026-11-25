package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.RootClockMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootClockVisibilityTest {
    @Test
    fun noHideNeverHidesClock() {
        assertFalse(shouldHideRootClock(RootClockMode.NO_HIDE, true, true))
        assertFalse(shouldHideRootClock(RootClockMode.NO_HIDE, true, false))
        assertFalse(shouldHideRootClock(RootClockMode.NO_HIDE, false, true))
    }

    @Test
    fun launcherModeHidesClockOnEveryLauncherSurface() {
        assertTrue(shouldHideRootClock(RootClockMode.LAUNCHER, true, true))
        assertTrue(shouldHideRootClock(RootClockMode.LAUNCHER, true, false))
    }

    @Test
    fun homeModeHidesClockOnlyOnHomeSurface() {
        assertTrue(shouldHideRootClock(RootClockMode.HOME, true, true))
        assertFalse(shouldHideRootClock(RootClockMode.HOME, true, false))
    }

    @Test
    fun clockIsRestoredWhenLauncherIsNoLongerResumed() {
        assertFalse(shouldHideRootClock(RootClockMode.LAUNCHER, false, true))
        assertFalse(shouldHideRootClock(RootClockMode.HOME, false, true))
    }

    @Test
    fun homeScopeRequiresTheConfiguredHomePage() {
        assertTrue(isRootClockHomePage(2, 2))
        assertFalse(isRootClockHomePage(0, 2))
        assertFalse(isRootClockHomePage(3, 2))
    }
}
