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

internal fun isRootClockHomePage(currentPage: Int, defaultPage: Int): Boolean =
    currentPage == defaultPage

/**
 * Serializes SystemUI clock writes across every LauncherActivity instance.
 * Identical snapshots do not invalidate an operation already in progress.
 */
internal object RootClockCoordinator {
    private val mutex = Mutex()
    private val revision = AtomicLong(0)
    private val stateLock = Any()
    private var lastOwner: Any? = null
    private var lastSnapshot: Any? = null

    fun request(owner: Any, snapshot: Any): Long = synchronized(stateLock) {
        if (lastOwner !== owner || lastSnapshot != snapshot) {
            lastOwner = owner
            lastSnapshot = snapshot
            revision.incrementAndGet()
        }
        revision.get()
    }

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
