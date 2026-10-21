package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartStrategiesTest {
    private fun names(list: List<StartStrategy>) = list.map { it.name }

    @Test
    fun defaultOrderIsSomnambulatorThenDreamPackage() {
        assertEquals(
            listOf(StartStrategies.SOMNAMBULATOR, StartStrategies.DREAM_PACKAGE_ACTIVITY),
            names(StartStrategies.ordered(null)),
        )
    }

    @Test
    fun provenStrategyMovesToTheFront() {
        assertEquals(
            listOf(StartStrategies.DREAM_PACKAGE_ACTIVITY, StartStrategies.SOMNAMBULATOR),
            names(StartStrategies.ordered(StartStrategies.DREAM_PACKAGE_ACTIVITY)),
        )
        assertEquals(
            listOf(StartStrategies.SOMNAMBULATOR, StartStrategies.DREAM_PACKAGE_ACTIVITY),
            names(StartStrategies.ordered(StartStrategies.SOMNAMBULATOR)),
        )
    }

    @Test
    fun provenNameIsTrimmedAndCaseInsensitive() {
        assertEquals(
            StartStrategies.DREAM_PACKAGE_ACTIVITY,
            StartStrategies.ordered("  Dream-Package-Activity ").first().name,
        )
    }

    @Test
    fun unknownBlankOrNullProvenNameChangesNothing() {
        val default = names(StartStrategies.DEFAULT_ORDER)
        assertEquals(default, names(StartStrategies.ordered("telepathy")))
        assertEquals(default, names(StartStrategies.ordered("")))
        assertEquals(default, names(StartStrategies.ordered("   ")))
    }

    @Test
    fun orderingNeverDropsOrDuplicatesAStrategy() {
        val three = listOf(StartStrategy("a", ""), StartStrategy("b", ""), StartStrategy("c", ""))
        assertEquals(listOf("c", "a", "b"), names(StartStrategies.ordered("c", three)))
        assertEquals(listOf("b", "a", "c"), names(StartStrategies.ordered("b", three)))
        assertEquals(3, StartStrategies.ordered("c", three).toSet().size)
    }

    // --- runner ---

    private fun s(name: String) = StartStrategy(name, "")

    @Test
    fun runnerStopsAtTheFirstStrategyThatStarts() {
        val tried = ArrayList<String>()
        val logs = ArrayList<String>()
        val ok = runStartStrategies(
            listOf(s("a"), s("b"), s("c")),
            { st ->
                tried.add(st.name)
                if (st.name == "b") StartAttempt(StartOutcome.STARTED, "x/y") else StartAttempt(StartOutcome.NOT_RESOLVED, "nope")
            },
            { logs.add(it) },
        )
        assertTrue(ok)
        assertEquals(listOf("a", "b"), tried)
        assertEquals(2, logs.size)
        assertTrue(logs[0], logs[0].contains("'a'") && logs[0].contains("NOT_RESOLVED") && logs[0].contains("nope"))
        assertTrue(logs[1], logs[1].contains("'b'") && logs[1].contains("STARTED"))
    }

    @Test
    fun runnerReturnsFalseWhenNothingStartsAndSaysSo() {
        val logs = ArrayList<String>()
        val ok = runStartStrategies(
            StartStrategies.DEFAULT_ORDER,
            { StartAttempt(StartOutcome.NOT_RESOLVED) },
            { logs.add(it) },
        )
        assertFalse(ok)
        assertEquals(3, logs.size)
        assertTrue(logs.last().contains("no start strategy worked"))
    }

    @Test
    fun runnerTreatsAThrowingAttemptAsFailedAndKeepsGoing() {
        val logs = ArrayList<String>()
        val ok = runStartStrategies(
            listOf(s("boom"), s("fine")),
            { st ->
                if (st.name == "boom") throw SecurityException("denied")
                StartAttempt(StartOutcome.STARTED)
            },
            { logs.add(it) },
        )
        assertTrue(ok)
        assertTrue(logs[0], logs[0].contains("FAILED") && logs[0].contains("SecurityException") && logs[0].contains("denied"))
    }

    @Test
    fun emptyOrderIsFalse() {
        assertFalse(runStartStrategies(emptyList(), { StartAttempt(StartOutcome.STARTED) }, { }))
    }

    // --- dream package activity picker ---

    private fun cand(
        name: String,
        exported: Boolean = true,
        leanback: Boolean = false,
        launcher: Boolean = false,
    ) = DreamActivityCandidate("com.x.$name", exported, leanback, launcher)

    @Test
    fun pickerPrefersAmbientNamesThenLeanbackThenLauncher() {
        val ambient = cand("AmbientActivity")
        val leanback = cand("TvHomeActivity", leanback = true, launcher = true)
        val launcher = cand("MainActivity", launcher = true)
        val other = cand("DeepLinkActivity")
        assertEquals(ambient, DreamActivityPicker.pick(listOf(other, launcher, leanback, ambient)))
        assertEquals(leanback, DreamActivityPicker.pick(listOf(other, launcher, leanback)))
        assertEquals(launcher, DreamActivityPicker.pick(listOf(other, launcher)))
        assertEquals(other, DreamActivityPicker.pick(listOf(other)))
    }

    @Test
    fun pickerNeverChoosesUnexportedOrSettingsLikeActivities() {
        assertNull(DreamActivityPicker.pick(listOf(cand("AmbientActivity", exported = false))))
        assertNull(DreamActivityPicker.pick(listOf(cand("AmbientSettingsActivity", leanback = true))))
        assertNull(DreamActivityPicker.pick(listOf(cand("SetupWizard", launcher = true))))
        assertNull(DreamActivityPicker.pick(emptyList()))
    }

    @Test
    fun pickerKeepsTheFirstOfEqualScores() {
        val a = cand("AlphaActivity", launcher = true)
        val b = cand("BetaActivity", launcher = true)
        assertEquals(a, DreamActivityPicker.pick(listOf(a, b)))
    }

    @Test
    fun otherPackageIsOnlyTriedWhenTheSelectedDreamIsNotOurs() {
        val own = "com.andrewevmiller.tvlauncher"
        assertFalse(DreamActivityPicker.shouldTryPackage(null, own))
        assertFalse(DreamActivityPicker.shouldTryPackage(ComponentRef(own, "$own.ambient.OxyfieldDreamService"), own))
        assertTrue(DreamActivityPicker.shouldTryPackage(ScreensaverComponents.GOOGLE_PHOTOS, own))
    }
}
