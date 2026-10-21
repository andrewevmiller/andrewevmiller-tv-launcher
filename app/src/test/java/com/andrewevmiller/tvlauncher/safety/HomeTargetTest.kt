package com.andrewevmiller.tvlauncher.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTargetTest {
    private val own = "com.andrewevmiller.tvlauncher"
    private val gathin = HomeComponent("com.gathin.tvhome", "com.gathin.tvhome.WiiMenuActivity")
    private val vidaa = HomeComponent("com.vt.tv.customerlauncher", "com.vt.tv.customerlauncher.Main")
    private val ours = HomeComponent(own, "$own.HomeAlias")

    // --- unflatten -------------------------------------------------------------------------------------

    @Test
    fun unflattenExpandsLeadingDotAgainstThePackage() {
        assertEquals(gathin, HomeComponent.unflatten("com.gathin.tvhome/.WiiMenuActivity"))
    }

    @Test
    fun unflattenKeepsFullyQualifiedClass() {
        assertEquals(gathin, HomeComponent.unflatten("com.gathin.tvhome/com.gathin.tvhome.WiiMenuActivity"))
    }

    @Test
    fun unflattenTrimsWhitespace() {
        assertEquals(gathin, HomeComponent.unflatten("  com.gathin.tvhome/.WiiMenuActivity\n"))
    }

    @Test
    fun unflattenRejectsJunk() {
        assertNull(HomeComponent.unflatten(null))
        assertNull(HomeComponent.unflatten(""))
        assertNull(HomeComponent.unflatten("   "))
        assertNull(HomeComponent.unflatten("null"))
        assertNull(HomeComponent.unflatten("com.gathin.tvhome"))
        assertNull(HomeComponent.unflatten("com.gathin.tvhome/"))
        assertNull(HomeComponent.unflatten("/.WiiMenuActivity"))
    }

    @Test
    fun flattenRoundTrips() {
        assertEquals("com.gathin.tvhome/com.gathin.tvhome.WiiMenuActivity", gathin.flatten())
        assertEquals(gathin, HomeComponent.unflatten(gathin.flatten()))
    }

    // --- choose ----------------------------------------------------------------------------------------

    @Test
    fun choosesRecordedPreviousHomeWhenStillAvailable() {
        val candidates = listOf(HomeCandidate(ours), HomeCandidate(vidaa), HomeCandidate(gathin))
        assertEquals(gathin, HomeTargetChooser.choose("com.gathin.tvhome/.WiiMenuActivity", candidates, own))
    }

    @Test
    fun recordedPreviousHomeMayBeAnyHomeActivityEvenWithoutDefaultCategory() {
        val candidates = listOf(HomeCandidate(vidaa), HomeCandidate(gathin, declaresDefaultCategory = false))
        assertEquals(gathin, HomeTargetChooser.choose(gathin.flatten(), candidates, own))
    }

    @Test
    fun fallsBackToAnotherHomeWhenRecordedOneIsGone() {
        val candidates = listOf(HomeCandidate(ours), HomeCandidate(vidaa))
        assertEquals(vidaa, HomeTargetChooser.choose("com.gathin.tvhome/.WiiMenuActivity", candidates, own))
    }

    @Test
    fun fallsBackToAnotherHomeWhenNothingWasRecorded() {
        val candidates = listOf(HomeCandidate(ours), HomeCandidate(vidaa))
        assertEquals(vidaa, HomeTargetChooser.choose(null, candidates, own))
        assertEquals(vidaa, HomeTargetChooser.choose("null", candidates, own))
        assertEquals(vidaa, HomeTargetChooser.choose("not a component", candidates, own))
    }

    @Test
    fun anyOtherChoiceMustDeclareDefaultCategory() {
        val candidates = listOf(HomeCandidate(vidaa, declaresDefaultCategory = false), HomeCandidate(gathin))
        assertEquals(gathin, HomeTargetChooser.choose(null, candidates, own))
    }

    @Test
    fun neverChoosesOurOwnPackage() {
        val candidates = listOf(HomeCandidate(ours))
        assertNull(HomeTargetChooser.choose(null, candidates, own))
        // Even if the recorded previous home somehow points at us.
        assertNull(HomeTargetChooser.choose(ours.flatten(), candidates, own))
    }

    @Test
    fun returnsNullWhenNoOtherHomeExists() {
        assertNull(HomeTargetChooser.choose(gathin.flatten(), emptyList(), own))
        assertNull(HomeTargetChooser.choose(gathin.flatten(), listOf(HomeCandidate(ours)), own))
        assertNull(HomeTargetChooser.choose(null, listOf(HomeCandidate(vidaa, declaresDefaultCategory = false)), own))
    }

    // --- notice text -----------------------------------------------------------------------------------

    @Test
    fun noticeMessageNamesThePreviousHomeAndTheRecoveryCommand() {
        val notice = FallbackNotice.forPreviousHome("Wii Menu")
        assertEquals("Wii Menu", notice.previousHome)
        assertEquals(
            "Field Launcher crashed twice, so Home went back to Wii Menu. " +
                "Run scripts/deploy.sh to make it Home again.",
            notice.message,
        )
    }
}
