package com.andrewevmiller.tvlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.dotGrid
import com.andrewevmiller.tvlauncher.ui.icons.FieldIcons

// Previews for the ui/components set. Each has a dark and (where it matters) a light or reduced-motion
// variant, and a "focused" variant that asks for focus on one item when the preview is interactive
// (Android Studio's Interactive mode; a static render shows the rest state).

@Composable
private fun PreviewFrame(
    dark: Boolean = true,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    FieldTheme(dark = dark, reducedMotion = reducedMotion) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Field.colors.bg)
                .dotGrid(Field.colors.line)
                .padding(horizontal = FieldDimens.safeHorizontal, vertical = FieldDimens.safeVertical),
        ) {
            content()
        }
    }
}

@Composable
private fun rememberFocusOnShow(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Throws if the preview has no window focus yet; the rest state is fine then.
        runCatching { requester.requestFocus() }
    }
    return requester
}

private fun sampleChips(): List<ChipOption<String>> = listOf(
    ChipOption("all", "All"),
    ChipOption("video", "Video"),
    ChipOption("music", "Music"),
    ChipOption("games", "Games"),
    ChipOption("tools", "Tools"),
)

private fun sampleRecent(): List<TimelineItem> = listOf(
    TimelineItem("plex", "10-21", "18:05", FieldIcons.Play, "Plex", "The Expanse · S2E4"),
    TimelineItem("spotify", "10-20", "21:40", FieldIcons.Note, "Spotify", "Late-night focus mix"),
    TimelineItem("moonlight", "10-19", "20:12", FieldIcons.Pad, "Moonlight", "Streamed from desktop"),
    TimelineItem("youtube", "10-18", "09:30", FieldIcons.Wave, "YouTube", "EPR oximetry lecture"),
    TimelineItem("analyzer", "10-17", "22:01", FieldIcons.Points, "Analyzer", "Flow run 0412 review"),
)

private fun samplePhotos(): List<GalleryItem> = listOf(
    GalleryItem("p1", "2026-08-14 · Shenandoah"),
    GalleryItem("p2", "2026-07-02 · Lab bench"),
    GalleryItem("p3", "2026-06-21 · Solstice"),
    GalleryItem("p4", "2026-05-09 · Fly room"),
    GalleryItem("p5", "2026-04-18 · Rain"),
    GalleryItem("p6", "2026-03-30 · Coast"),
)

// ---- C1: chips -------------------------------------------------------------------------------------

@Preview(name = "Chips: rest", widthDp = 960, heightDp = 120)
@Composable
private fun ChipsRowPreview() {
    PreviewFrame {
        var selected by remember { mutableStateOf("video") }
        ChipsRow(options = sampleChips(), selected = selected, onSelect = { selected = it })
    }
}

@Preview(name = "Chips: focused chip", widthDp = 960, heightDp = 120)
@Composable
private fun ChipsFocusedPreview() {
    PreviewFrame {
        val focus = rememberFocusOnShow()
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap)) {
            FieldChip(label = "All", selected = false, onClick = {})
            FieldChip(label = "Video", selected = true, onClick = {})
            FieldChip(label = "Music", selected = false, onClick = {}, modifier = Modifier.focusRequester(focus))
            FieldChip(label = "Games", selected = false, onClick = {})
        }
    }
}

@Preview(name = "Chips: light", widthDp = 960, heightDp = 120)
@Composable
private fun ChipsLightPreview() {
    PreviewFrame(dark = false) {
        ChipsRow(options = sampleChips(), selected = "all", onSelect = {})
    }
}

// ---- C2: feature band ------------------------------------------------------------------------------

@Preview(name = "Band: rest", widthDp = 960, heightDp = 240)
@Composable
private fun FeatureBandPreview() {
    PreviewFrame {
        FeatureBand(
            kicker = "Featured · Plex · resume at 31:08",
            title = "The Expanse, season 2",
            body = "Episode 4 of 13. Picks up where you left off yesterday at 18:05.",
            ctaLabel = "Resume →",
            onCta = {},
            bleed = FieldDimens.safeHorizontal,
        )
    }
}

@Preview(name = "Band: CTA focused", widthDp = 960, heightDp = 240)
@Composable
private fun FeatureBandFocusedPreview() {
    PreviewFrame {
        val focus = rememberFocusOnShow()
        FeatureBand(
            kicker = "Featured · Plex · resume at 31:08",
            title = "The Expanse, season 2",
            body = "Episode 4 of 13. Picks up where you left off yesterday at 18:05.",
            ctaLabel = "Resume →",
            onCta = {},
            ctaModifier = Modifier.focusRequester(focus),
            bleed = FieldDimens.safeHorizontal,
        )
    }
}

@Preview(name = "Band: light, reduced motion", widthDp = 960, heightDp = 240)
@Composable
private fun FeatureBandLightPreview() {
    PreviewFrame(dark = false, reducedMotion = true) {
        val focus = rememberFocusOnShow()
        FeatureBand(
            kicker = "Featured · Plex · resume at 31:08",
            title = "The Expanse, season 2",
            body = "Episode 4 of 13. Picks up where you left off yesterday at 18:05.",
            ctaLabel = "Resume →",
            onCta = {},
            ctaModifier = Modifier.focusRequester(focus),
        )
    }
}

// ---- C3: timeline ----------------------------------------------------------------------------------

@Preview(name = "Timeline entry: rest and focused", widthDp = 960, heightDp = 160)
@Composable
private fun TimelineEntryPreview() {
    PreviewFrame {
        val focus = rememberFocusOnShow()
        val items = sampleRecent()
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap)) {
            TimelineEntry(item = items[0], onClick = {}, modifier = Modifier.focusRequester(focus))
            TimelineEntry(item = items[1], onClick = {})
            TimelineEntry(item = items[2], onClick = {})
        }
    }
}

@Preview(name = "Recent row: collapsed", widthDp = 960, heightDp = 120)
@Composable
private fun RecentRowCollapsedPreview() {
    PreviewFrame {
        RecentRow(items = sampleRecent(), expanded = false, onToggle = {}, onOpen = {})
    }
}

@Preview(name = "Recent row: expanded (interactive)", widthDp = 960, heightDp = 200)
@Composable
private fun RecentRowExpandedPreview() {
    PreviewFrame {
        var expanded by remember { mutableStateOf(true) }
        Column {
            RecentRow(
                items = sampleRecent(),
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onOpen = {},
            )
        }
    }
}

// ---- C4: gallery -----------------------------------------------------------------------------------

@Preview(name = "Gallery row: tilted prints", widthDp = 960, heightDp = 200)
@Composable
private fun GalleryRowPreview() {
    PreviewFrame {
        GalleryRow(items = samplePhotos(), onSelect = {})
    }
}

@Preview(name = "Gallery tile: focused shows caption", widthDp = 960, heightDp = 200)
@Composable
private fun GalleryTileFocusedPreview() {
    PreviewFrame {
        val focus = rememberFocusOnShow()
        val photos = samplePhotos()
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FieldDimens.tileGap)) {
            GalleryTile(item = photos[0], index = 0, onSelect = {})
            GalleryTile(item = photos[1], index = 1, onSelect = {}, modifier = Modifier.focusRequester(focus))
            GalleryTile(item = photos[2], index = 2, onSelect = {})
        }
    }
}
