package com.andrewevmiller.tvlauncher.home.apps.system

import android.content.Intent
import android.media.tv.TvInputInfo
import android.provider.Settings
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure system-tiles code duplicates a few framework constants so it can be unit-tested without
 * Android. This test pins the copies to the real values. It imports Android, so purecheck skips it;
 * it runs under the owner's `./gradlew testDebugUnitTest`. These are compile-time constants, so no
 * Android runtime is needed.
 */
class FrameworkConstantsTest {

    @Test
    fun inputTypesMatchTvInputInfo() {
        assertEquals(TvInputInfo.TYPE_OTHER, TvInputType.OTHER)
        assertEquals(TvInputInfo.TYPE_TUNER, TvInputType.TUNER)
        assertEquals(TvInputInfo.TYPE_COMPOSITE, TvInputType.COMPOSITE)
        assertEquals(TvInputInfo.TYPE_SVIDEO, TvInputType.SVIDEO)
        assertEquals(TvInputInfo.TYPE_SCART, TvInputType.SCART)
        assertEquals(TvInputInfo.TYPE_COMPONENT, TvInputType.COMPONENT)
        assertEquals(TvInputInfo.TYPE_VGA, TvInputType.VGA)
        assertEquals(TvInputInfo.TYPE_DVI, TvInputType.DVI)
        assertEquals(TvInputInfo.TYPE_HDMI, TvInputType.HDMI)
        assertEquals(TvInputInfo.TYPE_DISPLAY_PORT, TvInputType.DISPLAY_PORT)
    }

    @Test
    fun intentActionsMatchTheFramework() {
        assertEquals(Intent.ACTION_ASSIST, SystemIntentActions.ASSIST)
        assertEquals(RecognizerIntent.ACTION_WEB_SEARCH, SystemIntentActions.WEB_SEARCH)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, SystemIntentActions.WIFI_SETTINGS)
        assertEquals(Settings.ACTION_SETTINGS, SystemIntentActions.SETTINGS)
    }
}
