package com.andrewevmiller.tvlauncher.sleep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the sleep timer is. Times are wall-clock epoch milliseconds so the state survives a restart. */
sealed interface SleepTimerState {
    data object Idle : SleepTimerState

    /** Counting down. [totalMillis] is the length it was started with plus any extensions. */
    data class Running(val deadlineAtMillis: Long, val totalMillis: Long) : SleepTimerState {
        fun remainingMillis(nowMillis: Long): Long = (deadlineAtMillis - nowMillis).coerceAtLeast(0L)
    }

    /** The end sequence is running (pause, closing sound, dim screen). Back to [Idle] when it is done. */
    data object Firing : SleepTimerState
}

/**
 * The launcher's sleep timer. At the deadline it pauses media, plays the closing sound and shows the dim screen
 * (see docs/SLEEP_TIMER_PLAN.md). The UI only talks to this interface; the Android implementation schedules an
 * alarm so the timer survives the launcher process being killed while another app is in front.
 *
 * CONTRACT: integrator-owned. Add members, never rename or remove one.
 */
interface SleepTimerController {
    val state: StateFlow<SleepTimerState>

    /** Starts (or restarts) a timer of [minutes], which should be one of [SleepPresets.minutes]. */
    fun start(minutes: Int)

    /** Adds [minutes] to a running timer, never beyond [SleepPresets.MAX_MINUTES] from now. No-op when idle. */
    fun extend(minutes: Int = SleepPresets.EXTEND_MINUTES)

    /** Stops a running timer. No-op when idle. */
    fun cancel()
}

/** Never runs; for previews and tests that do not care about the timer. */
object NoOpSleepTimerController : SleepTimerController {
    override val state: StateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState.Idle)
    override fun start(minutes: Int) = Unit
    override fun extend(minutes: Int) = Unit
    override fun cancel() = Unit
}

/** In-memory timer driven by [now]; it never fires by itself. Tests move [now] and read [state]. */
class FakeSleepTimerController(private val now: () -> Long = System::currentTimeMillis) : SleepTimerController {
    private val mutable = MutableStateFlow<SleepTimerState>(SleepTimerState.Idle)
    override val state: StateFlow<SleepTimerState> = mutable

    override fun start(minutes: Int) {
        val total = minutes * MILLIS_PER_MINUTE
        mutable.value = SleepTimerState.Running(deadlineAtMillis = now() + total, totalMillis = total)
    }

    override fun extend(minutes: Int) {
        val current = mutable.value as? SleepTimerState.Running ?: return
        val cap = now() + SleepPresets.MAX_MINUTES * MILLIS_PER_MINUTE
        val deadline = minOf(current.deadlineAtMillis + minutes * MILLIS_PER_MINUTE, cap)
        mutable.value = current.copy(
            deadlineAtMillis = deadline,
            totalMillis = current.totalMillis + (deadline - current.deadlineAtMillis),
        )
    }

    override fun cancel() {
        mutable.value = SleepTimerState.Idle
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
