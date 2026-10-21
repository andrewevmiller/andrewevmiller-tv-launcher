package com.andrewevmiller.tvlauncher.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepRestoreTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    @Test
    fun aDeadlineStillAheadIsRunningAndRescheduledAtItsOwnTime() {
        val restored = restoreTimer(now + 30 * minute, 45 * minute, now)
        assertEquals(SleepTimerState.Running(now + 30 * minute, 45 * minute), restored.state)
        assertEquals(now + 30 * minute, restored.alarmAtMillis)
        assertFalse(restored.clearStore)
    }

    @Test
    fun nothingStoredIsIdleWithNothingToClear() {
        val restored = restoreTimer(null, null, now)
        assertEquals(SleepTimerState.Idle, restored.state)
        assertNull(restored.alarmAtMillis)
        assertFalse(restored.clearStore)
    }

    @Test
    fun aDeadlineThatPassedLongAgoIsDroppedAndCleared() {
        // The TV was off at the deadline: never fire late.
        for (ago in listOf(SleepConfig.DUE_GRACE_MILLIS + 1, 10 * minute, 8 * 60 * minute)) {
            val restored = restoreTimer(now - ago, 45 * minute, now)
            assertEquals("$ago ms ago", SleepTimerState.Idle, restored.state)
            assertNull(restored.alarmAtMillis)
            assertTrue(restored.clearStore)
        }
    }

    @Test
    fun aDeadlineJustPastIsTheAlarmFiringIntoAColdProcessAndStaysDue() {
        // A process started for the alarm sees now > deadline by the cold-start time. It must not be dropped.
        for (ago in listOf(0L, 40L, 800L, 30_000L, SleepConfig.DUE_GRACE_MILLIS)) {
            val restored = restoreTimer(now - ago, 45 * minute, now)
            assertEquals("$ago ms ago", SleepTimerState.Running(now - ago, 45 * minute), restored.state)
            assertFalse(restored.clearStore)
            // Only a back-up alarm: the real one is firing, or already fired.
            assertEquals(now + SleepConfig.FALLBACK_DELAY_MILLIS, restored.alarmAtMillis)
            assertTrue(isFireable(restored.state, now))
        }
    }

    @Test
    fun corruptOrHalfStoredValuesAreDroppedAndCleared() {
        val cases = listOf(
            "no total" to Pair<Long?, Long?>(now + minute, null),
            "no deadline" to Pair<Long?, Long?>(null, 45 * minute),
            "zero total" to Pair<Long?, Long?>(now + minute, 0L),
            "negative total" to Pair<Long?, Long?>(now + minute, -5L),
            "deadline beyond the cap" to Pair<Long?, Long?>(now + 5 * 60 * minute, 45 * minute),
            "just past with no total" to Pair<Long?, Long?>(now - 1_000, null),
            "just past with zero total" to Pair<Long?, Long?>(now - 1_000, 0L),
        )
        for ((name, stored) in cases) {
            val restored = restoreTimer(stored.first, stored.second, now)
            assertEquals(name, SleepTimerState.Idle, restored.state)
            assertNull(name, restored.alarmAtMillis)
            assertTrue(name, restored.clearStore)
        }
    }

    // ---- isFireable -----------------------------------------------------------------------------------

    private val running = SleepTimerState.Running(now, 45 * minute)

    @Test
    fun fireableAtAndAfterTheDeadline() {
        assertTrue(isFireable(running, now))
        assertTrue(isFireable(running, now + 800))
        assertTrue(isFireable(running, now + 90 * minute))
    }

    @Test
    fun fireableAlittleBeforeTheDeadlineBecauseTheWallClockCanBeStepped() {
        assertTrue(isFireable(running, now - SleepConfig.EARLY_TOLERANCE_MILLIS))
        assertTrue(isFireable(running, now - 1_000))
    }

    @Test
    fun notFireableWhenItWasExtendedOrNeverStarted() {
        // An extension adds at least minutes, far beyond the early tolerance.
        assertFalse(isFireable(running, now - SleepConfig.EARLY_TOLERANCE_MILLIS - 1))
        assertFalse(isFireable(SleepLogic.extend(running, now, 15), now))
        assertFalse(isFireable(SleepTimerState.Idle, now))
        assertFalse(isFireable(SleepTimerState.Firing, now))
    }
}
