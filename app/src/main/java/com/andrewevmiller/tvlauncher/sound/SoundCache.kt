package com.andrewevmiller.tvlauncher.sound

import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.sound.synth.FixedSounds
import com.andrewevmiller.tvlauncher.sound.synth.VoiceRenderer
import com.andrewevmiller.tvlauncher.sound.synth.VoiceSpec
import java.util.concurrent.ConcurrentHashMap

/*
 * Pre-rendered sounds for one waveform: the fixed interface sounds, plus one clip per app motif, rendered on
 * demand and remembered. Pure Kotlin (JVM-testable); the Android engine drives it from a background thread.
 */

/** Turns synth notes into samples. [VoiceRenderer] is the real one; tests substitute a fast or failing fake. */
fun interface SpecRenderer {
    fun render(specs: List<VoiceSpec>): FloatArray
}

/** The two facts about an installed app that its motif depends on. */
data class MotifTarget(val packageName: String, val category: AppCategory)

/** Every fixed interface sound for one waveform. Ticks exist for rows 0..[TICK_ROWS]-1, normal and held. */
class FixedClips(
    val waveform: Waveform,
    private val tickNormal: List<Clip>,
    private val tickHeld: List<Clip>,
    val edge: Clip,
    val select: Clip,
    val back: Clip,
    val launchFallback: Clip,
    val sleep: Clip,
    val wake: Clip,
) {
    init {
        require(tickNormal.size == TICK_ROWS && tickHeld.size == TICK_ROWS) { "need $TICK_ROWS tick clips per kind" }
    }

    /** The tick for [row] (clamped to the rendered rows) in the given key-repeat state. */
    fun tick(row: Int, kind: TickKind): Clip {
        val r = clampTickRow(row)
        return if (kind == TickKind.HELD) tickHeld[r] else tickNormal[r]
    }

    companion object {
        const val TICK_ROWS = TICK_ROW_MAX + 1
    }
}

private data class MotifKey(val packageName: String, val category: AppCategory, val rerollCount: Int)

/**
 * One waveform's sounds: the fixed clips (ready at construction) and the motif clips (lazy, memoised, safe to
 * call from several threads; two threads racing on the same motif may both render it, and both get the same clip).
 */
class SoundSet internal constructor(
    val waveform: Waveform,
    val fixed: FixedClips,
    private val renderer: SpecRenderer,
) {
    private val motifs = ConcurrentHashMap<MotifKey, Clip>()

    /** Number of motif clips rendered so far. */
    val motifCount: Int get() = motifs.size

    /** The motif clip if it has been rendered, else null. Never renders. */
    fun peekMotif(packageName: String, category: AppCategory, rerollCount: Int): Clip? =
        motifs[MotifKey(packageName, category, rerollCount)]

    /** The motif clip for an app, rendering it the first time. */
    fun motifClip(packageName: String, category: AppCategory, rerollCount: Int): Clip {
        val key = MotifKey(packageName, category, rerollCount)
        motifs[key]?.let { return it }
        val motif = motifFor(packageName, rerollCount)
        val specs = motifVoiceSpecs(motif, timbreFor(category), motif.seed.toLong())
        val rendered = Clip(renderer.render(specs))
        return motifs.putIfAbsent(key, rendered) ?: rendered
    }

    /** Forgets this package's clips that were rendered for a different re-roll count (an old tune nobody will hear). */
    fun evictOtherRerolls(packageName: String, keepRerollCount: Int) {
        val keys = motifs.keys.iterator()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.packageName == packageName && k.rerollCount != keepRerollCount) keys.remove()
        }
    }
}

/**
 * Renders and holds the sounds for the current waveform.
 *
 * [active] is always a complete, consistent [SoundSet]. Changing the waveform renders the new fixed set first and
 * swaps it in only when it is ready, so a sound requested in the meantime plays from the old set and there is
 * never silence. Rendering is blocking CPU work: call it from a background thread.
 */
class SoundCache(
    private val rendererFactory: (Waveform) -> SpecRenderer = { waveform ->
        val synth = VoiceRenderer(waveform, seed = DEFAULT_SEED)
        SpecRenderer { specs -> synth.render(specs) }
    },
) {
    @Volatile
    private var current: SoundSet? = null
    private val buildLock = Any()

    /** The set playback should use right now, or null before the first render has finished. */
    fun active(): SoundSet? = current

    /**
     * Makes [waveform] the active set and returns it, rendering its fixed sounds if needed. Returns at once if it
     * already is. The previous set stays active until this one is complete.
     */
    fun prepare(waveform: Waveform): SoundSet {
        current?.let { if (it.waveform == waveform) return it }
        synchronized(buildLock) {
            current?.let { if (it.waveform == waveform) return it }
            val renderer = rendererFactory(waveform)
            val set = SoundSet(waveform, renderFixedClips(waveform, renderer), renderer)
            current = set
            return set
        }
    }

    /** The fixed sounds for [waveform] (see [prepare]). */
    fun renderFixed(waveform: Waveform): FixedClips = prepare(waveform).fixed

    /** One app's motif clip for [waveform]; lazy and memoised. */
    fun motifClip(waveform: Waveform, packageName: String, category: AppCategory, rerollCount: Int): Clip =
        prepare(waveform).motifClip(packageName, category, rerollCount)

    /**
     * Background pre-render: the fixed set for [waveform], then the motif of every app that is not muted, at its
     * current re-roll count (an app with several launcher activities counts once). [progress] gets
     * `(done, total)` after the fixed set (done = 0) and after each motif; it may throw to abort, which is how
     * a cancelled coroutine stops the work between clips.
     */
    fun renderAll(
        waveform: Waveform,
        apps: List<MotifTarget>,
        overrides: Map<String, MotifOverride>,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val set = prepare(waveform)
        val targets = apps.distinctBy { it.packageName }.filter { overrides[it.packageName]?.muted != true }
        progress(0, targets.size)
        for ((i, target) in targets.withIndex()) {
            val rerolls = overrides[target.packageName]?.rerollCount ?: 0
            set.motifClip(target.packageName, target.category, rerolls)
            set.evictOtherRerolls(target.packageName, rerolls)
            progress(i + 1, targets.size)
        }
    }

    companion object {
        /** Fixed so the same waveform always renders to the same sounds. */
        const val DEFAULT_SEED = 1L

        private fun renderFixedClips(waveform: Waveform, renderer: SpecRenderer): FixedClips {
            fun clip(specs: List<VoiceSpec>) = Clip(renderer.render(specs))
            return FixedClips(
                waveform = waveform,
                tickNormal = List(FixedClips.TICK_ROWS) { clip(FixedSounds.tick(it, held = false)) },
                tickHeld = List(FixedClips.TICK_ROWS) { clip(FixedSounds.tick(it, held = true)) },
                edge = clip(FixedSounds.edge()),
                select = clip(FixedSounds.select()),
                back = clip(FixedSounds.back()),
                launchFallback = clip(FixedSounds.launchFallback()),
                sleep = clip(FixedSounds.sleep()),
                wake = clip(FixedSounds.wake()),
            )
        }
    }
}
