package com.andrewevmiller.tvlauncher.tools.sound

import com.andrewevmiller.tvlauncher.sound.Waveform
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/*
 * tools/sound: renders every launcher sound to WAV for desktop listening, with the app's own sound code (see
 * build.gradle.kts for how the sources are shared). Nothing here re-implements the synth or the mixer.
 */

/** Where the WAVs go when --out is not given. Relative paths are relative to tools/sound (the run task's directory). */
private const val DEFAULT_OUT = "build/wavs"

/**
 * Gain on the whole mix, in dB, before the limiter. This is the value of the app's FieldSoundEngine.OUTPUT_GAIN_DB
 * (the plan's sounds are quiet on purpose, so everything is lifted by 10 dB), copied here rather than imported.
 */
private const val DEFAULT_GAIN_DB = 10.0

private val USAGE = """
    Usage: gradle -p tools/sound run --args="[options]"

      --out DIR          folder for the WAVs and index.html (default: build/wavs)
      --waveform NAME    one waveform: sine, triangle, saw, square, supersaw, white_noise, pink_noise,
                         or all (default)
      --gain-db DB       output gain before the limiter (default: 10.0, the app's OUTPUT_GAIN_DB)
      --list             print what would be rendered, then exit
      --help             this text
""".trimIndent()

internal class Options(
    val outDir: File,
    val waveforms: List<Waveform>,
    val gainDb: Double,
    val list: Boolean,
)

fun main(args: Array<String>) {
    val options = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(USAGE)
        exitProcess(2)
    }
    if (options == null) {
        println(USAGE)
        return
    }
    if (options.list) {
        printList()
        return
    }
    exitProcess(if (renderAll(options)) 0 else 1)
}

/** Returns null for --help. Throws IllegalArgumentException for a bad argument. */
internal fun parseArgs(args: Array<String>): Options? {
    var outDir = File(DEFAULT_OUT)
    var waveforms: List<Waveform> = Waveform.entries
    var gainDb = DEFAULT_GAIN_DB
    var list = false
    var i = 0

    fun argValue(flag: String): String {
        i++
        if (i >= args.size) throw IllegalArgumentException("$flag needs a value")
        return args[i]
    }

    while (i < args.size) {
        when (val arg = args[i]) {
            "--out" -> outDir = File(argValue(arg))
            "--waveform" -> waveforms = parseWaveforms(argValue(arg))
            "--gain-db" -> {
                val raw = argValue(arg)
                gainDb = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException("--gain-db needs a number, got '$raw'")
            }
            "--list" -> list = true
            "--help", "-h" -> return null
            else -> throw IllegalArgumentException("unknown argument '$arg'")
        }
        i++
    }
    return Options(outDir, waveforms, gainDb, list)
}

internal fun parseWaveforms(raw: String): List<Waveform> {
    val key = raw.trim().lowercase(Locale.ROOT).replace('-', '_')
    if (key == "all") return Waveform.entries
    val names = Waveform.entries.joinToString(", ") { folderName(it) }
    val w = Waveform.entries.firstOrNull { folderName(it) == key }
        ?: throw IllegalArgumentException("unknown waveform '$raw' (use all, $names)")
    return listOf(w)
}

private fun printList() {
    println("Waveforms (--waveform): all, " + Waveform.entries.joinToString(", ") { folderName(it) })
    println("Files per waveform (${SOUNDS_PER_WAVEFORM}):")
    for (name in soundNames()) println("  $name.wav")
    println("Motif packages (BUILD_PLAN 8.3):")
    for (p in MOTIF_PACKAGES) println("  ${p.packageName.padEnd(32)} ${p.category.name}")
    println("Output: <out>/<waveform>/<file>.wav for each waveform, plus <out>/index.html")
}

/** Renders, writes, reads back and summarizes. Returns true only if every check passed. */
private fun renderAll(opts: Options): Boolean {
    val golden = goldenProblems()
    if (golden.isNotEmpty()) {
        golden.forEach { System.err.println("golden check failed: $it") }
        return false
    }

    val outDir = opts.outDir.absoluteFile.normalize()
    outDir.mkdirs()
    val masterGain = dbToLinear(opts.gainDb)
    println("Field Launcher sound renders")
    println("output: $outDir")
    println(
        String.format(
            Locale.ROOT,
            "gain %+.1f dB (master x%.4f), ceiling %.1f dBFS, 48000 Hz, 16-bit mono",
            opts.gainDb,
            masterGain,
            CEILING_DBFS,
        ),
    )
    println("golden vectors (BUILD_PLAN 8.3): all match")

    val problems = ArrayList<String>()
    val sections = ArrayList<IndexSection>()
    var written = 0
    for (waveform in opts.waveforms) {
        val folder = File(outDir, folderName(waveform)).apply { mkdirs() }
        val errors = ArrayList<String>()
        val sounds = try {
            renderWaveform(waveform, masterGain, errors)
        } catch (t: Throwable) {
            errors += "rendering ${folderName(waveform)} failed: $t"
            emptyList<Sound>()
        }
        errors.forEach { problems += "${folderName(waveform)}: $it" }

        println()
        println("== ${waveform.name}  (${folderName(waveform)}/)")
        println(String.format(Locale.ROOT, "  %-40s %10s %11s %10s", "file", "ms", "peak dBFS", "RMS dBFS"))
        val entries = ArrayList<IndexEntry>()
        for (sound in sounds) {
            val rel = "${folderName(waveform)}/${sound.name}.wav"
            val file = File(folder, "${sound.name}.wav")
            writeWav(file, sound.pcm.samples)
            written++
            val fileProblems = verifyWav(file, sound.pcm.samples, sound.durationMs)
            fileProblems.forEach { problems += "$rel: $it" }

            if (sound.pcm.scaledByDb < 0.0) {
                System.err.println(
                    String.format(
                        Locale.ROOT,
                        "WARNING %s peaks at %+.2f dBFS, above the %.1f dBFS ceiling: scaled by %.2f dB",
                        rel,
                        sound.pcm.floatPeakDb,
                        CEILING_DBFS,
                        sound.pcm.scaledByDb,
                    ),
                )
            }
            val scaled = if (sound.pcm.scaledByDb < 0.0) String.format(Locale.ROOT, "  (scaled %.2f dB)", sound.pcm.scaledByDb) else ""
            println(
                String.format(
                    Locale.ROOT,
                    "  %-40s %10.1f %11s %10s%s",
                    "${sound.name}.wav",
                    sound.durationMs,
                    fmtDb(sound.peakDb),
                    fmtDb(sound.rmsDb),
                    scaled,
                ),
            )
            entries += IndexEntry(rel, sound.label, sound.durationMs, sound.peakDb)
        }
        sections += IndexSection(waveform, entries)
    }

    File(outDir, "index.html").writeText(indexHtml(sections, opts.gainDb), Charsets.UTF_8)

    val expected = opts.waveforms.size * SOUNDS_PER_WAVEFORM
    println()
    println("wrote $written WAV files (expected $expected) and index.html to $outDir")
    println("read back and checked ${written} files with javax.sound.sampled: ${problems.size} problem(s)")
    problems.forEach { System.err.println("CHECK FAILED $it") }
    return problems.isEmpty() && written == expected
}

private fun fmtDb(db: Double): String =
    if (db.isInfinite()) "-inf" else String.format(Locale.ROOT, "%.1f", db)
