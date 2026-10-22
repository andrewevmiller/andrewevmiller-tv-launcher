@file:OptIn(ExperimentalTestApi::class)

package com.andrewevmiller.tvlauncher.ui.behavior

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.HomeDeps
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.data.AppCategory
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.home.data.FieldLayout
import com.andrewevmiller.tvlauncher.home.data.InMemoryLaunchHistory
import com.andrewevmiller.tvlauncher.home.data.InMemoryLayoutStore
import com.andrewevmiller.tvlauncher.home.data.LaunchEntry
import com.andrewevmiller.tvlauncher.home.data.LaunchHistory
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.settings.FakeSettingsRepository
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/*
 * Shared plumbing for the Compose behaviour tests in this package. They run on the JVM under Robolectric with
 * the real Compose runtime, focus system and key dispatch, so a failing test here means the launcher's own UI
 * code misbehaves, not a mock.
 *
 * The TV canvas is 960x540dp at 320dpi (1920x1080px), which is what FieldDimens assumes; the default Robolectric
 * screen is a 320dp-wide phone, where a 4-column grid of 192dp tiles would be squeezed out of shape.
 */

/** Robolectric qualifiers for the TV canvas: pass as `@Config(sdk = [34], qualifiers = TvQualifiers)`. */
const val TvQualifiers = "w960dp-h540dp-xhdpi"

/** The rule every behaviour test uses; it exposes the activity so tests can press Back. */
typealias FieldRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

// ---------------------------------------------------------------------------------------------------------
// Recording sound
// ---------------------------------------------------------------------------------------------------------

/** One call on [FieldSound], in the order it happened. */
sealed interface SoundEvent {
    data class Tick(val row: Int) : SoundEvent
    data object Edge : SoundEvent
    data object Select : SoundEvent
    data object Back : SoundEvent
    data class Launch(val app: LauncherApp) : SoundEvent
    data object Sleep : SoundEvent
    data object Wake : SoundEvent
    data object Preview : SoundEvent
}

/** A [FieldSound] that remembers every call. The lists are thread-safe (audio code may call from any thread). */
class RecordingFieldSound : FieldSound {
    private val log = CopyOnWriteArrayList<SoundEvent>()

    /** Every call, oldest first. */
    val events: List<SoundEvent> get() = log.toList()

    val ticks: List<Int> get() = log.filterIsInstance<SoundEvent.Tick>().map { it.row }
    val launches: List<LauncherApp> get() = log.filterIsInstance<SoundEvent.Launch>().map { it.app }
    val edgeCount: Int get() = count<SoundEvent.Edge>()
    val selectCount: Int get() = count<SoundEvent.Select>()
    val backCount: Int get() = count<SoundEvent.Back>()
    val sleepCount: Int get() = count<SoundEvent.Sleep>()
    val wakeCount: Int get() = count<SoundEvent.Wake>()
    val previewCount: Int get() = count<SoundEvent.Preview>()

    private inline fun <reified T : SoundEvent> count(): Int = log.count { it is T }

    /** Forget everything so far, e.g. after the setup presses of a test. */
    fun clear() = log.clear()

    override fun tick(row: Int) { log += SoundEvent.Tick(row) }
    override fun edge() { log += SoundEvent.Edge }
    override fun select() { log += SoundEvent.Select }
    override fun back() { log += SoundEvent.Back }
    override fun launch(app: LauncherApp) { log += SoundEvent.Launch(app) }
    override fun sleep() { log += SoundEvent.Sleep }
    override fun wake() { log += SoundEvent.Wake }
    override fun preview() { log += SoundEvent.Preview }
}

/**
 * Sets [content] inside the launcher theme with [sound] provided as `LocalFieldSound`.
 *
 * Reduced motion is on by default: animations snap, and the feature band's dot field draws one still frame.
 * That is a production code path (`Field.reducedMotion`), and it keeps the Compose test clock from chasing an
 * infinite frame loop.
 */
