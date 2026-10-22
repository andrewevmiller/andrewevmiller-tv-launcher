package com.andrewevmiller.tvlauncher.home.data

// Pure Kotlin on purpose (no android.* imports): everything the repository decides about the app list
// and the layout lives here so it can be unit-tested on the plain JVM. Generic over the item type T
// with key/label lambdas, so tests use plain Strings and the repository uses its own raw entries.

/** Result of [arrange]: the three lists the [AppsRepository] flows are made of. */
data class Arranged<T>(
    /** Every non-hidden item, sorted by label ignoring case. */
    val all: List<T>,
    /** Pinned items in the user's order. */
    val favorites: List<T>,
    /** Hidden items, sorted by label ignoring case. */
    val hidden: List<T>,
)

/** Maps every item in all three lists, keeping the structure. */
fun <T, R> Arranged<T>.mapItems(transform: (T) -> R): Arranged<R> =
    Arranged(all.map(transform), favorites.map(transform), hidden.map(transform))

/** Label order ignoring case; ties broken by key so the order is deterministic. */
private fun <T> labelOrder(key: (T) -> String, label: (T) -> String): Comparator<T> =
    compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { label(it) }.thenBy { key(it) }

/**
 * Combines the installed [items] with the user's [layout].
 *
 * - `all`: items whose key is not in `layout.hidden`, sorted by label ignoring case.
 * - `favorites`: `layout.pinned` in order. Keys that are not installed are skipped silently (they stay in
 *   the layout, so an app that is reinstalled later gets its slot back) and so are hidden ones.
 * - `hidden`: items whose key is in `layout.hidden`, sorted by label ignoring case.
 */
fun <T> arrange(
    items: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    layout: FieldLayout,
): Arranged<T> {
    val byLabel = labelOrder(key, label)
    val (hiddenItems, visibleItems) = items.partition { key(it) in layout.hidden }
    val byKey = HashMap<String, T>(items.size * 2)
    for (item in items) byKey[key(item)] = item
    val favorites = layout.pinned
        .distinct()
        .filter { it !in layout.hidden }
        .mapNotNull { byKey[it] }
    return Arranged(
        all = visibleItems.sortedWith(byLabel),
        favorites = favorites,
        hidden = hiddenItems.sortedWith(byLabel),
    )
}

/**
 * One entry per package. When a package has leanback entries the first of those wins, otherwise the first
 * entry. The result keeps the order in which packages first appear in [items].
 */
fun <T> preferLeanbackPerPackage(
    items: List<T>,
    packageOf: (T) -> String,
    isLeanback: (T) -> Boolean,
): List<T> {
    val chosen = LinkedHashMap<String, T>()
    for (item in items) {
        val pkg = packageOf(item)
        val current = chosen[pkg]
        if (current == null || (!isLeanback(current) && isLeanback(item))) chosen[pkg] = item
    }
    return chosen.values.toList()
}

/**
 * The order in which first-run defaults are pinned: known apps (anything categorised VIDEO, MUSIC, GAMES
 * or TOOLS) before the rest; within each group leanback activities first, then alphabetical by label
 * ignoring case. Returns the keys.
 */
fun <T> seedOrder(
    items: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    category: (T) -> AppCategory,
    isLeanback: (T) -> Boolean,
): List<String> {
    val order = compareBy<T> { if (category(it) == AppCategory.OTHER) 1 else 0 }
        .thenBy { if (isLeanback(it)) 0 else 1 }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { label(it) }
        .thenBy { key(it) }
    return items.sortedWith(order).map(key)
}

// ---- Layout transforms. Each returns the same instance when nothing changes. ----

/** Appends [key] to the pinned list if absent and removes it from hidden. */
fun pin(layout: FieldLayout, key: String): FieldLayout {
    val pinned = if (key in layout.pinned) layout.pinned else layout.pinned + key
    val hidden = layout.hidden - key
    return if (pinned == layout.pinned && hidden == layout.hidden) layout else layout.copy(pinned = pinned, hidden = hidden)
}

/** Removes [key] from the pinned list. Does not touch hidden. */
fun unpin(layout: FieldLayout, key: String): FieldLayout =
    if (key in layout.pinned) layout.copy(pinned = layout.pinned - key) else layout

/**
 * Moves the pinned [key] so it ends up at [toIndex] among the pinned entries that satisfy [isVisible]
 * (clamped to the valid range). No-op if [key] is not pinned.
 *
 * The pinned list can contain keys of apps that are not installed right now; the UI never sees those, so a
 * UI index is an index into the visible entries. The default treats every pinned key as visible, which makes
 * [toIndex] a plain index into `layout.pinned`. Invisible entries keep their relative order.
 */
fun move(
    layout: FieldLayout,
    key: String,
    toIndex: Int,
    isVisible: (String) -> Boolean = { true },
): FieldLayout {
    if (key !in layout.pinned) return layout
    val rest = layout.pinned - key
    val visibleRest = rest.filter(isVisible)
    if (visibleRest.isEmpty()) return layout // nothing visible to order against
    val target = toIndex.coerceIn(0, visibleRest.size)
    val insertAt = if (target < visibleRest.size) rest.indexOf(visibleRest[target]) else rest.indexOf(visibleRest.last()) + 1
    val moved = rest.toMutableList().apply { add(insertAt, key) }
    return if (moved == layout.pinned) layout else layout.copy(pinned = moved)
}

/** Removes [key] from pinned and adds it to hidden. */
fun hide(layout: FieldLayout, key: String): FieldLayout =
    if (key in layout.pinned || key !in layout.hidden) {
        layout.copy(pinned = layout.pinned - key, hidden = layout.hidden + key)
    } else {
        layout
    }

/** Removes [key] from hidden. The app is not re-pinned. */
fun unhide(layout: FieldLayout, key: String): FieldLayout =
    if (key in layout.hidden) layout.copy(hidden = layout.hidden - key) else layout

/**
 * First-run defaults. Only does anything while `layout.seeded == false`: pins up to [max] of
 * [installedKeysInPreferenceOrder] (hidden and duplicate keys skipped) and sets `seeded = true`.
 *
 * `seeded` is what stops a user who later unpins everything from getting the defaults back. A layout that
 * is not seeded but already has pinned apps (the user got there first) keeps them and is just marked seeded.
 */
fun seedDefaults(
    layout: FieldLayout,
    installedKeysInPreferenceOrder: List<String>,
    max: Int = 8,
): FieldLayout {
    if (layout.seeded) return layout
    if (layout.pinned.isNotEmpty()) return layout.copy(seeded = true)
    val picks = installedKeysInPreferenceOrder
        .asSequence()
        .distinct()
        .filter { it !in layout.hidden }
        .take(max.coerceAtLeast(0))
        .toList()
    return layout.copy(pinned = picks, seeded = true)
}
