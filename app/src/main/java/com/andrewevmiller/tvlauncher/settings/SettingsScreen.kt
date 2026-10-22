package com.andrewevmiller.tvlauncher.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.ambient.NoOpScreensaverController
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.home.SleepChip
import com.andrewevmiller.tvlauncher.home.SleepToolsLogic
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.FakeAppsRepository
import com.andrewevmiller.tvlauncher.sleep.SleepPresets
import com.andrewevmiller.tvlauncher.sound.FakeMotifOverrides
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.sound.LocalSoundRow
import com.andrewevmiller.tvlauncher.sound.MotifOverride
import com.andrewevmiller.tvlauncher.sound.MotifOverrides
import com.andrewevmiller.tvlauncher.sound.Waveform
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.dotGrid
import com.andrewevmiller.tvlauncher.ui.components.rowEdgeKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hands a reducer to the settings repository. The reducer runs on the latest stored value. */
private typealias SettingsEdit = ((LauncherSettings) -> LauncherSettings) -> Unit

/** The tick pitch row for each section, so moving between sections is faintly audible. */
private const val ROW_APPEARANCE = 0
private const val ROW_SOUND = 1
private const val ROW_SCREENSAVER = 2
private const val ROW_ABOUT = 3
private const val ROW_MOTIFS = 4
private const val ROW_SLEEP = 5
private const val ROW_VIEWING_LOG = 6

/** How long the "can't start on demand" message stays up. */
private const val START_MESSAGE_MS = 6_000L

/**
 * Settings: a full-screen, D-pad-operable list on the dot-grid page.
 *
 * - Up / down move between rows, OK toggles or selects, left / right adjust the dot-grid stepper.
 * - Every change goes through `settings.update { ... }` and comes back through `settings.settings`, so it takes
 *   effect at once. The screen wraps itself in [FieldTheme] from the stored values so dark / light and reduced
 *   motion show up here immediately even before the host reads them.
 * - Back calls [onBack] (and plays the back sound). The host decides what is underneath.
 *
 * Section order: Appearance, Sound, Screensaver, About, then the long per-app motif list last so the other
 * sections stay a few key presses apart.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    overrides: MotifOverrides,
    apps: AppsRepository,
    screensaver: ScreensaverController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Forgets the Viewing log's sessions. Called on the second OK of "Clear log". */
    onClearViewingLog: () -> Unit = {},
) {
    val sound = LocalFieldSound.current
    BackHandler {
        sound.back()
        onBack()
    }

    // Null until the stored settings have been read once, so defaults never flash and never trigger a preview.
    val loaded by settings.settings.collectAsState(initial = null)
    val current = loaded
    if (current == null) {
        Box(modifier.fillMaxSize().background(Field.colors.bg))
    } else {
        FieldTheme(dark = current.darkMode, reducedMotion = current.reducedMotion) {
            SettingsContent(
                settings = settings,
                overrides = overrides,
                apps = apps,
                screensaver = screensaver,
                current = current,
                modifier = modifier,
                onClearViewingLog = onClearViewingLog,
            )
        }
    }
}

