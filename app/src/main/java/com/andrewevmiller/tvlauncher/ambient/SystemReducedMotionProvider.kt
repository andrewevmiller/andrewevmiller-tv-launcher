package com.andrewevmiller.tvlauncher.ambient

import android.content.Context
import android.provider.Settings

/**
 * Default [ReducedMotionProvider]: the system's "animations off" switch
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). The integrator swaps in a provider backed by the
 * launcher's own `reducedMotion` setting.
 */
class SystemReducedMotionProvider(context: Context) : ReducedMotionProvider {
    private val resolver = context.contentResolver

    override fun isReduced(): Boolean =
        isReducedByAnimatorScale(
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
        )
}
