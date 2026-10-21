package com.andrewevmiller.tvlauncher.sleep

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end sequence with every platform call replaced by a recorder, so the order of events can be read back. */
class SleepEndRunnerTest {
    /** One shared event list: the order of everything that happened, across timer, pause, sound and dim. */
    private val events = ArrayList<String>()
    private val states = ArrayList<SleepTimerState>()

    private inner class FakeTimer(var due: Boolean = true) : SleepEndTimer {
        var state: SleepTimerState = SleepTimerState.Running(deadlineAtMillis = 1_000, totalMillis = 600_000)
            private set

        override fun beginFiring(): Boolean {
            events.add("begin")
            if (!due || state !is SleepTimerState.Running) return false
            due = false // a claim works once
            state = SleepTimerState.Firing
            states.add(state)
            return true
        }

        override fun endFiring() {
            events.add("end")
            if (state is SleepTimerState.Firing) {
                state = SleepTimerState.Idle
                states.add(state)
            }
        }
    }

    private inner class FakeActions : SleepEndActions {
        var playing = true
        var pausesWhenRung: String? = PauseRungs.MEDIA_KEY
        var isPlayingThrows = false
        var performThrows = false
        var shownByStrategy: String? = DimStrategies.OWN_ACTIVITY
        var oxyfield = true
        var soundThrows = false
        var factoryThrows = false

        val performed = ArrayList<String>()
        val dimTried = ArrayList<String>()

        override fun warmUp() { events.add("warmUp") }

        override fun playClosingSound() {
            events.add("sound")
            if (soundThrows) throw IllegalStateException("no audio")
        }

        override fun sleep(millis: Long) { events.add("dim-sleep") }

        override fun isPlaying(): Boolean {
            if (isPlayingThrows) throw IllegalStateException("audio service gone")
            return playing
        }

        override fun perform(rung: PauseRung) {
            events.add("pause:${rung.name}")
            performed.add(rung.name)
            if (performThrows) throw SecurityException("no key")
            if (rung.name == pausesWhenRung) playing = false
        }

        override fun release() { events.add("pause-release") }

        override fun startDimActivity() {
            events.add("dim:own-activity")
            dimTried.add(DimStrategies.OWN_ACTIVITY)
        }

        override fun dimActivityShown(): Boolean = shownByStrategy == DimStrategies.OWN_ACTIVITY
        override fun abandonDimActivity() { events.add("dim:abandon") }
        override fun oxyfieldIsActiveScreensaver(): Boolean = oxyfield

        override fun requestDreamDim() {
            events.add("dim:screensaver")
            dimTried.add(DimStrategies.SCREENSAVER)
        }

        override fun startScreensaver(): Boolean = true
        override fun dreamDimConsumed(): Boolean = shownByStrategy == DimStrategies.SCREENSAVER
        override fun clearDreamDim() { events.add("dim:clear") }
    }

    private val logs = ArrayList<String>()

    private fun runner(
        timer: FakeTimer,
        actions: FakeActions,
        provenPause: String? = null,
        provenDim: String? = null,
        factory: () -> SleepEndActions = { actions },
    ) = SleepEndRunner(
        timer = timer,
        actionsFactory = factory,
        settle = { events.add("settle $it") },
        log = { logs.add(it) },
        provenPause = provenPause,
        provenDim = provenDim,
    )

    private fun run(runner: SleepEndRunner, requested: AtomicInteger = AtomicInteger()): SleepEndOutcome? =
        runBlocking { runner.run { requested.incrementAndGet() } }

    // ---- not due ---------------------------------------------------------------------------------------

    @Test
    fun notDueMeansNothingHappensAndNothingPlays() {
        val actions = FakeActions()
        val factoryCalls = AtomicInteger()
        val requested = AtomicInteger()
        val outcome = run(
            runner(FakeTimer(due = false), actions, factory = { factoryCalls.incrementAndGet(); actions }),
            requested,
        )
        assertNull(outcome)
        assertEquals(listOf("begin"), events)
        assertEquals("the platform side is not even built for a stray alarm", 0, factoryCalls.get())
        assertEquals(0, requested.get())
        assertTrue(states.isEmpty())
        assertTrue(logs.any { it.contains("no due timer") })
    }

    // ---- due -------------------------------------------------------------------------------------------

    @Test
    fun dueRunsFiringThenIdleAndTheStepsInOrder() {
        val actions = FakeActions()
        val outcome = run(runner(FakeTimer(), actions))
        assertNotNull(outcome)
        assertEquals(listOf(SleepTimerState.Firing, SleepTimerState.Idle), states)
        assertEquals(
            listOf(
                "begin", "warmUp",
                "pause:media-key", "settle 1500", "pause-release",
                "settle 600", "sound", "settle 1200",
                "dim:own-activity",
                "end",
            ),
            events,
        )
        assertTrue(outcome!!.dimShown)
        assertEquals(PauseResult.PAUSED, outcome.pause?.result)
        assertTrue(outcome.errors.isEmpty())
    }

