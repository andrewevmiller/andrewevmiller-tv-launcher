package com.andrewevmiller.tvlauncher.viewinglog

import android.content.Context
import java.io.File

/**
 * The log on the TV: one file in app storage (encoded by [ViewingLogCodec]) and the sync cursor in SharedPreferences.
 * Reads prune past the retention window. Writes go to a temporary file that is then renamed over the log, so a
 * power cut cannot leave half a file behind.
 */
class ViewingLogStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewingLogStorage {
    private val app = context.applicationContext
    private val file = File(app.filesDir, FILE_NAME)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): List<ViewingSession> {
        val raw = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        return ViewingLogCodec.decode(raw, clock())
    }

    @Synchronized
    override fun write(sessions: List<ViewingSession>) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(ViewingLogCodec.encode(sessions))
        if (!tmp.renameTo(file)) {
            // Could not replace in place: keep the old file rather than lose it, and say nothing to the caller.
            tmp.delete()
        }
    }

    override fun cursor(): Long? = if (prefs.contains(CURSOR_KEY)) prefs.getLong(CURSOR_KEY, 0L) else null

    override fun setCursor(millis: Long) {
        prefs.edit().putLong(CURSOR_KEY, millis).apply()
    }

    private companion object {
        const val FILE_NAME = "viewing_log.tsv"
        const val PREFS = "viewing_log"
        const val CURSOR_KEY = "cursor"
    }
}
