package com.andrewevmiller.tvlauncher.ambient

import com.andrewevmiller.tvlauncher.settings.Screensaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosHandoffPolicyTest {
    private val hint = "Pick Google Photos as the screensaver in Settings, then let the TV idle."

    @Test
    fun hintTextIsTheAgreedOne() {
        assertEquals(hint, PhotosHandoffPolicy.HINT)
    }

    @Test
    fun safetyTimeoutIsTwoHours() {
        assertEquals(7_200_000L, PhotosHandoffPolicy.SAFETY_TIMEOUT_MS)
    }

    // --- plan ---

    @Test
    fun whenSwitchingIsAllowedWeApplyStartAndRememberTheOwnersChoice() {
        val p = PhotosHandoffPolicy.plan(canSwitch = true, current = Screensaver.OXYFIELD)
        assertEquals(HandoffAction.APPLY_AND_START, p.action)
        assertEquals(Screensaver.OXYFIELD, p.restoreTo)
    }

    @Test
    fun nothingToRestoreWhenPhotosAlreadyIsTheOwnersChoice() {
        val p = PhotosHandoffPolicy.plan(canSwitch = true, current = Screensaver.GOOGLE_PHOTOS)
        assertEquals(HandoffAction.APPLY_AND_START, p.action)
        assertNull(p.restoreTo)
    }

    @Test
    fun withoutPermissionAndAnotherScreensaverSelectedWeOnlyHint() {
        val p = PhotosHandoffPolicy.plan(canSwitch = false, current = Screensaver.OXYFIELD)
        assertEquals(HandoffAction.HINT_ONLY, p.action)
        assertNull(p.restoreTo)
    }

    @Test
    fun withoutPermissionButPhotosSelectedByHandWeStillTryToStart() {
        val p = PhotosHandoffPolicy.plan(canSwitch = false, current = Screensaver.GOOGLE_PHOTOS)
        assertEquals(HandoffAction.START_ONLY, p.action)
        assertNull(p.restoreTo)
    }

    // --- outcome ---

    @Test
    fun successfulStartRestoresWhenTheDreamEnds() {
        val plan = PhotosHandoffPolicy.plan(true, Screensaver.OXYFIELD)
        val o = PhotosHandoffPolicy.outcome(plan, started = true)
        assertNull(o.message)
        assertFalse(o.restoreNow)
        assertTrue(o.restoreAfterDream)
    }

    @Test
    fun refusedStartAfterSwitchingUndoesTheSwitchNowAndHints() {
        val plan = PhotosHandoffPolicy.plan(true, Screensaver.OXYFIELD)
        val o = PhotosHandoffPolicy.outcome(plan, started = false)
        assertEquals(hint, o.message)
        assertTrue(o.restoreNow)
        assertFalse(o.restoreAfterDream)
    }

    @Test
    fun successWhenPhotosWasAlreadyTheChoiceNeedsNoRestore() {
        val plan = PhotosHandoffPolicy.plan(true, Screensaver.GOOGLE_PHOTOS)
        val ok = PhotosHandoffPolicy.outcome(plan, started = true)
        assertNull(ok.message)
        assertFalse(ok.restoreNow)
        assertFalse(ok.restoreAfterDream)
        val refused = PhotosHandoffPolicy.outcome(plan, started = false)
        assertEquals(hint, refused.message)
        assertFalse(refused.restoreNow)
        assertFalse(refused.restoreAfterDream)
    }

    @Test
    fun hintOnlyAlwaysHintsRegardlessOfTheStartFlag() {
        val plan = PhotosHandoffPolicy.plan(false, Screensaver.OXYFIELD)
        for (started in listOf(true, false)) {
            val o = PhotosHandoffPolicy.outcome(plan, started)
            assertEquals(hint, o.message)
            assertFalse(o.restoreNow)
            assertFalse(o.restoreAfterDream)
        }
    }

    @Test
    fun startOnlyHintsOnlyWhenTheStartIsRefused() {
        val plan = PhotosHandoffPolicy.plan(false, Screensaver.GOOGLE_PHOTOS)
        assertNull(PhotosHandoffPolicy.outcome(plan, started = true).message)
        assertEquals(hint, PhotosHandoffPolicy.outcome(plan, started = false).message)
    }

    @Test
    fun neverBothRestoreNowAndRestoreAfterDream() {
        for (canSwitch in listOf(true, false)) for (cur in Screensaver.values()) for (started in listOf(true, false)) {
            val o = PhotosHandoffPolicy.outcome(PhotosHandoffPolicy.plan(canSwitch, cur), started)
            assertFalse(o.restoreNow && o.restoreAfterDream)
        }
    }
}
