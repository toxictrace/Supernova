package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutPinCleanupTest {

    private fun shortcut(
        itemId: String,
        packageName: String,
        shortcutId: String,
        userSerial: Long = 0L
    ) = LauncherItem(
        id = itemId,
        itemType = ItemType.SHORTCUT,
        packageName = packageName,
        shortcutId = shortcutId,
        userSerial = userSerial
    )

    @Test
    fun removedShortcutIsReleasedWhenNoPlacementRemains() {
        val removed = listOf(shortcut("home", "com.browser", "page-1"))

        assertEquals(
            mapOf(ShortcutPinOwner("com.browser", 0L) to setOf("page-1")),
            ShortcutPinCleanup.idsNoLongerUsed(removed, emptyList())
        )
    }

    @Test
    fun anotherPlacementKeepsTheShortcutPinned() {
        val removed = listOf(shortcut("home", "com.browser", "page-1"))
        val remaining = listOf(shortcut("dock", "com.browser", "page-1"))

        assertEquals(emptyMap<ShortcutPinOwner, Set<String>>(),
            ShortcutPinCleanup.idsNoLongerUsed(removed, remaining))
    }

    @Test
    fun shortcutIdsAreSeparatedByPackageAndProfile() {
        val removed = listOf(
            shortcut("a", "com.browser", "same-id", 0L),
            shortcut("b", "com.browser", "same-id", 10L),
            shortcut("c", "com.other", "same-id", 0L)
        )
        val remaining = listOf(shortcut("d", "com.browser", "same-id", 10L))

        assertEquals(
            mapOf(
                ShortcutPinOwner("com.browser", 0L) to setOf("same-id"),
                ShortcutPinOwner("com.other", 0L) to setOf("same-id")
            ),
            ShortcutPinCleanup.idsNoLongerUsed(removed, remaining)
        )
    }

    @Test
    fun nonAppShortcutsAreIgnored() {
        val removed = listOf(
            LauncherItem(id = "action", itemType = ItemType.SHORTCUT),
            LauncherItem(id = "app", itemType = ItemType.APP, packageName = "com.browser")
        )

        assertEquals(emptyMap<ShortcutPinOwner, Set<String>>(),
            ShortcutPinCleanup.idsNoLongerUsed(removed, emptyList()))
    }
}
