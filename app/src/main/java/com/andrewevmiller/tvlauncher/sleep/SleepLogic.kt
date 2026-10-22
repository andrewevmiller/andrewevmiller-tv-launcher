package com.andrewevmiller.tvlauncher.sleep

/*
 * Deadline math and countdown text for the sleep timer, with no clock and no Android types. Every function takes
 * the time it needs as a parameter. FakeSleepTimerController.extend is the reference for extend(); the parity
 * test in SleepLogicTest drives both with the same inputs.
 */
object SleepLogic {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val MINUTES_PER_HOUR = 60L

    /** Milliseconds left before the deadline, never negative. */
    fun remaining(state: SleepTimerState.Running, now: Long): Long = state.remainingMillis(now)

    /**
     * Short text for the Home tile: "1 h 05 min", "42 min", "<1 min" or "0 min". The time is rounded UP to whole
     * minutes first, so a running timer never reads 0 while time is left, and the hours format is chosen from the
     * rounded value (59:01 reads "1 h 00 min", not "60 min").
     */
    fun caption(remainingMillis: Long): String {
        if (remainingMillis <= 0L) return "0 min"
        if (remainingMillis < MILLIS_PER_MINUTE) return "<1 min"
        val minutes = ceilDiv(remainingMillis, MILLIS_PER_MINUTE)
        if (minutes < MINUTES_PER_HOUR) return "$minutes min"
        return "${minutes / MINUTES_PER_HOUR} h ${pad2(minutes % MINUTES_PER_HOUR)} min"
    }

    /** "H:MM:SS" for the dim screen and the tile's detail view, rounded UP to the next whole second, never negative. */
    fun clock(remainingMillis: Long): String {
        val seconds = if (remainingMillis <= 0L) 0L else ceilDiv(remainingMillis, MILLIS_PER_SECOND)
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / 60L
        val secs = seconds % 60L
        return "$hours:${pad2(minutes)}:${pad2(secs)}"
    }

    /** A new timer of [minutes] (sanitised to a preset) that ends at [now] plus that length. */
    fun start(now: Long, minutes: Int): SleepTimerState.Running {
        val total = SleepPresets.sanitize(minutes) * MILLIS_PER_MINUTE
        return SleepTimerState.Running(deadlineAtMillis = now + total, totalMillis = total)
    }

    /**
     * Moves the deadline [minutes] later, but never past [now] plus [SleepPresets.MAX_MINUTES].
     * The total grows by the amount the deadline actually moved. No-op unless Running.
     * Matches FakeSleepTimerController.extend.
     */
    fun extend(state: SleepTimerState, now: Long, minutes: Int): SleepTimerState {
        if (state !is SleepTimerState.Running) return state
        val cap = now + SleepPresets.MAX_MINUTES * MILLIS_PER_MINUTE
        val deadline = minOf(state.deadlineAtMillis + minutes * MILLIS_PER_MINUTE, cap)
        return state.copy(
            deadlineAtMillis = deadline,
            totalMillis = state.totalMillis + (deadline - state.deadlineAtMillis),
        )
    }

    /**
     * The timer as it was stored before the TV or the launcher went away. Running only when both values are present,
     * the total is positive, the deadline is still in the future, and it is no further ahead than
     * [SleepPresets.MAX_MINUTES] (a larger value is a corrupt store). Anything else is Idle: a deadline that passed
     * while the TV was off is dropped, never fired late.
     */
    fun restore(storedDeadlineAtMillis: Long?, storedTotalMillis: Long?, now: Long): SleepTimerState {
        val deadline = storedDeadlineAtMillis ?: return SleepTimerState.Idle
        val total = storedTotalMillis ?: return SleepTimerState.Idle
        if (total <= 0L) return SleepTimerState.Idle
        if (deadline <= now) return SleepTimerState.Idle
        if (deadline - now > SleepPresets.MAX_MINUTES * MILLIS_PER_MINUTE) return SleepTimerState.Idle
        return SleepTimerState.Running(deadlineAtMillis = deadline, totalMillis = total)
    }

    /** True once a running timer has reached its deadline. Idle and Firing are never due. */
    fun isDue(state: SleepTimerState, now: Long): Boolean =
        state is SleepTimerState.Running && now >= state.deadlineAtMillis

    /** [numerator] / [denominator] rounded up, for positive inputs. Avoids (a + b - 1), which can overflow. */
    private fun ceilDiv(numerator: Long, denominator: Long): Long {
        val quotient = numerator / denominator
        return if (numerator % denominator != 0L) quotient + 1L else quotient
    }

    private fun pad2(value: Long): String = value.toString().padStart(2, '0')
}
