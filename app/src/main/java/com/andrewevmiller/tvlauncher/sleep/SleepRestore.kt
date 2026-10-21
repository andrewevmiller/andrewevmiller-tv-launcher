package com.andrewevmiller.tvlauncher.sleep

/**
 * What to do with a timer found in storage when the controller is created.
 *
 * [state] is what the controller starts as. [alarmAtMillis] is when to (re)schedule the alarm, or null for none.
 * [clearStore] drops whatever is stored (stale or corrupt values).
 */
data class RestoredTimer(val state: SleepTimerState, val alarmAtMillis: Long?, val clearStore: Boolean)

/**
 * Decides the starting state from the stored values. Pure; the controller does the I/O.
 *
 * - A deadline still ahead (and sane, see [SleepLogic.restore]) is Running and its alarm is scheduled again.
 * - A deadline up to [SleepConfig.DUE_GRACE_MILLIS] in the past is the alarm firing right now into a process that had
 *   to be created for it. SleepLogic.restore alone would drop it, and the receiver would find nothing to fire. It stays
 *   Running (due), and a back-up alarm is set [SleepConfig.FALLBACK_DELAY_MILLIS] ahead in case the original is lost.
 *   The receiver claiming it cancels that alarm, so it never fires twice.
 * - Anything else (older than the grace, corrupt, half stored) is Idle and the store is cleared. Nothing stored is
 *   Idle with nothing to clear.
 */
fun restoreTimer(storedDeadlineAtMillis: Long?, storedTotalMillis: Long?, now: Long): RestoredTimer {
    val restored = SleepLogic.restore(storedDeadlineAtMillis, storedTotalMillis, now)
    if (restored is SleepTimerState.Running) {
        return RestoredTimer(restored, alarmAtMillis = restored.deadlineAtMillis, clearStore = false)
    }
    val deadline = storedDeadlineAtMillis
    val total = storedTotalMillis
    if (deadline != null && total != null && total > 0L && deadline <= now && now - deadline <= SleepConfig.DUE_GRACE_MILLIS) {
        return RestoredTimer(
            state = SleepTimerState.Running(deadlineAtMillis = deadline, totalMillis = total),
            alarmAtMillis = now + SleepConfig.FALLBACK_DELAY_MILLIS,
            clearStore = false,
        )
    }
    return RestoredTimer(SleepTimerState.Idle, alarmAtMillis = null, clearStore = deadline != null || total != null)
}

/** True when an alarm arriving at [now] should fire the stored timer: Running and (nearly) at its deadline. */
fun isFireable(state: SleepTimerState, now: Long): Boolean =
    SleepLogic.isDue(state, now + SleepConfig.EARLY_TOLERANCE_MILLIS)
