package com.andrewevmiller.tvlauncher.viewinglog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingLogCodecTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val minute = 60_000L

    @Test
    fun roundTripKeepsEverySession() {
        val sessions = listOf(
            ViewingSession("com.google.android.youtube.tv", now - 2 * day, now - 2 * day + 90 * minute),
            ViewingSession("com.plexapp.android", now - day, now - day + 45 * minute),
            ViewingSession("com.spotify.tv.android", now - 10 * minute, now - 2 * minute),
        )
        val text = ViewingLogCodec.encode(sessions)
        assertEquals(sessions, ViewingLogCodec.decode(text, now))
    }

    @Test
    fun encodeWritesTabSeparatedLines() {
        val text = ViewingLogCodec.encode(listOf(ViewingSession("com.a", 100, 200)))
        assertEquals("com.a\t100\t200", text)
    }

    @Test
    fun tabsAndLineBreaksInANameBecomeSpaces() {
        val text = ViewingLogCodec.encode(listOf(ViewingSession("com.a\tb\nc", 100, 200)))
        assertEquals("com.a b c\t100\t200", text)
        assertEquals("com.a b c", ViewingLogCodec.decode(text, 200).single().packageName)
    }

    @Test
    fun badLinesAreSkippedAndGoodOnesKept() {
        val raw = listOf(
            "",
            "com.a\t1\t2\t3",                  // four fields
            "com.a\t1",                        // two fields
            "bad line with no tabs",
            "\t100\t200",                      // no package
            "com.a\tx\t200",                   // start not a number
            "com.a\t100\ty",                   // end not a number
            "com.a\t200\t100",                 // ends before it starts
            "com.a\t200\t200",                 // zero length
            "com.good\t${now - minute}\t${now}",
        ).joinToString("\n")
        val decoded = ViewingLogCodec.decode(raw, now)
        assertEquals(listOf(ViewingSession("com.good", now - minute, now)), decoded)
    }

    @Test
    fun nullAndBlankGiveNothing() {
        assertTrue(ViewingLogCodec.decode(null, now).isEmpty())
        assertTrue(ViewingLogCodec.decode("   \n  ", now).isEmpty())
    }

    @Test
    fun sessionsOlderThanNinetyDaysArePruned() {
        val cutoff = now - ViewingLogRules.RETENTION_DAYS * day
        assertEquals(90, ViewingLogRules.RETENTION_DAYS)
        val old = ViewingSession("com.old", cutoff - 1, cutoff - 1 + minute)
        val onTheCutoff = ViewingSession("com.edge", cutoff, cutoff + minute)
        val recent = ViewingSession("com.new", now - day, now - day + minute)
        val decoded = ViewingLogCodec.decode(ViewingLogCodec.encode(listOf(old, onTheCutoff, recent)), now)
        assertEquals(listOf(onTheCutoff, recent), decoded)
    }

    @Test
    fun decodeSortsOldestFirst() {
        val a = ViewingSession("com.a", now - 3 * minute, now - 2 * minute)
        val b = ViewingSession("com.b", now - 9 * minute, now - 8 * minute)
        assertEquals(listOf(b, a), ViewingLogCodec.decode(ViewingLogCodec.encode(listOf(a, b)), now))
    }

    @Test
    fun aRepeatedSessionIsKeptOnce() {
        val s = ViewingSession("com.a", now - minute, now)
        val text = ViewingLogCodec.encode(listOf(s, s))
        assertEquals(listOf(s), ViewingLogCodec.decode(text, now))
    }

    @Test
    fun mergeAddsNewSessionsAndPrunes() {
        val old = ViewingSession("com.old", now - 100 * day, now - 100 * day + minute)
        val kept = ViewingSession("com.kept", now - day, now - day + minute)
        val added = ViewingSession("com.added", now - minute, now)
        assertEquals(listOf(kept, added), ViewingLogCodec.merge(listOf(old, kept), listOf(added), now))
    }

    @Test
    fun mergeOfTheSameSessionTwiceKeepsOne() {
        val s = ViewingSession("com.a", now - minute, now)
        assertEquals(listOf(s), ViewingLogCodec.merge(listOf(s), listOf(s), now))
    }
}
