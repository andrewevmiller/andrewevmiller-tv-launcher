package com.andrewevmiller.tvlauncher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.Feature
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRow
import com.andrewevmiller.tvlauncher.marginnote.MarginNoteRules
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The margin note on Home and in its editor, to PNGs (see [ScreenshotSupport]). Run with FIELD_SCREENSHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class MarginNoteScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun only() = ScreenshotSupport.assumeEnabled()

    private fun app(pkg: String, label: String, category: AppCategory) =
        LauncherApp(ComponentName(pkg, "$pkg.Main"), label, category, artwork = null, artworkIsBanner = false)

    private val installed = listOf(
        app("com.netflix.ninja", "Netflix", AppCategory.VIDEO),
        app("com.plexapp.android", "Plex", AppCategory.VIDEO),
        app("com.spotify.tv.android", "Spotify", AppCategory.MUSIC),
    )

    private var note by mutableStateOf("")
    private var editing by mutableStateOf(false)

    @Composable
    private fun Home() {
        HomeScreen(
            allApps = installed,
            systemTiles = listOf(
                SystemTile("HDMI 1", FieldIcons.Port) {},
                SystemTile("Wi-Fi", FieldIcons.Wifi) {},
                SystemTile("Search", FieldIcons.Search) {},
                SystemTile("Settings", FieldIcons.Gear) {},
            ),
            chip = Chip.ALL, drawer = false, onChip = {}, onOpenDrawer = {}, onLaunch = {},
            features = listOf(Feature(installed.first(), "2026-10-21 at 09:00")),
            nowPlaying = null, onOpenPlaying = {}, recent = emptyList(), recentOpen = false, onToggleRecent = {},
            order = emptyList(), editingPkg = null, onLongPress = {}, onMove = { _, _ -> }, onEditEnd = {},
            pinnedSet = installed.map { it.component.packageName }.toSet(),
            menuApp = null, onMenuPick = {},
            onOpenSettings = {},
            note = {
                MarginNoteRow(
                    note = MarginNoteRules.sanitize(note),
                    editing = editing,
                    onOpen = { editing = true },
                    onSave = { text ->
                        note = MarginNoteRules.sanitize(text)
                        editing = false
                    },
                )
            },
        )
    }

    private fun show(initial: String, open: Boolean = false) {
        note = initial
        editing = false
        rule.setContent { FieldTheme(reducedMotion = true) { Home() } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        if (open) {
            rule.onNodeWithText("NOTE  $initial").performClick()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
        }
    }

    @Test
    fun homeWithANote() {
        show("finish S2 of Severance")
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "margin-note-home-with-note").path)
    }

    @Test
    fun homeWithNoNote() {
        show("")
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "margin-note-home-no-note").path)
    }

    @Test
    fun editorOpen() {
        show("finish S2 of Severance", open = true)
        println("screenshot " + ScreenshotSupport.capture(rule.activity, "margin-note-editor-open").path)
    }
}
