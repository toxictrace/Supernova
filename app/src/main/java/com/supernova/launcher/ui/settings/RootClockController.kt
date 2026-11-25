package com.supernova.launcher.ui.settings

import android.content.Context
import android.util.Log
import com.supernova.launcher.data.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the clock's desired state for the lifetime of the app process.
 *
 * The preference store is authoritative. Activity callbacks only report which
 * launcher surface is currently visible; they never replace the saved mode.
 */
internal class RootClockController(
    context: Context,
    private val preferences: LauncherPreferences,
    scope: CoroutineScope
) {
    private val logTag = context.applicationContext.packageName

    private data class LauncherState(
        val active: Boolean = false,
        val homeSurface: Boolean = false
    )

    private val launcherState = java.util.concurrent.atomic.AtomicReference(LauncherState())
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        // A settings change always schedules reconciliation, even when Settings
        // is the foreground Activity and the launcher itself is stopped.
        scope.launch {
            preferences.flow.map { it.rootClockMode }.distinctUntilChanged().collect {
                requests.trySend(Unit)
            }
        }

        // One process-wide worker serializes every root command. New state
        // replaces pending work and interrupts retries of obsolete targets.
        scope.launch {
            for (ignored in requests) {
                var retryDelayMs = INITIAL_RETRY_DELAY_MS
                while (true) {
                    val prefs = runCatching { preferences.flow.first() }.getOrNull()
                    if (prefs == null) {
                        Log.w(logTag, "Unable to read root clock preferences")
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }

                    val state = launcherState.get()
                    val target = shouldHideRootClock(
                        prefs.rootClockMode,
                        state.active,
                        state.homeSurface
                    )
                    val succeeded = runCatching {
                        withContext(Dispatchers.IO) {
                            RootStatusBarClock.setHidden(target, preferences)
                        }
                    }.getOrElse { error ->
                        Log.w(logTag, "Root clock reconciliation failed", error)
                        false
                    }

                    val latestMode = runCatching { preferences.flow.first().rootClockMode }.getOrNull()
                    val stillCurrent = latestMode == prefs.rootClockMode && launcherState.get() == state
                    if (!stillCurrent) {
                        requests.trySend(Unit)
                        break
                    }
                    if (succeeded) break

                    Log.w(
                        logTag,
                        "Unable to reconcile root clock: mode=${prefs.rootClockMode}, " +
                            "launcherActive=${state.active}, homeSurface=${state.homeSurface}; " +
                            "retry in ${retryDelayMs}ms"
                    )
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    fun updateLauncherState(active: Boolean, homeSurface: Boolean) {
        val next = LauncherState(active, homeSurface)
        if (launcherState.getAndSet(next) != next) requests.trySend(Unit)
    }

    /** Reapply the saved mode after SystemUI or another window changes focus. */
    fun reapply() {
        requests.trySend(Unit)
    }

    private companion object {
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 15_000L
    }
}