    @Test
    fun theClosingSoundIsPlayedOnceAndAfterThePause() {
        val actions = FakeActions()
        run(runner(FakeTimer(), actions))
        assertEquals(1, events.count { it == "sound" })
        assertTrue(events.indexOf("sound") > events.indexOf("pause:media-key"))
        assertTrue(events.indexOf("sound") > events.indexOf("pause-release"))
        assertTrue("and before the dim screen", events.indexOf("sound") < events.indexOf("dim:own-activity"))
    }

    @Test
    fun nothingPlayingSkipsThePauseButStillSoundsAndDims() {
        val actions = FakeActions().apply { playing = false }
        val outcome = run(runner(FakeTimer(), actions))
        assertEquals(PauseResult.NOT_PLAYING, outcome!!.pause?.result)
        assertTrue(actions.performed.isEmpty())
        assertTrue(events.contains("sound"))
        assertTrue(events.contains("dim:own-activity"))
    }

    // ---- pause ladder order ---------------------------------------------------------------------------

    @Test
    fun theLadderGoesThroughTheDefaultOrderWhenNothingPauses() {
        val actions = FakeActions().apply { pausesWhenRung = null }
        val outcome = run(runner(FakeTimer(), actions))
        assertEquals(listOf("media-key", "media-key-retry", "audio-focus"), actions.performed)
        assertEquals(PauseResult.STILL_PLAYING, outcome!!.pause?.result)
    }

    @Test
    fun theProvenRungIsTriedFirstAndStopsTheLadderWhenItWorks() {
        val actions = FakeActions().apply { pausesWhenRung = PauseRungs.AUDIO_FOCUS }
        val outcome = run(runner(FakeTimer(), actions, provenPause = PauseRungs.AUDIO_FOCUS))
        assertEquals(listOf("audio-focus"), actions.performed)
        assertEquals(PauseResult.PAUSED, outcome!!.pause?.result)
        assertTrue(logs.any { it == "pause order: audio-focus, media-key, media-key-retry" })
    }

    @Test
    fun aProvenRungThatDoesNotWorkFallsBackToTheOthers() {
        val actions = FakeActions().apply { pausesWhenRung = PauseRungs.MEDIA_KEY_RETRY }
        run(runner(FakeTimer(), actions, provenPause = PauseRungs.AUDIO_FOCUS))
        assertEquals(listOf("audio-focus", "media-key", "media-key-retry"), actions.performed)
    }

    // ---- dim order ------------------------------------------------------------------------------------

    @Test
    fun dimStrategiesAreTriedInOrderUntilOneIsShown() {
        val actions = FakeActions().apply { shownByStrategy = DimStrategies.SCREENSAVER }
        val requested = AtomicInteger()
        val outcome = run(runner(FakeTimer(), actions), requested)
        assertEquals(listOf("own-activity", "screensaver"), actions.dimTried)
        assertTrue(outcome!!.dimShown)
        assertTrue("the abandoned activity is told to close if it ever shows", events.contains("dim:abandon"))
        assertEquals("both requests released the broadcast hook", 2, requested.get())
    }

    @Test
    fun theProvenDimStrategyGoesFirst() {
        val actions = FakeActions().apply { shownByStrategy = DimStrategies.SCREENSAVER }
        run(runner(FakeTimer(), actions, provenDim = DimStrategies.SCREENSAVER))
        assertEquals(listOf("screensaver"), actions.dimTried)
        assertTrue(logs.any { it == "dim order: screensaver, own-activity" })
    }

    @Test
    fun theScreensaverStrategyIsSkippedWhenOxyfieldIsNotActive() {
        val actions = FakeActions().apply {
            shownByStrategy = null
            oxyfield = false
        }
        val outcome = run(runner(FakeTimer(), actions))
        assertEquals(listOf("own-activity"), actions.dimTried)
        assertFalse(outcome!!.dimShown)
        assertTrue(logs.any { it.contains("'screensaver': NOT_RESOLVED") })
        assertTrue(logs.any { it.contains("dim screen not shown") })
    }

    @Test
    fun noDimScreenStillLeavesThePauseAndTheSoundDone() {
        val actions = FakeActions().apply { shownByStrategy = null }
        val outcome = run(runner(FakeTimer(), actions))
        assertFalse(outcome!!.dimShown)
        assertEquals(PauseResult.PAUSED, outcome.pause?.result)
        assertTrue(events.contains("sound"))
        assertEquals(listOf(SleepTimerState.Firing, SleepTimerState.Idle), states)
    }

    // ---- failures -------------------------------------------------------------------------------------

