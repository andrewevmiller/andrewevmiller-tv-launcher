package com.andrewevmiller.tvlauncher.ambient

import com.andrewevmiller.tvlauncher.settings.Screensaver

/**
 * Chooses and starts the system screensaver. Writing the choice needs `WRITE_SECURE_SETTINGS`,
 * granted once over adb (`deploy.sh` does it).
 */
interface ScreensaverController {
    /** True when `WRITE_SECURE_SETTINGS` is granted, so [apply] can change the system setting. */
    val canSwitch: Boolean

    /** Writes `Settings.Secure.screensaver_components` for [choice]. No-op when [canSwitch] is false. */
    fun apply(choice: Screensaver)

    /** Starts the active screensaver now. False if the device refuses an on-demand start. */
    fun startNow(): Boolean
}

/** Does nothing; for previews and tests. */
object NoOpScreensaverController : ScreensaverController {
    override val canSwitch: Boolean = false
    override fun apply(choice: Screensaver) = Unit
    override fun startNow(): Boolean = false
}
