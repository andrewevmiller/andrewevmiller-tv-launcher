package com.andrewevmiller.tvlauncher.sleep

/**
 * The note passed between the alarm receiver's worker thread and SleepDimActivity, which share one process. Android 10+
 * drops a blocked background activity start without an exception, so the only proof that the dim screen came up is the
 * activity saying so: it calls [shown] from onCreate (and onNewIntent), and the worker polls [isShown].
 *
 * [abandon] is for a start that did not show in time: if the activity appears late anyway, [shown] returns false and the
 * activity closes at once, so it cannot show up on top of the screensaver strategy that was tried next.
 *
 * Times are monotonic milliseconds passed in by the caller. Pure and thread-safe.
 */
object SleepDimHandshake {
    private const val NONE = Long.MIN_VALUE

    private val lock = Any()
    private var requestedAt = NONE
    private var shownAt = NONE
    private var abandoned = false

    /** A start is about to be made: forget the last one. */
    fun request(nowMillis: Long) {
        synchronized(lock) {
            requestedAt = nowMillis
            shownAt = NONE
            abandoned = false
        }
    }

    /** The start was given up on. A late [shown] now returns false. */
    fun abandon() {
        synchronized(lock) { abandoned = true }
    }

    /**
     * The activity was created (or got a new intent) at [nowMillis]. Returns false when the start had been abandoned, in
     * which case the activity should finish itself. Without any [request] (started by a test) it just records the time.
     */
    fun shown(nowMillis: Long): Boolean =
        synchronized(lock) {
            if (abandoned) return false
            shownAt = nowMillis
            true
        }

    /** True once the activity of the latest [request] has called [shown]. */
    fun isShown(): Boolean =
        synchronized(lock) { shownAt != NONE && (requestedAt == NONE || shownAt >= requestedAt) }

    /** Back to nothing requested. For tests. */
    fun reset() {
        synchronized(lock) {
            requestedAt = NONE
            shownAt = NONE
            abandoned = false
        }
    }
}
