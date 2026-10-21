package com.andrewevmiller.tvlauncher.ambient

/**
 * A process-wide, one-shot "start the next dream dimmed" note. The sleep timer calls [request] right before it
 * starts [OxyfieldDreamService]; the dream calls [consume] when it attaches and, if that returns true, shows
 * the sleep look instead of the normal screensaver.
 *
 * It expires after [EXPIRY_MILLIS], so a request whose dream never started (the system refused it, or the TV
 * went to sleep first) can never dim a later, ordinary screensaver. Times are monotonic milliseconds passed
 * in by the caller (default: [System.nanoTime]), which keeps this pure and testable.
 */
object SleepDimRequest {
    /** How long a request stays valid. */
    const val EXPIRY_MILLIS = 30_000L

    private const val NONE = Long.MIN_VALUE

    private var requestedAtMillis = NONE
    private val lock = Any()

    private fun monotonicNow(): Long = System.nanoTime() / 1_000_000L

    /** Marks the next dream as a sleep dream. A newer request restarts the 30 s window. */
    fun request(nowMillis: Long = monotonicNow()) {
        synchronized(lock) { requestedAtMillis = nowMillis }
    }

    /** Forgets any request. */
    fun clear() {
        synchronized(lock) { requestedAtMillis = NONE }
    }

    /** True if a request is waiting and not expired. Does not consume it. */
    fun isPending(nowMillis: Long = monotonicNow()): Boolean =
        synchronized(lock) { isLive(requestedAtMillis, nowMillis) }

    /**
     * Take the request: true once for a live request, false afterwards. An expired request is dropped and
     * counts as not pending. Safe to call from any thread.
     */
    fun consume(nowMillis: Long = monotonicNow()): Boolean =
        synchronized(lock) {
            val live = isLive(requestedAtMillis, nowMillis)
            requestedAtMillis = NONE
            live
        }

    /**
     * Convenience flag. Reading is [isPending] now; setting true is [request] now, false is [clear].
     * Prefer [request] and [consume] with an explicit time in tests.
     */
    var pending: Boolean
        get() = isPending()
        set(value) {
            if (value) request() else clear()
        }

    // A time before the request means the clock is not the one it was stamped with: do not trust it.
    private fun isLive(requestedAt: Long, now: Long): Boolean =
        requestedAt != NONE && now - requestedAt in 0L..EXPIRY_MILLIS
}
