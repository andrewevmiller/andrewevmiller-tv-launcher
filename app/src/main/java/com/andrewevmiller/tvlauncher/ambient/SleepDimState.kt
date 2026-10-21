package com.andrewevmiller.tvlauncher.ambient

/**
 * Where a view is in the sleep fade. Pure, so the view stays thin: it feeds frame times in and reads the two
 * brightness multipliers out. Times are milliseconds on whatever monotonic clock the caller uses (the view
 * uses the Choreographer's), and only differences matter.
 *
 * While inactive both levels are exactly 1, so a view that never calls [start] behaves as before.
 */
class SleepDimState(
    private val reducedMotion: Boolean,
    private val fadeMillis: Long = SleepDimMath.FADE_MILLIS,
) {
    private var startMillis = 0L

    /** True from [start] until [clear]. */
    var active: Boolean = false
        private set

    /** True once the fade is over (or was skipped by reduced motion): hold one still frame, stop animating. */
    var settled: Boolean = false
        private set

    /** Begins (or restarts) the fade at [nowMillis]. Reduced motion is settled at once. */
    fun start(nowMillis: Long) {
        startMillis = nowMillis
        active = true
        settled = SleepDimMath.isSettled(0L, fadeMillis, reducedMotion)
    }

    /** Back to normal brightness. */
    fun clear() {
        active = false
        settled = false
    }

    /** Feeds a frame time. Returns true if this frame is the one that settled the fade. */
    fun update(nowMillis: Long): Boolean {
        if (!active || settled) return false
        if (SleepDimMath.isSettled(nowMillis - startMillis, fadeMillis, reducedMotion)) {
            settled = true
            return true
        }
        return false
    }

    /** Brightness multiplier for dots and background at [nowMillis]; 1 when inactive. */
    fun level(nowMillis: Long): Float = when {
        !active -> 1f
        settled -> SleepDimMath.FLOOR
        else -> SleepDimMath.level(nowMillis - startMillis, fadeMillis, reducedMotion)
    }

    /** Brightness multiplier for the clock text at [nowMillis]; 1 when inactive, 0 once settled. */
    fun clockLevel(nowMillis: Long): Float = when {
        !active -> 1f
        settled -> 0f
        else -> SleepDimMath.clockLevel(nowMillis - startMillis, fadeMillis, reducedMotion)
    }
}
