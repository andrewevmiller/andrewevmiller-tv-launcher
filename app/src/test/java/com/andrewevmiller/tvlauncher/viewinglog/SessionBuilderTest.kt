package com.andrewevmiller.tvlauncher.viewinglog

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBuilderTest {
    private val base = LocalDate.of(2026, 10, 19).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun at(minutes: Int): Long = base + minutes * 60_000L
    private fun fg(minutes: Int, pkg: String) = UsageEvent(at(minutes), pkg, UsageKind.FOREGROUND)
    private fun bg(minutes: Int, pkg: String) = UsageEvent(at(minutes), pkg, UsageKind.BACKGROUND)
    private fun on(minutes: Int) = UsageEvent(at(minutes), "", UsageKind.SCREEN_ON)
    private fun off(minutes: Int) = UsageEvent(at(minutes), "", UsageKind.SCREEN_OFF)
    private fun session(pkg: String, fromMin: Int, toMin: Int) = ViewingSession(pkg, at(fromMin), at(toMin))
    private val yt = "com.google.android.youtube.tv"
    private val plex = "com.plexapp.android"
    private val spotify = "com.spotify.tv.android"

    @Test
    fun standbyClosesTheSessionAndDropsForegroundsWhileTheScreenIsOff() {
        val events = listOf(
            fg(0, yt),
            off(10),
            fg(20, plex),          // screen still off: not a session
            on(30),
            fg(31, plex),
            bg(40, plex),
        )
        assertEquals(listOf(session(yt, 0, 10), session(plex, 31, 40)), SessionBuilder.build(events, at(50)))
    }

    @Test
    fun overlapClosesTheFirstAppWhenTheSecondStarts() {
        val events = listOf(fg(0, yt), fg(5, plex), bg(20, plex), bg(25, yt))
        assertEquals(listOf(session(yt, 0, 5), session(plex, 5, 20)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun aBackgroundForAnotherAppIsIgnored() {
        val events = listOf(fg(0, yt), bg(10, plex), bg(20, yt))
        assertEquals(listOf(session(yt, 0, 20)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun aBackgroundWithNothingOpenIsIgnored() {
        assertTrue(SessionBuilder.build(listOf(bg(5, yt)), at(60)).isEmpty())
    }

    @Test
    fun aSecondForegroundOfTheSameAppDoesNotRestartIt() {
        val events = listOf(fg(0, yt), fg(5, yt), bg(10, yt))
        assertEquals(listOf(session(yt, 0, 10)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun aPauseAndResumeOfTheSameAppAreJoined() {
        // Switching between two activities of one app: the old one pauses, the new one resumes at the same moment.
        val events = listOf(fg(0, yt), bg(10, yt), fg(10, yt), bg(20, yt))
        assertEquals(listOf(session(yt, 0, 20)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun sessionsOfOneAppLessThanTwoMinutesApartAreJoined() {
        val events = listOf(fg(0, yt), bg(10, yt), fg(11, yt), bg(20, yt))
        assertEquals(listOf(session(yt, 0, 20)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun sessionsExactlyTwoMinutesApartAreNotJoined() {
        val events = listOf(fg(0, yt), bg(10, yt), fg(12, yt), bg(20, yt))
        assertEquals(listOf(session(yt, 0, 10), session(yt, 12, 20)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun joiningIsPerApp() {
        // Plex comes in between two YouTube sessions one minute apart: the YouTube pair joins, Plex keeps its own
        // (it was closed by YouTube coming back, one minute in, so it is a one-minute session).
        val events = listOf(fg(0, yt), bg(10, yt), fg(10, plex), fg(11, yt), bg(20, yt))
        assertEquals(listOf(session(yt, 0, 20), session(plex, 10, 11)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun sessionsUnderOneMinuteAreDropped() {
        val events = listOf(
            UsageEvent(at(0), yt, UsageKind.FOREGROUND),
            UsageEvent(at(0) + 30_000, yt, UsageKind.BACKGROUND),                 // 30 s: dropped
            UsageEvent(at(10), plex, UsageKind.FOREGROUND),
            UsageEvent(at(10) + 60_000, plex, UsageKind.BACKGROUND),              // exactly 1 min: kept
        )
        assertEquals(listOf(session(plex, 10, 11)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun aSessionIsCappedAtEightHours() {
        val events = listOf(fg(0, yt), bg(20 * 60, yt))
        assertEquals(listOf(session(yt, 0, 8 * 60)), SessionBuilder.build(events, at(21 * 60)))
    }

    @Test
    fun joinedSessionsAreCappedAtEightHours() {
        val events = listOf(fg(0, yt), bg(7 * 60, yt), fg(7 * 60 + 1, yt), bg(12 * 60, yt))
        assertEquals(listOf(session(yt, 0, 8 * 60)), SessionBuilder.build(events, at(13 * 60)))
    }

    @Test
    fun aSessionWithNoEndYetRunsToNowAsAnOpenSession() {
        val events = listOf(fg(0, yt))
        assertEquals(listOf(session(yt, 0, 30)), SessionBuilder.build(events, at(30)))
        val built = SessionBuilder.buildOpen(events, at(30))
        assertTrue(built.closed.isEmpty())
        assertEquals(session(yt, 0, 30), built.open)
    }

    @Test
    fun anOpenSessionIsCappedAndThenCountsAsClosed() {
        val events = listOf(fg(0, yt))
        val built = SessionBuilder.buildOpen(events, at(9 * 60))
        assertNull(built.open)
        assertEquals(listOf(session(yt, 0, 8 * 60)), built.closed)
    }

    @Test
    fun ignoredPackagesNeitherStartNorEndASession() {
        val events = listOf(
            fg(0, ViewingLogRules.LAUNCHER_PACKAGE),
            fg(1, ViewingLogRules.SYSTEM_UI_PACKAGE),
            fg(2, ViewingLogRules.GOOGLE_SCREENSAVER_PACKAGE),
            fg(5, yt),
            fg(10, ViewingLogRules.LAUNCHER_PACKAGE),   // Home pressed: YouTube's own BACKGROUND ends it
            bg(10, yt),
            fg(15, ViewingLogRules.GOOGLE_SCREENSAVER_PACKAGE),
            bg(20, ViewingLogRules.GOOGLE_SCREENSAVER_PACKAGE),
        )
        assertEquals(listOf(session(yt, 5, 10)), SessionBuilder.build(events, at(60)))
    }

    @Test
    fun aCustomIgnoredPredicateIsUsed() {
        val events = listOf(fg(0, yt), bg(10, yt), fg(10, plex), bg(20, plex))
        assertEquals(listOf(session(plex, 10, 20)), SessionBuilder.build(events, at(60)) { it == yt })
    }

    @Test
    fun unsortedInputGivesTheSameSessions() {
        val sorted = listOf(fg(0, yt), off(10), on(30), fg(31, plex), bg(40, plex))
        val shuffled = listOf(bg(40, plex), on(30), fg(31, plex), fg(0, yt), off(10))
        assertEquals(SessionBuilder.build(sorted, at(50)), SessionBuilder.build(shuffled, at(50)))
    }

    @Test
    fun emptyInputGivesNoSessions() {
        assertTrue(SessionBuilder.build(emptyList(), at(60)).isEmpty())
        val built = SessionBuilder.buildOpen(emptyList(), at(60))
        assertTrue(built.closed.isEmpty())
        assertNull(built.open)
    }

    @Test
    fun aScreenOffWithNoForegroundIsHarmless() {
        assertTrue(SessionBuilder.build(listOf(off(5), on(6)), at(60)).isEmpty())
    }
}
