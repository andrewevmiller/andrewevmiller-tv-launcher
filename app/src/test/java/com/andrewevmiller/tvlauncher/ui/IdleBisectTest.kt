package com.andrewevmiller.tvlauncher.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.andrewevmiller.tvlauncher.home.ClockHeader
import com.andrewevmiller.tvlauncher.home.apps.AppsSection
import com.andrewevmiller.tvlauncher.home.apps.system.SystemTilesRow
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.ui.components.BandCanvas
import com.andrewevmiller.tvlauncher.ui.components.ChipOption
import com.andrewevmiller.tvlauncher.ui.components.ChipsRow
import com.andrewevmiller.tvlauncher.ui.components.FeatureBand
import com.andrewevmiller.tvlauncher.ui.components.GalleryItem
import com.andrewevmiller.tvlauncher.ui.components.GalleryRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Temporary: which Home piece keeps the Compose test clock busy? */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleBisectTest {
    @get:Rule val rule = createComposeRule()

    private fun check(content: @Composable () -> Unit) {
        rule.setContent { FieldTheme(reducedMotion = true) { content() } }
        rule.waitForIdle()
    }

    private val apps = listOf("a", "b", "c").map {
        LauncherApp(ComponentName("p.$it", "Main"), it, AppCategory.VIDEO, null, false)
    }

    @Test fun clockHeader() = check { ClockHeader() }
    @Test fun bandCanvas() = check { BandCanvas() }
    @Test fun featureBand() = check { FeatureBand("k", "t", "b", "Open", {}) }
    @Test fun chips() = check { ChipsRow(listOf(ChipOption(1, "A"), ChipOption(2, "B")), 1, {}) }
    @Test fun appsSection() = check { AppsSection(FakeAppsRepository(installed = apps), null, {}) }
    @Test fun gallery() = check { GalleryRow(listOf(GalleryItem("a", "A")), {}) }
    @Test fun systemTiles() = check { SystemTilesRow() }
}
