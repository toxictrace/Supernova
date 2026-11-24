package com.supernova.launcher.data

// A package's pinned shortcuts are tracked per user profile.
internal data class ShortcutPinOwner(val packageName: String, val userSerial: Long)

// Work out which removed app shortcuts no longer have a home-screen placement.
// The same shortcut may be placed more than once, including inside folders.
internal object ShortcutPinCleanup {

    fun idsNoLongerUsed(
        removedItems: List<LauncherItem>,
        remainingItems: List<LauncherItem>
    ): Map<ShortcutPinOwner, Set<String>> {
        fun references(items: List<LauncherItem>): Map<ShortcutPinOwner, Set<String>> =
            items.mapNotNull { item ->
                if (item.itemType != ItemType.SHORTCUT) return@mapNotNull null
                val packageName = item.packageName ?: return@mapNotNull null
                val shortcutId = item.shortcutId ?: return@mapNotNull null
                ShortcutPinOwner(packageName, item.userSerial) to shortcutId
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.toSet() }

        val removed = references(removedItems)
        val remaining = references(remainingItems)
        return removed.mapValues { (owner, ids) -> ids - remaining[owner].orEmpty() }
            .filterValues { it.isNotEmpty() }
    }
}
