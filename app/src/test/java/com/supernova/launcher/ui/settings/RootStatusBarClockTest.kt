package com.supernova.launcher.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStatusBarClockTest {
    @Test
    fun `adding clock preserves existing blacklist entries`() {
        val (updated, added) = IconBlacklist.addClock("wifi,battery")
        assertEquals("battery,clock,wifi", updated)
        assertTrue(added)
    }

    @Test
    fun `unset blacklist retains standard AOSP exclusions`() {
        val (updated, added) = IconBlacklist.addClock("null")
        assertEquals("clock,headset,rotate", updated)
        assertTrue(added)
    }

    @Test
    fun `already hidden clock is not claimed by this feature`() {
        val (updated, added) = IconBlacklist.addClock("clock,wifi")
        assertEquals("clock,wifi", updated)
        assertFalse(added)
    }

    @Test
    fun `clock presence is read from the actual blacklist`() {
        assertTrue(IconBlacklist.containsClock("clock,wifi"))
        assertFalse(IconBlacklist.containsClock("wifi,battery"))
        assertFalse(IconBlacklist.containsClock("null"))
    }

    @Test
    fun `removing clock preserves all other entries`() {
        assertEquals("battery,wifi", IconBlacklist.removeClock("clock,wifi,battery"))
    }
}
