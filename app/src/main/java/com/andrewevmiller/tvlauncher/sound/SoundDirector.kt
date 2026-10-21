package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory

/**
 * Every decision of the interface-sound engine, with the Android parts passed in as functions. The Android
 * [FieldSoundEngine] builds one of these around an AudioTrack-backed [Mixer]; the unit tests build one around a
 * fake clock and a plain [Mixer] and read the mix back.
 *
 * It picks the clip (fixed sound, tick row and key-repeat state, per-app motif), skips muted apps, gates everything
 * on [allowed], humanizes launches, schedules the preview with sample delays, and falls back to the generic launch
 * sound when a motif cannot be rendered. It never throws: every entry point catches and reports through [onError].
 * Audio plays from [SoundCache.active], so there is a sound set to play from as soon as the first render is done
 * and the old one keeps playing while a new waveform renders.
 *
 * @param clock monotonic milliseconds (SystemClock.elapsedRealtime on the TV), fed to the tick throttle.
 * @param allowed true when the launcher toggle and the system sound-effects setting are both on; called per sound.
 * @param overrides the latest per-app overrides.
 * @param unit uniform random draws in [0, 1), for the launch humanize.
 * @param background runs a job off the calling thread (a missing motif is rendered there, not on the UI thread).
 * @param wakeWriter tells the audio thread there is something new to mix.
 */
class SoundDirector(
    private val cache: SoundCache,
    private val mixer: Mixer,
    private val clock: () -> Long,
    private val allowed: () -> Boolean,
    private val overrides: () -> Map<String, MotifOverride>,
    private val unit: () -> Double,
    private val background: (() -> Unit) -> Unit,
    private val wakeWriter: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private val throttle = TickThrottle()

    fun tick(row: Int) {
        guarded("tick") {
            if (!allowed()) return@guarded
            val set = cache.active() ?: return@guarded
            // Only sounding ticks feed the throttle, like the mockup (it returns before touching `last` when off).
            val kind = throttle.classify(clock())
            play(set.fixed.tick(row, kind))
        }
    }

    fun edge() = fixed("edge") { it.edge }
    fun select() = fixed("select") { it.select }
    fun back() = fixed("back") { it.back }
    fun sleep() = fixed("sleep") { it.sleep }
    fun wake() = fixed("wake") { it.wake }
    fun windDown() = fixed("windDown") { it.windDown }

    /** The app's own motif, humanized; nothing if the app is muted; the generic launch sound if it cannot be rendered. */
    fun launch(packageName: String, category: AppCategory) {
        guarded("launch") {
            if (!allowed()) return@guarded
            val set = cache.active() ?: return@guarded
            val choice = launchChoice(packageName, overrides())
            if (choice !is LaunchChoice.Motif) return@guarded
            val h = humanize(unit(), unit())
            val ready = set.peekMotif(packageName, category, choice.rerollCount)
            if (ready != null) {
                play(ready, h.rate, h.gain)
            } else {
                background {
                    guarded("launch render") {
                        val clip = try {
                            set.motifClip(packageName, category, choice.rerollCount)
                        } catch (t: Throwable) {
                            onError("motif render for $packageName", t)
                            set.fixed.launchFallback
                        }
                        play(clip, h.rate, h.gain)
                    }
                }
            }
        }
    }

    /**
     * Tick(2) at 0, tick(2) at 110 ms, select at 240 ms, the Plex motif at 480 ms, all scheduled in one go through
     * the mixer's sample delays. No humanize: a preview should sound the same every time. May render the Plex motif
     * the first time, so call it off the UI thread.
     */
    fun playPreview() {
        guarded("preview") {
            if (!allowed()) return@guarded
            val set = cache.active() ?: return@guarded
            for (step in PREVIEW_STEPS) {
                val clip = when (step.sound) {
                    PreviewSound.TICK -> set.fixed.tick(PREVIEW_TICK_ROW, TickKind.NORMAL)
                    PreviewSound.SELECT -> set.fixed.select
                    PreviewSound.MOTIF -> try {
                        set.motifClip(PREVIEW_PACKAGE, PREVIEW_CATEGORY, 0)
                    } catch (t: Throwable) {
                        onError("preview motif", t)
                        set.fixed.launchFallback
                    }
                }
                mixer.add(clip, 1.0, 1f, msToSamples(step.delayMs))
            }
            wakeWriter()
        }
    }

    private inline fun fixed(what: String, pick: (FixedClips) -> Clip) {
        guarded(what) {
            if (!allowed()) return@guarded
            val set = cache.active() ?: return@guarded
            play(pick(set.fixed))
        }
    }

    private fun play(clip: Clip, rate: Double = 1.0, gain: Float = 1f) {
        mixer.add(clip, rate, gain, 0)
        wakeWriter()
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(what, t)
        }
    }
}
