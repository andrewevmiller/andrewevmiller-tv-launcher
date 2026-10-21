package com.andrewevmiller.tvlauncher.sound

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.andrewevmiller.tvlauncher.home.data.LauncherApp

/**
 * Interface sounds. Navigation is near-silent; only [select], [back] and [launch] are voiced.
 * UI code calls this and never plays audio directly. Implementations obey the system
 * sound-effects setting and the launcher's own toggle.
 */
interface FieldSound {
    /** Focus moved. [row] shifts the pitch; implementations thin out key-repeat. */
    fun tick(row: Int)

    /** Focus could not move (end of a row or column). */
    fun edge()

    fun select()
    fun back()

    /** Per-app generative motif, seeded by the app's package name. */
    fun launch(app: LauncherApp)

    /** Entering a screensaver. */
    fun sleep()
    fun wake()

    /** The sleep timer ran out: a short descending closing sound. Falls back to [sleep] until an engine gives it its own voice. */
    fun windDown() = sleep()

    /** Two ticks, select, a launch motif. Used by Settings when the waveform changes. */
    fun preview()
}

/** Silent implementation for previews, tests, and when audio is unavailable. */
object NoOpFieldSound : FieldSound {
    override fun tick(row: Int) = Unit
    override fun edge() = Unit
    override fun select() = Unit
    override fun back() = Unit
    override fun launch(app: LauncherApp) = Unit
    override fun sleep() = Unit
    override fun wake() = Unit
    override fun windDown() = Unit
    override fun preview() = Unit
}

val LocalFieldSound = staticCompositionLocalOf<FieldSound> { NoOpFieldSound }

/**
 * The visual row the current composition sits in, which sets the pitch of the focus tick
 * (`FieldSound.tick(row)`). HomeScreen provides it for each row; a multi-row grid adds its own grid-row
 * index on top. `Modifier.fieldFocus()` plays the tick when an element gains focus, so components
 * need no sound code for navigation.
 */
val LocalSoundRow = compositionLocalOf { 0 }
