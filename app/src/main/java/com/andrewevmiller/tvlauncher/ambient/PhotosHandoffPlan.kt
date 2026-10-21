package com.andrewevmiller.tvlauncher.ambient

import com.andrewevmiller.tvlauncher.settings.Screensaver

/*
 * The decisions behind the Photos slideshow hand-off, with no Android types: what to do given whether the
 * screensaver can be switched, and what to do once startNow() has answered. PhotosHandoff.kt carries them out.
 */

enum class HandoffAction {
    /** Can't switch and Photos isn't already the screensaver: starting would show the wrong one. Hint only. */
    HINT_ONLY,

    /** Switch to Google Photos, start it, put the user's choice back afterwards. */
    APPLY_AND_START,

    /** Can't switch but the user already has Photos selected: just start it. */
    START_ONLY,
}

/** [restoreTo] is the user's own screensaver to put back, or null when nothing was changed or needs undoing. */
data class PhotosHandoffPlan(val action: HandoffAction, val restoreTo: Screensaver?)

/**
 * [message] is a hint for the integrator to show, null when everything worked. At most one of
 * [restoreNow] and [restoreAfterDream] is true.
 */
data class PhotosHandoffOutcome(
    val message: String?,
    val restoreNow: Boolean,
    val restoreAfterDream: Boolean,
)

object PhotosHandoffPolicy {
    const val HINT = "Pick Google Photos as the screensaver in Settings, then let the TV idle."

    /** If the dream never reports that it stopped, stop waiting (and put the user's choice back) after this. */
    const val SAFETY_TIMEOUT_MS: Long = 2L * 60L * 60L * 1000L

    fun plan(canSwitch: Boolean, current: Screensaver): PhotosHandoffPlan = when {
        canSwitch -> PhotosHandoffPlan(
            HandoffAction.APPLY_AND_START,
            if (current == Screensaver.GOOGLE_PHOTOS) null else current,
        )
        current == Screensaver.GOOGLE_PHOTOS -> PhotosHandoffPlan(HandoffAction.START_ONLY, null)
        else -> PhotosHandoffPlan(HandoffAction.HINT_ONLY, null)
    }

    /**
     * [started] is what startNow() returned (ignored for HINT_ONLY, which never calls it).
     * A failed start after switching is undone straight away: the screensaver must not stay changed when
     * the owner got only a hint.
     */
    fun outcome(plan: PhotosHandoffPlan, started: Boolean): PhotosHandoffOutcome = when (plan.action) {
        HandoffAction.HINT_ONLY -> PhotosHandoffOutcome(HINT, restoreNow = false, restoreAfterDream = false)
        HandoffAction.START_ONLY ->
            PhotosHandoffOutcome(if (started) null else HINT, restoreNow = false, restoreAfterDream = false)
        HandoffAction.APPLY_AND_START ->
            if (started) {
                PhotosHandoffOutcome(null, restoreNow = false, restoreAfterDream = plan.restoreTo != null)
            } else {
                PhotosHandoffOutcome(HINT, restoreNow = plan.restoreTo != null, restoreAfterDream = false)
            }
    }
}
