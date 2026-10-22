package com.andrewevmiller.tvlauncher.sound.synth

/**
 * The fixed interface sounds as [VoiceSpec] lists, transcribed from BUILD_PLAN section 8.1 and the mockup's
 * `sfx` block. Parameters left out here are the [VoiceSpec] defaults (voices 2, detune 9, lp 7000 -> 2500,
 * Q 1.5, drive 6, fold 2.2, crush 0, click 0). The global x1.6 drive multiplier is applied inside the shaper.
 *
 * The per-app launch motifs and the settings `preview` are compositions built elsewhere.
 */
object FixedSounds {
    /** Focus tick; the pitch steps down 60 Hz per row. [held] is a key-repeat tick (shorter, quieter). */
    fun tick(row: Int, held: Boolean): List<VoiceSpec> = listOf(
        VoiceSpec(
            // The mockup has no floor; 110 Hz only guards absurd row numbers (row >= 22 would be <= 0 Hz).
            freq = (1320.0 - row * 60.0).coerceAtLeast(110.0),
            dur = if (held) 0.02 else 0.03,
            gain = if (held) 0.012 else 0.02,
            voices = 1,
            fold = 3.0,
            drive = 8.0,
            lpFrom = 9000.0,
            lpTo = 3000.0,
            click = if (held) 0.01 else 0.03,
        ),
    )

    /** Focus could not move: a folded, crushed low thud. */
    fun edge(): List<VoiceSpec> = listOf(
        VoiceSpec(
            freq = 70.0,
            glideTo = 50.0,
            dur = 0.13,
            gain = 0.06,
            detune = 20.0,
            fold = 3.5,
            drive = 10.0,
            crush = 12.0,
            lpFrom = 1800.0,
            lpTo = 300.0,
            click = 0.02,
        ),
    )

    fun select(): List<VoiceSpec> = listOf(
        VoiceSpec(freq = 440.0, dur = 0.08, gain = 0.028, fold = 2.5, drive = 7.0, click = 0.025),
        VoiceSpec(freq = 660.0, atSec = 0.05, dur = 0.10, gain = 0.026, fold = 2.5, drive = 7.0),
    )

    fun back(): List<VoiceSpec> = listOf(
        VoiceSpec(freq = 660.0, dur = 0.07, gain = 0.026, fold = 2.5, drive = 7.0, click = 0.025),
        VoiceSpec(freq = 440.0, atSec = 0.045, dur = 0.10, gain = 0.028, fold = 2.5, drive = 7.0),
    )

    /** Launch sound for an app without its own motif: four rising stabs that fold harder and open brighter, plus a sub. */
    fun launchFallback(): List<VoiceSpec> {
        val notes = doubleArrayOf(261.6, 329.6, 392.0, 523.3)
        val specs = ArrayList<VoiceSpec>(notes.size + 1)
        for (i in notes.indices) {
            specs += VoiceSpec(
                freq = notes[i],
                atSec = i * 0.045,
                dur = 0.28 - i * 0.03,
                gain = 0.024,
                fold = 1.6 + i * 0.6,
                drive = 7.0,
                lpFrom = 2500.0 + i * 1200.0,
                lpTo = 6000.0,
                q = 3.0,
                click = if (i == 0) 0.035 else 0.0,
            )
        }
        specs += VoiceSpec(
            freq = 65.4,
            dur = 0.3,
            gain = 0.035,
            voices = 1,
            fold = 1.2,
            drive = 4.0,
            lpFrom = 600.0,
            lpTo = 200.0,
        )
        return specs
    }

    /** Entering a screensaver: a slow detuned swell with the filter creeping open. */
    fun sleep(): List<VoiceSpec> = listOf(
        VoiceSpec(
            freq = 110.0,
            glideTo = 165.0,
            dur = 1.7,
            gain = 0.024,
            detune = 25.0,
            fold = 1.5,
            drive = 5.0,
            lpFrom = 600.0,
            lpTo = 4000.0,
            q = 4.0,
        ),
    )

    /**
     * The sleep timer ran out: [sleep]'s counterpart. One slow detuned note that sinks an octave (E3 to E2, where
     * [sleep] ended its rise) while the filter closes and the level fades out, so it settles instead of announcing.
     * The shared envelope is a straight dB-per-second decay over [VoiceSpec.dur], so the longer note already fades
     * slower than [sleep] (about 16 dB/s against 28). Gentle fold, no attack click. Quiet: the peak is about 0.6x
     * [select]'s; [VoiceSpec.gain] is the one knob if it is too loud or too faint.
     */
    fun windDown(): List<VoiceSpec> = listOf(
        VoiceSpec(
            freq = 165.0,
            glideTo = 82.4,
            dur = 2.6,
            gain = 0.011,
            detune = 25.0,
            fold = 1.2,
            drive = 4.0,
            lpFrom = 2600.0,
            lpTo = 350.0,
            q = 3.0,
        ),
    )

    /** Leaving a screensaver: a short bright sweep. */
    fun wake(): List<VoiceSpec> = listOf(
        VoiceSpec(
            freq = 220.0,
            glideTo = 330.0,
            dur = 0.26,
            gain = 0.028,
            fold = 3.0,
            drive = 8.0,
            lpFrom = 1500.0,
            lpTo = 7000.0,
            click = 0.02,
        ),
    )
}
