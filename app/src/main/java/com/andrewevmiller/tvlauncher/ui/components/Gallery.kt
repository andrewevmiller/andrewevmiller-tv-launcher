package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.fieldTile

/**
 * One print in the photo row. [caption] is the mono line shown while the tile is focused ("2026-08-14 ·
 * Shenandoah"). With no [image] the tile draws a placeholder plate from the theme's neutral tokens.
 */
data class GalleryItem(
    val key: String,
    val caption: String,
    val image: ImageBitmap? = null,
)

/** Five tiles and four gaps are exactly the 864dp safe content width. */
private val GalleryTileWidth = 160.dp
private val GalleryTileHeight = 112.dp

/** The mat around the picture, like the white border of a print. */
private val GalleryMat = 6.dp
private val GalleryPlateShape = RoundedCornerShape(2.dp)
private val GalleryCaptionShape = RoundedCornerShape(2.dp)

/** Opacity of the caption's backing plate (the mockup's rgba(23,23,22,.72), here `bg` at 72%). */
private const val GalleryCaptionBacking = 0.72f

/**
 * A print-like tile (the mockup's `.photo`): a `bgSoft` mat with a hairline border, the picture inset in it,
 * leaning [tiltFor]([index]) degrees at rest. `fieldTile` straightens it and scales it up on focus; the mono
 * caption fades in only while focused (it snaps when reduced motion is on), except on a tile with no
 * picture, whose caption is always shown. OK plays `FieldSound.select()`
 * (unless [playSelect] is false, e.g. when the handler plays the sleep sound) and calls [onSelect].
 */
@Composable
fun GalleryTile(
    item: GalleryItem,
    index: Int,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    playSelect: Boolean = true,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    val reduced = Field.reducedMotion
    var focused by remember { mutableStateOf(false) }
    val captionAlpha by animateFloatAsState(
        // A tile with no picture has nothing else to say what it is, so its caption is always visible.
        targetValue = if (focused || item.image == null) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(durationMillis = 160),
        label = "galleryCaption",
    )
    Box(
        modifier
            .size(GalleryTileWidth, GalleryTileHeight)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .fieldTile(
                onClick = {
                    if (playSelect) sound.select()
                    onSelect()
                },
                restTilt = tiltFor(index),
            )
            .background(colors.bgSoft, FieldShapes.tile)
            .padding(GalleryMat),
    ) {
        val image = item.image
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(GalleryPlateShape)
                    .border(FieldDimens.hairline, colors.line, GalleryPlateShape),
            )
        } else {
            // Placeholder plate: a diagonal wash between bgSoft, panel and line, mirrored on odd tiles.
            val stops = if ((index and 1) == 0) {
                listOf(colors.line, colors.panel, colors.bgSoft)
            } else {
                listOf(colors.bgSoft, colors.panel, colors.line)
            }
            val angle = plateAngle(index)
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(GalleryPlateShape)
                    .drawBehind {
                        val g = gradientLine(size.width, size.height, angle)
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = stops,
                                start = Offset(g.x0, g.y0),
                                end = Offset(g.x1, g.y1),
                            ),
                        )
                    }
                    .border(FieldDimens.hairline, colors.line, GalleryPlateShape),
            )
        }
        Text(
            text = item.caption,
            style = Field.type.caption,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .graphicsLayer { alpha = captionAlpha }
                .background(colors.bg.copy(alpha = GalleryCaptionBacking), GalleryCaptionShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The Photos row: [GalleryTile]s in a horizontal lazy row, tilts alternating by position. LEFT/RIGHT at
 * the ends of the row play `FieldSound.edge()`. [onSelect] gets the chosen item (the caller starts the
 * slideshow); [playSelect] is forwarded to every tile.
 */
@Composable
fun GalleryRow(
    items: List<GalleryItem>,
    onSelect: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    playSelect: Boolean = true,
) {
    val sound = LocalFieldSound.current
    LazyRow(
        modifier = modifier.focusBleed(),
        horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap),
        contentPadding = PaddingValues(FocusBleed),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            GalleryTile(
                item = item,
                index = index,
                onSelect = { onSelect(item) },
                modifier = Modifier.rowEdgeKeys(index, items.size) { sound.edge() },
                playSelect = playSelect,
            )
        }
    }
}
