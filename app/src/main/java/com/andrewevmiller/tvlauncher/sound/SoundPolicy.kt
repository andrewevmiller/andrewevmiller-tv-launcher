package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory

/**
 * Decides whether a focus move gets a tick, and how loud, from the time since the last one that played.
 * Free of Android types so it can be tested on the JVM; callers pass a monotonic clock in milliseconds.
 */
internal class TickThrottle(private val floorMs: Long = FLOOR_MS, private val heldMs: Long = HELD_MS) {
    enum class Tick { NONE, NORMAL, HELD }

    private var lastPlayed: Long? = null

    /**
     * NONE when a tick played less than [floorMs] ago (this also absorbs a focus callback that fires twice
     * for one move). HELD, the thinner and quieter tick, when the last one was under [heldMs] ago, as when
     * a D-pad key is held down. NORMAL otherwise, including the first tick and after the clock moved back.
     * A skipped tick does not count as played.
     */
    @Synchronized
    fun next(nowMs: Long): Tick {
        val last = lastPlayed
        val gap = if (last == null) Long.MAX_VALUE else nowMs - last
        if (gap in 0 until floorMs) return Tick.NONE
        lastPlayed = nowMs
        return if (gap in 0 until heldMs) Tick.HELD else Tick.NORMAL
    }

    companion object {
        const val FLOOR_MS = 35L
        const val HELD_MS = 90L
    }
}

/**
 * Names of the pre-rendered fixed sounds in the cache directory. The waveform and a render version are part
 * of the name, so changing either one makes the old files stale instead of silently reusing them.
 */
internal object SoundFiles {
    /** Bump whenever the DSP, a fixed sound's parameters, or the output level changes. */
    const val RENDER_VERSION = 1

    fun name(sound: String, waveform: Waveform): String = "fs_${sound}_${waveform.name.lowercase()}_v$RENDER_VERSION.wav"

    /** Files this feature owns, including the unversioned names and half-written temp files of earlier builds. */
    fun owns(fileName: String): Boolean = fileName.startsWith("fs_") && (fileName.endsWith(".wav") || fileName.endsWith(".wav.tmp"))

    fun stale(existing: Collection<String>, keep: Set<String>): List<String> = existing.filter { owns(it) && it !in keep }
}

/** What identifies one rendered motif. The per-launch humanizing is deliberately not part of it. */
internal data class MotifKey(val packageName: String, val category: AppCategory, val waveform: Waveform, val version: Int = SoundFiles.RENDER_VERSION)

/** A small least-recently-used cache of rendered motifs (about 58 KB each at 0.6 s, mono, 48 kHz). */
internal class MotifCache(private val maxEntries: Int = 64) {
    private val map = object : LinkedHashMap<MotifKey, ShortArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotifKey, ShortArray>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: MotifKey): ShortArray? = map[key]
    @Synchronized fun put(key: MotifKey, pcm: ShortArray) { map[key] = pcm }
    @Synchronized fun contains(key: MotifKey): Boolean = map.containsKey(key)
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}
