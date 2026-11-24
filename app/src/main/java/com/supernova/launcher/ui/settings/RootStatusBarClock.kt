package com.supernova.launcher.ui.settings

import java.util.concurrent.TimeUnit

/**
 * Changes only SystemUI's status-bar clock slot.
 *
 * A missing secure setting uses AOSP's default excluded slots. Preserve those
 * defaults when writing an explicit list, so hiding the clock changes no
 * other standard status-bar icon.
 */
internal object RootStatusBarClock {
    private const val SETTING = "icon_blacklist"

    fun hasRoot(): Boolean = runRoot("id")?.contains("uid=0") == true

    /** Returns null on failure, otherwise whether this feature added "clock". */
    fun hideClock(): Boolean? {
        val current = runRoot("settings get secure $SETTING") ?: return null
        val (updated, added) = IconBlacklist.addClock(current)
        if (!added) return false
        if (!write(updated)) return null
        return isClockHidden()?.takeIf { it }
    }

    /** Reads SystemUI's actual user blacklist state, not a launcher-side cache. */
    fun isClockHidden(): Boolean? {
        val current = runRoot("settings get secure $SETTING") ?: return null
        return IconBlacklist.containsClock(current)
    }

    fun showClock(addedByFeature: Boolean): Boolean {
        if (!addedByFeature) return true
        val current = runRoot("settings get secure $SETTING") ?: return false
        if (!IconBlacklist.containsClock(current)) return true
        val updated = IconBlacklist.removeClock(current)
        val written = if (updated == null) {
            runRoot("settings delete secure $SETTING") != null
        } else {
            write(updated)
        }
        return written && isClockHidden() == false
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
