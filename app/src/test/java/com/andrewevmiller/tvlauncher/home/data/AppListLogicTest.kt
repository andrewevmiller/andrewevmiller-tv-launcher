package com.andrewevmiller.tvlauncher.home.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListLogicTest {
    /** Test items are Strings of the form "key|label"; key is the part before the bar. */
    private fun key(item: String) = item.substringBefore('|')
    private fun label(item: String) = item.substringAfter('|')

    private fun arr(items: List<String>, layout: FieldLayout) = arrange(items, { key(it) }, { label(it) }, layout)

    // ---- arrange ----

    @Test
    fun allIsSortedByLabelIgnoringCase() {
        val items = listOf("c|banana", "a|Cherry", "b|apple", "d|Apple Pie")
        val result = arr(items, FieldLayout())
        assertEquals(listOf("b|apple", "d|Apple Pie", "c|banana", "a|Cherry"), result.all)
    }

    @Test
    fun equalLabelsAreOrderedByKeyForDeterminism() {
        val items = listOf("z|Same", "a|same", "m|SAME")
        assertEquals(listOf("a|same", "m|SAME", "z|Same"), arr(items, FieldLayout()).all)
        assertEquals(listOf("a|same", "m|SAME", "z|Same"), arr(items.reversed(), FieldLayout()).all)
    }

    @Test
    fun allExcludesHiddenAndHiddenListsThemSorted() {
        val items = listOf("a|Zed", "b|Bee", "c|Aye", "d|Dee")
        val result = arr(items, FieldLayout(hidden = setOf("a", "d")))
        assertEquals(listOf("c|Aye", "b|Bee"), result.all)
        assertEquals(listOf("d|Dee", "a|Zed"), result.hidden)
    }

    @Test
    fun favoritesFollowPinnedOrderNotLabelOrder() {
        val items = listOf("a|Alpha", "b|Bravo", "c|Charlie")
        val result = arr(items, FieldLayout(pinned = listOf("c", "a", "b")))
        assertEquals(listOf("c|Charlie", "a|Alpha", "b|Bravo"), result.favorites)
    }

    @Test
    fun favoritesSkipUninstalledKeysWithoutTouchingTheLayout() {
        val items = listOf("a|Alpha", "c|Charlie")
        val layout = FieldLayout(pinned = listOf("a", "gone", "c"))
        val result = arr(items, layout)
        assertEquals(listOf("a|Alpha", "c|Charlie"), result.favorites)
        // arrange never mutates or returns the layout; the uninstalled key is still there.
        assertEquals(listOf("a", "gone", "c"), layout.pinned)
    }

    @Test
    fun favoritesSkipHiddenKeysEvenIfStillPinned() {
        val items = listOf("a|Alpha", "b|Bravo")
        val layout = FieldLayout(pinned = listOf("a", "b"), hidden = setOf("a"))
        val result = arr(items, layout)
        assertEquals(listOf("b|Bravo"), result.favorites)
        assertEquals(listOf("a|Alpha"), result.hidden)
    }

    @Test
    fun hiddenKeysThatAreNotInstalledAreNotListed() {
        val result = arr(listOf("a|Alpha"), FieldLayout(hidden = setOf("gone")))
        assertEquals(listOf("a|Alpha"), result.all)
        assertEquals(emptyList<String>(), result.hidden)
    }

    @Test
    fun duplicatePinnedKeysShowOnce() {
        val result = arr(listOf("a|Alpha", "b|Bravo"), FieldLayout(pinned = listOf("a", "b", "a")))
        assertEquals(listOf("a|Alpha", "b|Bravo"), result.favorites)
    }

    @Test
    fun emptyInputs() {
        val result = arr(emptyList(), FieldLayout(pinned = listOf("a"), hidden = setOf("b")))
        assertEquals(Arranged<String>(emptyList(), emptyList(), emptyList()), result)
    }

    @Test
    fun mapItemsKeepsStructure() {
        val arranged = Arranged(listOf("a", "b"), listOf("b"), listOf("c"))
        assertEquals(Arranged(listOf("A", "B"), listOf("B"), listOf("C")), arranged.mapItems { it.uppercase() })
    }

    // ---- preferLeanbackPerPackage ----

    private data class Entry(val pkg: String, val activity: String, val leanback: Boolean)

    private fun dedupe(vararg entries: Entry) = preferLeanbackPerPackage(entries.toList(), { it.pkg }, { it.leanback })

    @Test
    fun dedupeKeepsTheLeanbackActivityWhenAnAppHasBoth() {
        val phone = Entry("p", ".Phone", false)
        val tv = Entry("p", ".Tv", true)
        assertEquals(listOf(tv), dedupe(phone, tv))
        assertEquals(listOf(tv), dedupe(tv, phone))
    }

    @Test
    fun dedupeKeepsTheFirstEntryWhenNoneAreLeanback() {
        val first = Entry("p", ".A", false)
        assertEquals(listOf(first), dedupe(first, Entry("p", ".B", false)))
    }

    @Test
    fun dedupeKeepsTheFirstLeanbackEntryWhenSeveralAre() {
        val first = Entry("p", ".A", true)
        assertEquals(listOf(first), dedupe(first, Entry("p", ".B", true)))
    }

    @Test
    fun dedupeKeepsDifferentPackagesAndTheirFirstSeenOrder() {
        val a = Entry("a", ".A", false)
        val b = Entry("b", ".B", true)
        val a2 = Entry("a", ".A2", true)
        assertEquals(listOf(a2, b), dedupe(a, b, a2))
    }

    @Test
    fun dedupeOfNothingIsNothing() {
        assertEquals(emptyList<Entry>(), dedupe())
    }

    // ---- seedOrder ----

    private data class SeedItem(val key: String, val label: String, val category: AppCategory, val leanback: Boolean)

    private fun order(vararg items: SeedItem) =
        seedOrder(items.toList(), { it.key }, { it.label }, { it.category }, { it.leanback })

    @Test
    fun seedOrderPutsKnownAppsBeforeOthersThenLeanbackThenAlphabetical() {
        val result = order(
            SeedItem("other-tv", "Aardvark", AppCategory.OTHER, true),
            SeedItem("video-phone", "Zoom Video", AppCategory.VIDEO, false),
            SeedItem("other-phone", "Aaa Phone", AppCategory.OTHER, false),
            SeedItem("game-tv", "Pong", AppCategory.GAMES, true),
            SeedItem("tools-tv", "files", AppCategory.TOOLS, true),
            SeedItem("music-tv", "Beats", AppCategory.MUSIC, true),
        )
        assertEquals(
            listOf("music-tv", "tools-tv", "game-tv", "video-phone", "other-tv", "other-phone"),
            result,
        )
    }

    @Test
    fun seedOrderIsDeterministicForEqualLabels() {
        val a = SeedItem("a", "Same", AppCategory.OTHER, true)
        val b = SeedItem("b", "same", AppCategory.OTHER, true)
        assertEquals(listOf("a", "b"), order(b, a))
    }

    // ---- pin ----

    @Test
    fun pinAppendsWhenAbsent() {
        assertEquals(listOf("a", "b"), pin(FieldLayout(pinned = listOf("a")), "b").pinned)
    }

    @Test
    fun pinRemovesFromHidden() {
        val result = pin(FieldLayout(pinned = listOf("a"), hidden = setOf("b", "c")), "b")
        assertEquals(listOf("a", "b"), result.pinned)
        assertEquals(setOf("c"), result.hidden)
    }

    @Test
    fun pinOfAnAlreadyPinnedKeyKeepsItsPositionAndIsANoOp() {
        val layout = FieldLayout(pinned = listOf("a", "b", "c"))
        assertSame(layout, pin(layout, "a"))
        assertEquals(listOf("a", "b", "c"), pin(layout, "b").pinned)
    }

    @Test
    fun pinOfAnAlreadyPinnedAndHiddenKeyRepairsTheInvariant() {
        val result = pin(FieldLayout(pinned = listOf("a"), hidden = setOf("a")), "a")
        assertEquals(listOf("a"), result.pinned)
        assertEquals(emptySet<String>(), result.hidden)
    }

    @Test
    fun pinKeepsTheSeededFlag() {
        assertTrue(pin(FieldLayout(seeded = true), "a").seeded)
    }

    // ---- unpin ----

    @Test
    fun unpinRemovesTheKeyAndKeepsTheRestInOrder() {
        assertEquals(listOf("a", "c"), unpin(FieldLayout(pinned = listOf("a", "b", "c")), "b").pinned)
    }

    @Test
    fun unpinOfAnAbsentKeyIsANoOp() {
        val layout = FieldLayout(pinned = listOf("a"))
        assertSame(layout, unpin(layout, "zzz"))
    }

    @Test
    fun unpinDoesNotTouchHiddenOrSeeded() {
        val result = unpin(FieldLayout(pinned = listOf("a"), hidden = setOf("h"), seeded = true), "a")
        assertEquals(emptyList<String>(), result.pinned)
        assertEquals(setOf("h"), result.hidden)
        assertTrue(result.seeded)
    }

    // ---- move ----

    private val abcd = FieldLayout(pinned = listOf("a", "b", "c", "d"))

    @Test
    fun moveForward() {
        assertEquals(listOf("b", "c", "a", "d"), move(abcd, "a", 2).pinned)
    }

    @Test
    fun moveBackward() {
        assertEquals(listOf("d", "a", "b", "c"), move(abcd, "d", 0).pinned)
    }

    @Test
    fun moveToTheLastIndex() {
        assertEquals(listOf("b", "c", "d", "a"), move(abcd, "a", 3).pinned)
    }

    @Test
    fun moveClampsAboveAndBelow() {
        assertEquals(listOf("b", "c", "d", "a"), move(abcd, "a", 99).pinned)
        assertEquals(listOf("d", "a", "b", "c"), move(abcd, "d", -5).pinned)
    }

    @Test
    fun moveToTheSamePlaceIsANoOp() {
        assertSame(abcd, move(abcd, "b", 1))
    }

    @Test
    fun moveOfAnUnpinnedKeyIsANoOp() {
        assertSame(abcd, move(abcd, "zzz", 0))
    }

    @Test
    fun moveKeepsHiddenAndSeeded() {
        val layout = FieldLayout(pinned = listOf("a", "b"), hidden = setOf("h"), seeded = true)
        val result = move(layout, "a", 1)
        assertEquals(listOf("b", "a"), result.pinned)
        assertEquals(setOf("h"), result.hidden)
        assertTrue(result.seeded)
    }

    @Test
    fun moveIndexIsRelativeToVisibleEntriesWhenSomePinnedAppsAreUninstalled() {
        // "x" and "y" are pinned but not installed, so the UI sees [a, b, c].
        val layout = FieldLayout(pinned = listOf("a", "x", "b", "y", "c"))
        val visible = { k: String -> k != "x" && k != "y" }
        // Move a to the end of the UI list.
        assertEquals(listOf("x", "b", "y", "c", "a"), move(layout, "a", 2, visible).pinned)
        // Move c to the front.
        assertEquals(listOf("c", "a", "x", "b", "y"), move(layout, "c", 0, visible).pinned)
        // Move a between b and c (UI index 1).
        assertEquals(listOf("x", "b", "y", "a", "c"), move(layout, "a", 1, visible).pinned)
        // Clamped.
        assertEquals(listOf("x", "b", "y", "c", "a"), move(layout, "a", 50, visible).pinned)
    }

    @Test
    fun moveWithNothingElseVisibleIsANoOp() {
        val layout = FieldLayout(pinned = listOf("a", "x"))
        assertSame(layout, move(layout, "a", 0) { it == "a" })
    }

    @Test
    fun moveOnASingleEntryIsANoOp() {
        val layout = FieldLayout(pinned = listOf("a"))
        assertSame(layout, move(layout, "a", 0))
        assertSame(layout, move(layout, "a", 5))
    }

    // ---- hide / unhide ----

    @Test
    fun hideRemovesFromPinnedAndAddsToHidden() {
        val result = hide(FieldLayout(pinned = listOf("a", "b", "c")), "b")
        assertEquals(listOf("a", "c"), result.pinned)
        assertEquals(setOf("b"), result.hidden)
    }

    @Test
    fun hideOfAnUnpinnedAppJustHidesIt() {
        val result = hide(FieldLayout(pinned = listOf("a")), "z")
        assertEquals(listOf("a"), result.pinned)
        assertEquals(setOf("z"), result.hidden)
    }

    @Test
    fun hideIsIdempotent() {
        val once = hide(FieldLayout(pinned = listOf("a", "b")), "a")
        assertSame(once, hide(once, "a"))
    }

    @Test
    fun unhideRemovesFromHiddenWithoutRepinning() {
        val result = unhide(FieldLayout(pinned = listOf("a"), hidden = setOf("b", "c")), "b")
        assertEquals(setOf("c"), result.hidden)
        assertEquals(listOf("a"), result.pinned)
    }

    @Test
    fun unhideOfAVisibleKeyIsANoOp() {
        val layout = FieldLayout(hidden = setOf("b"))
        assertSame(layout, unhide(layout, "zzz"))
    }

    // ---- seedDefaults ----

    private val prefs = listOf("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10")

    @Test
    fun seedPinsUpToEightInPreferenceOrderAndMarksSeeded() {
        val result = seedDefaults(FieldLayout(), prefs)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8"), result.pinned)
        assertTrue(result.seeded)
    }

    @Test
    fun seedHonoursACustomMax() {
        assertEquals(listOf("p1", "p2", "p3"), seedDefaults(FieldLayout(), prefs, max = 3).pinned)
        assertEquals(emptyList<String>(), seedDefaults(FieldLayout(), prefs, max = 0).pinned)
        assertEquals(emptyList<String>(), seedDefaults(FieldLayout(), prefs, max = -4).pinned)
    }

    @Test
    fun seedPinsEverythingWhenFewerThanMaxAreInstalled() {
        val result = seedDefaults(FieldLayout(), listOf("p1", "p2"))
        assertEquals(listOf("p1", "p2"), result.pinned)
        assertTrue(result.seeded)
    }

    @Test
    fun seedSkipsHiddenAndDuplicateKeys() {
        val result = seedDefaults(FieldLayout(hidden = setOf("p2")), listOf("p1", "p2", "p1", "p3"), max = 3)
        assertEquals(listOf("p1", "p3"), result.pinned)
        assertEquals(setOf("p2"), result.hidden)
    }

    @Test
    fun seedDoesNothingOnceSeeded() {
        val layout = FieldLayout(pinned = listOf("mine"), seeded = true)
        assertSame(layout, seedDefaults(layout, prefs))
    }

    @Test
    fun aUserWhoUnpinsEverythingDoesNotGetDefaultsBack() {
        var layout = seedDefaults(FieldLayout(), prefs, max = 2)
        assertEquals(listOf("p1", "p2"), layout.pinned)
        layout = unpin(unpin(layout, "p1"), "p2")
        assertEquals(emptyList<String>(), layout.pinned)
        assertTrue(layout.seeded)
        assertSame(layout, seedDefaults(layout, prefs))
        assertEquals(emptyList<String>(), seedDefaults(layout, prefs).pinned)
    }

    @Test
    fun aUserWhoHidesEverythingDoesNotGetDefaultsBack() {
        var layout = seedDefaults(FieldLayout(), listOf("p1"))
        layout = hide(layout, "p1")
        assertEquals(emptyList<String>(), seedDefaults(layout, prefs).pinned)
    }

    @Test
    fun seedKeepsExistingPinsOfAnUnseededLayoutAndJustMarksItSeeded() {
        val result = seedDefaults(FieldLayout(pinned = listOf("mine")), prefs)
        assertEquals(listOf("mine"), result.pinned)
        assertTrue(result.seeded)
    }

    @Test
    fun seedOfNothingStillMarksSeeded() {
        val result = seedDefaults(FieldLayout(), emptyList())
        assertEquals(emptyList<String>(), result.pinned)
        assertTrue(result.seeded)
    }

    // ---- The transforms compose through a LayoutStore the way the repository uses them ----

    @Test
    fun transformsComposeThroughTheStore() = runBlocking {
        val store = InMemoryLayoutStore()
        store.update { seedDefaults(it, listOf("a", "b", "c"), max = 2) }
        store.update { pin(it, "c") }
        store.update { move(it, "c", 0) }
        store.update { hide(it, "a") }
        assertEquals(FieldLayout(pinned = listOf("c", "b"), hidden = setOf("a"), seeded = true), store.layout.first())
        store.update { unhide(it, "a") }
        store.update { unpin(it, "b") }
        assertEquals(FieldLayout(pinned = listOf("c"), seeded = true), store.layout.first())
    }
}
