package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.RootClockMode

internal fun shouldHideRootClock(
    mode: RootClockMode,
    launcherActive: Boolean,
    homeSurfaceActive: Boolean
): Boolean = when (mode) {
    RootClockMode.NO_HIDE -> false
    RootClockMode.LAUNCHER -> launcherActive
    RootClockMode.HOME -> launcherActive && homeSurfaceActive
}

internal fun isRootClockHomePage(currentPage: Int, defaultPage: Int): Boolean =
    currentPage == defaultPage
