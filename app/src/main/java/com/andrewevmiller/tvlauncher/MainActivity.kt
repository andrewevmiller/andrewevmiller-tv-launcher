package com.andrewevmiller.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrewevmiller.tvlauncher.home.Chip
import com.andrewevmiller.tvlauncher.home.HomeScreen
import com.andrewevmiller.tvlauncher.home.SystemTile
import com.andrewevmiller.tvlauncher.home.data.InstalledApps
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import androidx.compose.runtime.CompositionLocalProvider
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.FieldIcons
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var sound: FieldSound
    private var apps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var chip by mutableStateOf(Chip.ALL)
    private var drawer by mutableStateOf(false)
    private var systemTiles by mutableStateOf<List<SystemTile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher is the bottom of the stack; Back only leaves the drawer.
        onBackPressedDispatcher.addCallback(this) {
            if (drawer) {
                drawer = false
                chip = Chip.ALL
            }
        }
        sound = FieldSound(this)
        systemTiles = buildSystemTiles()
        setContent {
            FieldTheme {
              CompositionLocalProvider(LocalFieldSound provides sound) {
                HomeScreen(
                    allApps = apps,
                    systemTiles = systemTiles,
                    chip = chip,
                    drawer = drawer,
                    onChip = {
                        sound.select()
                        chip = it
                    },
                    onOpenDrawer = {
                        sound.select()
                        drawer = true
                        chip = Chip.ALL
                    },
                    onLaunch = {
                        sound.launch(it)
                        InstalledApps.launch(this, it)
                    },
                )
              }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home always returns to the top of Home.
        drawer = false
        chip = Chip.ALL
    }

    override fun onResume() {
        super.onResume()
        // Reload on every return so installs and uninstalls show up.
        thread { apps = InstalledApps.load(this) }
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }

    private fun open(intent: Intent) {
        sound.select()
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun buildSystemTiles(): List<SystemTile> {
        val inputs = runCatching {
            (getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager).tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI }
        }.getOrDefault(emptyList())
        val hdmi = inputs.map { input ->
            val label = input.loadCustomLabel(this)?.toString() ?: input.loadLabel(this).toString()
            SystemTile(label, FieldIcons.Port) {
                open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))
            }
        }
        return hdmi + listOf(
            SystemTile("Wi-Fi", FieldIcons.Wifi) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            SystemTile("Search", FieldIcons.Search) { open(Intent("android.search.action.GLOBAL_SEARCH")) },
            SystemTile("Settings", FieldIcons.Gear) { open(Intent(Settings.ACTION_SETTINGS)) },
        )
    }
}
