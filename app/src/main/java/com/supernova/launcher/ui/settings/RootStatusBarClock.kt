package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPreferences
import java.util.concurrent.TimeUnit

/**
 * Changes only SystemUI's status-bar clock slot.
 *
 * Ownership and the previous blacklist value are device-local. Existing
 * user entries are preserved, and an unset setting is restored as unset.
 */
internal object RootStatusBarClock {
    private const val SETTING = "icon_blacklist"

    fun hasRoot(): Boolean = runRoot("id")?.contains("uid=0") == true

    suspend fun setHidden(
        hidden: Boolean,
        preferences: LauncherPreferences
    ): Boolean {
        val owned = preferences.rootClockEntryOwned()

        // Showing the clock needs no root access when this feature owns no
        // blacklist entry. This also avoids pointless retries without root.
        if (!hidden && !owned) return true

        val current = runRoot("settings get secure $SETTING") ?: return false
        val clockPresent = IconBlacklist.containsClock(current)

        if (hidden) {
            // A clock hidden before this feature was enabled belongs to the user.
            if (clockPresent) return true

            val updated = IconBlacklist.addClock(current)
            if (!owned) {
                val original = current.takeUnless { it.isBlank() || it == "null" }
                preferences.claimRootClockEntry(original)
            }
            if (!write(updated)) return false

            // Ownership remains set until a verified restore completes.
            return isClockHidden() == true
        }

        if (!shouldRestoreClock(owned, clockPresent)) {
            if (owned && !clockPresent) preferences.releaseRootClockEntry()
            return true
        }

        val original = preferences.rootClockOriginalBlacklist()
        val restoreValue = IconBlacklist.restoreValue(current, original)
        val written = if (restoreValue == null) {
            runRoot("settings delete secure $SETTING") != null
        } else {
            write(restoreValue)
        }
        if (!written) return false

        val verified = isClockHidden() ?: return false
        if (verified) return false

        preferences.releaseRootClockEntry()
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

/** Pure list operations, preserving the user's entries. */
internal object IconBlacklist {
    private val aospDefaults = listOf("headset", "rotate")

    fun addClock(raw: String?): String {
        val slots = parse(raw).ifEmpty { aospDefaults }
        if ("clock" in slots) return slots.joinToString(",")
        return (slots + "clock").distinct().sorted().joinToString(",")
    }

    fun defaultBlacklist(): String = aospDefaults.joinToString(",")

    private fun normalized(raw: String): String = parse(raw).sorted().joinToString(",")

    fun removeClock(raw: String?): String? =
        parse(raw).filterNot { it == "clock" }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    fun restoreValue(raw: String?, original: String?): String? {
        val withoutClock = removeClock(raw)
        val matchesOriginal = if (original == null) {
            withoutClock == defaultBlacklist()
        } else {
            withoutClock == normalized(original)
        }
        return if (matchesOriginal) original else withoutClock
    }

    fun containsClock(raw: String?): Boolean = "clock" in parse(raw)

    private fun parse(raw: String?): List<String> =
        if (raw.isNullOrBlank() || raw.trim() == "null") {
            emptyList()
        } else {
            raw.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
        }
}

internal fun shouldRestoreClock(owned: Boolean, clockPresent: Boolean): Boolean =
    owned && clockPresent