@Composable
private fun SettingsContent(
    settings: SettingsRepository,
    overrides: MotifOverrides,
    apps: AppsRepository,
    screensaver: ScreensaverController,
    current: LauncherSettings,
    modifier: Modifier,
    onClearViewingLog: () -> Unit,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val installed by apps.apps.collectAsState(initial = emptyList())
    val overrideMap by overrides.overrides.collectAsState(initial = emptyMap())
    val motifRows = remember(installed, overrideMap) {
        buildMotifRows(installed.map { MotifApp(it.label, it.component.packageName) }, overrideMap)
    }
    val appByPackage = remember(installed) { installed.associateBy { it.component.packageName } }
    val versionName = remember(context) { readVersionName(context) }

    val edit: SettingsEdit = { transform -> scope.launch { settings.update(transform) } }
    val confirm: () -> Unit = { sound.select() }

    // Waveform preview: plays after the new waveform has come back from the repository, never for the value
    // first read when the screen opened (previous is null until then).
    var seenWaveform by remember { mutableStateOf<Waveform?>(null) }
    LaunchedEffect(current.waveform) {
        val previous = seenWaveform
        seenWaveform = current.waveform
        if (SoundFeedback.shouldPreview(previous, current.waveform, current.soundsEnabled)) {
            delay(SoundFeedback.SETTLE_MS)
            sound.preview()
        }
    }

    // Turning interface sounds on plays one confirming select (it cannot be heard at press time: sounds were off).
    var seenSounds by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(current.soundsEnabled) {
        val previous = seenSounds
        seenSounds = current.soundsEnabled
        if (SoundFeedback.shouldConfirmSoundsOn(previous, current.soundsEnabled)) {
            delay(SoundFeedback.SETTLE_MS)
            sound.select()
        }
    }

    // Start now: a short mono message when the TV refuses.
    var startMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(startMessage) {
        if (startMessage != null) {
            delay(START_MESSAGE_MS)
            startMessage = null
        }
    }

    // Clear log: the first OK asks, the second OK clears. Moving focus away un-asks.
    var clearArmed by remember { mutableStateOf(false) }
    var clearMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(clearMessage) {
        if (clearMessage != null) {
            delay(START_MESSAGE_MS)
            clearMessage = null
        }
    }
    val onClearLog: () -> Unit = {
        if (!clearArmed) {
            confirm()
            clearArmed = true
            clearMessage = null
        } else {
            sound.select()
            clearArmed = false
            clearMessage = "Viewing log cleared."
            onClearViewingLog()
        }
    }

    // Focus starts on the first row. Whenever that row takes focus again the list snaps back to the top, so
    // the title is visible there.
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val firstRowModifier = Modifier
        .onFocusChanged { state ->
            if (state.isFocused || state.hasFocus) scope.launch { listState.scrollToItem(0) }
        }
        .focusRequester(firstFocus)

    val onReroll: (MotifRow) -> Unit = { row ->
        val app = appByPackage[row.packageName]
        scope.launch {
            rerollMotif(overrides, row.packageName) {
                if (app != null) sound.launch(app)
            }
        }
    }
    val onToggleMute: (MotifRow) -> Unit = { row ->
        confirm()
        scope.launch { overrides.setMuted(row.packageName, !row.override.muted) }
    }
    val onReset: (MotifRow) -> Unit = { row ->
        confirm()
        scope.launch { overrides.reset(row.packageName) }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            // The brightness setting drives the live texture, so the stepper's effect is visible right here.
            .dotGrid(colors.line.copy(alpha = DotBrightness.snap(current.dotGridAlpha)))
            // Up / Down nobody below handled: move focus ourselves so the first and last rows can play the edge
            // thud, like Home. Left / Right stay with the rows (the stepper plays its own edge).
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    val moved = focusManager.moveFocus(direction)
                    if (!moved && event.nativeKeyEvent.repeatCount == 0) sound.edge()
                }
                true
            },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                horizontal = FieldDimens.safeHorizontal,
                vertical = FieldDimens.safeVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItem("title", ROW_APPEARANCE) { SettingsTitle() }
            appearanceSection(current, edit, confirm, firstRowModifier)
            soundSection(current, edit, onReplayPreview = { sound.preview() })
            screensaverSection(
                current = current,
                controller = screensaver,
                startMessage = startMessage,
                onChoose = { choice ->
                    confirm()
                    scope.launch { chooseScreensaver(choice, settings, screensaver) }
                },
                onStartNow = {
                    confirm()
                    startMessage = ScreensaverText.startNowMessage(screensaver.startNow())
                },
            )
            sleepSection(
                current = current,
                onChoose = { minutes ->
                    confirm()
                    edit { it.copy(sleepMinutes = SleepToolsLogic.clampMinutes(minutes)) }
                },
                onEdge = { sound.edge() },
            )
            viewingLogSection(
                current = current,
                onToggle = {
                    confirm()
                    edit { it.copy(viewingLogEnabled = !it.viewingLogEnabled) }
                },
                clearArmed = clearArmed,
                clearMessage = clearMessage,
                onClear = onClearLog,
                onDisarm = { clearArmed = false },
            )
            aboutSection(versionName)
            motifSection(motifRows, onReroll, onToggleMute, onReset)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sections. Each adds keyed items to the screen's one LazyColumn.
// ---------------------------------------------------------------------------------------------

/** One keyed LazyColumn item, with the focus tick pitched for [soundRow]. */
private fun LazyListScope.rowItem(key: String, soundRow: Int, content: @Composable () -> Unit) {
    item(key = key) {
        CompositionLocalProvider(LocalSoundRow provides soundRow) { content() }
    }
}

@Composable
private fun SettingsTitle() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("Settings", style = Field.type.title, color = Field.colors.ink)
        Text("Back to return", style = Field.type.label, color = Field.colors.muted)
    }
}

