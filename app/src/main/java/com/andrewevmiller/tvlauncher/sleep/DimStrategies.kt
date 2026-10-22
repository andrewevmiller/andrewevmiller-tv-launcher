package com.andrewevmiller.tvlauncher.sleep

/*
 * Data-driven "show the dim screen now" logic for the end of the sleep timer. Same shape as
 * ambient/StartStrategies.kt: the strategies are named, ordered here, and tried one by one until one reports that the
 * dim screen really came up. Spike E3 on the TV decides which method works; put its name in
 * SleepConfig.PROVEN_DIM_STRATEGY and it moves to the front. The Android layer only implements [DimHost].
 */

data class DimStrategy(val name: String, val description: String)

enum class DimOutcome {
    /** The dim screen came up (we saw proof, not just a start call that returned). */
    SHOWN,

    /** It was requested without an error, but no proof arrived in time (Android silently drops blocked starts). */
    NOT_SHOWN,

    /** This strategy cannot be tried here (for example Oxyfield is not the active screensaver). */
    NOT_RESOLVED,

    /** Resolved but the start threw or was refused. */
    FAILED,
}

data class DimAttempt(val outcome: DimOutcome, val detail: String = "")

object DimStrategies {
    /** Start our own SleepDimActivity from the alarm receiver. */
    const val OWN_ACTIVITY = "own-activity"

    /** Start the system screensaver, when it is our Oxyfield dream, asking the dream to come up dimmed. */
    const val SCREENSAVER = "screensaver"

    val DEFAULT_ORDER: List<DimStrategy> = listOf(
        DimStrategy(OWN_ACTIVITY, "Start SleepDimActivity (Oxyfield dimmed) with FLAG_ACTIVITY_NEW_TASK from the receiver"),
        DimStrategy(SCREENSAVER, "Request a dimmed dream, then start the screensaver, if Oxyfield is the active one"),
    )

    /**
     * [all] with the strategy named [provenFirst] moved to the front. A null, blank or unknown name leaves the order
     * unchanged; the relative order of the rest is kept. Names match case-insensitively after trimming.
     */
    fun ordered(provenFirst: String?, all: List<DimStrategy> = DEFAULT_ORDER): List<DimStrategy> {
        val name = provenFirst?.trim()
        if (name.isNullOrEmpty()) return all
        val proven = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return all
        return listOf(proven) + all.filter { it !== proven }
    }
}

/**
 * Tries [order] one by one until an attempt reports [DimOutcome.SHOWN]. An attempt that throws counts as FAILED.
 * Every try is reported through [log]. False when none worked: the caller carries on, the pause and the sound
 * already happened.
 */
fun runDimStrategies(
    order: List<DimStrategy>,
    attempt: (DimStrategy) -> DimAttempt,
    log: (String) -> Unit,
): Boolean {
    for (strategy in order) {
        val result = try {
            attempt(strategy)
        } catch (e: Exception) {
            DimAttempt(DimOutcome.FAILED, "${e.javaClass.simpleName}: ${e.message}")
        }
        val detail = if (result.detail.isEmpty()) "" else " (${result.detail})"
        log("dim strategy '${strategy.name}': ${result.outcome}$detail")
        if (result.outcome == DimOutcome.SHOWN) return true
    }
    log("dim screen not shown (${order.size} strategies tried)")
    return false
}

/**
 * Checks [condition] now and then every [intervalMillis] (waiting with [sleep]) until it holds or [timeoutMillis] of
 * waiting is used up. The wait is counted in whole intervals, not read from a clock, so a fake [sleep] makes it exact.
 */
fun pollUntil(
    timeoutMillis: Long,
    intervalMillis: Long,
    sleep: (Long) -> Unit,
    condition: () -> Boolean,
): Boolean {
    var waited = 0L
    while (true) {
        if (condition()) return true
        if (waited >= timeoutMillis) return false
        sleep(intervalMillis)
        waited += intervalMillis
    }
}

/** What the Android layer must be able to do for the two dim strategies. Every call is made on a worker thread. */
interface DimHost {
    /** OWN_ACTIVITY: starts the dim activity from the background. Throws if the system refuses at once. */
    fun startDimActivity()

