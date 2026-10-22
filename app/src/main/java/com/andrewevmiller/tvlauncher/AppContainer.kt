package com.andrewevmiller.tvlauncher

import android.app.Application
import com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService
import com.andrewevmiller.tvlauncher.ambient.ReducedMotionProvider
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.ambient.SystemScreensaverController
import com.andrewevmiller.tvlauncher.home.data.AppsRepository
import com.andrewevmiller.tvlauncher.home.data.AppsRepositoryImpl
import com.andrewevmiller.tvlauncher.home.data.DataStoreLayoutStore
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.home.data.LaunchHistory
import com.andrewevmiller.tvlauncher.home.data.RecordingAppsRepository
import com.andrewevmiller.tvlauncher.home.data.SharedPrefsLaunchHistory
import com.andrewevmiller.tvlauncher.settings.DataStoreSettingsRepository
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.sleep.AndroidSleepEndActions
import com.andrewevmiller.tvlauncher.sleep.SleepTimerController
import com.andrewevmiller.tvlauncher.sleep.SystemSleepTimer
import com.andrewevmiller.tvlauncher.sound.DataStoreMotifOverrides
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.MotifOverrides
import com.andrewevmiller.tvlauncher.sound.FieldSoundEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What the Home screen needs. [AppContainer] implements it; UI tests supply fakes. */
interface HomeDeps {
    val settings: SettingsRepository
    val apps: AppsRepository
    val launchHistory: LaunchHistory
    val screensaver: ScreensaverController
}

/**
 * The launcher's singletons, built lazily so a cold start that only needs one of them (for example the
 * screensaver reading the reduced-motion setting) doesn't pay for the rest.
 */
class AppContainer(private val app: Application) : HomeDeps {
    /** Lives as long as the process; repositories that watch the system use it. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settings: SettingsRepository by lazy { DataStoreSettingsRepository(app) }

    private val history: LaunchHistory by lazy { SharedPrefsLaunchHistory(app) }
    override val launchHistory: LaunchHistory get() = history

    override val apps: AppsRepository by lazy {
        RecordingAppsRepository(AppsRepositoryImpl(app, DataStoreLayoutStore(app), scope), history)
    }

    val overrides: MotifOverrides by lazy { DataStoreMotifOverrides(app) }

    @Volatile
    private var latestApps: List<LauncherApp> = emptyList()

    /** One engine for the process: UI sounds, per-app motifs, and the Settings preview all go through it. */
    val sound: FieldSound by lazy {
        val engine = FieldSoundEngine(app, settings, overrides, { latestApps }, scope)
        // Keep motifs pre-rendered for whatever is installed; a missing one is still rendered on first launch.
        scope.launch {
            apps.apps.collect {
                latestApps = it
                engine.onAppsChanged()
            }
        }
        engine
    }

    override val screensaver: ScreensaverController by lazy { SystemScreensaverController(app) }

    /**
     * The sleep timer. The alarm receiver reaches the same instance through here, also in a process that was started
     * just for the alarm; the sound engine and the screensaver are only touched if the timer really fires.
     */
    val sleepTimer: SleepTimerController by lazy {
        SystemSleepTimer(app, endActions = { AndroidSleepEndActions(app, { sound }, { screensaver }) })
    }

    /** Lets the Oxyfield dream read the reduced-motion setting; it runs once at dream start, off no UI thread work. */
    fun installDreamHooks() {
        OxyfieldDreamService.reducedMotionProvider = ReducedMotionProvider {
            runBlocking { settings.settings.first().reducedMotion }
        }
    }
}
