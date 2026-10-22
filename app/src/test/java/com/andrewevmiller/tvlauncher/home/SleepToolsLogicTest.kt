package com.andrewevmiller.tvlauncher.home

import com.andrewevmiller.tvlauncher.sleep.FakeSleepTimerController
import com.andrewevmiller.tvlauncher.sleep.SleepLogic
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sleep.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepToolsLogicTest {
    private val minute = 60_000L
    private val idle = SleepTimerState.Idle
    private fun running(totalMinutes: Int, leftMillis: Long) =
        SleepTimerState.Running(deadlineAtMillis = leftMillis, totalMillis = totalMinutes * minute)

    // --- caption ---

    @Test
    fun captionIsOffWhenIdleOrFiring() {
        assertEquals("Off", SleepToolsLogic.caption(idle, 0L))
        assertEquals("Off", SleepToolsLogic.caption(SleepTimerState.Firing, 0L))
    }

    @Test
    fun captionCountsDownInMinutes() {
        val state = running(45, leftMillis = 45 * minute)
        assertEquals("Sleep · 45 min", SleepToolsLogic.caption(state, 0L))
        assertEquals("Sleep · 42 min", SleepToolsLogic.caption(state, 3 * minute))
        assertEquals("Sleep · 1 h 05 min", SleepToolsLogic.caption(running(90, 65 * minute), 0L))
        assertEquals("Sleep · <1 min", SleepToolsLogic.caption(state, 45 * minute - 10_000L))
    }

    // --- chips ---

    @Test
    fun idleShowsOffAndTheSixPresets() {
        val labels = SleepToolsLogic.options(running = false).map { it.label }
        assertEquals(listOf("Off", "15", "30", "45", "60", "90", "120"), labels)
    }

    @Test
    fun runningAddsExtendAndCancel() {
        val labels = SleepToolsLogic.options(running = true).map { it.label }
        assertEquals(listOf("Off", "15", "30", "45", "60", "90", "120", "+15 min", "Cancel"), labels)
    }

    @Test
    fun defaultFocusLandsOnTheDefaultLength() {
        val idleOptions = SleepToolsLogic.options(false)
        assertEquals("45", idleOptions[SleepToolsLogic.defaultFocusIndex(idleOptions, 45)].label)
        assertEquals("90", idleOptions[SleepToolsLogic.defaultFocusIndex(idleOptions, 90)].label)
        val runningOptions = SleepToolsLogic.options(true)
        assertEquals("15", runningOptions[SleepToolsLogic.defaultFocusIndex(runningOptions, 15)].label)
    }

    @Test
    fun anUnknownDefaultFallsBackToTheDefaultPreset() {
        val options = SleepToolsLogic.options(false)
        assertEquals("45", options[SleepToolsLogic.defaultFocusIndex(options, 7)].label)
        assertEquals("45", options[SleepToolsLogic.defaultFocusIndex(options, -1)].label)
    }

    @Test
    fun clampKeepsPresetsAndReplacesAnythingElse() {
        SleepPresets.minutes.forEach { assertEquals(it, SleepToolsLogic.clampMinutes(it)) }
        assertEquals(SleepPresets.DEFAULT_MINUTES, SleepToolsLogic.clampMinutes(0))
        assertEquals(SleepPresets.DEFAULT_MINUTES, SleepToolsLogic.clampMinutes(1000))
    }

    // --- selected chip ---

    @Test
    fun offIsSelectedWhileIdleAndNothingElseIs() {
        val options = SleepToolsLogic.options(false)
        assertEquals(listOf("Off"), options.filter { SleepToolsLogic.isSelected(it, idle) }.map { it.label })
    }

    @Test
    fun theRunningLengthIsSelectedWhileRunning() {
        val options = SleepToolsLogic.options(true)
        val state = running(30, 20 * minute)
        assertEquals(listOf("30"), options.filter { SleepToolsLogic.isSelected(it, state) }.map { it.label })
    }

    @Test
    fun anExtendedTotalThatIsAPresetIsSelectedAndOneThatIsNotSelectsNothing() {
        val options = SleepToolsLogic.options(true)
        assertEquals(listOf("60"), options.filter { SleepToolsLogic.isSelected(it, running(60, 50 * minute)) }.map { it.label })
        assertTrue(options.none { SleepToolsLogic.isSelected(it, running(75, 50 * minute)) })
    }

    // --- actions ---

    @Test
    fun lengthStartsOffCancelsExtendExtendsCancelCancels() {
        val state = running(30, 20 * minute)
        assertEquals(SleepAction.Start(60), SleepToolsLogic.actionFor(SleepOption.Length(60), state))
        assertEquals(SleepAction.Cancel, SleepToolsLogic.actionFor(SleepOption.Off, state))
        assertEquals(SleepAction.Extend, SleepToolsLogic.actionFor(SleepOption.Extend, state))
        assertEquals(SleepAction.Cancel, SleepToolsLogic.actionFor(SleepOption.Cancel, state))
    }

    @Test
    fun extendAndCancelDoNothingOnceTheTimerHasRunOut() {
        assertEquals(SleepAction.None, SleepToolsLogic.actionFor(SleepOption.Extend, idle))
        assertEquals(SleepAction.None, SleepToolsLogic.actionFor(SleepOption.Cancel, idle))
        assertEquals(SleepAction.None, SleepToolsLogic.actionFor(SleepOption.Extend, SleepTimerState.Firing))
        assertEquals(SleepAction.Start(15), SleepToolsLogic.actionFor(SleepOption.Length(15), idle))
        assertEquals(SleepAction.Cancel, SleepToolsLogic.actionFor(SleepOption.Off, idle))
    }

    // --- extension cap (the engine's rule, which the +15 chip relies on) ---

    @Test
    fun extendingNeverGoesPastTheCapFromNow() {
        var now = 0L
        val timer = FakeSleepTimerController { now }
        timer.start(120)
        repeat(20) { timer.extend(SleepPresets.EXTEND_MINUTES) }
        val state = timer.state.value as SleepTimerState.Running
        assertEquals(SleepPresets.MAX_MINUTES * minute, state.remainingMillis(now))
        assertFalse(SleepToolsLogic.caption(state, now).contains("Off"))
        assertEquals("Sleep · 4 h 00 min", SleepToolsLogic.caption(state, now))
        assertEquals(SleepLogic.extend(state, now, 15), state)
    }

    // --- ticker ---

    @Test
    fun tickWaitsUntilTheMinuteRollsOverButNeverMoreThanFifteenSeconds() {
        assertEquals(15_000L, SleepToolsLogic.nextTickDelayMillis(45 * minute))
        assertEquals(15_000L, SleepToolsLogic.nextTickDelayMillis(10 * minute + 40_000L))
        assertEquals(10_050L, SleepToolsLogic.nextTickDelayMillis(3 * minute + 10_000L))
        assertEquals(250L, SleepToolsLogic.nextTickDelayMillis(3 * minute + 10L))
        assertEquals(15_000L, SleepToolsLogic.nextTickDelayMillis(0L))
    }
}
