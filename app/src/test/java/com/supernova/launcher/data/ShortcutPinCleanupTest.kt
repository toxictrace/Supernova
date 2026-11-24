package com.supernova.launcher.data

import com.supernova.launcher.feed.CardAction
import com.supernova.launcher.feed.FeedCard
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
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

    private fun pin(packageName: String, shortcutId: String, userSerial: Long = 0L) =
        ShortcutPinRef(ShortcutPinOwner(packageName, userSerial), shortcutId)

    @Test
    fun removedShortcutIsReleasedWhenNoPlacementOrOtherReferenceRemains() {
        val removed = listOf(shortcut("home", "com.browser", "page-1"))

        assertEquals(
            mapOf(ShortcutPinOwner("com.browser", 0L) to setOf("page-1")),
            ShortcutPinCleanup.idsNoLongerUsed(removed, LauncherModel(), LauncherPrefs())
        )
    }

    @Test
    fun anotherPlacementKeepsTheShortcutPinned() {
        val removed = listOf(shortcut("home", "com.browser", "page-1"))
        val remaining = LauncherModel(items = listOf(shortcut("dock", "com.browser", "page-1")))

        assertEquals(
            emptyMap<ShortcutPinOwner, Set<String>>(),
            ShortcutPinCleanup.idsNoLongerUsed(removed, remaining, LauncherPrefs())
        )
    }

    @Test
    fun shortcutIdsAreSeparatedByPackageAndProfile() {
        val pinned = listOf(
            pin("com.browser", "same-id"),
            pin("com.browser", "same-id", 10L),
            pin("com.other", "same-id")
        )
        val model = LauncherModel(items = listOf(shortcut("work", "com.browser", "same-id", 10L)))

        assertEquals(
            mapOf(
                ShortcutPinOwner("com.browser", 0L) to setOf("same-id"),
                ShortcutPinOwner("com.other", 0L) to setOf("same-id")
            ),
            ShortcutPinCleanup.orphanedPinned(pinned, model, LauncherPrefs())
        )
    }

    @Test
    fun swipeActionAndGestureReferencesKeepShortcutsPinned() {
        val swipe = shortcut("home", "com.browser", "placed").copy(
            swipeAction = "com.browser|swipe-up@0"
        )
        val gesture = GestureDefaults.encode(
            Gesture.DOUBLE_TAP,
            GestureBinding(GestureAction.LAUNCH_SHORTCUT, "com.browser|gesture@0", "Browser shortcut")
        )
        val model = LauncherModel(items = listOf(swipe))

        assertEquals(
            mapOf(ShortcutPinOwner("com.browser", 0L) to setOf("orphan")),
            ShortcutPinCleanup.orphanedPinned(
                listOf(
                    pin("com.browser", "placed"),
                    pin("com.browser", "swipe-up"),
                    pin("com.browser", "gesture"),
                    pin("com.browser", "orphan")
                ),
                model,
                LauncherPrefs(gestureBindings = setOf(gesture))
            )
        )
    }

    @Test
    fun feedCardFeaturedAndActionShortcutsStayPinned() {
        val model = LauncherModel(
            feedCards = listOf(
                FeedCard(
                    id = "card",
                    featuredType = "SHORTCUT",
                    featuredTarget = "com.browser|featured@0",
                    actions = listOf(CardAction(type = "SHORTCUT", target = "com.browser|action@0"))
                )
            )
        )

        assertEquals(
            emptyMap<ShortcutPinOwner, Set<String>>(),
            ShortcutPinCleanup.orphanedPinned(
                listOf(pin("com.browser", "featured"), pin("com.browser", "action")),
                model,
                LauncherPrefs()
            )
        )
    }

    @Test
    fun nonAppShortcutsAreIgnored() {
        val removed = listOf(
            LauncherItem(id = "action", itemType = ItemType.SHORTCUT),
            LauncherItem(id = "app", itemType = ItemType.APP, packageName = "com.browser")
        )

        assertEquals(
            emptyMap<ShortcutPinOwner, Set<String>>(),
            ShortcutPinCleanup.idsNoLongerUsed(removed, LauncherModel(), LauncherPrefs())
        )
    }
}
