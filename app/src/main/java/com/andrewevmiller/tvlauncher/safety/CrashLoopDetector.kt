package com.andrewevmiller.tvlauncher.safety

/** Value of [CrashState.processStartMillis] before any process start has been recorded. */
const val UNKNOWN_PROCESS_START = -1L

/**
 * The small amount of crash bookkeeping that has to survive a process death.
 *
 * @property processStartMillis wall-clock time the *current* process started, or [UNKNOWN_PROCESS_START].
 * @property earlyCrashMillis wall-clock times of recent crashes that happened early in a process's life.
 */
data class CrashState(
    val processStartMillis: Long = UNKNOWN_PROCESS_START,
    val earlyCrashMillis: List<Long> = emptyList(),
)

/**
 * Where [CrashState] lives between processes. The Android implementation is SharedPreferences with
 * `commit()` (see CrashGuard); tests use an in-memory one. Implementations must be synchronous: the
 * detector is called from inside an uncaught-exception handler, right before the process dies.
 */
interface CrashStore {
    fun load(): CrashState
    fun save(state: CrashState)
}

/**
 * Encodes [CrashState] as one short string, so the Android store is a single SharedPreferences key.
 * Format: `v1;<processStartMillis>;<t1>,<t2>,...`. Decoding never throws: anything unreadable becomes
 * the empty default state (a lost record only delays the fallback, it can't crash the crash handler).
 */
object CrashStateCodec {
    private const val PREFIX = "v1"

    fun encode(state: CrashState): String =
        "$PREFIX;${state.processStartMillis};${state.earlyCrashMillis.joinToString(",")}"

    fun decode(text: String?): CrashState {
        if (text.isNullOrBlank()) return CrashState()
        val parts = text.split(';')
        if (parts.size != 3 || parts[0] != PREFIX) return CrashState()
        val start = parts[1].toLongOrNull() ?: return CrashState()
        val crashes: List<Long> = if (parts[2].isEmpty()) {
            emptyList()
        } else {
            parts[2].split(',').map { it.toLongOrNull() ?: return CrashState() }
        }
        return CrashState(start, crashes)
    }
}

/**
 * Decides when the launcher is in a crash loop: **two early crashes within one window**.
 *
 * - A crash is *early* when it happens strictly less than [windowMillis] after its process started.
 *   A crash at or after that much uptime is late: it is not recorded and does not extend the window.
 * - The detector trips when [tripCount] early crashes (the one just recorded included) lie within
 *   [windowMillis] of each other, measured back from the latest. Exactly [windowMillis] apart still counts.
 * - Records older than the window are dropped. Records stamped in the future (the wall clock jumped
 *   backwards) are dropped too, and so is a crash whose process start is unknown or in the future:
 *   when the clock can't be trusted we do not hand Home away.
 *
 * All times are wall-clock milliseconds supplied by the caller; this class never reads a clock.
 */
class CrashLoopDetector(
    private val store: CrashStore,
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    val tripCount: Int = DEFAULT_TRIP_COUNT,
) {
    /** Call once, first thing in the process. Keeps still-relevant early-crash records. */
    fun recordProcessStart(nowMillis: Long) {
        val state = store.load()
        store.save(CrashState(nowMillis, retained(state.earlyCrashMillis, nowMillis)))
    }

    /**
     * Call from the uncaught-exception handler. Persists the updated state and returns true when the
     * crash loop threshold has been reached, i.e. the caller should hand Home back.
     */
    fun onCrash(nowMillis: Long): Boolean {
        val state = store.load()
        val kept = retained(state.earlyCrashMillis, nowMillis)
        val uptime = nowMillis - state.processStartMillis
        val early = state.processStartMillis != UNKNOWN_PROCESS_START && uptime >= 0 && uptime < windowMillis
        val updated = if (early) (kept + nowMillis).takeLast(MAX_RECORDS) else kept
        store.save(state.copy(earlyCrashMillis = updated))
        return early && updated.size >= tripCount
    }

    /** Forget recorded crashes (after a successful hand-off) but keep the current process start. */
    fun reset() {
        val state = store.load()
        store.save(state.copy(earlyCrashMillis = emptyList()))
    }

    private fun retained(records: List<Long>, nowMillis: Long): List<Long> =
        records.filter { it <= nowMillis && nowMillis - it <= windowMillis }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_TRIP_COUNT = 2
        private const val MAX_RECORDS = 10
    }
}