private fun LazyListScope.appearanceSection(
    current: LauncherSettings,
    edit: SettingsEdit,
    confirm: () -> Unit,
    firstRowModifier: Modifier,
) {
    rowItem("appearance-header", ROW_APPEARANCE) { SectionHeader("Appearance") }
    rowItem("theme-dark", ROW_APPEARANCE) {
        OptionRow(
            label = "Dark",
            selected = current.darkMode,
            onSelect = { confirm(); edit { it.withDarkMode(true) } },
            modifier = firstRowModifier,
            detail = "Default",
        )
    }
    rowItem("theme-light", ROW_APPEARANCE) {
        OptionRow(
            label = "Light",
            selected = !current.darkMode,
            onSelect = { confirm(); edit { it.withDarkMode(false) } },
            detail = "Parchment",
        )
    }
    rowItem("reduced-motion", ROW_APPEARANCE) {
        ToggleRow(
            label = "Reduced motion",
            on = current.reducedMotion,
            onToggle = { confirm(); edit { it.withReducedMotion(!it.reducedMotion) } },
        )
    }
    rowItem("dot-brightness", ROW_APPEARANCE) {
        StepperRow(
            label = "Dot-grid brightness",
            alpha = current.dotGridAlpha,
            onStep = { delta -> edit { it.withDotGridStep(delta) } },
            onCycle = { confirm(); edit { it.withDotGridCycled() } },
        )
    }
    rowItem("dot-hint", ROW_APPEARANCE) {
        Hint("Left and right adjust. The dots are tuned by eye on this TV.")
    }
}