    @Test
    fun aThrowingPauseStillLetsTheSoundAndTheDimRun() {
        val actions = FakeActions().apply { isPlayingThrows = true }
        val outcome = run(runner(FakeTimer(), actions))
        assertNull(outcome!!.pause)
        assertEquals(1, outcome.errors.size)
        assertTrue(outcome.errors[0].startsWith("pause FAILED: IllegalStateException"))
        assertTrue(events.contains("sound"))
        assertTrue(events.contains("dim:own-activity"))
        assertTrue(outcome.dimShown)
        assertEquals("pause-release", events[events.indexOf("settle 600") - 1])
        assertEquals(SleepTimerState.Idle, states.last())
    }

    @Test
    fun aRungThatThrowsIsLoggedAndTheLadderGoesOn() {
        val actions = FakeActions().apply {
            performThrows = true
            pausesWhenRung = null
        }
        val outcome = run(runner(FakeTimer(), actions))
        assertEquals(listOf("media-key", "media-key-retry", "audio-focus"), actions.performed)
        assertEquals(PauseResult.STILL_PLAYING, outcome!!.pause?.result)
        assertTrue(logs.any { it.contains("'media-key': FAILED: SecurityException") })
        assertTrue(events.contains("sound"))
    }

    @Test
    fun aThrowingSoundStillDims() {
        val actions = FakeActions().apply { soundThrows = true }
        val outcome = run(runner(FakeTimer(), actions))
        assertTrue(outcome!!.dimShown)
        assertEquals(1, outcome.errors.size)
        assertTrue(outcome.errors[0].startsWith("closing sound FAILED"))
    }

    @Test
    fun theFocusIsReleasedEvenWhenTheLadderThrewOutright() {
        val actions = FakeActions().apply { isPlayingThrows = true }
        run(runner(FakeTimer(), actions))
        assertEquals(1, events.count { it == "pause-release" })
    }

    @Test
    fun aFailureToBuildThePlatformSideStillHandsTheTimerBack() {
        val timer = FakeTimer()
        var thrown: Exception? = null
        try {
            run(runner(timer, FakeActions(), factory = { throw IllegalStateException("no audio service") }))
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertNotNull("the failure is reported to the caller, which logs it", thrown)
        assertEquals(listOf(SleepTimerState.Firing, SleepTimerState.Idle), states)
        assertEquals("end", events.last())
    }

    @Test
    fun aFailingWarmUpDoesNotStopTheSequence() {
        val actions = object : SleepEndActions by FakeActions() {
            override fun warmUp() {
                throw IllegalStateException("engine")
            }
        }
        val outcome = run(runner(FakeTimer(), FakeActions(), factory = { actions }))
        assertNotNull(outcome)
        assertTrue(logs.any { it.contains("warm-up FAILED: IllegalStateException: engine") })
        assertTrue(events.contains("sound"))
    }

    // ---- double fire ----------------------------------------------------------------------------------

    @Test
    fun aSecondFireAttemptIsIgnored() {
        val timer = FakeTimer()
        val actions = FakeActions()
        val runner = runner(timer, actions)
        assertNotNull(run(runner))
        events.clear()
        states.clear()
        assertNull(run(runner))
        assertEquals("only the claim was attempted", listOf("begin"), events)
        assertEquals(1, logs.count { it.contains("timer reached its deadline") })
    }

    @Test
    fun aSecondFireWhileTheFirstIsStillRunningIsIgnored() {
        val timer = FakeTimer()
        val actions = FakeActions()
        var second: SleepEndOutcome? = SleepEndOutcome(null, false, emptyList())
        var secondRan = false
        val first = SleepEndRunner(
            timer = timer,
            actionsFactory = { actions },
            // The second alarm arrives while the first sequence is in the middle of settling.
            settle = {
                if (!secondRan) {
                    secondRan = true
                    second = runBlocking { runner(timer, actions).run() }
                }
                events.add("settle $it")
            },
            log = { logs.add(it) },
        )
        assertNotNull(runBlocking { first.run() })
        assertTrue(secondRan)
        assertNull(second)
        assertEquals(1, events.count { it == "sound" })
    }

    // ---- the receiver's one-shot release --------------------------------------------------------------

    @Test
    fun onceActionRunsExactlyOnceHoweverOftenItIsCalled() {
        val count = AtomicInteger()
        val once = OnceAction { count.incrementAndGet() }
        assertTrue(once())
        assertFalse(once())
        assertFalse(once())
        assertEquals(1, count.get())
    }

    @Test
    fun onceActionIsSafeFromSeveralThreadsAtOnce() {
        val count = AtomicInteger()
        val once = OnceAction { count.incrementAndGet() }
        val threads = List(8) { Thread { repeat(100) { once() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, count.get())
    }
}
