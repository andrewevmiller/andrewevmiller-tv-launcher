package com.andrewevmiller.tvlauncher.home.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.home.data.LauncherApp
import com.andrewevmiller.tvlauncher.sound.FieldSound
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcon
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons

/** Apps without a 16:9 banner show their icon large, not a small glyph in the corner (CLAUDE.md). */
private val IconSize = 44.dp

/** Height of the label band over the bottom of a banner. */
private val CaptionBandHeight = 24.dp

/** The focus scale of the standard style is 1.06; a lifted tile (edit mode) goes a little further, as in the mockup. */
private const val LiftedScale = 1.1f
private const val FocusedScale = 1.06f

/** Selecting an app: the per-app launch motif first, then the actual launch. */
internal fun launchApp(sound: FieldSound, app: LauncherApp, onLaunch: (LauncherApp) -> Unit) {
    sound.launch(app)
    onLaunch(app)
}

/**
 * One app tile, 192x108dp (`FieldDimens.bannerWidth/Height`) with the standard `fieldTile` focus.
 *
 * - Real 16:9 banner: drawn full-bleed, with the label as a mono caption bottom-left on a `bgSoft` band.
 * - Icon only: the icon large (about 44dp) top-left, the label in `bodyStrong` and the category in `caption`
 *   at the bottom (the mockup's `.app`).
 * - No artwork: the category glyph from [FieldIcons.forCategory] at the same size, accent while focused.
 *
 * Selecting (OK, or a click) plays `FieldSound.launch(app)` and then calls [onLaunch]. Grids put their key
 * handling and `FocusRequester` in [modifier]. [lifted] is edit mode: a slightly larger focus scale and a
 * mono "MOVING" label in accent. [onFocusChange] reports focus gain and loss.
 */
@Composable
fun AppTile(
    app: LauncherApp,
    onLaunch: (LauncherApp) -> Unit,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .size(FieldDimens.bannerWidth, FieldDimens.bannerHeight)
            .onFocusChanged { focusState ->
                val now = focusState.isFocused || focusState.hasFocus
                if (now != focused) {
                    focused = now
                    onFocusChange(now)
                }
            }
            .fieldTile(
                onClick = { launchApp(sound, app, onLaunch) },
                focusedScale = if (lifted) LiftedScale else FocusedScale,
            )
            .clip(FieldShapes.tile)
            .background(colors.bgSoft),
    ) {
        val art = app.artwork
        if (art != null && app.artworkIsBanner) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(CaptionBandHeight)
                    .background(colors.bgSoft.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = app.label,
                    style = Field.type.caption,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // 8dp + 44dp icon + 30dp label line + 18dp caption line + 8dp = the 108dp tile.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.size(IconSize),
                    )
                } else {
                    FieldIcon(
                        icon = FieldIcons.forCategory(app.category),
                        size = IconSize,
                        tint = if (focused) colors.accent else colors.muted,
                    )
                }
                Column {
                    Text(
                        text = app.label,
                        style = Field.type.bodyStrong,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.category.name,
                        style = Field.type.caption,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
            }
        }
        if (lifted) MovingLabel(Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The "All apps" cell that ends the Home grid: same size and focus as an app tile, transparent fill
 * (the mockup's `.app.all`), a grid glyph, the name and, when known, how many apps are installed.
 * Selecting plays `FieldSound.select()` and calls [onOpen].
 */
@Composable
fun AllAppsTile(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    installedCount: Int? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .size(FieldDimens.bannerWidth, FieldDimens.bannerHeight)
            .onFocusChanged { focusState ->
                val now = focusState.isFocused || focusState.hasFocus
                if (now != focused) {
                    focused = now
                    onFocusChange(now)
                }
            }
            .fieldTile(onClick = {
                sound.select()
                onOpen()
            })
            .clip(FieldShapes.tile),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            FieldIcon(
                icon = FieldIcons.Grid,
                size = IconSize,
                tint = if (focused) colors.accent else colors.muted,
            )
            Column {
                Text(
                    text = "All apps",
                    style = Field.type.bodyStrong,
                    color = colors.ink,
                    maxLines = 1,
                )
                if (installedCount != null) {
                    Text(
                        text = "$installedCount INSTALLED",
                        style = Field.type.caption,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Edit mode's marker. Accent here is the screen's one emphasis besides the focused tile. */
@Composable
private fun MovingLabel(modifier: Modifier) {
    val colors = Field.colors
    Text(
        text = "MOVING",
        style = Field.type.caption,
        color = colors.accent,
        modifier = modifier
            .padding(6.dp)
            .background(colors.bgSoft, FieldShapes.tile)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
