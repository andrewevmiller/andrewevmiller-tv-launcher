package com.andrewevmiller.tvlauncher.viewinglog

/**
 * Turns raw usage events into sessions. Pure: the Android glue only supplies events and the clock.
 *
 * Rules, in the order they apply:
 * - Events for ignored packages (the launcher, system UI, screensavers) are dropped first.
 * - While the screen is off (a SCREEN_OFF with no SCREEN_ON since) foregrounds are dropped.
 * - SCREEN_OFF closes the open session. A BACKGROUND for the app in front closes it too; a BACKGROUND for any
 *   other app, or with nothing open, is ignored. A FOREGROUND for the app already in front is ignored.
 * - A FOREGROUND for a second app closes the first at that moment.
 * - Still open at the end: it runs to `nowMillis`, or to the cap, whichever is first.
 * - Then, per app: sessions less than [ViewingLogRules.JOIN_GAP_MILLIS] apart are joined; each session is capped
 *   at [ViewingLogRules.MAX_SESSION_MILLIS]; sessions shorter than [ViewingLogRules.MIN_SESSION_MILLIS] are dropped.
 */
object SessionBuilder {

    /**
     * [closed] are finished sessions. [open] is the one still running at `nowMillis` (below the cap), or null.
     * The sync keeps [open] out of the store and resumes from its start next time, so nothing is counted twice.
     */
    data class Built(val closed: List<ViewingSession>, val open: ViewingSession?)

    /** Every session, the open one included, oldest first. */
    fun build(
        events: List<UsageEvent>,
        nowMillis: Long,
        ignored: (String) -> Boolean = ViewingLogRules::isIgnored,
    ): List<ViewingSession> {
        val built = buildOpen(events, nowMillis, ignored)
        return finish(built.closed + listOfNotNull(built.open))
    }

    /** As [build], but keeps the still-open session apart so the caller can resume from it. */
    fun buildOpen(
        events: List<UsageEvent>,
        nowMillis: Long,
        ignored: (String) -> Boolean = ViewingLogRules::isIgnored,
    ): Built {
        val raw = ArrayList<ViewingSession>()
        var inFront: ViewingSession? = null   // endMillis unused while open
        var screenOff = false

        // A stable sort keeps the input order of events with the same timestamp.
        for (event in events.sortedBy { it.atMillis }) {
            when (event.kind) {
                UsageKind.SCREEN_OFF -> {
                    inFront?.let { raw.add(it.copy(endMillis = event.atMillis)) }
                    inFront = null
                    screenOff = true
                }
                UsageKind.SCREEN_ON -> screenOff = false
                UsageKind.FOREGROUND -> {
                    if (ignored(event.packageName) || screenOff) continue
                    val current = inFront
                    if (current?.packageName == event.packageName) continue
                    current?.let { raw.add(it.copy(endMillis = event.atMillis)) }
                    inFront = ViewingSession(event.packageName, event.atMillis, event.atMillis)
                }
                UsageKind.BACKGROUND -> {
                    if (ignored(event.packageName)) continue
                    val current = inFront ?: continue
                    if (current.packageName != event.packageName) continue
                    raw.add(current.copy(endMillis = event.atMillis))
                    inFront = null
                }
            }
        }

        var open: ViewingSession? = null
        inFront?.let { session ->
            val capEnd = session.startMillis + ViewingLogRules.MAX_SESSION_MILLIS
            if (nowMillis >= capEnd) {
                // Nothing reported for longer than the cap: the session is finished at the cap.
                raw.add(session.copy(endMillis = capEnd))
            } else {
                open = session.copy(endMillis = maxOf(nowMillis, session.startMillis))
            }
        }
        return Built(closed = finish(raw), open = open)
    }

    /** Joins per app, caps, and drops the short ones. The result is oldest first. */
    private fun finish(raw: List<ViewingSession>): List<ViewingSession> =
        raw.groupBy { it.packageName }
            .values
            .flatMap { joinApp(it) }
            .map { s -> s.copy(endMillis = minOf(s.endMillis, s.startMillis + ViewingLogRules.MAX_SESSION_MILLIS)) }
            .filter { it.durationMillis >= ViewingLogRules.MIN_SESSION_MILLIS }
            .sortedBy { it.startMillis }

    /**
     * One app's sessions, oldest first. A gap under the join threshold merges the next one in, unless the merged
     * session would start-to-end run past the cap (then the cap is what ends it, and the next one stands alone).
     */
    private fun joinApp(sessions: List<ViewingSession>): List<ViewingSession> {
        val sorted = sessions.sortedBy { it.startMillis }
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<ViewingSession>()
        var cur = sorted[0]
        for (next in sorted.drop(1)) {
            val gap = next.startMillis - cur.endMillis
            val withinCap = next.startMillis < cur.startMillis + ViewingLogRules.MAX_SESSION_MILLIS
            if (gap < ViewingLogRules.JOIN_GAP_MILLIS && withinCap) {
                cur = cur.copy(endMillis = maxOf(cur.endMillis, next.endMillis))
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }
}
