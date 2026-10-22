package com.andrewevmiller.tvlauncher.home.data

/**
 * Coarse grouping used by the chip filters and to pick a launch motif's timbre.
 * Kept in its own file with no Android imports so the sound core can use it in JVM tests.
 */
enum class AppCategory { VIDEO, MUSIC, GAMES, TOOLS, OTHER }
