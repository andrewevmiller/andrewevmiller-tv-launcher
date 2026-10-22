package com.andrewevmiller.tvlauncher.viewinglog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSyncTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 24L * 60 * minute
    private val yt = "com.google.android.youtube.tv"

    /** A feed over a fixed event list that remembers the windows it was asked for. */
    private class FakeFeed(private val events: List<UsageEvent>) : UsageEventFeed {
        val windows = mutableListOf<Pair<Long, Long>>()
        override fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> {
            windows.add(fromMillis to toMillis)
            return events.filter { it.atMillis >= fromMillis && it.atMillis < toMillis }
        }
    }

    private class MemoryStorage(initial: List<ViewingSession> = emptyList(), var cursorValue: Long? = null) : ViewingLogStorage {
        var stored = initial
        var writes = 0
        override fun read(): List<ViewingSession> = stored
        override fun write(sessions: List<ViewingSession>) {
            writes++
            stored = sessions
        }
        override fun cursor(): Long? = cursorValue
        override fun setCursor(millis: Long) {
            cursorValue = millis
        }
    }

    private fun fg(at: Long, pkg: String = yt) = UsageEvent(at, pkg, UsageKind.FOREGROUND)
    private fun bg(at: Long, pkg: String = yt) = UsageEvent(at, pkg, UsageKind.BACKGROUND)

    @Test
    fun firstRunReadsSevenDaysAndStoresFinishedSessions() {
        val feed = FakeFeed(listOf(fg(now - 2 * day), bg(now - 2 * day + 30 * minute)))
        val storage = MemoryStorage()
        val added = UsageSync(feed, storage).run(now)
        assertEquals(1, added)
        assertEquals(listOf((now - 7 * day) to now), feed.windows)
        assertEquals(listOf(ViewingSession(yt, now - 2 * day, now - 2 * day + 30 * minute)), storage.stored)
        assertEquals(now, storage.cursorValue)
    }

    @Test
    fun aSessionStillRunningIsNotStoredAndTheCursorMovesToItsStart() {
        val start = now - 10 * minute
        val feed = FakeFeed(listOf(fg(start)))
        val storage = MemoryStorage()
        assertEquals(0, UsageSync(feed, storage).run(now))
        assertEquals(emptyList<ViewingSession>(), storage.stored)
        assertEquals(start, storage.cursorValue)
    }

    @Test
    fun theNextRunPicksUpTheSessionFromItsStartAndStoresItOnce() {
        val start = now - 10 * minute
        val feed = FakeFeed(listOf(fg(start), bg(now + 5 * minute)))
        val storage = MemoryStorage(cursorValue = start)
        // The first run saw only the start; the second sees the end too.
        val later = now + 6 * minute
        assertEquals(1, UsageSync(feed, storage).run(later))
        assertEquals(listOf(ViewingSession(yt, start, now + 5 * minute)), storage.stored)
        assertEquals(later, storage.cursorValue)
    }

    @Test
    fun aRepeatedReadAddsNothing() {
        val events = listOf(fg(now - 3 * day), bg(now - 3 * day + 40 * minute))
        val storage = MemoryStorage()
        UsageSync(FakeFeed(events), storage).run(now)
        storage.setCursor(now - 4 * day)   // force the same events to be read again
        assertEquals(0, UsageSync(FakeFeed(events), storage).run(now))
        assertEquals(1, storage.stored.size)
    }

    @Test
    fun aStaleCursorIsClampedToTheLookback() {
        val feed = FakeFeed(emptyList())
        val storage = MemoryStorage(cursorValue = now - 60 * day)
        UsageSync(feed, storage).run(now)
        assertEquals(now - 7 * day, feed.windows.single().first)
    }

    @Test
    fun aCursorAtOrAfterNowReadsNothing() {
        val feed = FakeFeed(listOf(fg(now - minute)))
        val storage = MemoryStorage(cursorValue = now)
        assertEquals(0, UsageSync(feed, storage).run(now))
        assertEquals(emptyList<Pair<Long, Long>>(), feed.windows)
        assertEquals(0, storage.writes)
    }

    @Test
    fun sessionsOlderThanRetentionAreDroppedOnWrite() {
        val old = ViewingSession(yt, now - 120 * day, now - 120 * day + minute)
        val storage = MemoryStorage(initial = listOf(old))
        UsageSync(FakeFeed(emptyList()), storage).run(now)
        assertEquals(emptyList<ViewingSession>(), storage.stored)
    }

    @Test
    fun aFreshStorageHasNoCursorBeforeTheFirstRun() {
        assertNull(MemoryStorage().cursor())
    }
}
