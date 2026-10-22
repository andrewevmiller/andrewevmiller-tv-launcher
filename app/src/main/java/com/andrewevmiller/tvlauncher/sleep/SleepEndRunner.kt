package com.andrewevmiller.tvlauncher.sleep

/*
 * The orchestration of the end of the sleep timer, with every Android call behind a small interface so the whole
 * sequence is tested on the plain JVM. SystemSleepTimer is the real SleepEndTimer, AndroidSleepEndActions the real
 * actions; the tests pass fakes.
 *
 * The step order and the "a failing step never stops the next one" rule live in runSleepEnd (PauseLadder.kt); the
 * ladder order in runPauseLadder; the dim order in runDimStrategies. This class adds the bookkeeping around them:
 * claim a due timer exactly once, skip everything if the TV is already off, warm up, pick the orders from SleepConfig,
 * and always put the timer back to Idle.
 */

/** The timer's side of the firing: a claim that can succeed only once, and the hand-back. */
interface SleepEndTimer {
    /**
     * Claims a due timer for firing: true at most once per timer. On true the state is Firing, and the stored deadline
     * and the alarm are already gone, so a second alarm (or a restart) cannot fire it again. False when there is
     * nothing due (never started, cancelled, extended in the meantime, already claimed).
     */
    fun beginFiring(): Boolean

    /** The sequence is over, whatever happened in it: Firing becomes Idle (a timer started meanwhile is left alone). */
    fun endFiring()
}

/** What the pause ladder needs from the platform. Called on a worker thread. */
interface PauseActions {
    /** AudioManager.isMusicActive on the TV. */
    fun isPlaying(): Boolean

    /** Sends one rung: a media key, or audio focus. May throw; the ladder logs it and goes on. */
    fun perform(rung: PauseRung)

    /** Lets go of anything [perform] still holds (audio focus). Always called after the ladder, even when it threw. */
    fun release()
}

/** Everything the end sequence does to the outside world. */
interface SleepEndActions : PauseActions, DimHost {
    /**
     * True while the TV is on (the screen is interactive, which includes a screensaver running). False when the user
     * switched the TV off before the deadline: then the alarm must not play a sound or light the screen again.
     */
    fun isScreenOn(): Boolean

    /** Started first, right after the claim: build whatever is slow to build (the sound engine is, on a cold process). */
    fun warmUp()

    /** The closing sound (`FieldSound.windDown()`), which obeys the interface-sounds switch itself. */
    fun playClosingSound()

    /** Waits on the calling worker thread (the dim-screen poll). */
    fun sleep(millis: Long)
}

/**
 * Runs the end of the timer once.
 *
 * @param actionsFactory builds the platform side. Called only after the timer was claimed, so a stray alarm costs
 *   nothing, and inside the guarded region, so a failure to build it still hands the timer back.
 * @param settle waits between steps; `delay` on the device, a recorder in tests.
 * @param log every line goes here (logcat tag [SleepConfig.TAG] on the device).
 * @param provenPause the spike's winners, see [SleepConfig]; [provenDim] likewise.
 */
class SleepEndRunner(
    private val timer: SleepEndTimer,
    private val actionsFactory: () -> SleepEndActions,
    private val settle: suspend (Long) -> Unit,
    private val log: (String) -> Unit,
    private val provenPause: String? = SleepConfig.PROVEN_PAUSE_RUNG,
    private val provenDim: String? = SleepConfig.PROVEN_DIM_STRATEGY,
) {
    /**
     * Claims the timer and runs pause, closing sound and dim screen. Returns null, having done and played nothing, when
     * no timer is due. [onDimRequested] is called when a dim screen has been asked for (the receiver finishes its
     * broadcast there); it may be called more than once, or never.
     *
     * If the TV is already off when the timer is claimed, the whole sequence is skipped (no pause, no sound, no dim
     * screen) and the outcome has [SleepEndOutcome.skipped] set.
     *
     * The timer is handed back (Firing -> Idle) in a finally block, so an exception or cancellation cannot leave it
     * stuck in Firing. That only happens for a timer this call claimed: a call that finds nothing to claim returns
     * before the try and never touches the state.
     */
    suspend fun run(onDimRequested: () -> Unit = {}): SleepEndOutcome? {
        if (!timer.beginFiring()) {
            log("sleep end: no due timer, nothing to do")
            return null
        }
        log("sleep end: timer reached its deadline")
        try {
            val actions = actionsFactory()
            if (!screenIsOn(actions)) {
                log("sleep end: TV already off, skipped")
                return SleepEndOutcome(pause = null, dimShown = false, errors = emptyList(), skipped = "TV already off")
            }
            try {
                actions.warmUp()
            } catch (e: Exception) {
                log("sleep end: warm-up FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
            return runSleepEnd(
                pause = { pauseStep(actions) },
                closingSound = actions::playClosingSound,
                showDimScreen = { dimStep(actions, onDimRequested) },
                settle = settle,
                log = log,
            )
        } finally {
            timer.endFiring()
        }
    }

    /** A screen check that fails counts as "on": better one sequence too many than a timer that silently does nothing. */
    private fun screenIsOn(actions: SleepEndActions): Boolean =
        try {
            actions.isScreenOn()
        } catch (e: Exception) {
            log("sleep end: screen check FAILED: ${e.javaClass.simpleName}: ${e.message}")
            true
        }

    private suspend fun pauseStep(actions: SleepEndActions): PauseReport? {
        val order = PauseRungs.ordered(provenPause)
        log("pause order: ${order.joinToString { it.name }}")
        try {
            return runPauseLadder(
                order = order,
                isPlaying = actions::isPlaying,
                perform = actions::perform,
                settle = settle,
                log = log,
            )
        } finally {
            try {
                actions.release()
            } catch (e: Exception) {
                log("pause: release FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun dimStep(actions: SleepEndActions, onDimRequested: () -> Unit): Boolean {
        val order = DimStrategies.ordered(provenDim)
        log("dim order: ${order.joinToString { it.name }}")
        return runDimStrategies(
            order = order,
            attempt = { attemptDim(it, actions, actions::sleep, onDimRequested) },
            log = log,
        )
    }
}

/** Runs [action] at most once, from any thread; later calls do nothing. For finishing a PendingResult exactly once. */
class OnceAction(private val action: () -> Unit) {
    private val done = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True if this call ran the action. */
    operator fun invoke(): Boolean {
        if (!done.compareAndSet(false, true)) return false
        action()
        return true
    }
}
