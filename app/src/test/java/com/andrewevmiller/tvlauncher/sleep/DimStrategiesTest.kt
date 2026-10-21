package com.andrewevmiller.tvlauncher.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DimStrategiesTest {
    private val names get() = DimStrategies.DEFAULT_ORDER.map { it.name }

    @Test
    fun defaultOrderIsOwnActivityThenScreensaver() {
        assertEquals(listOf("own-activity", "screensaver"), names)
        assertEquals(DimStrategies.DEFAULT_ORDER, DimStrategies.ordered(null))
    }

    @Test
    fun orderedMovesAKnownNameToTheFrontAndKeepsTheRest() {
        assertEquals(
            listOf("screensaver", "own-activity"),
            DimStrategies.ordered(DimStrategies.SCREENSAVER).map { it.name },
        )
        assertEquals(
            listOf("own-activity", "screensaver"),
            DimStrategies.ordered(DimStrategies.OWN_ACTIVITY).map { it.name },
        )
        // Case and surrounding spaces do not matter.
        assertEquals(
            listOf("screensaver", "own-activity"),
            DimStrategies.ordered("  Screensaver ").map { it.name },
        )
    }

    @Test
    fun orderedIgnoresBlankAndUnknownNames() {
        for (bad in listOf("", "   ", "overlay", "somnambulator")) {
            assertSame("for '$bad'", DimStrategies.DEFAULT_ORDER, DimStrategies.ordered(bad))
        }
    }

    @Test
    fun theSleepConfigConstantsNameRealThings() {
        // Whatever the owner puts in SleepConfig must be a no-op or a real name, never a typo that silently does nothing.
        val dim = SleepConfig.PROVEN_DIM_STRATEGY
        assertTrue(dim == null || names.contains(dim))
        val pause = SleepConfig.PROVEN_PAUSE_RUNG
        assertTrue(pause == null || PauseRungs.DEFAULT_ORDER.any { it.name == pause })
    }

    // ---- runDimStrategies -----------------------------------------------------------------------------

    private fun run(
        order: List<DimStrategy> = DimStrategies.DEFAULT_ORDER,
        logs: MutableList<String> = ArrayList(),
        tried: MutableList<String> = ArrayList(),
        attempt: (DimStrategy) -> DimAttempt,
    ): Boolean = runDimStrategies(order, { s -> tried.add(s.name); attempt(s) }, { logs.add(it) })

    @Test
    fun stopsAtTheFirstStrategyThatWasShown() {
        val tried = ArrayList<String>()
        val shown = run(tried = tried) { DimAttempt(DimOutcome.SHOWN) }
        assertTrue(shown)
        assertEquals(listOf("own-activity"), tried)
    }

    @Test
    fun carriesOnPastAnythingThatWasNotShown() {
        for (miss in listOf(DimOutcome.NOT_SHOWN, DimOutcome.NOT_RESOLVED, DimOutcome.FAILED)) {
            val tried = ArrayList<String>()
            val shown = run(tried = tried) { s ->
                if (s.name == DimStrategies.OWN_ACTIVITY) DimAttempt(miss, "x") else DimAttempt(DimOutcome.SHOWN)
            }
            assertTrue("after $miss", shown)
            assertEquals("after $miss", listOf("own-activity", "screensaver"), tried)
        }
    }

    @Test
    fun anAttemptThatThrowsCountsAsFailedAndTheNextOneRuns() {
        val tried = ArrayList<String>()
        val logs = ArrayList<String>()
        val shown = run(tried = tried, logs = logs) { s ->
            if (s.name == DimStrategies.OWN_ACTIVITY) throw SecurityException("blocked") else DimAttempt(DimOutcome.SHOWN)
        }
        assertTrue(shown)
        assertEquals(listOf("own-activity", "screensaver"), tried)
        assertTrue(logs.any { it.contains("own-activity") && it.contains("FAILED") && it.contains("SecurityException: blocked") })
    }

    @Test
    fun falseWhenNothingWorkedAndSaysSo() {
        val logs = ArrayList<String>()
        val tried = ArrayList<String>()
        assertFalse(run(tried = tried, logs = logs) { DimAttempt(DimOutcome.NOT_SHOWN) })
        assertEquals(listOf("own-activity", "screensaver"), tried)
        assertEquals("dim screen not shown (2 strategies tried)", logs.last())
    }

    @Test
    fun anEmptyOrderIsNotShown() {
        assertFalse(run(order = emptyList()) { DimAttempt(DimOutcome.SHOWN) })
    }

    @Test
    fun theProvenStrategyIsTriedFirst() {
        val tried = ArrayList<String>()
        run(order = DimStrategies.ordered("screensaver"), tried = tried) { DimAttempt(DimOutcome.NOT_SHOWN) }
        assertEquals(listOf("screensaver", "own-activity"), tried)
    }

    // ---- pollUntil ------------------------------------------------------------------------------------

    @Test
    fun pollUntilReturnsAtOnceWhenTheConditionAlreadyHolds() {
        val slept = ArrayList<Long>()
        assertTrue(pollUntil(3_000, 100, { slept.add(it) }) { true })
        assertTrue(slept.isEmpty())
    }

    @Test
    fun pollUntilWaitsInStepsAndStopsWhenItHolds() {
        val slept = ArrayList<Long>()
        assertTrue(pollUntil(3_000, 100, { slept.add(it) }) { slept.size == 3 })
        assertEquals(listOf(100L, 100L, 100L), slept)
    }

    @Test
    fun pollUntilGivesUpAfterTheTimeoutHasBeenSleptThrough() {
        val slept = ArrayList<Long>()
        assertFalse(pollUntil(3_000, 100, { slept.add(it) }) { false })
        assertEquals(30, slept.size)
        assertEquals(3_000L, slept.sum())
    }

    // ---- attemptDim: what each strategy asks of the platform ------------------------------------------

    private class FakeDimHost : DimHost {
        val calls = ArrayList<String>()
        var shownAfterPolls = Int.MAX_VALUE
        var polls = 0
        var startThrows: Exception? = null
        var oxyfield = true
        var screensaverStarts = true
        var screensaverThrows: Exception? = null
        var consumedAfterPolls = Int.MAX_VALUE

        override fun startDimActivity() {
            calls.add("startDimActivity")
            startThrows?.let { throw it }
        }

        override fun dimActivityShown(): Boolean {
            calls.add("dimActivityShown")
            return polls++ >= shownAfterPolls
        }

        override fun abandonDimActivity() { calls.add("abandonDimActivity") }
        override fun oxyfieldIsActiveScreensaver(): Boolean { calls.add("oxyfieldIsActive"); return oxyfield }
        override fun requestDreamDim() { calls.add("requestDreamDim") }

        override fun startScreensaver(): Boolean {
            calls.add("startScreensaver")
            screensaverThrows?.let { throw it }
            return screensaverStarts
        }

        override fun dreamDimConsumed(): Boolean {
            calls.add("dreamDimConsumed")
            return polls++ >= consumedAfterPolls
        }

        override fun clearDreamDim() { calls.add("clearDreamDim") }
    }

    private val ownActivity = DimStrategies.DEFAULT_ORDER[0]
    private val screensaver = DimStrategies.DEFAULT_ORDER[1]

    private fun attempt(
        strategy: DimStrategy,
        host: DimHost,
        requested: MutableList<String> = ArrayList(),
        slept: MutableList<Long> = ArrayList(),
    ): DimAttempt = attemptDim(strategy, host, { slept.add(it) }, { requested.add("requested") })

    @Test
    fun ownActivityIsShownOnceItIsCreated() {
        val host = FakeDimHost().apply { shownAfterPolls = 2 }
        val requested = ArrayList<String>()
        val result = attempt(ownActivity, host, requested)
        assertEquals(DimOutcome.SHOWN, result.outcome)
        assertEquals(listOf("requested"), requested)
        assertEquals(
            listOf("startDimActivity", "dimActivityShown", "dimActivityShown", "dimActivityShown"),
            host.calls,
        )
    }

    @Test
    fun ownActivityNeverCreatedIsNotShownAndIsAbandoned() {
        val host = FakeDimHost()
        val slept = ArrayList<Long>()
        val requested = ArrayList<String>()
        val result = attempt(ownActivity, host, requested, slept)
        assertEquals(DimOutcome.NOT_SHOWN, result.outcome)
        assertEquals(3_000L, slept.sum())
        assertEquals("abandonDimActivity", host.calls.last())
        // The broadcast may be released as soon as the start was requested, before the 3 s wait.
        assertEquals(listOf("requested"), requested)
    }

    @Test
    fun ownActivityStartThatThrowsPropagatesAndNeverReleasesTheBroadcast() {
        val host = FakeDimHost().apply { startThrows = IllegalStateException("no") }
        val requested = ArrayList<String>()
        try {
            attempt(ownActivity, host, requested)
            org.junit.Assert.fail("expected the throw to propagate to runDimStrategies")
        } catch (e: IllegalStateException) {
            assertEquals("no", e.message)
        }
        assertTrue(requested.isEmpty())
    }

    @Test
    fun screensaverIsNotResolvedUnlessOxyfieldIsTheActiveOne() {
        val host = FakeDimHost().apply { oxyfield = false }
        val result = attempt(screensaver, host)
        assertEquals(DimOutcome.NOT_RESOLVED, result.outcome)
        // Nothing was requested or started: someone else's screensaver would not show our dim screen.
        assertEquals(listOf("oxyfieldIsActive"), host.calls)
    }

    @Test
    fun screensaverRequestsTheDimThenStartsThenWaitsForTheDreamToTakeIt() {
        val host = FakeDimHost().apply { consumedAfterPolls = 1 }
        val requested = ArrayList<String>()
        val result = attempt(screensaver, host, requested)
        assertEquals(DimOutcome.SHOWN, result.outcome)
        assertEquals(
            listOf("oxyfieldIsActive", "requestDreamDim", "startScreensaver", "dreamDimConsumed", "dreamDimConsumed"),
            host.calls,
        )
        assertEquals(listOf("requested"), requested)
    }

    @Test
    fun screensaverThatNobodyPickedUpIsNotShownAndTheRequestIsCleared() {
        val host = FakeDimHost()
        val result = attempt(screensaver, host)
        assertEquals(DimOutcome.NOT_SHOWN, result.outcome)
        assertEquals("clearDreamDim", host.calls.last())
    }

    @Test
    fun screensaverThatRefusesToStartFailsAndClearsTheRequest() {
        val host = FakeDimHost().apply { screensaverStarts = false }
        val requested = ArrayList<String>()
        val result = attempt(screensaver, host, requested)
        assertEquals(DimOutcome.FAILED, result.outcome)
        assertEquals(listOf("oxyfieldIsActive", "requestDreamDim", "startScreensaver", "clearDreamDim"), host.calls)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun screensaverStartThatThrowsClearsTheRequestAndPropagates() {
        val host = FakeDimHost().apply { screensaverThrows = SecurityException("no") }
        try {
            attempt(screensaver, host)
            org.junit.Assert.fail("expected the throw to propagate")
        } catch (e: SecurityException) {
            assertEquals("no", e.message)
        }
        assertEquals("clearDreamDim", host.calls.last())
    }

    @Test
    fun anUnknownStrategyIsNotResolved() {
        val result = attempt(DimStrategy("overlay", "?"), FakeDimHost())
        assertEquals(DimOutcome.NOT_RESOLVED, result.outcome)
    }
}
