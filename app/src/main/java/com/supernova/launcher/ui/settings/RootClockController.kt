package com.supernova.launcher.ui.settings

import android.content.Context
import android.util.Log
import com.supernova.launcher.data.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
    private val appContext = context.applicationContext
    private val desired = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in requests) {
                val target = desired.get() ?: continue
                val succeeded = runCatching {
                    withContext(Dispatchers.IO) {
                        RootStatusBarClock.setHidden(target, preferences)
                    }
                }.getOrElse { error ->
                    Log.w("Supernova", "Root clock reconciliation failed", error)
                    false
                }
                if (!succeeded) {
                    Log.w("Supernova", "Unable to reconcile status-bar clock hidden=$target")
                }
                // A newer request may have arrived during the root operation.
                if (desired.get() != target) requests.trySend(Unit)
            }
        }
    }

    fun request(hidden: Boolean) {
        desired.set(hidden)
        requests.trySend(Unit)
    }
}
