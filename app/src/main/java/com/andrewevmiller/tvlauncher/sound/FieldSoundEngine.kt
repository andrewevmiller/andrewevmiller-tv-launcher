package com.andrewevmiller.tvlauncher.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE
import java.util.Random
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FieldSound"

/**
 * The interface sounds, played through one low-latency streaming [AudioTrack].
 *
 * Thin Android glue around pure, tested parts: [SoundDirector] decides what plays (clip, tick row, mute,
 * humanize, preview schedule, gating), [SoundCache] renders the clips for the current waveform, and [Mixer] sums
 * overlapping sounds. This class owns the audio output, a writer thread, and the observation of settings and
 * per-app overrides.
 *
 * - Everything is gated by the launcher toggle AND the system "Sound effects" setting, checked on each call
 *   (the toggle from a cached copy of the settings flow; the system setting re-read at most once a second).
 * - Changing the waveform renders the new fixed sounds, then the app motifs, in the background. The old sounds
 *   keep playing until the new fixed set is ready. A re-roll re-renders just that app's motif.
 * - Audio failures are logged under the tag `FieldSound` and degrade to silence; nothing here throws to callers.
 *
 * Call [release] when the owner of [scope] goes away.
 */
class FieldSoundEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val overrides: MotifOverrides,
    private val appsProvider: () -> List<LauncherApp>,
    private val scope: CoroutineScope,
) : FieldSound {

    private val appContext: Context = context.applicationContext
    private val cache = SoundCache()
    private val mixer = Mixer().also { it.masterGain = dbToLinear(OUTPUT_GAIN_DB) }
    private val stream = AudioStream(appContext, mixer)

    @Volatile
    private var settingsNow = LauncherSettings()

    @Volatile
    private var overridesNow: Map<String, MotifOverride> = emptyMap()

    @Volatile
    private var released = false

    private val systemSoundEffects = ThrottledValue(SYSTEM_SETTING_REFRESH_MS) { systemSoundEffectsEnabled(appContext) }
    private val random = Random()
    private val jobs = ArrayList<Job>()

    private val director = SoundDirector(
        cache = cache,
        mixer = mixer,
        clock = { SystemClock.elapsedRealtime() },
        allowed = { soundsAllowed(settingsNow.soundsEnabled, systemSoundEffects.get(SystemClock.elapsedRealtime())) },
        overrides = { overridesNow },
        unit = { random.nextDouble() },
        background = { job -> scope.launch(Dispatchers.Default) { job() } },
        wakeWriter = { stream.wake() },
        onError = { what, t -> Log.w(TAG, "$what failed", t) },
    )

    init {
        stream.start()
        jobs += scope.launch(Dispatchers.Default) {
            settings.settings.collect { settingsNow = it }
        }
        jobs += scope.launch(Dispatchers.Default) {
            settings.settings.map { it.waveform }.distinctUntilChanged().collectLatest { renderFor(it) }
        }
        jobs += scope.launch(Dispatchers.Default) {
            var previous: Map<String, MotifOverride> = emptyMap()
            overrides.overrides.collect { current ->
                overridesNow = current
                rerenderChanged(previous, current)
                previous = current
            }
        }
    }

    override fun tick(row: Int) = director.tick(row)

    override fun edge() = director.edge()

    override fun select() = director.select()

    override fun back() = director.back()

    override fun launch(app: LauncherApp) = director.launch(app.component.packageName, app.category)

    override fun sleep() = director.sleep()

    override fun wake() = director.wake()

    override fun windDown() = director.windDown()

    /** The first sound set is rendered; until then every sound is dropped (see [SoundDirector]). */
    override fun isReady(): Boolean = cache.active() != null

    /**
     * Two ticks, select, a launch motif, against the latest waveform: if a re-render for a just-changed waveform is
     * still running this waits for it (at most [PREVIEW_MAX_WAIT_MS]) and then plays whatever is ready.
     */
    override fun preview() {
        if (released) return
        scope.launch(Dispatchers.Default) {
            withTimeoutOrNull(PREVIEW_MAX_WAIT_MS) {
                while (cache.active()?.waveform != settingsNow.waveform) delay(PREVIEW_POLL_MS)
            }
            director.playPreview()
        }
    }

    /** Tell the engine the installed-app list changed, so new apps get their motif rendered ahead of time. */
    fun onAppsChanged() {
        if (released) return
        val waveform = cache.active()?.waveform ?: return
        scope.launch(Dispatchers.Default) { renderFor(waveform) }
    }

    /** Stops the writer thread, releases the AudioTrack and stops observing. Safe to call twice. */
    fun release() {
        released = true
        for (job in jobs) job.cancel()
        stream.stop()
        mixer.clear()
    }

    private fun targets(): List<MotifTarget> =
        appsProvider().map { MotifTarget(it.component.packageName, it.category) }

    private suspend fun renderFor(waveform: Waveform) {
        if (released) return
        val job = currentCoroutineContext()[Job]
        try {
            cache.renderAll(waveform, targets(), overridesNow) { _, _ -> job?.ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "rendering sounds for $waveform failed", t)
        }
    }

    /** A re-roll changes the seed: render that app's new motif now so its next launch (and the settings preview) is ready. */
    private fun rerenderChanged(old: Map<String, MotifOverride>, new: Map<String, MotifOverride>) {
        val changed = rerollChanged(old, new)
        if (changed.isEmpty()) return
        val set = cache.active() ?: return
        try {
            val byPackage = LinkedHashMap<String, LauncherApp>()
            for (app in appsProvider()) byPackage.putIfAbsent(app.component.packageName, app)
            for (pkg in changed) {
                val app = byPackage[pkg] ?: continue
                val entry = new[pkg]
                if (entry?.muted == true) continue
                val rerolls = entry?.rerollCount ?: 0
                set.motifClip(pkg, app.category, rerolls)
                set.evictOtherRerolls(pkg, rerolls)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "re-rendering re-rolled motifs failed", t)
        }
    }

    companion object {
        /**
         * Gain applied to the whole mix, in dB, before the limiter. The plan's sounds are quiet on purpose
         * (their peaks sit around -15 to -35 dBFS), which is too little for a TV speaker, so everything is lifted
         * by this much. THE OWNER TUNES THIS BY EAR ON THE TV: raise it if the ticks are hard to hear, lower it if
         * select/launch are too loud. Overlapping sounds are caught by the limiter, so it cannot clip.
         */
        const val OUTPUT_GAIN_DB = 10.0

        private const val SYSTEM_SETTING_REFRESH_MS = 1000L
        private const val PREVIEW_POLL_MS = 10L

        private fun dbToLinear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()
    }
}

