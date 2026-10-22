package com.andrewevmiller.tvlauncher.home

import com.andrewevmiller.tvlauncher.sleep.SleepLogic
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sleep.SleepTimerState

/** One chip in the sleep picker. Pure so the choice of chips, focus target and actions are unit tested. */
sealed interface SleepOption {
    val label: String

    data object Off : SleepOption {
        override val label = "Off"
    }

    data class Length(val minutes: Int) : SleepOption {
        override val label = minutes.toString()
    }

    data object Extend : SleepOption {
        override val label = "+${SleepPresets.EXTEND_MINUTES} min"
    }

    data object Cancel : SleepOption {
        override val label = "Cancel"
    }
}

/** What picking a chip does to the timer. */
sealed interface SleepAction {
    data class Start(val minutes: Int) : SleepAction
    data object Extend : SleepAction
    data object Cancel : SleepAction

    /** Nothing to do (extend or cancel after the timer already ran out). */
    data object None : SleepAction
}

/** Text, chips and focus rules for the Home "Sleep timer" tile and its picker (mockup: toolsSec, sleepPicker). */
object SleepToolsLogic {
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MIN_TICK_MILLIS = 250L
    private const val MAX_TICK_MILLIS = 15_000L

    /** "Off", or "Sleep · 42 min" counting down. Firing reads as Off: the timer is spent. */
    fun caption(state: SleepTimerState, now: Long): String =
        if (state is SleepTimerState.Running) "Sleep · " + SleepLogic.caption(state.remainingMillis(now)) else "Off"

    fun isRunning(state: SleepTimerState): Boolean = state is SleepTimerState.Running

    /** Off and the presets; while a timer runs also "+15 min" and "Cancel". */
    fun options(running: Boolean): List<SleepOption> =
        listOf<SleepOption>(SleepOption.Off) + SleepPresets.minutes.map { SleepOption.Length(it) } +
            if (running) listOf(SleepOption.Extend, SleepOption.Cancel) else emptyList()

    /** Index of the user's default length in [options]. Anything that is not a preset falls back to 45. */
    fun defaultFocusIndex(options: List<SleepOption>, defaultMinutes: Int): Int {
        val target = clampMinutes(defaultMinutes)
        val index = options.indexOfFirst { it is SleepOption.Length && it.minutes == target }
        return if (index >= 0) index else 0
    }

    /** A stored or chosen length forced to one of the presets. */
    fun clampMinutes(minutes: Int): Int = SleepPresets.sanitize(minutes)

    /**
     * The chip drawn as selected: Off while idle; while running the length that equals the timer's total
     * (the length it started with plus any extensions), if that is a preset.
     */
    fun isSelected(option: SleepOption, state: SleepTimerState): Boolean = when (state) {
        is SleepTimerState.Running ->
            option is SleepOption.Length && state.totalMillis == option.minutes * MILLIS_PER_MINUTE
        else -> option is SleepOption.Off
    }

    /** Extend and Cancel do nothing once the timer has run out while the picker was open. */
    fun actionFor(option: SleepOption, state: SleepTimerState): SleepAction {
        val running = isRunning(state)
        return when (option) {
            SleepOption.Off -> SleepAction.Cancel
            is SleepOption.Length -> SleepAction.Start(clampMinutes(option.minutes))
            SleepOption.Extend -> if (running) SleepAction.Extend else SleepAction.None
            SleepOption.Cancel -> if (running) SleepAction.Cancel else SleepAction.None
        }
    }

    /**
     * How long to wait before the caption can next change: until the minutes figure rolls over, never more than
     * 15 s and never less than 250 ms, so the tile updates on time and Home is not recomposed every second.
     */
    fun nextTickDelayMillis(remainingMillis: Long): Long {
        if (remainingMillis <= 0L) return MAX_TICK_MILLIS
        val intoMinute = remainingMillis % MILLIS_PER_MINUTE
        val untilChange = if (intoMinute == 0L) MILLIS_PER_MINUTE else intoMinute
        return (untilChange + 50L).coerceIn(MIN_TICK_MILLIS, MAX_TICK_MILLIS)
    }
}
