package com.andrewevmiller.tvlauncher.ambient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionProviderTest {
    @Test
    fun animatorScaleZeroMeansReduced() {
        assertTrue(isReducedByAnimatorScale(0f))
    }

    @Test
    fun anyOtherAnimatorScaleMeansFullMotion() {
        assertFalse(isReducedByAnimatorScale(1f))
        assertFalse(isReducedByAnimatorScale(0.5f))
        assertFalse(isReducedByAnimatorScale(10f))
    }

    @Test
    fun providerIsASingleMethodInterfaceSoTheIntegratorCanPassALambda() {
        var reduced = false
        val provider = ReducedMotionProvider { reduced }
        assertFalse(provider.isReduced())
        reduced = true
        assertTrue(provider.isReduced())
    }
}
