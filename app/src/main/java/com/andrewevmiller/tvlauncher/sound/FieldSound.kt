package com.andrewevmiller.tvlauncher.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Null in previews; the activity provides the real engine. */
val LocalFieldSound = staticCompositionLocalOf<FieldSound?> { null }

enum class Waveform { SINE, TRIANGLE, SAW, SQUARE, SUPERSAW }

/**
 * Interface sounds, ported from the `sfx` block of docs/mockup/field-launcher-mockup.html:
 * detuned oscillators -> sine wavefolder -> tanh saturation (drive x1.6) -> resonant low-pass sweep,
 * with a band-passed noise click on the attack, then a 70Hz high-pass. Focus ticks, edge thud and
 * select/back are pre-rendered into a SoundPool; each app's launch motif is rendered on demand from
 * its package name (FNV-1a -> mulberry32, A minor pentatonic) so an app always plays the same tune.
 */
class FieldSound(private val context: Context) {
    var waveform = Waveform.SAW

    private val rate = 48000
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(attrs())
        .build()
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var lastTick = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
        worker.execute { renderFixedSet() }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Follows the system "sound effects" setting, as the design rules require. */
    private fun allowed() = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    }.getOrDefault(true)

    fun tick() {
        val now = System.currentTimeMillis()
        val held = now - lastTick < 90
        lastTick = now
        if (now - lastTick < 0) return
        play(if (held) "tick_held" else "tick", if (held) 0.6f else 1f)
    }

    fun edge() = play("edge")
    fun select() = play("select")
    fun back() = play("back")

    fun launch(app: LauncherApp) {
        if (!allowed()) return
        worker.execute {
            val motif = motifVoices(app.component.packageName, app.category, humanize = true)
            playBuffer(render(motif, 0.6))
        }
    }

    // --- playback ---------------------------------------------------------------------------

    private fun play(name: String, volume: Float = 1f) {
        if (!allowed()) return
        val id = ids[name] ?: return
        if (synchronized(ready) { id !in ready }) return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    private fun playBuffer(samples: ShortArray) {
        val bytes = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        main.postDelayed({ runCatching { track.release() } }, samples.size * 1000L / rate + 150)
    }

    private fun renderFixedSet() {
        fun add(name: String, voices: List<Voice>, dur: Double) {
            val file = File(context.cacheDir, "fs_$name.wav")
            file.writeBytes(wav(render(voices, dur)))
            ids[name] = pool.load(file.absolutePath, 1)
        }
        add("tick", listOf(tick(1320.0, 0.03, 0.02, 0.03)), 0.06)
        add("tick_held", listOf(tick(1320.0, 0.02, 0.012, 0.01)), 0.05)
        add("edge", listOf(Voice(70.0, 0.0, 0.13, 0.06, detune = 20.0, glide = 50.0, fold = 3.5, drive = 10.0, crush = 12, lpFrom = 1800.0, lpTo = 300.0, click = 0.02)), 0.2)
        add(
            "select",
            listOf(
                Voice(440.0, 0.0, 0.08, 0.028, fold = 2.5, drive = 7.0, click = 0.025),
                Voice(660.0, 0.05, 0.10, 0.026, fold = 2.5, drive = 7.0),
            ),
            0.2,
        )
        add(
            "back",
            listOf(
                Voice(660.0, 0.0, 0.07, 0.026, fold = 2.5, drive = 7.0, click = 0.025),
                Voice(440.0, 0.045, 0.10, 0.028, fold = 2.5, drive = 7.0),
            ),
            0.2,
        )
    }

    private fun tick(f: Double, dur: Double, gain: Double, click: Double) =
        Voice(f, 0.0, dur, gain, voices = 1, fold = 3.0, drive = 8.0, lpFrom = 9000.0, lpTo = 3000.0, click = click)

    // --- motifs -----------------------------------------------------------------------------

    private class Timbre(val voices: Int, val detune: Double, val drive: Double, val fold: Double, val crush: Int, val lpFrom: Double, val lpTo: Double, val q: Double, val click: Double)

    private fun timbre(c: AppCategory) = when (c) {
        AppCategory.VIDEO -> Timbre(2, 14.0, 4.0, 0.0, 0, 2400.0, 900.0, 2.0, 0.015)
        AppCategory.MUSIC -> Timbre(3, 10.0, 5.0, 1.3, 0, 3200.0, 1100.0, 7.0, 0.02)
        AppCategory.GAMES -> Timbre(2, 6.0, 8.0, 0.0, 8, 7000.0, 3000.0, 1.5, 0.02)
        AppCategory.TOOLS -> Timbre(1, 0.0, 10.0, 2.0, 0, 5000.0, 2000.0, 1.5, 0.045)
        AppCategory.OTHER -> Timbre(2, 9.0, 6.0, 1.5, 0, 4000.0, 1500.0, 2.0, 0.02)
    }

    private fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in s) h = (h xor ch.code) * 0x01000193
        return h
    }

    private fun mulberry32(seed0: Int): () -> Double {
        var seed = seed0
        return {
            seed += 0x6D2B79F5
            var t = seed
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    /** Same hash, PRNG and draw order as the mockup so the TV plays the motifs auditioned there. */
    private fun motifVoices(pkg: String, category: AppCategory, humanize: Boolean): List<Voice> {
        val r = mulberry32(fnv1a(pkg))
        val count = if (r() < 0.3) 4 else 3
        var deg = floor(r() * 5).toInt()
        val degs = mutableListOf(deg)
        for (i in 1 until count) {
            deg += 1 + floor(r() * 2).toInt()
            degs += deg
        }
        val gap = (35 + r() * 35).roundToInt()
        val hold = r() < 0.5
        val scale = intArrayOf(0, 3, 5, 7, 10)
        val freqs = degs.map { d -> 440.0 * 2.0.pow((57 + scale[d % 5] + 12 * (d / 5) - 69) / 12.0) }
        val tb = timbre(category)
        val cents = if (humanize) Math.random() * 10 - 5 else 0.0
        val vel = if (humanize) 0.9 + Math.random() * 0.2 else 1.0
        val voices = freqs.mapIndexed { i, f ->
            val last = i == freqs.lastIndex
            Voice(
                f * 2.0.pow(cents / 1200), i * gap / 1000.0, if (last) (if (hold) 0.24 else 0.1) else 0.11, 0.026 * vel,
                voices = tb.voices, detune = tb.detune, fold = tb.fold, drive = tb.drive, crush = tb.crush,
                lpFrom = tb.lpFrom, lpTo = tb.lpTo, q = tb.q, click = if (i == 0) tb.click else 0.0,
            )
        }
        val sub = Voice(freqs[0] / 4, 0.0, 0.26, 0.03 * vel, voices = 1, detune = 0.0, fold = 0.0, drive = 3.0, lpFrom = 400.0, lpTo = 150.0)
        return voices + sub
    }

    // --- DSP --------------------------------------------------------------------------------

    private class Voice(
        val f: Double, val at: Double, val dur: Double, val gain: Double,
        val voices: Int = 2, val detune: Double = 9.0, val glide: Double? = null,
        val fold: Double = 2.2, val drive: Double = 6.0, val crush: Int = 0,
        val lpFrom: Double = 7000.0, val lpTo: Double = 2500.0, val q: Double = 1.5, val click: Double = 0.0,
    )

    private class Biquad {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun osc(p: Double): Double = when (waveform) {
        Waveform.SINE -> sin(2 * PI * p)
        Waveform.TRIANGLE -> 4 * abs(p - 0.5) - 1
        Waveform.SQUARE -> if (p < 0.5) 1.0 else -1.0
        else -> 2 * p - 1
    }

    private fun render(voices: List<Voice>, totalSeconds: Double): ShortArray {
        val n = (totalSeconds * rate).toInt()
        val mix = DoubleArray(n)
        for (v in voices) {
            val start = (v.at * rate).toInt()
            val len = minOf((v.dur * rate).toInt(), n - start)
            val count = if (waveform == Waveform.SUPERSAW) 7 else v.voices
            val spread = if (count == 1) doubleArrayOf(0.0) else DoubleArray(count) { -1 + 2.0 * it / (count - 1) }
            val detune = if (waveform == Waveform.SUPERSAW) maxOf(v.detune, 22.0) else v.detune
            val phases = DoubleArray(count) { Math.random() }
            val k = v.drive * 1.6
            val norm = tanh(k)
            val lp = Biquad()
            val bp = Biquad()
            val peak = v.gain
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                val base = if (v.glide != null) v.f * (v.glide / v.f).pow(t / v.dur) else v.f
                var sum = 0.0
                for (o in 0 until count) {
                    val fo = base * 2.0.pow(spread[o] * detune / 1200)
                    phases[o] = (phases[o] + fo / rate) % 1.0
                    sum += osc(phases[o])
                }
                sum /= sqrt(count.toDouble())
                var y = sum.coerceIn(-1.0, 1.0)
                if (v.fold > 0) y = sin(v.fold * y * PI / 2)
                y = tanh(k * y) / norm
                if (v.crush > 0) y = (y * v.crush).roundToInt().toDouble() / v.crush
                // resonant low-pass with an exponential cutoff sweep (RBJ biquad)
                val fc = (v.lpFrom * (maxOf(60.0, v.lpTo) / v.lpFrom).pow(t / v.dur)).coerceAtMost(rate * 0.45)
                val qLin = 10.0.pow(v.q / 20)
                val w = 2 * PI * fc / rate
                val alpha = sin(w) / (2 * qLin)
                val c = cos(w)
                val a0 = 1 + alpha
                y = lp.process(y, (1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
                val env = if (t < 0.003) 1e-4 * (peak / 1e-4).pow(t / 0.003)
                else peak * (1e-4 / peak).pow((t - 0.003) / maxOf(1e-3, v.dur - 0.003))
                var out = y * env
                if (v.click > 0 && t < 0.02) {
                    // noise burst through a 3.2kHz band-pass (Q 1.2), 12ms decay
                    val wb = 2 * PI * 3200 / rate
                    val ab = sin(wb) / (2 * 1.2)
                    val a0b = 1 + ab
                    val noise = bp.process(Math.random() * 2 - 1, ab / a0b, 0.0, -ab / a0b, -2 * cos(wb) / a0b, (1 - ab) / a0b)
                    out += noise * v.click * (1e-4 / v.click).pow(minOf(t, 0.012) / 0.012)
                }
                mix[start + i] += out
            }
        }
        // master: 70Hz high-pass, output trim and a soft limiter. OUTPUT_BOOST lifts the mockup's very
        // quiet levels (about -30 dBFS) to something audible on a TV speaker.
        val hp = Biquad()
        val wh = 2 * PI * 70 / rate
        val ah = sin(wh) / (2 * 0.707)
        val a0h = 1 + ah
        val ch = cos(wh)
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val y = hp.process(mix[i], (1 + ch) / 2 / a0h, -(1 + ch) / a0h, (1 + ch) / 2 / a0h, -2 * ch / a0h, (1 - ah) / a0h)
            pcm[i] = (tanh(y * 0.8 * OUTPUT_BOOST) * 32000).toInt().toShort()
        }
        return pcm
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return ByteArrayOutputStream().apply { write(h.array()); write(data) }.toByteArray()
    }

    fun release() {
        worker.shutdown()
        pool.release()
    }

    private companion object {
        const val OUTPUT_BOOST = 8.0
    }
}
