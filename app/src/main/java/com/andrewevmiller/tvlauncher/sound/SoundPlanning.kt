package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE

/*
 * The decisions behind the sound engine, as small pure functions: which tick row, how a launch is humanized,
 * whether a launch is muted, what the settings preview plays and when. Pure Kotlin, JVM-tested; the Android
 * engine only supplies the clock, the audio output and the settings.
 */

/** Rows 0..11 get their own tick pitch (1320 Hz down in 60 Hz steps); deeper rows reuse the last one. */
const val TICK_ROW_MAX = 11

fun clampTickRow(row: Int): Int = row.coerceIn(0, TICK_ROW_MAX)

/** Per-launch variation: the same tune, never quite identical. */
data class Humanize(
    /** Playback-rate multiplier: 2^(cents/1200) with cents in +-5. */
    val rate: Double,
    /** Level multiplier in 0.9..1.1. */
    val gain: Float,
)

const val HUMANIZE_CENTS = 5.0
const val HUMANIZE_VELOCITY = 0.1

/** [centsUnit] and [velocityUnit] are uniform draws in [0, 1): cents uniform in +-5, velocity uniform in 0.9..1.1. */
fun humanize(centsUnit: Double, velocityUnit: Double): Humanize {
    val cents = (centsUnit * 2.0 - 1.0) * HUMANIZE_CENTS
    val velocity = 1.0 - HUMANIZE_VELOCITY + velocityUnit * 2.0 * HUMANIZE_VELOCITY
    return Humanize(rate = rateForCents(cents), gain = velocity.toFloat())
}

/** What a launch should do for one package. */
sealed interface LaunchChoice {
    /** Muted in Settings: no sound at all. */
    object Muted : LaunchChoice

    /** Play the motif seeded with this re-roll count. */
    data class Motif(val rerollCount: Int) : LaunchChoice
}

fun launchChoice(packageName: String, overrides: Map<String, MotifOverride>): LaunchChoice {
    val o = overrides[packageName] ?: return LaunchChoice.Motif(0)
    return if (o.muted) LaunchChoice.Muted else LaunchChoice.Motif(o.rerollCount.coerceAtLeast(0))
}

/** Packages whose re-roll count differs between two override maps (so their motif clips must be re-rendered). */
fun rerollChanged(old: Map<String, MotifOverride>, new: Map<String, MotifOverride>): Set<String> {
    val changed = LinkedHashSet<String>()
    for (pkg in old.keys + new.keys) {
        if ((old[pkg]?.rerollCount ?: 0) != (new[pkg]?.rerollCount ?: 0)) changed += pkg
    }
    return changed
}

enum class PreviewSound { TICK, SELECT, MOTIF }

/** One step of the settings preview: [sound] starts [delayMs] after the preview is requested. */
data class PreviewStep(val delayMs: Int, val sound: PreviewSound)

/** Row of the preview's two ticks. */
const val PREVIEW_TICK_ROW = 2

/** The preview's motif: Plex, a video app, so it always sounds the same whatever is installed. */
const val PREVIEW_PACKAGE = "com.plexapp.android"
val PREVIEW_CATEGORY = AppCategory.VIDEO

/** BUILD_PLAN section 8.1 `preview`: tick(2) at 0, tick(2) at 110 ms, select at 240 ms, Plex motif at 480 ms. */
val PREVIEW_STEPS: List<PreviewStep> = listOf(
    PreviewStep(0, PreviewSound.TICK),
    PreviewStep(110, PreviewSound.TICK),
    PreviewStep(240, PreviewSound.SELECT),
    PreviewStep(480, PreviewSound.MOTIF),
)

fun msToSamples(ms: Int, sampleRate: Int = SAMPLE_RATE): Int = (ms.toLong() * sampleRate / 1000).toInt()

/** How long the preview waits for an in-flight waveform re-render before playing whatever is ready. */
const val PREVIEW_MAX_WAIT_MS = 800L

/** Re-reads an expensive value (the system sound-effects setting) at most once per [intervalMs]. */
class ThrottledValue<T>(private val intervalMs: Long, private val read: () -> T) {
    private var value: T? = null
    private var readAtMs = 0L
    private var hasValue = false

    @Synchronized
    fun get(nowMs: Long): T {
        if (!hasValue || nowMs < readAtMs || nowMs - readAtMs >= intervalMs) {
            value = read()
            readAtMs = nowMs
            hasValue = true
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
