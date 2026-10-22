package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.andrewevmiller.tvlauncher.FieldApplication
import com.andrewevmiller.tvlauncher.home.FieldHome
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every Home composition makes its own [com.andrewevmiller.tvlauncher.home.data.NowPlayingMonitor], and each
 * monitor owns a thread. Pressing Home or returning from Settings recomposes Home from scratch, so the monitor
 * has to be closed when Home leaves the composition or one thread leaks per visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class FieldHomeLifecycleTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private var homeShown by mutableStateOf(true)

    private fun nowPlayingThreads() =
        Thread.getAllStackTraces().keys.count { it.name == "now-playing" && it.isAlive }

    @Test
    fun leavingHomeEndsTheNowPlayingThread() {
        val container = ApplicationProvider.getApplicationContext<FieldApplication>().container
        val ui = HomeUiState()
        val before = nowPlayingThreads()

        rule.setContentWithSound(sound) {
            if (homeShown) {
                FieldHome(
                    container = container,
                    ui = ui,
                    settings = LauncherSettings(),
                    onOpenSettings = {},
                )
            }
        }
        rule.waitForIdle()
        assertEquals("Home should own one monitor thread while it is shown", before + 1, nowPlayingThreads())

        homeShown = false
        rule.waitForIdle()

        // close() quits the looper from its own thread, so give it a moment to finish.
        val deadline = System.currentTimeMillis() + 3_000
        while (nowPlayingThreads() > before && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("Home left the composition, so its monitor thread should be gone", before, nowPlayingThreads())
    }
}
