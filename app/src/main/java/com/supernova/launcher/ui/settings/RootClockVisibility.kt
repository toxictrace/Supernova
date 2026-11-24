package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.RootClockScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

internal fun shouldHideRootClock(
    enabled: Boolean,
    launcherActive: Boolean,
    homeSurfaceActive: Boolean,
    scope: RootClockScope
): Boolean = enabled && launcherActive &&
    (scope == RootClockScope.LAUNCHER || homeSurfaceActive)

/**
 * Process-wide owner of SystemUI clock writes.
 *
 * Newer surface snapshots supersede older requests. The mutex prevents
 * commands from separate LauncherActivity instances from overlapping.
 */
internal object RootClockCoordinator {
    private val mutex = Mutex()
    private val revision = AtomicLong(0)

    fun request(): Long = revision.incrementAndGet()

    fun isCurrent(request: Long): Boolean = revision.get() == request

    suspend fun serialized(request: Long, action: suspend () -> Unit) {
        mutex.lock()
        try {
            if (isCurrent(request)) action()
        } finally {
            mutex.unlock()
        }
    }
}

internal fun isRootClockWorkspacePage(currentPage: Int): Boolean = currentPage >= 0
