package com.andrewevmiller.tvlauncher.home.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One app the user opened from the launcher. [key] is `ComponentName.flattenToString()`.
 * This is the Recent row's source until spike E1 shows whether Watch Next is readable on this TV.
 */
data class LaunchEntry(
    val key: String,
    val label: String,
    val category: AppCategory,
    val atMillis: Long,
)

/** Newest first, one entry per app, capped. Pure so it is unit-tested; persistence is separate. */
object LaunchLog {
    const val MAX_ENTRIES = 12

    fun record(log: List<LaunchEntry>, entry: LaunchEntry, max: Int = MAX_ENTRIES): List<LaunchEntry> =
        (listOf(entry) + log.filter { it.key != entry.key }).take(max)

    /** One entry per line, tab-separated: key, label, category, time. Tabs and line breaks in text become spaces. */
    fun encode(log: List<LaunchEntry>): String = log.joinToString("\n") { e ->
        listOf(e.key, e.label, e.category.name, e.atMillis.toString()).joinToString("\t") { it.flat() }
    }

    /** Forgiving: bad lines are skipped, an unknown category becomes OTHER, duplicates keep the newest. */
    fun decode(raw: String?, max: Int = MAX_ENTRIES): List<LaunchEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = raw.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            val at = f.getOrNull(3)?.toLongOrNull()
            val key = f.getOrNull(0)?.trim().orEmpty()
            if (f.size < 4 || key.isEmpty() || at == null) return@mapNotNull null
            LaunchEntry(
                key = key,
                label = f[1].trim().ifEmpty { key.substringBefore('/') },
                category = AppCategory.entries.firstOrNull { it.name == f[2].trim() } ?: AppCategory.OTHER,
                atMillis = at,
            )
        }.toList()
        return parsed.sortedByDescending { it.atMillis }.distinctBy { it.key }.take(max)
    }

    /** `10-21`, the Recent row's date column. */
    fun dateLabel(atMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MM-dd").withZone(zone).format(Instant.ofEpochMilli(atMillis))

    /** `18:05`, or `6:05 PM` when [is24Hour] is false. */
    fun timeLabel(atMillis: Long, zone: ZoneId, is24Hour: Boolean): String =
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a").withZone(zone).format(Instant.ofEpochMilli(atMillis))

    private fun String.flat(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/** Where launches are recorded. The Android implementation persists; [InMemoryLaunchHistory] is for tests and previews. */
interface LaunchHistory {
    val entries: Flow<List<LaunchEntry>>
    fun record(entry: LaunchEntry)
}

class InMemoryLaunchHistory(initial: List<LaunchEntry> = emptyList()) : LaunchHistory {
    private val state = MutableStateFlow(initial)
    override val entries: Flow<List<LaunchEntry>> = state
    override fun record(entry: LaunchEntry) = state.update { LaunchLog.record(it, entry) }
}