private fun LazyListScope.soundSection(
    current: LauncherSettings,
    edit: SettingsEdit,
    onReplayPreview: () -> Unit,
) {
    rowItem("sound-header", ROW_SOUND) { SectionHeader("Sound") }
    rowItem("sounds-enabled", ROW_SOUND) {
        ToggleRow(
            label = "Interface sounds",
            on = current.soundsEnabled,
            onToggle = { edit { it.withSoundsEnabled(!it.soundsEnabled) } },
        )
    }
    if (!current.soundsEnabled) {
        rowItem("sounds-off-hint", ROW_SOUND) { Hint("Sounds are off. Turn them on to hear previews.") }
    }
    rowItem("waveform-label", ROW_SOUND) { SubLabel("Waveform") }
    WaveformOptions.chunked(2).forEachIndexed { index, pair ->
        rowItem("waveform-row-$index", ROW_SOUND) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = RowInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (option in pair) {
                    val selected = current.waveform == option.waveform
                    OptionRow(
                        label = option.label,
                        selected = selected,
                        // The change plays a preview from the effect in SettingsContent once it is stored;
                        // choosing the waveform that is already selected just replays it.
                        onSelect = { if (selected) onReplayPreview() else edit { it.withWaveform(option.waveform) } },
                        modifier = Modifier.weight(1f),
                        accentMarker = true,
                        inset = 0.dp,
                    )
                }
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
    rowItem("waveform-hint", ROW_SOUND) { Hint("Changing the waveform plays a short preview.") }
}

private fun LazyListScope.screensaverSection(
    current: LauncherSettings,
    controller: ScreensaverController,
    startMessage: String?,
    onChoose: (Screensaver) -> Unit,
    onStartNow: () -> Unit,
) {
    rowItem("screensaver-header", ROW_SCREENSAVER) { SectionHeader("Screensaver") }
    for (choice in Screensaver.values()) {
        rowItem("screensaver-${choice.name}", ROW_SCREENSAVER) {
            OptionRow(
                label = choice.displayName(),
                selected = current.screensaver == choice,
                onSelect = { onChoose(choice) },
                detail = choice.detail(),
            )
        }
    }
    val switchHint = ScreensaverText.switchHint(controller.canSwitch)
    if (switchHint != null) {
        rowItem("screensaver-switch-hint", ROW_SCREENSAVER) { Hint(switchHint) }
    }
    rowItem("screensaver-start", ROW_SCREENSAVER) { ActionRow(label = "Start now", onClick = onStartNow) }
    if (startMessage != null) {
        rowItem("screensaver-message", ROW_SCREENSAVER) { Hint(startMessage) }
    }
}

/** Sleep: the default length the picker on Home opens on. A chip per preset; OK stores it. */
private fun LazyListScope.sleepSection(
    current: LauncherSettings,
    onChoose: (Int) -> Unit,
    onEdge: () -> Unit,
) {
    rowItem("sleep-header", ROW_SLEEP) { SectionHeader("Sleep") }
    rowItem("sleep-label", ROW_SLEEP) { SubLabel("Default length · min") }
    rowItem("sleep-chips", ROW_SLEEP) {
        val selected = SleepToolsLogic.clampMinutes(current.sleepMinutes)
        Row(
            Modifier.padding(horizontal = RowInset + 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SleepPresets.minutes.forEachIndexed { index, minutes ->
                SleepChip(
                    label = minutes.toString(),
                    selected = minutes == selected,
                    onClick = { onChoose(minutes) },
                    // The ends of the row are edges (the thud), not a leap to the waveform column above.
                    modifier = Modifier.rowEdgeKeys(index, SleepPresets.minutes.size, onEdge),
                )
            }
        }
    }
    rowItem("sleep-hint", ROW_SLEEP) { Hint("The sleep picker on Home opens on this length.") }
}

/** Viewing log: the on/off switch, and Clear log (a second OK confirms). The page itself is on the Tools row. */
private fun LazyListScope.viewingLogSection(
    current: LauncherSettings,
    onToggle: () -> Unit,
    clearArmed: Boolean,
    clearMessage: String?,
    onClear: () -> Unit,
    onDisarm: () -> Unit,
) {
    rowItem("viewing-log-header", ROW_VIEWING_LOG) { SectionHeader("Viewing log") }
    rowItem("viewing-log-toggle", ROW_VIEWING_LOG) {
        ToggleRow(label = "Viewing log", on = current.viewingLogEnabled, onToggle = onToggle)
    }
    rowItem("viewing-log-hint", ROW_VIEWING_LOG) {
        Hint("Which app was in front, and for how long. Kept on this TV only.")
    }
    // The armed prompt is the row's own label, so it is on screen while the row has focus.
    rowItem("viewing-log-clear", ROW_VIEWING_LOG) {
        ActionRow(
            label = if (clearArmed) "Press OK again to clear the log" else "Clear log",
            onClick = onClear,
            modifier = Modifier.onFocusChanged { if (!it.hasFocus) onDisarm() },
        )
    }
    if (clearMessage != null) rowItem("viewing-log-message", ROW_VIEWING_LOG) { Hint(clearMessage) }
}

private fun LazyListScope.aboutSection(versionName: String?) {
    rowItem("about-header", ROW_ABOUT) { SectionHeader("About") }
    rowItem("about-info", ROW_ABOUT) {
        InfoBlock(
            lines = listOf(
                AboutText.version(versionName),
                AboutText.RESCUE_HOME,
                AboutText.CRASH_FALLBACK,
            ),
        )
    }
}

private fun LazyListScope.motifSection(
    rows: List<MotifRow>,
    onReroll: (MotifRow) -> Unit,
    onToggleMute: (MotifRow) -> Unit,
    onReset: (MotifRow) -> Unit,
) {
    rowItem("motifs-header", ROW_MOTIFS) { SectionHeader("Sound · app motifs") }
    rowItem("motifs-hint", ROW_MOTIFS) {
        Hint("Every app plays its own launch motif. Re-roll for a new one, or mute it.")
    }
    if (rows.isEmpty()) {
        rowItem("motifs-empty", ROW_MOTIFS) { Hint("No apps found yet.") }
    }
    items(rows, key = { it.key }) { row ->
        CompositionLocalProvider(LocalSoundRow provides ROW_MOTIFS) {
            MotifListRow(
                row = row,
                onReroll = { onReroll(row) },
                onToggleMute = { onToggleMute(row) },
                onReset = { onReset(row) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Suppress("DEPRECATION")
private fun readVersionName(context: Context): String? =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsScreenPreview() {
    FieldTheme {
        SettingsScreen(
            settings = remember { FakeSettingsRepository() },
            overrides = remember {
                FakeMotifOverrides(
                    mapOf(
                        "preview.netflix" to MotifOverride(rerollCount = 3),
                        "preview.spotify" to MotifOverride(muted = true),
                    ),
                )
            },
            apps = remember { FakeAppsRepository() },
            screensaver = NoOpScreensaverController,
            onBack = {},
        )
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsScreenLightPreview() {
    FieldTheme(dark = false) {
        SettingsScreen(
            settings = remember {
                FakeSettingsRepository(
                    LauncherSettings(
                        darkMode = false,
                        soundsEnabled = false,
                        waveform = Waveform.SUPERSAW,
                        screensaver = Screensaver.GOOGLE_PHOTOS,
                        dotGridAlpha = 0.6f,
                    ),
                )
            },
            overrides = remember {
                FakeMotifOverrides(mapOf("preview.plex" to MotifOverride(rerollCount = 1, muted = true)))
            },
            apps = remember { FakeAppsRepository() },
            screensaver = NoOpScreensaverController,
            onBack = {},
        )
    }
}
