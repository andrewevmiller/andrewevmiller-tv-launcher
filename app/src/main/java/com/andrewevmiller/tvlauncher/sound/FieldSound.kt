package com.andrewevmiller.tvlauncher.sound

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Null in previews; the activity provides the real engine. */
val LocalFieldSound = staticCompositionLocalOf<FieldSound?> { null }

/**
 * Interface sounds, ported from the `sfx` block of docs/mockup/field-launcher-mockup.html (see [Synth]).
 * Focus ticks, edge thud and select/back are rendered once per waveform into the cache directory and loaded
 * into a SoundPool; each app's launch motif ([Motif]) is rendered once, kept in memory, and played with a
 * small per-launch pitch and level variation.
 *
 * The UI thread only enqueues: rendering runs on a background-priority thread, and every call into
 * SoundPool or AudioTrack runs on a separate playback thread.
 */
class FieldSound(context: Context) {
    private val appContext = context.applicationContext

    /** Changing it re-renders the fixed sounds and drops the cached motifs. */
    @Volatile
    var waveform: Waveform = Waveform.SAW
        set(value) {
            if (field == value) return
            field = value
            motifs.clear()
            onRender { renderFixedSet() }
        }

    private val main = Handler(Looper.getMainLooper())
    private val renderer = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "field-sound-render")
    }
    private val playback = Executors.newSingleThreadExecutor { task -> Thread(task, "field-sound-play") }
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(attrs())
        .build()
    private val ids = ConcurrentHashMap<String, Int>()
    private val ready = HashSet<Int>()
    private val throttle = TickThrottle()
    private val motifs = MotifCache()

    @Volatile private var soundEffects = true
    @Volatile private var released = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshSoundEffects()
    }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
        appContext.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SOUND_EFFECTS_ENABLED), false, observer,
        )
        onRender {
            refreshSoundEffects()
            renderFixedSet()
        }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Follows the system "sound effects" setting, as the design rules require. Read once, then kept current by [observer]. */
    private fun refreshSoundEffects() {
        soundEffects = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
        }.getOrDefault(true)
    }

    // --- the sounds ---------------------------------------------------------------------------

    fun tick() {
        Trace.beginSection("FieldSound.tick")
        try {
            when (throttle.next(SystemClock.elapsedRealtime())) {
                TickThrottle.Tick.NONE -> Unit
                TickThrottle.Tick.NORMAL -> play("tick", 1f)
                TickThrottle.Tick.HELD -> play("tick_held", 0.6f)
            }
        } finally {
            Trace.endSection()
        }
    }

    fun edge() = play("edge")
    fun select() = play("select")
    fun back() = play("back")

    /** Plays the app's motif, rendering it first only if [prepareMotifs] has not already. */
    fun launch(app: LauncherApp) {
        if (!soundEffects) return
        val requestedAt = SystemClock.elapsedRealtime()
        val key = MotifKey(app.component.packageName, app.category, waveform)
        val human = Humanize.random { Math.random() }
        onPlayback {
            val cached = motifs.get(key)
            if (cached != null) {
                Log.d(TAG, "launch motif: cache hit")
                playBuffer(cached, human, requestedAt)
            } else {
                Log.d(TAG, "launch motif: cache miss, rendering")
                onRender {
                    val pcm = motifs.get(key) ?: renderMotif(key).also { motifs.put(key, it) }
                    onPlayback { playBuffer(pcm, human, requestedAt) }
                }
            }
        }
    }

    /** Renders the motifs for Home's apps ahead of time, in the background, so a launch never waits for them. */
    fun prepareMotifs(apps: List<LauncherApp>) {
        if (!soundEffects) return
        val wanted = apps.filter { it.pinned }.take(MAX_PREPARED)
        if (wanted.isEmpty()) return
        onRender {
            Trace.beginSection("FieldSound.prepareMotifs")
            try {
                val wf = waveform
                for (app in wanted) {
                    if (released || wf != waveform) return@onRender
                    val key = MotifKey(app.component.packageName, app.category, wf)
                    if (!motifs.contains(key)) motifs.put(key, renderMotif(key))
                }
            } finally {
                Trace.endSection()
            }
        }
    }

    // --- playback ---------------------------------------------------------------------------

    private fun play(name: String, volume: Float = 1f) {
        if (!soundEffects) return
        onPlayback {
            val id = ids[name] ?: return@onPlayback
            if (synchronized(ready) { id !in ready }) return@onPlayback
            pool.play(id, volume, volume, 1, 0, 1f)
        }
    }

    private fun playBuffer(pcm: ShortArray, human: Humanize, requestedAt: Long) {
        Trace.beginSection("FieldSound.playBuffer")
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                // Per-launch variation: a few cents of pitch as playback rate, a few percent of level as volume.
                track.playbackRate = human.playbackRate(Synth.RATE)
                track.setVolume(human.velocity.toFloat())
                track.play()
            } catch (e: Exception) {
                track.release()
                throw e
            }
            Log.d(TAG, "launch motif: playing ${SystemClock.elapsedRealtime() - requestedAt} ms after the request")
            main.postDelayed({ runCatching { track.release() } }, pcm.size * 1000L / Synth.RATE + 150)
        } finally {
            Trace.endSection()
        }
    }

    // --- rendering --------------------------------------------------------------------------

    private fun renderMotif(key: MotifKey): ShortArray {
        Trace.beginSection("FieldSound.renderMotif")
        try {
            return Synth.render(Motif.voices(key.packageName, key.category), MOTIF_SECONDS, key.waveform, { Math.random() })
        } finally {
            Trace.endSection()
        }
    }

    /** Loads the fixed sounds for the current waveform, rendering only those not already cached on disk. */
    private fun renderFixedSet() {
        Trace.beginSection("FieldSound.renderFixedSet")
        try {
            val wf = waveform
            val keep = HashSet<String>()
            val loaded = HashMap<String, Int>()
            fun add(name: String, voices: List<Voice>, seconds: Double) {
                val fileName = SoundFiles.name(name, wf)
                keep += fileName
                val file = File(appContext.cacheDir, fileName)
                if (!file.exists() || file.length() <= WAV_HEADER_BYTES) {
                    val temp = File(appContext.cacheDir, "$fileName.tmp")
                    temp.writeBytes(Synth.wav(Synth.render(voices, seconds, wf, { Math.random() })))
                    if (!temp.renameTo(file)) {
                        temp.delete()
                        return
                    }
                }
                loaded[name] = pool.load(file.absolutePath, 1)
            }
            for (spec in FixedSounds.all) add(spec.name, spec.voices, spec.seconds)
            val replaced = loaded.keys.mapNotNull { ids[it] }
            ids.putAll(loaded)
            for (id in replaced) {
                pool.unload(id)
                synchronized(ready) { ready -= id }
            }
            for (name in SoundFiles.stale(appContext.cacheDir.list()?.toList() ?: emptyList(), keep)) {
                File(appContext.cacheDir, name).delete()
            }
        } finally {
            Trace.endSection()
        }
    }

    // --- threads ----------------------------------------------------------------------------

    /**
     * An exception that escapes a background thread kills the whole app, and a failed sound (no audio output,
     * a codec hiccup) must never take the launcher down with it. So every task logs and carries on.
     */
    private fun guarded(what: String, task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    private fun onPlayback(task: () -> Unit) {
        try {
            playback.execute { guarded("playback", task) }
        } catch (e: RejectedExecutionException) {
            // Released while this was being submitted.
        }
    }

    private fun onRender(task: () -> Unit) {
        try {
            renderer.execute { guarded("render", task) }
        } catch (e: RejectedExecutionException) {
            // Released while this was being submitted.
        }
    }

    fun release() {
        released = true
        appContext.contentResolver.unregisterContentObserver(observer)
        renderer.shutdownNow()
        playback.shutdown()
        pool.release()
    }

    private companion object {
        const val TAG = "FieldSound"
        const val MOTIF_SECONDS = 0.6
        const val WAV_HEADER_BYTES = 44L

        /** Home shows at most a screenful of apps; this bounds the work and the memory (about 58 KB each). */
        const val MAX_PREPARED = 24
    }
}
