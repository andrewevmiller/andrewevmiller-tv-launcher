package com.andrewevmiller.tvlauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andrewevmiller.tvlauncher.home.FieldToast
import com.andrewevmiller.tvlauncher.home.FieldHome
import com.andrewevmiller.tvlauncher.home.HomeUiState
import com.andrewevmiller.tvlauncher.safety.CrashGuard
import com.andrewevmiller.tvlauncher.safety.FallbackNoticeBanner
import com.andrewevmiller.tvlauncher.settings.LauncherSettings
import com.andrewevmiller.tvlauncher.settings.SettingsScreen
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.viewinglog.ui.ViewingLogScreen

private enum class Route { Home, Log, Settings }

/**
 * The launcher's UI root. [homeResets] increments each time Home is pressed (MainActivity.onNewIntent): it
 * returns to the Home route with a fresh [HomeUiState] and a fresh [FieldHome], so Recent collapses, the chip
 * filter clears and the drawer closes. [settingsRequests] increments on the remote's Menu/Settings key.
 */
@Composable
fun FieldRoot(container: AppContainer, homeResets: Int, settingsRequests: Int, onContentReady: () -> Unit = {}) {
    val loaded by container.settings.settings.collectAsStateWithLifecycle<LauncherSettings?>(initialValue = null)
    // Wait for the stored theme so a light-mode user never sees a dark flash.
    val settings = loaded ?: return
    SideEffect { onContentReady() }

    FieldTheme(dark = settings.darkMode, reducedMotion = settings.reducedMotion) {
        CompositionLocalProvider(LocalFieldSound provides container.sound) {
            val context = LocalContext.current
            var route by remember(homeResets) { mutableStateOf(Route.Home) }
            val ui = remember(homeResets) { HomeUiState() }
            var notice by remember { mutableStateOf(CrashGuard.pendingNotice(context)) }
            // Counts each return from the Viewing log, so the Tools tile takes focus back.
            var logReturns by remember(homeResets) { mutableStateOf(0) }

            LaunchedEffect(settingsRequests) {
                if (settingsRequests > 0) route = Route.Settings
            }

            when (route) {
                // The log is drawn over Home. Home stays composed (its list keeps its place) but cannot take focus.
                Route.Home, Route.Log -> Box(Modifier.fillMaxSize()) {
                    // Home pressed again: a fresh FieldHome, so Recent collapses, the filter clears and the drawer closes.
                    key(homeResets) {
                        FieldHome(
                            container = container,
                            ui = ui,
                            settings = settings,
                            onOpenSettings = { route = Route.Settings },
                            modifier = if (route == Route.Home) Modifier else Modifier.focusProperties { canFocus = false },
                            onOpenLog = {
                                container.viewingLog.refresh()
                                route = Route.Log
                            },
                            focusLogTile = logReturns,
                        )
                    }
                    if (route == Route.Log) {
                        val installed by container.apps.apps.collectAsStateWithLifecycle(initialValue = emptyList())
                        val sessions by container.viewingLog.sessions.collectAsStateWithLifecycle()
                        val labels = remember(installed) { installed.associate { it.component.packageName to it.label } }
                        val usageGranted = remember { container.viewingLog.hasUsageAccess() }
                        ViewingLogScreen(
                            sessions = sessions,
                            enabled = settings.viewingLogEnabled,
                            usageGranted = usageGranted,
                            labelOf = { pkg -> labels[pkg] ?: pkg.substringAfterLast('.') },
                            onBack = {
                                logReturns++
                                route = Route.Home
                            },
                            dotAlpha = settings.dotGridAlpha,
                        )
                    }
                    notice?.let { n ->
                        FallbackNoticeBanner(
                            notice = n,
                            onDismiss = {
                                CrashGuard.acknowledge(context)
                                notice = null
                            },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                    FieldToast(
                        message = ui.message,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = FieldDimens.safeHorizontal, bottom = FieldDimens.safeVertical),
                    )
                }

                Route.Settings -> SettingsScreen(
                    settings = container.settings,
                    overrides = container.overrides,
                    apps = container.apps,
                    screensaver = container.screensaver,
                    onBack = { route = Route.Home },
                    onClearViewingLog = { container.viewingLog.clear() },
                )
            }
        }
    }
}
