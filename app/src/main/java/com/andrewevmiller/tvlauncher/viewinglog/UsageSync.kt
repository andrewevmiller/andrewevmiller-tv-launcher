package com.andrewevmiller.tvlauncher.viewinglog

/** Where raw usage events come from. The TV implementation reads UsageStatsManager; tests pass a list. */
fun interface UsageEventFeed {
    /** Events with `fromMillis <= at < toMillis`, in any order. */
    fun events(fromMillis: Long, toMillis: Long): List<UsageEvent>
}

/** Where the log lives. The TV implementation is a file plus one number in SharedPreferences. */
interface ViewingLogStorage {
    fun read(): List<ViewingSession>
    fun write(sessions: List<ViewingSession>)

    /** Where the next sync starts reading. Null before the first sync. */
    fun cursor(): Long?
    fun setCursor(millis: Long)
}

/**
 * Copies new usage into the log. Run when Home resumes, and only when the log is on and usage access is granted
 * (the caller checks both; nothing here touches settings).
 *
 * The system keeps usage events for about a week, so each run reads from the cursor (the last sync), never further
 * back than [ViewingLogRules.FIRST_SYNC_LOOKBACK_MILLIS]. A session still running at the end of the read is not
 * stored: the cursor moves to its start, so the next run reads it again and stores it once it has ended. Sessions
 * already stored are matched by app and start, so a repeated read adds nothing twice.
 */
class UsageSync(
    private val feed: UsageEventFeed,
    private val storage: ViewingLogStorage,
    private val ignored: (String) -> Boolean = ViewingLogRules::isIgnored,
) {
    /** Returns how many finished sessions were added. */
    fun run(nowMillis: Long): Int {
        val lookback = nowMillis - ViewingLogRules.FIRST_SYNC_LOOKBACK_MILLIS
        val from = maxOf(storage.cursor() ?: lookback, lookback)
        if (from >= nowMillis) return 0
        val built = SessionBuilder.buildOpen(feed.events(from, nowMillis), nowMillis, ignored)
        val before = storage.read()
        val after = ViewingLogCodec.merge(before, built.closed, nowMillis)
        storage.write(after)
        storage.setCursor(built.open?.startMillis ?: nowMillis)
        val known = before.toSet()
        return after.count { it !in known }
    }
}