fun ComposeContentTestRule.setContentWithSound(
    sound: FieldSound,
    reducedMotion: Boolean = true,
    content: @Composable () -> Unit,
) {
    setContent {
        FieldTheme(reducedMotion = reducedMotion) {
            CompositionLocalProvider(LocalFieldSound provides sound, content = content)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Keys. They go to the focused element through the real key dispatch, like a remote.
// ---------------------------------------------------------------------------------------------------------

fun ComposeTestRule.press(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}

fun ComposeTestRule.pressRight() = press(Key.DirectionRight)
fun ComposeTestRule.pressLeft() = press(Key.DirectionLeft)
fun ComposeTestRule.pressUp() = press(Key.DirectionUp)
fun ComposeTestRule.pressDown() = press(Key.DirectionDown)

/** The remote's OK / centre button: a tap (down, then up well before the 500 ms long-press threshold). */
fun ComposeTestRule.pressOk() = press(Key.DirectionCenter)

fun ComposeTestRule.pressEnter() = press(Key.Enter)

/**
 * Holds [key] down for [holdMillis]. The test framework generates auto-repeat key events while a key stays down
 * (the first after 500 ms, then every 50 ms, each with a higher `repeatCount`), like a real remote does. The
 * repeats are delivered back to back with no frame in between.
 */
fun ComposeTestRule.hold(key: Key, holdMillis: Long = 700) {
    onRoot().performKeyInput {
        keyDown(key)
        advanceEventTime(holdMillis)
        keyUp(key)
    }
    waitForIdle()
}

/** Holds OK long enough for the repeat events that make a long press (see [hold]). */
fun ComposeTestRule.longPressOk(holdMillis: Long = 700) = hold(Key.DirectionCenter, holdMillis)

/** Presses [key] [times] times, checking after each that exactly one element is focused. */
fun ComposeTestRule.pressRepeatedly(key: Key, times: Int, checkSingleFocus: Boolean = true) {
    repeat(times) {
        press(key)
        if (checkSingleFocus) assertSingleFocus()
    }
}

/** The system Back button, as the activity receives it. */
fun FieldRule.pressBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

// ---------------------------------------------------------------------------------------------------------
// Focus assertions
// ---------------------------------------------------------------------------------------------------------

fun ComposeTestRule.focusedNodes(): List<SemanticsNode> = onAllNodes(isFocused()).fetchSemanticsNodes()

/** Exactly one element has focus: it was never lost and never doubled. */
fun ComposeTestRule.assertSingleFocus() {
    onAllNodes(isFocused()).assertCountEquals(1)
}

/** No element has focus. */
fun ComposeTestRule.assertNoFocus() {
    onAllNodes(isFocused()).assertCountEquals(0)
}

/** The texts of the single focused element (a tile reads like `["Plex", "VIDEO"]`). */
fun ComposeTestRule.focusedTexts(): List<String> {
    val nodes = focusedNodes()
    check(nodes.size == 1) { "Expected exactly one focused element, found ${nodes.size}" }
    return nodes.single().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

/** A focusable element showing [text]. Plain text matching is ambiguous when a label repeats (a title, a tile). */
fun focusableWithText(text: String): SemanticsMatcher = hasText(text) and isFocusable()

/** The focusable element showing [text] has focus, and nothing else does. */
fun ComposeTestRule.assertFocused(text: String) {
    onNode(focusableWithText(text)).assertIsFocused()
    assertSingleFocus()
}

/** Gives the focusable element showing [text] focus directly (setup only; navigation under test uses keys). */
fun ComposeTestRule.focus(text: String) {
    focusNode(onNode(focusableWithText(text)))
}

fun ComposeTestRule.focusNode(node: SemanticsNodeInteraction) {
    node.performSemanticsAction(SemanticsActions.RequestFocus)
    waitForIdle()
}

// ---------------------------------------------------------------------------------------------------------
// App fixtures
// ---------------------------------------------------------------------------------------------------------

fun testApp(pkg: String, label: String, category: AppCategory = AppCategory.OTHER): LauncherApp =
    LauncherApp(ComponentName(pkg, "Main"), label, category, null, false)

/** `App 01` .. `App NN`, categories cycling VIDEO, MUSIC, GAMES, TOOLS, OTHER. Labels sort in numeric order. */
fun numberedApps(count: Int): List<LauncherApp> {
    val categories = AppCategory.entries
    return (1..count).map { i ->
        testApp("test.app%02d".format(i), "App %02d".format(i), categories[(i - 1) % categories.size])
    }
}

fun LauncherApp.key(): String = component.flattenToString()

/**
 * A [FakeAppsRepository] over [installed] with the first [pinnedCount] apps pinned, plus handles for assertions:
 * the apps passed to `launch`, and the stored layout.
 */
class RepoFixture(
    val installed: List<LauncherApp>,
    pinnedCount: Int = installed.size.coerceAtMost(6),
) {
    val launched = CopyOnWriteArrayList<LauncherApp>()
    val store = InMemoryLayoutStore(
        FieldLayout(pinned = installed.take(pinnedCount).map { it.key() }, seeded = true),
    )
    val repo = FakeAppsRepository(installed = installed, store = store, onLaunch = { launched += it })

    fun pinnedLabels(): List<String> = runBlocking { repo.favorites.first() }.map { it.label }
    fun allLabels(): List<String> = runBlocking { repo.apps.first() }.map { it.label }
    fun hiddenLabels(): List<String> = runBlocking { repo.hidden.first() }.map { it.label }
    fun layout(): FieldLayout = runBlocking { store.layout.first() }
    fun app(label: String): LauncherApp = installed.first { it.label == label }

    fun unpin(label: String) = runBlocking { repo.unpin(app(label).component) }
    fun pin(label: String) = runBlocking { repo.pin(app(label).component) }
    fun hide(label: String) = runBlocking { repo.hide(app(label).component) }
}

/** A plain focusable stand-in for "the section above/below" (a chips row, a feature band). */
@Composable
fun FocusableStub(label: String, onClick: () -> Unit = {}) {
    Text(label, modifier = Modifier.fieldTile(onClick = onClick).padding(8.dp))
}

/** Fake dependencies for `HomeContent`: the fixture's repository, an in-memory launch history and settings. */
class FakeHomeDeps(
    fx: RepoFixture,
    history: List<LaunchEntry> = emptyList(),
    override val screensaver: ScreensaverController = NoOpScreensaverController,
    override val settings: SettingsRepository = FakeSettingsRepository(),
) : HomeDeps {
    override val apps: AppsRepository = fx.repo
    override val launchHistory: LaunchHistory = InMemoryLaunchHistory(history)
}
