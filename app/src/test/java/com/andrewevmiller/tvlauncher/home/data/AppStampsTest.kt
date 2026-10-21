package com.andrewevmiller.tvlauncher.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStampsTest {
    private val a = AppStamp("com.example.a", 100L, true)
    private val b = AppStamp("com.example.b", 200L, true)

    private fun sig(vararg s: AppStamp, locale: String = "en-US") = AppStamps.signature(s.toList(), locale)

    @Test fun signatureIgnoresOrder() = assertEquals(sig(a, b), sig(b, a))

    @Test fun signatureIsStableHex() {
        val s = sig(a, b)
        assertEquals(64, s.length)
        assertTrue(s.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(s, sig(a, b))
    }

    @Test fun signatureChangesWhenUpdateTimeChanges() = assertNotEquals(sig(a, b), sig(a.copy(lastUpdateTime = 101L), b))

    @Test fun signatureChangesWhenAppIsDisabled() = assertNotEquals(sig(a, b), sig(a, b.copy(enabled = false)))

    @Test fun signatureChangesWhenAnAppIsAddedOrRemoved() {
        assertNotEquals(sig(a), sig(a, b))
        assertNotEquals(sig(a, b), sig(a))
    }

    @Test fun signatureChangesWithLocale() = assertNotEquals(sig(a, b, locale = "en-US"), sig(a, b, locale = "de-DE"))

    private val record = ScanRecord("abc", at = 1_000_000L)

    @Test fun noEarlierScanMeansRescan() = assertTrue(AppStamps.needsRescan(null, "abc", 1_000_000L))

    @Test fun sameDigestAndFreshMeansSkip() = assertFalse(AppStamps.needsRescan(record, "abc", record.at + 60_000L))

    @Test fun differentDigestMeansRescan() = assertTrue(AppStamps.needsRescan(record, "xyz", record.at + 1L))

    @Test fun oldScanMeansRescan() = assertTrue(AppStamps.needsRescan(record, "abc", record.at + AppStamps.MAX_AGE_MS + 1))

    @Test fun exactlyMaxAgeIsStillFresh() = assertFalse(AppStamps.needsRescan(record, "abc", record.at + AppStamps.MAX_AGE_MS))

    @Test fun clockGoingBackwardsMeansRescan() = assertTrue(AppStamps.needsRescan(record, "abc", record.at - 1L))

    private val entry = SnapshotEntry("com.example.a", "com.example.a.Main", "A", AppCategory.VIDEO, true, ArtKind.BANNER, 100L)

    @Test fun matchingEntryIsReusable() =
        assertTrue(AppStamps.reusable(entry, "com.example.a", "com.example.a.Main", true, 100L))

    @Test fun noPreviousEntryIsNotReusable() =
        assertFalse(AppStamps.reusable(null, "com.example.a", "com.example.a.Main", true, 100L))

    @Test fun updatedPackageIsNotReusable() =
        assertFalse(AppStamps.reusable(entry, "com.example.a", "com.example.a.Main", true, 101L))

    @Test fun differentActivityIsNotReusable() =
        assertFalse(AppStamps.reusable(entry, "com.example.a", "com.example.a.Other", true, 100L))

    @Test fun changedPinnedFlagIsNotReusable() =
        assertFalse(AppStamps.reusable(entry, "com.example.a", "com.example.a.Main", false, 100L))

    @Test fun differentPackageIsNotReusable() =
        assertFalse(AppStamps.reusable(entry, "com.example.b", "com.example.a.Main", true, 100L))

    @Test fun unknownUpdateTimeIsNeverTrusted() =
        assertFalse(AppStamps.reusable(entry.copy(lastUpdateTime = 0L), "com.example.a", "com.example.a.Main", true, 0L))
}
