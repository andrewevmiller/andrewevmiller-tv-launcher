package com.andrewevmiller.tvlauncher.sleep

import kotlinx.coroutines.CancellationException

/*
 * Data-driven "pause the media now" logic and the firing sequence. The Android layer knows how to send each named
 * rung (a media key event, audio focus); this file decides the order and when to stop. Spike S0 on the TV decides
 * which rung really works: pass its name as `provenFirst` and it moves to the front. Same shape as
 * ambient/StartStrategies.kt.
 */

data class PauseRung(val name: String, val description: String)

object PauseRungs {
    /** One AudioManager.dispatchMediaKeyEvent with KEYCODE_MEDIA_PAUSE. */
    const val MEDIA_KEY = "media-key"

    /** The same media pause key event, sent a second time. */
    const val MEDIA_KEY_RETRY = "media-key-retry"

    /** Take audio focus so the playing app pauses. */
    const val AUDIO_FOCUS = "audio-focus"

    val DEFAULT_ORDER: List<PauseRung> = listOf(
        PauseRung(MEDIA_KEY, "AudioManager.dispatchMediaKeyEvent with KEYCODE_MEDIA_PAUSE"),
        PauseRung(MEDIA_KEY_RETRY, "the same media pause key event, sent again"),
        PauseRung(AUDIO_FOCUS, "take audio focus so the playing app pauses"),
    )

    /**
     * [all] with the rung named [provenFirst] moved to the front. A null, blank or unknown name leaves the order
     * unchanged; the relative order of the rest is kept. Names match case-insensitively after trimming.
     */
    fun ordered(provenFirst: String?, all: List<PauseRung> = DEFAULT_ORDER): List<PauseRung> {
        val name = provenFirst?.trim()
        if (name.isNullOrEmpty()) return all
        val proven = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return all
        return listOf(proven) + all.filter { it !== proven }
    }
}

enum class PauseResult {
    /** Nothing was playing when the sleep timer fired. No rung was tried. */
    NOT_PLAYING,

    /** Something was playing and a rung stopped it. */
    PAUSED,

    /** Something was playing after every rung. */
    STILL_PLAYING,
}

/** [attempts] are the names of the rungs that were tried, in order (a rung that threw is still an attempt). */
data class PauseReport(val result: PauseResult, val attempts: List<String>)

/** How long the pause is given to take effect before the next check, in milliseconds. */
private const val PAUSE_SETTLE_MILLIS = 1_500L

/**
 * Tries [order] one rung at a time. Before any rung, [isPlaying] is checked: if nothing plays, the result is
 * NOT_PLAYING with no attempts. Otherwise each rung is performed, [settle] waits [settleMillis], and [isPlaying] is
 * checked again. The first rung after which nothing plays ends the ladder as PAUSED. If every rung is used and
 * something still plays, the result is STILL_PLAYING.
 *
 * A [perform] that throws is logged as FAILED and the ladder carries on, still waiting and checking after that rung.
 * Every step is reported through [log]. [settle] is injected so tests do not wait.
 */
suspend fun runPauseLadder(
    order: List<PauseRung>,
    isPlaying: () -> Boolean,
    perform: (PauseRung) -> Unit,
    settle: suspend (Long) -> Unit,
    settleMillis: Long = PAUSE_SETTLE_MILLIS,
    log: (String) -> Unit,
): PauseReport {
    if (!isPlaying()) {
        log("pause: nothing is playing, no rungs tried")
        return PauseReport(PauseResult.NOT_PLAYING, emptyList())
    }
    val attempts = ArrayList<String>()
    for ((index, rung) in order.withIndex()) {
        attempts.add(rung.name)
        log("pause rung '${rung.name}': ${rung.description}")
        try {
            perform(rung)
            log("pause rung '${rung.name}': sent")
        } catch (e: Exception) {
            log("pause rung '${rung.name}': FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        settle(settleMillis)
        if (!isPlaying()) {
            log("pause rung '${rung.name}': not playing after $settleMillis ms")
            log("pause: PAUSED on rung ${index + 1} of ${order.size}")
            return PauseReport(PauseResult.PAUSED, attempts)
        }
        log("pause rung '${rung.name}': still playing after $settleMillis ms")
    }
    log("pause: still playing after all ${order.size} rung(s)")
    return PauseReport(PauseResult.STILL_PLAYING, attempts)
}

/**
 * What the firing sequence did. [pause] is null when no pause report came back. [errors] lists every failed step.
 * [skipped] is the reason when the whole sequence was left out (the TV was already off); then nothing else happened.
 */
data class SleepEndOutcome(
    val pause: PauseReport?,
    val dimShown: Boolean,
    val errors: List<String>,
    val skipped: String? = null,
)

/** Gap between the pause and the closing sound, so the pause lands first. */
private const val SOUND_AFTER_PAUSE_MILLIS = 600L

/** Gap between the closing sound and the dim screen, so the sound finishes first. */
private const val DIM_AFTER_SOUND_MILLIS = 1_200L

/**
 * The firing sequence, in order: [pause], wait 600 ms, [closingSound], wait 1200 ms, [showDimScreen]. [pause] returns
 * the ladder's report, or null when there is nothing to report. Each step runs even if an earlier one threw: the
 * exception is caught, logged as "<step> FAILED: ...", and recorded in [SleepEndOutcome.errors]. The dim screen is
 * never skipped because the pause failed. A CancellationException is not swallowed; it stops the sequence.
 */
suspend fun runSleepEnd(
    pause: suspend () -> PauseReport?,
    closingSound: () -> Unit,
    showDimScreen: () -> Boolean,
    settle: suspend (Long) -> Unit,
    log: (String) -> Unit,
): SleepEndOutcome {
    val errors = ArrayList<String>()

    var pauseReport: PauseReport? = null
    log("sleep end: pausing media")
    guard("pause", errors, log) { pauseReport = pause() }

    log("sleep end: settle $SOUND_AFTER_PAUSE_MILLIS ms")
    guard("settle after pause", errors, log) { settle(SOUND_AFTER_PAUSE_MILLIS) }

    log("sleep end: closing sound")
    guard("closing sound", errors, log) { closingSound() }

    log("sleep end: settle $DIM_AFTER_SOUND_MILLIS ms")
    guard("settle after sound", errors, log) { settle(DIM_AFTER_SOUND_MILLIS) }

    var dimShown = false
    log("sleep end: dim screen")
    guard("dim screen", errors, log) { dimShown = showDimScreen() }
    log("sleep end: done, dim screen ${if (dimShown) "shown" else "not shown"}, ${errors.size} error(s)")

    return SleepEndOutcome(pause = pauseReport, dimShown = dimShown, errors = errors)
}

/** Runs one step of the sequence. A failure is recorded and logged; a cancellation is rethrown. */
private suspend fun guard(
    step: String,
    errors: MutableList<String>,
    log: (String) -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val line = "$step FAILED: ${e.javaClass.simpleName}: ${e.message}"
        errors.add(line)
        log("sleep end: $line")
    }
}
