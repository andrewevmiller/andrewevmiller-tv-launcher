package com.andrewevmiller.tvlauncher.sleep

import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepContractsTest {
    @Test
    fun presetsAreAscendingAndContainTheDefault() {
        assertEquals(SleepPresets.minutes.sorted(), SleepPresets.minutes)
        assertTrue(SleepPresets.DEFAULT_MINUTES in SleepPresets.minutes)
        assertTrue(SleepPresets.minutes.last() <= SleepPresets.MAX_MINUTES)
    }

    @Test
    fun sanitizeKeepsPresetsAndReplacesEverythingElseWithTheDefault() {
        for (m in SleepPresets.minutes) assertEquals(m, SleepPresets.sanitize(m))
        for (bad in listOf(null, 0, -5, 44, 46, 1000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals("for $bad", SleepPresets.DEFAULT_MINUTES, SleepPresets.sanitize(bad))
        }
    }

    @Test
    fun settingsDefaultToTheDefaultLengthAndOldStoresStillLoad() {
        assertEquals(SleepPresets.DEFAULT_MINUTES, LauncherSettings().sleepMinutes)
        // A store written before the setting existed has no value for it.
        assertEquals(LauncherSettings(), SettingsCodec.decode(null, null, null, null, null, null))
    }

    @Test
    fun codecKeepsAPresetAndRepairsAnythingElse() {
        assertEquals(90, SettingsCodec.decode(null, null, null, null, null, null, sleepMinutes = 90).sleepMinutes)
        assertEquals(
            SleepPresets.DEFAULT_MINUTES,
            SettingsCodec.decode(null, null, null, null, null, null, sleepMinutes = 7).sleepMinutes,
        )
    }

    @Test
    fun fakeStartsCountsDownAndCancels() {
        var now = 1_000_000L
        val timer = FakeSleepTimerController { now }
        assertEquals(SleepTimerState.Idle, timer.state.value)

        timer.start(30)
        val running = timer.state.value as SleepTimerState.Running
        assertEquals(1_000_000L + 30 * 60_000L, running.deadlineAtMillis)
        assertEquals(30 * 60_000L, running.totalMillis)

        now += 10 * 60_000L
        assertEquals(20 * 60_000L, running.remainingMillis(now))
        assertEquals(0L, running.remainingMillis(running.deadlineAtMillis + 5))

        timer.cancel()
        assertEquals(SleepTimerState.Idle, timer.state.value)
    }

    @Test
    fun extendAddsTimeButNeverPastTheCap() {
        var now = 0L
        val timer = FakeSleepTimerController { now }
        timer.extend() // idle: nothing happens
        assertEquals(SleepTimerState.Idle, timer.state.value)

        timer.start(120)
        timer.extend()
        var running = timer.state.value as SleepTimerState.Running
        assertEquals(135 * 60_000L, running.deadlineAtMillis)
        assertEquals(135 * 60_000L, running.totalMillis)

        repeat(20) { timer.extend() }
        running = timer.state.value as SleepTimerState.Running
        assertEquals(SleepPresets.MAX_MINUTES * 60_000L, running.deadlineAtMillis)
        assertEquals(SleepPresets.MAX_MINUTES * 60_000L, running.totalMillis)

        now += 60 * 60_000L // an hour passes: the cap moves with "now", so one more step fits
        timer.extend()
        running = timer.state.value as SleepTimerState.Running
        assertEquals(255 * 60_000L, running.deadlineAtMillis)
        assertTrue(running.deadlineAtMillis <= now + SleepPresets.MAX_MINUTES * 60_000L)
    }
}
