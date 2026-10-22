package com.andrewevmiller.tvlauncher.ambient

/*
 * Pure math for the Oxyfield "sleep" look: when the sleep timer ends the whole picture (dots and background)
 * fades to near-black over FADE_MILLIS and then holds one still frame. The clock (time and date) fades with it,
 * sooner, to a faint floor and stays. No Android types, so it is unit-tested with purecheck.
 * See docs/SLEEP_TIMER_PLAN.md (S4).
 */
object SleepDimMath {
    /** How long the fade takes, from full brightness to [FLOOR]. */
    const val FADE_MILLIS = 45_000L

    /**
     * Brightness the picture ends at, as a fraction of normal (1 = unchanged, 0 = pure black).
     *
     * LCD panels crush near-black: the last few code values below this are not distinguishable and a
     * backlit panel never shows a true 0 anyway. So this is tuned on the TV, by eye in a dark room, like the
     * dot-grid brightness. Raise it if the dots vanish completely; lower it if the room is still lit by the screen.
     */
    const val FLOOR = 0.05f

    /**
     * Brightness the clock (the time and the weekday/date line) ends at, as a fraction of its normal colour.
     * It stays there for as long as the sleep screen is up, so the time can be read from the bed.
     *
     * LCD panels crush near-black, like [FLOOR]: ink at 0.15 is about 35/255 (from #ECEBE5 at 236), which is
     * where a small glyph is still legible on this kind of panel in a dark room. Tune it on the TV by eye:
     * raise it if the digits vanish, lower it if the clock lights the room. It is deliberately above [FLOOR], so
     * the clock outlasts the dots as the one readable thing.
     */
    const val CLOCK_FLOOR = 0.15f

    /** The clock has reached [CLOCK_FLOOR] once this fraction of the fade has passed, so it settles before the dots do. */
    const val CLOCK_SETTLED_FRACTION = 0.6

    /**
     * Brightness multiplier for the dots and the background: 1.0 at the start of the fade, easing (smoothstep)
     * down to [FLOOR] at [fadeMillis] and staying there. Negative [elapsedMillis] counts as the start.
     * With [reducedMotion] there is no animation, so it is [FLOOR] immediately (the still dim frame).
     */
    fun level(elapsedMillis: Long, fadeMillis: Long = FADE_MILLIS, reducedMotion: Boolean): Float {
        if (reducedMotion) return FLOOR
        val t = progress(elapsedMillis, fadeMillis)
        if (t <= 0.0) return 1f
        if (t >= 1.0) return FLOOR
        return (FLOOR + (1.0 - FLOOR) * (1.0 - smoothstep(t))).toFloat()
    }

    /**
     * Brightness multiplier for the clock text: 1.0 at the start, easing (the same smoothstep) down to
     * [CLOCK_FLOOR] at [CLOCK_SETTLED_FRACTION] of the fade and staying there (so also once settled).
     * Reduced motion: [CLOCK_FLOOR] immediately.
     */
    fun clockLevel(elapsedMillis: Long, fadeMillis: Long = FADE_MILLIS, reducedMotion: Boolean): Float {
        if (reducedMotion) return CLOCK_FLOOR
        val t = progress(elapsedMillis, fadeMillis) / CLOCK_SETTLED_FRACTION
        if (t <= 0.0) return 1f
        if (t >= 1.0) return CLOCK_FLOOR
        return (CLOCK_FLOOR + (1.0 - CLOCK_FLOOR) * (1.0 - smoothstep(t))).toFloat()
    }

    /** True when neither [level] nor [clockLevel] can change any more: nothing left to animate. */
    fun isSettled(elapsedMillis: Long, fadeMillis: Long = FADE_MILLIS, reducedMotion: Boolean): Boolean =
        reducedMotion || fadeMillis <= 0L || elapsedMillis >= fadeMillis

    /**
     * Scales the red, green and blue of an ARGB colour by [level] (clamped to 0..1) towards black and keeps
     * the alpha. The background is dimmed with the same call, so the whole picture is the normal frame at
     * [level] brightness.
     */
    fun scaleColor(argb: Int, level: Float): Int {
        val l = if (level.isNaN() || level <= 0f) 0f else if (level >= 1f) 1f else level
        if (l >= 1f) return argb
        return (argb and (0xFF shl 24)) or
            (scaleChannel(argb, l, 16) shl 16) or
            (scaleChannel(argb, l, 8) shl 8) or
            scaleChannel(argb, l, 0)
    }

    private fun scaleChannel(argb: Int, level: Float, shift: Int): Int {
        val c = (argb ushr shift) and 0xFF
        val v = (c * level + 0.5f).toInt()
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    private fun progress(elapsedMillis: Long, fadeMillis: Long): Double {
        if (fadeMillis <= 0L) return 1.0
        val t = elapsedMillis.toDouble() / fadeMillis.toDouble()
        return if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
    }

    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
}
