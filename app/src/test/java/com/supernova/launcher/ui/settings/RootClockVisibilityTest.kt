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
    fun homeScopeHidesClockOnlyOnHomeSurface() {
        assertTrue(shouldHideRootClock(true, true, true, RootClockScope.HOME))
        assertFalse(shouldHideRootClock(true, true, false, RootClockScope.HOME))
    }

    @Test
    fun inactiveLauncherAlwaysRestoresClock() {
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.LAUNCHER))
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.HOME))
    }
}
