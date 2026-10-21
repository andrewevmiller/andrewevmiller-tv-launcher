package com.andrewevmiller.tvlauncher.tools.sound

import com.andrewevmiller.tvlauncher.sound.synth.SAMPLE_RATE
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/*
 * WAV writing and read-back. The writer uses javax.sound.sampled; the verifier opens each file again from disk with
 * the same library and checks what a player would see, plus the samples themselves.
 */

/** A file whose loudest sample is below this is reported as silent. */
internal const val SILENCE_FLOOR_DBFS = -60.0

private val WAV_FORMAT = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    SAMPLE_RATE.toFloat(),
    16,
    1,
    2,
    SAMPLE_RATE.toFloat(),
    false,
)

/** Writes [pcm] as 16-bit little-endian mono PCM WAV at 48 kHz. */
internal fun writeWav(file: File, pcm: ShortArray) {
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
        val v = pcm[i].toInt()
        bytes[2 * i] = v.toByte()
        bytes[2 * i + 1] = (v shr 8).toByte()
    }
    AudioInputStream(ByteArrayInputStream(bytes), WAV_FORMAT, pcm.size.toLong()).use { stream ->
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
    }
}

/**
 * Reads [file] back and checks it: RIFF/WAVE header; PCM signed, 48000 Hz, 16-bit, mono, little-endian; frame count
 * equal to [expected].size and duration equal to [printedDurationMs] (within 0.05 ms, the printed precision); every
 * sample equal to what was rendered; not silent; peak at or below -1 dBFS. Returns one line per problem.
 */
internal fun verifyWav(file: File, expected: ShortArray, printedDurationMs: Double): List<String> {
    val problems = ArrayList<String>()
    val head = file.inputStream().use { it.readNBytes(12) }
    if (head.size < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
        problems += "no RIFF/WAVE header"
    }
    val stream = try {
        AudioSystem.getAudioInputStream(file)
    } catch (e: Exception) {
        return problems + "not readable with javax.sound.sampled: $e"
    }
    stream.use { ais ->
        val f = ais.format
        if (f.encoding != AudioFormat.Encoding.PCM_SIGNED) problems += "encoding ${f.encoding}, want PCM_SIGNED"
        if (f.sampleRate != SAMPLE_RATE.toFloat()) problems += "sample rate ${f.sampleRate}, want $SAMPLE_RATE"
        if (f.sampleSizeInBits != 16) problems += "${f.sampleSizeInBits}-bit, want 16"
        if (f.channels != 1) problems += "${f.channels} channels, want mono"
        if (f.isBigEndian) problems += "big-endian, want little-endian"

        val frames = ais.frameLength
        if (frames != expected.size.toLong()) problems += "$frames frames, rendered ${expected.size}"
        val readMs = frames * 1000.0 / SAMPLE_RATE
        if (abs(readMs - printedDurationMs) > 0.05) {
            problems += String.format(Locale.ROOT, "duration %.2f ms, printed %.1f ms", readMs, printedDurationMs)
        }

        val bytes = ais.readAllBytes()
        if (bytes.size != expected.size * 2) {
            problems += "read ${bytes.size} bytes, want ${expected.size * 2}"
            return problems
        }
        var peak = 0
        var mismatches = 0
        var firstMismatch = -1
        for (i in expected.indices) {
            val v = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            if (abs(v) > peak) peak = abs(v)
            if (v != expected[i].toInt()) {
                if (mismatches == 0) firstMismatch = i
                mismatches++
            }
        }
        if (mismatches > 0) problems += "$mismatches samples differ from the rendered PCM (first at frame $firstMismatch)"
        val readPeakDb = dbOf(peak / FULL_SCALE)
        if (peak == 0 || readPeakDb < SILENCE_FLOOR_DBFS) {
            problems += String.format(Locale.ROOT, "silent or near-silent (peak %.1f dBFS)", readPeakDb)
        }
        if (readPeakDb > CEILING_DBFS + 1e-9) {
            problems += String.format(Locale.ROOT, "peak %.3f dBFS is above the %.1f dBFS ceiling", readPeakDb, CEILING_DBFS)
        }
    }
    return problems
}
