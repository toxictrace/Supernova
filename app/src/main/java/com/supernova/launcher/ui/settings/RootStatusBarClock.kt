package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPreferences
import com.supernova.launcher.data.Pref
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Changes only SystemUI's status-bar clock slot.
 *
 * Ownership is stored before the first write. An inconclusive verification
 * never clears it, so a later restore can finish the operation safely.
 */
internal object RootStatusBarClock {
    private const val SETTING = "icon_blacklist"

    fun hasRoot(): Boolean = runRoot("id")?.contains("uid=0") == true

    suspend fun setHidden(
        hidden: Boolean,
        preferences: LauncherPreferences
    ): Boolean {
        val owned = preferences.flow.first().rootClockAddedToBlacklist

        val current = runRoot("settings get secure $SETTING") ?: return false
        val actuallyHidden = IconBlacklist.containsClock(current)

        if (hidden) {
            // A pre-existing clock entry belongs to the user, not this feature.
            if (actuallyHidden) return true

            val (updated, added) = IconBlacklist.addClock(current)
            if (!added) return true

            if (!owned) preferences.set(Pref.rootClockAddedToBlacklist, true)
            if (!write(updated)) return false

            // Keep ownership on false/null verification. The write may have
            // reached SystemUI even when the follow-up read did not.
            return isClockHidden() == true
        }

        if (!actuallyHidden) {
            preferences.set(Pref.rootClockAddedToBlacklist, false)
            return true
        }

        val updated = IconBlacklist.removeClock(current)
        val written = if (updated == null) {
            runRoot("settings delete secure $SETTING") != null
        } else {
            write(updated)
        }
        if (!written) return false

        val verified = isClockHidden() ?: return false
        if (verified) return false

        preferences.set(Pref.rootClockAddedToBlacklist, false)
        return true
    }

    private fun isClockHidden(): Boolean? {
        val current = runRoot("settings get secure $SETTING") ?: return null
        return IconBlacklist.containsClock(current)
    }

    private fun write(value: String): Boolean =
        runRoot("settings put secure $SETTING ${shellQuote(value)}") != null

    private fun runRoot(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output.takeIf { process.exitValue() == 0 }
    }.getOrNull()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

/** Pure list operations, separated so preservation rules can be unit-tested. */
internal object IconBlacklist {
    private val aospDefaults = listOf("rotate", "headset")

    fun addClock(raw: String?): Pair<String, Boolean> {
        val slots = parse(raw)
        if ("clock" in slots) return slots.sorted().joinToString(",") to false
        return (slots + "clock").sorted().joinToString(",") to true
    }

    fun removeClock(raw: String?): String? {
        val slots = parse(raw) - "clock"
        return slots.sorted().joinToString(",").takeIf { it.isNotEmpty() }
    }

    fun containsClock(raw: String?): Boolean = "clock" in parse(raw)

    private fun parse(raw: String?): Set<String> =
        if (raw.isNullOrBlank() || raw.trim() == "null") {
            aospDefaults.toSet()
        } else {
            raw.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        }
}
