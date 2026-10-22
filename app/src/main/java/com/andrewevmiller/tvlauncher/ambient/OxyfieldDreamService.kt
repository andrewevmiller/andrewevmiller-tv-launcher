package com.andrewevmiller.tvlauncher.ambient

import android.graphics.Typeface
import android.service.dreams.DreamService
import androidx.compose.ui.graphics.toArgb
import com.andrewevmiller.tvlauncher.R
import com.andrewevmiller.tvlauncher.theme.DarkFieldColors

/**
 * The Oxyfield screensaver. A screensaver is always dark, so the palette is [DarkFieldColors] converted
 * to ARGB. The content is a plain Canvas [OxyfieldView]: no Compose in a DreamService.
 *
 * Select it with
 * `adb shell settings put secure screensaver_components com.andrewevmiller.tvlauncher/.ambient.OxyfieldDreamService`
 * or from Settings (via [SystemScreensaverController]).
 *
 * The sleep timer can start this dream in a dimmed form: it sets [SleepDimRequest] first, and a dream that
 * [SleepDimRequest.consume]s a live request fades to near-black and holds a still frame (see [OxyfieldView.startSleepDim]).
 * A dream started without that request is the normal screensaver, unchanged.
 */
class OxyfieldDreamService : DreamService() {
    private var oxyfieldView: OxyfieldView? = null
    private var sleepDim = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Any key press or touch wakes the TV; the dream never takes input and never dims itself.
        setInteractive(false)
        setFullscreen(true)
        setScreenBright(true)

        val provider = reducedMotionProvider ?: SystemReducedMotionProvider(this)
        val view = OxyfieldView(
            context = this,
            palette = darkPalette(),
            reducedMotion = provider.isReduced(),
            typeface = plexMono(),
        )
        oxyfieldView = view

        // One-shot, and it expires, so only the dream the sleep timer just started is dimmed. Armed here so a
        // reduced-motion dream never paints a bright frame first; the fade clock restarts in onDreamingStarted.
        sleepDim = SleepDimRequest.consume()
        if (sleepDim) view.startSleepDim()
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // The dream is on screen now: the 45 s fade runs from here.
        if (sleepDim) oxyfieldView?.startSleepDim()
        oxyfieldView?.start()
    }

    override fun onDreamingStopped() {
        oxyfieldView?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        oxyfieldView?.stop()
        oxyfieldView = null
        super.onDetachedFromWindow()
    }

    private fun plexMono(): Typeface =
        try {
            resources.getFont(R.font.plex_mono_regular)
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }

    private fun darkPalette(): OxyfieldPalette = OxyfieldPalette(
        bg = DarkFieldColors.bg.toArgb(),
        dot = DarkFieldColors.line.toArgb(),
        muted = DarkFieldColors.muted.toArgb(),
        accent = DarkFieldColors.accent.toArgb(),
        ink = DarkFieldColors.ink.toArgb(),
    )

    companion object {
        /**
         * Decides reduced motion when a dream starts. Null means [SystemReducedMotionProvider]
         * (the system animation scale). The integrator sets one backed by `LauncherSettings.reducedMotion`
         * early (Application.onCreate), because the dream can start before any activity has run.
         */
        @Volatile
        var reducedMotionProvider: ReducedMotionProvider? = null
    }
}
