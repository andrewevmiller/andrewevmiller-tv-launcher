package com.andrewevmiller.tvlauncher.sleep

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepEndTest {
    private val paused = PauseReport(PauseResult.PAUSED, listOf("media-key"))

    /** Runs the sequence with every step recording itself into [events]; [configure] can make one step fail. */
    private fun run(
        events: MutableList<String>,
        logs: MutableList<String> = ArrayList(),
        pause: suspend () -> PauseReport? = {
            events.add("pause")
            paused
        },
        sound: () -> Unit = { events.add("sound") },
        dim: () -> Boolean = {
            events.add("dim")
            true
        },
        settle: suspend (Long) -> Unit = { events.add("settle $it") },
    ): SleepEndOutcome = runBlocking {
        runSleepEnd(
            pause = pause,
            closingSound = sound,
            showDimScreen = dim,
            settle = settle,
            log = { logs.add(it) },
        )
    }

    @Test
    fun stepsRunInTheDocumentedOrder() {
        val events = ArrayList<String>()
        val outcome = run(events)
        assertEquals(listOf("pause", "settle 600", "sound", "settle 1200", "dim"), events)
        assertEquals(paused, outcome.pause)
        assertTrue(outcome.dimShown)
        assertTrue(outcome.errors.isEmpty())
    }

    @Test
    fun aNullPauseReportStillRunsTheRestInOrder() {
        val events = ArrayList<String>()
        val outcome = run(events, pause = { events.add("pause"); null })
        assertEquals(listOf("pause", "settle 600", "sound", "settle 1200", "dim"), events)
        assertNull(outcome.pause)
        assertTrue(outcome.dimShown)
        assertTrue(outcome.errors.isEmpty())
    }

    @Test
    fun aThrowingPauseStillRunsSoundAndDim() {
        val events = ArrayList<String>()
        val logs = ArrayList<String>()
        val outcome = run(
            events,
            logs,
            pause = {
                events.add("pause")
                throw IllegalStateException("no player")
            },
        )
        assertEquals(listOf("pause", "settle 600", "sound", "settle 1200", "dim"), events)
        assertNull(outcome.pause)
        assertTrue(outcome.dimShown)
        assertEquals(listOf("pause FAILED: IllegalStateException: no player"), outcome.errors)
        assertTrue(logs.contains("sleep end: pause FAILED: IllegalStateException: no player"))
    }

    @Test
    fun aThrowingSoundStillShowsTheDimScreen() {
        val events = ArrayList<String>()
        val outcome = run(
            events,
            sound = {
                events.add("sound")
                throw SecurityException("no audio")
            },
        )
        assertEquals(listOf("pause", "settle 600", "sound", "settle 1200", "dim"), events)
        assertTrue(outcome.dimShown)
        assertEquals(listOf("closing sound FAILED: SecurityException: no audio"), outcome.errors)
        assertEquals(paused, outcome.pause)
    }

    @Test
    fun aThrowingDimScreenIsReportedAsNotShown() {
        val events = ArrayList<String>()
        val outcome = run(
            events,
            dim = { throw IllegalStateException("no overlay") },
        )
        assertFalse(outcome.dimShown)
        assertEquals(listOf("dim screen FAILED: IllegalStateException: no overlay"), outcome.errors)
    }

    @Test
    fun aDimScreenThatReportsNotShownIsNotAnError() {
        val events = ArrayList<String>()
        val outcome = run(events, dim = { events.add("dim"); false })
        assertFalse(outcome.dimShown)
        assertTrue(outcome.errors.isEmpty())
    }

    @Test
    fun aThrowingSettleStillRunsTheRest() {
        val events = ArrayList<String>()
        var calls = 0
        val outcome = run(
            events,
            settle = {
                calls++
                events.add("settle $it")
                if (calls == 1) throw IllegalStateException("interrupted")
            },
        )
        assertEquals(listOf("pause", "settle 600", "sound", "settle 1200", "dim"), events)
        assertTrue(outcome.dimShown)
        assertEquals(listOf("settle after pause FAILED: IllegalStateException: interrupted"), outcome.errors)
    }

    @Test
    fun everyFailureIsKeptInOrderAndTheDimScreenStillRuns() {
        val events = ArrayList<String>()
        val outcome = run(
            events,
            pause = { throw IllegalStateException("a") },
            sound = { throw IllegalStateException("b") },
        )
        assertTrue(outcome.dimShown)
        assertEquals(
            listOf("pause FAILED: IllegalStateException: a", "closing sound FAILED: IllegalStateException: b"),
            outcome.errors,
        )
        assertEquals(listOf("settle 600", "settle 1200", "dim"), events)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val events = ArrayList<String>()
        var thrown: Throwable? = null
        try {
            run(events, pause = { throw CancellationException("scope cancelled") })
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue("expected a CancellationException", thrown is CancellationException)
        assertFalse(events.contains("dim"))
    }
}
