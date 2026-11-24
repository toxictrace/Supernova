package com.supernova.launcher.ui.settings

import android.content.Context
import android.util.Log
import com.supernova.launcher.data.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serializes clock reconciliation for the whole app process.
 * Root commands outlive individual Activity lifecycle scopes.
 */
internal class RootClockController(
    context: Context,
    private val preferences: LauncherPreferences,
    scope: CoroutineScope
) {
    private val desired = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        // Keep retrying a failed root operation while its target remains current.
        // A newer request replaces the target without starting a second worker.
        scope.launch {
            for (ignored in requests) {
                var retryDelayMs = INITIAL_RETRY_DELAY_MS
                while (true) {
                    val target = desired.get() ?: break
                    val succeeded = runCatching {
                        withContext(Dispatchers.IO) {
                            RootStatusBarClock.setHidden(target, preferences)
                        }
                    }.getOrElse { error ->
                        Log.w("Supernova", "Root clock reconciliation failed", error)
                        false
                    }

                    // Never retry or leave a stale target as the final state.
                    if (desired.get() != target) {
                        requests.trySend(Unit)
                        break
                    }
                    if (succeeded) break

                    Log.w(
                        "Supernova",
                        "Unable to reconcile status-bar clock hidden=$target; retry in ${retryDelayMs}ms"
                    )
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    fun request(hidden: Boolean) {
        desired.set(hidden)
        requests.trySend(Unit)
    }

    private companion object {
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 15_000L
    }
}