    /** OWN_ACTIVITY: true once the activity started by the last [startDimActivity] has actually been created. */
    fun dimActivityShown(): Boolean

    /**
     * OWN_ACTIVITY: the start did not show in time; if it appears late it must close again at once. An activity that
     * had already been created before this call is left alone, so [dimActivityShown] read right after it is the truth.
     */
    fun abandonDimActivity()

    /** SCREENSAVER: true only when our own Oxyfield dream is the selected screensaver. */
    fun oxyfieldIsActiveScreensaver(): Boolean

    /** SCREENSAVER: marks the next dream as a sleep dream (it dims itself). */
    fun requestDreamDim()

    /** SCREENSAVER: starts the active screensaver now. False if the device refuses an on-demand start. */
    fun startScreensaver(): Boolean

    /** SCREENSAVER: true once a dream picked up the request (so a dream really started and is dimming). */
    fun dreamDimConsumed(): Boolean

    /** SCREENSAVER: forgets the request so it can never dim a later, ordinary screensaver. */
    fun clearDreamDim()
}

/**
 * Performs one dim [strategy] against [host]. [onRequested] is called as soon as a request has gone out (before the
 * wait for proof), which is when the alarm receiver may release its broadcast. A throw from [host] propagates;
 * [runDimStrategies] turns it into FAILED.
 */
fun attemptDim(
    strategy: DimStrategy,
    host: DimHost,
    sleep: (Long) -> Unit,
    onRequested: () -> Unit,
    waitMillis: Long = SleepConfig.DIM_WAIT_MILLIS,
    pollMillis: Long = SleepConfig.DIM_POLL_MILLIS,
): DimAttempt = when (strategy.name) {
    DimStrategies.OWN_ACTIVITY -> attemptOwnActivity(host, sleep, onRequested, waitMillis, pollMillis)
    DimStrategies.SCREENSAVER -> attemptScreensaver(host, sleep, onRequested, waitMillis, pollMillis)
    else -> DimAttempt(DimOutcome.NOT_RESOLVED, "unknown strategy")
}

private fun attemptOwnActivity(
    host: DimHost,
    sleep: (Long) -> Unit,
    onRequested: () -> Unit,
    waitMillis: Long,
    pollMillis: Long,
): DimAttempt {
    host.startDimActivity()
    onRequested()
    if (pollUntil(waitMillis, pollMillis, sleep) { host.dimActivityShown() }) {
        return DimAttempt(DimOutcome.SHOWN, "the activity was created")
    }
    host.abandonDimActivity()
    // The activity may have been created in the gap between the last poll and the abandon. Abandon never closes an
    // activity that already showed, so if it did, it is on screen: report it, and do not start a second strategy.
    if (host.dimActivityShown()) {
        return DimAttempt(DimOutcome.SHOWN, "the activity was created just as the wait ended")
    }
    return DimAttempt(DimOutcome.NOT_SHOWN, "the activity was not created within $waitMillis ms (background start blocked?)")
}

private fun attemptScreensaver(
    host: DimHost,
    sleep: (Long) -> Unit,
    onRequested: () -> Unit,
    waitMillis: Long,
    pollMillis: Long,
): DimAttempt {
    // Starting someone else's screensaver would not show our dim screen, so this strategy only applies to Oxyfield.
    if (!host.oxyfieldIsActiveScreensaver()) {
        return DimAttempt(DimOutcome.NOT_RESOLVED, "Oxyfield is not the active screensaver")
    }
    host.requestDreamDim()
    val started = try {
        host.startScreensaver()
    } catch (e: Exception) {
        host.clearDreamDim()
        throw e
    }
    if (!started) {
        host.clearDreamDim()
        return DimAttempt(DimOutcome.FAILED, "startNow() returned false")
    }
    onRequested()
    if (pollUntil(waitMillis, pollMillis, sleep) { host.dreamDimConsumed() }) {
        return DimAttempt(DimOutcome.SHOWN, "the dream picked up the dim request")
    }
    host.clearDreamDim()
    return DimAttempt(DimOutcome.NOT_SHOWN, "no dream picked up the dim request within $waitMillis ms")
}
