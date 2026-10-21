package com.andrewevmiller.tvlauncher.sound

/**
 * The fixed interface sounds, ported from the mockup: focus tick (and its quieter "held" form), the
 * edge thud, select and back. Parameters are the mockup's; if any change, bump [SoundFiles.RENDER_VERSION].
 */
internal object FixedSounds {
    class Spec(val name: String, val voices: List<Voice>, val seconds: Double)

    private fun tick(f: Double, dur: Double, gain: Double, click: Double) =
        Voice(f, 0.0, dur, gain, voices = 1, fold = 3.0, drive = 8.0, lpFrom = 9000.0, lpTo = 3000.0, click = click)

    val all: List<Spec> = listOf(
        Spec("tick", listOf(tick(1320.0, 0.03, 0.02, 0.03)), 0.06),
        Spec("tick_held", listOf(tick(1320.0, 0.02, 0.012, 0.01)), 0.05),
        Spec(
            "edge",
            listOf(Voice(70.0, 0.0, 0.13, 0.06, detune = 20.0, glide = 50.0, fold = 3.5, drive = 10.0, crush = 12, lpFrom = 1800.0, lpTo = 300.0, click = 0.02)),
            0.2,
        ),
        Spec(
            "select",
            listOf(
                Voice(440.0, 0.0, 0.08, 0.028, fold = 2.5, drive = 7.0, click = 0.025),
                Voice(660.0, 0.05, 0.10, 0.026, fold = 2.5, drive = 7.0),
            ),
            0.2,
        ),
        Spec(
            "back",
            listOf(
                Voice(660.0, 0.0, 0.07, 0.026, fold = 2.5, drive = 7.0, click = 0.025),
                Voice(440.0, 0.045, 0.10, 0.028, fold = 2.5, drive = 7.0),
            ),
            0.2,
        ),
    )
}