/**
 * The audio output and its writer thread. Streams only while there is something to play, plus a short tail of
 * silence; then pauses the track and sleeps on a semaphore (no busy-waiting) until [wake].
 */
private class AudioStream(context: Context, private val mixer: Mixer) {
    private val wakeup = Semaphore(0)
    private val blockFrames: Int = outputBurstFrames(context)
    private val tailBlocks: Int = max(1, (TAIL_MS * SAMPLE_RATE / 1000 / blockFrames).toInt())

    @Volatile
    private var running = true
    private var thread: Thread? = null

    fun start() {
        val t = Thread({ writerLoop() }, "FieldSound-audio")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Something was added to the mixer. */
    fun wake() {
        wakeup.release()
    }

    fun stop() {
        running = false
        wakeup.release()
        try {
            thread?.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun writerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            Log.w(TAG, "could not raise audio thread priority", t)
        }
        val block = FloatArray(blockFrames)
        var output: Output? = null
        var playing = false
        var idleBlocks = 0
        var failures = 0
        try {
            while (running) {
                val voices = mixer.hasActiveVoices()
                if (!voices && !playing) {
                    // Nothing to play and the track is paused: sleep until the next sound is requested.
                    wakeup.drainPermits()
                    if (!mixer.hasActiveVoices() && running) wakeup.acquire()
                    continue
                }
                if (failures >= MAX_FAILURES) {
                    // Audio is not working on this device: stay silent, and don't let voices pile up.
                    mixer.clear()
                    playing = false
                    continue
                }
                val out = output ?: Output.open(blockFrames).also { output = it }
                if (out == null) {
                    failures++
                    mixer.clear()
                    playing = false
                    continue
                }
                if (!playing) {
                    if (!out.play()) {
                        out.release()
                        output = null
                        failures++
                        continue
                    }
                    playing = true
                    idleBlocks = 0
                }
                mixer.mix(block)
                if (!out.write(block)) {
                    out.release()
                    output = null
                    playing = false
                    failures++
                    mixer.clear()
                    continue
                }
                failures = 0
                if (voices) {
                    idleBlocks = 0
                } else if (++idleBlocks >= tailBlocks) {
                    out.pause()
                    playing = false
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "audio writer stopped", t)
        } finally {
            output?.release()
        }
    }

    /** A streaming AudioTrack for 48 kHz mono, float if the device takes it, else 16-bit. */
    private class Output(
        private val track: AudioTrack,
        private val useFloat: Boolean,
        blockFrames: Int,
    ) {
        private val shorts: ShortArray? = if (useFloat) null else ShortArray(blockFrames)
        private var underruns = 0

        fun play(): Boolean = try {
            track.play()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.play failed", t)
            false
        }

        fun pause() {
            try {
                track.pause()
                track.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.pause failed", t)
            }
        }

        /** Blocks until the block is queued. False if the track reported an error (dead object, etc.). */
        fun write(block: FloatArray): Boolean {
            return try {
                val n = block.size
                val written = if (useFloat) {
                    track.write(block, 0, n, AudioTrack.WRITE_BLOCKING)
                } else {
                    val s = shorts!!
                    for (i in 0 until n) {
                        val v = block[i]
                        s[i] = (if (v > 1f) 1f else if (v < -1f) -1f else v).times(32767f).toInt().toShort()
                    }
                    track.write(s, 0, n, AudioTrack.WRITE_BLOCKING)
                }
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write returned $written")
                    false
                } else {
                    // A short write (track stopped under us) must not turn the writer loop into a busy loop.
                    if (written < n) Thread.sleep(1)
                    growBufferOnUnderrun()
                    true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.write failed", t)
                false
            }
        }

        /** Each underrun means the writer thread was late: give the track one more block of headroom. */
        private fun growBufferOnUnderrun() {
            val count = track.underrunCount
            if (count > underruns) {
                underruns = count
                val grown = min(track.bufferSizeInFrames + GROW_FRAMES, track.bufferCapacityInFrames)
                track.setBufferSizeInFrames(grown)
            }
        }

        fun release() {
            try {
                track.release()
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.release failed", t)
            }
        }

        companion object {
            private const val GROW_FRAMES = 128

            fun open(blockFrames: Int): Output? {
                for (useFloat in booleanArrayOf(true, false)) {
                    val track = build(useFloat, blockFrames) ?: continue
                    return Output(track, useFloat, blockFrames)
                }
                return null
            }

            private fun build(useFloat: Boolean, blockFrames: Int): AudioTrack? {
                return try {
                    val encoding = if (useFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
                    val bytesPerFrame = if (useFloat) 4 else 2
                    val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, encoding)
                    if (minBytes <= 0) return null
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(max(minBytes, BUFFER_BLOCKS * blockFrames * bytesPerFrame))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        track.release()
                        null
                    } else {
                        track
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "could not create an AudioTrack (float=$useFloat)", t)
                    null
                }
            }
        }
    }

    companion object {
        /**
         * Silence kept streaming after the last sound ends, in ms, before the track is paused. A paused track needs
         * a moment to restart, which would delay the first tick after a pause; a few seconds of idle silence costs
         * almost nothing and keeps navigation sounds immediate. Tunable.
         */
        private const val TAIL_MS = 3000L

        /** Track buffer size in write blocks (lower = lower latency, higher = safer against late writes). */
        private const val BUFFER_BLOCKS = 3

        private const val DEFAULT_BURST_FRAMES = 256
        private const val MAX_FAILURES = 3
        private const val STOP_JOIN_MS = 300L

        /** The device's preferred output burst, so one write is one hardware period; 64..512 frames. */
        private fun outputBurstFrames(context: Context): Int {
            val manager = context.getSystemService(AudioManager::class.java)
            val burst = manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            return (burst ?: DEFAULT_BURST_FRAMES).coerceIn(64, 512)
        }
    }
}
