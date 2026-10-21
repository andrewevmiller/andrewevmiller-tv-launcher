package com.andrewevmiller.tvlauncher.ui.icons

/**
 * One primitive of a line icon, as SVG path data in a 24 x 24 viewBox.
 *
 * [filled] primitives are drawn with fill only (the mockup's `fill="currentColor"` dots); all others are
 * stroked with no fill.
 */
data class IconPath(val d: String, val filled: Boolean = false)

/**
 * Path data for the line-icon set, copied from the `const ICON = {...}` object in
 * docs/mockup/field-launcher-mockup.html. Primitive order is kept. Each `<circle cx cy r>` is written as the
 * arc path `M{cx-r},{cy} a{r},{r} 0 1,0 {2r},0 a{r},{r} 0 1,0 {-2r},0 z`, and each rounded `<rect x y width height rx>`
 * as `M{x+rx},{y} H{x+w-rx} A{rx},{rx} 0 0 1 {x+w},{y+rx} V{y+h-rx} A.. {x+w-rx},{y+h} H{x+rx} A.. {x},{y+h-rx} V{y+rx} A.. {x+rx},{y} z`.
 *
 * `IconPathsTest` re-reads the mockup and fails if any entry here drifts from it.
 * Pure Kotlin on purpose: no Android imports, so `tools/purecheck` can compile and test it.
 */
object IconPaths {
    val all: Map<String, List<IconPath>> = mapOf(
        "play" to listOf(
            IconPath("M7 5.5v13l11-6.5z"),
        ),
        "note" to listOf(
            IconPath("M9 18V6l10-2v12"),
            IconPath("M4,18 a2.5,2.5 0 1,0 5,0 a2.5,2.5 0 1,0 -5,0 z"),
            IconPath("M14,16 a2.5,2.5 0 1,0 5,0 a2.5,2.5 0 1,0 -5,0 z"),
        ),
        "pad" to listOf(
            IconPath("M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6L13.5 14h-3L9 15.6A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z"),
            IconPath("M7 11v3M5.5 12.5h3"),
            IconPath("M15.4,11.5 a0.6,0.6 0 1,0 1.2,0 a0.6,0.6 0 1,0 -1.2,0 z", filled = true),
            IconPath("M17.4,13.5 a0.6,0.6 0 1,0 1.2,0 a0.6,0.6 0 1,0 -1.2,0 z", filled = true),
        ),
        "tool" to listOf(
            IconPath("M14.5 6.5a4 4 0 0 0 5 5L12 19a2.1 2.1 0 0 1-3-3z"),
            IconPath("M14.5 6.5 17 4l3 3-2.5 2.5"),
        ),
        "wave" to listOf(
            IconPath("M2 12h3l2-6 3 12 3-9 2 5 2-2h5"),
        ),
        "port" to listOf(
            IconPath("M4 8h16v5l-2 3H6l-2-3z"),
            IconPath("M8 11h8"),
        ),
        "gear" to listOf(
            IconPath("M9,12 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 z"),
            IconPath("M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"),
        ),
        "wifi" to listOf(
            IconPath("M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0"),
            IconPath("M11.2,19 a0.8,0.8 0 1,0 1.6,0 a0.8,0.8 0 1,0 -1.6,0 z", filled = true),
        ),
        "search" to listOf(
            IconPath("M4.5,10.5 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 z"),
            IconPath("m15 15 5.5 5.5"),
        ),
        "folder" to listOf(
            IconPath("M3 6.5h6l2 2h10V18H3z"),
        ),
        "grid" to listOf(
            IconPath("M5.2,4 H9.3 A1.2,1.2 0 0 1 10.5,5.2 V9.3 A1.2,1.2 0 0 1 9.3,10.5 H5.2 A1.2,1.2 0 0 1 4,9.3 V5.2 A1.2,1.2 0 0 1 5.2,4 z"),
            IconPath("M14.7,4 H18.8 A1.2,1.2 0 0 1 20,5.2 V9.3 A1.2,1.2 0 0 1 18.8,10.5 H14.7 A1.2,1.2 0 0 1 13.5,9.3 V5.2 A1.2,1.2 0 0 1 14.7,4 z"),
            IconPath("M5.2,13.5 H9.3 A1.2,1.2 0 0 1 10.5,14.7 V18.8 A1.2,1.2 0 0 1 9.3,20 H5.2 A1.2,1.2 0 0 1 4,18.8 V14.7 A1.2,1.2 0 0 1 5.2,13.5 z"),
            IconPath("M14.7,13.5 H18.8 A1.2,1.2 0 0 1 20,14.7 V18.8 A1.2,1.2 0 0 1 18.8,20 H14.7 A1.2,1.2 0 0 1 13.5,18.8 V14.7 A1.2,1.2 0 0 1 14.7,13.5 z"),
        ),
        "moon" to listOf(
            IconPath("M20 14.2A8.5 8.5 0 1 1 9.8 4a8 8 0 0 0 10.2 10.2z"),
        ),
        "points" to listOf(
            IconPath("M5,16 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 z", filled = true),
            IconPath("M8,9 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 z", filled = true),
            IconPath("M12,14 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 z", filled = true),
            IconPath("M16,7 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 z", filled = true),
            IconPath("M17,17 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 z", filled = true),
            IconPath("M3 21h18M3 3v18"),
        ),
    )
}
