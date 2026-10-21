package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.sound.synth.VoiceSpec
import com.andrewevmiller.tvlauncher.sound.synth.XorShift64

/*
 * A launch motif as synth notes: BUILD_PLAN section 8.2 "Playback", transcribed from the mockup's `api.motif`.
 * Pure Kotlin. The per-launch humanize (+-5 cents, +-10% level) is NOT baked into the notes: the clip is
 * rendered once, and the mixer applies it at playback as a playback-rate and gain change.
 */

private const val NOTE_GAIN = 0.026
private const val NOTE_DUR = 0.11
private const val LAST_NOTE_DUR = 0.10
private const val LAST_NOTE_DUR_HELD = 0.24
private const val JITTER_SECONDS = 0.003
private const val JITTER_SALT = 0x6A697474L // "jitt"

private const val SUB_DUR = 0.26
private const val SUB_GAIN = 0.03
private const val SUB_DRIVE = 3.0
private const val SUB_LP_FROM = 400.0
private const val SUB_LP_TO = 150.0

/**
 * The notes of [motif] in [timbre], ready for `VoiceRenderer.render`.
 *
 * - Note i starts at `i * gapMs` plus 0-3 ms of start jitter. The jitter comes from [seed] (a fixed stream per
 *   seed), so the same motif always renders to the same clip.
 * - Duration 0.11 s; the last note 0.24 s if [Motif.hold] else 0.10 s. Gain 0.026. The attack click is on
 *   note 0 only, with the timbre's click level.
 * - Voices, detune, drive, fold, crush, low-pass sweep and Q come from [timbre].
 * - A sub rides under it: the first note's frequency / 4, one voice, 0.26 s, gain 0.03, fold 0, drive 3,
 *   low-pass 400 -> 150.
 */
fun motifVoiceSpecs(motif: Motif, timbre: Timbre, seed: Long): List<VoiceSpec> {
    val notes = motif.frequencies.size
    val rng = XorShift64(seed xor JITTER_SALT)
    val specs = ArrayList<VoiceSpec>(notes + 1)
    for (i in 0 until notes) {
        val last = i == notes - 1
        specs += VoiceSpec(
            freq = motif.frequencies[i],
            atSec = i * motif.gapMs / 1000.0 + rng.nextDouble() * JITTER_SECONDS,
            dur = if (last) (if (motif.hold) LAST_NOTE_DUR_HELD else LAST_NOTE_DUR) else NOTE_DUR,
            gain = NOTE_GAIN,
            voices = timbre.voices,
            detune = timbre.detune,
            lpFrom = timbre.lpFrom,
            lpTo = timbre.lpTo,
            q = timbre.q,
            drive = timbre.drive,
            fold = timbre.fold,
            crush = timbre.crush,
            click = if (i == 0) timbre.click else 0.0,
        )
    }
    specs += VoiceSpec(
        freq = motif.frequencies[0] / 4.0,
        dur = SUB_DUR,
        gain = SUB_GAIN,
        voices = 1,
        detune = 0.0,
        fold = 0.0,
        drive = SUB_DRIVE,
        lpFrom = SUB_LP_FROM,
        lpTo = SUB_LP_TO,
    )
    return specs
}
