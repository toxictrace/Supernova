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
    fun everyWorkspacePageCountsAsHomeSurface() {
        assertTrue(isRootClockWorkspacePage(0))
        assertTrue(isRootClockWorkspacePage(2))
        assertTrue(isRootClockWorkspacePage(3))
        assertFalse(isRootClockWorkspacePage(-1))
    }

    @Test
    fun identicalSurfaceSnapshotsDoNotInvalidateCurrentWork() {
        val owner = Any()
        val snapshot = Triple(true, true, false)
        val first = RootClockCoordinator.request(owner, snapshot)
        val repeated = RootClockCoordinator.request(owner, snapshot)
        assertEquals(first, repeated)
        assertTrue(RootClockCoordinator.isCurrent(first))
    }

    @Test
    fun changedSurfaceSnapshotInvalidatesPreviousWork() {
        val owner = Any()
        val first = RootClockCoordinator.request(owner, Triple(true, true, false))
        val second = RootClockCoordinator.request(owner, Triple(false, false, false))
        assertFalse(RootClockCoordinator.isCurrent(first))
        assertTrue(RootClockCoordinator.isCurrent(second))
    }

    @Test
    fun homeScopeMatchesOnlyConfiguredHomePage() {
        assertTrue(isRootClockHomePage(2, 2))
        assertFalse(isRootClockHomePage(0, 2))
        assertFalse(isRootClockHomePage(3, 2))
    }

    @Test
    fun inactiveLauncherAlwaysRestoresClock() {
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.LAUNCHER))
        assertFalse(shouldHideRootClock(true, false, true, RootClockScope.HOME))
    }
}
