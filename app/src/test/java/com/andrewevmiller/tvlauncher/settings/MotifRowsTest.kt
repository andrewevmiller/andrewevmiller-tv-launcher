package com.andrewevmiller.tvlauncher.settings

import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.sound.MotifOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotifRowsTest {

    private val youtube = MotifApp("YouTube", "com.google.android.youtube.tv")
    private val netflix = MotifApp("Netflix", "com.netflix.ninja")
    private val plex = MotifApp("Plex", "com.plexapp.android")
    private val files = MotifApp("files", "com.alphainventor.filemanager")

    @Test
    fun rowsAreSortedByLabelIgnoringCase() {
        val rows = buildMotifRows(listOf(youtube, plex, files, netflix), emptyMap())
        assertEquals(listOf("files", "Netflix", "Plex", "YouTube"), rows.map { it.label })
    }

    @Test
    fun orderIsDeterministicWhateverTheInputOrder() {
        val a = buildMotifRows(listOf(youtube, plex, files, netflix), emptyMap())
        val b = buildMotifRows(listOf(netflix, files, youtube, plex), emptyMap())
        val c = buildMotifRows(listOf(plex, netflix, youtube, files).reversed(), emptyMap())
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun sameLabelBreaksTiesByPackageName() {
        val one = MotifApp("Player", "b.player")
        val two = MotifApp("Player", "a.player")
        assertEquals(listOf("a.player", "b.player"), buildMotifRows(listOf(one, two), emptyMap()).map { it.packageName })
        assertEquals(listOf("a.player", "b.player"), buildMotifRows(listOf(two, one), emptyMap()).map { it.packageName })
    }

    @Test
    fun anAppWithSeveralLauncherActivitiesAppearsOnce() {
        val main = MotifApp("Plex", "com.plexapp.android")
        val second = MotifApp("Plex Live", "com.plexapp.android")
        val rows = buildMotifRows(listOf(second, main), emptyMap())
        assertEquals(1, rows.size)
        assertEquals("Plex", rows.single().label)
    }

    @Test
    fun overridesAreAttachedByPackageName() {
        val overrides = mapOf(
            plex.packageName to MotifOverride(rerollCount = 3),
            netflix.packageName to MotifOverride(muted = true),
        )
        val rows = buildMotifRows(listOf(youtube, plex, netflix), overrides).associateBy { it.packageName }
        assertEquals(MotifOverride(rerollCount = 3), rows.getValue(plex.packageName).override)
        assertEquals(MotifOverride(muted = true), rows.getValue(netflix.packageName).override)
        assertEquals(MotifOverride(), rows.getValue(youtube.packageName).override)
    }

    @Test
    fun overridesForUninstalledAppsAreIgnored() {
        val rows = buildMotifRows(listOf(plex), mapOf("gone.app" to MotifOverride(rerollCount = 9)))
        assertEquals(listOf(MotifRow("Plex", plex.packageName)), rows)
    }

    @Test
    fun emptyInputGivesNoRows() {
        assertTrue(buildMotifRows(emptyList(), mapOf("x" to MotifOverride(muted = true))).isEmpty())
    }

    @Test
    fun statusLabelsMatchThePlan() {
        assertEquals("rerolled 3×", MotifRow("Plex", "p", MotifOverride(rerollCount = 3)).statusLabel)
        assertEquals("rerolled 1×", MotifRow("Plex", "p", MotifOverride(rerollCount = 1)).statusLabel)
        assertEquals("muted", MotifRow("Plex", "p", MotifOverride(muted = true)).statusLabel)
        assertEquals("rerolled 2× · muted", MotifRow("Plex", "p", MotifOverride(2, true)).statusLabel)
        assertNull(MotifRow("Plex", "p").statusLabel)
    }

    @Test
    fun isOverriddenDrivesTheResetAction() {
        assertFalse(MotifRow("Plex", "p").isOverridden)
        assertTrue(MotifRow("Plex", "p", MotifOverride(rerollCount = 1)).isOverridden)
        assertTrue(MotifRow("Plex", "p", MotifOverride(muted = true)).isOverridden)
    }

    @Test
    fun keysAreUniquePerPackageAndStableAcrossOverrideChanges() {
        val before = buildMotifRows(listOf(youtube, plex, netflix), emptyMap())
        val after = buildMotifRows(listOf(youtube, plex, netflix), mapOf(plex.packageName to MotifOverride(rerollCount = 5)))
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(before.size, before.map { it.key }.toSet().size)
    }

    @Test
    fun aRerollNeverMovesItsRow() {
        val apps = listOf(youtube, plex, files, netflix)
        val order0 = buildMotifRows(apps, emptyMap()).map { it.packageName }
        val order1 = buildMotifRows(apps, mapOf(files.packageName to MotifOverride(rerollCount = 1))).map { it.packageName }
        assertEquals(order0, order1)
    }

    // ----- re-roll then play -----

    @Test
    fun rerollWritesTheOverrideThenWaitsThenPlays() = runBlocking {
        val overrides = FakeMotifOverrides()
        val events = mutableListOf<String>()
        rerollMotif(
            overrides,
            plex.packageName,
            settle = { events += "settle:" + overrides.overrides.first()[plex.packageName]?.rerollCount },
            play = { events += "play" },
        )
        // The new count is already stored when the settle pause starts, and play comes last.
        assertEquals(listOf("settle:1", "play"), events)
    }

    @Test
    fun repeatedRerollsCountUpAndResetClears() = runBlocking {
        val overrides = FakeMotifOverrides()
        var plays = 0
        repeat(3) { rerollMotif(overrides, plex.packageName, settle = {}, play = { plays++ }) }
        assertEquals(3, plays)
        val rows = buildMotifRows(listOf(plex), overrides.overrides.first())
        assertEquals("rerolled 3×", rows.single().statusLabel)

        overrides.reset(plex.packageName)
        assertNull(buildMotifRows(listOf(plex), overrides.overrides.first()).single().statusLabel)
    }

    @Test
    fun muteToggleShowsInTheRow() = runBlocking {
        val overrides = FakeMotifOverrides()
        overrides.setMuted(netflix.packageName, true)
        assertEquals("muted", buildMotifRows(listOf(netflix), overrides.overrides.first()).single().statusLabel)
        overrides.setMuted(netflix.packageName, false)
        assertFalse(buildMotifRows(listOf(netflix), overrides.overrides.first()).single().isOverridden)
    }
}
