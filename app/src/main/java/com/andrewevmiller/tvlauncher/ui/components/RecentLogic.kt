package com.andrewevmiller.tvlauncher.ui.components

/**
 * Text and counting rules for the collapsible Recent row, kept free of Compose so they are unit-tested.
 *
 * The caller owns the `expanded` state (collapsed by default, collapses again on Home) and builds the
 * section label with [recentSectionLabel] so the hidden count sits next to "Recent", as in the mockup.
 */

/** The toggle chip's label: "Show recent" while collapsed, "Hide" while expanded. */
fun recentToggleLabel(expanded: Boolean): String = if (expanded) "Hide" else "Show recent"

/** "5 hidden". A negative count is treated as zero. */
fun hiddenCountLabel(count: Int): String = "${count.coerceAtLeast(0)} hidden"

/**
 * The Recent section label: "Recent · 5 hidden" while collapsed, plain "Recent" once expanded
 * (nothing is hidden then). The home frame upper-cases section labels.
 */
fun recentSectionLabel(expanded: Boolean, count: Int): String =
    if (expanded) "Recent" else "Recent · ${hiddenCountLabel(count)}"

/**
 * Number of focusable items in the Recent row: the toggle chip alone when collapsed,
 * the chip plus every entry when expanded. Used for the row-end edge sound.
 */
fun recentFocusableCount(expanded: Boolean, itemCount: Int): Int =
    if (expanded) 1 + itemCount.coerceAtLeast(0) else 1
