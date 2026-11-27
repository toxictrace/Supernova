package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackMatchTest {

    private val byComponent = mapOf(
        "com.android.chrome/com.google.android.apps.chrome.Main" to "chrome",
        "com.whatsapp/com.whatsapp.Main" to "whatsapp"
    )
    private val byPackage = mapOf(
        "com.android.chrome" to "chrome",
        "com.whatsapp" to "whatsapp"
    )

    @Test
    fun exactComponentWins() {
        assertEquals(
            "chrome",
            IconPackRepository.drawableFor(
                byComponent, byPackage,
                "com.android.chrome", "com.google.android.apps.chrome.Main"
            )
        )
    }

    @Test
    fun renamedActivityStillThemesFromTheSamePackage() {
        // WhatsApp's launcher activity moved to HomeActivity years after most
        // packs were written; without the package fallback it renders unthemed.
        assertEquals(
            "whatsapp",
            IconPackRepository.drawableFor(
                byComponent, byPackage, "com.whatsapp", "com.whatsapp.HomeActivity"
            )
        )
    }

    @Test
    fun anUnknownPackageStaysUnthemed() {
        assertNull(
            IconPackRepository.drawableFor(
                byComponent, byPackage, "com.example.unknown", "com.example.unknown.Main"
            )
        )
    }

    @Test
    fun closestBackUsesTheNearestPerceptualColour() {
        val target = 0xFFEF4540.toInt()
        val candidates = listOf(
            0xFFEA403B.toInt(),
            0xFF20C850.toInt(),
            0xFF2440D8.toInt()
        )
        assertEquals(0, DominantColor.closestIndex(target, candidates))
    }

    @Test
    fun closestBackSkipsCandidatesWithoutAReadableColour() {
        val target = 0xFF20C850.toInt()
        val candidates = listOf<Int?>(null, 0xFF20C850.toInt(), 0xFFEA403B.toInt())
        assertEquals(1, DominantColor.closestIndex(target, candidates))
    }

    @Test
    fun missingTargetColourKeepsTheFirstAvailableBack() {
        assertEquals(0, DominantColor.closestIndex(null, listOf(0xFFEA403B.toInt(), 0xFF20C850.toInt())))
    }

    @Test
    fun emptyBackListHasNoSelectedIndex() {
        assertEquals(0, DominantColor.closestIndex(0xFFFFFFFF.toInt(), emptyList()))
    }

    @Test
    fun calendarNameStillAppendsTheDay() {
        assertNotNull(IconPackRepository.calendarDrawableName("cal_", 3))
        assertEquals("cal_3", IconPackRepository.calendarDrawableName("cal_", 3))
    }

    @Test
    fun pickerMatchesPutTheExactComponentFirstAndDropRepeats() {
        val entries = listOf(
            "com.whatsapp/com.whatsapp.Main" to "whatsapp_old",
            "com.whatsapp/com.whatsapp.HomeActivity" to "whatsapp",
            "com.whatsapp/com.whatsapp.HomeActivity" to "whatsapp_alt",
            "com.whatsapp/com.whatsapp.Other" to "whatsapp"
        )
        assertEquals(
            listOf("whatsapp", "whatsapp_alt", "whatsapp_old"),
            IconPackRepository.matchOrder(entries, "com.whatsapp/com.whatsapp.HomeActivity")
        )
    }

    @Test
    fun pickerMatchesFallBackToThePackageWhenTheActivityIsUnmapped() {
        val entries = listOf("com.android.chrome/com.google.android.apps.chrome.Main" to "chrome")
        assertEquals(
            listOf("chrome"),
            IconPackRepository.matchOrder(entries, "com.android.chrome/org.chromium.chrome.NewMain")
        )
    }
}
