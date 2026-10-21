package com.andrewevmiller.tvlauncher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentLogicTest {
    @Test
    fun toggleLabelFollowsState() {
        assertEquals("Show recent", recentToggleLabel(expanded = false))
        assertEquals("Hide", recentToggleLabel(expanded = true))
    }

    @Test
    fun hiddenCountLabelCountsHiddenEntries() {
        assertEquals("5 hidden", hiddenCountLabel(5))
        assertEquals("1 hidden", hiddenCountLabel(1))
        assertEquals("0 hidden", hiddenCountLabel(0))
    }

    @Test
    fun negativeCountIsClampedToZero() {
        assertEquals("0 hidden", hiddenCountLabel(-3))
    }

    @Test
    fun sectionLabelShowsHiddenCountOnlyWhileCollapsed() {
        assertEquals("Recent · 5 hidden", recentSectionLabel(expanded = false, count = 5))
        assertEquals("Recent", recentSectionLabel(expanded = true, count = 5))
    }

    @Test
    fun collapsedRowHasExactlyOneFocusableItem() {
        assertEquals(1, recentFocusableCount(expanded = false, itemCount = 5))
        assertEquals(1, recentFocusableCount(expanded = false, itemCount = 0))
    }

    @Test
    fun expandedRowIsTheChipPlusEveryEntry() {
        assertEquals(6, recentFocusableCount(expanded = true, itemCount = 5))
        assertEquals(1, recentFocusableCount(expanded = true, itemCount = 0))
        assertEquals(1, recentFocusableCount(expanded = true, itemCount = -2))
    }
}
