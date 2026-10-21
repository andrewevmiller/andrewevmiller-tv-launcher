package com.andrewevmiller.tvlauncher.home.data

import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchLogTest {
    private fun e(key: String, at: Long, cat: AppCategory = AppCategory.VIDEO, label: String = key.substringBefore('/')) =
        LaunchEntry(key, label, cat, at)

    @Test
    fun recordPutsNewestFirstAndDeduplicatesByApp() {
        var log = emptyList<LaunchEntry>()
        log = LaunchLog.record(log, e("a/.A", 1))
        log = LaunchLog.record(log, e("b/.B", 2))
        log = LaunchLog.record(log, e("a/.A", 3))
        assertEquals(listOf("a/.A", "b/.B"), log.map { it.key })
        assertEquals(3L, log.first().atMillis)
    }

    @Test
    fun recordCapsTheLog() {
        var log = emptyList<LaunchEntry>()
        for (i in 1..30) log = LaunchLog.record(log, e("p$i/.M", i.toLong()), max = 5)
        assertEquals(5, log.size)
        assertEquals("p30/.M", log.first().key)
    }

    @Test
    fun roundTripKeepsEverything() {
        val log = listOf(
            e("com.plexapp.android/.activity.SplashActivity", 3000, AppCategory.VIDEO, "Plex"),
            e("com.spotify.tv.android/com.spotify.tv.android.SpotifyTVActivity", 2000, AppCategory.MUSIC, "Spotify"),
            e("x/.Y\$Inner", 1000, AppCategory.OTHER, "Zed ünï"),
        )
        assertEquals(log, LaunchLog.decode(LaunchLog.encode(log)))
    }

    @Test
    fun textWithTabsAndLineBreaksCannotBreakTheFormat() {
        val log = listOf(e("a/.A", 5, label = "Two\tPart\nName"))
        val back = LaunchLog.decode(LaunchLog.encode(log))
        assertEquals(1, back.size)
        assertEquals("Two Part Name", back.single().label)
    }

    @Test
    fun decodeIsForgiving() {
        assertTrue(LaunchLog.decode(null).isEmpty())
        assertTrue(LaunchLog.decode("").isEmpty())
        assertTrue(LaunchLog.decode("garbage\n\t\t\t\nonly\ttwo").isEmpty())
        val raw = "a/.A\tAlpha\tNOT_A_CATEGORY\t10\nb/.B\tBeta\tGAMES\tnot-a-number\na/.A\tAlpha old\tVIDEO\t5"
        val back = LaunchLog.decode(raw)
        assertEquals(listOf("a/.A"), back.map { it.key })          // bad time dropped, duplicate keeps the newest
        assertEquals(AppCategory.OTHER, back.single().category)    // unknown category
        assertEquals(10L, back.single().atMillis)
    }

    @Test
    fun decodeSortsNewestFirstAndCaps() {
        val raw = (1..20).joinToString("\n") { "p$it/.M\tP$it\tVIDEO\t$it" }
        val back = LaunchLog.decode(raw, max = 3)
        assertEquals(listOf("p20/.M", "p19/.M", "p18/.M"), back.map { it.key })
    }

    @Test
    fun labelsUseTheGivenZone() {
        // 2026-10-21 18:05:00 UTC
        val at = 1_792_605_900_000L
        assertEquals("10-21", LaunchLog.dateLabel(at, ZoneOffset.UTC))
        assertEquals("18:05", LaunchLog.timeLabel(at, ZoneOffset.UTC, is24Hour = true))
        assertEquals("10-22", LaunchLog.dateLabel(at, ZoneOffset.ofHours(7)))
        assertEquals("01:05", LaunchLog.timeLabel(at, ZoneOffset.ofHours(7), is24Hour = true))
    }

    @Test
    fun inMemoryHistoryRecords() {
        val h = InMemoryLaunchHistory()
        h.record(e("a/.A", 1))
        h.record(e("b/.B", 2))
        runBlocking {
            assertEquals(listOf("b/.B", "a/.A"), h.entries.first().map { it.key })
        }
    }
}
