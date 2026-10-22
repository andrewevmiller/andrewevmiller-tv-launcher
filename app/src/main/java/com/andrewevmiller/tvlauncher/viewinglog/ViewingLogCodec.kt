package com.andrewevmiller.tvlauncher.viewinglog

/**
 * The log file's format: one session per line, tab-separated: package, start millis, end millis. The same style as
 * `LaunchLog` in home/data. Tabs and line breaks inside a package name become spaces.
 *
 * Decoding is forgiving: a bad line is skipped, not an error. Sessions older than [ViewingLogRules.RETENTION_MILLIS]
 * are pruned, and an app's session that repeats (same package and start) is kept once.
 */
object ViewingLogCodec {

    fun encode(sessions: List<ViewingSession>): String = sessions.joinToString("\n") { s ->
        listOf(s.packageName, s.startMillis.toString(), s.endMillis.toString()).joinToString("\t") { it.flat() }
    }

    fun decode(raw: String?, nowMillis: Long): List<ViewingSession> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = raw.lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3) return@mapNotNull null
            val pkg = fields[0].trim()
            val start = fields[1].trim().toLongOrNull()
            val end = fields[2].trim().toLongOrNull()
            if (pkg.isEmpty() || start == null || end == null || end <= start) return@mapNotNull null
            ViewingSession(pkg, start, end)
        }.toList()
        return normalise(parsed, nowMillis)
    }

    /** The stored sessions plus [added], pruned to the retention window, oldest first, repeats removed. */
    fun merge(existing: List<ViewingSession>, added: List<ViewingSession>, nowMillis: Long): List<ViewingSession> =
        normalise(existing + added, nowMillis)

    /** Pruning past the retention window: a session that starts before the cutoff goes. */
    fun prune(sessions: List<ViewingSession>, nowMillis: Long): List<ViewingSession> {
        val cutoff = nowMillis - ViewingLogRules.RETENTION_MILLIS
        return sessions.filter { it.startMillis >= cutoff }
    }

    private fun normalise(sessions: List<ViewingSession>, nowMillis: Long): List<ViewingSession> =
        prune(sessions, nowMillis)
            .distinctBy { it.packageName to it.startMillis }
            .sortedBy { it.startMillis }

    private fun String.flat(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
