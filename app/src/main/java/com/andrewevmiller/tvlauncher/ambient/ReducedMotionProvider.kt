package com.andrewevmiller.tvlauncher.ambient

/**
 * Says whether the screensaver should show a single still frame instead of animating.
 * Called once, on the main thread, when the dream starts, so it should not block for long.
 * The integrator can replace the default with one backed by `LauncherSettings.reducedMotion`
 * (see [OxyfieldDreamService.reducedMotionProvider]).
 */
fun interface ReducedMotionProvider {
    fun isReduced(): Boolean
}

/** `Settings.Global.ANIMATOR_DURATION_SCALE` of exactly 0 is the system's "animations off". */
fun isReducedByAnimatorScale(scale: Float): Boolean = scale == 0f
