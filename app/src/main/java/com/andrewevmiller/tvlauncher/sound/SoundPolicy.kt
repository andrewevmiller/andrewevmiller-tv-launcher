package com.andrewevmiller.tvlauncher.sound

/** How a focus tick sounds: [NORMAL] for a single move, [HELD] while the key is repeating. */
enum class TickKind { NORMAL, HELD }

/**
 * Key-repeat thinning for focus ticks. Mirrors the mockup's `api.tick`: `held = now - last < 90; last = now`,
 * with two deliberate differences from a literal port: the first tick is always NORMAL, and a clock that
 * goes backwards is NORMAL. Feed it the clock on every tick. One instance per sound engine.
 */
class TickThrottle(private val heldWindowMs: Long = 90) {
    private var lastMs: Long? = null

    fun classify(nowMs: Long): TickKind {
        val previous = lastMs
        lastMs = nowMs
        if (previous == null) return TickKind.NORMAL
        val sincePrevious = nowMs - previous
        if (sincePrevious < 0) return TickKind.NORMAL   // clock went backwards: don't guess
        return if (sincePrevious < heldWindowMs) TickKind.HELD else TickKind.NORMAL
    }
}

/** Interface sounds play only when the launcher's own toggle and the system sound-effects setting are both on. */
fun soundsAllowed(launcherToggle: Boolean, systemSoundEffects: Boolean): Boolean =
    launcherToggle && systemSoundEffects
