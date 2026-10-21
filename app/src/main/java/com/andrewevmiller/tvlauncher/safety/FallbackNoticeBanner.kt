package com.andrewevmiller.tvlauncher.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile

/**
 * Hairline-ruled strip telling the owner that the crash-loop fallback handed Home back.
 *
 * Mono "NOTICE" label in muted, the message in body/ink, and an "OK" chip that takes focus as soon as
 * the banner appears (accent shows only on that focused chip). The banner applies the 5% safe-area
 * padding itself, so place it outside the padded home list, for example overlaid at the top of the
 * screen. When [onDismiss] runs, the caller should call `CrashGuard.acknowledge` and move focus back
 * into the home screen.
 */
@Composable
fun FallbackNoticeBanner(
    notice: FallbackNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    val okFocus = remember { FocusRequester() }
    LaunchedEffect(notice) {
        try {
            okFocus.requestFocus()
        } catch (e: IllegalStateException) {
            // Focus target not attached yet. Never let a notice be the reason the launcher crashes.
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = FieldDimens.safeHorizontal, vertical = FieldDimens.safeVertical),
    ) {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("NOTICE", style = Field.type.label, color = colors.muted)
                Spacer(Modifier.height(6.dp))
                Text(notice.message, style = Field.type.body, color = colors.ink)
            }
            Text(
                "OK",
                modifier = Modifier
                    .focusRequester(okFocus)
                    .fieldTile(onClick = onDismiss, shape = FieldShapes.chip)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                color = colors.ink,
                style = Field.type.label,
            )
        }
        Rule()
    }
}

/** The site's `.rule`: a 1dp `line` hairline. */
@Composable
private fun Rule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(FieldDimens.hairline)
            .background(Field.colors.line),
    )
}

@Preview(device = "id:tv_1080p")
@Composable
private fun FallbackNoticeBannerPreview() {
    FieldTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Field.colors.bg),
        ) {
            FallbackNoticeBanner(
                notice = FallbackNotice.forPreviousHome("Wii Menu"),
                onDismiss = {},
            )
        }
    }
}
