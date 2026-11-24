package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.RootClockScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootClockVisibilityTest {
    @Test
    fun disabledOptionNeverHidesClock() {
        assertFalse(shouldHideRootClock(false, true, true, RootClockScope.LAUNCHER))
        assertFalse(shouldHideRootClock(false, true, true, RootClockScope.HOME))
    }

    @Test
    fun launcherScopeHidesClockOnEveryLauncherSurface() {
        assertTrue(shouldHideRootClock(true, true, true, RootClockScope.LAUNCHER))
        assertTrue(shouldHideRootClock(true, true, false, RootClockScope.LAUNCHER))
    }

    @Test
    fun homeScopeHidesClockOnlyOnConfiguredHomeSurface() {
        assertTrue(shouldHideRootClock(true, true, true, RootClockScope.HOME))
        assertFalse(shouldHideRootClock(true, true, false, RootClockScope.HOME))
    }

    @Test
    fun clockIsRestoredWhenLauncherLosesFocusOrResumesAnotherApp() {
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.LAUNCHER))
        assertTrue(shouldHideRootClock(true, true, true, RootClockScope.HOME))
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.HOME))
    }

    @Test
    fun homeScopeRequiresTheConfiguredHomePage() {
        assertTrue(isRootClockHomePage(2, 2))
        assertFalse(isRootClockHomePage(0, 2))
        assertFalse(isRootClockHomePage(3, 2))
    }
}
