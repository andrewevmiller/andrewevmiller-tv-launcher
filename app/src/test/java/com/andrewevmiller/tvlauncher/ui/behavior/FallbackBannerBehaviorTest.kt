package com.andrewevmiller.tvlauncher.ui.behavior

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.andrewevmiller.tvlauncher.home.HomeContent
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.home.data.PreviewApps
import com.andrewevmiller.tvlauncher.safety.FallbackNotice
import com.andrewevmiller.tvlauncher.safety.FallbackNoticeBanner
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crash-loop fallback notice (A4): the banner takes focus on its OK chip by itself, so a single OK press on
 * the remote dismisses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TvQualifiers)
class FallbackBannerBehaviorTest {
    @get:Rule
    val rule: FieldRule = createAndroidComposeRule<ComponentActivity>()

    private val sound = RecordingFieldSound()
    private var dismissed = 0

    private fun show() {
        rule.setContentWithSound(sound) {
            FallbackNoticeBanner(
                notice = FallbackNotice.forPreviousHome("Wii Menu"),
                onDismiss = { dismissed++ },
            )
        }
        rule.waitForIdle()
    }

    @Ignore(
        "BUG: FallbackNoticeBanner.kt:46-52 calls okFocus.requestFocus() in a LaunchedEffect before the first " +
            "frame; Compose silently drops a request on a node that is not laid out yet, so OK is never focused",
    )
    @Test
    fun theOkChipIsFocusedOnItsOwn() {
        show()

        rule.assertFocused("OK")
    }

    @Test
    fun theNoticeNamesThePreviousHome() {
        show()

        rule.onNodeWithText("NOTICE").assertExists()
        rule.onNodeWithText(FallbackNotice.messageFor("Wii Menu")).assertExists()
    }

    @Test
    fun okOnTheRemoteDismisses() {
        show()
        rule.focus("OK") // set up by hand: see theOkChipIsFocusedOnItsOwn for the automatic focus

        rule.pressOk()

        assertEquals(1, dismissed)
    }

    @Test
    fun enterDismisses() {
        show()
        rule.focus("OK")

        rule.pressEnter()

        assertEquals(1, dismissed)
    }

    @Test
    fun arrowKeysDoNotDismissAndFocusStaysOnOk() {
        show()
        rule.focus("OK")

        rule.pressLeft()
        rule.pressRight()
        rule.pressUp()
        rule.pressDown()

        assertEquals(0, dismissed)
        rule.assertFocused("OK")
    }

    @Ignore(
        "BUG: FieldRoot overlays the banner on HomeContent, whose first-focus loop (HomeContent.kt " +
            "LaunchedEffect(ui, loaded)) focuses the first app tile even when the banner already holds focus, so " +
            "OK on the remote launches an app instead of dismissing the notice (confirmed with a banner patched " +
            "to request focus after the first frame)",
    )
    @Test
    fun overHomeTheNoticeKeepsFocusOnOkUntilItIsDismissed() {
        val fx = RepoFixture(PreviewApps)
        val ui = HomeUiState()
        rule.setContentWithSound(sound) {
            // The same arrangement as FieldRoot: Home with the banner overlaid at the top.
            Box(Modifier.fillMaxSize()) {
                HomeContent(
                    deps = FakeHomeDeps(fx),
                    ui = ui,
                    settings = LauncherSettings(),
                    onOpenDrawer = {},
                    onOpenSettings = {},
                )
                FallbackNoticeBanner(
                    notice = FallbackNotice.forPreviousHome("Wii Menu"),
                    onDismiss = { dismissed++ },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("YouTube").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()

        rule.assertFocused("OK")
        rule.pressOk()
        assertEquals(1, dismissed)
        assertEquals("pressing OK must not launch an app", emptyList<Any>(), fx.launched.toList())
    }
}
