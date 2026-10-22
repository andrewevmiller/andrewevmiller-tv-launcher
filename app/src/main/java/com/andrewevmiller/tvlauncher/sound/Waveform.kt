package com.andrewevmiller.tvlauncher.sound

/**
 * The user-selectable oscillator (Settings → Sound → Waveform). Default is [SAW].
 * Pure Kotlin on purpose: the synth core and the JVM preview tool depend on it.
 */
enum class Waveform { SINE, TRIANGLE, SAW, SQUARE, SUPERSAW, WHITE_NOISE, PINK_NOISE }
