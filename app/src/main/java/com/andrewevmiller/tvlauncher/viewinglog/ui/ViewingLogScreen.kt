package com.andrewevmiller.tvlauncher.viewinglog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.andrewevmiller.tvlauncher.settings.SectionHeader
import com.andrewevmiller.tvlauncher.sound.LocalFieldSound
import com.andrewevmiller.tvlauncher.theme.Field
import com.andrewevmiller.tvlauncher.theme.FieldDimens
import com.andrewevmiller.tvlauncher.theme.FieldShapes
import com.andrewevmiller.tvlauncher.theme.dotGrid
import com.andrewevmiller.tvlauncher.theme.fieldFocus
import com.andrewevmiller.tvlauncher.theme.fieldTile
import com.andrewevmiller.tvlauncher.ui.components.rowEdgeKeys
import com.andrewevmiller.tvlauncher.viewinglog.LogRange
import com.andrewevmiller.tvlauncher.viewinglog.ViewingLogText
import com.andrewevmiller.tvlauncher.viewinglog.ViewingSession
import com.andrewevmiller.tvlauncher.viewinglog.ViewingStats
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** How far a row is scaled on focus: full-width rows scale less, so the ring stays inside the safe area. */
private const val ROW_FOCUS_SCALE = 1.02f

/**
 * The Viewing log, a full-screen page opened from the Tools row. Back returns to Home ([onBack]).
 *
 * Top to bottom: the title, `THIS WEEK  14 h 20 m · 11 sessions`, the three range chips, one text bar per day (or per
 * week for a month), the top apps with their time, then the sessions grouped by date. Every chip and row can take
 * focus, so the D-pad scrolls the page. Accent is focus only. When the log is off, or usage access is not granted,
 * the page says so and how to fix it instead of showing nothing.
 *
 * [sessions] is what the store holds; the screen does the range maths. [labelOf] and [categoryOf] come from the
 * installed apps. [today] is the wall clock (tests pin it).
 */
@Composable
fun ViewingLogScreen(
    sessions: List<ViewingSession>,
    enabled: Boolean,
    usageGranted: Boolean,
    labelOf: (String) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialRange: LogRange = LogRange.THIS_WEEK,
    zone: ZoneId = ZoneId.systemDefault(),
    today: () -> LocalDate = { LocalDate.now(zone) },
    dotAlpha: Float = 1f,
) {
    val colors = Field.colors
    val sound = LocalFieldSound.current
    BackHandler {
        sound.back()
        onBack()
    }

    var range by remember { mutableStateOf(initialRange) }
    val day = remember { today() }
    val chipRequesters = remember { List(LogRange.entries.size) { FocusRequester() } }

    // Focus starts on the chip for the range shown, so the D-pad begins at the top of the page.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { chipRequesters[initialRange.ordinal].requestFocus() }
    }

    val days = remember(range, day) { ViewingStats.range(range, day) }
    val inRange = remember(sessions, range, day, zone) { ViewingStats.sessionsIn(sessions, days, zone) }
    val total = remember(sessions, range, day, zone) { ViewingStats.total(sessions, days, zone) }
    val buckets = remember(range, day) { ViewingLogText.buckets(range, day) }
    val bucketTotals = remember(sessions, buckets, zone) { buckets.map { ViewingStats.total(sessions, it.days, zone) } }
    val barMax = bucketTotals.maxOrNull() ?: 0L
    val topApps = remember(sessions, range, day, zone) { ViewingStats.topApps(sessions, days, zone) }
    val groups = remember(inRange, zone) { ViewingLogText.groupByDay(inRange, zone) }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .dotGrid(colors.line.copy(alpha = colors.line.alpha * dotAlpha.coerceIn(0f, 1f))),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = FieldDimens.safeHorizontal,
                vertical = FieldDimens.safeVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("title") { LogTitle() }
            if (!enabled) {
                item("off") {
                    Message(
                        lead = "Viewing log is off.",
                        detail = "Turn it on in Settings, under Viewing log.",
                    )
                }
                return@LazyColumn
            }

            item("summary") {
                LogSummary(heading = ViewingLogText.heading(range), summary = ViewingLogText.summary(total, inRange.size))
            }
            item("chips") {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LogRange.entries.forEachIndexed { index, option ->
                        LogChip(
                            label = ViewingLogText.chipLabel(option),
                            selected = option == range,
                            onClick = {
                                sound.select()
                                range = option
                            },
                            modifier = Modifier
                                .rowEdgeKeys(index, LogRange.entries.size) { sound.edge() }
                                .focusRequester(chipRequesters[index]),
                        )
                    }
                }
            }

            if (!usageGranted) {
                item("grant") {
                    Message(
                        lead = "Usage access is not granted, so nothing new is recorded.",
                        detail = "Grant it once from a computer with adb connected to the TV:",
                        command = ViewingLogText.GRANT_COMMAND,
                    )
                }
            }

            if (inRange.isEmpty()) {
                item("empty") {
                    Message(lead = "No sessions recorded yet.", detail = "Watch something for a minute or more, then come back to this page.")
                }
            } else {
                item("bars-header") { SectionHeader(ViewingLogText.barsLabel(range)) }
                itemsIndexed(buckets, key = { _, b -> "bar-${b.label}" }) { index, bucket ->
                    BarRow(
                        label = bucket.label,
                        value = bucketTotals[index],
                        max = barMax,
                    )
                }

                item("top-header") { SectionHeader("Top apps") }
                items(topApps, key = { "top-${it.packageName}" }) { app ->
                    TopAppRow(
                        name = labelOf(app.packageName),
                        millis = app.millis,
                        percent = if (total > 0) (100.0 * app.millis / total).roundToInt() else 0,
                    )
                }

                item("sessions-header") { SectionHeader("Sessions") }
                groups.forEach { (date, list) ->
                    item("day-$date") {
                        Text(
                            ViewingLogText.dateLabel(date),
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                            style = Field.type.label,
                            color = colors.muted,
                        )
                    }
                    items(list, key = { "s-${it.startMillis}-${it.packageName}" }) { session ->
                        SessionRow(
                            time = ViewingLogText.timeRange(session, zone),
                            name = labelOf(session.packageName),
                            millis = session.durationMillis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTitle() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("Viewing log", style = Field.type.title, color = Field.colors.ink)
        Text("Back to return", style = Field.type.label, color = Field.colors.muted)
    }
}

/** `THIS WEEK` in muted mono, then the summary in ink: the mockup's `.sum` line. */
@Composable
private fun LogSummary(heading: String, summary: String) {
    val colors = Field.colors
    val text: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.muted)) { append(heading) }
        append("  ")
        withStyle(SpanStyle(color = colors.ink)) { append(summary) }
    }
    Text(text, modifier = Modifier.padding(horizontal = 4.dp), style = Field.type.label)
}

