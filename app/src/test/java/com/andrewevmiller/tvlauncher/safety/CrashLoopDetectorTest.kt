package com.andrewevmiller.tvlauncher.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [CrashStore]. */
private class MemoryCrashStore(var state: CrashState = CrashState()) : CrashStore {
    override fun load(): CrashState = state
    override fun save(state: CrashState) {
        this.state = state
    }
}

/** Stores the encoded string only, like SharedPreferences does, to prove the codec loses nothing. */
private class EncodedCrashStore(var text: String? = null) : CrashStore {
    override fun load(): CrashState = CrashStateCodec.decode(text)
    override fun save(state: CrashState) {
        text = CrashStateCodec.encode(state)
    }
}

class CrashLoopDetectorTest {
    private val t0 = 1_700_000_000_000L

    /** Starts a "process" at [startAt] and returns whether a crash [uptimeMillis] later trips. */
    private fun CrashLoopDetector.processCrashesAfter(startAt: Long, uptimeMillis: Long): Boolean {
        recordProcessStart(startAt)
        return onCrash(startAt + uptimeMillis)
    }

    @Test
    fun singleEarlyCrashDoesNotTrip() {
        val detector = CrashLoopDetector(MemoryCrashStore())
        assertFalse(detector.processCrashesAfter(t0, 5_000))
    }

    @Test
    fun twoEarlyCrashesFiveAndTwentySecondsAfterStartTrip() {
        val detector = CrashLoopDetector(MemoryCrashStore())
        // First process dies 5 s after start; the next one starts a second later and dies 20 s after its start.
        assertFalse(detector.processCrashesAfter(t0, 5_000))
        assertTrue(detector.processCrashesAfter(t0 + 6_000, 20_000))
    }

