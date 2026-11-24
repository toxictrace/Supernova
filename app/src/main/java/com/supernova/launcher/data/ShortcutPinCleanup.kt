package com.supernova.launcher.data

import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureDefaults

// A package's pinned shortcuts are tracked per user profile.
internal data class ShortcutPinOwner(val packageName: String, val userSerial: Long)

internal data class ShortcutPinRef(val owner: ShortcutPinOwner, val shortcutId: String)

// Finds pins this launcher no longer uses. Keep every reference, not only
// desktop icons: swipe actions, feed cards and gestures also launch shortcuts.
internal object ShortcutPinCleanup {

    fun idsNoLongerUsed(
        removedItems: List<LauncherItem>,
        remainingModel: LauncherModel,
        prefs: LauncherPrefs
    ): Map<ShortcutPinOwner, Set<String>> {
        val removed = references(removedItems, emptyList(), LauncherPrefs())
        val remaining = references(remainingModel.items, remainingModel.feedCards, prefs)
        return removed.groupBy { it.owner }
            .mapValues { (owner, refs) ->
                refs.map { it.shortcutId }.toSet() -
                    remaining.filter { it.owner == owner }.map { it.shortcutId }.toSet()
            }
            .filterValues { it.isNotEmpty() }
    }

    fun orphanedPinned(
        pinned: List<ShortcutPinRef>,
        model: LauncherModel,
        prefs: LauncherPrefs
    ): Map<ShortcutPinOwner, Set<String>> {
        val referenced = references(model.items, model.feedCards, prefs).groupBy { it.owner }
        return pinned.groupBy { it.owner }
            .mapValues { (owner, refs) ->
                refs.map { it.shortcutId }.toSet() -
                    referenced[owner].orEmpty().map { it.shortcutId }.toSet()
            }
            .filterValues { it.isNotEmpty() }
    }

    private fun references(
        items: List<LauncherItem>,
        feedCards: List<com.supernova.launcher.feed.FeedCard>,
        prefs: LauncherPrefs
    ): Set<ShortcutPinRef> {
        val result = linkedSetOf<ShortcutPinRef>()

        fun add(packageName: String?, shortcutId: String?, userSerial: Long) {
            if (!packageName.isNullOrBlank() && !shortcutId.isNullOrBlank()) {
                result += ShortcutPinRef(ShortcutPinOwner(packageName, userSerial), shortcutId)
            }
        }

        fun addTarget(target: String?) {
            if (target.isNullOrBlank()) return
            val (body, serial) = LaunchTarget.parse(target)
            val separator = body.indexOf('|')
            if (separator <= 0 || separator == body.lastIndex) return
            add(body.substring(0, separator), body.substring(separator + 1), serial ?: 0L)
        }

        items.forEach { item ->
            if (item.itemType == ItemType.SHORTCUT) {
                add(item.packageName, item.shortcutId, item.userSerial)
            }
            addTarget(item.swipeAction)
            addTarget(item.swipeActionDown)
            ActionShortcut.decode(item.intentUri)
                ?.takeIf { it.action == GestureAction.LAUNCH_SHORTCUT }
                ?.payload
                ?.let(::addTarget)
        }

        feedCards.forEach { card ->
            if (card.featuredType.equals("SHORTCUT", ignoreCase = true)) addTarget(card.featuredTarget)
            card.actions.filter { it.type.equals("SHORTCUT", ignoreCase = true) }
                .forEach { addTarget(it.target) }
        }

        GestureDefaults.decode(prefs.gestureBindings).values
            .filter { it.action == GestureAction.LAUNCH_SHORTCUT }
            .mapNotNull { it.payload }
            .forEach(::addTarget)

        return result
    }
}
