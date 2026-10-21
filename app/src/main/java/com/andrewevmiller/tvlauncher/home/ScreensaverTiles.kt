package com.andrewevmiller.tvlauncher.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.ambient.startPhotosSlideshow
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.ui.components.GalleryItem
import com.andrewevmiller.tvlauncher.ui.components.GalleryRow
import kotlinx.coroutines.launch

private const val PHOTOS_KEY = "photos"
private const val OXYFIELD_KEY = "oxyfield"
private const val NO_ON_DEMAND = "This TV won't start it on demand. It will run when idle."

/**
 * The two "start a screensaver now" tiles. Shared by [HomeContent] and [FieldHome] so both start them the
 * same way. [current] is the stored choice; [onMessage] receives anything worth telling the user.
 */
@Composable
fun ScreensaverTiles(
    screensaver: ScreensaverController,
    settings: SettingsRepository,
    current: Screensaver,
    onMessage: (String) -> Unit,
) {
    val sound = LocalFieldSound.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GalleryRow(
        items = listOf(
            GalleryItem(PHOTOS_KEY, "Google Photos · slideshow"),
            GalleryItem(OXYFIELD_KEY, "Oxyfield · dot field"),
        ),
        playSelect = false,
        onSelect = { item ->
            sound.sleep()
            when (item.key) {
                PHOTOS_KEY -> startPhotosSlideshow(context, screensaver, current, onMessage)
                OXYFIELD_KEY -> {
                    if (screensaver.canSwitch) {
                        screensaver.apply(Screensaver.OXYFIELD)
                        scope.launch { settings.update { it.copy(screensaver = Screensaver.OXYFIELD) } }
                    }
                    if (!screensaver.startNow()) onMessage(NO_ON_DEMAND)
                }
            }
        },
    )
}