/** A pill like the sleep picker's chips: the chosen range in ink, the rest muted. Focus is the only accent. */
@Composable
private fun LogChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Field.colors
    Text(
        label,
        style = Field.type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
        color = if (selected) colors.ink else colors.muted,
        modifier = modifier
            .fieldTile(onClick, FieldShapes.chip)
            .background(colors.bgSoft, FieldShapes.chip)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/** A full-width focusable row on the tile surface. Focus scrolls the page and is the only accent. */
@Composable
private fun LogRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .fieldFocus(focusedScale = ROW_FOCUS_SCALE)
            .focusable()
            .background(Field.colors.bgSoft, FieldShapes.tile)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun BarRow(label: String, value: Long, max: Long) {
    val colors = Field.colors
    val bar = ViewingLogText.textBar(value, max)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.ink)) { append(bar.filled + bar.partial) }
        withStyle(SpanStyle(color = colors.muted)) { append(bar.rest) }
    }
    LogRow {
        Text(label, Modifier.width(96.dp), style = Field.type.label, color = colors.muted, maxLines = 1)
        Text(
            text,
            Modifier.width(380.dp),
            style = Field.type.caption.copy(fontSize = 15.sp, lineHeight = 20.sp),
            maxLines = 1,
        )
        Text(
            if (value > 0) ViewingLogText.duration(value) else "–",
            style = Field.type.label,
            color = if (value > 0) colors.ink else colors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopAppRow(name: String, millis: Long, percent: Int) {
    val colors = Field.colors
    LogRow {
        Text(
            name,
            Modifier.width(300.dp),
            style = Field.type.body,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(ViewingLogText.duration(millis), Modifier.width(140.dp), style = Field.type.label, color = colors.ink, maxLines = 1)
        Text("$percent%", style = Field.type.label, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun SessionRow(time: String, name: String, millis: Long) {
    val colors = Field.colors
    LogRow {
        Text(time, Modifier.width(170.dp), style = Field.type.label, color = colors.muted, maxLines = 1)
        Text(
            name,
            Modifier.width(300.dp),
            style = Field.type.body,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(ViewingLogText.duration(millis), style = Field.type.label, color = colors.ink, maxLines = 1)
    }
}

/** A message with an optional command line. Not focusable: it is text, not a choice. */
@Composable
private fun Message(lead: String, detail: String, command: String? = null) {
    val colors = Field.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(lead, style = Field.type.body, color = colors.ink)
        Spacer(Modifier.height(2.dp))
        Text(detail, style = Field.type.caption, color = colors.muted)
        if (command != null) {
            Spacer(Modifier.height(6.dp))
            Text(command, style = Field.type.caption, color = colors.ink)
        }
    }
}
