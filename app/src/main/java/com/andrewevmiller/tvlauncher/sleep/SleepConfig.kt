package com.andrewevmiller.tvlauncher.sleep

/*
 * The two numbers the owner flips after the TV spike (spike/README.md section 6, E3), and the timings the
 * Android side shares. Pure, so the tests read the same constants the app uses.
 */
object SleepConfig {
    /**
     * The pause rung the spike proved on the TV: one of [PauseRungs.MEDIA_KEY], [PauseRungs.MEDIA_KEY_RETRY] or
     * [PauseRungs.AUDIO_FOCUS]. Null keeps [PauseRungs.DEFAULT_ORDER]. The proven rung is tried first; the rest stay
     * as fallbacks, so a wrong value costs a second or two, never the pause.
     */
    val PROVEN_PAUSE_RUNG: String? = null

    /**
     * The dim-screen strategy the spike proved: [DimStrategies.OWN_ACTIVITY] or [DimStrategies.SCREENSAVER]. Null
     * keeps [DimStrategies.DEFAULT_ORDER]. Same rule as above: moved to the front, the others stay as fallbacks.
     */
    val PROVEN_DIM_STRATEGY: String? = null

    /** Logcat tag for everything the end sequence does: `adb logcat -s FieldSleep`. */
    const val TAG = "FieldSleep"

    /** SharedPreferences file that keeps the running timer across a process restart. */
    const val PREFS_NAME = "field_sleep"

    /**
     * A stored deadline up to this far in the PAST is not stale, it is the alarm that is firing right now into a
     * process that had to be started for it (cold start plus scheduling delay). Older than this the TV was off or the
     * launcher was away at the deadline, and the timer is dropped, never fired late. Within it a deadline IS still fired
     * on the next start, after a reboot too (see [restoreTimer]).
     */
    const val DUE_GRACE_MILLIS = 120_000L

    /** An alarm may arrive a little before the stored deadline (wall clock stepped by a time sync). Still due. */
    const val EARLY_TOLERANCE_MILLIS = 5_000L

    /** A just-due timer found on startup also gets a back-up alarm this far ahead, in case the original was lost. */
    const val FALLBACK_DELAY_MILLIS = 2_000L

    /** The receiver finishes its PendingResult by this time whatever the sequence is doing (the system allows ~10 s). */
    const val RELEASE_WATCHDOG_MILLIS = 8_500L

    /** After asking for a dim screen, how long to wait for proof that it came up, and how often to look. */
    const val DIM_WAIT_MILLIS = 3_000L
    const val DIM_POLL_MILLIS = 100L

    /**
     * Audio focus is held this long on top of the ladder's own settle time before the check, so the total hold is the
     * spike's 3 s (1.5 s here + 1.5 s settle).
     */
    const val FOCUS_EXTRA_HOLD_MILLIS = 1_500L

    /** The dim screen ignores keys this long after it appears (a key still being released must not wake it). */
    const val DIM_KEY_GRACE_MILLIS = 700L

    /**
     * SleepDimActivity closes itself after this long, if no key was pressed, so that route cannot leave the TV on a
     * dark screen indefinitely (the app that was playing comes back to the front, still paused). The SCREENSAVER route
     * has no such timer: Oxyfield is then an ordinary dream and ends on the first key press, like any dream.
     */
    const val DIM_AUTO_FINISH_MILLIS = 30L * 60_000L

    /**
     * Whether SleepDimActivity sets FLAG_KEEP_SCREEN_ON. A trade-off the owner settles after the TV test:
     *  - false (the default): the TV's own screen-off timeout keeps running, so real power-off stays with the TV, as the
     *    plan says and as Google's own dream behaves. The cost: the timeout may cut the 45 s fade short. When the screen
     *    goes off the dim activity closes itself (it is stopped), and the paused app is what shows on the next wake.
     *  - true: the dim screen is held until a key is pressed or [DIM_AUTO_FINISH_MILLIS] passes, and the TV's own
     *    timeout does not run meanwhile.
     */
    const val DIM_KEEPS_SCREEN_ON = false

    /**
     * Before the closing sound, wait up to this long for the sound engine to say it is ready. The engine renders its
     * sound set in the background and drops every sound until that is done, which on a process started for the alarm is
     * the first thing that happens. The sound is played after the wait either way, ready or not.
     */
    const val SOUND_READY_WAIT_MILLIS = 3_000L
    const val SOUND_READY_POLL_MILLIS = 50L
}
