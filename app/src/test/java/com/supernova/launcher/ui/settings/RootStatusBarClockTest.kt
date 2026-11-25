package com.supernova.launcher.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStatusBarClockTest {
    @Test
    fun addingClockPreservesExistingBlacklistEntries() {
        assertEquals("battery,clock,wifi", IconBlacklist.addClock("wifi,battery"))
    }

    @Test
    fun addingClockToUnsetBlacklistPreservesAospDefaults() {
        assertEquals("clock,headset,rotate", IconBlacklist.addClock("null"))
    }

    @Test
    fun alreadyHiddenClockIsNotChanged() {
        assertEquals("clock,wifi", IconBlacklist.addClock("clock,wifi"))
    }

    @Test
    fun clockPresenceIsReadFromTheActualBlacklist() {
        assertTrue(IconBlacklist.containsClock("clock,wifi"))
        assertFalse(IconBlacklist.containsClock("wifi,battery"))
        assertFalse(IconBlacklist.containsClock("null"))
    }

    @Test
    fun removingClockPreservesAllOtherEntries() {
        assertEquals("wifi,battery", IconBlacklist.removeClock("wifi,clock,battery"))
    }

    @Test
    fun removingOnlyClockRestoresUnsetBlacklist() {
        assertNull(IconBlacklist.removeClock("clock"))
    }

    @Test
    fun originalUnsetBlacklistIsRestoredAsUnset() {
        assertNull(IconBlacklist.restoreValue("clock,headset,rotate", null))
    }

    @Test
    fun originalExplicitBlacklistIsRestoredExactly() {
        assertEquals("wifi,battery", IconBlacklist.restoreValue("battery,clock,wifi", "wifi,battery"))
    }

    @Test
    fun externalBlacklistChangesArePreserved() {
        assertEquals(
            "battery,wifi,bluetooth",
            IconBlacklist.restoreValue("battery,clock,wifi,bluetooth", "wifi,battery")
        )
    }

    @Test
    fun onlyAClockEntryOwnedByThisFeatureIsRestored() {
        assertTrue(shouldRestoreClock(owned = true, clockPresent = true))
        assertFalse(shouldRestoreClock(owned = false, clockPresent = true))
        assertFalse(shouldRestoreClock(owned = true, clockPresent = false))
        assertFalse(shouldRestoreClock(owned = false, clockPresent = false))
    }
}
