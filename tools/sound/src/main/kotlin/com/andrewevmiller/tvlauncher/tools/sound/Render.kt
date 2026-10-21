package com.andrewevmiller.tvlauncher.tools.sound

import com.andrewevmiller.tvlauncher.sound.Clip
import com.andrewevmiller.tvlauncher.sound.Mixer
import com.andrewevmiller.tvlauncher.sound.PREVIEW_CATEGORY
import com.andrewevmiller.tvlauncher.sound.PREVIEW_PACKAGE
import com.andrewevmiller.tvlauncher.sound.PREVIEW_STEPS
import com.andrewevmiller.tvlauncher.sound.PREVIEW_TICK_ROW
import com.andrewevmiller.tvlauncher.sound.PreviewSound
import com.andrewevmiller.tvlauncher.sound.SoundCache
import com.andrewevmiller.tvlauncher.sound.SoundDirector
import com.andrewevmiller.tvlauncher.sound.SoundSet
import com.andrewevmiller.tvlauncher.sound.TickKind
import com.andrewevmiller.tvlauncher.sound.Waveform
import com.andrewevmiller.tvlauncher.sound.msToSamples
import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Turns the app's clips into the WAV contents. The clips come from SoundCache (fixed sounds, motifs); the preview
 * is scheduled by SoundDirector.playPreview (the app's own PREVIEW_STEPS); every clip is summed by the app's Mixer,
 * which applies the master gain and the soft limiter exactly as on the TV. Only the last step is the tool's:
 * the -1 dBFS ceiling and the 16-bit quantization, so the files cannot clip on a speaker.
 */

internal const val SOUNDS_PER_WAVEFORM = 19

/** Peak ceiling for every written file. Above it, the file is scaled down (and a warning printed). */
internal const val CEILING_DBFS = -1.0
private val CEILING_LINEAR: Double = 10.0.pow(CEILING_DBFS / 20.0)

/** Full scale for the 16-bit values; the quantizer and the meters both use it. */
internal const val FULL_SCALE = 32767.0

/** Mixer block size. Any size gives identical output; the length is trimmed exactly from the schedule. */
private const val BLOCK_FRAMES = 256

/** Folder name for a waveform: its enum name in lower case, e.g. WHITE_NOISE -> white_noise. */
internal fun folderName(w: Waveform): String = w.name.lowercase(Locale.ROOT)

/** The gain in dB to the linear master gain (same formula as the app's FieldSoundEngine). */
internal fun dbToLinear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

internal fun dbOf(linear: Double): Double = if (linear > 0.0) 20.0 * log10(linear) else Double.NEGATIVE_INFINITY

internal fun peakDbOf(pcm: ShortArray): Double {
    var peak = 0
    for (v in pcm) {
        val a = abs(v.toInt())
        if (a > peak) peak = a
    }
    return dbOf(peak / FULL_SCALE)
}

internal fun rmsDbOf(pcm: ShortArray): Double {
    if (pcm.isEmpty()) return Double.NEGATIVE_INFINITY
    var sum = 0.0
    for (v in pcm) {
        val x = v.toDouble() / FULL_SCALE
        sum += x * x
    }
    return dbOf(sqrt(sum / pcm.size))
}

/** Rendered samples as 16-bit PCM. [floatPeakDb] is the peak before the ceiling; [scaledByDb] is the cut applied (0 if none). */
internal class Pcm(val samples: ShortArray, val floatPeakDb: Double, val scaledByDb: Double)

/** One WAV file's worth of audio: [name] is the file name without ".wav", [label] is for people reading the output. */
internal class Sound(val name: String, val label: String, val pcm: Pcm) {
    val durationMs: Double get() = pcm.samples.size * 1000.0 / SAMPLE_RATE
    val peakDb: Double get() = peakDbOf(pcm.samples)
    val rmsDb: Double get() = rmsDbOf(pcm.samples)
}

/**
 * The largest 16-bit magnitude within the ceiling: floor(10^(-1/20) x 32767) = 29203. Scaling to the exact linear
 * ceiling (29203.6) and rounding would give 29204, which reads -0.999 dBFS and fails the check.
 */
private val CEILING_INT: Int = floor(CEILING_LINEAR * FULL_SCALE).toInt()

/** Applies the -1 dBFS ceiling (scaling down only if needed) and quantizes to signed 16-bit. */
internal fun toPcm16(mixed: FloatArray): Pcm {
    var peak = 0.0
    for (x in mixed) {
        val a = abs(x.toDouble())
        if (a > peak) peak = a
    }
    val scale = if ((peak * FULL_SCALE).roundToInt() > CEILING_INT) CEILING_INT / (peak * FULL_SCALE) else 1.0
    val samples = ShortArray(mixed.size) { i ->
        (mixed[i] * scale * FULL_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()
    }
    return Pcm(samples, dbOf(peak), if (scale < 1.0) dbOf(scale) else 0.0)
}

/** Reads [length] frames out of [mixer], one block at a time. Fails if voices are still playing after that. */
internal fun drain(mixer: Mixer, length: Int): FloatArray {
    val out = FloatArray(length)
    val block = FloatArray(BLOCK_FRAMES)
    var pos = 0
    while (pos < length) {
        val n = min(BLOCK_FRAMES, length - pos)
        mixer.mix(block, n)
        System.arraycopy(block, 0, out, pos, n)
        pos += n
    }
    check(!mixer.hasActiveVoices()) { "voices still playing after $length frames: the schedule length is wrong" }
    return out
}

/** One clip through the app's Mixer at rate 1, gain 1, no delay: what a launch or a fixed sound does on the TV. */
internal fun playOne(clip: Clip, masterGain: Float): FloatArray {
    val mixer = Mixer().also { it.masterGain = masterGain }
    mixer.add(clip)
    return drain(mixer, clip.size)
}

/**
 * The settings preview as the app plays it: [SoundDirector.playPreview] schedules tick(2) at 0, tick(2) at 110 ms,
 * select at 240 ms and the Plex motif at 480 ms into the Mixer with sample delays. The length is the end of the
 * last voice, computed from the same schedule.
 */
internal fun renderPreview(cache: SoundCache, set: SoundSet, masterGain: Float, errors: MutableList<String>): FloatArray {
    val mixer = Mixer().also { it.masterGain = masterGain }
    val director = SoundDirector(
        cache = cache,
        mixer = mixer,
        clock = { 0L },
        allowed = { true },
        overrides = { emptyMap() },
        unit = { 0.5 },
        background = { job -> job() },
        wakeWriter = { },
        onError = { what, t -> errors += "$what: $t" },
    )
    director.playPreview()
    val length = PREVIEW_STEPS.maxOf { step -> msToSamples(step.delayMs) + previewClip(set, step.sound).size }
    return drain(mixer, length)
}

private fun previewClip(set: SoundSet, sound: PreviewSound): Clip = when (sound) {
    PreviewSound.TICK -> set.fixed.tick(PREVIEW_TICK_ROW, TickKind.NORMAL)
    PreviewSound.SELECT -> set.fixed.select
    PreviewSound.MOTIF -> set.motifClip(PREVIEW_PACKAGE, PREVIEW_CATEGORY, 0)
}

/**
 * Every WAV for one waveform, in file order: the 9 fixed sounds, the preview, and one motif per package at
 * re-roll 0 (the motif an app gets before anyone re-rolls it). Motifs are rendered without the per-launch humanize,
 * so each file is the same every run.
 */
internal fun renderWaveform(waveform: Waveform, masterGain: Float, errors: MutableList<String>): List<Sound> {
    val cache = SoundCache()
    val set = cache.prepare(waveform)
    val fixed = set.fixed
    val sounds = ArrayList<Sound>(SOUNDS_PER_WAVEFORM)

    fun single(name: String, label: String, clip: Clip) {
        sounds += Sound(name, label, toPcm16(playOne(clip, masterGain)))
    }
    single("tick-row0", "Focus tick, row 0", fixed.tick(0, TickKind.NORMAL))
    single("tick-row4", "Focus tick, row 4", fixed.tick(4, TickKind.NORMAL))
    single("tick-held-row0", "Focus tick, held (key repeat), row 0", fixed.tick(0, TickKind.HELD))
    single("edge", "Edge: focus cannot move", fixed.edge)
    single("select", "Select", fixed.select)
    single("back", "Back", fixed.back)
    single("launch-fallback", "Launch, app without a motif (fallback)", fixed.launchFallback)
    single("sleep", "Sleep: screensaver starts", fixed.sleep)
    single("wake", "Wake: screensaver ends", fixed.wake)

    val preview = renderPreview(cache, set, masterGain, errors)
    sounds += Sound(PREVIEW_NAME, "Settings preview: tick, tick, select, Plex motif", toPcm16(preview))

    for (p in MOTIF_PACKAGES) {
        val clip = set.motifClip(p.packageName, p.category, 0)
        sounds += Sound(motifName(p), "Launch motif: ${p.packageName} (${p.category.name})", toPcm16(playOne(clip, masterGain)))
    }
    return sounds
}
