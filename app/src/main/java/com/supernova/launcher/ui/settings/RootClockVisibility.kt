package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.RootClockScope

internal fun shouldHideRootClock(
    enabled: Boolean,
    launcherActive: Boolean,
    homeSurfaceActive: Boolean,
    scope: RootClockScope
): Boolean = enabled && launcherActive &&
    (scope == RootClockScope.LAUNCHER || homeSurfaceActive)

internal fun isRootClockHomePage(currentPage: Int, defaultPage: Int): Boolean =
    currentPage == defaultPage