    @Test
    fun twoEarlyCrashesNinetySecondsApartDoNotTrip() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 5_000))
        // Crash #2 is at t0 + 95 s, so the two crashes are 90 s apart.
        assertFalse(detector.processCrashesAfter(t0 + 90_000, 5_000))
        // The stale first record was dropped; only the new one is kept.
        assertEquals(listOf(t0 + 95_000), store.state.earlyCrashMillis)
    }

    @Test
    fun crashAfterSixtySecondsOfUptimeIsIgnored() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 61_000))
        assertTrue(store.state.earlyCrashMillis.isEmpty())
        // A late crash does not count towards a later early one either.
        assertFalse(detector.processCrashesAfter(t0 + 62_000, 5_000))
    }

    @Test
    fun uptimeOfExactlyTheWindowIsLate() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 60_000))
        assertTrue(store.state.earlyCrashMillis.isEmpty())
        assertFalse(detector.processCrashesAfter(t0 + 61_000, 59_999))
        assertEquals(1, store.state.earlyCrashMillis.size)
    }

    @Test
    fun lateCrashNeitherCountsNorExtendsTheWindow() {
        val detector = CrashLoopDetector(MemoryCrashStore())
        // Early crash at t0 + 10 s.
        assertFalse(detector.processCrashesAfter(t0, 10_000))
        // The next process lives 61 s and then crashes late (t0 + 72 s). That must not keep the
        // first record alive, so an early crash a few seconds later is again a lone crash.
        assertFalse(detector.processCrashesAfter(t0 + 11_000, 61_000))
        assertFalse(detector.processCrashesAfter(t0 + 73_000, 5_000))
    }

    @Test
    fun oldRecordsExpire() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 1_000)) // crash at t0 + 1 s
        // 60.001 s later: the first record is just outside the window.
        assertFalse(detector.processCrashesAfter(t0 + 60_000, 1_001)) // crash at t0 + 61.001 s
        assertEquals(listOf(t0 + 61_001), store.state.earlyCrashMillis)
    }

    @Test
    fun recordExactlyOneWindowOldStillCounts() {
        val detector = CrashLoopDetector(MemoryCrashStore())
        assertFalse(detector.processCrashesAfter(t0, 1_000)) // crash at t0 + 1 s
        assertTrue(detector.processCrashesAfter(t0 + 59_000, 2_000)) // crash at t0 + 61 s, 60 s later
    }

    @Test
    fun startingAProcessPrunesOldRecords() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        detector.processCrashesAfter(t0, 5_000)
        detector.recordProcessStart(t0 + 120_000)
        assertTrue(store.state.earlyCrashMillis.isEmpty())
        assertEquals(t0 + 120_000, store.state.processStartMillis)
    }

    @Test
    fun resetForgetsCrashesButKeepsProcessStart() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 5_000))
        assertTrue(detector.processCrashesAfter(t0 + 6_000, 5_000))
        detector.reset()
        assertTrue(store.state.earlyCrashMillis.isEmpty())
        assertEquals(t0 + 6_000, store.state.processStartMillis)
        // After a hand-off, one more early crash is just a first crash again.
        assertFalse(detector.processCrashesAfter(t0 + 20_000, 5_000))
    }

    @Test
    fun crashWithUnknownProcessStartIsNotCounted() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.onCrash(t0))
        assertFalse(detector.onCrash(t0 + 1_000))
        assertTrue(store.state.earlyCrashMillis.isEmpty())
    }

    @Test
    fun wallClockGoingBackwardsNeverTrips() {
        val store = MemoryCrashStore()
        val detector = CrashLoopDetector(store)
        assertFalse(detector.processCrashesAfter(t0, 5_000)) // record at t0 + 5 s
        // Clock jumps back an hour before the next process starts; its crash is stamped before the old record.
        assertFalse(detector.processCrashesAfter(t0 - 3_600_000, 5_000))
        assertEquals(listOf(t0 - 3_595_000), store.state.earlyCrashMillis)
        // A crash stamped before the process even started (clock moved mid-run) is not early.
        detector.recordProcessStart(t0)
        assertFalse(detector.onCrash(t0 - 1))
    }

    @Test
    fun threeFastCrashesStillTripOnTheSecond() {
        val detector = CrashLoopDetector(MemoryCrashStore())
        assertFalse(detector.processCrashesAfter(t0, 3_000))
        assertTrue(detector.processCrashesAfter(t0 + 4_000, 3_000))
        assertTrue(detector.processCrashesAfter(t0 + 8_000, 3_000))
    }

    @Test
    fun customWindowAndTripCount() {
        val detector = CrashLoopDetector(MemoryCrashStore(), windowMillis = 10_000L, tripCount = 3)
        assertFalse(detector.processCrashesAfter(t0, 1_000))
        assertFalse(detector.processCrashesAfter(t0 + 2_000, 1_000))
        assertTrue(detector.processCrashesAfter(t0 + 4_000, 1_000))
    }

    // --- persistence -------------------------------------------------------------------------------------

    @Test
    fun stateSurvivesSaveAndLoad() {
        val store = EncodedCrashStore()
        val state = CrashState(processStartMillis = t0, earlyCrashMillis = listOf(t0 + 5_000, t0 + 26_000))
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun emptyCrashListSurvivesSaveAndLoad() {
        val store = EncodedCrashStore()
        store.save(CrashState(processStartMillis = t0))
        assertEquals(CrashState(processStartMillis = t0), store.load())
        store.save(CrashState())
        assertEquals(CrashState(), store.load())
    }

    @Test
    fun detectorTripsAcrossProcessRestartsThroughPersistedState() {
        val store = EncodedCrashStore()
        // Process 1: its own detector instance, dies early.
        assertFalse(CrashLoopDetector(store).processCrashesAfter(t0, 5_000))
        // Process 2: a fresh detector instance over the same persisted text.
        assertTrue(CrashLoopDetector(store).processCrashesAfter(t0 + 6_000, 20_000))
    }

    @Test
    fun unreadableStateDecodesToEmptyDefault() {
        val empty = CrashState()
        assertEquals(empty, CrashStateCodec.decode(null))
        assertEquals(empty, CrashStateCodec.decode(""))
        assertEquals(empty, CrashStateCodec.decode("garbage"))
        assertEquals(empty, CrashStateCodec.decode("v2;1;2"))
        assertEquals(empty, CrashStateCodec.decode("v1;notanumber;"))
        assertEquals(empty, CrashStateCodec.decode("v1;5;1,x,3"))
        assertEquals(empty, CrashStateCodec.decode("v1;5"))
    }
}
